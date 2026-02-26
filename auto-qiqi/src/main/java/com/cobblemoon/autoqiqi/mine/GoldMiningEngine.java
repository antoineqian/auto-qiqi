package com.cobblemoon.autoqiqi.mine;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.battle.AutoBattleEngine;
import com.cobblemoon.autoqiqi.battle.CaptureEngine;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import com.cobblemoon.autoqiqi.legendary.PokemonWalker;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Lowest-priority idle activity: mines Nether Gold Ore when in the Nether
 * and no higher-priority engine (battle, capture, legendary, fishing) is busy.
 *
 * Automatically finds a netherite/diamond pickaxe in the inventory,
 * switches to it, checks durability, and sends /repair when needed.
 *
 * State machine: IDLE -> WALKING -> MINING -> PICKUP_WAIT -> IDLE
 */
public class GoldMiningEngine {
    private static final GoldMiningEngine INSTANCE = new GoldMiningEngine();

    public enum State { IDLE, WALKING, MINING, PICKUP_WAIT }

    private State state = State.IDLE;
    private BlockPos targetOre = null;
    private int scanCooldown = 0;
    private int miningTicks = 0;
    private int pickupWaitTicks = 0;
    private int sessionOresMined = 0;
    private int stuckTicks = 0;

    private int previousSlot = -1;
    private int pickaxeSlot = -1;
    private boolean repairPending = false;

    private static final int SCAN_RADIUS = 16;
    private static final int SCAN_INTERVAL = 40;
    private static final int SCAN_INTERVAL_NO_PICKAXE = 200;
    private static final int SCAN_INTERVAL_AFTER_REPAIR = 60;
    private static final int SCAN_INTERVAL_REPAIR_ON_CD = 400;
    private static final double MINE_REACH = 4.3;
    private static final int MAX_MINING_TICKS = 300;
    private static final int PICKUP_WAIT_DURATION = 15;
    private static final int WALK_STUCK_TIMEOUT = 200;
    private static final float YAW_SPEED = 12.0f;
    private static final float PITCH_SPEED = 10.0f;

    private GoldMiningEngine() {}

    public static GoldMiningEngine get() { return INSTANCE; }
    public State getState() { return state; }
    public int getSessionOresMined() { return sessionOresMined; }
    public boolean isActive() { return state != State.IDLE; }
    public BlockPos getTargetOre() { return targetOre; }

