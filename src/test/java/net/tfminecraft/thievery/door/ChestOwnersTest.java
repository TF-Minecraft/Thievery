package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import net.tfminecraft.thievery.owner.LockOwner;

class ChestOwnersTest {
    final LockOwner kira = LockOwner.character(UUID.randomUUID());
    final LockOwner rook = LockOwner.character(UUID.randomUUID());

    ContainerData half(LockOwner owner) {
        return new ContainerData(new Location(null, 0, 0, 0), owner);
    }

    @Test void aDoubleChestHasOneOwnerUnlessItsHalvesDisagree() {
        assertEquals(new ChestOwners(null, false), ChestOwners.of(half(null), half(null)));
        assertEquals(new ChestOwners(kira, false), ChestOwners.of(half(kira), half(null)));
        assertEquals(new ChestOwners(kira, false), ChestOwners.of(half(null), half(kira)));
        assertEquals(new ChestOwners(kira, false), ChestOwners.of(half(kira), half(kira)));
        assertEquals(new ChestOwners(null, true), ChestOwners.of(half(kira), half(rook)));
        assertTrue(ChestOwners.of(half(LockOwner.server()), half(kira)).diverged());
        assertEquals(kira, ChestOwners.of(kira, null).effective());
        assertNull(ChestOwners.of((LockOwner) null, null).effective());
        assertEquals(LockOwner.unreadable(), ChestOwners.of(kira, rook).effective(), "diverged halves act for nobody");
    }

    @Test void mergingNeedsAnUnownedNeighbourOrExactlyTheSameOwner() {
        assertTrue(ChestMergeRules.personallyOwns(kira, null));
        assertTrue(ChestMergeRules.personallyOwns(null, null));
        assertTrue(ChestMergeRules.personallyOwns(kira, LockOwner.character(kira.id())));
        assertFalse(ChestMergeRules.personallyOwns(kira, rook));
        assertFalse(ChestMergeRules.personallyOwns(null, kira), "without a character, only unowned chests merge");
        assertFalse(ChestMergeRules.personallyOwns(LockOwner.server(), kira));
        assertTrue(ChestMergeRules.personallyOwns(LockOwner.server(), LockOwner.server()));
    }

    @Test void newLocksBelongToTheCurrentCharacterOrTheServerForSessionlessStaff() {
        try (var identity = new net.tfminecraft.thievery.testsupport.FactionFixture()) {
            var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
            UUID account = UUID.randomUUID();
            org.mockito.Mockito.when(player.getUniqueId()).thenReturn(account);
            assertEquals(LockOwner.character(account), LockWriters.placementOwner(player));
            assertEquals(LockOwner.character(account), LockWriters.claimOwner(player));
            identity.plays(player, null);
            assertNull(LockWriters.placementOwner(player));
            assertNull(LockWriters.claimOwner(player));
            org.mockito.Mockito.when(player.hasPermission("thievery.admin")).thenReturn(true);
            assertEquals(LockOwner.server(), LockWriters.placementOwner(player));
            assertNull(LockWriters.claimOwner(player), "staff claims and rotations still need a character");
        }
        assertEquals(LockState.PRIVATE, LockWriters.initialState(LockOwner.server()));
        assertEquals(LockState.PUBLIC, LockWriters.initialState(kira));
    }
}
