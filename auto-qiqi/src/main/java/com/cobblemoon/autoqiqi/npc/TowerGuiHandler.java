package com.cobblemoon.autoqiqi.npc;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.HumanDelay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Handles the tower director NPC dialog.
 * When the dialog opens (after interacting with "Directeur de la tour"),
 * clicks the first option "Accès à l'étage n 1" (first slot on the left).
 */
public class TowerGuiHandler {
    private static final TowerGuiHandler INSTANCE = new TowerGuiHandler();

    /** Title keywords that indicate the tower dialog */
    private static final String[] TOWER_TITLE_KEYWORDS = {
            "directeur", "tour", "étage", "etage", "accès", "acces"
    };

    /** Preferred option text (first option) */
    private static final String PREFERRED_OPTION = "Accès à l'étage";
    private static final String PREFERRED_OPTION_ALT = "étage";

    private HandledScreen<?> activeScreen = null;
    private int ticksSinceOpened = 0;
    private boolean processed = false;
    private boolean itemsDetected = false;
    private int itemsDetectedTick = 0;
    private boolean expectingTowerDialog = false;
    private Integer pendingClickSlot = null;
    private int clickAtTick = 0;

    private static final int TICKS_AFTER_ITEMS = 4;
    private static final int TICKS_TIMEOUT = 120;

    private TowerGuiHandler() {}

    public static TowerGuiHandler get() {
        return INSTANCE;
    }

    /** Called when we've just interacted with the tower NPC - expect dialog soon */
    public void setExpectingTowerDialog(boolean expecting) {
        expectingTowerDialog = expecting;
    }

    public void onScreenOpened(HandledScreen<?> screen) {
        String title = screen.getTitle().getString();
        boolean isTower = isTowerDialog(title) || expectingTowerDialog;

        if (isTower) {
            activeScreen = screen;
            ticksSinceOpened = 0;
            processed = false;
            itemsDetected = false;
            itemsDetectedTick = 0;
            pendingClickSlot = null;
            AutoQiqiClient.log("Tower", "GUI opened (tower dialog): '" + title + "'");
        }
    }

    public void onScreenTick(HandledScreen<?> screen) {
        if (screen != activeScreen || processed) return;

        ticksSinceOpened++;

        if (!itemsDetected && hasNonEmptySlots(screen)) {
            itemsDetected = true;
            itemsDetectedTick = ticksSinceOpened;
        }

        if (itemsDetected && pendingClickSlot == null
                && (ticksSinceOpened - itemsDetectedTick) >= TICKS_AFTER_ITEMS) {
            scheduleFirstOptionClick(screen);
        }

        if (pendingClickSlot != null && ticksSinceOpened >= clickAtTick) {
            int slot = pendingClickSlot;
            pendingClickSlot = null;
            processed = true;
            performClick(screen, slot);
        }

        if (ticksSinceOpened >= TICKS_TIMEOUT && !processed) {
            processed = true;
            pendingClickSlot = null;
            expectingTowerDialog = false;
            AutoQiqiClient.log("Tower", "GUI timeout");
        }
    }

    public void onScreenClosed() {
        if (activeScreen != null) {
            expectingTowerDialog = false;
        }
        activeScreen = null;
    }

    private boolean isTowerDialog(String title) {
        String lower = title.toLowerCase();
        for (String kw : TOWER_TITLE_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private boolean hasNonEmptySlots(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        int guiSlots = getGuiSlotCount(handler);
        for (int i = 0; i < guiSlots; i++) {
            if (!handler.slots.get(i).getStack().isEmpty()) return true;
        }
        return false;
    }

    private int getGuiSlotCount(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler containerHandler) {
            return containerHandler.getRows() * 9;
        }
        return Math.max(handler.slots.size() - 36, 0);
    }

    private void scheduleFirstOptionClick(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        int guiSlotCount = getGuiSlotCount(handler);

        int slotToClick = -1;
        for (int i = 0; i < guiSlotCount; i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String name = stack.getName().getString();
            if (name.contains(PREFERRED_OPTION) || name.contains(PREFERRED_OPTION_ALT)) {
                slotToClick = i;
                AutoQiqiClient.log("Tower", "Found option slot " + i + ": '" + name + "'");
                break;
            }
            if (slotToClick < 0) {
                slotToClick = i;
            }
        }

        if (slotToClick >= 0) {
            pendingClickSlot = slotToClick;
            clickAtTick = ticksSinceOpened + HumanDelay.guiClickTicks();
            AutoQiqiClient.log("Tower", "Scheduled click on slot " + slotToClick + " at tick " + clickAtTick);
        } else {
            processed = true;
            AutoQiqiClient.log("Tower", "No option slot found");
        }
    }

    private void performClick(HandledScreen<?> screen, int slotIndex) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null || client.player == null) {
            AutoQiqiClient.log("Tower", "performClick: no interactionManager or player");
            client.setScreen(null);
            return;
        }

        int syncId = screen.getScreenHandler().syncId;
        AutoQiqiClient.log("Tower", "Clicking slot " + slotIndex + " (Accès à l'étage n 1)");
        client.interactionManager.clickSlot(syncId, slotIndex, 0, SlotActionType.PICKUP, client.player);
        client.setScreen(null);
        expectingTowerDialog = false;

        // Schedule auto-engage with floor combat NPC after teleport
        TowerNpcEngine.get().scheduleFloorCombatEngage();
    }
}
