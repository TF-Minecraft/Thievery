package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.IntStream;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class PlayerSlotMapTest {
    private Player player;
    private PlayerInventory inventory;
    private ItemStack[] storage;
    private ItemStack[] armor;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        storage = new ItemStack[36];
        armor = new ItemStack[4];
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(storage);
        when(inventory.getArmorContents()).thenReturn(armor);
    }

    @Test
    void listsEveryLogicalSlotInOrderAndReturnsIndependentLists() {
        List<Integer> expected = IntStream.range(0, 41).boxed().toList();
        assertEquals(expected, PlayerSlotMap.listLogicalSlots());
        PlayerSlotMap.listLogicalSlots().clear();
        assertEquals(expected, PlayerSlotMap.listLogicalSlots());
    }

    @Test
    void mapsOnlyMainInventorySlotsForPickpocketing() {
        for (int logical = 0; logical < 27; logical++) {
            assertEquals(logical + 9, PlayerSlotMap.toPlayerSlot(logical));
            assertEquals(logical, PlayerSlotMap.toPickpocketLogical(logical + 9));
        }
        for (int invalid : new int[] {Integer.MIN_VALUE, -1, 27, 40, Integer.MAX_VALUE}) {
            assertEquals(-1, PlayerSlotMap.toPlayerSlot(invalid));
        }
        for (int invalid : new int[] {Integer.MIN_VALUE, -1, 0, 8, 36, 40, Integer.MAX_VALUE}) {
            assertEquals(-1, PlayerSlotMap.toPickpocketLogical(invalid));
        }
    }

    @Test
    void readsStorageArmorAndOffhandWithoutCopyingItems() {
        for (int i = 0; i < storage.length; i++) {
            storage[i] = mock(ItemStack.class);
            assertSame(storage[i], PlayerSlotMap.getItem(player, i));
        }
        for (int i = 0; i < armor.length; i++) {
            armor[i] = mock(ItemStack.class);
            assertSame(armor[i], PlayerSlotMap.getItem(player, 36 + i));
        }
        ItemStack offhand = mock(ItemStack.class);
        when(inventory.getItemInOffHand()).thenReturn(offhand);
        assertSame(offhand, PlayerSlotMap.getItem(player, 40));
    }

    @Test
    void invalidReadsAndWritesDoNotAccessInventory() {
        ItemStack item = mock(ItemStack.class);
        assertNull(PlayerSlotMap.getItem(null, 0));
        PlayerSlotMap.setItem(null, 0, item);
        for (int invalid : new int[] {-1, 41, Integer.MAX_VALUE}) {
            assertNull(PlayerSlotMap.getItem(player, invalid));
            PlayerSlotMap.setItem(player, invalid, item);
        }
        verifyNoInteractions(inventory);
        verify(player, never()).getInventory();
    }

    @Test
    void writesEachInventoryRegionAndPreservesOtherArmor() {
        ItemStack replacement = mock(ItemStack.class);
        for (int slot : new int[] {0, 35}) {
            PlayerSlotMap.setItem(player, slot, replacement);
            verify(inventory).setItem(slot, replacement);
        }
        for (int i = 0; i < armor.length; i++) {
            armor[i] = mock(ItemStack.class);
        }
        for (int i = 0; i < armor.length; i++) {
            ItemStack[] before = armor.clone();
            PlayerSlotMap.setItem(player, 36 + i, replacement);
            for (int other = 0; other < armor.length; other++) {
                assertSame(other == i ? replacement : before[other], armor[other]);
            }
        }
        verify(inventory, times(4)).setArmorContents(armor);
        PlayerSlotMap.setItem(player, 40, replacement);
        verify(inventory).setItemInOffHand(replacement);
        PlayerSlotMap.setItem(player, 40, null);
        verify(inventory).setItemInOffHand(null);
    }

    @Test
    void pickpocketAccessUsesMainInventoryAndRejectsInvalidLogicalSlots() {
        ItemStack item = mock(ItemStack.class);
        storage[9] = item;
        storage[35] = item;
        assertSame(item, PlayerSlotMap.getPickpocketItem(player, 0));
        assertSame(item, PlayerSlotMap.getPickpocketItem(player, 26));
        PlayerSlotMap.setPickpocketItem(player, 0, item);
        PlayerSlotMap.setPickpocketItem(player, 26, null);
        verify(inventory).setItem(9, item);
        verify(inventory).setItem(35, null);
        clearInvocations(player, inventory);
        for (int invalid : new int[] {-1, 27}) {
            assertNull(PlayerSlotMap.getPickpocketItem(player, invalid));
            PlayerSlotMap.setPickpocketItem(player, invalid, item);
        }
        verifyNoInteractions(player, inventory);
    }

    @Test
    void searchableSlotsIncludeAllRegionsButSkipEmptyAirAndIgnoredItems() {
        ItemStack ignored = item(Material.DIAMOND);
        storage[0] = item(Material.STONE);
        storage[1] = item(Material.AIR);
        storage[2] = ignored;
        storage[35] = item(Material.STONE);
        armor[0] = item(Material.IRON_BOOTS);
        armor[3] = item(Material.IRON_HELMET);
        ItemStack offhand = item(Material.SHIELD);
        when(inventory.getItemInOffHand()).thenReturn(offhand);
        try (MockedStatic<StealIgnoreRules> rules = mockStatic(StealIgnoreRules.class)) {
            rules.when(() -> StealIgnoreRules.isIgnored(ignored)).thenReturn(true);
            assertEquals(List.of(0, 35, 36, 39, 40), PlayerSlotMap.listSearchableSlots(player));
            rules.verify(() -> StealIgnoreRules.isIgnored(storage[1]), never());
        }
    }

    @Test
    void pickpocketListExcludesHotbarAndEquipmentAndUsesLogicalIndices() {
        ItemStack ignored = item(Material.DIAMOND);
        storage[0] = item(Material.STONE);
        storage[9] = item(Material.STONE);
        storage[10] = item(Material.CAVE_AIR);
        storage[11] = ignored;
        storage[35] = item(Material.STONE);
        armor[0] = item(Material.IRON_BOOTS);
        ItemStack offhand = item(Material.SHIELD);
        when(inventory.getItemInOffHand()).thenReturn(offhand);
        try (MockedStatic<StealIgnoreRules> rules = mockStatic(StealIgnoreRules.class)) {
            rules.when(() -> StealIgnoreRules.isIgnored(ignored)).thenReturn(true);
            assertEquals(List.of(0, 26), PlayerSlotMap.listPickpocketSlots(player));
            rules.verify(() -> StealIgnoreRules.isIgnored(storage[10]), never());
        }
    }

    @Test
    void droppingClearsAndDropsEligibleItemsWhilePreservingIgnoredAndAir() {
        World world = mock(World.class);
        Location location = new Location(world, 12, 64, 34);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(location);
        ItemStack normal = item(Material.STONE);
        ItemStack worn = item(Material.IRON_BOOTS);
        ItemStack held = item(Material.SHIELD);
        ItemStack ignored = item(Material.DIAMOND);
        storage[0] = normal;
        storage[1] = item(Material.AIR);
        storage[2] = ignored;
        armor[0] = worn;
        armor[1] = ignored;
        when(inventory.getItemInOffHand()).thenReturn(held);
        try (MockedStatic<StealIgnoreRules> rules = mockStatic(StealIgnoreRules.class)) {
            rules.when(() -> StealIgnoreRules.isIgnored(ignored)).thenReturn(true);
            PlayerSlotMap.dropAllExceptIgnored(player);
            verify(inventory).setItem(0, null);
            verify(inventory, never()).setItem(eq(1), any());
            verify(inventory, never()).setItem(eq(2), any());
            assertNull(armor[0]);
            assertSame(ignored, armor[1]);
            verify(inventory).setArmorContents(armor);
            verify(inventory).setItemInOffHand(null);
            verify(world).dropItemNaturally(location, normal);
            verify(world).dropItemNaturally(location, worn);
            verify(world).dropItemNaturally(location, held);
            verifyNoMoreInteractions(world);
        }
    }

    @Test
    void nullPlayerHasNoSearchableItemsAndCanBeDroppedSafely() {
        assertTrue(PlayerSlotMap.listSearchableSlots(null).isEmpty());
        assertTrue(PlayerSlotMap.listPickpocketSlots(null).isEmpty());
        PlayerSlotMap.dropAllExceptIgnored(null);
    }

    private static ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        // Material.isAir() uses the live Paper registry; model that API boundary here.
        Material type = mock(Material.class);
        when(type.isAir()).thenReturn(material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR);
        when(item.getType()).thenReturn(type);
        return item;
    }
}
