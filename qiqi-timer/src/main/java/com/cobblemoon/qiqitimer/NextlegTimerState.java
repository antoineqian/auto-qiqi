package com.cobblemoon.qiqitimer;

/**
 * Holds the single global nextleg timer state (remaining seconds, last update).
 * When the display has been at 00:00 for longer than this, we treat the timer as expired
 * and show ??:?? until the next successful /nextleg parse.
 */
public class NextlegTimerState {
    private static final NextlegTimerState INSTANCE = new NextlegTimerState();
    /** After being at 0 for this long (ms), show ??:?? so we don't appear "stuck at 00". */
    private static final long EXPIRED_AFTER_MS = 60_000;

    public static NextlegTimerState get() {
        return INSTANCE;
    }

    private long remainingSeconds = -1;
    private long lastUpdatedAt = 0;

    public void updateTimer(long seconds) {
        this.remainingSeconds = seconds;
        this.lastUpdatedAt = System.currentTimeMillis();
    }

    /** -1 if unknown or expired (stuck at 0 for too long). */
    public long getEstimatedRemainingSeconds() {
        if (remainingSeconds < 0) return -1;
        long elapsed = (System.currentTimeMillis() - lastUpdatedAt) / 1000;
        long remaining = remainingSeconds - elapsed;
        if (remaining <= 0 && (System.currentTimeMillis() - lastUpdatedAt) > EXPIRED_AFTER_MS) {
            return -1; // expired: show ??:?? until next poll updates us
        }
        return Math.max(0, remaining);
    }

    public boolean isTimerKnown() {
        return remainingSeconds >= 0;
    }

    public String getFormattedTime() {
        if (!isTimerKnown()) return "??:??";
        long secs = getEstimatedRemainingSeconds();
        if (secs < 0) return "??:??";  // expired (stuck at 0 too long)
        if (secs == 0) return "00:00";
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
