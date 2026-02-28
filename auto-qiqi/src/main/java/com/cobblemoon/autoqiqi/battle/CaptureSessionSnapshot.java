package com.cobblemoon.autoqiqi.battle;

/**
 * Immutable snapshot of capture session state for strategy decisions.
 * Contains only primitive/domain data; no Entity or Cobblemon references.
 */
public record CaptureSessionSnapshot(
    String targetName,
    int targetLevel,
    boolean targetIsLegendary,
    boolean targetAtOneHp,
    boolean falseSwipeUsedThisBattle,
    int falseSwipeCount,
    float lastOppHpBeforeFalseSwipe,
    boolean thunderWaveApplied,
    int ballsSinceLastTWave,
    boolean cycleNextIsBall,
    int consecutiveSwitchAttempts,
    CaptureAction lastAttemptedSwitchAction,
    int totalBallsThrown,
    int decisionCount
) {}
