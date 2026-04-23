package com.cobblemoon.autoqiqi.battle;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.ChatUtil;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.common.NextlegAfkHelper;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.ChatMessageHandler;
import com.cobblemoon.autoqiqi.legendary.PokemonWalker;
import com.cobblemoon.autoqiqi.legendary.WorldTracker;
import com.cobblemon.mod.common.client.CobblemonClient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles <strong>battle mode</strong> and <strong>overworld targeting</strong> only:
 * scans for nearby wild Pokemon, walks toward the closest one, aims, and simulates
 * Cobblemon's send-out key to start a battle.
 * <p>
 * This engine does <em>not</em> make in-battle decisions (which move, fight vs switch).
 * Those are made by {@link TrainerBattleEngine} or {@link CaptureEngine}, and routed
 * via {@link com.cobblemoon.autoqiqi.battle.BattleDecisionRouter} from the battle GUI mixins.
 * <p>
 * Supports {@link BattleMode#BERSERK} (all wild, auto-fight),
 * {@link BattleMode#ROAMING} (engage legendaries only — the user handles the battle manually
 * once it starts), and {@link BattleMode#TRAINER}.
 */
public class AutoBattleEngine {
    private static final AutoBattleEngine INSTANCE = new AutoBattleEngine();

    private static final double SCAN_RANGE = 24.0;
    private static final double ENGAGE_RANGE = 4.0;
    private static final int SCAN_INTERVAL = 10;
    private static final int POST_BATTLE_COOLDOWN = 80;
    private static final int ENGAGE_COOLDOWN = 60;
    private static final int AIM_TICKS_BEFORE_PRESS = 12;
    private static final int AIM_TICKS_AFTER_PRESS = 5;
    private static final double LEVEL_PREFER_RADIUS = 5.0;

    private static final float WALK_YAW_SPEED = 10.0f;
    private static final float WALK_PITCH_SPEED = 8.0f;

    private BattleMode mode = BattleMode.OFF;
    private Entity target;
    private int scanTimer = 0;
    private int cooldown = 0;
    private boolean wasInBattle = false;
    private boolean walking = false;
    private int battleCount = 0;
    private int aimTicks = 0;
    private boolean keySimulated = false;
    private int keySimulatedAtTick = 0;
    private InputUtil.Key pendingRelease = null;

    private KeyBinding cachedSendOutKey = null;
    private boolean keybindSearchDone = false;

    private KeyBinding cachedPartyUpKey = null;
    private boolean partyUpSearchDone = false;
    private int partyResetPressesLeft = 0;
    private static final int PARTY_RESET_INTERVAL = 4;

    // Line-of-sight strafing
    private int losStrafeTicks = 0;
    private int losStrafeDir = 1;

    // Aim offset cycling when crosshair is blocked by non-solid blocks (flowers, grass)
    private int aimOffsetIndex = 0;
    private static final double[] AIM_HEIGHT_FRACTIONS = {0.5, 0.75, 0.9, 0.35};

    // Loot pickup after battle
    private boolean collectingLoot = false;
    private Entity lootTarget = null;
    private int lootTicks = 0;
    private int lootCollected = 0;
    private static final double LOOT_SCAN_RANGE = 12.0;
    private static final double LOOT_PICKUP_RANGE = 1.5;
    private static final int LOOT_MAX_TICKS = 100; // 5 seconds max per item
    private static final int LOOT_SETTLE_TICKS = 10; // wait for items to drop

    // Engage-fail blacklist: entity IDs that we tried to engage but no battle started.
    // Maps entity ID -> game tick when the blacklist expires.
    private final Map<Integer, Long> engageBlacklist = new HashMap<>();
    private static final long BLACKLIST_DURATION_TICKS = 600; // 30 seconds
    private static final double OTHER_PLAYER_PROXIMITY = 6.0;
    private int lastEngagedEntityId = -1;
    private int bossEngageRetries = 0;
    private static final int MAX_BOSS_ENGAGE_RETRIES = 5;
    private long globalTickCounter = 0;

    // Berserk engage timeout: skip target if we can't reach it within 5 seconds
    private int berserkEngageTicks = 0;
    private static final int BERSERK_ENGAGE_TIMEOUT = 100; // 5 seconds at 20 tps

    // Roaming engage timeout: abort if we can't reach target within 30 seconds
    private int roamingEngageTicks = 0;
    private static final int ROAMING_ENGAGE_TIMEOUT = 600; // 30 seconds at 20 tps

    // Grace period after mode switch (prevents intermediate modes from starting battles while user cycles K)
    private int modeSwitchGraceTicks = 0;
    private static final int MODE_SWITCH_GRACE = 15; // ~0.75s

    // Session stats (reset when mode is toggled ON from OFF)
    private int sessionBossKills = 0;
    private int sessionPokemonKills = 0;
    private final java.util.List<String> sessionCaptures = new java.util.ArrayList<>();
    private boolean lastFightWasBoss = false;
    private boolean lastFightWasLegendary = false;
    private String lastTargetName = "Unknown";

    // Pending forced target: survives wasInBattle reset so legendary engagement isn't lost
    // when the spawn happens while another battle is in progress.
    private Entity pendingForceTarget = null;

    // Roaming in-battle anti-AFK: every N seconds of a legendary battle, minimize the battle UI,
    // aim at the Pokémon, and re-press send-out to reopen the GUI. The brief trip to the
    // overworld + camera motion resets the server-side AFK timer so we stay eligible.
    private Entity activeLegendaryInBattle = null;
    private long inBattleTicks = 0;
    private long lastInBattleAntiAfkTick = 0;
    private boolean inBattleAntiAfkPending = false;

    // Roaming nextleg/afk (delegated to shared helper)
    private final NextlegAfkHelper roamingAfkHelper = new NextlegAfkHelper("Roaming");
    /** True when capture was active last tick; used to resume /afk right after returning from a capture. */
    private boolean wasCaptureActiveLastTick = false;
    /** Throttle: log at most once per 5 min when nextleg/afk is skipped (battle or capture active). */
    private long lastRoamingSkipLogTick = 0;
    private static final int ROAMING_SKIP_LOG_INTERVAL = 6000; // 5 min

    private AutoBattleEngine() {}

    public static AutoBattleEngine get() { return INSTANCE; }
    public Entity getTarget() { return target; }
    public boolean isWalking() { return walking; }
    public BattleMode getMode() { return mode; }
    public void setMode(BattleMode mode) {
        if (this.mode != mode) {
            AutoQiqiClient.logDebug("Battle", "Mode changed: " + this.mode + " -> " + mode);
            modeSwitchGraceTicks = MODE_SWITCH_GRACE;
            if (mode == BattleMode.ROAMING) {
                roamingAfkHelper.reset();
            }
            if (mode != BattleMode.OFF && this.mode == BattleMode.OFF) {
                sessionBossKills = 0;
                sessionPokemonKills = 0;
                sessionCaptures.clear();
                battleCount = 0;
            }
            if (mode == BattleMode.OFF) {
                if (CaptureEngine.get().isActive()) {
                    CaptureEngine.get().stop();
                    AutoQiqiClient.logDebug("Battle", "CaptureEngine stopped (mode -> OFF)");
                }
                PokemonWalker.get().stop();
            }
        }
        this.mode = mode;
    }

    public boolean isEngagingBoss() {
        return target != null && target.isAlive() && PokemonScanner.isBoss(target);
    }

    /**
     * Quick check for any boss within battle scan range.
     */
    public boolean isBossNearby() {
        if (ChatMessageHandler.get().isEntityClearancePending()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return false;
        Box box = client.player.getBoundingBox().expand(SCAN_RANGE);
        for (Entity e : client.world.getOtherEntities(client.player, box)) {
            if (e instanceof PokemonEntity pe && e.isAlive() && !e.isRemoved()) {
                try {
                    var pokemon = pe.getPokemon();
                    if (pokemon.isPlayerOwned() || pokemon.getOwnerUUID() != null) continue;
                    if (pe.isBusy() || pe.getOwner() != null) continue;
                    if (PokemonScanner.isBoss(e)) return true;
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    public void clearTarget() {
        if (target != null) {
            AutoQiqiClient.logDebug("Battle", "clearTarget: releasing " + PokemonScanner.getDisplayInfo(target));
        }
        if (walking) stopWalking();
        target = null;
        cooldown = 20;
        losStrafeTicks = 0;
        aimOffsetIndex = 0;
    }

    public void reset() {
        if (walking) stopWalking();
        if (pendingRelease != null) {
            KeyBinding.setKeyPressed(pendingRelease, false);
            pendingRelease = null;
        }
        target = null;
        pendingForceTarget = null;
        cooldown = 0;
        wasInBattle = false;
        losStrafeTicks = 0;
        aimTicks = 0;
        keySimulated = false;
        aimOffsetIndex = 0;
        partyResetPressesLeft = 0;
        lastFightWasBoss = false;
        lastFightWasLegendary = false;
        collectingLoot = false;
        lootTarget = null;
        lootTicks = 0;
        lastEngagedEntityId = -1;
        berserkEngageTicks = 0;
        activeLegendaryInBattle = null;
        inBattleTicks = 0;
        lastInBattleAntiAfkTick = 0;
        inBattleAntiAfkPending = false;
        engageBlacklist.clear();
    }

    /** Clears accumulated caches without affecting battle state. Called by /pk reset. */
    public void clearCaches() {
        engageBlacklist.clear();
        sessionCaptures.clear();
        sessionBossKills = 0;
        sessionPokemonKills = 0;
    }

    public void forceTarget(Entity entity) {
        this.target = entity;
        this.pendingForceTarget = entity;
        this.aimTicks = 0;
        this.keySimulated = false;
        this.losStrafeTicks = 0;
        this.cooldown = 0;
        AutoQiqiClient.logDebug("Battle", "Force target set: " + PokemonScanner.getDisplayInfo(entity));
    }

    public void recordCapture(String pokemonName) {
        sessionCaptures.add(pokemonName);
        AutoQiqiClient.logDebug("Stats", "Capture recorded: " + pokemonName + " (session total: " + sessionCaptures.size() + ")");
        CaptureEngine cap = CaptureEngine.get();
        com.cobblemoon.autoqiqi.common.SessionLogger.get().logCapture(
                pokemonName, cap.getTargetLevel(), cap.isTargetLegendary(), cap.getTotalBallsThrown());
    }

    // ========================
    // Loot collection after battle
    // ========================

    private void tickLootCollection(MinecraftClient client, ClientPlayerEntity player) {
        // Initial settle delay: wait for items to appear on ground
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        // Handle party reset presses during loot collection too
        if (partyResetPressesLeft > 0 && lootTicks % PARTY_RESET_INTERVAL == 0) {
            pressPartyUpKey(client);
            partyResetPressesLeft--;
        }

        // If current target is gone (picked up), find next
        if (lootTarget != null && (!lootTarget.isAlive() || lootTarget.isRemoved())) {
            lootCollected++;
            AutoQiqiClient.logDebug("Battle", "Loot picked up (#" + lootCollected + ")");
            lootTarget = null;
            lootTicks = 0;
        }

        // Find next loot item
        if (lootTarget == null) {
            lootTarget = findNearbyLoot(client, player);
            if (lootTarget == null) {
                finishLootCollection(client);
                return;
            }
            lootTicks = 0;
            AutoQiqiClient.logDebug("Battle", "Walking to loot: "
                    + getLootItemName(lootTarget) + " dist="
                    + String.format("%.1f", player.distanceTo(lootTarget)));
        }

        lootTicks++;
        if (lootTicks > LOOT_MAX_TICKS) {
            AutoQiqiClient.logDebug("Battle", "Loot pickup timeout, skipping");
            lootTarget = null;
            lootTicks = 0;
            finishLootCollection(client);
            return;
        }

        double dist = player.distanceTo(lootTarget);
        if (dist <= LOOT_PICKUP_RANGE) {
            return; // auto-pickup range, just wait
        }

        // Walk toward the loot
        MovementHelper.lookAtEntity(player, lootTarget, 10.0f, 8.0f);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(dist > 4.0);
    }

    private void finishLootCollection(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        collectingLoot = false;
        lootTarget = null;
        if (lootCollected > 0) {
            AutoQiqiClient.logDebug("Battle", "Loot collection done: " + lootCollected + " items");
        }

        // Auto-hop stays disabled after legendary — must be re-enabled manually
        lastFightWasLegendary = false;

        cooldown = POST_BATTLE_COOLDOWN;
    }

    /**
     * Roaming nextleg flow: poll /nextleg for single global timer, send /afk for points,
     * and move camera shortly before timer expiry to disable AFK and become eligible for legendary spawn.
     * Runs in addition to normal roaming: the same tick still does findTarget/engage/capture for uncaught;
     * when we return from a capture we resume sending /afk (go back into AFK mode).
     *
     * IMPORTANT: /afk is suppressed when the timer is within the camera threshold so that the
     * camera-move un-AFK is not immediately undone. /afk resumes once the timer resets (new cycle).
     */
    private void tickRoamingNextlegAfk(MinecraftClient client, ClientPlayerEntity player) {
        if (!AutoQiqiClient.isConnected(client)) return;

        if (wasCaptureActiveLastTick) {
            roamingAfkHelper.resetAfkCooldown();
            AutoQiqiClient.logDebug("Battle", "Roaming: returned from capture, resuming AFK mode");
        }

        long remaining = WorldTracker.get().getGlobalRemainingSeconds();
        roamingAfkHelper.tick(player, globalTickCounter, remaining);
    }

    /**
     * While engaged in a ROAMING legendary battle, every N seconds minimize the battle UI,
     * look at the Pokémon, and re-press send-out so the GUI comes back. The brief trip
     * through the overworld + camera look packet resets the server-side AFK timer without
     * affecting the (server-side) battle state.
     */
    private void tickRoamingInBattleAntiAfk(MinecraftClient client, ClientPlayerEntity player) {
        if (mode != BattleMode.ROAMING) return;
        if (activeLegendaryInBattle == null) return;

        boolean battleActive = CobblemonClient.INSTANCE.getBattle() != null;
        if (!battleActive) {
            activeLegendaryInBattle = null;
            inBattleTicks = 0;
            lastInBattleAntiAfkTick = 0;
            inBattleAntiAfkPending = false;
            return;
        }

        int intervalSeconds = AutoQiqiConfig.get().roamingInBattleAntiAfkIntervalSeconds;
        if (intervalSeconds <= 0) return;
        long intervalTicks = intervalSeconds * 20L;

        inBattleTicks++;
        if (inBattleAntiAfkPending) return;
        if (inBattleTicks - lastInBattleAntiAfkTick < intervalTicks) return;
        lastInBattleAntiAfkTick = inBattleTicks;

        var battle = CobblemonClient.INSTANCE.getBattle();
        if (battle == null) return;

        battle.setMinimised(true);
        // Slight camera nudge to produce a look packet (matches tickRoamingNextlegAfk).
        player.setYaw(player.getYaw() + 8.0f);
        player.setPitch(net.minecraft.util.math.MathHelper.clamp(player.getPitch() + 4.0f, -90.0f, 90.0f));
        AutoQiqiClient.logDebug("Battle", "Roaming anti-AFK: minimized + nudged camera (next in "
                + intervalSeconds + "s)");
        inBattleAntiAfkPending = true;

        // Give the server a moment to register the camera packet, then press send-out to reopen
        // the battle GUI (same trick used by CaptureEngine after a breakout).
        AutoQiqiClient.runLater(() -> {
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player == null) return;
                if (activeLegendaryInBattle == null) return;
                if (!activeLegendaryInBattle.isAlive() || activeLegendaryInBattle.isRemoved()) return;
                if (CobblemonClient.INSTANCE.getBattle() == null) return;
                if (mc.currentScreen != null) return; // user already reopened something
                snapLookAtEntity(mc.player, activeLegendaryInBattle);
                simulateSendOutKey(mc);
                AutoQiqiClient.logDebug("Battle", "Roaming anti-AFK: reopened battle GUI");
            } finally {
                inBattleAntiAfkPending = false;
            }
        }, 800);
    }

    private Entity findNearbyLoot(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) return null;
        Entity closest = null;
        double closestDist = LOOT_SCAN_RANGE;
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof ItemEntity)) continue;
            double dist = player.distanceTo(entity);
            if (dist < closestDist) {
                closestDist = dist;
                closest = entity;
            }
        }
        return closest;
    }

    private String getLootItemName(Entity entity) {
        if (entity instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getStack();
            return stack.getName().getString() + " x" + stack.getCount();
        }
        return "item";
    }

    /**
     * Returns a session summary and resets the counters.
     * Returns null if nothing happened.
     */
    public java.util.List<String> getSessionSummaryAndReset() {
        java.util.List<String> lines = buildSummaryLines("Pokemon captures");
        if (lines == null) return null;
        lines.add(0, "§e=== Session Summary ===");
        sessionBossKills = 0;
        sessionPokemonKills = 0;
        sessionCaptures.clear();
        return lines;
    }

    public java.util.List<String> getSessionSummaryLines() {
        return buildSummaryLines("Captures");
    }

    private java.util.List<String> buildSummaryLines(String captureLabel) {
        if (sessionBossKills == 0 && sessionPokemonKills == 0 && sessionCaptures.isEmpty()) {
            return null;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (!sessionCaptures.isEmpty()) {
            lines.add("§a" + captureLabel + ": §f" + sessionCaptures.size());
            for (String name : sessionCaptures) {
                lines.add("  §7- §f" + name);
            }
        }
        if (sessionBossKills > 0) {
            lines.add("§cBoss kills: §f" + sessionBossKills);
        }
        if (sessionPokemonKills > 0) {
            lines.add("§7Pokemon kills: §f" + sessionPokemonKills);
        }
        return lines;
    }

    public void tick() {
        try {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;

        if (player == null || client.world == null || client.interactionManager == null) return;
        if (player.isDead()) {
            if (walking) stopWalking();
            return;
        }

        globalTickCounter++;

        if (modeSwitchGraceTicks > 0) {
            modeSwitchGraceTicks--;
            return;
        }

        // In-battle roaming anti-AFK — runs before the screen-based early returns because we
        // briefly leave the battle screen (setMinimised) as part of the nudge itself.
        tickRoamingInBattleAntiAfk(client, player);

        // Use the Cobblemon battle object as the authoritative source for "in battle" — screen
        // class names flip during legendary intros and other sub-screens, which used to cause
        // false "battle finished" events mid-battle.
        boolean inCobblemonBattle = BattleScreenHelper.isInBattleScreen(client);
        if (inCobblemonBattle) {
            if (walking) stopWalking();
            if (collectingLoot) {
                client.options.forwardKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
            }
            wasInBattle = true;
            collectingLoot = false;
            return;
        }
        if (client.currentScreen != null) {
            if (walking) stopWalking();
            if (collectingLoot) {
                client.options.forwardKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
            }
            return;
        }

        if (wasInBattle) {
            wasInBattle = false;
            lastEngagedEntityId = -1;
            bossEngageRetries = 0;
            battleCount++;
            if (lastFightWasBoss) {
                sessionBossKills++;
                AutoQiqiClient.logDebug("Battle", "Boss killed (session total: " + sessionBossKills + ")");
                com.cobblemoon.autoqiqi.common.SessionLogger.get().logKill(lastTargetName, true);
            } else {
                sessionPokemonKills++;
                com.cobblemoon.autoqiqi.common.SessionLogger.get().logKill(lastTargetName, false);
            }
            lastFightWasBoss = false;
            AutoQiqiClient.logDebug("Battle", mode.displayName() + " battle finished (total: " + battleCount + ")");

            int healEvery = AutoQiqiConfig.get().battleHealEveryN;
            if (healEvery > 0 && battleCount % healEvery == 0 && AutoQiqiClient.isConnected(MinecraftClient.getInstance())) {
                try {
                    ChatUtil.sendCommand(player, "pokeheal");
                    AutoQiqiClient.logDebug("Battle", "Sent /pokeheal (after " + battleCount + " battles)");
                } catch (Exception e) {
                    AutoQiqiClient.logDebug("Battle", "pokeheal failed (network?): " + e.getMessage());
                }
            }

            if (mode != BattleMode.BERSERK) {
                int upPresses = AutoQiqiConfig.get().postBattlePartyUpPresses;
                if (upPresses > 0) {
                    partyResetPressesLeft = upPresses;
                    AutoQiqiClient.logDebug("Battle", "Will press party-up " + upPresses + " time(s) to reset lead Pokemon");
                }
            }

            target = null;
            aimTicks = 0;
            keySimulated = false;

            // Restore forced target that was set during the battle (e.g. legendary spawn while fighting)
            if (pendingForceTarget != null && pendingForceTarget.isAlive() && !pendingForceTarget.isRemoved()) {
                target = pendingForceTarget;
                AutoQiqiClient.logDebug("Battle", "Restoring forced target after battle: " + PokemonScanner.getDisplayInfo(target));
            }
            pendingForceTarget = null;

            if (mode == BattleMode.BERSERK) {
                cooldown = 20;
                collectingLoot = false;
            } else {
                cooldown = LOOT_SETTLE_TICKS;
                collectingLoot = true;
                lootTarget = null;
                lootTicks = 0;
                lootCollected = 0;
            }
            return;
        }

        if (collectingLoot) {
            tickLootCollection(client, player);
            return;
        }

        if (cooldown > 0) {
            if (partyResetPressesLeft > 0 && cooldown % PARTY_RESET_INTERVAL == 0) {
                pressPartyUpKey(client);
                partyResetPressesLeft--;
            }
            cooldown--;
            if (cooldown == 0 && lastEngagedEntityId != -1) {
                if (lastFightWasBoss && bossEngageRetries < MAX_BOSS_ENGAGE_RETRIES) {
                    bossEngageRetries++;
                    AutoQiqiClient.logDebug("Battle", "Boss engage attempt " + bossEngageRetries + "/" + MAX_BOSS_ENGAGE_RETRIES
                            + " failed — retrying (entity #" + lastEngagedEntityId + ")");
                    lastEngagedEntityId = -1;
                    scanTimer = 0;
                } else {
                    engageBlacklist.put(lastEngagedEntityId, globalTickCounter + BLACKLIST_DURATION_TICKS);
                    AutoQiqiClient.logDebug("Battle", "Engage failed (no battle started) — blacklisting entity #"
                            + lastEngagedEntityId + " for 30s"
                            + (lastFightWasBoss ? " (boss, " + bossEngageRetries + " retries exhausted)" : ""));
                    lastEngagedEntityId = -1;
                    bossEngageRetries = 0;
                }
            }
            return;
        }

        if (mode == BattleMode.TRAINER) return;

        // Roaming: single timer from /nextleg, /afk for points, camera move before expiry to stay eligible for legendary.
        // This runs in addition to normal roaming: we still scan for uncaught, capture them, then resume AFK (see tickRoamingNextlegAfk).
        if (mode == BattleMode.ROAMING && AutoQiqiConfig.get().nextlegAfkEnabled) {
            boolean inBattle = CobblemonClient.INSTANCE.getBattle() != null;
            boolean captureActive = CaptureEngine.get().isActive();
            boolean autoHopActive = com.cobblemoon.autoqiqi.legendary.autohop.AutoHopEngine.get().isActive();
            if (!inBattle && !captureActive && !autoHopActive) {
                tickRoamingNextlegAfk(client, player);
            } else {
                if (globalTickCounter - lastRoamingSkipLogTick >= ROAMING_SKIP_LOG_INTERVAL) {
                    lastRoamingSkipLogTick = globalTickCounter;
                    String reason = inBattle ? "in battle" : captureActive ? "capture active" : "auto-hop active";
                    AutoQiqiClient.logDebug("Battle", "Roaming nextleg/afk: skipped (" + reason + ")");
                }
            }
        }

        if (target != null && (!target.isAlive() || target.isRemoved())) {
            target = null;
            aimTicks = 0;
            keySimulated = false;
            if (walking) stopWalking();
        }

        // Yield to manual engagement: user chose to walk to this Pokemon
        PokemonWalker walkerForYield = PokemonWalker.get();
        if (target != null) {
            if (walkerForYield.isActive() && walkerForYield.getTarget() != null && target.getId() == walkerForYield.getTarget().getId()) {
                AutoQiqiClient.logDebug("Battle", "Dropping target " + PokemonScanner.getDisplayInfo(target) + " — user walking to it (manual)");
                target = null;
                aimTicks = 0;
                keySimulated = false;
                if (walking) stopWalking();
            } else if (walkerForYield.isInManualWalkGracePeriod() && target.getId() == walkerForYield.getLastWalkTargetEntityId()) {
                AutoQiqiClient.logDebug("Battle", "Dropping target " + PokemonScanner.getDisplayInfo(target) + " — manual engagement grace");
                target = null;
                aimTicks = 0;
                keySimulated = false;
                if (walking) stopWalking();
            }
        }

        if (target == null) {
            if (scanTimer > 0) { scanTimer--; return; }
            target = findTarget(client, player);
            scanTimer = SCAN_INTERVAL;
            if (target == null) return;

            lastTargetName = PokemonScanner.getDisplayInfo(target);
            berserkEngageTicks = 0;
            roamingEngageTicks = 0;
            AutoQiqiClient.logDebug("Battle", "Target acquired: " + lastTargetName);
        }

        if (pendingRelease != null) {
            KeyBinding.setKeyPressed(pendingRelease, false);
            pendingRelease = null;
        }

        double dist = player.distanceTo(target);

        if (dist <= ENGAGE_RANGE) {
            if (walking) stopWalking();
            berserkEngageTicks = 0;
            roamingEngageTicks = 0;

            boolean hasLOS = MovementHelper.hasLineOfSight(player, target);

            if (!hasLOS && losStrafeTicks < 120) {
                losStrafeTicks++;
                if (losStrafeTicks == 1) {
                    AutoQiqiClient.logDebug("Battle", "No LOS to target, strafing");
                }
                if (losStrafeTicks % 30 == 0) losStrafeDir = -losStrafeDir;
                MovementHelper.strafeSideways(client, target, player, losStrafeDir);
                aimTicks = 0;
                keySimulated = false;
            } else {
                if (losStrafeTicks > 0) {
                    MovementHelper.stopStrafe(client);
                    losStrafeTicks = 0;
                }

                snapLookAtEntity(player, target);
                aimTicks++;

                if (!keySimulated && aimTicks >= AIM_TICKS_BEFORE_PRESS) {
                    boolean onTarget = isCrosshairOnTarget(client, target);
                    if (!onTarget && aimTicks < AIM_TICKS_BEFORE_PRESS + 20) {
                        if ((aimTicks - AIM_TICKS_BEFORE_PRESS) % 5 == 0) {
                            aimOffsetIndex = (aimOffsetIndex + 1) % AIM_HEIGHT_FRACTIONS.length;
                        }
                        return;
                    }
                    lastFightWasBoss = PokemonScanner.isBoss(target);
                    lastFightWasLegendary = PokemonScanner.isLegendary(target);
                    lastEngagedEntityId = target.getId();
                    if (mode == BattleMode.ROAMING && lastFightWasLegendary) {
                        activeLegendaryInBattle = target;
                        inBattleTicks = 0;
                        lastInBattleAntiAfkTick = 0;
                    }
                    AutoQiqiClient.recordModEngagement();
                    simulateSendOutKey(client);
                    keySimulated = true;
                    keySimulatedAtTick = aimTicks;
                }

                if (keySimulated && aimTicks >= keySimulatedAtTick + AIM_TICKS_AFTER_PRESS) {
                    cooldown = ENGAGE_COOLDOWN;
                    target = null;
                    pendingForceTarget = null;
                    aimTicks = 0;
                    keySimulated = false;
                    aimOffsetIndex = 0;
                }
            }
        } else {
            aimTicks = 0;
            keySimulated = false;
            losStrafeTicks = 0;

            if (mode == BattleMode.BERSERK) {
                berserkEngageTicks++;
                if (berserkEngageTicks >= BERSERK_ENGAGE_TIMEOUT) {
                    AutoQiqiClient.logDebug("Battle", "Berserk engage timeout (" + (BERSERK_ENGAGE_TIMEOUT / 20)
                            + "s) — skipping " + PokemonScanner.getDisplayInfo(target));
                    engageBlacklist.put(target.getId(), globalTickCounter + BLACKLIST_DURATION_TICKS * 10);
                    if (walking) stopWalking();
                    target = null;
                    berserkEngageTicks = 0;
                    scanTimer = 0;
                    return;
                }
            }

            if (mode == BattleMode.ROAMING) {
                roamingEngageTicks++;
                if (roamingEngageTicks >= ROAMING_ENGAGE_TIMEOUT) {
                    AutoQiqiClient.logDebug("Battle", "Roaming engage timeout (" + (ROAMING_ENGAGE_TIMEOUT / 20)
                            + "s) — cannot reach " + PokemonScanner.getDisplayInfo(target) + ", aborting");
                    player.sendMessage(Text.literal("§6[Roaming]§r §cImpossible d'atteindre la cible en 30s. Abandon."), false);
                    engageBlacklist.put(target.getId(), globalTickCounter + BLACKLIST_DURATION_TICKS);
                    if (walking) stopWalking();
                    target = null;
                    roamingEngageTicks = 0;
                    scanTimer = 0;
                    return;
                }
            }

            walkToward(client, player, target);
        }
        } finally {
        wasCaptureActiveLastTick = CaptureEngine.get().isActive();
        }
    }

    private void snapLookAtEntity(ClientPlayerEntity player, Entity target) {
        net.minecraft.util.math.Vec3d eye = player.getEyePos();
        double heightFrac = AIM_HEIGHT_FRACTIONS[aimOffsetIndex];
        net.minecraft.util.math.Vec3d aimPoint = target.getPos().add(0, target.getHeight() * heightFrac, 0);
        double dx = aimPoint.x - eye.x;
        double dy = aimPoint.y - eye.y;
        double dz = aimPoint.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f);
        player.setPitch((float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI))));
    }

    private boolean isCrosshairOnTarget(MinecraftClient client, Entity target) {
        if (client.crosshairTarget == null) return false;
        if (client.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            Entity hit = ((EntityHitResult) client.crosshairTarget).getEntity();
            return hit == target || hit.getId() == target.getId();
        }
        return false;
    }

    private void simulateSendOutKey(MinecraftClient client) {
        KeyBinding sendOut = findSendOutKey(client);
        if (sendOut != null) {
            InputUtil.Key key = InputUtil.fromTranslationKey(sendOut.getBoundKeyTranslationKey());
            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);
            pendingRelease = key;
        } else {
            AutoQiqiClient.logDebug("Battle", "Could not find Cobblemon send-out keybinding");
        }
    }

    private void pressPartyUpKey(MinecraftClient client) {
        KeyBinding partyUp = findPartyUpKey(client);
        if (partyUp != null) {
            InputUtil.Key key = InputUtil.fromTranslationKey(partyUp.getBoundKeyTranslationKey());
            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);
            KeyBinding.setKeyPressed(key, false);
            AutoQiqiClient.logDebug("Battle", "Pressed party-up key (" + partyResetPressesLeft + " remaining)");
        } else {
            AutoQiqiClient.logDebug("Battle", "Could not find Cobblemon party-up keybinding");
            partyResetPressesLeft = 0;
        }
    }

    private KeyBinding findPartyUpKey(MinecraftClient client) {
        if (partyUpSearchDone) return cachedPartyUpKey;

        for (KeyBinding kb : client.options.allKeys) {
            String translationKey = kb.getTranslationKey().toLowerCase();
            String category = kb.getCategory().toLowerCase();

            if (category.contains("cobblemon") || translationKey.contains("cobblemon")) {
                if (translationKey.contains("up") && (translationKey.contains("part") || translationKey.contains("select"))) {
                    cachedPartyUpKey = kb;
                    AutoQiqiClient.logDebug("Battle", "Found party-up key: " + kb.getTranslationKey()
                            + " bound to " + kb.getBoundKeyTranslationKey());
                    break;
                }
            }
        }

        if (cachedPartyUpKey == null) {
            for (KeyBinding kb : client.options.allKeys) {
                if (kb.getBoundKeyTranslationKey().equals("key.keyboard.up")) {
                    String cat = kb.getCategory().toLowerCase();
                    if (cat.contains("cobblemon") || kb.getTranslationKey().toLowerCase().contains("cobblemon")) {
                        cachedPartyUpKey = kb;
                        AutoQiqiClient.logDebug("Battle", "Found party-up key (by Up arrow): " + kb.getTranslationKey());
                        break;
                    }
                }
            }
        }

        partyUpSearchDone = true;
        if (cachedPartyUpKey == null) {
            AutoQiqiClient.logDebug("Battle", "WARNING: Could not find Cobblemon party-up keybinding. "
                    + "Dumping Cobblemon keys:");
            for (KeyBinding kb : client.options.allKeys) {
                if (kb.getCategory().toLowerCase().contains("cobblemon")
                        || kb.getTranslationKey().toLowerCase().contains("cobblemon")) {
                    AutoQiqiClient.logDebug("Battle", "  " + kb.getTranslationKey()
                            + " -> " + kb.getBoundKeyTranslationKey());
                }
            }
        }
        return cachedPartyUpKey;
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


    /**
     * Find the best target. In ROAMING mode, priority is:
     * 1. Bosses (will be killed — cannot be captured)
     * 2. Uncaught non-boss Pokemon (will be captured)
     * 3. Whitelisted Pokemon (will be killed)
     * In BERSERK mode, all wild Pokemon are targeted (highest level nearby preferred).
     */
    private boolean isOtherPlayerNearby(MinecraftClient client, Entity pokemon) {
        if (client.world == null) return false;
        Box area = pokemon.getBoundingBox().expand(OTHER_PLAYER_PROXIMITY);
        for (Entity e : client.world.getOtherEntities(pokemon, area)) {
            if (e instanceof OtherClientPlayerEntity) return true;
        }
        return false;
    }

    private Entity findTarget(MinecraftClient client, ClientPlayerEntity player) {
        if (ChatMessageHandler.get().isEntityClearancePending()) return null;

        double scanRange = (mode == BattleMode.BERSERK)
                ? AutoQiqiConfig.get().berserkScanRange
                : SCAN_RANGE;
        Box box = player.getBoundingBox().expand(scanRange);
        boolean roaming = (mode == BattleMode.ROAMING);
        java.util.List<String> whitelist = AutoQiqiConfig.get().battleTargetWhitelist;

        engageBlacklist.entrySet().removeIf(entry -> entry.getValue() <= globalTickCounter);

        java.util.List<Entity> candidates = client.world.getOtherEntities(player, box, e -> {
            if (!(e instanceof PokemonEntity pe)) return false;
            if (!e.isAlive() || e.isRemoved()) return false;
            if (engageBlacklist.containsKey(e.getId())) return false;
            try {
                var pokemon = pe.getPokemon();
                if (pokemon.isPlayerOwned()) return false;
                if (pokemon.getOwnerUUID() != null) return false;
                if (pe.isBusy()) return false;
                if (pe.getOwner() != null) return false;
                return true;
            } catch (Exception ex) {
                return false;
            }
        });

        // Deprioritize Pokémon with another player standing next to them
        candidates.removeIf(e -> {
            if (isOtherPlayerNearby(client, e)) {
                AutoQiqiClient.logDebug("Battle", "Skipping " + PokemonScanner.getPokemonName(e)
                        + " — another player nearby");
                return true;
            }
            return false;
        });

        // Do not auto-engage the Pokemon the user chose to walk to (manual engagement)
        PokemonWalker walker = PokemonWalker.get();
        if (walker.isActive() && walker.getTarget() != null) {
            final int walkTargetId = walker.getTarget().getId();
            candidates.removeIf(e -> {
                if (e.getId() == walkTargetId) {
                    AutoQiqiClient.logDebug("Battle", "Skipping " + PokemonScanner.getPokemonName(e)
                            + " — user is walking to this Pokemon (manual engagement)");
                    return true;
                }
                return false;
            });
        }
        if (walker.isInManualWalkGracePeriod()) {
            final int lastId = walker.getLastWalkTargetEntityId();
            candidates.removeIf(e -> {
                if (e.getId() == lastId) {
                    AutoQiqiClient.logDebug("Battle", "Skipping " + PokemonScanner.getPokemonName(e)
                            + " — just arrived (manual engagement grace)");
                    return true;
                }
                return false;
            });
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.comparingDouble(e -> e.distanceTo(player)));

        if (roaming) {
            return findRoamingTarget(candidates, whitelist);
        }

        // BERSERK: highest level within LEVEL_PREFER_RADIUS of nearest
        double nearestDist = candidates.get(0).distanceTo(player);
        Entity best = null;
        int bestLevel = -1;
        for (Entity e : candidates) {
            if (e.distanceTo(player) > nearestDist + LEVEL_PREFER_RADIUS) break;
            try {
                int level = ((PokemonEntity) e).getPokemon().getLevel();
                if (level > bestLevel) {
                    bestLevel = level;
                    best = e;
                }
            } catch (Exception ex) {
                if (best == null) best = e;
            }
        }
        return best;
    }

    /**
     * ROAMING (LEG_ONLY, engage-only): pick the nearest legendary (uncaught preferred).
     * Only legendaries are engaged — bosses and non-legendaries are ignored.
     * In-battle decisions (fight / switch / capture) are left to the user.
     */
    private Entity findRoamingTarget(java.util.List<Entity> sortedCandidates, java.util.List<String> whitelist) {
        Entity bestUncaughtLegendary = null;
        Entity bestCaughtLegendary = null;
        int uncaughtLegCount = 0, caughtLegCount = 0;

        for (Entity e : sortedCandidates) {
            boolean legendary = PokemonScanner.isLegendary(e);
            boolean boss = PokemonScanner.isBoss(e);
            if (!legendary || boss) continue;

            boolean uncaught = !PokemonScanner.isSpeciesCaught(e);
            if (uncaught) {
                uncaughtLegCount++;
                if (bestUncaughtLegendary == null) bestUncaughtLegendary = e;
            } else {
                caughtLegCount++;
                if (bestCaughtLegendary == null) bestCaughtLegendary = e;
            }
        }

        AutoQiqiClient.logDebug("Battle", "Roaming scan [LEG_ONLY, engage-only]: " + sortedCandidates.size() + " candidates"
                + " (uncaughtLeg=" + uncaughtLegCount + " caughtLeg=" + caughtLegCount + ")");

        if (bestUncaughtLegendary != null) {
            AutoQiqiClient.logDebug("Battle", "Roaming: -> uncaught legendary (engage) " + PokemonScanner.getDisplayInfo(bestUncaughtLegendary));
            return bestUncaughtLegendary;
        }
        if (bestCaughtLegendary != null) {
            AutoQiqiClient.logDebug("Battle", "Roaming: -> caught legendary (engage) " + PokemonScanner.getDisplayInfo(bestCaughtLegendary));
            return bestCaughtLegendary;
        }

        return null;
    }

    private boolean isWhitelisted(Entity entity, java.util.List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) return false;
        String name = PokemonScanner.getPokemonName(entity).toLowerCase();
        for (String entry : whitelist) {
            if (name.contains(entry.toLowerCase())) return true;
        }
        return false;
    }

    private void walkToward(MinecraftClient client, ClientPlayerEntity player, Entity target) {
        if (!walking) {
            String name = PokemonScanner.getPokemonName(target);
            AutoQiqiClient.logDebug("Battle", "MOVE: walking toward " + name + " (dist=" + String.format("%.1f", player.distanceTo(target)) + ")");
        }
        walking = true;
        MovementHelper.lookAtEntity(player, target, WALK_YAW_SPEED, WALK_PITCH_SPEED);

        double dist = player.distanceTo(target);
        client.options.forwardKey.setPressed(true);

        if (player.isTouchingWater()) {
            client.options.jumpKey.setPressed(true);
            client.options.sprintKey.setPressed(false);
        } else {
            client.options.sprintKey.setPressed(dist > 8.0);
            client.options.jumpKey.setPressed(player.horizontalCollision);
        }
    }

    private void stopWalking() {
        if (walking) {
            AutoQiqiClient.logDebug("Battle", "MOVE: stopped walking");
        }
        walking = false;
        MovementHelper.releaseMovementKeys(MinecraftClient.getInstance());
    }
}
