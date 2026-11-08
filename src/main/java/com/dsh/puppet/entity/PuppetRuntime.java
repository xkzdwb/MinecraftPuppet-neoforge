package com.dsh.puppet.entity;

import com.dsh.puppet.Protocol;
import com.dsh.puppet.entity.task.GatherTask;
import com.dsh.puppet.entity.task.GuardTask;
import com.dsh.puppet.entity.task.Task;
import com.dsh.puppet.network.Downlink;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 某一个玩家实体的“人偶”运行时：接收指令、跑任务、注入输入、生成观测。
 * 每个 {@link ServerPlayer} 恰好一份，用弱引用挂载，玩家退出后自动回收。
 */
public final class PuppetRuntime {
    private static final Logger LOG = LoggerFactory.getLogger("puppet/runtime");

    public enum Source {GAME, COMMAND, EXTERNAL}

    public enum Chatty {QUIET, NORMAL, LOUD}

    public record Result(boolean ok, String message) {
        public static Result ok(String msg) {
            return new Result(true, msg);
        }

        public static Result fail(String msg) {
            return new Result(false, msg);
        }
    }

    public final SimulatedInput input = new SimulatedInput();
    public final PuppetConfig config = new PuppetConfig();
    public final ServerPlayer player;

    private Task task;
    private String taskName = "idle";
    private boolean armed;
    private Chatty chatty = Chatty.NORMAL;
    private long taskStarted;
    private long tick;

    private Entity lastAttacker;
    private long lastAttackerTick = Long.MIN_VALUE / 2;
    private DamageSource lastDamageSource;

    private final List<String> recentEvents = new ArrayList<>();
    private final List<Consumer<PuppetRuntime>> commandQueue = new ArrayList<>();

    private PuppetRuntime(ServerPlayer player) {
        this.player = player;
        this.taskStarted = player.level().getGameTime();
    }

    public static PuppetRuntime of(ServerPlayer player) {
        return ((PuppetPlayer) player).puppet$runtime(player);
    }

    public static PuppetRuntime create(ServerPlayer player) {
        return new PuppetRuntime(player);
    }

    // ================================================================ 生命周期

    public void tick() {
        tick++;
        if (player.isRemoved() || !player.isAlive()) {
            return;
        }
        if (tick % 20 == 0) {
            processQueue();
        }
        autoEat();
        Task current = task;
        if (current == null) {
            input.endTick();
            return;
        }
        input.reset();
        try {
            current.tick();
        } catch (Throwable t) {
            LOG.error("[puppet] task '{}' crashed", taskName, t);
            finish("执行出错：" + t.getClass().getSimpleName());
            return;
        }
        if (current.isRunning() && (now() - taskStarted) > config.maxTaskTicks) {
            finish("任务超时");
            return;
        }
        if (input.hasRotation) {
            ((PuppetRotation) player).puppet$setServerRotation(input.yaw, input.pitch);
        }
        InputInjector.apply(this);
        input.endTick();
        if (task == current && !current.isRunning()) {
            String why = current.reason() == null ? "任务完成" : current.reason();
            finish(why);
        }
    }

    public boolean isArmed() {
        return armed;
    }

    public void setArmed(boolean on) {
        this.armed = on;
    }

    public boolean isChatty() {
        return chatty != Chatty.QUIET;
    }

    public void setChatty(Chatty c) {
        this.chatty = c;
    }

