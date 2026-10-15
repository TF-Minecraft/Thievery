package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockStores;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class EntityLockDataManagerTest {
    @TempDir Path temp;
    private MockedStatic<Thievery> thievery;
    private MockedStatic<Bukkit> bukkit;
    private EntityLockDataManager manager;
    private Logger logger;
    private FactionFixture identity;

    @BeforeEach void setup() {
        identity = new FactionFixture();
        var plugin = mock(Thievery.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getInstance).thenReturn(plugin);
        logger = mock(Logger.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
        manager = new EntityLockDataManager();
    }
    @AfterEach void close() { bukkit.close(); thievery.close(); identity.close(); }
    private Path path(UUID id) { return temp.resolve("entities/" + id + ".json"); }

    @Test void ownershipKindAndLockStateSurviveRestartReplacementAndDeletion() throws Exception {
        UUID id = UUID.randomUUID();
        var data = new EntityLockData(id);
        UUID owner = UUID.randomUUID();
        data.setOwner(LockOwner.character(owner));
        data.setLockState(LockState.PRIVATE);
        manager.save(data);
        assertTrue(Files.readString(path(id)).contains("\"ownerKind\":\"CHARACTER\""));
        var restarted = new EntityLockDataManager();
        var loaded = restarted.load(id);
        assertEquals(id, loaded.getEntityId());
        assertEquals(LockOwner.character(owner), loaded.getOwner());
        assertEquals(LockState.PRIVATE, loaded.getLockState());
        long modified = Files.getLastModifiedTime(path(id)).toMillis();
        Files.setLastModifiedTime(path(id), java.nio.file.attribute.FileTime.fromMillis(modified - 10_000));
        manager.save(loaded);
        assertEquals(modified - 10_000, Files.getLastModifiedTime(path(id)).toMillis(), "unchanged records are not rewritten");
        loaded.setLockState(LockState.PUBLIC);
        manager.save(loaded);
        assertEquals(LockState.PUBLIC, restarted.load(id).getLockState());
        assertTrue(restarted.delete(id));
        assertFalse(restarted.delete(id));
        assertNull(restarted.load(id).getOwner());
        assertEquals(LockState.DEFAULT, restarted.load(id).getLockState());
        verifyNoInteractions(logger);
    }
    @Test void savesAndDeletesKeepTheOwnerIndex() {
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        var data = new EntityLockData(id, LockOwner.character(owner));
        data.setLockState(LockState.FACTION);
        manager.save(data);
        assertEquals(java.util.List.of(new OwnedLocks.Lock(path(id).toAbsolutePath().normalize(), LockStores.Store.ENTITIES,
                owner, LockState.FACTION, null, null, null)), OwnedLocks.of(owner));
        assertTrue(manager.delete(id));
        assertEquals(java.util.List.of(), OwnedLocks.of(owner));
    }
    @Test void missingAndUnownedRecordsAreUnclaimedButBrokenOnesStayLocked() throws Exception {
        UUID id = UUID.randomUUID();
        assertEquals(id, manager.load(id).getEntityId());
        for (String json : new String[] {"{}", "{\"lockState\":null}"}) {
            Files.writeString(path(id), json);
            var data = manager.load(id);
            assertNull(data.getOwner());
            assertEquals(LockState.DEFAULT, data.getLockState());
        }
        UUID owner = UUID.randomUUID();
        for (String json : new String[] {"null", "", "{\"owner\":\"" + owner + "\"}", "{\"owner\":\"x\",\"lockState\":\"PUBLIC\"}"}) {
            Files.writeString(path(id), json);
            var data = manager.load(id);
            assertTrue(data.isUnreadable(), json);
            assertEquals(LockOwner.unreadable(), data.getOwner());
            assertEquals(LockState.PRIVATE, data.getLockState());
            manager.save(data);
            assertEquals(json, Files.readString(path(id)));
        }
        assertNull(manager.load(null).getEntityId());
        manager.save(null);
        manager.save(new EntityLockData(null));
        assertFalse(manager.delete(null));
        try (var files = Files.list(temp.resolve("entities"))) { assertEquals(1, files.count()); }
    }
    @Test void ioFailuresAreReportedWithoutDestroyingExistingData() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectory(path(id));
        Files.writeString(path(id).resolve("keep"), "preserve");
        assertTrue(manager.load(id).isUnreadable());
        manager.save(new EntityLockData(id, LockOwner.character(UUID.randomUUID())));
        assertFalse(manager.delete(id));
        verify(logger).warning(contains("Unreadable entity lock"));
        verify(logger).warning(contains("Failed to save entity lock data"));
        verify(logger).warning(contains("Failed to delete entity lock data"));
        assertEquals("preserve", Files.readString(path(id).resolve("keep")));
    }
    @Test void savingRecreatesRemovedDataDirectoryAndReplacesBrokenFiles() throws Exception {
        Files.delete(temp.resolve("entities"));
        UUID id = UUID.randomUUID();
        manager.save(new EntityLockData(id));
        assertTrue(Files.isRegularFile(path(id)));
        assertEquals(id, manager.load(id).getEntityId());
        Files.writeString(path(id), "{broken");
        manager.save(new EntityLockData(id, LockOwner.server()));
        assertEquals(LockOwner.server(), manager.load(id).getOwner());
    }
    @Test void guardModeFreezesSavesAndQueuesDeathDeletions() throws Exception {
        UUID id = UUID.randomUUID();
        manager.save(new EntityLockData(id, LockOwner.server()));
        LockGuard.enter("test");
        manager.save(new EntityLockData(id, LockOwner.character(UUID.randomUUID())));
        assertEquals(LockOwner.server(), manager.load(id).getOwner());
        assertFalse(manager.delete(id));
        assertTrue(Files.exists(path(id)));
        assertEquals(java.util.List.of(path(id)), LockGuard.pendingDeletions());
        LockGuard.lift(() -> { });
        assertFalse(Files.exists(path(id)));
    }
}
