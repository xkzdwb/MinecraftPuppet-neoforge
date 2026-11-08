package com.dsh.puppet.entity;

import com.dsh.puppet.entity.path.Pathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 人偶“手脚”：移动 / 视线 / 跳跃 / 挖掘 / 交互 / 攻击。
 *
 * <p>核心做法：不读键盘、不发按键事件，而是
 * <ol>
 *   <li>把“这一 tick 想怎么走、想朝哪看、想按哪个动作”写进 {@link SimulatedInput}；</li>
 *   <li>由 {@link InputInjector} 每 tick 末尾把这份输入换算成实体运动（客户端在后台/失焦也照样动）；</li>
 *   <li>挖掘/交互/攻击直接调用 {@code gameMode}、{@code player.attack} 等原版入口。</li>
 * </ol>
 */
public final class PuppetActions {
    private PuppetActions() {
    }

    // ================================================================ 移动

    /** 朝 target 走一步；水平距离进入 tol 返回 true。 */
    public static boolean walkTo(PuppetRuntime rt, Vec3 target, double tol, boolean allowSprint) {
        ServerPlayer player = rt.player;
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double distH = Math.sqrt(dx * dx + dz * dz);
        if (distH <= tol) {
            stopMoving(rt);
            return true;
        }
        float yaw = PuppetRuntime.lookYaw(player.position(), target);
        rt.input.forward = distH < 1.2 ? 0.6f : 1.0f;
        rt.input.strafe = 0.0f;
        rt.input.sprinting = allowSprint && distH > 3.0 && player.getFoodData().getFoodLevel() > 6;
        rt.input.jumping = false;
        rt.input.face(yaw, 20.0f);
        if (player.onGround() && shouldJump(player, yaw)) {
            jump(player);
            rt.input.jumping = true;
        }
        return false;
    }

    /** 只输出输入、不额外步进物理（由注入器统一处理）。 */
    public static void driveToward(PuppetRuntime rt, Vec3 target, double tol, boolean allowSprint) {
        ServerPlayer player = rt.player;
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        double distH = Math.sqrt(dx * dx + dz * dz);
        if (distH <= tol) {
            stopMoving(rt);
            return;
        }
        float yaw = PuppetRuntime.lookYaw(player.position(), target);
        rt.input.forward = distH < 1.2 ? 0.6f : 1.0f;
        rt.input.strafe = 0.0f;
        rt.input.sprinting = allowSprint && distH > 3.0;
        rt.input.face(yaw, 15.0f);
        if (player.onGround() && (shouldJump(player, yaw) || target.y - player.getY() > 0.7)) {
            rt.input.jumping = true;
        }
    }

    public static void stopMoving(PuppetRuntime rt) {
        rt.input.forward = 0.0f;
        rt.input.strafe = 0.0f;
        rt.input.jumping = false;
        rt.input.sprinting = false;
        rt.player.setSprinting(false);
    }

    public static void jump(ServerPlayer player) {
        if (player.onGround()) {
            player.jumpFromGround();
        }
    }

    /** 前方第二层有碰撞、第一层可通行 -> 需要跳。 */
    public static boolean shouldJump(ServerPlayer player, float yaw) {
        return obstacleAhead(player, yaw);
    }

    /** 站在方块旁，并把朝向对准它（交互/挖掘用）。 */
    public static void faceBlock(PuppetRuntime rt, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        rt.lookAt(center);
    }

    // ================================================================ 视线

