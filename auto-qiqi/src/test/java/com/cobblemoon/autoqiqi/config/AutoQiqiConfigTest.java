package com.cobblemoon.autoqiqi.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoQiqiConfigTest {

    private AutoQiqiConfig config;

    @BeforeEach
    void setUp() {
        config = new AutoQiqiConfig();
        AutoQiqiConfig.setInstance(config);
    }

    // ── Teleport mode helpers ──

    @Test
    void getTeleportModeReturnsDefaultWhenNoOverride() {
        assertEquals("last", config.getTeleportMode("anyWorld"));
    }

    @Test
    void getTeleportModeReturnsOverrideWhenSet() {
        config.worldTeleportModes.put("world1", "spawn");
        assertEquals("spawn", config.getTeleportMode("World1"));
    }

    @Test
    void getTeleportModeCaseInsensitive() {
        config.worldTeleportModes.put("world1", "home");
        assertEquals("home", config.getTeleportMode("WORLD1"));
        assertEquals("home", config.getTeleportMode("World1"));
    }

    @Test
    void defaultTeleportModeUsedAsFallback() {
        config.defaultTeleportMode = "spawn";
        assertEquals("spawn", config.getTeleportMode("unknownWorld"));
    }

    // ── Default values ──

    @Test
    void defaultBattleModeIsRoaming() {
        assertEquals("ROAMING", config.battleMode);
    }

    @Test
    void defaultWorldNamesPopulated() {
        assertFalse(config.worldNames.isEmpty());
        assertEquals(7, config.worldNames.size());
    }

    @Test
    void defaultSwitchBeforeSeconds() {
        assertEquals(90, config.switchBeforeSeconds);
    }

    @Test
    void defaultSwitchJitter() {
        assertEquals(10, config.switchBeforeJitterSeconds);
    }

    // ── get() returns the injected instance ──

    @Test
    void getReturnsInjectedInstance() {
        assertSame(config, AutoQiqiConfig.get());
    }
}
