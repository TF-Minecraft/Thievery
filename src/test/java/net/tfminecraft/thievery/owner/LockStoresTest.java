package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockStoresTest {
    @TempDir Path temp;

    @Test void defaultsMatchTheDataManagersPaths() {
        LockStores stores = LockStores.defaults(new File("plugins/Thievery"));
        assertEquals(Path.of("plugins/Thievery/data"), stores.root(LockStores.Store.CONTAINERS));
        assertEquals(Path.of("plugins/Thievery/door-data"), stores.root(LockStores.Store.DOORS));
        assertEquals(Path.of("plugins/Thievery/entities"), stores.root(LockStores.Store.ENTITIES));
        assertEquals(Path.of("plugins/Thievery/migrations"), stores.migrations());
        assertEquals("ownerUUID", LockStores.Store.DOORS.ownerField());
    }

    @Test void filesAreListedInAStableOrderAndResolveBackByName() throws Exception {
        LockStores stores = new LockStores(temp.resolve("data"), temp.resolve("door-data"), temp.resolve("entities"), temp.resolve("m"));
        assertTrue(stores.files().isEmpty());
        Files.createDirectories(temp.resolve("data/1_0"));
        Files.createDirectories(temp.resolve("data/0_0"));
        Files.writeString(temp.resolve("data/1_0/b.json"), "{}");
        Files.writeString(temp.resolve("data/0_0/a.json"), "{}");
        Files.writeString(temp.resolve("data/0_0/a.json.tmp"), "{}");
        Files.writeString(temp.resolve("data/data-version"), "1");
        Files.createDirectories(temp.resolve("data/0_0/dir.json"));
        Files.createDirectories(temp.resolve("entities/nested"));
        Files.writeString(temp.resolve("entities/e.json"), "{}");
        Files.writeString(temp.resolve("door-data"), "not a directory");
        List<String> names = stores.files().stream().map(LockStores.StoreFile::name).toList();
        assertEquals(List.of("data/0_0/a.json", "data/1_0/b.json", "entities/e.json"), names);
        LockStores.StoreFile door = stores.resolve("door-data/0_0/1_2_3.json");
        assertEquals(LockStores.Store.DOORS, door.store());
        assertEquals(temp.resolve("door-data/0_0/1_2_3.json"), door.path());
        assertEquals(temp.resolve("entities/e.json"), stores.resolve("entities/e.json").path());
        assertThrows(IllegalArgumentException.class, () -> stores.resolve("elsewhere/x.json"));
        assertThrows(IllegalArgumentException.class, () -> stores.resolve("database.json"));
    }
}
