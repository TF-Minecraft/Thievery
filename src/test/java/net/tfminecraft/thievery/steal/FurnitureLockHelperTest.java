package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class FurnitureLockHelperTest {
    @Test void ownershipAndLockChangesPersistAndPreserveOtherFurnitureVariables() {
        var plugin = mock(InteractibleFurniture.class, RETURNS_DEEP_STUBS);
        var furniture = mock(Furniture.class);
        Map<String, Object> variables = new HashMap<>(Map.of("decoration", "blue"));
        when(furniture.getVariables()).thenReturn(variables);
        UUID owner = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        try (var api = mockStatic(InteractibleFurniture.class)) {
            api.when(InteractibleFurniture::getInstance).thenReturn(plugin);
            FurnitureLockHelper.setOwner(furniture, owner);
            assertEquals(owner, FurnitureLockHelper.getOwner(furniture));
            assertTrue(FurnitureLockHelper.owns(furniture, player));
            assertFalse(FurnitureLockHelper.owns(furniture, null));
            assertFalse(FurnitureLockHelper.owns(furniture, mock(Player.class)));
            FurnitureLockHelper.setLockState(furniture, LockState.PUBLIC);
            assertEquals(LockState.PUBLIC, FurnitureLockHelper.getLockState(furniture));
            assertEquals(LockState.PUBLIC.next(), FurnitureLockHelper.rotateLockState(furniture));
            FurnitureLockHelper.setLockState(furniture, null);
            assertEquals(LockState.DEFAULT, FurnitureLockHelper.getLockState(furniture));
            FurnitureLockHelper.setOwner(furniture, null);
            assertNull(FurnitureLockHelper.getOwner(furniture));
            assertFalse(FurnitureLockHelper.owns(furniture, player));
            assertEquals("blue", variables.get("decoration"));
            verify(plugin.getFurnitureManager(), times(5)).persistFurniture(furniture);
        }
    }
    @Test void legacyAndMalformedVariablesHaveSafeDefaults() {
        var furniture = mock(Furniture.class);
        Map<String, Object> variables = new HashMap<>();
        when(furniture.getVariables()).thenReturn(variables);
        assertNull(FurnitureLockHelper.getOwner(furniture));
        assertEquals(LockState.DEFAULT, FurnitureLockHelper.getLockState(furniture));
        variables.put(FurnitureLockHelper.OWNER_KEY, "deleted owner");
        variables.put(FurnitureLockHelper.LOCK_STATE_KEY, "unknown");
        assertNull(FurnitureLockHelper.getOwner(furniture));
        assertEquals(LockState.DEFAULT, FurnitureLockHelper.getLockState(furniture));
        variables.put(FurnitureLockHelper.LOCK_STATE_KEY, "public");
        assertEquals(LockState.PUBLIC, FurnitureLockHelper.getLockState(furniture));
        assertNull(FurnitureLockHelper.getOwner(null));
        assertEquals(LockState.DEFAULT, FurnitureLockHelper.getLockState(null));
        FurnitureLockHelper.setOwner(null, UUID.randomUUID());
        FurnitureLockHelper.setLockState(null, LockState.PUBLIC);
    }
    @Test void configuredFurnitureUsesTheSharedOwnershipAccessPolicy() {
        var furniture = mock(Furniture.class);
        UUID owner = UUID.randomUUID();
        when(furniture.getId()).thenReturn("cabinet");
        when(furniture.getVariables()).thenReturn(Map.of(FurnitureLockHelper.OWNER_KEY, owner.toString(), FurnitureLockHelper.LOCK_STATE_KEY, "PRIVATE"));
        Player player = mock(Player.class);
        try (var parameters = mockStatic(Parameters.class); var access = mockStatic(LockAccess.class)) {
            parameters.when(() -> Parameters.isLockableFurnitureId("cabinet")).thenReturn(true);
            assertTrue(FurnitureLockHelper.isLockable(furniture));
            assertFalse(FurnitureLockHelper.isLockable(null));
            parameters.when(() -> Parameters.isLockableFurnitureId("cabinet")).thenReturn(false);
            assertFalse(FurnitureLockHelper.isLockable(furniture));
            access.when(() -> LockAccess.canAccess(player, owner, LockState.PRIVATE)).thenReturn(true);
            assertTrue(FurnitureLockHelper.canAccess(furniture, player));
            access.when(() -> LockAccess.canAccess(player, owner, LockState.PRIVATE)).thenReturn(false);
            assertFalse(FurnitureLockHelper.canAccess(furniture, player));
        }
    }
}
