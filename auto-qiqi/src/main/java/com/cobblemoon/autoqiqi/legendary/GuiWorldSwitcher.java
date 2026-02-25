package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.HumanDelay;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles world switching through the server's /monde inventory GUI.
 */
public class GuiWorldSwitcher {
    private static final GuiWorldSwitcher INSTANCE = new GuiWorldSwitcher();

    private final Map<String, Integer> worldSlotMap = new HashMap<>();
    private String pendingWorldSwitch = null;
    private boolean waitingForGui = false;
    private HandledScreen<?> activeScreen = null;
    private int ticksSinceScreenOpened = 0;
    private boolean screenProcessed = false;
    private boolean itemsDetected = false;
    private int itemsDetectedTick = 0;
    private String pendingClickWorld = null;
    private int clickAtTick = 0;
    private boolean expectingSubMenu = false;
    private String subMenuWorldName = null;
    private int pendingSubMenuSlot = -1;

    private static final int TICKS_AFTER_ITEMS_DETECTED = 4;
    private static final int TICKS_TIMEOUT = 200;

    private GuiWorldSwitcher() {}

    public static GuiWorldSwitcher get() { return INSTANCE; }

    public void onScreenOpened(HandledScreen<?> screen) {
        String title = screen.getTitle().getString();
        ScreenHandler handler = screen.getScreenHandler();
        AutoQiqiClient.log("Legendary", "GUI opened: '" + title + "' slots=" + handler.slots.size());

        boolean shouldTrack = waitingForGui || expectingSubMenu;
        if (!shouldTrack && worldSlotMap.isEmpty()) {
            String lowerTitle = title.toLowerCase();
            shouldTrack = lowerTitle.contains("monde") || lowerTitle.contains("world");
        }

        if (shouldTrack) {
            activeScreen = screen;
            ticksSinceScreenOpened = 0;
            screenProcessed = false;
            itemsDetected = false;
            itemsDetectedTick = 0;
            pendingSubMenuSlot = -1;
        }
    }

    public void onScreenTick(HandledScreen<?> screen) {
        if (screen != activeScreen || screenProcessed) return;

        ticksSinceScreenOpened++;

        if (!itemsDetected) {
            if (hasNonEmptyGuiSlots(screen)) {
                itemsDetected = true;
                itemsDetectedTick = ticksSinceScreenOpened;
            }
        }

        if (itemsDetected && pendingClickWorld == null && pendingSubMenuSlot < 0
                && (ticksSinceScreenOpened - itemsDetectedTick) >= TICKS_AFTER_ITEMS_DETECTED) {
            processScreen(screen);
        }

        if (pendingClickWorld != null && ticksSinceScreenOpened >= clickAtTick) {
            String target = pendingClickWorld;
            pendingClickWorld = null;
            screenProcessed = true;
            performClick(screen, target);
        }

        if (pendingSubMenuSlot >= 0 && ticksSinceScreenOpened >= clickAtTick) {
            int slot = pendingSubMenuSlot;
            pendingSubMenuSlot = -1;
            screenProcessed = true;
            performSubMenuClick(screen, slot);
        }

        if (ticksSinceScreenOpened >= TICKS_TIMEOUT && !screenProcessed) {
            screenProcessed = true;
            pendingClickWorld = null;
            pendingSubMenuSlot = -1;
            expectingSubMenu = false;
            subMenuWorldName = null;
            cancelPending();
            AutoSwitchEngine.get().onGuiTimeout();
        }
    }

    public void onScreenClosed() {
        activeScreen = null;
    }

    private boolean hasNonEmptyGuiSlots(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        int guiSlotCount = getGuiSlotCount(handler);
        for (int i = 0; i < guiSlotCount; i++) {
            if (!handler.slots.get(i).getStack().isEmpty()) return true;
        }
        return false;
    }

