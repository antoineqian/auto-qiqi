package com.cobblemoon.autoqiqi.fish.monitor;

import com.cobblemoon.autoqiqi.fish.AutofishEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

public class FishMonitorMPMotion implements FishMonitorMP {

    private static final int PACKET_MOTION_Y_THRESHOLD = -350;
    private static final int START_CATCHING_AFTER_THRESHOLD = 1000;

    private boolean hasHitWater = false;
    private long bobberRiseTimestamp = 0;

    @Override
    public void hookTick(AutofishEngine autofish, MinecraftClient minecraft, FishingBobberEntity hook) {
        if (worldContainsWater(hook.getWorld(), hook.getBoundingBox())) {
            hasHitWater = true;
        }
    }

    @Override
    public void handleHookRemoved() {
        hasHitWater = false;
        bobberRiseTimestamp = 0;
    }

    @Override
    public void handlePacket(AutofishEngine autofish, Object packet, MinecraftClient minecraft) {
        if (packet instanceof EntityVelocityUpdateS2CPacket velocityPacket) {
            if (minecraft.player != null && minecraft.player.fishHook != null
                    && minecraft.player.fishHook.getId() == velocityPacket.getEntityId()) {

                if (hasHitWater && bobberRiseTimestamp == 0 && velocityPacket.getVelocityY() > 0) {
                    bobberRiseTimestamp = autofish.getTimeMillis();
                }

                long timeInWater = autofish.getTimeMillis() - bobberRiseTimestamp;

                if (hasHitWater && bobberRiseTimestamp != 0 && timeInWater > START_CATCHING_AFTER_THRESHOLD) {
                    if (velocityPacket.getVelocityY() < PACKET_MOTION_Y_THRESHOLD) {
                        autofish.catchFish();
                        this.handleHookRemoved();
                    }
                }
            }
        }
    }

    private static boolean worldContainsWater(World world, Box box) {
        int i = MathHelper.floor(box.minX);
        int j = MathHelper.ceil(box.maxX);
        int k = MathHelper.floor(box.minY);
        int l = MathHelper.ceil(box.maxY);
        int m = MathHelper.floor(box.minZ);
        int n = MathHelper.ceil(box.maxZ);
        for (BlockPos pos : BlockPos.iterate(i, k, m, j - 1, l - 1, n - 1)) {
            FluidState fluidState = world.getFluidState(pos);
            if (fluidState.isIn(FluidTags.WATER)) return true;
        }
        return false;
    }
}
