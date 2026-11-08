package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetActions;
import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/** 跟着某个实体（玩家/村民）走；目标不见了就地待命。 */
public class FollowTask extends BaseTask {
    private final String targetName;
    private final double distance;
    private Entity target;
    private int lostTicks;

    public FollowTask(PuppetRuntime rt, String targetName, double distance) {
        super(rt);
        this.targetName = targetName;
        this.distance = distance;
    }

    @Override
    public String name() {
        return "follow";
    }

    @Override
    public void start() {
        target = find(targetName);
    }

    private Entity find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        List<Entity> all = rt.nearbyEntities(64.0);
        for (Entity e : all) {
            if (e != rt.player && e.getName().getString().toLowerCase(java.util.Locale.ROOT).equals(n)) {
                return e;
            }
        }
        for (Entity e : all) {
            if (e != rt.player && e.getName().getString().toLowerCase(java.util.Locale.ROOT).contains(n)) {
                return e;
            }
        }
        return null;
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        if (target == null || !target.isAlive() || target.isRemoved()) {
            if (++lostTicks % 40 == 0) {
                target = find(targetName);
            }
            PuppetActions.stopMoving(rt);
            return;
        }
        double d = rt.player.distanceTo(target);
        if (d <= distance) {
            PuppetActions.stopMoving(rt);
            rt.face(target.position().add(0, target.getBbHeight() * 0.5, 0));
            return;
        }
        PuppetActions.driveToward(rt, target.position(), distance * 0.8, d > 8.0);
    }
}