    private int getGuiSlotCount(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler containerHandler) {
            return containerHandler.getRows() * 9;
        }
        int guiSlots = handler.slots.size() - 36;
        return Math.max(guiSlots, 0);
    }

    private void processScreen(HandledScreen<?> screen) {
        if (expectingSubMenu) {
            AutoQiqiClient.log("GUI", "processScreen: handling sub-menu for " + subMenuWorldName);
            handleSubMenu(screen);
            return;
        }

        int prevCount = worldSlotMap.size();
        readAndMapItems(screen);
        int newMappings = worldSlotMap.size() - prevCount;

        if (!waitingForGui) {
            AutoQiqiClient.log("GUI", "processScreen: not waiting for GUI, newMappings=" + newMappings + " learned=" + hasLearnedWorlds());
            screenProcessed = true;
            if (newMappings > 0 && hasLearnedWorlds()) {
                AutoSwitchEngine.get().onWorldsLearned();
            }
            return;
        }

        if (pendingWorldSwitch != null) {
            if (worldSlotMap.containsKey(pendingWorldSwitch.toLowerCase())) {
                AutoQiqiClient.log("GUI", "processScreen: found slot for '" + pendingWorldSwitch + "', scheduling click");
                pendingClickWorld = pendingWorldSwitch;
                pendingWorldSwitch = null;
                waitingForGui = false;
                int delay = HumanDelay.guiClickTicks();
                clickAtTick = ticksSinceScreenOpened + delay;
            } else {
                AutoQiqiClient.log("GUI", "processScreen: '" + pendingWorldSwitch + "' NOT found in slot map! Available: " + worldSlotMap.keySet());
                screenProcessed = true;
                pendingWorldSwitch = null;
                waitingForGui = false;
                MinecraftClient.getInstance().setScreen(null);
                AutoSwitchEngine.get().onGuiTimeout();
            }
        } else {
            AutoQiqiClient.log("GUI", "processScreen: learning mode, newMappings=" + newMappings + " learned=" + hasLearnedWorlds());
            screenProcessed = true;
            waitingForGui = false;
            if (newMappings > 0 || hasLearnedWorlds()) {
                MinecraftClient.getInstance().setScreen(null);
                AutoSwitchEngine.get().onWorldsLearned();
            } else {
                MinecraftClient.getInstance().setScreen(null);
                AutoSwitchEngine.get().onGuiTimeout();
            }
        }
    }

    private void handleSubMenu(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        int guiSlotCount = getGuiSlotCount(handler);
        int firstNonEmpty = -1;
        for (int i = 0; i < guiSlotCount; i++) {
            if (!handler.slots.get(i).getStack().isEmpty() && firstNonEmpty < 0) {
                firstNonEmpty = i;
            }
        }

        if (firstNonEmpty >= 0) {
            pendingSubMenuSlot = firstNonEmpty;
            clickAtTick = ticksSinceScreenOpened + HumanDelay.guiClickTicks();
        } else {
            screenProcessed = true;
            expectingSubMenu = false;
            subMenuWorldName = null;
            MinecraftClient.getInstance().setScreen(null);
            AutoSwitchEngine.get().onGuiTimeout();
        }
    }

    private void performSubMenuClick(HandledScreen<?> screen, int slotIndex) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null || client.player == null) {
            expectingSubMenu = false;
            subMenuWorldName = null;
            client.setScreen(null);
            AutoSwitchEngine.get().onGuiTimeout();
            return;
        }

        int syncId = screen.getScreenHandler().syncId;
        client.interactionManager.clickSlot(syncId, slotIndex, 0, SlotActionType.PICKUP, client.player);
        client.setScreen(null);

        WorldTracker.get().setCurrentWorld(subMenuWorldName);
        expectingSubMenu = false;
        subMenuWorldName = null;
        AutoSwitchEngine.get().onWorldSwitchComplete();
    }

    private void readAndMapItems(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        AutoQiqiConfig config = AutoQiqiConfig.get();
        int guiSlotCount = getGuiSlotCount(handler);

        int matchedCount = 0;
        for (int i = 0; i < guiSlotCount; i++) {
            Slot slot = handler.slots.get(i);
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;

            String itemName = stack.getName().getString();
            boolean matched = false;
            for (String worldName : config.worldNames) {
                if (config.isHomeWorld(worldName)) continue;
                if (worldNameMatches(itemName, worldName)) {
                    worldSlotMap.put(worldName.toLowerCase(), i);
                    AutoQiqiClient.log("GUI", "Mapped slot " + i + " '" + itemName + "' -> " + worldName);
                    matchedCount++;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                AutoQiqiClient.log("GUI", "Slot " + i + " '" + itemName + "' -> no world match");
            }
        }
        int homeSkipped = 0;
        for (String worldName : config.worldNames) {
            if (config.isHomeWorld(worldName)) homeSkipped++;
        }
        AutoQiqiClient.log("GUI", "readAndMapItems: " + matchedCount + " matched, "
                + homeSkipped + " home-worlds skipped, total mapped=" + worldSlotMap.size());
    }

    private boolean worldNameMatches(String itemName, String worldName) {
        String lowerItem = itemName.toLowerCase().trim();
        String lowerWorld = worldName.toLowerCase().trim();

        if (lowerItem.contains(lowerWorld)) return true;
        if (lowerWorld.contains(lowerItem) && lowerItem.length() > 2) return true;

        String itemClean = lowerItem.replaceAll("[^a-z0-9]", "");
        String worldClean = lowerWorld.replaceAll("[^a-z0-9]", "");
        if (!itemClean.isEmpty() && !worldClean.isEmpty()) {
            if (itemClean.contains(worldClean)) return true;
            if (worldClean.contains(itemClean) && itemClean.length() > 2) return true;
        }

        Matcher worldMatcher = Pattern.compile("([a-z]+)(\\d+)").matcher(worldClean);
        if (worldMatcher.matches()) {
            String base = worldMatcher.group(1);
            String num = worldMatcher.group(2);
            if (lowerItem.contains(base) && lowerItem.contains(num)) return true;
        }
        return false;
    }

    private void performClick(HandledScreen<?> screen, String worldName) {
        Integer slotIndex = worldSlotMap.get(worldName.toLowerCase());
        if (slotIndex == null) {
            AutoQiqiClient.log("GUI", "performClick: no slot for '" + worldName + "', aborting");
            MinecraftClient.getInstance().setScreen(null);
            AutoSwitchEngine.get().onGuiTimeout();
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.interactionManager == null || client.player == null) {
            AutoQiqiClient.log("GUI", "performClick: no interactionManager or player, aborting");
            client.setScreen(null);
            AutoSwitchEngine.get().onGuiTimeout();
            return;
        }

        int syncId = screen.getScreenHandler().syncId;
        AutoQiqiConfig config = AutoQiqiConfig.get();
        String mode = config.getTeleportMode(worldName);
        int button = "last".equalsIgnoreCase(mode) ? 1 : 0;

        AutoQiqiClient.log("GUI", "performClick: slot=" + slotIndex + " world='" + worldName
                + "' mode=" + mode + " button=" + button + " (syncId=" + syncId + ")");
        client.interactionManager.clickSlot(syncId, slotIndex, button, SlotActionType.PICKUP, client.player);
        client.setScreen(null);

        if (hasSubMenu(worldName)) {
            AutoQiqiClient.log("GUI", "performClick: expecting sub-menu for " + worldName);
            expectingSubMenu = true;
            subMenuWorldName = worldName;
            waitingForGui = true;
        } else {
            WorldTracker.get().setCurrentWorld(worldName);
            AutoSwitchEngine.get().onWorldSwitchComplete();
        }
    }

    private boolean hasSubMenu(String worldName) {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        String lower = worldName.toLowerCase();
        for (String sub : config.worldsWithSubMenu) {
            if (lower.contains(sub.toLowerCase()) || sub.toLowerCase().contains(lower)) return true;
        }
        return false;
    }

    public void requestSwitch(String worldName) {
        AutoQiqiClient.log("GUI", "requestSwitch: " + worldName);
        this.pendingWorldSwitch = worldName;
    }
    public void requestLearning() {
        AutoQiqiClient.log("GUI", "requestLearning");
        this.pendingWorldSwitch = null;
    }
    public void onMondeCommandSent() { this.waitingForGui = true; }

    public boolean hasLearnedWorlds() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        if (config.worldNames.isEmpty()) return false;
        for (String world : config.worldNames) {
            if (config.isHomeWorld(world)) continue;
            if (!worldSlotMap.containsKey(world.toLowerCase())) return false;
        }
        return true;
    }

    public int getLearnedWorldCount() { return worldSlotMap.size(); }

    public int getGuiWorldCount() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        int count = 0;
        for (String world : config.worldNames) {
            if (!config.isHomeWorld(world)) count++;
        }
        return count;
    }
    public boolean isWaitingForGui() { return waitingForGui; }

    public void cancelPending() {
        pendingWorldSwitch = null;
        pendingClickWorld = null;
        pendingSubMenuSlot = -1;
        expectingSubMenu = false;
        subMenuWorldName = null;
        waitingForGui = false;
    }

    public void reset() {
        worldSlotMap.clear();
        cancelPending();
        activeScreen = null;
        screenProcessed = true;
    }
}
