package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LockDataTest {
    @Test
    void doorRetainsLockIdentityAndAllowsExpiryToBeCleared() {
        Location location = new Location(null, 1, 2, 3);
        UUID owner = UUID.randomUUID();
        DoorData door = new DoorData(location, "bronze", 0.75, owner);
        assertSame(location, door.getLocation());
        assertEquals("bronze", door.getKey());
        assertEquals(0.75, door.getStrength());
        assertEquals(owner, door.getOwnerUUID());
        assertNull(door.getUnlockExpiryMs());
        door.setUnlockExpiryMs(123456L);
        assertEquals(123456L, door.getUnlockExpiryMs());
        door.setUnlockExpiryMs(null);
        assertNull(door.getUnlockExpiryMs());
    }

    @Test
    void entityOwnershipCanBeAssignedTransferredAndRemoved() {
        UUID entityId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        EntityLockData lock = new EntityLockData(entityId);
        assertEquals(entityId, lock.getEntityId());
        assertNull(lock.getOwner());
        assertFalse(lock.owns(player));
        lock.setOwner(owner);
        assertEquals(owner, lock.getOwner());
        assertTrue(lock.owns(player));
        lock.setOwner(UUID.randomUUID());
        assertFalse(lock.owns(player));
        lock.setOwner(null);
        assertFalse(lock.owns(player));
        assertEquals(owner, new EntityLockData(entityId, owner).getOwner());
    }

    @Test
    void entityAccessDelegatesCurrentOwnerAndStateAndPropagatesDecision() {
        Player player = mock(Player.class);
        UUID owner = UUID.randomUUID();
        EntityLockData lock = new EntityLockData(UUID.randomUUID(), owner);
        assertEquals(LockState.DEFAULT, lock.getLockState());
        lock.setLockState(LockState.GUILD);
        assertEquals(LockState.GUILD, lock.getLockState());
        try (MockedStatic<LockAccess> access = mockStatic(LockAccess.class)) {
            access.when(() -> LockAccess.canAccess(player, owner, LockState.GUILD)).thenReturn(true);
            assertTrue(lock.canAccess(player));
            access.verify(() -> LockAccess.canAccess(player, owner, LockState.GUILD));
            lock.setLockState(LockState.PRIVATE);
            assertFalse(lock.canAccess(player));
            access.verify(() -> LockAccess.canAccess(player, owner, LockState.PRIVATE));
        }
        lock.setLockState(null);
        assertEquals(LockState.DEFAULT, lock.getLockState());
        assertEquals(LockState.PRIVATE, lock.rotateLockState());
        assertEquals(LockState.PRIVATE, lock.getLockState());
    }

    @Test
    void containerOwnershipCanBeAssignedTransferredAndRemoved() {
        Location location = new Location(null, 4, 5, 6);
        UUID owner = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        ContainerData container = new ContainerData(location);
        assertSame(location, container.getLocation());
        assertNull(container.getOwner());
        assertFalse(container.owns(player));
        container.setOwner(owner);
        assertEquals(owner, container.getOwner());
        assertTrue(container.owns(player));
        container.setOwner(UUID.randomUUID());
        assertFalse(container.owns(player));
        container.setOwner(null);
        assertFalse(container.owns(player));
        assertEquals(owner, new ContainerData(location, owner).getOwner());
    }

    @Test
    void containerAccessDelegatesCurrentOwnerAndStateAndPropagatesDecision() {
        Player player = mock(Player.class);
        UUID owner = UUID.randomUUID();
        ContainerData container = new ContainerData(new Location(null, 0, 0, 0), owner);
        assertEquals(LockState.DEFAULT, container.getLockState());
        container.setLockState(LockState.FACTION);
        assertEquals(LockState.FACTION, container.getLockState());
        try (MockedStatic<LockAccess> access = mockStatic(LockAccess.class)) {
            access.when(() -> LockAccess.canAccess(player, owner, LockState.FACTION)).thenReturn(true);
            assertTrue(container.canAccess(player));
            access.verify(() -> LockAccess.canAccess(player, owner, LockState.FACTION));
            container.setLockState(LockState.PRIVATE);
            assertFalse(container.canAccess(player));
            access.verify(() -> LockAccess.canAccess(player, owner, LockState.PRIVATE));
        }
        container.setLockState(null);
        assertEquals(LockState.DEFAULT, container.getLockState());
        assertEquals(LockState.PRIVATE, container.rotateLockState());
        assertEquals(LockState.PRIVATE, container.getLockState());
    }

    @Test
    void containerAccessHistoryUpdatesOnlyTheGivenPlayerAndSupportsLoadedMaps() {
        ContainerData container = new ContainerData(new Location(null, 0, 0, 0));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(container.getAccessMap().isEmpty());
        assertNull(container.getLastAccess(first));
        container.updateAccess(first, "2026-09-25");
        container.updateAccess(second, "2026-09-24");
        container.updateAccess(first, "2026-09-26");
        assertEquals("2026-09-26", container.getLastAccess(first));
        assertEquals("2026-09-24", container.getLastAccess(second));
        Map<UUID, String> loaded = new HashMap<>(Map.of(first, "loaded"));
        container.setAccessMap(loaded);
        assertEquals("loaded", container.getLastAccess(first));
        assertNull(container.getLastAccess(second));
        assertEquals(loaded, container.getAccessMap());
    }

    @Test
    void lockStateRotationVisitsEveryStateAndWraps() {
        assertEquals(LockState.PUBLIC, LockState.DEFAULT);
        assertEquals(LockState.GUILD, LockState.PRIVATE.next());
        assertEquals(LockState.FACTION, LockState.GUILD.next());
        assertEquals(LockState.PUBLIC, LockState.FACTION.next());
        assertEquals(LockState.PRIVATE, LockState.PUBLIC.next());
    }

    @Test
    void lockProfilesKeepIdentityAndClampNegativeMultipliersIndependently() {
        LockTypeProfile identity = LockTypeProfile.IDENTITY;
        assertEquals(1, identity.budgetMultiplier());
        assertEquals(1, identity.riskMultiplier());
        assertTrue(identity.criticalRisk());
        assertEquals(1, identity.breakChanceMultiplier());
        LockTypeProfile custom = new LockTypeProfile(2, 0.5, false, 3);
        assertEquals(2, custom.budgetMultiplier());
        assertEquals(0.5, custom.riskMultiplier());
        assertFalse(custom.criticalRisk());
        assertEquals(3, custom.breakChanceMultiplier());
        LockTypeProfile clamped = new LockTypeProfile(-1, -2, true, -3);
        assertEquals(0, clamped.budgetMultiplier());
        assertEquals(0, clamped.riskMultiplier());
        assertTrue(clamped.criticalRisk());
        assertEquals(0, clamped.breakChanceMultiplier());
    }
}
