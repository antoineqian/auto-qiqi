package com.cobblemoon.autoqiqi.legendary.predict;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Maps Minecraft biome IDs to their tag sets (e.g. "minecraft:deep_ocean" → {"cobblemon:is_deep_ocean", ...}).
 * Loaded from bundled biome_tags.json (reverse-engineered from LegendTracker).
 */
public final class BiomeTagMap {

    private static final String ASSET_PATH = "/assets/auto-qiqi/biome_tags.json";
    private static final Map<String, Set<String>> BIOME_TO_TAGS = new HashMap<>();
    private static boolean loaded = false;

    private BiomeTagMap() {}

    public static void load() {
        BIOME_TO_TAGS.clear();
        try (InputStream is = BiomeTagMap.class.getResourceAsStream(ASSET_PATH)) {
            if (is == null) {
                AutoQiqiClient.logDebug("BiomeTagMap", "biome_tags.json not found in assets");
                loaded = true;
                return;
            }
            Type type = new TypeToken<Map<String, List<String>>>() {}.getType();
            Map<String, List<String>> raw = new Gson().fromJson(
                    new InputStreamReader(is, StandardCharsets.UTF_8), type);
            if (raw != null) {
                for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
                    BIOME_TO_TAGS.put(entry.getKey(), new HashSet<>(entry.getValue()));
                }
            }
            AutoQiqiClient.logDebug("BiomeTagMap", "Loaded " + BIOME_TO_TAGS.size() + " biome mappings");
        } catch (Exception e) {
            AutoQiqiClient.logDebug("BiomeTagMap", "Failed to load biome_tags.json: " + e.getMessage());
        }
        loaded = true;
    }

    /** Returns the tag set for a biome ID, or empty set if unknown. */
    public static Set<String> getTags(String biomeId) {
        Set<String> tags = BIOME_TO_TAGS.get(biomeId);
        return tags != null ? tags : Set.of();
    }

    /** True if the biome has at least one of the given tags. */
    public static boolean hasAnyTag(String biomeId, Set<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) return true;
        Set<String> biomeTags = getTags(biomeId);
        for (String tag : requiredTags) {
            if (biomeTags.contains(tag)) return true;
        }
        return false;
    }

    public static boolean isLoaded() { return loaded; }
}