    public Result submit(String raw, Source source) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Result.fail("指令为空");
        }
        armed = true;
        try {
            return Commands.execute(this, text, source);
        } catch (Throwable t) {
            LOG.error("[puppet] parse '{}' failed", text, t);
            return Result.fail("指令解析失败：" + t.getMessage());
        }
    }

    /** 外部线程投递指令，下一 tick 主线程处理。 */
    public void enqueueCommand(String text) {
        synchronized (commandQueue) {
            commandQueue.add(rt -> submit(text, Source.EXTERNAL));
        }
    }

    private void processQueue() {
        List<Consumer<PuppetRuntime>> pending;
        synchronized (commandQueue) {
            if (commandQueue.isEmpty()) {
                return;
            }
            pending = new ArrayList<>(commandQueue);
            commandQueue.clear();
        }
        for (Consumer<PuppetRuntime> c : pending) {
            c.accept(this);
        }
    }

    // ================================================================ 任务

    public void setTask(Task t) {
        Task old = task;
        task = null;
        if (old != null) {
            old.stop("被新任务打断");
        }
        task = t;
        taskName = t.name();
        taskStarted = now();
        input.reset();
        event("task", "开始：" + taskName);
        t.start();
    }

    public void stop(String why) {
        Task old = task;
        task = null;
        if (old != null) {
            old.stop(why);
        }
        taskName = "idle";
        PuppetActions.releaseInputs(this);
        InputInjector.release(this);
        event("stop", why == null ? "已停止" : why);
    }

    private void finish(String why) {
        Task old = task;
        task = null;
        if (old != null) {
            old.stop(why);
        }
        taskName = "idle";
        PuppetActions.releaseInputs(this);
        InputInjector.release(this);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e[人偶] " + why), true);
        event("finish", why);
    }

    public void say(String msg) {
        if (!isChatty() || msg == null) {
            return;
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§b[人偶] §r" + msg), true);
    }

    public String taskName() {
        return taskName;
    }

    public Task task() {
        return task;
    }

    public long taskElapsed() {
        return now() - taskStarted;
    }

    public long now() {
        return player.level().getGameTime();
    }

    public long tickCount() {
        return tick;
    }

    // ================================================================ 事件回调

    public void onDamaged(DamageSource source, float amount) {
        lastDamageSource = source;
        Entity a = source.getEntity();
        if (a != null) {
            lastAttacker = a;
            lastAttackerTick = tick;
        }
        event("damaged", "受到 " + Math.round(amount) + " 点伤害");
        if (task instanceof GuardTask g && a instanceof LivingEntity le) {
            g.revenge(le);
        }
        if (task == null && armed && a instanceof Enemy) {
            setTask(new GuardTask(this, null, 12.0));
        }
    }

    public void onDeath() {
        event("death", "玩家死亡");
        stop("死亡");
    }

    public void onPickup(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        event("pickup", "拾取 " + stack.getHoverName().getString() + " x" + stack.getCount());
        Task t = task;
        if (t instanceof GatherTask g) {
            g.onPickup(stack);
        }
    }

    public void onBlockBroken(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        Task t = task;
        if (t instanceof GatherTask g) {
            g.onBlockBroken(pos, state);
        }
    }

    public Entity lastAttacker() {
        if (lastAttacker != null && tick - lastAttackerTick < 20 * 15 && lastAttacker.isAlive()) {
            return lastAttacker;
        }
        return null;
    }

    public DamageSource lastDamageSource() {
        return lastDamageSource;
    }

    // ================================================================ 自助行为

    private void autoEat() {
        if (task == null) {
            return;
        }
        if (!player.canEat(false)) {
            return;
        }
        FoodData food = player.getFoodData();
        if (food.getFoodLevel() > 14 && player.getHealth() > player.getMaxHealth() * 0.6f) {
            return;
        }
        int slot = findFoodSlot();
        if (slot < 0) {
            return;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        var props = stack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (props == null) {
            return;
        }
        player.getFoodData().eat(props);
        stack.shrink(1);
        event("eat", "吃了 " + stack.getHoverName().getString());
    }

    private int findFoodSlot() {
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.has(net.minecraft.core.component.DataComponents.FOOD)) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================ 朝向 / 观察

    public void face(Vec3 target) {
        Vec3 eye = eyes();
        float yaw = lookYaw(eye, target);
        float pitch = lookPitch(eye, target);
        input.face(yaw, pitch);
        ((PuppetRotation) player).puppet$setServerRotation(
                Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0f, 90.0f));
    }

    /** 立即（本 tick）转向某点：任务里挖/打之前调用。 */
    public void lookAt(Vec3 target) {
        face(target);
    }

    public float yaw() {
        return ((PuppetRotation) player).puppet$serverYaw();
    }

    public float pitch() {
        return ((PuppetRotation) player).puppet$serverPitch();
    }

    public Vec3 eyes() {
        return new Vec3(player.getX(), player.getEyeY(), player.getZ());
    }

    public ItemStack mainHand() {
        return player.getMainHandItem();
    }

    public boolean hasItem(String id) {
        int want = 1;
        String itemId = id == null ? "" : id;
        int plus = itemId.lastIndexOf('+');
        if (plus > 0) {
            try {
                want = Integer.parseInt(itemId.substring(plus + 1).trim());
                itemId = itemId.substring(0, plus).trim();
            } catch (NumberFormatException ignored) {
            }
        }
        var inv = player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(itemId)) {
                count += s.getCount();
                if (count >= want) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Entity> nearbyEntities(double r) {
        ServerLevel world = player.level();
        AABB box = player.getBoundingBox().inflate(r);
        return world.getEntities(player, box, e -> e.isAlive() && !e.isRemoved());
    }

    /** 正前方（视线方向约 ±55°）最近的实体，供手动模式的“攻击键”用。 */
    public Entity nearestEntityInFront(double r) {
        Vec3 look = Vec3.directionFromRotation(pitch(), yaw()).normalize();
        Vec3 eye = eyes();
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : nearbyEntities(r)) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double len = to.length();
            if (len < 0.01) {
                continue;
            }
            double dot = look.dot(to.scale(1.0 / len));
            if (dot < 0.55) {
                continue;
            }
            double score = len - dot * 1.5;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    public static boolean isHostile(LivingEntity le) {
        return le instanceof Enemy;
    }

    public boolean selectedSword() {
        return player.getMainHandItem().is(ItemTags.SWORDS)
                || player.getMainHandItem().is(ItemTags.AXES);
    }

    public void selectBestWeapon() {
        var inv = player.getInventory();
        int best = -1;
        float bestDmg = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            float d = 1.0f;
            if (s.is(ItemTags.SWORDS)) {
                d = 2.0f;
            } else if (s.is(ItemTags.AXES)) {
                d = 1.5f;
            }
            if (d > bestDmg) {
                bestDmg = d;
                best = i;
            }
        }
        if (best >= 0) {
            inv.setSelectedSlot(best);
        }
    }

    public void selectBestTool(net.minecraft.world.level.block.state.BlockState state) {
        var inv = player.getInventory();
        int best = inv.getSelectedSlot();
        float bestSpeed = inv.getItem(best).isEmpty() ? 1.0f : inv.getItem(best).getDestroySpeed(state);
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best != inv.getSelectedSlot()) {
            inv.setSelectedSlot(best);
        }
    }

    // ================================================================ 上行

    public void sendSnapshot() {
        Downlink.send(player, Protocol.P_SNAPSHOT, snapshot());
    }

    public JsonObject snapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("player", player.getName().getString());
        o.addProperty("armed", armed);
        o.addProperty("task", taskName);
        o.addProperty("task_time", taskElapsed());
        o.addProperty("dim", player.level().dimension().identifier().toString());
        o.addProperty("game_time", now());
        o.add("pos", vec(player.getX(), player.getY(), player.getZ()));
        o.add("vel", vec(player.getDeltaMovement().x, player.getDeltaMovement().y, player.getDeltaMovement().z));
        o.addProperty("yaw", round(yaw()));
        o.addProperty("pitch", round(pitch()));
        o.addProperty("on_ground", player.onGround());
        o.addProperty("sneaking", player.isShiftKeyDown());
        o.addProperty("sprinting", player.isSprinting());
        o.addProperty("health", round(player.getHealth()));
        o.addProperty("max_health", round(player.getMaxHealth()));
        o.addProperty("food", player.getFoodData().getFoodLevel());
        o.addProperty("xp", player.experienceLevel);
        o.add("held", stackJson(player.getMainHandItem()));
        o.addProperty("air", player.getAirSupply());
        o.addProperty("is_op", player.createCommandSourceStack().permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER));
        Entity attacker = lastAttacker();
        if (attacker != null) {
            o.addProperty("attacker", attacker.getName().getString());
            o.addProperty("attacker_dist", round(player.distanceTo(attacker)));
        }
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) {
            hotbar.add(stackJson(player.getInventory().getItem(i)));
        }
        o.add("hotbar", hotbar);
        o.addProperty("selected", player.getInventory().getSelectedSlot());
        JsonArray nearby = new JsonArray();
        for (Entity e : nearbyEntities(8.0)) {
            JsonObject je = new JsonObject();
            je.addProperty("id", e.getId());
            je.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            je.addProperty("name", e.getName().getString());
            je.add("pos", vec(e.getX(), e.getY(), e.getZ()));
            je.addProperty("dist", round(player.distanceTo(e)));
            if (e instanceof LivingEntity le) {
                je.addProperty("health", round(le.getHealth()));
                je.addProperty("hostile", isHostile(le));
            }
            nearby.add(je);
        }
        o.add("nearby", nearby);
        JsonArray events = new JsonArray();
        synchronized (recentEvents) {
            for (String s : recentEvents) {
                events.add(s);
            }
        }
        o.add("events", events);
        return o;
    }

    public void event(String kind, String text) {
        synchronized (recentEvents) {
            recentEvents.add("[" + kind + "] " + text);
            while (recentEvents.size() > 10) {
                recentEvents.remove(0);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("text", text);
        o.addProperty("task", taskName);
        o.addProperty("player", player.getName().getString());
        Downlink.send(player, Protocol.P_EVENT, o);
        ExternalBridge bridge = ExternalBridge.get(player.level().getServer());
        if (bridge.hasClients()) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "event");
            for (var e : o.entrySet()) {
                wrapper.add(e.getKey(), e.getValue());
            }
            bridge.push(Protocol.line(wrapper));
        }
    }

    public void result(boolean ok, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("text", text);
        Downlink.send(player, Protocol.P_RESULT, o);
    }

    // ================================================================ 静态工具

    public static JsonArray vec(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(round(x));
        a.add(round(y));
        a.add(round(z));
        return a;
    }

    public static double round(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }

    public static JsonElement stackJson(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        o.addProperty("count", s.getCount());
        o.addProperty("name", s.getHoverName().getString());
        if (s.isDamageableItem()) {
            o.addProperty("damage", s.getDamageValue());
            o.addProperty("durability", s.getMaxDamage() - s.getDamageValue());
        }
        return o;
    }

    public static float lookYaw(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
    }

    public static float lookPitch(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return (float) (-(Mth.atan2(dy, horiz) * 180.0 / Math.PI));
    }

    public String pretty() {
        return String.format(Locale.ROOT, "%s at (%.1f, %.1f, %.1f) task=%s",
                player.getName().getString(), player.getX(), player.getY(), player.getZ(), taskName);
    }

    @Override
    public String toString() {
        return "PuppetRuntime{" + pretty() + "}";
    }
}
