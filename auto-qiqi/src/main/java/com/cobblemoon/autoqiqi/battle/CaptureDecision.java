package com.cobblemoon.autoqiqi.battle;

/**
 * Result of {@link CaptureStrategy#decide}: next action plus session updates.
 * When giveUp is true, engine should stop capture (e.g. legendary give-up).
 */
public record CaptureDecision(
    CaptureAction action,
    String statusMessage,
    SessionUpdates sessionUpdates,
    boolean giveUp
) {
    public static CaptureDecision of(CaptureAction action, String statusMessage, SessionUpdates sessionUpdates) {
        return new CaptureDecision(action, statusMessage, sessionUpdates, false);
    }

    /** Factory for a decision that signals the engine to abandon capture (e.g. legendary give-up). */
    public static CaptureDecision giveUpDecision() {
        return new CaptureDecision(CaptureAction.THROW_BALL, null, null, true);
    }
}
