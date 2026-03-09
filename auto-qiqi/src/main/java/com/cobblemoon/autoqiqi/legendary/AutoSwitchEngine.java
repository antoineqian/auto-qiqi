package com.cobblemoon.autoqiqi.legendary;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.BattleMode;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.common.HumanDelay;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.World;

/**
 * Core engine that manages the legendary tracking lifecycle with priority-based polling.
 * World switching uses GUI clicks (GuiWorldSwitcher) for standard worlds,
 * or /home commands for home-based worlds (e.g. resource sub-worlds).
 */
public class AutoSwitchEngine {
    private static final AutoSwitchEngine INSTANCE = new AutoSwitchEngine();

    private static final int MAX_EVENT_REPOLL_RETRIES = 5;
    private static final int FAST_WINDOW_SECONDS = 60;
    private static final int MAX_HOME_RETRIES = 2;
    private static final int BOSS_YIELD_TIMEOUT_TICKS = 600; // 30 seconds

    private long tickCount = 0;
    /** Current run state; created when legendaryEnabled on first tick. */
    private LegendaryRunState run = null;

    private AutoSwitchEngine() {}

    public static AutoSwitchEngine get() { return INSTANCE; }

    /** Returns the current run state; null if legendary never enabled this session. */
    public LegendaryRunState getCurrentRun() { return run; }

    /** Ensures a run exists when legendary is enabled; returns null otherwise. */
    private LegendaryRunState getOrCreateRun() {
        if (run == null) run = new LegendaryRunState();
        return run;
    }

    public boolean isPaused() {
        return run != null && run.state == LegendaryRunState.State.PAUSED_FOR_CAPTURE;
    }

    public boolean isWaitingForHomeTeleport() {
        return run != null && run.isHomeTeleport
                && (run.state == LegendaryRunState.State.WAITING_AFTER_SWITCH
                || run.state == LegendaryRunState.State.WAITING_AFTER_EVENT_SWITCH);
    }

    public String getStateDisplay() {
        if (!AutoQiqiConfig.get().legendaryAutoSwitch) return "Inactif";
        if (run == null) return "En attente";
        return switch (run.state) {
            case IDLE -> "En attente";
            case OPENING_MENU -> switch (run.switchPurpose) {
                case LEARNING -> "Apprentissage mondes...";
                case EVENT -> "EVENT! Ouverture menu...";
                case POLL -> "Changement -> " + run.currentPollWorld;
            };
            case WAITING_AFTER_SWITCH -> "Teleportation " + run.currentPollWorld + "...";
            case POLLING -> "Envoi /nextleg...";
            case WAITING_FOR_POLL_RESPONSE -> "Lecture timer " + run.currentPollWorld + "...";
            case WAITING_AFTER_EVENT_SWITCH -> "En position dans " + run.targetEventWorld;
            case PAUSED_FOR_CAPTURE -> "PAUSE - Capture " + run.pausedForPokemon + " (" + getPauseRemainingDisplay() + ")";
        };
    }

    public String getCommandsStatus() {
        GuiWorldSwitcher switcher = GuiWorldSwitcher.get();
        AutoQiqiConfig config = AutoQiqiConfig.get();
        int guiLearned = switcher.getLearnedWorldCount();
        int guiTotal = switcher.getGuiWorldCount();
        int homeCount = config.homeWorlds.size();
        int totalWorlds = config.worldNames.size();
        if (guiLearned == 0) return "Mondes: en attente d'apprentissage...";
        if (guiLearned < guiTotal) return "Mondes: " + guiLearned + "/" + guiTotal + " GUI appris";
        return "Mondes: " + totalWorlds + " (" + guiTotal + " GUI + " + homeCount + " home) OK";
    }

    private String getPauseRemainingDisplay() {
        if (run == null) return "";
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (config.pauseDurationSeconds <= 0) return "reprendre: [J]";
        long elapsed = (tickCount - run.pauseStartTick) / 20;
        long remaining = config.pauseDurationSeconds - elapsed;
        if (remaining <= 0) return "reprise...";
        long min = remaining / 60;
        long sec = remaining % 60;
        return String.format("%dm%02ds", min, sec);
    }

    public long getTickCount() { return tickCount; }

    // ========================
    // Callbacks from GuiWorldSwitcher
    // ========================

    public void onWorldsLearned() {
        GuiWorldSwitcher switcher = GuiWorldSwitcher.get();
        int count = switcher.getLearnedWorldCount();
        AutoQiqiClient.log("Legendary", "Worlds learned: " + count + " GUI mapped. " + dumpTimerSummary());

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§6[Auto-Qiqi]§r " + count + " mondes appris ! Mod actif."), false);
        }

