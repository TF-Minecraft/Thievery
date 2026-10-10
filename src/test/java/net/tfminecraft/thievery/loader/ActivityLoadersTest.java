package net.tfminecraft.thievery.loader;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.tfminecraft.thievery.steal.StealIgnoreRules;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ActivityLoadersTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void restoreDefaults() {
        RobberyLoader.load(new YamlConfiguration());
        PickpocketLoader.load(new YamlConfiguration());
        StealIgnoreRules.load(List.of());
        MockBukkit.unmock();
    }

    @Test
    void emptyConfigurationSuppliesActivityDefaults() {
        RobberyLoader.load(new YamlConfiguration());
        PickpocketLoader.load(new YamlConfiguration());
        assertEquals(List.of(), RobberyLoader.getTraits());
        assertEquals(300, RobberyLoader.getBudget());
        assertEquals(3, RobberyLoader.getCooldownDays());
        assertEquals(120, RobberyLoader.getDurationSeconds());
        assertEquals(4, RobberyLoader.getMaxDistance());
        assertEquals(30, RobberyLoader.getAcceptTimeoutSeconds());
        assertEquals(10, RobberyLoader.getPouchClickAmount());
        assertEquals(100, RobberyLoader.getPouchShiftAmount());
        assertEquals(List.of("Slot"), RobberyLoader.getIgnoreNameContains());
        assertTrue(PickpocketLoader.isMinigameEnabled());
        assertEquals(30.0, PickpocketLoader.getMinigameTimeLimitSeconds());
        assertEquals(List.of("Slot"), StealIgnoreRules.getNameContains());
        assertEquals(List.of("thief"), PickpocketLoader.getTraits());
        assertEquals(10, PickpocketLoader.getBudget());
        assertEquals(3_600_000L, PickpocketLoader.getCooldownMillis());
        assertEquals(4, PickpocketLoader.getMaxDistance());
        assertEquals(ThieveryTexts.formatGui("#d65c5cYou catch {character_name} going through your pockets"),
                PickpocketLoader.getAlertCritical());
    }

    @Test
    void configuredActivitiesOverrideDefaultsAndExposeReadOnlyLists() throws Exception {
        YamlConfiguration config = yaml("""
                robbery:
                  traits: [bandit, outlaw]
                  budget: 123.5
                  cooldown-days: 7
                  duration-seconds: 90
                  max-distance: 6.5
                  accept-timeout-seconds: 15
                  ignore:
                    name-contains: [Protected]
                pickpocket:
                  traits: [rogue]
                  budget: 25.5
                  cooldown-hours: 1000000
                  max-distance: 2.5
                  alert-critical: '#d65c5cCaught {character_name}'
                  minigame:
                    enabled: false
                    time-limit-seconds: 12.5
                  ignore:
                    name-contains: [Soulbound]
                """);
        RobberyLoader.load(config);
        assertEquals(List.of("Protected"), StealIgnoreRules.getNameContains());
        PickpocketLoader.load(config);
        assertEquals(List.of("bandit", "outlaw"), RobberyLoader.getTraits());
        assertEquals(123.5, RobberyLoader.getBudget());
        assertEquals(7, RobberyLoader.getCooldownDays());
        assertEquals(90, RobberyLoader.getDurationSeconds());
        assertEquals(6.5, RobberyLoader.getMaxDistance());
        assertEquals(15, RobberyLoader.getAcceptTimeoutSeconds());
        assertEquals(List.of("Protected"), RobberyLoader.getIgnoreNameContains());
        assertEquals(List.of("Soulbound"), StealIgnoreRules.getNameContains());
        assertEquals(List.of("rogue"), PickpocketLoader.getTraits());
        assertEquals(25.5, PickpocketLoader.getBudget());
        assertEquals(3_600_000_000_000L, PickpocketLoader.getCooldownMillis());
        assertEquals(2.5, PickpocketLoader.getMaxDistance());
        assertEquals(ThieveryTexts.formatGui("#d65c5cCaught {character_name}"), PickpocketLoader.getAlertCritical());
        assertFalse(PickpocketLoader.isMinigameEnabled());
        assertEquals(12.5, PickpocketLoader.getMinigameTimeLimitSeconds());
        config.set("pickpocket.minigame.time-limit-seconds", 2);
        PickpocketLoader.load(config);
        assertEquals(5.0, PickpocketLoader.getMinigameTimeLimitSeconds());
        assertThrows(UnsupportedOperationException.class, () -> RobberyLoader.getTraits().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> RobberyLoader.getIgnoreNameContains().clear());
        assertThrows(UnsupportedOperationException.class, () -> PickpocketLoader.getTraits().clear());
    }

    @Test
    void legacyCooldownUsesMostSpecificConfiguredKey() throws Exception {
        YamlConfiguration config = yaml("cooldown: 9\n");
        RobberyLoader.load(config);
        assertEquals(9, RobberyLoader.getCooldownDays());
        config.set("player-steal.cooldown-days", 5);
        RobberyLoader.load(config);
        assertEquals(5, RobberyLoader.getCooldownDays());
        config.set("robbery.cooldown-days", 2);
        RobberyLoader.load(config);
        assertEquals(2, RobberyLoader.getCooldownDays());
    }

    @Test
    void emptyPickpocketIgnoreListPreservesRobberyRulesAndEmptyTraitsUseThief() throws Exception {
        YamlConfiguration config = yaml("""
                robbery:
                  ignore:
                    name-contains: [DoNotSteal]
                pickpocket:
                  traits: []
                  ignore:
                    name-contains: []
                """);
        RobberyLoader.load(config);
        PickpocketLoader.load(config);
        assertEquals(List.of("DoNotSteal"), StealIgnoreRules.getNameContains());
        assertEquals(List.of("thief"), PickpocketLoader.getTraits());
    }

    private static YamlConfiguration yaml(String content) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(content);
        return config;
    }
}
