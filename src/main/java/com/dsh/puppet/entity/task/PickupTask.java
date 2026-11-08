package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetActions;
import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** 走过去捡掉落物，捡够了或者没得捡就结束。 */
public class PickupTask extends BaseTask {
    private int ticks;
    private ItemEntity target;
    private final Set<Integer> ignored = new HashSet<>();

    public PickupTask(PuppetRuntime rt) {
        super(rt);
    }

    @Override
    public String name() {
        return "pickup";
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        if (++ticks > 20 * 60) {
            finish("捡东西超时");
            return;
        }
        if (target == null || !target.isAlive() || target.isRemoved()) {
            target = nearest();
            if (target == null) {
                finish("附近没有可捡的东西了");
                return;
            }
        }
        if (rt.player.distanceTo(target) < 1.2) {
            // 走到脚边，让物品自己吸附（原版 ItemEntity#playerTouch）
            ignored.add(target.getId());
            target = null;
            PuppetActions.stopMoving(rt);
            return;
        }
        PuppetActions.driveToward(rt, target.position(), 0.8, true);
    }

    private ItemEntity nearest() {
        AABB box = rt.player.getBoundingBox().inflate(24.0);
        List<ItemEntity> items = rt.player.level().getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && !e.isRemoved() && !e.getItem().isEmpty() && !ignored.contains(e.getId()));
        return items.stream()
                .min(Comparator.comparingDouble(e -> rt.player.distanceToSqr(e)))
                .orElse(null);
    }
}
