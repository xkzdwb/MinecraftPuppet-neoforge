package com.dsh.puppet.mixin;

import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拾取事件：服务端玩家碰到掉落物时回调，用于任务计数与事件流。
 * （1.21.4 之后 {@code ServerPlayer#onPickupItem} 已不存在，这里挂在 ItemEntity 上。）
 */
@Mixin(ItemEntity.class)
public class ItemPickupMixin {

    @Inject(method = "playerTouch", at = @At("TAIL"))
    private void puppet$onPlayerTouch(net.minecraft.world.entity.player.Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        ItemEntity self = (ItemEntity) (Object) this;
        try {
            PuppetRuntime.of(sp).onPickup(self.getItem());
        } catch (Throwable ignored) {
        }
    }
}
