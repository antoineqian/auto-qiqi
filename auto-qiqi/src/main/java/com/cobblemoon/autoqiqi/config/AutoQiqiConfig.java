package com.cobblemoon.autoqiqi.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Unified configuration for all Auto-Qiqi features.
 * Stored as JSON in config/auto-qiqi.json
 */
public class AutoQiqiConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;

    private static AutoQiqiConfig INSTANCE;

    static Path getConfigPath() {
        if (configPath == null) {
            configPath = FabricLoader.getInstance().getConfigDir().resolve("auto-qiqi.json");
        }
        return configPath;
    }

    // ========================
    // Battle
    // ========================

    public String battleMode = "OFF";
    public long battleSelectDelay = 200;
    public double battleSwitchChance = 0.0;
    public int battleHealEveryN = 3;
    public int postBattlePartyUpPresses = 1;
    public List<String> battleTargetWhitelist = new ArrayList<>(List.of(
            "Combee", "Vespiquen", "Cutiefly", "Ribombee"
    ));

    // ========================
    // Legendary
    // ========================

    public boolean legendaryEnabled = true;
    public boolean legendaryAutoSwitch = false;
    public boolean legendaryHudVisible = true;

    public int switchBeforeSeconds = 90;
    public int switchBeforeJitterSeconds = 15;
    public int pollIntervalSeconds = 60;
    public String nextlegCommand = "/nextleg";
    public String mondeCommand = "/monde";

    public List<String> worldNames = new ArrayList<>(List.of(
            "Monde Construction (Lune)",
            "Monde Construction (Soleil)",
            "Monde Construction (Ultra-Lune)",
            "Monde Construction (Ultra-Soleil)",
            "Ressources (Overworld)",
            "Ressources (Nether)",
            "Ressources (End)"
    ));

    public List<String> worldsWithSubMenu = new ArrayList<>();

    public Map<String, String> homeWorlds = new HashMap<>(Map.of(
            "ressources (overworld)", "overworld",
            "ressources (nether)", "nether",
            "ressources (end)", "end"
    ));

    public Map<String, String> worldArrivalHome = new HashMap<>();

    public String defaultTeleportMode = "last";
    public Map<String, String> worldTeleportModes = new HashMap<>();

    public String timerPattern = "(?i)(\\d+)\\s*minutes?\\s*(?:and|et)\\s*(\\d+)\\s*seconds?";
    public String timerPatternSecondsOnly = "(?i)(\\d+)\\s*seconds?";
    public String eventNowPattern = "(?i)un\\s+pokémon\\s+légendaire\\s+(?:est\\s+apparu|vient\\s+d'apparaître)";
    public String legendarySpawnPattern = "(?i)(.+?)\\s+est\\s+apparu.*?pr[eè]s\\s+de\\s+(\\S+)";

    public boolean legendarySpawnSoundEnabled = true;
    public boolean legendarySpawnSoundOnlyForMe = false;
    public int legendarySpawnSoundRepeats = 3;
    public boolean pauseOnLegendarySpawn = true;
    public int pauseDurationSeconds = 300;
    public int repollCooldownSeconds = 30;
    public int eventRepollWaitSeconds = 15;
    public int commandDelayMinMs = 500;
    public int commandDelayMaxMs = 4000;
    public int homeTeleportWarmupSeconds = 4;
    /** If true, verify we are in the expected dimension (nether/overworld/end) before setting currentWorld after /home. Set false for servers with custom resource dimensions. */
    public boolean verifyHomeDimension = false;
    /** Cooldown (seconds) before re-attempting capture of a Pokemon that just escaped/failed. Prevents immediate retry loops. */
    public int failedCaptureCooldownSeconds = 60;
    public String worldDetectPattern = "(?i)(?:monde|world)\\s*:?\\s*(\\S+)";

    public int hudColor = 0xFFFFFF00;
    public int hudColorUrgent = 0xFFFF4444;
    public int hudColorReady = 0xFF44FF44;

    // ========================
    // Auto-reconnect
    // ========================

    public boolean autoReconnectEnabled = false;
    public int reconnectDelaySeconds = 10;
    public int reconnectMaxRetries = 5;
    public String reconnectButtonText = "Rejoindre";

    // ========================
    // Mining (Nether Gold Ore)
    // ========================

    public boolean goldMiningEnabled = false;
    public int goldMiningDurabilitySafetyMargin = 10;
    public long goldMiningRepairCooldownMs = 21_600_000L; // 6 hours
    public long goldMiningLastRepairTimeMs = 0L;

    // ========================
    // Walk (circle)
    // ========================

    public boolean walkEnabled = false;

    // ========================
    // Fish
    // ========================

    public boolean fishEnabled = true;
    public boolean fishMultiRod = false;
    public boolean fishNoBreak = false;
    public boolean fishPersistentMode = false;
    public boolean fishAutoAim = true;
    public boolean fishUseSoundDetection = false;
    public boolean fishForceMPDetection = false;
    public long fishRecastDelay = 1500;
    public String fishClearLagRegex = "\\[ClearLag\\] Removed [0-9]+ Entities!";
    public int fishDurabilitySafetyMargin = 10;
    public boolean fishAutoRepair = true;
    public long fishRepairCooldownMs = 21_600_000L; // 6 hours
    public long lastRepairTimeMs = 0L;

    // ========================
    // Teleport mode helpers
    // ========================

    public String getTeleportMode(String worldName) {
        String mode = worldTeleportModes.get(worldName.toLowerCase());
        return mode != null ? mode : defaultTeleportMode;
    }

    public void setTeleportMode(String worldName, String mode) {
        worldTeleportModes.put(worldName.toLowerCase(), mode);
        save();
    }

    public void setDefaultTeleportMode(String mode) {
        this.defaultTeleportMode = mode;
        save();
    }

    // ========================
    // Home world helpers
    // ========================

    public boolean isHomeWorld(String worldName) {
        return homeWorlds.containsKey(worldName.toLowerCase());
    }

    public String getHomeCommand(String worldName) {
        return homeWorlds.get(worldName.toLowerCase());
    }

    public String getArrivalHome(String worldName) {
        return worldArrivalHome.get(worldName.toLowerCase());
    }

    // ========================
    // Config migration
    // ========================

    private void migrateResourceWorlds() {
        boolean hasOldRessources = worldNames.removeIf(w -> w.equalsIgnoreCase("Ressources"));
        if (!hasOldRessources) return;

        System.out.println("[Auto-Qiqi] Migrating config: splitting 'Ressources' into 3 sub-worlds");
        List<String> subWorlds = List.of("Ressources (Overworld)", "Ressources (Nether)", "Ressources (End)");
        for (String sw : subWorlds) {
            if (!worldNames.contains(sw)) worldNames.add(sw);
        }

        if (homeWorlds == null) homeWorlds = new HashMap<>();
        homeWorlds.putIfAbsent("ressources (overworld)", "overworld");
        homeWorlds.putIfAbsent("ressources (nether)", "nether");
        homeWorlds.putIfAbsent("ressources (end)", "end");

        worldsWithSubMenu.removeIf(w -> w.equalsIgnoreCase("Ressources"));

        if (worldArrivalHome == null) worldArrivalHome = new HashMap<>();
    }

    // ========================
    // Fish config constraints
    // ========================

    public boolean enforceFishConstraints() {
        boolean changed = false;
        if (fishRecastDelay < 500) {
            fishRecastDelay = 500;
            changed = true;
        }
        if (fishClearLagRegex == null) {
            fishClearLagRegex = "";
            changed = true;
        }
        return changed;
    }

    // ========================
    // Persistence
    // ========================

    public static AutoQiqiConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    /** Inject a config instance directly (used by tests). */
    public static void setInstance(AutoQiqiConfig config) {
        INSTANCE = config;
    }

    public static void load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                INSTANCE = GSON.fromJson(json, AutoQiqiConfig.class);
                if (INSTANCE == null) {
                    INSTANCE = new AutoQiqiConfig();
                }
                INSTANCE.enforceFishConstraints();
                INSTANCE.migrateResourceWorlds();
                save();
            } catch (IOException e) {
                System.err.println("[Auto-Qiqi] Failed to load config: " + e.getMessage());
                INSTANCE = new AutoQiqiConfig();
            }
        } else {
            INSTANCE = new AutoQiqiConfig();
            save();
        }
    }

    public static void save() {
        try {
            Path path = getConfigPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(INSTANCE != null ? INSTANCE : new AutoQiqiConfig()));
        } catch (IOException e) {
            System.err.println("[Auto-Qiqi] Failed to save config: " + e.getMessage());
        }
    }
}
