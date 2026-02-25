package com.cobblemoon.autoqiqi.fish;

import net.minecraft.util.Util;

public class Action {

    private final ActionType actionType;
    private final long delay;
    private long timeToComplete;
    private final Runnable runnable;

    public Action(ActionType actionType, long delay, Runnable runnable) {
        this.actionType = actionType;
        this.delay = delay;
        this.timeToComplete = Util.getMeasuringTimeMs() + delay;
        this.runnable = runnable;
    }

    /**
     * @return true if the action was completed (and should be removed from queue)
     */
    public boolean tick() {
        if (Util.getMeasuringTimeMs() >= timeToComplete) {
            runnable.run();
            if (actionType == ActionType.REPEATING_ACTION) {
                this.timeToComplete = Util.getMeasuringTimeMs() + delay;
            }
            return true;
        }
        return false;
    }

    public ActionType getActionType() { return actionType; }

    public void resetTimer() {
        this.timeToComplete = Util.getMeasuringTimeMs() + delay;
    }
}
