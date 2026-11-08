package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetActions;
import com.dsh.puppet.entity.PuppetRuntime;
import com.dsh.puppet.entity.path.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 采集任务：找目标 → 走过去 → 挖 → 收掉落物 → 循环。
 *
 * 支持的目标：
 * <ul>
 *   <li>原矿：coal / iron / diamond …（自动同时匹配深层变种，如 deepslate_iron_ore）</li>
 *   <li>木头：任意 log / 指定 log（oak_log …）；只砍树干</li>
 *   <li>农作物：成熟才收，收完补种</li>
 * </ul>
 */
public class GatherTask extends BaseTask {
    public enum Kind {MINE, CHOP, HARVEST, CUSTOM}

    private final Kind kind;
    private final String arg;
    private final int count;
    private final int radius;
    private final Set<Block> wanted = new HashSet<>();
    private final Predicate<BlockState> matcher;

    private int collected;
    private BlockPos current;
    private int mineTicks;
    private int searchCooldown;
    private int noPathTicks;
    private final Set<BlockPos> blacklist = new HashSet<>();
    private Vec3 lastPos;
    private int stuckTicks;
    private int pickupTicks;

    public GatherTask(PuppetRuntime rt, Kind kind, String arg, int count) {
        super(rt);
        this.kind = kind;
        this.arg = arg == null ? "" : arg.toLowerCase(Locale.ROOT).trim();
        this.count = count <= 0 ? 8 : count;
        this.radius = 48;

        List<Block> blocks = new ArrayList<>();
        switch (kind) {
            case CHOP -> {
                if (this.arg.isEmpty() || any(this.arg, "any", "log", "wood", "tree", "trees", "树", "木材", "木头")) {
                    findTag(BlockTags.LOGS, blocks);
                } else {
                    addById(this.arg, blocks);
                    if (blocks.isEmpty()) {
                        addById(this.arg + "_log", blocks);
                    }
                }
            }
            case MINE -> {
                String a = this.arg.isEmpty() ? "iron" : this.arg;
                if (any(a, "any", "ore", "ores", "矿", "矿石")) {
                    findOres(blocks);
                } else {
                    String ore = a.endsWith("_ore") ? a : a + "_ore";
                    addById(ore, blocks);
                    addById("deepslate_" + sanitize(ore), blocks);
                    if (a.startsWith("raw_")) {
                        addById(a, blocks);
                    }
                    if (any(a, "debris", "ancient_debris", "远古残骸")) {
                        addById("ancient_debris", blocks);
                    }
                    if (any(a, "quartz", "石英")) {
                        addById("nether_quartz_ore", blocks);
                    }
                }
            }
            case HARVEST -> {
                if (this.arg.isEmpty() || any(this.arg, "any", "crop", "crops", "菜", "农作物")) {
                    findTag(BlockTags.CROPS, blocks);
                } else {
                    addById(this.arg, blocks);
                }
            }
            default -> addById(this.arg, blocks);
        }
        wanted.addAll(blocks);
        this.matcher = state -> !state.isAir()
                && wanted.contains(state.getBlock())
                && (kind != Kind.HARVEST || PuppetActions.isMatureCrop(state));
    }

    private static boolean any(String a, String... words) {
        for (String w : words) {
            if (a.equals(w)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitize(String s) {
        return s.replace("minecraft:", "").trim();
    }

    private void addById(String raw, List<Block> out) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String id = raw.contains(":") ? raw : "minecraft:" + raw;
        Identifier rl;
        try {
            rl = Identifier.parse(id);
        } catch (Exception e) {
            return;
        }
        if (BuiltInRegistries.BLOCK.containsKey(rl)) {
            out.add(BuiltInRegistries.BLOCK.getValue(rl));
        }
    }

    private void findTag(TagKey<Block> tag, List<Block> out) {
        var lookup = rt.player.level().registryAccess().lookupOrThrow(Registries.BLOCK);
        lookup.get(tag).ifPresent(named -> {
            for (Holder<Block> h : named) {
                out.add(h.value());
            }
        });
    }

    private void findOres(List<Block> out) {
        for (Block b : BuiltInRegistries.BLOCK) {
            if (PuppetActions.isOre(b.defaultBlockState())) {
                out.add(b);
            }
        }
    }

    @Override
    public String name() {
        return switch (kind) {
            case MINE -> "mine_" + (arg.isEmpty() ? "ore" : arg);
            case CHOP -> "chop";
            case HARVEST -> "harvest";
            case CUSTOM -> "gather_" + arg;
        };
    }

    @Override
    public void start() {
        if (wanted.isEmpty()) {
            finish("没有可采集的目标方块：" + arg);
        }
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        if (collected >= count) {
            finish("采集完成：" + collected + "/" + count);
            return;
        }
        // 危险：守护优先
        Enemy danger = nearestHostile(rt.config.dangerScanRadius);
        if (danger != null) {
            rt.say("有威胁，先打它：" + ((net.minecraft.world.entity.Entity) danger).getName().getString());
            rt.setTask(new GuardTask(rt, null, rt.config.dangerScanRadius));
            return;
        }
        // 捡掉落物
        if (pickupTicks > 0) {
            pickupTicks--;
            ItemEntity item = nearestItem(6.0);
            if (item != null) {
                PuppetActions.driveToward(rt, item.position(), 0.6, false);
                return;
            }
            pickupTicks = 0;
        }
        if (current != null) {
            BlockState state = rt.player.level().getBlockState(current);
            if (state.isAir()) {
                onDoneCurrent();
                return;
            }
            if (!matcher.test(state)) {
                current = null;
                return;
            }
            if (!mineTarget(current)) {
                blacklist.add(current);
                current = null;
            }
            return;
        }
        if (searchCooldown-- > 0) {
            return;
        }
        searchCooldown = 10;
        BlockPos found = findTarget();
        if (found == null) {
            if (collected > 0) {
                finish("附近没有更多目标了，已获得 " + collected + " 个");
            } else {
                finish("附近（" + radius + " 格内）找不到目标");
            }
            return;
        }
        current = found;
        mineTicks = 0;
        noPathTicks = 0;
        PuppetActions.stopMoving(rt);
    }

    private void onDoneCurrent() {
        if (current != null) {
            collected++;
            rt.say("完成 " + collected + "/" + count);
            if (kind == Kind.HARVEST) {
                replant(current);
            }
        }
        current = null;
        pickupTicks = 12;
        searchCooldown = 4;
        mineTicks = 0;
    }

    private void replant(BlockPos pos) {
        var inv = rt.player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && (s.is(ItemTags.VILLAGER_PLANTABLE_SEEDS) || s.getItem() instanceof BlockItem)) {
                int prev = inv.getSelectedSlot();
                inv.setSelectedSlot(i);
                PuppetActions.interactBlock(rt, pos.below());
                inv.setSelectedSlot(prev);
                return;
            }
        }
    }

