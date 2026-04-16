package com.cobblemoon.autoqiqi.legendary.predict;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.legendary.WorldTracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection bridge to LegendTracker mod's prediction engine.
 * Reads computed predictions directly from LegendTracker instead of reimplementing the logic.
 * Gracefully returns empty results if LegendTracker isn't loaded.
 *
 * Key LegendTracker classes (obfuscated):
 *   client.legendtracker.g     = TimerParser — g.a(e$a, long) → g$b prediction, g.c = spawn epoch ms, g.d = timer map
 *   client.legendtracker.g$b   = PredictionResult { double a (EV), Map<String,Double> b (pokemon→%) }
 *   client.legendtracker.g$c   = TimerData { long b (spawnTickTarget), long c (syncUtcMs), long d (worldTicksAtSync), double e (tps) }
 *   client.legendtracker.e$a   = HomeConfig { String a (name), b (world), c (cmd), d (biome), int f (yLevel) }
 *   client.legendtracker.e.e   = Map<String, e$a> all home configs
 */
public final class LegendTrackerBridge {

    private static Boolean available;

    // ── Change detection & caching ──────────────────────────────────────

    /** g.c — epoch millis when legendary spawns (changes when LT gets new timer data). */
    private static Field spawnEpochField;

    /** Last observed spawn epoch — used to detect timer updates. */
    private static long lastSpawnEpoch = 0;

    /** Cached prediction results — recomputed on timer change or every CACHE_TTL_MS. */
    private static volatile List<HomePrediction> cachedPredictions = List.of();
    private static long cacheComputedAtMs = 0;
    private static final long CACHE_TTL_MS = 3_000; // refresh at most every 3s even if no change

    /**
     * Homes that were physically visited this cycle and found to have dropped EV.
     * Maps home name → assessed EV. Used to prevent epoch-triggered cache refreshes
     * from reverting the ranking when the player leaves the assessed world.
     */
    private static final Map<String, Double> assessedHomeEvs = new ConcurrentHashMap<>();

    /** Listeners notified when LegendTracker's timer data changes. */
    private static final List<Runnable> changeListeners = new ArrayList<>();

    /** Register a listener called on the client tick thread when timer data changes. */
    public static void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Record that a home was physically visited and its EV was found to have dropped.
     * Prevents subsequent cache refreshes (e.g. epoch-triggered) from reverting the
     * ranking when the player leaves that world and LT loses observed conditions.
     */
    public static void markHomeAssessed(String homeName, double assessedEv) {
        assessedHomeEvs.put(homeName, assessedEv);
        AutoQiqiClient.logDebug("LTBridge", "Home assessed: " + homeName + " EV=" + String.format("%.2f", assessedEv));
    }

    /** Clear assessed home state — called at the start of a new legendary cycle. */
    public static void clearAssessedHomes() {
        if (!assessedHomeEvs.isEmpty()) {
            AutoQiqiClient.logDebug("LTBridge", "Clearing " + assessedHomeEvs.size() + " assessed homes");
            assessedHomeEvs.clear();
        }
    }

