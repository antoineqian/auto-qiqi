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

    public String battleMode = "ROAMING";
    public long battleSelectDelay = 200;
    public double battleSwitchChance = 0.0;
    public int battleHealEveryN = 3;
    public int postBattlePartyUpPresses = 1;
    /** In Berserk mode: scan radius (blocks) for finding wild Pokemon to fight. Default 12. */
    public double berserkScanRange = 12.0;
    public List<String> battleTargetWhitelist = new ArrayList<>(List.of(
            "Combee", "Vespiquen", "Cutiefly", "Ribombee"
    ));
    /** Pokemon in this list always show as [WANT] in /pk scan, even if already caught. Case-insensitive. Reload with /pk reload. */
    public List<String> scanCaptureWhitelist = new ArrayList<>();

    /** Species for which all alternate forms are already captured. These won't trigger [FORM] alerts. Case-insensitive. */
    public List<String> formCompleteIgnoreList = new ArrayList<>();

    // ========================
    // Legendary
    // ========================

    public boolean predictionHudVisible = true;
    public int predictionHudMaxResults = 8;
    public int predictionRecomputeIntervalSeconds = 5;

    public int switchBeforeSeconds = 90;
    public int switchBeforeJitterSeconds = 10;
    public int pollIntervalSeconds = 60;
    public String nextlegCommand = "/nextleg";
    public String mondeCommand = "/monde";

    /** Poll /nextleg for single global timer, send /afk, move camera before expiry to stay eligible for legendary spawn. Used by both ROAMING mode and auto-hop fallback (no LegendTracker). */
    public boolean nextlegAfkEnabled = true;
    /** Command to gain AFK points (e.g. /afk). */
    public String afkCommand = "/afk";
    /** Seconds between /afk sends. */
    public int afkIntervalSeconds = 60;
    /** Seconds between /nextleg polls when using single global timer. */
    public int nextlegPollIntervalSeconds = 15;
    /** Move camera this many seconds before timer expiry to disable AFK and become eligible for legendary spawn. */
    public int cameraMoveSecondsBefore = 30;
    /** While engaged in a legendary battle, minimize the battle UI and nudge the camera every N seconds so the server doesn't flag us AFK. Default 540 = 9 min. Set 0 to disable. */
    public int roamingInBattleAntiAfkIntervalSeconds = 540;
    /** When true, at 1 min left on nextleg timer also send the world menu command (e.g. /monde) so the world selection GUI opens. */
    public boolean nextlegOpenMondeAt1Min = true;

    /** Legendaries in this list are completely ignored when they spawn — no engagement, no auto-hop pause, nothing. Names match game language; comparison is case-insensitive. */
    public List<String> legendaryIgnoreList = new ArrayList<>(List.of(
            "Tokotoro", "Tokopisco", "Électhor", "Favianos", "Fortusimia"
    ));

    /** Legendaries in this list are always targeted for kill (never capture), even when uncaught. Used in ROAMING and when a legendary spawns near the player. Names match game language; comparison is case-insensitive. */
    public List<String> legendaryKillWhitelist = new ArrayList<>(List.of(
            "Yveltal", "Groudon", "Koraidon", "Heatran", "Kyurem", "Genesect", "Registeel",
            "Ogerpon", "Tokopiyon", "Amovénus", "Boréas", "Kyogre", "Giratina", "Pêchaminus",
            "Regieleki", "Terrakium", "Rayquaza"
    ));

    /** Trigger auto-hop rotation when remaining seconds is at or below this. Default 180 = 3m. */
    public int autoHopThresholdSeconds = 180;

    /** Seconds to wait after legendary battle (capture/kill) before re-enabling auto-hop (loot pickup time). */
    public int autohopReEnableDelaySeconds = 5;

    /** Whether to /tpahere the alt account after auto-hop teleports (and safety TPs). */
    public boolean autohopTpaEnabled = false;
    /** Alt account name to /tpahere after auto-hop teleports to best world. Empty = disabled. */
    public String autohopTpaAltAccount = "KetaMaxxing";

    /** Auto-hop mode: "auto" = only auto* homes, "all" = all homes, "off" = disabled. */
    public String autohopMode = "auto";

    /** World+biome combos to always skip during auto-hop (all modes). Format: "World|biome_id" (case-insensitive world match). */
    public List<String> autohopSkipWorldBiomes = new ArrayList<>(List.of(
            "Lune|minecraft:mushroom_fields",
            "Soleil|minecraft:mushroom_fields"
    ));

    /** Minutes past midnight at which auto-hop switches from "auto" to "all" (e.g. 150 = 2:30). -1 = disabled. */
    public int autohopSwitchToAllHour = -1;

    /** Minutes past midnight at which auto-hop switches from "off" to "all" (e.g. 150 = 2:30). -1 = disabled. */
    public int autohopSwitchOffToAllHour = -1;

    /** Reconnect every N rotations to flush client-side dimension caches (0 = disabled).
     *  World-hopping accumulates client caches that slow down subsequent hops;
     *  a disconnect+reconnect clears them without a full game restart. */
    public int autohopReconnectEveryNRotations = 5;

    /** Round-robin homes for auto-hop when LegendTracker is not installed. Map of display name → teleport command. */
    public Map<String, String> autohopHomes = new LinkedHashMap<>(Map.of(
            "end", "/home end",
            "nether", "/home nether",
            "overworld", "/home overworld"
    ));

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

    public Map<String, String> worldArrivalHome = new HashMap<>();

    public String defaultTeleportMode = "last";
    public Map<String, String> worldTeleportModes = new HashMap<>();

    public String timerPattern = "(?i)(\\d+)\\s*minutes?\\s*(?:and|et)\\s*(\\d+)\\s*seconds?";
    public String timerPatternSecondsOnly = "(?i)(\\d+)\\s*seconds?";
    public String eventNowPattern = "(?i)un\\s+pokémon\\s+légendaire\\s+(?:est\\s+apparu|vient\\s+d'apparaître)";
    public String legendarySpawnPattern = "(?i)(.+?)\\s+est\\s+apparu.*?pr[eè]s\\s+de\\s+(\\S+)";

    public boolean pauseOnLegendarySpawn = true;
    public int pauseDurationSeconds = 300;
    public int repollCooldownSeconds = 30;
    public int eventRepollWaitSeconds = 15;
    public int commandDelayMinMs = 200;
    public int commandDelayMaxMs = 4000;
    public int homeTeleportWarmupSeconds = 4;
    /** If true, verify we are in the expected dimension (nether/overworld/end) before setting currentWorld after /home. Set false for servers with custom resource dimensions. */
    public boolean verifyHomeDimension = false;
    /** Cooldown (seconds) before re-attempting capture of a Pokemon that just escaped/failed. Prevents immediate retry loops. */
    public int failedCaptureCooldownSeconds = 60;
    public String worldDetectPattern = "(?i)(?:monde|world)\\s*:?\\s*(\\S+)";

    /** Minecraft ticks per real second for day/night cycle prediction. Vanilla = 20. Adjust if server uses a custom day cycle. */
    public int dayTickRate = 20;

    public int hudColor = 0xFFFFFF00;
    public int hudColorUrgent = 0xFFFF4444;
    public int hudColorReady = 0xFF44FF44;

    // ========================
    // Auto-reconnect
    // ========================

    public boolean autoReconnectEnabled = false;
    public int reconnectDelaySeconds = 10;
    /** Max seconds to wait for connection after clicking Rejoindre (covers e.g. ~1 min WiFi outage). */
    public int reconnectWaitConnectionSeconds = 90;
    public int reconnectMaxRetries = 5;
    public String reconnectButtonText = "Rejoindre";
    /** Button text to click to leave the disconnect/error screen (FR: "Retour à la liste des serveurs", EN: "Back to server list"). */
    public String reconnectBackToServerListButtonText = "Retour à la liste des serveurs";
    /** @deprecated No longer used — post-reconnect now picks a random home from legendtracker.properties. Kept for config compatibility. */
    public String reconnectHome = "";

    // ========================
    // Biome Discovery
    // ========================

    public boolean biomeDiscoveryEnabled = false;
    /** Target biome IDs to search for (e.g. "minecraft:jungle", "minecraft:mushroom_fields"). */
    public List<String> biomeDiscoveryTargets = new ArrayList<>(List.of("biomeswevegone:forgotten_forest",
        "biomeswevegone:pale_bog",
        "biomeswevegone:allium_shrubland",
        "biomeswevegone:white_mangrove_marshes",
        "biomeswevegone:weeping_witch_forest",
        "biomeswevegone:baobab_savanna",
        "minecraft:savanna_plateau",
        "minecraft:mushroom_fields",
        "minecraft:deep_ocean",
        "biomeswevegone:pumpkin_valley",
        "terralith:caldera",
        "terralith:cave/thermal_caves",
        "terralith:yellowstone",
        "wythers:calcite_caverns",
        "wythers:mediterranean_island_thermal_springs",
        "biomeswevegone:allium_shrubland",
        "wythers:snowy_thermal_taiga",
        "wythers:thermal_taiga_crags",
        "wythers:thermal_taiga",
        "wythers:tibesti_mountains",
        "wythers:danakil_desert",
        "biomeswevegone:cypress_swamplands",
        "minecraft:windswept_hills"
));
    /** Y altitude at which to start flying (ascend phase target). */
    public int biomeDiscoveryAltitude = 175;
    /** Max Y altitude to reach while flying. Player keeps ascending during flight until this height. */
    public int biomeDiscoveryMaxAltitude = 230;
    /** Stop flying when global timer drops to this many seconds (3:15 = 195). */
    public int biomeDiscoveryStopThresholdSeconds = 195;
    /** Wait this many seconds after timer reset before starting a new cycle. */
    public int biomeDiscoveryGraceSeconds = 10;

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

    public String getArrivalHome(String worldName) {
        return worldArrivalHome.get(worldName.toLowerCase());
    }

    // ========================
    // Config migration
    // ========================

    private void migrateResourceWorlds() {
        boolean hasOldRessources = worldNames.removeIf(w -> w.equalsIgnoreCase("Ressources"));
        if (!hasOldRessources) return;

        // Config migration (no stdout — chat-only mode)
        List<String> subWorlds = List.of("Ressources (Overworld)", "Ressources (Nether)", "Ressources (End)");
        for (String sw : subWorlds) {
            if (!worldNames.contains(sw)) worldNames.add(sw);
        }

        worldsWithSubMenu.removeIf(w -> w.equalsIgnoreCase("Ressources"));

        if (worldArrivalHome == null) worldArrivalHome = new HashMap<>();
    }

    /** Merge code-defined defaults into the loaded config (adds missing entries, never removes user entries). */
    private void mergeDefaults() {
        AutoQiqiConfig defaults = new AutoQiqiConfig();
        for (String biome : defaults.biomeDiscoveryTargets) {
            if (!biomeDiscoveryTargets.contains(biome)) {
                biomeDiscoveryTargets.add(biome);
            }
        }
    }

    /** Clamp Berserk scan range to sensible bounds. */
    public boolean enforceBattleConstraints() {
        boolean changed = false;
        if (berserkScanRange < 1.0) { berserkScanRange = 1.0; changed = true; }
        if (berserkScanRange > 128.0) { berserkScanRange = 128.0; changed = true; }
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
                INSTANCE.enforceBattleConstraints();
                INSTANCE.migrateResourceWorlds();
                INSTANCE.mergeDefaults();
                save();
            } catch (IOException e) {
                // Failed to load config (no file logging)
                INSTANCE = new AutoQiqiConfig();
            }
        } else {
            INSTANCE = new AutoQiqiConfig();
            INSTANCE.enforceBattleConstraints();
            save();
        }
    }

    public static void save() {
        try {
            Path path = getConfigPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(INSTANCE != null ? INSTANCE : new AutoQiqiConfig()));
        } catch (IOException e) {
            // Failed to save config (no file logging)
        }
    }
}
