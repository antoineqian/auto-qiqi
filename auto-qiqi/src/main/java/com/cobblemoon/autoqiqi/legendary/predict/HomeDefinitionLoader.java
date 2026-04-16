package com.cobblemoon.autoqiqi.legendary.predict;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parses all home definitions from legendtracker.properties.
 * Groups multi-line home.KEY.field=value entries into {@link HomeDefinition} records.
 */
public final class HomeDefinitionLoader {

    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("legendtracker.properties");

    private static List<HomeDefinition> cached = null;

    private HomeDefinitionLoader() {}

    /** Load (or return cached) home definitions. Call {@link #reload()} to refresh. */
    public static List<HomeDefinition> get() {
        if (cached == null) reload();
        return cached;
    }

    /** Force re-read from disk. */
    public static void reload() {
        cached = load();
    }

    private static List<HomeDefinition> load() {
        if (!Files.exists(CONFIG_PATH)) {
            AutoQiqiClient.logDebug("HomeDefLoader", "legendtracker.properties not found");
            return List.of();
        }

        // Collect all properties per home key
        Map<String, Map<String, String>> homeProps = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.startsWith("home.")) continue;

                // home.KEY.field=value
                int firstDot = "home.".length();
                int secondDot = line.indexOf('.', firstDot);
                int eq = line.indexOf('=', firstDot);
                if (secondDot < 0 || eq < 0 || secondDot >= eq) continue;

                String key = line.substring(firstDot, secondDot);
                String field = line.substring(secondDot + 1, eq);
                String value = line.substring(eq + 1).trim();

                homeProps.computeIfAbsent(key, k -> new HashMap<>()).put(field, value);
            }
        } catch (Exception e) {
            AutoQiqiClient.logDebug("HomeDefLoader", "Failed to parse: " + e.getMessage());
            return List.of();
        }

        List<HomeDefinition> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : homeProps.entrySet()) {
            String key = entry.getKey();
            Map<String, String> props = entry.getValue();

            String world = props.getOrDefault("world", "");
            String biome = props.getOrDefault("biome", "");
            String cmd = props.getOrDefault("cmd", "");
            int yLevel = parseIntOr(props.get("yLevel"), 64);
            boolean underground = "true".equalsIgnoreCase(props.get("isUnderground"));
            boolean underwater = "true".equalsIgnoreCase(props.get("isUnderwater"));

            result.add(new HomeDefinition(key, world, biome, cmd, yLevel, underground, underwater));
        }

        AutoQiqiClient.logDebug("HomeDefLoader", "Loaded " + result.size() + " home definitions");
        return Collections.unmodifiableList(result);
    }

    private static int parseIntOr(String s, int defaultVal) {
        if (s == null) return defaultVal;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return defaultVal; }
    }
}
