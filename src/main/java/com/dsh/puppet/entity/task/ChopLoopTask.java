package com.dsh.puppet.entity.task;

import com.dsh.puppet.entity.PuppetActions;
import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 种树循环：砍一棵 → 在树桩原地补种树苗 → 找下一棵。
 * 没有树苗时会说明原因并结束。
 */
public class ChopLoopTask extends BaseTask {
    private final int rounds;
    private int done;
    private GatherTask current;
    private int waitTicks;

    public ChopLoopTask(PuppetRuntime rt, int rounds, int perTree) {
        super(rt);
        this.rounds = Math.max(1, rounds);
    }

    @Override
    public String name() {
        return "farm_tree";
    }

    @Override
    public void start() {
        next();
    }

    private void next() {
        if (done >= rounds) {
            finish("种树循环完成：" + done + "/" + rounds);
            return;
        }
        current = new GatherTask(rt, GatherTask.Kind.CHOP, "", 1) {
            private BlockPos lastBroken;

            @Override
            public void onBlockBroken(BlockPos pos, BlockState state) {
                lastBroken = pos;
                super.onBlockBroken(pos, state);
            }

            @Override
            public void stop(String reason) {
                super.stop(reason);
                if (lastBroken != null) {
                    plantAt(lastBroken);
                }
            }

            private void plantAt(BlockPos stump) {
                ItemStack sapling = findSapling();
                if (sapling.isEmpty()) {
                    rt.say("没有树苗了，先收着木头吧");
                    return;
                }
                var inv = rt.player.getInventory();
                int slot = inv.findSlotMatchingItem(sapling);
                if (slot < 0) {
                    return;
                }
                int prev = inv.getSelectedSlot();
                inv.setSelectedSlot(slot);
                ServerLevel world = rt.player.level();
                BlockPos soil = stump.below();
                if (!world.getBlockState(soil).isAir()) {
                    PuppetActions.interactBlock(rt, soil);
                    rt.say("补种了一棵树苗");
                }
                inv.setSelectedSlot(prev);
            }

            private ItemStack findSapling() {
                var inv = rt.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (!s.isEmpty() && s.is(net.minecraft.tags.ItemTags.SAPLINGS)) {
                        return s;
                    }
                }
                return ItemStack.EMPTY;
            }
        };
        rt.setTask(current);
    }

    @Override
    public void tick() {
        if (!running) {
            return;
        }
        if (current != null && !current.isRunning()) {
            done++;
            current = null;
            waitTicks = 40;
            rt.say("第 " + done + " 轮完成");
        }
        if (current == null) {
            if (waitTicks-- <= 0) {
                next();
            }
        }
    }
}
