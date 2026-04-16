package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.AutoQiqiClient;

import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the LegendTracker MariaDB database for timer data and feeds it
 * into our local {@link WorldTracker} so we always have up-to-date
 * legendary spawn predictions without needing /nextleg chat commands.
 */
public class LegendTimerSync {

    private static final LegendTimerSync INSTANCE = new LegendTimerSync();
    public static LegendTimerSync get() { return INSTANCE; }

    private static final String JDBC_URL = "jdbc:mariadb://92.222.85.61:3355/LEGENDTRACKER";
    private static final String DB_USER = "lt";
    private static final String DB_PASS = "JamesMcChib!";
    private static final String QUERY = "SELECT WORLD, SPAWN_TICK_TARGET, SYNC_UTC, WORLD_TICKS_AT_SYNC, TPS FROM TIMER_V2";

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    /** Last raw rows we fetched, keyed by world name. */
    private volatile Map<String, TimerRow> lastRows = Map.of();

    /** Epoch millis of last successful DB fetch. 0 = never synced. */
    private volatile long lastSuccessMs = 0;
    /** Epoch millis of last failed DB fetch. 0 = no failure. */
    private volatile long lastFailureMs = 0;
    /** Total successful polls since start. */
    private volatile int successCount = 0;
    /** Total failed polls since start. */
    private volatile int failureCount = 0;
    /** Last failure message. */
    private volatile String lastError = null;

    /** Rows older than this (vs the freshest row) are considered stale. */
    private static final long STALE_THRESHOLD_MS = 120_000; // 2 minutes

    private LegendTimerSync() {}

    /** Raw DB row. */
    public record TimerRow(String world, long spawnTickTarget, long syncUtcMs, long worldTicksAtSync, double tps) {

        /** Remaining seconds until spawn, using the same formula as LegendTracker. */
        public long remainingSeconds() {
            if (spawnTickTarget <= 0) return -1;
            double tickRate = tps > 0 ? tps : 20.0;
            long elapsedMs = System.currentTimeMillis() - syncUtcMs;
            long extraTicks = (long) (elapsedMs * tickRate / 1000.0);
            long remaining = spawnTickTarget - (worldTicksAtSync + extraTicks);
            return Math.max(0, (long) Math.ceil(remaining / tickRate));
        }
    }

    public void start(int intervalSeconds) {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "legend-timer-sync");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::poll, 0, intervalSeconds, TimeUnit.SECONDS);
        AutoQiqiClient.logDebug("LegendTimerSync", "Started (interval=" + intervalSeconds + "s)");
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        AutoQiqiClient.logDebug("LegendTimerSync", "Stopped");
    }

    public boolean isRunning() { return running; }
    public Map<String, TimerRow> getLastRows() { return lastRows; }
    public long getLastSuccessMs() { return lastSuccessMs; }
    public long getLastFailureMs() { return lastFailureMs; }
    public int getSuccessCount() { return successCount; }
    public int getFailureCount() { return failureCount; }
    public String getLastError() { return lastError; }

    /**
     * Returns the row with the most recent SYNC_UTC.
     * Since the legendary timer is global, this gives the most reliable countdown.
     */
    public TimerRow getFreshestRow() {
        TimerRow freshest = null;
        for (TimerRow row : lastRows.values()) {
            if (freshest == null || row.syncUtcMs() > freshest.syncUtcMs()) {
                freshest = row;
            }
        }
        return freshest;
    }

    /** Returns true if the row's sync is too old relative to the freshest row. */
    public boolean isStale(TimerRow row, TimerRow freshest) {
        if (freshest == null) return true;
        return (freshest.syncUtcMs() - row.syncUtcMs()) > STALE_THRESHOLD_MS;
    }

    /** Seconds since last successful sync, or -1 if never synced. */
    public long getSecondsSinceLastSync() {
        if (lastSuccessMs == 0) return -1;
        return (System.currentTimeMillis() - lastSuccessMs) / 1000;
    }

    private void poll() {
        try {
            Map<String, TimerRow> rows = fetchTimers();
            lastRows = rows;
            lastSuccessMs = System.currentTimeMillis();
            successCount++;
            lastError = null;
            pushToWorldTracker(rows);
        } catch (Exception e) {
            lastFailureMs = System.currentTimeMillis();
            failureCount++;
            lastError = e.getMessage();
            AutoQiqiClient.logDebug("LegendTimerSync", "Poll failed: " + e.getMessage());
        }
    }

    private Map<String, TimerRow> fetchTimers() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", DB_USER);
        props.setProperty("password", DB_PASS);
        props.setProperty("connectTimeout", "5000");
        props.setProperty("socketTimeout", "5000");
        props.setProperty("tcpKeepAlive", "true");

        Map<String, TimerRow> rows = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(JDBC_URL, props);
             PreparedStatement ps = conn.prepareStatement(QUERY);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String world = rs.getString("WORLD");
                long spawnTickTarget = rs.getLong("SPAWN_TICK_TARGET");
                long syncUtc = rs.getLong("SYNC_UTC");
                long worldTicksAtSync = rs.getLong("WORLD_TICKS_AT_SYNC");
                double tps = rs.getDouble("TPS");
                rows.put(world, new TimerRow(world, spawnTickTarget, syncUtc, worldTicksAtSync, tps));
            }
        }
        return rows;
    }

    private void pushToWorldTracker(Map<String, TimerRow> rows) {
        WorldTracker tracker = WorldTracker.get();
        TimerRow freshest = getFreshestRow();
        if (freshest == null) return;
        long globalSecs = freshest.remainingSeconds();

        for (TimerRow row : rows.values()) {
            long secs = isStale(row, freshest) ? globalSecs : row.remainingSeconds();
            tracker.updateWorldTimer(row.world(), secs);
        }
    }
}
