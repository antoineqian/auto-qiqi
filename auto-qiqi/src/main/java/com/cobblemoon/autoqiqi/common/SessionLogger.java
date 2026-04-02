package com.cobblemoon.autoqiqi.common;

import java.util.List;

/**
 * Session "logger" that does not write to disk (chat-only mode).
 * All methods are no-ops; no session-*.log or session-stats.json are created.
 * Use AutoQiqiClient.log(module, message) for in-game chat output.
 */
public final class SessionLogger {

    private static final SessionLogger INSTANCE = new SessionLogger();

    private SessionLogger() {}

    public static SessionLogger get() { return INSTANCE; }

    public void logEvent(String category, String message) {}
    public void logCapture(String pokemonName, int level, boolean legendary, int ballsThrown) {}
    public void logCaptureFailed(String pokemonName, int level, boolean legendary, int ballsThrown, String reason) {}
    public void logKill(String pokemonName, boolean boss) {}
    public void logLegendarySpawn(String pokemonName, String world, boolean nearPlayer) {}
    public void logWorldSwitch(String from, String to) {}
    public void logBattleStart(String pokemonName, int level, String action) {}
    public void logBallThrow(String pokemonName, String ballType, int throwNumber) {}
    public void logInfo(String message) {}

    /** No persisted stats; recap is never available. */
    public List<String> getLastSessionRecap() {
        return null;
    }

    public void resetStats() {}
}
