package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.testsupport.FactionFixture;

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
    void simpleFactionsWithLockGuildInstallsTheBridge() {
        when(plugins.getPlugin("SimpleFactions")).thenReturn(mock(Plugin.class));
        assertTrue(LockStartup.start(stores, log));
        assertFalse(LockGuildBridge.degraded());
        assertInstanceOf(SimpleFactionsLockGuilds.class, LockGuildBridge.get());
        verify(log, never()).warning(contains("lockGuild"));
    }
}
