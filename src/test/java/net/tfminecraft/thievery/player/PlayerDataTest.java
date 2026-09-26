package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.Gson;
import java.util.*;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.ItemCategory;
import net.tfminecraft.thievery.loader.CategoryLoader;
import org.junit.jupiter.api.*;

class PlayerDataTest {
    private int points, interval, clueMax, clueHours, criticalHours;
    @BeforeEach void configure() {
        points = Cache.categoryPoints; interval = Cache.pointGainIntervalHours;
        clueMax = Cache.recentClueMax; clueHours = Cache.recentClueCooldownHours; criticalHours = Cache.criticalCooldownHours;
        Cache.categoryPoints = 30; Cache.pointGainIntervalHours = 1; Cache.recentClueMax = 2;
        Cache.recentClueCooldownHours = 1; Cache.criticalCooldownHours = 1;
    }
    @AfterEach void restore() {
        Cache.categoryPoints = points; Cache.pointGainIntervalHours = interval; Cache.recentClueMax = clueMax;
        Cache.recentClueCooldownHours = clueHours; Cache.criticalCooldownHours = criticalHours;
    }
    @Test void negativeConfiguredCategoryCapExhaustsSelectionsWithoutRemovingPastEmpty() {
        var data = new PlayerData(UUID.randomUUID());
        Cache.categoryPoints = -1;
        data.setActiveCategories(new ArrayList<>(List.of("removed-category")));
        try (var categories = mockStatic(CategoryLoader.class)) {
            assertTrue(data.enforceCategoryPointsCap());
            assertTrue(data.getActiveCategories().isEmpty());
            assertEquals(0, data.getPoints());
            assertFalse(data.enforceCategoryPointsCap());
        }
    }

