package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.estate.EstateBridge;
import net.tfminecraft.thievery.estate.LockEstates;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.EstateService;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;

class LockStartupTest {
    @TempDir Path temp;
    LockStores stores;
    Logger log;
    FactionFixture identity;
    MockedStatic<Bukkit> bukkit;
    PluginManager plugins;
    Path legacy;

    @BeforeEach
    void setUp() throws IOException {
        stores = new LockStores(temp.resolve("data"), temp.resolve("door-data"), temp.resolve("entities"), temp.resolve("migrations"));
        log = mock(Logger.class);
        identity = new FactionFixture();
        plugins = mock(PluginManager.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        bukkit.when(Bukkit::getLogger).thenReturn(log);
        legacy = temp.resolve("data/0_0/1_64_1.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "{\"owner\":\"" + java.util.UUID.randomUUID() + "\",\"lockState\":\"PRIVATE\"}");
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        identity.close();
    }

    @Test
    void readyHeirsMigrateClearTheCacheApplyQueuedDeletionsAndLiftTheGuard() throws IOException {
        Path queued = temp.resolve("entities/gone.json");
        Files.createDirectories(queued.getParent());
        Files.writeString(queued, "{}");
        LockGuard.enter("test");
        LockGuard.queueDeletion(queued);
        try (MockedStatic<net.tfminecraft.thievery.door.ContainerDataManager> cache =
                mockStatic(net.tfminecraft.thievery.door.ContainerDataManager.class)) {
            assertTrue(LockStartup.start(stores, log));
            cache.verify(net.tfminecraft.thievery.door.ContainerDataManager::clearOwnerCache);
        }
        assertEquals(LockGuard.Mode.FULL, LockGuard.mode());
        assertFalse(Files.exists(queued));
        assertTrue(Files.readString(legacy).contains("CHARACTER"));
        assertTrue(LockGuildBridge.degraded());
        verify(log).warning(contains("guild and faction locks deny everyone except staff"));
    }

    @Test
    void tlibsWithoutHeirLookupsKeepsTheGuardAndTouchesNothing() throws IOException {
        identity.identity().when(OwnerIdentity::heirApiPresent).thenReturn(false);
        String before = Files.readString(legacy);
        assertFalse(LockStartup.start(stores, log));
        assertTrue(LockGuard.guarded());
        assertEquals("TLibs is older than 2.4.0", LockGuard.reason());
        assertEquals(before, Files.readString(legacy));
        verify(log).severe(contains("TLibs 2.4.0 or later"));
    }

    @Test
    void heirsNotReadyAtEnableKeepTheGuardForTheProcessAndAskForARestart() throws IOException {
        identity.identity().when(OwnerIdentity::heirsReady).thenReturn(false);
        String before = Files.readString(legacy);
        assertFalse(LockStartup.start(stores, log));
        assertTrue(LockGuard.guarded());
        assertEquals("restart required", LockGuard.reason());
        assertEquals(before, Files.readString(legacy));
        assertFalse(Files.exists(temp.resolve("migrations")));
        verify(log).severe(contains("until a restart"));
    }

    @Test
    void aNewerStoreKeepsTheGuard() throws IOException {
        Files.writeString(temp.resolve("data/data-version"), "9\n");
        assertFalse(LockStartup.start(stores, log));
        assertEquals("the lock stores were written by a newer Thievery", LockGuard.reason());
        verify(log).severe(contains("newer Thievery"));
    }

    @Test
    void aFailedMigrationKeepsTheGuardWithoutRetrying() throws IOException {
        Files.writeString(temp.resolve("migrations"), "a file where the directory should be");
        assertFalse(LockStartup.start(stores, log));
        assertTrue(LockGuard.guarded());
        assertEquals("lock migration failed; restart required", LockGuard.reason());
        verify(log).log(eq(Level.SEVERE), contains("the lock migration failed"), any(IOException.class));
        verify(log, never()).info(contains("Lock migration:"));
    }

    @Test
    void aLinkageErrorDuringTheMigrationKeepsTheGuardWithoutRetrying() throws IOException {
        NoSuchMethodError mismatch = new NoSuchMethodError("CharacterInfo.ref()");
        identity.identity().when(() -> OwnerIdentity.info(any())).thenThrow(mismatch);
        String before = Files.readString(legacy);
        assertFalse(LockStartup.start(stores, log));
        assertTrue(LockGuard.guarded());
        assertEquals("lock migration failed; restart required", LockGuard.reason());
        assertEquals(before, Files.readString(legacy));
        verify(log).log(eq(Level.SEVERE), contains("the lock migration failed"), same(mismatch));
        verify(log, never()).info(contains("Lock migration:"));
    }

    @Test
    void simpleFactionsWithLockGuildInstallsTheBridge() {
        when(plugins.getPlugin("SimpleFactions")).thenReturn(mock(Plugin.class));
        assertTrue(LockStartup.start(stores, log));
        assertFalse(LockGuildBridge.degraded());
        assertInstanceOf(SimpleFactionsLockGuilds.class, LockGuildBridge.get());
        verify(log, never()).warning(contains("lockGuild"));
    }

    @Test
    void decidedEstatesMoveInTheBootPassAndAreReportedOnceLocksAreFull() throws IOException {
        java.util.UUID account = java.util.UUID.randomUUID(), dead = java.util.UUID.randomUUID();
        java.util.UUID heir = java.util.UUID.randomUUID(), deathId = java.util.UUID.randomUUID();
        Path chest = temp.resolve("data/0_0/5_64_5.json");
        Files.writeString(chest, "{\"owner\":\"" + dead + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"PRIVATE\"}");
        EstateDecision decision = new EstateDecision(deathId, new CharacterRef(account, dead), Optional.empty(),
                Instant.EPOCH, Optional.of(new CharacterRef(account, heir)), Optional.empty(), Optional.empty(),
                Recipient.FROZEN, Recipient.character(new CharacterRef(account, heir)), Recipient.FROZEN,
                Recipient.SENDER, 2);
        EstateService service = mock(EstateService.class);
        when(service.decided()).thenReturn(List.of(decision));
        when(service.decision(deathId)).thenReturn(Optional.of(decision));
        when(service.stateOf(dead)).thenReturn(Optional.of(EstateState.DECIDED));
        doAnswer(call -> {
            assertEquals(LockGuard.Mode.FULL, LockGuard.mode(), "reported once locks are full");
            return null;
        }).when(service).report(any(), any(), anyLong(), any());
        CharacterIdentity characters = mock(CharacterIdentity.class);
        when(characters.info(dead)).thenReturn(Optional.of(FactionFixture.info(dead, account, CharacterStatus.DEAD)));
        when(characters.deathId(dead)).thenReturn(Optional.of(deathId));
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(EstateService.class)).thenReturn(service);
        when(services.load(CharacterIdentity.class)).thenReturn(characters);
        bukkit.when(Bukkit::getServicesManager).thenReturn(services);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);

        assertTrue(LockStartup.start(stores, log));

        assertTrue(Files.readString(chest).contains(heir.toString()));
        verify(service).report(deathId, "thievery", 2L,
                Outcome.done(Map.of("containers", 1), List.of("data/0_0/5_64_5.json")));
        verify(log).info(contains("Estate " + deathId + " (revision 2): locks DONE, 1 moved in all, 0 still with the testator."));
        assertEquals(Outcome.done(Map.of("containers", 1), List.of("data/0_0/5_64_5.json")),
                ((EstateParticipant) EstateBridge.get()).settle(decision));
    }

