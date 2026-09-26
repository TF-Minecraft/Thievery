package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;

import net.tfminecraft.rpcharacters.grave.Grave;
import net.tfminecraft.rpcharacters.grave.GraveLootRules;
import net.tfminecraft.rpcharacters.grave.GraveManager;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.category.CategoryHandler;
import net.tfminecraft.thievery.category.ItemValue;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.PlayerManager;
import net.tfminecraft.thievery.utils.EvilRpPlays;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

class GraveStealListenerTest {
    private ServerMock server;
    private Player player;
    private Block block;
    private Grave grave;
    private GraveManager graves;
    private PlayerData thief;
    private GraveStealListener listener;
    private final Map<Integer, ItemStack> contents = new HashMap<>();
    private final Set<Material> revealable = EnumSet.of(Material.DIAMOND, Material.EMERALD);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private MockedStatic<GraveLootRules> permissions;
    private MockedStatic<ItemValue> values;
    private MockedStatic<EvilRpPlays> evil;

    private <T> MockedStatic<T> statics(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = spy(server.addPlayer());
        block = server.addSimpleWorld("graves").getBlockAt(1, 64, 1);
        block.setType(Material.CHEST);
        grave = mock(Grave.class);
        when(grave.getItem(anyInt())).thenAnswer(call -> contents.get(call.<Integer>getArgument(0)));
        doAnswer(call -> {
            int slot = call.getArgument(0);
            ItemStack item = call.getArgument(1);
            if (item == null) contents.remove(slot); else contents.put(slot, item);
            return null;
        }).when(grave).setItem(anyInt(), nullable(ItemStack.class));
        graves = mock(GraveManager.class);
        when(graves.getAt(any(Block.class))).thenReturn(grave);
        statics(GraveManager.class).when(GraveManager::get).thenReturn(graves);
        permissions = statics(GraveLootRules.class);
        permissions.when(() -> GraveLootRules.canSteal(player, grave)).thenReturn(true);
        thief = new PlayerData(player.getUniqueId());
        PlayerManager players = mock(PlayerManager.class);
        when(players.get(player.getUniqueId())).thenReturn(thief);
        statics(Thievery.class).when(Thievery::getPlayerManager).thenReturn(players);
        statics(CategoryHandler.class).when(() -> CategoryHandler.canRevealItem(eq(thief), any()))
                .thenAnswer(call -> revealable.contains(((ItemStack) call.getArgument(1)).getType()));
        values = statics(ItemValue.class);
        values.when(() -> ItemValue.isBundle(any())).thenAnswer(call -> ((ItemStack) call.getArgument(0)).getType() == Material.BUNDLE);
        evil = statics(EvilRpPlays.class);
        listener = new GraveStealListener();
    }

    @AfterEach
    void tearDown() {
        for (int i = statics.size() - 1; i >= 0; i--) statics.get(i).close();
        MockBukkit.unmock();
    }

