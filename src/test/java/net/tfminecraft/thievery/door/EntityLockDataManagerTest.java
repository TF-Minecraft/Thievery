package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import net.tfminecraft.thievery.Thievery;
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

    @BeforeEach void setup() {
        var plugin = mock(Thievery.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getInstance).thenReturn(plugin);
        logger = mock(Logger.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
        manager = new EntityLockDataManager();
    }
    @AfterEach void close() { bukkit.close(); thievery.close(); }
    private Path path(UUID id) { return temp.resolve("entities/" + id + ".json"); }

    @Test void ownershipAndLockStateSurviveRestartReplacementAndDeletion() {
        UUID id = UUID.randomUUID();
        var data = new EntityLockData(id);
        UUID owner = UUID.randomUUID();
        data.setOwner(owner);
        data.setLockState(LockState.PRIVATE);
        manager.save(data);
        var restarted = new EntityLockDataManager();
        var loaded = restarted.load(id);
        assertEquals(id, loaded.getEntityId());
        assertEquals(owner, loaded.getOwner());
        assertEquals(LockState.PRIVATE, loaded.getLockState());
        loaded.setLockState(LockState.PUBLIC);
        manager.save(loaded);
        assertEquals(LockState.PUBLIC, restarted.load(id).getLockState());
        assertTrue(restarted.delete(id));
        assertFalse(restarted.delete(id));
        assertNull(restarted.load(id).getOwner());
        assertEquals(LockState.DEFAULT, restarted.load(id).getLockState());
        verifyNoInteractions(logger);
    }
    @Test void legacyMissingAndNullRecordsDefaultToUnclaimedLocks() throws Exception {
        UUID id = UUID.randomUUID();
        assertEquals(id, manager.load(id).getEntityId());
        for (String json : new String[] {"{}", "null", "{\"lockState\":null}"}) {
            Files.writeString(path(id), json);
            var data = manager.load(id);
            assertNull(data.getOwner());
            assertEquals(LockState.DEFAULT, data.getLockState());
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
        assertNull(manager.load(id).getOwner());
        manager.save(new EntityLockData(id));
        assertFalse(manager.delete(id));
        verify(logger).warning(contains("Failed to load entity lock data"));
        verify(logger).warning(contains("Failed to save entity lock data"));
        assertEquals("preserve", Files.readString(path(id).resolve("keep")));
    }
    @Test void savingRecreatesRemovedDataDirectory() throws Exception {
        Files.delete(temp.resolve("entities"));
        UUID id = UUID.randomUUID();
        manager.save(new EntityLockData(id));
        assertTrue(Files.isRegularFile(path(id)));
        assertEquals(id, manager.load(id).getEntityId());
    }
}
