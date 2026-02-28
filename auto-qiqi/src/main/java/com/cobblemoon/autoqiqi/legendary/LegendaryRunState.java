package com.cobblemoon.autoqiqi.legendary;

/**
 * Holds all mutable state for one legendary world-switching run.
 * Engine lifecycle: getOrCreateRun() → tick(run) → persist/display as needed.
 * Enables clear state ownership, testable flows, and easier pause/resume.
 */
public class LegendaryRunState {

    public enum State {
        IDLE, OPENING_MENU, WAITING_AFTER_SWITCH, POLLING,
        WAITING_FOR_POLL_RESPONSE, WAITING_AFTER_EVENT_SWITCH, PAUSED_FOR_CAPTURE
    }

    public enum SwitchPurpose { LEARNING, POLL, EVENT }

    public State state = State.IDLE;
    public SwitchPurpose switchPurpose = null;
    public String currentPollWorld = null;
    public String targetEventWorld = null;
    public boolean screenCloseAttempted = false;
    public boolean arrivalHomePending = false;
    public boolean isHomeTeleport = false;
    public String pendingHomeWorld = null;
    public long eventTimerZeroTick = 0;
    public boolean isEventRepoll = false;
    public int eventRepollRetries = 0;

    public long pauseStartTick = 0;
    public String pausedForPokemon = null;

    public String pendingCommand = null;
    public long commandExecuteAtTick = 0;

    public int homeRetryCount = 0;

    public long bossYieldStartTick = 0;

    /** Tick when current state was entered (for timeouts and cooldowns). */
    public long stateEnteredTick = 0;
    /** Next tick at which to act (IDLE cooldown). */
    public long nextActionAtTick = 0;
}
