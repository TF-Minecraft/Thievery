package net.tfminecraft.thievery.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class Cache {

    private Cache() {}
    public static int cooldown;
    public static int radius;

    public static boolean coreProtect = false;

    public static List<String> traits = new ArrayList<>();

    public static int categoryPoints = 30;
    public static int pointGainIntervalHours = 24;

    public static double defaultItemValue = 0.1;

    private static final Map<String, Double> QUALITY_PERCENT = new HashMap<>();
    private static final Map<Integer, Double> AURA_PERCENT = new HashMap<>();
    private static final NavigableMap<Integer, Double> AURA_MINS = new TreeMap<>();

    static {
        putDefaultItemValueTables();
    }

    public static int recentClueMax = 6;
    public static int recentClueCooldownHours = 72;
    public static int criticalCooldownHours = 24;
    public static int minCluesDoor = 0;
    public static int minCluesContainer = 1;

    public static double riskGainDoorMin = 0.05;
    public static double riskGainDoorMax = 0.15;
    public static double riskGainChestMin = 0.025;
    public static double riskGainChestMax = 0.075;
    public static double riskGainPickpocketMin = 0.04;
    public static double riskGainPickpocketMax = 0.12;
    public static double riskPickReduction = 0.5;
    public static double riskDecayPerHour = 0.08;

    public static double criticalBase = 0.0;
    public static double criticalRiskWeight = 0.5;
    public static double criticalDexReduction = 0.15;
    public static double criticalStrengthReduction = 0.2;
    public static double takeValueScale = 12.0;
    public static double takeValueMaxBonus = 0.35;
    public static double takeClueDivisor = 10.0;
    public static String criticalClue = "§7This seems to be the work of #d6cf69{character_name}";

    public static boolean interactibleFurniture = false;

    public static boolean requireOwnerOnline = false;
    public static boolean debugAllowOwnChest = false;
    public static boolean debugCluePreview = false;

    public static void putDefaultItemValueTables() {
        QUALITY_PERCENT.clear();
        QUALITY_PERCENT.put("rusted", -0.20);
        QUALITY_PERCENT.put("tempered", 0.00);
        QUALITY_PERCENT.put("polished", 0.25);
        QUALITY_PERCENT.put("gleaming", 0.50);
        QUALITY_PERCENT.put("masterwork", 0.75);
        AURA_PERCENT.clear();
        AURA_PERCENT.put(0, 0.00);
        AURA_PERCENT.put(1, 0.00);
        AURA_PERCENT.put(2, 0.15);
        AURA_PERCENT.put(3, 0.40);
        AURA_PERCENT.put(4, 0.75);
        AURA_MINS.clear();
        AURA_MINS.put(1, 10.0);
        AURA_MINS.put(2, 40.0);
        AURA_MINS.put(3, 75.0);
        AURA_MINS.put(4, 110.0);
    }

    public static void clearQualityPercents() {
        QUALITY_PERCENT.clear();
    }

    public static void putQualityPercent(String id, double percent) {
        if (id == null || id.isBlank()) {
            return;
        }
        QUALITY_PERCENT.put(id.trim().toLowerCase(Locale.ROOT), percent);
    }

    public static double qualityPercent(String id) {
        if (id == null || id.isBlank()) {
            return 0;
        }
        return QUALITY_PERCENT.getOrDefault(id.trim().toLowerCase(Locale.ROOT), 0.0);
    }

    public static void clearAuraPercents() {
        AURA_PERCENT.clear();
    }

    public static void putAuraPercent(int band, double percent) {
        AURA_PERCENT.put(band, percent);
    }

    public static double auraPercent(int band) {
        return AURA_PERCENT.getOrDefault(band, 0.0);
    }

    public static void clearAuraMins() {
        AURA_MINS.clear();
    }

    public static void putAuraMin(int band, double minFill) {
        if (band <= 0) {
            return;
        }
        AURA_MINS.put(band, Math.max(0.0, minFill));
    }

    public static int auraBand(double fill) {
        int best = 0;
        for (Map.Entry<Integer, Double> entry : AURA_MINS.entrySet()) {
            if (fill + 0.0001 >= entry.getValue()) {
                best = Math.max(best, entry.getKey());
            }
        }
        return best;
    }
}
