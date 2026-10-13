package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.tfminecraft.thievery.testsupport.FactionFixture;

class DoorDataManagerTest {
    @TempDir Path temp;
    private DoorDataManager manager;
    private Location location;
    private Path file;
    private Logger logger;
    private MockedStatic<Bukkit> bukkit;
    private FactionFixture identity;

    @BeforeEach
    void setUp() throws Exception {
        identity = new FactionFixture();
        manager = new DoorDataManager();
        Field folder = DoorDataManager.class.getDeclaredField("dataFolder");
        folder.setAccessible(true);
        folder.set(manager, temp.toFile()); // Exercise real IO without touching plugin data.
        location = mock(Location.class);
        when(location.getBlockX()).thenReturn(-17);
        when(location.getBlockY()).thenReturn(65);
        when(location.getBlockZ()).thenReturn(33);
        file = temp.resolve("-2_2/-17_65_33.json");
        logger = mock(Logger.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
    }

    @AfterEach
    void cleanUp() {
        bukkit.close();
        identity.close();
    }

    private void write(String json) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    @Test
    void missingDoorIsUnlockedAndCannotBeDeleted() {
        assertNull(manager.loadDoorData(location));
        assertFalse(manager.deleteDoorData(location));
        verifyNoInteractions(logger);
    }

    @Test
    void roundTripKeepsTheOwnerAsABareUuidWithItsKind() throws Exception {
        UUID owner = UUID.randomUUID();
        DoorData original = new DoorData(location, "copper", 0.75, LockOwner.character(owner));
        original.setUnlockExpiryMs(1_800_000_000_000L);
        manager.saveDoorData(original);
        String json = Files.readString(file);
        assertTrue(json.contains("\"ownerUUID\":\"" + owner + "\",\"ownerKind\":\"CHARACTER\""), json);
        DoorData loaded = manager.loadDoorData(location);
        assertSame(location, loaded.getLocation());
        assertEquals("copper", loaded.getKey());
        assertEquals(0.75, loaded.getStrength());
        assertEquals(LockOwner.character(owner), loaded.getOwner());
        assertEquals(1_800_000_000_000L, loaded.getUnlockExpiryMs());
        manager.saveDoorData(new DoorData(location, "iron", 0.9, LockOwner.server()));
        loaded = manager.loadDoorData(location);
        assertEquals("iron", loaded.getKey());
        assertEquals(LockOwner.server(), loaded.getOwner());
        assertNull(loaded.getUnlockExpiryMs());
        manager.saveDoorData(new DoorData(location, "iron", 0.9, null));
        assertNull(manager.loadDoorData(location).getOwner());
        assertTrue(manager.deleteDoorData(location));
        assertFalse(manager.deleteDoorData(location));
        assertNull(manager.loadDoorData(location));
        verifyNoInteractions(logger);
    }

    @Test
    void unlockWindowChangesAreNotForcedButOwnerAndKeyChangesAre() throws Exception {
        DoorData door = new DoorData(location, "copper", 0.5, LockOwner.character(UUID.randomUUID()));
        try (MockedStatic<DurableFiles> durable = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            manager.saveDoorData(door);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(true)));
            durable.clearInvocations();
            manager.saveDoorData(door);
            durable.verifyNoInteractions();
            door.setUnlockExpiryMs(5L);
            manager.saveDoorData(door);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(false)));
            durable.clearInvocations();
            door.setOwner(LockOwner.server());
            manager.saveDoorData(door);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(true)));
            durable.clearInvocations();
            manager.saveDoorData(new DoorData(location, "silver", 0.5, LockOwner.server()));
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(true)));
            durable.clearInvocations();
            write("{broken");
            manager.saveDoorData(door);
            durable.verify(() -> DurableFiles.write(eq(file), any(), eq(true)));
        }
    }

    @Test
    void olderDoorsWithoutOwnerExpiryOrStrengthStillLoad() throws Exception {
        write("{\"key\":\"legacy\"}");
        DoorData loaded = manager.loadDoorData(location);
        assertEquals("legacy", loaded.getKey());
        assertEquals(0.0, loaded.getStrength());
        assertNull(loaded.getOwner());
        assertNull(loaded.getUnlockExpiryMs());
        write("{\"key\":\"legacy\",\"strength\":0.3,\"unlockExpiryMs\":null}");
        assertNull(manager.loadDoorData(location).getUnlockExpiryMs());
        UUID account = UUID.randomUUID();
        write("{\"key\":\"legacy\",\"strength\":0.3,\"ownerUUID\":\"" + account + "\"}");
        assertEquals(LockOwner.character(account), manager.loadDoorData(location).getOwner());
    }

    @Test
    void unreadableDoorsStayLockedKeepTheirKeyWhenTheyCanAndAreNeverOverwritten() throws Exception {
        String[] broken = { "", "null", "{\"key\":\"k\"", "{\"strength\":1}", "{\"key\":5}",
            "{\"key\":\"k\",\"strength\":\"strong\"}", "{\"key\":\"k\",\"unlockExpiryMs\":\"soon\"}",
            "{\"key\":\"k\",\"ownerUUID\":\"nope\"}", "{\"key\":\"k\",\"strength\":null}" };
        for (String json : broken) {
            write(json);
            DoorData loaded = manager.loadDoorData(location);
            assertNotNull(loaded, json);
            assertTrue(loaded.isUnreadable(), json);
            assertEquals(LockOwner.unreadable(), loaded.getOwner(), json);
            manager.saveDoorData(loaded);
            assertEquals(json, Files.readString(file), json);
        }
        write("{\"key\":\"kept\",\"ownerUUID\":\"nope\"}");
        assertEquals("kept", manager.loadDoorData(location).getKey());
        write("{\"key\":7,\"ownerUUID\":\"nope\"}");
        assertNull(manager.loadDoorData(location).getKey());
        DoorData fixed = manager.loadDoorData(location);
        fixed.setOwner(LockOwner.server());
        assertFalse(fixed.isUnreadable());
    }

    @Test
    void directoryInPlaceOfDataFileStaysLockedAndLogsFailures() throws Exception {
        Files.createDirectories(file);
        Files.writeString(file.resolve("keep"), "preserve");
        DoorData loaded = manager.loadDoorData(location);
        assertTrue(loaded.isUnreadable());
        assertNull(loaded.getKey());
        manager.saveDoorData(new DoorData(location, "copper", 0.5, LockOwner.character(UUID.randomUUID())));
        assertFalse(manager.deleteDoorData(location));
        assertEquals("preserve", Files.readString(file.resolve("keep")));
        verify(logger).warning(contains("Unreadable door lock"));
        verify(logger).warning(contains("Failed to save door data"));
        verify(logger).warning(contains("Failed to delete door data"));
    }

    @Test
    void guardModeFreezesSavesAndQueuesDeletions() throws Exception {
        manager.saveDoorData(new DoorData(location, "copper", 0.5, LockOwner.character(UUID.randomUUID())));
        String before = Files.readString(file);
        LockGuard.enter("test");
        DoorData door = manager.loadDoorData(location);
        door.setUnlockExpiryMs(1L);
        manager.saveDoorData(door);
        assertEquals(before, Files.readString(file));
        assertFalse(manager.deleteDoorData(location));
        assertTrue(Files.exists(file));
        LockGuard.lift(() -> { });
        assertFalse(Files.exists(file));
    }
}
