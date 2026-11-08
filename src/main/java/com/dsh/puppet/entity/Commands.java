package com.dsh.puppet.entity;

import com.dsh.puppet.entity.task.ChopLoopTask;
import com.dsh.puppet.entity.task.FollowTask;
import com.dsh.puppet.entity.task.GatherTask;
import com.dsh.puppet.entity.task.GotoTask;
import com.dsh.puppet.entity.task.GuardTask;
import com.dsh.puppet.entity.task.KillTask;
import com.dsh.puppet.entity.task.ManualTask;
import com.dsh.puppet.entity.task.PickupTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 指令解析 —— 接 LLM 的核心层。
 *
 * <p>同时支持“结构化指令”和“自然语言”，中英文混用：
 * <pre>
 *   去 100 64 -30        goto 100 64 -30        过来
 *   挖 10 个钻石         mine 16 iron           挖矿
 *   砍 5 棵树            chop 3 oak_log         砍树 / 来点木头
 *   跟着我               follow Steve
 *   保护这个村民         guard                  原地警戒
 *   打僵尸               kill creeper
 *   收菜                 harvest
 *   捡东西               pickup
 *   种树 4               farm 4
 *   停 / stop / 别动了
 *   状态 / status
 *   手动 1 0 90 0 jump   input ...              逐帧手动操控（RL 用）
 * </pre>
 */
