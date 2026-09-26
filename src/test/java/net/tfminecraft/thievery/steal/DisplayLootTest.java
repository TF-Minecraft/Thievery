package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.thievery.category.*;
import net.tfminecraft.thievery.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

class DisplayLootTest {
    private MockedStatic<CategoryHandler> categories;
    private MockedStatic<ClueGiver> clues;
    private PlayerData data;
    private Player player;
    private Inventory storage;

    @BeforeEach void setup() {
        MockBukkit.mock();
        data = new PlayerData(UUID.randomUUID());
        player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        storage = MockBukkit.getMock().createInventory(null, 36);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(i -> storage.addItem(i.getArgument(0, ItemStack.class)));
        when(inventory.removeItem(any(ItemStack.class))).thenAnswer(i -> storage.removeItem(i.getArgument(0, ItemStack.class)));
        when(inventory.getStorageContents()).thenAnswer(i -> storage.getStorageContents());
        categories = mockStatic(CategoryHandler.class);
        categories.when(() -> CategoryHandler.canRevealItem(eq(data), any())).thenReturn(true);
        categories.when(() -> CategoryHandler.getPerItemValue(any())).thenReturn(2.0);
        categories.when(() -> CategoryHandler.getTotalValue(any())).thenAnswer(i -> 2.0 * i.getArgument(0, ItemStack.class).getAmount());
        clues = mockStatic(ClueGiver.class);
    }
    @AfterEach void close() { clues.close(); categories.close(); MockBukkit.unmock(); }

