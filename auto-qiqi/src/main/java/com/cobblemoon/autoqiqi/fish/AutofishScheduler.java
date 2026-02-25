package com.cobblemoon.autoqiqi.fish;

import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

public class AutofishScheduler {

    private final List<Action> queuedActions = new ArrayList<>();
    private final List<Action> repeatingActions = new ArrayList<>();
    private boolean doesWorldExist;

    public void tick(MinecraftClient client) {
        if ((client.world == null) == doesWorldExist) {
            doesWorldExist = (client.world != null);
            repeatingActions.forEach(Action::resetTimer);
        }

        if (!AutoQiqiConfig.get().fishEnabled) queuedActions.clear();
        if (client.world == null || client.player == null) {
            queuedActions.clear();
            return;
        }

        queuedActions.removeIf(Action::tick);
        repeatingActions.forEach(Action::tick);
    }

    public void scheduleAction(ActionType actionType, long delay, Runnable runnable) {
        queuedActions.add(new Action(actionType, delay, runnable));
    }

    public void scheduleRepeatingAction(long interval, Runnable runnable) {
        repeatingActions.add(new Action(ActionType.REPEATING_ACTION, interval, runnable));
    }

    public boolean isRecastQueued() {
        return queuedActions.stream().anyMatch(action -> action.getActionType() == ActionType.RECAST);
    }
}
