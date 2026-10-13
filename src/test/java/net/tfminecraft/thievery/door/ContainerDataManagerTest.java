package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.owner.DurableFiles;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.OwnerIdentity;
import net.tfminecraft.thievery.testsupport.FactionFixture;

class ContainerDataManagerTest {
    @TempDir Path temp;
    private ContainerDataManager manager;
    private Location location;
    private Path file;
    private Logger logger;
    private MockedStatic<Bukkit> bukkit;
    private FactionFixture identity;

    @BeforeEach
    void setUp() throws Exception {
        identity = new FactionFixture();
        manager = managerAt(temp);
        location = location(12345, 64, -6789);
        file = fileAt(location);
        ContainerDataManager.clearOwnerCache(); // Shared between manager instances.
        logger = mock(Logger.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
    }

    @AfterEach
    void cleanUp() {
        ContainerDataManager.clearOwnerCache();
        bukkit.close();
        identity.close();
    }

    @Test
    void roundTripPersistsOwnerKindLockAndAccessDates() throws Exception {
        UUID character = UUID.randomUUID();
        UUID visitor = UUID.randomUUID();
        ContainerData data = new ContainerData(location, LockOwner.character(character));
        data.setLockState(LockState.PRIVATE);
        data.updateAccess(visitor, "2026-09-26");
        manager.saveContainerData(data);
        String json = Files.readString(file);
        assertTrue(json.contains("\"owner\":\"" + character + "\""), json);
        assertTrue(json.contains("\"ownerKind\":\"CHARACTER\""), json);
        ContainerData loaded = managerAt(temp).loadContainerData(location);
        assertSame(location, loaded.getLocation());
        assertEquals(LockOwner.character(character), loaded.getOwner());
        assertEquals(LockState.PRIVATE, loaded.getLockState());
        assertEquals(Map.of(visitor, "2026-09-26"), loaded.getAccessMap());
        assertFalse(loaded.isUnreadable());
        assertEquals(LockOwner.character(character), manager.getOwner(location));

        data.setOwner(LockOwner.server());
        manager.saveContainerData(data);
        assertTrue(Files.readString(file).contains("\"owner\":\"00000000-0000-0000-0000-000000000000\",\"ownerKind\":\"SERVER\""));
        assertEquals(LockOwner.server(), managerAt(temp).loadContainerData(location).getOwner());

        data.setOwner(null);
        data.setLockState(LockState.GUILD);
        manager.saveContainerData(data);
        assertNull(manager.getOwner(location));
        assertEquals(LockState.GUILD, manager.loadContainerData(location).getLockState());
        assertFalse(Files.readString(file).contains("owner\""));
        verifyNoInteractions(logger);
    }

    @Test
    void writesOnlyOnChangeAndForcesOnlyOwnerAndStateChanges() throws Exception {
        UUID character = UUID.randomUUID();
        ContainerData data = new ContainerData(location, LockOwner.character(character));
        try (MockedStatic<DurableFiles> durable = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            manager.saveContainerData(data);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(true)));
            durable.clearInvocations();
            manager.saveContainerData(manager.loadContainerData(location));
            durable.verifyNoInteractions();
            data.updateAccess(UUID.randomUUID(), "today");
            manager.saveContainerData(data);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(false)));
            durable.clearInvocations();
            data.rotateLockState();
            manager.saveContainerData(data);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(true)));
            durable.clearInvocations();
            data.setOwner(LockOwner.character(UUID.randomUUID()));
            manager.saveContainerData(data);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(true)));
            durable.clearInvocations();
            // A file that cannot be read is replaced, and forced, when staff set a new record.
            write("garbage");
            manager.saveContainerData(data);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(true)));
        }
        assertFalse(Files.list(file.getParent()).anyMatch(path -> path.toString().endsWith(".tmp")));
    }

    @Test
    void aNullAccessMapIsAnEmptyLedger() throws Exception {
        write("{\"owner\":\"" + UUID.randomUUID() + "\",\"lockState\":\"PRIVATE\",\"accessMap\":null}");
        ContainerData loaded = manager.loadContainerData(location);
        assertFalse(loaded.isUnreadable());
        assertTrue(loaded.getAccessMap().isEmpty());
    }

    @Test
    void markerlessOwnersAreClassifiedOnLoad() throws Exception {
        UUID character = UUID.randomUUID();
        write("{\"owner\":\"" + character + "\",\"lockState\":\"PRIVATE\"}");
        ContainerData loaded = manager.loadContainerData(location);
        assertEquals(LockOwner.character(character), loaded.getOwner());
        assertFalse(loaded.isProvisional());

        write("{\"owner\":\"00000000-0000-0000-0000-000000000000\",\"lockState\":\"PRIVATE\"}");
        assertEquals(LockOwner.server(), manager.loadContainerData(location).getOwner());

        UUID account = UUID.randomUUID();
        identity.identity().when(() -> OwnerIdentity.info(account)).thenReturn(Optional.empty());
        identity.identity().when(() -> OwnerIdentity.heir(account)).thenReturn(Optional.of(character));
        write("{\"owner\":\"" + account + "\",\"lockState\":\"GUILD\"}");
        assertEquals(LockOwner.legacy(account), manager.loadContainerData(location).getOwner());
        // Saving a legacy record keeps its bare value: classifications are never written back as markers.
        ContainerData legacy = manager.loadContainerData(location);
        legacy.updateAccess(UUID.randomUUID(), "today");
        manager.saveContainerData(legacy);
        assertFalse(Files.readString(file).contains("ownerKind"));
    }

    @Test
    void classificationsMadeWhileIdentityIsDownAreNeitherCachedNorPersisted() throws Exception {
        UUID character = UUID.randomUUID();
        identity.identity().when(OwnerIdentity::available).thenReturn(false);
        write("{\"owner\":\"" + character + "\",\"lockState\":\"PRIVATE\",\"accessMap\":{}}");
        ContainerData provisional = manager.loadContainerData(location);
        assertEquals(LockOwner.unresolved(character), provisional.getOwner());
        assertTrue(provisional.isProvisional());
        assertEquals(LockOwner.unresolved(character), manager.getOwner(location));
        provisional.updateAccess(UUID.randomUUID(), "today");
        manager.saveContainerData(provisional);
        assertFalse(Files.readString(file).contains("ownerKind"));
        identity.identity().when(OwnerIdentity::available).thenReturn(true);
        assertEquals(LockOwner.character(character), manager.getOwner(location));
    }

    @Test
    void unreadableRecordsLoadLockedUnresolvedAndAreNeverOverwritten() throws Exception {
        UUID owner = UUID.randomUUID();
        String[] broken = {
            "", "   ", "null", "{\"owner\":\"" + owner + "\"", "[1,2]", "{\"owner\":\"not-a-uuid\",\"lockState\":\"PUBLIC\"}",
            "{\"owner\":\"" + owner + "\"}", "{\"owner\":\"" + owner + "\",\"lockState\":null}",
            "{\"owner\":\"" + owner + "\",\"lockState\":\"WIDE_OPEN\"}", "{\"lockState\":\"SHUT\"}",
            "{\"owner\":\"" + owner + "\",\"ownerKind\":\"ALIEN\",\"lockState\":\"PUBLIC\"}",
            "{\"owner\":\"" + owner + "\",\"ownerKind\":\"SERVER\",\"lockState\":\"PUBLIC\"}",
            "{\"owner\":\"00000000-0000-0000-0000-000000000000\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"PUBLIC\"}",
            "{\"ownerKind\":\"CHARACTER\",\"lockState\":\"PUBLIC\"}", "{\"owner\":7,\"lockState\":\"PUBLIC\"}", "{\"owner\":{},\"lockState\":\"PUBLIC\"}",
            "{\"owner\":\"" + owner + "\",\"lockState\":\"PUBLIC\",\"accessMap\":[]}",
            "{\"owner\":\"" + owner + "\",\"lockState\":\"PUBLIC\",\"accessMap\":{\"x\":\"today\"}}",
            "{\"owner\":\"" + owner + "\",\"lockState\":\"PUBLIC\",\"accessMap\":{\"" + owner + "\":3}}",
            "{\"owner\":\"1-1-1-1-1\",\"lockState\":\"PUBLIC\"}",
        };
        for (String json : broken) {
            ContainerDataManager.clearOwnerCache();
            write(json);
            ContainerData loaded = manager.loadContainerData(location);
            assertTrue(loaded.isUnreadable(), json);
            assertEquals(LockOwner.unreadable(), loaded.getOwner(), json);
            assertEquals(LockState.PRIVATE, loaded.getLockState(), json);
            assertEquals(LockOwner.unreadable(), manager.getOwner(location), json);
            manager.saveContainerData(loaded);
            assertEquals(json, Files.readString(file), json);
        }
        assertFalse(LockQuarantine.snapshot().unreadable().isEmpty());
        UUID thief = UUID.randomUUID();
        write("{\"owner\":\"nope\",\"lockState\":\"PUBLIC\",\"accessMap\":{\"" + thief + "\":\"2026-10-01\"}}");
        assertEquals(Map.of(thief, "2026-10-01"), manager.loadContainerData(location).getAccessMap(), "cooldowns are salvaged");
        write("{\"owner\":\"nope\",\"accessMap\":[1]}");
        assertTrue(manager.loadContainerData(location).getAccessMap().isEmpty());
    }

    @Test
    void ioErrorsLoadLockedAndFailedWritesInvalidateTheCache() throws Exception {
        Files.createDirectories(file);
        assertTrue(manager.loadContainerData(location).isUnreadable());
        verify(logger).warning(contains("Unreadable container lock"));
        Files.delete(file);

        UUID first = UUID.randomUUID();
        ContainerData data = new ContainerData(location, LockOwner.character(first));
        manager.saveContainerData(data);
        assertEquals(LockOwner.character(first), manager.getOwner(location));
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "nonempty directory");
        data.setOwner(LockOwner.character(UUID.randomUUID()));
        manager.saveContainerData(data);
        verify(logger).warning(contains("Failed to save container data"));
        assertFalse(manager.deleteContainerData(location));
        verify(logger).warning(contains("Failed to delete container data"));
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        assertNull(manager.getOwner(location));
    }

    @Test
    void cachedLookupsSurviveFileEditsUntilSavedDeletedOrCleared() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertNull(manager.getOwner(location));
        write("{\"owner\":\"" + first + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"PRIVATE\"}");
        assertNull(manager.getOwner(location));
        ContainerDataManager.clearOwnerCache();
        assertEquals(LockOwner.character(first), manager.getOwner(location));
        assertEquals(LockState.PRIVATE, manager.getLock(location).state());
        write("{\"owner\":\"" + second + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"PRIVATE\"}");
        assertEquals(LockOwner.character(first), managerAt(temp).getOwner(location));
        assertTrue(manager.deleteContainerData(location));
        assertFalse(manager.deleteContainerData(location));
        assertNull(manager.getOwner(location));
    }

    @Test
    void guardModeFreezesSavesAndQueuesDeletionsUntilTheGuardLifts() throws Exception {
        UUID character = UUID.randomUUID();
        ContainerData data = new ContainerData(location, LockOwner.character(character));
        manager.saveContainerData(data);
        String before = Files.readString(file);
        LockGuard.enter("test");
        data.rotateLockState();
        manager.saveContainerData(data);
        manager.clearAllAccessMaps();
        manager.removePlayerFromAllAccessMaps(UUID.randomUUID());
        assertEquals(before, Files.readString(file));
        assertFalse(manager.deleteContainerData(location));
        assertTrue(Files.exists(file));
        assertEquals(java.util.List.of(file), LockGuard.pendingDeletions());
        LockGuard.lift(ContainerDataManager::clearOwnerCache);
        assertFalse(Files.exists(file));
        assertNull(manager.getOwner(location));
    }

    @Test
    void bulkCooldownResetsKeepOwnersAndMarkersAndSkipUnreadableFiles() throws Exception {
        UUID target = UUID.randomUUID();
        UUID remaining = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ContainerData data = new ContainerData(location, LockOwner.character(owner));
        data.setLockState(LockState.FACTION);
        data.updateAccess(target, "yesterday");
        data.updateAccess(remaining, "today");
        manager.saveContainerData(data);
        Path ignored = file.getParent().resolve("notes.txt");
        Files.writeString(ignored, "not json");
        Path garbage = file.getParent().resolve("garbage.json");
        Files.writeString(garbage, "{broken");
        Files.writeString(temp.resolve("root.json"), "not a chunk file");
        manager.removePlayerFromAllAccessMaps(null);
        assertEquals(2, manager.loadContainerData(location).getAccessMap().size());
        manager.removePlayerFromAllAccessMaps(target);
        ContainerData changed = manager.loadContainerData(location);
        assertEquals(Map.of(remaining, "today"), changed.getAccessMap());
        assertEquals(LockOwner.character(owner), changed.getOwner());
        assertEquals(LockState.FACTION, changed.getLockState());
        String before = Files.readString(file);
        manager.removePlayerFromAllAccessMaps(target);
        assertEquals(before, Files.readString(file));
        manager.clearAllAccessMaps();
        assertTrue(manager.loadContainerData(location).getAccessMap().isEmpty());
        assertTrue(Files.readString(file).contains("CHARACTER"));
        before = Files.readString(file);
        manager.clearAllAccessMaps();
        assertEquals(before, Files.readString(file));
        assertEquals("{broken", Files.readString(garbage));
        assertEquals("not json", Files.readString(ignored));
        assertEquals("not a chunk file", Files.readString(temp.resolve("root.json")));
        verify(logger, atLeastOnce()).warning(contains("Failed to update container cooldown data"));
    }

    @Test
    void bulkOperationsHandleMissingRootRootThatIsAFileAndUnlistableChunks() throws Exception {
        ContainerDataManager missing = managerAt(temp.resolve("absent"));
        missing.removePlayerFromAllAccessMaps(UUID.randomUUID());
        missing.clearAllAccessMaps();
        assertFalse(Files.exists(temp.resolve("absent")));
        Path regularFile = temp.resolve("regular-file");
        Files.writeString(regularFile, "preserve");
        managerAt(regularFile).clearAllAccessMaps();
        assertEquals("preserve", Files.readString(regularFile));

        UUID owner = UUID.randomUUID(), target = UUID.randomUUID(), other = UUID.randomUUID();
        ContainerData saved = new ContainerData(location, LockOwner.character(owner));
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
        java.io.File broken = mock(java.io.File.class);
        when(broken.exists()).thenReturn(true);
        when(broken.listFiles(any(java.io.FileFilter.class))).thenReturn(null);
        folder.set(resetting, broken);
        resetting.clearAllAccessMaps();
        verifyNoInteractions(logger);
    }

    @Test
    void filesAreNamedByChunkWithoutLoadingIt() {
        assertEquals(file.toFile(), manager.getFileForLocation(location));
        verify(location, never()).getChunk();
    }

    private void write(String json) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private Path fileAt(Location loc) {
        return temp.resolve((loc.getBlockX() >> 4) + "_" + (loc.getBlockZ() >> 4))
                .resolve(loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ() + ".json");
    }

    static ContainerDataManager managerAt(Path root) throws Exception {
        ContainerDataManager manager = new ContainerDataManager();
        Field folder = ContainerDataManager.class.getDeclaredField("dataFolder");
        folder.setAccessible(true);
        folder.set(manager, root.toFile()); // Redirect real disk persistence into the test sandbox.
        return manager;
    }

    static Location location(int x, int y, int z) {
        Location location = mock(Location.class);
        when(location.getBlockX()).thenReturn(x);
        when(location.getBlockY()).thenReturn(y);
        when(location.getBlockZ()).thenReturn(z);
        return location;
    }
}
