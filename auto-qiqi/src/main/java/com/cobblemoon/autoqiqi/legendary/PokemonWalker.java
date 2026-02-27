package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.common.PathFinder;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Client-side auto-walk engine using A* pathfinding (shared {@link PathFinder}).
 * Uses {@link MovementHelper} for shared rotation logic.
 */
public class PokemonWalker {
    private static final PokemonWalker INSTANCE = new PokemonWalker();

    private Entity target = null;
    private boolean active = false;

    private List<Vec3d> path = null;
    private int waypointIndex = 0;
    private Vec3d lastTargetPos = null;

    private long lastWaypointReachedMs = 0;
    private static final long STUCK_TIMEOUT_MS = 3000;
    private int recalcCount = 0;
    private static final int MAX_RECALCS = 10;

    private long walkStartMs = 0;
    private static final long WALK_TOTAL_TIMEOUT_MS = 30_000;
    private boolean timedOut = false;

    private static final double WAYPOINT_REACH_XZ = 0.5;
    private static final double WAYPOINT_REACH_Y = 1.5;
    private static final double ARRIVAL_DISTANCE = 3.5;
    private static final double BEELINE_DISTANCE = 4.0;
    private static final double REPATH_TARGET_MOVED = 5.0;

    // Stuck recovery
    private int stuckTicks = 0;
    private int jiggleDirection = 1;
    private static final int JIGGLE_COMMIT_TICKS = 30; // commit to one direction for 1.5s
    private static final int BEELINE_STUCK_REPATH_TICKS = 25; // after 1.25s stuck in beeline, use A*
    private boolean beelineStuckTriedAstar = false;

    private static final float YAW_SPEED = 10.0f;
    private static final float PITCH_SPEED = 8.0f;

    /** After arriving at a user-chosen walk target, this entity is not auto-engaged for a grace period (manual fight/capture). */
    private int lastWalkTargetEntityId = -1;
    private int lastWalkTargetGraceTicks = 0;
    private static final int MANUAL_WALK_GRACE_TICKS = 400; // ~20 seconds

    private PokemonWalker() {}

    public static PokemonWalker get() { return INSTANCE; }
    public boolean isActive() { return active; }
    public boolean hasTimedOut() { return timedOut; }
    public Entity getTarget() { return target; }
    public int getLastWalkTargetEntityId() { return lastWalkTargetEntityId; }
    public boolean isInManualWalkGracePeriod() { return lastWalkTargetGraceTicks > 0; }

    public void startWalking(Entity targetEntity) {
        this.lastWalkTargetEntityId = -1;
        this.lastWalkTargetGraceTicks = 0;
        this.target = targetEntity;
        this.active = true;
        this.path = null;
        this.waypointIndex = 0;
        this.lastTargetPos = null;
        this.recalcCount = 0;
        this.lastWaypointReachedMs = System.currentTimeMillis();
        this.walkStartMs = System.currentTimeMillis();
        this.stuckTicks = 0;
        this.beelineStuckTriedAstar = false;
        this.timedOut = false;
        AutoQiqiClient.log("Walker", "Start walking to " + PokemonScanner.getPokemonName(targetEntity)
                + " at " + fmtPos(targetEntity.getPos()));
    }

