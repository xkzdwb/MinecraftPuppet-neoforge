package com.dsh.puppet.mixin;

import com.dsh.puppet.entity.PuppetPlayer;
import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给每个玩家实体挂一份 {@link PuppetRuntime}（弱引用，玩家退出后自动回收）。
 * 这里不会再生成任何实体 —— 人偶就是玩家本人。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements PuppetPlayer {
    @Unique
    private transient java.lang.ref.WeakReference<PuppetRuntime> puppet$runtimeRef;

    @Override
    public PuppetRuntime puppet$runtime(ServerPlayer self) {
        PuppetRuntime rt = puppet$runtimeRef == null ? null : puppet$runtimeRef.get();
        if (rt == null) {
            rt = PuppetRuntime.create(self);
            puppet$runtimeRef = new java.lang.ref.WeakReference<>(rt);
        }
        return rt;
    }
}
