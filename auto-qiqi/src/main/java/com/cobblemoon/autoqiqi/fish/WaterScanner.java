package com.cobblemoon.autoqiqi.fish;

import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Scans the area around the player for water surfaces suitable for fishing.
 * Scores candidates by distance, water body size, and vertical alignment
 * to pick the best casting spot.
 */
public class WaterScanner {

    private static final int SCAN_RADIUS_H = 20;
    private static final int SCAN_Y_DOWN = 10;
    private static final int SCAN_Y_UP = 3;
    private static final double IDEAL_MIN_DIST = 4.0;
    private static final double IDEAL_MAX_DIST = 16.0;
    private static final double MAX_DIST = 22.0;
    private static final int MIN_WATER_AREA = 3;

    /**
     * Find the best fishing spot near the player.
     * @return a Vec3d slightly above the water surface to aim at, or null if nothing suitable
     */
    public static Vec3d findFishingSpot(ClientPlayerEntity player, ClientWorld world) {
        BlockPos playerPos = player.getBlockPos();
        int px = playerPos.getX();
        int py = playerPos.getY();
        int pz = playerPos.getZ();

        BlockPos bestPos = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int dx = -SCAN_RADIUS_H; dx <= SCAN_RADIUS_H; dx++) {
            for (int dz = -SCAN_RADIUS_H; dz <= SCAN_RADIUS_H; dz++) {
                if (dx * dx + dz * dz > SCAN_RADIUS_H * SCAN_RADIUS_H) continue;

                for (int dy = -SCAN_Y_DOWN; dy <= SCAN_Y_UP; dy++) {
                    BlockPos pos = new BlockPos(px + dx, py + dy, pz + dz);

                    if (!isWaterSurface(world, pos)) continue;

                    double dist = Math.sqrt(pos.getSquaredDistance(playerPos));
                    if (dist > MAX_DIST || dist < 2.0) continue;

                    int area = countWaterArea(world, pos);
                    if (area < MIN_WATER_AREA) continue;

                    double score = scoreSpot(dist, area, py - pos.getY());
                    if (score > bestScore) {
                        bestScore = score;
                        bestPos = pos;
                    }
                }
            }
        }

        if (bestPos == null) return null;
        return new Vec3d(bestPos.getX() + 0.5, bestPos.getY() + 0.85, bestPos.getZ() + 0.5);
    }

    private static boolean isWaterSurface(ClientWorld world, BlockPos pos) {
        FluidState fluid = world.getFluidState(pos);
        if (!fluid.isIn(FluidTags.WATER)) return false;
        BlockState above = world.getBlockState(pos.up());
        return !above.blocksMovement() && !world.getFluidState(pos.up()).isIn(FluidTags.WATER);
    }

    private static int countWaterArea(ClientWorld world, BlockPos center) {
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (world.getFluidState(center.add(dx, 0, dz)).isIn(FluidTags.WATER)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Score a water spot. Higher is better.
     * @param distance   horizontal distance from player
     * @param waterArea  number of water blocks in 5x5 around the spot
     * @param yDiffDown  playerY - spotY (positive = water below player, which is good)
     */
    private static double scoreSpot(double distance, int waterArea, int yDiffDown) {
        double distScore;
        if (distance < IDEAL_MIN_DIST) {
            distScore = distance / IDEAL_MIN_DIST;
        } else if (distance <= IDEAL_MAX_DIST) {
            distScore = 1.0;
        } else {
            distScore = Math.max(0, 1.0 - (distance - IDEAL_MAX_DIST) / (MAX_DIST - IDEAL_MAX_DIST));
        }

        double areaScore = Math.min(waterArea / 15.0, 1.0);

        double yScore;
        if (yDiffDown >= 0 && yDiffDown <= 4) {
            yScore = 1.0;
        } else if (yDiffDown > 4) {
            yScore = Math.max(0, 1.0 - (yDiffDown - 4) * 0.15);
        } else {
            yScore = Math.max(0, 1.0 + yDiffDown * 0.3);
        }

        return distScore * 3.0 + areaScore * 2.0 + yScore * 1.0;
    }
}
