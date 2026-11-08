package com.dsh.puppet.entity;

import net.minecraft.server.level.ServerPlayer;

/**
 * 挂在 {@link ServerPlayer} 上的运行时状态。
 * 用 mixin 把接口注入到玩家实体本身，而不是新生成实体。
 */
public interface PuppetPlayer {
    PuppetRuntime puppet$runtime();

    PuppetRuntime puppet$runtime(ServerPlayer self);
}
