package net.tfminecraft.thievery.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.entity.Player;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class RiskCalculator {

    private static NavigableMap<Integer, Double> dexterityLerpMap = defaultDexterityMap();

    private RiskCalculator() {}

    public static int getDexterity(Player player) {
        try {
            return PlayerData.get(player.getUniqueId())
                    .getAttributes()
                    .getInstance(Parameters.lockpickAttribute)
                    .getTotal();
        } catch (Exception e) {
            return 0;
        }
    }

    public static void loadDexterityLerp(Map<?, ?> fromConfig) {
        TreeMap<Integer, Double> parsed = new TreeMap<>();
        if (fromConfig != null) {
            for (Map.Entry<?, ?> entry : fromConfig.entrySet()) {
                try {
                    int dex = Integer.parseInt(entry.getKey().toString().trim());
                    double value = ((Number) entry.getValue()).doubleValue();
                    parsed.put(dex, value);
                } catch (NumberFormatException | ClassCastException ignored) {
                }
            }
        }
        dexterityLerpMap = parsed.isEmpty() ? defaultDexterityMap() : parsed;
    }

    public static double getDexterityLerpValue(int dexterity) {
        Map.Entry<Integer, Double> floor = dexterityLerpMap.floorEntry(dexterity);
        Map.Entry<Integer, Double> ceiling = dexterityLerpMap.ceilingEntry(dexterity);
        if (floor == null) {
            return ceiling.getValue();
        }
        if (ceiling == null) {
            return floor.getValue();
        }
        if (floor.getKey().equals(ceiling.getKey())) {
            return floor.getValue();
        }
        double t = (dexterity - floor.getKey()) / (double) (ceiling.getKey() - floor.getKey());
        return floor.getValue() + t * (ceiling.getValue() - floor.getValue());
    }

    private static NavigableMap<Integer, Double> defaultDexterityMap() {
        NavigableMap<Integer, Double> defaults = new TreeMap<>();
        defaults.put(0, 1.0);
        defaults.put(40, 2.0);
        return defaults;
    }

    public static double computeGain(int dexterity, double lockpickStrength) {
        return computeGain(dexterity, lockpickStrength, Cache.riskGainDoorMin, Cache.riskGainDoorMax);
    }

    public static double computeGain(int dexterity, double lockpickStrength, double min, double max) {
        double raw = min + ThreadLocalRandom.current().nextDouble() * (max - min);
        double dexFactor = 1.0 / (1.0 + dexterity / 40.0);
        double strength = clamp01(lockpickStrength);
        double pickFactor = 1.0 - strength * Cache.riskPickReduction;
        return raw * dexFactor * pickFactor;
    }

    public static double computeDecay(int dexterity, long lastDecayMs, long nowMs) {
        if (lastDecayMs <= 0 || nowMs <= lastDecayMs) return 0.0;
        double hours = (nowMs - lastDecayMs) / 3_600_000.0;
        return Cache.riskDecayPerHour * hours * (1.0 + dexterity / 40.0);
    }

    public static double computeCritical(double risk, int dexterity, double lockpickStrength) {
        double strength = clamp01(lockpickStrength);
        double critical = Cache.criticalBase
                + risk * Cache.criticalRiskWeight
                - (dexterity / 40.0) * Cache.criticalDexReduction
                - strength * Cache.criticalStrengthReduction;
        return clamp01(critical);
    }

    public static double computeValueRisk(double stolenValue) {
        if (stolenValue <= 0 || Cache.takeValueScale <= 0) {
            return 0.0;
        }
        double factor = 1.0 - Math.exp(-stolenValue / Cache.takeValueScale);
        return Cache.takeValueMaxBonus * factor;
    }

    public static double computeTakeClueChanceRaw(double sessionRisk, double stolenValue) {
        double valueRisk = computeValueRisk(stolenValue);
        double sessionCap = computeTakeSessionCap();
        return clamp01(clamp01(sessionRisk) * sessionCap + valueRisk);
    }

    public static double computeTakeSessionCap() {
        return 1.0 - Cache.takeValueMaxBonus;
    }

    public static double computeTakeClueChance(double sessionRisk, double stolenValue) {
        double chance = computeTakeClueChanceRaw(sessionRisk, stolenValue);
        if (Cache.takeClueDivisor > 1.0) {
            chance /= Cache.takeClueDivisor;
        }
        return clamp01(chance);
    }

    public record TakeCluePreview(double clueChance, double criticalOnTake) {}

    public static TakeCluePreview computeTakeCluePreview(double sessionRisk, double stolenValue,
            int dexterity, double lockpickStrength) {
        return computeTakeCluePreview(sessionRisk, stolenValue, dexterity, lockpickStrength, false);
    }

    public static TakeCluePreview computeTakeCluePreview(double sessionRisk, double stolenValue,
            int dexterity, double lockpickStrength, boolean guaranteedClue) {
        return computeTakeCluePreview(sessionRisk, stolenValue, dexterity, lockpickStrength, guaranteedClue, true);
    }

    public static TakeCluePreview computeTakeCluePreview(double sessionRisk, double stolenValue,
            int dexterity, double lockpickStrength, boolean guaranteedClue, boolean criticalRisk) {
        double criticalIfClue = criticalRisk
                ? computeCritical(sessionRisk, dexterity, lockpickStrength)
                : 0.0;
        if (guaranteedClue) {
            return new TakeCluePreview(1.0, criticalIfClue);
        }
        double clueChance = computeTakeClueChance(sessionRisk, stolenValue);
        return new TakeCluePreview(clueChance, clueChance * criticalIfClue);
    }

    public static String formatPercent(double value) {
        return String.format("%.2f%%", value * 100.0);
    }

    public static String formatPercentWhole(double value) {
        return Math.round(value * 100.0) + "%";
    }

    public static List<String> formatRiskLore(double risk, double critical) {
        List<String> lore = new ArrayList<>();
        if (risk > 0) {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Risk: " + ThieveryTexts.MUTED + formatPercent(risk)));
        }
        if (critical > 0) {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.CRITICAL + "Critical: " + ThieveryTexts.MUTED + formatPercent(critical)));
        }
        return lore;
    }

    public static String formatRiskTitle(double risk, double critical) {
        StringBuilder title = new StringBuilder();
        if (risk > 0) {
            title.append(ThieveryTexts.MUTED).append("Risk: ").append(ThieveryTexts.MUTED).append(formatPercentWhole(risk));
        }
        if (critical > 0) {
            if (title.length() > 0) {
                title.append(" ");
            }
            title.append(ThieveryTexts.CRITICAL).append("Crit: ").append(ThieveryTexts.MUTED).append(formatPercentWhole(critical));
        }
        return title.length() > 0 ? ThieveryTexts.formatDisplay(title.toString()) : "";
    }

    private static double clamp01(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }
}
