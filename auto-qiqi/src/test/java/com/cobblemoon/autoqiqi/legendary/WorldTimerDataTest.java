package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldTimerDataTest {

    @BeforeEach
    void setUp() {
        AutoQiqiConfig.setInstance(new AutoQiqiConfig());
    }

    @Test
    void newTimerHasUnknownState() {
        WorldTimerData data = new WorldTimerData("world1");
        assertEquals("world1", data.getWorldName());
        assertFalse(data.isTimerKnown());
        assertFalse(data.isEventActive());
        assertEquals(-1, data.getEstimatedRemainingSeconds());
    }

    @Test
    void updateTimerSetsKnownState() {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(300);

        assertTrue(data.isTimerKnown());
        assertEquals(300, data.getRawRemainingSeconds());
        assertFalse(data.isEventActive());
    }

    @Test
    void updateTimerWithZeroActivatesEvent() {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(0);

        assertTrue(data.isTimerKnown());
        assertTrue(data.isEventActive());
        assertEquals(0, data.getEstimatedRemainingSeconds());
    }

    @Test
    void setEventActiveSetsTimerToZero() {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(300);
        data.setEventActive(true);

        assertTrue(data.isEventActive());
        assertEquals(0, data.getRawRemainingSeconds());
        assertEquals(0, data.getEstimatedRemainingSeconds());
    }

    @Test
    void estimatedRemainingDecreasesOverTime() throws InterruptedException {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(10);

        Thread.sleep(1100);

        long remaining = data.getEstimatedRemainingSeconds();
        assertTrue(remaining >= 8 && remaining <= 9,
                "After ~1s, 10s timer should show 8-9s remaining, got " + remaining);
    }

    @Test
    void estimatedRemainingNeverGoesBelowZero() throws InterruptedException {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(1);

        Thread.sleep(1500);

        assertEquals(0, data.getEstimatedRemainingSeconds());
    }

    // ── getFormattedTime ──

    @Test
    void formattedTimeUnknownTimer() {
        WorldTimerData data = new WorldTimerData("world1");
        assertEquals("??:??", data.getFormattedTime());
    }

    @Test
    void formattedTimeZeroShowsDoubleZero() {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(0);
        assertEquals("00:00", data.getFormattedTime());
    }

    @Test
    void formattedTimeMinutesAndSeconds() {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(125); // 2m5s
        String formatted = data.getFormattedTime();
        assertTrue(formatted.matches("\\d{2}m\\d{2}s"), "Expected XXmXXs format, got: " + formatted);
    }

    @Test
    void formattedTimeHoursMinutesSeconds() {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(3661); // 1h1m1s
        String formatted = data.getFormattedTime();
        assertTrue(formatted.matches("\\d+h\\d{2}m\\d{2}s"), "Expected XhXXmXXs format, got: " + formatted);
    }

    // ── effectiveSwitchThreshold ──

    @Test
    void effectiveSwitchThresholdAddsJitter() {
        WorldTimerData data = new WorldTimerData("world1");
        int threshold = data.getEffectiveSwitchThreshold(90);
        assertTrue(threshold >= 90, "Threshold should be >= base (90), got " + threshold);
        // Default jitter max is 15
        assertTrue(threshold <= 105, "Threshold should be <= 105 (90+15), got " + threshold);
    }

    @Test
    void effectiveSwitchThresholdWithZeroJitter() {
        AutoQiqiConfig config = new AutoQiqiConfig();
        config.switchBeforeJitterSeconds = 0;
        AutoQiqiConfig.setInstance(config);

        WorldTimerData data = new WorldTimerData("world1");
        assertEquals(90, data.getEffectiveSwitchThreshold(90));
    }

    // ── needsRepoll ──

    @Test
    void needsRepollFalseWhenTimerUnknown() {
        WorldTimerData data = new WorldTimerData("world1");
        assertFalse(data.needsRepoll(30));
    }

    @Test
    void needsRepollFalseWhenTimerStillRunning() {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(300);
        assertFalse(data.needsRepoll(30));
    }

    @Test
    void needsRepollTrueWhenExpiredAndCooldownPassed() throws InterruptedException {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(0);

        Thread.sleep(1100);

        assertTrue(data.needsRepoll(1));
    }

    // ── resetTimer to unknown ──

    @Test
    void updateTimerWithNegativeOneResetsToUnknown() {
        WorldTimerData data = new WorldTimerData("world1");
        data.updateTimer(300);
        assertTrue(data.isTimerKnown());

        data.updateTimer(-1);
        assertFalse(data.isTimerKnown());
    }
}
