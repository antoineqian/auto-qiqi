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
 * and optionally sends key J when the timer reaches the 1-minute mark.
 */
public class QiqiTimerClient implements ClientModInitializer {

    private static KeyBinding toggleAutohopKey;

    private long tickCount = 0;
    private long lastPollTick = 0;
    /** True once we've sent J this cycle (remaining <= threshold); reset when remaining > threshold. */
    private boolean sentJThisCycle = false;

    @Override
    public void onInitializeClient() {
        QiqiTimerConfig.get();
        toggleAutohopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.qiqitimer.toggle_autohop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.qiqitimer"));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudRenderCallback.EVENT.register(this::renderHud);
    }

    private void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        QiqiTimerConfig config = QiqiTimerConfig.get();

        if (toggleAutohopKey.wasPressed()) {
            config.sendJAt1MinLeft = !config.sendJAt1MinLeft;
            config.save();
            log("Autohop (send J at 30 sec): " + (config.sendJAt1MinLeft ? "ON" : "OFF"));
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[Qiqi-Timer] Autohop: " + (config.sendJAt1MinLeft ? "ON" : "OFF")), false);
            }
        }

        if (config.nextlegCommand == null || config.nextlegCommand.isEmpty()) return;

        tickCount++;
        long remaining = NextlegTimerState.get().getEstimatedRemainingSeconds();
        int threshold = Math.max(0, config.sendJThresholdSeconds);

        if (remaining > threshold) {
            sentJThisCycle = false;
        } else if (config.sendJAt1MinLeft && remaining >= 0 && remaining <= threshold && !sentJThisCycle
                && client.getWindow() != null) {
            if (isInBattle(client)) {
                log("Send K/J skipped (in battle), remaining=" + remaining + "s");
            } else {
                // K reloads legendary percentages, then J (e.g. resume / world menu)
                sendKey("key.keyboard.k");
                sendKey("key.keyboard.j");
                sentJThisCycle = true;
                log("Sent key K then J (remaining=" + remaining + "s)");
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

    private static void sendKey(String translationKey) {
        InputUtil.Key key = InputUtil.fromTranslationKey(translationKey);
        KeyBinding.setKeyPressed(key, true);
        KeyBinding.onKeyPressed(key);
        KeyBinding.setKeyPressed(key, false);
    }

    private static void log(String message) {
        System.out.println("[Qiqi-Timer] " + message);
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
