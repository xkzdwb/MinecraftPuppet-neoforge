package com.dsh.puppet.network;

import com.dsh.puppet.Protocol;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端 -> 客户端 的下行通道。
 *
 * <p>NeoForge 会对自定义 payload 做握手：客户端没装模组时不能给它发。
 * 所以这里先确认连接仍然可用，再发送；异常一律吞掉，避免影响任务 tick。
 */
public final class Downlink {
    private Downlink() {
    }

    public static void send(ServerPlayer player, String type, JsonObject body) {
        if (player == null) {
            return;
        }
        String line;
        try {
            line = PuppetMessages.encode(type, body);
        } catch (Exception e) {
            return;
        }
        sendRaw(player, line);
    }

    public static void sendRaw(ServerPlayer player, String line) {
        if (player == null || player.connection == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayer(player, new PuppetPayload(line));
        } catch (Throwable t) {
            com.dsh.puppet.PuppetMod.LOG.debug("downlink failed: {}", t.toString());
        }
    }

    public static void event(ServerPlayer player, String kind, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("text", text);
        o.addProperty("time", System.currentTimeMillis());
        send(player, Protocol.P_EVENT, o);
    }
}
