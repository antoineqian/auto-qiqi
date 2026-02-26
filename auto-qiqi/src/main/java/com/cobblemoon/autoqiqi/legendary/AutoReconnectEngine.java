package com.cobblemoon.autoqiqi.legendary;

import com.cobblemoon.autoqiqi.AutoQiqiClient;
import com.cobblemoon.autoqiqi.common.SessionLogger;
import com.cobblemoon.autoqiqi.config.AutoQiqiConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Detects disconnected/logged-out state and automatically navigates
 * the reconnect flow: DisconnectedScreen -> TitleScreen -> "Rejoindre"
 * -> lobby right-click -> GUI item click -> spawn.
 */
public class AutoReconnectEngine {
    private static final AutoReconnectEngine INSTANCE = new AutoReconnectEngine();

    public enum Phase {
        DISABLED,
        MONITORING,
        WAIT_BEFORE_DISMISS,
        DISMISS_DISCONNECT,
        CLICK_BACK_TO_SERVER_LIST,
        CLICK_REJOINDRE,
        WAIT_CONNECT,
        WAIT_LOBBY_LOAD,
        RIGHT_CLICK_LOBBY,
        WAIT_LOBBY_GUI,
        CLICK_GUI_ITEM,
        WAIT_SPAWN,
        COOLDOWN
    }

    private Phase phase = Phase.DISABLED;
    private long phaseStartMs = 0;
    private int consecutiveFailures = 0;

    private int lobbyGuiTicksSinceOpen = 0;
    private boolean lobbyGuiItemsDetected = false;
    private int lobbyGuiItemsDetectedTick = 0;
    private HandledScreen<?> activeLobbyScreen = null;

    private static final int GUI_WAIT_TICKS = 4;
    private static final int GUI_TIMEOUT_TICKS = 200;
    private static final long PHASE_TIMEOUT_MS = 30_000;
    private static final long LOBBY_LOAD_DELAY_MS = 3_000;

    private AutoReconnectEngine() {}

    public static AutoReconnectEngine get() { return INSTANCE; }

    public Phase getPhase() { return phase; }

    public boolean isActive() { return phase != Phase.DISABLED && phase != Phase.MONITORING; }

    public void enable() {
        if (phase == Phase.DISABLED) {
            phase = Phase.MONITORING;
            consecutiveFailures = 0;
            AutoQiqiClient.log("Reconnect", "Auto-reconnect enabled, monitoring for disconnects");
            SessionLogger.get().logInfo("Auto-reconnect enabled");
        }
    }

    public void disable() {
        phase = Phase.DISABLED;
        resetLobbyGui();
        AutoQiqiClient.log("Reconnect", "Auto-reconnect disabled");
        SessionLogger.get().logInfo("Auto-reconnect disabled");
    }

    public void tick(MinecraftClient client) {
        AutoQiqiConfig config = AutoQiqiConfig.get();

        if (phase == Phase.DISABLED) {
            if (config.autoReconnectEnabled) {
                enable();
            }
            return;
        }

        if (!config.autoReconnectEnabled) {
            disable();
            return;
        }

        switch (phase) {
            case MONITORING -> tickMonitoring(client);
            case WAIT_BEFORE_DISMISS -> tickWaitBeforeDismiss(client);
            case DISMISS_DISCONNECT -> tickDismissDisconnect(client);
            case CLICK_BACK_TO_SERVER_LIST -> tickClickBackToServerList(client);
            case CLICK_REJOINDRE -> tickClickRejoindre(client);
            case WAIT_CONNECT -> tickWaitConnect(client);
            case WAIT_LOBBY_LOAD -> tickWaitLobbyLoad(client);
            case RIGHT_CLICK_LOBBY -> tickRightClickLobby(client);
            case WAIT_LOBBY_GUI -> tickWaitLobbyGui(client);
            case CLICK_GUI_ITEM -> tickClickGuiItem(client);
            case WAIT_SPAWN -> tickWaitSpawn(client);
            case COOLDOWN -> tickCooldown();
            default -> {}
        }
    }

