package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.rpcharacters.grave.GraveManager;
import net.tfminecraft.thievery.utils.ToolResolver;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.*;

class ContainerManagerTest {
    World world; Player player; ContainerManager manager; ContainerDataManager storage; Map<Location,ContainerData> data;
    MockedStatic<net.tfminecraft.thievery.Thievery> plugin;
    MockedConstruction<ContainerDataManager> construction; MockedStatic<GraveManager> graves; MockedStatic<ToolResolver> tools; MockedStatic<FactionLockTutorial> tutorial;
    @BeforeEach void setup() {
        MockBukkit.mock();
        var instance=mock(net.tfminecraft.thievery.Thievery.class); when(instance.isEnabled()).thenReturn(true); when(instance.getName()).thenReturn("Thievery"); when(instance.namespace()).thenReturn("thievery");
        plugin=mockStatic(net.tfminecraft.thievery.Thievery.class); plugin.when(net.tfminecraft.thievery.Thievery::getInstance).thenReturn(instance);
        world=MockBukkit.getMock().addSimpleWorld("containers"); data=new HashMap<>();
        construction=mockConstruction(ContainerDataManager.class,(mock,context)-> {
            when(mock.loadContainerData(any())).thenAnswer(call->data.computeIfAbsent(call.getArgument(0),ContainerData::new));
            when(mock.getOwner(any())).thenAnswer(call->data.computeIfAbsent(call.getArgument(0),ContainerData::new).getOwner());
            doAnswer(call->{ContainerData saved=call.getArgument(0); data.put(saved.getLocation(),saved); return null;}).when(mock).saveContainerData(any());
        }); manager=new ContainerManager(); storage=construction.constructed().getFirst();
        player=mock(Player.class,RETURNS_DEEP_STUBS); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getName()).thenReturn("thief"); when(player.getLocation()).thenReturn(new Location(world,0,64,0)); when(player.getInventory().getItemInMainHand()).thenReturn(new ItemStack(Material.STICK));
        graves=mockStatic(GraveManager.class,RETURNS_DEEP_STUBS); tools=mockStatic(ToolResolver.class); tutorial=mockStatic(FactionLockTutorial.class);
    }
    @AfterEach void close() { tutorial.close(); tools.close(); graves.close(); construction.close(); plugin.close(); MockBukkit.unmock(); }
    Block block(int x,Material type) { var block=world.getBlockAt(x,64,0); block.setType(type); return block; }
    ContainerData lock(Block block,UUID owner) { var lock=new ContainerData(block.getLocation(),owner); lock.setLockState(LockState.PRIVATE); data.put(block.getLocation(),lock); return lock; }
    PlayerInteractEvent interact(Block block,Action action) { var event=mock(PlayerInteractEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getClickedBlock()).thenReturn(block); when(event.getAction()).thenReturn(action); return event; }
    InventoryOpenEvent open(Inventory inventory) { var event=mock(InventoryOpenEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getInventory()).thenReturn(inventory); return event; }
    @Test void feedbackDefaultsOffAndAdminJoinsEnableIt() {
        assertFalse(manager.getFeedbackState(player.getUniqueId())); var event=mock(PlayerJoinEvent.class); when(event.getPlayer()).thenReturn(player); manager.onPlayerJoin(event); assertFalse(manager.getFeedbackState(player.getUniqueId()));
        when(player.hasPermission("thievery.admin")).thenReturn(true); manager.onPlayerJoin(event); assertTrue(manager.getFeedbackState(player.getUniqueId())); manager.setFeedbackState(player.getUniqueId(),false); assertFalse(manager.getFeedbackState(player.getUniqueId())); manager.enableFeedback(player); assertTrue(manager.getFeedbackState(player.getUniqueId()));
    }
    @Test void openingUnclaimedContainerClaimsItButPrivateLocksRequireAccessOrStaff() {
        var block=block(0,Material.BARREL); var inventory=((Container)block.getState()).getInventory(); var event=open(inventory); manager.onInventoryOpen(event); assertEquals(player.getUniqueId(),data.get(block.getLocation()).getOwner()); verify(event,never()).setCancelled(true);
        var privateData=lock(block,UUID.randomUUID()); clearInvocations(storage); manager.onInventoryOpen(event); verify(event).setCancelled(true); verify(storage,never()).saveContainerData(any());
        when(player.hasPermission("thievery.admin")).thenReturn(true); clearInvocations(event); manager.onInventoryOpen(event); verify(event,never()).setCancelled(true); verify(player).sendMessage("§cBypassing lock due to staff"); assertNotEquals(player.getUniqueId(),privateData.getOwner());
        var unrelated=open(mock(Inventory.class)); manager.onInventoryOpen(unrelated); verify(unrelated,never()).setCancelled(true);
        when(unrelated.getPlayer()).thenReturn(mock(HumanEntity.class)); manager.onInventoryOpen(unrelated);
    }
    @Test void rightClickAccessGuardDoesNotInterceptLockpickToolsOrNoncontainers() {
        var barrel=block(0,Material.BARREL); lock(barrel,UUID.randomUUID()); var event=interact(barrel,Action.RIGHT_CLICK_BLOCK); manager.onContainerRightClickAccessCheck(event); verify(event).setCancelled(true);
        clearInvocations(event); when(player.hasPermission("thievery.admin")).thenReturn(true); manager.onContainerRightClickAccessCheck(event); verify(event,never()).setCancelled(true); verify(player,never()).sendMessage("§cBypassing lock due to staff");
        when(player.hasPermission("thievery.admin")).thenReturn(false); tools.when(()->ToolResolver.isLockpick(any())).thenReturn(true); manager.onContainerRightClickAccessCheck(event); verify(event,never()).setCancelled(true);
        tools.when(()->ToolResolver.isLockpick(any())).thenReturn(false); graves.when(()->GraveManager.get().isGrave(barrel)).thenReturn(true); manager.onContainerRightClickAccessCheck(event); verify(event,never()).setCancelled(true);
        for(var other:List.of(interact(barrel,Action.LEFT_CLICK_BLOCK),interact(null,Action.RIGHT_CLICK_BLOCK),interact(block(4,Material.STONE),Action.RIGHT_CLICK_BLOCK))) { manager.onContainerRightClickAccessCheck(other); verify(other,never()).setCancelled(true); }
    }
    @Test void onlyOwnerMayCycleLockStateAndFactionStateShowsTutorial() {
        var barrel=block(0,Material.BARREL); var event=interact(barrel,Action.LEFT_CLICK_BLOCK); manager.onShiftLeftClickContainer(event); verify(event,never()).setCancelled(true);
        when(player.isSneaking()).thenReturn(true); var locked=lock(barrel,UUID.randomUUID()); manager.onShiftLeftClickContainer(event); verify(event).setCancelled(true); assertEquals(LockState.PRIVATE,locked.getLockState());
        locked.setOwner(player.getUniqueId()); locked.setLockState(LockState.GUILD); manager.onShiftLeftClickContainer(event); assertEquals(LockState.FACTION,locked.getLockState()); verify(storage).saveContainerData(locked); tutorial.verify(()->FactionLockTutorial.onLockState(player,LockState.FACTION));
        manager.onShiftLeftClickContainer(interact(barrel,Action.RIGHT_CLICK_BLOCK)); manager.onShiftLeftClickContainer(interact(null,Action.LEFT_CLICK_BLOCK)); manager.onShiftLeftClickContainer(interact(block(2,Material.STONE),Action.LEFT_CLICK_BLOCK));
        graves.when(()->GraveManager.get().isGrave(barrel)).thenReturn(true); manager.onShiftLeftClickContainer(event); assertEquals(LockState.FACTION,locked.getLockState());
    }
    @Test void breakingDeniedContainerPreservesMetadataAndAllowedBreakDeletesIt() {
        var barrel=block(0,Material.BARREL); var locked=lock(barrel,UUID.randomUUID()); var event=new BlockBreakEvent(barrel,player); manager.onBlockBreak(event); assertTrue(event.isCancelled()); verify(storage,never()).deleteContainerData(any());
        locked.setOwner(player.getUniqueId()); event=new BlockBreakEvent(barrel,player); manager.onBlockBreak(event); assertFalse(event.isCancelled()); verify(storage,never()).deleteContainerData(any()); barrel.setType(Material.AIR); MockBukkit.getMock().getScheduler().performTicks(5); verify(storage).deleteContainerData(barrel.getLocation());
        clearInvocations(storage); manager.onBlockBreak(new BlockBreakEvent(block(3,Material.STONE),player)); verifyNoInteractions(storage);
    }
    @Test void placingContainersClaimsThemAndPreventsMergingOtherOwnersChest() {
        var barrel=block(0,Material.BARREL); var event=mock(BlockPlaceEvent.class); when(event.getPlayer()).thenReturn(player); when(event.getBlockPlaced()).thenReturn(barrel); manager.onBlockPlace(event); assertEquals(player.getUniqueId(),data.get(barrel.getLocation()).getOwner());
        var chest=block(3,Material.CHEST); var neighbor=block(4,Material.CHEST); var owned=lock(neighbor,UUID.randomUUID()); when(event.getBlockPlaced()).thenReturn(chest); manager.onBlockPlace(event); verify(event).setCancelled(true); assertFalse(data.containsKey(chest.getLocation()));
        owned.setOwner(player.getUniqueId()); clearInvocations(event); manager.onBlockPlace(event); verify(event,never()).setCancelled(true); assertEquals(player.getUniqueId(),data.get(chest.getLocation()).getOwner());
        when(event.getBlockPlaced()).thenReturn(block(7,Material.STONE)); clearInvocations(storage); manager.onBlockPlace(event); verifyNoInteractions(storage);
    }
    @Test void hoppersOnlyTransferBetweenTheirOwnersContainers() {
        var hopper=mock(Hopper.class); when(hopper.getLocation()).thenReturn(new Location(world,1,64,0)); var initiator=mock(Inventory.class); when(initiator.getType()).thenReturn(InventoryType.HOPPER); when(initiator.getHolder(false)).thenReturn(hopper);
        var event=mock(InventoryMoveItemEvent.class); when(event.getInitiator()).thenReturn(initiator); when(event.getSource()).thenReturn(initiator); manager.onInventoryMoveItem(event); verify(event).setCancelled(true);
        var own=new ContainerData(hopper.getLocation(),player.getUniqueId()); data.put(hopper.getLocation(),own); clearInvocations(event); manager.onInventoryMoveItem(event); verify(event,never()).setCancelled(true);
        var destination=mock(Inventory.class); when(event.getDestination()).thenReturn(destination); manager.onInventoryMoveItem(event); verify(event,never()).setCancelled(true);
        var target=mock(Container.class); when(target.getLocation()).thenReturn(new Location(world,2,64,0)); when(destination.getHolder(false)).thenReturn(target); manager.onInventoryMoveItem(event); verify(event).setCancelled(true);
        data.put(target.getLocation(),new ContainerData(target.getLocation(),player.getUniqueId())); clearInvocations(event); manager.onInventoryMoveItem(event); verify(event,never()).setCancelled(true);
        when(event.getSource()).thenReturn(destination); when(event.getDestination()).thenReturn(initiator); manager.onInventoryMoveItem(event); verify(event,never()).setCancelled(true);
        when(initiator.getType()).thenReturn(InventoryType.CHEST); manager.onInventoryMoveItem(event); when(initiator.getType()).thenReturn(InventoryType.HOPPER); when(initiator.getHolder(false)).thenReturn(null); manager.onInventoryMoveItem(event); verify(event,never()).setCancelled(true);
    }

    record DoubleFixture(Block block, Chest left, Chest right, DoubleChest holder, DoubleChestInventory inventory) {}
    @Test void hopperCannotExtractFromAnotherOwnersContainer() {
        var hopper=mock(Hopper.class);
        Location hopperLocation=new Location(world,1,64,0);
        when(hopper.getLocation()).thenReturn(hopperLocation);
        var hopperInventory=mock(Inventory.class);
        when(hopperInventory.getType()).thenReturn(InventoryType.HOPPER);
        when(hopperInventory.getHolder(false)).thenReturn(hopper);
        data.put(hopperLocation,new ContainerData(hopperLocation,player.getUniqueId()));
        var barrel=block(2,Material.BARREL);
        var source=mock(Inventory.class);
        Container sourceContainer=(Container)barrel.getState();
        when(source.getHolder(false)).thenReturn(sourceContainer);
        var foreign=lock(barrel,UUID.randomUUID());
        var denied=new InventoryMoveItemEvent(source,new ItemStack(Material.DIAMOND),hopperInventory,false);

        manager.onInventoryMoveItem(denied);

        assertTrue(denied.isCancelled());
        foreign.setOwner(player.getUniqueId());
        var allowed=new InventoryMoveItemEvent(source,new ItemStack(Material.DIAMOND),hopperInventory,false);
        manager.onInventoryMoveItem(allowed);
        assertFalse(allowed.isCancelled());
        verify(storage,never()).saveContainerData(any());
    }

    @Test void clickingPluginMenuDoesNotInspectLocksOrAlertStaff() {
        var inventory=Bukkit.createInventory(null,9,"Menu");
        var event=mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getCurrentItem()).thenReturn(new ItemStack(Material.DIAMOND));

        manager.onInventoryClick(event);

        verifyNoInteractions(storage);
        verify(player,never()).sendMessage(anyString());
        verify(event,never()).setCancelled(true);
    }

    @Test void accessToLeftHalfDoesNotAllowOpeningForeignRightHalfButStaffCanBypassSilently() {
        var chest=doubleChest();
        var left=lock(chest.block(),player.getUniqueId());
        var right=lock(chest.right().getBlock(),UUID.randomUUID());
        UUID rightOwner=right.getOwner();
        var denied=interact(chest.block(),Action.RIGHT_CLICK_BLOCK);

        manager.onContainerRightClickAccessCheck(denied);

        verify(denied).setCancelled(true);
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        var allowed=interact(chest.block(),Action.RIGHT_CLICK_BLOCK);
        manager.onContainerRightClickAccessCheck(allowed);
        verify(allowed,never()).setCancelled(true);
        verify(player,never()).sendMessage("§cBypassing lock due to staff");
        assertEquals(player.getUniqueId(),left.getOwner());
        assertEquals(rightOwner,right.getOwner());
        verify(storage,never()).saveContainerData(any());
    }

    DoubleFixture doubleChest() {
        var block=mock(Block.class); var rightBlock=mock(Block.class); var left=mock(Chest.class); var right=mock(Chest.class);
        var inventory=mock(DoubleChestInventory.class); var holder=new DoubleChest(inventory);
        var leftInventory=mock(Inventory.class); var rightInventory=mock(Inventory.class);
        when(inventory.getLeftSide()).thenReturn(leftInventory); when(inventory.getRightSide()).thenReturn(rightInventory);
        when(leftInventory.getHolder()).thenReturn(left); when(rightInventory.getHolder()).thenReturn(right);
        when(leftInventory.getHolder(false)).thenReturn(left); when(rightInventory.getHolder(false)).thenReturn(right);
        World mapped=mock(World.class); when(mapped.getName()).thenReturn("containers-double");
        Location leftLoc=new Location(mapped,10,64,0), rightLoc=new Location(mapped,11,64,0);
        when(mapped.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            if(y==64&&z==0&&x==10)return block;if(y==64&&z==0&&x==11)return rightBlock;
            return world.getBlockAt(x,y,z);
        });
        when(mapped.getBlockAt(any(Location.class))).thenAnswer(call->{Location at=call.getArgument(0);return mapped.getBlockAt(at.getBlockX(),at.getBlockY(),at.getBlockZ());});
        when(block.getLocation()).thenReturn(leftLoc); when(block.getState()).thenReturn(left); when(block.getType()).thenReturn(Material.CHEST);
        when(rightBlock.getLocation()).thenReturn(rightLoc); when(rightBlock.getState()).thenReturn(right); when(rightBlock.getType()).thenReturn(Material.CHEST); when(left.getBlock()).thenReturn(block); when(right.getBlock()).thenReturn(rightBlock);
        when(left.getLocation()).thenReturn(leftLoc); when(right.getLocation()).thenReturn(rightLoc); when(left.getInventory()).thenReturn(inventory); when(right.getInventory()).thenReturn(inventory); when(inventory.getSize()).thenReturn(54);
        when(inventory.getHolder()).thenReturn(holder); when(inventory.getHolder(false)).thenReturn(holder);
        return new DoubleFixture(block,left,right,holder,inventory);
    }
    @Test void openingDoubleChestClaimsBothHalvesAndPreservesExistingOwnerAndLock() {
        var chest=doubleChest(); var event=open(chest.inventory()); manager.onInventoryOpen(event);
        assertEquals(player.getUniqueId(),data.get(chest.left().getLocation()).getOwner()); assertEquals(player.getUniqueId(),data.get(chest.right().getLocation()).getOwner());
        data.clear(); var right=new ContainerData(chest.right().getLocation(),player.getUniqueId()); right.setLockState(LockState.PRIVATE); data.put(right.getLocation(),right);
        manager.onInventoryOpen(event); assertEquals(player.getUniqueId(),data.get(chest.left().getLocation()).getOwner()); assertEquals(LockState.PRIVATE,data.get(chest.left().getLocation()).getLockState());
        var foreign=new ContainerData(chest.left().getLocation(),UUID.randomUUID()); foreign.setLockState(LockState.PRIVATE); data.put(foreign.getLocation(),foreign); clearInvocations(storage); manager.onInventoryOpen(event); verify(event).setCancelled(true); verify(storage,never()).saveContainerData(any());
        when(player.hasPermission("thievery.admin")).thenReturn(true); clearInvocations(event); manager.onInventoryOpen(event); verify(event,never()).setCancelled(true); assertEquals(foreign.getOwner(),right.getOwner()); verify(player).sendMessage("§cBypassing lock due to staff");
    }
    @Test void doubleChestAccessBreakAndLockChangesRespectBothHalves() {
        var chest=doubleChest(); var left=new ContainerData(chest.left().getLocation(),UUID.randomUUID()); left.setLockState(LockState.PRIVATE); data.put(left.getLocation(),left);
        var right=new ContainerData(chest.right().getLocation(),player.getUniqueId()); right.setLockState(LockState.PRIVATE); data.put(right.getLocation(),right);
        var interact=interact(chest.block(),Action.RIGHT_CLICK_BLOCK); manager.onContainerRightClickAccessCheck(interact); verify(interact).setCancelled(true);
        var breaking=new BlockBreakEvent(chest.block(),player); manager.onBlockBreak(breaking); assertTrue(breaking.isCancelled()); verify(storage,never()).deleteContainerData(any());
        UUID originalLeftOwner=left.getOwner();
        when(player.isSneaking()).thenReturn(true); var toggle=interact(chest.block(),Action.LEFT_CLICK_BLOCK); manager.onShiftLeftClickContainer(toggle); assertEquals(LockState.GUILD,left.getLockState()); assertEquals(left.getLockState(),right.getLockState()); assertEquals(originalLeftOwner,left.getOwner()); assertEquals(player.getUniqueId(),right.getOwner());
        left.setOwner(UUID.randomUUID()); right.setOwner(UUID.randomUUID()); clearInvocations(storage); manager.onShiftLeftClickContainer(toggle); verify(storage,never()).saveContainerData(any());
    }
    @Test void owningOnlyLeftDoubleChestHalfCyclesBothHalvesWithoutTransferringOwnership() {
        var chest = doubleChest();
        ContainerData left = lock(chest.block(), player.getUniqueId());
        UUID otherOwner = UUID.randomUUID();
        ContainerData right = lock(chest.right().getBlock(), otherOwner);
        when(player.isSneaking()).thenReturn(true);

        manager.onShiftLeftClickContainer(interact(chest.block(), Action.LEFT_CLICK_BLOCK));

        assertEquals(LockState.GUILD, left.getLockState());
        assertEquals(LockState.GUILD, right.getLockState());
        assertEquals(player.getUniqueId(), left.getOwner());
        assertEquals(otherOwner, right.getOwner());
        verify(storage).saveContainerData(left);
        verify(storage).saveContainerData(right);
    }

    @Test void singleChestBreakIsGuardedByItsOwnLock() {
        Block chest = block(0, Material.CHEST);
        ContainerData locked = lock(chest, UUID.randomUUID());
        BlockBreakEvent denied = new BlockBreakEvent(chest, player);

        manager.onBlockBreak(denied);

        assertTrue(denied.isCancelled());
        verify(player).sendMessage(contains("do not have access to break"));
        locked.setOwner(player.getUniqueId());
        BlockBreakEvent allowed = new BlockBreakEvent(chest, player);
        manager.onBlockBreak(allowed);
        assertFalse(allowed.isCancelled());
        chest.setType(Material.AIR);
        MockBukkit.getMock().getScheduler().performTicks(2);
        verify(storage).deleteContainerData(chest.getLocation());
    }

    @Test void hopperDoubleChestOwnershipFallsBackToRightWhenLeftUnowned() {
        var chest=doubleChest(); var hopper=mock(Hopper.class); when(hopper.getLocation()).thenReturn(new Location(world,12,64,0)); var initiator=mock(Inventory.class); when(initiator.getType()).thenReturn(InventoryType.HOPPER); when(initiator.getHolder(false)).thenReturn(hopper);
        data.put(hopper.getLocation(),new ContainerData(hopper.getLocation(),player.getUniqueId()));
        var event=mock(InventoryMoveItemEvent.class); when(event.getInitiator()).thenReturn(initiator); when(event.getSource()).thenReturn(initiator); when(event.getDestination()).thenReturn(chest.inventory());
        manager.onInventoryMoveItem(event); verify(event).setCancelled(true); data.put(chest.right().getLocation(),new ContainerData(chest.right().getLocation(),player.getUniqueId())); clearInvocations(event); manager.onInventoryMoveItem(event); verify(event,never()).setCancelled(true);
        data.put(chest.left().getLocation(),new ContainerData(chest.left().getLocation(),UUID.randomUUID())); manager.onInventoryMoveItem(event); verify(event).setCancelled(true);
    }
    @Test void takingFromForeignContainerAlertsOnlyOptedInStaffAndThrottlesRepeats() {
        var barrel=block(0,Material.BARREL); var inventory=((Container)barrel.getState()).getInventory(); var lock=lock(barrel,UUID.randomUUID());
        var staff=mock(Player.class); when(staff.getUniqueId()).thenReturn(UUID.randomUUID()); when(staff.hasPermission("thievery.admin")).thenReturn(true);
        var disabled=mock(Player.class); when(disabled.getUniqueId()).thenReturn(UUID.randomUUID()); when(disabled.hasPermission("thievery.admin")).thenReturn(true); var ordinary=mock(Player.class);
        manager.enableFeedback(staff);
        var event=mock(InventoryClickEvent.class); when(event.getWhoClicked()).thenReturn(player); when(event.getInventory()).thenReturn(inventory); when(event.getClick()).thenReturn(ClickType.LEFT); when(event.getCurrentItem()).thenReturn(new ItemStack(Material.DIAMOND));
        try(var bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(staff,disabled,ordinary)); manager.onInventoryClick(event); manager.onInventoryClick(event);
            var message=ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class); verify(staff).sendMessage(message.capture()); assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message.getValue()).contains("thief is taking from a chest")); verify(disabled,never()).sendMessage(any(net.kyori.adventure.text.Component.class)); verify(ordinary,never()).sendMessage(any(net.kyori.adventure.text.Component.class));
            manager.onBlockBreak(new BlockBreakEvent(barrel,player)); verify(staff,times(2)).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
        when(event.getClick()).thenReturn(ClickType.RIGHT); manager.onInventoryClick(event); when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT); when(event.getCurrentItem()).thenReturn(null); manager.onInventoryClick(event); when(event.getCurrentItem()).thenReturn(new ItemStack(Material.AIR)); manager.onInventoryClick(event);
        when(event.getCurrentItem()).thenReturn(new ItemStack(Material.DIAMOND)); lock.setOwner(null); manager.onInventoryClick(event); lock.setOwner(player.getUniqueId()); manager.onInventoryClick(event); lock.setOwner(UUID.randomUUID()); when(player.hasPermission("thievery.admin")).thenReturn(true); manager.onInventoryClick(event);
        when(event.getWhoClicked()).thenReturn(mock(HumanEntity.class)); manager.onInventoryClick(event);
    }
    @Test void cancelledContainerBreakPreservesOwnershipMetadata() {
        var barrel=block(0,Material.BARREL); lock(barrel,player.getUniqueId());
        MockBukkit.getMock().getPluginManager().registerEvents(manager,MockBukkit.createMockPlugin());
        var event=new BlockBreakEvent(barrel,player); event.setCancelled(true);
        MockBukkit.getMock().getPluginManager().callEvent(event);
        MockBukkit.getMock().getScheduler().performTicks(5);
        verify(storage,never()).deleteContainerData(barrel.getLocation());
    }

    @Test void owningOnlyBrokenDoubleChestHalfStillRequiresOtherHalfAccessUnlessStaffBypasses() {
        var chest = doubleChest();
        ContainerData left = lock(chest.block(), player.getUniqueId());
        UUID otherOwner = UUID.randomUUID();
        ContainerData right = lock(chest.right().getBlock(), otherOwner);
        BlockBreakEvent denied = new BlockBreakEvent(chest.block(), player);

        manager.onBlockBreak(denied);

        assertTrue(denied.isCancelled());
        verify(storage, never()).deleteContainerData(any());
        verify(player).sendMessage(contains("do not have access to break"));
        when(player.hasPermission("thievery.admin")).thenReturn(true);
        BlockBreakEvent allowed = new BlockBreakEvent(chest.block(), player);
        manager.onBlockBreak(allowed);
        assertFalse(allowed.isCancelled());
        verify(player).sendMessage("§cBypassing lock due to staff");
        when(chest.block().getState()).thenReturn(mock(BlockState.class));
        when(chest.block().getType()).thenReturn(Material.AIR);
        MockBukkit.getMock().getScheduler().performTicks(2);

        verify(storage).deleteContainerData(left.getLocation());
        verify(storage, never()).deleteContainerData(right.getLocation());
        assertEquals(otherOwner, right.getOwner());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void hopperRejectsCapturedDoubleChestIfEarlierListenerRemovedEitherHalf(boolean removeLeft) {
        Block left = block(0, Material.CHEST);
        Block right = block(1, Material.CHEST);
        Block hopperBlock = block(3, Material.HOPPER);
        lock(left, player.getUniqueId());
        lock(right, player.getUniqueId());
        lock(hopperBlock, player.getUniqueId());

        // MockBukkit has no joined-chest inventory. These boundary inventories
        // reproduce Paper's holder lookup from the current world block state.
        DoubleChestInventory captured = mock(DoubleChestInventory.class);
        Inventory leftInventory = currentBlockInventory(left);
        Inventory rightInventory = currentBlockInventory(right);
        when(captured.getLeftSide()).thenReturn(leftInventory);
        when(captured.getRightSide()).thenReturn(rightInventory);
        DoubleChest holder = new DoubleChest(captured);
        when(captured.getHolder(false)).thenReturn(holder);
        Inventory hopperInventory = mock(Inventory.class);
        when(hopperInventory.getType()).thenReturn(InventoryType.HOPPER);
        Hopper hopper = (Hopper) hopperBlock.getState();
        when(hopperInventory.getHolder(false)).thenReturn(hopper);
        InventoryMoveItemEvent event = new InventoryMoveItemEvent(captured,
                new ItemStack(Material.DIAMOND), hopperInventory, false);

        (removeLeft ? left : right).setType(Material.AIR);
        manager.onInventoryMoveItem(event);

        assertTrue(event.isCancelled());
        verify(storage, never()).saveContainerData(any());
        verify(storage, never()).deleteContainerData(any());
        assertEquals(Material.CHEST, (removeLeft ? right : left).getType());
    }

    private Inventory currentBlockInventory(Block block) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder(false)).thenAnswer(call ->
                block.getState() instanceof InventoryHolder holder ? holder : null);
        return inventory;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"-1,false", "0,false", "0,true"})
    void lockpickingStartsHiddenSessionRecordsNearbyAccessAndRejectsConcurrentAttempt(int nearbyRadius, boolean debugOwnChest) {
        int radius=net.tfminecraft.thievery.cache.Cache.radius; boolean own=net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest, online=net.tfminecraft.thievery.cache.Cache.requireOwnerOnline; var traits=net.tfminecraft.thievery.cache.Cache.traits;
        net.tfminecraft.thievery.cache.Cache.radius=nearbyRadius; net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest=debugOwnChest; net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=false; net.tfminecraft.thievery.cache.Cache.traits=List.of();
        var barrel=lockpickBlock(); var locked=lock(barrel,debugOwnChest ? player.getUniqueId() : UUID.randomUUID()); var pick=mock(net.tfminecraft.thievery.player.LockpickDefinition.class); when(pick.getCapacity()).thenReturn(10); when(pick.getStrength()).thenReturn(.5);
        tools.when(()->ToolResolver.isLockpick(any())).thenReturn(true); tools.when(()->ToolResolver.resolveLockpick(any())).thenReturn(pick);
        var stealManager=mock(net.tfminecraft.thievery.steal.StealManager.class); var gui=mock(Inventory.class);
        try(var clues=mockStatic(net.tfminecraft.thievery.clue.ClueChecker.class); var cooldown=mockStatic(net.tfminecraft.thievery.player.GuildAccessCooldown.class); var risk=mockStatic(net.tfminecraft.thievery.player.RiskCalculator.class); var targets=mockStatic(net.tfminecraft.thievery.player.TargetKeyResolver.class); var evil=mockStatic(net.tfminecraft.thievery.utils.EvilRpPlays.class); var sessions=mockStatic(net.tfminecraft.thievery.steal.StealManager.class); var menus=mockStatic(net.tfminecraft.thievery.steal.StealGui.class); var references=mockConstruction(net.tfminecraft.thievery.steal.ChestStealReference.class,(ref,context)->when(ref.buildTitle(any())).thenReturn("Search"))) {
            clues.when(()->net.tfminecraft.thievery.clue.ClueChecker.hasEnoughClues(player)).thenReturn(true); cooldown.when(net.tfminecraft.thievery.player.GuildAccessCooldown::today).thenReturn("2026-09-26"); targets.when(()->net.tfminecraft.thievery.player.TargetKeyResolver.resolve(any())).thenReturn("target");
            sessions.when(net.tfminecraft.thievery.steal.StealManager::getInstance).thenReturn(stealManager); menus.when(()->net.tfminecraft.thievery.steal.StealGui.buildHiddenGui(any(),any(),anyString())).thenReturn(gui);
            var event=interact(barrel,Action.RIGHT_CLICK_BLOCK); manager.onRightClickChest(event); verify(event,atLeastOnce()).setCancelled(true); assertEquals(1,references.constructed().size()); verify(stealManager).openSession(player,references.constructed().getFirst(),gui); assertEquals("2026-09-26",locked.getLastAccess(player.getUniqueId())); evil.verify(()->net.tfminecraft.thievery.utils.EvilRpPlays.record(player));
            verify(storage,times(nearbyRadius < 0 ? 1 : 2)).saveContainerData(locked);
            manager.onRightClickChest(event); verify(player).sendMessage("§4Someone is already lockpicking this container!"); verify(stealManager,times(1)).openSession(any(),any(),any());
            Player other=mock(Player.class,RETURNS_DEEP_STUBS); when(other.getUniqueId()).thenReturn(UUID.randomUUID()); when(other.getName()).thenReturn("other thief"); when(other.getInventory().getItemInMainHand()).thenReturn(new ItemStack(Material.STICK));
            var second=lockpickBlock(4);var secondData=lock(second,UUID.randomUUID());var secondEvent=interact(second,Action.RIGHT_CLICK_BLOCK);when(secondEvent.getPlayer()).thenReturn(other);
            clues.when(()->net.tfminecraft.thievery.clue.ClueChecker.hasEnoughClues(other)).thenReturn(true);
            manager.onRightClickChest(secondEvent);
            assertEquals(2,references.constructed().size());verify(stealManager).openSession(other,references.constructed().get(1),gui);
            assertEquals("2026-09-26",secondData.getLastAccess(other.getUniqueId()));
            assertEquals("2026-09-26",locked.getLastAccess(player.getUniqueId()));
            manager.onRightClickChest(event);verify(player,times(2)).sendMessage("§4Someone is already lockpicking this container!");verify(stealManager,times(2)).openSession(any(),any(),any());
        } finally { net.tfminecraft.thievery.cache.Cache.radius=radius; net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest=own; net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=online; net.tfminecraft.thievery.cache.Cache.traits=traits; }
    }
    @Test void lockpickingRejectsOwnedTargetsMissingCluesAndCooldownBeforeOpeningMenu() {
        boolean online=net.tfminecraft.thievery.cache.Cache.requireOwnerOnline; var traits=net.tfminecraft.thievery.cache.Cache.traits; net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=false; net.tfminecraft.thievery.cache.Cache.traits=List.of();
        var barrel=lockpickBlock(); var locked=lock(barrel,player.getUniqueId()); var event=interact(barrel,Action.RIGHT_CLICK_BLOCK); tools.when(()->ToolResolver.isLockpick(any())).thenReturn(true);
        try(var clues=mockStatic(net.tfminecraft.thievery.clue.ClueChecker.class); var cooldown=mockStatic(net.tfminecraft.thievery.player.GuildAccessCooldown.class)) {
            manager.onRightClickChest(event); verify(player).sendMessage("§cYou already have access to this container."); clues.verifyNoInteractions();
            locked.setOwner(UUID.randomUUID()); manager.onRightClickChest(event); clues.verify(()->net.tfminecraft.thievery.clue.ClueChecker.sendInsufficientCluesMessage(player));
            clues.when(()->net.tfminecraft.thievery.clue.ClueChecker.hasEnoughClues(player)).thenReturn(true); manager.onRightClickChest(event); verify(storage,never()).saveContainerData(any());
            var pick=mock(net.tfminecraft.thievery.player.LockpickDefinition.class); tools.when(()->ToolResolver.resolveLockpick(any())).thenReturn(pick); cooldown.when(()->net.tfminecraft.thievery.player.GuildAccessCooldown.isOnCooldown(anyMap(),eq(player),anyInt())).thenReturn(true); cooldown.when(()->net.tfminecraft.thievery.player.GuildAccessCooldown.formatRemaining(anyLong())).thenReturn("one day");
            manager.onRightClickChest(event); verify(player).sendMessage(contains("one day before attempting")); verify(storage,never()).saveContainerData(any());
        } finally { net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=online; net.tfminecraft.thievery.cache.Cache.traits=traits; }
    }
    @Test void lockpickGuardIgnoresOtherActionsGravesNoncontainersAndExcludedMaterials() {
        var barrel=block(0,Material.BARREL); var event=interact(barrel,Action.RIGHT_CLICK_BLOCK); manager.onRightClickChest(event); verify(event,never()).setCancelled(true);
        manager.onRightClickChest(interact(null,Action.RIGHT_CLICK_BLOCK)); manager.onRightClickChest(interact(barrel,Action.LEFT_CLICK_BLOCK)); manager.onRightClickChest(interact(block(4,Material.STONE),Action.RIGHT_CLICK_BLOCK));
        tools.when(()->ToolResolver.isLockpick(any())).thenReturn(true); graves.when(()->GraveManager.get().isGrave(barrel)).thenReturn(true); manager.onRightClickChest(event); verify(event,never()).setCancelled(true);
        graves.when(()->GraveManager.get().isGrave(barrel)).thenReturn(false); var saved=net.tfminecraft.thievery.cache.Parameters.excludedContainerMaterials;
        try { net.tfminecraft.thievery.cache.Parameters.excludedContainerMaterials=Set.of(Material.BARREL); manager.onRightClickChest(event); verify(event,never()).setCancelled(true); } finally { net.tfminecraft.thievery.cache.Parameters.excludedContainerMaterials=saved; }
    }
    Block lockpickBlock() { return lockpickBlock(0); }
    Block lockpickBlock(int x) {
        var real=block(x,Material.BARREL); var block=mock(Block.class); var container=mock(Container.class); var inventory=mock(Inventory.class);
        when(block.getLocation()).thenReturn(real.getLocation()); when(block.getType()).thenReturn(Material.BARREL); when(block.getState()).thenReturn(container);
        when(container.getInventory()).thenReturn(inventory); when(container.getLocation()).thenReturn(real.getLocation()); when(container.getBlock()).thenReturn(block);
        when(inventory.getHolder(false)).thenReturn(container); when(inventory.getSize()).thenReturn(27);
        return block;
    }
    @Test void laterProtectionCancellationPreservesContainerMetadata() {
        var barrel=block(0,Material.BARREL); lock(barrel,player.getUniqueId());
        var registration=MockBukkit.createMockPlugin();
        MockBukkit.getMock().getPluginManager().registerEvents(manager,registration);
        MockBukkit.getMock().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.HIGH)
            public void protect(BlockBreakEvent event) { event.setCancelled(true); }
        },registration);
        var event=new BlockBreakEvent(barrel,player);
        MockBukkit.getMock().getPluginManager().callEvent(event);
        MockBukkit.getMock().getScheduler().performTicks(5);
        assertTrue(event.isCancelled()); assertEquals(Material.BARREL,barrel.getType());
        verify(storage,never()).deleteContainerData(barrel.getLocation());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=Material.class,names={"BARREL","CHEST"})
    void allowedBreakPreservesMetadataIfAContainerRemainsAtTheLocation(Material replacement) {
        var barrel=block(0,Material.BARREL);lock(barrel,player.getUniqueId());
        var event=new BlockBreakEvent(barrel,player);manager.onBlockBreak(event);assertFalse(event.isCancelled());
        if(replacement!=Material.BARREL) {barrel.setType(Material.AIR);barrel.setType(replacement);lock(barrel,UUID.randomUUID());}
        var retained=data.get(barrel.getLocation());MockBukkit.getMock().getScheduler().performTicks(5);
        assertEquals(replacement,barrel.getType());assertSame(retained,data.get(barrel.getLocation()));verify(storage,never()).deleteContainerData(any());
    }
    @Test void placingBesideAnExistingDoubleChestDoesNotTreatItAsAMerge() {
        var chest=doubleChest();lock(chest.block(),UUID.randomUUID());lock(chest.right().getBlock(),UUID.randomUUID());
        Block placed=mock(Block.class);Chest placedState=mock(Chest.class);
        when(placed.getState()).thenReturn(placedState);when(placed.getType()).thenReturn(Material.CHEST);when(placed.getLocation()).thenReturn(new Location(world,10,64,1));
        when(placed.getRelative(any(org.bukkit.block.BlockFace.class))).thenReturn(block(20,Material.AIR));when(placed.getRelative(org.bukkit.block.BlockFace.NORTH)).thenReturn(chest.block());
        var event=mock(BlockPlaceEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getBlockPlaced()).thenReturn(placed);
        manager.onBlockPlace(event);verify(event,never()).setCancelled(true);assertEquals(player.getUniqueId(),data.get(placed.getLocation()).getOwner());
        assertNotEquals(player.getUniqueId(),data.get(chest.left().getLocation()).getOwner());assertNotEquals(player.getUniqueId(),data.get(chest.right().getLocation()).getOwner());
    }
    @Test void successfulDoubleChestBreakDeletesOnlyRemovedHalfAfterBlockRemoval() {
        var chest=doubleChest();
        data.put(chest.left().getLocation(),new ContainerData(chest.left().getLocation(),player.getUniqueId()));
        data.put(chest.right().getLocation(),new ContainerData(chest.right().getLocation(),player.getUniqueId()));
        var event=new BlockBreakEvent(chest.block(),player); manager.onBlockBreak(event);
        assertFalse(event.isCancelled()); verify(storage,never()).deleteContainerData(any());
        when(chest.block().getState()).thenReturn(mock(BlockState.class)); when(chest.block().getType()).thenReturn(Material.AIR);
        MockBukkit.getMock().getScheduler().performTicks(5);
        verify(storage).deleteContainerData(chest.left().getLocation());
        verify(storage,never()).deleteContainerData(chest.right().getLocation());
    }

    @Test void lockpickingRequiresConfiguredCharacterTraitsBeforeCheckingClues() {
        var savedTraits=net.tfminecraft.thievery.cache.Cache.traits;
        boolean online=net.tfminecraft.thievery.cache.Cache.requireOwnerOnline;
        net.tfminecraft.thievery.cache.Cache.traits=List.of("thief"); net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=false;
        var barrel=lockpickBlock();lock(barrel,UUID.randomUUID());var event=interact(barrel,Action.RIGHT_CLICK_BLOCK);
        tools.when(()->ToolResolver.isLockpick(any())).thenReturn(true);
        var profile=mock(net.tfminecraft.rpcharacters.objects.PlayerData.class);var character=mock(net.tfminecraft.rpcharacters.objects.RPCharacter.class);
        var other=mock(net.tfminecraft.rpcharacters.objects.trait.Trait.class);when(other.getId()).thenReturn("scholar");
        var required=mock(net.tfminecraft.rpcharacters.objects.trait.Trait.class);when(required.getId()).thenReturn("thief");
        try(var roleplay=mockStatic(net.tfminecraft.rpcharacters.managers.PlayerManager.class);var clues=mockStatic(net.tfminecraft.thievery.clue.ClueChecker.class)) {
            roleplay.when(()->net.tfminecraft.rpcharacters.managers.PlayerManager.get(player)).thenReturn(profile);
            manager.onRightClickChest(event);verify(event).setCancelled(true);clues.verifyNoInteractions();
            when(profile.hasActiveCharacter()).thenReturn(true);when(profile.getActiveCharacter()).thenReturn(character);when(character.getTraits()).thenReturn(List.of(other));
            manager.onRightClickChest(event);verify(player).sendMessage("§cYou lack the needed character trait(s) to lockpick!");clues.verifyNoInteractions();
            when(character.getTraits()).thenReturn(List.of(other,required));manager.onRightClickChest(event);
            clues.verify(()->net.tfminecraft.thievery.clue.ClueChecker.sendInsufficientCluesMessage(player));
            verify(storage,never()).saveContainerData(any());
        } finally {net.tfminecraft.thievery.cache.Cache.traits=savedTraits;net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=online;}
    }
    @Test void doubleChestAlreadyAccessibleCheckRequiresAccessToBothHalves() {
        var savedTraits=net.tfminecraft.thievery.cache.Cache.traits;boolean online=net.tfminecraft.thievery.cache.Cache.requireOwnerOnline,own=net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest;
        net.tfminecraft.thievery.cache.Cache.traits=List.of();net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=false;net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest=false;
        var chest=doubleChest();var left=lock(chest.block(),player.getUniqueId());var right=lock(chest.right().getBlock(),player.getUniqueId());var event=interact(chest.block(),Action.RIGHT_CLICK_BLOCK);
        tools.when(()->ToolResolver.isLockpick(any())).thenReturn(true);
        try(var clues=mockStatic(net.tfminecraft.thievery.clue.ClueChecker.class)) {
            manager.onRightClickChest(event);verify(player).sendMessage("§cYou already have access to this container.");clues.verifyNoInteractions();
            right.setOwner(UUID.randomUUID());manager.onRightClickChest(event);
            left.setOwner(UUID.randomUUID());right.setOwner(player.getUniqueId());manager.onRightClickChest(event);
            clues.verify(()->net.tfminecraft.thievery.clue.ClueChecker.sendInsufficientCluesMessage(player),times(2));
            verify(storage,never()).saveContainerData(any());
        } finally {net.tfminecraft.thievery.cache.Cache.traits=savedTraits;net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=online;net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest=own;}
    }
    @Test void absentGuildMembersDenyPickingUnlessAnActiveWindowAllowsAWarning() {
        var savedTraits=net.tfminecraft.thievery.cache.Cache.traits;boolean online=net.tfminecraft.thievery.cache.Cache.requireOwnerOnline;
        net.tfminecraft.thievery.cache.Cache.traits=List.of();net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=true;
        var barrel=lockpickBlock();UUID ownerId=UUID.randomUUID();lock(barrel,ownerId);var event=interact(barrel,Action.RIGHT_CLICK_BLOCK);
        tools.when(()->ToolResolver.isLockpick(any())).thenReturn(true);
        var owner=mock(OfflinePlayer.class);when(owner.getName()).thenReturn("owner");var guild=mock(net.tfminecraft.simplefactions.guild.Guild.class);when(guild.getName()).thenReturn("Night Watch");when(guild.getMembers()).thenReturn(List.of("OfflineMember"));
        try(var bukkit=mockStatic(Bukkit.class,CALLS_REAL_METHODS);var factions=mockStatic(net.tfminecraft.simplefactions.managers.FactionManager.class);var targets=mockStatic(net.tfminecraft.thievery.player.TargetKeyResolver.class);var window=mockStatic(net.tfminecraft.thievery.cache.LockpickTargetCache.class);var clues=mockStatic(net.tfminecraft.thievery.clue.ClueChecker.class)) {
            bukkit.when(()->Bukkit.getOfflinePlayer(ownerId)).thenReturn(owner);factions.when(()->net.tfminecraft.simplefactions.managers.FactionManager.getGuildByMember("owner")).thenReturn(guild);
            targets.when(()->net.tfminecraft.thievery.player.TargetKeyResolver.resolve(ownerId)).thenReturn("guild:night-watch");
            manager.onRightClickChest(event);verify(player).sendMessage("§cCannot lockpick - Night Watch has no members online.");clues.verifyNoInteractions();
            window.when(()->net.tfminecraft.thievery.cache.LockpickTargetCache.isActive("guild:night-watch")).thenReturn(true);
            window.when(()->net.tfminecraft.thievery.cache.LockpickTargetCache.getRemainingMs("guild:night-watch")).thenReturn(65000L);
            manager.onRightClickChest(event);verify(player).sendMessage("§eNight Watch has no members online - 1m 5s remaining on lockpick window.");
            clues.verify(()->net.tfminecraft.thievery.clue.ClueChecker.sendInsufficientCluesMessage(player));verify(storage,never()).saveContainerData(any());
        } finally {net.tfminecraft.thievery.cache.Cache.traits=savedTraits;net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=online;}
    }
    @Test void startingDoubleChestSessionRecordsBothHalvesAndPingsEachHalfOnlyOnce() {
        int radius=net.tfminecraft.thievery.cache.Cache.radius;var traits=net.tfminecraft.thievery.cache.Cache.traits;
        boolean online=net.tfminecraft.thievery.cache.Cache.requireOwnerOnline,own=net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest;
        net.tfminecraft.thievery.cache.Cache.radius=1;net.tfminecraft.thievery.cache.Cache.traits=List.of();net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=false;net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest=false;
        var chest=doubleChest();UUID owner=UUID.randomUUID();var left=lock(chest.block(),owner);var right=lock(chest.right().getBlock(),owner);
        var pick=mock(net.tfminecraft.thievery.player.LockpickDefinition.class);when(pick.getCapacity()).thenReturn(10);when(pick.getStrength()).thenReturn(.5);
        tools.when(()->ToolResolver.isLockpick(any())).thenReturn(true);tools.when(()->ToolResolver.resolveLockpick(any())).thenReturn(pick);
        var stealManager=mock(net.tfminecraft.thievery.steal.StealManager.class);var menu=mock(Inventory.class);List<ChestLockpickSession> started=new ArrayList<>();List<Runnable> closers=new ArrayList<>();
        try(var clues=mockStatic(net.tfminecraft.thievery.clue.ClueChecker.class);var cooldown=mockStatic(net.tfminecraft.thievery.player.GuildAccessCooldown.class);var risk=mockStatic(net.tfminecraft.thievery.player.RiskCalculator.class);var targets=mockStatic(net.tfminecraft.thievery.player.TargetKeyResolver.class);var evil=mockStatic(net.tfminecraft.thievery.utils.EvilRpPlays.class);var sessions=mockStatic(net.tfminecraft.thievery.steal.StealManager.class);var menus=mockStatic(net.tfminecraft.thievery.steal.StealGui.class);var references=mockConstruction(net.tfminecraft.thievery.steal.ChestStealReference.class,(reference,context)->{started.add((ChestLockpickSession)context.arguments().get(0));closers.add((Runnable)context.arguments().get(1));when(reference.buildTitle(any())).thenReturn("Double chest");})) {
            clues.when(()->net.tfminecraft.thievery.clue.ClueChecker.hasEnoughClues(player)).thenReturn(true);cooldown.when(net.tfminecraft.thievery.player.GuildAccessCooldown::today).thenReturn("2026-09-26");targets.when(()->net.tfminecraft.thievery.player.TargetKeyResolver.resolve(owner)).thenReturn("owner-target");
            sessions.when(net.tfminecraft.thievery.steal.StealManager::getInstance).thenReturn(stealManager);menus.when(()->net.tfminecraft.thievery.steal.StealGui.buildHiddenGui(any(),any(),anyString())).thenReturn(menu);
            manager.onRightClickChest(interact(chest.block(),Action.RIGHT_CLICK_BLOCK));
            assertEquals(1,started.size());assertSame(chest.block(),started.getFirst().getChestBlock());assertEquals(54,started.getFirst().getLayout().getLogicalSlotCount());assertEquals("owner-target",started.getFirst().getTargetKey());
            assertEquals("2026-09-26",left.getLastAccess(player.getUniqueId()));assertEquals("2026-09-26",right.getLastAccess(player.getUniqueId()));assertEquals(owner,left.getOwner());assertEquals(owner,right.getOwner());
            verify(storage,times(2)).saveContainerData(left);verify(storage,times(2)).saveContainerData(right);
            verify(stealManager).openSession(player,references.constructed().getFirst(),menu);
            closers.getFirst().run();manager.onRightClickChest(interact(chest.block(),Action.RIGHT_CLICK_BLOCK));assertEquals(2,started.size());
        } finally {net.tfminecraft.thievery.cache.Cache.radius=radius;net.tfminecraft.thievery.cache.Cache.traits=traits;net.tfminecraft.thievery.cache.Cache.requireOwnerOnline=online;net.tfminecraft.thievery.cache.Cache.debugAllowOwnChest=own;}
    }

    @Test void doubleChestTheftFeedbackUsesTheCanonicalLeftLocation() {
        var chest = doubleChest();
        lock(chest.block(), UUID.randomUUID());
        var staff = mock(Player.class);
        when(staff.getUniqueId()).thenReturn(UUID.randomUUID());
        when(staff.hasPermission("thievery.admin")).thenReturn(true);
        manager.enableFeedback(staff);
        var event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(chest.inventory());
        when(event.getClick()).thenReturn(ClickType.SHIFT_LEFT);
        when(event.getCurrentItem()).thenReturn(new ItemStack(Material.DIAMOND));
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(staff));
            manager.onInventoryClick(event);
            var feedback = ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
            verify(staff).sendMessage(feedback.capture());
            String message = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(feedback.getValue());
            assertTrue(message.contains("thief is taking from a chest"));
            verify(storage).loadContainerData(chest.left().getLocation());
            verify(storage, never()).loadContainerData(chest.right().getLocation());
        }
    }

}
