package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldTrackerTest {

    private WorldTracker tracker;

    @BeforeEach
    void setUp() {
        AutoQiqiConfig config = new AutoQiqiConfig();
        config.worldNames = List.of("world-a", "world-b", "world-c");
        config.switchBeforeSeconds = 90;
        config.switchBeforeJitterSeconds = 0; // deterministic for tests
        AutoQiqiConfig.setInstance(config);

        tracker = WorldTracker.get();
        tracker.setCurrentWorld(null);
        tracker.refreshWorldList();
        tracker.resetAllTimers();
    }

    // ── refreshWorldList ──

    @Test
    void refreshWorldListCreatesTimers() {
        assertEquals(3, tracker.getAllTimers().size());
        assertNotNull(tracker.getTimer("world-a"));
        assertNotNull(tracker.getTimer("world-b"));
        assertNotNull(tracker.getTimer("world-c"));
    }

    @Test
    void refreshWorldListRemovesStaleWorlds() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        config.worldNames = List.of("world-a");
        tracker.refreshWorldList();

        assertEquals(1, tracker.getAllTimers().size());
        assertNull(tracker.getTimer("world-b"));
    }

    @Test
    void refreshWorldListAddsNewWorlds() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        config.worldNames = List.of("world-a", "world-b", "world-c", "world-d");
        tracker.refreshWorldList();

        assertEquals(4, tracker.getAllTimers().size());
        assertNotNull(tracker.getTimer("world-d"));
    }

    // ── updateWorldTimer / setEventActive ──

    @Test
    void updateWorldTimerSetsTimer() {
        tracker.updateWorldTimer("world-a", 300);
        assertTrue(tracker.getTimer("world-a").isTimerKnown());
        assertEquals(300, tracker.getTimer("world-a").getRawRemainingSeconds());
    }

    @Test
    void updateWorldTimerIgnoresUnknownWorld() {
        tracker.updateWorldTimer("nonexistent", 300);
        assertNull(tracker.getTimer("nonexistent"));
    }

    @Test
    void setEventActiveMarksWorldReady() {
        tracker.setEventActive("world-b", true);
        assertTrue(tracker.getTimer("world-b").isEventActive());
    }

    // ── getWorldToSwitchTo ──

    @Test
    void switchToActiveEventWorld() {
        tracker.setCurrentWorld("world-a");
        tracker.setEventActive("world-b", true);

        assertEquals("world-b", tracker.getWorldToSwitchTo());
    }

    @Test
    void switchToActiveEventWorldPrioritizesOverThreshold() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-b", 60); // within threshold (90s)
        tracker.setEventActive("world-c", true);

        assertEquals("world-c", tracker.getWorldToSwitchTo());
    }

    @Test
    void switchToClosestWorldWithinThreshold() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-b", 80);  // within 90s threshold
        tracker.updateWorldTimer("world-c", 50);  // closer, also within threshold

        assertEquals("world-c", tracker.getWorldToSwitchTo());
    }

    @Test
    void noSwitchWhenAllTimersAboveThreshold() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-b", 200);
        tracker.updateWorldTimer("world-c", 300);

        assertNull(tracker.getWorldToSwitchTo());
    }

    @Test
    void noSwitchToCurrentWorld() {
        tracker.setCurrentWorld("world-a");
        tracker.setEventActive("world-a", true);

        assertNull(tracker.getWorldToSwitchTo());
    }

    @Test
    void noSwitchWhenTimersUnknown() {
        tracker.setCurrentWorld("world-a");
        assertNull(tracker.getWorldToSwitchTo());
    }

    // ── hasOtherWorldsNeedingAttention ──

    @Test
    void hasOtherWorldsNeedingAttentionTrueWhenWithinThreshold() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-b", 60);

        assertTrue(tracker.hasOtherWorldsNeedingAttention());
    }

    @Test
    void hasOtherWorldsNeedingAttentionFalseWhenAllFar() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-b", 200);
        tracker.updateWorldTimer("world-c", 300);

        assertFalse(tracker.hasOtherWorldsNeedingAttention());
    }

    @Test
    void hasOtherWorldsNeedingAttentionFalseWhenNoCurrentWorld() {
        tracker.updateWorldTimer("world-b", 10);
        assertFalse(tracker.hasOtherWorldsNeedingAttention());
    }

    // ── hasOtherWorldWithinSeconds ──

    @Test
    void hasOtherWorldWithinSecondsTrue() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-b", 30);

        assertTrue(tracker.hasOtherWorldWithinSeconds(60));
    }

    @Test
    void hasOtherWorldWithinSecondsFalse() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-b", 120);

        assertFalse(tracker.hasOtherWorldWithinSeconds(60));
    }

    @Test
    void hasOtherWorldWithinSecondsIgnoresCurrentWorld() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-a", 10);

        assertFalse(tracker.hasOtherWorldWithinSeconds(60));
    }

    // ── allTimersKnown ──

    @Test
    void allTimersKnownFalseInitially() {
        assertFalse(tracker.allTimersKnown());
    }

    @Test
    void allTimersKnownTrueWhenAllSet() {
        tracker.updateWorldTimer("world-a", 100);
        tracker.updateWorldTimer("world-b", 200);
        tracker.updateWorldTimer("world-c", 300);

        assertTrue(tracker.allTimersKnown());
    }

    @Test
    void allTimersKnownFalseWhenOneMissing() {
        tracker.updateWorldTimer("world-a", 100);
        tracker.updateWorldTimer("world-b", 200);

        assertFalse(tracker.allTimersKnown());
    }

    // ── getWorldWithUnknownTimer ──

    @Test
    void getWorldWithUnknownTimerReturnsFirst() {
        assertNotNull(tracker.getWorldWithUnknownTimer());
    }

    @Test
    void getWorldWithUnknownTimerNullWhenAllKnown() {
        tracker.updateWorldTimer("world-a", 100);
        tracker.updateWorldTimer("world-b", 200);
        tracker.updateWorldTimer("world-c", 300);

        assertNull(tracker.getWorldWithUnknownTimer());
    }

    // ── getWorldWithSoonestTimer ──

    @Test
    void getWorldWithSoonestTimerReturnsClosest() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-a", 500);
        tracker.updateWorldTimer("world-b", 100);
        tracker.updateWorldTimer("world-c", 200);

        assertEquals("world-b", tracker.getWorldWithSoonestTimer());
    }

    @Test
    void getWorldWithSoonestTimerNullIfSoonestIsCurrent() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-a", 50);
        tracker.updateWorldTimer("world-b", 100);
        tracker.updateWorldTimer("world-c", 200);

        assertNull(tracker.getWorldWithSoonestTimer());
    }

    @Test
    void getWorldWithSoonestTimerNullWhenNotAllKnown() {
        tracker.updateWorldTimer("world-a", 100);
        assertNull(tracker.getWorldWithSoonestTimer());
    }

    // ── markAllForRepoll ──

    @Test
    void markAllForRepollResetsAllTimers() {
        tracker.updateWorldTimer("world-a", 100);
        tracker.updateWorldTimer("world-b", 200);
        tracker.updateWorldTimer("world-c", 300);
        assertTrue(tracker.allTimersKnown());

        tracker.markAllForRepoll();
        assertFalse(tracker.allTimersKnown());
    }

    // ── currentWorldHasImminentEvent ──

    @Test
    void currentWorldHasImminentEventTrue() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-a", 60);

        assertTrue(tracker.currentWorldHasImminentEvent());
    }

    @Test
    void currentWorldHasImminentEventFalseWhenFar() {
        tracker.setCurrentWorld("world-a");
        tracker.updateWorldTimer("world-a", 200);

        assertFalse(tracker.currentWorldHasImminentEvent());
    }

    @Test
    void currentWorldHasImminentEventFalseWhenNoCurrentWorld() {
        assertFalse(tracker.currentWorldHasImminentEvent());
    }
}
