package com.cobblemoon.autoqiqi.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent session logger that writes events to disk.
 *
 * Files (in .minecraft/auto-qiqi/):
 *   session.log       — human-readable timestamped event log (appended)
 *   session-stats.json — running counters, read on next launch for recap
 */
public final class SessionLogger {

    private static final SessionLogger INSTANCE = new SessionLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Path dir;
    private Path logFile;
    private Path statsFile;
    private boolean initialized = false;

    private SessionLogger() {}

    public static SessionLogger get() { return INSTANCE; }

    private void ensureInit() {
        if (initialized) return;
        initialized = true;
        dir = FabricLoader.getInstance().getGameDir().resolve("auto-qiqi");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("[Auto-Qiqi/SessionLogger] Failed to create dir: " + e.getMessage());
        }
        String today = LocalDateTime.now().format(FILE_DATE);
        logFile = dir.resolve("session-" + today + ".log");
        statsFile = dir.resolve("session-stats.json");
    }

    // ------------------------------------------------------------------
    // Event logging (appended to session-<date>.log)
    // ------------------------------------------------------------------

    public void logEvent(String category, String message) {
        ensureInit();
        String line = "[" + LocalDateTime.now().format(TS) + "] [" + category + "] " + message;
        try {
            Files.writeString(logFile, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Auto-Qiqi/SessionLogger] Write failed: " + e.getMessage());
        }
    }

    public void logCapture(String pokemonName, int level, boolean legendary, int ballsThrown) {
        logEvent("CAPTURE", pokemonName + " Lv." + level
                + (legendary ? " [LEGENDARY]" : "")
                + " (balls: " + ballsThrown + ")");
        incrementStat("captures", 1);
        appendToList("captured_pokemon", pokemonName + " Lv." + level + (legendary ? " [LEG]" : ""));
    }

    public void logCaptureFailed(String pokemonName, int level, boolean legendary, int ballsThrown, String reason) {
        logEvent("CAPTURE_FAIL", pokemonName + " Lv." + level
                + (legendary ? " [LEGENDARY]" : "")
                + " — " + reason + " (balls: " + ballsThrown + ")");
        incrementStat("capture_failures", 1);
        appendToList("failed_captures", pokemonName + " Lv." + level + " (" + reason + ")");
    }

    public void logKill(String pokemonName, boolean boss) {
        logEvent("KILL", pokemonName + (boss ? " [BOSS]" : ""));
        if (boss) {
            incrementStat("boss_kills", 1);
        } else {
            incrementStat("pokemon_kills", 1);
        }
    }

    public void logLegendarySpawn(String pokemonName, String world, boolean nearPlayer) {
        logEvent("LEGENDARY_SPAWN", pokemonName + " in " + world
                + (nearPlayer ? " (near you)" : " (near another player)"));
        appendToList("legendary_spawns", pokemonName + " in " + world);
    }

    public void logWorldSwitch(String from, String to) {
        logEvent("WORLD_SWITCH", (from != null ? from : "?") + " -> " + to);
    }

    public void logBattleStart(String pokemonName, int level, String action) {
        logEvent("BATTLE", "Started: " + pokemonName + " Lv." + level + " (" + action + ")");
    }

    public void logBallThrow(String pokemonName, String ballType, int throwNumber) {
        logEvent("BALL", ballType + " #" + throwNumber + " at " + pokemonName);
    }

    public void logError(String message) {
        logEvent("ERROR", message);
        incrementStat("errors", 1);
    }

    public void logInfo(String message) {
        logEvent("INFO", message);
    }

    // ------------------------------------------------------------------
    // Stats persistence (session-stats.json)
    // ------------------------------------------------------------------

    private Map<String, Object> readStats() {
        ensureInit();
        if (!Files.exists(statsFile)) return new LinkedHashMap<>();
        try {
            String json = Files.readString(statsFile);
            Map<String, Object> map = GSON.fromJson(json,
                    new TypeToken<LinkedHashMap<String, Object>>(){}.getType());
            return map != null ? map : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void writeStats(Map<String, Object> stats) {
        ensureInit();
        try (Writer w = Files.newBufferedWriter(statsFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(stats, w);
        } catch (IOException e) {
            System.err.println("[Auto-Qiqi/SessionLogger] Stats write failed: " + e.getMessage());
        }
    }

    private void incrementStat(String key, int amount) {
        Map<String, Object> stats = readStats();
        double current = stats.containsKey(key) ? ((Number) stats.get(key)).doubleValue() : 0;
        stats.put(key, (int)(current + amount));
        stats.put("last_updated", LocalDateTime.now().format(TS));
        writeStats(stats);
    }

    @SuppressWarnings("unchecked")
    private void appendToList(String key, String value) {
        Map<String, Object> stats = readStats();
        List<String> list;
        if (stats.containsKey(key) && stats.get(key) instanceof List) {
            list = new ArrayList<>((List<String>) stats.get(key));
        } else {
            list = new ArrayList<>();
        }
        list.add(value);
        stats.put(key, list);
        writeStats(stats);
    }

    // ------------------------------------------------------------------
    // Session recap: read stats, format for chat, then archive
    // ------------------------------------------------------------------

    /**
     * Returns formatted chat lines summarizing what happened since last launch.
     * Returns null if nothing happened. Resets stats after reading.
     */
    @SuppressWarnings("unchecked")
    public List<String> getLastSessionRecap() {
        ensureInit();
        Map<String, Object> stats = readStats();
        if (stats.isEmpty()) return null;

        String lastUpdated = (String) stats.getOrDefault("last_updated", "?");
        int captures = getInt(stats, "captures");
        int failures = getInt(stats, "capture_failures");
        int bossKills = getInt(stats, "boss_kills");
        int pokemonKills = getInt(stats, "pokemon_kills");
        int errors = getInt(stats, "errors");
        List<String> capturedList = getStringList(stats, "captured_pokemon");
        List<String> failedList = getStringList(stats, "failed_captures");
        List<String> legendarySpawns = getStringList(stats, "legendary_spawns");

        if (captures == 0 && failures == 0 && bossKills == 0 && pokemonKills == 0
                && legendarySpawns.isEmpty()) {
            resetStats();
            return null;
        }

        List<String> lines = new ArrayList<>();
        lines.add("§e§l=== Last Session Recap ===");
        lines.add("§7Last activity: §f" + lastUpdated);

        if (!capturedList.isEmpty()) {
            lines.add("§a\u2714 Captures: §f" + captures);
            for (String name : capturedList) {
                lines.add("  §7- §f" + name);
            }
        }

        if (!failedList.isEmpty()) {
            lines.add("§c\u2718 Failed captures: §f" + failures);
            for (String name : failedList) {
                lines.add("  §7- §f" + name);
            }
        }

        if (!legendarySpawns.isEmpty()) {
            lines.add("§d\u2605 Legendary spawns: §f" + legendarySpawns.size());
            for (String name : legendarySpawns) {
                lines.add("  §7- §f" + name);
            }
        }

        if (bossKills > 0) lines.add("§c\u2620 Boss kills: §f" + bossKills);
        if (pokemonKills > 0) lines.add("§7\u2694 Pokemon kills: §f" + pokemonKills);
        if (errors > 0) lines.add("§c\u26A0 Errors: §f" + errors);

        lines.add("§7Log: §oauto-qiqi/session-*.log");

        resetStats();
        return lines;
    }

    public void resetStats() {
        ensureInit();
        try {
            Files.deleteIfExists(statsFile);
        } catch (IOException ignored) {}
    }

    private static int getInt(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringList(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof List) return new ArrayList<>((List<String>) v);
        return new ArrayList<>();
    }

    /** Path to the log directory, for display purposes. */
    public Path getLogDir() {
        ensureInit();
        return dir;
    }
}
