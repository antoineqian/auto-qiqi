package com.cobblemoon.autoqiqi.biome;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.WorldTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.biome.Biome;

import java.util.*;

/**
 * Automated biome discovery engine. Runs during idle time between legendary events.
 * <p>
 * Flow per cycle: /rtp → /fly → ascend to target altitude → spiral outward checking biomes.
 * When a target biome is found, sends /sethome autodisco_N. Stops when the global timer
 * drops to the configured threshold (default 3:15) or all targets are found.
 */
public class BiomeDiscoveryEngine {
    private static final BiomeDiscoveryEngine INSTANCE = new BiomeDiscoveryEngine();
    public static BiomeDiscoveryEngine get() { return INSTANCE; }

    private enum State {
        IDLE,
        SENDING_RTP,
        WAITING_RTP,
        SENDING_FLY,
        WAITING_FLY_RESPONSE,
        ASCENDING,
        SPIRALING
    }

    // --- Timeouts ---
    private static final long RTP_WAIT_MS = 5000;
    private static final long FLY_RESPONSE_TIMEOUT_MS = 5000;
    private static final long ASCEND_TIMEOUT_MS = 30_000;

    // --- Spiral ---
    private static final double ARC_STEP = 16.0;
    private static final int WAYPOINT_BATCH = 50;
    private static final int BIOME_CHECK_INTERVAL = 20; // ticks
    private static final int ALTITUDE_CHECK_INTERVAL = 40; // ticks

    // --- State ---
    private State state = State.IDLE;
    private boolean enabled = false;

    private double originX, originZ;
    private double currentTheta;
    private List<Vec3d> waypoints = new ArrayList<>();
    private int waypointIndex;

    private int nextHomeIndex = 1;
    private final Set<String> foundBiomes = new LinkedHashSet<>();
    private final Set<String> discoveredBiomes = new LinkedHashSet<>();

    private boolean flyRetried;
    private long stateStartMs;
    private int tickCounter;

    // Timer tracking for auto-start
    private long lastGlobalRemaining = -1;
    private long lastTimerResetMs = 0;

    private BiomeDiscoveryEngine() {}

    // ========================
    // Public API
    // ========================

    public boolean isActive() {
        return enabled && state != State.IDLE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void start() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (config.biomeDiscoveryTargets.isEmpty()) {
            msg("§c[Biome]§r Aucun biome cible configure.");
            return;
        }
        enabled = true;
        lastTimerResetMs = System.currentTimeMillis(); // start grace period from now
        discoveredBiomes.clear();
        AutoQiqiClient.log("Biome", "Biome discovery enabled. Targets: " + config.biomeDiscoveryTargets);
        msg("§a[Biome]§r Recherche activee. Cibles: §f" + String.join(", ", config.biomeDiscoveryTargets));
    }

    public void stop() {
        if (state != State.IDLE) {
            releaseKeys();
            AutoQiqiClient.log("Biome", "Biome discovery stopped (was " + state + ")");
        }
        state = State.IDLE;
        enabled = false;
        waypoints.clear();
    }

    public Set<String> getFoundBiomes() {
        return Collections.unmodifiableSet(foundBiomes);
    }

    public String getStatusText() {
        if (!enabled) return "OFF";
        return switch (state) {
            case IDLE -> "En attente du timer...";
            case SENDING_RTP, WAITING_RTP -> "Teleportation /rtp...";
            case SENDING_FLY, WAITING_FLY_RESPONSE -> "Activation du vol...";
            case ASCENDING -> {
                MinecraftClient client = MinecraftClient.getInstance();
                int y = client.player != null ? (int) client.player.getY() : 0;
                yield "Montee Y=" + y + "/" + AutoQiqiConfig.get().biomeDiscoveryAltitude;
            }
            case SPIRALING -> {
                double r = getCurrentRadius();
                int found = foundBiomes.size();
                int total = AutoQiqiConfig.get().biomeDiscoveryTargets.size();
                String biomeList = String.join(", ", foundBiomes);
                yield "Spirale r=" + (int) r + "m | " + found + "/" + total
                        + (biomeList.isEmpty() ? "" : " | " + biomeList);
            }
        };
    }

    /** Called from ChatMessageHandler when a "Mode vol" message is detected. */
    public void onFlightChatMessage(String stripped) {
        if (state != State.WAITING_FLY_RESPONSE) return;

        if (stripped.contains("désactivé") || stripped.contains("desactivé") || stripped.contains("desactive")) {
            // Flight was on, we just toggled it off. Retry once.
            if (!flyRetried) {
                flyRetried = true;
                AutoQiqiClient.log("Biome", "Flight was already on (désactivé), retrying /fly");
                transitionTo(State.SENDING_FLY);
            } else {
                AutoQiqiClient.log("Biome", "Flight toggle failed after retry, aborting");
                msg("§c[Biome]§r Erreur: impossible d'activer le vol.");
                transitionTo(State.IDLE);
            }
        } else if (stripped.contains("activé") || stripped.contains("active")) {
            AutoQiqiClient.log("Biome", "Flight enabled, ascending");
            transitionTo(State.ASCENDING);
        }
    }

