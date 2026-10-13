package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.tfminecraft.thievery.door.LockStateLog;
import net.tfminecraft.thievery.utils.ToolResolver;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
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
    private final AtomicReference<LockOwner> owner = new AtomicReference<>();
    private FactionFixture identity;
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
        identity = new FactionFixture();
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
        owner.set(LockOwner.character(UUID.randomUUID()));
        locks = mockStatic(FurnitureLockHelper.class);
        locks.when(() -> FurnitureLockHelper.read(furniture)).thenAnswer(i -> new FurnitureLockHelper.FurnitureLock(owner.get(), state.get(), false));
        locks.when(() -> FurnitureLockHelper.isLockable(furniture)).thenReturn(true);
        locks.when(() -> FurnitureLockHelper.getOwner(furniture)).thenAnswer(i -> owner.get());
        locks.when(() -> FurnitureLockHelper.getLockState(furniture)).thenAnswer(i -> state.get());
        locks.when(() -> FurnitureLockHelper.setOwner(eq(furniture), any())).thenAnswer(i -> { owner.set(i.getArgument(1)); return null; });
        locks.when(() -> FurnitureLockHelper.setLockState(eq(furniture), any())).thenAnswer(i -> { state.set(i.getArgument(1)); return null; });
        locks.when(() -> FurnitureLockHelper.rotateLockState(furniture)).thenAnswer(i -> state.updateAndGet(LockState::next));
        access = mockStatic(LockAccess.class, CALLS_REAL_METHODS);
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
        identity.close();
        MockBukkit.unmock();
    }

    @Test
    void placementClaimsUnownedFurnitureButPreservesExistingOwnershipAndIgnoresAutomatedPlacement() {
        FurniturePlaceEvent event = new FurniturePlaceEvent(furniture, player);
        LockOwner original = owner.get();
        listener.onFurniturePlace(event);
        assertEquals(original, owner.get());
        owner.set(null);
        listener.onFurniturePlace(event);
        assertEquals(LockOwner.character(player.getUniqueId()), owner.get());
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
    void aDeadOwnersPieceIsInheritedOnFirstInteractionSoTheRecipientCanRotateIt() {
        when(player.isSneaking()).thenReturn(true);
        // The first interaction applies the estate before the lock is read.
        locks.when(() -> FurnitureLockHelper.inherit(furniture)).thenAnswer(i -> {
            owner.set(LockOwner.character(player.getUniqueId()));
            return null;
        });
        FurnitureBreakEvent event = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(event);
        assertTrue(event.isCancelled());
        assertEquals(LockState.GUILD, state.get(), "the recipient rotated the inherited lock");
        locks.verify(() -> FurnitureLockHelper.inherit(furniture));
    }

    @Test
    void sneakingBreakClaimsUnownedFurnitureRotatesOwnersLockAndRejectsOtherPlayers() {
        when(player.isSneaking()).thenReturn(true);
        FurnitureBreakEvent event = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(event);
        assertTrue(event.isCancelled());
        verify(player).sendMessage(contains("Only the owner can change this lock"));
        owner.set(null);
        listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
        assertEquals(LockOwner.character(player.getUniqueId()), owner.get());
        locks.verify(() -> FurnitureLockHelper.upgrade(furniture), atLeastOnce());
        assertEquals(LockState.DEFAULT, state.get());
        Location origin = new Location(null, 3, 63, 5);
        Location below = new Location(null, 3, 62, 5);
        Location furnitureLocation = mock(Location.class, RETURNS_DEEP_STUBS);
        when(furnitureLocation.getBlock().getRelative(BlockFace.DOWN).getLocation()).thenReturn(below);
        when(furniture.getLoc()).thenReturn(furnitureLocation);
        when(furniture.getOriginBlockLocation()).thenReturn(Optional.of(origin));
        try (MockedStatic<LockStateLog> log = mockStatic(LockStateLog.class)) {
            listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
            log.verify(() -> LockStateLog.record(player, origin, LockState.PRIVATE, false));
            when(furniture.getOriginBlockLocation()).thenReturn(Optional.empty());
            listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
            log.verify(() -> LockStateLog.record(player, below, LockState.GUILD, false));
        }
        assertEquals(LockState.GUILD, state.get());
        locks.verify(() -> FurnitureLockHelper.rotateLockState(furniture), times(2));
    }

    @Test
    void placementWithoutACharacterLeavesFurnitureUnownedAndStaffStampTheServer() {
        FurniturePlaceEvent event = new FurniturePlaceEvent(furniture, player);
        owner.set(null);
        identity.plays(player, null);
        listener.onFurniturePlace(event);
        assertNull(owner.get());
        locks.verify(() -> FurnitureLockHelper.setOwner(any(), any()), never());
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        listener.onFurniturePlace(event);
        assertEquals(LockOwner.server(), owner.get());
        assertEquals(LockState.PRIVATE, state.get());
        assertFalse(event.isCancelled());
    }

    @Test
    void aClaimNeedsACharacterAndAnAltCannotRotateItsMainsLock() {
        when(player.isSneaking()).thenReturn(true);
        owner.set(null);
        identity.plays(player, null);
        listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
        assertNull(owner.get());
        verify(player).sendMessage(contains("Play a character"));
        UUID main = UUID.randomUUID();
        owner.set(LockOwner.character(main));
        state.set(LockState.PRIVATE);
        identity.plays(player, UUID.randomUUID());
        listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
        assertEquals(LockState.PRIVATE, state.get());
        identity.plays(player, main);
        try (MockedStatic<LockStateLog> log = mockStatic(LockStateLog.class)) {
            when(furniture.getOriginBlockLocation()).thenReturn(Optional.of(new Location(null, 0, 0, 0)));
            listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
        }
        assertEquals(LockState.GUILD, state.get());
        owner.set(LockOwner.unreadable());
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        listener.onFurnitureBreak(new FurnitureBreakEvent(furniture, player));
        assertEquals(LockState.GUILD, state.get());
        verify(player).sendMessage(contains("unreadable"));
    }

    @Test
    void guardModeCancelsPlacementAndBreakingRefusesLockChangesAndKeepsPlayersOut() {
        net.tfminecraft.thievery.owner.LockGuard.enter("test");
        FurniturePlaceEvent place = new FurniturePlaceEvent(furniture, player);
        owner.set(null);
        listener.onFurniturePlace(place);
        assertTrue(place.isCancelled());
        assertNull(owner.get());
        owner.set(LockOwner.character(player.getUniqueId()));
        FurnitureBreakEvent breaking = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(breaking);
        assertTrue(breaking.isCancelled());
        when(player.isSneaking()).thenReturn(true);
        FurnitureBreakEvent toggle = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(toggle);
        assertTrue(toggle.isCancelled());
        assertEquals(LockState.PRIVATE, state.get());
        FurnitureInteractEvent use = interaction();
        listener.onFurnitureInteract(use);
        verify(use).setCancelled(true);
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        FurnitureInteractEvent staff = interaction();
        listener.onFurnitureInteract(staff);
        verify(staff, never()).setCancelled(true);
        state.set(LockState.PUBLIC);
        when(player.hasPermission("thievery.admin")).thenReturn(false);
        FurnitureInteractEvent open = interaction();
        listener.onFurnitureInteract(open);
        verify(open, never()).setCancelled(true);
        owner.set(null);
        state.set(LockState.PRIVATE);
        FurnitureInteractEvent unowned = interaction();
        listener.onFurnitureInteract(unowned);
        verify(unowned, never()).setCancelled(true);
        verify(player, atLeastOnce()).sendMessage(contains("Locks are starting up"));
    }

    @Test
    void anErrorOnAnOwnershipPathCancelsTheEvent() {
        locks.when(() -> FurnitureLockHelper.read(furniture)).thenThrow(new IllegalStateException("boom"));
        FurnitureInteractEvent use = interaction();
        listener.onFurnitureInteract(use);
        verify(use).setCancelled(true);
        FurnitureBreakEvent breaking = new FurnitureBreakEvent(furniture, player);
        listener.onFurnitureBreak(breaking);
        assertTrue(breaking.isCancelled());
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
