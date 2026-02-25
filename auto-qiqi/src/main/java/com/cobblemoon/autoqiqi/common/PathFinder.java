package com.cobblemoon.autoqiqi.common;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Client-side A* pathfinder on the block grid.
 * Supports walk, jump-up-1, drop-down (up to 4), diagonals,
 * diagonal jump-ups, and swimming through water columns.
 */
public class PathFinder {

    private static final int MAX_ITERATIONS = 15000;
    private static final int MAX_DROP = 4;
    private static final double DIAGONAL_COST = Math.sqrt(2);
    private static final double JUMP_COST = 2.0;
    private static final double DROP_COST_PER_BLOCK = 0.5;
    private static final double LIQUID_COST = 2.0;
    private static final double SWIM_UP_COST = 1.5;

    private static final int[][] CARDINAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONAL = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final ClientWorld world;

    public PathFinder(ClientWorld world) {
        this.world = world;
    }

    public List<Vec3d> findPath(BlockPos start, BlockPos goal, double arrivalDist) {
        log("A* start=" + fmt(start) + " goal=" + fmt(goal) + " dist=" + String.format("%.1f", Math.sqrt(start.getSquaredDistance(goal))));

        start = snapToGround(start);
        goal = snapToGround(goal);

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Node> best = new HashMap<>();

        Node startNode = new Node(start, 0, heuristic(start, goal), null);
        open.add(startNode);
        best.put(posKey(start), startNode);

        Node closest = startNode;
        int iterations = 0;

        while (!open.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            Node current = open.poll();

            if (current.closed) continue;
            current.closed = true;

            double distToGoal = Math.sqrt(current.pos.getSquaredDistance(goal));
            if (distToGoal <= arrivalDist) {
                List<Vec3d> result = reconstructPath(current);
                log("A* FOUND path: " + iterations + " iters, " + result.size() + " waypoints");
                return result;
            }

            if (heuristic(current.pos, goal) < heuristic(closest.pos, goal)) {
                closest = current;
            }

            for (Neighbor nb : getNeighbors(current.pos)) {
                double newG = current.g + nb.cost;
                long key = posKey(nb.pos);
                Node existing = best.get(key);

                if (existing != null && (existing.closed || newG >= existing.g)) continue;

                Node node = new Node(nb.pos, newG, heuristic(nb.pos, goal), current);
                best.put(key, node);
                open.add(node);
            }
        }

        double closestDist = Math.sqrt(closest.pos.getSquaredDistance(goal));
        if (heuristic(closest.pos, goal) < heuristic(start, goal) - 3) {
            List<Vec3d> result = reconstructPath(closest);
            log("A* PARTIAL path: " + iterations + " iters, " + result.size() + " waypoints, stopped " + String.format("%.1f", closestDist) + " from goal (explored " + best.size() + " nodes)");
            return result;
        }
        log("A* FAILED: " + iterations + " iters, explored " + best.size() + " nodes, closest=" + String.format("%.1f", closestDist) + " at " + fmt(closest.pos));
        return null;
    }

    /**
     * Snap a position to the ground: if floating in air, drop down.
     * If inside solid, push up. Ensures valid start/goal.
     */
    private BlockPos snapToGround(BlockPos pos) {
        // If inside solid, push up
        for (int i = 0; i < 10; i++) {
            if (isPassable(pos) && isPassable(pos.up())) break;
            pos = pos.up();
        }
        // If floating, drop down to ground
        for (int i = 0; i < 20; i++) {
            if (isSolid(pos.down()) || isLiquid(pos.down())) break;
            if (!isPassable(pos.down())) break;
            pos = pos.down();
        }
        return pos;
    }

    private List<Vec3d> reconstructPath(Node node) {
        List<Vec3d> path = new ArrayList<>();
        while (node != null) {
            path.add(new Vec3d(node.pos.getX() + 0.5, node.pos.getY(), node.pos.getZ() + 0.5));
            node = node.parent;
        }
        Collections.reverse(path);
        if (path.size() > 1) path.remove(0);
        return simplifyPath(path);
    }

