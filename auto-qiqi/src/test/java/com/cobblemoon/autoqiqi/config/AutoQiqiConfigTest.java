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

    // ── Fish constraints ──

    @Test
    void enforceFishConstraintsClampsLowRecastDelay() {
        config.fishRecastDelay = 100;
        boolean changed = config.enforceFishConstraints();

        assertTrue(changed);
        assertEquals(500, config.fishRecastDelay);
    }

    @Test
    void enforceFishConstraintsNoChangeWhenValid() {
        config.fishRecastDelay = 1500;
        config.fishClearLagRegex = "test";
        boolean changed = config.enforceFishConstraints();

        assertFalse(changed);
    }

    @Test
    void enforceFishConstraintsFixesNullRegex() {
        config.fishClearLagRegex = null;
        boolean changed = config.enforceFishConstraints();

        assertTrue(changed);
        assertEquals("", config.fishClearLagRegex);
    }

    @Test
    void enforceFishConstraintsEdgeCaseExactly500() {
        config.fishRecastDelay = 500;
        boolean changed = config.enforceFishConstraints();
        assertFalse(changed);
        assertEquals(500, config.fishRecastDelay);
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
    void defaultLegendaryEnabled() {
        assertTrue(config.legendaryEnabled);
    }

    @Test
    void defaultSwitchBeforeSeconds() {
        assertEquals(90, config.switchBeforeSeconds);
    }

    @Test
    void defaultSwitchJitter() {
        assertEquals(15, config.switchBeforeJitterSeconds);
    }

    // ── get() returns the injected instance ──

    @Test
    void getReturnsInjectedInstance() {
        assertSame(config, AutoQiqiConfig.get());
    }
}
