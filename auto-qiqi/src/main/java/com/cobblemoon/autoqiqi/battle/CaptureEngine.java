package com.cobblemoon.autoqiqi.battle;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.client.battle.ClientBattle;
import com.cobblemon.mod.common.client.battle.ClientBattlePokemon;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleGeneralActionSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleMoveSelection.MoveTile;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection;
import com.cobblemon.mod.common.client.gui.battle.subscreen.BattleSwitchPokemonSelection.SwitchTile;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.common.SessionLogger;
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

    /** Sends a ball-hit/breakout monitoring line to chat for debugging. */
    public static void chatBall(String msg) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.player != null) {
            c.player.sendMessage(Text.literal("§6[Ball]§r §7" + msg), false);
        }
    }

    public enum GeneralChoice { FIGHT, SWITCH, CAPTURE }

    public enum Phase { IDLE, WALKING, ENGAGING, IN_BATTLE }

    /** Single active capture run; null when idle. */
    private CaptureSession session = null;

    // Keybind cache (shared across sessions)
    private KeyBinding cachedSendOutKey = null;
    private boolean keybindSearchDone = false;

    private static final int MAX_WALK_TICKS = 20 * 30; // 30 seconds at 20 tps
    private static final int MAX_WALK_RETRIES = 3;
    private static final long CAPTURE_TOTAL_TIMEOUT_MS = 60_000; // 60 seconds
    private static final int AIM_TICKS_BEFORE_PRESS = 10;
    private static final int AIM_TICKS_AFTER_PRESS = 5;
    private static final int MAX_ENGAGE_ATTEMPTS = 15;
    private static final int CROSSHAIR_BYPASS_AFTER = 6;
    static final double ENGAGE_RANGE_INITIAL = 6.0;
    private static final double ENGAGE_RANGE_MIN = 3.5;
    private static final double ENGAGE_RANGE_TOO_CLOSE = 3.0;
    private static final int LOS_STRAFE_SWITCH_TICKS = 30;
    private static final int LOS_MAX_STRAFE_TICKS = 120;
    private static final int ENTITY_OBSTRUCTION_MAX_STRAFE = 140;
    private static final int ENTITY_OBSTRUCTION_SWITCH_DIR_TICKS = 70;
    private static final float AIM_YAW_SPEED = 20.0f;
    private static final float AIM_PITCH_SPEED = 14.0f;
    private static final int MAX_SWITCH_ATTEMPTS = 3;
    private static final int THROW_DELAY_TICKS = 10;
    private static final int AIM_BEFORE_THROW_TICKS = 8;
    private static final double MIN_THROW_DISTANCE = 4.5;
    private static final int MAX_BACKUP_TICKS = 40;
    private static final int MISS_TIMEOUT_TICKS = 240;
    private static final long BALL_HIT_WALL_CLOCK_TIMEOUT_MS = 20_000;
    private static final int MAX_MISSES = 6;
    public static final int BALL_HIT_EXTRA_DELAY = 100;
    private static final long THROW_COOLDOWN_MS = 12_000;
    private static final long IN_BATTLE_IDLE_TIMEOUT_MS = 15_000;
    private static final int MAX_PICKUP_TICKS = 20 * 10;

    /** Pokemon names that recently failed capture -> timestamp (ms). Prevents immediate retry after escape. */
    private static final Map<String, Long> recentlyFailedCaptures = new ConcurrentHashMap<>();

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

    /** Legendaries (level 70+): ultra ball only, give up after this many throws. */
    private static final BallEntry[] LEGENDARY_BALLS = {
            new BallEntry("ultra_ball", Integer.MAX_VALUE),
    };
    private static final int LEGENDARY_LEVEL_THRESHOLD = 70;
    private static final int LEGENDARY_GIVE_UP_AFTER_THROWS = 20;

    private CaptureEngine() {}

    public static CaptureEngine get() { return INSTANCE; }

    // ========================
    // Lifecycle
    // ========================

    public CaptureSession getCurrentSession() { return session; }

    public void start(String name, int level, boolean isLegendary, Entity entity) {
        if (isRecentlyFailed(name)) {
            AutoQiqiClient.log("Capture", "Skipping " + name + " - recently failed (cooldown " + AutoQiqiConfig.get().failedCaptureCooldownSeconds + "s)");
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§6[Capture]§r §c" + name + " a echappe recemment, attente " + AutoQiqiConfig.get().failedCaptureCooldownSeconds + "s avant de reessayer."), false);
            }
            return;
        }
        CaptureSession s = new CaptureSession();
        s.targetName = name;
        s.targetLevel = level;
        s.targetIsLegendary = isLegendary;
        s.targetEntity = entity;
        s.activeBallSequence = level >= LEGENDARY_LEVEL_THRESHOLD ? LEGENDARY_BALLS : (level >= 50 ? HIGH_LEVEL_BALLS : LOW_LEVEL_BALLS);
        s.phase = (entity != null) ? Phase.WALKING : Phase.IN_BATTLE;
        s.captureStartMs = System.currentTimeMillis();
        s.resetBattleAndBallState();
        s.statusMessage = "Capture: " + name + " Lv." + level;
        this.session = s;
        AutoQiqiClient.log("Capture", "Started for " + name + " Lv." + level
                + " (legendary=" + isLegendary + ", phase=" + s.phase + ")");
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logBattleStart(name, level, "capture");
    }

    public void stop() {
        if (session != null) {
            AutoQiqiClient.log("Capture", "Stopped");
            PokemonWalker.get().stop();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.options.forwardKey.setPressed(false);
                MovementHelper.stopStrafe(client);
            }
            if (session.pendingKeyRelease != null) {
                KeyBinding.setKeyPressed(session.pendingKeyRelease, false);
                session.pendingKeyRelease = null;
            }
            session = null;
        }
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

    public boolean isActive() { return session != null; }
    public Phase getPhase() { return session != null ? session.phase : Phase.IDLE; }
    public String getStatusMessage() { return session != null ? session.statusMessage : ""; }
    public CaptureAction getCurrentAction() { return session != null ? session.currentAction : null; }
    public int getTargetLevel() { return session != null ? session.targetLevel : 0; }
    public boolean isTargetLegendary() { return session != null && session.targetIsLegendary; }
    public int getTotalBallsThrown() { return session != null ? session.totalBallsThrown : 0; }
    public String getTargetName() { return session != null ? session.targetName : null; }

    /**
     * Called when the battle object goes null (debounced).
     * If a capture was confirmed by chat, this is expected. Otherwise,
     * re-engage the target if it's still alive in the world.
     */
    public void onBattleEnded() {
        CaptureSession s = session;
        if (s == null) return;

        // Target still alive? Re-engage instead of giving up.
        if (s.targetEntity != null && s.targetEntity.isAlive() && !s.targetEntity.isRemoved()) {
            AutoQiqiClient.log("Capture", "Battle ended but " + s.targetName + " is still alive — re-engaging");
            reengageTarget(s, "battle ended, target still alive");
            return;
        }

        if (s.targetName != null) recordCaptureFailed(s.targetName);
        String msg = s.targetName + " - battle ended (Balls: " + s.totalBallsThrown + ")";
        s.statusMessage = msg;
        AutoQiqiClient.log("Capture", msg);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§6[Capture]§r §a" + msg), false);
            client.options.forwardKey.setPressed(false);
        }
        String reason = s.totalBallsThrown == 0 ? "no balls thrown" : "escaped after " + s.totalBallsThrown + " balls";
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logCaptureFailed(
                s.targetName, s.targetLevel, s.targetIsLegendary, s.totalBallsThrown, reason);
        session = null;
    }

    /**
     * Called by ChatMessageHandler when "a été ajouté a votre PC" is detected.
     * This is the reliable capture success signal.
     */
    public void onCaptureConfirmedByChat(String pokemonName) {
        CaptureSession s = session;
        if (s == null) return;
        AutoQiqiClient.log("Capture", "CAPTURE CONFIRMED via chat: " + pokemonName);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("§6[Capture]§r §a§l" + pokemonName + " capture ! §7(balls: " + s.totalBallsThrown + ")"), false);
        }
        session = null;
    }

    // ========================
    // Main tick (walking + engagement phases)
    // ========================

    public void tick(MinecraftClient client) {
        CaptureSession s = session;
        if (s == null || client.player == null) return;

        // Total capture timeout (walking + engaging phases only)
        if (s.phase == Phase.WALKING || s.phase == Phase.ENGAGING) {
            long elapsed = System.currentTimeMillis() - s.captureStartMs;
            if (elapsed > CAPTURE_TOTAL_TIMEOUT_MS) {
                AutoQiqiClient.log("Capture", "TOTAL TIMEOUT after " + (elapsed / 1000) + "s");
                client.player.sendMessage(
                        Text.literal("§6[Capture]§r §cTimeout: impossible de capturer §e" + s.targetName + "§c. Arret."), false);
                stop();
                return;
            }
        }

        if (s.pendingKeyRelease != null) {
            KeyBinding.setKeyPressed(s.pendingKeyRelease, false);
            s.pendingKeyRelease = null;
        }

        switch (s.phase) {
            case WALKING -> tickWalking(s, client);
            case ENGAGING -> tickEngaging(s, client);
            case IN_BATTLE -> {
                if (s.pickingUpBall) tickPickupBall(s, client);
                tickInBattleIdleCheck(s);
            }
            default -> {}
        }
    }

    private void tickWalking(CaptureSession s, MinecraftClient client) {
        if (s.targetEntity == null || !s.targetEntity.isAlive() || s.targetEntity.isRemoved()) {
            AutoQiqiClient.log("Capture", "Target gone during WALKING phase");
            client.player.sendMessage(Text.literal("§6[Capture]§r §cPokemon disparu !"), false);
            stop();
            return;
        }

        if (client.currentScreen != null) return;

        s.walkTicks++;
        if (s.walkTicks > MAX_WALK_TICKS) {
            s.walkRetries++;
            double dist = client.player.distanceTo(s.targetEntity);
            AutoQiqiClient.log("Capture", "Walk timeout (" + s.walkRetries + "/" + MAX_WALK_RETRIES
                    + ") dist=" + String.format("%.1f", dist)
                    + " player=" + fmtPos(client.player.getPos())
                    + " target=" + fmtPos(s.targetEntity.getPos()));
            if (s.walkRetries >= MAX_WALK_RETRIES) {
                client.player.sendMessage(Text.literal("§6[Capture]§r §cImpossible d'atteindre " + s.targetName + " (pathfinding echoue). /pk stop pour annuler."), false);
                stop();
                return;
            }
            s.walkTicks = 0;
            PokemonWalker.get().stop();
        }

        if (!PokemonWalker.get().isActive()) {
            if (PokemonWalker.get().hasTimedOut()) {
                AutoQiqiClient.log("Capture", "Walker timed out, aborting capture");
                client.player.sendMessage(Text.literal("§6[Capture]§r §cImpossible d'atteindre " + s.targetName + ". Arret."), false);
                stop();
                return;
            }
            double dist = client.player.distanceTo(s.targetEntity);
            if (dist <= s.currentEngageRange) {
                s.phase = Phase.ENGAGING;
                s.aimTicks = 0;
                s.keySent = false;
                s.statusMessage = "Engaging " + s.targetName + "...";
                AutoQiqiClient.log("Capture", "WALKING->ENGAGING dist=" + String.format("%.1f", dist));
            } else {
                AutoQiqiClient.log("Capture", "Walker stopped but still far (dist=" + String.format("%.1f", dist) + "), restarting walk");
                PokemonWalker.get().startWalking(s.targetEntity);
            }
        }
    }

    private void tickEngaging(CaptureSession s, MinecraftClient client) {
        // Detect battle before "target gone": when send-out starts battle, Cobblemon often
        // removes the wild entity; we must transition to IN_BATTLE so capture mixin is used.
        if (client.currentScreen != null) {
            String screenName = client.currentScreen.getClass().getSimpleName();
            AutoQiqiClient.log("Capture", "Screen opened during ENGAGING: " + screenName);
            if (screenName.toLowerCase().contains("battle")) {
                s.reengagePending = false;
                s.phase = Phase.IN_BATTLE;
                s.statusMessage = "In battle - " + s.targetName;
                AutoQiqiClient.log("Capture", "ENGAGING->IN_BATTLE (battle screen detected)");
                s.aimTicks = 0;
                s.keySent = false;
            }
            return;
        }

        // Also check if CobblemonClient reports a battle (screen may lag).
        if (!s.reengagePending
                && CobblemonClient.INSTANCE.getBattle() != null
                && s.phase != Phase.IN_BATTLE) {
            s.phase = Phase.IN_BATTLE;
            s.statusMessage = "In battle - " + s.targetName;
            AutoQiqiClient.log("Capture", "ENGAGING->IN_BATTLE (CobblemonClient.getBattle() != null)");
            s.aimTicks = 0;
            s.keySent = false;
            return;
        }

        if (s.targetEntity == null || !s.targetEntity.isAlive() || s.targetEntity.isRemoved()) {
            AutoQiqiClient.log("Capture", "Target gone during ENGAGING phase");
            client.player.sendMessage(Text.literal("§6[Capture]§r §cPokemon disparu !"), false);
            stop();
            return;
        }

        double dist = client.player.distanceTo(s.targetEntity);
        if (dist > s.currentEngageRange) {
            MovementHelper.stopStrafe(client);
            s.losStrafeTicks = 0;
            AutoQiqiClient.log("Capture", "ENGAGING->WALKING target moved away (dist=" + String.format("%.1f", dist) + ")");
            s.phase = Phase.WALKING;
            PokemonWalker.get().startWalking(s.targetEntity);
            s.statusMessage = "Walking to " + s.targetName + "...";
            return;
        }

        if (dist < ENGAGE_RANGE_TOO_CLOSE) {
            MovementHelper.lookAtEntity(client.player, s.targetEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);
            client.options.backKey.setPressed(true);
            client.options.forwardKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
            s.statusMessage = "Too close, backing up...";
            s.aimTicks = 0;
            s.keySent = false;
            return;
        }
        client.options.backKey.setPressed(false);

        boolean hasLOS = MovementHelper.hasLineOfSight(client.player, s.targetEntity);

        if (!hasLOS) {
            s.losStrafeTicks++;
            if (s.losStrafeTicks == 1) {
                AutoQiqiClient.log("Capture", "No LOS to target, strafing to find clear angle");
            }
            if (s.losStrafeTicks % LOS_STRAFE_SWITCH_TICKS == 0) {
                s.losStrafeDir = -s.losStrafeDir;
            }
            if (s.losStrafeTicks > LOS_MAX_STRAFE_TICKS) {
                AutoQiqiClient.log("Capture", "LOS strafe timeout, trying to engage anyway");
                s.losStrafeTicks = 0;
            } else {
                MovementHelper.strafeSideways(client, s.targetEntity, client.player, s.losStrafeDir);
                s.statusMessage = "Finding clear angle on " + s.targetName + "...";
                s.aimTicks = 0;
                s.keySent = false;
                return;
            }
        } else if (s.losStrafeTicks > 0) {
            AutoQiqiClient.log("Capture", "LOS acquired after " + s.losStrafeTicks + " ticks of strafing");
            MovementHelper.stopStrafe(client);
            s.losStrafeTicks = 0;
        }

        snapLookAtEntity(client.player, s.targetEntity);
        s.aimTicks++;

        boolean crosshairOnTarget = isCrosshairOnTarget(client, s.targetEntity);

        if (!s.keySent && s.aimTicks >= AIM_TICKS_BEFORE_PRESS) {
            boolean bypassCrosshair = s.engageAttempts >= CROSSHAIR_BYPASS_AFTER;
            if (!crosshairOnTarget && !bypassCrosshair) {
                if (s.aimTicks % 4 == 0) {
                    AutoQiqiClient.log("Capture", "Crosshair not on target, wiggling aim (dist=" + String.format("%.1f", dist) + ")");
                }
                if (s.aimTicks > AIM_TICKS_BEFORE_PRESS + 10) {
                    s.aimTicks = 0;
                    s.engageAttempts++;
                    if (s.engageAttempts >= CROSSHAIR_BYPASS_AFTER) {
                        AutoQiqiClient.log("Capture", "Crosshair bypass active after " + s.engageAttempts + " misses (small hitbox?), will force send-out key");
                    }
                    if (s.engageAttempts >= 4 && s.currentEngageRange > ENGAGE_RANGE_MIN) {
                        s.currentEngageRange = Math.max(ENGAGE_RANGE_MIN, s.currentEngageRange - 1.0);
                        AutoQiqiClient.log("Capture", "Reducing engage range to " + String.format("%.1f", s.currentEngageRange));
                    }
                }
                return;
            }

            s.engageAttempts++;
            if (s.engageAttempts > MAX_ENGAGE_ATTEMPTS) {
                AutoQiqiClient.log("Capture", "Engagement failed after " + MAX_ENGAGE_ATTEMPTS + " attempts (not wild?)");
                client.player.sendMessage(Text.literal("§6[Capture]§r §cImpossible d'engager " + s.targetName + " (pas sauvage ?). Arret."), false);
                stop();
                return;
            }
            String reason = crosshairOnTarget ? "crosshair hit" : "bypass (snap aim)";
            AutoQiqiClient.log("Capture", "Sending send-out key (" + reason + ", attempt=" + s.engageAttempts + "/" + MAX_ENGAGE_ATTEMPTS
                    + " dist=" + String.format("%.1f", dist) + " reengage=" + s.reengagePending + ")");
            s.reengagePending = false;
            AutoQiqiClient.recordModEngagement();
            simulateSendOutKey(s, client);
            s.keySent = true;
        }

        if (s.keySent && s.aimTicks >= AIM_TICKS_BEFORE_PRESS + AIM_TICKS_AFTER_PRESS) {
            s.aimTicks = 0;
            s.keySent = false;
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

    private void simulateSendOutKey(CaptureSession s, MinecraftClient client) {
        KeyBinding sendOut = findSendOutKey(client);
        if (sendOut != null) {
            InputUtil.Key key = InputUtil.fromTranslationKey(sendOut.getBoundKeyTranslationKey());
            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);
            s.pendingKeyRelease = key;
            AutoQiqiClient.log("Capture", "Send-out key pressed");
        } else {
            AutoQiqiClient.log("Capture", "Could not find Cobblemon send-out keybinding");
        }
    }

    private KeyBinding findSendOutKey(MinecraftClient client) {
        if (keybindSearchDone) return cachedSendOutKey;

        KeyBinding boundToR = null;
        for (KeyBinding kb : client.options.allKeys) {
            String translationKey = kb.getTranslationKey().toLowerCase();
            String category = kb.getCategory().toLowerCase();
            if (category.contains("cobblemon") || translationKey.contains("cobblemon")) {
                if (translationKey.contains("send") || translationKey.contains("throw")
                        || translationKey.contains("summon") || translationKey.contains("battle")
                        || translationKey.contains("challenge") || translationKey.contains("pokemon")) {
                    cachedSendOutKey = kb;
                    if (kb.getBoundKeyTranslationKey().equals("key.keyboard.r")) {
                        boundToR = kb;
                        break;
                    }
                }
            }
        }
        if (boundToR != null) {
            cachedSendOutKey = boundToR;
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
        CaptureSession s = session;
        if (client.player == null || s == null) return;

        // Deferred retry: reattempt throw once cooldown expires
        if (s.retryThrowPending && !s.pendingBallThrow && !s.waitingForBallHit && !s.pickingUpBall) {
            long sinceLast = System.currentTimeMillis() - s.lastThrowTimeMs;
            if (sinceLast >= THROW_COOLDOWN_MS) {
                s.retryThrowPending = false;
                AutoQiqiClient.log("Capture", "Deferred retry: cooldown expired, retrying ball throw");
                prepareBallThrow();
            }
        }

        // Battle ended during throw phase (e.g. fled): cancel and allow main tick to call onBattleEnded
        if (s.pendingBallThrow && CobblemonClient.INSTANCE.getBattle() == null) {
            s.pendingBallThrow = false;
            s.backingUp = false;
            s.backupTicks = 0;
            s.entityObstructionStrafeTicks = 0;
            s.throwSlotSelected = false;
            s.throwAimTicks = 0;
            s.retryThrowPending = false;
            client.options.backKey.setPressed(false);
            MovementHelper.stopStrafe(client);
            AutoQiqiClient.log("Capture", "Battle ended during throw phase, cancelling");
            return;
        }

        if (!s.pendingBallThrow) return;

        s.throwTicksRemaining--;
        if (s.throwTicksRemaining > 0) return;

        // Phase 0: back up if too close
        if (s.targetEntity != null && s.targetEntity.isAlive() && !s.targetEntity.isRemoved()) {
            double dist = client.player.distanceTo(s.targetEntity);
            if (dist > s.currentEngageRange) {
                s.pendingBallThrow = false;
                s.backingUp = false;
                s.backupTicks = 0;
                s.entityObstructionStrafeTicks = 0;
                s.throwSlotSelected = false;
                s.throwAimTicks = 0;
                client.options.backKey.setPressed(false);
                MovementHelper.stopStrafe(client);
                AutoQiqiClient.log("Capture", "Too far from target during throw (dist=" + String.format("%.1f", dist)
                        + " > " + s.currentEngageRange + "), aborting");
                return;
            }
            if (dist < MIN_THROW_DISTANCE && dist < s.currentEngageRange - 0.5 && s.backupTicks < MAX_BACKUP_TICKS) {
                if (!s.backingUp) {
                    s.backingUp = true;
                    AutoQiqiClient.log("Capture", "Too close to throw (dist=" + String.format("%.1f", dist)
                            + "), backing up to " + MIN_THROW_DISTANCE);
                }
                MovementHelper.lookAtEntity(client.player, s.targetEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);
                client.options.backKey.setPressed(true);
                client.options.forwardKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
                s.backupTicks++;
                return;
            }
            if (s.backingUp) {
                client.options.backKey.setPressed(false);
                s.backingUp = false;
                AutoQiqiClient.log("Capture", "Backed up to dist=" + String.format("%.1f", dist)
                        + " after " + s.backupTicks + " ticks");
            }
        }

        ClientBattle battle = CobblemonClient.INSTANCE.getBattle();
        if (battle != null && !battle.getMinimised() && s.targetEntity != null && s.targetEntity.isAlive() && !s.targetEntity.isRemoved()) {
            Entity blocker = MovementHelper.getEntityBlockingThrow(client.player, s.targetEntity);
            if (blocker != null && s.entityObstructionStrafeTicks < ENTITY_OBSTRUCTION_MAX_STRAFE) {
                if (s.entityObstructionStrafeTicks == 0) {
                    String blockerName = PokemonScanner.getPokemonName(blocker);
                    AutoQiqiClient.log("Capture", "Entity blocking throw path: " + blockerName
                            + " at dist=" + String.format("%.1f", client.player.distanceTo(blocker))
                            + ", strafing quarter-circle " + (s.losStrafeDir > 0 ? "left" : "right"));
                }
                s.entityObstructionStrafeTicks++;
                if (s.entityObstructionStrafeTicks == ENTITY_OBSTRUCTION_SWITCH_DIR_TICKS) {
                    s.losStrafeDir = -s.losStrafeDir;
                    AutoQiqiClient.log("Capture", "Quarter-circle blocked, reversing to "
                            + (s.losStrafeDir > 0 ? "left" : "right"));
                }
                MovementHelper.lookAtEntity(client.player, s.targetEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);
                MovementHelper.strafeSideways(client, s.targetEntity, client.player, s.losStrafeDir);
                s.throwAimTicks = 0;
                return;
            } else if (blocker == null && s.entityObstructionStrafeTicks > 0) {
                AutoQiqiClient.log("Capture", "Entity obstruction cleared after " + s.entityObstructionStrafeTicks + " ticks");
                MovementHelper.stopStrafe(client);
                s.entityObstructionStrafeTicks = 0;
            }
        }

        if (s.targetEntity != null && s.targetEntity.isAlive() && !s.targetEntity.isRemoved()) {
            MovementHelper.lookAtEntity(client.player, s.targetEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);

            boolean hasLOS = MovementHelper.hasLineOfSight(client.player, s.targetEntity);
            if (!hasLOS && s.throwAimTicks < AIM_BEFORE_THROW_TICKS + 60) {
                s.losStrafeTicks++;
                if (s.losStrafeTicks % LOS_STRAFE_SWITCH_TICKS == 0) s.losStrafeDir = -s.losStrafeDir;
                MovementHelper.strafeSideways(client, s.targetEntity, client.player, s.losStrafeDir);
                s.throwAimTicks++;
                return;
            } else if (hasLOS && s.losStrafeTicks > 0) {
                MovementHelper.stopStrafe(client);
                s.losStrafeTicks = 0;
            }
        }
        s.throwAimTicks++;
        if (s.throwAimTicks < AIM_BEFORE_THROW_TICKS) return;

        // Phase 2: select hotbar slot (once)
        if (!s.throwSlotSelected) {
            int slot = findBallInHotbar(client, s.pendingBallName);
            if (slot == -1) {
                AutoQiqiClient.log("Capture", "Ball '" + s.pendingBallName + "' not in hotbar (seqIdx=" + s.ballSequenceIndex
                        + " count=" + s.currentBallCount + " total=" + s.totalBallsThrown + "), searching any ball...");
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
                    s.statusMessage = "No balls in hotbar!";
                    s.pendingBallThrow = false;
                    s.throwAimTicks = 0;
                    return;
                }
            }

            ItemStack ballStack = client.player.getInventory().getStack(slot);
            Identifier ballId = net.minecraft.registry.Registries.ITEM.getId(ballStack.getItem());
            AutoQiqiClient.log("Capture", "Selecting ball: slot=" + slot + " item=" + ballId + " x" + ballStack.getCount());

            client.player.getInventory().selectedSlot = slot;
            s.throwSlotSelected = true;
            return; // wait one more tick for slot selection to register
        }

        // Phase 3: throw
        AutoQiqiClient.log("Capture", "Throwing ball (aimed for " + s.throwAimTicks + " ticks)");
        if (client.interactionManager != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            AutoQiqiClient.log("Capture", "interactItem called (ball thrown)");
            com.cobblemoon.autoqiqi.common.SessionLogger.get().logBallThrow(
                    s.targetName, s.pendingBallName != null ? s.pendingBallName : "unknown", s.totalBallsThrown);
        }

        s.pendingBallThrow = false;
        s.throwAimTicks = 0;
        s.throwSlotSelected = false;
        s.backingUp = false;
        s.backupTicks = 0;
        s.entityObstructionStrafeTicks = 0;
        s.lastThrowTimeMs = System.currentTimeMillis();

        s.waitingForBallHit = true;
        s.throwWaitTicks = 0;
        AutoQiqiClient.log("Capture", "Waiting for ball hit (timeout=" + MISS_TIMEOUT_TICKS + " ticks)");
        chatBall("Waiting for hit (tick=" + (MISS_TIMEOUT_TICKS / 20) + "s, wall=" + (BALL_HIT_WALL_CLOCK_TIMEOUT_MS / 1000) + "s)");
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
        CaptureSession s = session;
        if (s == null || !s.waitingForBallHit || client.player == null) return;

        if (CobblemonClient.INSTANCE.getBattle() == null) {
            s.waitingForBallHit = false;
            s.throwWaitTicks = 0;
            s.retryThrowPending = false;
            AutoQiqiClient.log("Capture", "Battle ended while waiting for ball hit, clearing state");
            chatBall("Battle ended while waiting for hit, clearing");
            return;
        }

        long waitMs = System.currentTimeMillis() - s.lastThrowTimeMs;
        if (waitMs >= BALL_HIT_WALL_CLOCK_TIMEOUT_MS) {
            AutoQiqiClient.log("Capture", "Ball wait wall-clock timeout (" + (waitMs / 1000) + "s) — re-engaging");
            chatBall("Wall-clock timeout " + (waitMs / 1000) + "s → re-engage");
            s.waitingForBallHit = false;
            s.throwWaitTicks = 0;
            s.ballHitJustConfirmed = false;
            reengageTarget(s, "ball wait wall-clock timeout");
            return;
        }

        s.throwWaitTicks++;
        if (s.throwWaitTicks >= MISS_TIMEOUT_TICKS) {
            s.missCount++;
            AutoQiqiClient.log("Capture", "Ball MISSED! (timeout " + MISS_TIMEOUT_TICKS
                    + " ticks, missCount=" + s.missCount + "/" + MAX_MISSES + ")");
            chatBall("Tick timeout " + (MISS_TIMEOUT_TICKS / 20) + "s → Ball MISSED (" + s.missCount + "/" + MAX_MISSES + ")");
            client.player.sendMessage(
                    Text.literal("§6[Capture]§r §eBall rate ! (" + s.missCount + "/" + MAX_MISSES + ")"), false);

            s.waitingForBallHit = false;
            s.throwWaitTicks = 0;

            if (s.missCount >= MAX_MISSES) {
                AutoQiqiClient.log("Capture", "Too many misses (" + MAX_MISSES + "), switching to kill");
                String target = s.targetName != null ? s.targetName : "Pokemon";
                SessionLogger.get().logCaptureFailed(target, s.targetLevel, s.targetIsLegendary, s.totalBallsThrown, "too many misses (" + MAX_MISSES + "), switching to kill");
                client.player.sendMessage(
                        Text.literal("§6[Capture]§r §cTrop de balls ratees (" + MAX_MISSES + "). Abandon capture — passage en combat pour tuer §e" + target + "§c."), false);
                stop();
                return;
            }

            startPickupBall(s, client);
        }
    }

    /**
     * Called by the mixin when the battle action selection reappears,
     * confirming the previous ball hit the target.
     */
    public void onBallHitConfirmed() {
        CaptureSession s = session;
        if (s != null && s.waitingForBallHit) {
            AutoQiqiClient.log("Capture", "Ball HIT confirmed (wait=" + s.throwWaitTicks + " ticks)");
            chatBall("Ball HIT confirmed (wait=" + s.throwWaitTicks + " ticks)");
            s.waitingForBallHit = false;
            s.throwWaitTicks = 0;
            s.missCount = 0;
            s.ballHitJustConfirmed = true;
        }
    }

    /**
     * Returns true (and clears the flag) if a ball hit was just confirmed.
     * Used by the mixin to add extra delay before the next action.
     */
    public boolean consumeBallHitConfirmed() {
        CaptureSession s = session;
        if (s != null && s.ballHitJustConfirmed) {
            s.ballHitJustConfirmed = false;
            return true;
        }
        return false;
    }

    public int getBallHitExtraDelay() {
        return BALL_HIT_EXTRA_DELAY;
    }

    public boolean isWaitingForBallHit() {
        return session != null && session.waitingForBallHit;
    }

    public boolean isPendingBallThrow() {
        return session != null && session.pendingBallThrow;
    }

    public boolean isPickingUpBall() {
        return session != null && session.pickingUpBall;
    }

    // ========================
    // Pickup dropped ball after miss
    // ========================

    private void startPickupBall(CaptureSession s, MinecraftClient client) {
        s.droppedBallEntity = findNearbyBallItem(client);
        if (s.droppedBallEntity == null) {
            if (CobblemonClient.INSTANCE.getBattle() == null) {
                s.waitingForBallHit = false;
                AutoQiqiClient.log("Capture", "No dropped ball and battle ended, not retrying");
                chatBall("No dropped ball, battle ended — not retrying");
                return;
            }
            AutoQiqiClient.log("Capture", "No dropped ball item found nearby, retrying throw directly");
            chatBall("No dropped ball nearby → retry throw");
            retryThrow();
            return;
        }

        double dist = client.player.distanceTo(s.droppedBallEntity);
        AutoQiqiClient.log("Capture", "Found dropped ball at " + fmtPos(s.droppedBallEntity.getPos())
                + " dist=" + String.format("%.1f", dist) + ", walking to pick up");
        client.player.sendMessage(
                Text.literal("§6[Capture]§r §7Recuperation de la ball..."), false);
        s.pickingUpBall = true;
        s.pickupTicks = 0;
    }

    private void tickPickupBall(CaptureSession s, MinecraftClient client) {
        if (!s.pickingUpBall || client.player == null) return;

        s.pickupTicks++;

        if (s.droppedBallEntity == null || !s.droppedBallEntity.isAlive() || s.droppedBallEntity.isRemoved()) {
            AutoQiqiClient.log("Capture", "Dropped ball entity gone (picked up or despawned) after " + s.pickupTicks + " ticks");
            client.options.forwardKey.setPressed(false);
            s.pickingUpBall = false;
            s.droppedBallEntity = null;
            retryThrow();
            return;
        }

        double dist = client.player.distanceTo(s.droppedBallEntity);
        if (dist <= 1.5) {
            AutoQiqiClient.log("Capture", "Close enough to ball (dist=" + String.format("%.1f", dist) + "), waiting for auto-pickup");
            return;
        }

        if (s.pickupTicks > MAX_PICKUP_TICKS) {
            AutoQiqiClient.log("Capture", "Pickup timeout (" + MAX_PICKUP_TICKS + " ticks), giving up on this ball");
            client.options.forwardKey.setPressed(false);
            s.pickingUpBall = false;
            s.droppedBallEntity = null;
            PokemonWalker.get().stop();
            retryThrow();
            return;
        }

        if (!PokemonWalker.get().isActive() || s.pickupTicks % 40 == 0) {
            MovementHelper.lookAtEntity(client.player, s.droppedBallEntity, AIM_YAW_SPEED, AIM_PITCH_SPEED);
            walkTowardEntity(client, s.droppedBallEntity);
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

    private void tickInBattleIdleCheck(CaptureSession s) {
        if (s.pendingBallThrow || s.waitingForBallHit || s.pickingUpBall) {
            s.inBattleIdleSinceMs = 0;
            return;
        }
        if (s.inBattleIdleSinceMs == 0) {
            s.inBattleIdleSinceMs = System.currentTimeMillis();
            return;
        }
        long idleMs = System.currentTimeMillis() - s.inBattleIdleSinceMs;
        if (idleMs >= IN_BATTLE_IDLE_TIMEOUT_MS) {
            AutoQiqiClient.log("Capture", "IN_BATTLE idle for " + (idleMs / 1000) + "s, re-engaging");
            reengageTarget(s, "idle safety net after " + (idleMs / 1000) + "s");
        }
    }

    /**
     * Called from BattleCaptureEndHandlerMixin after a breakout.
     */
    public void reengageAfterBreakout(String reason) {
        CaptureSession s = session;
        if (s == null) return;
        if (s.phase != Phase.IN_BATTLE) return;
        if (s.pendingBallThrow || s.waitingForBallHit) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null) return;
        reengageTarget(s, reason);
    }

    private void reengageTarget(CaptureSession s, String reason) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (s.targetEntity == null || !s.targetEntity.isAlive() || s.targetEntity.isRemoved()) {
            AutoQiqiClient.log("Capture", "Re-engage: target entity gone, cannot re-engage (" + reason + ")");
            client.player.sendMessage(
                    Text.literal("§6[Capture]§r §cPokemon disparu, impossible de re-engager."), false);
            stop();
            return;
        }

        AutoQiqiClient.log("Capture", "RE-ENGAGING " + s.targetName + " (" + reason + ")");
        client.player.sendMessage(
                Text.literal("§6[Capture]§r §eRe-engagement de §f" + s.targetName + "§e..."), false);

        s.pendingBallThrow = false;
        s.waitingForBallHit = false;
        s.ballHitJustConfirmed = false;
        s.retryThrowPending = false;
        s.pickingUpBall = false;
        s.droppedBallEntity = null;
        s.inBattleIdleSinceMs = 0;
        s.lastThrowTimeMs = 0;

        s.reengagePending = true;
        s.phase = Phase.ENGAGING;
        s.aimTicks = 0;
        s.keySent = false;
        s.engageAttempts = 0;
        s.currentEngageRange = ENGAGE_RANGE_INITIAL;
        s.captureStartMs = System.currentTimeMillis();
        s.statusMessage = "Re-engaging " + s.targetName + "...";
    }

    private void retryThrow() {
        CaptureSession s = session;
        if (s == null) return;
        AutoQiqiClient.log("Capture", "Retrying ball throw (missCount=" + s.missCount + ")");
        var battle = CobblemonClient.INSTANCE.getBattle();
        if (battle != null && battle.getMinimised()) {
            prepareBallThrow();
            if (session != null && !session.pendingBallThrow) {
                session.retryThrowPending = true;
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
        return decideGeneralAction(forceSwitch, false);
    }

    public GeneralChoice decideGeneralAction(boolean forceSwitch, boolean trapped) {
        CaptureSession s = session;
        if (s == null) return GeneralChoice.FIGHT;

        s.decisionCount++;
        BattleSnapshot battle = new BattleSnapshot(
            getActivePokemonName(),
            getActiveHpPercent(),
            getOpponentHpPercent(),
            getOpponentPokemonName(),
            getOpponentStatus()
        );
        CaptureSessionSnapshot sessionSnapshot = new CaptureSessionSnapshot(
            s.targetName,
            s.targetLevel,
            s.targetIsLegendary,
            s.targetAtOneHp,
            s.falseSwipeUsedThisBattle,
            s.falseSwipeCount,
            s.lastOppHpBeforeFalseSwipe,
            s.thunderWaveApplied,
            s.ballsSinceLastTWave,
            s.cycleNextIsBall,
            s.consecutiveSwitchAttempts,
            s.lastAttemptedSwitchAction,
            s.totalBallsThrown,
            s.decisionCount
        );

        CaptureDecision decision = CaptureStrategy.decide(sessionSnapshot, battle, forceSwitch, trapped);

        if (decision.giveUp()) {
            AutoQiqiClient.log("Capture", "Legendary: " + s.totalBallsThrown + " balls thrown, giving up — killing " + s.targetName);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                client.player.sendMessage(
                        Text.literal("§6[Capture]§r §c" + s.totalBallsThrown + " balls, abandon capture — passage en combat pour tuer §e" + s.targetName + "§c."), false);
            }
            SessionLogger.get().logCaptureFailed(s.targetName, s.targetLevel, true, s.totalBallsThrown, "gave up after " + s.totalBallsThrown + " balls");
            stop();
            return GeneralChoice.FIGHT;
        }

        SessionUpdates u = decision.sessionUpdates();
        s.targetAtOneHp = u.targetAtOneHp();
        s.thunderWaveApplied = u.thunderWaveApplied();
        s.ballsSinceLastTWave = u.ballsSinceLastTWave();
        s.lastOppHpBeforeFalseSwipe = u.lastOppHpBeforeFalseSwipe();
        s.cycleNextIsBall = u.cycleNextIsBall();
        s.consecutiveSwitchAttempts = u.consecutiveSwitchAttempts();
        s.lastAttemptedSwitchAction = u.lastAttemptedSwitchAction();
        s.currentAction = decision.action();
        if (decision.statusMessage() != null) s.statusMessage = decision.statusMessage();

        int minFS = CaptureStrategy.getMinFalseSwipes(s.targetLevel);
        AutoQiqiClient.log("Capture", "Decide #" + s.decisionCount
                + ": target=" + s.targetName + " Lv." + s.targetLevel + (s.targetIsLegendary ? " [LEG]" : "")
                + " | active=" + battle.activePokemonName() + " HP=" + f(battle.activeHpPercent()) + "%"
                + " | opp=" + battle.opponentPokemonName() + " HP=" + f(battle.opponentHpPercent()) + "% status=" + battle.opponentStatus()
                + " | at1HP=" + s.targetAtOneHp + " twave=" + s.thunderWaveApplied
                + " | fsCount=" + s.falseSwipeCount + " minFS=" + minFS + " prevHp=" + f(s.lastOppHpBeforeFalseSwipe) + "%"
                + " | balls=" + s.totalBallsThrown + " ballsSinceTWave=" + s.ballsSinceLastTWave
                + " cycleNext=" + (s.cycleNextIsBall ? "BALL" : "FS")
                + " | forceSwitch=" + forceSwitch + " trapped=" + trapped
                + " | switchAttempts=" + s.consecutiveSwitchAttempts);
        AutoQiqiClient.log("Capture", "Decision: " + decision.action() + (decision.statusMessage() != null ? " — " + decision.statusMessage() : ""));
        chatBall("Decide #" + s.decisionCount + ": " + formatDecisionChoice(decision.action()));

        return actionToGeneralChoice(decision.action());
    }

    private static String formatDecisionChoice(CaptureAction action) {
        if (action == CaptureAction.THROW_BALL) return "THROW_BALL (total=...)";
        if (action == CaptureAction.SWITCH_MAROWAK || action == CaptureAction.SWITCH_DRAGONITE || action == CaptureAction.SWITCH_TANK) return "SWITCH (" + action + ")";
        return action.toString();
    }

    private static GeneralChoice actionToGeneralChoice(CaptureAction action) {
        return switch (action) {
            case FALSE_SWIPE, THUNDER_WAVE -> GeneralChoice.FIGHT;
            case SWITCH_MAROWAK, SWITCH_DRAGONITE, SWITCH_TANK -> GeneralChoice.SWITCH;
            case THROW_BALL -> GeneralChoice.CAPTURE;
        };
    }

    // ========================
    // Capture: prepare ball throw
    // ========================

    /**
     * Called by the mixin after minimizing the battle.
     */
    public void prepareBallThrow() {
        CaptureSession s = session;
        if (s == null) return;
        if (s.pendingBallThrow) {
            AutoQiqiClient.log("Capture", "Ball throw already pending, skipping");
            return;
        }
        if (s.waitingForBallHit) {
            AutoQiqiClient.log("Capture", "Still waiting for ball hit, skipping");
            return;
        }
        long sinceLast = System.currentTimeMillis() - s.lastThrowTimeMs;
        if (s.lastThrowTimeMs > 0 && sinceLast < THROW_COOLDOWN_MS) {
            AutoQiqiClient.log("Capture", "Throw cooldown active (" + sinceLast + "ms/" + THROW_COOLDOWN_MS + "ms), skipping");
            return;
        }
        s.pendingBallName = getNextBallName(s);
        s.pendingBallThrow = true;
        s.throwTicksRemaining = THROW_DELAY_TICKS;
        AutoQiqiClient.log("Capture", "Ball throw prepared: " + s.pendingBallName);
    }

    private String getNextBallName(CaptureSession s) {
        if (s.activeBallSequence == null) return "ultra_ball";
        if (s.ballSequenceIndex >= s.activeBallSequence.length) {
            s.statusMessage = "Ultra Ball #" + (s.ultraBallsThrown + 1);
            s.ultraBallsThrown++;
            s.totalBallsThrown++;
            return "ultra_ball";
        }

        BallEntry entry = s.activeBallSequence[s.ballSequenceIndex];
        if (s.currentBallCount >= entry.count) {
            s.ballSequenceIndex++;
            s.currentBallCount = 0;
            return getNextBallName(s);
        }

        s.statusMessage = formatBallName(entry.name) + " (" + (s.currentBallCount + 1) + "/" + entry.count + ")";
        AutoQiqiClient.log("Capture", "Throwing " + entry.name + " " + (s.currentBallCount + 1) + "/" + entry.count);

        s.currentBallCount++;
        s.totalBallsThrown++;
        if (entry.name.equals("great_ball")) s.greatBallsThrown++;
        if (entry.name.equals("ultra_ball")) s.ultraBallsThrown++;

        return entry.name;
    }

    // ========================
    // Move selection: called by BattleMoveSelectionMixin
    // ========================

    public MoveTile chooseMoveFromTiles(List<MoveTile> selectableTiles) {
        CaptureSession s = session;
        if (s == null) return null;
        String target = (s.currentAction == CaptureAction.THUNDER_WAVE)
                ? "thunder wave" : "false swipe";
        for (MoveTile tile : selectableTiles) {
            String moveName = tile.getMove().getMove().toLowerCase();
            if (moveName.contains(target)) {
                if (target.equals("false swipe") && (moveName.contains("false swipe") || moveName.contains("chage"))) {
                    s.falseSwipeUsedThisBattle = true;
                    s.falseSwipeCount++;
                    AutoQiqiClient.log("Capture", "Selected False Swipe #" + s.falseSwipeCount + " (HP before=" + f(s.lastOppHpBeforeFalseSwipe) + "%)");
                }
                AutoQiqiClient.log("Capture", "Selected move: " + moveName);
                return tile;
            }
        }
        for (MoveTile tile : selectableTiles) {
            String moveName = tile.getMove().getMove().toLowerCase();
            if (moveName.contains("wave") || moveName.contains("swipe")
                    || moveName.contains("spore") || moveName.contains("sleep")
                    || moveName.contains("stun") || moveName.contains("paralyze")) {
                AutoQiqiClient.log("Capture", "Fallback safe move: " + moveName);
                return tile;
            }
        }
        if (s.consecutiveSwitchAttempts >= MAX_SWITCH_ATTEMPTS && !selectableTiles.isEmpty()) {
            MoveTile fallback = selectableTiles.get(0);
            AutoQiqiClient.log("Capture", "WARNING: switch blocked, using fallback move: " + fallback.getMove().getMove());
            return fallback;
        }
        AutoQiqiClient.log("Capture", "WARNING: target move '" + target + "' not found! Moves: "
                + selectableTiles.stream().map(t -> t.getMove().getMove()).toList()
                + " — aborting move to avoid killing");
        return null;
    }

    public SwitchTile chooseSwitchFromTiles(List<SwitchTile> availableTiles) {
        CaptureSession s = session;
        if (s == null) return availableTiles.isEmpty() ? null : availableTiles.get(0);
        if (s.currentAction == CaptureAction.SWITCH_MAROWAK) {
            return findTileBySpecies(availableTiles, "marowak");
        }
        if (s.currentAction == CaptureAction.SWITCH_DRAGONITE) {
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
            // Disambiguate: if maxHp looks like an integer stat (> 1), treat hpValue as absolute HP.
            // Otherwise (maxHp <= 1), treat hpValue as a 0-1 ratio.
            // Using maxHp avoids the edge case where hpValue == 1.0 (exactly 1 HP remaining).
            if (maxHp > 1.0f) {
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

    private static String f(float v) { return String.format("%.1f", v); }

    private static String fmtPos(net.minecraft.util.math.Vec3d v) {
        return String.format("(%.1f,%.1f,%.1f)", v.x, v.y, v.z);
    }

    private static String formatBallName(String id) {
        String s = id.replace("_", " ");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static void handleCaptureAction(BattleGeneralActionSelection self) {
        CaptureEngine engine = CaptureEngine.get();
        boolean forceSwitch = self.getRequest().getForceSwitch();
        boolean trapped = !forceSwitch
                && self.getRequest().getMoveSet() != null
                && self.getRequest().getMoveSet().getTrapped();

        CaptureEngine.GeneralChoice choice = engine.decideGeneralAction(forceSwitch, trapped);
        AutoQiqiClient.log("Mixin", "GeneralAction capture choice=" + choice + " action=" + engine.getCurrentAction() + " forceSwitch=" + forceSwitch);
        chatBall("Choice: " + choice + " (" + engine.getCurrentAction() + ")");
        self.playDownSound(MinecraftClient.getInstance().getSoundManager());

        switch (choice) {
            case FIGHT -> {
                AutoQiqiClient.log("Mixin", "GeneralAction -> FIGHT (opening move selection)");
                self.getBattleGUI().changeActionSelection(
                    new BattleMoveSelection(self.getBattleGUI(), self.getRequest()));
            }
            case SWITCH -> {
                AutoQiqiClient.log("Mixin", "GeneralAction -> SWITCH (opening switch selection)");
                self.getBattleGUI().changeActionSelection(
                    new BattleSwitchPokemonSelection(self.getBattleGUI(), self.getRequest()));
            }
            case CAPTURE -> {
                var battle = CobblemonClient.INSTANCE.getBattle();
                if (battle != null) {
                    battle.setMinimised(true);
                    AutoQiqiClient.log("Mixin", "GeneralAction -> CAPTURE (battle minimized, preparing ball)");
                } else {
                    AutoQiqiClient.log("Mixin", "GeneralAction -> CAPTURE but getBattle() is null!");
                }
                engine.prepareBallThrow();
            }
        }
    }

    public record BallEntry(String name, int count) {}
}
