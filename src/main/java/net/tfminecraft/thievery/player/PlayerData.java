package net.tfminecraft.thievery.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.ItemCategory;
import net.tfminecraft.thievery.loader.CategoryLoader;

public class PlayerData {

    private UUID id;
    private int points;
    private long lastGain;
    private List<String> activeCategories = new ArrayList<>();
    private double risk;
    private long lastRiskDecayMs;
    private List<RecentClueEntry> recentClues = new ArrayList<>();
    private Map<String, Long> lastCriticalClueAtByTarget = new HashMap<>();
    private Map<String, Long> paperKeyCooldownExpiryByDoorUuid = new HashMap<>();
    private boolean factionLockWarningDismissed;

    public PlayerData(UUID id) {
        this.id = id;
        this.points = Cache.categoryPoints;
        this.lastGain = System.currentTimeMillis();
        this.activeCategories = new ArrayList<>();
        this.risk = 0;
        this.lastRiskDecayMs = System.currentTimeMillis();
        this.recentClues = new ArrayList<>();
        this.lastCriticalClueAtByTarget = new HashMap<>();
        this.paperKeyCooldownExpiryByDoorUuid = new HashMap<>();
        this.factionLockWarningDismissed = false;
    }

    public UUID getId() {
        return id;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = Math.max(0, Math.min(points, Cache.categoryPoints));
    }

    public long getLastGain() {
        return lastGain;
    }

    public void setLastGain(long lastGain) {
        this.lastGain = lastGain;
    }

    public List<String> getActiveCategories() {
        return activeCategories;
    }

    public void setActiveCategories(List<String> activeCategories) {
        this.activeCategories = activeCategories != null ? activeCategories : new ArrayList<>();
    }

    public boolean isCategoryActive(String categoryId) {
        return activeCategories.contains(categoryId);
    }

    public double getRisk() {
        return risk;
    }

    public void setRisk(double risk) {
        double clamped = Math.min(1.0, Math.max(0.0, risk));
        this.risk = Math.round(clamped * 1000.0) / 1000.0;
    }

    public long getLastRiskDecayMs() {
        return lastRiskDecayMs;
    }

    public void setLastRiskDecayMs(long lastRiskDecayMs) {
        this.lastRiskDecayMs = lastRiskDecayMs;
    }

    public void applyRiskDecay(int dexterity) {
        long now = System.currentTimeMillis();
        double decay = RiskCalculator.computeDecay(dexterity, lastRiskDecayMs, now);
        if (decay > 0) {
            setRisk(risk - decay);
        }
        lastRiskDecayMs = now;
    }

    public void addRiskGain(int dexterity, double lockpickStrength, RiskSource source) {
        addRiskGain(dexterity, lockpickStrength, source, 1.0);
    }

    public void addRiskGain(int dexterity, double lockpickStrength, RiskSource source, double riskMultiplier) {
        applyRiskDecay(dexterity);
        double scale = Math.max(0.0, riskMultiplier);
        double min;
        double max;
        if (source == RiskSource.CHEST) {
            min = Cache.riskGainChestMin * scale;
            max = Cache.riskGainChestMax * scale;
        } else if (source == RiskSource.PICKPOCKET) {
            min = Cache.riskGainPickpocketMin;
            max = Cache.riskGainPickpocketMax;
        } else {
            min = Cache.riskGainDoorMin;
            max = Cache.riskGainDoorMax;
        }
        setRisk(risk + RiskCalculator.computeGain(dexterity, lockpickStrength, min, max));
    }

    public double getCriticalChance(int dexterity, double lockpickStrength) {
        applyRiskDecay(dexterity);
        return RiskCalculator.computeCritical(risk, dexterity, lockpickStrength);
    }

    public List<RecentClueEntry> getRecentClues() {
        return recentClues;
    }

    public void setRecentClues(List<RecentClueEntry> recentClues) {
        this.recentClues = recentClues != null ? recentClues : new ArrayList<>();
    }

    public Map<String, Long> getLastCriticalClueAtByTarget() {
        return lastCriticalClueAtByTarget;
    }

    public void setLastCriticalClueAtByTarget(Map<String, Long> lastCriticalClueAtByTarget) {
        this.lastCriticalClueAtByTarget = lastCriticalClueAtByTarget != null
                ? lastCriticalClueAtByTarget : new HashMap<>();
    }

