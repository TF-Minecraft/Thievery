package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class PlayerManagerTest {
    private PlayerManager manager;
    private MockedStatic<Database> database;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<Thievery> thievery;
    private MockedStatic<RiskCalculator> risk;
    private BukkitScheduler scheduler;
    private Thievery plugin;

    @BeforeEach
    void setUp() {
        manager = new PlayerManager();
        database = mockStatic(Database.class);
        bukkit = mockStatic(Bukkit.class);
        thievery = mockStatic(Thievery.class);
        risk = mockStatic(RiskCalculator.class);
        scheduler = mock(BukkitScheduler.class);
        plugin = mock(Thievery.class);
        BukkitTask task = mock(BukkitTask.class);
        when(task.getTaskId()).thenReturn(71);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1200L), eq(1200L))).thenReturn(task);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        thievery.when(Thievery::getInstance).thenReturn(plugin);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        risk.close();
        thievery.close();
        bukkit.close();
        database.close();
    }

    private Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private PlayerData persisted(UUID id) {
        PlayerData data = mock(PlayerData.class);
        database.when(() -> Database.hasPlayerData(id)).thenReturn(true);
        database.when(() -> Database.loadPlayerData(id)).thenReturn(data);
        return data;
    }

    @Test
    void firstLookupCreatesDataAndRepeatedInitializationKeepsIt() {
        UUID id = UUID.randomUUID();
        Player player = player(id);
        assertFalse(manager.exists(id));
        assertFalse(manager.exists(player));
        PlayerData created = manager.get(player);
        assertEquals(id, created.getId());
        assertTrue(manager.exists(player));
        assertSame(created, manager.get(id));
        manager.add(id);
        manager.init(player);
        assertSame(created, manager.get(player));
        database.verify(() -> Database.hasPlayerData(id), times(1));
        database.verify(() -> Database.loadPlayerData(id), never());
    }

    @Test
    void joinLoadsPersistedDataAndQuitSavesThenUnloadsExactlyOnce() {
        UUID id = UUID.randomUUID();
        Player player = player(id);
        PlayerData saved = persisted(id);
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(player);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        manager.onJoin(join);
        assertSame(saved, manager.get(player));
        verify(saved).applyPointGain();
        manager.onQuit(quit);
        assertFalse(manager.exists(id));
        manager.save(id);
        database.verify(() -> Database.savePlayerData(saved), times(1));
    }

    @Test
    void loadedIdsAreASnapshotAndCannotRemoveManagedData() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        manager.add(first);
        Iterable<UUID> snapshot = manager.getLoadedIds();
        manager.add(second);
        List<UUID> copied = new ArrayList<>();
        snapshot.forEach(copied::add);
        assertEquals(List.of(first), copied);
        var iterator = snapshot.iterator();
        iterator.next();
        iterator.remove();
        assertTrue(manager.exists(first));
    }

    @Test
    void loadAllRefreshesOnlineProfilesWhileKeepingOtherLoadedProfiles() {
        UUID online = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PlayerData previous = manager.get(online);
        PlayerData retained = manager.get(other);
        PlayerData refreshed = persisted(online);
        Player onlinePlayer = player(online);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(onlinePlayer));
        manager.loadAll();
        assertNotSame(previous, manager.get(online));
        assertSame(refreshed, manager.get(online));
        assertSame(retained, manager.get(other));
        verify(refreshed).applyPointGain();
    }

    @Test
    void scheduledTickGainsPointsForLoadedProfilesAndDecaysOnlyOnlinePlayers() {
        UUID onlineId = UUID.randomUUID();
        UUID offlineId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        Player online = player(onlineId);
        Player offline = player(offlineId);
        when(online.isOnline()).thenReturn(true);
        when(offline.isOnline()).thenReturn(false);
        PlayerData onlineData = persisted(onlineId);
        PlayerData offlineData = persisted(offlineId);
        PlayerData missingData = persisted(missingId);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(online));
        bukkit.when(() -> Bukkit.getPlayer(onlineId)).thenReturn(online);
        bukkit.when(() -> Bukkit.getPlayer(offlineId)).thenReturn(offline);
        risk.when(() -> RiskCalculator.getDexterity(online)).thenReturn(17);
        manager.start();
        manager.add(offlineId);
        manager.add(missingId);
        clearInvocations(onlineData, offlineData, missingData);
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskTimer(eq(plugin), tick.capture(), eq(1200L), eq(1200L));
        tick.getValue().run();
        verify(onlineData).applyPointGain();
        verify(offlineData).applyPointGain();
        verify(missingData).applyPointGain();
        verify(onlineData).applyRiskDecay(17);
        verify(offlineData, never()).applyRiskDecay(anyInt());
        verify(missingData, never()).applyRiskDecay(anyInt());
    }

    @Test
    void restartingReplacesTimerAndRepeatedStopIsHarmless() {
        manager.stop();
        verify(scheduler, never()).cancelTask(anyInt());
        manager.start();
        manager.start();
        verify(scheduler).cancelTask(71);
        verify(scheduler, times(2)).runTaskTimer(eq(plugin), any(Runnable.class), eq(1200L), eq(1200L));
        manager.stop();
        manager.stop();
        verify(scheduler, times(2)).cancelTask(71);
    }

    @Test
    void unloadAllStopsTimerSavesLoadedOnlinePlayersAndClearsMemory() {
        UUID loadedId = UUID.randomUUID();
        UUID unloadedId = UUID.randomUUID();
        UUID offlineId = UUID.randomUUID();
        manager.start();
        PlayerData loaded = manager.get(loadedId);
        PlayerData offline = manager.get(offlineId);
        List<Player> onlinePlayers = List.of(player(loadedId), player(unloadedId));
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(onlinePlayers);
        manager.unloadAll();
        verify(scheduler).cancelTask(71);
        database.verify(() -> Database.savePlayerData(loaded));
        database.verify(() -> Database.savePlayerData(offline), never());
        assertFalse(manager.getLoadedIds().iterator().hasNext());
        assertFalse(manager.exists(loadedId));
        assertFalse(manager.exists(offlineId));
        assertFalse(manager.exists(unloadedId));
    }
}
