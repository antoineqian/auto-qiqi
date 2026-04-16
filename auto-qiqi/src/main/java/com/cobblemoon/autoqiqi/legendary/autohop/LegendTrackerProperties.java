package com.cobblemoon.autoqiqi.legendary.autohop;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.legendary.predict.LegendTrackerBridge.HomePrediction;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads homes and tier weights from legendtracker.properties when the LegendTracker mod is not loaded.
 */
public final class LegendTrackerProperties {

    /** Cached tier weights: pokemon name (lowercase) → tier value. */
    private static Map<String, Double> tierWeights;

    private LegendTrackerProperties() {}

    private static Path getPropsPath() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("config/legendtracker.properties");
    }

    private static Properties loadProps() {
        Path propsFile = getPropsPath();
        if (!Files.exists(propsFile)) {
            AutoQiqiClient.logDebug("LTProps", "legendtracker.properties not found");
            return null;
        }
        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(propsFile)) {
            props.load(reader);
            return props;
        } catch (Exception e) {
            AutoQiqiClient.logDebug("LTProps", "Failed to read: " + e.getMessage());
            return null;
        }
    }

    public static List<HomePrediction> loadHomes() {
        Properties props = loadProps();
        if (props == null) return List.of();

        Set<String> homeNames = new LinkedHashSet<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("home.") && key.endsWith(".world")) {
                String name = key.substring(5, key.length() - 6);
                if (!name.isEmpty()) homeNames.add(name);
            }
        }

        List<HomePrediction> homes = new ArrayList<>();
        for (String name : homeNames) {
            String world = props.getProperty("home." + name + ".world", "");
            String cmd = props.getProperty("home." + name + ".cmd", "");
            String biome = props.getProperty("home." + name + ".biome", "");
            int yLevel = 64;
            try {
                yLevel = Integer.parseInt(props.getProperty("home." + name + ".yLevel", "64"));
            } catch (NumberFormatException ignored) {}

            if (cmd.isEmpty()) continue;
            homes.add(new HomePrediction(name, world, cmd, biome, yLevel, Map.of(), 0, false));
        }

        AutoQiqiClient.logDebug("LTProps", "Loaded " + homes.size() + " homes");
        return homes;
    }

    /**
     * Returns tier weights: pokemon name (lowercase) → tier value.
     * Parsed from tier.X=Name1,Name2 and tier.X.value=60.0 entries.
     */
    public static Map<String, Double> getTierWeights() {
        if (tierWeights != null) return tierWeights;
        Properties props = loadProps();
        if (props == null) {
            tierWeights = Map.of();
            return tierWeights;
        }

        Map<String, Double> weights = new HashMap<>();
        for (int tier = 1; tier <= 20; tier++) {
            String names = props.getProperty("tier." + tier);
            String valueStr = props.getProperty("tier." + tier + ".value");
            if (names == null || valueStr == null) continue;

            double value;
            try {
                value = Double.parseDouble(valueStr);
            } catch (NumberFormatException e) { continue; }

            for (String name : names.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    weights.put(trimmed.toLowerCase(), value);
                }
            }
        }

        AutoQiqiClient.logDebug("LTProps", "Loaded " + weights.size() + " tier weights");
        tierWeights = weights;
        return tierWeights;
    }

    /**
     * Computes EV from parsed spawn probabilities and tier weights.
     * @param spawnProbabilities map of pokemon name → probability (0-100)
     * @return weighted sum: sum(prob/100 * tierValue)
     */
    public static double computeEV(Map<String, Double> spawnProbabilities) {
        Map<String, Double> weights = getTierWeights();
        double ev = 0;
        for (Map.Entry<String, Double> entry : spawnProbabilities.entrySet()) {
            String name = entry.getKey().toLowerCase();
            double prob = entry.getValue() / 100.0;
            Double tierValue = weights.get(name);
            if (tierValue != null) {
                ev += prob * tierValue;
            }
        }
        return ev;
    }

    /** Clear cached tier weights (e.g. on config reload). */
    public static void clearCache() {
        tierWeights = null;
    }
}
