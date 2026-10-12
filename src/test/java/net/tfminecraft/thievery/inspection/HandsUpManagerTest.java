package net.tfminecraft.thievery.inspection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.tfminecraft.rpcharacters.profile.CharacterProfileViewEvent;
import net.tfminecraft.thievery.player.PickpocketManager;
import net.tfminecraft.thievery.robbery.RobberyManager;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class HandsUpManagerTest {
    private ServerMock server;
    private Plugin plugin;
    private HandsUpManager manager;
    private PlayerMock target;
    private PlayerMock viewer;

    @BeforeEach
    void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        manager = new HandsUpManager();
        server.getPluginManager().registerEvents(manager, plugin);
        server.getPluginManager().registerEvents(new HandsUpCharacterListener(manager), plugin);
        target = server.addPlayer();
        viewer = server.addPlayer();
        target.setLocation(new Location(server.addSimpleWorld("inspection"), 0, 64, 0));
        viewer.setLocation(target.getLocation().add(2, 0, 0));
        // MockBukkit's initial crafting view has no top inventory.
        target.openInventory(Bukkit.createInventory(null, 9));
        viewer.openInventory(Bukkit.createInventory(null, 9));
    }

    @AfterEach
    void teardown() {
        MockBukkit.unmock();
    }

    private void toggle(PlayerMock player) {
        assertTrue(manager.onCommand(player, null, "handsup", new String[0]));
    }

    private PlayerInteractEntityEvent interact(PlayerMock from, Entity to, EquipmentSlot hand) {
        var event = new PlayerInteractEntityEvent(from, to, hand);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private Inventory inspect() {
        assertTrue(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
        Inventory inventory = viewer.getOpenInventory().getTopInventory();
        assertInstanceOf(InspectionInventory.class, inventory.getHolder());
        return inventory;
    }

    @Test
    void consentDefaultsOffAndCommandTogglesForOrdinaryPlayersOnly() {
        assertFalse(viewer.isOp());
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
        CommandSender console = mock(CommandSender.class);
        assertTrue(manager.onCommand(console, null, "handsup", new String[0]));
        verify(console).sendMessage(contains("Only players"));
        assertTrue(manager.onCommand(target, null, "handsup", new String[]{"someone-else"}));
        assertTrue(target.nextMessage().contains("Use /handsup"));
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
        assertEquals(List.of(), manager.onTabComplete(target, null, "handsup", new String[]{""}));
        toggle(target);
        assertTrue(target.nextMessage().contains("cannot take items"));
        Inventory inspection = inspect();
        toggle(target);
        assertTrue(target.nextMessage().contains("inspection is off"));
        assertNotSame(inspection, viewer.getOpenInventory().getTopInventory());
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
    }

    @Test
    void displayContainsAllStorageEquipmentAndMetadataWithIndependentLiveCopies() {
        for (int slot = 0; slot < 36; slot++) {
            target.getInventory().setItem(slot, new ItemStack(Material.STONE, slot + 1));
        }
        target.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        target.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        target.getInventory().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
        target.getInventory().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        ItemStack offhand = new ItemStack(Material.SHIELD);
        offhand.editMeta(meta -> {
            meta.displayName(Component.text("Custom shield"));
            meta.lore(List.of(Component.text("Preserve this lore")));
        });
        target.getInventory().setItemInOffHand(offhand);
        toggle(target);
        Inventory inventory = inspect();
        assertEquals(Component.text("Inventory (read-only)"), viewer.getOpenInventory().title());
        assertEquals(45, inventory.getSize());
        for (int slot = 0; slot < 27; slot++) {
            assertEquals(slot + 10, inventory.getItem(slot).getAmount());
        }
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(slot + 1, inventory.getItem(slot + 27).getAmount());
        }
        assertEquals(Material.DIAMOND_HELMET, inventory.getItem(36).getType());
        assertEquals(Material.DIAMOND_CHESTPLATE, inventory.getItem(37).getType());
        assertEquals(Material.DIAMOND_LEGGINGS, inventory.getItem(38).getType());
        assertEquals(Material.DIAMOND_BOOTS, inventory.getItem(39).getType());
        assertEquals(offhand, inventory.getItem(40));
        assertNull(inventory.getItem(41));
        assertNotSame(target.getInventory().getItem(9), inventory.getItem(0));
        inventory.getItem(0).setAmount(1);
        assertEquals(10, target.getInventory().getItem(9).getAmount());
        manager.refresh();
        assertEquals(10, inventory.getItem(0).getAmount());
        target.getInventory().setItem(9, null);
        target.getInventory().setItemInOffHand(new ItemStack(Material.TORCH, 3));
        manager.refresh();
        assertTrue(inventory.getItem(0) == null || inventory.getItem(0).getType().isAir());
        assertEquals(new ItemStack(Material.TORCH, 3), inventory.getItem(40));
        manager.refresh();
        assertEquals(new ItemStack(Material.TORCH, 3), inventory.getItem(40));
    }

    @Test
    void clicksInEitherInventoryAndOutsideCannotMoveCloneDropOrCollectItems() {
        target.getInventory().setItem(9, new ItemStack(Material.DIAMOND, 5));
        viewer.getInventory().setItem(0, new ItemStack(Material.GOLD_INGOT, 7));
        toggle(target);
        Inventory inventory = inspect();
        record Attempt(ClickType click, InventoryAction action, int slot, int key) {}
        List<Attempt> attempts = List.of(
                new Attempt(ClickType.LEFT, InventoryAction.PICKUP_ALL, 0, -1),
                new Attempt(ClickType.RIGHT, InventoryAction.PICKUP_HALF, 0, -1),
                new Attempt(ClickType.LEFT, InventoryAction.PLACE_ALL, 1, -1),
                new Attempt(ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, 0, -1),
                new Attempt(ClickType.SHIFT_RIGHT, InventoryAction.MOVE_TO_OTHER_INVENTORY, 72, -1),
                new Attempt(ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0, 0),
                new Attempt(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, 0, -1),
                new Attempt(ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, 0, -1),
                new Attempt(ClickType.DROP, InventoryAction.DROP_ONE_SLOT, 0, -1),
                new Attempt(ClickType.CONTROL_DROP, InventoryAction.DROP_ALL_SLOT, 0, -1),
                new Attempt(ClickType.LEFT, InventoryAction.PICKUP_ALL, 72, -1),
                new Attempt(ClickType.LEFT, InventoryAction.DROP_ALL_CURSOR, -999, -1));
        for (Attempt attempt : attempts) {
            var event = new InventoryClickEvent(viewer.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                    attempt.slot(), attempt.click(), attempt.action(), attempt.key());
            server.getPluginManager().callEvent(event);
            assertTrue(event.isCancelled(), attempt.toString());
        }
        viewer.setGameMode(GameMode.CREATIVE);
        var clone = new InventoryClickEvent(viewer.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.MIDDLE, InventoryAction.CLONE_STACK);
        server.getPluginManager().callEvent(clone);
        assertTrue(clone.isCancelled());
        var creative = new InventoryCreativeEvent(viewer.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, new ItemStack(Material.NETHERITE_INGOT, 64));
        server.getPluginManager().callEvent(creative);
        assertTrue(creative.isCancelled());
        assertEquals(5, target.getInventory().getItem(9).getAmount());
        assertEquals(5, inventory.getItem(0).getAmount());
        assertEquals(7, viewer.getInventory().getItem(0).getAmount());
    }

    @Test
    void dragsAreBlockedAcrossTopBottomAndBothButOtherChestsStayUsable() {
        toggle(target);
        inspect();
        for (Map<Integer, ItemStack> slots : List.of(
                Map.of(0, new ItemStack(Material.STONE)), Map.of(45, new ItemStack(Material.STONE)),
                Map.of(0, new ItemStack(Material.STONE), 45, new ItemStack(Material.STONE)))) {
            var drag = new InventoryDragEvent(viewer.getOpenInventory(), new ItemStack(Material.STONE),
                    new ItemStack(Material.STONE, 3), false, slots);
            server.getPluginManager().callEvent(drag);
            assertTrue(drag.isCancelled());
        }
        viewer.openInventory(Bukkit.createInventory(null, 9));
        Inventory other = viewer.getOpenInventory().getTopInventory();
        var click = new InventoryClickEvent(viewer.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(click);
        assertFalse(click.isCancelled());
        var drag = new InventoryDragEvent(viewer.getOpenInventory(), new ItemStack(Material.STONE),
                new ItemStack(Material.STONE, 3), true, Map.of(0, new ItemStack(Material.STONE)));
        server.getPluginManager().callEvent(drag);
        assertFalse(drag.isCancelled());
        manager.shutdown();
        assertSame(other, viewer.getOpenInventory().getTopInventory());
    }

    @Test
    void bothHandEventsPreventTheftTargetingButOnlyMainHandOpensOnce() {
        // Register the real handlers with armed selections to exercise Bukkit event ordering.
        RobberyManager robbery = new RobberyManager();
        PickpocketManager pickpocket = new PickpocketManager(null);
        try (var steals = mockStatic(net.tfminecraft.thievery.steal.StealManager.class)) {
            steals.when(net.tfminecraft.thievery.steal.StealManager::getInstance)
                    .thenReturn(mock(net.tfminecraft.thievery.steal.StealManager.class));
            robbery.startAwaitingTarget(viewer);
            pickpocket.startAwaitingTarget(viewer);
        }
        server.getPluginManager().registerEvents(robbery, plugin);
        server.getPluginManager().registerEvents(pickpocket, plugin);
        toggle(target);
        Inventory previous = viewer.getOpenInventory().getTopInventory();
        assertTrue(interact(viewer, target, EquipmentSlot.OFF_HAND).isCancelled());
        assertSame(previous, viewer.getOpenInventory().getTopInventory());
        Inventory inspection = inspect();
        assertTrue(interact(viewer, target, EquipmentSlot.OFF_HAND).isCancelled());
        assertSame(inspection, viewer.getOpenInventory().getTopInventory());
        assertTrue(robbery.isAwaitingTarget(viewer.getUniqueId()));
        assertTrue(pickpocket.isAwaitingTarget(viewer.getUniqueId()));
    }

    @Test
    void invalidTargetsAndCancelledInteractionsNeverOpenAnInspection() {
        toggle(target);
        assertFalse(interact(target, target, EquipmentSlot.HAND).isCancelled());
        assertFalse(interact(viewer, mock(Entity.class), EquipmentSlot.HAND).isCancelled());
        viewer.setLocation(target.getLocation().add(4.01, 0, 0));
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
        viewer.setLocation(target.getLocation().add(4, 0, 0));
        Inventory inventory = inspect();
        viewer.setLocation(new Location(server.addSimpleWorld("elsewhere"), 0, 64, 0));
        manager.refresh();
        assertNotSame(inventory, viewer.getOpenInventory().getTopInventory());
        viewer.setLocation(target.getLocation());
        viewer.hidePlayer(plugin, target);
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
        viewer.showPlayer(plugin, target);
        Inventory beforeCancelledClick = viewer.getOpenInventory().getTopInventory();
        var cancelled = new PlayerInteractEntityEvent(viewer, target, EquipmentSlot.HAND);
        cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        assertSame(beforeCancelledClick, viewer.getOpenInventory().getTopInventory());
        // A dead viewer or target cannot start an inspection.
        viewer.setHealth(0);
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
        var dyingTarget = mock(org.bukkit.entity.Player.class);
        when(dyingTarget.getUniqueId()).thenReturn(target.getUniqueId());
        when(dyingTarget.isDead()).thenReturn(true);
        assertFalse(interact(viewer, dyingTarget, EquipmentSlot.HAND).isCancelled());
        target.setHealth(0);
        toggle(target);
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
    }

    @Test
    void deniedInventoryOpenDoesNotTrackOrCloseAnotherMenu() {
        toggle(target);
        Inventory other = viewer.getOpenInventory().getTopInventory();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void onOpen(InventoryOpenEvent event) {
                if (event.getInventory().getHolder() instanceof InspectionInventory) event.setCancelled(true);
            }
        }, plugin);
        assertTrue(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
        assertFalse(viewer.getOpenInventory().getTopInventory() != null
                && viewer.getOpenInventory().getTopInventory().getHolder() instanceof InspectionInventory);
        viewer.openInventory(other);
        manager.shutdown();
        assertSame(other, viewer.getOpenInventory().getTopInventory());
    }

    @Test
    void withdrawingConsentClosesEveryViewerButLeavesOtherInspectionsOpen() {
        toggle(target);
        Inventory first = inspect();
        PlayerMock second = server.addPlayer();
        second.setLocation(viewer.getLocation());
        interact(second, target, EquipmentSlot.HAND);
        Inventory secondView = second.getOpenInventory().getTopInventory();
        toggle(viewer);
        interact(target, viewer, EquipmentSlot.HAND);
        manager.clearPlayer(second);
        assertNotSame(secondView, second.getOpenInventory().getTopInventory());
        assertSame(first, viewer.getOpenInventory().getTopInventory());
        toggle(target);
        assertNotSame(first, viewer.getOpenInventory().getTopInventory());
        assertFalse(interact(second, target, EquipmentSlot.HAND).isCancelled());
    }

    @Test
    void leavingRangeClosesTheViewAndReenteringRequiresAnotherClick() {
        toggle(target);
        Inventory inventory = inspect();
        target.setLocation(target.getLocation().add(20, 0, 0));
        manager.refresh();
        assertNotSame(inventory, viewer.getOpenInventory().getTopInventory());
        target.setLocation(viewer.getLocation());
        assertNotSame(inventory, inspect());
        manager.shutdown();
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
    }

    @Test
    void quitDeathTeleportAndCharacterSwitchRevokeConsentAndCloseViews() {
        toggle(target);
        Inventory inventory = inspect();
        server.getPluginManager().callEvent(new PlayerQuitEvent(target, Component.empty()));
        assertNotSame(inventory, viewer.getOpenInventory().getTopInventory());
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
        toggle(target);
        inventory = inspect();
        var death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(target);
        manager.onDeath(death);
        assertNotSame(inventory, viewer.getOpenInventory().getTopInventory());
        toggle(target);
        inventory = inspect();
        var teleport = new PlayerTeleportEvent(target, target.getLocation(), target.getLocation().add(1, 0, 0));
        teleport.setCancelled(true);
        server.getPluginManager().callEvent(teleport);
        assertSame(inventory, viewer.getOpenInventory().getTopInventory());
        teleport.setCancelled(false);
        server.getPluginManager().callEvent(teleport);
        assertNotSame(inventory, viewer.getOpenInventory().getTopInventory());
        toggle(target);
        inventory = inspect();
        server.getPluginManager().callEvent(new CharacterSessionEndEvent(target, mock(CharacterSession.class),
                CharacterSessionEndEvent.Reason.SWITCH));
        assertNotSame(inventory, viewer.getOpenInventory().getTopInventory());
        assertFalse(interact(viewer, target, EquipmentSlot.HAND).isCancelled());
    }

    @Test
    void disconnectedTargetsAreClosedEvenIfQuitCleanupWasMissed() {
        toggle(target);
        Inventory inventory = inspect();
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getPlayer(target.getUniqueId())).thenReturn(null);
            manager.refresh();
        }
        assertNotSame(inventory, viewer.getOpenInventory().getTopInventory());
    }

    @Test
    void inspectionSuppressesClickProfilesButAllowsExplicitProfileCommands() {
        var ordinary = new CharacterProfileViewEvent(viewer, target, null, false, false);
        manager.onProfile(ordinary);
        assertFalse(ordinary.isCancelled());
        toggle(target);
        manager.onProfile(ordinary);
        assertTrue(ordinary.isCancelled());
        var command = new CharacterProfileViewEvent(viewer, target, null, false, true);
        manager.onProfile(command);
        assertFalse(command.isCancelled());
        var missingTarget = new CharacterProfileViewEvent(viewer, null, null, false, false);
        manager.onProfile(missingTarget);
        assertFalse(missingTarget.isCancelled());
    }
}
