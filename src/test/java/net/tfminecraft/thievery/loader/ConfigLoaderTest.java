package net.tfminecraft.thievery.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.door.LockTypeProfile;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.steal.StealIgnoreRules;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class ConfigLoaderTest {
    @TempDir Path temp;
    private final ConfigLoader loader = new ConfigLoader();
    private MockedStatic<Thievery> thievery;
    private Logger logger;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Thievery plugin = mock(Thievery.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getInstance).thenReturn(plugin);
    }

    @AfterEach
    void restoreDefaults() throws Exception {
        load("");
        Cache.traits = new java.util.ArrayList<>();
        StealIgnoreRules.load(List.of());
        thievery.close();
        MockBukkit.unmock();
    }

    @Test
    void emptyFileLoadsDefaultsAndResetsProfilesAndValueTables() throws Exception {
        Cache.putQualityPercent("custom", 5);
        Parameters.putLockTypeProfile(LockState.PRIVATE, new LockTypeProfile(5, 5, false, 5));
        load("");
        assertEquals(3, Cache.cooldown);
        assertEquals(4, Cache.radius);
        assertEquals(30, Cache.categoryPoints);
        assertEquals(24, Cache.pointGainIntervalHours);
        assertEquals(0.1, Cache.defaultItemValue);
        assertEquals(0, Cache.qualityPercent("custom"));
        assertEquals(0.75, Cache.qualityPercent("masterwork"));
        assertEquals(0.4, Cache.auraPercent(3));
        assertEquals(4, Cache.auraBand(110));
        assertEquals(6, Cache.recentClueMax);
        assertEquals(72, Cache.recentClueCooldownHours);
        assertEquals(24, Cache.criticalCooldownHours);
        assertEquals(0, Cache.minCluesDoor);
        assertEquals(1, Cache.minCluesContainer);
        assertEquals(0.05, Cache.riskGainDoorMin);
        assertEquals(0.15, Cache.riskGainDoorMax);
        assertEquals(0.025, Cache.riskGainChestMin);
        assertEquals(0.075, Cache.riskGainChestMax);
        assertEquals(0.04, Cache.riskGainPickpocketMin);
        assertEquals(0.12, Cache.riskGainPickpocketMax);
        assertEquals(10, Cache.takeClueDivisor);
        assertFalse(Cache.requireOwnerOnline);
        assertFalse(Cache.debugAllowOwnChest);
        assertFalse(Cache.debugCluePreview);
        assertEquals(1, Parameters.chestBaseSuccessChance);
        assertEquals(0.1, Parameters.chestBreakChanceRampPerSlot);
        assertEquals(0.95, Parameters.maxSuccessChance);
        assertEquals(LockTypeProfile.IDENTITY, Parameters.lockTypeProfile(LockState.PRIVATE));
        assertEquals(Set.of(Material.ENDER_CHEST), Parameters.excludedContainerMaterials);
        assertTrue(Parameters.lockableFurnitureIds.isEmpty());
        assertTrue(Parameters.lockableEntityTypes.isEmpty());
        assertEquals(0.5, Parameters.displayLockStrength);
        verifyNoInteractions(logger);
    }

    @Test
    void configuredValuesFlowIntoCachesAndParameters() throws Exception {
        load("""
                cooldown: 8
                lockpick-range: 7
                radius: 99
                category_points: 40
                point_gain_interval: 12
                default_item_value: 2.5
                default_value: 99
                traits: [thief, rogue]
                clues:
                  recent-max: 9
                  recent-cooldown-hours: 48
                  critical-cooldown-hours: 12
                  min-clues-door: -1
                  min-clues-container: -2
                  risk-gain-door: {min: 0.2, max: 0.4}
                  risk-gain-chest: {min: 0.3, max: 0.6}
                  risk-gain-pickpocket: {min: 0.1, max: 0.2}
                  risk-pick-reduction: 0.6
                  risk-decay-per-hour: 0.07
                  critical-base: 0.1
                  critical-risk-weight: 0.7
                  critical-dex-reduction: 0.2
                  critical-strength-reduction: 0.3
                  take-value-scale: 15
                  take-value-max-bonus: 0.4
                  take-clue-divisor: 0
                  critical-clue: '#56ccf2Clue {character_name}'
                lockpicking:
                  chest: {base-success-chance: 0.8, base-chance: 0.1, break-chance-ramp-per-slot: 0.2}
                  max-success-chance: 0.9
                  dex-map: {'0': 0.2, '10': 0.8}
                  require-owner-online: true
                  debug-allow-own-chest: true
                  debug-clue-preview: true
                  door-max-distance: 5
                  lockpick-max-reduction: 0.6
                  min-lock-strength-ratio: 0.7
                  fail-cooldown-ms: 1234
                  door-unlock-window-minutes: 7
                  bar:
                    length: 25
                    max-success-slots: 5
                    min-break-slots: 2
                    max-break-slots: 22
                    base-speed: 3
                    dex-speed-reduction-per-level: 0.04
                    min-speed: 0.2
                    speed-jitter-fraction: 0.1
                    random-flip-chance: 0.05
                  attribute: agility
                  excluded-containers: [' chest ', BARREL]
                  lockable-furniture: [' Safe ', VAULT, '   ']
                  lockable-entities: [' item_frame ', ARMOR_STAND]
                  display-lock-strength: 2
                """);
        assertEquals(8, Cache.cooldown);
        assertEquals(7, Cache.radius);
        assertEquals(40, Cache.categoryPoints);
        assertEquals(12, Cache.pointGainIntervalHours);
        assertEquals(2.5, Cache.defaultItemValue);
        assertEquals(List.of("thief", "rogue"), Cache.traits);
        assertEquals(9, Cache.recentClueMax);
        assertEquals(48, Cache.recentClueCooldownHours);
        assertEquals(12, Cache.criticalCooldownHours);
        assertEquals(0, Cache.minCluesDoor);
        assertEquals(0, Cache.minCluesContainer);
        assertEquals(0.2, Cache.riskGainDoorMin);
        assertEquals(0.4, Cache.riskGainDoorMax);
        assertEquals(0.3, Cache.riskGainChestMin);
        assertEquals(0.6, Cache.riskGainChestMax);
        assertEquals(0.1, Cache.riskGainPickpocketMin);
        assertEquals(0.2, Cache.riskGainPickpocketMax);
        assertEquals(0.6, Cache.riskPickReduction);
        assertEquals(0.07, Cache.riskDecayPerHour);
        assertEquals(0.1, Cache.criticalBase);
        assertEquals(0.7, Cache.criticalRiskWeight);
        assertEquals(0.2, Cache.criticalDexReduction);
        assertEquals(0.3, Cache.criticalStrengthReduction);
        assertEquals(15, Cache.takeValueScale);
        assertEquals(0.4, Cache.takeValueMaxBonus);
        assertEquals(1, Cache.takeClueDivisor);
        assertEquals(ThieveryTexts.formatGui("#56ccf2Clue {character_name}"), Cache.criticalClue);
        assertEquals(0.8, Parameters.chestBaseSuccessChance);
        assertEquals(0.2, Parameters.chestBreakChanceRampPerSlot);
        assertEquals(0.9, Parameters.maxSuccessChance);
        assertEquals(0.5, RiskCalculator.getDexterityLerpValue(5), 1e-12);
        assertTrue(Cache.requireOwnerOnline);
        assertTrue(Cache.debugAllowOwnChest);
        assertTrue(Cache.debugCluePreview);
        assertEquals(5, Parameters.doorMaxDistance);
        assertEquals(0.6, Parameters.lockpickMaxReduction);
        assertEquals(0.7, Parameters.lockpickMinLockStrengthRatio);
        assertEquals(1234, Parameters.lockpickFailCooldownMs);
        assertEquals(420_000, Parameters.doorUnlockWindowMs);
        assertEquals(25, Parameters.barLength);
        assertEquals(5, Parameters.maxSuccessSlots);
        assertEquals(2, Parameters.minBreakSlots);
        assertEquals(22, Parameters.maxBreakSlots);
        assertEquals(3, Parameters.baseBarSpeed);
        assertEquals(0.04, Parameters.dexSpeedReductionPerLevel);
        assertEquals(0.2, Parameters.minBarSpeed);
        assertEquals(0.1, Parameters.speedJitterFraction);
        assertEquals(0.05, Parameters.randomFlipChance);
        assertEquals("agility", Parameters.lockpickAttribute);
        assertEquals(Set.of(Material.CHEST, Material.BARREL), Parameters.excludedContainerMaterials);
        assertEquals(Set.of("safe", "vault"), Parameters.lockableFurnitureIds);
        assertEquals(Set.of(EntityType.ITEM_FRAME, EntityType.ARMOR_STAND), Parameters.lockableEntityTypes);
        assertEquals(1, Parameters.displayLockStrength);
    }

    @Test
    void nonpositiveLockpickBarLengthKeepsASelectableSlot() throws Exception {
        load("lockpicking:\n  bar:\n    length: 0\n");
        assertEquals(1, Parameters.barLength);
        load("lockpicking:\n  bar:\n    length: -4\n");
        assertEquals(1, Parameters.barLength);
    }

    @Test
    void legacyValuesAndPartialRiskSectionsHaveIndependentFallbacks() throws Exception {
        load("""
                radius: 6
                default_value: 0.7
                clues:
                  risk-gain-min: 0.2
                  risk-gain-max: 0.4
                  risk-gain-chest: {max: 0.9}
                  risk-gain-pickpocket: {max: 0.8}
                lockpicking:
                  chest: {base-chance: 0.6}
                  display-lock-strength: -1
                """);
        assertEquals(6, Cache.radius);
        assertEquals(0.7, Cache.defaultItemValue);
        assertEquals(0.2, Cache.riskGainDoorMin);
        assertEquals(0.4, Cache.riskGainDoorMax);
        assertEquals(0.1, Cache.riskGainChestMin);
        assertEquals(0.9, Cache.riskGainChestMax);
        assertEquals(0.04, Cache.riskGainPickpocketMin);
        assertEquals(0.8, Cache.riskGainPickpocketMax);
        assertEquals(0.6, Parameters.chestBaseSuccessChance);
        assertEquals(0, Parameters.displayLockStrength);
        load("clues:\n  risk-gain-door: {min: 0.6, max: 0.8}\n");
        assertEquals(0.3, Cache.riskGainChestMin);
        assertEquals(0.4, Cache.riskGainChestMax);
    }

    @Test
    void configuredTablesReplaceDefaultsAndMalformedBandsProduceWarnings() throws Exception {
        load("""
                item_value:
                  quality: {custom: 0.8}
                  aura: {'6': 0.9, bad: 2}
                  aura_mins: {'6': 50, invalid: 1}
                """);
        assertEquals(0.8, Cache.qualityPercent("custom"));
        assertEquals(0, Cache.qualityPercent("masterwork"));
        assertEquals(0.9, Cache.auraPercent(6));
        assertEquals(0, Cache.auraPercent(3));
        assertEquals(0, Cache.auraBand(49));
        assertEquals(6, Cache.auraBand(50));
        verify(logger).warning("[Thievery] item_value.aura 'bad' is not a band number");
        verify(logger).warning("[Thievery] item_value.aura_mins 'invalid' is not a band number");
        load("item_value: {quality: {}, aura: {}, aura_mins: {}}\n");
        assertEquals(0.75, Cache.qualityPercent("masterwork"));
        assertEquals(0.4, Cache.auraPercent(3));
        assertEquals(4, Cache.auraBand(110));
        load("item_value: {other: 1}\n");
        assertEquals(0.75, Cache.qualityPercent("masterwork"));
    }

    @Test
    void invalidFiltersWarnAndValidLockProfilesSupportScalarDefaults() throws Exception {
        load("""
                lockpicking:
                  excluded-containers: [' ', not_a_material]
                  lockable-entities: [' ', not_an_entity]
                  lock-types:
                    ' ': {}
                    nonsense: {}
                    ' private ': {budget-multiplier: 2, risk-multiplier: 3, critical-risk: false, break-chance-multiplier: 4}
                    guild: scalar
                    faction: {}
                """);
        assertEquals(Set.of(Material.ENDER_CHEST), Parameters.excludedContainerMaterials);
        assertTrue(Parameters.lockableEntityTypes.isEmpty());
        assertEquals(new LockTypeProfile(2, 3, false, 4), Parameters.lockTypeProfile(LockState.PRIVATE));
        assertEquals(LockTypeProfile.IDENTITY, Parameters.lockTypeProfile(LockState.GUILD));
        assertEquals(LockTypeProfile.IDENTITY, Parameters.lockTypeProfile(LockState.FACTION));
        verify(logger).warning("Unknown lockpicking excluded container material: not_a_material");
        verify(logger).warning("Unknown lockpicking lockable entity type: not_an_entity");
        verify(logger).warning("Unknown lockpicking lock type: nonsense");
    }

    @Test
    void failedReadsKeepExistingConfigurationAndReportErrors() throws Exception {
        load("cooldown: 11\ntraits: [retained]\n");
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream errors = new PrintStream(captured)) {
            System.setErr(errors);
            loader.loadConfig(temp.resolve("missing.yml").toFile());
            assertEquals(11, Cache.cooldown);
            load("cooldown: [invalid\n");
            assertEquals(11, Cache.cooldown);
            assertEquals(List.of("retained"), Cache.traits);
        } finally {
            System.setErr(original);
        }
        assertTrue(captured.toString().contains("FileNotFoundException"));
        assertTrue(captured.toString().contains("InvalidConfigurationException"));
        load("");
        assertEquals(List.of("retained"), Cache.traits);
    }

    private void load(String yaml) throws Exception {
        Path file = temp.resolve("config.yml");
        Files.writeString(file, yaml);
        loader.loadConfig(file.toFile());
    }
}