    public static HitResult pick(PuppetRuntime rt, double reach) {
        ServerPlayer player = rt.player;
        Vec3 eye = rt.eyes();
        Vec3 look = Vec3.directionFromRotation(rt.pitch(), rt.yaw()).scale(reach);
        return player.level().clip(new ClipContext(eye, eye.add(look),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    public static boolean hasLineOfSight(PuppetRuntime rt, BlockPos pos) {
        ServerPlayer player = rt.player;
        Vec3 eye = rt.eyes();
        Vec3 to = new Vec3(pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5);
        HitResult hit = player.level().clip(new ClipContext(eye, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() != HitResult.Type.BLOCK
                || ((BlockHitResult) hit).getBlockPos().equals(pos);
    }

    public static Direction sideToward(PuppetRuntime rt, BlockPos pos) {
        Vec3 d = rt.eyes().subtract(Vec3.atCenterOf(pos));
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        if (ax >= ay && ax >= az) {
            return d.x >= 0 ? Direction.WEST : Direction.EAST;
        }
        if (az >= ay) {
            return d.z >= 0 ? Direction.NORTH : Direction.SOUTH;
        }
        return d.y >= 0 ? Direction.DOWN : Direction.UP;
    }

    // ================================================================ 挖掘

    /**
     * 这一 tick 继续挖 pos。方块已经没了返回 true。
     * 进度由原版 {@code ServerPlayerGameMode} 维护 —— 和真实玩家左键完全同一条代码路径。
     */
    public static boolean mineTick(PuppetRuntime rt, BlockPos pos) {
        ServerPlayer player = rt.player;
        ServerLevel world = player.level();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > 36.0) {
            return false;
        }
        faceBlock(rt, pos);
        if (!hasLineOfSight(rt, pos)) {
            // 被别的方块挡住时，直接放弃这一 tick 的进度（vanilla 会自己衰减）
            return false;
        }
        rt.selectBestTool(state);
        player.swing(InteractionHand.MAIN_HAND, true);
        // 与客户端左键按住完全同一条路径
        player.gameMode.handleBlockBreakAction(pos,
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                sideToward(rt, pos), world.getHeight(), 0);
        return world.getBlockState(pos).isAir();
    }

    // ================================================================ 交互 / 攻击 / 使用

    public static boolean interactBlock(PuppetRuntime rt, BlockPos pos) {
        ServerPlayer player = rt.player;
        faceBlock(rt, pos);
        Vec3 eye = rt.eyes();
        Vec3 to = Vec3.atCenterOf(pos);
        double reach = Math.max(player.blockInteractionRange(), 4.5) + 0.5;
        if (eye.distanceToSqr(to) > reach * reach) {
            return false;
        }
        HitResult hit = player.level().clip(new ClipContext(eye, to,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        BlockHitResult bhit;
        if (hit.getType() == HitResult.Type.BLOCK && ((BlockHitResult) hit).getBlockPos().equals(pos)) {
            bhit = (BlockHitResult) hit;
        } else {
            // 正中心被别的方块/实体挡住时，退化为“该格朝向自己的那一面”
            bhit = new BlockHitResult(to, sideToward(rt, pos), pos, false);
        }
        return player.gameMode.useItemOn(player, player.level(),
                player.getMainHandItem(), InteractionHand.MAIN_HAND, bhit).consumesAction();
    }

    public static void useItem(PuppetRuntime rt) {
        ServerPlayer player = rt.player;
        player.gameMode.useItem(player, player.level(),
                player.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    public static void dropSelected(PuppetRuntime rt) {
        rt.player.drop(false);
    }

    /**
     * 攻击实体：先转身对准，够得着才出手。
     * 这里主动把目标的受击无敌帧清零 —— 自动战斗需要稳定节奏。
     */
    public static boolean attack(PuppetRuntime rt, Entity target) {
        ServerPlayer player = rt.player;
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        rt.lookAt(target.getBoundingBox().getCenter().add(0.0, target.getBbHeight() * 0.5, 0.0));
        if (player.distanceTo(target) > rt.config.attackRange || !player.hasLineOfSight(target)) {
            return false;
        }
        if (target instanceof LivingEntity le) {
            le.invulnerableTime = 0;
        }
        player.attack(target);
        player.swing(InteractionHand.MAIN_HAND, true);
        return true;
    }

    public static void releaseInputs(PuppetRuntime rt) {
        rt.input.reset();
        rt.player.setSprinting(false);
        rt.player.setJumping(false);
    }

    /** 当前到某格方块（中心）的距离。 */
    public static double distanceTo(PuppetRuntime rt, BlockPos pos) {
        return Math.sqrt(rt.player.distanceToSqr(Vec3.atCenterOf(pos)));
    }

    // ================================================================ 内部

    private static boolean obstacleAhead(ServerPlayer player, float yaw) {
        var world = player.level();
        Vec3 dir = Vec3.directionFromRotation(0.0f, yaw).normalize().scale(0.8);
        BlockPos feet = BlockPos.containing(player.getX() + dir.x, player.getY() + 0.1, player.getZ() + dir.z);
        BlockPos up1 = feet.above();
        BlockPos up2 = up1.above();
        boolean low = !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty();
        boolean mid = !world.getBlockState(up1).getCollisionShape(world, up1).isEmpty();
        boolean high = !world.getBlockState(up2).getCollisionShape(world, up2).isEmpty();
        return low && !mid && !high;
    }

    /* ---------- 便于外部任务复用的小工具 ---------- */

    public static boolean isOre(BlockState s) {
        return s.is(BlockTags.MINEABLE_WITH_PICKAXE)
                && (s.is(Blocks.COAL_ORE) || s.is(Blocks.DEEPSLATE_COAL_ORE)
                || s.is(Blocks.IRON_ORE) || s.is(Blocks.DEEPSLATE_IRON_ORE)
                || s.is(Blocks.COPPER_ORE) || s.is(Blocks.DEEPSLATE_COPPER_ORE)
                || s.is(Blocks.GOLD_ORE) || s.is(Blocks.DEEPSLATE_GOLD_ORE)
                || s.is(Blocks.REDSTONE_ORE) || s.is(Blocks.DEEPSLATE_REDSTONE_ORE)
                || s.is(Blocks.LAPIS_ORE) || s.is(Blocks.DEEPSLATE_LAPIS_ORE)
                || s.is(Blocks.DIAMOND_ORE) || s.is(Blocks.DEEPSLATE_DIAMOND_ORE)
                || s.is(Blocks.EMERALD_ORE) || s.is(Blocks.DEEPSLATE_EMERALD_ORE)
                || s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.NETHER_GOLD_ORE)
                || s.is(Blocks.ANCIENT_DEBRIS));
    }

    public static boolean isLog(BlockState s) {
        return s.is(BlockTags.LOGS);
    }

    public static boolean isFoliage(BlockState s) {
        return s.is(BlockTags.LEAVES);
    }

    public static boolean isCrop(BlockState s) {
        return s.getBlock() instanceof CropBlock || s.is(BlockTags.CROPS);
    }

    public static boolean isMatureCrop(BlockState s) {
        if (s.getBlock() instanceof CropBlock crop) {
            return crop.isMaxAge(s);
        }
        if (s.getBlock() instanceof NetherWartBlock) {
            return s.getValue(NetherWartBlock.AGE) >= 3;
        }
        return false;
    }

    /** 目标属于“可破坏”的方块（不碰基岩/命令方块/箱子）。 */
    public static boolean isBreakable(BlockState s) {
        return !s.isAir()
                && s.getDestroySpeed(null, null) >= 0 && s.getDestroySpeed(null, null) < 60
                && !s.is(Blocks.BEDROCK) && !s.is(Blocks.BARRIER)
                && !s.is(Blocks.COMMAND_BLOCK) && !s.is(Blocks.CHEST);
    }

    public static boolean swordOrBetter(PuppetRuntime rt) {
        return rt.mainHand().is(ItemTags.SWORDS) || rt.mainHand().is(ItemTags.AXES);
    }
}
