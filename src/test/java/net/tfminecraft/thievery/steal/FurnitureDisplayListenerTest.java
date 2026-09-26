package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.events.*;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.PlacedSlot;
import net.tfminecraft.interactiblefurniture.furniture.SlotDefinition;
import net.tfminecraft.thievery.door.FactionLockTutorial;
import net.tfminecraft.thievery.door.LockAccess;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.utils.ToolResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class FurnitureDisplayListenerTest {
    private ServerMock server;
    private FurnitureDisplayListener listener;
    private DisplayStealManager displays;
    private Furniture furniture;
    private Player player;
    private Entity entity;
    private ItemStack held;
    private final AtomicReference<UUID> owner = new AtomicReference<>();
    private final AtomicReference<LockState> state = new AtomicReference<>(LockState.PRIVATE);
    private MockedStatic<FurnitureLockHelper> locks;
    private MockedStatic<LockAccess> access;
    private MockedStatic<FactionLockTutorial> tutorial;
    private MockedStatic<ToolResolver> tools;
    private MockedStatic<DisplayLoot> loot;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<InteractibleFurniture> furniturePlugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(server.addSimpleWorld("furniture"), 0, 64, 0));
        PlayerInventory inventory = mock(PlayerInventory.class);
        held = new ItemStack(Material.STICK);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(player.getInventory()).thenReturn(inventory);
        furniture = mock(Furniture.class);
        when(furniture.getEntityId()).thenReturn(UUID.randomUUID());
        when(furniture.getActiveSlots()).thenReturn(Map.of());
        entity = mock(Entity.class);
        displays = mock(DisplayStealManager.class);
        listener = new FurnitureDisplayListener(displays);
        owner.set(UUID.randomUUID());
        locks = mockStatic(FurnitureLockHelper.class);
        locks.when(() -> FurnitureLockHelper.isLockable(furniture)).thenReturn(true);
        locks.when(() -> FurnitureLockHelper.getOwner(furniture)).thenAnswer(i -> owner.get());
        locks.when(() -> FurnitureLockHelper.getLockState(furniture)).thenAnswer(i -> state.get());
        locks.when(() -> FurnitureLockHelper.setOwner(eq(furniture), any())).thenAnswer(i -> { owner.set(i.getArgument(1)); return null; });
        locks.when(() -> FurnitureLockHelper.setLockState(eq(furniture), any())).thenAnswer(i -> { state.set(i.getArgument(1)); return null; });
        locks.when(() -> FurnitureLockHelper.rotateLockState(furniture)).thenAnswer(i -> state.updateAndGet(LockState::next));
        access = mockStatic(LockAccess.class);
        tutorial = mockStatic(FactionLockTutorial.class);
        tools = mockStatic(ToolResolver.class);
        loot = mockStatic(DisplayLoot.class);
        furniturePlugin = mockStatic(InteractibleFurniture.class, RETURNS_DEEP_STUBS);
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(() -> Bukkit.getEntity(furniture.getEntityId())).thenReturn(entity);
    }

    @AfterEach
    void tearDown() {
        bukkit.close(); furniturePlugin.close(); loot.close(); tools.close(); tutorial.close(); access.close(); locks.close();
        MockBukkit.unmock();
    }

    @Test
    void placementClaimsUnownedFurnitureButPreservesExistingOwnershipAndIgnoresAutomatedPlacement() {
        FurniturePlaceEvent event = new FurniturePlaceEvent(furniture, player);
        UUID original = owner.get();
        listener.onFurniturePlace(event);
        assertEquals(original, owner.get());
        owner.set(null);
        listener.onFurniturePlace(event);
        assertEquals(player.getUniqueId(), owner.get());
        assertEquals(LockState.DEFAULT, state.get());
        tutorial.verify(() -> FactionLockTutorial.onLockState(player, LockState.DEFAULT));
        owner.set(null);
        listener.onFurniturePlace(new FurniturePlaceEvent(furniture, null));
        assertNull(owner.get());
        locks.when(() -> FurnitureLockHelper.isLockable(furniture)).thenReturn(false);
        listener.onFurniturePlace(event);
        assertNull(owner.get());
    }

    @Test
    void sneakingBreakClaimsUnownedFurnitureRotatesOwnersLockAndRejectsOtherPlayers() {
        when(player.isSneaking()).thenReturn(true);
        FurnitureBreakEvent event = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(event);
        assertTrue(event.isCancelled());
        verify(player).sendMessage(contains("containers you own"));
        owner.set(null);
        listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
        assertEquals(player.getUniqueId(), owner.get());
        assertEquals(LockState.DEFAULT, state.get());
        listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
        assertEquals(LockState.PRIVATE, state.get());
        locks.verify(() -> FurnitureLockHelper.rotateLockState(furniture));
    }

    @Test
    void breakAndInteractRespectAccessWhileUnmanagedOrAutomatedEventsAreIgnored() {
        FurnitureBreakEvent denied = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(denied);
        assertTrue(denied.isCancelled());
        FurnitureInteractEvent interact = interaction();
        listener.onFurnitureInteract(interact);
        verify(interact).setCancelled(true);
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        FurnitureBreakEvent allowed = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(allowed);
        assertFalse(allowed.isCancelled());
        verify(player).sendMessage(contains("Bypassing lock"));
        FurnitureInteractEvent allowedInteraction = interaction();
        listener.onFurnitureInteract(allowedInteraction);
        verify(allowedInteraction, never()).setCancelled(true);
        FurnitureBreakEvent automated = new FurnitureBreakEvent(furniture, null);
        listener.onFurnitureBreak(automated);
        assertFalse(automated.isCancelled());
        locks.when(() -> FurnitureLockHelper.isLockable(furniture)).thenReturn(false);
        FurnitureBreakEvent unmanaged = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(unmanaged);
        assertFalse(unmanaged.isCancelled());
        FurnitureInteractEvent unmanagedInteract = interaction();
        listener.onFurnitureInteract(unmanagedInteract);
        verify(unmanagedInteract, never()).setCancelled(true);
    }

    @Test
    void slotTakeAndAddEnforceAccessExceptDuringAuthorizedDump() {
        FurnitureSlotItemTakeEvent take = mock(FurnitureSlotItemTakeEvent.class);
        when(take.getFurniture()).thenReturn(furniture);
        when(take.getPlayer()).thenReturn(player);
        FurnitureSlotItemAddEvent add = mock(FurnitureSlotItemAddEvent.class);
        when(add.getFurniture()).thenReturn(furniture);
        when(add.getPlayer()).thenReturn(player);
        listener.onSlotTake(take);
        listener.onSlotAdd(add);
        verify(take).setCancelled(true);
        verify(add).setCancelled(true);
        clearInvocations(take, add);
        loot.when(DisplayLoot::isDumping).thenReturn(true);
        listener.onSlotTake(take);
        verify(take, never()).setCancelled(true);
        loot.when(DisplayLoot::isDumping).thenReturn(false);
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        listener.onSlotTake(take);
        listener.onSlotAdd(add);
        verify(take, never()).setCancelled(true);
        verify(add, never()).setCancelled(true);
        when(player.hasPermission("thievery.admin")).thenReturn(false);
        locks.when(() -> FurnitureLockHelper.isLockable(furniture)).thenReturn(false);
        listener.onSlotTake(take);
        listener.onSlotAdd(add);
        verify(take, never()).setCancelled(true);
        verify(add, never()).setCancelled(true);
    }

    @Test
    void lockpickDelegatesEntityOwnershipAndLiveSlotsAndTransfersOnlyRequestedAmount() {
        PlacedSlot placed = placedSlot(new ItemStack(Material.DIAMOND, 5));
        var slot = stealSlot(placed);
        assertEquals(5, slot.get().getAmount());
        ItemStack taken = new ItemStack(Material.DIAMOND, 2);
        AtomicReference<FurnitureSlotItemTakeEvent> observed = new AtomicReference<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void observe(FurnitureSlotItemTakeEvent event) { observed.set(event); }
        }, MockBukkit.createMockPlugin());
        assertTrue(slot.take(taken));
        assertSame(furniture, observed.get().getFurniture());
        assertSame(player, observed.get().getPlayer());
        assertNotSame(taken, observed.get().getItem());
        assertEquals(taken, observed.get().getItem());
        ArgumentCaptor<ItemStack> remaining = ArgumentCaptor.forClass(ItemStack.class);
        verify(placed).setCurrentItem(remaining.capture());
        assertEquals(3, remaining.getValue().getAmount());
        verify(furniture, never()).removeActiveSlot(anyString());
        verify(InteractibleFurniture.getInstance().getFurnitureManager()).persistFurniture(furniture);
    }

    @Test
    void takingEntireSlotRemovesItAndPersistsFurniture() {
        PlacedSlot placed = placedSlot(new ItemStack(Material.DIAMOND, 2));
        var slot = stealSlot(placed);
        assertTrue(slot.take(new ItemStack(Material.DIAMOND, 2)));
        verify(furniture).removeActiveSlot("display");
        verify(placed, never()).setCurrentItem(any());
        verify(InteractibleFurniture.getInstance().getFurnitureManager()).persistFurniture(furniture);
    }

    @Test
    void cancelledSlotTakePreservesItemAndDoesNotPersistOrRemoveAnything() {
        PlacedSlot placed = placedSlot(new ItemStack(Material.DIAMOND, 2));
        var slot = stealSlot(placed);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void cancel(FurnitureSlotItemTakeEvent event) { event.setCancelled(true); }
        }, MockBukkit.createMockPlugin());
        assertFalse(slot.take(new ItemStack(Material.DIAMOND)));
        verify(furniture, never()).removeActiveSlot(anyString());
        verify(placed, never()).setCurrentItem(any());
        furniturePlugin.verifyNoInteractions();
    }

    @Test
    void slotEmptiedBeforeSelectionCannotBeTakenAgain() {
        PlacedSlot placed = placedSlot(new ItemStack(Material.DIAMOND));
        var slot = stealSlot(placed);
        when(placed.getCurrentItem()).thenReturn(null);
        assertNull(slot.get());
        assertFalse(slot.take(new ItemStack(Material.DIAMOND)));
        ItemStack air = new ItemStack(Material.AIR);
        when(placed.getCurrentItem()).thenReturn(air);
        assertFalse(slot.take(new ItemStack(Material.DIAMOND)));
        verify(placed, never()).setCurrentItem(any());
        furniturePlugin.verifyNoInteractions();
    }

    private PlacedSlot placedSlot(ItemStack item) {
        PlacedSlot placed = mock(PlacedSlot.class);
        when(placed.getId()).thenReturn("display");
        when(placed.getDefinition()).thenReturn(mock(SlotDefinition.class));
        when(placed.getCurrentItem()).thenReturn(item);
        return placed;
    }

    @SuppressWarnings("unchecked")
    private DisplayLoot.DisplaySlot stealSlot(PlacedSlot placed) {
        when(furniture.getActiveSlots()).thenReturn(Map.of("display", placed));
        tools.when(() -> ToolResolver.isLockpick(held)).thenReturn(true);
        FurnitureInteractEvent event = interaction();
        listener.onFurnitureInteract(event);
        verify(event).setCancelled(true);
        ArgumentCaptor<List<DisplayLoot.DisplaySlot>> slots = ArgumentCaptor.forClass(List.class);
        verify(displays).handleLockpick(eq(player), eq(entity), eq(owner.get()), eq(state.get()), slots.capture());
        assertEquals(1, slots.getValue().size());
        return slots.getValue().getFirst();
    }

    private FurnitureInteractEvent interaction() {
        FurnitureInteractEvent event = mock(FurnitureInteractEvent.class);
        when(event.getFurniture()).thenReturn(furniture);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }
}