    @Test void defaultsAndSettersKeepPointsAndRiskWithinBounds() {
        UUID id = UUID.randomUUID(); long before = System.currentTimeMillis(); var data = new PlayerData(id);
        assertEquals(id, data.getId()); assertEquals(30, data.getPoints()); assertEquals(0, data.getRisk());
        assertTrue(data.getLastGain() >= before); assertTrue(data.getLastRiskDecayMs() >= before);
        assertFalse(data.isFactionLockWarningDismissed()); data.setFactionLockWarningDismissed(true); assertTrue(data.isFactionLockWarningDismissed());
        data.setPoints(-1); assertEquals(0, data.getPoints()); data.setPoints(99); assertEquals(30, data.getPoints());
        data.setRisk(-1); assertEquals(0, data.getRisk()); data.setRisk(3); assertEquals(1, data.getRisk()); data.setRisk(.1236); assertEquals(.124, data.getRisk());
        data.setActiveCategories(new ArrayList<>(List.of("ore"))); assertTrue(data.isCategoryActive("ore")); assertFalse(data.isCategoryActive("money"));
        data.setActiveCategories(null); assertTrue(data.getActiveCategories().isEmpty());
        data.setRecentClues(null); assertTrue(data.getRecentClues().isEmpty());
        data.setLastCriticalClueAtByTarget(null); assertTrue(data.getLastCriticalClueAtByTarget().isEmpty());
        data.setPaperKeyCooldownExpiryByDoorUuid(null); assertTrue(data.getPaperKeyCooldownExpiryByDoorUuid().isEmpty());
    }
    @Test void riskGainUsesSourceSpecificRangesAndOnlyScalesChestRisk() {
        var data = new PlayerData(UUID.randomUUID());
        try (var calculator = mockStatic(RiskCalculator.class)) {
            calculator.when(() -> RiskCalculator.computeDecay(anyInt(), anyLong(), anyLong())).thenReturn(.1);
            data.setRisk(.5); data.setLastRiskDecayMs(1); data.applyRiskDecay(20); assertEquals(.4, data.getRisk()); assertTrue(data.getLastRiskDecayMs() > 1);
            calculator.when(() -> RiskCalculator.computeDecay(anyInt(), anyLong(), anyLong())).thenReturn(0.0);
            calculator.when(() -> RiskCalculator.computeGain(anyInt(), anyDouble(), anyDouble(), anyDouble())).thenReturn(.05);
            data.addRiskGain(20, .2, RiskSource.CHEST, 2);
            calculator.verify(() -> RiskCalculator.computeGain(20, .2, Cache.riskGainChestMin * 2, Cache.riskGainChestMax * 2));
            data.addRiskGain(20, .2, RiskSource.CHEST, -1); calculator.verify(() -> RiskCalculator.computeGain(20, .2, 0, 0));
            data.addRiskGain(20, .2, RiskSource.PICKPOCKET, 5); calculator.verify(() -> RiskCalculator.computeGain(20, .2, Cache.riskGainPickpocketMin, Cache.riskGainPickpocketMax));
            calculator.clearInvocations();
            data.addRiskGain(20, .2, RiskSource.DOOR); calculator.verify(() -> RiskCalculator.computeGain(20, .2, Cache.riskGainDoorMin, Cache.riskGainDoorMax));
            assertEquals(.6, data.getRisk());
            calculator.when(() -> RiskCalculator.computeCritical(.6, 20, .2)).thenReturn(.3);
            assertEquals(.3, data.getCriticalChance(20, .2));
        }
    }
    @Test void paperKeyCooldownsRoundUpExpireAndIgnoreInvalidRequests() {
        var data = new PlayerData(UUID.randomUUID());
        assertEquals(0, data.getPaperKeyCooldownRemainingMinutes(null)); assertFalse(data.isPaperKeyOnCooldown("missing"));
        data.recordPaperKeyCooldown(null, 5); data.recordPaperKeyCooldown("zero", 0); data.recordPaperKeyCooldown("negative", -1);
        assertTrue(data.getPaperKeyCooldownExpiryByDoorUuid().isEmpty());
        data.recordPaperKeyCooldown("door", 2); assertTrue(data.isPaperKeyOnCooldown("door")); assertEquals(2, data.getPaperKeyCooldownRemainingMinutes("door"));
        data.setPaperKeyCooldownExpiryByDoorUuid(new HashMap<>(Map.of("expired", 1L, "partial", System.currentTimeMillis()+30_000)));
        assertEquals(1, data.getPaperKeyCooldownRemainingMinutes("partial")); assertEquals(0, data.getPaperKeyCooldownRemainingMinutes("expired"));
        assertFalse(data.getPaperKeyCooldownExpiryByDoorUuid().containsKey("expired"));
    }
    @Test void recentCluesPruneExpiredInvalidAndOldestPerTarget() {
        var data = new PlayerData(UUID.randomUUID()); long now = System.currentTimeMillis();
        var old = new RecentClueEntry("old", now-10_000, "door");
        data.setRecentClues(new ArrayList<>(List.of(old, new RecentClueEntry("newer", now-5_000, "door"),
            new RecentClueEntry("other", now, "other"), new RecentClueEntry("expired", 1, "door"),
            new RecentClueEntry("invalid", now, null), new RecentClueEntry("empty", now, ""))));
        assertEquals(List.of("newer", "old"), data.getRecentCluesForExclude("door"));
        data.recordClueUsed(null, "door"); data.recordClueUsed("", "door"); data.recordClueUsed("bad", null);
        assertEquals(3, data.getRecentClues().size()); data.recordClueUsed("latest", "door");
        assertEquals(List.of("latest", "newer"), data.getRecentCluesForExclude("door"));
        assertEquals(List.of("other"), data.getRecentCluesForExclude("other"));
        assertFalse(data.getRecentClues().contains(old)); data.recordClueUsed("first", "fresh"); assertEquals(List.of("first"), data.getRecentCluesForExclude("fresh"));
    }
    @Test void criticalCooldownsAreTargetSpecificAndCanBeClearedTogether() {
        var data = new PlayerData(UUID.randomUUID());
        assertFalse(data.isCriticalOnCooldown(null)); assertFalse(data.isCriticalOnCooldown("door")); data.recordCriticalClue(null);
        assertTrue(data.getLastCriticalClueAtByTarget().isEmpty()); data.recordCriticalClue("door"); assertTrue(data.isCriticalOnCooldown("door"));
        data.setLastCriticalClueAtByTarget(new HashMap<>(Map.of("expired", 1L))); assertFalse(data.isCriticalOnCooldown("expired")); assertTrue(data.getLastCriticalClueAtByTarget().isEmpty());
        data.recordCriticalClue("door"); data.recordPaperKeyCooldown("door", 5); data.recordClueUsed("clue", "door"); data.clearCooldowns();
        assertTrue(data.getRecentClues().isEmpty()); assertTrue(data.getLastCriticalClueAtByTarget().isEmpty()); assertTrue(data.getPaperKeyCooldownExpiryByDoorUuid().isEmpty());
    }
    @Test void pointGainCapsAndAdvancesOnlyCompletedIntervals() {
        var data = new PlayerData(UUID.randomUUID()); data.setPoints(28);
        long start = System.currentTimeMillis() - 3 * 3_600_000L - 10_000; data.setLastGain(start);
        assertEquals(2, data.applyPointGain()); assertEquals(30, data.getPoints()); assertEquals(start + 3 * 3_600_000L, data.getLastGain());
        assertEquals(0, data.applyPointGain()); data.setLastGain(start); assertEquals(0, data.applyPointGain()); assertEquals(start + 3 * 3_600_000L, data.getLastGain());
        Cache.pointGainIntervalHours = 0; data.setLastGain(start); assertEquals(0, data.applyPointGain()); assertEquals(start, data.getLastGain());
    }
    @Test void loadedLegacyProfilesNormalizeNullsAndClampInvalidValues() {
        var data = new Gson().fromJson("""
            {"points":80,"risk":1.5,"lastRiskDecayMs":0,"activeCategories":null,
             "recentClues":null,"lastCriticalClueAtByTarget":null,"paperKeyCooldownExpiryByDoorUuid":null}
            """, PlayerData.class);
        assertTrue(data.normalizeAfterLoad()); assertEquals(30, data.getPoints()); assertEquals(1, data.getRisk());
        assertTrue(data.getLastRiskDecayMs() > 0); assertTrue(data.getActiveCategories().isEmpty());
        assertTrue(data.getRecentClues().isEmpty()); assertTrue(data.getLastCriticalClueAtByTarget().isEmpty()); assertTrue(data.getPaperKeyCooldownExpiryByDoorUuid().isEmpty());
        assertFalse(data.normalizeAfterLoad());
    }
    @Test void normalizationMigratesDeduplicatesAndPrunesLoadedProfiles() {
        var data = new PlayerData(UUID.randomUUID()); long now = System.currentTimeMillis();
        data.setRecentClues(new ArrayList<>(List.of(new RecentClueEntry("null", now, null), new RecentClueEntry("empty", now, ""), new RecentClueEntry("ok", now, "door"))));
        data.setLastCriticalClueAtByTarget(new HashMap<>(Map.of("old", 1L, "current", now)));
        data.setPaperKeyCooldownExpiryByDoorUuid(new HashMap<>(Map.of("old", 1L, "current", now+60_000)));
        data.setActiveCategories(new ArrayList<>(List.of("ac_armour_tier_1", "ac_armor_tier_1", "ac_weapons_tier_2", "ac_bows_tier_3", "ac_bows_tier_9", "ore", "ore", "unknown")));
        try (var categories = mockStatic(CategoryLoader.class)) {
            for (String id : List.of("tier_1_armor", "tier_2_weapons", "tier_3_bows", "ore")) {
                var category = mock(ItemCategory.class); when(category.getCost()).thenReturn(2); categories.when(() -> CategoryLoader.getById(id)).thenReturn(category);
            }
            assertFalse(data.normalizeAfterLoad()); assertEquals(List.of("tier_1_armor", "tier_2_weapons", "tier_3_bows", "ore"), data.getActiveCategories()); assertEquals(8, data.getAllocatedCost());
        }
        assertEquals(List.of("ok"), data.getRecentCluesForExclude("door")); assertEquals(Set.of("current"), data.getLastCriticalClueAtByTarget().keySet()); assertEquals(Set.of("current"), data.getPaperKeyCooldownExpiryByDoorUuid().keySet());
    }
    @Test void capRemovesLastAllocatedCategoriesAndIgnoresUnknownIds() {
        var data = new PlayerData(UUID.randomUUID()); data.setActiveCategories(new ArrayList<>(List.of("first", "second", "missing")));
        try (var categories = mockStatic(CategoryLoader.class)) {
            var category = mock(ItemCategory.class); when(category.getCost()).thenReturn(20);
            categories.when(() -> CategoryLoader.getById("first")).thenReturn(category); categories.when(() -> CategoryLoader.getById("second")).thenReturn(category);
            assertEquals(40, data.getAllocatedCost()); assertTrue(data.enforceCategoryPointsCap()); assertEquals(List.of("first"), data.getActiveCategories());
            assertFalse(data.enforceCategoryPointsCap());
        }
    }
    @Test void clueEntryBeanSupportsPersistenceRoundTrip() {
        var entry = new RecentClueEntry(); entry.setText("footprint"); entry.setTargetKey("chest"); entry.setUsedAtMs(123);
        entry = new Gson().fromJson(new Gson().toJson(entry), RecentClueEntry.class);
        assertEquals("footprint", entry.getText()); assertEquals("chest", entry.getTargetKey()); assertEquals(123, entry.getUsedAtMs());
    }
}