    private PlayerInteractEvent interact(Action action, EquipmentSlot hand, Block clicked) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(action);
        when(event.getHand()).thenReturn(hand);
        when(event.getPlayer()).thenReturn(player);
        when(event.getClickedBlock()).thenReturn(clicked);
        listener.onInteract(event);
        return event;
    }

    private void loot() {
        interact(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, block);
    }

    private void open(HumanEntity actor, Inventory inventory) {
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getPlayer()).thenReturn(actor);
        when(event.getInventory()).thenReturn(inventory);
        listener.onInventoryOpen(event);
    }

    @Test void depletedGraveStacksDoNotCreateItemsOrPreventTakingRemainingLoot() {
        ItemStack depleted = new ItemStack(Material.DIAMOND);
        depleted.setAmount(0);
        contents.put(0, depleted);
        contents.put(1, new ItemStack(Material.EMERALD, 2));
        loot();
        assertEquals(0, inventoryCount(Material.DIAMOND));
        assertEquals(2, inventoryCount(Material.EMERALD));
        assertFalse(contents.containsKey(1));
        verify(grave).flush();
        verify(graves).removeIfEmpty(grave);
    }

    private int inventoryCount(Material material) {
        return Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull)
                .filter(item -> item.getType() == material).mapToInt(ItemStack::getAmount).sum();
    }

    private void fillInventory() {
        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
    }

    @Test
    void interactionRequiresMainHandRightClickOnANonOwnedGrave() {
        interact(Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND, block);
        interact(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.OFF_HAND, block);
        interact(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, null);
        verifyNoInteractions(graves);
        when(graves.getAt(block)).thenReturn(null);
        loot();
        permissions.verifyNoInteractions();
        when(graves.getAt(block)).thenReturn(grave);
        when(grave.isOwner(player.getUniqueId())).thenReturn(true);
        loot();
        permissions.verifyNoInteractions();
        verify(grave, never()).flush();
    }

    @Test
    void deniedGraveRemainsUntouchedAndRepeatedEventsAreDebouncedPerChest() throws Exception {
        permissions.when(() -> GraveLootRules.canSteal(player, grave)).thenReturn(false);
        loot();
        // Advance/refresh the existing timestamp to model event timing without a sleep.
        Field field = GraveStealListener.class.getDeclaredField("lastStealAt");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID, Long> times = (Map<UUID, Long>) field.get(listener);
        times.put(player.getUniqueId(), System.currentTimeMillis());
        loot();
        permissions.verify(() -> GraveLootRules.canSteal(player, grave), times(1));
        Block otherChest = block.getWorld().getBlockAt(2, 64, 1);
        interact(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, otherChest);
        permissions.verify(() -> GraveLootRules.canSteal(player, grave), times(2));
        times.put(player.getUniqueId(), System.currentTimeMillis() - 1000);
        interact(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, otherChest);
        permissions.verify(() -> GraveLootRules.canSteal(player, grave), times(3));
        verify(grave, never()).flush();
        evil.verifyNoInteractions();
    }

    @Test
    void inventoryOpenFindsBlockHoldersAndLocationFallbackButIgnoresNonPlayersAndPortableInventories() {
        open(mock(HumanEntity.class), mock(Inventory.class));
        open(player, mock(Inventory.class));
        verifyNoInteractions(graves);
        permissions.when(() -> GraveLootRules.canSteal(player, grave)).thenReturn(false);
        Inventory heldByBlock = mock(Inventory.class);
        when(heldByBlock.getHolder()).thenReturn((org.bukkit.block.Chest) block.getState());
        open(player, heldByBlock);
        verify(graves).getAt(block);
        Block otherBlock = block.getWorld().getBlockAt(4, 64, 1);
        Inventory located = mock(Inventory.class);
        when(located.getLocation()).thenReturn(otherBlock.getLocation());
        open(player, located);
        verify(graves).getAt(otherBlock);
    }

    @Test
    void eligibleStacksTransferAndFlushWhileHiddenItemsStayInNamedOwnersGrave() {
        Player owner = server.addPlayer("Owner");
        when(grave.getOwner()).thenReturn(owner.getUniqueId());
        contents.put(0, new ItemStack(Material.DIAMOND, 5));
        contents.put(1, new ItemStack(Material.GOLD_INGOT, 2));
        contents.put(2, new ItemStack(Material.EMERALD, 3));
        contents.put(3, new ItemStack(Material.AIR));
        loot();
        assertEquals(5, inventoryCount(Material.DIAMOND));
        assertEquals(3, inventoryCount(Material.EMERALD));
        assertEquals(0, inventoryCount(Material.GOLD_INGOT));
        assertFalse(contents.containsKey(0));
        assertFalse(contents.containsKey(2));
        assertEquals(2, contents.get(1).getAmount());
        verify(grave).flush();
        verify(graves).removeIfEmpty(grave);
        evil.verify(() -> EvilRpPlays.record(player));
        verify(player).sendMessage(contains("You took 2 stacks from Owner's grave"));
    }

    @Test
    void partialFitKeepsRemainderAndReportsFullInventoryAfterSuccessfulStack() {
        fillInventory();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 63));
        contents.put(0, new ItemStack(Material.DIAMOND, 5));
        contents.put(1, new ItemStack(Material.EMERALD, 1));
        loot();
        assertEquals(64, inventoryCount(Material.DIAMOND));
        assertEquals(4, contents.get(0).getAmount());
        assertEquals(1, contents.get(1).getAmount());
        verify(player).sendMessage(contains("You took 1 stack from someone's grave"));
        verify(player).sendMessage(contains("Your inventory is full"));
        verify(grave).flush();
    }

    @Test
    void noInventorySpacePreservesItemsAndReportsFailureWithoutSuccess() {
        fillInventory();
        contents.put(0, new ItemStack(Material.DIAMOND, 2));
        loot();
        assertEquals(2, contents.get(0).getAmount());
        assertEquals(0, inventoryCount(Material.DIAMOND));
        verify(player).sendMessage(contains("don't have enough inventory space"));
        verify(player, never()).sendMessage(contains("You took"));
        verify(grave).flush();
    }

    @Test
    void rejectedInventoryInsertionDoesNotRemoveGraveStack() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null});
        ItemStack stack = new ItemStack(Material.DIAMOND, 2);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>(Map.of(0, stack)));
        doReturn(inventory).when(player).getInventory();
        contents.put(0, stack);
        loot();
        assertSame(stack, contents.get(0));
        assertEquals(2, stack.getAmount());
        verify(grave, never()).setItem(anyInt(), any());
        verify(player).sendMessage(contains("don't have enough inventory space"));
    }

    @Test
    void hiddenAndEmptyGraveContentsReportNothingStealable() {
        contents.put(0, new ItemStack(Material.GOLD_INGOT));
        contents.put(1, new ItemStack(Material.BUNDLE));
        loot();
        assertEquals(2, contents.size());
        verify(player).sendMessage(contains("nothing you can steal here"));
        verify(grave, never()).setItem(anyInt(), any());
        verify(grave).flush();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings={"   "})
    void visibleBundleShellCanBeTakenWhenNoInnerContentsAreStealable(String ownerName) {
        revealable.add(Material.BUNDLE);
        contents.put(0, new ItemStack(Material.BUNDLE));
        UUID ownerId = UUID.randomUUID();
        when(grave.getOwner()).thenReturn(ownerId);
        OfflinePlayer unknownOwner = mock(OfflinePlayer.class);
        when(unknownOwner.getName()).thenReturn(ownerName);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(ownerId)).thenReturn(unknownOwner);
            loot();
        }
        assertTrue(contents.isEmpty());
        assertEquals(1, inventoryCount(Material.BUNDLE));
        verify(player).sendMessage(contains("1 stack from someone's grave"));
    }

    @Test
    void successfulBundleResultRemovesOrUpdatesGraveSourceAndReportsStack() {
        ItemStack first = new ItemStack(Material.BUNDLE);
        ItemStack second = new ItemStack(Material.RED_BUNDLE);
        contents.put(0, first);
        contents.put(1, second);
        values.when(() -> ItemValue.isBundle(second)).thenReturn(true);
        values.when(() -> ItemValue.hasStealableContents(thief, first, Double.POSITIVE_INFINITY)).thenReturn(true);
        values.when(() -> ItemValue.hasStealableContents(thief, second, Double.POSITIVE_INFINITY)).thenReturn(true);
        values.when(() -> ItemValue.takeFromBundle(first, player, thief, Double.POSITIVE_INFINITY, ItemValue.BundleTakeMode.GREEDY))
                .thenReturn(ItemValue.BundleTakeResult.removedFromSource(5));
        ItemValue.BundleTakeResult partial = mock(ItemValue.BundleTakeResult.class);
        when(partial.isAnyTaken()).thenReturn(true);
        ItemStack updated = new ItemStack(Material.RED_BUNDLE);
        when(partial.getUpdatedBundle()).thenReturn(updated);
        values.when(() -> ItemValue.takeFromBundle(second, player, thief, Double.POSITIVE_INFINITY, ItemValue.BundleTakeMode.GREEDY)).thenReturn(partial);
        loot();
        assertFalse(contents.containsKey(0));
        assertSame(updated, contents.get(1));
        verify(player).sendMessage(contains("You took 2 stacks"));
        verify(grave).flush();
    }

    @Test
    void bundleWithNoSuccessfulTakeContinuesToOtherSlotsIfInventoryHasSpace() {
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        contents.put(0, bundle);
        contents.put(1, new ItemStack(Material.DIAMOND));
        values.when(() -> ItemValue.hasStealableContents(thief, bundle, Double.POSITIVE_INFINITY)).thenReturn(true);
        values.when(() -> ItemValue.takeFromBundle(bundle, player, thief, Double.POSITIVE_INFINITY, ItemValue.BundleTakeMode.GREEDY))
                .thenReturn(ItemValue.BundleTakeResult.none(bundle));
        loot();
        assertSame(bundle, contents.get(0));
        assertFalse(contents.containsKey(1));
        assertEquals(1, inventoryCount(Material.DIAMOND));
        verify(player).sendMessage(contains("You took 1 stack"));
    }

    @Test
    void failedBundleTakeWithFullInventoryPreservesBundleAndStopsLooting() {
        fillInventory();
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        contents.put(0, bundle);
        values.when(() -> ItemValue.hasStealableContents(thief, bundle, Double.POSITIVE_INFINITY)).thenReturn(true);
        values.when(() -> ItemValue.takeFromBundle(bundle, player, thief, Double.POSITIVE_INFINITY, ItemValue.BundleTakeMode.GREEDY))
                .thenReturn(ItemValue.BundleTakeResult.none(bundle));
        loot();
        assertSame(bundle, contents.get(0));
        verify(player).sendMessage(contains("don't have enough inventory space"));
        verify(grave).flush();
    }
}
