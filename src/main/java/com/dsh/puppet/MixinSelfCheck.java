package com.dsh.puppet;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * 纯静态的“目标方法存在性”自检。
 *
 * <p>Mixin 的 {@code @Inject} 用的是字符串方法名，编译器不检查。这里用反射把
 * 每个注入点都验证一遍 —— 版本迁移时最容易在这里静默炸掉。
 *
 * <p>由 {@code gradle verifyMixins} 任务在编译后运行（见 build.gradle）。
 */
public final class MixinSelfCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        // 注意：接口是由 mixin 在运行期织入的，编译期的目标类必然“没实现”。
        // 所以这里不校验 implements，只校验注入点的方法/字段真实存在。

        // EntityRotationMixin: setYRot / setXRot
        requireMethod(Entity.class, "setYRot", float.class);
        requireMethod(Entity.class, "setXRot", float.class);

        // PlayerEventsMixin: hurtServer / die
        requireMethod(ServerPlayer.class, "hurtServer",
                net.minecraft.server.level.ServerLevel.class,
                net.minecraft.world.damagesource.DamageSource.class, float.class);
        requireMethod(ServerPlayer.class, "die",
                net.minecraft.world.damagesource.DamageSource.class);

        // ItemPickupMixin: ItemEntity#playerTouch(Player)
        requireMethod(ItemEntity.class, "playerTouch", net.minecraft.world.entity.player.Player.class);

        // InteractionManagerMixin: ServerPlayerGameMode#destroyBlock(BlockPos) + protected player 字段
        requireMethod(ServerPlayerGameMode.class, "destroyBlock", net.minecraft.core.BlockPos.class);
        requireField(ServerPlayerGameMode.class, "player", ServerPlayer.class);

        // PuppetActions 依赖的关键入口
        requireMethod(ServerPlayerGameMode.class, "handleBlockBreakAction",
                net.minecraft.core.BlockPos.class,
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.class,
                net.minecraft.core.Direction.class, int.class, int.class);
        requireMethod(ServerPlayerGameMode.class, "useItemOn",
                ServerPlayer.class, net.minecraft.world.level.Level.class,
                net.minecraft.world.item.ItemStack.class, net.minecraft.world.InteractionHand.class,
                net.minecraft.world.phys.BlockHitResult.class);
        requireMethod(net.minecraft.world.entity.LivingEntity.class, "jumpFromGround");

        if (failures > 0) {
            System.err.println("MIXIN SELF-CHECK FAILED: " + failures + " problem(s)");
            System.exit(1);
        }
        System.out.println("MIXIN SELF-CHECK OK: all injection targets present");
    }

    private static void requireMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            owner.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            fail(owner.getSimpleName() + "#" + name + "(" + params.length + " args) 不存在");
        }
    }

    private static void requireMethod(Class<?> owner, String name) {
        requireMethod(owner, name, new Class<?>[0]);
    }

    private static void requireField(Class<?> owner, String name, Class<?> type) {
        try {
            var f = owner.getDeclaredField(name);
            if (f.getType() != type) {
                fail(owner.getSimpleName() + "#" + name + " 类型是 " + f.getType().getSimpleName());
            }
        } catch (NoSuchFieldException e) {
            fail(owner.getSimpleName() + "#" + name + " 字段不存在");
        }
    }

    private static void fail(String msg) {
        System.err.println("  [FAIL] " + msg);
        failures++;
    }
}