    private List<Vec3d> simplifyPath(List<Vec3d> path) {
        if (path.size() <= 2) return path;

        List<Vec3d> simplified = new ArrayList<>();
        simplified.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            Vec3d prev = path.get(i - 1);
            Vec3d curr = path.get(i);
            Vec3d next = path.get(i + 1);

            double dx1 = curr.x - prev.x, dz1 = curr.z - prev.z, dy1 = curr.y - prev.y;
            double dx2 = next.x - curr.x, dz2 = next.z - curr.z, dy2 = next.y - curr.y;

            if (Math.abs(dx1 - dx2) > 0.01 || Math.abs(dz1 - dz2) > 0.01 || Math.abs(dy1 - dy2) > 0.01) {
                simplified.add(curr);
            }
        }
        simplified.add(path.get(path.size() - 1));
        return simplified;
    }

    private List<Neighbor> getNeighbors(BlockPos pos) {
        List<Neighbor> neighbors = new ArrayList<>(18);
        for (int[] dir : CARDINAL) {
            addCardinalMoves(pos, dir[0], dir[1], neighbors, 1.0);
        }
        for (int[] dir : DIAGONAL) {
            addDiagonalMoves(pos, dir[0], dir[1], neighbors);
        }
        addSwimMoves(pos, neighbors);
        return neighbors;
    }

    private void addCardinalMoves(BlockPos pos, int dx, int dz, List<Neighbor> out, double baseCost) {
        BlockPos target = pos.add(dx, 0, dz);

        // Flat walk
        if (canStandAt(target)) {
            double cost = baseCost;
            if (isLiquid(target) || isLiquid(target.up())) cost += LIQUID_COST;
            out.add(new Neighbor(target, cost));
        }

        // Jump up 1
        BlockPos up = pos.add(dx, 1, dz);
        if (canStandAt(up) && isPassable(pos.up(2))) {
            out.add(new Neighbor(up, baseCost + JUMP_COST));
        }

        // Jump up 2 (rare: 2-block climb via intermediate)
        BlockPos up2 = pos.add(dx, 2, dz);
        if (canStandAt(up2) && isPassable(pos.up(2)) && isPassable(pos.up(3))
                && isPassable(pos.add(dx, 1, dz)) && isPassable(pos.add(dx, 2, dz))) {
            // Can only reach +2 if there's a wall to "climb" (solid at +1 to side)
            if (isSolid(pos.add(dx, 0, dz)) || isSolid(pos.add(dx, 1, dz).down())) {
                out.add(new Neighbor(up2, baseCost + JUMP_COST * 2.5));
            }
        }

        // Drop down
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            BlockPos down = pos.add(dx, -drop, dz);
            if (canStandAt(down)) {
                boolean clearFall = true;
                for (int y = 0; y < drop; y++) {
                    if (!isPassable(pos.add(dx, -y, dz))) {
                        clearFall = false;
                        break;
                    }
                }
                if (clearFall) {
                    double cost = baseCost + drop * DROP_COST_PER_BLOCK;
                    if (isLiquid(down.down())) cost -= drop * DROP_COST_PER_BLOCK;
                    out.add(new Neighbor(down, cost));
                }
                break;
            }
        }
    }

    private void addDiagonalMoves(BlockPos pos, int dx, int dz, List<Neighbor> out) {
        BlockPos sideX = pos.add(dx, 0, 0);
        BlockPos sideZ = pos.add(0, 0, dz);
        boolean xOpen = isPassable(sideX) && isPassable(sideX.up());
        boolean zOpen = isPassable(sideZ) && isPassable(sideZ.up());
        if (!xOpen && !zOpen) return;

        // Flat diagonal
        BlockPos target = pos.add(dx, 0, dz);
        if (canStandAt(target)) {
            double cost = DIAGONAL_COST;
            if (isLiquid(target) || isLiquid(target.up())) cost += LIQUID_COST;
            out.add(new Neighbor(target, cost));
        }

        // Diagonal jump up 1
        BlockPos upTarget = pos.add(dx, 1, dz);
        if (canStandAt(upTarget) && isPassable(pos.up(2))) {
            boolean diagHeadroom = isPassable(pos.add(dx, 2, dz));
            if (diagHeadroom) {
                out.add(new Neighbor(upTarget, DIAGONAL_COST + JUMP_COST));
            }
        }

        // Diagonal drop
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            BlockPos down = pos.add(dx, -drop, dz);
            if (canStandAt(down)) {
                boolean clearFall = true;
                for (int y = 0; y < drop; y++) {
                    if (!isPassable(pos.add(dx, -y, dz))) {
                        clearFall = false;
                        break;
                    }
                }
                if (clearFall) {
                    out.add(new Neighbor(down, DIAGONAL_COST + drop * DROP_COST_PER_BLOCK));
                }
                break;
            }
        }
    }

    /**
     * Swimming: in water, allow moving straight up and diagonally up through water.
     */
    private void addSwimMoves(BlockPos pos, List<Neighbor> out) {
        if (!isLiquid(pos) && !isLiquid(pos.up())) return;

        // Swim straight up
        BlockPos above = pos.up();
        if (isPassable(above) && isPassable(above.up())) {
            out.add(new Neighbor(above, SWIM_UP_COST));
        }

        // Swim up + cardinal
        for (int[] dir : CARDINAL) {
            BlockPos target = pos.add(dir[0], 1, dir[1]);
            if (canStandAt(target) || (isLiquid(target) && isPassable(target.up()))) {
                if (isPassable(pos.up(2)) || isLiquid(pos.up(2))) {
                    out.add(new Neighbor(target, 1.0 + SWIM_UP_COST));
                }
            }
        }
    }

    private boolean canStandAt(BlockPos pos) {
        boolean solidBelow = isSolid(pos.down()) || isLiquid(pos.down());
        return solidBelow && isPassable(pos) && isPassable(pos.up());
    }

    private boolean isPassable(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.blocksMovement();
    }

    private boolean isSolid(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.blocksMovement();
    }

    private boolean isLiquid(BlockPos pos) {
        return world.getBlockState(pos).isLiquid();
    }

    /**
     * Tighter heuristic that properly accounts for the higher cost of going up.
     * Admissible: never overestimates the true shortest path cost.
     */
    private double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        int dyRaw = b.getY() - a.getY();

        double horizontal = Math.abs(dx - dz) + Math.min(dx, dz) * DIAGONAL_COST;

        if (dyRaw > 0) {
            // Going up: each jump-up costs at least JUMP_COST extra on top of the
            // horizontal move it includes. The jump also covers 1 horizontal block,
            // so we can subtract that from the remaining horizontal distance.
            double jumps = dyRaw;
            double horizontalFromJumps = Math.min(jumps, horizontal);
            double remainingHorizontal = horizontal - horizontalFromJumps;
            return remainingHorizontal + jumps * (1.0 + JUMP_COST);
        } else {
            double absDy = Math.abs(dyRaw);
            return horizontal + absDy * DROP_COST_PER_BLOCK;
        }
    }

    private long posKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
    }

    private static class Node implements Comparable<Node> {
        final BlockPos pos;
        final double g;
        final double h;
        final Node parent;
        boolean closed;

        Node(BlockPos pos, double g, double h, Node parent) {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.parent = parent;
        }

        double f() { return g + h; }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.f(), other.f());
        }
    }

    private record Neighbor(BlockPos pos, double cost) {}

    private static String fmt(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static void log(String msg) {
        System.out.println("[Auto-Qiqi|Path] " + msg);
    }
}