    @Test
    void aFailingEstateServiceNeverKeepsTheGuard() throws IOException {
        // Off the main thread the estate facade throws: the planner contains it and the pass still completes.
        assertTrue(LockStartup.start(stores, log));
        assertEquals(LockGuard.Mode.FULL, LockGuard.mode());
        assertTrue(Files.readString(legacy).contains("CHARACTER"));
        verify(log).log(eq(Level.WARNING), contains("estate lock planner failed"), any(IllegalStateException.class));
    }

    @Test
    void aFailedReportIsLoggedAndLocksStayFull() {
        LockEstates.Estate estate = new LockEstates.Estate(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 0, null, null);
        LockEstates estates = mock(LockEstates.class);
        when(estates.decided()).thenReturn(List.of(estate));
        when(estates.report(any())).thenThrow(new IllegalStateException("estate service gone"));
        try (MockedStatic<EstateBridge> bridge = mockStatic(EstateBridge.class)) {
            bridge.when(EstateBridge::probe).thenReturn(true);
            bridge.when(EstateBridge::get).thenReturn(estates);
            assertTrue(LockStartup.start(stores, log));
        }
        assertEquals(LockGuard.Mode.FULL, LockGuard.mode());
        verify(log).log(eq(Level.WARNING), contains("Could not report estate locks"), any(IllegalStateException.class));
    }

    @Test
    void anOlderTLibsLeavesLocksOutOfEstates() {
        try (MockedStatic<EstateBridge> bridge = mockStatic(EstateBridge.class)) {
            bridge.when(EstateBridge::probe).thenReturn(false);
            bridge.when(EstateBridge::get).thenReturn(LockEstates.NONE);
            assertTrue(LockStartup.start(stores, log));
        }
        verify(log).warning(contains("TLibs is older than 2.6.0, so locks take no part in estates"));
    }
}
