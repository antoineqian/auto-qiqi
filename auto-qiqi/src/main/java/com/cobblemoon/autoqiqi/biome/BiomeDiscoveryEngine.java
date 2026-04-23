package com.cobblemoon.autoqiqi.biome;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.ChatUtil;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.WorldTracker;
import com.cobblemoon.autoqiqi.legendary.predict.HomeDefinition;
import com.cobblemoon.autoqiqi.legendary.predict.HomeDefinitionLoader;
import com.cobblemoon.autoqiqi.legendary.predict.LegendTrackerBridge;
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
 * Flow per cycle: /rtp → /fly → ascend to target altitude → fly straight in a random
 * direction while checking biomes. A straight line maximises unique-biome encounters per
 * block flown (no revisits, fast access to distant biome regions). /rtp between cycles
 * provides the spatial randomisation that a spiral would give.
 * <p>
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
        SENDING_TOP,
        ASCENDING,
        FLYING
    }

    // --- Timeouts ---
    private static final long RTP_WAIT_MS = 5000;
    private static final long FLY_RESPONSE_TIMEOUT_MS = 5000;
    private static final long ASCEND_TIMEOUT_MS = 30_000;

    // --- Flight ---
    private static final int BIOME_CHECK_INTERVAL = 20; // ticks
    private static final int ALTITUDE_CHECK_INTERVAL = 40; // ticks
    private static final int STUCK_CHECK_INTERVAL = 40; // ticks (2s)
    /** Sprint-fly speed is ~10.9 blocks/s → ~21.8 blocks in 2s → ~475 sq.
     *  Threshold at 80% speed (17.4 blocks → 304 sq) catches wall-sliding at steep angles. */
    private static final double STUCK_MIN_DISTANCE_SQ = 304.0;

    // --- State ---
    private State state = State.IDLE;
    private boolean enabled = false;
    /** When true, tick() is a no-op even if enabled. Set after reconnect, cleared when auto-hop sends /home. */
    private boolean suspended = false;

    private float flyYaw; // random heading for straight-line flight

    private final Set<String> foundBiomes = new LinkedHashSet<>();
    private final Set<String> discoveredBiomes = new LinkedHashSet<>();

    // Pending retroactive checks: homeName -> (biomeId, tickWhenToCheck)
    private final Map<String, PendingCheck> pendingChecks = new LinkedHashMap<>();
    private boolean wasPausedForChecks = false;
    private static final int RETROACTIVE_CHECK_DELAY_TICKS = 60; // ~3 seconds

    private static final int RETROACTIVE_MAX_RETRIES = 5;
    private record PendingCheck(String biomeId, int checkAtTick, int retries) {}

    private boolean flyRetried;
    private long stateStartMs;
    private int tickCounter;
    private int ascendTicks;

    // Stuck detection
    private Vec3d lastStuckCheckPos;
    private int lastStuckCheckTick;

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
            ChatUtil.msg("§c[Biome]§r Aucun biome cible configure.");
            return;
        }
        enabled = true;
        lastTimerResetMs = System.currentTimeMillis(); // start grace period from now
        foundBiomes.clear();
        discoveredBiomes.clear();
        AutoQiqiClient.log("Biome", "Biome discovery enabled. Targets: " + config.biomeDiscoveryTargets);
        ChatUtil.msg("§a[Biome]§r Recherche activee. Cibles: §f" + String.join(", ", config.biomeDiscoveryTargets));
    }

    public void stop() {
        if (state != State.IDLE) {
            releaseKeys();
            AutoQiqiClient.log("Biome", "Biome discovery stopped (was " + state + ")");
        }
        state = State.IDLE;
        enabled = false;
    }

    /** Suspend biome discovery (e.g. after reconnect). tick() becomes a no-op until unsuspend(). */
    public void suspend() {
        abort();
        suspended = true;
        AutoQiqiClient.logDebug("Biome", "Suspended (waiting for auto-hop /home)");
    }

    /** Resume from suspension (called by auto-hop after sending /home). */
    public void unsuspend() {
        if (suspended) {
            suspended = false;
            AutoQiqiClient.logDebug("Biome", "Unsuspended");
        }
    }

    /** Abort the current cycle but stay enabled so auto-start can resume later. */
    public void abort() {
        if (state != State.IDLE) {
            releaseKeys();
            AutoQiqiClient.log("Biome", "Biome discovery aborted (was " + state + ")");
        }
        transitionTo(State.IDLE);
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
            case SENDING_TOP -> "/top...";
            case ASCENDING -> {
                MinecraftClient client = MinecraftClient.getInstance();
                int y = client.player != null ? (int) client.player.getY() : 0;
                yield "Montee Y=" + y + "/" + AutoQiqiConfig.get().biomeDiscoveryAltitude;
            }
            case FLYING -> {
                int found = foundBiomes.size();
                int total = AutoQiqiConfig.get().biomeDiscoveryTargets.size();
                MinecraftClient c = MinecraftClient.getInstance();
                int y = c.player != null ? (int) c.player.getY() : 0;
                String biomeList = String.join(", ", foundBiomes);
                yield "Vol Y=" + y + "/" + AutoQiqiConfig.get().biomeDiscoveryMaxAltitude
                        + " | " + found + "/" + total
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
                ChatUtil.msg("§c[Biome]§r Erreur: impossible d'activer le vol.");
                transitionTo(State.IDLE);
            }
        } else if (stripped.contains("activé") || stripped.contains("active")) {
            AutoQiqiClient.log("Biome", "Flight enabled, sending /top");
            transitionTo(State.SENDING_TOP);
        }
    }

    // ========================
    // Tick
    // ========================

    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || suspended || client.player == null || client.world == null) return;
        if (client.player.isDead()) {
            if (state != State.IDLE) {
                AutoQiqiClient.log("Biome", "Player died, stopping biome discovery");
                releaseKeys();
                transitionTo(State.IDLE);
            }
            return;
        }

        tickCounter++;

        // Process pending retroactive duplicate checks
        processPendingChecks(client);

        // Auto-start/stop: use LegendTracker live data (same as AutoHop), fall back to global timer
        long remaining = LegendTrackerBridge.getRemainingSeconds();
        if (remaining < 0) {
            remaining = WorldTracker.get().getGlobalRemainingSeconds();
        }
        trackTimerReset(remaining);

        AutoQiqiConfig config = AutoQiqiConfig.get();

        if (state != State.IDLE && remaining >= 0 && remaining <= config.biomeDiscoveryStopThresholdSeconds) {
            AutoQiqiClient.log("Biome", "Timer at " + remaining + "s <= threshold " + config.biomeDiscoveryStopThresholdSeconds + "s, stopping spiral");
            releaseKeys();
            transitionTo(State.IDLE);
            ChatUtil.msg("§e[Biome]§r Spirale arretee (timer " + remaining + "s). Pret pour legendaire.");
            return;
        }

        // Stop if a legendary is being engaged, captured, or player is in battle
        if (state != State.IDLE
                && (com.cobblemoon.autoqiqi.battle.AutoBattleEngine.get().getTarget() != null
                    || com.cobblemoon.autoqiqi.battle.CaptureEngine.get().isActive()
                    || com.cobblemoon.autoqiqi.battle.BattleScreenHelper.isInBattleScreen(client)
                    || com.cobblemoon.autoqiqi.legendary.autohop.AutoHopEngine.get().isDisabled())) {
            AutoQiqiClient.log("Biome", "Legendary engaged/capture/battle active, stopping biome discovery");
            stop();
            ChatUtil.msg("§e[Biome]§r Arret — legendaire en cours. Reactivez manuellement.");
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
            case SENDING_TOP -> tickSendingTop(client);
            case ASCENDING -> tickAscending(client);
            case FLYING -> tickFlying(client);
        }
    }

    // ========================
    // State ticks
    // ========================

    private void tickSendingRtp(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        try {
            com.cobblemoon.autoqiqi.legendary.AutoReconnectEngine.get().suppressMonitoringForMs(15_000);
            ChatUtil.sendCommand(player, "rtp");
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
                AutoQiqiClient.log("Biome", "RTP done at " + (int) player.getX() + ", " + (int) player.getZ());
            }
            transitionTo(State.SENDING_FLY);
        }
    }

    private void tickSendingFly(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        if (player.getAbilities().flying) {
            AutoQiqiClient.log("Biome", "Already flying, skipping /fly");
            transitionTo(State.SENDING_TOP);
            return;
        }

        try {
            ChatUtil.sendCommand(player, "fly");
            AutoQiqiClient.log("Biome", "Sent /fly");
        } catch (Exception e) {
            AutoQiqiClient.log("Biome", "/fly failed: " + e.getMessage());
        }
        transitionTo(State.WAITING_FLY_RESPONSE);
    }

    private void tickWaitingFlyResponse() {
        if (System.currentTimeMillis() - stateStartMs >= FLY_RESPONSE_TIMEOUT_MS) {
            AutoQiqiClient.log("Biome", "Timeout waiting for /fly response, aborting");
            ChatUtil.msg("§c[Biome]§r Timeout /fly, abandon du cycle.");
            transitionTo(State.IDLE);
        }
    }

    private static final long TOP_WAIT_MS = 2000;

    private void tickSendingTop(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        // Send /top on first tick of this state
        if (System.currentTimeMillis() - stateStartMs < 50) {
            try {
                ChatUtil.sendCommand(player, "top");
                AutoQiqiClient.log("Biome", "Sent /top");
            } catch (Exception e) {
                AutoQiqiClient.log("Biome", "/top failed: " + e.getMessage());
            }
            return;
        }

        // Wait a bit for the teleport to complete, then start ascending
        if (System.currentTimeMillis() - stateStartMs >= TOP_WAIT_MS) {
            transitionTo(State.ASCENDING);
        }
    }

    private void tickAscending(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        ascendTicks++;

        // Engage flight directly via abilities (works even in water, unlike double-tap jump)
        if (!player.getAbilities().flying) {
            if (player.getAbilities().allowFlying) {
                player.getAbilities().flying = true;
                player.sendAbilitiesUpdate();
            }
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            return;
        }

        int targetAlt = AutoQiqiConfig.get().biomeDiscoveryAltitude;

        if (player.getY() >= targetAlt) {
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            // Pick a random heading for straight-line flight
            flyYaw = (float) (Math.random() * 360.0 - 180.0);
            player.setYaw(flyYaw);
            player.setPitch(0);
            AutoQiqiClient.log("Biome", "Reached altitude " + (int) player.getY() + ", flying heading " + (int) flyYaw + "°");
            transitionTo(State.FLYING);
            return;
        }

        if (System.currentTimeMillis() - stateStartMs >= ASCEND_TIMEOUT_MS) {
            AutoQiqiClient.log("Biome", "Ascend timeout, aborting");
            releaseKeys();
            ChatUtil.msg("§c[Biome]§r Timeout montee, abandon du cycle.");
            transitionTo(State.IDLE);
            return;
        }

        // Hold jump to ascend in fly mode
        client.options.jumpKey.setPressed(true);
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    private void tickFlying(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        AutoQiqiConfig config = AutoQiqiConfig.get();

        // Biome check
        if (tickCounter % BIOME_CHECK_INTERVAL == 0) {
            checkBiome(client, player, config);
        }

        // Pause flight while retroactive checks are pending — stay hovering in place
        // so Legend Tracker has time to write the world to the file.
        if (!pendingChecks.isEmpty()) {
            if (!wasPausedForChecks) {
                AutoQiqiClient.log("Biome", "Pausing flight for retroactive checks (" + pendingChecks.size() + " pending)");
                wasPausedForChecks = true;
            }
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            return;
        }
        if (wasPausedForChecks) {
            AutoQiqiClient.log("Biome", "Retroactive checks done, resuming flight");
            wasPausedForChecks = false;
            // Reset stuck detection — position is stale after hovering
            lastStuckCheckPos = null;
            lastStuckCheckTick = tickCounter;
        }

        // Altitude: keep ascending toward maxAltitude while flying
        int maxAlt = config.biomeDiscoveryMaxAltitude;
        if (tickCounter % ALTITUDE_CHECK_INTERVAL == 0) {
            if (player.getY() < maxAlt - 2) {
                client.options.jumpKey.setPressed(true);
            } else {
                client.options.jumpKey.setPressed(false);
            }
        }

        // Stuck detection: if barely moved horizontally, pick a new heading
        if (tickCounter - lastStuckCheckTick >= STUCK_CHECK_INTERVAL) {
            if (lastStuckCheckPos != null) {
                double dx = player.getX() - lastStuckCheckPos.x;
                double dz = player.getZ() - lastStuckCheckPos.z;
                double distSq = dx * dx + dz * dz;
                if (distSq < STUCK_MIN_DISTANCE_SQ) {
                    // Stuck — turn roughly perpendicular (60-120°), randomly left or right
                    float turn = 60.0f + (float) (Math.random() * 60.0);
                    if (Math.random() < 0.5) turn = -turn;
                    flyYaw = flyYaw + turn;
                    if (flyYaw > 180) flyYaw -= 360;
                    if (flyYaw < -180) flyYaw += 360;
                    AutoQiqiClient.log("Biome", "Stuck detected (moved² " + String.format("%.1f", distSq)
                            + "), new heading " + (int) flyYaw + "°");
                    ChatUtil.msg("§e[Biome]§r Mur detecte, nouveau cap: " + (int) flyYaw + "°");
                }
            }
            lastStuckCheckPos = player.getPos();
            lastStuckCheckTick = tickCounter;
        }

        // Maintain heading and fly forward
        player.setYaw(flyYaw);
        player.setPitch(0);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(true);
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
                // Prefer LegendTracker's world (always set after timer parse),
                // fall back to WorldTracker (may be null early in session).
                String world = LegendTrackerBridge.getCurrentWorld();
                if (world == null) world = WorldTracker.get().getCurrentWorld();
                String homeName = buildHomeName(id, world);

                try {
                    ChatUtil.sendCommandViaChat(player, "sethome " + homeName);
                    AutoQiqiClient.log("Biome", "Target biome found: " + id + " at "
                            + (int) player.getX() + ", " + (int) player.getZ()
                            + " -> /sethome " + homeName);
                    ChatUtil.msg("§a[Biome]§r Biome trouve: §f" + id + "§r a ("
                            + (int) player.getX() + ", " + (int) player.getZ()
                            + ") -> §e" + homeName);
                } catch (Exception e) {
                    AutoQiqiClient.log("Biome", "/sethome failed: " + e.getMessage());
                }

                // Schedule retroactive check — Legend Tracker will write the world to the file
                pendingChecks.put(homeName, new PendingCheck(id, tickCounter + RETROACTIVE_CHECK_DELAY_TICKS, 0));
                AutoQiqiClient.log("Biome", "Scheduled retroactive check for " + homeName + " in " + RETROACTIVE_CHECK_DELAY_TICKS + " ticks");

                checkAllTargetsFound(config);
            }
        } catch (Exception e) {
            AutoQiqiClient.log("Biome", "Biome check failed: " + e.getMessage());
        }
    }

    /**
     * After /sethome, Legend Tracker writes the home with its world to legendtracker.properties.
     * We reload the file and check if another home already covers the same biome in the same world.
     * If so, send /delhome to remove the duplicate.
     */
    private void processPendingChecks(MinecraftClient client) {
        if (pendingChecks.isEmpty()) return;

        // Collect retries to add after iteration (can't modify map while iterating)
        Map<String, PendingCheck> retries = null;

        Iterator<Map.Entry<String, PendingCheck>> it = pendingChecks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingCheck> entry = it.next();
            if (tickCounter < entry.getValue().checkAtTick()) continue;

            String homeName = entry.getKey();
            String biomeId = entry.getValue().biomeId();
            int retryCount = entry.getValue().retries();
            it.remove();

            // Primary: read from file (Legend Tracker writes immediately on /sethome)
            HomeDefinitionLoader.reload();
            List<HomeDefinition> homes = HomeDefinitionLoader.get();

            // Find the home we just created to read its world
            String savedWorld = null;
            for (HomeDefinition h : homes) {
                if (h.key().equalsIgnoreCase(homeName)) {
                    savedWorld = h.world();
                    break;
                }
            }

            // Fallback: try LegendTrackerBridge in-memory data
            if ((savedWorld == null || savedWorld.isEmpty()) && LegendTrackerBridge.isAvailable()) {
                List<HomeDefinition> bridgeHomes = LegendTrackerBridge.getAllHomesAsDefinitions();
                for (HomeDefinition h : bridgeHomes) {
                    if (h.key().equalsIgnoreCase(homeName)) {
                        savedWorld = h.world();
                        AutoQiqiClient.log("Biome", "Retroactive check: found " + homeName + " via LTBridge (world=" + savedWorld + ")");
                        homes = bridgeHomes; // use bridge list for duplicate check too
                        break;
                    }
                }
            }

            if (savedWorld == null || savedWorld.isEmpty()) {
                // Diagnostic: log all home keys to see what's in the file
                if (retryCount == 0) {
                    StringBuilder keys = new StringBuilder();
                    for (HomeDefinition h : homes) {
                        if (keys.length() > 0) keys.append(", ");
                        keys.append(h.key());
                        if (keys.length() > 200) { keys.append("..."); break; }
                    }
                    AutoQiqiClient.log("Biome", "Retroactive check: looking for '" + homeName + "' in " + homes.size()
                            + " homes: [" + keys + "]");
                }
                if (retryCount < RETROACTIVE_MAX_RETRIES) {
                    AutoQiqiClient.log("Biome", "Retroactive check: home " + homeName + " not found/no world yet"
                            + ", retry " + (retryCount + 1) + "/" + RETROACTIVE_MAX_RETRIES);
                    if (retries == null) retries = new LinkedHashMap<>();
                    retries.put(homeName, new PendingCheck(biomeId, tickCounter + RETROACTIVE_CHECK_DELAY_TICKS, retryCount + 1));
                } else {
                    AutoQiqiClient.log("Biome", "Retroactive check: home " + homeName + " not found after " + RETROACTIVE_MAX_RETRIES + " retries, giving up");
                }
                continue;
            }

            // Check if the home name has the correct world prefix (fixes X_ fallback when world was unknown at sethome time).
            // Since we pause flight during retroactive checks, the player is still near the original position
            // — safe to rename via /delhome + /sethome.
            String originalName = homeName;
            String correctName = buildHomeName(biomeId, savedWorld);
            if (!homeName.equals(correctName)) {
                AutoQiqiClient.log("Biome", "Retroactive check: renaming " + homeName + " -> " + correctName
                        + " (world=" + savedWorld + ")");
                if (client.player != null) {
                    try {
                        ChatUtil.sendCommandViaChat(client.player, "delhome " + homeName);
                        ChatUtil.sendCommandViaChat(client.player, "sethome " + correctName);
                        ChatUtil.msg("§e[Biome]§r Renommage: §f" + homeName + "§r -> §e" + correctName);
                        homeName = correctName;
                    } catch (Exception e) {
                        AutoQiqiClient.log("Biome", "Rename failed: " + e.getMessage());
                    }
                }
            }

            // Check if another home already covers this biome in the same world
            // Skip both our current name and original name (in case of rename, the old entry is still in the stale list)
            boolean alreadyCovered = false;
            for (HomeDefinition h : homes) {
                if (h.key().equalsIgnoreCase(homeName)) continue;
                if (h.key().equalsIgnoreCase(originalName)) continue;
                if (h.biomeId().equals(biomeId) && h.world().equalsIgnoreCase(savedWorld)) {
                    alreadyCovered = true;
                    AutoQiqiClient.log("Biome", "Retroactive check: " + biomeId + " already covered by " + h.key() + " in " + savedWorld);
                    break;
                }
            }

            if (alreadyCovered) {
                AutoQiqiClient.log("Biome", "Retroactive check: deleting " + homeName + " (duplicate for " + biomeId + " in " + savedWorld + ")");
                ChatUtil.msg("§e[Biome]§r " + biomeId + " deja couvert dans §f" + savedWorld + "§r -> /delhome " + homeName);
                if (client.player != null) {
                    try {
                        ChatUtil.sendCommandViaChat(client.player, "delhome " + homeName);
                    } catch (Exception e) {
                        AutoQiqiClient.log("Biome", "/delhome failed: " + e.getMessage());
                    }
                }
            } else {
                AutoQiqiClient.log("Biome", "Retroactive check: " + homeName + " is new for " + biomeId + " in " + savedWorld + ", keeping");
            }
        }

        if (retries != null) {
            pendingChecks.putAll(retries);
        }
    }

    /** Builds a readable home name like "L_jungle_d" from biome ID + world, max 16 chars. */
    private static String buildHomeName(String biomeId, String world) {
        int colon = biomeId.indexOf(':');
        String biomeName = colon >= 0 ? biomeId.substring(colon + 1) : biomeId;

        String wAbbrev = worldAbbrev(world);
        // Budget: world + "_" (1) + biome + "_d" (2)
        int maxBiomeLen = 16 - 3 - wAbbrev.length();

        if (biomeName.length() > maxBiomeLen) {
            biomeName = biomeName.substring(0, maxBiomeLen);
            if (biomeName.endsWith("_")) {
                biomeName = biomeName.substring(0, biomeName.length() - 1);
            }
        }

        return wAbbrev.toUpperCase() + "_" + biomeName.toLowerCase() + "_d";
    }

    private static String worldAbbrev(String world) {
        if (world == null) return "x";
        String w = world.toLowerCase();
        if (w.contains("ultra") && w.contains("lune")) return "ul";
        if (w.contains("ultra") && w.contains("soleil")) return "us";
        if (w.contains("lune")) return "l";
        if (w.contains("soleil")) return "s";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(w);
        if (m.find()) return "r" + m.group();
        return w.length() >= 2 ? w.substring(0, 2) : w;
    }

    private void checkAllTargetsFound(AutoQiqiConfig config) {
        if (foundBiomes.containsAll(config.biomeDiscoveryTargets)) {
            AutoQiqiClient.log("Biome", "All target biomes found! " + foundBiomes);
            releaseKeys();
            transitionTo(State.IDLE);
            enabled = false;
            ChatUtil.msg("§a[Biome]§r Tous les biomes trouves ! Recherche terminee.");
        }
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

        // Don't start if a legendary is being engaged, captured, or auto-hop disabled (legendary event)
        if (com.cobblemoon.autoqiqi.battle.AutoBattleEngine.get().getTarget() != null) return false;
        if (com.cobblemoon.autoqiqi.battle.CaptureEngine.get().isActive()) return false;
        if (com.cobblemoon.autoqiqi.legendary.autohop.AutoHopEngine.get().isDisabled()) return false;
        if (com.cobblemoon.autoqiqi.battle.BattleScreenHelper.isInBattleScreen(
                net.minecraft.client.MinecraftClient.getInstance())) return false;

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
        if (newState == State.ASCENDING) {
            ascendTicks = 0;
        }
        if (newState == State.FLYING) {
            lastStuckCheckPos = null;
            lastStuckCheckTick = tickCounter;
        }
    }

    private void releaseKeys() {
        MinecraftClient client = MinecraftClient.getInstance();
        MovementHelper.releaseMovementKeys(client);
    }

}
