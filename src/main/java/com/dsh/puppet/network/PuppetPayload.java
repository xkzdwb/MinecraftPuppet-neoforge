package com.dsh.puppet.network;

import com.dsh.puppet.Protocol;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 一条“一行 JSON”的自定义数据包。客户端与服务端共用一个 payload 类型，
 * 靠 JSON 里的 {@code type} 字段区分消息种类。
 */
public record PuppetPayload(String data) implements CustomPacketPayload {

    public static final Type<PuppetPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Protocol.MOD_ID, "msg"));

    public static final StreamCodec<ByteBuf, PuppetPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, PuppetPayload::data,
                    PuppetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
