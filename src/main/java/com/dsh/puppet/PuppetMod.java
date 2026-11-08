package com.dsh.puppet;

import com.dsh.puppet.entity.ExternalBridge;
import com.dsh.puppet.entity.PuppetRuntime;
import com.dsh.puppet.network.PuppetMessages;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模组入口（服务端逻辑）。
 *
 * 设计要点：
 * <ul>
 *   <li>被操控的对象就是“玩家自己”的 {@link ServerPlayer}，不是新生成的假人实体。
 *       物品栏、经验、护甲、附魔、掉血、死亡、存档全都照常，和真人操控是同一个对象。</li>
 *   <li>不模拟键盘/鼠标：服务端直接改写玩家的移动输入与朝向，
 *       并直接调用原版的挖掘/交互/攻击入口。</li>
 *   <li>观测与事件通过自定义 payload 下行，客户端 HUD 与 Python 都从同一条流里取数据。</li>
 * </ul>
 */
@Mod(Protocol.MOD_ID)
public class PuppetMod {
    public static final Logger LOG = LoggerFactory.getLogger(Protocol.MOD_ID);

    /** 每 2 tick 推一次快照（≈10Hz）。 */
    public static final int SNAPSHOT_INTERVAL = 2;

    /** 打开了 HUD 面板或 Python 驱动的客户端。 */
    public static final Set<UUID> GUI_CLIENTS = ConcurrentHashMap.newKeySet();
    /** 允许 Python / LLM 驱动的客户端。 */
    public static final Set<UUID> EXTERNAL_CLIENTS = ConcurrentHashMap.newKeySet();

    private static int tickCounter;

    public PuppetMod(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.register(this);
        modBus.register(com.dsh.puppet.network.PuppetNetwork.class);
        NeoForge.EVENT_BUS.register(com.dsh.puppet.PuppetCommands.GameEvents.class);
        LOG.info("[puppet] loaded (NeoForge) — 输入 /puppet help 查看用法");
    }

    // ================================================================ 事件

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ExternalBridge.get(event.getServer()).start();
        LOG.info("[puppet] Python 桥接：/puppetctl bridge 查看状态");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        ExternalBridge.get(event.getServer()).stop();
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        GUI_CLIENTS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        tickCounter++;
        ExternalBridge bridge = ExternalBridge.get(server);
        boolean pushExternal = bridge.hasClients();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PuppetRuntime rt;
            try {
                rt = PuppetRuntime.of(player);
            } catch (Throwable t) {
                LOG.error("[puppet] runtime missing for {}", player.getName().getString(), t);
                continue;
            }
            rt.tick();

            if (tickCounter % SNAPSHOT_INTERVAL != 0) {
                continue;
            }
            String line = PuppetMessages.encode(Protocol.P_SNAPSHOT, rt.snapshot());
            if (pushExternal) {
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("type", "snapshot");
                for (var e : rt.snapshot().entrySet()) {
                    wrapper.add(e.getKey(), e.getValue());
                }
                bridge.broadcast(Protocol.line(wrapper));
            }
            if (GUI_CLIENTS.contains(player.getUUID())) {
                com.dsh.puppet.network.Downlink.sendRaw(player, line);
            }
        }
    }

    // ================================================================ 客户端消息

    /** 客户端 payload 统一入口（由 PuppetNetwork 在主线程调用）。 */
    public static void dispatchClientMessage(ServerPlayer player, String line) {
        if (player == null || line == null) {
            return;
        }
        JsonObject json = Protocol.tryParse(line);
        if (json == null || !json.has("type")) {
            return;
        }
        String type = json.get("type").getAsString();
        PuppetRuntime rt = PuppetRuntime.of(player);
        if (rt == null) {
            return;
        }
        switch (type) {
            case Protocol.P_HELLO -> {
                GUI_CLIENTS.add(player.getUUID());
                rt.event("handshake", "客户端已连接");
            }
            case Protocol.P_WANTED -> {
                String mode = json.has("mode") ? json.get("mode").getAsString() : "on";
                if ("off".equalsIgnoreCase(mode)) {
                    GUI_CLIENTS.remove(player.getUUID());
                } else {
                    GUI_CLIENTS.add(player.getUUID());
                }
            }
            case Protocol.P_ARM -> rt.setArmed(json.has("on") && json.get("on").getAsBoolean());
            case Protocol.P_COMMAND -> {
                String text = json.has("text") ? json.get("text").getAsString() : "";
                PuppetRuntime.Result r = rt.submit(text, PuppetRuntime.Source.GAME);
                rt.result(r.ok(), r.message());
                if (!r.ok()) {
                    player.sendSystemMessage(Component.literal("§c[人偶] " + r.message()));
                }
            }
            default -> rt.event("warn", "未知消息类型 " + type);
        }
    }
}
