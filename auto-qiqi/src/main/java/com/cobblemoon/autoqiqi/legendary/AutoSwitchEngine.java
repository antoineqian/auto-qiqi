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

    private long tickCount = 0;
    private long stateEnteredTick = 0;
    private long nextActionAtTick = 0;

    private enum State {
        IDLE, OPENING_MENU, WAITING_AFTER_SWITCH, POLLING,
        WAITING_FOR_POLL_RESPONSE, WAITING_AFTER_EVENT_SWITCH, PAUSED_FOR_CAPTURE
    }

    private enum SwitchPurpose { LEARNING, POLL, EVENT }

    private State state = State.IDLE;
    private SwitchPurpose switchPurpose = null;
    private String currentPollWorld = null;
    private String targetEventWorld = null;
    private boolean screenCloseAttempted = false;
    private boolean arrivalHomePending = false;
    private boolean isHomeTeleport = false;
    private String pendingHomeWorld = null;
    private long eventTimerZeroTick = 0;
    private boolean isEventRepoll = false;
    private int eventRepollRetries = 0;
    private static final int MAX_EVENT_REPOLL_RETRIES = 5;
    private static final int FAST_WINDOW_SECONDS = 60;

    private long pauseStartTick = 0;
    private String pausedForPokemon = null;

    private String pendingCommand = null;
    private long commandExecuteAtTick = 0;

    private int homeRetryCount = 0;
    private static final int MAX_HOME_RETRIES = 2;

    private long bossYieldStartTick = 0;
    private static final int BOSS_YIELD_TIMEOUT_TICKS = 600; // 30 seconds

    private AutoSwitchEngine() {}

    public static AutoSwitchEngine get() { return INSTANCE; }

    public boolean isPaused() { return state == State.PAUSED_FOR_CAPTURE; }

    /** True when waiting for /home teleport to complete. Movement would cancel the teleport. */
    public boolean isWaitingForHomeTeleport() {
        return isHomeTeleport && (state == State.WAITING_AFTER_SWITCH || state == State.WAITING_AFTER_EVENT_SWITCH);
    }

    public String getStateDisplay() {
        if (!AutoQiqiConfig.get().legendaryAutoSwitch) return "Inactif";
        return switch (state) {
            case IDLE -> "En attente";
            case OPENING_MENU -> switch (switchPurpose) {
                case LEARNING -> "Apprentissage mondes...";
                case EVENT -> "EVENT! Ouverture menu...";
                case POLL -> "Changement -> " + currentPollWorld;
            };
            case WAITING_AFTER_SWITCH -> "Teleportation " + currentPollWorld + "...";
            case POLLING -> "Envoi /nextleg...";
            case WAITING_FOR_POLL_RESPONSE -> "Lecture timer " + currentPollWorld + "...";
            case WAITING_AFTER_EVENT_SWITCH -> "En position dans " + targetEventWorld;
            case PAUSED_FOR_CAPTURE -> "PAUSE - Capture " + pausedForPokemon + " (" + getPauseRemainingDisplay() + ")";
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
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (config.pauseDurationSeconds <= 0) return "reprendre: [J]";
        long elapsed = (tickCount - pauseStartTick) / 20;
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

        if (state == State.OPENING_MENU && switchPurpose == SwitchPurpose.LEARNING) {
            setState(State.IDLE, "worlds learned");
            stateEnteredTick = 0;
            nextActionAtTick = 0;
        }
    }

    public void onWorldSwitchComplete() {
        stopActiveEngines();

        if (state == State.OPENING_MENU) {
            isHomeTeleport = false;
            pendingHomeWorld = null;
            if (switchPurpose == SwitchPurpose.EVENT) {
                AutoQiqiClient.log("Legendary", "GUI switch complete (EVENT) -> " + targetEventWorld);
                setState(State.WAITING_AFTER_EVENT_SWITCH, "gui event switch done");
                stateEnteredTick = tickCount;
                eventTimerZeroTick = 0;
                isEventRepoll = false;
                eventRepollRetries = 0;
            } else {
                AutoQiqiClient.log("Legendary", "GUI switch complete (POLL) -> " + currentPollWorld);
                setState(State.WAITING_AFTER_SWITCH, "gui poll switch done");
                stateEnteredTick = tickCount;
                screenCloseAttempted = false;
                arrivalHomePending = false;
            }
        }
    }

    public void onGuiTimeout() {
        if (state == State.OPENING_MENU) {
            AutoQiqiClient.log("Legendary", "GUI timeout (purpose=" + switchPurpose + ", target=" + currentPollWorld + ")");
            setState(State.IDLE, "gui timeout");
            stateEnteredTick = tickCount;
        }
    }

    // ========================
    // Pause / Resume
    // ========================

    public void pauseForCapture(String pokemonName) {
        AutoQiqiClient.log("Legendary", "PAUSING for capture: " + pokemonName + " (was " + state + ")");
        this.pausedForPokemon = pokemonName;
        this.pauseStartTick = tickCount;
        setState(State.PAUSED_FOR_CAPTURE, "capture " + pokemonName);
        this.stateEnteredTick = tickCount;
        GuiWorldSwitcher.get().cancelPending();
    }

    public void resumeFromPause() {
        if (state == State.PAUSED_FOR_CAPTURE) {
            AutoQiqiClient.log("Legendary", "RESUMING from capture pause (was paused for " + pausedForPokemon + ")");
            pausedForPokemon = null;
            setState(State.IDLE, "resume from pause");
            stateEnteredTick = tickCount;
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

        if (!config.legendaryAutoSwitch && state != State.IDLE) {
            AutoQiqiClient.log("Legendary", "legendaryAutoSwitch OFF while in " + state + ", cancelling");
            GuiWorldSwitcher.get().cancelPending();
            pendingCommand = null;
            pendingHomeWorld = null;
            isHomeTeleport = false;
            setState(State.IDLE, "autoSwitch disabled");
            stateEnteredTick = tickCount;
            return;
        }

        tickPendingCommand(client);
        if (pendingCommand != null) return;

        switch (state) {
            case IDLE -> handleIdle(client, config);
            case OPENING_MENU -> handleOpeningMenu();
            case WAITING_AFTER_SWITCH -> handleWaitingAfterSwitch(client);
            case POLLING -> handlePolling(client, config);
            case WAITING_FOR_POLL_RESPONSE -> handleWaitingForResponse();
            case WAITING_AFTER_EVENT_SWITCH -> handleWaitingAfterEventSwitch(config);
            case PAUSED_FOR_CAPTURE -> handlePausedForCapture(config);
        }
    }

    private boolean isInBattle() {
        return CobblemonClient.INSTANCE.getBattle() != null
                || CaptureEngine.get().isActive();
    }

    /**
     * Boss encounters take priority over legendary world switching.
     * Returns true when battle mode is active and a boss is being engaged or is nearby.
     */
    private boolean isBossActive() {
        AutoBattleEngine battle = AutoBattleEngine.get();
        if (battle.getMode() == BattleMode.OFF) return false;
        if (battle.isEngagingBoss()) return true;
        return battle.isBossNearby();
    }

    private void handleIdle(MinecraftClient client, AutoQiqiConfig config) {
        if (!config.legendaryAutoSwitch) return;
        if (client.currentScreen != null) return;
        if (isInBattle()) return;

        if (isBossActive()) {
            if (bossYieldStartTick == 0) {
                bossYieldStartTick = tickCount;
                AutoQiqiClient.log("Legendary", "IDLE: yielding to boss encounter");
            }
            long waited = tickCount - bossYieldStartTick;
            if (waited >= BOSS_YIELD_TIMEOUT_TICKS) {
                AutoQiqiClient.log("Legendary", "IDLE: boss yield timeout after " + (waited / 20) + "s, proceeding anyway");
                bossYieldStartTick = 0;
            } else {
                if (waited % 200 == 0 && waited > 0) {
                    AutoQiqiClient.log("Legendary", "IDLE: still yielding to boss (" + (waited / 20) + "s / " + (BOSS_YIELD_TIMEOUT_TICKS / 20) + "s)");
                }
                return;
            }
        } else {
            bossYieldStartTick = 0;
        }

        GuiWorldSwitcher switcher = GuiWorldSwitcher.get();
        WorldTracker tracker = WorldTracker.get();

        if (!switcher.hasLearnedWorlds()) {
            if (stateEnteredTick > 0 && (tickCount - stateEnteredTick) < 200) return;
            AutoQiqiClient.log("Legendary", "IDLE: worlds not learned yet, opening /monde for learning");
            switcher.requestLearning();
            sendCommand(client, config.mondeCommand);
            switchPurpose = SwitchPurpose.LEARNING;
            setState(State.OPENING_MENU, "learning");
            stateEnteredTick = tickCount;
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
                targetEventWorld = currentWorldName;
                setState(State.WAITING_AFTER_EVENT_SWITCH, "camping timer expired");
                stateEnteredTick = tickCount;
                eventTimerZeroTick = tickCount;
                isEventRepoll = false;
                eventRepollRetries = 0;
                return;
            }
        }

        String eventWorld = tracker.getWorldToSwitchTo();
        if (eventWorld != null && AutoBattleEngine.get().getMode() == BattleMode.TEST) {
            if (tickCount % 200 == 0) {
                AutoQiqiClient.log("Legendary", "IDLE: event ready (" + eventWorld + ") but TEST mode active, skipping switch");
            }
        } else if (eventWorld != null) {
            targetEventWorld = eventWorld;
            WorldTimerData evtData = tracker.getTimer(eventWorld);
            String evtInfo = evtData != null ? (evtData.isEventActive() ? "EVENT_ACTIVE" : evtData.getFormattedTime()) : "?";
            AutoQiqiClient.log("Legendary", "IDLE: event switch needed -> " + eventWorld
                    + " (timer=" + evtInfo + ", from=" + currentWorldName + ")");
            if (config.isHomeWorld(eventWorld)) {
                String homeName = config.getHomeCommand(eventWorld);
                AutoQiqiClient.log("Legendary", "EVENT! Home switch -> " + eventWorld + " via /home " + homeName);
                stopActiveEngines();
                releaseMovementKeys(client);
                sendCommand(client, "/home " + homeName);
                pendingHomeWorld = eventWorld;
                isHomeTeleport = true;
                homeRetryCount = 0;
                setState(State.WAITING_AFTER_EVENT_SWITCH, "event /home " + eventWorld);
                stateEnteredTick = tickCount;
                eventTimerZeroTick = 0;
                isEventRepoll = false;
                eventRepollRetries = 0;
            } else {
                switcher.requestSwitch(eventWorld);
                sendCommand(client, config.mondeCommand);
                switchPurpose = SwitchPurpose.EVENT;
                setState(State.OPENING_MENU, "event gui " + eventWorld);
                stateEnteredTick = tickCount;
            }
            return;
        }

        if (tickCount < nextActionAtTick) return;

        String expiredWorld = tracker.getWorldWithExpiredTimer(config.repollCooldownSeconds);
        if (expiredWorld != null) {
            WorldTimerData expData = tracker.getTimer(expiredWorld);
            AutoQiqiClient.log("Legendary", "IDLE: repoll expired timer -> " + expiredWorld
                    + " (last update " + (expData != null ? expData.getSecondsSinceLastUpdate() + "s ago" : "?") + ")");
            switchAndPoll(client, config, expiredWorld);
            return;
        }

        String unknownWorld = tracker.getWorldWithUnknownTimer();
        if (unknownWorld != null) {
            AutoQiqiClient.log("Legendary", "IDLE: discovering unknown timer -> " + unknownWorld
                    + " (" + tracker.getKnownTimerCount() + "/" + tracker.getTotalWorldCount() + " known)");
            switchAndPoll(client, config, unknownWorld);
            return;
        }

        String soonestWorld = tracker.getWorldWithSoonestTimer();
        if (soonestWorld != null) {
            WorldTimerData soonData = tracker.getTimer(soonestWorld);
            AutoQiqiClient.log("Legendary", "IDLE: all timers known, camping soonest -> " + soonestWorld
                    + " (" + (soonData != null ? soonData.getFormattedTime() : "?") + "). " + dumpTimerSummary());
            switchAndPoll(client, config, soonestWorld);
        }
    }

    private void switchAndPoll(MinecraftClient client, AutoQiqiConfig config, String worldName) {
        currentPollWorld = worldName;
        String currentWorld = WorldTracker.get().getCurrentWorld();

        if (worldName.equalsIgnoreCase(currentWorld)) {
            AutoQiqiClient.log("Legendary", "switchAndPoll: already in " + worldName + ", polling directly");
            setState(State.POLLING, "already in " + worldName);
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logWorldSwitch(currentWorld, worldName);
            stateEnteredTick = tickCount;
        } else if (config.isHomeWorld(worldName)) {
            String homeName = config.getHomeCommand(worldName);
            AutoQiqiClient.log("Legendary", "switchAndPoll: " + currentWorld + " -> " + worldName + " via /home " + homeName);
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logWorldSwitch(currentWorld, worldName);
            stopActiveEngines();
            releaseMovementKeys(client);
            sendCommand(client, "/home " + homeName);
            pendingHomeWorld = worldName;
            isHomeTeleport = true;
            homeRetryCount = 0;
            setState(State.WAITING_AFTER_SWITCH, "/home " + homeName);
            stateEnteredTick = tickCount;
            screenCloseAttempted = true;
            arrivalHomePending = false;
        } else {
            GuiWorldSwitcher switcher = GuiWorldSwitcher.get();
            if (switcher.hasLearnedWorlds()) {
                AutoQiqiClient.log("Legendary", "switchAndPoll: " + currentWorld + " -> " + worldName + " via GUI");
                com.cobblemoon.autoqiqi.common.SessionLogger.get().logWorldSwitch(currentWorld, worldName);
                switcher.requestSwitch(worldName);
                sendCommand(client, config.mondeCommand);
                switchPurpose = SwitchPurpose.POLL;
                setState(State.OPENING_MENU, "gui poll " + worldName);
                stateEnteredTick = tickCount;
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

    private void handleOpeningMenu() {
        if ((tickCount - stateEnteredTick) > 400) {
            AutoQiqiClient.log("Legendary", "OPENING_MENU timeout after 400 ticks (purpose=" + switchPurpose + ", target=" + currentPollWorld + ")");
            GuiWorldSwitcher.get().cancelPending();
            setState(State.IDLE, "menu timeout");
            stateEnteredTick = tickCount;
        }
    }

    private void handleWaitingAfterSwitch(MinecraftClient client) {
        if (!screenCloseAttempted && client.currentScreen != null && (tickCount - stateEnteredTick) > 20) {
            screenCloseAttempted = true;
            client.setScreen(null);
        }

        if (isHomeTeleport) {
            releaseMovementKeys(client);
        }

        int waitTicks;
        if (isHomeTeleport) {
            int warmupTicks = AutoQiqiConfig.get().homeTeleportWarmupSeconds * 20;
            waitTicks = warmupTicks + 40;
        } else {
            waitTicks = WorldTracker.get().hasOtherWorldWithinSeconds(FAST_WINDOW_SECONDS) ? 40 : 80;
        }

        if ((tickCount - stateEnteredTick) > waitTicks) {
            if (pendingHomeWorld != null) {
                if (!verifyHomeDimension(client, pendingHomeWorld)) {
                    if (homeRetryCount < MAX_HOME_RETRIES) {
                        homeRetryCount++;
                        String homeCmd = AutoQiqiConfig.get().getHomeCommand(pendingHomeWorld);
                        AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: dimension mismatch (movement cancelled /home?), retrying /home " + homeCmd + " (" + homeRetryCount + "/" + MAX_HOME_RETRIES + ")");
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §cTeleport interrompu? Nouvelle tentative /home " + homeCmd + "..."), false);
                        }
                        sendCommand(client, "/home " + homeCmd);
                        stateEnteredTick = tickCount;
                        return;
                    } else {
                        AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: dimension still wrong after " + MAX_HOME_RETRIES + " retries, aborting switch");
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §cTeleport echoue apres " + MAX_HOME_RETRIES + " tentatives."), false);
                        }
                        homeRetryCount = 0;
                        pendingHomeWorld = null;
                        isHomeTeleport = false;
                        returnToIdle();
                        return;
                    }
                }
                homeRetryCount = 0;
                AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: /home warmup done, setting currentWorld=" + pendingHomeWorld);
                WorldTracker.get().setCurrentWorld(pendingHomeWorld);
                pendingHomeWorld = null;
                isHomeTeleport = false;
            }

            AutoQiqiConfig config = AutoQiqiConfig.get();
            String arrivalHome = config.getArrivalHome(currentPollWorld);
            if (arrivalHome != null && !arrivalHomePending) {
                AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: arrival home -> /home " + arrivalHome + " for " + currentPollWorld);
                arrivalHomePending = true;
                isHomeTeleport = true;
                pendingHomeWorld = null;
                sendCommand(client, "/home " + arrivalHome);
                stateEnteredTick = tickCount;
                screenCloseAttempted = true;
                return;
            }
            arrivalHomePending = false;
            AutoQiqiClient.log("Legendary", "WAITING_AFTER_SWITCH: switch to " + currentPollWorld + " complete (waited " + waitTicks + " ticks), now polling");
            setState(State.POLLING, "switch wait done");
            stateEnteredTick = tickCount;
        }
    }

    private void handlePolling(MinecraftClient client, AutoQiqiConfig config) {
        if (client.currentScreen != null) return;
        if (isInBattle()) return;
        AutoQiqiClient.log("Legendary", "POLLING: sending " + config.nextlegCommand + " for " + currentPollWorld);
        ChatMessageHandler.get().setPendingPoll(currentPollWorld);
        sendCommand(client, config.nextlegCommand);
        setState(State.WAITING_FOR_POLL_RESPONSE, "poll " + currentPollWorld);
        stateEnteredTick = tickCount;
    }

    private void handleWaitingForResponse() {
        if ((tickCount - stateEnteredTick) > 400) {
            AutoQiqiClient.log("Legendary", "WAITING_FOR_POLL_RESPONSE: timeout (400 ticks) for " + currentPollWorld
                    + " (isEventRepoll=" + isEventRepoll + ")");
            if (isEventRepoll && eventRepollRetries < MAX_EVENT_REPOLL_RETRIES) {
                eventRepollRetries++;
                AutoQiqiClient.log("Legendary", "Event repoll response timeout, retrying ("
                        + eventRepollRetries + "/" + MAX_EVENT_REPOLL_RETRIES + ")");
                setState(State.WAITING_AFTER_EVENT_SWITCH, "repoll retry #" + eventRepollRetries);
                eventTimerZeroTick = tickCount;
                stateEnteredTick = tickCount;
                return;
            }
            isEventRepoll = false;
            returnToIdle();
        }
    }

    private void handleWaitingAfterEventSwitch(AutoQiqiConfig config) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (pendingHomeWorld != null) {
            int warmupTicks = config.homeTeleportWarmupSeconds * 20;
            if (mc != null) releaseMovementKeys(mc);
            if ((tickCount - stateEnteredTick) < warmupTicks + 40) return;
            if (!verifyHomeDimension(mc, pendingHomeWorld)) {
                if (homeRetryCount < MAX_HOME_RETRIES) {
                    homeRetryCount++;
                    String homeCmd = config.getHomeCommand(pendingHomeWorld);
                    AutoQiqiClient.log("Legendary", "WAITING_AFTER_EVENT_SWITCH: dimension mismatch, retrying /home " + homeCmd);
                    if (mc != null && mc.player != null) {
                        mc.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r §cTeleport interrompu? Nouvelle tentative..."), false);
                    }
                    sendCommand(mc, "/home " + homeCmd);
                    stateEnteredTick = tickCount;
                    return;
                }
                homeRetryCount = 0;
                pendingHomeWorld = null;
                isHomeTeleport = false;
                return;
            }
            homeRetryCount = 0;
            AutoQiqiClient.log("Legendary", "WAITING_AFTER_EVENT_SWITCH: /home warmup done, setting currentWorld=" + pendingHomeWorld);
            WorldTracker.get().setCurrentWorld(pendingHomeWorld);
            pendingHomeWorld = null;
            isHomeTeleport = false;
            stateEnteredTick = tickCount;
        }

        if ((tickCount - stateEnteredTick) > 6000) {
            AutoQiqiClient.log("Legendary", "WAITING_AFTER_EVENT_SWITCH: timeout (5min) for " + targetEventWorld + ", forcing repoll");
            transitionToEventRepoll();
            return;
        }

        WorldTracker tracker = WorldTracker.get();
        WorldTimerData currentTimer = tracker.getTimer(targetEventWorld);

        if (currentTimer != null && currentTimer.isTimerKnown()) {
            long remaining = currentTimer.getEstimatedRemainingSeconds();
            if (remaining > 0) {
                eventTimerZeroTick = 0;
                return;
            }
        }

        if (eventTimerZeroTick == 0) {
            AutoQiqiClient.log("Legendary", "WAITING_AFTER_EVENT_SWITCH: timer reached 0 for " + targetEventWorld + ", playing bell");
            eventTimerZeroTick = tickCount;
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
        if ((tickCount - eventTimerZeroTick) < (waitSeconds * 20L)) return;

        transitionToEventRepoll();
    }

    private void transitionToEventRepoll() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        if (isInBattle()) return;
        if (client.currentScreen != null) {
            if ((tickCount - eventTimerZeroTick) > 40) client.setScreen(null);
            return;
        }

        isEventRepoll = true;
        currentPollWorld = targetEventWorld;
        AutoQiqiClient.log("Legendary", "transitionToEventRepoll: " + targetEventWorld + " -> polling for new timer");
        setState(State.POLLING, "event repoll " + targetEventWorld);
        stateEnteredTick = tickCount;
    }

    private void handlePausedForCapture(AutoQiqiConfig config) {
        if (config.pauseDurationSeconds > 0) {
            long pausedTicks = tickCount - pauseStartTick;
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
        if (state == State.WAITING_FOR_POLL_RESPONSE) {
            WorldTimerData pollData = WorldTracker.get().getTimer(currentPollWorld);
            AutoQiqiClient.log("Legendary", "Timer response received for " + currentPollWorld
                    + ": " + (pollData != null ? pollData.getFormattedTime() + " (raw=" + pollData.getRawRemainingSeconds() + "s)" : "null"));

            if (isEventRepoll) {
                long timerValue = (pollData != null && pollData.isTimerKnown())
                        ? pollData.getRawRemainingSeconds() : -1;

                if (timerValue <= 0 && eventRepollRetries < MAX_EVENT_REPOLL_RETRIES) {
                    eventRepollRetries++;
                    AutoQiqiClient.log("Legendary", "Event repoll: timer still 0, retry #" + eventRepollRetries);
                    setState(State.WAITING_AFTER_EVENT_SWITCH, "repoll timer=0 retry");
                    eventTimerZeroTick = tickCount;
                    return;
                }

                AutoQiqiClient.log("Legendary", "Event repoll done for " + currentPollWorld
                        + ": new timer=" + timerValue + "s");
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null && timerValue > 0) {
                    long min = timerValue / 60;
                    long sec = timerValue % 60;
                    client.player.sendMessage(
                            Text.literal("§6[Auto-Qiqi]§r §7Nouveau timer " + currentPollWorld
                                    + ": §f" + String.format("%02dm%02ds", min, sec)), false);
                }
                isEventRepoll = false;
                eventRepollRetries = 0;
            }
            returnToIdle();
        }
    }

    private void returnToIdle() {
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
        nextActionAtTick = tickCount + cooldownTicks;
        AutoQiqiClient.log("Legendary", "returnToIdle: cooldown=" + cooldownTicks + " ticks (" + reason + "). " + dumpTimerSummary());
        setState(State.IDLE, "return " + reason);
        stateEnteredTick = tickCount;
    }

    public void forcePoll() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            AutoQiqiClient.log("Legendary", "forcePoll: marking all timers for repoll");
            WorldTracker.get().markAllForRepoll();
            setState(State.IDLE, "force poll");
            stateEnteredTick = 0;
            nextActionAtTick = 0;
        }
    }

    public void relearnCommands() {
        AutoQiqiClient.log("Legendary", "relearnCommands: resetting GUI world map");
        GuiWorldSwitcher.get().reset();
        setState(State.IDLE, "relearn");
        stateEnteredTick = 0;
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

    private void sendCommand(MinecraftClient client, String command) {
        if (client.player == null) return;
        AutoQiqiConfig config = AutoQiqiConfig.get();
        int delayMs = HumanDelay.commandDelayMs(config.commandDelayMinMs, config.commandDelayMaxMs);
        if (WorldTracker.get().hasOtherWorldWithinSeconds(FAST_WINDOW_SECONDS)) {
            delayMs = Math.min(delayMs, 1200);
        }
        int delayTicks = Math.max(1, delayMs / 50);
        pendingCommand = command;
        commandExecuteAtTick = tickCount + delayTicks;
        AutoQiqiClient.log("Legendary", "Command queued: '" + command + "' (delay=" + delayTicks + " ticks / " + delayMs + "ms)");
    }

    private void tickPendingCommand(MinecraftClient client) {
        if (pendingCommand != null && tickCount >= commandExecuteAtTick) {
            String cmd = pendingCommand;
            pendingCommand = null;

            AutoQiqiConfig config = AutoQiqiConfig.get();
            if (cmd.equalsIgnoreCase(config.mondeCommand)) {
                GuiWorldSwitcher.get().onMondeCommandSent();
            }

            AutoQiqiClient.log("Legendary", "Command executing: '" + cmd + "'");
            if (cmd.startsWith("/")) {
                client.player.networkHandler.sendCommand(cmd.substring(1));
            } else {
                client.player.networkHandler.sendChatMessage(cmd);
            }
        }
    }

    private void setState(State newState, String reason) {
        if (state != newState) {
            AutoQiqiClient.log("Legendary", "State: " + state + " -> " + newState + " (" + reason + ")");
        }
        state = newState;
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
