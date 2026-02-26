package com.cobblemoon.autoqiqi.npc;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.HumanDelay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Handles the tower director NPC dialog.
 * Supports two dialog types:
 * <ul>
 *   <li><b>EasyNPC DialogScreen</b>: a button-based dialog — clicks the button matching "étage".</li>
 *   <li><b>HandledScreen (chest GUI)</b>: an inventory-based dialog — clicks the slot matching "étage".</li>
 * </ul>
 */
public class TowerGuiHandler {
    private static final TowerGuiHandler INSTANCE = new TowerGuiHandler();

    /** Keywords for floor selection dialog */
    private static final String[] FLOOR_KEYWORDS = { "étage", "etage", "accès", "acces" };
    /** Keywords for combat confirmation dialog */
    private static final String[] CONFIRM_KEYWORDS = { "bien sûr", "bien sur", "oui", "yes", "accepter", "combattre" };

    private boolean expectingTowerDialog = false;

    // EasyNPC dialog handling
    private Screen activeDialogScreen = null;
    private int dialogTicksSinceOpened = 0;
    private boolean dialogProcessed = false;
    private ButtonWidget pendingButton = null;
    private boolean pendingIsFloorSelect = false;
    private int buttonClickAtTick = 0;

    // HandledScreen (chest GUI) handling
    private HandledScreen<?> activeChestScreen = null;
    private int chestTicksSinceOpened = 0;
    private boolean chestProcessed = false;
    private boolean chestItemsDetected = false;
    private int chestItemsDetectedTick = 0;
    private Integer pendingClickSlot = null;
    private int slotClickAtTick = 0;

    private static final int TICKS_WAIT_FOR_BUTTONS = 5;
    private static final int TICKS_AFTER_ITEMS = 4;
    private static final int TICKS_TIMEOUT = 120;

    private TowerGuiHandler() {}

    public static TowerGuiHandler get() { return INSTANCE; }

    public void setExpectingTowerDialog(boolean expecting) {
        expectingTowerDialog = expecting;
    }

    public boolean isExpectingDialog() {
        return expectingTowerDialog;
    }

    /** True when we're expecting or actively processing a tower dialog. */
    public boolean isHandlingScreen() {
        return expectingTowerDialog
                || (activeDialogScreen != null && !dialogProcessed)
                || (activeChestScreen != null && !chestProcessed);
    }

    // ========================
    // Screen lifecycle — called from registerScreenEvents
    // ========================

    /** Called for ANY screen type (not just HandledScreen). */
    public void onAnyScreenOpened(Screen screen) {
        if (!expectingTowerDialog) return;

        if (screen instanceof HandledScreen<?>) return; // handled by onChestScreenOpened

        String className = screen.getClass().getName();
        String title = screen.getTitle().getString();
        AutoQiqiClient.log("Tower", "Dialog screen opened: " + className + " title='" + title + "'");

        activeDialogScreen = screen;
        dialogTicksSinceOpened = 0;
        dialogProcessed = false;
        pendingButton = null;
        TowerNpcEngine.get().clearInteractionTick();
    }

    /** Called for HandledScreen (chest GUI) type screens. */
    public void onChestScreenOpened(HandledScreen<?> screen) {
        String title = screen.getTitle().getString();
        boolean isTower = expectingTowerDialog || isTowerTitle(title);

        if (isTower) {
            activeChestScreen = screen;
            chestTicksSinceOpened = 0;
            chestProcessed = false;
            chestItemsDetected = false;
            chestItemsDetectedTick = 0;
            pendingClickSlot = null;
            TowerNpcEngine.get().clearInteractionTick();
            AutoQiqiClient.log("Tower", "Chest GUI opened (tower dialog): '" + title + "'");
        }
    }

    /** Called every tick for ANY screen when tower dialog is active. */
    public void onAnyScreenTick(Screen screen) {
        if (screen != activeDialogScreen || dialogProcessed) return;
        tickDialogScreen(screen);
    }

    /** Called every tick for HandledScreen. */
    public void onChestScreenTick(HandledScreen<?> screen) {
        if (screen != activeChestScreen || chestProcessed) return;
        tickChestScreen(screen);
    }

    public void onScreenClosed() {
        if (activeDialogScreen != null || activeChestScreen != null) {
            expectingTowerDialog = false;
        }
        activeDialogScreen = null;
        activeChestScreen = null;
    }

    // ========================
    // EasyNPC Dialog handling (button-based)
    // ========================

    private void tickDialogScreen(Screen screen) {
        dialogTicksSinceOpened++;

        if (pendingButton == null && dialogTicksSinceOpened >= TICKS_WAIT_FOR_BUTTONS) {
            scanDialogButtons(screen);
        }

        if (pendingButton != null && dialogTicksSinceOpened >= buttonClickAtTick) {
            ButtonWidget btn = pendingButton;
            pendingButton = null;
            dialogProcessed = true;
            expectingTowerDialog = false;
            clickDialogButton(btn);
        }

        if (dialogTicksSinceOpened >= TICKS_TIMEOUT && !dialogProcessed) {
            dialogProcessed = true;
            pendingButton = null;
            expectingTowerDialog = false;
            AutoQiqiClient.log("Tower", "Dialog timeout");
        }
    }

