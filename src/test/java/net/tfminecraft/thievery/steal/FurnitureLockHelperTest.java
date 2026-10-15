package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.*;
import net.tfminecraft.thievery.estate.EstateBridge;
import net.tfminecraft.thievery.estate.LockEstates;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockQuarantine;
import net.tfminecraft.thievery.owner.OwnerIdentity;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class FurnitureLockHelperTest {
    FactionFixture identity; MockedStatic<InteractibleFurniture> api; InteractibleFurniture plugin; Furniture furniture; Map<String, Object> variables;
    @BeforeEach void setUp() {
        identity = new FactionFixture();
        plugin = mock(InteractibleFurniture.class, RETURNS_DEEP_STUBS);
        api = mockStatic(InteractibleFurniture.class); api.when(InteractibleFurniture::getInstance).thenReturn(plugin);
        furniture = mock(Furniture.class); variables = new HashMap<>(Map.of("decoration", "blue"));
        when(furniture.getVariables()).thenReturn(variables);
        World world = mock(World.class); when(world.getName()).thenReturn("world");
        when(furniture.getLoc()).thenReturn(new Location(world, 1, 2, 3));
    }
    @AfterEach void tearDown() { api.close(); identity.close(); }

    @Test void ownershipAndLockChangesPersistWithTheirKindAndPreserveOtherVariables() {
        UUID owner = UUID.randomUUID();
        FurnitureLockHelper.setOwner(furniture, LockOwner.character(owner));
        assertEquals(owner.toString(), variables.get(FurnitureLockHelper.OWNER_KEY));
        assertEquals("CHARACTER", variables.get(FurnitureLockHelper.OWNER_KIND_KEY));
        FurnitureLockHelper.setLockState(furniture, LockState.PRIVATE);
        assertEquals(LockOwner.character(owner), FurnitureLockHelper.getOwner(furniture));
        assertEquals(LockState.PRIVATE, FurnitureLockHelper.getLockState(furniture));
        assertEquals(LockState.GUILD, FurnitureLockHelper.rotateLockState(furniture));
        FurnitureLockHelper.setLockState(furniture, null);
        assertEquals(LockState.DEFAULT, FurnitureLockHelper.getLockState(furniture));
        FurnitureLockHelper.setOwner(furniture, LockOwner.server());
        assertEquals(LockOwner.server(), FurnitureLockHelper.getOwner(furniture));
        FurnitureLockHelper.setOwner(furniture, LockOwner.legacy(owner));
        assertFalse(variables.containsKey(FurnitureLockHelper.OWNER_KIND_KEY), "legacy owners are never marked");
        FurnitureLockHelper.setOwner(furniture, null);
        assertNull(FurnitureLockHelper.getOwner(furniture));
        assertFalse(variables.containsKey(FurnitureLockHelper.OWNER_KIND_KEY));
        assertEquals("blue", variables.get("decoration"));
        verify(plugin.getFurnitureManager(), times(7)).persistFurniture(furniture);
        assertNull(FurnitureLockHelper.getOwner(null));
        assertEquals(LockState.DEFAULT, FurnitureLockHelper.getLockState(null));
        FurnitureLockHelper.setOwner(null, LockOwner.server());
        FurnitureLockHelper.setLockState(null, LockState.PUBLIC);
    }

    @Test void garbageVariablesLoadLockedWithAnUnresolvedOwner() {
        UUID owner = UUID.randomUUID();
        assertEquals(new FurnitureLockHelper.FurnitureLock(null, LockState.DEFAULT, false), FurnitureLockHelper.read(furniture));
        String[][] broken = { {"deleted owner", "PRIVATE", null}, {owner.toString(), null, null}, {owner.toString(), "unknown", null},
            {owner.toString(), "PRIVATE", "ALIEN"}, {null, "PRIVATE", "CHARACTER"}, {null, "sideways", null} };
        for (String[] values : broken) {
            put(FurnitureLockHelper.OWNER_KEY, values[0]); put(FurnitureLockHelper.LOCK_STATE_KEY, values[1]); put(FurnitureLockHelper.OWNER_KIND_KEY, values[2]);
            var lock = FurnitureLockHelper.read(furniture);
            assertTrue(lock.unreadable(), Arrays.toString(values));
            assertEquals(LockOwner.unreadable(), lock.owner());
            assertEquals(LockState.PRIVATE, lock.state());
        }
        assertEquals(List.of("furniture/world_1_2_3"), LockQuarantine.snapshot().unreadable().stream().map(LockQuarantine.Entry::path).toList());
        put(FurnitureLockHelper.OWNER_KEY, null); put(FurnitureLockHelper.OWNER_KIND_KEY, null); put(FurnitureLockHelper.LOCK_STATE_KEY, "public");
        assertEquals(LockState.PUBLIC, FurnitureLockHelper.getLockState(furniture));
    }

    @Test void firstInteractionUpgradesBareOwnersAndLeavesTheRestAlone() {
        UUID character = UUID.randomUUID(), account = UUID.randomUUID(), heir = UUID.randomUUID(), orphan = UUID.randomUUID();
        put(FurnitureLockHelper.OWNER_KEY, character.toString()); put(FurnitureLockHelper.LOCK_STATE_KEY, "GUILD");
        FurnitureLockHelper.upgrade(furniture);
        assertEquals("CHARACTER", variables.get(FurnitureLockHelper.OWNER_KIND_KEY));
        assertEquals(character.toString(), variables.get(FurnitureLockHelper.OWNER_KEY));
        assertEquals("GUILD", variables.get(FurnitureLockHelper.LOCK_STATE_KEY));

        identity.identity().when(() -> OwnerIdentity.info(account)).thenReturn(Optional.empty());
        identity.identity().when(() -> OwnerIdentity.heir(account)).thenReturn(Optional.of(heir));
        put(FurnitureLockHelper.OWNER_KEY, account.toString()); put(FurnitureLockHelper.OWNER_KIND_KEY, null);
        assertEquals(LockOwner.legacy(account), FurnitureLockHelper.getOwner(furniture));
        FurnitureLockHelper.upgrade(furniture);
        assertEquals(LockOwner.character(heir), FurnitureLockHelper.getOwner(furniture));

        put(FurnitureLockHelper.OWNER_KEY, LockOwner.NIL.toString()); put(FurnitureLockHelper.OWNER_KIND_KEY, null);
        FurnitureLockHelper.upgrade(furniture);
        assertEquals("SERVER", variables.get(FurnitureLockHelper.OWNER_KIND_KEY));

        identity.identity().when(() -> OwnerIdentity.info(orphan)).thenReturn(Optional.empty());
        put(FurnitureLockHelper.OWNER_KEY, orphan.toString()); put(FurnitureLockHelper.OWNER_KIND_KEY, null);
        FurnitureLockHelper.upgrade(furniture);
        assertNull(variables.get(FurnitureLockHelper.OWNER_KIND_KEY), "no heir: stays bare and denies");
        identity.identity().when(OwnerIdentity::available).thenReturn(false);
        put(FurnitureLockHelper.OWNER_KEY, character.toString());
        FurnitureLockHelper.upgrade(furniture);
        assertNull(variables.get(FurnitureLockHelper.OWNER_KIND_KEY), "nothing is persisted while identity is down");
        identity.identity().when(OwnerIdentity::available).thenReturn(true);
        LockGuard.enter("test");
        FurnitureLockHelper.upgrade(furniture);
        FurnitureLockHelper.setOwner(furniture, LockOwner.server());
        FurnitureLockHelper.setLockState(furniture, LockState.PUBLIC);
        assertNull(variables.get(FurnitureLockHelper.OWNER_KIND_KEY), "guard mode writes nothing");
        assertEquals("GUILD", variables.get(FurnitureLockHelper.LOCK_STATE_KEY));
        LockGuard.forceFull();
        put(FurnitureLockHelper.OWNER_KEY, "garbage");
        FurnitureLockHelper.upgrade(furniture);
        put(FurnitureLockHelper.OWNER_KEY, null);
        FurnitureLockHelper.upgrade(furniture);
        put(FurnitureLockHelper.OWNER_KEY, character.toString()); put(FurnitureLockHelper.OWNER_KIND_KEY, "CHARACTER");
        clearInvocations(plugin.getFurnitureManager());
        FurnitureLockHelper.upgrade(furniture);
        verifyNoInteractions(plugin.getFurnitureManager());
    }

    @Test void aDeadOwnersPieceGoesToTheRecipientOfItsPartOnFirstInteraction() {
        UUID dead = UUID.randomUUID(), heir = UUID.randomUUID(), leader = UUID.randomUUID(), living = UUID.randomUUID();
        LockEstates estates = mock(LockEstates.class);
        when(estates.ofOwner(any())).thenReturn(Optional.empty());
        when(estates.ofOwner(dead)).thenReturn(Optional.of(new LockEstates.Estate(UUID.randomUUID(), dead, 1, heir, leader)));
        EstateBridge.set(estates);
        java.util.logging.Logger log = mock(java.util.logging.Logger.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(log);
            put(FurnitureLockHelper.OWNER_KEY, dead.toString()); put(FurnitureLockHelper.OWNER_KIND_KEY, "CHARACTER");
            put(FurnitureLockHelper.LOCK_STATE_KEY, "PRIVATE");
            FurnitureLockHelper.inherit(furniture);
            assertEquals(LockOwner.character(heir), FurnitureLockHelper.getOwner(furniture));
            assertEquals(LockState.PRIVATE, FurnitureLockHelper.getLockState(furniture), "state kept");
            verify(log).info(contains("passed from " + dead + " to " + heir));

            put(FurnitureLockHelper.OWNER_KEY, dead.toString()); put(FurnitureLockHelper.LOCK_STATE_KEY, "faction");
            FurnitureLockHelper.inherit(furniture);
            assertEquals(LockOwner.character(leader), FurnitureLockHelper.getOwner(furniture));

            // A waiting part, a living owner, guard mode, server and bare owners and unreadable variables stay.
            when(estates.ofOwner(dead)).thenReturn(Optional.of(new LockEstates.Estate(UUID.randomUUID(), dead, 1, heir, null)));
            put(FurnitureLockHelper.OWNER_KEY, dead.toString()); put(FurnitureLockHelper.LOCK_STATE_KEY, "GUILD");
            FurnitureLockHelper.inherit(furniture);
            assertEquals(LockOwner.character(dead), FurnitureLockHelper.getOwner(furniture));
            put(FurnitureLockHelper.OWNER_KEY, living.toString());
            FurnitureLockHelper.inherit(furniture);
            assertEquals(LockOwner.character(living), FurnitureLockHelper.getOwner(furniture));
            put(FurnitureLockHelper.OWNER_KEY, dead.toString()); put(FurnitureLockHelper.LOCK_STATE_KEY, "PRIVATE");
            LockGuard.enter("test");
            FurnitureLockHelper.inherit(furniture);
            LockGuard.forceFull();
            assertEquals(LockOwner.character(dead), FurnitureLockHelper.getOwner(furniture));
            put(FurnitureLockHelper.OWNER_KEY, LockOwner.NIL.toString()); put(FurnitureLockHelper.OWNER_KIND_KEY, "SERVER");
            FurnitureLockHelper.inherit(furniture);
            put(FurnitureLockHelper.OWNER_KEY, dead.toString()); put(FurnitureLockHelper.OWNER_KIND_KEY, null);
            FurnitureLockHelper.inherit(furniture);
            put(FurnitureLockHelper.OWNER_KIND_KEY, "CHARACTER"); put(FurnitureLockHelper.LOCK_STATE_KEY, null);
            FurnitureLockHelper.inherit(furniture);
            put(FurnitureLockHelper.OWNER_KEY, "garbage"); put(FurnitureLockHelper.LOCK_STATE_KEY, "PRIVATE");
            FurnitureLockHelper.inherit(furniture);
            verify(estates, times(1)).ofOwner(living);
            verify(estates, times(3)).ofOwner(dead);

            // A failed estate lookup leaves the piece alone.
            put(FurnitureLockHelper.OWNER_KEY, dead.toString());
            when(estates.ofOwner(dead)).thenThrow(new NoSuchMethodError("Estates.stateOf"));
            FurnitureLockHelper.inherit(furniture);
            assertEquals(LockOwner.character(dead), FurnitureLockHelper.getOwner(furniture));
            verify(log).log(eq(java.util.logging.Level.WARNING), contains("Could not check the estate"), any(NoSuchMethodError.class));
        }
    }

    @Test void ownedByListsLockablePiecesMarkedAsTheCharacters() {
        UUID alice = UUID.randomUUID();
        Furniture mine = piece("pedestal", alice.toString().toUpperCase(Locale.ROOT), "CHARACTER", "guild");
        Map<UUID, Furniture> placed = new LinkedHashMap<>();
        placed.put(UUID.randomUUID(), piece("chair", alice.toString(), "CHARACTER", "PRIVATE"));
        placed.put(UUID.randomUUID(), piece("pedestal", null, null, null));
        placed.put(UUID.randomUUID(), piece("pedestal", UUID.randomUUID().toString(), "CHARACTER", "PRIVATE"));
        placed.put(UUID.randomUUID(), piece("pedestal", alice.toString(), null, "PRIVATE"));
        placed.put(UUID.randomUUID(), piece("pedestal", alice.toString(), "CHARACTER", null));
        placed.put(UUID.randomUUID(), mine);
        when(plugin.getFurnitureManager().getPlacedFurniture()).thenReturn(placed);
        Set<String> saved = Parameters.lockableFurnitureIds;
        try {
            Parameters.lockableFurnitureIds = Set.of("pedestal");
            assertEquals(List.of(new FurnitureLockHelper.Owned("pedestal", mine.getLoc(), LockState.GUILD)),
                FurnitureLockHelper.ownedBy(alice), "unlockable, unowned, other, bare and unreadable pieces are left out");
        } finally {
            Parameters.lockableFurnitureIds = saved;
        }
        assertEquals(List.of(), LockQuarantine.snapshot().unreadable(), "listing notes nothing");
    }

    private static Furniture piece(String id, String owner, String kind, String state) {
        Furniture piece = mock(Furniture.class);
        Map<String, Object> values = new HashMap<>();
        if (owner != null) values.put(FurnitureLockHelper.OWNER_KEY, owner);
        if (kind != null) values.put(FurnitureLockHelper.OWNER_KIND_KEY, kind);
        if (state != null) values.put(FurnitureLockHelper.LOCK_STATE_KEY, state);
        when(piece.getId()).thenReturn(id);
        when(piece.getVariables()).thenReturn(values);
        when(piece.getLoc()).thenReturn(new Location(null, 4, 5, 6));
        return piece;
    }

    @Test void configuredFurnitureIsLockable() {
        when(furniture.getId()).thenReturn("cabinet");
        try (var parameters = mockStatic(Parameters.class)) {
            parameters.when(() -> Parameters.isLockableFurnitureId("cabinet")).thenReturn(true);
            assertTrue(FurnitureLockHelper.isLockable(furniture));
            assertFalse(FurnitureLockHelper.isLockable(null));
            parameters.when(() -> Parameters.isLockableFurnitureId("cabinet")).thenReturn(false);
            assertFalse(FurnitureLockHelper.isLockable(furniture));
        }
    }

    private void put(String key, String value) {
        if (value == null) variables.remove(key); else variables.put(key, value);
    }
}