    // --- Phase: MONITORING ---

    private void tickMonitoring(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen instanceof DisconnectedScreen) {
            AutoQiqiClient.log("Reconnect", "Disconnect detected (DisconnectedScreen), starting reconnect flow");
            SessionLogger.get().logEvent("AUTO_RECONNECT", "Disconnect detected, starting reconnect");
            transition(Phase.WAIT_BEFORE_DISMISS);
        } else if (screen instanceof TitleScreen && client.player == null) {
            AutoQiqiClient.log("Reconnect", "Title screen detected while not connected, starting reconnect flow");
            SessionLogger.get().logEvent("AUTO_RECONNECT", "Title screen detected, starting reconnect");
            transition(Phase.WAIT_BEFORE_DISMISS);
        } else if (screen != null && hasBackToServerListButton(screen)) {
            AutoQiqiClient.log("Reconnect", "Error/disconnect screen with 'Retour à la liste des serveurs' detected, clicking it");
            SessionLogger.get().logEvent("AUTO_RECONNECT", "Error screen (back to server list) detected");
            transition(Phase.CLICK_BACK_TO_SERVER_LIST);
        }
    }

    // --- Phase: WAIT_BEFORE_DISMISS ---

    private void tickWaitBeforeDismiss(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen instanceof TitleScreen) {
            AutoQiqiClient.log("Reconnect", "Title screen visible, proceeding to Rejoindre");
            transition(Phase.CLICK_REJOINDRE);
            return;
        }
        if (client.player != null && client.world != null) {
            onSuccess();
            return;
        }
        long elapsed = System.currentTimeMillis() - phaseStartMs;
        long delayMs = getReconnectDelayMs();
        if (elapsed >= delayMs) {
            if (screen instanceof DisconnectedScreen) {
                transition(Phase.DISMISS_DISCONNECT);
            } else {
                AutoQiqiClient.log("Reconnect", "Unexpected screen after delay: "
                        + (screen != null ? screen.getClass().getSimpleName() : "null"));
                transition(Phase.MONITORING);
            }
        }
    }

    // --- Phase: DISMISS_DISCONNECT ---

    private void tickDismissDisconnect(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen instanceof DisconnectedScreen) {
            if (tryClickBackToServerListButton(screen)) {
                AutoQiqiClient.log("Reconnect", "Clicked 'Retour à la liste des serveurs' on DisconnectedScreen");
                transition(Phase.WAIT_BEFORE_DISMISS);
            } else {
                AutoQiqiClient.log("Reconnect", "Dismissing DisconnectedScreen -> TitleScreen (no button found)");
                client.setScreen(new TitleScreen());
                transition(Phase.CLICK_REJOINDRE);
            }
        } else if (screen instanceof TitleScreen) {
            transition(Phase.CLICK_REJOINDRE);
        } else {
            checkTimeout(Phase.MONITORING, "dismiss disconnect");
        }
    }

    // --- Phase: CLICK_BACK_TO_SERVER_LIST ---

    private void tickClickBackToServerList(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen == null) {
            transition(Phase.MONITORING);
            return;
        }
        if (screen instanceof TitleScreen) {
            AutoQiqiClient.log("Reconnect", "Already on TitleScreen after back, proceeding to Rejoindre");
            transition(Phase.CLICK_REJOINDRE);
            return;
        }
        if (tryClickBackToServerListButton(screen)) {
            AutoQiqiClient.log("Reconnect", "Clicked 'Retour à la liste des serveurs'");
            transition(Phase.WAIT_BEFORE_DISMISS);
        } else {
            checkTimeout(Phase.MONITORING, "find back-to-server-list button");
        }
    }

    private boolean hasBackToServerListButton(Screen screen) {
        String match = AutoQiqiConfig.get().reconnectBackToServerListButtonText;
        if (match == null || match.isEmpty()) return false;
        String lower = match.toLowerCase();
        for (var child : screen.children()) {
            if (child instanceof PressableWidget widget) {
                String label = widget.getMessage().getString();
                if (label.toLowerCase().contains(lower)) return true;
            }
        }
        return false;
    }

    /** Clicks the "Retour à la liste des serveurs" (or config) button if present. Returns true if clicked. */
    private boolean tryClickBackToServerListButton(Screen screen) {
        String match = AutoQiqiConfig.get().reconnectBackToServerListButtonText;
        if (match == null || match.isEmpty()) return false;
        String lower = match.toLowerCase();
        for (var child : screen.children()) {
            if (child instanceof PressableWidget widget) {
                String label = widget.getMessage().getString();
                if (label.toLowerCase().contains(lower)) {
                    AutoQiqiClient.log("Reconnect", "Clicking button: '" + label + "'");
                    widget.onPress();
                    return true;
                }
            }
        }
        return false;
    }

    // --- Phase: CLICK_REJOINDRE ---

    private void tickClickRejoindre(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (!(screen instanceof TitleScreen)) {
            if (screen instanceof ConnectScreen) {
                AutoQiqiClient.log("Reconnect", "Already on ConnectScreen, waiting for connection");
                transition(Phase.WAIT_CONNECT);
                return;
            }
            checkTimeout(Phase.MONITORING, "wait for title screen");
            return;
        }

        AutoQiqiConfig config = AutoQiqiConfig.get();
        String buttonText = config.reconnectButtonText;

        for (var child : screen.children()) {
            if (child instanceof PressableWidget widget) {
                String label = widget.getMessage().getString();
                if (label.toLowerCase().contains(buttonText.toLowerCase())) {
                    AutoQiqiClient.log("Reconnect", "Found button '" + label + "', clicking");
                    widget.onPress();
                    transition(Phase.WAIT_CONNECT);
                    return;
                }
            }
        }

        checkTimeout(Phase.MONITORING, "find '" + buttonText + "' button on TitleScreen");
    }

    // --- Phase: WAIT_CONNECT ---

    private void tickWaitConnect(MinecraftClient client) {
        if (client.player != null && client.world != null) {
            AutoQiqiClient.log("Reconnect", "Connected to server, waiting for lobby to load");
            transition(Phase.WAIT_LOBBY_LOAD);
            return;
        }

        Screen screen = client.currentScreen;
        if (screen instanceof DisconnectedScreen) {
            AutoQiqiClient.log("Reconnect", "Connection failed (DisconnectedScreen), will retry");
            onFailure("connection failed");
            return;
        }

        long timeoutMs = AutoQiqiConfig.get().reconnectWaitConnectionSeconds * 1000L;
        long elapsed = System.currentTimeMillis() - phaseStartMs;
        if (elapsed >= timeoutMs) {
            AutoQiqiClient.log("Reconnect", "Timeout waiting for connection (" + (timeoutMs / 1000) + "s)");
            onFailure("timeout: wait for connection");
        }
    }

    // --- Phase: WAIT_LOBBY_LOAD ---

    private void tickWaitLobbyLoad(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            AutoQiqiClient.log("Reconnect", "Lost connection during lobby load");
            onFailure("disconnected during lobby load");
            return;
        }

        long elapsed = System.currentTimeMillis() - phaseStartMs;
        if (elapsed >= LOBBY_LOAD_DELAY_MS) {
            AutoQiqiClient.log("Reconnect", "Lobby load delay elapsed, sending right-click");
            transition(Phase.RIGHT_CLICK_LOBBY);
        }
    }

    // --- Phase: RIGHT_CLICK_LOBBY ---

    private void tickRightClickLobby(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            onFailure("no player/interaction during lobby right-click");
            return;
        }

        if (client.currentScreen != null) {
            if (client.currentScreen instanceof HandledScreen<?>) {
                AutoQiqiClient.log("Reconnect", "GUI already open, proceeding to click item");
                activeLobbyScreen = (HandledScreen<?>) client.currentScreen;
                lobbyGuiTicksSinceOpen = 0;
                lobbyGuiItemsDetected = false;
                transition(Phase.CLICK_GUI_ITEM);
                return;
            }
            AutoQiqiClient.log("Reconnect", "Screen open during right-click phase: "
                    + client.currentScreen.getClass().getSimpleName() + ", waiting...");
            checkTimeout(Phase.MONITORING, "right-click lobby (screen blocking)");
            return;
        }

        AutoQiqiClient.log("Reconnect", "Sending right-click in lobby");
        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        transition(Phase.WAIT_LOBBY_GUI);
    }

    // --- Phase: WAIT_LOBBY_GUI ---

    private void tickWaitLobbyGui(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen instanceof HandledScreen<?> hs) {
            AutoQiqiClient.log("Reconnect", "Lobby GUI opened: " + screen.getTitle().getString());
            activeLobbyScreen = hs;
            lobbyGuiTicksSinceOpen = 0;
            lobbyGuiItemsDetected = false;
            transition(Phase.CLICK_GUI_ITEM);
            return;
        }

        if (client.player == null) {
            onFailure("disconnected while waiting for lobby GUI");
            return;
        }

        checkTimeout(Phase.RIGHT_CLICK_LOBBY, "wait for lobby GUI to open");
    }

    // --- Phase: CLICK_GUI_ITEM ---

    private void tickClickGuiItem(MinecraftClient client) {
        if (activeLobbyScreen == null || client.currentScreen != activeLobbyScreen) {
            if (client.player != null && client.world != null && client.currentScreen == null) {
                AutoQiqiClient.log("Reconnect", "GUI closed, player in world — assuming success");
                onSuccess();
                return;
            }
            AutoQiqiClient.log("Reconnect", "Lobby GUI disappeared unexpectedly");
            onFailure("lobby GUI closed unexpectedly");
            return;
        }

        lobbyGuiTicksSinceOpen++;

        if (!lobbyGuiItemsDetected) {
            if (hasNonEmptySlots(activeLobbyScreen)) {
                lobbyGuiItemsDetected = true;
                lobbyGuiItemsDetectedTick = lobbyGuiTicksSinceOpen;
            }
        }

        if (lobbyGuiItemsDetected
                && (lobbyGuiTicksSinceOpen - lobbyGuiItemsDetectedTick) >= GUI_WAIT_TICKS) {
            performLobbyClick(client, activeLobbyScreen);
            return;
        }

        if (lobbyGuiTicksSinceOpen >= GUI_TIMEOUT_TICKS) {
            AutoQiqiClient.log("Reconnect", "Lobby GUI timed out waiting for items");
            client.setScreen(null);
            onFailure("lobby GUI timeout");
        }
    }

    private void performLobbyClick(MinecraftClient client, HandledScreen<?> screen) {
        if (client.interactionManager == null || client.player == null) {
            client.setScreen(null);
            onFailure("no interaction manager for lobby click");
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        int guiSlots = handler.slots.size() - 36;
        if (guiSlots <= 0) guiSlots = handler.slots.size();

        int targetSlot = -1;
        for (int i = 0; i < guiSlots; i++) {
            if (!handler.slots.get(i).getStack().isEmpty()) {
                String name = handler.slots.get(i).getStack().getName().getString();
                AutoQiqiClient.log("Reconnect", "Lobby GUI slot " + i + ": '" + name + "'");
                if (targetSlot < 0) targetSlot = i;
            }
        }

        if (targetSlot < 0) {
            AutoQiqiClient.log("Reconnect", "No non-empty slot found in lobby GUI");
            client.setScreen(null);
            onFailure("no item in lobby GUI");
            return;
        }

        int syncId = handler.syncId;
        AutoQiqiClient.log("Reconnect", "Clicking lobby GUI slot " + targetSlot
                + " (syncId=" + syncId + ")");
        client.interactionManager.clickSlot(syncId, targetSlot, 0, SlotActionType.PICKUP, client.player);
        client.setScreen(null);

        transition(Phase.WAIT_SPAWN);
    }

    // --- Phase: WAIT_SPAWN ---

    private void tickWaitSpawn(MinecraftClient client) {
        if (client.player != null && client.world != null && client.currentScreen == null) {
            long elapsed = System.currentTimeMillis() - phaseStartMs;
            if (elapsed >= 2_000) {
                onSuccess();
                return;
            }
        }

        if (client.player == null || client.world == null) {
            long elapsed = System.currentTimeMillis() - phaseStartMs;
            if (elapsed >= PHASE_TIMEOUT_MS) {
                onFailure("lost connection during spawn wait");
            }
        }

        checkTimeout(Phase.MONITORING, "wait for spawn");
    }

    // --- Helpers ---

    private boolean hasNonEmptySlots(HandledScreen<?> screen) {
        ScreenHandler handler = screen.getScreenHandler();
        int guiSlots = handler.slots.size() - 36;
        if (guiSlots <= 0) guiSlots = handler.slots.size();

        for (int i = 0; i < guiSlots; i++) {
            if (!handler.slots.get(i).getStack().isEmpty()) return true;
        }
        return false;
    }

    private void transition(Phase newPhase) {
        AutoQiqiClient.log("Reconnect", "Phase: " + phase + " -> " + newPhase);
        phase = newPhase;
        phaseStartMs = System.currentTimeMillis();
    }

    private void checkTimeout(Phase fallbackPhase, String context) {
        long elapsed = System.currentTimeMillis() - phaseStartMs;
        if (elapsed >= PHASE_TIMEOUT_MS) {
            AutoQiqiClient.log("Reconnect", "Timeout in phase " + phase + " (" + context + ")");
            onFailure("timeout: " + context);
        }
    }

    private long getReconnectDelayMs() {
        AutoQiqiConfig config = AutoQiqiConfig.get();
        int baseDelay = config.reconnectDelaySeconds;
        int backoff = baseDelay * (1 << Math.min(consecutiveFailures, 4));
        int jitter = ThreadLocalRandom.current().nextInt(0, Math.max(1, backoff / 4));
        return (backoff + jitter) * 1000L;
    }

    private void onSuccess() {
        AutoQiqiClient.log("Reconnect", "Reconnect successful! Failures reset. Returning to MONITORING.");
        SessionLogger.get().logEvent("AUTO_RECONNECT", "Reconnect successful after "
                + consecutiveFailures + " failures");
        consecutiveFailures = 0;
        resetLobbyGui();
        transition(Phase.MONITORING);
    }

    private void onFailure(String reason) {
        consecutiveFailures++;
        AutoQiqiConfig config = AutoQiqiConfig.get();

        AutoQiqiClient.log("Reconnect", "Reconnect attempt failed (" + reason
                + "), failure #" + consecutiveFailures + "/" + config.reconnectMaxRetries);
        SessionLogger.get().logEvent("AUTO_RECONNECT",
                "Attempt failed: " + reason + " (#" + consecutiveFailures + ")");

        resetLobbyGui();

        if (consecutiveFailures >= config.reconnectMaxRetries) {
            AutoQiqiClient.log("Reconnect", "Max retries reached, disabling auto-reconnect");
            SessionLogger.get().logEvent("AUTO_RECONNECT",
                    "Max retries (" + config.reconnectMaxRetries + ") reached, giving up");
            disable();
            return;
        }

        transition(Phase.COOLDOWN);
    }

    private void tickCooldown() {
        long elapsed = System.currentTimeMillis() - phaseStartMs;
        long delayMs = getReconnectDelayMs();
        if (elapsed >= delayMs) {
            AutoQiqiClient.log("Reconnect", "Cooldown elapsed (" + (delayMs / 1000)
                    + "s), retrying reconnect");
            transition(Phase.MONITORING);
        }
    }

    private void resetLobbyGui() {
        activeLobbyScreen = null;
        lobbyGuiTicksSinceOpen = 0;
        lobbyGuiItemsDetected = false;
        lobbyGuiItemsDetectedTick = 0;
    }
}