    public void stop() {
        if (active) {
            AutoQiqiClient.log("Walker", "Stopped (recalcs=" + recalcCount + ")");
        }
        this.active = false;
        this.target = null;
        this.path = null;
        this.waypointIndex = 0;
        this.recalcCount = 0;
        this.stuckTicks = 0;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            if (client.options != null) {
                client.options.leftKey.setPressed(false);
                client.options.rightKey.setPressed(false);
            }
            MovementHelper.releaseMovementKeys(client);
        }
    }

    public String getStatusDisplay() {
        if (!active || target == null) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;
        double dist = client.player.distanceTo(target);
        String mode = path != null ? "A*" : "direct";
        return "Walk -> " + PokemonScanner.getPokemonName(target)
                + " (" + String.format("%.1f", dist) + "m, " + mode + ")";
    }

    public void tick() {
        if (!active && lastWalkTargetGraceTicks > 0) {
            lastWalkTargetGraceTicks--;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!active || target == null) return;

        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) { stop(); return; }
        if (client.currentScreen != null) { MovementHelper.releaseMovementKeys(client); return; }
        if (player.isDead()) { stop(); return; }

        if (!target.isAlive() || target.isRemoved()) {
            player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §cPokemon disparu !"), false);
            stop();
            return;
        }

        long elapsed = System.currentTimeMillis() - walkStartMs;
        if (elapsed > WALK_TOTAL_TIMEOUT_MS) {
            String name = PokemonScanner.getPokemonName(target);
            AutoQiqiClient.log("Walker", "TIMEOUT after " + (elapsed / 1000) + "s walking to " + name);
            player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §cTimeout: impossible d'atteindre §e" + name + "§c en " + (WALK_TOTAL_TIMEOUT_MS / 1000) + "s."), false);
            timedOut = true;
            stop();
            return;
        }

        double distToTarget = player.distanceTo(target);

        if (distToTarget <= ARRIVAL_DISTANCE) {
            String name = PokemonScanner.getPokemonName(target);
            AutoQiqiClient.log("Walker", "Arrived near " + name + " (dist=" + String.format("%.1f", distToTarget) + ")");
            player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §aArrive pres de §e" + name + "§a !"), false);
            lastWalkTargetEntityId = target.getId();
            lastWalkTargetGraceTicks = MANUAL_WALK_GRACE_TICKS;
            stop();
            return;
        }

        // If close but stuck during beeline, fall back to A*
        if (distToTarget <= BEELINE_DISTANCE && !beelineStuckTriedAstar) {
            if (player.horizontalCollision) {
                stuckTicks++;
                if (stuckTicks >= BEELINE_STUCK_REPATH_TICKS) {
                    AutoQiqiClient.log("Walker", "Beeline stuck for " + stuckTicks + " ticks, falling back to A*");
                    beelineStuckTriedAstar = true;
                    stuckTicks = 0;
                    computePath(client, player);
                    if (path != null) {
                        followPath(client, player);
                        return;
                    }
                }
            } else {
                stuckTicks = 0;
            }

            // Not stuck or A* failed, keep beelining
            if (!beelineStuckTriedAstar || path == null) {
                moveDirectToTarget(client, player);
                return;
            }
        }

        // Use A* path if we have one (including beeline fallback)
        if (path != null && waypointIndex < path.size()) {
            followPath(client, player);
            return;
        }

        // Need to compute a path
        if (path == null || shouldRepath()) {
            computePath(client, player);
        }

        if (path != null && waypointIndex < path.size()) {
            followPath(client, player);
        } else if (distToTarget <= BEELINE_DISTANCE * 2) {
            moveDirectToTarget(client, player);
        } else {
            AutoQiqiClient.log("Walker", "No path available, beelining (dist=" + String.format("%.1f", distToTarget) + ")");
            moveDirectToTarget(client, player);
        }
    }

    private void computePath(MinecraftClient client, ClientPlayerEntity player) {
        if (recalcCount >= MAX_RECALCS) {
            AutoQiqiClient.log("Walker", "Max recalcs (" + MAX_RECALCS + ") reached, giving up A*");
            path = null;
            return;
        }

        BlockPos start = player.getBlockPos();
        BlockPos goal = target.getBlockPos();

        AutoQiqiClient.log("Walker", "Computing path #" + (recalcCount + 1)
                + " player=" + fmtBlock(start) + " target=" + fmtBlock(goal)
                + " dist=" + String.format("%.1f", player.distanceTo(target)));

        PathFinder pathFinder = new PathFinder(client.world);
        path = pathFinder.findPath(start, goal, ARRIVAL_DISTANCE);
        waypointIndex = 0;
        lastWaypointReachedMs = System.currentTimeMillis();
        lastTargetPos = target.getPos();
        recalcCount++;

        if (path == null) {
            AutoQiqiClient.log("Walker", "A* returned NO path");
        } else {
            AutoQiqiClient.log("Walker", "A* returned " + path.size() + " waypoints");
        }
    }

    private boolean shouldRepath() {
        if (lastTargetPos != null && target.getPos().squaredDistanceTo(lastTargetPos) > REPATH_TARGET_MOVED * REPATH_TARGET_MOVED) {
            AutoQiqiClient.log("Walker", "Target moved, repathing");
            recalcCount = Math.max(0, recalcCount - 1);
            return true;
        }
        long stuckMs = System.currentTimeMillis() - lastWaypointReachedMs;
        if (stuckMs > STUCK_TIMEOUT_MS) {
            MinecraftClient client = MinecraftClient.getInstance();
            String playerPos = client.player != null ? fmtPos(client.player.getPos()) : "?";
            String wpInfo = (path != null && waypointIndex < path.size()) ? fmtPos(path.get(waypointIndex)) : "none";
            AutoQiqiClient.log("Walker", "STUCK for " + stuckMs + "ms! player=" + playerPos
                    + " waypoint[" + waypointIndex + "]=" + wpInfo
                    + " collision=" + (client.player != null && client.player.horizontalCollision));
            return true;
        }
        return path != null && waypointIndex >= path.size();
    }

    private void followPath(MinecraftClient client, ClientPlayerEntity player) {
        Vec3d waypoint = path.get(waypointIndex);
        Vec3d playerPos = player.getPos();

        double dx = waypoint.x - playerPos.x;
        double dz = waypoint.z - playerPos.z;
        double dy = waypoint.y - playerPos.y;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist <= WAYPOINT_REACH_XZ && Math.abs(dy) < WAYPOINT_REACH_Y) {
            waypointIndex++;
            lastWaypointReachedMs = System.currentTimeMillis();
            stuckTicks = 0;
            if (waypointIndex >= path.size()) return;
            waypoint = path.get(waypointIndex);
            dy = waypoint.y - playerPos.y;
        }

        boolean inWater = player.isTouchingWater();
        boolean needsJump = dy > 0.5;
        boolean hitWall = player.horizontalCollision;

        if (hitWall) {
            stuckTicks++;
            // Commit to one sideways direction for longer to actually clear obstacles
            if (stuckTicks % JIGGLE_COMMIT_TICKS == 0) {
                jiggleDirection = -jiggleDirection;
            }
            // Walk sideways + forward + jump to get around
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(true);
            client.options.leftKey.setPressed(jiggleDirection > 0);
            client.options.rightKey.setPressed(jiggleDirection < 0);
            client.options.sprintKey.setPressed(false);
            MovementHelper.lookAtPoint(player, waypoint, YAW_SPEED * 2, PITCH_SPEED);
        } else {
            if (stuckTicks > 0) stuckTicks = 0;
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            MovementHelper.lookAtPoint(player, waypoint, YAW_SPEED, PITCH_SPEED);

            client.options.forwardKey.setPressed(true);
            if (inWater || needsJump) {
                client.options.jumpKey.setPressed(true);
                client.options.sprintKey.setPressed(false);
            } else {
                client.options.jumpKey.setPressed(false);
                client.options.sprintKey.setPressed(canSprintAhead());
            }
        }
    }

    private boolean canSprintAhead() {
        if (path == null) return false;
        int lookAhead = Math.min(waypointIndex + 4, path.size());
        for (int i = waypointIndex; i < lookAhead; i++) {
            if (i > waypointIndex) {
                Vec3d prev = path.get(i - 1);
                Vec3d wp = path.get(i);
                if (Math.abs(wp.y - prev.y) > 0.5) return false;
            }
        }
        return true;
    }

    private void moveDirectToTarget(MinecraftClient client, ClientPlayerEntity player) {
        boolean hitWall = player.horizontalCollision;

        if (hitWall) {
            stuckTicks++;
            // Commit to one direction for JIGGLE_COMMIT_TICKS before switching
            if (stuckTicks % JIGGLE_COMMIT_TICKS == 0) {
                jiggleDirection = -jiggleDirection;
            }

            // Walk mostly sideways with some forward to go around the obstacle
            boolean goingSideways = stuckTicks > 10;
            client.options.forwardKey.setPressed(!goingSideways);
            client.options.jumpKey.setPressed(true);
            client.options.leftKey.setPressed(jiggleDirection > 0);
            client.options.rightKey.setPressed(jiggleDirection < 0);
            client.options.sprintKey.setPressed(false);

            if (!goingSideways) {
                MovementHelper.lookAtEntity(player, target, YAW_SPEED * 2, PITCH_SPEED);
            }
        } else {
            if (stuckTicks > 0) stuckTicks = 0;
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            MovementHelper.lookAtEntity(player, target, YAW_SPEED, PITCH_SPEED);

            double dist = player.distanceTo(target);
            client.options.forwardKey.setPressed(true);

            if (player.isTouchingWater()) {
                client.options.jumpKey.setPressed(true);
                client.options.sprintKey.setPressed(false);
            } else {
                client.options.sprintKey.setPressed(dist > 6.0);
                client.options.jumpKey.setPressed(false);
            }
        }
    }

    private static String fmtPos(Vec3d v) {
        return String.format("(%.1f,%.1f,%.1f)", v.x, v.y, v.z);
    }

    private static String fmtBlock(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
