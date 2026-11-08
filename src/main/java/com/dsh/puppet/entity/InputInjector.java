package com.dsh.puppet.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 输入注入器。
 *
 * <p>这是整个模组里唯一“碰实体状态”的地方，也是最关键的一步：
 * 不走键盘、不走鼠标、不发按键事件，直接把“这一 tick 想怎么动”写进玩家自己的实体里。
 *
 * <p>三条原则：
 * <ol>
 *   <li>{@link SimulatedInput} 由任务每 tick 重置后填写，只描述意图（前进/平移/跳/疾跑/朝向）；</li>
 *   <li>服务端自己执行一遍 {@code player.move(...)}，让人偶的位移由服务端权威计算 ——
 *       这样即使玩家的客户端在后台、失焦、甚至关了游戏，人偶也照样走；</li>
 *   <li>朝向写进 {@link PuppetRotation}（服务端权威 yaw/pitch），由 mixin 记忆。</li>
 * </ol>
 */
public final class InputInjector {
    private InputInjector() {
    }

    /** 玩家实体每 tick 的最后一步：把程序输入转成实体运动。 */
    public static void apply(PuppetRuntime rt) {
        ServerPlayer player = rt.player;
        if (player.isRemoved() || !player.isAlive()) {
            return;
        }
        SimulatedInput in = rt.input;

        // 1) 朝向：先落库，再由客户端渲染
        if (in.hasRotation) {
            ((PuppetRotation) player).puppet$setServerRotation(in.yaw, in.pitch);
        }

        // 2) 水平输入 -> 实体运动
        boolean wantsMove = in.forward != 0.0f || in.strafe != 0.0f;
        if (wantsMove) {
            // 用朝向把“前进/平移”换算成世界方向，然后交给原版 move()
            float yaw = in.hasRotation ? in.yaw : ((PuppetRotation) player).puppet$serverYaw();
            double f = in.forward;
            double s = in.strafe;
            double len = Math.sqrt(f * f + s * s);
            if (len > 1.0) {
                f /= len;
                s /= len;
            }
            double yawRad = Math.toRadians(yaw);
            double dx = -Math.sin(yawRad) * f + Math.cos(yawRad) * s;
            double dz = Math.cos(yawRad) * f + Math.sin(yawRad) * s;

            double speed = player.getSpeed();
            if (in.sprinting) {
                speed *= 1.3;
            }
            if (in.sneaking) {
                speed *= 0.3;
            }
            speed *= rt.config.moveScale;
            player.move(MoverType.SELF, new Vec3(dx * speed, 0.0, dz * speed));
            if (in.sprinting && !player.isShiftKeyDown()) {
                player.setSprinting(true);
            }
        } else {
            player.setSprinting(false);
        }

        // 3) 跳跃
        if (in.jumping && player.onGround()) {
            player.jumpFromGround();
        }
        player.setJumping(in.jumping);

        // 4) 潜行
        player.setShiftKeyDown(in.sneaking);

        // 5) 动作类：挖掘 / 用物品 / 攻击 / 丢弃
        if (in.attacking && rt.task() != null) {
            Entity t = rt.nearestEntityInFront(3.0);
            if (t != null) {
                PuppetActions.attack(rt, t);
            }
        }
        if (in.using) {
            PuppetActions.useItem(rt);
        }
        if (in.mining) {
            HitResult hit = PuppetActions.pick(rt, 4.5);
            if (hit.getType() == HitResult.Type.BLOCK) {
                PuppetActions.mineTick(rt, ((BlockHitResult) hit).getBlockPos());
            }
        }
        if (in.dropping) {
            PuppetActions.dropSelected(rt);
        }
    }

    /** 解除接管：让实体自然停下来。 */
    public static void release(PuppetRuntime rt) {
        ServerPlayer player = rt.player;
        if (player == null) {
            return;
        }
        player.setSprinting(false);
        player.setJumping(false);
        player.setDeltaMovement(player.getDeltaMovement().multiply(0.3, 1.0, 0.3));
    }

    public static boolean isSolid(ServerPlayer player, BlockState state) {
        return !state.isAir() && !state.getCollisionShape(player.level(), player.blockPosition()).isEmpty();
    }
}
