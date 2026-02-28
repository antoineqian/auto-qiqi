package com.cobblemoon.autocobblemon;

import com.cobblemoon.autocobblemon.common.PokemonScanner;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Lightweight Cobblemon mod: periodic scan + alerts and /pk scan command only.
 */
public class AutoCobblemonClient implements ClientModInitializer {
    public static final String MOD_ID = "auto-cobblemon";

    private static final int PERIODIC_SCAN_INTERVAL = 600; // 30 seconds at 20 tps
    private int periodicScanTicks = 0;

    @Override
    public void onInitializeClient() {
        log("Init", "Auto-Cobblemon initialized (scan + alerts only).");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;
            tickPeriodicScan(client);
        });

        registerCommands();
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    ClientCommandManager.literal("pk")
                            .then(ClientCommandManager.literal("scan")
                                    .executes(context -> { executeScan(); return 1; }))
            );
        });
    }

    private void tickPeriodicScan(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        periodicScanTicks++;
        if (periodicScanTicks < PERIODIC_SCAN_INTERVAL) return;
        periodicScanTicks = 0;

        List<Entity> results = PokemonScanner.get().scan();
        int uncaught = PokemonScanner.countUncaught(results);
        int total = results.size();

        int bosses = 0;
        StringBuilder bossNames = new StringBuilder();
        for (Entity e : results) {
            if (PokemonScanner.isBoss(e)) {
                bosses++;
                if (bossNames.length() > 0) bossNames.append(", ");
                bossNames.append(PokemonScanner.getPokemonName(e));
            }
        }

        log("Scan", "Periodic: " + total + " wild nearby, " + uncaught + " uncaught, " + bosses + " boss(es).");

        if (bosses > 0) {
            client.player.sendMessage(
                    Text.literal("§6[Auto-Cobblemon]§r §c§lBoss: " + bossNames + "§r §7nearby!"), false);
        }
        if (uncaught > 0) {
            client.player.sendMessage(
                    Text.literal("§6[Auto-Cobblemon]§r §a" + uncaught + " uncaught§7/§f" + total + " wild nearby"),
                    true);
        }
    }

    private void executeScan() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        List<Entity> results = PokemonScanner.get().manualScan();
        if (results.isEmpty()) {
            msg(client, "§7No wild Pokemon detected nearby.");
            return;
        }

        int uncaught = PokemonScanner.countUncaught(results);
        msg(client, "§a=== Wild Pokemon (" + uncaught + " uncaught / " + results.size() + " total) ===");
        for (int i = 0; i < results.size(); i++) {
            Entity entity = results.get(i);
            String info = PokemonScanner.getDisplayInfo(entity);
            boolean caught = PokemonScanner.isSpeciesCaught(entity);
            String cTag = caught ? " §7(o)" : "";
            double dist = client.player.distanceTo(entity);
            msg(client, "§e" + (i + 1) + ". §f" + info + cTag + " §7- " + String.format("%.1f", dist) + " blocks");
        }
        msg(client, "§7Use §f/pk scan§7 to refresh.");
    }

    public static void log(String prefix, String message) {
        System.out.println("[Auto-Cobblemon/" + prefix + "] " + message);
    }

    private static void msg(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§6[Auto-Cobblemon]§r " + message), false);
        }
    }
}
