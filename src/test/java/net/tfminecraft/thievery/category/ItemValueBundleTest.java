package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;

import net.tfminecraft.thievery.category.ItemValue.BundleTakeMode;
import net.tfminecraft.thievery.category.ItemValue.BundleTakeResult;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class ItemValueBundleTest {
    private MockedStatic<CategoryHandler> categories;
    private MockedStatic<ClueChecker> clues;
    private MockedStatic<StringFormatter> names;
    private Player player;
    private PlayerData thief;
    private ItemCategory shellCategory;
    private final Set<Material> revealable = EnumSet.of(Material.DIAMOND, Material.EMERALD, Material.DIRT);

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        player = MockBukkit.getMock().addPlayer();
        thief = new PlayerData(player.getUniqueId());
        shellCategory = mock(ItemCategory.class);
        when(shellCategory.getId()).thenReturn("bundles");
        categories = mockStatic(CategoryHandler.class);
        categories.when(() -> CategoryHandler.canRevealItem(eq(thief), any())).thenAnswer(call -> {
            ItemStack item = call.getArgument(1);
            return item != null && revealable.contains(item.getType());
        });
        categories.when(() -> CategoryHandler.resolveCategory(any())).thenAnswer(call -> {
            ItemStack item = call.getArgument(0);
            return item != null && item.getType() == Material.BUNDLE ? shellCategory : null;
        });
        categories.when(() -> CategoryHandler.getPerItemValue(any())).thenAnswer(call -> value(call.getArgument(0)));
        categories.when(() -> CategoryHandler.getTotalValue(any())).thenAnswer(call -> {
            ItemStack item = call.getArgument(0);
            return item.getType() == Material.BUNDLE ? ItemValue.getContentsValue(item) : value(item) * item.getAmount();
        });
        clues = mockStatic(ClueChecker.class);
        names = mockStatic(StringFormatter.class);
        names.when(() -> StringFormatter.getName(any(ItemStack.class))).thenAnswer(call -> ((ItemStack) call.getArgument(0)).getType().name());
    }

    @AfterEach
    void tearDown() {
        names.close();
        clues.close();
        categories.close();
        MockBukkit.unmock();
    }

    private double value(ItemStack item) {
        return switch (item.getType()) {
            case DIAMOND -> 2;
            case EMERALD -> 3;
            case DIRT -> 0;
            case GOLD_INGOT -> 10;
            case BUNDLE -> 1;
            default -> 0.1;
        };
    }

    private ItemStack item(Material material, int count) {
        return new ItemStack(material, count);
    }

    private ItemStack bundle(ItemStack... contents) {
        ItemStack item = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) item.getItemMeta();
        meta.setItems(List.of(contents));
        item.setItemMeta(meta);
        return item;
    }

    private Map<Material, Integer> contents(ItemStack bundle) {
        Map<Material, Integer> totals = new EnumMap<>(Material.class);
        for (ItemStack item : ((BundleMeta) bundle.getItemMeta()).getItems()) totals.merge(item.getType(), item.getAmount(), Integer::sum);
        return totals;
    }

    private int inventoryAmount(Material material) {
        return Arrays.stream(player.getInventory().getStorageContents()).filter(Objects::nonNull)
                .filter(item -> item.getType() == material).mapToInt(ItemStack::getAmount).sum();
    }

    private void assertNone(BundleTakeResult result, ItemStack original) {
        assertFalse(result.isAnyTaken());
        assertFalse(result.isRemovedFromSource());
        assertEquals(0, result.getValueTaken());
        assertEquals(original, result.getUpdatedBundle());
    }

    private void useStorageOnlyInventory() {
        // MockBukkit 4.95 scans the newer Paper player's extra equipment slots in addItem.
        // A real 36-slot mock inventory models Bukkit's storage-only insertion contract.
        Inventory storage = MockBukkit.getMock().createInventory(null, 36);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents()).thenAnswer(call -> storage.getContents());
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> storage.addItem((ItemStack) call.getArgument(0)));
        doAnswer(call -> {
            storage.setItem(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
    }

    private void fillInventory() {
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, item(Material.STONE, 64));

    }

    @Test
    void ordinaryItemsDelegateValueAndVisibilityButCannotBeTakenAsBundles() {
        ItemStack diamonds = item(Material.DIAMOND, 3);
        assertFalse(ItemValue.isBundle(null));
        assertFalse(ItemValue.isBundle(new ItemStack(Material.AIR)));
        assertFalse(ItemValue.isBundle(diamonds));
        assertTrue(ItemValue.canRevealBundle(thief, diamonds));
        assertEquals(6, ItemValue.getInnerContentsValue(diamonds));
        assertEquals(6, ItemValue.getContentsValue(diamonds));
        assertEquals(6, ItemValue.getRevealableContentsValue(thief, diamonds));
        assertEquals(0, ItemValue.getRevealableContentsValue(thief, item(Material.GOLD_INGOT, 2)));
        assertNull(ItemValue.buildDisplayBundle(thief, diamonds));
        assertTrue(ItemValue.getRevealableContentsLore(thief, diamonds).isEmpty());
        assertFalse(ItemValue.canStealAnything(thief, diamonds, 10));
        assertFalse(ItemValue.allInnersStealable(thief, diamonds));
        assertEquals(2, ItemValue.estimateOneTakeValue(diamonds, thief, 0));
        assertEquals(6, ItemValue.estimateGreedyTakeValue(diamonds, player, thief, 0));
        assertNone(ItemValue.takeFromBundle(diamonds, player, thief, 10, BundleTakeMode.ONE), diamonds);
    }

    @Test
    void emptyBundleRequiresVisibleShellAndHasNoStealableContents() {
        ItemStack empty = bundle();
        assertTrue(ItemValue.isBundle(empty));
        assertFalse(ItemValue.canRevealBundle(thief, empty));
        assertEquals(0, ItemValue.getInnerContentsValue(empty));
        assertEquals(1, ItemValue.getContentsValue(empty));
        assertEquals(0, ItemValue.getRevealableContentsValue(thief, empty));
        assertNull(ItemValue.buildDisplayBundle(thief, empty));
        assertTrue(ItemValue.getRevealableContentsLore(thief, empty).isEmpty());
        assertTrue(ItemValue.allInnersStealable(thief, empty));
        assertFalse(ItemValue.hasStealableContents(thief, empty, 10));
        assertEquals(0, ItemValue.estimateOneTakeValue(empty, thief, 10));
        assertEquals(0, ItemValue.estimateGreedyTakeValue(empty, player, thief, 0));
        assertNone(ItemValue.takeFromBundle(empty, player, thief, 10, BundleTakeMode.ONE), empty);
        assertNone(ItemValue.takeFromBundle(empty, player, thief, 0, BundleTakeMode.GREEDY), empty);
        thief.setActiveCategories(new ArrayList<>(List.of("bundles")));
        assertTrue(ItemValue.canRevealBundle(thief, empty));
        assertEquals(1, ItemValue.getRevealableContentsValue(thief, empty));
        assertNotNull(ItemValue.buildDisplayBundle(thief, empty));
        categories.when(() -> CategoryHandler.resolveCategory(empty)).thenReturn(null);
        thief.setActiveCategories(List.of());
        assertTrue(ItemValue.canRevealBundle(thief, empty));
        clues.when(() -> ClueChecker.isClueItem(empty)).thenReturn(true);
        assertFalse(ItemValue.canRevealBundle(thief, empty));
    }

    @Test
    void mixedBundleValuesIncludeAllContentsButPreviewOnlyShowsVisibleItems() {
        ItemStack real = bundle(item(Material.DIAMOND, 2), item(Material.GOLD_INGOT, 1));
        assertTrue(ItemValue.canRevealBundle(thief, real));
        assertEquals(14, ItemValue.getInnerContentsValue(real));
        assertEquals(15, ItemValue.getContentsValue(real));
        assertEquals(4, ItemValue.getRevealableContentsValue(thief, real));
        assertFalse(ItemValue.allInnersStealable(thief, real));
        ItemStack display = ItemValue.buildDisplayBundle(thief, real);
        assertNotSame(real, display);
        assertEquals(Map.of(Material.DIAMOND, 2), contents(display));
        assertEquals(Map.of(Material.DIAMOND, 2, Material.GOLD_INGOT, 1), contents(real));
        assertEquals(List.of("§f- DIAMOND §7x2"), ItemValue.getRevealableContentsLore(thief, real));
        thief.setActiveCategories(List.of("bundles"));
        assertEquals(5, ItemValue.getRevealableContentsValue(thief, real));
    }

    @Test
    void hiddenContentsStillAllowAVisibleEmptyShellPreview() {
        ItemStack real = bundle(item(Material.GOLD_INGOT, 1));
        assertFalse(ItemValue.canRevealBundle(thief, real));
        assertNull(ItemValue.buildDisplayBundle(thief, real));
        thief.setActiveCategories(List.of("bundles"));
        assertTrue(ItemValue.canRevealBundle(thief, real));
        assertTrue(contents(ItemValue.buildDisplayBundle(thief, real)).isEmpty());
        assertEquals(Map.of(Material.GOLD_INGOT, 1), contents(real));
        assertEquals(0, ItemValue.estimateOneTakeValue(real, thief, 100));
        assertFalse(ItemValue.canStealAnything(thief, real, 100));
    }

    @Test
    void affordabilityUsesOneItemCostAndAllowsZeroValueContentsAtZeroBudget() {
        ItemStack diamonds = bundle(item(Material.DIAMOND, 12));
        assertFalse(ItemValue.canStealAnything(thief, diamonds, 1.99));
        assertTrue(ItemValue.hasStealableContents(thief, diamonds, 2));
        assertEquals(0, ItemValue.estimateOneTakeValue(diamonds, thief, 1));
        assertEquals(2, ItemValue.estimateOneTakeValue(diamonds, thief, 2));
        ItemStack free = bundle(item(Material.DIRT, 3));
        assertTrue(ItemValue.canStealAnything(thief, free, 0));
        assertEquals(0, ItemValue.estimateOneTakeValue(free, thief, 0));
    }

    @Test
    void singleTakeEstimateAveragesAffordableVisibleStacksInsteadOfTheirAmounts() {
        ItemStack real = bundle(item(Material.DIAMOND, 4), item(Material.EMERALD, 1), item(Material.DIRT, 2), item(Material.GOLD_INGOT, 1));
        assertEquals(5.0 / 3, ItemValue.estimateOneTakeValue(real, thief, 3), 1e-12);
        assertEquals(1, ItemValue.estimateOneTakeValue(real, thief, 2));
    }

    @Test
    void oneTakeRemovesExactlyOneAffordableVisibleItemWithoutChangingSource() {
        ItemStack real = bundle(item(Material.DIAMOND, 3), item(Material.EMERALD, 1), item(Material.GOLD_INGOT, 1));
        BundleTakeResult result = ItemValue.takeFromBundle(real, player, thief, 2, BundleTakeMode.ONE);
        assertTrue(result.isAnyTaken());
        assertFalse(result.isRemovedFromSource());
        assertEquals(2, result.getValueTaken());
        assertEquals(1, inventoryAmount(Material.DIAMOND));
        assertEquals(Map.of(Material.DIAMOND, 2, Material.EMERALD, 1, Material.GOLD_INGOT, 1), contents(result.getUpdatedBundle()));
        assertEquals(3, contents(real).get(Material.DIAMOND));
    }

    @Test
    void takingLastFreeInnerLeavesEmptyShellAndChargesNoBudget() {
        ItemStack real = bundle(item(Material.DIRT, 1));
        BundleTakeResult result = ItemValue.takeFromBundle(real, player, thief, 0, BundleTakeMode.ONE);
        assertTrue(result.isAnyTaken());
        assertEquals(0, result.getValueTaken());
        assertTrue(contents(result.getUpdatedBundle()).isEmpty());
        assertEquals(1, inventoryAmount(Material.DIRT));
        assertFalse(result.isRemovedFromSource());
    }

    @Test
    void oneTakeWithNoAffordableItemOrFullInventoryPreservesSourceAndBudget() {
        useStorageOnlyInventory();
        ItemStack real = bundle(item(Material.DIAMOND, 2), item(Material.GOLD_INGOT, 1));
        assertNone(ItemValue.takeFromBundle(real, player, thief, 1, BundleTakeMode.ONE), real);
        fillInventory();
        assertNone(ItemValue.takeFromBundle(real, player, thief, 2, BundleTakeMode.ONE), real);
        assertEquals(0, inventoryAmount(Material.DIAMOND));
    }

    @Test
    void greedyCanTransferWholeBundleIncludingItsShellWhenBudgetAndInventoryPermit() {
        ItemStack real = bundle(item(Material.DIAMOND, 2));
        thief.setActiveCategories(List.of("bundles"));
        assertTrue(ItemValue.allInnersStealable(thief, real));
        assertTrue(ItemValue.canFitBundleItem(player, real));
        assertEquals(5, ItemValue.estimateGreedyTakeValue(real, player, thief, 5));
        BundleTakeResult result = ItemValue.takeFromBundle(real, player, thief, 5, BundleTakeMode.GREEDY);
        assertTrue(result.isAnyTaken());
        assertTrue(result.isRemovedFromSource());
        assertNull(result.getUpdatedBundle());
        assertEquals(5, result.getValueTaken());
        assertEquals(real, player.getInventory().getItem(0));
        assertEquals(Map.of(Material.DIAMOND, 2), contents(real));
    }

    @Test
    void greedyInnerTakeRespectsVisibilityAndBudgetAcrossMultiplePasses() {
        ItemStack real = bundle(item(Material.DIAMOND, 3), item(Material.EMERALD, 2), item(Material.DIRT, 2), item(Material.GOLD_INGOT, 1));
        assertEquals(7, ItemValue.estimateGreedyTakeValue(real, player, thief, 7));
        BundleTakeResult result = ItemValue.takeFromBundle(real, player, thief, 7, BundleTakeMode.GREEDY);
        assertTrue(result.isAnyTaken());
        assertFalse(result.isRemovedFromSource());
        assertEquals(7, result.getValueTaken());
        assertEquals(Map.of(Material.DIAMOND, 1, Material.EMERALD, 1, Material.GOLD_INGOT, 1), contents(result.getUpdatedBundle()));
        assertEquals(2, inventoryAmount(Material.DIAMOND));
        assertEquals(1, inventoryAmount(Material.EMERALD));
        assertEquals(2, inventoryAmount(Material.DIRT));
        assertEquals(3, contents(real).get(Material.DIAMOND));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {4, 5})
    void greedyStopsWhenInventoryFillsAndReturnsOnlySuccessfullyInsertedValue(double budget) {
        useStorageOnlyInventory();
        ItemStack real = bundle(item(Material.DIAMOND, 2));
        fillInventory();
        assertFalse(ItemValue.canFitBundleItem(player, real));
        assertEquals(0, ItemValue.estimateGreedyTakeValue(real, player, thief, budget));
        assertNone(ItemValue.takeFromBundle(real, player, thief, budget, BundleTakeMode.GREEDY), real);
        player.getInventory().setItem(0, item(Material.DIAMOND, 63));
        double estimated = ItemValue.estimateGreedyTakeValue(real, player, thief, budget);
        BundleTakeResult result = ItemValue.takeFromBundle(real, player, thief, budget, BundleTakeMode.GREEDY);
        assertTrue(result.isAnyTaken());
        assertEquals(2, result.getValueTaken());
        assertEquals(Map.of(Material.DIAMOND, 1), contents(result.getUpdatedBundle()));
        assertEquals(64, inventoryAmount(Material.DIAMOND));
        assertEquals(result.getValueTaken(), estimated,
                "Preview must account for the single remaining stack space");
    }

    @Test
    void greedyCanEmptyAllInnersWithoutPayingForOrRemovingShell() {
        ItemStack real = bundle(item(Material.DIAMOND, 1), item(Material.DIRT, 2));
        assertEquals(2, ItemValue.estimateGreedyTakeValue(real, player, thief, 2));
        BundleTakeResult result = ItemValue.takeFromBundle(real, player, thief, 2, BundleTakeMode.GREEDY);
        assertEquals(2, result.getValueTaken());
        assertTrue(result.isAnyTaken());
        assertFalse(result.isRemovedFromSource());
        assertTrue(contents(result.getUpdatedBundle()).isEmpty());
    }

    @Test
    void rejectedWholeBundleInsertionDoesNotRemoveSourceOrChargeValue() {
        ItemStack real = bundle(item(Material.DIAMOND, 1));
        Player rejecting = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(rejecting.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null});
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>(Map.of(0, real)));
        assertNone(ItemValue.takeFromBundle(real, rejecting, thief, 10, BundleTakeMode.GREEDY), real);
        assertEquals(Map.of(Material.DIAMOND, 1), contents(real));
    }

    @Test
    void greedyPreviewAcceptsAirStorageSlotsWithoutMutatingTheInventorySnapshot() {
        ItemStack[] storage = new ItemStack[36];
        Arrays.fill(storage, item(Material.STONE, 64));
        storage[0] = new ItemStack(Material.AIR);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getStorageContents()).thenReturn(storage);
        Player recipient = mock(Player.class);
        when(recipient.getInventory()).thenReturn(inventory);
        ItemStack source = bundle(item(Material.DIAMOND, 2), item(Material.EMERALD, 1), item(Material.GOLD_INGOT, 1));
        ItemStack original = source.clone();
        assertEquals(2, ItemValue.estimateGreedyTakeValue(source, recipient, thief, 20));
        assertEquals(Material.AIR, storage[0].getType());
        assertEquals(original, source);
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    @Test
    void greedyPreviewReservesTheOnlyEmptySlotForTheFirstItemType() {
        useStorageOnlyInventory();
        fillInventory();
        player.getInventory().setItem(0, null);
        ItemStack bundle = bundle(item(Material.DIAMOND, 2), item(Material.EMERALD, 1), item(Material.GOLD_INGOT, 1));
        ItemStack sourceBefore = bundle.clone();
        ItemStack[] inventoryBefore = player.getInventory().getStorageContents();
        double preview = ItemValue.estimateGreedyTakeValue(bundle, player, thief, 20);
        assertEquals(2, preview);
        assertArrayEquals(inventoryBefore, player.getInventory().getStorageContents());
        assertEquals(sourceBefore, bundle);
        BundleTakeResult taken = ItemValue.takeFromBundle(bundle, player, thief, 20, BundleTakeMode.GREEDY);
        assertEquals(preview, taken.getValueTaken());
        assertEquals(1, inventoryAmount(Material.DIAMOND));
        assertEquals(0, inventoryAmount(Material.EMERALD));
    }

    @Test
    void greedyPreviewUsesPartialStackBeforeReservingEmptySlotForAnotherType() {
        useStorageOnlyInventory();
        fillInventory();
        player.getInventory().setItem(0, item(Material.DIAMOND, 63));
        player.getInventory().setItem(1, null);
        ItemStack bundle = bundle(item(Material.DIAMOND, 2), item(Material.EMERALD, 1), item(Material.GOLD_INGOT, 1));
        ItemStack sourceBefore = bundle.clone();
        ItemStack[] inventoryBefore = player.getInventory().getStorageContents();
        double preview = ItemValue.estimateGreedyTakeValue(bundle, player, thief, 20);
        assertEquals(5, preview);
        assertArrayEquals(inventoryBefore, player.getInventory().getStorageContents());
        assertEquals(sourceBefore, bundle);
        BundleTakeResult taken = ItemValue.takeFromBundle(bundle, player, thief, 20, BundleTakeMode.GREEDY);
        assertEquals(preview, taken.getValueTaken());
        assertEquals(64, inventoryAmount(Material.DIAMOND));
        assertEquals(1, inventoryAmount(Material.EMERALD));
        assertEquals(Map.of(Material.DIAMOND, 1, Material.GOLD_INGOT, 1), contents(taken.getUpdatedBundle()));
    }

}
