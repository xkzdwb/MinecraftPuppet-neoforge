package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetActions;
import com.dsh.puppet.entity.PuppetRuntime;
import com.dsh.puppet.entity.path.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** A* 寻路走到某个坐标。 */
public class GotoTask extends BaseTask {
    private final Vec3 target;
    private final double tol;
    private final boolean sprint;
    private List<BlockPos> path;
    private int index;
    private int repathTicks;
    private int stuckTicks;
    private Vec3 lastPos;

    public GotoTask(PuppetRuntime rt, Vec3 target, double tol, boolean sprint) {
        super(rt);
        this.target = target;
        this.tol = tol;
        this.sprint = sprint;
    }

    @Override
    public String name() {
        return "goto";
    }

    @Override
    public void start() {
        recalc();
    }

    private void recalc() {
        ServerLevel world = rt.player.level();
        BlockPos start = rt.player.blockPosition();
        BlockPos goal = BlockPos.containing(target);
        path = Pathfinder.find(world, start, goal, Pathfinder.Policy.DIG_1x1, 4000);
        index = 0;
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        double dist = Math.sqrt(Math.pow(target.x - rt.player.getX(), 2) + Math.pow(target.z - rt.player.getZ(), 2));
        if (dist <= tol) {
            finish("到达目的地");
            return;
        }
        if (--repathTicks <= 0) {
            recalc();
            repathTicks = 40;
        }
        if (path == null || path.isEmpty()) {
            // 找不到路：直接朝目标硬走，靠跳跃/下落翻越
            PuppetActions.driveToward(rt, target, tol, sprint);
            stuckCheck();
            return;
        }
        while (index < path.size() - 1) {
            BlockPos wp = path.get(index);
            double d = Math.sqrt(Math.pow(wp.getX() + 0.5 - rt.player.getX(), 2)
                    + Math.pow(wp.getZ() + 0.5 - rt.player.getZ(), 2));
            if (d < 0.9) {
                index++;
            } else {
                break;
            }
        }
        BlockPos wp = path.get(index);
        Vec3 aim = new Vec3(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
        PuppetActions.driveToward(rt, aim, 0.7, sprint);
        stuckCheck();
    }

    private void stuckCheck() {
        Vec3 p = rt.player.position();
        if (lastPos != null && p.distanceToSqr(lastPos) < 0.0025) {
            stuckTicks++;
            if (stuckTicks > 20 && rt.player.onGround()) {
                PuppetActions.jump(rt.player);
            }
            if (stuckTicks > 100) {
                finish("卡住了，放弃");
            }
        } else {
            stuckTicks = 0;
        }
        lastPos = p;
    }
}
