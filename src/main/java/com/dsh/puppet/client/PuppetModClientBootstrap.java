package com.dsh.puppet.client;

import com.dsh.puppet.Protocol;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端事件注册入口。
 *
 * <p>NeoForge 26.2 里去掉了 {@code @EventBusSubscriber(bus = ...)} 的 Bus 枚举，
 * 所以这里用 {@code @Mod(dist = Dist.CLIENT)} 显式拿两条总线来注册。
 */
@OnlyIn(Dist.CLIENT)
@Mod(value = Protocol.MOD_ID, dist = Dist.CLIENT)
public final class PuppetModClientBootstrap {
    public PuppetModClientBootstrap(IEventBus modBus) {
        modBus.register(PuppetModClient.ModEvents.class);
        NeoForge.EVENT_BUS.register(PuppetModClient.GameEvents.class);
    }
}
