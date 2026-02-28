package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;

/**
 * Direction-only guide: shows which way to walk to a Pokemon (from last scan).
 * Does not move the player — use this to walk yourself and avoid obstacles.
 * Set via /pk guide &lt;n&gt;, cleared with /pk guide stop or when target is gone.
 */
public class DirectionGuide {
    private static final DirectionGuide INSTANCE = new DirectionGuide();

    private Entity target = null;

    private DirectionGuide() {}

    public static DirectionGuide get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return target != null && target.isAlive() && !target.isRemoved();
    }

    public Entity getTarget() {
        return target;
    }

    /** Set guide target from last scan index (1-based). Caller should validate scan. */
    public void setTarget(Entity entity) {
        this.target = entity;
        if (entity != null) {
            AutoQiqiClient.log("Guide", "Direction guide -> " + PokemonScanner.getPokemonName(entity)
                    + " at " + String.format("%.1f", entity.getPos().x) + "," + String.format("%.1f", entity.getPos().z));
        }
    }

    public void stop() {
        if (target != null) {
            AutoQiqiClient.log("Guide", "Direction guide cleared");
        }
        this.target = null;
    }

    /**
     * Returns a HUD line like "Guide -> Pikachu (28m, N)" or null if no valid target.
     */
    public String getStatusDisplay() {
        if (!isActive()) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return null;

        double dist = player.distanceTo(target);
        String name = PokemonScanner.getPokemonName(target);
        String cardinal = getCardinalDirection(player, target);
        return "Guide -> " + name + " (" + String.format("%.1f", dist) + "m, " + cardinal + ")";
    }

    /** Minecraft: +Z South, -Z North, +X West, -X East. Returns N, NE, E, SE, S, SW, W, NW. */
    private static String getCardinalDirection(ClientPlayerEntity player, Entity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        // Angle from North (-Z): atan2(dx, -dz) -> 0=N, 90=W, 180=S, 270=E
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0) deg += 360;

        if (deg < 22.5 || deg >= 337.5) return "N";
        if (deg < 67.5) return "NE";
        if (deg < 112.5) return "E";
        if (deg < 157.5) return "SE";
        if (deg < 202.5) return "S";
        if (deg < 247.5) return "SW";
        if (deg < 292.5) return "W";
        return "NW";
    }
}
