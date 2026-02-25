package com.cobblemoon.autoqiqi.common;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

/**
 * Shared rotation, aiming, and movement-key utilities used by
 * AutoBattle, PokemonWalker, and CircleWalkEngine.
 */
public final class MovementHelper {
    private MovementHelper() {}

    public static float smoothAngle(float current, float target, float maxStep) {
        float diff = MathHelper.wrapDegrees(target - current);
        if (Math.abs(diff) <= maxStep) return target;
        return current + Math.copySign(maxStep, diff);
    }

    public static void lookAtPoint(ClientPlayerEntity player, Vec3d point,
                                   float yawSpeed, float pitchSpeed) {
        Vec3d eye = player.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI)));

        player.setYaw(smoothAngle(player.getYaw(), yaw, yawSpeed));
        player.setPitch(smoothAngle(player.getPitch(), pitch, pitchSpeed));
    }

    public static void lookAtEntity(ClientPlayerEntity player, Entity target,
                                    float yawSpeed, float pitchSpeed) {
        lookAtPoint(player, target.getPos().add(0, target.getHeight() / 2.0, 0),
                yawSpeed, pitchSpeed);
    }

    /**
     * Returns true if there are no solid blocks between the player's eyes
     * and the center of the target entity.
     */
    public static boolean hasLineOfSight(ClientPlayerEntity player, Entity target) {
        if (player == null || target == null) return false;
        World world = player.getWorld();
        if (world == null) return false;

        Vec3d eyePos = player.getEyePos();
        Vec3d targetCenter = target.getPos().add(0, target.getHeight() / 2.0, 0);

        BlockHitResult result = world.raycast(new RaycastContext(
                eyePos, targetCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player));

        return result.getType() == HitResult.Type.MISS
                || eyePos.squaredDistanceTo(result.getPos()) >= eyePos.squaredDistanceTo(targetCenter) * 0.95;
    }

    /**
     * Checks if there's a friendly (non-wild) PokemonEntity between the player
     * and the target entity. Returns the blocking entity, or null if path is clear.
     */
    public static Entity getEntityBlockingThrow(ClientPlayerEntity player, Entity target) {
        if (player == null || target == null) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return null;

        Vec3d eyePos = player.getEyePos();
        Vec3d targetCenter = target.getPos().add(0, target.getHeight() / 2.0, 0);
        Vec3d direction = targetCenter.subtract(eyePos);
        double maxDist = direction.length();
        if (maxDist < 0.1) return null;

        Box searchBox = player.getBoundingBox().expand(maxDist);
        List<Entity> entities = client.world.getOtherEntities(player, searchBox,
                e -> e instanceof PokemonEntity && e != target && e.isAlive());

        for (Entity entity : entities) {
            Box entityBox = entity.getBoundingBox().expand(0.3);
            Optional<Vec3d> hit = entityBox.raycast(eyePos, targetCenter);
            if (hit.isPresent()) {
                double hitDist = eyePos.distanceTo(hit.get());
                double targetDist = eyePos.distanceTo(targetCenter);
                if (hitDist < targetDist) {
                    return entity;
                }
            }
        }
        return null;
    }

    /**
     * Strafe sideways to find line of sight. Call each tick when LOS is blocked.
     */
    public static void strafeSideways(MinecraftClient client, Entity target,
                                      ClientPlayerEntity player, int direction) {
        lookAtEntity(player, target, 15f, 10f);
        client.options.forwardKey.setPressed(false);
        client.options.leftKey.setPressed(direction > 0);
        client.options.rightKey.setPressed(direction < 0);
        client.options.jumpKey.setPressed(player.horizontalCollision);
        client.options.sprintKey.setPressed(false);
    }

    public static void stopStrafe(MinecraftClient client) {
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    public static void releaseMovementKeys(MinecraftClient client) {
        if (client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
        }
    }
}