    // ========================
    // Tick
    // ========================

    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || client.player == null || client.world == null) return;
        if (client.player.isDead()) {
            if (state != State.IDLE) {
                AutoQiqiClient.log("Biome", "Player died, stopping biome discovery");
                releaseKeys();
                transitionTo(State.IDLE);
            }
            return;
        }

        tickCounter++;

        // Auto-start/stop based on global timer
        long remaining = WorldTracker.get().getGlobalRemainingSeconds();
        trackTimerReset(remaining);

        AutoQiqiConfig config = AutoQiqiConfig.get();

        if (state != State.IDLE && remaining >= 0 && remaining <= config.biomeDiscoveryStopThresholdSeconds) {
            AutoQiqiClient.log("Biome", "Timer at " + remaining + "s <= threshold " + config.biomeDiscoveryStopThresholdSeconds + "s, stopping spiral");
            releaseKeys();
            transitionTo(State.IDLE);
            msg("§e[Biome]§r Spirale arretee (timer " + remaining + "s). Pret pour legendaire.");
            return;
        }

        if (state == State.IDLE && shouldAutoStart(remaining, config)) {
            AutoQiqiClient.log("Biome", "Auto-starting biome discovery (timer=" + remaining + "s)");
            transitionTo(State.SENDING_RTP);
        }

        switch (state) {
            case IDLE -> {}
            case SENDING_RTP -> tickSendingRtp(client);
            case WAITING_RTP -> tickWaitingRtp(client);
            case SENDING_FLY -> tickSendingFly(client);
            case WAITING_FLY_RESPONSE -> tickWaitingFlyResponse();
            case ASCENDING -> tickAscending(client);
            case SPIRALING -> tickSpiraling(client);
        }
    }

    // ========================
    // State ticks
    // ========================

    private void tickSendingRtp(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        try {
            player.networkHandler.sendChatCommand("rtp");
            AutoQiqiClient.log("Biome", "Sent /rtp");
        } catch (Exception e) {
            AutoQiqiClient.log("Biome", "/rtp failed: " + e.getMessage());
        }
        transitionTo(State.WAITING_RTP);
    }

    private void tickWaitingRtp(MinecraftClient client) {
        if (System.currentTimeMillis() - stateStartMs >= RTP_WAIT_MS) {
            ClientPlayerEntity player = client.player;
            if (player != null) {
                originX = player.getX();
                originZ = player.getZ();
                currentTheta = 0;
                waypoints.clear();
                waypointIndex = 0;
                AutoQiqiClient.log("Biome", "RTP done, origin at " + (int) originX + ", " + (int) originZ);
            }
            transitionTo(State.SENDING_FLY);
        }
    }

    private void tickSendingFly(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        try {
            player.networkHandler.sendChatCommand("fly");
            AutoQiqiClient.log("Biome", "Sent /fly");
        } catch (Exception e) {
            AutoQiqiClient.log("Biome", "/fly failed: " + e.getMessage());
        }
        transitionTo(State.WAITING_FLY_RESPONSE);
    }

    private void tickWaitingFlyResponse() {
        if (System.currentTimeMillis() - stateStartMs >= FLY_RESPONSE_TIMEOUT_MS) {
            AutoQiqiClient.log("Biome", "Timeout waiting for /fly response, aborting");
            msg("§c[Biome]§r Timeout /fly, abandon du cycle.");
            transitionTo(State.IDLE);
        }
    }

    private void tickAscending(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        int targetAlt = AutoQiqiConfig.get().biomeDiscoveryAltitude;

        if (player.getY() >= targetAlt) {
            client.options.jumpKey.setPressed(false);
            AutoQiqiClient.log("Biome", "Reached altitude " + (int) player.getY() + ", starting spiral");
            transitionTo(State.SPIRALING);
            return;
        }

        if (System.currentTimeMillis() - stateStartMs >= ASCEND_TIMEOUT_MS) {
            AutoQiqiClient.log("Biome", "Ascend timeout, aborting");
            releaseKeys();
            msg("§c[Biome]§r Timeout montee, abandon du cycle.");
            transitionTo(State.IDLE);
            return;
        }

        // Hold jump to fly up, double-tap to start flying
        client.options.jumpKey.setPressed(true);
    }

    private void tickSpiraling(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        AutoQiqiConfig config = AutoQiqiConfig.get();

        // Biome check
        if (tickCounter % BIOME_CHECK_INTERVAL == 0) {
            checkBiome(client, player, config);
        }

        // Altitude maintenance
        if (tickCounter % ALTITUDE_CHECK_INTERVAL == 0) {
            if (player.getY() < config.biomeDiscoveryAltitude - 2) {
                client.options.jumpKey.setPressed(true);
            } else {
                client.options.jumpKey.setPressed(false);
            }
        }

        // Follow waypoints
        if (waypoints.isEmpty() || waypointIndex >= waypoints.size()) {
            computeNextWaypoints(config);
        }

        if (waypointIndex < waypoints.size()) {
            Vec3d target = waypoints.get(waypointIndex);
            double dx = target.x - player.getX();
            double dz = target.z - player.getZ();
            double distXZ = Math.sqrt(dx * dx + dz * dz);

            if (distXZ < 5.0) {
                waypointIndex++;
            } else {
                MovementHelper.lookAtPoint(player, target, 8f, 4f);
                client.options.forwardKey.setPressed(true);
                client.options.sprintKey.setPressed(true);
            }
        }
    }

    // ========================
    // Biome detection
    // ========================

    private void checkBiome(MinecraftClient client, ClientPlayerEntity player, AutoQiqiConfig config) {
        if (client.world == null) return;

        try {
            RegistryEntry<Biome> biomeEntry = client.world.getBiome(player.getBlockPos());
            Optional<RegistryKey<Biome>> key = biomeEntry.getKey();
            if (key.isEmpty()) return;
            String id = key.get().getValue().toString();
            discoveredBiomes.add(id);

            if (config.biomeDiscoveryTargets.contains(id) && !foundBiomes.contains(id)) {
                foundBiomes.add(id);
                int homeIdx = nextHomeIndex++;
                String homeName = "autodisco_" + homeIdx;

                try {
                    player.networkHandler.sendChatCommand("sethome " + homeName);
                    AutoQiqiClient.log("Biome", "Target biome found: " + id + " at "
                            + (int) player.getX() + ", " + (int) player.getZ()
                            + " -> /sethome " + homeName);
                    msg("§a[Biome]§r Biome trouve: §f" + id + "§r a ("
                            + (int) player.getX() + ", " + (int) player.getZ()
                            + ") -> §e" + homeName);
                } catch (Exception e) {
                    AutoQiqiClient.log("Biome", "/sethome failed: " + e.getMessage());
                }

                // Check if all targets found
                if (foundBiomes.containsAll(config.biomeDiscoveryTargets)) {
                    AutoQiqiClient.log("Biome", "All target biomes found! " + foundBiomes);
                    releaseKeys();
                    transitionTo(State.IDLE);
                    enabled = false;
                    msg("§a[Biome]§r Tous les biomes trouves ! Recherche terminee.");
                }
            }
        } catch (Exception e) {
            AutoQiqiClient.log("Biome", "Biome check failed: " + e.getMessage());
        }
    }

    // ========================
    // Spiral math
    // ========================

    private void computeNextWaypoints(AutoQiqiConfig config) {
        waypoints.clear();
        waypointIndex = 0;
        double b = config.biomeDiscoverySpiralSpacing / (2.0 * Math.PI);

        for (int i = 0; i < WAYPOINT_BATCH; i++) {
            double r = b * currentTheta;
            double x = originX + r * Math.cos(currentTheta);
            double z = originZ + r * Math.sin(currentTheta);
            waypoints.add(new Vec3d(x, config.biomeDiscoveryAltitude, z));

            double denominator = Math.sqrt(r * r + b * b);
            if (denominator < 1.0) denominator = 1.0;
            currentTheta += ARC_STEP / denominator;
        }
    }

    private double getCurrentRadius() {
        double b = AutoQiqiConfig.get().biomeDiscoverySpiralSpacing / (2.0 * Math.PI);
        return b * currentTheta;
    }

    // ========================
    // Timer auto-start logic
    // ========================

    private void trackTimerReset(long remaining) {
        if (lastGlobalRemaining <= 0 && remaining > 0) {
            lastTimerResetMs = System.currentTimeMillis();
            AutoQiqiClient.log("Biome", "Global timer reset detected (" + remaining + "s). Grace period started.");
        }
        lastGlobalRemaining = remaining;
    }

    private boolean shouldAutoStart(long remaining, AutoQiqiConfig config) {
        if (!config.biomeDiscoveryEnabled) return false;
        if (remaining < 0) return false; // timer unknown
        if (remaining <= config.biomeDiscoveryStopThresholdSeconds) return false;
        if (allTargetsFound(config)) return false;

        long elapsed = System.currentTimeMillis() - lastTimerResetMs;
        return elapsed >= config.biomeDiscoveryGraceSeconds * 1000L;
    }

    private boolean allTargetsFound(AutoQiqiConfig config) {
        return !config.biomeDiscoveryTargets.isEmpty()
                && foundBiomes.containsAll(config.biomeDiscoveryTargets);
    }

    // ========================
    // Helpers
    // ========================

    private void transitionTo(State newState) {
        AutoQiqiClient.log("Biome", "State: " + state + " -> " + newState);
        state = newState;
        stateStartMs = System.currentTimeMillis();

        if (newState == State.SENDING_FLY) {
            flyRetried = false;
        }
    }

    private void releaseKeys() {
        MinecraftClient client = MinecraftClient.getInstance();
        MovementHelper.releaseMovementKeys(client);
    }

    private static void msg(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), false);
        }
    }
}
