package com.cobblemoon.autoqiqi.walk;

import com.cobblemoon.autoqiqi.common.MovementHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Walks the player in a horizontal circle (~5.5 blocks radius).
 * Uses {@link MovementHelper} for shared rotation logic.
 */
public class CircleWalkEngine {
    private static final CircleWalkEngine INSTANCE = new CircleWalkEngine();

    private static final double RADIUS = 5.5;
    private static final double ANGLE_SPEED_RAD_PER_TICK = 0.025;
    private static final float YAW_SPEED = 20.0f;
    private static final float PITCH_SPEED = 12.0f;

    private double centerX;
    private double centerZ;
    private double angleRad;

    private CircleWalkEngine() {}

    public static CircleWalkEngine get() { return INSTANCE; }

    public void start(double playerX, double playerZ) {
        this.centerX = playerX;
        this.centerZ = playerZ;
        this.angleRad = 0;
    }

    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        Vec3d target = new Vec3d(
                centerX + RADIUS * Math.cos(angleRad),
                player.getY(),
                centerZ + RADIUS * Math.sin(angleRad));

        MovementHelper.lookAtPoint(player, target, YAW_SPEED, PITCH_SPEED);

        client.options.forwardKey.setPressed(true);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);

        boolean inWater = player.isTouchingWater();
        boolean hitWall = player.horizontalCollision;
        client.options.jumpKey.setPressed(inWater || hitWall);
        client.options.sprintKey.setPressed(false);

        angleRad += ANGLE_SPEED_RAD_PER_TICK;
        if (angleRad >= 2 * Math.PI) angleRad -= 2 * Math.PI;
    }
}
