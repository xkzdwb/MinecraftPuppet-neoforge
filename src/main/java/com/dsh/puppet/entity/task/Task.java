package com.dsh.puppet.entity.task;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** 人偶任务的基本接口。任务只在服务端主线程的 tick 中运行。 */
public interface Task {
    /** 短名称，例如 "goto"、"mine_ore"。 */
    String name();

    /** 任务开始时调用一次。 */
    void start();

    /** 每个玩家 tick 调用一次；可写 {@code rt.input}、调用 {@code PuppetActions}。 */
    void tick();

    boolean isRunning();

    /** 结束原因，供界面显示。 */
    String reason();

    void stop(String reason);

    // ---- 可选事件（默认忽略）
    default void onPickup(ItemStack stack) {
    }

    default void onBlockBroken(BlockPos pos, BlockState state) {
    }
}
