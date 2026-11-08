package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetActions;
import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 主动清怪：找最近的指定怪物并打死，直到打够或没怪了。 */
public class KillTask extends BaseTask {
    private final String mobId;
    private final int count;
    private int killed;
    private LivingEntity current;
    private int searchCooldown;
    private int noTargetTicks;

    public KillTask(PuppetRuntime rt, String mobId, int count) {
        super(rt);
        this.mobId = mobId == null ? "mob" : mobId.toLowerCase(Locale.ROOT).trim();
        this.count = Math.max(1, count);
    }

    @Override
    public String name() {
        return "kill_" + mobId;
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        if (killed >= count) {
            finish("打完收工：" + killed + "/" + count);
            return;
        }
        if (current == null || !current.isAlive() || current.isRemoved()) {
            if (current != null) {
                killed++;
                rt.say("击杀 " + killed + "/" + count);
                current = null;
            }
            if (searchCooldown-- > 0) {
                PuppetActions.stopMoving(rt);
                return;
            }
            searchCooldown = 10;
            current = findTarget();
            if (current == null) {
                if (++noTargetTicks > 20 * 8) {
                    finish(killed > 0 ? "附近没怪了，已击杀 " + killed : "附近找不到目标怪物");
                }
                PuppetActions.stopMoving(rt);
                return;
            }
            noTargetTicks = 0;
        }
        rt.selectBestWeapon();
        double d = rt.player.distanceTo(current);
        if (d > rt.config.attackRange) {
            PuppetActions.driveToward(rt, current.position(), rt.config.attackRange * 0.7, d > 5.0);
        } else {
            PuppetActions.stopMoving(rt);
        }
        PuppetActions.attack(rt, current);
    }

    private LivingEntity findTarget() {
        AABB box = rt.player.getBoundingBox().inflate(24.0);
        List<Entity> found = rt.player.level().getEntities(rt.player, box,
                e -> e instanceof LivingEntity && e.isAlive() && !e.isRemoved() && matches(e));
        return found.stream()
                .map(e -> (LivingEntity) e)
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(rt.player)))
                .orElse(null);
    }

    private boolean matches(Entity e) {
        if (mobId.equals("mob") || mobId.equals("any") || mobId.equals("enemy") || mobId.equals("怪")) {
            return e instanceof Enemy;
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        String path = id.getPath();
        String want = mobId.replace("minecraft:", "");
        return path.equals(want) || path.contains(want);
    }
}
