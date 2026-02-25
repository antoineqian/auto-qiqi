package com.cobblemoon.autoqiqi.common;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Centralizes human-like timing randomization for GUI clicks,
 * command delays, and action cooldowns.
 */
public final class HumanDelay {
    private HumanDelay() {}

    /** Ticks to wait before clicking a GUI slot. 1.2s – 3s */
    public static int guiClickTicks() {
        return between(24, 60);
    }

    /** Ticks to wait between consecutive non-urgent idle actions. 6s – 14s */
    public static int actionCooldownTicks() {
        return between(120, 280);
    }

    /** Milliseconds to wait before sending a command. */
    public static int commandDelayMs(int minMs, int maxMs) {
        return between(Math.max(0, minMs), Math.max(minMs + 1, maxMs));
    }

    private static int between(int lo, int hi) {
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }
}