public final class Commands {
    private static final Pattern COORD3 = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)[\\s,，]+(-?\\d+(?:\\.\\d+)?)[\\s,，]+(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern NUM = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    public static final String HELP = """
            可用指令（中英文都可以）：
              去 <x> <y> <z>          goto <x> <y> <z>          前往坐标（A* 寻路）
              挖 <数量> <矿种>        mine 10 diamond           挖矿
                矿种：coal/iron/gold/copper/redstone/lapis/emerald/diamond/debris
              砍 <数量> <树种>        chop 5 oak_log            砍树（默认任意树）
              收 <数量>               harvest                   收成熟作物并补种
              捡                      pickup                    捡掉落物
              跟着 [名字]             follow [name]             跟随玩家 / 村民
              保护 [名字]             guard / protect           守护某人，或原地警戒
              打 <怪物> <数量>        kill zombie 3             主动战斗
              种树 <数量>             farm 4                    种树循环
              停                      stop                      停止所有动作
              状态                    status                    汇报状态
              手动 <前> <横> <yaw> <pitch> [jump] [sprint]     逐帧手动操控（RL 用）
            """;

    private Commands() {
    }

    // ================================================================ 入口

    public static PuppetRuntime.Result execute(PuppetRuntime rt, String raw, PuppetRuntime.Source source) {
        String text = raw.trim().replaceFirst("^/?puppet\\s+", "").trim();
        if (text.isEmpty()) {
            return PuppetRuntime.Result.fail("指令为空");
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String head = lower.split("\\s+")[0];
        String rest = text.length() > head.length() ? text.substring(head.length()).trim() : "";

        // ---------- 控制 ----------
        if (any(head, "help", "帮助", "?", "？")) {
            rt.say(HELP);
            return PuppetRuntime.Result.ok(HELP);
        }
        if (any(head, "stop", "halt", "停", "停止", "别动", "别动了", "取消", "停下", "暂停", "pause")) {
            rt.stop("手动停止");
            return PuppetRuntime.Result.ok("已停止");
        }
        if (any(head, "status", "状态", "info", "报告")) {
            String s = String.format(Locale.ROOT,
                    "位置 (%.1f, %.1f, %.1f)  任务=%s  血量=%.1f/%.1f  饥饿=%d",
                    rt.player.getX(), rt.player.getY(), rt.player.getZ(), rt.taskName(),
                    rt.player.getHealth(), rt.player.getMaxHealth(),
                    rt.player.getFoodData().getFoodLevel());
            rt.say(s);
            return PuppetRuntime.Result.ok(s);
        }
        if (any(head, "chatty", "啰嗦", "播报")) {
            rt.setChatty(rt.isChatty() ? PuppetRuntime.Chatty.QUIET : PuppetRuntime.Chatty.NORMAL);
            return PuppetRuntime.Result.ok("播报：" + (rt.isChatty() ? "开" : "关"));
        }
        if (any(head, "input", "手动", "手动输入", "manual")) {
            if (!(rt.task() instanceof ManualTask)) {
                rt.setTask(new ManualTask(rt));
            }
            return manualInput(rt, text);
        }

        // ---------- 移动 ----------
        if (any(head, "goto", "go", "walk", "tp", "move", "去", "走到", "前往", "过去", "到", "去往", "移动")) {
            BlockPos pos = coords(text);
            if (pos != null) {
                rt.setTask(new GotoTask(rt, Vec3.atBottomCenterOf(pos), 1.0, true));
                return PuppetRuntime.Result.ok("前往 " + posStr(pos));
            }
            return followEntity(rt, rest, true);
        }

        // ---------- 采集 ----------
        if (any(head, "mine", "dig", "挖", "挖矿", "开采", "挖块", "采集", "resource")) {
            return gather(rt, GatherTask.Kind.MINE, rest, text, 8);
        }
        if (any(head, "chop", "wood", "lumber", "砍", "砍树", "砍木头", "木材", "木头", "log")) {
            return gather(rt, GatherTask.Kind.CHOP, rest, text, 4);
        }
        if (any(head, "harvest", "crop", "收", "收菜", "收割", "收获", "农", "种地")) {
            return gather(rt, GatherTask.Kind.HARVEST, rest, text, 16);
        }
        if (any(head, "farm", "plant", "种", "种树") || (lower.contains("树") && lower.contains("种"))) {
            rt.setTask(new ChopLoopTask(rt, count(text, 1), 12));
            return PuppetRuntime.Result.ok("开始种树循环");
        }
        if (any(head, "pickup", "collect", "loot", "捡", "捡东西", "拾取", "拾")) {
            rt.setTask(new PickupTask(rt));
            return PuppetRuntime.Result.ok("去捡掉落物");
        }

        // ---------- 跟随 / 战斗 ----------
        if (any(head, "follow", "跟", "跟着", "跟随", "跟我", "来", "过来")) {
            return followEntity(rt, rest, true);
        }
        if (any(head, "guard", "protect", "保护", "守护", "守卫", "警戒", "站岗")) {
            return followEntity(rt, rest, false);
        }
        if (any(head, "kill", "attack", "fight", "打", "杀", "攻击", "干掉", "揍", "fighting")) {
            return kill(rt, rest, text);
        }
        if (any(head, "eat", "吃", "吃饭", "吃东西")) {
            return eat(rt);
        }

        // ---------- 整句自然语言兜底 ----------
        return fuzzy(rt, lower, text);
    }

    // ================================================================ 各子命令

    private static PuppetRuntime.Result gather(PuppetRuntime rt, GatherTask.Kind kind,
                                               String rest, String whole, int defCount) {
        int n = count(whole, defCount);
        String what = autoGuessMaterial(kind, whole);
        rt.setTask(new GatherTask(rt, kind, what, n));
        return PuppetRuntime.Result.ok(kind.name().toLowerCase(Locale.ROOT) + " " + n
                + (what.isEmpty() ? "" : " " + what));
    }

    private static PuppetRuntime.Result kill(PuppetRuntime rt, String rest, String whole) {
        String target = rest.replaceAll("\\d+\\s*(?:只|个|条|次)?", "").trim();
        if (target.isEmpty()) {
            target = autoGuessMob(whole);
        }
        if (target.isEmpty()) {
            target = "mob";
        }
        rt.setTask(new KillTask(rt, mobId(target), count(whole, 1)));
        return PuppetRuntime.Result.ok("去对付 " + target);
    }

    private static PuppetRuntime.Result followEntity(PuppetRuntime rt, String rest, boolean follow) {
        String name = rest.trim();
        if (name.isEmpty() || name.equals("我") || name.equalsIgnoreCase("me")) {
            if (!follow) {
                rt.setTask(new GuardTask(rt, null, 12.0));
                return PuppetRuntime.Result.ok("原地警戒");
            }
            Entity owner = nearestOtherPlayer(rt);
            if (owner == null) {
                rt.setTask(new GuardTask(rt, null, 8.0));
                return PuppetRuntime.Result.ok("附近没有别的玩家，就地待命");
            }
            rt.setTask(new GuardTask(rt, owner, 3.5));
            return PuppetRuntime.Result.ok("跟随 " + owner.getName().getString());
        }
        Entity e = findByName(rt, name);
        if (e == null) {
            return PuppetRuntime.Result.fail("附近找不到 " + name);
        }
        if (follow) {
            rt.setTask(new FollowTask(rt, name, 3.0));
        } else {
            rt.setTask(new GuardTask(rt, e, 6.0));
        }
        return PuppetRuntime.Result.ok((follow ? "跟随 " : "保护 ") + e.getName().getString());
    }

    private static PuppetRuntime.Result eat(PuppetRuntime rt) {
        return eatFromInventory(rt);
    }

    /** 吃东西：直接把食物喂给饥饿管理器，再扣掉一个物品。 */
    public static PuppetRuntime.Result eatFromInventory(PuppetRuntime rt) {
        var inv = rt.player.getInventory();
        for (int i = 0; i < 9; i++) {
            var s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            var props = s.get(net.minecraft.core.component.DataComponents.FOOD);
            if (props == null) {
                continue;
            }
            rt.player.getFoodData().eat(props);
            s.shrink(1);
            return PuppetRuntime.Result.ok("吃了点东西");
        }
        return PuppetRuntime.Result.fail("背包里没有吃的");
    }

    private static PuppetRuntime.Result manualInput(PuppetRuntime rt, String text) {
        if (!(rt.task() instanceof ManualTask manual)) {
            return PuppetRuntime.Result.fail("当前不在手动模式");
        }
        Matcher m = NUM.matcher(text);
        List<Double> nums = new ArrayList<>();
        while (m.find()) {
            nums.add(Double.parseDouble(m.group()));
        }
        if (nums.size() < 2) {
            return PuppetRuntime.Result.fail("用法：手动 <forward> <strafe> <yaw> <pitch> [jump] [sprint]");
        }
        float fwd = nums.get(0).floatValue();
        float strafe = nums.get(1).floatValue();
        float yaw = nums.size() > 2 ? nums.get(2).floatValue() : rt.yaw();
        float pitch = nums.size() > 3 ? nums.get(3).floatValue() : rt.pitch();
        boolean jump = text.contains("jump") || (nums.size() > 4 && nums.get(4) > 0.5);
        boolean sprint = text.contains("sprint") || (nums.size() > 5 && nums.get(5) > 0.5);
        boolean sneak = text.contains("sneak") || (nums.size() > 6 && nums.get(6) > 0.5);
        manual.setInput(fwd, strafe, yaw, pitch, jump, sprint, sneak);
        return PuppetRuntime.Result.ok("input applied");
    }

    /** 整句自然语言：关键词猜测意图。 */
    private static PuppetRuntime.Result fuzzy(PuppetRuntime rt, String lower, String original) {
        if (contains(lower, "停止", "停下", "别动", "stop")) {
            rt.stop("手动停止");
            return PuppetRuntime.Result.ok("已停止");
        }
        if (contains(lower, "钻石", "diamond")) {
            return gather(rt, GatherTask.Kind.MINE, "", original, 8);
        }
        if (contains(lower, "铁", "iron")) {
            return gather(rt, GatherTask.Kind.MINE, "", original, 16);
        }
        if (contains(lower, "金", "gold")) {
            return gather(rt, GatherTask.Kind.MINE, "", original, 8);
        }
        if (contains(lower, "煤", "coal", "石头", "cobble")) {
            return gather(rt, GatherTask.Kind.MINE, "", original, 16);
        }
        if (contains(lower, "木头", "原木", "树", "log", "wood", "tree")) {
            if (contains(lower, "种")) {
                rt.setTask(new ChopLoopTask(rt, count(original, 2), 12));
                return PuppetRuntime.Result.ok("开始种树循环");
            }
            return gather(rt, GatherTask.Kind.CHOP, "", original, 4);
        }
        if (contains(lower, "保护", "守护", "guard", "protect")) {
            return followEntity(rt, "", false);
        }
        if (contains(lower, "跟随", "跟着", "跟我", "follow", "来")) {
            return followEntity(rt, "", true);
        }
        if (contains(lower, "打", "杀", "kill", "attack", "fight", "敌人", "怪物")) {
            String mob = autoGuessMob(lower);
            rt.setTask(new KillTask(rt, mob.isEmpty() ? "mob" : mob, count(original, 1)));
            return PuppetRuntime.Result.ok("去收拾" + (mob.isEmpty() ? "附近的敌人" : mob));
        }
        if (contains(lower, "收", "harvest", "菜", "农作物")) {
            return gather(rt, GatherTask.Kind.HARVEST, "", original, 16);
        }
        if (contains(lower, "哪里", "在哪", "位置", "坐标", "status", "where")) {
            return execute(rt, "status", PuppetRuntime.Source.GAME);
        }
        BlockPos pos = coords(original);
        if (pos != null) {
            rt.setTask(new GotoTask(rt, Vec3.atBottomCenterOf(pos), 1.0, true));
            return PuppetRuntime.Result.ok("前往 " + posStr(pos));
        }
        return PuppetRuntime.Result.fail("没听懂这条指令：" + original + "\n" + HELP);
    }

    // ================================================================ 文本解析

    private static BlockPos coords(String text) {
        Matcher m = COORD3.matcher(text);
        if (!m.find()) {
            return null;
        }
        return BlockPos.containing(Double.parseDouble(m.group(1)),
                Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)));
    }

    private static int count(String text, int def) {
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            try {
                int v = (int) Double.parseDouble(m.group());
                if (v > 0 && v < 10000) {
                    return v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static String autoGuessMaterial(GatherTask.Kind kind, String lower) {
        if (kind == GatherTask.Kind.HARVEST) {
            return "";
        }
        if (kind == GatherTask.Kind.CHOP) {
            for (String wood : new String[]{"oak", "birch", "spruce", "jungle", "acacia",
                    "dark_oak", "mangrove", "cherry", "pale_oak"}) {
                if (lower.contains(wood)) {
                    return wood + "_log";
                }
            }
            return "";
        }
        for (String ore : new String[]{"diamond", "iron", "gold", "copper", "coal", "redstone",
                "lapis", "emerald", "debris", "quartz"}) {
            if (lower.contains(ore)) {
                return ore;
            }
        }
        if (lower.contains("钻石") || lower.contains("钻")) {
            return "diamond";
        }
        if (lower.contains("铁")) {
            return "iron";
        }
        if (lower.contains("金")) {
            return "gold";
        }
        if (lower.contains("煤") || lower.contains("石")) {
            return "coal";
        }
        return "";
    }

    private static String autoGuessMob(String lower) {
        Set<String> hits = new LinkedHashSet<>();
        if (contains(lower, "僵尸", "zombie")) {
            hits.add("zombie");
        }
        if (contains(lower, "骷髅", "小白", "skeleton")) {
            hits.add("skeleton");
        }
        if (contains(lower, "苦力怕", "爬行者", "creeper")) {
            hits.add("creeper");
        }
        if (contains(lower, "蜘蛛", "spider")) {
            hits.add("spider");
        }
        if (contains(lower, "末影", "小黑", "enderman")) {
            hits.add("enderman");
        }
        if (contains(lower, "史莱姆", "slime")) {
            hits.add("slime");
        }
        if (contains(lower, "女巫", "witch")) {
            hits.add("witch");
        }
        if (contains(lower, "猪人", "piglin")) {
            hits.add("zombified_piglin");
        }
        if (contains(lower, "怪", "敌人", "enemy", "mob")) {
            hits.add("mob");
        }
        return hits.isEmpty() ? "" : hits.iterator().next();
    }

    private static String mobId(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim();
        String cn = autoGuessMob(s);
        if (!cn.isEmpty() && !s.contains(":")) {
            return cn;
        }
        String singular = s.replace("minecraft:", "");
        if (singular.endsWith("s")) {
            String guess = "minecraft:" + singular.substring(0, singular.length() - 1);
            Identifier rl;
            try {
                rl = Identifier.parse(guess);
            } catch (Exception e) {
                return singular;
            }
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
                return singular.substring(0, singular.length() - 1);
            }
        }
        return singular;
    }

    private static Entity findByName(PuppetRuntime rt, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        List<Entity> all = new ArrayList<>(rt.nearbyEntities(64.0));
        var server = rt.player.level().getServer();
        if (server != null) {
            all.addAll(server.getPlayerList().getPlayers());
        }
        for (Entity e : all) {
            if (e != rt.player && e.getName().getString().toLowerCase(Locale.ROOT).equals(n)) {
                return e;
            }
        }
        for (Entity e : all) {
            if (e != rt.player && e.getName().getString().toLowerCase(Locale.ROOT).contains(n)) {
                return e;
            }
        }
        String idGuess = autoGuessMob(n);
        if (idGuess.isEmpty()) {
            idGuess = n.replace(' ', '_');
        }
        for (Entity e : all) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (id.equals(idGuess) || id.contains(n)) {
                return e;
            }
        }
        return null;
    }

    private static String posStr(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /** 除了自己以外最近的玩家。 */
    private static Entity nearestOtherPlayer(PuppetRuntime rt) {
        var server = rt.player.level().getServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayers().stream()
                .filter(p -> p != rt.player)
                .min(java.util.Comparator.comparingDouble(rt.player::distanceTo))
                .orElse(null);
    }

    private static boolean any(String head, String... words) {
        for (String w : words) {
            if (head.equals(w.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String lower, String... words) {
        for (String w : words) {
            if (lower.contains(w.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 给外部做“一句话能不能识别”的快速判断。 */
    public static boolean looksLikeCommand(String text) {
        if (text == null) {
            return false;
        }
        String h = text.trim().toLowerCase(Locale.ROOT).split("\\s+")[0];
        String[] known = {"goto", "go", "walk", "去", "走到", "前往", "mine", "dig", "挖", "砍", "chop",
                "收", "harvest", "捡", "pickup", "跟", "跟着", "follow", "guard", "保护", "守护",
                "kill", "attack", "打", "杀", "farm", "种", "stop", "停", "status", "状态", "help", "帮助",
                "input", "手动"};
        return any(h, known);
    }
}