    private DisplayLoot.DisplaySlot slot(ItemStack item) {
        var slot = mock(DisplayLoot.DisplaySlot.class);
        when(slot.get()).thenReturn(item);
        when(slot.take(any())).thenAnswer(i -> {
            assertTrue(DisplayLoot.isDumping(), "Listeners must recognize transfers initiated by a loot dump");
            item.setAmount(item.getAmount() - i.getArgument(0, ItemStack.class).getAmount());
            return true;
        });
        return slot;
    }
    @Test void transfersOnlyAffordableItemsAndChargesForItemsActuallyMoved() {
        ItemStack source = new ItemStack(Material.DIAMOND, 9);
        var slot = slot(source);
        StealBudget budget = new StealBudget(7);
        DisplayLoot.dump(player, List.of(slot), budget, data);
        assertEquals(6, source.getAmount());
        assertEquals(3, storage.getItem(0).getAmount());
        assertEquals(6, budget.getUsed());
        assertFalse(DisplayLoot.isDumping());
    }
    @Test void inventoryCapacityLimitsTransfersAndFullStorageLeavesTheSourceUntouched() {
        for (int i = 0; i < 36; i++) storage.setItem(i, new ItemStack(Material.STONE, 64));
        storage.setItem(0, new ItemStack(Material.DIAMOND, 63));
        ItemStack source = new ItemStack(Material.DIAMOND, 5);
        var slot = slot(source);
        var budget = new StealBudget(10);
        DisplayLoot.dump(player, List.of(slot), budget, data);
        assertEquals(4, source.getAmount());
        assertEquals(64, storage.getItem(0).getAmount());
        assertEquals(2, budget.getUsed());
        clearInvocations(slot);
        DisplayLoot.dump(player, List.of(slot), budget, data);
        verify(slot, never()).take(any());
        assertEquals(2, budget.getUsed());
    }
    @Test void rejectedSourceRemovalRollsBackOnlyProvisionalLootAndDoesNotConsumeBudget() {
        var slot = slot(new ItemStack(Material.DIAMOND, 3));
        doReturn(false).when(slot).take(any());
        var budget = new StealBudget(10);
        storage.setItem(10, new ItemStack(Material.DIAMOND, 2));
        DisplayLoot.dump(player, List.of(slot), budget, data);
        assertEquals(new ItemStack(Material.DIAMOND, 2), storage.getItem(10));
        assertEquals(2, java.util.Arrays.stream(storage.getStorageContents()).filter(java.util.Objects::nonNull).mapToInt(ItemStack::getAmount).sum());
        assertEquals(3, slot.get().getAmount());
        assertEquals(0, budget.getUsed());
        assertFalse(DisplayLoot.isDumping());
    }
    @Test @SuppressWarnings("unchecked")
    void cancellingFurnitureListenerCanRemoveProvisionalLootWithoutRollbackRecreatingIt() {
        var furniture=mock(net.tfminecraft.interactiblefurniture.furniture.Furniture.class);
        var placed=mock(net.tfminecraft.interactiblefurniture.furniture.PlacedSlot.class);
        var definition=mock(net.tfminecraft.interactiblefurniture.furniture.SlotDefinition.class);
        ItemStack source=new ItemStack(Material.DIAMOND,3);when(placed.getCurrentItem()).thenReturn(source);when(placed.getDefinition()).thenReturn(definition);
        when(furniture.getActiveSlots()).thenReturn(Map.of("display",placed));UUID entityId=UUID.randomUUID();when(furniture.getEntityId()).thenReturn(entityId);
        var entity=mock(org.bukkit.entity.Entity.class);var displays=mock(DisplayStealManager.class);var listener=new FurnitureDisplayListener(displays);
        when(player.getInventory().getItemInMainHand()).thenReturn(new ItemStack(Material.STICK));
        var budget=new StealBudget(10);var callbacks=new java.util.concurrent.atomic.AtomicInteger();storage.setItem(10,new ItemStack(Material.DIAMOND,2));
        MockBukkit.getMock().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void revoke(net.tfminecraft.interactiblefurniture.events.FurnitureSlotItemTakeEvent event) {
                assertTrue(DisplayLoot.isDumping());assertTrue(storage.removeItem(new ItemStack(Material.DIAMOND,3)).isEmpty());
                callbacks.incrementAndGet();event.setCancelled(true);
            }
        },MockBukkit.createMockPlugin());
        try(var locks=mockStatic(FurnitureLockHelper.class);var tools=mockStatic(net.tfminecraft.thievery.utils.ToolResolver.class);var bukkit=mockStatic(org.bukkit.Bukkit.class,CALLS_REAL_METHODS)) {
            locks.when(()->FurnitureLockHelper.isLockable(furniture)).thenReturn(true);locks.when(()->FurnitureLockHelper.getLockState(furniture)).thenReturn(net.tfminecraft.thievery.door.LockState.PRIVATE);tools.when(()->net.tfminecraft.thievery.utils.ToolResolver.isLockpick(any())).thenReturn(true);
            bukkit.when(()->org.bukkit.Bukkit.getEntity(entityId)).thenReturn(entity);
            var interaction=mock(net.tfminecraft.interactiblefurniture.events.FurnitureInteractEvent.class);when(interaction.getFurniture()).thenReturn(furniture);when(interaction.getPlayer()).thenReturn(player);
            listener.onFurnitureInteract(interaction);
            org.mockito.ArgumentCaptor<List<DisplayLoot.DisplaySlot>> slots=org.mockito.ArgumentCaptor.forClass(List.class);
            verify(displays).handleLockpick(eq(player),eq(entity),isNull(),eq(net.tfminecraft.thievery.door.LockState.PRIVATE),slots.capture());
            DisplayLoot.dump(player,slots.getValue(),budget,data);
        }
        // The listener removed the provisional diamonds, so the thief keeps exactly their own two.
        assertEquals(1,callbacks.get());assertEquals(new ItemStack(Material.DIAMOND,2),storage.getItem(10));assertEquals(1,storage.all(Material.DIAMOND).size());
        assertEquals(3,source.getAmount());verify(placed,never()).setCurrentItem(any());verify(furniture,never()).removeActiveSlot(any());assertEquals(0,budget.getUsed());assertFalse(DisplayLoot.isDumping());
    }
    @Test void sourceFailureAlwaysClearsTheDumpListenerFlag() {
        var slot = mock(DisplayLoot.DisplaySlot.class);
        when(slot.get()).thenThrow(new IllegalStateException("display removed"));
        assertThrows(IllegalStateException.class, () -> DisplayLoot.dump(player, List.of(slot), new StealBudget(10), data));
        assertFalse(DisplayLoot.isDumping());
    }
    @Test void eligibilityRespectsCategoriesCluesBudgetAndBundleContents() {
        ItemStack diamond = new ItemStack(Material.DIAMOND);
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        assertFalse(DisplayLoot.isEligible(null, data, 10));
        assertFalse(DisplayLoot.isEligible(new ItemStack(Material.AIR), data, 10));
        assertFalse(DisplayLoot.isEligible(diamond, data, 1));
        assertTrue(DisplayLoot.hasAnything(List.of(slot(diamond)), data, 2));
        categories.when(() -> CategoryHandler.canRevealItem(data, diamond)).thenReturn(false);
        assertFalse(DisplayLoot.hasAnything(List.of(slot(diamond)), data, 10));
        categories.when(() -> CategoryHandler.canRevealItem(data, diamond)).thenReturn(true);
        clues.when(() -> ClueGiver.isClueItem(diamond)).thenReturn(true);
        assertFalse(DisplayLoot.isEligible(diamond, data, 10));
        try (var values = mockStatic(ItemValue.class)) {
            values.when(() -> ItemValue.isBundle(bundle)).thenReturn(true);
            categories.when(() -> CategoryHandler.canRevealItem(data, bundle)).thenReturn(false);
            assertFalse(DisplayLoot.isEligible(bundle, data, 10));
            values.when(() -> ItemValue.hasStealableContents(data, bundle, 10)).thenReturn(true);
            assertTrue(DisplayLoot.isEligible(bundle, data, 10));
            values.when(() -> ItemValue.hasStealableContents(data, bundle, 10)).thenReturn(false);
            categories.when(() -> CategoryHandler.canRevealItem(data, bundle)).thenReturn(true);
            assertTrue(DisplayLoot.isEligible(bundle, data, 10));
        }
    }
    @Test void absentDisplayContextCannotMoveItems() {
        var slot = slot(new ItemStack(Material.DIAMOND));
        var budget = new StealBudget(10);
        assertFalse(DisplayLoot.hasAnything(null, data, 10));
        assertFalse(DisplayLoot.hasAnything(List.of(slot), null, 10));
        DisplayLoot.dump(null, List.of(slot), budget, data);
        DisplayLoot.dump(player, null, budget, data);
        DisplayLoot.dump(player, List.of(slot), null, data);
        DisplayLoot.dump(player, List.of(slot), budget, null);
        categories.when(() -> CategoryHandler.canRevealItem(data, slot.get())).thenReturn(false);
        DisplayLoot.dump(player, List.of(slot), budget, data);
        verify(slot, never()).take(any());
        assertTrue(storage.isEmpty());
    }
}
