package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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

class ContainerDataManagerTest {
    @TempDir Path temp;
    private ContainerDataManager manager;
    private Location location;
    private Path file;
    private Logger logger;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() throws Exception {
        manager = managerAt(temp);
        location = location(12345, 64, -6789);
        file = fileAt(location);
        manager.deleteContainerData(location); // Static owner cache is shared between manager instances.
        logger = mock(Logger.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
    }

    @AfterEach
    void cleanUp() {
        manager.deleteContainerData(location);
        bukkit.close();
    }

    @Test
    void roundTripPersistsOwnerLockAndAccessDatesAndOverwritesExistingFiles() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID visitor = UUID.randomUUID();
        ContainerData data = new ContainerData(location, owner);
        data.setLockState(LockState.PRIVATE);
        data.updateAccess(visitor, "2026-09-26T12:34:56Z");
        manager.saveContainerData(data);
        assertTrue(Files.isRegularFile(file));
        ContainerData loaded = managerAt(temp).loadContainerData(location);
        assertSame(location, loaded.getLocation());
        assertEquals(owner, loaded.getOwner());
        assertEquals(LockState.PRIVATE, loaded.getLockState());
        assertEquals(Map.of(visitor, "2026-09-26T12:34:56Z"), loaded.getAccessMap());
        assertEquals(owner, manager.getOwner(location));
        data.setOwner(null);
        data.setLockState(LockState.GUILD);
        manager.saveContainerData(data);
        assertNull(manager.getOwner(location));
        assertNull(manager.loadContainerData(location).getOwner());
        assertEquals(LockState.GUILD, manager.loadContainerData(location).getLockState());
        verifyNoInteractions(logger);
    }

    @Test
    void missingAndLegacyDataHaveDefaultStateAndMissingOwnersAreCached() throws Exception {
        ContainerData fresh = manager.loadContainerData(location);
        assertSame(location, fresh.getLocation());
        assertNull(fresh.getOwner());
        assertEquals(LockState.PUBLIC, fresh.getLockState());
        assertTrue(fresh.getAccessMap().isEmpty());
        assertNull(manager.getOwner(location));
        UUID owner = UUID.randomUUID();
        write("{\"owner\":\"" + owner + "\"}");
        assertNull(manager.getOwner(location)); // Missing-owner result remains cached until a mutation.
        ContainerData legacy = manager.loadContainerData(location);
        assertEquals(owner, legacy.getOwner());
        assertEquals(LockState.PUBLIC, legacy.getLockState());
        assertTrue(legacy.getAccessMap().isEmpty());
        manager.saveContainerData(legacy);
        assertEquals(owner, manager.getOwner(location));
    }