    public Map<String, Long> getPaperKeyCooldownExpiryByDoorUuid() {
        return paperKeyCooldownExpiryByDoorUuid;
    }

    public void setPaperKeyCooldownExpiryByDoorUuid(Map<String, Long> paperKeyCooldownExpiryByDoorUuid) {
        this.paperKeyCooldownExpiryByDoorUuid = paperKeyCooldownExpiryByDoorUuid != null
                ? paperKeyCooldownExpiryByDoorUuid : new HashMap<>();
    }

    public boolean isFactionLockWarningDismissed() {
        return factionLockWarningDismissed;
    }

    public void setFactionLockWarningDismissed(boolean factionLockWarningDismissed) {
        this.factionLockWarningDismissed = factionLockWarningDismissed;
    }

    public boolean isPaperKeyOnCooldown(String doorKeyUuid) {
        return getPaperKeyCooldownRemainingMinutes(doorKeyUuid) > 0;
    }

    public long getPaperKeyCooldownRemainingMinutes(String doorKeyUuid) {
        if (doorKeyUuid == null) {
            return 0;
        }
        Long expiry = paperKeyCooldownExpiryByDoorUuid.get(doorKeyUuid);
        if (expiry == null) {
            return 0;
        }
        long remainingMs = expiry - System.currentTimeMillis();
        if (remainingMs <= 0) {
            paperKeyCooldownExpiryByDoorUuid.remove(doorKeyUuid);
            return 0;
        }
        return (remainingMs + 59_999L) / 60_000L;
    }

    public void recordPaperKeyCooldown(String doorKeyUuid, int cooldownMinutes) {
        if (doorKeyUuid == null || cooldownMinutes <= 0) {
            return;
        }
        long expiry = System.currentTimeMillis() + (long) cooldownMinutes * 60_000L;
        paperKeyCooldownExpiryByDoorUuid.put(doorKeyUuid, expiry);
    }

    public List<String> getRecentCluesForExclude(String targetKey) {
        pruneExpiredRecentClues();
        return recentClues.stream()
                .filter(entry -> targetKey.equals(entry.getTargetKey()))
                .sorted(Comparator.comparingLong(RecentClueEntry::getUsedAtMs).reversed())
                .limit(Cache.recentClueMax)
                .map(RecentClueEntry::getText)
                .collect(Collectors.toList());
    }

    public void recordClueUsed(String clueText, String targetKey) {
        if (clueText == null || clueText.isEmpty() || targetKey == null) return;
        recentClues.add(new RecentClueEntry(clueText, System.currentTimeMillis(), targetKey));
        pruneExpiredRecentClues();
        pruneTargetOverflow(targetKey);
    }

    public boolean isCriticalOnCooldown(String targetKey) {
        if (targetKey == null) return false;
        Long lastAt = lastCriticalClueAtByTarget.get(targetKey);
        if (lastAt == null) return false;
        long cooldownMs = Cache.criticalCooldownHours * 60L * 60L * 1000L;
        if (System.currentTimeMillis() - lastAt >= cooldownMs) {
            lastCriticalClueAtByTarget.remove(targetKey);
            return false;
        }
        return true;
    }

    public void recordCriticalClue(String targetKey) {
        if (targetKey == null) return;
        lastCriticalClueAtByTarget.put(targetKey, System.currentTimeMillis());
    }

    private void pruneExpiredRecentClues() {
        long cutoff = System.currentTimeMillis()
                - (Cache.recentClueCooldownHours * 60L * 60L * 1000L);
        recentClues.removeIf(entry -> entry.getUsedAtMs() < cutoff
                || entry.getTargetKey() == null || entry.getTargetKey().isEmpty());
    }

    private void pruneTargetOverflow(String targetKey) {
        List<RecentClueEntry> forTarget = recentClues.stream()
                .filter(entry -> targetKey.equals(entry.getTargetKey()))
                .sorted(Comparator.comparingLong(RecentClueEntry::getUsedAtMs))
                .collect(Collectors.toList());
        int overflow = forTarget.size() - Cache.recentClueMax;
        if (overflow <= 0) return;
        for (int i = 0; i < overflow; i++) {
            RecentClueEntry oldest = forTarget.get(i);
            recentClues.remove(oldest);
        }
    }

