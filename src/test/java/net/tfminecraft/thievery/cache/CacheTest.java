package net.tfminecraft.thievery.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CacheTest {
    private final Map<Field, Map<Object, Object>> originalTables = new LinkedHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void saveTables() throws Exception {
        for (String name : new String[] {"QUALITY_PERCENT", "AURA_PERCENT", "AURA_MINS"}) {
            Field field = Cache.class.getDeclaredField(name);
            field.setAccessible(true);
            originalTables.put(field, new HashMap<>((Map<Object, Object>) field.get(null)));
        }
        Cache.putDefaultItemValueTables();
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void restoreTables() throws Exception {
        for (Map.Entry<Field, Map<Object, Object>> entry : originalTables.entrySet()) {
            Map<Object, Object> table = (Map<Object, Object>) entry.getKey().get(null);
            table.clear();
            table.putAll(entry.getValue());
        }
    }

    @Test
    void defaultQualityAndAuraModifiersMatchEconomyConfiguration() {
        assertEquals(-0.2, Cache.qualityPercent("rusted"));
        assertEquals(0, Cache.qualityPercent("tempered"));
        assertEquals(0.25, Cache.qualityPercent("polished"));
        assertEquals(0.5, Cache.qualityPercent("gleaming"));
        assertEquals(0.75, Cache.qualityPercent("masterwork"));
        assertEquals(0, Cache.auraPercent(0));
        assertEquals(0, Cache.auraPercent(1));
        assertEquals(0.15, Cache.auraPercent(2));
        assertEquals(0.4, Cache.auraPercent(3));
        assertEquals(0.75, Cache.auraPercent(4));
        assertEquals(0, Cache.auraPercent(99));
    }

    @Test
    void qualityIdentifiersAreTrimmedCaseInsensitiveAndLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Cache.clearQualityPercents();
            Cache.putQualityPercent("  SHINING  ", 0.6);
            assertEquals(0.6, Cache.qualityPercent(" shining "));
            assertEquals(0.6, Cache.qualityPercent("SHINING"));
            Cache.putQualityPercent("shining", -0.1);
            assertEquals(-0.1, Cache.qualityPercent("SHINING"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void qualityIgnoresMissingIdentifiersAndClearingRemovesExistingValues() {
        Cache.putQualityPercent(null, 3);
        Cache.putQualityPercent(" \t ", 3);
        assertEquals(0, Cache.qualityPercent(null));
        assertEquals(0, Cache.qualityPercent(" \t "));
        assertEquals(0, Cache.qualityPercent("unknown"));
        Cache.clearQualityPercents();
        assertEquals(0, Cache.qualityPercent("masterwork"));
    }

    @Test
    void auraPercentTablesCanBeReplacedAndCleared() {
        Cache.clearAuraPercents();
        assertEquals(0, Cache.auraPercent(4));
        Cache.putAuraPercent(4, 0.2);
        assertEquals(0.2, Cache.auraPercent(4));
        Cache.putAuraPercent(4, 0.3);
        assertEquals(0.3, Cache.auraPercent(4));
        Cache.putAuraPercent(-1, -0.2);
        assertEquals(-0.2, Cache.auraPercent(-1));
    }

    @Test
    void auraBandsUseThresholdToleranceAndHighestEligibleBand() {
        assertEquals(0, Cache.auraBand(-1));
        assertEquals(0, Cache.auraBand(9.9998));
        assertEquals(1, Cache.auraBand(9.99995));
        assertEquals(1, Cache.auraBand(10));
        assertEquals(1, Cache.auraBand(39));
        assertEquals(2, Cache.auraBand(40));
        assertEquals(3, Cache.auraBand(75));
        assertEquals(4, Cache.auraBand(110));
        assertEquals(4, Cache.auraBand(1000));
    }

    @Test
    void auraMinimumConfigurationRejectsNonpositiveBandsAndClampsNegativeFill() {
        Cache.clearAuraMins();
        assertEquals(0, Cache.auraBand(1000));
        Cache.putAuraMin(0, 0);
        Cache.putAuraMin(-1, 0);
        assertEquals(0, Cache.auraBand(0));
        Cache.putAuraMin(3, -5);
        assertEquals(0, Cache.auraBand(-1));
        assertEquals(3, Cache.auraBand(0));
        Cache.putAuraMin(1, 50);
        assertEquals(3, Cache.auraBand(50));
        Cache.putAuraMin(3, 100);
        assertEquals(1, Cache.auraBand(50));
        assertEquals(3, Cache.auraBand(100));
    }

    @Test
    void resettingDefaultsRemovesCustomEntriesFromEveryTable() {
        Cache.putQualityPercent("custom", 4);
        Cache.putAuraPercent(9, 4);
        Cache.putAuraMin(9, 0);
        Cache.putDefaultItemValueTables();
        assertEquals(0, Cache.qualityPercent("custom"));
        assertEquals(0, Cache.auraPercent(9));
        assertEquals(4, Cache.auraBand(1000));
        assertEquals(0.75, Cache.qualityPercent("masterwork"));
    }
}
