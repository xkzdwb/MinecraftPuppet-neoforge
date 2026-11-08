package com.dsh.puppet.entity.path;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 3D A*：允许斜走、允许斜向攀爬 1 格、允许下落最多 3 格、允许在水里游。
 * 目标点优先取“站立点”（双脚 + 头顶两格空气），找不到时取“可挖掘点”。
 * 为了控制开销，搜索节点上限默认 4000。
 */
public final class Pathfinder {
    private static final int[][] HORIZ = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private Pathfinder() {
    }

    public enum Policy {
        /** 只走不需要挖的格子。 */
        SAFE,
        /** 允许挖开 1 格脚下的/头顶的“软”方块。 */
        DIG_1x1
    }

    public static List<BlockPos> find(Level world, BlockPos start, BlockPos goal, Policy policy, int maxNodes) {
        BlockPos from = standable(world, start) ? start : findNearestStandable(world, start, 3);
        if (from == null) {
            return null;
        }
        BlockPos to = goal;
        if (!standable(world, to)) {
            BlockPos near = findNearestStandable(world, goal, 2);
            to = near != null ? near : goal;
        }
        boolean goalIsStand = standable(world, to);

        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Map<BlockPos, Double> gScore = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>();

        gScore.put(from, 0.0);
        open.add(new Node(from, heuristic(from, to)));

        int expanded = 0;
        BlockPos closest = from;
        double closestH = heuristic(from, to);

        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            if (!closed.add(cur.pos)) {
                continue;
            }
            expanded++;
            double h = heuristic(cur.pos, to);
            if (h < closestH) {
                closestH = h;
                closest = cur.pos;
            }
            if (cur.pos.equals(to)) {
                return reconstruct(cameFrom, to);
            }
            if (goalIsStand && isAdjacentEnough(cur.pos, to)) {
                cameFrom.put(to, cur.pos);
                return reconstruct(cameFrom, to);
            }
            double g = gScore.getOrDefault(cur.pos, Double.MAX_VALUE);
            for (int[] d : HORIZ) {
                for (BlockPos next : neighbors(world, cur.pos, d, policy)) {
                    if (!closed.contains(next) && inRange(next, from)) {
                        double cost = moveCost(cur.pos, next, d);
                        double tentative = g + cost;
                        if (tentative < gScore.getOrDefault(next, Double.MAX_VALUE)) {
                            gScore.put(next, tentative);
                            cameFrom.put(next, cur.pos);
                            open.add(new Node(next, tentative + heuristic(next, to)));
                        }
                    }
                }
            }
        }
        if (goalIsStand) {
            return null;
        }
        if (!closest.equals(from)) {
            return reconstruct(cameFrom, closest);
        }
        return null;
    }

    private static boolean inRange(BlockPos p, BlockPos origin) {
        return p.distManhattan(origin) < 128 && Math.abs(p.getY() - origin.getY()) < 32;
    }

    private static double moveCost(BlockPos a, BlockPos b, int[] d) {
        double base = (d[0] != 0 && d[1] != 0) ? 1.4142 : 1.0;
        if (b.getY() > a.getY()) {
            base += 0.3;
        }
        if (b.getY() < a.getY()) {
            base += 0.1;
        }
        return base;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dy = Math.abs(a.getY() - b.getY());
        double dz = Math.abs(a.getZ() - b.getZ());
        return (dx + dz) + (1.4142 - 2) * Math.min(dx, dz) + dy * 0.8;
    }

    private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = end;
        int guard = 0;
        while (cur != null && guard++ < 4096) {
            path.add(cur);
            cur = cameFrom.get(cur);
            if (path.size() > 1 && cur != null && cur.equals(end)) {
                break;
            }
        }
        Collections.reverse(path);
        return path;
    }

    private static boolean isAdjacentEnough(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 1 && Math.abs(a.getZ() - b.getZ()) <= 1 && Math.abs(a.getY() - b.getY()) <= 1;
    }

    /** 从 pos 出发，按方向找出所有“下一步能站”的位置。 */
    private static List<BlockPos> neighbors(Level world, BlockPos pos, int[] d, Policy policy) {
        List<BlockPos> out = new ArrayList<>(3);
        int dx = d[0], dz = d[1];
        boolean diag = dx != 0 && dz != 0;

        if (diag) {
            // 斜走要求两个正交方向都不被挡，避免蹭墙角
            BlockPos a = pos.offset(dx, 0, 0);
            BlockPos b = pos.offset(0, 0, dz);
            if (blocked(world, a) || blocked(world, b)) {
                return out;
            }
        }

        // 1) 平走
        BlockPos level = pos.offset(dx, 0, dz);
        if (standable(world, level)) {
            out.add(level);
        }
        // 2) 上一格
        BlockPos up = pos.offset(dx, 1, dz);
        if (canStandAt(world, up)) {
            out.add(up);
        }
        // 3) 下落
        for (int drop = 1; drop <= 3; drop++) {
            BlockPos down = pos.offset(dx, -drop, dz);
            if (canStandAt(world, down)) {
                out.add(down);
                break;
            }
            if (!isPassable(world, pos.offset(dx, -drop, dz))) {
                break;
            }
        }
        // 4) 挖 1 格：平走被挡 -> 挖掉脚下方块再走进去
        if (policy == Policy.DIG_1x1 && blocked(world, level) && diggable(world, level)) {
            out.add(level);
        }
        return out;
    }

    private static boolean blocked(Level world, BlockPos p) {
        return !isPassable(world, p);
    }

    public static boolean standable(Level world, BlockPos p) {
        return canStandAt(world, p) && isSolidGround(world, p.below());
    }

    public static boolean canStandAt(Level world, BlockPos p) {
        return isPassable(world, p) && isPassable(world, p.above());
    }

    public static boolean isPassable(Level world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty()) {
            return true;
        }
        return s.getCollisionShape(world, p).isEmpty();
    }

    public static boolean isSolidGround(Level world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        if (!s.getFluidState().isEmpty()) {
            return true;
        }
        return !s.getCollisionShape(world, p).isEmpty();
    }

    private static boolean diggable(Level world, BlockPos p) {
        BlockState s = world.getBlockState(p);
        return !s.isAir() && s.getDestroySpeed(world, p) >= 0 && s.getDestroySpeed(world, p) < 30
                && !s.is(Blocks.BEDROCK);
    }

    public static BlockPos findNearestStandable(Level world, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (standable(world, p)) {
                        double d = dx * dx + dy * dy * 4 + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** 判断某方块是否是“木头/可采集”类，给任务用。 */
    public static boolean isLogOrLeaf(BlockState s) {
        return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
    }

    private record Node(BlockPos pos, double f) implements Comparable<Node> {
        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }
}