    @Test
    void diskOwnerLookupCachesAcrossInstancesAndDeleteInvalidatesBothPresentAndAbsentOwners() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        write("{\"owner\":\"" + first + "\"}");
        assertEquals(first, manager.getOwner(location));
        write("{\"owner\":\"" + second + "\"}");
        ContainerDataManager other = managerAt(temp);
        assertEquals(first, other.getOwner(location));
        assertTrue(other.deleteContainerData(location));
        assertFalse(other.deleteContainerData(location));
        write("{\"owner\":\"" + second + "\"}");
        assertEquals(second, manager.getOwner(location));
        assertTrue(manager.deleteContainerData(location));
        write("null");
        assertNull(manager.getOwner(location));
        assertTrue(manager.deleteContainerData(location));
        write("{}");
        assertNull(manager.getOwner(location));
    }

    @Test
    void readFailuresAreLoggedAndRetriedInsteadOfCachingFailure() throws Exception {
        Files.createDirectories(file);
        assertNull(manager.getOwner(location));
        assertNull(manager.loadContainerData(location).getOwner());
        verify(logger).warning(contains("Failed to load container owner"));
        verify(logger).warning(contains("Failed to load container data"));
        Files.delete(file);
        UUID owner = UUID.randomUUID();
        write("{\"owner\":\"" + owner + "\"}");
        assertEquals(owner, manager.getOwner(location));
    }

    @Test
    void failedSaveInvalidatesOldOwnerCacheAndFailedDeleteReportsFalse() throws Exception {
        UUID first = UUID.randomUUID();
        ContainerData data = new ContainerData(location, first);
        manager.saveContainerData(data);
        assertEquals(first, manager.getOwner(location));
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "nonempty directory");
        data.setOwner(UUID.randomUUID());
        manager.saveContainerData(data);
        verify(logger).warning(contains("Failed to save container data"));
        assertNull(manager.getOwner(location));
        assertFalse(manager.deleteContainerData(location));
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        write("{\"owner\":\"" + first + "\"}");
        assertEquals(first, manager.getOwner(location));
    }

    @Test
    void bulkRemovalAndClearUpdateOnlyAccessMapsAndSkipNonJsonFiles() throws Exception {
        UUID target = UUID.randomUUID();
        UUID remaining = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ContainerData data = new ContainerData(location, owner);
        data.setLockState(LockState.FACTION);
        data.updateAccess(target, "yesterday");
        data.updateAccess(remaining, "today");
        manager.saveContainerData(data);
        Path ignored = file.getParent().resolve("notes.txt");
        Files.writeString(ignored, "not json");
        Files.writeString(temp.resolve("root.json"), "not a chunk file");
        manager.removePlayerFromAllAccessMaps(null);
        assertEquals(2, manager.loadContainerData(location).getAccessMap().size());
        manager.removePlayerFromAllAccessMaps(target);
        ContainerData changed = manager.loadContainerData(location);
        assertEquals(Map.of(remaining, "today"), changed.getAccessMap());
        assertEquals(owner, changed.getOwner());
        assertEquals(LockState.FACTION, changed.getLockState());
        String before = Files.readString(file);
        manager.removePlayerFromAllAccessMaps(target);
        assertEquals(before, Files.readString(file));
        manager.clearAllAccessMaps();
        assertTrue(manager.loadContainerData(location).getAccessMap().isEmpty());
        before = Files.readString(file);
        manager.clearAllAccessMaps();
        assertEquals(before, Files.readString(file));
        assertEquals("not json", Files.readString(ignored));
        assertEquals("not a chunk file", Files.readString(temp.resolve("root.json")));
    }

    @Test
    void bulkUpdatesTolerateNullDocumentsMissingMapsAndReadErrors() throws Exception {
        Files.createDirectories(file.getParent());
        Path nullDocument = file.getParent().resolve("null.json");
        Path nullMap = file.getParent().resolve("null-map.json");
        Path unreadable = file.getParent().resolve("directory.json");
        Files.writeString(nullDocument, "null");
        Files.writeString(nullMap, "{\"accessMap\":null}");
        Files.createDirectory(unreadable);
        manager.removePlayerFromAllAccessMaps(UUID.randomUUID());
        manager.clearAllAccessMaps();
        assertEquals("null", Files.readString(nullDocument));
        assertEquals("{\"accessMap\":null}", Files.readString(nullMap));
        verify(logger, times(2)).warning(contains("Failed to update container cooldown data"));
    }

    @Test
    void bulkOperationsHandleMissingRootAndRootThatIsAFile() throws Exception {
        ContainerDataManager missing = managerAt(temp.resolve("absent"));
        missing.removePlayerFromAllAccessMaps(UUID.randomUUID());
        missing.clearAllAccessMaps();
        assertFalse(Files.exists(temp.resolve("absent")));
        Path regularFile = temp.resolve("regular-file");
        Files.writeString(regularFile, "preserve");
        ContainerDataManager invalidRoot = managerAt(regularFile);
        invalidRoot.clearAllAccessMaps();
        assertEquals("preserve", Files.readString(regularFile));
        verifyNoInteractions(logger);
    }

    @Test
    void inaccessibleChunkDoesNotPreventResettingHealthyChunkAccess() throws Exception {
        UUID owner = UUID.randomUUID(), target = UUID.randomUUID(), other = UUID.randomUUID();
        ContainerData saved = new ContainerData(location, owner);
        saved.setLockState(LockState.PRIVATE);
        saved.updateAccess(target, "yesterday");
        saved.updateAccess(other, "today");
        manager.saveContainerData(saved);

        // File.listFiles returns null when a listed directory disappears or becomes unreadable.
        java.io.File root = mock(java.io.File.class);
        java.io.File inaccessible = mock(java.io.File.class);
        when(root.exists()).thenReturn(true);
        when(root.listFiles(any(java.io.FileFilter.class)))
                .thenReturn(new java.io.File[] {inaccessible, file.getParent().toFile()});
        when(inaccessible.listFiles(any(java.io.FilenameFilter.class))).thenReturn(null);
        ContainerDataManager resetting = managerAt(temp);
        Field folder = ContainerDataManager.class.getDeclaredField("dataFolder");
        folder.setAccessible(true);
        folder.set(resetting, root);

        resetting.removePlayerFromAllAccessMaps(target);
        assertEquals(Map.of(other, "today"), manager.loadContainerData(location).getAccessMap());
        resetting.clearAllAccessMaps();
        ContainerData loaded = managerAt(temp).loadContainerData(location);
        assertTrue(loaded.getAccessMap().isEmpty());
        assertEquals(owner, loaded.getOwner());
        assertEquals(LockState.PRIVATE, loaded.getLockState());
        assertTrue(Files.readString(file).contains(owner.toString()));
        verify(inaccessible, times(2)).listFiles(any(java.io.FilenameFilter.class));
        verifyNoInteractions(logger);
    }

    private void write(String json) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private Path fileAt(Location loc) {
        return temp.resolve((loc.getBlockX() >> 4) + "_" + (loc.getBlockZ() >> 4))
                .resolve(loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ() + ".json");
    }

    private static ContainerDataManager managerAt(Path root) throws Exception {
        ContainerDataManager manager = new ContainerDataManager();
        Field folder = ContainerDataManager.class.getDeclaredField("dataFolder");
        folder.setAccessible(true);
        folder.set(manager, root.toFile()); // Redirect real disk persistence into the test sandbox.
        return manager;
    }

    private static Location location(int x, int y, int z) {
        Location location = mock(Location.class);
        Chunk chunk = mock(Chunk.class);
        when(location.getBlockX()).thenReturn(x);
        when(location.getBlockY()).thenReturn(y);
        when(location.getBlockZ()).thenReturn(z);
        when(location.getChunk()).thenReturn(chunk);
        when(chunk.getX()).thenReturn(x >> 4);
        when(chunk.getZ()).thenReturn(z >> 4);
        return location;
    }
}
