package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetActions;
import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.world.phys.Vec3;

/** 走到某条路径的下一格（给外部脚本分段驱动用）。 */
public class WalkTask extends BaseTask {
    private final Vec3 target;
    private final double tol;
    private int ticks;

    public WalkTask(PuppetRuntime rt, Vec3 target, double tol) {
        super(rt);
        this.target = target;
        this.tol = tol;
    }

    @Override
    public String name() {
        return "walk";
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        if (++ticks > 20 * 60) {
            finish("走路超时");
            return;
        }
        if (PuppetActions.walkTo(rt, target, tol, true)) {
            finish("走到了");
        }
    }
}
