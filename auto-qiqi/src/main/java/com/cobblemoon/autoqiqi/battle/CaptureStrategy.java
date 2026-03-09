package com.cobblemoon.autoqiqi.battle;

/**
 * Pure domain logic for "what to do next" in a capture battle.
 * Takes session snapshot + battle snapshot, returns {@link CaptureDecision} (action + session updates).
 * No Minecraft/Cobblemon dependencies — testable in isolation.
 */
public final class CaptureStrategy {

    private static final float LOW_HP_THRESHOLD = 50f;
    private static final int MAX_SWITCH_ATTEMPTS = 3;
    private static final int THUNDER_WAVE_REAPPLY_EVERY_BALLS = 8;
    private static final int LEGENDARY_LEVEL_THRESHOLD = 50;
    /** Level above which we apply status (Hypnosis/Thunder Wave). Use status when target level > 40. */
    private static final int STATUS_LEVEL_THRESHOLD = 41;
    private static final int LEGENDARY_GIVE_UP_AFTER_THROWS = 20;

    private CaptureStrategy() {}

    /** Level-dependent minimum False Swipe count before considering 1HP confirmed (one less than previous formula). */
    public static int getMinFalseSwipes(int level) {
        if (level < 30) return 1;
        return Math.max(1, (level - 20) / 10);
    }

