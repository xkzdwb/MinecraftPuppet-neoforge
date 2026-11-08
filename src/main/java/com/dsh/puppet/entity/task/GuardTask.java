package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetActions;
import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * 守护 / 跟随战斗 / 原地警戒。
 *
 * <ul>
 *   <li>{@code owner == null}：原地警戒，怪一靠近就上去打；</li>
 *   <li>{@code owner != null}：待在 owner 身边，owner 被打或周围有怪就打。</li>
 * </ul>
 */
public class GuardTask extends BaseTask {
    private final Entity owner;
    private final double radius;
    private LivingEntity revengeTarget;
    private int revengeUntil;
    private int ticks;

    public GuardTask(PuppetRuntime rt, Entity owner, double radius) {
        super(rt);
        this.owner = owner;
        this.radius = Math.max(4.0, radius);
    }

    @Override
    public String name() {
        return owner == null ? "guard" : "guard_" + owner.getName().getString();
    }

    /** 被打了就还手。 */
    public void revenge(LivingEntity attacker) {
        if (attacker != null && attacker.isAlive() && attacker instanceof Enemy) {
            this.revengeTarget = attacker;
            this.revengeUntil = ticks + 20 * 10;
        }
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        ticks++;
        if (owner != null && (!owner.isAlive() || owner.isRemoved())) {
            finish("守护目标不在了");
            return;
        }
        LivingEntity target = pickTarget();
        if (target != null) {
            engage(target);
            return;
        }
        // 没有敌人：回到 owner 身边或原地
        rt.selectBestWeapon();
        if (owner != null) {
            double d = rt.player.distanceTo(owner);
            if (d > radius * 0.6) {
                PuppetActions.driveToward(rt, owner.position(), radius * 0.4, d > 10.0);
            } else {
                PuppetActions.stopMoving(rt);
                rt.face(owner.position().add(0, owner.getBbHeight() * 0.6, 0));
            }
        } else {
            PuppetActions.stopMoving(rt);
        }
    }

    private void engage(LivingEntity target) {
        rt.selectBestWeapon();
        double d = rt.player.distanceTo(target);
        if (d > rt.config.attackRange) {
            PuppetActions.driveToward(rt, target.position(), rt.config.attackRange * 0.7, d > 5.0);
        } else {
            PuppetActions.stopMoving(rt);
        }
        PuppetActions.attack(rt, target);
    }

    private LivingEntity pickTarget() {
        if (revengeTarget != null && ticks < revengeUntil
                && revengeTarget.isAlive() && !revengeTarget.isRemoved()
                && rt.player.distanceTo(revengeTarget) < radius + 8) {
            return revengeTarget;
        }
        revengeTarget = null;
        ServerLevel world = rt.player.level();
        Vec3 center = owner != null ? owner.position() : rt.player.position();
        List<Entity> found = world.getEntities(rt.player,
                rt.player.getBoundingBox().inflate(radius),
                e -> e instanceof Enemy && e.isAlive() && !e.isRemoved());
        return found.stream()
                .filter(e -> e instanceof LivingEntity)
                .filter(e -> e.distanceToSqr(center) < radius * radius)
                .map(e -> (LivingEntity) e)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(rt.player)))
                .orElse(null);
    }
}