    private void scanDialogButtons(Screen screen) {
        AutoQiqiClient.log("Tower", "Scanning dialog children (" + screen.children().size() + " elements):");

        ButtonWidget bestMatch = null;
        boolean isFloorSelect = false;
        for (Element child : screen.children()) {
            if (child instanceof ClickableWidget cw) {
                String msg = cw.getMessage().getString();
                AutoQiqiClient.log("Tower", "  button: '" + msg + "' (" + cw.getClass().getSimpleName() + ")");

                    if (child instanceof ButtonWidget bw) {
                    if (matchesKeywords(msg, FLOOR_KEYWORDS)) {
                        bestMatch = bw;
                        isFloorSelect = true;
                        AutoQiqiClient.log("Tower", "  -> MATCH (floor select)");
                        break;
                    }
                    if (matchesKeywords(msg, CONFIRM_KEYWORDS)) {
                        bestMatch = bw;
                        isFloorSelect = false;
                        AutoQiqiClient.log("Tower", "  -> MATCH (combat confirm)");
                        break;
                    }
                }
            }
        }

        if (bestMatch != null) {
            pendingButton = bestMatch;
            pendingIsFloorSelect = isFloorSelect;
            buttonClickAtTick = dialogTicksSinceOpened + HumanDelay.guiClickTicks();
            AutoQiqiClient.log("Tower", "Scheduled dialog button click: '" + bestMatch.getMessage().getString()
                    + "' at tick " + buttonClickAtTick + " (floorSelect=" + isFloorSelect + ")");
        } else {
            if (dialogTicksSinceOpened < TICKS_TIMEOUT / 4) {
                AutoQiqiClient.log("Tower", "No matching button yet, will retry...");
            } else {
                dialogProcessed = true;
                expectingTowerDialog = false;
                AutoQiqiClient.log("Tower", "No matching dialog button found — wrong NPC, blacklisting");
                MinecraftClient.getInstance().setScreen(null);
                TowerNpcEngine.get().onDialogMismatch();
            }
        }
    }

    private void clickDialogButton(ButtonWidget button) {
        String msg = button.getMessage().getString();
        boolean floorSelect = pendingIsFloorSelect;
        AutoQiqiClient.log("Tower", "Clicking dialog button: '" + msg + "' (floorSelect=" + floorSelect + ")");
        button.onPress();
        MinecraftClient.getInstance().setScreen(null);

        if (floorSelect) {
            TowerNpcEngine.get().scheduleFloorCombatEngage();
        }
        // Combat confirm: battle starts directly, no further action needed
    }

    // ========================
    // Chest GUI handling (inventory slots)
    // ========================

    private void tickChestScreen(HandledScreen<?> screen) {
        chestTicksSinceOpened++;

        if (!chestItemsDetected && hasNonEmptySlots(screen)) {
            chestItemsDetected = true;
            chestItemsDetectedTick = chestTicksSinceOpened;
        }

        if (chestItemsDetected && pendingClickSlot == null
                && (chestTicksSinceOpened - chestItemsDetectedTick) >= TICKS_AFTER_ITEMS) {
            scanChestSlots(screen);
        }

        if (pendingClickSlot != null && chestTicksSinceOpened >= slotClickAtTick) {
            int slot = pendingClickSlot;
            pendingClickSlot = null;
            chestProcessed = true;
            clickChestSlot(screen, slot);
        }

        if (chestTicksSinceOpened >= TICKS_TIMEOUT && !chestProcessed) {
            chestProcessed = true;
            pendingClickSlot = null;
            expectingTowerDialog = false;
            AutoQiqiClient.log("Tower", "Chest GUI timeout — blacklisting NPC");
            MinecraftClient.getInstance().setScreen(null);
            TowerNpcEngine.get().onDialogMismatch();
        }
    }

    private void scanChestSlots(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        int guiSlotCount = getGuiSlotCount(handler);

        AutoQiqiClient.log("Tower", "Chest GUI slots (" + guiSlotCount + " gui, " + handler.slots.size() + " total):");
        int slotToClick = -1;
        for (int i = 0; i < guiSlotCount; i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String name = stack.getName().getString();
            AutoQiqiClient.log("Tower", "  slot[" + i + "] = '" + name + "'");

            if (matchesKeywords(name, FLOOR_KEYWORDS) || matchesKeywords(name, CONFIRM_KEYWORDS)) {
                slotToClick = i;
                AutoQiqiClient.log("Tower", "  -> MATCH (tower option)");
                break;
            }
        }

        if (slotToClick >= 0) {
            pendingClickSlot = slotToClick;
            slotClickAtTick = chestTicksSinceOpened + HumanDelay.guiClickTicks();
            AutoQiqiClient.log("Tower", "Scheduled chest click on slot " + slotToClick + " at tick " + slotClickAtTick);
        } else {
            chestProcessed = true;
            expectingTowerDialog = false;
            AutoQiqiClient.log("Tower", "No matching slot in chest GUI — wrong NPC, blacklisting");
            MinecraftClient.getInstance().setScreen(null);
            TowerNpcEngine.get().onDialogMismatch();
        }
    }

    private void clickChestSlot(HandledScreen<?> screen, int slotIndex) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null || client.player == null) {
            AutoQiqiClient.log("Tower", "clickChestSlot: no interactionManager or player");
            client.setScreen(null);
            return;
        }

        int syncId = screen.getScreenHandler().syncId;
        AutoQiqiClient.log("Tower", "Clicking chest slot " + slotIndex);
        client.interactionManager.clickSlot(syncId, slotIndex, 0, SlotActionType.PICKUP, client.player);
        client.setScreen(null);
        expectingTowerDialog = false;
        TowerNpcEngine.get().scheduleFloorCombatEngage();
    }

    // ========================
    // Helpers
    // ========================

    private boolean matchesKeywords(String text, String[] keywords) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    private boolean isTowerTitle(String title) {
        String lower = title.toLowerCase();
        return lower.contains("directeur") || lower.contains("tour")
                || lower.contains("étage") || lower.contains("etage");
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
}
