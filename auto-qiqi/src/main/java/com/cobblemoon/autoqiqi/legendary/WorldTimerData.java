package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Tracks the legendary event timer data for a single world.
 */
public class WorldTimerData {
    private final String worldName;
    private long remainingSeconds = -1;
    private long lastUpdatedAt = 0;
    private boolean eventActive = false;
    private int switchJitterSeconds = 0;

    public WorldTimerData(String worldName) {
        this.worldName = worldName;
        rerollJitter();
    }

    public String getWorldName() { return worldName; }

    public void updateTimer(long seconds) {
        this.remainingSeconds = seconds;
        this.lastUpdatedAt = System.currentTimeMillis();
        this.eventActive = (seconds <= 0);
        rerollJitter();
    }

    public void setEventActive(boolean active) {
        this.eventActive = active;
        if (active) this.remainingSeconds = 0;
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    /** Resets to unknown timer state (for tests or manual repoll). */
    public void resetToUnknown() {
        this.remainingSeconds = -1;
        this.eventActive = false;
        this.lastUpdatedAt = 0;
        rerollJitter();
    }

    public long getEstimatedRemainingSeconds() {
        if (remainingSeconds < 0) return -1;
        if (eventActive) return 0;
        long elapsed = (System.currentTimeMillis() - lastUpdatedAt) / 1000;
        return Math.max(0, remainingSeconds - elapsed);
    }

    public long getRawRemainingSeconds() { return remainingSeconds; }
    public boolean isEventActive() { return eventActive; }
    public boolean isTimerKnown() { return remainingSeconds >= 0; }
    public long getLastUpdatedAt() { return lastUpdatedAt; }

    public int getEffectiveSwitchThreshold(int baseSwitchBefore) {
        return baseSwitchBefore + switchJitterSeconds;
    }

    private void rerollJitter() {
        int maxJitter = AutoQiqiConfig.get().switchBeforeJitterSeconds;
        this.switchJitterSeconds = maxJitter > 0
                ? ThreadLocalRandom.current().nextInt(0, maxJitter + 1) : 0;
    }

    public boolean needsRepoll(int cooldownSeconds) {
        if (!isTimerKnown()) return false;
        if (getEstimatedRemainingSeconds() > 0) return false;
        long secondsSinceUpdate = (System.currentTimeMillis() - lastUpdatedAt) / 1000;
        return secondsSinceUpdate >= cooldownSeconds;
    }

    public long getSecondsSinceLastUpdate() {
        if (lastUpdatedAt == 0) return Long.MAX_VALUE;
        return (System.currentTimeMillis() - lastUpdatedAt) / 1000;
    }

    public String getFormattedTime() {
        if (!isTimerKnown()) return "??:??";
        long secs = getEstimatedRemainingSeconds();
        if (secs <= 0) return "00:00";
        long minutes = secs / 60;
        long seconds = secs % 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format("%dh%02dm%02ds", hours, minutes, seconds);
        }
        return String.format("%02dm%02ds", minutes, seconds);
    }
}
