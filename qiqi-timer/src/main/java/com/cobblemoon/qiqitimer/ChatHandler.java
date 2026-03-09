package com.cobblemoon.qiqitimer;

/**
 * Tracks pending /nextleg poll and parses timer from the next matching chat line.
 */
public class ChatHandler {
    private static final ChatHandler INSTANCE = new ChatHandler();
    private static final long POLL_TIMEOUT_MS = 25_000;

    private boolean pendingPoll = false;
    private long pendingPollTimestamp = 0;

    public static ChatHandler get() {
        return INSTANCE;
    }

    public void setPendingPoll() {
        this.pendingPoll = true;
        this.pendingPollTimestamp = System.currentTimeMillis();
    }

    public boolean isPendingPoll() {
        return pendingPoll && (System.currentTimeMillis() - pendingPollTimestamp) < POLL_TIMEOUT_MS;
    }

    /**
     * Called from mixin when a chat message is added. If we're waiting for a timer response,
     * try to parse it and update NextlegTimerState. Returns true if the message was consumed.
     */
    public boolean onChatMessage(String rawMessage) {
        if (!isPendingPoll()) return false;
        String stripped = TimerParser.stripFormatting(rawMessage);
        Long seconds = TimerParser.parse(stripped, QiqiTimerConfig.get());
        if (seconds != null) {
            NextlegTimerState.get().updateTimer(seconds);
            pendingPoll = false;
            System.out.println("[Qiqi-Timer] Timer parsed: " + seconds + "s");
            return true;
        }
        return false;
    }
}
