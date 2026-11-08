package com.dsh.puppet.entity;

/**
 * 服务端权威的朝向。
 *
 * <p>原版 {@code ServerPlayer#getYRot()} 在服务端并不是“玩家正在看的方向”（朝向由客户端上报），
 * 人偶自己要走、要挖、要打，就必须自己记一份 yaw/pitch，并让渲染与实体同步读这一份。
 */
public interface PuppetRotation {
    float puppet$serverYaw();

    float puppet$serverPitch();

    void puppet$setServerRotation(float yaw, float pitch);
}
