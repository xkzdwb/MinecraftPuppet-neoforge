package com.dsh.puppet.network;

import com.dsh.puppet.PuppetMod;
import com.dsh.puppet.Protocol;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * NeoForge 网络注册。
 *
 * <p>用 {@code optional()} 注册：客户端没装模组时不会因为握手中断连接，
 * 只是收不到下行快照（服务端的人偶照样干活）。
 */
public final class PuppetNetwork {
    private PuppetNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Protocol.PROTOCOL_VERSION).optional();

        // 客户端 -> 服务端
        registrar.playToServer(PuppetPayload.TYPE, PuppetPayload.CODEC, PuppetNetwork::onClientMessage);

        // 服务端 -> 客户端（可选：客户端没装模组就静默丢弃）
        registrar.playToClient(PuppetPayload.TYPE, PuppetPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> ClientReceiver.onDownlink(payload.data())));
    }

    private static void onClientMessage(PuppetPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            PuppetMod.dispatchClientMessage(player, payload.data());
        });
    }

    /** 客户端侧接收（单独成类，避免专用服务器误加载客户端类）。 */
    private static final class ClientReceiver {
        static void onDownlink(String line) {
            try {
                com.dsh.puppet.client.ClientController.onPacket(line);
            } catch (Throwable ignored) {
                // 客户端没装 / 出错时静默
            }
        }
    }
}
