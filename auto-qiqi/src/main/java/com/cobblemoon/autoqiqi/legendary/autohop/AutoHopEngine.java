package com.cobblemoon.autoqiqi.legendary.autohop;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.WorldTracker;
import com.cobblemoon.autoqiqi.legendary.predict.LegendTrackerBridge;
import com.cobblemoon.autoqiqi.legendary.predict.LegendTrackerBridge.HomePrediction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;

import java.util.*;

/**
 * Auto-hop rotation engine — simplified to work with LegendTracker's predictions.
 *
 * Flow:
 * 1. Query LegendTrackerBridge for all home predictions
 * 2. Visit only homes whose world needs confirmation (stale timer ⚠)
 * 3. After all confirmations, read fresh predictions from LT
 * 4. Teleport to the best EV home
 * 5. Send /pk reset to clear cache
 */
public class AutoHopEngine {

    /** Fallback timeout if "Téléportation terminée" is never received. */
    private static final long TELEPORT_FALLBACK_TICKS = 10 * 20;
    /** Delay after teleport confirmation before moving to next step. */
    private static final long POST_TELEPORT_DELAY_TICKS = 30; // 1.5s
    /** Time to wait after arriving at a stale home for LT to refresh its data. */
    private static final long SETTLE_DELAY_TICKS = 3 * 20; // 3s
    /** Timeout waiting for TPA alt account to accept. */
    private static final long TPA_TIMEOUT_TICKS = 15 * 20;

    /** Max fallback attempts if best home EV drops after arrival. */
    private static final int MAX_FALLBACK_ATTEMPTS = 3;

    public enum State {
        IDLE,
        // Phase 1: confirm stale homes
        CONFIRM_TELEPORTING,
        CONFIRM_WAITING_TP,
        CONFIRM_SETTLING,
        // Phase 2: go to best home, then verify
        BEST_TELEPORTING,
        BEST_WAITING_TP,
        BEST_SETTLING,      // wait for LT to see actual weather
        // Phase 3: reset cache
        SENDING_RESET,
        // Optional: TPA
        SENDING_TPA,
        WAITING_TPA
    }

    private State state = State.IDLE;
    private long tickCount = 0;
    private long stateEnteredTick = 0;

    private boolean disabled = false;
    private long reEnableAtMs = 0;
    private boolean forceRotation = false;

    /** True once rotation has been triggered for the current cycle. Reset when timer goes above threshold. */
    private boolean firedThisCycle = false;

    /** Number of completed rotations since last reconnect (for periodic cache flush). */
    private int rotationsSinceReconnect = 0;
    /** True when a reconnect was due but skipped because the player was disabled (e.g. legendary battle). */
    private boolean reconnectDeferred = false;

    /** Safety teleport thresholds (seconds). Re-sends /home to best home as a safeguard. */
    private static final int[] SAFETY_TP_THRESHOLDS = {90, 60, 30};
    /** Tracks which safety teleports have already fired this cycle. */
    private final boolean[] safetyTpFired = new boolean[SAFETY_TP_THRESHOLDS.length];
    /** Grace period after epoch change — old cycle's remaining=0 may still be reported briefly. */
    private static final long EPOCH_GRACE_MS = 10_000;
    private long lastEpochChangeMs = 0;

    /** Singleton — must be declared after all static fields it depends on. */
    private static final AutoHopEngine INSTANCE = new AutoHopEngine();

    /** Homes that need visiting to confirm weather/time. */
    private List<HomePrediction> confirmationHomes;
    private int confirmIndex;

    /** Best home chosen after confirmation phase. */
    private HomePrediction bestHome;
    /** EV of bestHome at the time we chose it (before teleporting). */
    private double bestHomeOriginalEv;
    /** How many fallback attempts we've made this rotation. */
    private int fallbackCount;

    /** Round-robin index for fallback mode (no LegendTracker). Persists across rotations. */
    private int roundRobinIndex = 0;
    /** True when current rotation is using round-robin fallback instead of LegendTracker. */
    private boolean roundRobinMode = false;

    /** Actual spawn probabilities from server Spawn Info at the best home (ground truth).
     *  Captured during BEST_WAITING_TP / BEST_SETTLING to cross-check LT's prediction. */
    private Map<String, Double> bestHomeSpawnInfo = null;

    private String pendingCommand;
    private long commandExecuteAtTick;

    private AutoHopEngine() {
        // Listen for LT epoch changes (new legendary cycle) to reliably reset the cycle flag.
        // This covers the case where the timer jumps from 0 to ≤threshold without ever
        // going above threshold, which would otherwise leave firedThisCycle stuck.
        LegendTrackerBridge.addChangeListener(this::onNewCycle);
    }

    public static AutoHopEngine get() { return INSTANCE; }

