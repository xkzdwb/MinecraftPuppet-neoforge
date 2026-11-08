package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetRuntime;

/**
 * 手动模式：外部（Python / RL）直接给逐帧输入。
 *
 * <p>服务端只做一件事 —— 把输入写进 {@code rt.input}，由 {@link com.dsh.puppet.entity.InputInjector}
 * 真正推动实体。超过 {@code timeoutTicks} 没有新输入就自动退出，避免脚本挂了人偶还在乱跑。
 */
public class ManualTask extends BaseTask {
    private final int timeoutTicks;
    private int idleTicks;
    private int totalTicks;

    private float forward;
    private float strafe;
    private float yaw;
    private float pitch;
    private boolean jump;
    private boolean sprint;
    private boolean sneak;

    public ManualTask(PuppetRuntime rt) {
        this(rt, 20 * 10);
    }

    public ManualTask(PuppetRuntime rt, int timeoutTicks) {
        super(rt);
        this.timeoutTicks = timeoutTicks;
        this.yaw = rt.yaw();
        this.pitch = rt.pitch();
    }

    @Override
    public String name() {
        return "manual";
    }

    public void setInput(float forward, float strafe, float yaw, float pitch,
                         boolean jump, boolean sprint, boolean sneak) {
        this.forward = forward;
        this.strafe = strafe;
        this.yaw = yaw;
        this.pitch = pitch;
        this.jump = jump;
        this.sprint = sprint;
        this.sneak = sneak;
        this.idleTicks = 0;
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        totalTicks++;
        idleTicks++;
        if (idleTicks > timeoutTicks) {
            finish("外部没有继续发输入，退出手动模式");
            return;
        }
        rt.input.forward = forward;
        rt.input.strafe = strafe;
        rt.input.jumping = jump;
        rt.input.sprinting = sprint;
        rt.input.sneaking = sneak;
        rt.input.face(yaw, pitch);
    }

    public int totalTicks() {
        return totalTicks;
    }
}
