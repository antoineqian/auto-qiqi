package com.cobblemoon.qiqitimer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config for qiqi-timer. Stored in config/qiqi-timer.json.
 */
public class QiqiTimerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;

    private static QiqiTimerConfig INSTANCE;

    static Path getConfigPath() {
        if (configPath == null) {
            configPath = FabricLoader.getInstance().getConfigDir().resolve("qiqi-timer.json");
        }
        return configPath;
    }

    public static QiqiTimerConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public static QiqiTimerConfig load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                QiqiTimerConfig c = GSON.fromJson(json, QiqiTimerConfig.class);
                if (c != null) return c;
            } catch (IOException e) {
                System.err.println("[Qiqi-Timer] Failed to load config: " + e.getMessage());
            }
        }
        QiqiTimerConfig c = new QiqiTimerConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(getConfigPath().getParent());
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[Qiqi-Timer] Failed to save config: " + e.getMessage());
        }
    }

    /** Command to poll (e.g. /nextleg). */
    public String nextlegCommand = "/nextleg";

    /** Seconds between /nextleg polls. */
    public int pollIntervalSeconds = 60;

    /** Show HUD. */
    public boolean hudVisible = true;

    /** Regex for "X minutes and Y seconds". Group 1 = minutes, group 2 = seconds. */
    public String timerPattern = "(?i)(\\d+)\\s*minutes?\\s*(?:and|et)\\s*(\\d+)\\s*seconds?";

    /** Regex for "X seconds" only. Group 1 = seconds. */
    public String timerPatternSecondsOnly = "(?i)(\\d+)\\s*seconds?";

    /** When true, send K+J when remaining time is at or below sendJThresholdSeconds. (Name is legacy; threshold is 30 sec by default.) */
    public boolean sendJAt1MinLeft = true;

    /** Send K+J when remaining seconds is in [0, this]. Default 30 = 30 seconds before next leg. */
    public int sendJThresholdSeconds = 30;

    /** GLFW key code for the "Toggle Autohop" keybinding default. Default 79 = O. See GLFW key constants (e.g. key.keyboard.o = 79). */
    public int toggleAutohopKeyCode = 79;

    /** Enable auto-hop rotation (visits auto_ homes, ranks by EV, teleports to best). Replaces old K+J autohop. */
    public boolean autoHopEnabled = true;

    /** Trigger auto-hop rotation when remaining seconds is at or below this. Default 90 = 1m30s. */
    public int autoHopThresholdSeconds = 90;
}
