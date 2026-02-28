package com.cobblemoon.autoqiqi.battle;

/**
 * Updates to apply to {@link CaptureSession} after a strategy decision.
 * Strategy returns this so the engine can apply without the strategy touching session directly.
 */
public record SessionUpdates(
    boolean targetAtOneHp,
    boolean thunderWaveApplied,
    int ballsSinceLastTWave,
    float lastOppHpBeforeFalseSwipe,
    boolean cycleNextIsBall,
    int consecutiveSwitchAttempts,
    CaptureAction lastAttemptedSwitchAction
) {
    /** Reset switch attempts (e.g. after FIGHT action). */
    public static SessionUpdates resetSwitchAttempts(
        boolean targetAtOneHp,
        boolean thunderWaveApplied,
        int ballsSinceLastTWave,
        float lastOppHpBeforeFalseSwipe,
        boolean cycleNextIsBall
    ) {
        return new SessionUpdates(
            targetAtOneHp,
            thunderWaveApplied,
            ballsSinceLastTWave,
            lastOppHpBeforeFalseSwipe,
            cycleNextIsBall,
            0,
            null
        );
    }
}