    /**
     * 挖掘当前目标：走过去 → 转身 → 推进破坏进度。
     * 返回 false 表示放弃当前目标。
     */
    private boolean mineTarget(BlockPos pos) {
        ServerLevel world = rt.player.level();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        double dist = Math.sqrt(rt.player.distanceToSqr(center));
        if (dist > 2.8) {
            if (PuppetActions.hasLineOfSight(rt, pos) && dist < rt.config.mineReach) {
                return PuppetActions.mineTick(rt, pos);
            }
            BlockPos stand = Pathfinder.findNearestStandable(world, pos, 3);
            if (stand != null) {
                PuppetActions.driveToward(rt,
                        new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5), 0.9, true);
            } else {
                PuppetActions.driveToward(rt, center, 2.0, true);
            }
            if (++noPathTicks > 20 * 12) {
                rt.say("够不到这个方块，换一个");
                return false;
            }
            stuckCheck();
            return true;
        }
        mineTicks++;
        boolean gone = PuppetActions.mineTick(rt, pos);
        if (gone) {
            if (kind == Kind.CHOP) {
                chopAbove(pos);
            }
            return false;
        }
        if (mineTicks > 20 * 60) {
            rt.say("挖太久了，放弃这个方块");
            return false;
        }
        return true;
    }

    /** 树干被砍掉后，把上方连着的一段也砍掉（露天的树）。 */
    private void chopAbove(BlockPos pos) {
        ServerLevel world = rt.player.level();
        BlockPos up = pos.above();
        int safety = 0;
        while (safety++ < 8) {
            BlockState s = world.getBlockState(up);
            if (s.isAir() || !wanted.contains(s.getBlock())) {
                break;
            }
            PuppetActions.mineTick(rt, up);
            up = up.above();
        }
    }

    private void stuckCheck() {
        Vec3 p = rt.player.position();
        if (lastPos != null && p.distanceToSqr(lastPos) < 0.0025) {
            stuckTicks++;
            if (stuckTicks > 20 && rt.player.onGround()) {
                PuppetActions.jump(rt.player);
            }
        } else {
            stuckTicks = 0;
        }
        lastPos = p;
    }

    // ---------------------------------------------------------------- 搜索

    private BlockPos findTarget() {
        ServerLevel world = rt.player.level();
        BlockPos origin = rt.player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int yMin = Math.max(world.getMinY() + 1, origin.getY() - (kind == Kind.MINE ? 24 : 10));
        int yMax = Math.min(origin.getY() + (kind == Kind.MINE ? 8 : 24), world.getMaxY());
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-radius, yMin - origin.getY(), -radius),
                origin.offset(radius, yMax - origin.getY(), radius))) {
            if (blacklist.contains(p)) {
                continue;
            }
            BlockState s = world.getBlockState(p);
            if (!matcher.test(s)) {
                continue;
            }
            if (!world.mayInteract(rt.player, p)) {
                continue;
            }
            double d = Math.sqrt(rt.player.distanceToSqr(Vec3.atCenterOf(p)));
            double score = d - p.getY() * 0.15;
            if (score < bestScore) {
                bestScore = score;
                best = p.immutable();
            }
        }
        return best;
    }

    private ItemEntity nearestItem(double r) {
        AABB box = rt.player.getBoundingBox().inflate(r);
        List<ItemEntity> items = rt.player.level().getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && !e.getItem().isEmpty());
        items.sort(Comparator.comparingDouble(e -> rt.player.distanceToSqr(e)));
        return items.isEmpty() ? null : items.get(0);
    }

    private Enemy nearestHostile(double r) {
        Entity e = rt.nearbyEntities(r).stream()
                .filter(en -> en instanceof Enemy)
                .min(Comparator.comparingDouble(en -> rt.player.distanceTo(en)))
                .orElse(null);
        return e instanceof Enemy h ? h : null;
    }

    // ---------------------------------------------------------------- 事件

    @Override
    public void onPickup(ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            rt.say("收到 " + stack.getHoverName().getString() + " x" + stack.getCount());
        }
    }

    @Override
    public void onBlockBroken(BlockPos pos, BlockState state) {
        if (current != null && pos.equals(current)) {
            onDoneCurrent();
        }
    }

    public int collected() {
        return collected;
    }

    public boolean hasTargets() {
        return !wanted.isEmpty();
    }
}
