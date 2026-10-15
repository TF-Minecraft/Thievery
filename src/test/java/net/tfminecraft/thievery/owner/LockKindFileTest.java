package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        return new LockKindFile(stores, file, log);
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
                "\"door-data/0_0/9_64_1.json\": {\"world\": \"world\", \"kind\": \"Door\"}, \"data/0_0/1_64_1.json\""));
        LockKindFile restarted = kinds();
        restarted.load();
        assertEquals(List.of(new Lock(chest, Store.CONTAINERS, alice, LockState.PRIVATE, "world", "Chest", "2_64_1.json"),
                new Lock(stores.resolve("door-data/0_0/9_64_1.json").path().toAbsolutePath().normalize(), Store.DOORS,
                        alice, null, "world", "Door", null)), OwnedLocks.of(alice));
        Files.delete(file);
        restarted.saveIfChanged();
        assertFalse(Files.exists(file), "loading is not a change");
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
        assertEquals(List.of(new Lock(chest, Store.CONTAINERS, alice, LockState.PRIVATE, null, null, null)),
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
