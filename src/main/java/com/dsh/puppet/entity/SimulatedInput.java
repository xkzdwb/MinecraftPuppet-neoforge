package com.dsh.puppet.entity;

import net.minecraft.util.Mth;

/** 一 tick 的“输入”：全部由程序填写，完全绕过键盘/鼠标。 */
public class SimulatedInput {
    public float forward;
    public float strafe;
    public boolean jumping;
    public boolean sprinting;
    public boolean sneaking;
    public boolean attacking;
    public boolean using;
    public boolean mining;
    public boolean dropping;
    /** 本 tick 是否需要朝向（由任务设置，客户端会跟随写入玩家实体朝向）。 */
    public boolean hasRotation;
    public float yaw;
    public float pitch;
    /** 上一 tick 的朝向，用于生成增量包（避免服务端实体被来回甩头）。 */
    public float lastYaw;
    public float lastPitch;
    public boolean rotationInitialized;

    public void reset() {
        forward = 0.0f;
        strafe = 0.0f;
        jumping = false;
        sprinting = false;
        sneaking = false;
        attacking = false;
        using = false;
        mining = false;
        dropping = false;
        hasRotation = false;
    }

    public boolean moving() {
        return forward != 0.0f || strafe != 0.0f;
    }

    public void face(float yaw, float pitch) {
        this.yaw = Mth.wrapDegrees(yaw);
        this.pitch = Mth.clamp(pitch, -90.0f, 90.0f);
        this.hasRotation = true;
    }

    /** 一 tick 结束后调用，保存朝向用于增量同步。 */
    public void endTick() {
        if (hasRotation) {
            lastYaw = yaw;
            lastPitch = pitch;
            rotationInitialized = true;
        }
    }
}