    public boolean isInNether() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world != null && client.world.getRegistryKey() == World.NETHER;
    }

    public boolean canRun() {
        if (!AutoQiqiConfig.get().goldMiningEnabled) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return false;
        if (!isInNether()) return false;
        if (client.currentScreen != null) return false;
        if (client.player.isDead()) return false;

        if (CaptureEngine.get().isActive()) return false;
        if (PokemonWalker.get().isActive()) return false;
        if (AutoBattleEngine.get().getTarget() != null) return false;

        return true;
    }

    public void tick() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (!canRun()) {
            if (state != State.IDLE) pause(client);
            return;
        }

        switch (state) {
            case IDLE -> tickIdle(client);
            case WALKING -> tickWalking(client);
            case MINING -> tickMining(client);
            case PICKUP_WAIT -> tickPickup(client);
        }
    }

    // ========================
    // Pickaxe & durability
    // ========================

    /**
     * Searches the hotbar (preferred) then main inventory for a netherite or diamond pickaxe.
     * If found in main inventory, swaps it into the hotbar automatically.
     * @return hotbar slot (0-8) of the pickaxe, or -1 if none found.
     */
    private int findPickaxe(ClientPlayerEntity player) {
        int bestHotbar = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.NETHERITE_PICKAXE) {
                return i;
            }
            if (stack.getItem() == Items.DIAMOND_PICKAXE && bestHotbar == -1) {
                bestHotbar = i;
            }
        }
        if (bestHotbar >= 0) return bestHotbar;

        // Not in hotbar — scan main inventory (PlayerInventory slots 9-35)
        int bestMain = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.NETHERITE_PICKAXE) {
                bestMain = i;
                break;
            }
            if (stack.getItem() == Items.DIAMOND_PICKAXE && bestMain == -1) {
                bestMain = i;
            }
        }
        if (bestMain == -1) return -1;

        // Swap into hotbar
        int targetHotbar = findEmptyHotbarSlot(player);
        if (targetHotbar == -1) targetHotbar = player.getInventory().selectedSlot;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager != null) {
            // In PlayerScreenHandler: slots 9-35 map directly, SWAP button = hotbar index 0-8
            client.interactionManager.clickSlot(
                    player.playerScreenHandler.syncId,
                    bestMain,
                    targetHotbar,
                    SlotActionType.SWAP,
                    player);
            AutoQiqiClient.log("Mine", "Swapped pickaxe from inventory slot " + bestMain
                    + " to hotbar slot " + targetHotbar);
        }
        return targetHotbar;
    }

    private int findEmptyHotbarSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    /** Returns remaining durability as a percentage (0-100). */
    private float getDurabilityPercent(ClientPlayerEntity player, int slot) {
        ItemStack stack = player.getInventory().getStack(slot);
        if (stack.isEmpty() || stack.getMaxDamage() == 0) return 0;
        return (float) (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage() * 100f;
    }

    private String getPickaxeName(ClientPlayerEntity player, int slot) {
        ItemStack stack = player.getInventory().getStack(slot);
        if (stack.getItem() == Items.NETHERITE_PICKAXE) return "Netherite";
        if (stack.getItem() == Items.DIAMOND_PICKAXE) return "Diamond";
        return "?";
    }

    private boolean isRepairOnCooldown() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        long elapsed = System.currentTimeMillis() - config.goldMiningLastRepairTimeMs;
        return elapsed < config.goldMiningRepairCooldownMs;
    }

    private void sendRepair(ClientPlayerEntity player, int slot) {
        if (previousSlot == -1) previousSlot = player.getInventory().selectedSlot;
        player.getInventory().selectedSlot = slot;

        if (AutoQiqiClient.isConnected(MinecraftClient.getInstance())) {
            try {
                player.networkHandler.sendChatCommand("repair");
            } catch (Exception e) {
                AutoQiqiClient.log("Mine", "repair send failed (network?): " + e.getMessage());
            }
        }
        AutoQiqiConfig config = AutoQiqiConfig.get();
        config.goldMiningLastRepairTimeMs = System.currentTimeMillis();
        AutoQiqiConfig.save();
        repairPending = true;
        AutoQiqiClient.log("Mine", "Sent /repair for pickaxe in slot " + slot);
    }

    private void switchToPickaxe(ClientPlayerEntity player, int slot) {
        if (previousSlot == -1) {
            previousSlot = player.getInventory().selectedSlot;
        }
        if (player.getInventory().selectedSlot != slot) {
            player.getInventory().selectedSlot = slot;
        }
    }

    private void restorePreviousSlot(ClientPlayerEntity player) {
        if (previousSlot >= 0 && previousSlot < 9) {
            player.getInventory().selectedSlot = previousSlot;
        }
        previousSlot = -1;
    }

    // ========================
    // State machine
    // ========================

    private void tickIdle(MinecraftClient client) {
        if (scanCooldown > 0) { scanCooldown--; return; }

        ClientPlayerEntity player = client.player;

        // Step 1: find a pickaxe
        int slot = findPickaxe(player);
        if (slot == -1) {
            scanCooldown = SCAN_INTERVAL_NO_PICKAXE;
            return;
        }
        pickaxeSlot = slot;

        // Step 2: check durability
        float durability = getDurabilityPercent(player, slot);
        int safetyMargin = AutoQiqiConfig.get().goldMiningDurabilitySafetyMargin;

        if (durability < safetyMargin) {
            String pickName = getPickaxeName(player, slot);
            if (repairPending) {
                // Already sent repair last cycle, still low → repair probably failed/on server CD
                repairPending = false;
                AutoQiqiClient.log("Mine", pickName + " pickaxe still at "
                        + String.format("%.0f%%", durability) + " after /repair — waiting");
                scanCooldown = SCAN_INTERVAL_REPAIR_ON_CD;
                return;
            }

            if (!isRepairOnCooldown()) {
                AutoQiqiClient.log("Mine", pickName + " pickaxe at "
                        + String.format("%.0f%%", durability) + "%, sending /repair");
                sendRepair(player, slot);
                scanCooldown = SCAN_INTERVAL_AFTER_REPAIR;
                return;
            } else {
                long remainMs = AutoQiqiConfig.get().goldMiningRepairCooldownMs
                        - (System.currentTimeMillis() - AutoQiqiConfig.get().goldMiningLastRepairTimeMs);
                AutoQiqiClient.log("Mine", pickName + " pickaxe at "
                        + String.format("%.0f%%", durability) + "%, /repair on cooldown ("
                        + (remainMs / 60_000) + "m left) — pausing mining");
                scanCooldown = SCAN_INTERVAL_REPAIR_ON_CD;
                return;
            }
        }

        repairPending = false;

        // Step 3: scan for ores
        List<BlockPos> ores = NethergoldScanner.scan(player, client.world, SCAN_RADIUS);
        scanCooldown = SCAN_INTERVAL;

        if (ores.isEmpty()) return;

        // Step 4: switch to pickaxe and start walking
        switchToPickaxe(player, slot);
        targetOre = ores.get(0);
        state = State.WALKING;
        stuckTicks = 0;
        AutoQiqiClient.log("Mine", "Found nether gold ore at " + fmt(targetOre)
                + " (" + String.format("%.1f", distanceTo(player, targetOre)) + " blocks)"
                + " — using " + getPickaxeName(player, slot) + " pickaxe");
    }

    private void tickWalking(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        if (!isNetherGoldOre(client, targetOre)) {
            AutoQiqiClient.log("Mine", "Target ore gone while walking, rescanning");
            releaseMovement(client);
            targetOre = null;
            state = State.IDLE;
            scanCooldown = 5;
            return;
        }

        double dist = distanceTo(player, targetOre);

        if (dist <= MINE_REACH) {
            releaseMovement(client);

            // Re-check durability before mining
            if (pickaxeSlot >= 0) {
                float dur = getDurabilityPercent(player, pickaxeSlot);
                if (dur < AutoQiqiConfig.get().goldMiningDurabilitySafetyMargin) {
                    AutoQiqiClient.log("Mine", "Pickaxe too damaged to mine ("
                            + String.format("%.0f%%", dur) + "), returning to IDLE");
                    targetOre = null;
                    state = State.IDLE;
                    scanCooldown = 5;
                    return;
                }
                switchToPickaxe(player, pickaxeSlot);
            }

            state = State.MINING;
            miningTicks = 0;
            AutoQiqiClient.log("Mine", "In range, mining " + fmt(targetOre));
            return;
        }

        if (player.horizontalCollision) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        if (stuckTicks > WALK_STUCK_TIMEOUT) {
            AutoQiqiClient.log("Mine", "Stuck walking to " + fmt(targetOre) + ", skipping");
            releaseMovement(client);
            targetOre = null;
            state = State.IDLE;
            scanCooldown = SCAN_INTERVAL;
            stuckTicks = 0;
            return;
        }

        Vec3d blockCenter = Vec3d.ofCenter(targetOre);
        lookAtPoint(player, blockCenter);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(dist > 8.0);
        client.options.jumpKey.setPressed(player.horizontalCollision);
    }

    private void tickMining(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        if (!isNetherGoldOre(client, targetOre)) {
            client.options.attackKey.setPressed(false);
            sessionOresMined++;
            AutoQiqiClient.log("Mine", "Ore mined! (" + sessionOresMined + " this session)");
            state = State.PICKUP_WAIT;
            pickupWaitTicks = PICKUP_WAIT_DURATION;
            return;
        }

        miningTicks++;
        if (miningTicks > MAX_MINING_TICKS) {
            AutoQiqiClient.log("Mine", "Mining timeout on " + fmt(targetOre) + ", skipping");
            client.options.attackKey.setPressed(false);
            targetOre = null;
            state = State.IDLE;
            scanCooldown = 10;
            return;
        }

        snapLookAtBlock(player, targetOre);

        if (miningTicks == 1) {
            InputUtil.Key key = InputUtil.fromTranslationKey(
                    client.options.attackKey.getBoundKeyTranslationKey());
            KeyBinding.setKeyPressed(key, true);
            KeyBinding.onKeyPressed(key);
        } else {
            client.options.attackKey.setPressed(true);
        }
    }

    private void tickPickup(MinecraftClient client) {
        pickupWaitTicks--;
        if (pickupWaitTicks <= 0) {
            targetOre = null;
            state = State.IDLE;
            scanCooldown = 5;
        }
    }

    // ========================
    // Pause / reset
    // ========================

    /**
     * Temporarily yield to a higher-priority activity.
     * Releases all keys, restores the previous hotbar slot,
     * and returns to IDLE without resetting session stats.
     */
    public void pause(MinecraftClient client) {
        if (state == State.MINING) {
            client.options.attackKey.setPressed(false);
        }
        if (state == State.WALKING) {
            releaseMovement(client);
        }
        if (client.player != null) {
            restorePreviousSlot(client.player);
        }
        state = State.IDLE;
        targetOre = null;
        miningTicks = 0;
        stuckTicks = 0;
    }

    /** Full reset including session stats. Used by /pk stop. */
    public void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (state == State.MINING) {
            client.options.attackKey.setPressed(false);
        }
        if (state == State.WALKING) {
            releaseMovement(client);
        }
        if (client.player != null) {
            restorePreviousSlot(client.player);
        }
        state = State.IDLE;
        targetOre = null;
        pickaxeSlot = -1;
        scanCooldown = 0;
        miningTicks = 0;
        pickupWaitTicks = 0;
        sessionOresMined = 0;
        stuckTicks = 0;
        repairPending = false;
    }

    // ========================
    // HUD
    // ========================

    public String getStatusDisplay() {
        if (state == State.IDLE) return null;
        MinecraftClient client = MinecraftClient.getInstance();
        switch (state) {
            case WALKING: {
                double dist = client.player != null && targetOre != null
                        ? distanceTo(client.player, targetOre) : 0;
                return "[Mine] Walking (" + String.format("%.1f", dist) + "m)";
            }
            case MINING:
                return "[Mine] Breaking... (" + String.format("%.1fs", miningTicks / 20.0) + ")";
            case PICKUP_WAIT:
                return "[Mine] Picking up...";
            default:
                return null;
        }
    }

    /** Returns a durability status string for the HUD, or null if not applicable. */
    public String getDurabilityDisplay() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || pickaxeSlot < 0) return null;
        float dur = getDurabilityPercent(client.player, pickaxeSlot);
        if (dur <= 0) return null;
        String color = dur < AutoQiqiConfig.get().goldMiningDurabilitySafetyMargin ? "§c" : "§7";
        return color + String.format("%.0f%%", dur);
    }

    // ========================
    // Helpers
    // ========================

    private void releaseMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void snapLookAtBlock(ClientPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f);
        player.setPitch((float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI))));
    }

    private void lookAtPoint(ClientPlayerEntity player, Vec3d point) {
        Vec3d eye = player.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) (-(Math.atan2(dy, hDist) * (180.0 / Math.PI)));

        player.setYaw(smoothAngle(player.getYaw(), targetYaw, YAW_SPEED));
        player.setPitch(smoothAngle(player.getPitch(), targetPitch, PITCH_SPEED));
    }

    private static float smoothAngle(float current, float target, float maxStep) {
        float diff = target - current;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        if (Math.abs(diff) <= maxStep) return target;
        return current + Math.copySign(maxStep, diff);
    }

    private boolean isNetherGoldOre(MinecraftClient client, BlockPos pos) {
        return client.world != null && client.world.getBlockState(pos).isOf(Blocks.NETHER_GOLD_ORE);
    }

    private double distanceTo(ClientPlayerEntity player, BlockPos pos) {
        return player.getPos().distanceTo(Vec3d.ofCenter(pos));
    }

    private static String fmt(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }
}