    public void clearCooldowns() {
        paperKeyCooldownExpiryByDoorUuid.clear();
        lastCriticalClueAtByTarget.clear();
        recentClues.clear();
    }

    public boolean normalizeAfterLoad() {
        if (activeCategories == null) {
            activeCategories = new ArrayList<>();
        }
        if (recentClues == null) {
            recentClues = new ArrayList<>();
        }
        if (lastCriticalClueAtByTarget == null) {
            lastCriticalClueAtByTarget = new HashMap<>();
        }
        if (paperKeyCooldownExpiryByDoorUuid == null) {
            paperKeyCooldownExpiryByDoorUuid = new HashMap<>();
        }
        if (lastRiskDecayMs <= 0) {
            lastRiskDecayMs = System.currentTimeMillis();
        }
        recentClues.removeIf(entry -> entry.getTargetKey() == null || entry.getTargetKey().isEmpty());
        pruneExpiredRecentClues();
        long criticalCutoff = System.currentTimeMillis()
                - (Cache.criticalCooldownHours * 60L * 60L * 1000L);
        Iterator<Map.Entry<String, Long>> it = lastCriticalClueAtByTarget.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() < criticalCutoff) {
                it.remove();
            }
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> paperIt = paperKeyCooldownExpiryByDoorUuid.entrySet().iterator();
        while (paperIt.hasNext()) {
            Map.Entry<String, Long> entry = paperIt.next();
            if (entry.getValue() <= now) {
                paperIt.remove();
            }
        }
        setRisk(risk);
        migrateLegacyCategoryIds();
        return enforceCategoryPointsCap();
    }

    public boolean enforceCategoryPointsCap() {
        boolean changed = false;
        while (getAllocatedCost() > Cache.categoryPoints && !activeCategories.isEmpty()) {
            activeCategories.remove(activeCategories.size() - 1);
            changed = true;
        }
        int before = points;
        setPoints(points);
        return changed || before != points;
    }

    private void migrateLegacyCategoryIds() {
        List<String> migrated = new ArrayList<>();
        for (String categoryId : activeCategories) {
            String mapped = mapLegacyCategoryId(categoryId);
            if (mapped != null) {
                if (CategoryLoader.getById(mapped) != null && !migrated.contains(mapped)) {
                    migrated.add(mapped);
                }
                continue;
            }
            if (CategoryLoader.getById(categoryId) != null && !migrated.contains(categoryId)) {
                migrated.add(categoryId);
            }
        }
        activeCategories = migrated;
    }

    private static String mapLegacyCategoryId(String id) {
        if (id.startsWith("ac_armour_tier_") || id.startsWith("ac_armor_tier_")) {
            return "tier_" + id.substring(id.lastIndexOf('_') + 1) + "_armor";
        }
        if (id.startsWith("ac_weapons_tier_")) {
            return "tier_" + id.substring(id.lastIndexOf('_') + 1) + "_weapons";
        }
        if (id.startsWith("ac_bows_tier_")) {
            return "tier_" + id.substring(id.lastIndexOf('_') + 1) + "_bows";
        }
        return null;
    }

    public int getAllocatedCost() {
        return getAllocatedCost(activeCategories);
    }

    public static int getAllocatedCost(Collection<String> categoryIds) {
        int total = 0;
        for (String categoryId : categoryIds) {
            ItemCategory category = CategoryLoader.getById(categoryId);
            if (category != null) {
                total += category.getCost();
            }
        }
        return total;
    }

    public int applyPointGain() {
        long intervalMs = Cache.pointGainIntervalHours * 60L * 60L * 1000L;
        if (intervalMs <= 0) return 0;

        long now = System.currentTimeMillis();
        long elapsed = now - lastGain;
        int intervals = (int) (elapsed / intervalMs);
        if (intervals <= 0) return 0;

        int added = 0;
        if (points < Cache.categoryPoints) {
            int newPoints = Math.min(points + intervals, Cache.categoryPoints);
            added = newPoints - points;
            points = newPoints;
        }

        lastGain += (long) intervals * intervalMs;
        return added;
    }
}
