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
    private static final int LEGENDARY_LEVEL_THRESHOLD = 50; // TWave from Lv50+
    private static final int LEGENDARY_GIVE_UP_AFTER_THROWS = 20;

    private CaptureStrategy() {}

    /** Level-dependent minimum False Swipe count before considering 1HP confirmed. */
    public static int getMinFalseSwipes(int level) {
        if (level < 30) return 1;
        return Math.max(1, (level - 10) / 10);
    }

    /**
     * Decides the next capture action from current session and battle state.
     *
     * @param session snapshot of capture session (no Entity/Cobblemon)
     * @param battle  snapshot of battle (active/opponent HP, names, status)
     * @param forceSwitch whether the battle request forces a switch
     * @param trapped whether the active Pokemon is trapped (switch blocked by game)
     * @return decision (action + status message + session updates to apply); giveUp true means engine should stop
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

        boolean isMarowak = nameContains(activeName, "marowak");
        boolean isDragonite = nameContains(activeName, "dragonite");
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

        // --- Low HP on key Pokemon -> tank
        if (!switchBlocked && (isMarowak || isDragonite)
            && battle.activeHpPercent() >= 0 && battle.activeHpPercent() < LOW_HP_THRESHOLD) {
            var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_TANK);
            return CaptureDecision.of(
                CaptureAction.SWITCH_TANK,
                activeName + " HP low, switching to tank",
                new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, session.lastOppHpBeforeFalseSwipe(), session.cycleNextIsBall(), tr.consecutive(), tr.lastAction())
            );
        }

        // --- Not yet at 1HP: need False Swipe (or switch to Marowak)
        if (!targetAtOneHp) {
            if (!isMarowak && !switchBlocked) {
                var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_MAROWAK);
                return CaptureDecision.of(
                    CaptureAction.SWITCH_MAROWAK,
                    "Switching to Marowak",
                    new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, oppHp, session.cycleNextIsBall(), tr.consecutive(), tr.lastAction())
                );
            }
            return CaptureDecision.of(
                CaptureAction.FALSE_SWIPE,
                "False Swipe (#" + (session.falseSwipeCount() + 1) + ")",
                SessionUpdates.resetSwitchAttempts(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, oppHp, session.cycleNextIsBall())
            );
        }

        // --- Need Thunder Wave (Lv50+, before first ball or every 8 balls)
        boolean needTWave = session.targetLevel() >= LEGENDARY_LEVEL_THRESHOLD
            && (!thunderWaveApplied || ballsSinceLastTWave >= THUNDER_WAVE_REAPPLY_EVERY_BALLS);
        if (needTWave) {
            if (!isDragonite && !switchBlocked) {
                var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_DRAGONITE);
                return CaptureDecision.of(
                    CaptureAction.SWITCH_DRAGONITE,
                    "Switching to Dragonite",
                    new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, session.lastOppHpBeforeFalseSwipe(), true, tr.consecutive(), tr.lastAction())
                );
            }
            return CaptureDecision.of(
                CaptureAction.THUNDER_WAVE,
                "Thunder Wave (balls since last=" + ballsSinceLastTWave + ")",
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
                null,
                SessionUpdates.resetSwitchAttempts(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave + 1,
                    session.lastOppHpBeforeFalseSwipe(), false)
            );
        }

        if (!isMarowak && !switchBlocked) {
            var tr = trackSwitch(session.consecutiveSwitchAttempts(), session.lastAttemptedSwitchAction(), CaptureAction.SWITCH_MAROWAK);
            return CaptureDecision.of(
                CaptureAction.SWITCH_MAROWAK,
                "Switching to Marowak (cycle FS)",
                new SessionUpdates(targetAtOneHp, thunderWaveApplied, ballsSinceLastTWave, session.lastOppHpBeforeFalseSwipe(), session.cycleNextIsBall(), tr.consecutive(), tr.lastAction())
            );
        }
        return CaptureDecision.of(
            CaptureAction.FALSE_SWIPE,
            "False Swipe (cycle #" + (session.falseSwipeCount() + 1) + ")",
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

    private static boolean nameContains(String name, String fragment) {
        return name != null && name.toLowerCase().contains(fragment);
    }
}
