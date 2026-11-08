package com.dsh.puppet.mixin;

import com.dsh.puppet.entity.PuppetRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块被破坏时通知任务。
 *
 * <p>挂在 {@code ServerPlayerGameMode#destroyBlock}：人偶的挖掘走的就是这条原版路径
 * （{@code gameMode.handleBlockBreakAction}），所以这里的回调一定命中。
 */
@Mixin(ServerPlayerGameMode.class)
public class InteractionManagerMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "destroyBlock", at = @At("TAIL"))
    private void puppet$onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        try {
            BlockState state = this.player.level().getBlockState(pos);
            PuppetRuntime.of(this.player).onBlockBroken(pos, state);
        } catch (Throwable ignored) {
        }
    }
}
