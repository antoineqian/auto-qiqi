package com.cobblemoon.autoqiqi.mine;

import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans for Nether Gold Ore blocks around the player within a configurable radius.
 * Returns positions sorted by distance (closest first).
 */
public class NethergoldScanner {

    private static final int SCAN_Y_RANGE = 10;

    /**
     * Scan a horizontal radius around the player for nether gold ore.
     * Vertical range is capped to +/- {@link #SCAN_Y_RANGE} for performance.
     */
    public static List<BlockPos> scan(ClientPlayerEntity player, ClientWorld world, int radius) {
        BlockPos center = player.getBlockPos();
        List<BlockPos> found = new ArrayList<>();

        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        int rSq = radius * radius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > rSq) continue;
                for (int dy = -SCAN_Y_RANGE; dy <= SCAN_Y_RANGE; dy++) {
                    BlockPos pos = new BlockPos(cx + dx, cy + dy, cz + dz);
                    if (world.getBlockState(pos).isOf(Blocks.NETHER_GOLD_ORE)) {
                        found.add(pos);
                    }
                }
            }
        }

        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(center)));
        return found;
    }
}
