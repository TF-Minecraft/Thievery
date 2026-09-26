package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class DoorDataManagerTest {
    @TempDir Path temp;
    private DoorDataManager manager;
    private Location location;
    private Path file;
    private Logger logger;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() throws Exception {
        manager = new DoorDataManager();
        Field folder = DoorDataManager.class.getDeclaredField("dataFolder");
        folder.setAccessible(true);
        folder.set(manager, temp.toFile()); // Exercise real IO without touching plugin data.
        location = mock(Location.class);
        Chunk chunk = mock(Chunk.class);
        when(location.getBlockX()).thenReturn(-17);
        when(location.getBlockY()).thenReturn(65);
        when(location.getBlockZ()).thenReturn(33);
        when(location.getChunk()).thenReturn(chunk);
        when(chunk.getX()).thenReturn(-2);
        when(chunk.getZ()).thenReturn(2);
        file = temp.resolve("-2_2/-17_65_33.json");
        logger = mock(Logger.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
    }

    @AfterEach
    void cleanUp() {
        bukkit.close();
    }

    @Test
    void missingDoorReturnsNullAndCannotBeDeleted() {
        assertNull(manager.loadDoorData(location));
        assertFalse(manager.deleteDoorData(location));
        verifyNoInteractions(logger);
    }

    @Test
    void roundTripPersistsAllFieldsAndExistingFileCanBeReplacedAndDeleted() throws Exception {
        UUID owner = UUID.randomUUID();
        DoorData original = new DoorData(location, "copper", 0.75, owner);
        original.setUnlockExpiryMs(1_800_000_000_000L);
        manager.saveDoorData(original);
        assertTrue(Files.isRegularFile(file));
        DoorData loaded = manager.loadDoorData(location);
        assertSame(location, loaded.getLocation());
        assertEquals("copper", loaded.getKey());
        assertEquals(0.75, loaded.getStrength());
        assertEquals(owner, loaded.getOwnerUUID());
        assertEquals(1_800_000_000_000L, loaded.getUnlockExpiryMs());
        manager.saveDoorData(new DoorData(location, "iron", 0.9, null));
        loaded = manager.loadDoorData(location);
        assertEquals("iron", loaded.getKey());
        assertEquals(0.9, loaded.getStrength());
        assertNull(loaded.getOwnerUUID());
        assertNull(loaded.getUnlockExpiryMs());
        assertTrue(manager.deleteDoorData(location));
        assertFalse(manager.deleteDoorData(location));
        assertFalse(Files.exists(file));
        assertNull(manager.loadDoorData(location));
        verifyNoInteractions(logger);
    }

    @Test
    void legacyDoorWithoutOwnerOrUnlockExpiryLoadsWithNulls() throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"key\":\"legacy\",\"strength\":0.3}");
        DoorData loaded = manager.loadDoorData(location);
        assertEquals("legacy", loaded.getKey());
        assertEquals(0.3, loaded.getStrength());
        assertNull(loaded.getOwnerUUID());
        assertNull(loaded.getUnlockExpiryMs());
    }

    @Test
    void directoryInPlaceOfDataFileLogsReadAndWriteFailuresAndCannotDeleteWhenNonempty() throws Exception {
        Files.createDirectories(file);
        Files.writeString(file.resolve("keep"), "preserve");
        assertNull(manager.loadDoorData(location));
        manager.saveDoorData(new DoorData(location, "copper", 0.5, UUID.randomUUID()));
        assertFalse(manager.deleteDoorData(location));
        assertEquals("preserve", Files.readString(file.resolve("keep")));
        verify(logger).warning(contains("Failed to load door data"));
        verify(logger).warning(contains("Failed to save door data"));
    }

    @Test
    void invalidParentPathLogsSaveFailureWithoutReplacingTheBlockingFile() throws Exception {
        Files.writeString(file.getParent(), "preserve");
        manager.saveDoorData(new DoorData(location, "key", 1, null));
        assertEquals("preserve", Files.readString(file.getParent()));
        verify(logger).warning(contains("Failed to save door data"));
    }
}