    /**
     * Decides the next capture action from current session and battle state.
     * Uses move-based checks (activeHasFalseSwipe/activeHasThunderWave) instead of species names.
     */
    public static CaptureDecision decide(
        CaptureSessionSnapshot session,
        BattleSnapshot battle,
        boolean forceSwitch,
        boolean trapped
    ) {
        int minFS = getMinFalseSwipes(session.targetLevel());
        float oppHp = battle.opponentHpPercent();
        String activeName = battle.activePokemonName();
        String oppStatus = battle.opponentStatus();

        boolean hasFalseSwipe = battle.activeHasFalseSwipe();
        boolean hasThunderWave = battle.activeHasThunderWave();

        // 1HP confirmation
        boolean targetAtOneHp = session.targetAtOneHp();
        if (session.falseSwipeCount() >= minFS && !targetAtOneHp) {
            targetAtOneHp = true;
        } else if (session.falseSwipeUsedThisBattle()
            && session.lastOppHpBeforeFalseSwipe() >= 0
            && oppHp >= 0
            && session.falseSwipeCount() >= 2) {
            float delta = Math.abs(session.lastOppHpBeforeFalseSwipe() - oppHp);
            if (delta < 0.5f) {
                targetAtOneHp = true;
            }
        }

        // Opponent healed above 10% -> reset 1HP
        if (targetAtOneHp && oppHp > 10f) {
            targetAtOneHp = false;
        }

        // Infer Thunder Wave from opponent status (paralysis)
        boolean thunderWaveApplied = session.thunderWaveApplied();
        int ballsSinceLastTWave = session.ballsSinceLastTWave();
        if (oppStatus != null && oppStatus.toLowerCase().contains("par") && !thunderWaveApplied) {
            thunderWaveApplied = true;
            ballsSinceLastTWave = 0;
        }

        boolean switchBlocked = trapped || (session.consecutiveSwitchAttempts() >= MAX_SWITCH_ATTEMPTS);

        // --- Force switch
        if (forceSwitch) {
            var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_TANK);
            return CaptureDecision.of(
                CaptureAction.SWITCH_TANK,
                "Forced switch -> tank",
                new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, session.lastOppHpBeforeFalseSwipe(), session.cycleNextIsBall(), tr.consecutive(), tr.lastAction())
            );
        }

        // --- Low HP on utility Pokemon (has FS or TW) -> tank
        boolean isUtilityPokemon = hasFalseSwipe || hasThunderWave;
        if (!switchBlocked && isUtilityPokemon
            && battle.activeHpPercent() >= 0 && battle.activeHpPercent() < LOW_HP_THRESHOLD) {
            var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_TANK);
            return CaptureDecision.of(
                CaptureAction.SWITCH_TANK,
                activeName + " HP low (" + fmt(battle.activeHpPercent()) + "%), switching to tank",
                new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, session.lastOppHpBeforeFalseSwipe(), session.cycleNextIsBall(), tr.consecutive(), tr.lastAction())
            );
        }

        // --- Not yet at 1HP: need False Swipe
        if (!targetAtOneHp) {
            if (!hasFalseSwipe && !switchBlocked) {
                var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_FOR_FALSE_SWIPE);
                return CaptureDecision.of(
                    CaptureAction.SWITCH_FOR_FALSE_SWIPE,
                    "Need False Swipe (fsCount=" + session.falseSwipeCount() + "/" + minFS + "), current " + activeName + " doesn't have it — switching",
                    new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, oppHp, session.cycleNextIsBall(), tr.consecutive(), tr.lastAction())
                );
            }
            return CaptureDecision.of(
                CaptureAction.FALSE_SWIPE,
                "False Swipe (#" + (session.falseSwipeCount() + 1) + "/" + minFS + ") — " + activeName + " has the move",
                SessionUpdates.resetSwitchAttempts(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, oppHp, session.cycleNextIsBall())
            );
        }

        // --- Need Thunder Wave (Lv50+, before first ball or every 8 balls)
        boolean needTWave = session.targetLevel() >= STATUS_LEVEL_THRESHOLD
            && (!thunderWaveApplied || ballsSinceLastTWave >= THUNDER_WAVE_REAPPLY_EVERY_BALLS);
        if (needTWave) {
            if (!hasThunderWave && !switchBlocked) {
                var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_FOR_THUNDER_WAVE);
                return CaptureDecision.of(
                    CaptureAction.SWITCH_FOR_THUNDER_WAVE,
                    "Need Thunder Wave (ballsSinceTW=" + ballsSinceLastTWave + "), current " + activeName + " doesn't have it — switching",
                    new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, session.lastOppHpBeforeFalseSwipe(), true, tr.consecutive(), tr.lastAction())
                );
            }
            return CaptureDecision.of(
                CaptureAction.THUNDER_WAVE,
                "Thunder Wave — " + activeName + " has the move (balls since last=" + ballsSinceLastTWave + ")",
                SessionUpdates.resetSwitchAttempts(targetAtOneHp, true, 0, session.lastOppHpBeforeFalseSwipe(), true)
            );
        }

        // --- Legendary give-up
        if (session.targetIsLegendary() && session.totalBallsThrown() >= LEGENDARY_GIVE_UP_AFTER_THROWS) {
            return CaptureDecision.giveUpDecision();
        }

        // --- Ball / False Swipe cycle
        if (session.cycleNextIsBall()) {
            return CaptureDecision.of(
                CaptureAction.THROW_BALL,
                "Cycle: throw ball (#" + (session.totalBallsThrown() + 1) + ")",
                SessionUpdates.resetSwitchAttempts(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave + 1,
                    session.lastOppHpBeforeFalseSwipe(), false)
            );
        }

        // Cycle: False Swipe turn
        if (!hasFalseSwipe && !switchBlocked) {
            var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_FOR_FALSE_SWIPE);
            return CaptureDecision.of(
                CaptureAction.SWITCH_FOR_FALSE_SWIPE,
                "Cycle FS turn: current " + activeName + " doesn't have False Swipe — switching",
                new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, session.lastOppHpBeforeFalseSwipe(), session.cycleNextIsBall(), tr.consecutive(), tr.lastAction())
            );
        }
        return CaptureDecision.of(
            CaptureAction.FALSE_SWIPE,
            "False Swipe (cycle #" + (session.falseSwipeCount() + 1) + ") — " + activeName,
            SessionUpdates.resetSwitchAttempts(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave,
                session.lastOppHpBeforeFalseSwipe(), true)
        );
    }

    private record SwitchTrack(int consecutive, CaptureAction lastAction) {}

    private static SwitchTrack trackSwitch(
        int consecutiveSwitchAttempts,
        CaptureAction lastAttemptedSwitchAction,
        CaptureAction action
    ) {
        int next = (action == lastAttemptedSwitchAction)
            ? consecutiveSwitchAttempts + 1
            : 1;
        return new SwitchTrack(next, action);
    }

    private static String fmt(float v) {
        return String.format("%.1f", v);
    }
}
