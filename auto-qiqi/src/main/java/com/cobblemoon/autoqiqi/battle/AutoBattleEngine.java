package com.cobblemoon.autoqiqi.battle;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.common.PokemonScanner;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.fish.AutofishEngine;
import com.cobblemoon.autoqiqi.legendary.AutoSwitchEngine;
import com.cobblemoon.autoqiqi.legendary.ChatMessageHandler;
import com.cobblemoon.autoqiqi.legendary.PokemonWalker;

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
 * Scans for nearby wild Pokemon, walks toward the closest one,
 * aims at it, and simulates Cobblemon's send-out key to start a battle.
 * Supports {@link BattleMode#BERSERK} (all wild) and
 * {@link BattleMode#ROAMING} (auto-capture uncaught, kill bosses + whitelist).
 */
public class AutoBattleEngine {
    private static final AutoBattleEngine INSTANCE = new AutoBattleEngine();

    private static final double SCAN_RANGE = 24.0;
    private static final double ENGAGE_RANGE = 6.0;
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
    private boolean targetForCapture = false;
    private int scanTimer = 0;
    private int cooldown = 0;
    private boolean wasInBattle = false;
    private boolean walking = false;
    private int battleCount = 0;
    private boolean pausedFishingForBattle = false;

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

    // Grace period after mode switch (prevents intermediate modes from starting battles while user cycles K)
    private int modeSwitchGraceTicks = 0;
    private static final int MODE_SWITCH_GRACE = 15; // ~0.75s

    // Session stats (reset when mode is toggled ON from OFF)
    private int sessionBossKills = 0;
    private int sessionPokemonKills = 0;
    private final java.util.List<String> sessionCaptures = new java.util.ArrayList<>();
    private boolean lastFightWasBoss = false;
    private String lastTargetName = "Unknown";

    private AutoBattleEngine() {}

    public static AutoBattleEngine get() { return INSTANCE; }
    public Entity getTarget() { return target; }
    public boolean isWalking() { return walking; }
    public boolean isTargetForCapture() { return targetForCapture; }
    public BattleMode getMode() { return mode; }
    public void setMode(BattleMode mode) {
        if (this.mode != mode) {
            AutoQiqiClient.log("Battle", "Mode changed: " + this.mode + " -> " + mode);
            modeSwitchGraceTicks = MODE_SWITCH_GRACE;
            if (mode != BattleMode.OFF && this.mode == BattleMode.OFF) {
                sessionBossKills = 0;
                sessionPokemonKills = 0;
                sessionCaptures.clear();
                battleCount = 0;
            }
            if (mode == BattleMode.OFF) {
                if (CaptureEngine.get().isActive()) {
                    CaptureEngine.get().stop();
                    AutoQiqiClient.log("Battle", "CaptureEngine stopped (mode -> OFF)");
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
     * Used by AutoSwitchEngine to avoid switching worlds when a boss is present.
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
            AutoQiqiClient.log("Battle", "clearTarget: releasing " + PokemonScanner.getDisplayInfo(target));
        }
        if (walking) stopWalking();
        target = null;
        targetForCapture = false;
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
        targetForCapture = false;
        cooldown = 0;
        wasInBattle = false;
        losStrafeTicks = 0;
        aimTicks = 0;
        keySimulated = false;
        aimOffsetIndex = 0;
        partyResetPressesLeft = 0;
        pausedFishingForBattle = false;
        lastFightWasBoss = false;
        collectingLoot = false;
        lootTarget = null;
        lootTicks = 0;
        lastEngagedEntityId = -1;
        engageBlacklist.clear();
    }

    public void forceTarget(Entity entity, boolean forCapture) {
        this.target = entity;
        this.targetForCapture = forCapture;
        this.aimTicks = 0;
        this.keySimulated = false;
        this.losStrafeTicks = 0;
        this.cooldown = 0;
        AutoQiqiClient.log("Battle", "Force target set: " + PokemonScanner.getDisplayInfo(entity)
                + " (forCapture=" + forCapture + ")");
    }

    public void recordCapture(String pokemonName) {
        sessionCaptures.add(pokemonName);
        AutoQiqiClient.log("Stats", "Capture recorded: " + pokemonName + " (session total: " + sessionCaptures.size() + ")");
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
            AutoQiqiClient.log("Battle", "Loot picked up (#" + lootCollected + ")");
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
            AutoQiqiClient.log("Battle", "Walking to loot: "
                    + getLootItemName(lootTarget) + " dist="
                    + String.format("%.1f", player.distanceTo(lootTarget)));
        }

        lootTicks++;
        if (lootTicks > LOOT_MAX_TICKS) {
            AutoQiqiClient.log("Battle", "Loot pickup timeout, skipping");
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
            AutoQiqiClient.log("Battle", "Loot collection done: " + lootCollected + " items");
        }
        cooldown = POST_BATTLE_COOLDOWN;
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
        if (sessionBossKills == 0 && sessionPokemonKills == 0 && sessionCaptures.isEmpty()) {
            return null;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("§e=== Session Summary ===");
        if (!sessionCaptures.isEmpty()) {
            lines.add("§aPokemon captures: §f" + sessionCaptures.size());
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
        sessionBossKills = 0;
        sessionPokemonKills = 0;
        sessionCaptures.clear();
        return lines;
    }

    public java.util.List<String> getSessionSummaryLines() {
        if (sessionBossKills == 0 && sessionPokemonKills == 0 && sessionCaptures.isEmpty()) {
            return null;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (!sessionCaptures.isEmpty()) {
            lines.add("§aCaptures: §f" + sessionCaptures.size());
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
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;

        if (player == null || client.world == null || client.interactionManager == null) return;
        if (AutoSwitchEngine.get().isWaitingForHomeTeleport()) return;
        if (player.isDead()) {
            if (walking) stopWalking();
            return;
        }

        globalTickCounter++;

        if (modeSwitchGraceTicks > 0) {
            modeSwitchGraceTicks--;
            return;
        }

        if (client.currentScreen != null) {
            if (walking) stopWalking();
            if (collectingLoot) {
                client.options.forwardKey.setPressed(false);
                client.options.sprintKey.setPressed(false);
            }
            String screenName = client.currentScreen.getClass().getName().toLowerCase();
            if (screenName.contains("battle")) {
                wasInBattle = true;
                collectingLoot = false;
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
                AutoQiqiClient.log("Battle", "Boss killed (session total: " + sessionBossKills + ")");
                com.cobblemoon.autoqiqi.common.SessionLogger.get().logKill(lastTargetName, true);
            } else {
                sessionPokemonKills++;
                com.cobblemoon.autoqiqi.common.SessionLogger.get().logKill(lastTargetName, false);
            }
            lastFightWasBoss = false;
            AutoQiqiClient.log("Battle", mode.displayName() + " battle finished (total: " + battleCount + ")");

            int healEvery = AutoQiqiConfig.get().battleHealEveryN;
            if (healEvery > 0 && battleCount % healEvery == 0 && AutoQiqiClient.isConnected(MinecraftClient.getInstance())) {
                try {
                    player.networkHandler.sendChatCommand("pokeheal");
                    AutoQiqiClient.log("Battle", "Sent /pokeheal (after " + battleCount + " battles)");
                } catch (Exception e) {
                    AutoQiqiClient.log("Battle", "pokeheal failed (network?): " + e.getMessage());
                }
            }

            if (pausedFishingForBattle) {
                pausedFishingForBattle = false;
                AutofishEngine fish = AutofishEngine.get();
                if (fish != null) {
                    fish.resumeAfterBossBattle();
                }
            }

            if (mode != BattleMode.BERSERK) {
                int upPresses = AutoQiqiConfig.get().postBattlePartyUpPresses;
                if (upPresses > 0) {
                    partyResetPressesLeft = upPresses;
                    AutoQiqiClient.log("Battle", "Will press party-up " + upPresses + " time(s) to reset lead Pokemon");
                }
            }

            target = null;
            targetForCapture = false;
            aimTicks = 0;
            keySimulated = false;

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
                    AutoQiqiClient.log("Battle", "Boss engage attempt " + bossEngageRetries + "/" + MAX_BOSS_ENGAGE_RETRIES
                            + " failed — retrying (entity #" + lastEngagedEntityId + ")");
                    lastEngagedEntityId = -1;
                    scanTimer = 0;
                } else {
                    engageBlacklist.put(lastEngagedEntityId, globalTickCounter + BLACKLIST_DURATION_TICKS);
                    AutoQiqiClient.log("Battle", "Engage failed (no battle started) — blacklisting entity #"
                            + lastEngagedEntityId + " for 30s"
                            + (lastFightWasBoss ? " (boss, " + bossEngageRetries + " retries exhausted)" : ""));
                    lastEngagedEntityId = -1;
                    bossEngageRetries = 0;
                }
            }
            return;
        }

        if (mode == BattleMode.TRAINER) return;

        if (target != null && (!target.isAlive() || target.isRemoved())) {
            target = null;
            targetForCapture = false;
            aimTicks = 0;
            keySimulated = false;
            if (walking) stopWalking();
        }

        if (target == null) {
            if (scanTimer > 0) { scanTimer--; return; }
            target = findTarget(client, player);
            scanTimer = SCAN_INTERVAL;
            if (target == null) return;

            AutofishEngine fish = AutofishEngine.get();
            if (fish != null && AutoQiqiConfig.get().fishEnabled && fish.isHoldingFishingRod()) {
                fish.pauseForBossBattle();
                pausedFishingForBattle = true;
                AutoQiqiClient.log("Battle", "Paused fishing to engage " + PokemonScanner.getDisplayInfo(target));
            }

            String action = targetForCapture ? "capture" : "fight";
            lastTargetName = PokemonScanner.getDisplayInfo(target);
            AutoQiqiClient.log("Battle", "Target acquired: " + lastTargetName
                    + " (action=" + action + ")");
        }

        if (pendingRelease != null) {
            KeyBinding.setKeyPressed(pendingRelease, false);
            pendingRelease = null;
        }

        double dist = player.distanceTo(target);

        if (dist <= ENGAGE_RANGE) {
            if (walking) stopWalking();

            if (targetForCapture) {
                MovementHelper.stopStrafe(client);
                losStrafeTicks = 0;
                engageForCapture(client, player);
            } else {
                boolean hasLOS = MovementHelper.hasLineOfSight(player, target);

                if (!hasLOS && losStrafeTicks < 120) {
                    losStrafeTicks++;
                    if (losStrafeTicks == 1) {
                        AutoQiqiClient.log("Battle", "No LOS to target, strafing");
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
                        logCrosshairInfo(client);
                        lastFightWasBoss = PokemonScanner.isBoss(target);
                        lastEngagedEntityId = target.getId();
                        simulateSendOutKey(client);
                        keySimulated = true;
                        keySimulatedAtTick = aimTicks;
                    }

                    if (keySimulated && aimTicks >= keySimulatedAtTick + AIM_TICKS_AFTER_PRESS) {
                        cooldown = ENGAGE_COOLDOWN;
                        target = null;
                        targetForCapture = false;
                        aimTicks = 0;
                        keySimulated = false;
                        aimOffsetIndex = 0;
                    }
                }
            }
        } else {
            aimTicks = 0;
            keySimulated = false;
            losStrafeTicks = 0;
            walkToward(client, player, target);
        }
    }

    private void engageForCapture(MinecraftClient client, ClientPlayerEntity player) {
        String name = PokemonScanner.getPokemonName(target);
        int level = PokemonScanner.getPokemonLevel(target);
        boolean legendary = PokemonScanner.isLegendary(target);

        if (CaptureEngine.isRecentlyFailed(name)) {
            AutoQiqiClient.log("Battle", "Skipping " + name + " - recently failed capture (cooldown)");
            player.sendMessage(Text.literal("§6[Roaming]§r §c" + name + " a echappe recemment, attente avant reessai."), false);
            target = null;
            targetForCapture = false;
            aimTicks = 0;
            keySimulated = false;
            return;
        }

        AutoQiqiClient.log("Battle", "Handing off to CaptureEngine: " + name + " Lv." + level);
        player.sendMessage(Text.literal(
                "§6[Roaming]§r §bCapture auto: §e" + name + " Lv." + level
                        + (legendary ? " §d[LEG]" : "")), false);

        CaptureEngine.get().start(name, level, legendary, target);
        PokemonWalker.get().startWalking(target);

        target = null;
        targetForCapture = false;
        aimTicks = 0;
        keySimulated = false;
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
            AutoQiqiClient.log("Battle", "Could not find Cobblemon send-out keybinding");
        }
    }

    private void pressPartyUpKey(MinecraftClient client) {
        KeyBinding partyUp = findPartyUpKey(client);
        if (partyUp != null) {
            InputUtil.Key key = InputUtil.fromTranslationKey(partyUp.getBoundKeyTranslationKey());
            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);
            KeyBinding.setKeyPressed(key, false);
            AutoQiqiClient.log("Battle", "Pressed party-up key (" + partyResetPressesLeft + " remaining)");
        } else {
            AutoQiqiClient.log("Battle", "Could not find Cobblemon party-up keybinding");
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
                    AutoQiqiClient.log("Battle", "Found party-up key: " + kb.getTranslationKey()
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
                        AutoQiqiClient.log("Battle", "Found party-up key (by Up arrow): " + kb.getTranslationKey());
                        break;
                    }
                }
            }
        }

        partyUpSearchDone = true;
        if (cachedPartyUpKey == null) {
            AutoQiqiClient.log("Battle", "WARNING: Could not find Cobblemon party-up keybinding. "
                    + "Dumping Cobblemon keys:");
            for (KeyBinding kb : client.options.allKeys) {
                if (kb.getCategory().toLowerCase().contains("cobblemon")
                        || kb.getTranslationKey().toLowerCase().contains("cobblemon")) {
                    AutoQiqiClient.log("Battle", "  " + kb.getTranslationKey()
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

    private void logCrosshairInfo(MinecraftClient client) {
        HitResult hit = client.crosshairTarget;
        if (hit == null) {
            AutoQiqiClient.log("Battle", "Crosshair: null");
        } else if (hit.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) hit;
            Entity e = entityHit.getEntity();
            AutoQiqiClient.log("Battle", "Crosshair: ENTITY " + e.getClass().getSimpleName()
                    + " (isTarget=" + (e == target) + ")");
        } else {
            AutoQiqiClient.log("Battle", "Crosshair: " + hit.getType());
        }
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

        Box box = player.getBoundingBox().expand(SCAN_RANGE);
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
                AutoQiqiClient.log("Battle", "Skipping " + PokemonScanner.getPokemonName(e)
                        + " — another player nearby");
                return true;
            }
            return false;
        });

        if (candidates.isEmpty()) return null;

        if (battleCount == 0 || candidates.size() > 0 && scanTimer == SCAN_INTERVAL) {
            AutoQiqiClient.log("Battle", "Scan: " + candidates.size() + " wild pokemon in range (mode=" + mode + ")");
        }

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
        targetForCapture = false;
        return best;
    }

    /**
     * ROAMING priority: boss (kill) > uncaught non-boss (capture) > uncaught legendary (capture) > caught legendary (kill) > whitelisted (kill).
     * Bosses are ALWAYS targeted for kill (they cannot be captured in Cobblemon).
     * Non-boss uncaught Pokemon are targeted for capture when canCapture is true.
     * Uncaught legendaries are always targeted for capture (even when canCapture is false) so wild legendaries are never skipped.
     */
    private Entity findRoamingTarget(java.util.List<Entity> sortedCandidates, java.util.List<String> whitelist) {
        boolean canCapture = AutoQiqiClient.canRoamCapture();
        Entity bestUncaught = null;
        Entity bestUncaughtLegendary = null;
        Entity bestBoss = null;
        Entity bestWhitelisted = null;
        Entity bestCaughtLegendary = null;
        int uncaughtCount = 0, bossCount = 0, whitelistedCount = 0, caughtCount = 0, caughtLegCount = 0;

        for (Entity e : sortedCandidates) {
            boolean uncaught = !PokemonScanner.isSpeciesCaught(e);
            boolean boss = PokemonScanner.isBoss(e);
            boolean whitelisted = isWhitelisted(e, whitelist);
            boolean legendary = PokemonScanner.isLegendary(e);

            if (uncaught) uncaughtCount++;
            else caughtCount++;
            if (boss) bossCount++;
            if (whitelisted) whitelistedCount++;

            if (boss && bestBoss == null) {
                bestBoss = e;
            }
            if (canCapture && uncaught && !boss && bestUncaught == null) {
                bestUncaught = e;
            }
            if (uncaught && !boss && legendary && bestUncaughtLegendary == null) {
                bestUncaughtLegendary = e;
            }
            if (!uncaught && legendary && bestCaughtLegendary == null) {
                caughtLegCount++;
                bestCaughtLegendary = e;
            }
            if (whitelisted && bestWhitelisted == null && !uncaught) {
                bestWhitelisted = e;
            }
        }

        if (uncaughtCount > 0 && !canCapture) {
            AutoQiqiClient.log("Battle", "Roaming: " + uncaughtCount + " uncaught but canCapture=false — skipping (won't kill uncaught)");
        }
        AutoQiqiClient.log("Battle", "Roaming scan: " + sortedCandidates.size() + " candidates"
                + " (uncaught=" + uncaughtCount + " caught=" + caughtCount
                + " boss=" + bossCount + " whitelisted=" + whitelistedCount
                + " caughtLeg=" + caughtLegCount
                + " canCapture=" + canCapture + ")");

        if (bestBoss != null) {
            targetForCapture = false;
            AutoQiqiClient.log("Battle", "Roaming: -> boss (kill) " + PokemonScanner.getDisplayInfo(bestBoss));
            return bestBoss;
        }
        if (bestUncaught != null) {
            targetForCapture = true;
            AutoQiqiClient.log("Battle", "Roaming: -> uncaught (capture) " + PokemonScanner.getDisplayInfo(bestUncaught));
            return bestUncaught;
        }
        if (bestUncaughtLegendary != null) {
            targetForCapture = true;
            AutoQiqiClient.log("Battle", "Roaming: -> uncaught legendary (capture) " + PokemonScanner.getDisplayInfo(bestUncaughtLegendary));
            return bestUncaughtLegendary;
        }
        if (bestCaughtLegendary != null) {
            targetForCapture = false;
            AutoQiqiClient.log("Battle", "Roaming: -> caught legendary (kill) " + PokemonScanner.getDisplayInfo(bestCaughtLegendary));
            return bestCaughtLegendary;
        }
        if (bestWhitelisted != null) {
            targetForCapture = false;
            AutoQiqiClient.log("Battle", "Roaming: -> whitelisted " + PokemonScanner.getDisplayInfo(bestWhitelisted));
            return bestWhitelisted;
        }

        targetForCapture = false;
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
        walking = false;
        MovementHelper.releaseMovementKeys(MinecraftClient.getInstance());
    }
}
