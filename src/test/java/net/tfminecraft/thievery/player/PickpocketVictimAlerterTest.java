package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.loader.PickpocketLoader;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class PickpocketVictimAlerterTest {
    private Player thief, victim;
    private PlayerData data;
    private MockedStatic<PlayerManager> roleplay;
    private MockedStatic<RiskCalculator> risk;
    private MockedStatic<Database> database;
    private net.tfminecraft.rpcharacters.objects.PlayerData rpData;
    private int originalCooldown;

    @BeforeEach void setUp() {
        var server = MockBukkit.mock();
        thief = mock(Player.class); victim = mock(Player.class);
        when(victim.isOnline()).thenReturn(true);
        when(victim.getLocation()).thenReturn(new Location(server.addSimpleWorld("alerts"), 0, 64, 0));
        data = spy(new PlayerData(UUID.randomUUID()));
        roleplay = mockStatic(PlayerManager.class); risk = mockStatic(RiskCalculator.class); database = mockStatic(Database.class);
        rpData = mock(net.tfminecraft.rpcharacters.objects.PlayerData.class);
        var config = new YamlConfiguration();
        config.set("pickpocket.alert-critical", "&4{character_name} is stealing!");
        PickpocketLoader.load(config);
        originalCooldown = Cache.criticalCooldownHours; Cache.criticalCooldownHours = 24;
    }
    @AfterEach void tearDown() {
        Cache.criticalCooldownHours = originalCooldown;
        PickpocketLoader.load(new YamlConfiguration());
        database.close(); risk.close(); roleplay.close(); MockBukkit.unmock();
    }

    @Test void missingOrOfflineVictimsCauseNoRiskChangesAlertsOrPersistence() {
        PickpocketVictimAlerter.tryAlert(thief, null, data, "target", 7);
        when(victim.isOnline()).thenReturn(false);
        PickpocketVictimAlerter.tryAlert(thief, victim, data, "target", 7);
        verify(data, never()).applyRiskDecay(anyInt());
        verify(victim, never()).sendActionBar(any(Component.class));
        verify(victim, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        roleplay.verifyNoInteractions(); database.verifyNoInteractions();
    }

    @Test void zeroRiskAndZeroCriticalChanceRemainSilentAfterDecay() {
        activeCharacter(); data.setRisk(0);
        PickpocketVictimAlerter.tryAlert(thief, victim, data, "target", 7);
        verify(data).applyRiskDecay(7);
        assertTrue(data.getLastRiskDecayMs() > 0);
        verify(victim, never()).sendActionBar(any(Component.class));
        verify(victim, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        assertTrue(data.getLastCriticalClueAtByTarget().isEmpty()); database.verifyNoInteractions();
    }

    @Test void fullRiskRummagesWithoutTextForRoleplayDataOrAnActiveCharacter() {
        data.setRisk(1);
        PickpocketVictimAlerter.tryAlert(thief, victim, data, "target", 7);
        assertRummage(1);
        roleplay.when(() -> PlayerManager.get(thief)).thenReturn(rpData);
        PickpocketVictimAlerter.tryAlert(thief, victim, data, "target", 7);
        assertRummage(2);
        verify(victim, never()).sendActionBar(any(Component.class));
        database.verifyNoInteractions(); assertTrue(data.getLastCriticalClueAtByTarget().isEmpty());
    }

    @Test void criticalAlertNamesTheCharacterPersistsCooldownAndRummagesOnce() {
        activeCharacter(); data.setRisk(1);
        risk.when(() -> RiskCalculator.computeCritical(1, 7, 0)).thenReturn(1.0);
        long before = System.currentTimeMillis();
        PickpocketVictimAlerter.tryAlert(thief, victim, data, "target", 7);
        assertRummage(1);
        verify(victim).sendActionBar(actionBar("§4Robin is stealing!"));
        assertTrue(data.isCriticalOnCooldown("target"));
        assertTrue(data.getLastCriticalClueAtByTarget().get("target") >= before);
        database.verify(() -> Database.savePlayerData(data));
    }

    @Test void criticalCooldownSuppressesRepeatIdentityButAllowsOrdinaryAlertAndDifferentTargets() {
        activeCharacter(); data.setRisk(1); data.recordCriticalClue("target");
        risk.when(() -> RiskCalculator.computeCritical(1, 7, 0)).thenReturn(1.0);
        long recorded = data.getLastCriticalClueAtByTarget().get("target");
        PickpocketVictimAlerter.tryAlert(thief, victim, data, "target", 7);
        assertRummage(1);
        verify(victim, never()).sendActionBar(any(Component.class));
        assertEquals(recorded, data.getLastCriticalClueAtByTarget().get("target")); database.verifyNoInteractions();
        PickpocketVictimAlerter.tryAlert(thief, victim, data, "other", 7);
        verify(victim).sendActionBar(actionBar("§4Robin is stealing!"));
        assertTrue(data.isCriticalOnCooldown("other")); database.verify(() -> Database.savePlayerData(data));
        assertRummage(2);
    }

    @Test void aFumbledPickAlertsTheVictimOutrightWithoutTouchingRisk() {
        PickpocketVictimAlerter.alert(victim);
        assertRummage(1);
        verify(victim, never()).sendActionBar(any(Component.class));
        verifyNoInteractions(thief);
        roleplay.verifyNoInteractions(); database.verifyNoInteractions();
    }

    private void activeCharacter() {
        RPCharacter character = mock(RPCharacter.class); when(character.getName()).thenReturn("Robin");
        when(rpData.hasActiveCharacter()).thenReturn(true); when(rpData.getActiveCharacter()).thenReturn(character);
        roleplay.when(() -> PlayerManager.get(thief)).thenReturn(rpData);
    }
    private void assertRummage(int times) {
        verify(victim, times(times)).playSound(victim.getLocation(), Sound.ITEM_BUNDLE_REMOVE_ONE, 1f, 0.8f);
        verify(victim, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }
    private static Component actionBar(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }
}
