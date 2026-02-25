package com.cobblemoon.autoqiqi.common;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;

import java.util.*;
public class PokemonScanner {
    private static final PokemonScanner INSTANCE = new PokemonScanner();
    private static final double SCAN_RANGE = 80.0;

    private List<Entity> lastScanResults = new ArrayList<>();
    private List<Entity> manualScanResults = new ArrayList<>();

    private PokemonScanner() {}

    public static PokemonScanner get() {
        return INSTANCE;
    }

    /**
     * Quick scan for periodic count. Does NOT overwrite manual scan results.
     */
    public List<Entity> scan() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            lastScanResults = Collections.emptyList();
            return lastScanResults;
        }

        List<Entity> pokemon = client.world.getOtherEntities(
                player,
                player.getBoundingBox().expand(SCAN_RANGE),
                entity -> isPokemonEntity(entity) && entity.isAlive() && isWild(entity)
        );

        pokemon.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(player)));
        lastScanResults = new ArrayList<>(pokemon);
        return lastScanResults;
    }

    /**
     * Manual scan triggered by /pk scan. Results are preserved for /pk capture.
     */
    public List<Entity> manualScan() {
        List<Entity> results = scan();
        manualScanResults = new ArrayList<>(results);
        return manualScanResults;
    }

    public Entity getFromLastScan(int oneBasedIndex) {
        int idx = oneBasedIndex - 1;
        if (idx < 0 || idx >= manualScanResults.size()) return null;
        Entity e = manualScanResults.get(idx);
        if (!e.isAlive() || e.isRemoved()) return null;
        return e;
    }

    public int getLastScanSize() {
        return manualScanResults.size();
    }

    // ========================
    // Entity detection
    // ========================

    public static boolean isPokemonEntity(Entity entity) {
        return entity instanceof PokemonEntity;
    }

    private static boolean isWild(Entity entity) {
        if (!(entity instanceof PokemonEntity pe)) return false;
        try {
            var pokemon = pe.getPokemon();
            if (pokemon.isPlayerOwned()) return false;
            if (pokemon.getOwnerUUID() != null) return false;
            if (pe.isBusy()) return false;
            if (pe.getOwner() != null) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ========================
    // Display helpers
    // ========================

    public static String getPokemonName(Entity entity) {
        if (entity instanceof PokemonEntity pe) {
            try {
                return pe.getPokemon().getSpecies().getTranslatedName().getString();
            } catch (Exception ignored) {}
        }
        return entity.getDisplayName().getString();
    }

    public static int getPokemonLevel(Entity entity) {
        if (entity instanceof PokemonEntity pe) {
            try {
                return pe.getPokemon().getLevel();
            } catch (Exception ignored) {}
        }
        return -1;
    }

    public static boolean isBoss(Entity entity) {
        if (entity.hasCustomName()) {
            String customName = entity.getCustomName().getString().toLowerCase();
            if (customName.contains("boss")) return true;
        }
        if (entity instanceof PokemonEntity pe) {
            try {
                var pokemon = pe.getPokemon();
                if (pokemon.hasLabels("boss")) return true;
                if (pokemon.getAspects().stream().anyMatch(a -> a.toLowerCase().contains("boss"))) return true;
                var form = pokemon.getForm();
                if (form.getName().toLowerCase().contains("boss")) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ========================
    // Pokedex (client-side)
    // ========================

    public static String getPokedexStatus(Entity entity) {
        if (!(entity instanceof PokemonEntity pe)) return "?";
        try {
            var pokemon = pe.getPokemon();
            var species = pokemon.getSpecies();
            var resourceId = species.getResourceIdentifier();
            if (resourceId == null) return "?";

            var pokedexData = com.cobblemon.mod.common.client.CobblemonClient.INSTANCE.getClientPokedexData();
            if (pokedexData == null) return "?";

            var speciesRecords = pokedexData.getSpeciesRecords();
            var record = speciesRecords.get(resourceId);
            if (record != null) {
                String k = record.getKnowledge().toString();
                if ("CAUGHT".equals(k)) return "CAUGHT";
                if ("ENCOUNTERED".equals(k)) return "SEEN";
            }
            return "NEW";
        } catch (Exception e) {
            return "?";
        }
    }

    public static boolean isSpeciesCaught(Entity entity) {
        return "CAUGHT".equals(getPokedexStatus(entity));
    }

    public static int countUncaught(List<Entity> entities) {
        int count = 0;
        for (Entity entity : entities) {
            if (!isSpeciesCaught(entity)) count++;
        }
        return count;
    }

    private static final java.util.Set<String> LEGENDARY_NAMES = java.util.Set.of(
            "articuno", "zapdos", "moltres", "mewtwo", "mew",
            "raikou", "entei", "suicune", "lugia", "hooh", "celebi",
            "regirock", "regice", "registeel", "latias", "latios",
            "kyogre", "groudon", "rayquaza", "jirachi", "deoxys",
            "uxie", "mesprit", "azelf", "dialga", "palkia", "heatran",
            "regigigas", "giratina", "cresselia", "phione", "manaphy",
            "darkrai", "shaymin", "arceus",
            "cobalion", "terrakion", "virizion", "tornadus", "thundurus",
            "reshiram", "zekrom", "landorus", "kyurem", "keldeo",
            "meloetta", "genesect",
            "xerneas", "yveltal", "zygarde", "diancie", "hoopa", "volcanion",
            "typenull", "type_null", "silvally",
            "tapukoko", "tapulele", "tapubulu", "tapufini",
            "cosmog", "cosmoem", "solgaleo", "lunala", "necrozma",
            "magearna", "marshadow", "zeraora",
            "zacian", "zamazenta", "eternatus", "kubfu", "urshifu",
            "regieleki", "regidrago", "glastrier", "spectrier", "calyrex",
            "enamorus",
            "wochien", "chienpao", "tinglu", "chiyu",
            "koraidon", "miraidon", "ogerpon", "terapagos", "pecharunt"
    );

    public static boolean isLegendary(Entity entity) {
        if (entity instanceof PokemonEntity pe) {
            try {
                var pokemon = pe.getPokemon();
                if (pokemon.isLegendary() || pokemon.isMythical() || pokemon.isUltraBeast()) return true;
                if (pokemon.hasLabels("legendary") || pokemon.hasLabels("mythical") || pokemon.hasLabels("ultra_beast")) return true;
            } catch (Exception ignored) {}
        }
        String name = getPokemonName(entity).toLowerCase().replaceAll("[^a-z]", "");
        return LEGENDARY_NAMES.contains(name);
    }

    public static String getDisplayInfo(Entity entity) {
        StringBuilder sb = new StringBuilder();
        if (isBoss(entity)) {
            sb.append("§c[BOSS] ");
        }
        if (isLegendary(entity)) {
            sb.append("§d[LEG] ");
        }
        String status = getPokedexStatus(entity);
        switch (status) {
            case "NEW"    -> sb.append("§a§l[NEW] §r§f");
            case "SEEN"   -> sb.append("§e[SEEN] §f");
            case "CAUGHT" -> sb.append("§7[DEX] §f");
            default       -> sb.append("§8[?] §f");
        }
        sb.append(getPokemonName(entity));
        int level = getPokemonLevel(entity);
        if (level > 0) {
            sb.append(" (Lv.").append(level).append(")");
        }
        return sb.toString();
    }

    public static void debugDumpEntity(Entity entity) {
        log("=== Pokemon Debug Dump ===");
        log("  Entity class: " + entity.getClass().getName());
        log("  DisplayName: " + entity.getDisplayName().getString());
        log("  CustomName: " + (entity.hasCustomName() ? entity.getCustomName().getString() : "none"));
        log("  Glowing: " + entity.isGlowing());

        if (entity instanceof PokemonEntity pe) {
            var pokemon = pe.getPokemon();
            log("  --- Pokemon ---");
            log("  Species: " + pokemon.getSpecies().getName());
            log("  Level: " + pokemon.getLevel());
            log("  PlayerOwned: " + pokemon.isPlayerOwned());
            log("  OwnerUUID: " + pokemon.getOwnerUUID());
            log("  Legendary: " + pokemon.isLegendary());
            log("  Mythical: " + pokemon.isMythical());
            log("  UltraBeast: " + pokemon.isUltraBeast());
            log("  Aspects: " + pokemon.getAspects());
            log("  Busy: " + pe.isBusy());
            log("  Owner: " + pe.getOwner());

            var species = pokemon.getSpecies();
            log("  --- Species ---");
            log("  Labels: " + species.getLabels());
        } else {
            log("  Not a PokemonEntity");
        }
    }

    private static void log(String message) {
        System.out.println("[Auto-Qiqi] " + message);
    }
}
