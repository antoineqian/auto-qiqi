package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks legendary event timers across all known worlds.
 */
public class WorldTracker {
    private static final WorldTracker INSTANCE = new WorldTracker();

    private final Map<String, WorldTimerData> worldTimers = new LinkedHashMap<>();
    private String currentWorld = null;

    private WorldTracker() {}

    public static WorldTracker get() { return INSTANCE; }

    public void refreshWorldList() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        for (String world : config.worldNames) {
            worldTimers.computeIfAbsent(world, WorldTimerData::new);
        }
        worldTimers.keySet().retainAll(config.worldNames);
    }

    /** Resets all timer data to unknown state (for tests or manual repoll). */
    public void resetAllTimers() {
        for (WorldTimerData data : worldTimers.values()) {
            data.resetToUnknown();
        }
    }

    /** Resolves world name to canonical form from config (case-insensitive). */
    public String resolveWorldName(String worldName) {
        if (worldName == null) return null;
        if (worldTimers.containsKey(worldName)) return worldName;
        String lower = worldName.toLowerCase();
        for (String key : worldTimers.keySet()) {
            if (key.toLowerCase().equals(lower)) return key;
        }
        return worldName;
    }

    public void updateWorldTimer(String worldName, long remainingSeconds) {
        String resolved = resolveWorldName(worldName);
        WorldTimerData data = worldTimers.get(resolved);
        if (data != null) {
            long oldRemaining = data.isTimerKnown() ? data.getEstimatedRemainingSeconds() : -1;
            data.updateTimer(remainingSeconds);
            AutoQiqiClient.log("WorldTracker", "Timer updated: " + resolved
                    + " " + oldRemaining + "s -> " + remainingSeconds + "s"
                    + (remainingSeconds <= 0 ? " [EVENT/EXPIRED]" : ""));
        } else {
            AutoQiqiClient.log("WorldTracker", "Timer update IGNORED: no entry for '" + worldName + "' (resolved: '" + resolved + "')");
        }
    }

    public void setEventActive(String worldName, boolean active) {
        WorldTimerData data = worldTimers.get(worldName);
        if (data != null) {
            data.setEventActive(active);
            AutoQiqiClient.log("WorldTracker", "Event " + (active ? "ACTIVE" : "cleared") + " for " + worldName);
        }
    }

    public String getCurrentWorld() { return currentWorld; }
    public void setCurrentWorld(String world) {
        String canonical = resolveWorldName(world);
        if (canonical != null && !canonical.equals(this.currentWorld)) {
            AutoQiqiClient.log("WorldTracker", "Current world: " + this.currentWorld + " -> " + canonical);
        }
        this.currentWorld = canonical;
    }
    public Collection<WorldTimerData> getAllTimers() { return worldTimers.values(); }
    public WorldTimerData getTimer(String worldName) { return worldTimers.get(resolveWorldName(worldName)); }

    public String getWorldToSwitchTo() {
        AutoQiqiConfig config = AutoQiqiConfig.get();

        for (WorldTimerData data : worldTimers.values()) {
            if (data.isEventActive() && !data.getWorldName().equals(currentWorld)) {
                AutoQiqiClient.log("WorldTracker", "getWorldToSwitchTo: " + data.getWorldName() + " has active event");
                return data.getWorldName();
            }
        }

        String closest = null;
        long closestTime = Long.MAX_VALUE;

        for (WorldTimerData data : worldTimers.values()) {
            if (data.getWorldName().equals(currentWorld)) continue;
            if (!data.isTimerKnown()) continue;
            long remaining = data.getEstimatedRemainingSeconds();
            int threshold = data.getEffectiveSwitchThreshold(config.switchBeforeSeconds);
            if (remaining > 0 && remaining <= threshold && remaining < closestTime) {
                closest = data.getWorldName();
                closestTime = remaining;
            }
        }
        if (closest != null) {
            AutoQiqiClient.log("WorldTracker", "getWorldToSwitchTo: " + closest + " within threshold (" + closestTime + "s remaining)");
        }
        return closest;
    }

    public boolean hasOtherWorldsNeedingAttention() {
        if (currentWorld == null) return false;
        AutoQiqiConfig config = AutoQiqiConfig.get();
        for (WorldTimerData data : worldTimers.values()) {
            if (data.getWorldName().equals(currentWorld)) continue;
            if (!data.isTimerKnown()) continue;
            long remaining = data.getEstimatedRemainingSeconds();
            int threshold = data.getEffectiveSwitchThreshold(config.switchBeforeSeconds);
            if (remaining <= threshold) return true;
        }
        return false;
    }

    public boolean hasOtherWorldWithinSeconds(long seconds) {
        if (currentWorld == null) return false;
        for (WorldTimerData data : worldTimers.values()) {
            if (data.getWorldName().equals(currentWorld)) continue;
            if (!data.isTimerKnown()) continue;
            long remaining = data.getEstimatedRemainingSeconds();
            if (remaining >= 0 && remaining <= seconds) return true;
        }
        return false;
    }

    public boolean currentWorldHasImminentEvent() {
        if (currentWorld == null) return false;
        AutoQiqiConfig config = AutoQiqiConfig.get();
        WorldTimerData data = worldTimers.get(currentWorld);
        if (data == null || !data.isTimerKnown()) return false;
        long remaining = data.getEstimatedRemainingSeconds();
        int threshold = data.getEffectiveSwitchThreshold(config.switchBeforeSeconds);
        return remaining > 0 && remaining <= threshold;
    }

    public void markAllForRepoll() {
        AutoQiqiClient.log("WorldTracker", "markAllForRepoll: resetting all " + worldTimers.size() + " timers");
        for (WorldTimerData data : worldTimers.values()) {
            if (data.isTimerKnown()) data.updateTimer(-1);
        }
    }

    public String getWorldWithExpiredTimer(int cooldownSeconds) {
        for (WorldTimerData data : worldTimers.values()) {
            if (data.needsRepoll(cooldownSeconds)) return data.getWorldName();
        }
        return null;
    }

    public String getWorldWithUnknownTimer() {
        for (WorldTimerData data : worldTimers.values()) {
            if (!data.isTimerKnown()) return data.getWorldName();
        }
        return null;
    }

    public boolean allTimersKnown() {
        for (WorldTimerData data : worldTimers.values()) {
            if (!data.isTimerKnown()) return false;
        }
        return !worldTimers.isEmpty();
    }

    public int getKnownTimerCount() {
        int count = 0;
        for (WorldTimerData data : worldTimers.values()) {
            if (data.isTimerKnown()) count++;
        }
        return count;
    }

    public int getTotalWorldCount() {
        return worldTimers.size();
    }

    /**
     * When all timers are known, returns the world with the soonest event timer
     * (excluding the current world). Used for proactive camping.
     */
    public String getWorldWithSoonestTimer() {
        if (!allTimersKnown()) return null;

        String soonest = null;
        long soonestTime = Long.MAX_VALUE;

        for (WorldTimerData data : worldTimers.values()) {
            if (!data.isTimerKnown()) continue;
            long remaining = data.getEstimatedRemainingSeconds();
            if (remaining >= 0 && remaining < soonestTime) {
                soonestTime = remaining;
                soonest = data.getWorldName();
            }
        }

        if (soonest != null && soonest.equals(currentWorld)) return null;
        return soonest;
    }
}
