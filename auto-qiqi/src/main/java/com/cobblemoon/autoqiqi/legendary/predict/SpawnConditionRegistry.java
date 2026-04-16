package com.cobblemoon.autoqiqi.legendary.predict;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads and caches all legendary spawn conditions from bundled spawns.json.
 * Same loading pattern as SmogonData.
 */
public final class SpawnConditionRegistry {

    private static final String ASSET_PATH = "/assets/auto-qiqi/spawns.json";
    private static final Map<String, SpawnCondition> ENTRIES = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private static boolean loaded = false;

    private SpawnConditionRegistry() {}

    public static void load() {
        ENTRIES.clear();
        try (InputStream is = SpawnConditionRegistry.class.getResourceAsStream(ASSET_PATH)) {
            if (is == null) {
                AutoQiqiClient.logDebug("SpawnRegistry", "spawns.json not found in assets");
                loaded = true;
                return;
            }
            JsonArray arr = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), JsonArray.class);
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String name = obj.get("name").getAsString();

                Set<String> times = parseStringSet(obj, "times");
                Set<String> weathers = parseStringSet(obj, "weathers");
                Set<String> biomes = parseStringSet(obj, "biomes");
                int maxY = obj.has("max_y") ? obj.get("max_y").getAsInt() : 320;
                int minY = obj.has("min_y") ? obj.get("min_y").getAsInt() : -64;
                boolean underground = obj.has("underground") && obj.get("underground").getAsBoolean();
                boolean underwater = obj.has("underwater") && obj.get("underwater").getAsBoolean();
                int weight = obj.has("weight") ? obj.get("weight").getAsInt() : 1;

                ENTRIES.put(name, new SpawnCondition(name, times, weathers, biomes, maxY, minY, underground, underwater, weight));
            }
            AutoQiqiClient.logDebug("SpawnRegistry", "Loaded " + ENTRIES.size() + " spawn conditions");
        } catch (Exception e) {
            AutoQiqiClient.logDebug("SpawnRegistry", "Failed to load spawns.json: " + e.getMessage());
        }
        loaded = true;
    }

    public static SpawnCondition get(String name) { return ENTRIES.get(name); }
    public static Collection<SpawnCondition> all() { return ENTRIES.values(); }
    public static boolean isLoaded() { return loaded; }

    private static Set<String> parseStringSet(JsonObject obj, String key) {
        Set<String> set = new LinkedHashSet<>();
        if (!obj.has(key)) return set;
        for (JsonElement el : obj.getAsJsonArray(key)) {
            set.add(el.getAsString());
        }
        return set;
    }
}