    /**
     * Call every client tick. Checks if LegendTracker's timer has changed
     * (new spawn epoch) and fires listeners + refreshes cache if so.
     */
    public static void tick() {
        if (!isAvailable()) return;
        try {
            long currentEpoch = spawnEpochField.getLong(null);
            if (currentEpoch != lastSpawnEpoch && currentEpoch > 0) {
                lastSpawnEpoch = currentEpoch;
                AutoQiqiClient.logDebug("LTBridge", "Timer changed — spawn epoch=" + currentEpoch);
                refreshCache();
                for (Runnable listener : changeListeners) {
                    try { listener.run(); } catch (Exception e) {
                        AutoQiqiClient.logDebug("LTBridge", "Listener error: " + e.getMessage());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // TimerParser (g) class and fields
    private static Class<?> timerParserClass;       // client.legendtracker.g
    private static Class<?> predictionResultClass;  // client.legendtracker.g$b
    private static Class<?> timerDataClass;         // client.legendtracker.g$c
    private static Class<?> homeConfigClass;        // client.legendtracker.e$a
    // g.a(e$a, long) → g$b  — prediction for a home at a given tick
    private static Method predictMethod;
    // g$c.a() → int remaining seconds (LegendTracker's own computation)
    private static Method timerRemainingMethod;
    // g.d — Map<String, g$c> timer data per world
    private static Field timerDataMapField;
    // e.e — Map<String, e$a> all home configs from ModConfig
    private static Field allHomesMapField;
    // g$b.a (EV double), g$b.b (Map<String,Double>)
    private static Field predictionEvField;
    private static Field predictionMapField;
    // g$c.b (spawnTickTarget), g$c.c (syncUtcMs)
    private static Field timerSpawnTickField;
    private static Field timerSyncUtcField;
    // e$a fields: a(name), b(world), c(cmd), d(biome), f(yLevel)
    private static Field homeNameField;
    private static Field homeWorldField;
    private static Field homeCmdField;
    private static Field homeBiomeField;
    private static Field homeYLevelField;

    private LegendTrackerBridge() {}

    public static boolean isAvailable() {
        if (available == null) {
            try {
                timerParserClass = Class.forName("client.legendtracker.g");
                predictionResultClass = Class.forName("client.legendtracker.g$b");
                timerDataClass = Class.forName("client.legendtracker.g$c");
                homeConfigClass = Class.forName("client.legendtracker.e$a");

                Class<?> modConfigClass = Class.forName("client.legendtracker.e");

                predictMethod = timerParserClass.getMethod("a", homeConfigClass, long.class);
                timerRemainingMethod = timerDataClass.getMethod("a");
                timerDataMapField = accessible(timerParserClass.getDeclaredField("d"));
                allHomesMapField = accessible(modConfigClass.getDeclaredField("e"));
                spawnEpochField = accessible(timerParserClass.getDeclaredField("c"));

                predictionEvField = accessible(predictionResultClass.getDeclaredField("a"));
                predictionMapField = accessible(predictionResultClass.getDeclaredField("b"));

                timerSpawnTickField = accessible(timerDataClass.getDeclaredField("b"));
                timerSyncUtcField = accessible(timerDataClass.getDeclaredField("c"));

                homeNameField = accessible(homeConfigClass.getDeclaredField("a"));
                homeWorldField = accessible(homeConfigClass.getDeclaredField("b"));
                homeCmdField = accessible(homeConfigClass.getDeclaredField("c"));
                homeBiomeField = accessible(homeConfigClass.getDeclaredField("d"));
                homeYLevelField = accessible(homeConfigClass.getDeclaredField("f"));

                available = true;
                AutoQiqiClient.logDebug("LTBridge", "LegendTracker detected — using native predictions");
            } catch (Exception e) {
                available = false;
                AutoQiqiClient.logDebug("LTBridge", "LegendTracker not found: " + e.getMessage());
            }
        }
        return available;
    }

    // ── Prediction for a specific home ──────────────────────────────────

    /**
     * Calls LegendTracker's prediction for the given home at a predicted tick.
     * Returns a map of pokemon name → spawn probability (0-100%).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Double> predictForHome(Object homeConfig, long predictedTick) {
        if (!isAvailable()) return Map.of();
        try {
            Object result = predictMethod.invoke(null, homeConfig, predictedTick);
            if (result == null) return Map.of();
            Map<String, Double> map = (Map<String, Double>) predictionMapField.get(result);
            return map != null ? map : Map.of();
        } catch (Exception e) {
            AutoQiqiClient.logDebug("LTBridge", "predictForHome failed: " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * Returns the EV (weighted score) from a prediction result.
     */
    public static double getPredictionEV(Object homeConfig, long predictedTick) {
        if (!isAvailable()) return 0;
        try {
            Object result = predictMethod.invoke(null, homeConfig, predictedTick);
            if (result == null) return 0;
            return predictionEvField.getDouble(result);
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Read LegendTracker's sorted home rotation ───────────────────────

    /**
     * Returns all home configs from LegendTracker's ModConfig (e.e map values).
     */
    @SuppressWarnings("unchecked")
    public static Collection<Object> getAllHomes() {
        if (!isAvailable()) return List.of();
        try {
            Map<String, Object> map = (Map<String, Object>) allHomesMapField.get(null);
            return map != null ? map.values() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── Read timer data ─────────────────────────────────────────────────

    /**
     * Returns the timer data map (world name → timer) from LegendTracker.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getTimerDataMap() {
        if (!isAvailable()) return Map.of();
        try {
            Map<String, Object> map = (Map<String, Object>) timerDataMapField.get(null);
            return map != null ? map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Remaining seconds — calls LegendTracker's own g$c.a() method. */
    public static long getTimerRemainingSeconds(Object timerData) {
        if (timerData == null) return -1;
        try {
            return ((Number) timerRemainingMethod.invoke(timerData)).longValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Spawn tick target from a g$c timer object. */
    public static long getSpawnTickTarget(Object timerData) {
        if (timerData == null) return -1;
        try {
            return timerSpawnTickField.getLong(timerData);
        } catch (Exception e) {
            return -1;
        }
    }

    // ── HomeConfig field accessors (used by refreshCache) ────────────────

    private static String getHomeName(Object home) {
        try { return (String) homeNameField.get(home); } catch (Exception e) { return ""; }
    }
    private static String getHomeWorld(Object home) {
        try { return (String) homeWorldField.get(home); } catch (Exception e) { return ""; }
    }
    private static String getHomeCmd(Object home) {
        try { return (String) homeCmdField.get(home); } catch (Exception e) { return ""; }
    }
    private static String getHomeBiome(Object home) {
        try { return (String) homeBiomeField.get(home); } catch (Exception e) { return ""; }
    }
    private static int getHomeYLevel(Object home) {
        try { return homeYLevelField.getInt(home); } catch (Exception e) { return 64; }
    }

    // ── Convenience: full prediction snapshot ───────────────────────────

    /**
     * Returns cached predictions. Cache is refreshed on timer change (via tick())
     * or if older than CACHE_TTL_MS.
     */
    public static List<HomePrediction> getFullPredictions() {
        if (!isAvailable()) return List.of();
        long now = System.currentTimeMillis();
        if (now - cacheComputedAtMs > CACHE_TTL_MS) {
            refreshCache();
        }
        return cachedPredictions;
    }

    /**
     * Force-refresh predictions, bypassing the cache TTL.
     * Used by auto-hop when it needs guaranteed fresh data (e.g. after teleporting).
     */
    public static List<HomePrediction> getFreshPredictions() {
        if (!isAvailable()) return List.of();
        refreshCache();
        return cachedPredictions;
    }

    private static void refreshCache() {
        cacheComputedAtMs = System.currentTimeMillis();
        try {
            Collection<Object> homes = getAllHomes();
            Map<String, Object> timers = getTimerDataMap();
            if (homes.isEmpty() || timers.isEmpty()) {
                cachedPredictions = List.of();
                snapshotTimeMs = 0;   // reset so getRemainingSeconds() returns -1
                snapshotRemainingSecs = -1;
                return;
            }

            // Find the freshest timer for the global remaining seconds display
            Object freshestTimer = null;
            long freshestSync = 0;
            for (Object timer : timers.values()) {
                long sync = timerSyncUtcField.getLong(timer);
                if (sync > freshestSync) {
                    freshestSync = sync;
                    freshestTimer = timer;
                }
            }
            if (freshestTimer == null) {
                cachedPredictions = List.of();
                return;
            }

            snapshotRemainingSecs = getTimerRemainingSeconds(freshestTimer);
            long now = System.currentTimeMillis();
            snapshotTimeMs = now;

            List<HomePrediction> results = new ArrayList<>();
            for (Object home : homes) {
                String homeWorld = getHomeWorld(home);

                // Find the timer matching this home's world — skip if no timer at all
                Object worldTimer = findTimerForWorld(timers, homeWorld);
                if (worldTimer == null) continue; // no timer data for this world
                boolean needsConfirm = isTimerStale(worldTimer, now);

                long spawnTick = getSpawnTickTarget(worldTimer);
                Map<String, Double> predictions = predictForHome(home, spawnTick);
                if (predictions.isEmpty()) continue;
                double ev = getPredictionEV(home, spawnTick);

                // If this home was physically assessed this cycle, use the assessed EV
                // to prevent epoch-triggered refreshes from reverting the ranking.
                String homeName = getHomeName(home);
                Double assessedEv = assessedHomeEvs.get(homeName);
                if (assessedEv != null) {
                    ev = assessedEv;
                }

                results.add(new HomePrediction(
                        homeName, homeWorld, getHomeCmd(home),
                        getHomeBiome(home), getHomeYLevel(home),
                        predictions, ev, needsConfirm
                ));
            }
            // Sort by EV descending; on tie, prefer the home in the current world
            // (avoids an unnecessary teleport when two homes have equal EV).
            String currentWorld = WorldTracker.get().getCurrentWorld();
            results.sort((a, b) -> {
                int cmp = Double.compare(b.ev(), a.ev());
                if (cmp != 0) return cmp;
                boolean aLocal = currentWorld != null && currentWorld.equalsIgnoreCase(a.world());
                boolean bLocal = currentWorld != null && currentWorld.equalsIgnoreCase(b.world());
                return Boolean.compare(bLocal, aLocal); // true (local) first
            });
            cachedPredictions = Collections.unmodifiableList(results);
        } catch (Exception e) {
            AutoQiqiClient.logDebug("LTBridge", "refreshCache failed: " + e.getMessage());
            cachedPredictions = List.of();
        }
    }

    /** Find timer matching a world name (case-insensitive partial match, same as LegendTracker). */
    private static Object findTimerForWorld(Map<String, Object> timers, String homeWorld) {
        if (homeWorld == null || homeWorld.isEmpty()) return null;
        // Exact match
        Object exact = timers.get(homeWorld);
        if (exact != null) return exact;
        // Case-insensitive / partial match
        String lower = homeWorld.toLowerCase();
        for (Map.Entry<String, Object> entry : timers.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (key.equals(lower) || key.contains(lower) || lower.contains(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Snapshot time (millis) when remainingSecondsAtSnapshot was captured. */
    private static long snapshotTimeMs = 0;

    /** Global remaining seconds from LegendTracker at the time of the last cache refresh. */
    public static long getRemainingSeconds() {
        if (snapshotTimeMs == 0) return -1;
        long elapsed = (System.currentTimeMillis() - snapshotTimeMs) / 1000;
        long remaining = snapshotRemainingSecs - elapsed;
        return Math.max(0, remaining);
    }

    private static long snapshotRemainingSecs = -1;

    /** Threshold used by LegendTracker's getPrefixForHome — world data older than this is "stale". */
    public static final long STALE_THRESHOLD_MS = 900_000; // 15 minutes

    public record HomePrediction(
            String name, String world, String command, String biome, int yLevel,
            Map<String, Double> pokemonProbabilities, double ev,
            boolean needsConfirmation
    ) {}

    // ── Confirmation detection ────────────────────────────────────────

    /**
     * Returns true if the timer data is stale or missing — matching LegendTracker's
     * ⚠ prefix logic in getPrefixForHome(). A stale world means weather/time-of-day
     * conditions haven't been confirmed recently, so predictions may be inaccurate.
     */
    private static boolean isTimerStale(Object worldTimer, long nowMs) {
        if (worldTimer == null) return true;
        try {
            long syncUtc = timerSyncUtcField.getLong(worldTimer);
            return (nowMs - syncUtc) > STALE_THRESHOLD_MS;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Returns the set of world names that currently need visiting (stale timer data).
     * Useful for auto-hop to decide which worlds to physically visit.
     */
    public static Set<String> getWorldsNeedingConfirmation() {
        if (!isAvailable()) return Set.of();
        try {
            Map<String, Object> timers = getTimerDataMap();
            long now = System.currentTimeMillis();
            Collection<Object> homes = getAllHomes();

            Set<String> staleWorlds = new LinkedHashSet<>();
            for (Object home : homes) {
                String world = getHomeWorld(home);
                if (world == null || world.isEmpty()) continue;
                Object timer = findTimerForWorld(timers, world);
                if (isTimerStale(timer, now)) {
                    staleWorlds.add(world);
                }
            }
            return staleWorlds;
        } catch (Exception e) {
            return Set.of();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static Field accessible(Field f) {
        f.setAccessible(true);
        return f;
    }
}