    private void onNewCycle() {
        long remaining = LegendTrackerBridge.getRemainingSeconds();
        int threshold = AutoQiqiConfig.get().autoHopThresholdSeconds;

        // If we already fired this cycle and the timer is still under threshold,
        // this epoch change was likely caused by our own /nextleg commands or a
        // timer data refresh — NOT a genuine new 15-minute legendary cycle.
        // A genuine new cycle would have remaining well above threshold (~900s).
        if (firedThisCycle && remaining >= 0 && remaining <= threshold) {
            AutoQiqiClient.logDebug("AutoHop", "LT epoch changed but remaining=" + remaining
                    + "s still ≤ threshold=" + threshold + "s — NOT resetting cycle flag (data refresh, not new cycle)");
            lastEpochChangeMs = System.currentTimeMillis();
            return;
        }

        AutoQiqiClient.logDebug("AutoHop", "LT epoch changed (remaining=" + remaining + "s) — resetting cycle");
        firedThisCycle = false;
        lastEpochChangeMs = System.currentTimeMillis();
        if (state == State.IDLE) {
            bestHome = null; // clear stale bestHome so safety TPs wait for this cycle's rotation
        }
        java.util.Arrays.fill(safetyTpFired, false);
        // Also auto re-enable if disabled from a previous legendary encounter
        if (disabled && reEnableAtMs == 0) {
            AutoQiqiClient.logDebug("AutoHop", "New epoch — auto re-enabling");
            msg("§a[Auto-Hop]§r Nouveau cycle — réactivation automatique");
            setDisabled(false);
        }
    }

    public boolean isActive() { return state != State.IDLE; }

