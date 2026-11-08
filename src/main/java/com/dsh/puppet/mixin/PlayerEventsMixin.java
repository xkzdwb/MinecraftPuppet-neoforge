package com.dsh.puppet.mixin;

import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 把受伤 / 死亡转给 {@link PuppetRuntime}，用于自动还手和事件流。 */
@Mixin(ServerPlayer.class)
public class PlayerEventsMixin {

    @Inject(method = "hurtServer", at = @At("TAIL"))
    private void puppet$onHurt(net.minecraft.server.level.ServerLevel level, DamageSource source, float amount,
                               CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()) {
            return;
        }
        try {
            PuppetRuntime.of((ServerPlayer) (Object) this).onDamaged(source, amount);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "die", at = @At("HEAD"))
    private void puppet$onDeath(DamageSource source, CallbackInfo ci) {
        try {
            PuppetRuntime.of((ServerPlayer) (Object) this).onDeath();
        } catch (Throwable ignored) {
        }
    }
}
