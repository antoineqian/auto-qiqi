package com.cobblemoon.autoqiqi.battle;

import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;

/**
 * Holds all mutable state for a single capture run (walk → engage → battle → balls).
 * One session per target; engine lifecycle: create session → tick(session) → clear on stop/success/fail.
 * Enables clear state ownership, testable flows, and easier pause/resume.
 */
public class CaptureSession {

    // Target
    public String targetName;
    public int targetLevel;
    public boolean targetIsLegendary;
    public Entity targetEntity;

    // Phase & status (uses CaptureEngine.Phase)
    public CaptureEngine.Phase phase = CaptureEngine.Phase.IDLE;
    public String statusMessage = "";

    // Walking
    public int walkTicks = 0;
    public int walkRetries = 0;

    // Total capture timeout
    public long captureStartMs = 0;

    // Engagement
    public int aimTicks = 0;
    public boolean keySent = false;
    public int engageAttempts = 0;
    public InputUtil.Key pendingKeyRelease = null;
    public double currentEngageRange = 6.0;
    public int losStrafeTicks = 0;
    public int losStrafeDir = 1;
    public int entityObstructionStrafeTicks = 0;

    // Battle strategy
    public boolean targetAtOneHp = false;
    public boolean falseSwipeUsedThisBattle = false;
    public int falseSwipeCount = 0;
    public float lastOppHpBeforeFalseSwipe = -1;
    public boolean thunderWaveApplied = false;
    public int ballsSinceLastTWave = 0;
    public boolean cycleNextIsBall = true;
    public CaptureAction currentAction = null;
    public int decisionCount = 0;
    public int consecutiveSwitchAttempts = 0;
    public CaptureAction lastAttemptedSwitchAction = null;

    // Ball sequence & counts
    public int ballSequenceIndex = 0;
    public int currentBallCount = 0;
    public int greatBallsThrown = 0;
    public int ultraBallsThrown = 0;
    public int totalBallsThrown = 0;

    // Ball throw (multi-tick)
    public boolean pendingBallThrow = false;
    public String pendingBallName = null;
    public int throwTicksRemaining = 0;
    public int throwAimTicks = 0;
    public boolean throwSlotSelected = false;
    public boolean backingUp = false;
    public int backupTicks = 0;

    // Ball hit / miss
    public boolean waitingForBallHit = false;
    public int throwWaitTicks = 0;
    public int missCount = 0;
    public boolean ballHitJustConfirmed = false;
    public long lastThrowTimeMs = 0;

    // Idle / re-engage
    public long inBattleIdleSinceMs = 0;
    public boolean reengagePending = false;
    public boolean retryThrowPending = false;

    // Pickup dropped ball
    public boolean pickingUpBall = false;
    public Entity droppedBallEntity = null;
    public int pickupTicks = 0;

    /** Ball sequence for this run (set by engine in start()). */
    public CaptureEngine.BallEntry[] activeBallSequence;

    /** Resets battle/ball state (e.g. after start or re-engage). Walking/engagement counters are set by caller. */
    public void resetBattleAndBallState() {
        targetAtOneHp = false;
        falseSwipeUsedThisBattle = false;
        falseSwipeCount = 0;
        lastOppHpBeforeFalseSwipe = -1;
        thunderWaveApplied = false;
        ballsSinceLastTWave = 0;
        cycleNextIsBall = true;
        decisionCount = 0;
        consecutiveSwitchAttempts = 0;
        lastAttemptedSwitchAction = null;
        currentAction = null;
        ballSequenceIndex = 0;
        currentBallCount = 0;
        greatBallsThrown = 0;
        ultraBallsThrown = 0;
        totalBallsThrown = 0;
        pendingBallThrow = false;
        pendingBallName = null;
        throwTicksRemaining = 0;
        throwAimTicks = 0;
        throwSlotSelected = false;
        backingUp = false;
        backupTicks = 0;
        entityObstructionStrafeTicks = 0;
        waitingForBallHit = false;
        throwWaitTicks = 0;
        missCount = 0;
        ballHitJustConfirmed = false;
        retryThrowPending = false;
        pickingUpBall = false;
        droppedBallEntity = null;
        pickupTicks = 0;
        aimTicks = 0;
        keySent = false;
        engageAttempts = 0;
        walkTicks = 0;
        walkRetries = 0;
        losStrafeTicks = 0;
        currentEngageRange = 6.0;
        lastThrowTimeMs = 0;
        inBattleIdleSinceMs = 0;
    }
}
