package com.cobblemoon.autoqiqi.battle;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection.SwitchTile;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.legendary.PokemonWalker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capture strategy engine for Cobblemon wild battles.
 *
 * In Cobblemon, wild battles have: Fight, Switch, Capture, Run.
 * "Capture" minimises the battle and the player throws a ball from hotbar.
 * There is no in-battle bag/item usage.
 *
 * Strategy:
 * 1. False Swipe (Marowak) N times (level-dependent: 1 below Lv30, +1 per 10 levels)
 * 2. Thunder Wave (Dragonite) if level >= 50 — before first ball, re-apply every 8 balls
 * 3. Cycle: throw 1 ball -> 1 False Swipe -> 1 ball -> ... (ball type per sequence)
 * 4. Switch to tank if Marowak/Dragonite HP < 50%
 * 5. If fainted: switch to tank (no revives in Cobblemon wild battles)
 */
public class CaptureEngine {
    private static final CaptureEngine INSTANCE = new CaptureEngine();

    public enum CaptureAction {
        FALSE_SWIPE, THUNDER_WAVE,
        SWITCH_MAROWAK, SWITCH_DRAGONITE, SWITCH_TANK,
        THROW_BALL
    }

    public enum GeneralChoice { FIGHT, SWITCH, CAPTURE }

    public enum Phase { IDLE, WALKING, ENGAGING, IN_BATTLE }

    private boolean active = false;
    private Phase phase = Phase.IDLE;
    private String targetName;
    private int targetLevel;
    private boolean targetIsLegendary;
    private Entity targetEntity;

    // Walking timeout (abort if stuck for too long)
    private int walkTicks = 0;
    private int walkRetries = 0;
    private static final int MAX_WALK_TICKS = 20 * 30; // 30 seconds at 20 tps
    private static final int MAX_WALK_RETRIES = 3;

    // Total capture timeout: abort if entire capture takes too long
    private long captureStartMs = 0;
    private static final long CAPTURE_TOTAL_TIMEOUT_MS = 60_000; // 60 seconds

    // Engagement (aim + send-out key)
    private int aimTicks = 0;
    private boolean keySent = false;
    private int engageAttempts = 0;
    private InputUtil.Key pendingKeyRelease = null;
    private KeyBinding cachedSendOutKey = null;
    private boolean keybindSearchDone = false;
    private static final int AIM_TICKS_BEFORE_PRESS = 10;
    private static final int AIM_TICKS_AFTER_PRESS = 5;
    private static final int MAX_ENGAGE_ATTEMPTS = 15;
    private static final int CROSSHAIR_BYPASS_AFTER = 6;
    private double currentEngageRange = 6.0;
    private static final double ENGAGE_RANGE_INITIAL = 6.0;
    private static final double ENGAGE_RANGE_MIN = 3.5;
    private static final double ENGAGE_RANGE_TOO_CLOSE = 3.0;

    // Line-of-sight strafing: move sideways when an obstacle blocks the target
    private int losStrafeTicks = 0;
    private int losStrafeDir = 1;
    private static final int LOS_STRAFE_SWITCH_TICKS = 30;
    private static final int LOS_MAX_STRAFE_TICKS = 120; // 6 seconds max strafing
    private int entityObstructionStrafeTicks = 0;
    private static final int ENTITY_OBSTRUCTION_MAX_STRAFE = 80; // 4 seconds max
    private static final float AIM_YAW_SPEED = 20.0f;
    private static final float AIM_PITCH_SPEED = 14.0f;

    private boolean targetAtOneHp = false;
    private boolean falseSwipeUsedThisBattle = false;
    private int falseSwipeCount = 0;
    private float lastOppHpBeforeFalseSwipe = -1;
    private boolean thunderWaveApplied = false;
    private int ballsSinceLastTWave = 0;
    private boolean cycleNextIsBall = true;
    private CaptureAction currentAction = null;
    private int decisionCount = 0;

    // Ball tracking
    private int ballSequenceIndex = 0;
    private int currentBallCount = 0;
    private int greatBallsThrown = 0;
    private int ultraBallsThrown = 0;
    private int totalBallsThrown = 0;

    // Ball throw state (multi-tick: back up -> aim -> select slot -> throw -> wait for hit)
    private boolean pendingBallThrow = false;
    private String pendingBallName = null;
    private int throwTicksRemaining = 0;
    private static final int THROW_DELAY_TICKS = 10;
    private static final int AIM_BEFORE_THROW_TICKS = 8;
    private static final double MIN_THROW_DISTANCE = 4.5;
    private static final int MAX_BACKUP_TICKS = 40;
    private int throwAimTicks = 0;
    private boolean throwSlotSelected = false;
    private boolean backingUp = false;
    private int backupTicks = 0;

    // Miss detection: after throwing, wait for mixin to fire (battle resumes)
    private boolean waitingForBallHit = false;
    private int throwWaitTicks = 0;
    private int missCount = 0;
    private static final int MISS_TIMEOUT_TICKS = 240; // 12 seconds — ball shake animation can take 8-12s
    private static final int MAX_MISSES = 3;

    // Cooldown after a ball hit is confirmed (wait for shake animation to finish)
    private boolean ballHitJustConfirmed = false;
    private static final int BALL_HIT_EXTRA_DELAY = 100; // 5 seconds for shake animation

    // Hard cooldown after any throw to prevent overlapping throws
    private long lastThrowTimeMs = 0;
    private static final long THROW_COOLDOWN_MS = 12_000; // 12s — must cover full shake animation

    // Deferred retry: set when retryThrow is rejected by cooldown, checked in tickBallThrow
    private boolean retryThrowPending = false;

    // Pickup dropped ball after miss
    private boolean pickingUpBall = false;
    private Entity droppedBallEntity = null;
    private int pickupTicks = 0;
    private static final int MAX_PICKUP_TICKS = 20 * 10; // 10 seconds

    private String statusMessage = "";

    /** Pokemon names that recently failed capture -> timestamp (ms). Prevents immediate retry after escape. */
    private static final Map<String, Long> recentlyFailedCaptures = new ConcurrentHashMap<>();

    private static final float LOW_HP_THRESHOLD = 50f;

    private static final BallEntry[] LOW_LEVEL_BALLS = {
            new BallEntry("premier_ball", 3),
            new BallEntry("slate_ball", 3),
            new BallEntry("roseate_ball", 3),
            new BallEntry("verdant_ball", 3),
            new BallEntry("citrine_ball", 3),
            new BallEntry("poke_ball", 3),
            new BallEntry("great_ball", 6),
            new BallEntry("ultra_ball", Integer.MAX_VALUE),
    };

    private static final BallEntry[] HIGH_LEVEL_BALLS = {
            new BallEntry("great_ball", 6),
            new BallEntry("ultra_ball", Integer.MAX_VALUE),
    };

    private BallEntry[] activeBallSequence;

