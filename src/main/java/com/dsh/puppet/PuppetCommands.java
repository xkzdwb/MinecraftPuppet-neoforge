package com.dsh.puppet;

import com.dsh.puppet.entity.ExternalBridge;
import com.dsh.puppet.entity.PuppetRuntime;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 服务端命令。
 * <ul>
 *   <li>{@code /puppet <任意文本>} —— 让“执行命令的玩家”的人偶去做，普通玩家即可用（不需要 OP）</li>
 *   <li>{@code /puppetctl ...}   —— 管理功能（需要 OP）：开关、面板、外部桥接</li>
 * </ul>
 */
public final class PuppetCommands {
    private PuppetCommands() {
    }

    /** 游戏总线：注册命令。 */
    public static final class GameEvents {
        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            register(event.getDispatcher());
        }
    }

    /** 兼容旧调用点（PuppetMod 里注册的是上面那个类）。 */
    public static final class ModEvents {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("puppet")
                .then(Commands.literal("help").executes(PuppetCommands::help))
                .then(Commands.literal("status").executes(c -> run(c, "status")))
                .then(Commands.literal("stop").executes(c -> run(c, "stop")))
                .then(Commands.literal("follow").executes(c -> run(c, "follow")))
                .then(Commands.literal("guard").executes(c -> run(c, "guard")))
                .then(Commands.literal("farm").executes(c -> run(c, "farm 1")))
                .then(Commands.literal("pickup").executes(c -> run(c, "pickup")))
                .then(Commands.literal("gui").executes(PuppetCommands::gui))
                .then(Commands.literal("chatty").executes(c -> run(c, "chatty")))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> run(c, StringArgumentType.getString(c, "text")))));

        dispatcher.register(Commands.literal("puppetctl")
                .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("arm").executes(c -> arm(c, true)))
                .then(Commands.literal("disarm").executes(c -> arm(c, false)))
                .then(Commands.literal("gui").executes(PuppetCommands::gui))
                .then(Commands.literal("bridge").executes(PuppetCommands::bridge))
                .then(Commands.literal("bridge-on").executes(c -> bridgeSet(c, true)))
                .then(Commands.literal("bridge-off").executes(c -> bridgeSet(c, false))));
    }

    // ---------------------------------------------------------------- 执行

    private static int run(CommandContext<CommandSourceStack> ctx, String text) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("这条指令需要玩家身份（人偶是玩家自己）"));
            return 0;
        }
        PuppetRuntime rt = PuppetRuntime.of(player);
        PuppetRuntime.Result r = rt.submit(text, PuppetRuntime.Source.COMMAND);
        rt.result(r.ok(), r.message());
        ctx.getSource().sendSuccess(() -> Component.literal((r.ok() ? "§a" : "§c") + "[人偶] " + r.message()), false);
        return r.ok() ? 1 : 0;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(com.dsh.puppet.entity.Commands.HELP), false);
        return 1;
    }

    private static int gui(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        PuppetMod.GUI_CLIENTS.add(player.getUUID());
        JsonObject o = new JsonObject();
        o.addProperty("key", "gui");
        o.addProperty("value", "show");
        com.dsh.puppet.network.Downlink.send(player, Protocol.P_PUSH, o);
        ctx.getSource().sendSuccess(() -> Component.literal("§a[人偶] 已在客户端打开面板（按 K 开关）"), false);
        return 1;
    }

    private static int arm(CommandContext<CommandSourceStack> ctx, boolean on) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        PuppetRuntime rt = PuppetRuntime.of(player);
        rt.setArmed(on);
        if (on) {
            PuppetMod.EXTERNAL_CLIENTS.add(player.getUUID());
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a[人偶] 接管 = " + on), false);
        return 1;
    }

    private static int bridge(CommandContext<CommandSourceStack> ctx) {
        ExternalBridge bridge = ExternalBridge.get(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal(bridge.status()), false);
        return 1;
    }

    private static int bridgeSet(CommandContext<CommandSourceStack> ctx, boolean on) {
        ExternalBridge bridge = ExternalBridge.get(ctx.getSource().getServer());
        bridge.setEnabled(on);
        ctx.getSource().sendSuccess(() -> Component.literal(bridge.status()), false);
        return 1;
    }
}
