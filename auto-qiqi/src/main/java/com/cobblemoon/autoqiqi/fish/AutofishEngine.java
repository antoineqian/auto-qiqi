package com.cobblemoon.autoqiqi.fish;

import com.cobblemoon.autoqiqi.common.MovementHelper;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.fish.monitor.FishMonitorMP;
import com.cobblemoon.autoqiqi.fish.monitor.FishMonitorMPMotion;
import com.cobblemoon.autoqiqi.fish.monitor.FishMonitorMPSound;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutofishEngine {
    private static AutofishEngine INSTANCE;

    private final MinecraftClient client;
    private final AutofishScheduler scheduler;
    private FishMonitorMP fishMonitorMP;

    private boolean hookExists = false;
    private long hookRemovedAt = 0L;
    private long timeMillis = 0L;

    // Fish-battle integration: Cobblemon Poké Rods trigger a battle on reel-in
    private boolean fishBattlePending = false;
    private long fishBattlePendingAt = 0L;
    private boolean fishBattleActive = false;
    private int fishBattleCount = 0;
    private static final long FISH_BATTLE_TIMEOUT_MS = 5000;

    // Auto-aim: find water and rotate toward it before casting
    private Vec3d aimTarget = null;
    private long aimStartTime = 0L;
    private static final long AIM_TIMEOUT_MS = 4000;
    private static final float AIM_TOLERANCE_DEG = 2.0f;
    private static final float AIM_YAW_SPEED = 6.0f;
    private static final float AIM_PITCH_SPEED = 4.0f;

    public AutofishEngine(AutofishScheduler scheduler) {
        INSTANCE = this;
        this.client = MinecraftClient.getInstance();
        this.scheduler = scheduler;
        setDetection();

        scheduler.scheduleRepeatingAction(10000, () -> {
            if (!AutoQiqiConfig.get().fishPersistentMode) return;
            if (!isHoldingFishingRod()) return;
            if (hookExists) return;
            if (scheduler.isRecastQueued()) return;
            if (aimTarget != null) return;
            if (isAboutToBreak(getHeldItem())) {
                tryRepairIfNeeded();
                if (AutoQiqiConfig.get().fishNoBreak) return;
            }
            if (AutoQiqiConfig.get().fishAutoAim) {
                startAutoFish();
            } else {
                useRod();
            }
        });
    }

    public static AutofishEngine get() { return INSTANCE; }
    public long getTimeMillis() { return timeMillis; }

    /** True when a fish-triggered battle should be auto-fought by the battle mixins. */
    public boolean isFishBattleActive() { return fishBattleActive; }

    /** Called by AutoQiqiClient when a battle screen opens while a fish battle is pending. */
    public void onBattleStarted() {
        if (fishBattlePending) {
            fishBattlePending = false;
            fishBattleActive = true;
            aimTarget = null;
            com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish", "Fish battle started — auto-fighting");
        }
    }

    /** Called by AutoQiqiClient when a battle screen closes while a fish battle was active. */
    public void onBattleEnded() {
        if (fishBattleActive) {
            fishBattleActive = false;
            fishBattleCount++;
            com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish", "Fish battle ended (total: " + fishBattleCount + ") — recast queued");

            int healEvery = AutoQiqiConfig.get().battleHealEveryN;
            if (healEvery > 0 && fishBattleCount % healEvery == 0 && client.player != null && client.player.networkHandler != null) {
                client.player.networkHandler.sendChatCommand("pokeheal");
                com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish", "Sent /pokeheal (after " + fishBattleCount + " fish battles)");
            }

            queueRecast();
        }
    }

    /** Called by AutoBattleEngine when it detects a boss while fishing — reels in the rod. */
    public void pauseForBossBattle() {
        if (client.player != null && client.player.fishHook != null) {
            useRod();
            com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish", "Reeled in rod for boss battle");
        }
    }

    /** Called by AutoBattleEngine after a boss battle ends while fishing was paused. */
    public void resumeAfterBossBattle() {
        com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish", "Boss battle done — recasting rod");
        queueRecast();
    }

    public void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) return;

        timeMillis = Util.getMeasuringTimeMs();
        AutoQiqiConfig config = AutoQiqiConfig.get();

        // Fish battle timeout (always process)
        if (config.fishEnabled) {
            if (fishBattlePending && (timeMillis - fishBattlePendingAt) > FISH_BATTLE_TIMEOUT_MS) {
                fishBattlePending = false;
            }
            if (fishBattleActive) return;
        }

        // Auto-aim toward water (works even if fishEnabled is off, for programmatic callers)
        if (aimTarget != null) {
            if (!isHoldingFishingRod()) {
                aimTarget = null;
                return;
            }
            MovementHelper.lookAtPoint(client.player, aimTarget, AIM_YAW_SPEED, AIM_PITCH_SPEED);
            if (isAimedAt(client.player, aimTarget)) {
                com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish", "Aimed at water — casting!");
                aimTarget = null;
                useRod();
            } else if (timeMillis - aimStartTime > AIM_TIMEOUT_MS) {
                com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish", "Auto-aim timeout");
                aimTarget = null;
            }
            return;
        }

        if (!config.fishEnabled) return;

        // Normal hook management
        if (isHoldingFishingRod()) {
            if (client.player.fishHook != null) {
                hookExists = true;
                if (shouldUseMPDetection()) {
                    fishMonitorMP.hookTick(this, client, client.player.fishHook);
                }
            } else {
                removeHook();
            }
        } else {
            removeHook();
        }
    }

    public void tickFishingLogic(Entity owner, int ticksCatchable) {
        client.execute(() -> {
            if (AutoQiqiConfig.get().fishEnabled && !shouldUseMPDetection()) {
                if (client.player != null && client.player.fishHook != null) {
                    if (ticksCatchable > 0 && owner.getUuid().compareTo(client.player.getUuid()) == 0) {
                        catchFish();
                    }
                }
            }
        });
    }

    public void handlePacket(Object packet) {
        if (AutoQiqiConfig.get().fishEnabled) {
            if (shouldUseMPDetection()) {
                fishMonitorMP.handlePacket(this, packet, client);
            }
        }
    }

    public void handleChat(GameMessageS2CPacket packet) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (config.fishEnabled) {
            if (!client.isInSingleplayer()) {
                if (isHoldingFishingRod()) {
                    if (hookExists || (timeMillis - hookRemovedAt < 2000)) {
                        String regex = config.fishClearLagRegex;
                        if (regex == null || regex.replaceAll("\\s", "").isEmpty()) return;
                        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(packet.content().getString());
                        if (matcher.find()) {
                            queueRecast();
                        }
                    }
                }
            }
        }
    }

    public void catchFish() {
        if (!scheduler.isRecastQueued()) {
            boolean cobblemonRod = isCobblemonRod(getHeldItem().getItem());
            queueRodSwitch();
            if (!cobblemonRod) {
                queueRecast();
            } else {
                // Cobblemon Poké Rods trigger a battle on reel-in;
                // recast will be queued after the battle ends instead.
                fishBattlePending = true;
                fishBattlePendingAt = Util.getMeasuringTimeMs();
            }
            useRod();
        }
    }

    public void queueRecast() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        scheduler.scheduleAction(ActionType.RECAST, config.fishRecastDelay, () -> {
            if (hookExists) return;
            if (!isHoldingFishingRod()) return;
            if (isAboutToBreak(getHeldItem())) {
                if (tryRepairIfNeeded()) {
                    // Repair sent — rod should be restored, proceed to cast
                } else if (config.fishNoBreak) {
                    return;
                }
            }
            useRod();
        });
    }

    private void queueRodSwitch() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        scheduler.scheduleAction(ActionType.ROD_SWITCH, config.fishRecastDelay - 250, () -> {
            if (!config.fishMultiRod) return;
            switchToFirstRod(client.player);
        });
    }

    private void removeHook() {
        if (hookExists) {
            hookExists = false;
            hookRemovedAt = timeMillis;
            fishMonitorMP.handleHookRemoved();
        }
    }

    public void switchToFirstRod(ClientPlayerEntity player) {
        if (player != null) {
            AutoQiqiConfig config = AutoQiqiConfig.get();
            PlayerInventory inventory = player.getInventory();
            for (int i = 0; i < inventory.main.size(); i++) {
                ItemStack slot = inventory.main.get(i);
                if (isItemFishingRod(slot.getItem())) {
                    if (i < 9) {
                        if (config.fishNoBreak) {
                            if (!isAboutToBreak(slot)) {
                                inventory.selectedSlot = i;
                                return;
                            }
                        } else {
                            inventory.selectedSlot = i;
                            return;
                        }
                    }
                }
            }
        }
    }

    public void useRod() {
        if (client.player != null && client.world != null) {
            Hand hand = getCorrectHand();
            ActionResult actionResult = client.interactionManager.interactItem(client.player, hand);
            if (actionResult.isAccepted()) {
                if (actionResult.shouldSwingHand()) {
                    client.player.swingHand(hand);
                }
            }
        }
    }

    public boolean isHoldingFishingRod() {
        if (client.player == null) return false;
        return isItemFishingRod(getHeldItem().getItem());
    }

    private Hand getCorrectHand() {
        if (client.player == null) return Hand.MAIN_HAND;
        if (!AutoQiqiConfig.get().fishMultiRod) {
            if (isItemFishingRod(client.player.getOffHandStack().getItem())) return Hand.OFF_HAND;
        }
        return Hand.MAIN_HAND;
    }

    private ItemStack getHeldItem() {
        if (client.player == null) return ItemStack.EMPTY;
        if (!AutoQiqiConfig.get().fishMultiRod) {
            if (isItemFishingRod(client.player.getOffHandStack().getItem()))
                return client.player.getOffHandStack();
        }
        return client.player.getMainHandStack();
    }

    private boolean isItemFishingRod(Item item) {
        if (item == Items.FISHING_ROD || item instanceof FishingRodItem) return true;
        return isCobblemonRod(item);
    }

    private boolean isAboutToBreak(ItemStack stack) {
        int maxDmg = stack.getMaxDamage();
        if (maxDmg <= 0) return false;
        int remaining = maxDmg - stack.getDamage();
        return remaining <= AutoQiqiConfig.get().fishDurabilitySafetyMargin;
    }

    /**
     * Attempts /repair if the held rod is low on durability and the cooldown has elapsed.
     * @return true if repair was sent (caller should proceed normally),
     *         false if repair was needed but on cooldown (caller should switch/stop)
     */
    private boolean tryRepairIfNeeded() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (!config.fishAutoRepair) return false;
        if (client.player == null || client.player.networkHandler == null) return false;

        ItemStack held = getHeldItem();
        if (!isAboutToBreak(held)) return false;

        long now = Util.getMeasuringTimeMs();
        long elapsed = now - config.lastRepairTimeMs;
        if (elapsed >= config.fishRepairCooldownMs) {
            client.player.networkHandler.sendChatCommand("repair");
            config.lastRepairTimeMs = now;
            AutoQiqiConfig.save();
            int remaining = held.getMaxDamage() - held.getDamage();
            com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish",
                    "Rod low (" + remaining + " uses left) — sent /repair");
            return true;
        }

        long remainingCooldown = config.fishRepairCooldownMs - elapsed;
        long mins = remainingCooldown / 60_000;
        com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish",
                "Rod low but /repair on cooldown (" + mins + "min left)");
        return false;
    }

    private boolean isCobblemonRod(Item item) {
        Identifier id = net.minecraft.registry.Registries.ITEM.getId(item);
        return id != null && id.getNamespace().equals("cobblemon") && id.getPath().contains("rod");
    }

    public void setDetection() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (config.fishUseSoundDetection) {
            fishMonitorMP = new FishMonitorMPSound();
        } else {
            fishMonitorMP = new FishMonitorMPMotion();
        }
    }

    private boolean shouldUseMPDetection() {
        if (AutoQiqiConfig.get().fishForceMPDetection) return true;
        return !client.isInSingleplayer();
    }

    // ========================
    // Auto-aim water detection
    // ========================

    /**
     * Find nearby water, aim at it, and cast when aimed.
     * Can be called programmatically (e.g. by the legendary system during idle time).
     * @return true if a suitable water spot was found and aiming has started
     */
    public boolean startAutoFish() {
        if (client.player == null || client.world == null) return false;
        if (aimTarget != null) return true;
        if (hookExists) return false;

        Vec3d spot = WaterScanner.findFishingSpot(client.player, client.world);
        if (spot != null) {
            aimTarget = spot;
            aimStartTime = timeMillis;
            com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish",
                    String.format("Water found at (%.0f, %.0f, %.0f) — aiming...", spot.x, spot.y, spot.z));
            return true;
        }
        com.cobblemoon.autoqiqi.AutoQiqiClient.log("Fish", "No suitable water found nearby");
        return false;
    }

    /** Cancel any ongoing auto-aim. */
    public void stopAutoFish() {
        aimTarget = null;
    }

    /** True if the engine is currently rotating the player toward water. */
    public boolean isAutoAiming() {
        return aimTarget != null;
    }

    private boolean isAimedAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI)));

        float yawDiff = Math.abs(MathHelper.wrapDegrees(player.getYaw() - targetYaw));
        float pitchDiff = Math.abs(player.getPitch() - targetPitch);

        return yawDiff < AIM_TOLERANCE_DEG && pitchDiff < AIM_TOLERANCE_DEG;
    }
}
