package com.cobblemoon.qiqitimer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Qiqi-Timer: polls /nextleg periodically, shows the legendary timer in the bottom-right HUD,
 * and optionally sends key J when the timer reaches the threshold (default 30 sec left).
 */
public class QiqiTimerClient implements ClientModInitializer {

    private static KeyBinding toggleAutohopKey;

    private long tickCount = 0;
    private long lastPollTick = 0;
    /** True once we've triggered auto-hop this cycle; reset when remaining > threshold. */
    private boolean sentAutoHopThisCycle = false;

    @Override
    public void onInitializeClient() {
        QiqiTimerConfig config = QiqiTimerConfig.get();
        int defaultKey = config.toggleAutohopKeyCode != 0 ? config.toggleAutohopKeyCode : GLFW.GLFW_KEY_O;
        toggleAutohopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.qiqitimer.toggle_autohop", InputUtil.Type.KEYSYM, defaultKey, "category.qiqitimer"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::renderHud);
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        QiqiTimerConfig config = QiqiTimerConfig.get();

        if (toggleAutohopKey.wasPressed()) {
            config.autoHopEnabled = !config.autoHopEnabled;
            config.save();
            log("Auto-hop rotation: " + (config.autoHopEnabled ? "ON" : "OFF"));
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[Qiqi-Timer] Auto-hop: " + (config.autoHopEnabled ? "ON" : "OFF")), false);
            }
            if (config.autoHopEnabled) {
                sentAutoHopThisCycle = false;
                long remaining = NextlegTimerState.get().getEstimatedRemainingSeconds();
                int threshold = Math.max(0, config.autoHopThresholdSeconds);
                if (remaining >= 0 && remaining <= threshold && !isInBattle(client)) {
                    log("[AutoHop-debug] Toggle ON with timer already under threshold — invoking immediately (remaining=" + remaining + "s)");
                    if (invokeAutoHopRotation(client)) {
                        sentAutoHopThisCycle = true;
                    }
                }
            }
        }

        if (config.nextlegCommand == null || config.nextlegCommand.isEmpty()) return;

        tickCount++;
        long remaining = NextlegTimerState.get().getEstimatedRemainingSeconds();
        int autoHopThreshold = Math.max(0, config.autoHopThresholdSeconds);
        debugLogTimerState(remaining, config);

        // Log auto-hop decision every 5 seconds when timer is in the interesting range
        if (tickCount % 100 == 0 && remaining >= 0 && remaining <= autoHopThreshold + 30) {
            boolean inBattle = isInBattle(client);
            log("[AutoHop-debug] remaining=" + remaining + "s threshold=" + autoHopThreshold
                    + "s enabled=" + config.autoHopEnabled + " sentThisCycle=" + sentAutoHopThisCycle
                    + " inBattle=" + inBattle);
        }

        if (remaining > autoHopThreshold) {
            sentAutoHopThisCycle = false;
        } else if (config.autoHopEnabled && remaining >= 0 && remaining <= autoHopThreshold
                && !sentAutoHopThisCycle && !isInBattle(client)) {
            log("[AutoHop-debug] All conditions met — invoking auto-hop rotation (remaining=" + remaining + "s)");
            if (invokeAutoHopRotation(client)) {
                sentAutoHopThisCycle = true;
                log("Auto-hop rotation triggered (remaining=" + remaining + "s)");
            } else {
                log("[AutoHop-debug] invokeAutoHopRotation returned false — reflection failed");
            }
        }

        int intervalTicks = Math.max(20, config.pollIntervalSeconds * 20);
        if (lastPollTick == 0 || (tickCount - lastPollTick) >= intervalTicks) {
            String cmd = config.nextlegCommand.startsWith("/") ? config.nextlegCommand.substring(1) : config.nextlegCommand;
            try {
                if (client.getNetworkHandler() != null && client.player != null) {
                    client.player.networkHandler.sendCommand(cmd);
                    lastPollTick = tickCount;
                    ChatHandler.get().setPendingPoll();
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /** Invokes AutoQiqiClient.invokeAutoHopRotation(client) via reflection. Returns true if invoked. */
    private static boolean invokeAutoHopRotation(MinecraftClient client) {
        try {
            Class<?> c = Class.forName("com.cobblemoon.autoqiqi.AutoQiqiClient");
            c.getMethod("invokeAutoHopRotation", MinecraftClient.class).invoke(null, client);
            return true;
        } catch (Throwable t) {
            log("invokeAutoHopRotation: reflection failed (" + t.getMessage() + ")");
            return false;
        }
    }

    private static void log(String message) {
        System.out.println("[Qiqi-Timer] " + message);
    }

    // Log timer state every 30 seconds for debugging
    private long lastTimerDebugTick = 0;

    private void debugLogTimerState(long remaining, QiqiTimerConfig config) {
        if (tickCount - lastTimerDebugTick >= 600) { // every 30s (600 ticks)
            lastTimerDebugTick = tickCount;
            long rawRemaining = NextlegTimerState.get().getEstimatedRemainingSeconds();
            log("[Timer-debug] estimated=" + rawRemaining + "s formatted=" + NextlegTimerState.get().getFormattedTime()
                    + " autoHopEnabled=" + config.autoHopEnabled + " sentThisCycle=" + sentAutoHopThisCycle);
        }
    }

    /** Returns true if the player is in a Cobblemon battle (uses reflection; no dependency). On failure we assume in battle so we never send J by mistake. */
    private static boolean isInBattle(MinecraftClient client) {
        try {
            Class<?> cobblemon = Class.forName("com.cobblemon.mod.common.client.CobblemonClient");
            Object instance = cobblemon.getField("INSTANCE").get(null);
            Object battle = instance.getClass().getMethod("getBattle").invoke(instance);
            return battle != null;
        } catch (Throwable t) {
            log("isInBattle: reflection failed (" + t.getMessage() + "), assuming in battle — J will not be sent");
            return true; // safe default: do not send J when we cannot detect
        }
    }

    private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.getDebugHud().shouldShowDebugHud()) return;

        QiqiTimerConfig config = QiqiTimerConfig.get();
        if (!config.hudVisible) return;

        TextRenderer tr = client.textRenderer;
        Window window = client.getWindow();
        int screenWidth = window.getScaledWidth();
        int screenHeight = window.getScaledHeight();
        int margin = 6;
        int lineHeight = 12;

        String timeStr = NextlegTimerState.get().getFormattedTime();
        String line = "Next leg: " + timeStr;
        int w = tr.getWidth(line);
        int x = screenWidth - w - margin;
        int y = screenHeight - lineHeight - margin;

        context.fill(x - 2, y - 1, x + w + 2, y + 10, 0x88000000);
        context.drawText(tr, Text.literal(line), x, y, 0xFFDDDDDD, true);
    }
}
