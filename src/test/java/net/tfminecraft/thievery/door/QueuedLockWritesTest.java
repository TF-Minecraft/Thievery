package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.owner.HeldLockDisk;
import net.tfminecraft.thievery.owner.LockFileWriter;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.testsupport.FactionFixture;

/** The lock managers with their writes on the writer thread: what readers see before, during and after the disk. */
class QueuedLockWritesTest {
    @TempDir Path temp;
    private ContainerDataManager containers;
    private Location location;
    private Path file;
    private Logger log;
    private MockedStatic<Bukkit> bukkit;
    private FactionFixture identity;
    private HeldLockDisk disk;
    // Failure tasks the writer hands to the main thread; the test runs them as the scheduler would.
    private final List<Runnable> main = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        identity = new FactionFixture();
        containers = ContainerDataManagerTest.managerAt(temp);
        location = ContainerDataManagerTest.location(12345, 64, -6789);
        file = temp.resolve("771_-425/12345_64_-6789.json");
        ContainerDataManager.clearOwnerCache();
        log = mock(Logger.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(log);
        disk = HeldLockDisk.install();
    }

    @AfterEach
    void cleanUp() {
        HeldLockDisk.uninstall(disk);
        ContainerDataManager.clearOwnerCache();
        bukkit.close();
        identity.close();
    }

    private void startWriter() {
        LockFileWriter.start(log, main::add);
    }

