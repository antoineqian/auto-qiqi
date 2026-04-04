package com.cobblemoon.autoqiqi;

import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.BattleDecisionRouter;
import com.cobblemoon.autoqiqi.battle.BattleMode;
import com.cobblemoon.autoqiqi.battle.BattleScreenHelper;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfigScreen;
import com.cobblemoon.autoqiqi.fish.AutofishEngine;
import com.cobblemoon.autoqiqi.legendary.*;
import com.cobblemoon.autoqiqi.mine.GoldMiningEngine;
import com.cobblemoon.autoqiqi.npc.TowerGuiHandler;
import com.cobblemoon.autoqiqi.npc.TowerNpcEngine;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.Element;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Main client entrypoint for Auto-Qiqi.
 * Merges AutoBattle, AutoLeg, and AutoWalk into one mod. (AutoFish disabled for now.)
 */
public class AutoQiqiClient implements ClientModInitializer {
    public static final String MOD_ID = "auto-qiqi";

    // Keybinds
    private static KeyBinding toggleBattleKey;
    private static KeyBinding toggleHudKey;
    private static KeyBinding toggleLegendaryKey;
    private static KeyBinding forcePollKey;
    private static KeyBinding toggleModKey;
    private static KeyBinding towerStartKey;
    private static KeyBinding stopAllKey;
    private static KeyBinding toggleAutoHopKey;

    private static final int PERIODIC_SCAN_INTERVAL = 600; // 30 seconds

    // Feature state
    private int periodicScanTicks = 0;


    // Capture-battle bridge: detect when a capture battle ends (with debounce)
    private boolean wasInCaptureBattle = false;
    private int battleNullTicks = 0;
    private static final int BATTLE_END_DEBOUNCE_TICKS = 40; // 2 seconds at 20 tps

    // Track whether CaptureEngine was ever active during a legendary pause,
    // so we only auto-resume when a real capture finishes (not immediately).
    private boolean captureSeenActiveDuringPause = false;
    private long legendaryPauseStartMs = 0;
    private static final long MIN_LEGENDARY_PAUSE_MS = 10_000; // 10 seconds minimum before auto-resume

    // Safety: release stuck keys on first tick
    private boolean firstTickDone = false;

    // Hunt timer: /pk hunt <hours> enables legendary + roaming for a duration
    private static long huntEndTimeMs = 0;
    private static boolean huntActive = false;

    // Idle/blocked state tracking: detect prolonged inactivity from disconnect or open screen
    private long blockedSinceMs = 0;
    private String blockedReason = null;
    private boolean wasConnected = false;
    private static final long BLOCKED_LOG_INTERVAL_MS = 300_000; // log every 5 minutes while blocked
    private long lastBlockedLogMs = 0;

    // Manual vs mod engagement: only auto-fight when the mod started the battle (simulated send-out key)
    private static long clientTickCounter = 0;
    private static long lastModEngagementTick = -1000;
    private static Boolean currentBattleStartedByMod = null;
    private boolean wasInBattleScreen = false;
    private static final int MOD_ENGAGEMENT_WINDOW_TICKS = 300; // ~15 seconds (lag between key and battle GUI)

    // Debug mode: /pk debug toggles verbose chat logging
    private static boolean debugMode = false;

    // Tick rate calibration: /pk tickrate
    private long tickrateStartMs = 0;
    private long tickrateStartTime = 0;
    private static final long TICKRATE_MEASURE_MS = 30_000; // measure over 30 seconds

    /** Debounce log when auto-closing Game Menu for unfocused autofight (log at most once per 5s). */
    private long lastUnfocusedCloseLogTick = -1000;
    private static final int UNFOCUSED_CLOSE_LOG_DEBOUNCE_TICKS = 100;

    /** Tick-based move selection: only act after we've been on the same move selection for this many ticks (lets GUI/state settle). */
    private Object moveSelectionRequestSeen = null;
    private long moveSelectionFirstSeenTick = -1000;
    private static final int MOVE_SELECTION_FALLBACK_DELAY_TICKS = 3;

    /** Tick-based general action fallback: recover if the mixin's runLater failed to fire. */
    private Object generalActionRequestSeen = null;
    private long generalActionFirstSeenTick = -1000;
    private static final int GENERAL_ACTION_FALLBACK_DELAY_TICKS = 20; // 1 second — give mixin time first

    /** Trainer dialog advance: tick path so we're not stuck on pre-battle dialogs (e.g. Ethan "Let's battle!"). */
    private Screen lastTrainerDialogScreen = null;
    private int trainerDialogTicksOnScreen = 0;
    private static final int TRAINER_DIALOG_ADVANCE_DELAY_TICKS = 20;

    /** Set in onInitializeClient so static warp callback can clear instance state. */
    private static AutoQiqiClient instance;

