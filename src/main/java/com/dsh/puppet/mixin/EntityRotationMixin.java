package com.dsh.puppet.mixin;

import com.dsh.puppet.entity.PuppetRotation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 保存服务端权威朝向。
 *
 * <p>玩家实体在服务端的 yaw/pitch 由客户端上报，人偶自己要走要挖要打，
 * 必须有一份“我想朝哪看”的记录。这里不改原版行为，只是旁路保存一份。
 */
@Mixin(Entity.class)
public class EntityRotationMixin implements PuppetRotation {
    @Unique
    private float puppet$yaw;
    @Unique
    private float puppet$pitch;

    @Override
    public float puppet$serverYaw() {
        return puppet$yaw;
    }

    @Override
    public float puppet$serverPitch() {
        return puppet$pitch;
    }

    @Override
    public void puppet$setServerRotation(float yaw, float pitch) {
        this.puppet$yaw = yaw;
        this.puppet$pitch = pitch;
    }

    /** 任何原版 rotate/setYRot 调用也同步一份，避免和服务端其它代码打架。 */
    @Inject(method = "setYRot", at = @At("TAIL"))
    private void puppet$onSetYRot(float yaw, CallbackInfo ci) {
        this.puppet$yaw = yaw;
    }

    @Inject(method = "setXRot", at = @At("TAIL"))
    private void puppet$onSetXRot(float pitch, CallbackInfo ci) {
        this.puppet$pitch = pitch;
    }
}