    private void runMainTasks() {
        List<Runnable> tasks = List.copyOf(main);
        main.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void aRotationIsSeenAtOnceByEveryReaderAndReachesTheDiskBehindThePlacement() throws Exception {
        UUID owner = UUID.randomUUID();
        startWriter();
        containers.saveContainerData(new ContainerData(location, LockOwner.character(owner)));
        disk.awaitHolding();

        ContainerData placed = containers.loadContainerData(location);
        assertEquals(LockOwner.character(owner), placed.getOwner());
        assertEquals(LockState.DEFAULT, placed.getLockState());
        LockState rotated = placed.rotateLockState();
        containers.saveContainerData(placed);

        // Another manager instance (each listener has its own) reads the queued rotation, as do the cache and index.
        ContainerDataManager other = ContainerDataManagerTest.managerAt(temp);
        assertEquals(rotated, other.loadContainerData(location).getLockState());
        assertEquals(rotated, other.getLock(location).state());
        assertEquals(rotated, OwnedLocks.of(owner).getFirst().state());
        assertFalse(Files.exists(file), "nothing is on disk while the writer is held");

        disk.release();
        LockFileWriter.flush();
        assertEquals(2, disk.done.size());
        assertTrue(disk.done.get(0).endsWith(" true"), "a new lock is forced");
        assertTrue(disk.done.get(1).endsWith(" true"), "a state change is forced");
        assertEquals(rotated, ContainerDataManagerTest.managerAt(temp).loadContainerData(location).getLockState());
        assertTrue(Files.readString(file).contains(rotated.name()));
        assertTrue(main.isEmpty());
        verifyNoInteractions(log);
    }

    @Test
    void aSaveTrustsTheRecordItWasLoadedAsUntilAnotherWriteTouchesItsFile() throws Exception {
        disk.release();
        UUID owner = UUID.randomUUID();
        UUID heir = UUID.randomUUID();
        containers.saveContainerData(new ContainerData(location, LockOwner.character(owner)));
        ContainerData loaded = containers.loadContainerData(location);
        LockState rotated = loaded.rotateLockState();
        // Edited behind the writer's back: a save that read the file again would see nothing to write.
        editState(file, LockState.DEFAULT, rotated);
        disk.done.clear();
        containers.saveContainerData(loaded);
        assertEquals(1, disk.done.size(), "saved from the record it was loaded as");

        // Once another data object has written the file, the stale one reads it again before deciding.
        ContainerData stale = containers.loadContainerData(location);
        ContainerData moved = containers.loadContainerData(location);
        moved.setOwner(LockOwner.character(heir));
        containers.saveContainerData(moved);
        disk.done.clear();
        containers.saveContainerData(stale);
        assertEquals(1, disk.done.size());
        assertTrue(disk.done.getFirst().contains(owner.toString()) && disk.done.getFirst().endsWith(" true"),
                disk.done.toString());

        // Writes of other files leave a loaded record trusted, as with the two halves of a double chest.
        Location right = ContainerDataManagerTest.location(12346, 64, -6789);
        Path rightFile = temp.resolve("771_-425/12346_64_-6789.json");
        containers.saveContainerData(new ContainerData(right, LockOwner.character(owner)));
        ContainerData left = containers.loadContainerData(location);
        ContainerData half = containers.loadContainerData(right);
        LockState both = left.rotateLockState();
        half.setLockState(both);
        editState(rightFile, LockState.DEFAULT, both);
        containers.saveContainerData(left);
        disk.done.clear();
        containers.saveContainerData(half);
        assertEquals(1, disk.done.size(), "the other half's write leaves this one's record trusted");
    }

    private static void editState(Path path, LockState from, LockState to) throws Exception {
        String json = Files.readString(path);
        assertTrue(json.contains(from.name()), json);
        Files.writeString(path, json.replace(from.name(), to.name()));
    }

    @Test
    void aRecordReadInGuardModeIsNeverTrustedBySaves() throws Exception {
        disk.release();
        containers.saveContainerData(new ContainerData(location, LockOwner.character(UUID.randomUUID())));
        net.tfminecraft.thievery.owner.LockGuard.enter("test");
        ContainerData loaded = containers.loadContainerData(location);
        net.tfminecraft.thievery.owner.LockGuard.forceFull();
        LockState rotated = loaded.rotateLockState();
        editState(file, LockState.DEFAULT, rotated);
        disk.done.clear();
        containers.saveContainerData(loaded);
        assertTrue(disk.done.isEmpty(), "the save read the file again and found it already rotated");
    }

    @Test
    void aSaveThatCannotReadTheCurrentRecordIsLoggedAndLeavesNothingCached() throws Exception {
        disk.release();
        Files.createDirectories(file.resolve("child"));
        containers.saveContainerData(new ContainerData(location, LockOwner.character(UUID.randomUUID())));
        verify(log).warning(startsWith("Failed to save container data for " + location + ": "));
        assertTrue(disk.done.isEmpty());
        Files.delete(file.resolve("child"));
        Files.delete(file);
        assertNull(containers.getOwner(location));
    }

    @Test
    void aQueuedDeletionReadsAsNoLockBeforeTheFileIsGone() throws Exception {
        UUID owner = UUID.randomUUID();
        startWriter();
        containers.saveContainerData(new ContainerData(location, LockOwner.character(owner)));
        disk.awaitHolding();
        assertTrue(containers.deleteContainerData(location));
        assertFalse(containers.deleteContainerData(location), "already going");
        assertFalse(containers.loadContainerData(location).isOwned());
        assertNull(containers.getOwner(location));
        assertEquals(List.of(), OwnedLocks.of(owner));
        disk.release();
        LockFileWriter.flush();
        assertTrue(disk.done.getLast().startsWith("delete "), disk.done.toString());
        assertFalse(Files.exists(file));
    }

    @Test
    void aFailedQueuedWriteIsLoggedAndTheCacheAndIndexReturnToTheDisk() throws Exception {
        disk.release();
        UUID owner = UUID.randomUUID();
        UUID heir = UUID.randomUUID();
        ContainerData data = new ContainerData(location, LockOwner.character(owner));
        containers.saveContainerData(data);
        startWriter();
        disk.fail(file.getFileName().toString());

        data.setOwner(LockOwner.character(heir));
        containers.saveContainerData(data);
        assertEquals(LockOwner.character(heir), containers.getOwner(location));
        LockFileWriter.flush();
        verify(log).warning(startsWith("Failed to save container data for " + location + ": disk refused"));
        assertEquals(1, main.size());
        runMainTasks();
        assertEquals(LockOwner.character(owner), containers.getOwner(location), "back to the disk");
        assertEquals(1, OwnedLocks.of(owner).size());
        assertEquals(List.of(), OwnedLocks.of(heir));

        assertTrue(containers.deleteContainerData(location));
        LockFileWriter.flush();
        verify(log).warning(startsWith("Failed to delete container data for " + location + ": disk refused"));
        runMainTasks();
        assertEquals(LockOwner.character(owner), containers.getOwner(location));
        assertEquals(1, OwnedLocks.of(owner).size());
    }

    @Test
    void cooldownResetsWaitForTheQueueSoTheyReachFilesStillBeingCreated() throws Exception {
        UUID thief = UUID.randomUUID();
        startWriter();
        ContainerData data = new ContainerData(location, LockOwner.character(UUID.randomUUID()));
        data.updateAccess(thief, "2026-10-30");
        containers.saveContainerData(data);
        disk.awaitHolding();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            disk.release();
        });
        releaser.start();
        containers.removePlayerFromAllAccessMaps(thief);
        LockFileWriter.flush();
        releaser.join();
        assertTrue(containers.loadContainerData(location).getAccessMap().isEmpty());
        assertFalse(Files.readString(file).contains(thief.toString()));
        assertTrue(disk.done.getLast().endsWith(" false"), "a cooldown change is not forced");
    }

    @Test
    void doorsReadTheirQueuedWritesAndFailuresReturnTheIndexToTheDisk() throws Exception {
        DoorDataManager doors = new DoorDataManager();
        Field folder = DoorDataManager.class.getDeclaredField("dataFolder");
        folder.setAccessible(true);
        folder.set(doors, temp.resolve("doors").toFile());
        Path door = doors.getFileForLocation(location).toPath();
        UUID owner = UUID.randomUUID();
        UUID heir = UUID.randomUUID();
        startWriter();
        doors.saveDoorData(new DoorData(location, "brass", 0.8, LockOwner.character(owner)));
        disk.awaitHolding();
        assertEquals("brass", doors.loadDoorData(location).getKey());
        assertTrue(doors.deleteDoorData(location));
        assertNull(doors.loadDoorData(location));
        disk.release();
        LockFileWriter.flush();
        assertFalse(Files.exists(door));

        doors.saveDoorData(new DoorData(location, "brass", 0.8, LockOwner.character(owner)));
        LockFileWriter.flush();
        disk.fail(door.getFileName().toString());
        doors.saveDoorData(new DoorData(location, "brass", 0.8, LockOwner.character(heir)));
        assertEquals(1, OwnedLocks.of(heir).size());
        LockFileWriter.flush();
        verify(log).warning(startsWith("Failed to save door data for " + location + ": disk refused"));
        runMainTasks();
        assertEquals(1, OwnedLocks.of(owner).size());
        assertTrue(doors.deleteDoorData(location));
        LockFileWriter.flush();
        verify(log).warning(startsWith("Failed to delete door data for " + location + ": disk refused"));
        runMainTasks();
        assertEquals(1, OwnedLocks.of(owner).size());
        assertEquals(LockOwner.character(owner), doors.loadDoorData(location).getOwner());
    }

    @Test
    void entityLocksReadTheirQueuedWritesAndFailuresReturnTheIndexToTheDisk() throws Exception {
        Thievery plugin = mock(Thievery.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        try (MockedStatic<Thievery> thievery = mockStatic(Thievery.class)) {
            thievery.when(Thievery::getInstance).thenReturn(plugin);
            EntityLockDataManager entities = new EntityLockDataManager();
            UUID id = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            UUID heir = UUID.randomUUID();
            startWriter();
            EntityLockData data = new EntityLockData(id, LockOwner.character(owner));
            data.setLockState(LockState.PRIVATE);
            entities.save(data);
            disk.awaitHolding();
            entities.save(data);
            assertEquals(LockState.PRIVATE, entities.load(id).getLockState());
            assertTrue(entities.delete(id));
            assertNull(entities.load(id).getOwner());
            disk.release();
            LockFileWriter.flush();
            assertEquals(2, disk.done.size(), "the unchanged save is not queued");
            assertTrue(disk.done.getLast().startsWith("delete "), disk.done.toString());

            entities.save(data);
            LockFileWriter.flush();
            disk.fail(id + ".json");
            entities.save(new EntityLockData(id, LockOwner.character(heir)));
            LockFileWriter.flush();
            verify(log).warning(startsWith("Failed to save entity lock data for " + id + ": disk refused"));
            runMainTasks();
            assertEquals(1, OwnedLocks.of(owner).size());
            assertTrue(entities.delete(id));
            LockFileWriter.flush();
            verify(log).warning(startsWith("Failed to delete entity lock data for " + id + ": disk refused"));
            runMainTasks();
            assertEquals(LockOwner.character(owner), entities.load(id).getOwner());
            assertEquals(1, OwnedLocks.of(owner).size());
        }
    }
}