    @Override
    public void onInitializeClient() {
        instance = this;
        log("Init", "Initializing Auto-Qiqi v" + BuildConstants.VERSION + "...");
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logInfo("Auto-Qiqi v" + BuildConstants.VERSION + " initialized (new session)");

        AutoQiqiConfig.load();
        com.cobblemoon.autoqiqi.battle.SmogonData.load();
        AutoBattleEngine.get().setMode(BattleMode.fromString(AutoQiqiConfig.get().battleMode));
        WorldTracker.get().refreshWorldList();
        if (AutoQiqiConfig.get().autoReconnectEnabled) {
            AutoReconnectEngine.get().enable();
        }

        registerKeybindings();
        registerScreenEvents();
        registerCommands();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            clientTickCounter++;
            trackBlockedState(client);
            boolean nowInBattleScreen = BattleScreenHelper.isInBattleScreen(client);
            // When leaving battle screen, clear battle attribution — but not when we're only showing the Game Menu
            // (pause screen), so we keep attribution and will close the menu below for unfocused autofight
            boolean isGameMenuScreen = isPauseOrGameMenuScreen(client.currentScreen);
            boolean skipClearForUnfocused = wasInBattleScreen && !nowInBattleScreen && isGameMenuScreen
                    && !client.isWindowFocused()
                    && com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null;
            if (wasInBattleScreen && !nowInBattleScreen && !skipClearForUnfocused) {
                currentBattleStartedByMod = null;
                BattleDecisionRouter.clearMoveSelectionDebounce();
            }
            wasInBattleScreen = nowInBattleScreen;

            // Unfocused autofight: close Game Menu when in battle or berserk scanning, and any autofight is enabled
            // Only do this when the window is NOT focused (i.e. Minecraft auto-opened the pause menu).
            // If the user manually paused while focused, leave it alone.
            boolean inBattle = com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null;
            boolean berserkScanning = AutoBattleEngine.get().getMode() == BattleMode.BERSERK;
            if (client.currentScreen != null
                    && !client.isWindowFocused()
                    && isPauseOrGameMenuScreen(client.currentScreen)
                    && (inBattle || berserkScanning)
                    && isAutofightRelevantForUnfocused()) {
                client.setScreen(null);
                if (clientTickCounter - lastUnfocusedCloseLogTick >= UNFOCUSED_CLOSE_LOG_DEBOUNCE_TICKS) {
                    lastUnfocusedCloseLogTick = clientTickCounter;
                    logDebug("Battle", "Unfocused: closed Game Menu to continue autofight");
                }
            }

            // Tick-based general action fallback: if the mixin's runLater didn't fire, handle it here.
            try {
            if (com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null
                    && shouldAutoFight()
                    && !CaptureEngine.get().isActive()
                    && client.currentScreen != null) {
                Object actionSelection = getBattleCurrentActionSelection(client.currentScreen);
                if (actionSelection instanceof BattleGeneralActionSelection gas) {
                    if (gas.getRequest().getResponse() == null) {
                        Object req = gas.getRequest();
                        if (req != generalActionRequestSeen) {
                            generalActionRequestSeen = req;
                            generalActionFirstSeenTick = clientTickCounter;
                        }
                        if (clientTickCounter - generalActionFirstSeenTick >= GENERAL_ACTION_FALLBACK_DELAY_TICKS) {
                            logDebug("Battle", "GeneralAction: tick fallback (mixin may have missed)");
                            BattleDecisionRouter.handleGeneralAction(gas);
                            generalActionRequestSeen = null;
                        }
                    } else {
                        generalActionRequestSeen = null;
                    }
                } else {
                    generalActionRequestSeen = null;
                }
            }
            } catch (Exception e) {
                logDebug("Battle", "GeneralAction tick error: " + e.getMessage());
                generalActionRequestSeen = null;
            }

            // Tick-based move selection (single path for autofight): only this code path submits the move, so no race when unfocused.
            // currentScreen is usually the BattleGUI (top-level), not BattleMoveSelection; get current action selection from it.
            try {
            if (com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null
                    && shouldAutoFight()
                    && !CaptureEngine.get().isActive()
                    && client.currentScreen != null) {
                Object moveSelection = null;
                if (BattleMoveSelection.class.isInstance(client.currentScreen)) {
                    moveSelection = client.currentScreen;
                } else {
                    String screenClassName = client.currentScreen.getClass().getSimpleName();
                    if (screenClassName.toLowerCase().contains("battle")) {
                        moveSelection = getBattleCurrentMoveSelection(client.currentScreen);
                    }
                }
                if (moveSelection != null && BattleMoveSelection.class.isInstance(moveSelection)) {
                    BattleMoveSelection sel = (BattleMoveSelection) (Object) moveSelection;
                    if (sel.getRequest().getResponse() == null) {
                        Object req = sel.getRequest();
                        if (req != moveSelectionRequestSeen) {
                            moveSelectionRequestSeen = req;
                            moveSelectionFirstSeenTick = clientTickCounter;
                        }
                        if (clientTickCounter - moveSelectionFirstSeenTick >= MOVE_SELECTION_FALLBACK_DELAY_TICKS) {
                            logDebug("Battle", "MoveSelection: tick");
                            BattleDecisionRouter.performMoveSelection(sel);
                        }
                    } else {
                        moveSelectionRequestSeen = null;
                    }
                } else {
                    moveSelectionRequestSeen = null;
                    // Log when we're on a battle screen but can't get move selection (e.g. Extended Battle UI structure)
                    if (moveSelection == null) {
                        String screenClassName = client.currentScreen != null ? client.currentScreen.getClass().getSimpleName() : "null";
                        if (screenClassName.toLowerCase().contains("battle") && clientTickCounter % 100 == 50) {
                            logDebug("Battle", "MoveSelection: screen not detected (class=" + screenClassName + ") — cannot auto-pick move");
                        }
                    }
                }
            }
            } catch (Exception e) {
                logDebug("Battle", "MoveSelection tick error: " + e.getMessage());
                moveSelectionRequestSeen = null;
            }

            // Tick-based trainer dialog advance: when autofight is TRAINER and a dialog is open (e.g. Ethan "Let's battle!"),
            // we have no mixin for it — only battle screens trigger mixins. This tick path unsticks pre-battle dialogs.
            if (shouldAutoFight()
                    && AutoBattleEngine.get().getMode() == BattleMode.TRAINER
                    && !CaptureEngine.get().isActive()
                    && !TowerGuiHandler.get().isHandlingScreen()
                    && client.currentScreen != null) {
                Screen screen = client.currentScreen;
                boolean isBattleScreen = screen.getClass().getSimpleName().toLowerCase().contains("battle");
                boolean preBattleOrNonBattleGui = com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() == null
                        || !isBattleScreen;
                if (preBattleOrNonBattleGui && hasClickableDialogButton(screen)) {
                    if (screen != lastTrainerDialogScreen) {
                        lastTrainerDialogScreen = screen;
                        trainerDialogTicksOnScreen = 0;
                    }
                    trainerDialogTicksOnScreen++;
                    if (trainerDialogTicksOnScreen >= TRAINER_DIALOG_ADVANCE_DELAY_TICKS) {
                        ButtonWidget first = getFirstDialogButton(screen);
                        if (first != null) {
                            logDebug("Battle", "Trainer dialog: advancing (clicking first button after " + TRAINER_DIALOG_ADVANCE_DELAY_TICKS + " ticks)");
                            first.onPress();
                        }
                        lastTrainerDialogScreen = null;
                        trainerDialogTicksOnScreen = 0;
                    }
                } else {
                    lastTrainerDialogScreen = null;
                    trainerDialogTicksOnScreen = 0;
                }
            } else {
                lastTrainerDialogScreen = null;
                trainerDialogTicksOnScreen = 0;
            }

            AutoReconnectEngine.get().tick(client);

            if (!firstTickDone && client.player != null) {
                firstTickDone = true;
                // Ensure everything starts disabled: no walking, hopping, or capture
                PokemonWalker.get().stop();
                CaptureEngine.get().stop();
                MovementHelper.releaseMovementKeys(client);
                logDebug("Init", "First tick: all engines disabled, movement keys released");

                // Show last session recap (delayed so chat is ready)
                runLater(() -> {
                    java.util.List<String> recap = com.cobblemoon.autoqiqi.common.SessionLogger.get().getLastSessionRecap();
                    if (recap != null) {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        for (String line : recap) {
                            msg(mc, line);
                        }
                    }
                }, 3000);
            }

            handleKeybindings(client);

            // Capture: tick engagement + ball throw + miss detection + pickup
            if (CaptureEngine.get().isActive()) {
                CaptureEngine.get().tick(client);
                CaptureEngine.get().tickBallThrow(client);
                CaptureEngine.get().tickBallWait(client);

                boolean battleActive = com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null;
                boolean inBattlePhase = CaptureEngine.get().getPhase() == CaptureEngine.Phase.IN_BATTLE;

                // Reset stale battle state when a new capture starts (not yet in battle)
                if (wasInCaptureBattle && !inBattlePhase && !battleActive) {
                    wasInCaptureBattle = false;
                    battleNullTicks = 0;
                }

                if (battleActive) {
                    battleNullTicks = 0;
                    if (!wasInCaptureBattle && inBattlePhase) {
                        wasInCaptureBattle = true;
                        logDebug("Tick", "Capture battle confirmed (phase=IN_BATTLE, getBattle() != null)");
                    }
                } else if (wasInCaptureBattle) {
                    // Don't trigger battle end while picking up a dropped ball or waiting for hit
                    if (CaptureEngine.get().isPickingUpBall() || CaptureEngine.get().isWaitingForBallHit()) {
                        battleNullTicks = 0;
                    } else {
                        battleNullTicks++;
                        if (battleNullTicks >= BATTLE_END_DEBOUNCE_TICKS) {
                            wasInCaptureBattle = false;
                            battleNullTicks = 0;
                            logDebug("Tick", "Capture battle ENDED (getBattle() null for " + BATTLE_END_DEBOUNCE_TICKS + " ticks)");
                            CaptureEngine.get().onBattleEnded();
                        } else if (battleNullTicks == 1) {
                            logDebug("Tick", "getBattle() went null, debouncing... (phase=" + CaptureEngine.get().getPhase() + ")");
                        }
                    }
                }
            }

            // Battle (skip AutoBattleEngine while capture is running)
            BattleMode bm = AutoBattleEngine.get().getMode();
            if (bm != BattleMode.OFF && !CaptureEngine.get().isActive()) {
                AutoBattleEngine.get().tick();
            }

            // Legendary: auto-resume from pause after capture finishes.
            // Guards: minimum pause duration + CaptureEngine must have been active and finished.
            if (AutoSwitchEngine.get().isPaused()) {
                if (legendaryPauseStartMs == 0) {
                    legendaryPauseStartMs = System.currentTimeMillis();
                    captureSeenActiveDuringPause = false;
                }
                if (CaptureEngine.get().isActive()) {
                    captureSeenActiveDuringPause = true;
                } else if (captureSeenActiveDuringPause) {
                    long elapsed = System.currentTimeMillis() - legendaryPauseStartMs;
                    if (elapsed >= MIN_LEGENDARY_PAUSE_MS) {
                        captureSeenActiveDuringPause = false;
                        legendaryPauseStartMs = 0;
                        logDebug("Legendary", "Capture finished after " + (elapsed / 1000) + "s, auto-resuming legendary switching");
                        AutoSwitchEngine.get().resumeFromPause();
                        if (client.player != null) {
                            client.player.sendMessage(
                                    Text.literal("§6[Auto-Qiqi]§r §aCapture terminee, reprise du legendary."), false);
                        }
                    }
                }
            } else {
                captureSeenActiveDuringPause = false;
                legendaryPauseStartMs = 0;
            }
            AutoSwitchEngine.get().tick();
            com.cobblemoon.autoqiqi.legendary.autohop.AutoHopEngine.get().tick();
            PokemonWalker.get().tick();
            TowerNpcEngine.get().tick();

            // Hunt timer
            if (huntActive && System.currentTimeMillis() >= huntEndTimeMs) {
                stopHunt(client, "duree ecoulee");
            }

            // Gold mining (lowest priority – only in Nether when idle)
            GoldMiningEngine.get().tick();

            // Periodic Pokedex scan
            tickPeriodicScan(client);

            // Tick rate calibration
            tickTickrateMeasurement();
        });

        HudRenderCallback.EVENT.register((context, renderTickCounter) -> {
            AutoQiqiHud.render(context, renderTickCounter.getTickDelta(true));
        });

        log("Init", "Auto-Qiqi v" + BuildConstants.VERSION + " initialized!");
        log("Init", "Keybinds: K=battle, H=leg HUD, J=leg auto, U=force poll, L=leg mod, I=tower start");
    }

    // ========================
    // Keybindings
    // ========================

    private void registerKeybindings() {
        toggleBattleKey = reg("key.autoqiqi.toggle_battle", GLFW.GLFW_KEY_K);
        toggleHudKey = reg("key.autoqiqi.toggle_hud", GLFW.GLFW_KEY_H);
        toggleLegendaryKey = reg("key.autoqiqi.toggle_legendary", GLFW.GLFW_KEY_J);
        forcePollKey = reg("key.autoqiqi.force_poll", GLFW.GLFW_KEY_U);
        toggleModKey = reg("key.autoqiqi.toggle_mod", GLFW.GLFW_KEY_L);
        towerStartKey = reg("key.autoqiqi.tower_start", GLFW.GLFW_KEY_I);
        stopAllKey = reg("key.autoqiqi.stop_all", GLFW.GLFW_KEY_O);
        toggleAutoHopKey = reg("key.autoqiqi.toggle_autohop", GLFW.GLFW_KEY_P);
    }

    private static KeyBinding reg(String translationKey, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translationKey, InputUtil.Type.KEYSYM, key, "category.autoqiqi"));
    }

    private void handleKeybindings(MinecraftClient client) {
        if (client.player == null) return;
        // Stop-all works even when a screen is open (world switch GUI, battle, etc.) so automation is always cancelable
        if (stopAllKey.wasPressed()) {
            executeStop();
            return;
        }
        if (client.currentScreen != null) return;

        if (toggleBattleKey.wasPressed()) {
            client.setScreen(new AutoQiqiConfigScreen());
        }

        if (toggleHudKey.wasPressed()) {
            AutoQiqiConfig config = AutoQiqiConfig.get();
            config.legendaryHudVisible = !config.legendaryHudVisible;
            AutoQiqiConfig.save();
            msg(client, "Legendary HUD: " + (config.legendaryHudVisible ? "ON" : "OFF"));
        }

        if (toggleLegendaryKey.wasPressed()) {
            invokeNextlegOneMinuteAction(client);
        }

        if (forcePollKey.wasPressed()) {
            if (AutoSwitchEngine.get().isPaused()) {
                AutoSwitchEngine.get().resumeFromPause();
                msg(client, "Reprise ! Capture terminee.");
            } else {
                AutoSwitchEngine.get().forcePoll();
                msg(client, "Force polling...");
            }
        }

        if (toggleModKey.wasPressed()) {
            AutoQiqiConfig config = AutoQiqiConfig.get();
            config.legendaryEnabled = !config.legendaryEnabled;
            AutoQiqiConfig.save();
            msg(client, "Legendary: " + (config.legendaryEnabled ? "ENABLED" : "DISABLED"));
        }

        if (toggleAutoHopKey.wasPressed()) {
            var hop = com.cobblemoon.autoqiqi.legendary.autohop.AutoHopEngine.get();
            hop.setDisabled(!hop.isDisabled());
            msg(client, "§6[Auto-Hop]§r " + (hop.isDisabled() ? "§cDÉSACTIVÉ" : "§aACTIVÉ"));
        }

        if (towerStartKey.wasPressed()) {
            if (TowerNpcEngine.get().isLoopEnabled()) {
                TowerNpcEngine.get().stopLoop();
                AutoBattleEngine.get().setMode(BattleMode.fromString(AutoQiqiConfig.get().battleMode));
                msg(client, "§e[Tower]§r Tour arrêtée après le prochain combat.");
            } else if (TowerNpcEngine.get().tryStartTower()) {
                AutoBattleEngine.get().setMode(BattleMode.TRAINER);
                AutoQiqiConfig config = AutoQiqiConfig.get();
                if (config.legendaryAutoSwitch) {
                    config.legendaryAutoSwitch = false;
                    AutoQiqiConfig.save();
                    AutoSwitchEngine.get().cancelToIdle();
                    msg(client, "§7[Tower]§r Legendary hop désactivé (mutuellement exclusif).");
                }
                msg(client, "§a[Tower]§r Tour démarrée (auto-combat activé). Appuyez sur I pour arrêter.");
            } else {
                msg(client, "§c[Tower]§r Aucun NPC de tour trouvé (Directeur ou combat).");
            }
        }
    }

    /**
     * Runs when 1 min is left on the nextleg timer: same as pressing J (resume or toggle legendary),
     * and optionally opens the world menu (e.g. /monde) so the user can switch world.
     * Does nothing (no toggle, no /monde) while in a Cobblemon battle to avoid teleporting mid-fight.
     */
    public static void invokeNextlegOneMinuteAction(MinecraftClient client) {
        if (com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null
                || CaptureEngine.get().isActive()) {
            logDebug("Battle", "1-min action skipped (in battle) — no toggle, no /monde");
            return;
        }
        if (AutoSwitchEngine.get().isPaused()) {
            AutoSwitchEngine.get().resumeFromPause();
            msg(client, "Reprise ! Capture terminee.");
        } else {
            AutoQiqiConfig config = AutoQiqiConfig.get();
            config.legendaryAutoSwitch = !config.legendaryAutoSwitch;
            AutoQiqiConfig.save();
            msg(client, "Legendary Auto-switch: " + (config.legendaryAutoSwitch ? "ON" : "OFF"));
        }
        if (client.player != null && AutoQiqiConfig.get().roamingNextlegOpenMondeAt1Min) {
            String mondeCmd = AutoQiqiConfig.get().mondeCommand;
            String cmd = mondeCmd.startsWith("/") ? mondeCmd.substring(1) : mondeCmd;
            try {
                client.player.networkHandler.sendChatCommand(cmd);
                logDebug("Battle", "Roaming: sent " + mondeCmd + " (1 min left — open world menu)");
            } catch (Exception e) {
                logDebug("Battle", "Roaming: " + mondeCmd + " failed at 1min: " + e.getMessage());
            }
        }
    }

    /**
     * Entry point for auto-hop rotation, called via reflection from qiqi-timer.
     * Visits all auto_ homes, polls /nextleg, ranks by EV, and teleports to the best.
     */
    public static void invokeAutoHopRotation(MinecraftClient client) {
        com.cobblemoon.autoqiqi.legendary.autohop.AutoHopEngine.get().startRotation();
    }

    // ========================
    // Screen events (legendary GUI switching)
    // ========================

    private void registerScreenEvents() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // Tower dialog: also handle non-HandledScreen (EasyNPC dialog buttons)
            TowerGuiHandler.get().onAnyScreenOpened(screen);

            if (screen instanceof HandledScreen<?> handledScreen) {
                TowerGuiHandler.get().onChestScreenOpened(handledScreen);
                if (!TowerGuiHandler.get().isHandlingScreen()) {
                    GuiWorldSwitcher.get().onScreenOpened(handledScreen);
                }
            }

            ScreenEvents.afterTick(screen).register(s -> {
                TowerGuiHandler.get().onAnyScreenTick(s);
                if (s instanceof HandledScreen<?> hs) {
                    TowerGuiHandler.get().onChestScreenTick(hs);
                    if (!TowerGuiHandler.get().isHandlingScreen()) {
                        GuiWorldSwitcher.get().onScreenTick(hs);
                    }
                }
            });

            ScreenEvents.remove(screen).register(s -> {
                GuiWorldSwitcher.get().onScreenClosed();
                TowerGuiHandler.get().onScreenClosed();
            });
        });
    }

    // ========================
    // Commands (/pk scan, walk, stop, debug, tp)
    // ========================

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("pk")
                    .then(ClientCommandManager.literal("scan")
                            .executes(context -> { executeScan(); return 1; }))
                    .then(ClientCommandManager.literal("walk")
                            .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        executeWalk(IntegerArgumentType.getInteger(context, "index"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("guide")
                            .executes(context -> { executeGuideStop(); return 1; })
                            .then(ClientCommandManager.literal("stop")
                                    .executes(context -> { executeGuideStop(); return 1; }))
                            .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        executeGuide(IntegerArgumentType.getInteger(context, "index"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("stop")
                            .executes(context -> { executeStop(); return 1; }))
                    .then(ClientCommandManager.literal("debug")
                            .executes(context -> { executeDebugToggle(); return 1; })
                            .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        executeDebug(IntegerArgumentType.getInteger(context, "index"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("capture")
                            .executes(context -> { executeCaptureInBattle(); return 1; })
                            .then(ClientCommandManager.literal("stop")
                                    .executes(context -> { executeCaptureStop(); return 1; }))
                            .then(ClientCommandManager.argument("index", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        executeCapture(IntegerArgumentType.getInteger(context, "index"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("hunt")
                            .executes(context -> { executeHuntStatus(); return 1; })
                            .then(ClientCommandManager.literal("stop")
                                    .executes(context -> { executeHuntStop(); return 1; }))
                            .then(ClientCommandManager.argument("hours", DoubleArgumentType.doubleArg(0.1, 24))
                                    .executes(context -> {
                                        executeHuntStart(DoubleArgumentType.getDouble(context, "hours"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("tp")
                            .executes(context -> { executeTpShow(); return 1; })
                            .then(ClientCommandManager.literal("default")
                                    .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                builder.suggest("last");
                                                builder.suggest("random");
                                                return builder.buildFuture();
                                            })
                                            .executes(context -> {
                                                executeTpDefault(StringArgumentType.getString(context, "mode"));
                                                return 1;
                                            })))
                            .then(ClientCommandManager.argument("worldIndex", IntegerArgumentType.integer(1))
                                    .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                builder.suggest("last");
                                                builder.suggest("random");
                                                return builder.buildFuture();
                                            })
                                            .executes(context -> {
                                                executeTpWorld(
                                                        IntegerArgumentType.getInteger(context, "worldIndex"),
                                                        StringArgumentType.getString(context, "mode"));
                                                return 1;
                                            }))))
                    .then(ClientCommandManager.literal("reconnect")
                            .executes(context -> { executeReconnectToggle(); return 1; }))
                    .then(ClientCommandManager.literal("reload")
                            .executes(context -> { executeReload(); return 1; }))
                    .then(ClientCommandManager.literal("version")
                            .executes(context -> { executeVersion(); return 1; }))
                    .then(ClientCommandManager.literal("tickrate")
                            .executes(context -> { executeTickrate(); return 1; }))
                    .then(ClientCommandManager.literal("smogon")
                            .executes(context -> { executeSmogonInfo(); return 1; }))
            );
        });
    }

    private void tickPeriodicScan(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        periodicScanTicks++;
        if (periodicScanTicks < PERIODIC_SCAN_INTERVAL) return;
        periodicScanTicks = 0;

        List<Entity> results = PokemonScanner.get().scan();
        int uncaught = PokemonScanner.countUncaught(results);
        int total = results.size();

        int bosses = 0;
        StringBuilder bossNames = new StringBuilder();
        for (Entity e : results) {
            if (PokemonScanner.isBoss(e)) {
                bosses++;
                if (bossNames.length() > 0) bossNames.append(", ");
                bossNames.append(PokemonScanner.getPokemonName(e));
            }
        }

        if (bosses > 0) {
            client.player.sendMessage(
                    Text.literal("§6[Auto-Qiqi]§r §c§lBoss: " + bossNames + "§r §7nearby!"), false);
        }
        if (uncaught > 0) {
            client.player.sendMessage(
                    Text.literal("§6[Auto-Qiqi]§r §a" + uncaught + " uncaught§7/§f" + total + " wild nearby"),
                    true);
        }
    }

    private void executeScan() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        List<Entity> results = PokemonScanner.get().manualScan();
        if (results.isEmpty()) {
            msg(client, "§7Aucun Pokemon sauvage detecte a proximite.");
            return;
        }

        int uncaught = PokemonScanner.countUncaught(results);
        msg(client, "§a=== Pokemon sauvages (" + uncaught + " uncaught / " + results.size() + " total) ===");
        for (int i = 0; i < results.size(); i++) {
            Entity entity = results.get(i);
            String info = PokemonScanner.getDisplayInfo(entity);
            boolean caught = PokemonScanner.isSpeciesCaught(entity);
            String cTag = caught ? " §7(o)" : "";
            double dist = client.player.distanceTo(entity);
            msg(client, "§e" + (i + 1) + ". §f" + info + cTag + " §7- " + String.format("%.1f", dist) + " blocs");
        }
        msg(client, "§7Utilise §f/pk walk <n>§7, §f/pk guide <n>§7 ou §f/pk capture <n>§7.");
    }

    private void executeGuide(int index) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        PokemonScanner scanner = PokemonScanner.get();
        if (scanner.getLastScanSize() == 0) {
            msg(client, "§cFais d'abord §f/pk scan§c !");
            return;
        }

        Entity target = scanner.getFromLastScan(index);
        if (target == null) {
            msg(client, "§cIndex invalide. Refais §f/pk scan§c.");
            return;
        }

        double dist = client.player.distanceTo(target);
        String name = PokemonScanner.getDisplayInfo(target);
        DirectionGuide.get().setTarget(target);
        msg(client, "§bGuide direction vers §e" + name + "§b (" + String.format("%.1f", dist) + " blocs) — marche toi-meme, /pk guide stop pour annuler.");
    }

    private void executeGuideStop() {
        DirectionGuide.get().stop();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            msg(client, "§7Guide direction annule.");
        }
    }

    private void executeWalk(int index) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        PokemonScanner scanner = PokemonScanner.get();
        if (scanner.getLastScanSize() == 0) {
            msg(client, "§cFais d'abord §f/pk scan§c !");
            return;
        }

        Entity target = scanner.getFromLastScan(index);
        if (target == null) {
            msg(client, "§cIndex invalide. Refais §f/pk scan§c.");
            return;
        }

        double dist = client.player.distanceTo(target);
        String name = PokemonScanner.getDisplayInfo(target);
        msg(client, "§bMarche vers §e" + name + "§b (" + String.format("%.1f", dist) + " blocs)");
        PokemonWalker.get().startWalking(target);
    }

    private void executeDebugToggle() {
        debugMode = !debugMode;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            msg(client, "Debug mode: " + (debugMode ? "§aON" : "§cOFF") + "§r (verbose logging)");
        }
    }

    private void executeDebug(int index) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        PokemonScanner scanner = PokemonScanner.get();
        if (scanner.getLastScanSize() == 0) {
            msg(client, "§cFais d'abord §f/pk scan§c !");
            return;
        }
        Entity target = scanner.getFromLastScan(index);
        if (target == null) {
            msg(client, "§cIndex invalide. Refais §f/pk scan§c.");
            return;
        }
        msg(client, "§7Dump de §e" + PokemonScanner.getDisplayInfo(target) + "§7 dans les logs...");
        PokemonScanner.debugDumpEntity(target);
    }

    /**
     * Called when the player sends a command starting with /warp.
     * Turns off all automatic features (capture, walk, guide, battle, mining, legendary, hunt).
     */
    public static void stopAllAutomaticFeaturesForWarp() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (instance != null) {
            instance.wasInCaptureBattle = false;
            instance.battleNullTicks = 0;
        }

        CaptureEngine.get().stop();
        PokemonWalker.get().stop();
        DirectionGuide.get().stop();
        AutoBattleEngine.get().setMode(BattleMode.OFF);
        GoldMiningEngine.get().reset();

        AutoSwitchEngine.get().cancelToIdle();
        AutoQiqiConfig config = AutoQiqiConfig.get();
        config.legendaryAutoSwitch = false;
        AutoQiqiConfig.save();
        if (client.currentScreen != null) {
            client.setScreen(null);
        }

        if (isHuntActive()) {
            instance.stopHunt(client, "Warp utilisé");
        } else {
            msg(client, "§7/warp détecté — toutes les automatiques arrêtées.");
        }
        log("Battle", "Warp command — all automatics stopped");
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logEvent("MANUAL",
                "Warp command — all automatics stopped");
    }

    private void executeStop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        wasInCaptureBattle = false;
        battleNullTicks = 0;

        // If hunt is active, stop it first (it will clear legendary and other engines)
        if (huntActive) {
            stopHunt(client, "arrete par /pk stop");
            return;
        }

        // Only close screen when we were in menu/teleport flow (not when paused for capture — that screen is the battle GUI)
        boolean wasInLegendaryMenuFlow = false;
        var run = AutoSwitchEngine.get().getCurrentRun();
        if (run != null && run.state != LegendaryRunState.State.IDLE && run.state != LegendaryRunState.State.PAUSED_FOR_CAPTURE) {
            wasInLegendaryMenuFlow = true;
        }

        boolean stopped = false;
        if (CaptureEngine.get().isActive()) {
            CaptureEngine.get().stop();
            msg(client, "§7Capture arretee.");
            stopped = true;
        }
        if (PokemonWalker.get().isActive()) {
            PokemonWalker.get().stop();
            msg(client, "§7Marche arretee.");
            stopped = true;
        }
        if (DirectionGuide.get().isActive()) {
            DirectionGuide.get().stop();
            msg(client, "§7Guide direction annule.");
            stopped = true;
        }
        if (AutoBattleEngine.get().getMode() != BattleMode.OFF) {
            AutoBattleEngine.get().setMode(BattleMode.OFF);
            msg(client, "§7Auto-battle OFF.");
            stopped = true;
        }
        if (GoldMiningEngine.get().isActive()) {
            int mined = GoldMiningEngine.get().getSessionOresMined();
            GoldMiningEngine.get().reset();
            msg(client, "§7Mining arrete." + (mined > 0 ? " §6" + mined + " ores mined." : ""));
            stopped = true;
        }
        // Legendary: cancel world-switch flow and disable auto-switch so it stays cancelable in any situation
        AutoSwitchEngine.get().cancelToIdle();
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (config.legendaryAutoSwitch) {
            config.legendaryAutoSwitch = false;
            AutoQiqiConfig.save();
            msg(client, "§7Legendary auto-switch OFF.");
            stopped = true;
        }
        if (wasInLegendaryMenuFlow) {
            stopped = true;
            if (client.currentScreen != null) {
                client.setScreen(null);
            }
        }
        if (stopped) {
            log("Battle", "User executed /pk stop — all engines stopped");
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logEvent("MANUAL",
                    "User /pk stop — all engines stopped");
        } else {
            msg(client, "§7Rien a arreter.");
        }
    }

    // ========================
    // Hunt: combined legendary + roaming for X hours
    // ========================

    private void executeHuntStart(double hours) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        AutoQiqiConfig config = AutoQiqiConfig.get();

        config.legendaryEnabled = true;
        config.legendaryAutoSwitch = true;
        AutoQiqiConfig.save();

        AutoBattleEngine.get().setMode(BattleMode.ROAMING);

        huntEndTimeMs = System.currentTimeMillis() + (long) (hours * 3600_000);
        huntActive = true;

        long totalMin = (long) (hours * 60);
        String timeStr = totalMin >= 60
                ? String.format("%dh%02dm", totalMin / 60, totalMin % 60)
                : totalMin + "m";

        msg(client, "§a§l=== HUNT STARTED ===");
        msg(client, "§7Duration: §f" + timeStr);
        msg(client, "§7Legendary auto-switch: §aON");
        msg(client, "§7Battle mode: §aROAMING");
        msg(client, "§7Stop with: §f/pk hunt stop");
        log("Hunt", "Started for " + hours + " hours (ends at " + huntEndTimeMs + ")");
    }

    private void executeHuntStop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (!huntActive) {
            msg(client, "§7Pas de chasse en cours.");
            return;
        }
        stopHunt(client, "arretee manuellement");
    }

    private void executeHuntStatus() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (!huntActive) {
            msg(client, "§7Pas de chasse en cours. §f/pk hunt <heures>§7 pour lancer.");
            return;
        }
        long remainingMs = huntEndTimeMs - System.currentTimeMillis();
        if (remainingMs <= 0) {
            msg(client, "§7Chasse terminee (en cours d'arret...)");
            return;
        }
        long remainMin = remainingMs / 60_000;
        long remainSec = (remainingMs % 60_000) / 1000;
        String remaining = remainMin >= 60
                ? String.format("%dh%02dm%02ds", remainMin / 60, remainMin % 60, remainSec)
                : String.format("%dm%02ds", remainMin, remainSec);

        java.util.List<String> summary = AutoBattleEngine.get().getSessionSummaryLines();
        msg(client, "§a=== HUNT STATUS ===");
        msg(client, "§7Temps restant: §f" + remaining);
        if (summary != null) {
            for (String line : summary) {
                msg(client, line);
            }
        }
    }

    private void stopHunt(MinecraftClient client, String reason) {
        huntActive = false;
        huntEndTimeMs = 0;

        log("Battle", "Hunt stopped: " + reason);
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logEvent("MANUAL",
                "Hunt stopped: " + reason);

        boolean wasInLegendaryFlow = AutoSwitchEngine.get().getCurrentRun() != null
                && AutoSwitchEngine.get().getCurrentRun().state != LegendaryRunState.State.IDLE;
        AutoSwitchEngine.get().cancelToIdle();
        AutoQiqiConfig config = AutoQiqiConfig.get();
        config.legendaryAutoSwitch = false;
        AutoQiqiConfig.save();
        if (wasInLegendaryFlow && client.currentScreen != null) {
            client.setScreen(null);
        }

        AutoBattleEngine engine = AutoBattleEngine.get();
        java.util.List<String> summary = engine.getSessionSummaryAndReset();
        engine.setMode(BattleMode.OFF);

        CaptureEngine.get().stop();
        PokemonWalker.get().stop();
        GoldMiningEngine.get().reset();

        msg(client, "§e§l=== HUNT TERMINEE ===");
        msg(client, "§7Raison: §f" + reason);
        if (summary != null) {
            for (String line : summary) {
                msg(client, line);
            }
        }
        log("Hunt", "Stopped: " + reason);
    }

    public static boolean isHuntActive() { return huntActive; }
    public static long getHuntRemainingMs() { return huntActive ? Math.max(0, huntEndTimeMs - System.currentTimeMillis()) : 0; }

    // ========================
    // Blocked state tracking
    // ========================

    private void trackBlockedState(MinecraftClient client) {
        long now = System.currentTimeMillis();
        boolean connected = client.player != null && client.world != null;

        if (wasConnected && !connected) {
            String screenName = client.currentScreen != null
                    ? client.currentScreen.getClass().getSimpleName() : "none";
            log("Idle", "DISCONNECTED from server (screen=" + screenName + ")");
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logEvent("DISCONNECT",
                    "Disconnected (screen=" + screenName + ")");
            if (CaptureEngine.get().isActive()) {
                String name = CaptureEngine.get().getTargetName();
                if (name != null) {
                    com.cobblemoon.autoqiqi.common.SessionLogger.get().logCaptureFailed(
                            name, CaptureEngine.get().getTargetLevel(), CaptureEngine.get().isTargetLegendary(),
                            CaptureEngine.get().getTotalBallsThrown(), "disconnected");
                }
                CaptureEngine.get().stop(false);
            }
        } else if (!wasConnected && connected) {
            if (blockedSinceMs > 0) {
                long blockedSec = (now - blockedSinceMs) / 1000;
                log("Idle", "RECONNECTED after " + formatDuration(blockedSec) + " offline");
                com.cobblemoon.autoqiqi.common.SessionLogger.get().logEvent("RECONNECT",
                        "Reconnected after " + formatDuration(blockedSec) + " offline");
            }
            blockedSinceMs = 0;
            blockedReason = null;
            lastBlockedLogMs = 0;
        }
        wasConnected = connected;

        String reason = null;
        if (!connected) {
            reason = "disconnected";
        } else if (client.currentScreen != null) {
            String screenName = client.currentScreen.getClass().getSimpleName();
            if (!screenName.toLowerCase().contains("battle")) {
                reason = "screen:" + screenName;
            }
        }

        if (reason != null) {
            if (blockedSinceMs == 0) {
                blockedSinceMs = now;
                blockedReason = reason;
                lastBlockedLogMs = 0;
            }
            long blockedMs = now - blockedSinceMs;
            if (blockedMs >= 60_000 && (lastBlockedLogMs == 0 || now - lastBlockedLogMs >= BLOCKED_LOG_INTERVAL_MS)) {
                logDebug("Idle", "Engines blocked for " + formatDuration(blockedMs / 1000)
                        + " (reason=" + blockedReason + ")");
                com.cobblemoon.autoqiqi.common.SessionLogger.get().logEvent("IDLE",
                        "Blocked for " + formatDuration(blockedMs / 1000)
                                + " (reason=" + blockedReason + ")");
                lastBlockedLogMs = now;
            }
        } else {
            if (blockedSinceMs > 0 && blockedReason != null && blockedReason.startsWith("screen:")) {
                long blockedSec = (now - blockedSinceMs) / 1000;
                if (blockedSec >= 10) {
                    logDebug("Idle", "Screen closed after " + formatDuration(blockedSec)
                            + " (was " + blockedReason + ")");
                }
            }
            blockedSinceMs = 0;
            blockedReason = null;
            lastBlockedLogMs = 0;
        }
    }

    private static String formatDuration(long totalSeconds) {
        if (totalSeconds < 60) return totalSeconds + "s";
        long min = totalSeconds / 60;
        long sec = totalSeconds % 60;
        if (min < 60) return String.format("%dm%02ds", min, sec);
        long hours = min / 60;
        min = min % 60;
        return String.format("%dh%02dm%02ds", hours, min, sec);
    }

    private void executeTpShow() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        AutoQiqiConfig config = AutoQiqiConfig.get();
        msg(client, "§a=== Teleport Modes ===");
        msg(client, "§7Default GUI mode: §f" + config.defaultTeleportMode);

        List<String> worlds = config.worldNames;
        for (int i = 0; i < worlds.size(); i++) {
            String world = worlds.get(i);
            if (config.isHomeWorld(world)) {
                String homeCmd = config.getHomeCommand(world);
                msg(client, "§e" + (i + 1) + ". §f" + world + " §7-> §b/home " + homeCmd);
            } else {
                String mode = config.getTeleportMode(world);
                boolean isOverride = config.worldTeleportModes.containsKey(world.toLowerCase());
                String suffix = isOverride ? " §e(override)" : " §8(default)";
                String arrivalHome = config.getArrivalHome(world);
                String arrivalSuffix = arrivalHome != null ? " §7+ §b/home " + arrivalHome : "";
                msg(client, "§e" + (i + 1) + ". §f" + world + " §7-> §f" + mode + suffix + arrivalSuffix);
            }
        }
        msg(client, "§7Usage: §f/pk tp default <last|random>");
        msg(client, "§7        §f/pk tp <n> <last|random> §8(GUI worlds only)");
    }

    private void executeTpDefault(String mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (!"last".equalsIgnoreCase(mode) && !"random".equalsIgnoreCase(mode)) {
            msg(client, "§cMode invalide. Utilise §flast§c ou §frandom§c.");
            return;
        }
        AutoQiqiConfig.get().setDefaultTeleportMode(mode.toLowerCase());
        msg(client, "§aDefault teleport mode: §f" + mode.toLowerCase());
    }

    private void executeTpWorld(int worldIndex, String mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (!"last".equalsIgnoreCase(mode) && !"random".equalsIgnoreCase(mode)) {
            msg(client, "§cMode invalide. Utilise §flast§c ou §frandom§c.");
            return;
        }
        AutoQiqiConfig config = AutoQiqiConfig.get();
        List<String> worlds = config.worldNames;
        if (worldIndex < 1 || worldIndex > worlds.size()) {
            msg(client, "§cIndex invalide (1-" + worlds.size() + "). Voir §f/pk tp§c.");
            return;
        }
        String worldName = worlds.get(worldIndex - 1);
        if (config.isHomeWorld(worldName)) {
            msg(client, "§c" + worldName + " utilise /home, pas de mode GUI a configurer.");
            return;
        }
        config.setTeleportMode(worldName, mode.toLowerCase());
        msg(client, "§a" + worldName + " §7-> §f" + mode.toLowerCase());
    }

    // ========================
    // Reconnect command
    // ========================

    private void executeReconnectToggle() {
        MinecraftClient client = MinecraftClient.getInstance();
        AutoQiqiConfig config = AutoQiqiConfig.get();
        AutoReconnectEngine engine = AutoReconnectEngine.get();

        config.autoReconnectEnabled = !config.autoReconnectEnabled;
        AutoQiqiConfig.save();

        if (config.autoReconnectEnabled) {
            engine.enable();
            if (client.player != null) {
                msg(client, "§a[Reconnect]§r ON — auto-reconnect on disconnect");
            }
        } else {
            engine.disable();
            if (client.player != null) {
                msg(client, "§7[Reconnect]§r OFF");
            }
        }
    }

    // ========================
    // Reload command
    // ========================

    private void executeReload() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        AutoQiqiConfig.load();
        int wl = AutoQiqiConfig.get().scanCaptureWhitelist.size();
        msg(client, "§aConfig rechargee. §7Whitelist: §f" + wl + " §7pokemon(s).");
    }

    // ========================
    // Version command
    // ========================

    private void executeVersion() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        msg(client, "§fAuto-Qiqi §av" + BuildConstants.VERSION);
    }

    private void executeSmogonInfo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        int count = com.cobblemoon.autoqiqi.battle.SmogonData.size();
        msg(client, "§dSmogon OU §7| §f" + count + " §7Pokemon loaded (Gen 9 OU, all gens included)");
    }

    private void executeTickrate() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        if (tickrateStartMs > 0) {
            msg(client, "§eMesure déjà en cours...");
            return;
        }

        tickrateStartMs = System.currentTimeMillis();
        tickrateStartTime = client.world.getTimeOfDay();
        msg(client, "§eMesure du tick rate en cours... §7(30 secondes)");
    }

    private void tickTickrateMeasurement() {
        if (tickrateStartMs <= 0) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        long elapsed = System.currentTimeMillis() - tickrateStartMs;
        if (elapsed < TICKRATE_MEASURE_MS) return;

        long endTime = client.world.getTimeOfDay();
        tickrateStartMs = 0;

        long timeDelta = endTime - tickrateStartTime;
        // Handle day wrap-around
        if (timeDelta < 0) timeDelta += 24000;

        double realSeconds = elapsed / 1000.0;
        double ticksPerSecond = timeDelta / realSeconds;
        int rounded = (int) Math.round(ticksPerSecond);

        AutoQiqiConfig config = AutoQiqiConfig.get();
        msg(client, "§a[Tick Rate] §fRésultat: §e" + String.format("%.1f", ticksPerSecond)
                + " ticks/s §7(mesuré sur " + String.format("%.1f", realSeconds) + "s, delta=" + timeDelta + " ticks)");
        msg(client, "§7Config actuelle: §fdayTickRate=" + config.dayTickRate
                + (rounded != config.dayTickRate
                ? " §c⚠ Décalage détecté ! Suggéré: §f" + rounded
                : " §a✓ Correct"));
    }

    // ========================
    // Capture commands
    // ========================

    private void executeCapture(int index) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        PokemonScanner scanner = PokemonScanner.get();
        if (scanner.getLastScanSize() == 0) {
            msg(client, "§cFais d'abord §f/pk scan§c !");
            return;
        }

        Entity target = scanner.getFromLastScan(index);
        if (target == null) {
            msg(client, "§cIndex invalide. Refais §f/pk scan§c.");
            return;
        }

        String name = PokemonScanner.getPokemonName(target);
        int level = PokemonScanner.getPokemonLevel(target);
        boolean legendary = PokemonScanner.isLegendary(target);

        logDebug("Capture", "executeCapture index=" + index
                + " name=" + name + " level=" + level
                + " legendary=" + legendary + " entity=" + target.getClass().getSimpleName()
                + " alive=" + target.isAlive());

        if (CaptureEngine.isRecentlyFailed(name)) {
            msg(client, "§c" + name + " a echappe recemment. Attente " + com.cobblemoon.autoqiqi.config.AutoQiqiConfig.get().failedCaptureCooldownSeconds + "s.");
            return;
        }
        CaptureEngine.get().start(name, level, legendary, target);
        wasInCaptureBattle = false;
        battleNullTicks = 0;

        double dist = client.player.distanceTo(target);
        msg(client, "§bCapture: §e" + name + " Lv." + level
                + (legendary ? " §d[LEG]" : "")
                + "§b (" + String.format("%.1f", dist) + " blocs)");
        PokemonWalker.get().startWalking(target);
    }

    private void executeCaptureInBattle() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        CaptureEngine.get().start("Unknown", 50, false, null);
        wasInCaptureBattle = true;
        battleNullTicks = 0;
        msg(client, "§bCapture mode ON pour le combat en cours.");
    }

    private void executeCaptureStop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        CaptureEngine.get().stop();
        wasInCaptureBattle = false;
        battleNullTicks = 0;
        msg(client, "§7Capture mode OFF.");
    }

    /**
     * Returns true when capture-roaming is allowed during legendary mode.
     * Capture targets are only pursued when we're camping in the world with the
     * imminent timer (waiting for a legendary spawn). When we're in a different
     * world, we must switch first — no capture until we're in the right place.
     * If legendary mode is not active, always returns true.
     * Bosses and whitelisted kills are always allowed regardless of this check.
     */
    public static boolean canRoamCapture() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (!config.legendaryEnabled || !config.legendaryAutoSwitch) return true;

        WorldTracker tracker = WorldTracker.get();
        if (!tracker.allTimersKnown()) return false;

        String currentWorld = tracker.getCurrentWorld();
        if (currentWorld == null) return false;

        // We must NOT need to switch — we're already in the right world
        String worldToSwitch = tracker.getWorldToSwitchTo();
        if (worldToSwitch != null) {
            logDebug("Capture", "canRoamCapture=false: need to switch to " + worldToSwitch + " (current=" + currentWorld + ")");
            return false;
        }

        WorldTimerData data = tracker.getTimer(currentWorld);
        if (data == null || !data.isTimerKnown()) return false;

        // Only allow capture when we're in the imminent world: either event active
        // or timer within switch threshold (camping, waiting for legendary).
        boolean allowed = data.isEventActive() || tracker.currentWorldHasImminentEvent();
        if (!allowed) {
            logDebug("Capture", "canRoamCapture=false: not in imminent world (current=" + currentWorld
                    + " remaining=" + data.getEstimatedRemainingSeconds() + "s)");
        }
        return allowed;
    }

    // ========================
    // Public accessors for HUD and mixins
    // ========================

    public static BattleMode getBattleMode() { return AutoBattleEngine.get().getMode(); }

    /**
     * True when the client is in-game and has a network handler (can send commands).
     * Use before sending commands to avoid errors during/after network outages.
     */
    public static boolean isConnected(MinecraftClient client) {
        return client != null
                && client.getNetworkHandler() != null
                && client.player != null
                && client.world != null;
    }

    /** True when battle actions should be automated. True for capture, tower (tour de combat) battles, or when the mod started the battle (simulated send-out); manually engaged wild battles are not auto-fought. */
    public static boolean shouldAutoFight() {
        if (CaptureEngine.get().isActive()) return true;
        // Tour de combat (tower) mode: auto-fight trainer battles when tower loop is active
        if (AutoBattleEngine.get().getMode() == BattleMode.TRAINER && TowerNpcEngine.get().isLoopEnabled()) {
            return true;
        }
        // Berserk always auto-fights (no attribution needed — the mod fights everything)
        BattleMode mode = AutoBattleEngine.get().getMode();
        if (mode == BattleMode.BERSERK) {
            return true;
        }
        if (mode != BattleMode.OFF) {
            if (currentBattleStartedByMod == null) {
                currentBattleStartedByMod = (clientTickCounter - lastModEngagementTick) <= MOD_ENGAGEMENT_WINDOW_TICKS;
                logDebug("Battle", "Battle attribution: " + (currentBattleStartedByMod ? "mod (auto-fight)" : "manual (no auto-fight)"));
            }
            if (!currentBattleStartedByMod) return false;
            return true;
        }
        AutofishEngine fish = AutofishEngine.get();
        return fish != null && fish.isFishBattleActive();
    }

    /** Call right before simulating the send-out key so we attribute the ensuing battle to the mod. */
    public static void recordModEngagement() {
        lastModEngagementTick = clientTickCounter;
    }

    /** Current client tick count (for debouncing move selection so tick and mixin runLater don't both submit). */
    public static long getClientTickCounter() {
        return clientTickCounter;
    }

    /** True if the screen is the Minecraft pause / Game Menu (Yarn: GameMenuScreen). */
    private static boolean isPauseOrGameMenuScreen(Screen screen) {
        if (screen == null) return false;
        String name = screen.getClass().getSimpleName();
        return "GameMenuScreen".equals(name) || name.contains("GameMenu") || name.contains("Pause");
    }

    /** True if the screen has at least one clickable button (e.g. EasyNPC dialog, trainer pre-battle). */
    private static boolean hasClickableDialogButton(Screen screen) {
        return getFirstDialogButton(screen) != null;
    }

    /** First ButtonWidget on the screen, or null. Used to advance trainer/NPC dialogs. */
    private static ButtonWidget getFirstDialogButton(Screen screen) {
        if (screen == null) return null;
        for (Element child : screen.children()) {
            if (child instanceof ButtonWidget bw) return bw;
        }
        return null;
    }

    /**
     * If the given screen is a battle GUI, returns its current action selection (any type).
     * Uses reflection so we work with Cobblemon and Extended Battle UI.
     */
    private static Object getBattleCurrentActionSelection(Screen screen) {
        if (screen == null) return null;
        try {
            java.lang.reflect.Method m = screen.getClass().getMethod("getCurrentActionSelection");
            return m.invoke(screen);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * If the given screen is a battle GUI (e.g. BattleGUI or Extended Battle UI), returns its current action
     * selection when that selection is a move-selection screen; otherwise returns null.
     * Uses reflection so we work with Cobblemon and Extended Battle UI without requiring the exact class.
     */
    private static Object getBattleCurrentMoveSelection(Screen screen) {
        if (screen == null) return null;
        try {
            java.lang.reflect.Method m = screen.getClass().getMethod("getCurrentActionSelection");
            Object current = m.invoke(screen);
            if (current != null && current.getClass().getSimpleName().toLowerCase().contains("moveselection")) {
                return current;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** True when some form of autofight is on (for unfocused: we close the menu so battle can continue). */
    private static boolean isAutofightRelevantForUnfocused() {
        if (CaptureEngine.get().isActive()) return true;
        if (AutoBattleEngine.get().getMode() == BattleMode.TRAINER && TowerNpcEngine.get().isLoopEnabled()) return true;
        if (AutoBattleEngine.get().getMode() != BattleMode.OFF) return true;
        AutofishEngine fish = AutofishEngine.get();
        return fish != null && fish.isFishBattleActive();
    }

    /**
     * Execute a runnable on the main client thread after a delay.
     * Used by battle mixins for delayed action selection.
     */
    public static void runLater(Runnable r, long delayMs) {
        new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            MinecraftClient.getInstance().execute(r);
        }).start();
    }

    /** Sends message to in-game chat only (no file or stdout logging). Always shown. */
    public static void log(String prefix, String message) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.player != null) {
            c.player.sendMessage(Text.literal("§6[Auto-Qiqi/" + prefix + "]§r " + message), false);
        }
    }

    /** Debug-level log: only shown when /pk debug is ON. */
    public static void logDebug(String prefix, String message) {
        if (!debugMode) return;
        log(prefix, message);
    }

    public static boolean isDebugMode() { return debugMode; }

    private static void msg(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r " + message), false);
        }
    }
}
