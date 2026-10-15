package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.LockStores.Store;
import net.tfminecraft.thievery.owner.OwnedLocks.Lock;

/** The sidecar that carries what was seen of locked blocks across restarts. */
class LockKindFileTest {
    @TempDir Path temp;
    LockStores stores;
    Path file;
    Logger log;
    final UUID alice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        stores = new LockStores(temp.resolve("data"), temp.resolve("door-data"), temp.resolve("entities"),
                temp.resolve("migrations"));
        file = temp.resolve(LockKindFile.FILE);
        log = mock(Logger.class);
    }

    Path hold(Store store, String name, LockState state) {
        Path path = stores.resolve(name).path();
        OwnedLocks.put(store, path, new OwnerCodec.Stored(alice, LockOwner.Kind.CHARACTER), state, null);
        return path;
    }

    LockKindFile kinds() {
        return new LockKindFile(stores, file, log, Runnable::run);
    }

    @Test
    void sightingsSurviveARestartForRecordsStillHeldInTheSameStore() throws Exception {
        Path chest = hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        Path door = hold(Store.DOORS, "door-data/0_0/5_64_1.json", null);
        hold(Store.CONTAINERS, "data/0_0/9_64_1.json", LockState.PUBLIC);
        Path gone = hold(Store.CONTAINERS, "data/0_0/7_64_1.json", LockState.PUBLIC);
        LockKindFile kinds = kinds();
        kinds.load();
        assertFalse(Files.exists(file));
        kinds.saveIfChanged();
        assertFalse(Files.exists(file), "nothing seen, nothing written");

        OwnedLocks.seen(OwnedLocks.of(alice).get(0), "world", "Chest", "2_64_1.json");
        for (Lock lock : OwnedLocks.of(alice)) {
            if (lock.path().equals(door)) OwnedLocks.seen(lock, "world_nether", "Gate", null);
            if (lock.path().equals(gone)) OwnedLocks.seen(lock, "world", "Barrel", null);
        }
        OwnedLocks.remove(gone);
        kinds.saveIfChanged();
        String json = Files.readString(file);
        assertTrue(json.contains("\"data/0_0/1_64_1.json\": {"), json);
        assertTrue(json.contains("\"partner\": \"2_64_1.json\""), json);
        assertTrue(json.contains("\"door-data/0_0/5_64_1.json\": {"), json);
        assertFalse(json.contains("7_64_1"), "keys no longer held are dropped");
        assertFalse(json.contains("9_64_1"), "unseen records are not written");

        // Unchanged: not written again.
        Files.delete(file);
        kinds.saveIfChanged();
        assertFalse(Files.exists(file));
        OwnedLocks.remove(door);
        kinds.saveIfChanged();
        assertFalse(Files.readString(file).contains("5_64_1"));
        OwnedLocks.seen(OwnedLocks.of(alice).get(0), "world", "Chest", "2_64_1.json");

        // The next boot indexes the files afresh and applies what the file remembers to the records it holds.
        OwnedLocks.clear();
        hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        hold(Store.DOORS, "door-data/0_0/9_64_1.json", null);
        Files.writeString(file, Files.readString(file).replace("\"data/0_0/1_64_1.json\"",
                "\"door-data/0_0/9_64_1.json\": {\"world\": \"world\", \"bound\": false, \"kind\": \"Door\"},"
                        + " \"data/0_0/1_64_1.json\""));
        LockKindFile restarted = kinds();
        restarted.load();
        assertEquals(List.of(new Lock(chest, Store.CONTAINERS, alice, LockState.PRIVATE, "world", false, "Chest", "2_64_1.json"),
                new Lock(stores.resolve("door-data/0_0/9_64_1.json").path().toAbsolutePath().normalize(), Store.DOORS,
                        alice, null, "world", false, "Door", null)), OwnedLocks.of(alice));
        Files.delete(file);
        restarted.saveIfChanged();
        assertFalse(Files.exists(file), "loading is not a change");
        verifyNoInteractions(log);
    }

    @Test
    void boundWorldsAreKeptApartFromSightings() throws Exception {
        Path chest = hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        Path barrel = hold(Store.CONTAINERS, "data/0_0/3_64_1.json", LockState.PRIVATE);
        LockKindFile kinds = kinds();
        kinds.load();
        OwnedLocks.put(Store.CONTAINERS, chest, new OwnerCodec.Stored(alice, LockOwner.Kind.CHARACTER),
                LockState.PRIVATE, "world");
        for (Lock lock : OwnedLocks.of(alice)) {
            OwnedLocks.seen(lock, lock.path().equals(chest) ? "world" : "world_nether",
                    lock.path().equals(chest) ? "Chest" : "Barrel", null);
        }
        kinds.saveNow();
        String json = Files.readString(file);
        assertEquals(1, json.split("\"bound\": true", -1).length - 1, json);

        OwnedLocks.clear();
        hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        hold(Store.CONTAINERS, "data/0_0/3_64_1.json", LockState.PRIVATE);
        kinds().load();
        assertEquals(List.of(true, false), OwnedLocks.of(alice).stream().map(Lock::bound).toList());
        assertEquals(barrel, OwnedLocks.of(alice).get(1).path());
    }

    @Test
    void writesRunInTheBackgroundOneAtATimeAndAnOlderOneIsDropped() throws Exception {
        hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        List<Runnable> pending = new java.util.ArrayList<>();
        LockKindFile kinds = new LockKindFile(stores, file, log, pending::add);
        kinds.load();
        OwnedLocks.seen(OwnedLocks.of(alice).get(0), "world", "Chest", null);
        kinds.saveIfChanged();
        kinds.saveIfChanged();
        assertEquals(1, pending.size(), "one snapshot per change");
        assertFalse(Files.exists(file), "nothing is written on the calling thread");
        OwnedLocks.seen(OwnedLocks.of(alice).get(0), "world", "Barrel", null);
        kinds.saveNow();
        assertTrue(Files.readString(file).contains("Barrel"));
        pending.getFirst().run();
        assertTrue(Files.readString(file).contains("Barrel"), "the older snapshot does not overwrite the newer");
        kinds.saveNow();
        assertEquals(1, pending.size(), "nothing new to write");
    }

    @Test
    void disableWritesASnapshotThatIsQueuedButNotYetRun() throws Exception {
        hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        List<Runnable> pending = new java.util.ArrayList<>();
        LockKindFile kinds = new LockKindFile(stores, file, log, pending::add);
        kinds.load();
        OwnedLocks.seen(OwnedLocks.of(alice).get(0), "world", "Chest", null);
        kinds.saveIfChanged();
        kinds.saveNow();
        pending.clear(); // the scheduler drops the queued job at disable
        assertTrue(Files.readString(file).contains("Chest"));
    }

    @Test
    void disableWaitsForARunningWriteOfTheSameRevision() throws Exception {
        hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        List<Runnable> pending = new java.util.ArrayList<>();
        LockKindFile kinds = new LockKindFile(stores, file, log, pending::add);
        kinds.load();
        OwnedLocks.seen(OwnedLocks.of(alice).get(0), "world", "Chest", null);
        kinds.saveIfChanged();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            // Static mocks are per thread, so the background write holds itself open on its own thread.
            Future<?> writer = workers.submit(() -> {
                try (MockedStatic<DurableFiles> durable = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
                    durable.when(() -> DurableFiles.write(eq(file), any(byte[].class), eq(false))).thenAnswer(call -> {
                        writing.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return call.callRealMethod();
                    });
                    pending.getFirst().run();
                }
            });
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<?> flush = workers.submit(kinds::saveNow);
            try {
                assertThrows(TimeoutException.class, () -> flush.get(200, TimeUnit.MILLISECONDS),
                        "disable waits for the running write");
            } finally {
                release.countDown();
                writer.get(5, TimeUnit.SECONDS);
                flush.get(5, TimeUnit.SECONDS);
            }
        }
        assertTrue(Files.readString(file).contains("Chest"));
    }

    @Test
    void aBoundWorldIsKeptBeforeTheKindIsSeen() throws Exception {
        LockKindFile kinds = kinds();
        kinds.load();
        Path chest = hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        Path guessed = hold(Store.CONTAINERS, "data/0_0/3_64_1.json", LockState.PRIVATE);
        OwnerCodec.Stored marked = new OwnerCodec.Stored(alice, LockOwner.Kind.CHARACTER);
        OwnedLocks.seen(OwnedLocks.of(alice).get(1), "world_nether", "Barrel", null);
        // Saves bind both; the guessed one moves to the main world and its old look is dropped.
        OwnedLocks.put(Store.CONTAINERS, chest, marked, LockState.PRIVATE, "world");
        OwnedLocks.put(Store.CONTAINERS, guessed, marked, LockState.PRIVATE, "world");
        kinds.saveIfChanged();
        String json = Files.readString(file);
        assertFalse(json.contains("\"kind\""), json);

        OwnedLocks.clear();
        hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        hold(Store.CONTAINERS, "data/0_0/3_64_1.json", LockState.PRIVATE);
        kinds().load();
        assertEquals(List.of("world/true/null", "world/true/null"), OwnedLocks.of(alice).stream()
                .map(lock -> lock.world() + "/" + lock.bound() + "/" + lock.kind()).toList());
        verifyNoInteractions(log);
    }

    @Test
    void anUnreadableFileMeansNoRememberedKindsAndOneWarning() throws Exception {
        Path chest = hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        for (String broken : new String[] {"not json", "[]", "{\"version\":2,\"locks\":{}}",
                "{\"version\":1,\"locks\":{\"data/0_0/1_64_1.json\":{\"kind\":\"Chest\"}}}",
                "{\"version\":1,\"locks\":{\"elsewhere/1_64_1.json\":{\"world\":\"world\",\"kind\":\"Chest\"}}}"}) {
            Files.writeString(file, broken);
            kinds().load();
        }
        verify(log, times(5)).warning(contains("could not be read"));
        assertEquals(List.of(new Lock(chest, Store.CONTAINERS, alice, LockState.PRIVATE, null, false, null, null)),
                OwnedLocks.of(alice), "ownership is untouched");
    }

    @Test
    void aFailedWriteIsLoggedAndRetried() throws Exception {
        hold(Store.CONTAINERS, "data/0_0/1_64_1.json", LockState.PRIVATE);
        Files.createDirectories(file.resolve("blocker"));
        LockKindFile kinds = kinds();
        kinds.load();
        OwnedLocks.seen(OwnedLocks.of(alice).get(0), "world", "Chest", null);
        kinds.saveIfChanged();
        verify(log).warning(contains("Could not save remembered lock kinds"));
        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        kinds.saveIfChanged();
        assertTrue(Files.readString(file).contains("\"kind\": \"Chest\""));
    }
}
