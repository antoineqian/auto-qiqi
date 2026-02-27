package com.cobblemoon.autoqiqi;

import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.BattleMode;
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
import com.cobblemoon.autoqiqi.walk.CircleWalkEngine;

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
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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
    private static KeyBinding toggleWalkKey;
    private static KeyBinding toggleHudKey;
    private static KeyBinding toggleLegendaryKey;
    private static KeyBinding forcePollKey;
    private static KeyBinding toggleModKey;
    private static KeyBinding towerStartKey;

    private static final int PERIODIC_SCAN_INTERVAL = 600; // 30 seconds

    // Feature state
    private static boolean walkEnabled = false;
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
    private static final int MOD_ENGAGEMENT_WINDOW_TICKS = 100; // ~5 seconds to attribute battle to mod

    @Override
    public void onInitializeClient() {
        log("Init", "Initializing Auto-Qiqi...");
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logInfo("Auto-Qiqi initialized (new session)");

        AutoQiqiConfig.load();
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
            boolean nowInBattleScreen = client.currentScreen != null
                    && client.currentScreen.getClass().getSimpleName().toLowerCase().contains("battle");
            if (wasInBattleScreen && !nowInBattleScreen) {
                currentBattleStartedByMod = null;
            }
            wasInBattleScreen = nowInBattleScreen;
            AutoReconnectEngine.get().tick(client);

            if (!firstTickDone && client.player != null) {
                firstTickDone = true;
                // Ensure everything starts disabled: no walking, hopping, or capture
                walkEnabled = false;
                PokemonWalker.get().stop();
                CaptureEngine.get().stop();
                MovementHelper.releaseMovementKeys(client);
                log("Init", "First tick: all engines disabled, movement keys released");

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
                        log("Tick", "Capture battle confirmed (phase=IN_BATTLE, getBattle() != null)");
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
                            log("Tick", "Capture battle ENDED (getBattle() null for " + BATTLE_END_DEBOUNCE_TICKS + " ticks)");
                            CaptureEngine.get().onBattleEnded();
                        } else if (battleNullTicks == 1) {
                            log("Tick", "getBattle() went null, debouncing... (phase=" + CaptureEngine.get().getPhase() + ")");
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
                        log("Legendary", "Capture finished after " + (elapsed / 1000) + "s, auto-resuming legendary switching");
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
            PokemonWalker.get().tick();
            TowerNpcEngine.get().tick();

            // Walk
            if (walkEnabled) {
                if (client.player == null || client.currentScreen != null) {
                    disableWalk(client);
                } else {
                    CircleWalkEngine.get().tick(client);
                }
            }

            // Hunt timer
            if (huntActive && System.currentTimeMillis() >= huntEndTimeMs) {
                stopHunt(client, "duree ecoulee");
            }

            // Gold mining (lowest priority – only in Nether when idle)
            GoldMiningEngine.get().tick();

            // Periodic Pokedex scan
            tickPeriodicScan(client);
        });

        HudRenderCallback.EVENT.register((context, renderTickCounter) -> {
            AutoQiqiHud.render(context, renderTickCounter.getTickDelta(true));
        });

        log("Init", "Auto-Qiqi initialized!");
        log("Init", "Keybinds: K=battle, G=walk, H=leg HUD, J=leg auto, U=force poll, L=leg mod, I=tower start");
    }

    // ========================
    // Keybindings
    // ========================

    private void registerKeybindings() {
        toggleBattleKey = reg("key.autoqiqi.toggle_battle", GLFW.GLFW_KEY_K);
        toggleWalkKey = reg("key.autoqiqi.toggle_walk", GLFW.GLFW_KEY_G);
        toggleHudKey = reg("key.autoqiqi.toggle_hud", GLFW.GLFW_KEY_H);
        toggleLegendaryKey = reg("key.autoqiqi.toggle_legendary", GLFW.GLFW_KEY_J);
        forcePollKey = reg("key.autoqiqi.force_poll", GLFW.GLFW_KEY_U);
        toggleModKey = reg("key.autoqiqi.toggle_mod", GLFW.GLFW_KEY_L);
        towerStartKey = reg("key.autoqiqi.tower_start", GLFW.GLFW_KEY_I);
    }

    private static KeyBinding reg(String translationKey, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                translationKey, InputUtil.Type.KEYSYM, key, "category.autoqiqi"));
    }

    private void handleKeybindings(MinecraftClient client) {
        if (client.player == null || client.currentScreen != null) return;

        if (toggleBattleKey.wasPressed()) {
            client.setScreen(new AutoQiqiConfigScreen());
        }

        if (toggleWalkKey.wasPressed()) {
            walkEnabled = !walkEnabled;
            if (walkEnabled) {
                CircleWalkEngine.get().start(client.player.getX(), client.player.getZ());
                msg(client, "§a[Walk]§r ON – marche en cercle");
            } else {
                disableWalk(client);
                msg(client, "§7[Walk]§r OFF");
            }
        }

        if (toggleHudKey.wasPressed()) {
            AutoQiqiConfig config = AutoQiqiConfig.get();
            config.legendaryHudVisible = !config.legendaryHudVisible;
            AutoQiqiConfig.save();
            msg(client, "Legendary HUD: " + (config.legendaryHudVisible ? "ON" : "OFF"));
        }

        if (toggleLegendaryKey.wasPressed()) {
            if (AutoSwitchEngine.get().isPaused()) {
                AutoSwitchEngine.get().resumeFromPause();
                msg(client, "Reprise ! Capture terminee.");
            } else {
                AutoQiqiConfig config = AutoQiqiConfig.get();
                config.legendaryAutoSwitch = !config.legendaryAutoSwitch;
                AutoQiqiConfig.save();
                msg(client, "Legendary Auto-switch: " + (config.legendaryAutoSwitch ? "ON" : "OFF"));
            }
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

        if (towerStartKey.wasPressed()) {
            if (TowerNpcEngine.get().isLoopEnabled()) {
                TowerNpcEngine.get().stopLoop();
                msg(client, "§e[Tower]§r Tour arrêtée après le prochain combat.");
            } else if (TowerNpcEngine.get().tryStartTower()) {
                AutoQiqiConfig config = AutoQiqiConfig.get();
                if (config.legendaryAutoSwitch) {
                    config.legendaryAutoSwitch = false;
                    AutoQiqiConfig.save();
                    AutoSwitchEngine.get().cancelToIdle();
                    msg(client, "§7[Tower]§r Legendary hop désactivé (mutuellement exclusif).");
                }
                msg(client, "§a[Tower]§r Tour démarrée. Appuyez sur I pour arrêter.");
            } else {
                msg(client, "§c[Tower]§r Aucun NPC de tour trouvé (Directeur ou combat).");
            }
        }
    }

    private void disableWalk(MinecraftClient client) {
        walkEnabled = false;
        MovementHelper.releaseMovementKeys(client);
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
                    .then(ClientCommandManager.literal("stop")
                            .executes(context -> { executeStop(); return 1; }))
                    .then(ClientCommandManager.literal("debug")
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

        log("Scan", "Periodic: " + total + " wild nearby, " + uncaught + " uncaught, " + bosses + " boss(es).");

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
        msg(client, "§7Utilise §f/pk walk <n>§7 ou §f/pk capture <n>§7.");
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

    private void executeStop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        wasInCaptureBattle = false;
        battleNullTicks = 0;

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

        AutoQiqiConfig config = AutoQiqiConfig.get();
        config.legendaryAutoSwitch = false;
        AutoQiqiConfig.save();

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
                log("Idle", "Engines blocked for " + formatDuration(blockedMs / 1000)
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
                    log("Idle", "Screen closed after " + formatDuration(blockedSec)
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

        log("Capture", "executeCapture index=" + index
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
            log("Capture", "canRoamCapture=false: need to switch to " + worldToSwitch + " (current=" + currentWorld + ")");
            return false;
        }

        WorldTimerData data = tracker.getTimer(currentWorld);
        if (data == null || !data.isTimerKnown()) return false;

        // Only allow capture when we're in the imminent world: either event active
        // or timer within switch threshold (camping, waiting for legendary).
        boolean allowed = data.isEventActive() || tracker.currentWorldHasImminentEvent();
        if (!allowed) {
            log("Capture", "canRoamCapture=false: not in imminent world (current=" + currentWorld
                    + " remaining=" + data.getEstimatedRemainingSeconds() + "s)");
        }
        return allowed;
    }

    // ========================
    // Public accessors for HUD and mixins
    // ========================

    public static BattleMode getBattleMode() { return AutoBattleEngine.get().getMode(); }
    public static boolean isWalkEnabled() { return walkEnabled; }

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

    /** True when battle actions should be automated. Only true if the mod started the battle (simulated send-out), so manually engaged battles are not auto-fought. */
    public static boolean shouldAutoFight() {
        if (CaptureEngine.get().isActive()) return true;
        if (AutoBattleEngine.get().getMode() != BattleMode.OFF) {
            if (currentBattleStartedByMod == null) {
                currentBattleStartedByMod = (clientTickCounter - lastModEngagementTick) <= MOD_ENGAGEMENT_WINDOW_TICKS;
                log("Battle", "Battle attribution: " + (currentBattleStartedByMod ? "mod (auto-fight)" : "manual (no auto-fight)"));
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

    public static void log(String prefix, String message) {
        System.out.println("[Auto-Qiqi/" + prefix + "] " + message);
    }

    private static void msg(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§6[Auto-Qiqi]§r " + message), false);
        }
    }
}