    private CaptureEngine() {}

    public static CaptureEngine get() { return INSTANCE; }

    /** Level-dependent minimum False Swipe count before considering 1HP confirmed. */
    private static int getMinFalseSwipes(int level) {
        if (level < 30) return 1;
        return Math.max(1, (level - 10) / 10);
    }

    // ========================
    // Lifecycle
    // ========================

    public void start(String name, int level, boolean isLegendary, Entity entity) {
        this.active = true;
        this.targetName = name;
        this.targetLevel = level;
        this.targetIsLegendary = isLegendary;
        if (isRecentlyFailed(name)) {
            AutoQiqiClient.log("Capture", "Skipping " + name + " - recently failed (cooldown " + AutoQiqiConfig.get().failedCaptureCooldownSeconds + "s)");
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§6[Capture]§r §c" + name + " a echappe recemment, attente " + AutoQiqiConfig.get().failedCaptureCooldownSeconds + "s avant de reessayer."), false);
            }
            return;
        }
        this.targetEntity = entity;
        this.activeBallSequence = level >= 50 ? HIGH_LEVEL_BALLS : LOW_LEVEL_BALLS;
        this.phase = (entity != null) ? Phase.WALKING : Phase.IN_BATTLE;
        this.captureStartMs = System.currentTimeMillis();
        resetState();
        statusMessage = "Capture: " + name + " Lv." + level;
        AutoQiqiClient.log("Capture", "Started for " + name + " Lv." + level
                + " (legendary=" + isLegendary + ", phase=" + phase + ")");
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logBattleStart(name, level, "capture");
    }

    public void stop() {
        if (active) {
            AutoQiqiClient.log("Capture", "Stopped");
            PokemonWalker.get().stop();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.options.forwardKey.setPressed(false);
                MovementHelper.stopStrafe(client);
            }
        }
        losStrafeTicks = 0;
        active = false;
        phase = Phase.IDLE;
        targetName = null;
        targetEntity = null;
        statusMessage = "";
        currentAction = null;
        targetAtOneHp = false;
        falseSwipeUsedThisBattle = false;
        falseSwipeCount = 0;
        lastOppHpBeforeFalseSwipe = -1;
        thunderWaveApplied = false;
        ballsSinceLastTWave = 0;
        cycleNextIsBall = true;
        decisionCount = 0;
        pendingBallThrow = false;
        waitingForBallHit = false;
        ballHitJustConfirmed = false;
        retryThrowPending = false;
        pickingUpBall = false;
        droppedBallEntity = null;
        if (pendingKeyRelease != null) {
            KeyBinding.setKeyPressed(pendingKeyRelease, false);
            pendingKeyRelease = null;
        }
    }

    private void resetState() {
        targetAtOneHp = false;
        falseSwipeUsedThisBattle = false;
        falseSwipeCount = 0;
        lastOppHpBeforeFalseSwipe = -1;
        thunderWaveApplied = false;
        ballsSinceLastTWave = 0;
        cycleNextIsBall = true;
        decisionCount = 0;
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
        currentEngageRange = ENGAGE_RANGE_INITIAL;
        lastThrowTimeMs = 0;
    }

    /** Records that a capture failed (Pokemon escaped). Blocks re-capture for failedCaptureCooldownSeconds. */
    public static void recordCaptureFailed(String pokemonName) {
        if (pokemonName == null || pokemonName.isBlank()) return;
        recentlyFailedCaptures.put(pokemonName.toLowerCase(), System.currentTimeMillis());
    }

    /** Returns true if we should not start capture for this Pokemon (recently failed). */
    public static boolean isRecentlyFailed(String pokemonName) {
        if (pokemonName == null || pokemonName.isBlank()) return false;
        Long failedAt = recentlyFailedCaptures.get(pokemonName.toLowerCase());
        if (failedAt == null) return false;
        int cooldownMs = AutoQiqiConfig.get().failedCaptureCooldownSeconds * 1000;
        if (System.currentTimeMillis() - failedAt >= cooldownMs) {
            recentlyFailedCaptures.remove(pokemonName.toLowerCase());
            return false;
        }
        return true;
    }

    public boolean isActive() { return active; }
    public Phase getPhase() { return phase; }
    public String getStatusMessage() { return statusMessage; }
    public CaptureAction getCurrentAction() { return currentAction; }
    public int getTargetLevel() { return targetLevel; }
    public boolean isTargetLegendary() { return targetIsLegendary; }
    public int getTotalBallsThrown() { return totalBallsThrown; }
    public String getTargetName() { return targetName; }

    /**
     * Called when the battle object goes null (debounced).
     * If a capture was confirmed by chat, this is expected. Otherwise, log it.
     */
    public void onBattleEnded() {
        if (!active) return;
        if (targetName != null) recordCaptureFailed(targetName);
        String msg = targetName + " - battle ended (Balls: " + totalBallsThrown + ")";
        statusMessage = msg;
        AutoQiqiClient.log("Capture", msg);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§6[Capture]§r §a" + msg), false);
            client.options.forwardKey.setPressed(false);
        }
        String reason = totalBallsThrown == 0 ? "no balls thrown" : "escaped after " + totalBallsThrown + " balls";
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logCaptureFailed(
                targetName, targetLevel, targetIsLegendary, totalBallsThrown, reason);
        active = false;
        phase = Phase.IDLE;
        targetAtOneHp = false;
        falseSwipeUsedThisBattle = false;
        falseSwipeCount = 0;
        lastOppHpBeforeFalseSwipe = -1;
        thunderWaveApplied = false;
        ballsSinceLastTWave = 0;
        cycleNextIsBall = true;
        decisionCount = 0;
        pendingBallThrow = false;
        waitingForBallHit = false;
        retryThrowPending = false;
        pickingUpBall = false;
        droppedBallEntity = null;
    }

    /**
     * Called by ChatMessageHandler when "a été ajouté a votre PC" is detected.
     * This is the reliable capture success signal.
     */
    public void onCaptureConfirmedByChat(String pokemonName) {
        if (!active) return;
        AutoQiqiClient.log("Capture", "CAPTURE CONFIRMED via chat: " + pokemonName);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§6[Capture]§r §a§l" + pokemonName + " capture ! §7(balls: " + totalBallsThrown + ")"), false);
        }
        active = false;
        phase = Phase.IDLE;
        targetAtOneHp = false;
        falseSwipeUsedThisBattle = false;
        falseSwipeCount = 0;
        lastOppHpBeforeFalseSwipe = -1;
        thunderWaveApplied = false;
        ballsSinceLastTWave = 0;
        cycleNextIsBall = true;
        decisionCount = 0;
        pendingBallThrow = false;
        waitingForBallHit = false;
        retryThrowPending = false;
        pickingUpBall = false;
        droppedBallEntity = null;
    }

    // ========================
    // Main tick (walking + engagement phases)
    // ========================

    public void tick(MinecraftClient client) {
        if (!active || client.player == null) return;

        // Total capture timeout (walking + engaging phases only)
        if (phase == Phase.WALKING || phase == Phase.ENGAGING) {
            long elapsed = System.currentTimeMillis() - captureStartMs;
            if (elapsed > CAPTURE_TOTAL_TIMEOUT_MS) {
                AutoQiqiClient.log("Capture", "TOTAL TIMEOUT after " + (elapsed / 1000) + "s");
                client.player.sendMessage(
                        Text.literal("§6[Capture]§r §cTimeout: impossible de capturer §e" + targetName + "§c. Arret."), false);
                stop();
                return;
            }
        }

        if (pendingKeyRelease != null) {
            KeyBinding.setKeyPressed(pendingKeyRelease, false);
            pendingKeyRelease = null;
        }

        switch (phase) {
            case WALKING -> tickWalking(client);
            case ENGAGING -> tickEngaging(client);
            case IN_BATTLE -> {
                if (pickingUpBall) tickPickupBall(client);
            }
            default -> {}
        }
    }

    private void tickWalking(MinecraftClient client) {
        if (targetEntity == null || !targetEntity.isAlive() || targetEntity.isRemoved()) {
            AutoQiqiClient.log("Capture", "Target gone during WALKING phase");
            client.player.sendMessage(Text.literal("§6[Capture]§r §cPokemon disparu !"), false);
            stop();
            return;
        }

        if (client.currentScreen != null) return;

        walkTicks++;
        if (walkTicks > MAX_WALK_TICKS) {
            walkRetries++;
            double dist = client.player.distanceTo(targetEntity);
            AutoQiqiClient.log("Capture", "Walk timeout (" + walkRetries + "/" + MAX_WALK_RETRIES
                    + ") dist=" + String.format("%.1f", dist)
                    + " player=" + fmtPos(client.player.getPos())
                    + " target=" + fmtPos(targetEntity.getPos()));
            if (walkRetries >= MAX_WALK_RETRIES) {
                client.player.sendMessage(Text.literal("§6[Capture]§r §cImpossible d'atteindre " + targetName + " (pathfinding echoue). /pk stop pour annuler."), false);
                stop();
                return;
            }
            walkTicks = 0;
            PokemonWalker.get().stop();
        }

        if (!PokemonWalker.get().isActive()) {
            if (PokemonWalker.get().hasTimedOut()) {
                AutoQiqiClient.log("Capture", "Walker timed out, aborting capture");
                client.player.sendMessage(Text.literal("§6[Capture]§r §cImpossible d'atteindre " + targetName + ". Arret."), false);
                stop();
                return;
            }
            double dist = client.player.distanceTo(targetEntity);
            if (dist <= currentEngageRange) {
                phase = Phase.ENGAGING;
                aimTicks = 0;
                keySent = false;
                statusMessage = "Engaging " + targetName + "...";
                AutoQiqiClient.log("Capture", "WALKING->ENGAGING dist=" + String.format("%.1f", dist));
            } else {
                AutoQiqiClient.log("Capture", "Walker stopped but still far (dist=" + String.format("%.1f", dist) + "), restarting walk");
                PokemonWalker.get().startWalking(targetEntity);
            }
        }
    }

    private void tickEngaging(MinecraftClient client) {
        if (targetEntity == null || !targetEntity.isAlive() || targetEntity.isRemoved()) {
            AutoQiqiClient.log("Capture", "Target gone during ENGAGING phase");
            client.player.sendMessage(Text.literal("§6[Capture]§r §cPokemon disparu !"), false);
            stop();
            return;
        }

        if (client.currentScreen != null) {
            String screenName = client.currentScreen.getClass().getSimpleName();
            AutoQiqiClient.log("Capture", "Screen opened during ENGAGING: " + screenName);
            if (screenName.toLowerCase().contains("battle")) {
                phase = Phase.IN_BATTLE;
                statusMessage = "In battle - " + targetName;
                AutoQiqiClient.log("Capture", "ENGAGING->IN_BATTLE (battle screen detected)");
                aimTicks = 0;
                keySent = false;
            }
            return;
        }

        // Also check if CobblemonClient reports a battle (screen may lag)
        if (com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getBattle() != null && phase != Phase.IN_BATTLE) {
            phase = Phase.IN_BATTLE;
            statusMessage = "In battle - " + targetName;
            AutoQiqiClient.log("Capture", "ENGAGING->IN_BATTLE (CobblemonClient.getBattle() != null)");
            aimTicks = 0;
            keySent = false;
            return;
        }

        double dist = client.player.distanceTo(targetEntity);
        if (dist > currentEngageRange) {
            MovementHelper.stopStrafe(client);
            losStrafeTicks = 0;
            AutoQiqiClient.log("Capture", "ENGAGING->WALKING target moved away (dist=" + String.format("%.1f", dist) + ")");
            phase = Phase.WALKING;
            PokemonWalker.get().startWalking(targetEntity);
            statusMessage = "Walking to " + targetName + "...";
            return;
        }

        if (dist < ENGAGE_RANGE_TOO_CLOSE) {
            MovementHelper.lookAtEntity(client.player, targetEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);
            client.options.backKey.setPressed(true);
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            statusMessage = "Too close, backing up...";
            aimTicks = 0;
            keySent = false;
            return;
        }
        client.options.backKey.setPressed(false);

        boolean hasLOS = MovementHelper.hasLineOfSight(client.player, targetEntity);

        if (!hasLOS) {
            losStrafeTicks++;
            if (losStrafeTicks == 1) {
                AutoQiqiClient.log("Capture", "No LOS to target, strafing to find clear angle");
            }
            if (losStrafeTicks % LOS_STRAFE_SWITCH_TICKS == 0) {
                losStrafeDir = -losStrafeDir;
            }
            if (losStrafeTicks > LOS_MAX_STRAFE_TICKS) {
                AutoQiqiClient.log("Capture", "LOS strafe timeout, trying to engage anyway");
                losStrafeTicks = 0;
            } else {
                MovementHelper.strafeSideways(client, targetEntity, client.player, losStrafeDir);
                statusMessage = "Finding clear angle on " + targetName + "...";
                aimTicks = 0;
                keySent = false;
                return;
            }
        } else if (losStrafeTicks > 0) {
            AutoQiqiClient.log("Capture", "LOS acquired after " + losStrafeTicks + " ticks of strafing");
            MovementHelper.stopStrafe(client);
            losStrafeTicks = 0;
        }

        // Snap aim directly at entity (no smoothing) for crosshair precision
        snapLookAtEntity(client.player, targetEntity);
        aimTicks++;

        // Check if crosshair is actually on the target entity
        boolean crosshairOnTarget = isCrosshairOnTarget(client, targetEntity);

        if (!keySent && aimTicks >= AIM_TICKS_BEFORE_PRESS) {
            boolean bypassCrosshair = engageAttempts >= CROSSHAIR_BYPASS_AFTER;
            if (!crosshairOnTarget && !bypassCrosshair) {
                if (aimTicks % 4 == 0) {
                    AutoQiqiClient.log("Capture", "Crosshair not on target, wiggling aim (dist=" + String.format("%.1f", dist) + ")");
                }
                if (aimTicks > AIM_TICKS_BEFORE_PRESS + 10) {
                    aimTicks = 0;
                    engageAttempts++;
                    if (engageAttempts >= CROSSHAIR_BYPASS_AFTER) {
                        AutoQiqiClient.log("Capture", "Crosshair bypass active after " + engageAttempts + " misses (small hitbox?), will force send-out key");
                    }
                    if (engageAttempts >= 4 && currentEngageRange > ENGAGE_RANGE_MIN) {
                        currentEngageRange = Math.max(ENGAGE_RANGE_MIN, currentEngageRange - 1.0);
                        AutoQiqiClient.log("Capture", "Reducing engage range to " + String.format("%.1f", currentEngageRange));
                    }
                }
                return;
            }

            engageAttempts++;
            if (engageAttempts > MAX_ENGAGE_ATTEMPTS) {
                AutoQiqiClient.log("Capture", "Engagement failed after " + MAX_ENGAGE_ATTEMPTS + " attempts (not wild?)");
                client.player.sendMessage(Text.literal("§6[Capture]§r §cImpossible d'engager " + targetName + " (pas sauvage ?). Arret."), false);
                stop();
                return;
            }
            String reason = crosshairOnTarget ? "crosshair hit" : "bypass (snap aim)";
            AutoQiqiClient.log("Capture", "Sending send-out key (" + reason + ", attempt=" + engageAttempts + "/" + MAX_ENGAGE_ATTEMPTS + " dist=" + String.format("%.1f", dist) + ")");
            simulateSendOutKey(client);
            keySent = true;
        }

        if (keySent && aimTicks >= AIM_TICKS_BEFORE_PRESS + AIM_TICKS_AFTER_PRESS) {
            aimTicks = 0;
            keySent = false;
        }
    }

    private void snapLookAtEntity(net.minecraft.client.network.ClientPlayerEntity player, Entity target) {
        Vec3d eye = player.getEyePos();
        double aimHeight = target.getHeight() * 0.75;
        Vec3d targetCenter = target.getPos().add(0, aimHeight, 0);
        double dx = targetCenter.x - eye.x;
        double dy = targetCenter.y - eye.y;
        double dz = targetCenter.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI)));
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    private boolean isCrosshairOnTarget(MinecraftClient client, Entity target) {
        if (client.crosshairTarget == null) return false;
        if (client.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            Entity hitEntity = ((EntityHitResult) client.crosshairTarget).getEntity();
            return hitEntity == target || hitEntity.getId() == target.getId();
        }
        return false;
    }

    private void simulateSendOutKey(MinecraftClient client) {
        KeyBinding sendOut = findSendOutKey(client);
        if (sendOut != null) {
            InputUtil.Key key = InputUtil.fromTranslationKey(sendOut.getBoundKeyTranslationKey());
            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);
            pendingKeyRelease = key;
            AutoQiqiClient.log("Capture", "Send-out key pressed");
        } else {
            AutoQiqiClient.log("Capture", "Could not find Cobblemon send-out keybinding");
        }
    }

    private KeyBinding findSendOutKey(MinecraftClient client) {
        if (keybindSearchDone) return cachedSendOutKey;

        for (KeyBinding kb : client.options.allKeys) {
            String translationKey = kb.getTranslationKey().toLowerCase();
            String category = kb.getCategory().toLowerCase();
            if (category.contains("cobblemon") || translationKey.contains("cobblemon")) {
                if (translationKey.contains("send") || translationKey.contains("throw")
                        || translationKey.contains("summon") || translationKey.contains("battle")
                        || translationKey.contains("challenge") || translationKey.contains("pokemon")) {
                    cachedSendOutKey = kb;
                }
            }
        }
        if (cachedSendOutKey == null) {
            for (KeyBinding kb : client.options.allKeys) {
                if (kb.getBoundKeyTranslationKey().equals("key.keyboard.r")) {
                    String cat = kb.getCategory().toLowerCase();
                    if (cat.contains("cobblemon") || kb.getTranslationKey().toLowerCase().contains("cobblemon")) {
                        cachedSendOutKey = kb;
                        break;
                    }
                }
            }
        }
        keybindSearchDone = true;
        return cachedSendOutKey;
    }

    // ========================
    // Ball throw tick (called from main tick loop)
    // ========================

    public void tickBallThrow(MinecraftClient client) {
        if (client.player == null) return;

        // Deferred retry: reattempt throw once cooldown expires
        if (retryThrowPending && !pendingBallThrow && !waitingForBallHit && !pickingUpBall) {
            long sinceLast = System.currentTimeMillis() - lastThrowTimeMs;
            if (sinceLast >= THROW_COOLDOWN_MS) {
                retryThrowPending = false;
                AutoQiqiClient.log("Capture", "Deferred retry: cooldown expired, retrying ball throw");
                prepareBallThrow();
            }
        }

        if (!pendingBallThrow) return;

        throwTicksRemaining--;
        if (throwTicksRemaining > 0) return;

        // Phase 0: back up if too close (ball overshoots big Pokemon at close range)
        if (targetEntity != null && targetEntity.isAlive() && !targetEntity.isRemoved()) {
            double dist = client.player.distanceTo(targetEntity);
            if (dist < MIN_THROW_DISTANCE && backupTicks < MAX_BACKUP_TICKS) {
                if (!backingUp) {
                    backingUp = true;
                    AutoQiqiClient.log("Capture", "Too close to throw (dist=" + String.format("%.1f", dist)
                            + "), backing up to " + MIN_THROW_DISTANCE);
                }
                MovementHelper.lookAtEntity(client.player, targetEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);
                client.options.backKey.setPressed(true);
                client.options.forwardKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
                backupTicks++;
                return;
            }
            if (backingUp) {
                client.options.backKey.setPressed(false);
                backingUp = false;
                AutoQiqiClient.log("Capture", "Backed up to dist=" + String.format("%.1f", dist)
                        + " after " + backupTicks + " ticks");
            }
        }

        // Phase 0.5: check for friendly entity blocking throw path (e.g. player's own Pokemon)
        if (targetEntity != null && targetEntity.isAlive() && !targetEntity.isRemoved()) {
            Entity blocker = MovementHelper.getEntityBlockingThrow(client.player, targetEntity);
            if (blocker != null && entityObstructionStrafeTicks < ENTITY_OBSTRUCTION_MAX_STRAFE) {
                if (entityObstructionStrafeTicks == 0) {
                    String blockerName = PokemonScanner.getPokemonName(blocker);
                    AutoQiqiClient.log("Capture", "Entity blocking throw path: " + blockerName
                            + " at dist=" + String.format("%.1f", client.player.distanceTo(blocker))
                            + ", strafing around it");
                }
                entityObstructionStrafeTicks++;
                if (entityObstructionStrafeTicks % 25 == 0) losStrafeDir = -losStrafeDir;
                MovementHelper.lookAtEntity(client.player, targetEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);
                MovementHelper.strafeSideways(client, targetEntity, client.player, losStrafeDir);
                throwAimTicks = 0;
                return;
            } else if (blocker == null && entityObstructionStrafeTicks > 0) {
                AutoQiqiClient.log("Capture", "Entity obstruction cleared after " + entityObstructionStrafeTicks + " ticks");
                MovementHelper.stopStrafe(client);
                entityObstructionStrafeTicks = 0;
            }
        }

        // Phase 1: aim at target for several ticks, strafing if blocked by terrain
        if (targetEntity != null && targetEntity.isAlive() && !targetEntity.isRemoved()) {
            MovementHelper.lookAtEntity(client.player, targetEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);

            boolean hasLOS = MovementHelper.hasLineOfSight(client.player, targetEntity);
            if (!hasLOS && throwAimTicks < AIM_BEFORE_THROW_TICKS + 60) {
                losStrafeTicks++;
                if (losStrafeTicks % LOS_STRAFE_SWITCH_TICKS == 0) losStrafeDir = -losStrafeDir;
                MovementHelper.strafeSideways(client, targetEntity, client.player, losStrafeDir);
                throwAimTicks++;
                return;
            } else if (hasLOS && losStrafeTicks > 0) {
                MovementHelper.stopStrafe(client);
                losStrafeTicks = 0;
            }
        }
        throwAimTicks++;
        if (throwAimTicks < AIM_BEFORE_THROW_TICKS) return;

        // Phase 2: select hotbar slot (once)
        if (!throwSlotSelected) {
            int slot = findBallInHotbar(client, pendingBallName);
            if (slot == -1) {
                AutoQiqiClient.log("Capture", "Ball '" + pendingBallName + "' not in hotbar (seqIdx=" + ballSequenceIndex
                        + " count=" + currentBallCount + " total=" + totalBallsThrown + "), searching any ball...");
                slot = findAnyBallInHotbar(client);
                if (slot == -1) {
                    AutoQiqiClient.log("Capture", "NO balls found in hotbar! Dumping hotbar:");
                    for (int i = 0; i < 9; i++) {
                        ItemStack stack = client.player.getInventory().getStack(i);
                        if (!stack.isEmpty()) {
                            Identifier id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
                            AutoQiqiClient.log("Capture", "  slot " + i + ": " + id + " x" + stack.getCount());
                        }
                    }
                    statusMessage = "No balls in hotbar!";
                    pendingBallThrow = false;
                    throwAimTicks = 0;
                    return;
                }
            }

            ItemStack ballStack = client.player.getInventory().getStack(slot);
            Identifier ballId = net.minecraft.registry.Registries.ITEM.getId(ballStack.getItem());
            AutoQiqiClient.log("Capture", "Selecting ball: slot=" + slot + " item=" + ballId + " x" + ballStack.getCount());

            client.player.getInventory().selectedSlot = slot;
            throwSlotSelected = true;
            return; // wait one more tick for slot selection to register
        }

        // Phase 3: throw
        AutoQiqiClient.log("Capture", "Throwing ball (aimed for " + throwAimTicks + " ticks)");
        if (client.interactionManager != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            AutoQiqiClient.log("Capture", "interactItem called (ball thrown)");
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logBallThrow(
                    targetName, pendingBallName != null ? pendingBallName : "unknown", totalBallsThrown);
        }

        pendingBallThrow = false;
        throwAimTicks = 0;
        throwSlotSelected = false;
        backingUp = false;
        backupTicks = 0;
        entityObstructionStrafeTicks = 0;
        lastThrowTimeMs = System.currentTimeMillis();

        // Start miss detection
        waitingForBallHit = true;
        throwWaitTicks = 0;
        AutoQiqiClient.log("Capture", "Waiting for ball hit (timeout=" + MISS_TIMEOUT_TICKS + " ticks)");
    }

    private int findBallInHotbar(MinecraftClient client, String ballName) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                Identifier itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
                if (itemId.getPath().equals(ballName)) {
                    return i;
                }
            }
        }
        // Log what we were looking for and what's actually in the hotbar
        AutoQiqiClient.log("Capture", "Ball search FAILED for '" + ballName + "'. Hotbar contents:");
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                Identifier id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
                AutoQiqiClient.log("Capture", "  slot " + i + ": " + id.getNamespace() + ":" + id.getPath() + " x" + stack.getCount());
            }
        }
        return -1;
    }

    private int findAnyBallInHotbar(MinecraftClient client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                Identifier itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
                if (itemId.getPath().contains("ball") && itemId.getNamespace().equals("cobblemon")) {
                    AutoQiqiClient.log("Capture", "Fallback ball found: slot " + i + " = " + itemId.getNamespace() + ":" + itemId.getPath());
                    return i;
                }
            }
        }
        return -1;
    }

    // ========================
    // Miss detection: tick while waiting for ball hit
    // ========================

    public void tickBallWait(MinecraftClient client) {
        if (!waitingForBallHit || client.player == null) return;

        throwWaitTicks++;
        if (throwWaitTicks >= MISS_TIMEOUT_TICKS) {
            missCount++;
            AutoQiqiClient.log("Capture", "Ball MISSED! (timeout " + MISS_TIMEOUT_TICKS
                    + " ticks, missCount=" + missCount + "/" + MAX_MISSES + ")");
            client.player.sendMessage(
                    Text.literal("§6[Capture]§r §eBall rate ! (" + missCount + "/" + MAX_MISSES + ")"), false);

            waitingForBallHit = false;
            throwWaitTicks = 0;

            if (missCount >= MAX_MISSES) {
                AutoQiqiClient.log("Capture", "Too many misses (" + MAX_MISSES + "), aborting capture");
                client.player.sendMessage(
                        Text.literal("§6[Capture]§r §cTrop de balls ratees, arret."), false);
                stop();
                return;
            }

            // Try to pick up the dropped ball
            startPickupBall(client);
        }
    }

    /**
     * Called by the mixin when the battle action selection reappears,
     * confirming the previous ball hit the target.
     */
    public void onBallHitConfirmed() {
        if (waitingForBallHit) {
            AutoQiqiClient.log("Capture", "Ball HIT confirmed (wait=" + throwWaitTicks + " ticks)");
            waitingForBallHit = false;
            throwWaitTicks = 0;
            missCount = 0;
            ballHitJustConfirmed = true;
        }
    }

    /**
     * Returns true (and clears the flag) if a ball hit was just confirmed.
     * Used by the mixin to add extra delay before the next action.
     */
    public boolean consumeBallHitConfirmed() {
        if (ballHitJustConfirmed) {
            ballHitJustConfirmed = false;
            return true;
        }
        return false;
    }

    public int getBallHitExtraDelay() {
        return BALL_HIT_EXTRA_DELAY;
    }

    public boolean isWaitingForBallHit() {
        return waitingForBallHit;
    }

    public boolean isPendingBallThrow() {
        return pendingBallThrow;
    }

    public boolean isPickingUpBall() {
        return pickingUpBall;
    }

    // ========================
    // Pickup dropped ball after miss
    // ========================

    private void startPickupBall(MinecraftClient client) {
        droppedBallEntity = findNearbyBallItem(client);
        if (droppedBallEntity == null) {
            AutoQiqiClient.log("Capture", "No dropped ball item found nearby, retrying throw directly");
            retryThrow();
            return;
        }

        double dist = client.player.distanceTo(droppedBallEntity);
        AutoQiqiClient.log("Capture", "Found dropped ball at " + fmtPos(droppedBallEntity.getPos())
                + " dist=" + String.format("%.1f", dist) + ", walking to pick up");
        client.player.sendMessage(
                Text.literal("§6[Capture]§r §7Recuperation de la ball..."), false);
        pickingUpBall = true;
        pickupTicks = 0;
    }

    public void tickPickupBall(MinecraftClient client) {
        if (!pickingUpBall || client.player == null) return;

        pickupTicks++;

        if (droppedBallEntity == null || !droppedBallEntity.isAlive() || droppedBallEntity.isRemoved()) {
            AutoQiqiClient.log("Capture", "Dropped ball entity gone (picked up or despawned) after " + pickupTicks + " ticks");
            client.options.forwardKey.setPressed(false);
            pickingUpBall = false;
            droppedBallEntity = null;
            retryThrow();
            return;
        }

        double dist = client.player.distanceTo(droppedBallEntity);
        if (dist <= 1.5) {
            AutoQiqiClient.log("Capture", "Close enough to ball (dist=" + String.format("%.1f", dist) + "), waiting for auto-pickup");
            // Player auto-picks up items within ~1.5 blocks; just wait for entity to disappear
            return;
        }

        if (pickupTicks > MAX_PICKUP_TICKS) {
            AutoQiqiClient.log("Capture", "Pickup timeout (" + MAX_PICKUP_TICKS + " ticks), giving up on this ball");
            client.options.forwardKey.setPressed(false);
            pickingUpBall = false;
            droppedBallEntity = null;
            PokemonWalker.get().stop();
            retryThrow();
            return;
        }

        // Walk toward the dropped ball
        if (!PokemonWalker.get().isActive() || pickupTicks % 40 == 0) {
            MovementHelper.lookAtEntity(client.player, droppedBallEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);
            // Simple walk: press forward toward the entity
            walkTowardEntity(client, droppedBallEntity);
        }
    }

    private void walkTowardEntity(MinecraftClient client, Entity entity) {
        double dist = client.player.distanceTo(entity);
        if (dist > 1.5) {
            MovementHelper.lookAtEntity(client.player, entity, 360f, 360f);
            client.options.forwardKey.setPressed(true);
        } else {
            client.options.forwardKey.setPressed(false);
        }
    }

    private Entity findNearbyBallItem(MinecraftClient client) {
        if (client.world == null) return null;
        Entity closest = null;
        double closestDist = 20.0;
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getStack();
                Identifier itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
                if (itemId.getNamespace().equals("cobblemon") && itemId.getPath().contains("ball")) {
                    double dist = client.player.distanceTo(entity);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = entity;
                    }
                }
            }
        }
        if (closest != null) {
            Identifier id = net.minecraft.registry.Registries.ITEM.getId(
                    ((ItemEntity) closest).getStack().getItem());
            AutoQiqiClient.log("Capture", "Nearest ball item: " + id + " dist=" + String.format("%.1f", closestDist));
        }
        return closest;
    }

    private void retryThrow() {
        AutoQiqiClient.log("Capture", "Retrying ball throw (missCount=" + missCount + ")");
        var battle = CobblemonClient.INSTANCE.getBattle();
        if (battle != null && battle.getMinimised()) {
            prepareBallThrow();
            if (!pendingBallThrow) {
                retryThrowPending = true;
                AutoQiqiClient.log("Capture", "Retry deferred (cooldown), will retry when cooldown expires");
            }
        } else {
            AutoQiqiClient.log("Capture", "Battle not minimized, waiting for mixin to fire");
        }
    }

    // ========================
    // Decision: called by BattleGeneralActionSelectionMixin
    // ========================

    public GeneralChoice decideGeneralAction(boolean forceSwitch) {
        decisionCount++;
        float activeHp = getActiveHpPercent();
        float oppHp = getOpponentHpPercent();
        String activeName = getActivePokemonName();
        String oppStatus = getOpponentStatus();

        int minFS = getMinFalseSwipes(targetLevel);
        // 1HP confirmation: require minimum False Swipe count OR delta-confirmed HP unchanged
        if (falseSwipeCount >= minFS && !targetAtOneHp) {
            AutoQiqiClient.log("Capture", "1HP CONFIRMED: " + falseSwipeCount + " False Swipes used (min=" + minFS + " for Lv." + targetLevel + ")");
            targetAtOneHp = true;
        } else if (falseSwipeUsedThisBattle && lastOppHpBeforeFalseSwipe >= 0 && oppHp >= 0 && falseSwipeCount >= 2) {
            float delta = Math.abs(lastOppHpBeforeFalseSwipe - oppHp);
            if (delta < 0.5f) {
                AutoQiqiClient.log("Capture", "1HP CONFIRMED (delta): HP unchanged after False Swipe #" + falseSwipeCount
                        + " (" + f(lastOppHpBeforeFalseSwipe) + "% -> " + f(oppHp) + "%)");
                targetAtOneHp = true;
            }
        }

        if (targetAtOneHp && oppHp > 10) {
            AutoQiqiClient.log("Capture", "Opponent healed! HP=" + f(oppHp) + "%, resetting targetAtOneHp");
            targetAtOneHp = false;
        }
        if (oppStatus != null && oppStatus.toLowerCase().contains("par") && !thunderWaveApplied) {
            thunderWaveApplied = true;
            ballsSinceLastTWave = 0;
        }

        boolean isMarowak = nameContains(activeName, "marowak");
        boolean isDragonite = nameContains(activeName, "dragonite");

        AutoQiqiClient.log("Capture", "Decide #" + decisionCount
                + ": target=" + targetName + " Lv." + targetLevel + (targetIsLegendary ? " [LEG]" : "")
                + " | active=" + activeName + " HP=" + f(activeHp) + "%"
                + " | opp=" + getOpponentPokemonName() + " HP=" + f(oppHp) + "% status=" + oppStatus
                + " | at1HP=" + targetAtOneHp + " twave=" + thunderWaveApplied
                + " | fsCount=" + falseSwipeCount + " minFS=" + minFS + " prevHp=" + f(lastOppHpBeforeFalseSwipe) + "%"
                + " | balls=" + totalBallsThrown + " ballsSinceTWave=" + ballsSinceLastTWave
                + " cycleNext=" + (cycleNextIsBall ? "BALL" : "FS")
                + " | forceSwitch=" + forceSwitch);

        if (forceSwitch) {
            currentAction = CaptureAction.SWITCH_TANK;
            statusMessage = "Forced switch -> tank";
            AutoQiqiClient.log("Capture", "Decision: SWITCH_TANK (forced)");
            return GeneralChoice.SWITCH;
        }

        boolean isKeyPokemon = isMarowak || isDragonite;
        if (isKeyPokemon && activeHp >= 0 && activeHp < LOW_HP_THRESHOLD) {
            currentAction = CaptureAction.SWITCH_TANK;
            statusMessage = activeName + " HP low, switching to tank";
            AutoQiqiClient.log("Capture", "Decision: SWITCH_TANK (" + activeName + " HP=" + f(activeHp) + "%)");
            return GeneralChoice.SWITCH;
        }

        if (!targetAtOneHp) {
            if (!isMarowak) {
                currentAction = CaptureAction.SWITCH_MAROWAK;
                statusMessage = "Switching to Marowak";
                AutoQiqiClient.log("Capture", "Decision: SWITCH_MAROWAK (need False Swipe, active=" + activeName + ")");
                return GeneralChoice.SWITCH;
            }
            lastOppHpBeforeFalseSwipe = oppHp;
            currentAction = CaptureAction.FALSE_SWIPE;
            statusMessage = "False Swipe (#" + (falseSwipeCount + 1) + ")";
            AutoQiqiClient.log("Capture", "Decision: FALSE_SWIPE #" + (falseSwipeCount + 1) + " (opp HP=" + f(oppHp) + "%, saving for delta check)");
            return GeneralChoice.FIGHT;
        }

        boolean needTWave = targetLevel >= 50
                && (!thunderWaveApplied || ballsSinceLastTWave >= 8);
        if (needTWave) {
            if (!isDragonite) {
                currentAction = CaptureAction.SWITCH_DRAGONITE;
                statusMessage = "Switching to Dragonite";
                AutoQiqiClient.log("Capture", "Decision: SWITCH_DRAGONITE (need Thunder Wave, ballsSinceTWave=" + ballsSinceLastTWave + ")");
                return GeneralChoice.SWITCH;
            }
            currentAction = CaptureAction.THUNDER_WAVE;
            statusMessage = "Thunder Wave (balls since last=" + ballsSinceLastTWave + ")";
            AutoQiqiClient.log("Capture", "Decision: THUNDER_WAVE (ballsSinceTWave=" + ballsSinceLastTWave + ")");
            thunderWaveApplied = true;
            ballsSinceLastTWave = 0;
            cycleNextIsBall = true;
            return GeneralChoice.FIGHT;
        }

        if (cycleNextIsBall) {
            currentAction = CaptureAction.THROW_BALL;
            cycleNextIsBall = false;
            ballsSinceLastTWave++;
            AutoQiqiClient.log("Capture", "Decision: THROW_BALL [cycle] (total=" + totalBallsThrown
                    + " ballsSinceTWave=" + ballsSinceLastTWave + " oppHp=" + f(oppHp) + "%)");
            return GeneralChoice.CAPTURE;
        }

        if (!isMarowak) {
            currentAction = CaptureAction.SWITCH_MAROWAK;
            statusMessage = "Switching to Marowak (cycle FS)";
            AutoQiqiClient.log("Capture", "Decision: SWITCH_MAROWAK (cycle False Swipe, active=" + activeName + ")");
            return GeneralChoice.SWITCH;
        }
        currentAction = CaptureAction.FALSE_SWIPE;
        cycleNextIsBall = true;
        statusMessage = "False Swipe (cycle #" + (falseSwipeCount + 1) + ")";
        AutoQiqiClient.log("Capture", "Decision: FALSE_SWIPE [cycle] #" + (falseSwipeCount + 1) + " (opp HP=" + f(oppHp) + "%)");
        return GeneralChoice.FIGHT;
    }

    // ========================
    // Capture: prepare ball throw
    // ========================

    /**
     * Called by the mixin after minimizing the battle.
     * Sets up the pending ball throw to execute on the next ticks.
     */
    public void prepareBallThrow() {
        if (pendingBallThrow) {
            AutoQiqiClient.log("Capture", "Ball throw already pending, skipping");
            return;
        }
        if (waitingForBallHit) {
            AutoQiqiClient.log("Capture", "Still waiting for ball hit, skipping");
            return;
        }
        long sinceLast = System.currentTimeMillis() - lastThrowTimeMs;
        if (lastThrowTimeMs > 0 && sinceLast < THROW_COOLDOWN_MS) {
            AutoQiqiClient.log("Capture", "Throw cooldown active (" + sinceLast + "ms/" + THROW_COOLDOWN_MS + "ms), skipping");
            return;
        }
        pendingBallName = getNextBallName();
        pendingBallThrow = true;
        throwTicksRemaining = THROW_DELAY_TICKS;
        AutoQiqiClient.log("Capture", "Ball throw prepared: " + pendingBallName);
    }

    private String getNextBallName() {
        if (targetIsLegendary && ultraBallsThrown >= 20) {
            statusMessage = "Master Ball!";
            totalBallsThrown++;
            return "master_ball";
        }

        if (ballSequenceIndex >= activeBallSequence.length) {
            statusMessage = "Ultra Ball #" + (ultraBallsThrown + 1);
            ultraBallsThrown++;
            totalBallsThrown++;
            return "ultra_ball";
        }

        BallEntry entry = activeBallSequence[ballSequenceIndex];
        if (currentBallCount >= entry.count) {
            ballSequenceIndex++;
            currentBallCount = 0;
            return getNextBallName();
        }

        statusMessage = formatBallName(entry.name) + " (" + (currentBallCount + 1) + "/" + entry.count + ")";
        AutoQiqiClient.log("Capture", "Throwing " + entry.name + " " + (currentBallCount + 1) + "/" + entry.count);

        currentBallCount++;
        totalBallsThrown++;
        if (entry.name.equals("great_ball")) greatBallsThrown++;
        if (entry.name.equals("ultra_ball")) ultraBallsThrown++;

        return entry.name;
    }

    // ========================
    // Move selection: called by BattleMoveSelectionMixin
    // ========================

    public MoveTile chooseMoveFromTiles(List<MoveTile> selectableTiles) {
        String target = (currentAction == CaptureAction.THUNDER_WAVE)
                ? "thunder wave" : "false swipe";
        for (MoveTile tile : selectableTiles) {
            String moveName = tile.getMove().getMove().toLowerCase();
            if (moveName.contains(target)) {
                if (target.equals("false swipe") && (moveName.contains("false swipe") || moveName.contains("chage"))) {
                    falseSwipeUsedThisBattle = true;
                    falseSwipeCount++;
                    AutoQiqiClient.log("Capture", "Selected False Swipe #" + falseSwipeCount + " (HP before=" + f(lastOppHpBeforeFalseSwipe) + "%)");
                }
                AutoQiqiClient.log("Capture", "Selected move: " + moveName);
                return tile;
            }
        }
        // Fallback: pick safest non-damaging move to avoid killing
        for (MoveTile tile : selectableTiles) {
            String moveName = tile.getMove().getMove().toLowerCase();
            if (moveName.contains("wave") || moveName.contains("swipe")
                    || moveName.contains("spore") || moveName.contains("sleep")
                    || moveName.contains("stun") || moveName.contains("paralyze")) {
                AutoQiqiClient.log("Capture", "Fallback safe move: " + moveName);
                return tile;
            }
        }
        AutoQiqiClient.log("Capture", "WARNING: target move '" + target + "' not found! Moves: "
                + selectableTiles.stream().map(t -> t.getMove().getMove()).toList()
                + " — aborting move to avoid killing");
        return null;
    }

    // ========================
    // Switch selection: called by BattleSwitchPokemonSelectionMixin
    // ========================

    public SwitchTile chooseSwitchFromTiles(List<SwitchTile> availableTiles) {
        if (currentAction == CaptureAction.SWITCH_MAROWAK) {
            return findTileBySpecies(availableTiles, "marowak");
        }
        if (currentAction == CaptureAction.SWITCH_DRAGONITE) {
            return findTileBySpecies(availableTiles, "dragonite");
        }
        for (SwitchTile tile : availableTiles) {
            String species = tileSpecies(tile);
            if (!species.contains("marowak") && !species.contains("dragonite")) {
                AutoQiqiClient.log("Capture", "Switching to tank: " + species);
                return tile;
            }
        }
        return availableTiles.isEmpty() ? null : availableTiles.get(0);
    }

    private SwitchTile findTileBySpecies(List<SwitchTile> tiles, String speciesFragment) {
        for (SwitchTile tile : tiles) {
            String species = tileSpecies(tile);
            if (species.contains(speciesFragment)) {
                AutoQiqiClient.log("Capture", "Switching to " + species);
                return tile;
            }
        }
        AutoQiqiClient.log("Capture", "'" + speciesFragment + "' not found! Available: "
                + tiles.stream().map(t -> tileSpecies(t)).toList());
        return tiles.isEmpty() ? null : tiles.get(0);
    }

    private static String tileSpecies(SwitchTile tile) {
        try {
            return tile.getPokemon().getSpecies().getName().toLowerCase();
        } catch (Exception e) {
            return tile.getPokemon().getDisplayName(false).getString().toLowerCase();
        }
    }

    // ========================
    // Battle state reading
    // ========================

    private float getActiveHpPercent() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return -1;
            var actors = battle.getSide1().getActors();
            if (actors.isEmpty()) return -1;
            var actives = actors.get(0).getActivePokemon();
            if (actives.isEmpty()) return -1;
            ClientBattlePokemon bp = actives.get(0).getBattlePokemon();
            if (bp == null) return -1;
            float hpVal = (float) bp.getHpValue();
            float maxHp = (float) bp.getMaxHp();
            AutoQiqiClient.log("Capture", "HP_DEBUG_ACTIVE: hpValue=" + hpVal + " maxHp=" + maxHp);
            return hpVal / maxHp * 100f;
        } catch (Exception e) { return -1; }
    }

    private float getOpponentHpPercent() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return -1;
            var actors = battle.getSide2().getActors();
            if (actors.isEmpty()) return -1;
            var actives = actors.get(0).getActivePokemon();
            if (actives.isEmpty()) return -1;
            ClientBattlePokemon bp = actives.get(0).getBattlePokemon();
            if (bp == null) return -1;
            float hpValue = (float) bp.getHpValue();
            float maxHp = (float) bp.getMaxHp();
            float ratioPercent = hpValue * 100f;
            float divPercent = (maxHp > 0) ? hpValue / maxHp * 100f : -1;
            AutoQiqiClient.log("Capture", "HP_DEBUG_OPP: hpValue=" + hpValue + " maxHp=" + maxHp
                    + " asRatio=" + String.format("%.1f", ratioPercent) + "%"
                    + " asDivided=" + String.format("%.1f", divPercent) + "%");
            // Use heuristic: if hpValue > 1, it's an integer HP → divide by maxHp
            // If hpValue <= 1.0, it's a 0-1 ratio → multiply by 100
            if (hpValue > 1.0f) {
                return divPercent;
            } else {
                return ratioPercent;
            }
        } catch (Exception e) { return -1; }
    }

    private String getActivePokemonName() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return null;
            var actors = battle.getSide1().getActors();
            if (actors.isEmpty()) return null;
            var actives = actors.get(0).getActivePokemon();
            if (actives.isEmpty()) return null;
            ClientBattlePokemon bp = actives.get(0).getBattlePokemon();
            if (bp == null) return null;
            return bp.getSpecies().getName();
        } catch (Exception e) { return null; }
    }

    private String getOpponentPokemonName() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return "?";
            var actors = battle.getSide2().getActors();
            if (actors.isEmpty()) return "?";
            var actives = actors.get(0).getActivePokemon();
            if (actives.isEmpty()) return "?";
            ClientBattlePokemon bp = actives.get(0).getBattlePokemon();
            if (bp == null) return "?";
            return bp.getSpecies().getName();
        } catch (Exception e) { return "?"; }
    }

    private String getOpponentStatus() {
        try {
            ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
            if (battle == null) return null;
            var actors = battle.getSide2().getActors();
            if (actors.isEmpty()) return null;
            var actives = actors.get(0).getActivePokemon();
            if (actives.isEmpty()) return null;
            ClientBattlePokemon bp = actives.get(0).getBattlePokemon();
            if (bp == null) return null;
            var status = bp.getStatus();
            return status != null ? status.toString() : null;
        } catch (Exception e) { return null; }
    }

    // ========================
    // Helpers
    // ========================

    private static boolean nameContains(String name, String fragment) {
        return name != null && name.toLowerCase().contains(fragment);
    }

    private static String f(float v) { return String.format("%.1f", v); }

    private static String fmtPos(net.minecraft.util.math.Vec3d v) {
        return String.format("(%.1f,%.1f,%.1f)", v.x, v.y, v.z);
    }

    private static String formatBallName(String id) {
        String s = id.replace("_", " ");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record BallEntry(String name, int count) {}
}
