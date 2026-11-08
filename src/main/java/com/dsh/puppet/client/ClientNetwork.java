package com.dsh.puppet.client;

import com.dsh.puppet.network.PuppetPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.minecraft.client.Minecraft;

/** 客户端 -> 服务端 发送。 */
public final class ClientNetwork {
    private ClientNetwork() {
    }

    public static void send(String line) {
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new PuppetPayload(line));
    }
}
