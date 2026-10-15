package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.LockStores.Store;
import net.tfminecraft.thievery.owner.OwnedLocks.Lock;

/** The owner index behind the will screen. */
class OwnedLocksTest {
    @TempDir Path temp;

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();

    static OwnerCodec.Stored marked(UUID character) {
        return new OwnerCodec.Stored(character, LockOwner.Kind.CHARACTER);
    }

    List<String> names(UUID owner) {
        return OwnedLocks.of(owner).stream().map(Lock::name).toList();
    }

    @Test
    void holdsOnlyCharacterMarkedRecordsWithLockFileNames() {
        UUID entity = UUID.randomUUID();
        Path chest = temp.resolve("data/0_-1/1_64_-2.json");
        OwnedLocks.put(Store.ENTITIES, temp.resolve("entities/" + entity + ".json"), marked(alice), LockState.PRIVATE,
                null);
        OwnedLocks.put(Store.DOORS, temp.resolve("door-data/0_-1/1_64_-2.json"), marked(alice), null, null);
        OwnedLocks.put(Store.CONTAINERS, chest, marked(alice), LockState.GUILD, "world");
        OwnedLocks.put(Store.CONTAINERS, temp.resolve("data/0_0/2_64_2.json"),
                new OwnerCodec.Stored(LockOwner.NIL, LockOwner.Kind.SERVER), LockState.PRIVATE, null);
        OwnedLocks.put(Store.CONTAINERS, temp.resolve("data/0_0/3_64_2.json"), new OwnerCodec.Stored(alice, null),
                LockState.PRIVATE, null);
        OwnedLocks.put(Store.CONTAINERS, temp.resolve("data/0_0/4_64_2.json"), OwnerCodec.Stored.NONE,
                LockState.PUBLIC, null);
        OwnedLocks.put(Store.CONTAINERS, temp.resolve("data/0_0/5_64_2.json"), null, null, null);
        OwnedLocks.put(Store.CONTAINERS, temp.resolve("data/0_0/notes.json"), marked(alice), LockState.PRIVATE, null);
        OwnedLocks.put(Store.ENTITIES, temp.resolve("entities/1_64_2.json"), marked(alice), LockState.PRIVATE, null);

        assertEquals(List.of(new Lock(chest, Store.CONTAINERS, alice, LockState.GUILD, "world", null, null)),
                OwnedLocks.of(alice).subList(0, 1));
        assertEquals(List.of("1_64_-2.json", "1_64_-2.json", entity + ".json"), names(alice), "in file order");
        assertEquals(List.of(Store.CONTAINERS, Store.DOORS, Store.ENTITIES),
                OwnedLocks.of(alice).stream().map(Lock::store).toList());
        assertEquals(List.of(), OwnedLocks.of(bob));
    }

    @Test
    void writesReplaceARecordAndDeletesDropIt() {
        Path relative = Path.of("plugins/Thievery/data/0_0/1_64_1.json");
        Path other = temp.resolve("data/0_0/2_64_1.json");
        OwnedLocks.put(Store.CONTAINERS, relative, marked(alice), LockState.PRIVATE, null);
        OwnedLocks.put(Store.CONTAINERS, other, marked(alice), LockState.PUBLIC, null);
        // The lock pass names files through LockStores, the managers relative to the server directory.
        OwnedLocks.put(Store.CONTAINERS, relative.toAbsolutePath(), marked(bob), LockState.GUILD, null);
        assertEquals(List.of("2_64_1.json"), names(alice));
        assertEquals(LockState.GUILD, OwnedLocks.of(bob).get(0).state());

        OwnedLocks.put(Store.CONTAINERS, relative, new OwnerCodec.Stored(LockOwner.NIL, LockOwner.Kind.SERVER),
                LockState.PRIVATE, null);
        assertEquals(List.of(), OwnedLocks.of(bob), "a lock given to the server leaves the index");
        OwnedLocks.remove(relative);
        OwnedLocks.remove(temp.resolve("door-data/0_0/2_64_1.json"));
        assertEquals(List.of("2_64_1.json"), names(alice), "another store's file is another lock");
        OwnedLocks.remove(temp.resolve("data/x/../0_0/2_64_1.json"));
        assertEquals(List.of(), OwnedLocks.of(alice));
    }

    @Test
    void seenRemembersABlockUnlessItsRecordHasChanged() {
        Path chest = temp.resolve("data/0_0/1_64_1.json");
        OwnedLocks.put(Store.CONTAINERS, chest, marked(alice), LockState.PRIVATE, null);
        Lock held = OwnedLocks.of(alice).get(0);
        Lock seen = OwnedLocks.seen(held, "world", "Chest", "2_64_1.json");
        assertEquals(new Lock(chest, Store.CONTAINERS, alice, LockState.PRIVATE, "world", "Chest", "2_64_1.json"), seen);
        assertEquals(List.of(seen), OwnedLocks.of(alice));

        OwnedLocks.put(Store.CONTAINERS, chest, marked(alice), LockState.GUILD, null);
        Lock stale = OwnedLocks.seen(seen, "world", "Barrel", null);
        assertEquals("Barrel", stale.kind());
        assertEquals(new Lock(chest, Store.CONTAINERS, alice, LockState.GUILD, null, null, null),
                OwnedLocks.of(alice).get(0), "a rewritten record is not overwritten by an older look");
    }

    @Test
    void theGuardsQueuedDeletionsLeaveTheIndex() throws Exception {
        Path door = temp.resolve("door-data/0_0/1_64_1.json");
        Files.createDirectories(door.getParent());
        Files.writeString(door, "{}");
        OwnedLocks.put(Store.DOORS, door, marked(alice), null, null);
        LockGuard.enter("starting up");
        LockGuard.queueDeletion(door);
        LockGuard.lift(() -> {});
        assertFalse(Files.exists(door));
        assertEquals(List.of(), OwnedLocks.of(alice));
    }

    @Test
    void worldNamesALocationsWorldWhenItHasOne() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world_nether");
        assertEquals("world_nether", OwnedLocks.world(new Location(world, 1, 2, 3)));
        assertNull(OwnedLocks.world(new Location(null, 1, 2, 3)));
    }
}
