package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.Indyuce.mmocore.api.player.PlayerData;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.Parameters;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class RiskCalculatorTest {
    private final Map<Field, Object> originalCache = new LinkedHashMap<>();
    private Object originalDexterityMap;
    private Locale originalLocale;

    @BeforeEach
    void saveState() throws Exception {
        for (Field field : Cache.class.getFields()) {
            if (field.getType() == double.class) originalCache.put(field, field.get(null));
        }
        Field map = RiskCalculator.class.getDeclaredField("dexterityLerpMap");
        map.setAccessible(true);
        originalDexterityMap = map.get(null);
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        RiskCalculator.loadDexterityLerp(null);
        Cache.riskGainDoorMin = 0.05;
        Cache.riskGainDoorMax = 0.15;
        Cache.riskPickReduction = 0.5;
        Cache.riskDecayPerHour = 0.08;
        Cache.criticalBase = 0.0;
        Cache.criticalRiskWeight = 0.5;
        Cache.criticalDexReduction = 0.15;
        Cache.criticalStrengthReduction = 0.2;
        Cache.takeValueScale = 12;
        Cache.takeValueMaxBonus = 0.35;
        Cache.takeClueDivisor = 10;
    }

    @AfterEach
    void restoreState() throws Exception {
        for (Map.Entry<Field, Object> entry : originalCache.entrySet()) entry.getKey().set(null, entry.getValue());
        Field map = RiskCalculator.class.getDeclaredField("dexterityLerpMap");
        map.setAccessible(true);
        map.set(null, originalDexterityMap);
        Locale.setDefault(originalLocale);
    }

    @Test
    void readsConfiguredDexterityAndFallsBackWhenPlayerDataFails() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        PlayerData data = mock(PlayerData.class, RETURNS_DEEP_STUBS);
        when(data.getAttributes().getInstance(Parameters.lockpickAttribute).getTotal()).thenReturn(27);
        try (MockedStatic<PlayerData> players = mockStatic(PlayerData.class)) {
            players.when(() -> PlayerData.get(id)).thenReturn(data);
            assertEquals(27, RiskCalculator.getDexterity(player));
            players.when(() -> PlayerData.get(id)).thenThrow(new IllegalStateException("unavailable"));
            assertEquals(0, RiskCalculator.getDexterity(player));
        }
        assertEquals(0, RiskCalculator.getDexterity(null));
    }

    @Test
    void defaultInterpolationClampsAndInterpolates() {
        assertEquals(1, RiskCalculator.getDexterityLerpValue(-10));
        assertEquals(1, RiskCalculator.getDexterityLerpValue(0));
        assertEquals(1.5, RiskCalculator.getDexterityLerpValue(20));
        assertEquals(2, RiskCalculator.getDexterityLerpValue(40));
        assertEquals(2, RiskCalculator.getDexterityLerpValue(100));
    }

    @Test
    void configuredInterpolationSortsNumericKeysAndIgnoresMalformedEntries() {
        Map<Object, Object> values = new LinkedHashMap<>();
        values.put(" 20 ", 5);
        values.put(0, 1.0);
        values.put("invalid", 99);
        values.put("10", "invalid");
        RiskCalculator.loadDexterityLerp(values);
        assertEquals(3, RiskCalculator.getDexterityLerpValue(10));
        assertEquals(5, RiskCalculator.getDexterityLerpValue(30));
        RiskCalculator.loadDexterityLerp(Map.of("invalid", 99));
        assertEquals(1.5, RiskCalculator.getDexterityLerpValue(20));
        RiskCalculator.loadDexterityLerp(Map.of());
        assertEquals(2, RiskCalculator.getDexterityLerpValue(40));
    }

    @Test
    void singleConfiguredPointAppliesToEveryDexterity() {
        RiskCalculator.loadDexterityLerp(Map.of(10, 3));
        assertEquals(3, RiskCalculator.getDexterityLerpValue(-5));
        assertEquals(3, RiskCalculator.getDexterityLerpValue(10));
        assertEquals(3, RiskCalculator.getDexterityLerpValue(90));
    }

    @Test
    void gainAppliesDexterityAndClampedPickStrength() {
        assertEquals(0.2, RiskCalculator.computeGain(0, -1, 0.2, 0.2), 1e-12);
        assertEquals(0.075, RiskCalculator.computeGain(40, 0.5, 0.2, 0.2), 1e-12);
        assertEquals(0.05, RiskCalculator.computeGain(40, 2, 0.2, 0.2), 1e-12);
        Cache.riskGainDoorMin = Cache.riskGainDoorMax = 0.4;
        assertEquals(0.4, RiskCalculator.computeGain(0, 0), 1e-12);
    }

    @Test
    void randomGainStaysWithinConfiguredBounds() {
        for (int sample = 0; sample < 100; sample++) {
            double gain = RiskCalculator.computeGain(40, 1, 0.04, 0.12);
            assertTrue(gain >= 0.01 && gain < 0.03, "gain=" + gain);
        }
    }

    @Test
    void decayNeedsForwardTimeAndScalesWithDexterity() {
        assertEquals(0, RiskCalculator.computeDecay(0, 0, 3_600_000));
        assertEquals(0, RiskCalculator.computeDecay(0, -1, 3_600_000));
        assertEquals(0, RiskCalculator.computeDecay(0, 1000, 1000));
        assertEquals(0, RiskCalculator.computeDecay(0, 1000, 999));
        assertEquals(0.08, RiskCalculator.computeDecay(0, 1, 3_600_001), 1e-12);
        assertEquals(0.08, RiskCalculator.computeDecay(40, 1, 1_800_001), 1e-12);
    }

    @Test
    void criticalUsesAllModifiersAndClampsResult() {
        Cache.criticalBase = 0.1;
        assertEquals(0.25, RiskCalculator.computeCritical(1, 40, 1), 1e-12);
        assertEquals(0.6, RiskCalculator.computeCritical(1, 0, -1), 1e-12);
        assertEquals(0.4, RiskCalculator.computeCritical(1, 0, 2), 1e-12);
        assertEquals(0, RiskCalculator.computeCritical(0, 40, 1));
        assertEquals(1, RiskCalculator.computeCritical(10, 0, 0));
    }

    @Test
    void valueRiskHasExponentialSaturationAndRejectsNonpositiveInputs() {
        assertEquals(0, RiskCalculator.computeValueRisk(0));
        assertEquals(0, RiskCalculator.computeValueRisk(-1));
        assertEquals(0.35 * (1 - Math.exp(-1)), RiskCalculator.computeValueRisk(12), 1e-12);
        assertEquals(0.35, RiskCalculator.computeValueRisk(Double.POSITIVE_INFINITY));
        Cache.takeValueScale = 0;
        assertEquals(0, RiskCalculator.computeValueRisk(12));
        Cache.takeValueScale = -1;
        assertEquals(0, RiskCalculator.computeValueRisk(12));
    }

    @Test
    void takeChanceCombinesClampedSessionWithValueAndOptionalDivisor() {
        assertEquals(0.65, RiskCalculator.computeTakeSessionCap(), 1e-12);
        assertEquals(0, RiskCalculator.computeTakeClueChanceRaw(-1, 0));
        assertEquals(0.65, RiskCalculator.computeTakeClueChanceRaw(2, 0), 1e-12);
        assertEquals(1, RiskCalculator.computeTakeClueChanceRaw(1, Double.POSITIVE_INFINITY));
        assertEquals(0.0325, RiskCalculator.computeTakeClueChance(0.5, 0), 1e-12);
        Cache.takeClueDivisor = 1;
        assertEquals(0.325, RiskCalculator.computeTakeClueChance(0.5, 0), 1e-12);
        Cache.takeClueDivisor = 0.5;
        assertEquals(0.325, RiskCalculator.computeTakeClueChance(0.5, 0), 1e-12);
        Cache.takeValueMaxBonus = 2;
        assertEquals(1, RiskCalculator.computeTakeClueChanceRaw(0, Double.POSITIVE_INFINITY));
    }

    @Test
    void previewsExpressJointCriticalChanceUnlessClueIsGuaranteed() {
        RiskCalculator.TakeCluePreview normal = RiskCalculator.computeTakeCluePreview(1, 0, 0, 0);
        assertEquals(0.065, normal.clueChance(), 1e-12);
        assertEquals(0.0325, normal.criticalOnTake(), 1e-12);
        RiskCalculator.TakeCluePreview guaranteed = RiskCalculator.computeTakeCluePreview(1, 0, 0, 0, true);
        assertEquals(1, guaranteed.clueChance());
        assertEquals(0.5, guaranteed.criticalOnTake());
        RiskCalculator.TakeCluePreview safe = RiskCalculator.computeTakeCluePreview(1, 0, 0, 0, false, false);
        assertEquals(normal.clueChance(), safe.clueChance());
        assertEquals(0, safe.criticalOnTake());
        RiskCalculator.TakeCluePreview safeGuaranteed = RiskCalculator.computeTakeCluePreview(1, 0, 0, 0, true, false);
        assertEquals(1, safeGuaranteed.clueChance());
        assertEquals(0, safeGuaranteed.criticalOnTake());
    }

    @Test
    void riskFormattingOmitsNonpositiveComponentsAndSeparatesPresentComponents() {
        assertEquals("12.35%", RiskCalculator.formatPercent(0.123456));
        assertEquals("13%", RiskCalculator.formatPercentWhole(0.125));
        assertEquals(List.of(), RiskCalculator.formatRiskLore(0, -1));
        assertEquals(List.of("§7Risk: §712.50%"), RiskCalculator.formatRiskLore(0.125, 0));
        assertEquals(List.of("§4Critical: §725.00%"), RiskCalculator.formatRiskLore(0, 0.25));
        assertEquals(List.of("§7Risk: §712.50%", "§4Critical: §725.00%"), RiskCalculator.formatRiskLore(0.125, 0.25));
        assertEquals("", RiskCalculator.formatRiskTitle(-1, 0));
        assertEquals("§7Risk: §713%", RiskCalculator.formatRiskTitle(0.125, 0));
        assertEquals("§4Crit: §725%", RiskCalculator.formatRiskTitle(0, 0.25));
        assertEquals("§7Risk: §713% §4Crit: §725%", RiskCalculator.formatRiskTitle(0.125, 0.25));
    }
}