        if (run != null && run.state == LegendaryRunState.State.OPENING_MENU && run.switchPurpose == LegendaryRunState.SwitchPurpose.LEARNING) {
            setState(run, LegendaryRunState.State.IDLE, "worlds learned");
            run.stateEnteredTick = 0;
            run.nextActionAtTick = 0;
        }
    }

    public void onWorldSwitchComplete() {
        stopActiveEngines();
        if (run == null) return;

        if (run.state == LegendaryRunState.State.OPENING_MENU) {
            run.isHomeTeleport = false;
            run.pendingHomeWorld = null;
            if (run.switchPurpose == LegendaryRunState.SwitchPurpose.EVENT) {
                AutoQiqiClient.log("Legendary", "GUI switch complete (EVENT) -> " + run.targetEventWorld);
                setState(run, LegendaryRunState.State.WAITING_AFTER_EVENT_SWITCH, "gui event switch done");
                run.stateEnteredTick = tickCount;
                run.eventTimerZeroTick = 0;
                run.isEventRepoll = false;
                run.eventRepollRetries = 0;
            } else {
                AutoQiqiClient.log("Legendary", "GUI switch complete (POLL) -> " + run.currentPollWorld);
                setState(run, LegendaryRunState.State.WAITING_AFTER_SWITCH, "gui poll switch done");
                run.stateEnteredTick = tickCount;
                run.screenCloseAttempted = false;
                run.arrivalHomePending = false;
            }
        }
    }

    public void onGuiTimeout() {
        if (run != null && run.state == LegendaryRunState.State.OPENING_MENU) {
            AutoQiqiClient.log("Legendary", "GUI timeout (purpose=" + run.switchPurpose + ", target=" + run.currentPollWorld + ")");
            setState(run, LegendaryRunState.State.IDLE, "gui timeout");
            run.stateEnteredTick = tickCount;
        }
    }

    // ========================
    // Pause / Resume
    // ========================

    public void pauseForCapture(String pokemonName) {
        LegendaryRunState r = getOrCreateRun();
        AutoQiqiClient.log("Legendary", "PAUSING for capture: " + pokemonName + " (was " + r.state + ")");
        r.pausedForPokemon = pokemonName;
        r.pauseStartTick = tickCount;
        setState(r, LegendaryRunState.State.PAUSED_FOR_CAPTURE, "capture " + pokemonName);
        r.stateEnteredTick = tickCount;
        GuiWorldSwitcher.get().cancelPending();
    }

    public void resumeFromPause() {
        if (run != null && run.state == LegendaryRunState.State.PAUSED_FOR_CAPTURE) {
            AutoQiqiClient.log("Legendary", "RESUMING from capture pause (was paused for " + run.pausedForPokemon + ")");
            run.pausedForPokemon = null;
            setState(run, LegendaryRunState.State.IDLE, "resume from pause");
            run.stateEnteredTick = tickCount;
        }
    }

    // ========================
    // Main tick
    // ========================

    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (!config.legendaryEnabled) return;

        tickCount++;
        LegendaryRunState r = getOrCreateRun();

        if (!config.legendaryAutoSwitch && r.state != LegendaryRunState.State.IDLE) {
            AutoQiqiClient.log("Legendary", "legendaryAutoSwitch OFF while in " + r.state + ", cancelling");
            GuiWorldSwitcher.get().cancelPending();
            r.pendingCommand = null;
            r.pendingHomeWorld = null;
            r.isHomeTeleport = false;
            setState(r, LegendaryRunState.State.IDLE, "autoSwitch disabled");
            r.stateEnteredTick = tickCount;
            return;
        }

        // If we entered battle while in a switch flow, abort so we never teleport mid-fight.
        if (r.state != LegendaryRunState.State.IDLE && r.state != LegendaryRunState.State.PAUSED_FOR_CAPTURE
                && isInBattle()) {
            AutoQiqiClient.log("Legendary", "In battle during " + r.state + " — aborting to IDLE (no teleport)");
            GuiWorldSwitcher.get().cancelPending();
            r.pendingCommand = null;
            r.pendingHomeWorld = null;
            r.isHomeTeleport = false;
            setState(r, LegendaryRunState.State.IDLE, "in battle");
            r.stateEnteredTick = tickCount;
            return;
        }

        tickPendingCommand(client, r);
        if (r.pendingCommand != null) return;

        switch (r.state) {
            case IDLE -> handleIdle(client, config, r);
            case OPENING_MENU -> handleOpeningMenu(r);
            case WAITING_AFTER_SWITCH -> handleWaitingAfterSwitch(client, r);
            case POLLING -> handlePolling(client, config, r);
            case WAITING_FOR_POLL_RESPONSE -> handleWaitingForResponse(r);
            case WAITING_AFTER_EVENT_SWITCH -> handleWaitingAfterEventSwitch(config, r);
            case PAUSED_FOR_CAPTURE -> handlePausedForCapture(config, r);
        }
    }

    private boolean isInBattle() {
        return CobblemonClient.INSTANCE.getBattle() != null
                || CaptureEngine.get().isActive();
    }

    /** Minimum remaining time (seconds) on the soonest legendary timer to prioritize boss hunt. */
    private static final int BOSS_PRIORITY_MIN_REMAINING_SECONDS = 90;

    /**
     * Boss encounters take priority over legendary world switching only when there is
     * at least 1m30s (90s) or more on the soonest timer. With less time, we proceed with
     * world switch so we don't miss the legendary.
     */
    private boolean isBossActive() {
        AutoBattleEngine battle = AutoBattleEngine.get();
        if (battle.getMode() == BattleMode.OFF) return false;
        if (battle.isEngagingBoss()) return true;
        return battle.isBossNearby();
    }

    /** True when we should yield to boss (i.e. timer has at least 90s so we can prioritize boss hunt). */
    private boolean shouldPrioritizeBossOverLegendary() {
        WorldTracker tracker = WorldTracker.get();
        long soonest = tracker.getSoonestRemainingSeconds();
        return soonest >= BOSS_PRIORITY_MIN_REMAINING_SECONDS;
    }

    private void handleIdle(MinecraftClient client, AutoQiqiConfig config, LegendaryRunState r) {
        if (!config.legendaryAutoSwitch) return;
        if (client.currentScreen != null) return;
        if (isInBattle()) return;

        if (isBossActive() && shouldPrioritizeBossOverLegendary()) {
            if (r.bossYieldStartTick == 0) {
                r.bossYieldStartTick = tickCount;
                AutoQiqiClient.log("Legendary", "IDLE: yielding to boss encounter (timer >= " + BOSS_PRIORITY_MIN_REMAINING_SECONDS + "s)");
            }
            long waited = tickCount - r.bossYieldStartTick;
            if (waited >= BOSS_YIELD_TIMEOUT_TICKS) {
                AutoQiqiClient.log("Legendary", "IDLE: boss yield timeout after " + (waited / 20) + "s, proceeding anyway");
                r.bossYieldStartTick = 0;
            } else {
                if (waited % 200 == 0 && waited > 0) {
                    AutoQiqiClient.log("Legendary", "IDLE: still yielding to boss (" + (waited / 20) + "s / " + (BOSS_YIELD_TIMEOUT_TICKS / 20) + "s)");
                }
                return;
            }
        } else {
            if (isBossActive() && !shouldPrioritizeBossOverLegendary()) {
                AutoQiqiClient.log("Legendary", "IDLE: boss nearby but timer < " + BOSS_PRIORITY_MIN_REMAINING_SECONDS + "s — proceeding with legendary switch");
            }
            r.bossYieldStartTick = 0;
        }

        GuiWorldSwitcher switcher = GuiWorldSwitcher.get();
        WorldTracker tracker = WorldTracker.get();

        if (!switcher.hasLearnedWorlds()) {
            if (r.stateEnteredTick > 0 && (tickCount - r.stateEnteredTick) < 200) return;
            AutoQiqiClient.log("Legendary", "IDLE: worlds not learned yet, opening /monde for learning");
            switcher.requestLearning();
            sendCommand(client, config.mondeCommand, r);
            r.switchPurpose = LegendaryRunState.SwitchPurpose.LEARNING;
            setState(r, LegendaryRunState.State.OPENING_MENU, "learning");
            r.stateEnteredTick = tickCount;
            return;
        }

        if (tracker.currentWorldHasImminentEvent()) return;

        String currentWorldName = tracker.getCurrentWorld();
        if (currentWorldName != null) {
            WorldTimerData cwTimer = tracker.getTimer(currentWorldName);
            if (cwTimer != null && cwTimer.isTimerKnown()
                    && cwTimer.getEstimatedRemainingSeconds() <= 0
                    && cwTimer.getSecondsSinceLastUpdate() > 30) {
                AutoQiqiClient.log("Legendary", "IDLE: current world " + currentWorldName
                        + " timer expired while camping (last update " + cwTimer.getSecondsSinceLastUpdate() + "s ago), entering event repoll");
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(
                            "§6[Auto-Qiqi]§r §eTimer " + currentWorldName
                                    + " expire ! Repoll en cours..."), false);
                }
                r.targetEventWorld = currentWorldName;
                setState(r, LegendaryRunState.State.WAITING_AFTER_EVENT_SWITCH, "camping timer expired");
                r.stateEnteredTick = tickCount;
                r.eventTimerZeroTick = tickCount;
                r.isEventRepoll = false;
                r.eventRepollRetries = 0;
                return;
            }
        }

        String eventWorld = tracker.getWorldToSwitchTo();
        if (eventWorld != null) {
            r.targetEventWorld = eventWorld;
            WorldTimerData evtData = tracker.getTimer(eventWorld);
            String evtInfo = evtData != null ? (evtData.isEventActive() ? "EVENT_ACTIVE" : evtData.getFormattedTime()) : "?";
            AutoQiqiClient.log("Legendary", "IDLE: event switch needed -> " + eventWorld
                    + " (timer=" + evtInfo + ", from=" + currentWorldName + ")");
            if (config.isHomeWorld(eventWorld)) {
                String homeName = config.getHomeCommand(eventWorld);
                AutoQiqiClient.log("Legendary", "EVENT! Home switch -> " + eventWorld + " via /home " + homeName);
                stopActiveEngines();
                releaseMovementKeys(client);
                sendCommand(client, "/home " + homeName, r);
                r.pendingHomeWorld = eventWorld;
                r.isHomeTeleport = true;
                r.homeRetryCount = 0;
                setState(r, LegendaryRunState.State.WAITING_AFTER_EVENT_SWITCH, "event /home " + eventWorld);
                r.stateEnteredTick = tickCount;
                r.eventTimerZeroTick = 0;
                r.isEventRepoll = false;
                r.eventRepollRetries = 0;
            } else {
                switcher.requestSwitch(eventWorld);
                sendCommand(client, config.mondeCommand, r);
                r.switchPurpose = LegendaryRunState.SwitchPurpose.EVENT;
                setState(r, LegendaryRunState.State.OPENING_MENU, "event gui " + eventWorld);
                r.stateEnteredTick = tickCount;
            }
            return;
        }

        if (tickCount < r.nextActionAtTick) return;

        String expiredWorld = tracker.getWorldWithExpiredTimer(config.repollCooldownSeconds);
        if (expiredWorld != null) {
            WorldTimerData expData = tracker.getTimer(expiredWorld);
            AutoQiqiClient.log("Legendary", "IDLE: repoll expired timer -> " + expiredWorld
                    + " (last update " + (expData != null ? expData.getSecondsSinceLastUpdate() + "s ago" : "?") + ")");
            switchAndPoll(client, config, expiredWorld, r);
            return;
        }

        String unknownWorld = tracker.getWorldWithUnknownTimer();
        if (unknownWorld != null) {
            AutoQiqiClient.log("Legendary", "IDLE: discovering unknown timer -> " + unknownWorld
                    + " (" + tracker.getKnownTimerCount() + "/" + tracker.getTotalWorldCount() + " known)");
            switchAndPoll(client, config, unknownWorld, r);
            return;
        }

        String soonestWorld = tracker.getWorldWithSoonestTimer();
        if (soonestWorld != null) {
            WorldTimerData soonData = tracker.getTimer(soonestWorld);
            AutoQiqiClient.log("Legendary", "IDLE: all timers known, camping soonest -> " + soonestWorld
                    + " (" + (soonData != null ? soonData.getFormattedTime() : "?") + "). " + dumpTimerSummary());
            switchAndPoll(client, config, soonestWorld, r);
        }
    }

    private void switchAndPoll(MinecraftClient client, AutoQiqiConfig config, String worldName, LegendaryRunState r) {
        r.currentPollWorld = worldName;
        String currentWorld = WorldTracker.get().getCurrentWorld();

        if (worldName.equalsIgnoreCase(currentWorld)) {
            AutoQiqiClient.log("Legendary", "switchAndPoll: already in " + worldName + ", polling directly");
            setState(r, LegendaryRunState.State.POLLING, "already in " + worldName);
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logWorldSwitch(currentWorld, worldName);
            r.stateEnteredTick = tickCount;
        } else if (config.isHomeWorld(worldName)) {
            String homeName = config.getHomeCommand(worldName);
            AutoQiqiClient.log("Legendary", "switchAndPoll: " + currentWorld + " -> " + worldName + " via /home " + homeName);
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logWorldSwitch(currentWorld, worldName);
            stopActiveEngines();
            releaseMovementKeys(client);
            sendCommand(client, "/home " + homeName, r);
            r.pendingHomeWorld = worldName;
            r.isHomeTeleport = true;
            r.homeRetryCount = 0;
            setState(r, LegendaryRunState.State.WAITING_AFTER_SWITCH, "/home " + homeName);
            r.stateEnteredTick = tickCount;
            r.screenCloseAttempted = true;
            r.arrivalHomePending = false;
        } else {
            GuiWorldSwitcher switcher = GuiWorldSwitcher.get();
            if (switcher.hasLearnedWorlds()) {
                AutoQiqiClient.log("Legendary", "switchAndPoll: " + currentWorld + " -> " + worldName + " via GUI");
                com.cobblemoon.autoqiqi.common.SessionLogger.get().logWorldSwitch(currentWorld, worldName);
                switcher.requestSwitch(worldName);
                sendCommand(client, config.mondeCommand, r);
                r.switchPurpose = LegendaryRunState.SwitchPurpose.POLL;
                setState(r, LegendaryRunState.State.OPENING_MENU, "gui poll " + worldName);
                r.stateEnteredTick = tickCount;
            } else {
                AutoQiqiClient.log("Legendary", "switchAndPoll: can't switch to " + worldName + " - worlds not learned yet");
            }
        }
    }

    private void releaseMovementKeys(MinecraftClient client) {
        if (client.player == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
    }

    private void stopActiveEngines() {
        boolean walkerActive = PokemonWalker.get().isActive();
        boolean captureActive = CaptureEngine.get().isActive();
        if (walkerActive || captureActive) {
            AutoQiqiClient.log("Legendary", "Stopping active engines: walker=" + walkerActive + " capture=" + captureActive);
        }
        if (walkerActive) PokemonWalker.get().stop();
        if (captureActive) CaptureEngine.get().stop();
        AutoBattleEngine.get().clearTarget();
    }

    private void handleOpeningMenu(LegendaryRunState r) {
        if ((tickCount - r.stateEnteredTick) > 400) {
            AutoQiqiClient.log("Legendary", "OPENING_MENU timeout after 400 ticks (purpose=" + r.switchPurpose + ", target=" + r.currentPollWorld + ")");
            GuiWorldSwitcher.get().cancelPending();
            setState(r, LegendaryRunState.State.IDLE, "menu timeout");
            r.stateEnteredTick = tickCount;
        }
    }

    private void handleWaitingAfterSwitch(MinecraftClient client, LegendaryRunState r) {
        if (!r.screenCloseAttempted && client.currentScreen != null && (tickCount - r.stateEnteredTick) > 20) {
            r.screenCloseAttempted = true;
            client.setScreen(null);
        }

        if (r.isHomeTeleport) {
            releaseMovementKeys(client);
        }

        int waitTicks;
        if (r.isHomeTeleport) {
            int warmupTicks = AutoQiqiConfig.get().homeTeleportWarmupSeconds * 20;
            waitTicks = warmupTicks + 40;
        } else {
            waitTicks = WorldTracker.get().hasOtherWorldWithinSeconds(FAST_WINDOW_SECONDS) ? 40 : 80;
        }

        if ((tickCount - r.stateEnteredTick) > waitTicks) {
            if (r.pendingHomeWorld != null) {
                if (!verifyHomeDimension(client, r.pendingHomeWorld)) {
                    if (r.homeRetryCount < MAX_HOME_RETRIES) {
                        r.homeRetryCount++;
                        String homeCmd = AutoQiqiConfig.get().getHomeCommand(r.pendingHomeWorld);
                        AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: dimension mismatch (movement cancelled /home?), retrying /home " + homeCmd + " (" + r.homeRetryCount + "/" + MAX_HOME_RETRIES + ")");
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §cTeleport interrompu? Nouvelle tentative /home " + homeCmd + "..."), false);
                        }
                        sendCommand(client, "/home " + homeCmd, r);
                        r.stateEnteredTick = tickCount;
                        return;
                    } else {
                        AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: dimension still wrong after " + MAX_HOME_RETRIES + " retries, aborting switch");
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §cTeleport echoue apres " + MAX_HOME_RETRIES + " tentatives."), false);
                        }
                        r.homeRetryCount = 0;
                        r.pendingHomeWorld = null;
                        r.isHomeTeleport = false;
                        returnToIdle(r);
                        return;
                    }
                }
                r.homeRetryCount = 0;
                AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: /home warmup done, setting currentWorld=" + r.pendingHomeWorld);
                WorldTracker.get().setCurrentWorld(r.pendingHomeWorld);
                r.pendingHomeWorld = null;
                r.isHomeTeleport = false;
            }

            AutoQiqiConfig config = AutoQiqiConfig.get();
            String arrivalHome = config.getArrivalHome(r.currentPollWorld);
            if (arrivalHome != null && !r.arrivalHomePending) {
                AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: arrival home -> /home " + arrivalHome + " for " + r.currentPollWorld);
                r.arrivalHomePending = true;
                r.isHomeTeleport = true;
                r.pendingHomeWorld = null;
                sendCommand(client, "/home " + arrivalHome, r);
                r.stateEnteredTick = tickCount;
                r.screenCloseAttempted = true;
                return;
            }
            r.arrivalHomePending = false;
            AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: switch to " + r.currentPollWorld + " complete (waited " + waitTicks + " ticks), now polling");
            setState(r, LegendaryRunState.State.POLLING, "switch wait done");
            r.stateEnteredTick = tickCount;
        }
    }

    private void handlePolling(MinecraftClient client, AutoQiqiConfig config, LegendaryRunState r) {
        if (client.currentScreen != null) return;
        if (isInBattle()) return;
        AutoQiqiClient.log("Legendary", "POLLING: sending " + config.nextlegCommand + " for " + r.currentPollWorld);
        ChatMessageHandler.get().setPendingPoll(r.currentPollWorld);
        sendCommand(client, config.nextlegCommand, r);
        setState(r, LegendaryRunState.State.WAITING_FOR_POLL_RESPONSE, "poll " + r.currentPollWorld);
        r.stateEnteredTick = tickCount;
    }

    private void handleWaitingForResponse(LegendaryRunState r) {
        if ((tickCount - r.stateEnteredTick) > 400) {
            AutoQiqiClient.log("Legendary", "WAITING_FOR_POLL_RESPONSE: timeout (400 ticks) for " + r.currentPollWorld
                    + " (isEventRepoll=" + r.isEventRepoll + ")");
            if (r.isEventRepoll && r.eventRepollRetries < MAX_EVENT_REPOLL_RETRIES) {
                r.eventRepollRetries++;
                AutoQiqiClient.log("Legendary", "Event repoll response timeout, retrying ("
                        + r.eventRepollRetries + "/" + MAX_EVENT_REPOLL_RETRIES + ")");
                setState(r, LegendaryRunState.State.WAITING_AFTER_EVENT_SWITCH, "repoll retry #" + r.eventRepollRetries);
                r.eventTimerZeroTick = tickCount;
                r.stateEnteredTick = tickCount;
                return;
            }
            r.isEventRepoll = false;
            returnToIdle(r);
        }
    }

    private void handleWaitingAfterEventSwitch(AutoQiqiConfig config, LegendaryRunState r) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (r.pendingHomeWorld != null) {
            int warmupTicks = config.homeTeleportWarmupSeconds * 20;
            if (mc != null) releaseMovementKeys(mc);
            if ((tickCount - r.stateEnteredTick) < warmupTicks + 40) return;
            if (!verifyHomeDimension(mc, r.pendingHomeWorld)) {
                if (r.homeRetryCount < MAX_HOME_RETRIES) {
                    r.homeRetryCount++;
                    String homeCmd = config.getHomeCommand(r.pendingHomeWorld);
                    AutoQiqiClient.log("Legendary", "WAITING_AFTER_EVENT_SWITCH: dimension mismatch, retrying /home " + homeCmd);
                    if (mc != null && mc.player != null) {
                        mc.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §cTeleport interrompu? Nouvelle tentative..."), false);
                    }
                    sendCommand(mc, "/home " + homeCmd, r);
                    r.stateEnteredTick = tickCount;
                    return;
                }
                r.homeRetryCount = 0;
                r.pendingHomeWorld = null;
                r.isHomeTeleport = false;
                return;
            }
            r.homeRetryCount = 0;
            AutoQiqiClient.log("Legendary", "WAITING_AFTER_EVENT_SWITCH: /home warmup done, setting currentWorld=" + r.pendingHomeWorld);
            WorldTracker.get().setCurrentWorld(r.pendingHomeWorld);
            r.pendingHomeWorld = null;
            r.isHomeTeleport = false;
            r.stateEnteredTick = tickCount;
        }

        if ((tickCount - r.stateEnteredTick) > 6000) {
            AutoQiqiClient.log("Legendary", "WAITING_AFTER_EVENT_SWITCH: timeout (5min) for " + r.targetEventWorld + ", forcing repoll");
            transitionToEventRepoll(r);
            return;
        }

        WorldTracker tracker = WorldTracker.get();
        WorldTimerData currentTimer = tracker.getTimer(r.targetEventWorld);

        if (currentTimer != null && currentTimer.isTimerKnown()) {
            long remaining = currentTimer.getEstimatedRemainingSeconds();
            if (remaining > 0) {
                r.eventTimerZeroTick = 0;
                return;
            }
        }

        if (r.eventTimerZeroTick == 0) {
            AutoQiqiClient.log("Legendary", "WAITING_AFTER_EVENT_SWITCH: timer reached 0 for " + r.targetEventWorld + ", playing bell");
            r.eventTimerZeroTick = tickCount;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.world != null) {
                client.world.playSound(
                        client.player.getX(), client.player.getY(), client.player.getZ(),
                        SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(),
                        SoundCategory.MASTER, 1.0f, 1.5f, false);
            }
        }

        int waitSeconds = WorldTracker.get().hasOtherWorldWithinSeconds(FAST_WINDOW_SECONDS)
                ? 2
                : (WorldTracker.get().hasOtherWorldsNeedingAttention() ? 5 : config.eventRepollWaitSeconds);
        if ((tickCount - r.eventTimerZeroTick) < (waitSeconds * 20L)) return;

        transitionToEventRepoll(r);
    }

    private void transitionToEventRepoll(LegendaryRunState r) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        if (isInBattle()) return;
        if (client.currentScreen != null) {
            if ((tickCount - r.eventTimerZeroTick) > 40) client.setScreen(null);
            return;
        }

        r.isEventRepoll = true;
        r.currentPollWorld = r.targetEventWorld;
        AutoQiqiClient.log("Legendary", "transitionToEventRepoll: " + r.targetEventWorld + " -> polling for new timer");
        setState(r, LegendaryRunState.State.POLLING, "event repoll " + r.targetEventWorld);
        r.stateEnteredTick = tickCount;
    }

    private void handlePausedForCapture(AutoQiqiConfig config, LegendaryRunState r) {
        if (config.pauseDurationSeconds > 0) {
            long pausedTicks = tickCount - r.pauseStartTick;
            if (pausedTicks >= config.pauseDurationSeconds * 20L) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("§6[Auto-Qiqi]§r Pause terminee, reprise automatique."), false);
                }
                resumeFromPause();
            }
        }
    }

    public void onTimerResponseReceived() {
        if (run == null || run.state != LegendaryRunState.State.WAITING_FOR_POLL_RESPONSE) return;
        LegendaryRunState r = run;
        WorldTimerData pollData = WorldTracker.get().getTimer(r.currentPollWorld);
        AutoQiqiClient.log("Legendary", "Timer response received for " + r.currentPollWorld
                + ": " + (pollData != null ? pollData.getFormattedTime() + " (raw=" + pollData.getRawRemainingSeconds() + "s)" : "null"));

        if (r.isEventRepoll) {
            long timerValue = (pollData != null && pollData.isTimerKnown())
                    ? pollData.getRawRemainingSeconds() : -1;

            if (timerValue <= 0 && r.eventRepollRetries < MAX_EVENT_REPOLL_RETRIES) {
                r.eventRepollRetries++;
                AutoQiqiClient.log("Legendary", "Event repoll: timer still 0, retry #" + r.eventRepollRetries);
                setState(r, LegendaryRunState.State.WAITING_AFTER_EVENT_SWITCH, "repoll timer=0 retry");
                r.eventTimerZeroTick = tickCount;
                return;
            }

            AutoQiqiClient.log("Legendary", "Event repoll done for " + r.currentPollWorld
                    + ": new timer=" + timerValue + "s");
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && timerValue > 0) {
                long min = timerValue / 60;
                long sec = timerValue % 60;
                client.player.sendMessage(
                        Text.literal("§6[Auto-Qiqi]§r §7Nouveau timer " + r.currentPollWorld
                                + ": §f" + String.format("%02dm%02ds", min, sec)), false);
            }
            r.isEventRepoll = false;
            r.eventRepollRetries = 0;
        }
        returnToIdle(r);
    }

    private void returnToIdle(LegendaryRunState r) {
        WorldTracker tracker = WorldTracker.get();
        String reason;
        long cooldownTicks;
        if (tracker.hasOtherWorldWithinSeconds(FAST_WINDOW_SECONDS)) {
            cooldownTicks = 40;
            reason = "fast (other world within " + FAST_WINDOW_SECONDS + "s)";
        } else if (!tracker.allTimersKnown()) {
            cooldownTicks = 40;
            reason = "discovery (" + tracker.getKnownTimerCount() + "/" + tracker.getTotalWorldCount() + " known)";
        } else {
            cooldownTicks = HumanDelay.actionCooldownTicks();
            reason = "normal";
        }
        r.nextActionAtTick = tickCount + cooldownTicks;
        AutoQiqiClient.log("Legendary", "returnToIdle: cooldown=" + cooldownTicks + " ticks (" + reason + "). " + dumpTimerSummary());
        setState(r, LegendaryRunState.State.IDLE, "return " + reason);
        r.stateEnteredTick = tickCount;
    }

    public void forcePoll() {
        LegendaryRunState r = getOrCreateRun();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            AutoQiqiClient.log("Legendary", "forcePoll: marking all timers for repoll");
            WorldTracker.get().markAllForRepoll();
            setState(r, LegendaryRunState.State.IDLE, "force poll");
            r.stateEnteredTick = 0;
            r.nextActionAtTick = 0;
        }
    }

    public void relearnCommands() {
        LegendaryRunState r = getOrCreateRun();
        AutoQiqiClient.log("Legendary", "relearnCommands: resetting GUI world map");
        GuiWorldSwitcher.get().reset();
        setState(r, LegendaryRunState.State.IDLE, "relearn");
        r.stateEnteredTick = 0;
    }

    public void cancelToIdle() {
        if (run == null || run.state == LegendaryRunState.State.IDLE) return;
        AutoQiqiClient.log("Legendary", "cancelToIdle: " + run.state + " -> IDLE (tower or manual)");
        GuiWorldSwitcher.get().cancelPending();
        run.pendingCommand = null;
        run.pendingHomeWorld = null;
        run.isHomeTeleport = false;
        setState(run, LegendaryRunState.State.IDLE, "tower / mutually exclusive");
    }

    /**
     * Verifies we're in the expected dimension for the given world (home command mapping).
     * Returns true if we're in the right place (or if verification is disabled), false if dimension mismatch.
     * Disable via config.verifyHomeDimension for servers with custom resource dimensions.
     */
    private boolean verifyHomeDimension(MinecraftClient client, String worldName) {
        if (!AutoQiqiConfig.get().verifyHomeDimension) return true;
        if (client == null || client.world == null) return true;
        String homeCmd = AutoQiqiConfig.get().getHomeCommand(worldName);
        if (homeCmd == null) return true;
        var key = client.world.getRegistryKey();
        return switch (homeCmd.toLowerCase()) {
            case "nether" -> key == World.NETHER;
            case "end" -> key == World.END;
            case "overworld" -> key == World.OVERWORLD;
            default -> true;
        };
    }

    private void sendCommand(MinecraftClient client, String command, LegendaryRunState r) {
        if (client.player == null) return;
        AutoQiqiConfig config = AutoQiqiConfig.get();
        int delayMs = HumanDelay.commandDelayMs(config.commandDelayMinMs, config.commandDelayMaxMs);
        if (WorldTracker.get().hasOtherWorldWithinSeconds(FAST_WINDOW_SECONDS)) {
            delayMs = Math.min(delayMs, 1200);
        }
        int delayTicks = Math.max(1, delayMs / 50);
        r.pendingCommand = command;
        r.commandExecuteAtTick = tickCount + delayTicks;
        AutoQiqiClient.log("Legendary", "Command queued: '" + command + "' (delay=" + delayTicks + " ticks / " + delayMs + "ms)");
    }

    private void tickPendingCommand(MinecraftClient client, LegendaryRunState r) {
        if (r.pendingCommand != null && tickCount >= r.commandExecuteAtTick) {
            if (client.player == null || !AutoQiqiClient.isConnected(client)) {
                r.commandExecuteAtTick = tickCount + 40;
                AutoQiqiClient.log("Legendary", "Command deferred (no connection), retry in 2s: '" + r.pendingCommand + "'");
                return;
            }
            if (isInBattle()) {
                AutoQiqiClient.log("Legendary", "Command cancelled (in battle): '" + r.pendingCommand + "' — no teleport");
                r.pendingCommand = null;
                GuiWorldSwitcher.get().cancelPending();
                setState(r, LegendaryRunState.State.IDLE, "in battle before command");
                r.stateEnteredTick = tickCount;
                return;
            }
            String cmd = r.pendingCommand;
            r.pendingCommand = null;

            AutoQiqiConfig config = AutoQiqiConfig.get();
            if (cmd.equalsIgnoreCase(config.mondeCommand)) {
                GuiWorldSwitcher.get().onMondeCommandSent();
            }

            AutoQiqiClient.log("Legendary", "Command executing: '" + cmd + "'");
            try {
                if (cmd.startsWith("/")) {
                    client.player.networkHandler.sendCommand(cmd.substring(1));
                } else {
                    client.player.networkHandler.sendChatMessage(cmd);
                }
            } catch (Exception e) {
                AutoQiqiClient.log("Legendary", "Command send failed (network?): " + e.getMessage() + " — re-queuing");
                r.pendingCommand = cmd;
                r.commandExecuteAtTick = tickCount + 40;
            }
        }
    }

    private void setState(LegendaryRunState r, LegendaryRunState.State newState, String reason) {
        if (r.state != newState) {
            AutoQiqiClient.log("Legendary", "State: " + r.state + " -> " + newState + " (" + reason + ")");
        }
        r.state = newState;
    }

    private String dumpTimerSummary() {
        WorldTracker tracker = WorldTracker.get();
        StringBuilder sb = new StringBuilder("Timers[cur=");
        sb.append(tracker.getCurrentWorld()).append("]: ");
        boolean first = true;
        for (WorldTimerData d : tracker.getAllTimers()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(d.getWorldName()).append("=");
            if (!d.isTimerKnown()) {
                sb.append("??");
            } else if (d.isEventActive()) {
                sb.append("EVENT");
            } else {
                sb.append(d.getFormattedTime());
            }
        }
        return sb.toString();
    }
}