    public boolean isDisabled() { return disabled; }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
        if (disabled) {
            reEnableAtMs = 0;
            if (isActive()) {
                AutoQiqiClient.logDebug("AutoHop", "Disabled while active — aborting rotation");
                abort();
            }
        }
        AutoQiqiClient.logDebug("AutoHop", "Auto-hop " + (disabled ? "DISABLED" : "ENABLED"));
    }

    public void scheduleReEnable(int delaySeconds) {
        if (!disabled) return;
        reEnableAtMs = System.currentTimeMillis() + delaySeconds * 1000L;
        AutoQiqiClient.logDebug("AutoHop", "Auto-hop re-enable scheduled in " + delaySeconds + "s");
        msg("§6[Auto-Hop]§r Réactivation dans " + delaySeconds + "s (loot)");
    }

    public State getState() { return state; }

    public String getStateDisplay() {
        return switch (state) {
            case IDLE -> "";
            case CONFIRM_TELEPORTING, CONFIRM_WAITING_TP, CONFIRM_SETTLING ->
                    "Auto-hop: confirm " + currentConfirmName() + " (" + (confirmIndex + 1) + "/" + confirmationHomes.size() + ")";
            case BEST_TELEPORTING, BEST_WAITING_TP, BEST_SETTLING ->
                    "Auto-hop: → " + (bestHome != null ? bestHome.name() : "?");
            case SENDING_RESET -> "Auto-hop: /pk reset";
            case SENDING_TPA, WAITING_TPA -> "Auto-hop: tpahere alt";
        };
    }

    private String currentConfirmName() {
        if (confirmationHomes == null || confirmIndex >= confirmationHomes.size()) return "?";
        return confirmationHomes.get(confirmIndex).name();
    }

    // ========================
    // Entry point
    // ========================

    public void startRotation() { startRotation(false); }

    public void startRotationForced() { startRotation(true); }

    private void startRotation(boolean forced) {
        if (disabled) {
            AutoQiqiClient.logDebug("AutoHop", "startRotation ignored: disabled");
            return;
        }
        if (state != State.IDLE) {
            AutoQiqiClient.logDebug("AutoHop", "startRotation ignored: already in " + state);
            return;
        }
        if (!forced && isLegendaryNearby()) {
            AutoQiqiClient.logDebug("AutoHop", "startRotation ignored: legendary nearby");
            return;
        }
        forceRotation = forced;

        // If LegendTracker is not available, poll /nextleg at each home to compute EV
        if (!LegendTrackerBridge.isAvailable()) {
            startPollRotation();
            return;
        }
        roundRobinMode = false;

        // Get predictions, filtered to "auto*" homes only
        List<HomePrediction> all = filterAutoHomes(LegendTrackerBridge.getFreshPredictions());
        if (all.isEmpty()) {
            msg("§c[Auto-Hop]§r Aucune prédiction (homes 'auto*' introuvables)");
            return;
        }

        // Deduplicate by world — only need to visit one home per stale world for confirmation
        Set<String> seenWorlds = new HashSet<>();
        confirmationHomes = new ArrayList<>();
        for (HomePrediction hp : all) {
            if (hp.needsConfirmation() && seenWorlds.add(hp.world())) {
                confirmationHomes.add(hp);
            }
        }
        confirmIndex = 0;

        AutoQiqiClient.logDebug("AutoHop", "Starting rotation: " + all.size() + " auto* homes, "
                + confirmationHomes.size() + " worlds need confirmation");

        if (confirmationHomes.isEmpty()) {
            msg("§a[Auto-Hop]§r Aucun monde à confirmer — sélection directe du meilleur");
            teleportToBest();
        } else {
            msg("§6[Auto-Hop]§r " + confirmationHomes.size() + " monde(s) à confirmer");
            teleportToNextConfirmation();
        }
    }

    // ========================
    // Round-robin fallback (no LegendTracker)
    // ========================

    /** Stored spawn probabilities per home (populated during confirmation via /nextleg). */
    private final Map<String, Map<String, Double>> pollResults = new LinkedHashMap<>();
    /** Whether we've sent /nextleg for the current confirmation home and are waiting for response. */
    private boolean nextlegSent = false;

    private void startPollRotation() {
        List<HomePrediction> homes = LegendTrackerProperties.loadHomes();
        if (homes.isEmpty()) {
            msg("§c[Auto-Hop]§r Aucun home dans legendtracker.properties");
            return;
        }

        homes = filterAutoHomes(homes);
        if (homes.isEmpty()) {
            msg("§c[Auto-Hop]§r Aucun home 'auto*' dans legendtracker.properties");
            return;
        }

        roundRobinMode = true;
        pollResults.clear();

        // Visit all homes to poll /nextleg at each
        confirmationHomes = homes;
        confirmIndex = 0;

        AutoQiqiClient.logDebug("AutoHop", "Poll rotation: " + homes.size() + " homes to evaluate");
        msg("§6[Auto-Hop]§r Évaluation de " + homes.size() + " homes...");
        teleportToNextConfirmation();
    }

    /**
     * Called by ChatMessageHandler when "Apparitions possibles: ..." is parsed from Spawn Info.
     * In poll mode (round-robin): stores per-home spawn data for EV computation.
     * In LT mode: captures server ground truth at best home for verification.
     */
    public void onSpawnProbabilitiesParsed(Map<String, Double> spawns) {
        // Capture actual spawns during best-home phase — server ground truth
        // to cross-check LT's prediction (which may use wrong assumed weather).
        if (state == State.BEST_TELEPORTING || state == State.BEST_WAITING_TP || state == State.BEST_SETTLING) {
            bestHomeSpawnInfo = spawns;
            double actualEv = LegendTrackerProperties.computeEV(spawns);
            AutoQiqiClient.logDebug("AutoHop", "Spawn Info at best home: actualEV="
                    + String.format("%.2f", actualEv) + " spawns=" + spawns);
            return;
        }

        if (state != State.CONFIRM_SETTLING) return;
        if (confirmationHomes == null || confirmIndex <= 0 || confirmIndex > confirmationHomes.size()) return;

        HomePrediction home = confirmationHomes.get(confirmIndex - 1); // -1 because confirmIndex was incremented after teleport
        // Actually confirmIndex points to current home being confirmed (not yet incremented)
        // Let me use the home we're currently settling at
        String homeName = currentConfirmName();
        pollResults.put(homeName, spawns);

        double ev = LegendTrackerProperties.computeEV(spawns);
        AutoQiqiClient.logDebug("AutoHop", "Poll result for " + homeName + ": EV=" + String.format("%.2f", ev) + " " + spawns);
    }

    // ========================
    // Tick
    // ========================

    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        tickCount++;

        // ── Single trigger path: check LT timer every tick, fallback to global timer ──
        long remaining = LegendTrackerBridge.getRemainingSeconds();
        if (remaining < 0) {
            remaining = WorldTracker.get().getGlobalRemainingSeconds();
        }
        int threshold = AutoQiqiConfig.get().autoHopThresholdSeconds;

        // Reset cycle flag when timer goes back above threshold (new legendary cycle).
        // With LT: also reset when timer is unknown (-1) to cover epoch transitions.
        // Without LT: do NOT reset on -1, as the global timer can briefly go stale between polls.
        boolean resetOnUnknown = LegendTrackerBridge.isAvailable() && remaining < 0 && firedThisCycle;
        if (remaining > threshold || resetOnUnknown) {
            if (firedThisCycle) {
                AutoQiqiClient.logDebug("AutoHop", "Cycle reset (timer=" + remaining + "s, threshold=" + threshold + "s)");
                LegendTrackerBridge.clearAssessedHomes();

                // New cycle started, previous timer counted down.
                // If we've done enough rotations and no legendary is being engaged,
                // reconnect to flush client dimension caches before next cycle.
                if (state == State.IDLE && shouldReconnectForCacheFlush()) {
                    if (!disabled) {
                        triggerCacheFlushReconnect(client);
                        // Don't clear bestHome — triggerCacheFlushReconnect uses it
                        // to send us back to the right spot after reconnect.
                    } else {
                        reconnectDeferred = true;
                        AutoQiqiClient.logDebug("AutoHop", "Reconnect deferred (disabled during cycle reset)");
                    }
                } else if (state == State.IDLE) {
                    bestHome = null;
                }
            }
            firedThisCycle = false;
            java.util.Arrays.fill(safetyTpFired, false);
        }

        // Scheduled re-enable (after legendary battle loot pickup)
        if (reEnableAtMs > 0 && System.currentTimeMillis() >= reEnableAtMs) {
            reEnableAtMs = 0;
            AutoQiqiClient.logDebug("AutoHop", "Scheduled re-enable triggered");
            setDisabled(false);
        }

        // Re-enable on new cycle: if disabled and timer is back above threshold (or epoch reset via listener)
        if (disabled && reEnableAtMs == 0 && remaining > threshold) {
            AutoQiqiClient.logDebug("AutoHop", "New cycle detected (timer=" + remaining + "s) — auto re-enabling");
            msg("§a[Auto-Hop]§r Nouveau cycle détecté — réactivation automatique");
            setDisabled(false);
        }

        // Deferred reconnect: if a cache-flush reconnect was due but skipped because
        // the player was disabled (e.g. legendary battle), trigger it now.
        if (reconnectDeferred && !disabled && state == State.IDLE) {
            reconnectDeferred = false;
            AutoQiqiClient.logDebug("AutoHop", "Executing deferred cache-flush reconnect (rotations=" + rotationsSinceReconnect + ")");
            triggerCacheFlushReconnect(client);
            return; // reconnect disconnects — skip the rest of this tick
        }

        // Try to start rotation if idle, enabled, timer under threshold, not fired yet.
        // Grace period after epoch change: the old cycle's remaining≈0 may still be reported
        // for a few ticks before the new 15-min timer takes effect, which would false-trigger.
        boolean withinEpochGrace = lastEpochChangeMs > 0
                && (System.currentTimeMillis() - lastEpochChangeMs) < EPOCH_GRACE_MS;
        if (state == State.IDLE && !disabled && !firedThisCycle
                && remaining >= 0 && remaining <= threshold
                && !withinEpochGrace) {
            firedThisCycle = true;
            startRotation();
        }

        // Safety teleports: re-send /home to best home at 90s, 60s, 30s (LT mode only)
        // Re-evaluate predictions each time so we don't send the player to a stale home.
        if (state == State.IDLE && !disabled && !roundRobinMode && bestHome != null && remaining >= 0 && !isInBattle()) {
            for (int i = 0; i < SAFETY_TP_THRESHOLDS.length; i++) {
                if (!safetyTpFired[i] && remaining <= SAFETY_TP_THRESHOLDS[i]) {
                    safetyTpFired[i] = true;

                    // Re-read predictions and pick the current #1
                    List<HomePrediction> safetyFresh = filterAutoHomes(LegendTrackerBridge.getFreshPredictions());
                    if (!safetyFresh.isEmpty() && !safetyFresh.get(0).name().equals(bestHome.name())) {
                        HomePrediction newTop = safetyFresh.get(0);
                        AutoQiqiClient.logDebug("AutoHop", "Safety TP: top home changed "
                                + bestHome.name() + " → " + newTop.name()
                                + " (EV " + String.format("%.2f", newTop.ev()) + ")");
                        bestHome = newTop;
                        bestHomeOriginalEv = newTop.ev();
                    }

                    AutoQiqiClient.logDebug("AutoHop", "Safety teleport at " + SAFETY_TP_THRESHOLDS[i]
                            + "s — sending " + bestHome.command());
                    msg("§6[Auto-Hop]§r Safety TP (" + SAFETY_TP_THRESHOLDS[i] + "s) → §b" + bestHome.name());
                    if (bestHome.command().startsWith("/")) {
                        client.player.networkHandler.sendCommand(bestHome.command().substring(1));
                    } else {
                        client.player.networkHandler.sendChatMessage(bestHome.command());
                    }
                    if (i == SAFETY_TP_THRESHOLDS.length - 1) {
                        sendTpaHereInline(client);
                    }
                    break; // only one per tick
                }
            }
        }

        if (state == State.IDLE) return;

        // Abort if legendary appears (unless force or in final phase)
        if (!forceRotation && isLegendaryNearby()
                && state != State.BEST_TELEPORTING && state != State.BEST_WAITING_TP
                && state != State.BEST_SETTLING && state != State.SENDING_RESET
                && state != State.SENDING_TPA && state != State.WAITING_TPA) {
            AutoQiqiClient.logDebug("AutoHop", "Aborting: legendary nearby during " + state);
            msg("§c[Auto-Hop]§r Rotation annulée (légendaire détecté)");
            abort();
            return;
        }

        tickPendingCommand(client);

        switch (state) {
            case CONFIRM_WAITING_TP -> tickConfirmWaitingTp();
            case CONFIRM_SETTLING -> tickConfirmSettling();
            case BEST_WAITING_TP -> tickBestWaitingTp();
            case BEST_SETTLING -> tickBestSettling();
            case WAITING_TPA -> tickWaitingTpa();
            default -> {} // CONFIRM_TELEPORTING, BEST_TELEPORTING, SENDING_RESET, SENDING_TPA handled by pendingCommand
        }
    }

    // ========================
    // Phase 1: Confirm stale homes
    // ========================

    private void teleportToNextConfirmation() {
        HomePrediction home = confirmationHomes.get(confirmIndex);
        AutoQiqiClient.logDebug("AutoHop", "Confirming " + (confirmIndex + 1) + "/" + confirmationHomes.size()
                + ": " + home.name() + " (world=" + home.world() + ")");
        setState(State.CONFIRM_TELEPORTING);
        queueCommand(home.command());
    }

    private void tickConfirmWaitingTp() {
        if (ticksSinceStateEntered() >= TELEPORT_FALLBACK_TICKS) {
            AutoQiqiClient.logDebug("AutoHop", "Confirm teleport fallback — settling");
            setState(State.CONFIRM_SETTLING);
        }
    }

    private void tickConfirmSettling() {
        if (ticksSinceStateEntered() < SETTLE_DELAY_TICKS) return;

        // In poll mode (no LT): send /nextleg to get spawn probabilities, then wait for response
        if (roundRobinMode) {
            if (!nextlegSent) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null && client.player.networkHandler != null) {
                    AutoQiqiClient.logDebug("AutoHop", "Sending /nextleg at " + currentConfirmName());
                    client.player.networkHandler.sendCommand("nextleg");
                    nextlegSent = true;
                }
                return;
            }
            // Wait for response (pollResults gets populated by onSpawnProbabilitiesParsed)
            boolean hasResult = pollResults.containsKey(currentConfirmName());
            if (!hasResult && ticksSinceStateEntered() < SETTLE_DELAY_TICKS + 5 * 20) {
                return; // wait up to 5s for /nextleg response
            }
            nextlegSent = false;
        }

        msg("§7[Auto-Hop]§r ✓ " + currentConfirmName() + " confirmé");
        confirmIndex++;
        if (confirmIndex < confirmationHomes.size()) {
            teleportToNextConfirmation();
        } else {
            AutoQiqiClient.logDebug("AutoHop", "All confirmations done — selecting best home");
            teleportToBest();
        }
    }

    // ========================
    // Phase 2: Teleport to best home
    // ========================

    private void teleportToBest() {
        List<HomePrediction> fresh;

        if (roundRobinMode) {
            // Poll mode: compute EV from /nextleg results and tier weights
            fresh = new ArrayList<>();
            for (HomePrediction home : confirmationHomes) {
                Map<String, Double> spawns = pollResults.get(home.name());
                double ev = (spawns != null) ? LegendTrackerProperties.computeEV(spawns) : 0;
                fresh.add(new HomePrediction(home.name(), home.world(), home.command(),
                        home.biome(), home.yLevel(), spawns != null ? spawns : Map.of(), ev, false));
            }
            // Sort by EV descending; on tie, prefer home in current world
            String curWorld = WorldTracker.get().getCurrentWorld();
            fresh.sort((a, b) -> {
                int cmp = Double.compare(b.ev(), a.ev());
                if (cmp != 0) return cmp;
                boolean aLocal = curWorld != null && curWorld.equalsIgnoreCase(a.world());
                boolean bLocal = curWorld != null && curWorld.equalsIgnoreCase(b.world());
                return Boolean.compare(bLocal, aLocal);
            });
        } else {
            // LT mode: re-read predictions now that all worlds are confirmed
            fresh = filterAutoHomes(LegendTrackerBridge.getFreshPredictions());
        }

        if (fresh.isEmpty()) {
            msg("§c[Auto-Hop]§r Pas de prédictions après confirmation — abandon");
            setState(State.IDLE);
            return;
        }

        bestHome = fresh.get(0);
        bestHomeOriginalEv = bestHome.ev();
        fallbackCount = 0;

        logRanking(fresh);
        teleportToBestHome();
    }

    /**
     * Pick the current best prediction and teleport to it.
     * Called both on initial selection and on fallback.
     */
    private void teleportToBestHome() {
        bestHomeSpawnInfo = null; // Clear for fresh capture at new destination
        msg("§a[Auto-Hop]§r Meilleur: §b" + bestHome.name() + "§r (EV=" + String.format("%.2f", bestHome.ev()) + ")");
        setState(State.BEST_TELEPORTING);
        queueCommand(bestHome.command());
    }

    private void tickBestWaitingTp() {
        if (ticksSinceStateEntered() >= TELEPORT_FALLBACK_TICKS) {
            AutoQiqiClient.logDebug("AutoHop", "Best teleport fallback — settling to verify");
            setState(State.BEST_SETTLING);
        }
    }

    /**
     * After arriving at best home, wait for LT to observe actual weather/conditions,
     * then re-check predictions. If EV dropped significantly, try next best.
     *
     * Weather check: LT's g.a() can predict wrong weather (e.g. assumes rain → Kyogre,
     * but actual weather is clear → Manaphy). When the predicted top-EV pokemon is
     * completely absent from the server Spawn Info, weather is almost certainly wrong
     * and we fall back. We do NOT compare EV values against Spawn Info because LT is
     * good at predicting in-game time at spawn — probabilities may legitimately differ
     * from the current Spawn Info due to time-of-day shifts.
     */
    private void tickBestSettling() {
        if (ticksSinceStateEntered() < SETTLE_DELAY_TICKS) return;

        // Round-robin mode: no EV to verify, just proceed to reset
        if (roundRobinMode) {
            AutoQiqiClient.logDebug("AutoHop", "Round-robin: settled at " + (bestHome != null ? bestHome.name() : "?") + " — sending reset");
            sendReset();
            return;
        }

        // Re-read LT predictions (needed for fallback candidates and LT-based EV check)
        List<HomePrediction> fresh = filterAutoHomes(LegendTrackerBridge.getFreshPredictions());

        // ── Weather check: predicted top pokemon must appear in server Spawn Info ──
        // LT predicts time-of-day accurately, so probability differences vs Spawn Info
        // are expected and OK. But if the #1 predicted pokemon (driving the EV) is
        // COMPLETELY ABSENT from the Spawn Info, weather is almost certainly wrong
        // (e.g. LT assumed rain → Kyogre 100%, but server shows Manaphy 100% — no rain).
        if (bestHomeSpawnInfo != null && bestHome != null && !bestHome.pokemonProbabilities().isEmpty()) {
            String topPredicted = findTopEvPokemon(bestHome.pokemonProbabilities());
            if (topPredicted != null) {
                boolean presentInSpawnInfo = bestHomeSpawnInfo.entrySet().stream()
                        .anyMatch(e -> e.getKey().equalsIgnoreCase(topPredicted) && e.getValue() > 0);

                if (!presentInSpawnInfo) {
                    double actualEv = LegendTrackerProperties.computeEV(bestHomeSpawnInfo);
                    AutoQiqiClient.logDebug("AutoHop", "Weather mismatch: predicted " + topPredicted
                            + " absent from Spawn Info " + bestHomeSpawnInfo
                            + " — actualEV=" + String.format("%.2f", actualEv));

                    LegendTrackerBridge.markHomeAssessed(bestHome.name(), actualEv);
                    fallbackCount++;
                    AutoQiqiClient.logDebug("AutoHop", "EV dropped at " + bestHome.name()
                            + ": " + String.format("%.2f", bestHomeOriginalEv) + " -> " + String.format("%.2f", actualEv)
                            + " (weather) — fallback " + fallbackCount + "/" + MAX_FALLBACK_ATTEMPTS);

                    if (fallbackCount < MAX_FALLBACK_ATTEMPTS) {
                        HomePrediction newBest = pickFallback(fresh);
                        if (newBest != null) {
                            msg("§6[Auto-Hop]§r §c" + topPredicted + " absent du Spawn Info§r — "
                                    + bestHome.name() + " EV:" + String.format("%.0f", bestHomeOriginalEv)
                                    + "→" + String.format("%.0f", actualEv) + " — fallback: §b" + newBest.name()
                                    + "§r (EV=" + String.format("%.2f", newBest.ev()) + ")");
                            bestHome = newBest;
                            bestHomeOriginalEv = newBest.ev();
                            teleportToBestHome();
                            return;
                        }
                    }
                    msg("§c[Auto-Hop]§r Météo KO (" + topPredicted + ") — on reste ici");
                    sendReset();
                    return;
                }
                // Top pokemon IS present — time-of-day differences are expected, trust LT
                AutoQiqiClient.logDebug("AutoHop", "Weather OK: " + topPredicted
                        + " present in Spawn Info — trusting LT prediction");
            }
        }

        // ── Standard LT-based EV check (covers non-weather EV changes) ──
        if (fresh.isEmpty()) {
            sendReset();
            return;
        }

        double currentEv = 0;
        for (HomePrediction hp : fresh) {
            if (hp.name().equals(bestHome.name())) {
                currentEv = hp.ev();
                break;
            }
        }

        if (currentEv >= bestHomeOriginalEv * 0.5 || (currentEv > 0 && bestHomeOriginalEv == 0)) {
            AutoQiqiClient.logDebug("AutoHop", "Best home verified (LT): " + bestHome.name()
                    + " EV was " + String.format("%.2f", bestHomeOriginalEv)
                    + " now " + String.format("%.2f", currentEv) + " — staying");
            sendReset();
            return;
        }

        // EV dropped via LT prediction (non-weather reason)
        LegendTrackerBridge.markHomeAssessed(bestHome.name(), currentEv);

        fallbackCount++;
        AutoQiqiClient.logDebug("AutoHop", "EV dropped at " + bestHome.name()
                + ": " + String.format("%.2f", bestHomeOriginalEv) + " -> " + String.format("%.2f", currentEv)
                + " (LT) — fallback " + fallbackCount + "/" + MAX_FALLBACK_ATTEMPTS);

        if (fallbackCount >= MAX_FALLBACK_ATTEMPTS) {
            msg("§c[Auto-Hop]§r EV changé " + fallbackCount + "x — on reste ici");
            sendReset();
            return;
        }

        HomePrediction newBest = pickFallback(fresh);
        if (newBest == null) {
            msg("§c[Auto-Hop]§r Aucun meilleur candidat — on reste ici");
            sendReset();
            return;
        }

        msg("§6[Auto-Hop]§r " + bestHome.name() + " EV:" + String.format("%.0f", bestHomeOriginalEv)
                + "→" + String.format("%.0f", currentEv) + " — fallback: §b" + newBest.name()
                + "§r (EV=" + String.format("%.2f", newBest.ev()) + ")");

        bestHome = newBest;
        bestHomeOriginalEv = newBest.ev();
        teleportToBestHome();
    }

    /**
     * Finds the pokemon with the highest EV contribution in the prediction map.
     * Returns the pokemon name, or null if no tiered pokemon found.
     */
    private String findTopEvPokemon(Map<String, Double> pokemonProbabilities) {
        Map<String, Double> tierWeights = LegendTrackerProperties.getTierWeights();
        String topName = null;
        double topContribution = 0;
        for (Map.Entry<String, Double> e : pokemonProbabilities.entrySet()) {
            Double tierValue = tierWeights.get(e.getKey().toLowerCase());
            if (tierValue != null) {
                double contribution = (e.getValue() / 100.0) * tierValue;
                if (contribution > topContribution) {
                    topContribution = contribution;
                    topName = e.getKey();
                }
            }
        }
        return topContribution > 5.0 ? topName : null; // Only flag if meaningful EV at stake
    }

    /** Pick the next best home from fresh predictions, skipping the current one. */
    private HomePrediction pickFallback(List<HomePrediction> fresh) {
        for (HomePrediction hp : fresh) {
            if (!hp.name().equals(bestHome.name()) && hp.ev() > 0) {
                return hp;
            }
        }
        return null;
    }

    private void logRanking(List<HomePrediction> predictions) {
        AutoQiqiClient.logDebug("AutoHop", "=== RANKING (" + predictions.size() + " homes) ===");
        for (int i = 0; i < Math.min(predictions.size(), 10); i++) {
            HomePrediction hp = predictions.get(i);
            AutoQiqiClient.logDebug("AutoHop", "  #" + (i + 1) + " " + hp.name()
                    + " | EV=" + String.format("%.2f", hp.ev())
                    + " | world=" + hp.world()
                    + " | " + (hp.needsConfirmation() ? "⚠ STALE" : "✓")
                    + " | " + hp.pokemonProbabilities());
        }
    }

    // ========================
    // Phase 3: Reset cache
    // ========================

    private void sendReset() {
        // Clear assessed home EVs so next rotation starts fresh
        // (prevents stale overrides from compounding across rotations)
        LegendTrackerBridge.clearAssessedHomes();
        setState(State.SENDING_RESET);
        queueCommand("/pk reset");
    }

    // ========================
    // Teleport confirmed callback
    // ========================

    public void onTeleportConfirmed() {
        AutoQiqiClient.logDebug("AutoHop", "onTeleportConfirmed in state=" + state);
        switch (state) {
            case CONFIRM_WAITING_TP -> setState(State.CONFIRM_SETTLING);
            case BEST_WAITING_TP -> setState(State.BEST_SETTLING);
        }
    }

    // ========================
    // TPA (optional, post-rotation)
    // ========================

    private boolean shouldSendTpa() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        return config.autohopTpaEnabled
                && config.autohopTpaAltAccount != null
                && !config.autohopTpaAltAccount.isEmpty();
    }

    private void sendTpaHere() {
        String alt = AutoQiqiConfig.get().autohopTpaAltAccount;
        AutoQiqiClient.logDebug("AutoHop", "Sending /tpahere " + alt);
        msg("§6[Auto-Hop]§r /tpahere " + alt);
        setState(State.SENDING_TPA);
        queueCommand("/tpahere " + alt);
    }

    /**
     * Sends /tpahere to alt account inline (not through state machine).
     * Used after safety TPs where we don't want a full state transition.
     */
    private void sendTpaHereInline(MinecraftClient client) {
        if (!shouldSendTpa() || client.player == null) return;
        String alt = AutoQiqiConfig.get().autohopTpaAltAccount;
        AutoQiqiClient.logDebug("AutoHop", "Safety TP — sending /tpahere " + alt);
        client.player.networkHandler.sendCommand("tpahere " + alt);
    }

    public void onTpaCompleted() {
        if (state == State.WAITING_TPA) {
            msg("§a[Auto-Hop]§r Alt téléporté !");
            setState(State.IDLE);
        }
    }

    private void tickWaitingTpa() {
        if (ticksSinceStateEntered() >= TPA_TIMEOUT_TICKS) {
            msg("§c[Auto-Hop]§r TPA timeout");
            setState(State.IDLE);
        }
    }

    // ========================
    // Abort
    // ========================

    public void abort() {
        pendingCommand = null;
        forceRotation = false;
        roundRobinMode = false;
        setState(State.IDLE);
    }

    // ========================
    // Command queue
    // ========================

    private void queueCommand(String command) {
        int delayTicks = Math.max(1, AutoQiqiConfig.get().commandDelayMinMs / 50);
        pendingCommand = command;
        commandExecuteAtTick = tickCount + delayTicks;
    }

    private void tickPendingCommand(MinecraftClient client) {
        if (pendingCommand == null || tickCount < commandExecuteAtTick) return;

        if (client.player == null || !AutoQiqiClient.isConnected(client)) {
            commandExecuteAtTick = tickCount + 40;
            return;
        }

        String cmd = pendingCommand;
        pendingCommand = null;

        try {
            if (cmd.startsWith("/")) {
                client.player.networkHandler.sendCommand(cmd.substring(1));
            } else {
                client.player.networkHandler.sendChatMessage(cmd);
            }
        } catch (Exception e) {
            AutoQiqiClient.logDebug("AutoHop", "Command failed: " + e.getMessage());
            pendingCommand = cmd;
            commandExecuteAtTick = tickCount + 40;
            return;
        }

        // State transitions after command sent
        switch (state) {
            case CONFIRM_TELEPORTING -> setState(State.CONFIRM_WAITING_TP);
            case BEST_TELEPORTING -> setState(State.BEST_WAITING_TP);
            case SENDING_RESET -> {
                rotationsSinceReconnect++;
                AutoQiqiClient.logDebug("AutoHop", "Rotation complete — /pk reset sent (rotations since reconnect: " + rotationsSinceReconnect + ")");
                msg("§a[Auto-Hop]§r Rotation terminée");
                if (shouldSendTpa()) {
                    sendTpaHere();
                } else {
                    setState(State.IDLE);
                }
            }
            case SENDING_TPA -> setState(State.WAITING_TPA);
        }
    }

    // ========================
    // Helpers
    // ========================

    private boolean isInBattle() {
        return CobblemonClient.INSTANCE.getBattle() != null
                || CaptureEngine.get().isActive();
    }

    /**
     * If autohopMode is "auto", filters to homes whose name starts with "auto" (case-insensitive).
     * If "all", returns the full list. If "off", returns empty.
     */
    private List<HomePrediction> filterAutoHomes(List<HomePrediction> predictions) {
        String mode = AutoQiqiConfig.get().autohopMode;
        if ("off".equals(mode)) return List.of();
        if (!"auto".equals(mode)) return predictions;
        List<HomePrediction> filtered = new ArrayList<>();
        for (HomePrediction hp : predictions) {
            if (hp.name() != null && hp.name().toLowerCase().startsWith("auto")) {
                filtered.add(hp);
            }
        }
        AutoQiqiClient.logDebug("AutoHop", "Auto-home filter: " + predictions.size() + " → " + filtered.size());
        return filtered;
    }

    private boolean isLegendaryNearby() {
        if (isInBattle()) return true;
        for (Entity e : PokemonScanner.get().scan()) {
            if (PokemonScanner.isLegendary(e)) return true;
        }
        return false;
    }

    private void setState(State newState) {
        if (state != newState) {
            AutoQiqiClient.logDebug("AutoHop", state + " -> " + newState);
        }
        state = newState;
        stateEnteredTick = tickCount;
    }

    private long ticksSinceStateEntered() {
        return tickCount - stateEnteredTick;
    }

    private static void msg(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(text), false);
        }
    }

    // ========================
    // Periodic reconnect (cache flush)
    // ========================

    private boolean shouldReconnectForCacheFlush() {
        int n = AutoQiqiConfig.get().autohopReconnectEveryNRotations;
        return n > 0 && rotationsSinceReconnect >= n;
    }

    /**
     * Disconnects the client to flush dimension/chunk caches that accumulate
     * from world-hopping. AutoReconnectEngine picks up the disconnect and
     * handles the full reconnect flow back to the game.
     */
    private void triggerCacheFlushReconnect(MinecraftClient client) {
        rotationsSinceReconnect = 0;
        reconnectDeferred = false;
        msg("§6[Auto-Hop]§r Reconnexion pour vider le cache client...");
        AutoQiqiClient.logDebug("AutoHop", "Triggering cache-flush reconnect"
                + (bestHome != null ? " (will return to " + bestHome.name() + ")" : ""));

        // Ensure auto-reconnect is enabled so it picks up the disconnect
        AutoQiqiConfig.get().autoReconnectEnabled = true;
        var reconnect = com.cobblemoon.autoqiqi.legendary.AutoReconnectEngine.get();
        reconnect.enable();


        // Disconnect cleanly via client.disconnect() + TitleScreen — avoids
        // racing with network-level disconnects (UDP timeout etc.)
        client.execute(() -> {
            if (client.world != null) {
                client.world.disconnect();
            }
            client.disconnect();
            client.setScreen(new net.minecraft.client.gui.screen.TitleScreen());
        });
    }
}
