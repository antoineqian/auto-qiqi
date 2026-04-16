package com.cobblemoon.autoqiqi.legendary.predict;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.LegendTimerSync;
import net.minecraft.client.MinecraftClient;

import java.util.*;

/**
 * Core prediction engine. Computes, for each home, the expected value
 * of legendaries that can spawn there when the timer expires — using
 * biome tags, time-of-day, y-level, and underground/underwater constraints.
 *
 * Runs on the client tick thread; recomputes periodically (not every frame).
 */
public class SpawnPredictor {

    private static final SpawnPredictor INSTANCE = new SpawnPredictor();
    public static SpawnPredictor get() { return INSTANCE; }

    private volatile List<PredictionResult> lastResults = List.of();
    private long lastComputeMs = 0;

    private SpawnPredictor() {}

    public List<PredictionResult> getResults() { return lastResults; }

    /** Called every client tick. Recomputes if enough time has passed. */
    public void tick() {
        long now = System.currentTimeMillis();
        int intervalMs = AutoQiqiConfig.get().predictionRecomputeIntervalSeconds * 1000;
        if (now - lastComputeMs < intervalMs) return;
        lastComputeMs = now;
        recompute();
    }

    private void recompute() {
        if (!SpawnConditionRegistry.isLoaded() || !BiomeTagMap.isLoaded()) return;

        LegendTimerSync sync = LegendTimerSync.get();
        Map<String, LegendTimerSync.TimerRow> rows = sync.getLastRows();
        if (rows.isEmpty()) return;

        // Global remaining seconds from freshest row (timer is global)
        LegendTimerSync.TimerRow freshestRow = sync.getFreshestRow();
        if (freshestRow == null) return;
        long globalRemainingSecs = freshestRow.remainingSeconds();

        List<HomeDefinition> homes = HomeDefinitionLoader.get();
        if (homes.isEmpty()) return;

        Collection<SpawnCondition> allConditions = SpawnConditionRegistry.all();
        if (allConditions.isEmpty()) return;

        // Pokemon values now live in legendtracker.properties tiers — fallback predictor uses empty map
        Map<String, Double> pokemonValues = Map.of();

        // Predict time-of-day at spawn using client's actual day time + dayTickRate
        // (same approach as AutoHopEngine.predictTimeAtSpawn — accounts for /time set
        // and servers where dayTickRate != TPS)
        MinecraftClient client = MinecraftClient.getInstance();
        long clientPredictedTick = -1;
        if (client.world != null && globalRemainingSecs > 0) {
            long currentTime = client.world.getTimeOfDay();
            int dayTickRate = AutoQiqiConfig.get().dayTickRate;
            long futureTicks = globalRemainingSecs * dayTickRate;
            clientPredictedTick = ((currentTime + futureTicks) % 24000 + 24000) % 24000;
        }

        List<PredictionResult> results = new ArrayList<>();

        for (HomeDefinition home : homes) {
            // Find the timer row for this home's world (for time-of-day only)
            LegendTimerSync.TimerRow row = findRowForWorld(rows, home.world());
            if (row == null) continue;

            // Time-of-day is world-specific; remaining seconds is global
            long remainingSecs = globalRemainingSecs;
            // Use client-side time prediction (accurate); fall back to DB if unavailable
            long predictedTick = clientPredictedTick >= 0
                    ? clientPredictedTick
                    : ((row.spawnTickTarget() % 24000) + 24000) % 24000;

            // Find all matching spawn conditions for this home
            List<PredictionResult.MatchedSpawn> matched = new ArrayList<>();
            int totalWeight = 0;

            for (SpawnCondition cond : allConditions) {
                if (!matches(cond, home, predictedTick)) continue;
                totalWeight += cond.weight();
                matched.add(new PredictionResult.MatchedSpawn(
                        cond.name(), 0, cond.weight(), 0, 0));
            }

            if (totalWeight == 0) continue;

            // Compute probabilities and EV
            double ev = 0;
            List<PredictionResult.MatchedSpawn> finalMatched = new ArrayList<>();
            for (PredictionResult.MatchedSpawn m : matched) {
                double probability = (double) m.weight() / totalWeight;
                double value = getPokemonValue(pokemonValues, m.pokemonName());
                double contribution = probability * value;
                ev += contribution;
                finalMatched.add(new PredictionResult.MatchedSpawn(
                        m.pokemonName(), value, m.weight(), probability, contribution));
            }

            // Sort matched spawns by contribution descending
            finalMatched.sort((a, b) -> Double.compare(b.contribution(), a.contribution()));

            results.add(new PredictionResult(home, ev, finalMatched, predictedTick, remainingSecs));
        }

        Collections.sort(results);
        lastResults = Collections.unmodifiableList(results);
    }

    private static boolean matches(SpawnCondition cond, HomeDefinition home, long predictedTick) {
        // Time of day (same hour-based logic as LegendTracker)
        if (!SpawnCondition.isTimeMatch(predictedTick, cond.times())) return false;

        // Y-level: must be within [minY, maxY] (LegendTracker checks both bounds)
        if (home.yLevel() > cond.maxY() || home.yLevel() < cond.minY()) return false;

        // Underground/underwater: LegendTracker requires exact equality, not one-way
        if (cond.underground() != home.isUnderground()) return false;
        if (cond.underwater() != home.isUnderwater()) return false;

        // Biome: home's biome must have at least one of the condition's required tags
        if (!BiomeTagMap.hasAnyTag(home.biomeId(), cond.biomeTags())) return false;

        return true;
    }

    private static double getPokemonValue(Map<String, Double> values, String name) {
        // Case-insensitive lookup
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return 0.0;
    }

    /** Find the timer row matching a world name (case-insensitive partial match). */
    private static LegendTimerSync.TimerRow findRowForWorld(
            Map<String, LegendTimerSync.TimerRow> rows, String homeWorld) {
        if (homeWorld == null || homeWorld.isEmpty()) return null;

        // Exact match first
        LegendTimerSync.TimerRow exact = rows.get(homeWorld);
        if (exact != null) return exact;

        // Case-insensitive match
        String lower = homeWorld.toLowerCase();
        for (Map.Entry<String, LegendTimerSync.TimerRow> entry : rows.entrySet()) {
            if (entry.getKey().toLowerCase().equals(lower)) return entry.getValue();
        }

        // Partial match: home world "Lune" matches DB world "Monde Construction (Lune)"
        // Prefer the shortest key to avoid "Lune" matching "Ultra-Lune" over "Lune"
        Map.Entry<String, LegendTimerSync.TimerRow> bestMatch = null;
        for (Map.Entry<String, LegendTimerSync.TimerRow> entry : rows.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (key.contains(lower) || lower.contains(key)) {
                if (bestMatch == null || entry.getKey().length() < bestMatch.getKey().length()) {
                    bestMatch = entry;
                }
            }
        }
        return bestMatch != null ? bestMatch.getValue() : null;
    }
}
