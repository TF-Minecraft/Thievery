package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.CategoryHandler;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.utils.Keys;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

class StealItemDisplayTest {
    private ServerMock server;
    private PlayerData data;
    private MockedStatic<Thievery> thievery;
    private MockedStatic<CategoryHandler> categories;
    private MockedStatic<ClueGiver> clues;
    private int originalMinimum;

    @BeforeEach void setUp() {
        server = MockBukkit.mock(); data = new PlayerData(UUID.randomUUID());
        Thievery plugin = mock(Thievery.class); when(plugin.namespace()).thenReturn("thievery");
        thievery = mockStatic(Thievery.class); thievery.when(Thievery::getInstance).thenReturn(plugin);
        categories = mockStatic(CategoryHandler.class);
        categories.when(() -> CategoryHandler.canRevealItem(eq(data), any())).thenReturn(true);
        categories.when(() -> CategoryHandler.getPerItemValue(any())).thenReturn(2.0);
        categories.when(() -> CategoryHandler.getTotalValue(any())).thenAnswer(inv -> ((ItemStack)inv.getArgument(0)).getAmount() * 2.0);
        clues = mockStatic(ClueGiver.class);
        originalMinimum = Cache.minCluesContainer;
    }
    @AfterEach void tearDown() {
        Cache.minCluesContainer = originalMinimum;
        for (MockedStatic<?> mocked : new MockedStatic<?>[]{clues, categories, thievery}) if (mocked != null) mocked.close();
        MockBukkit.unmock();
    }

    @Test void displayAmountsDistinguishHiddenUnaffordableAndPartlyAffordableStacks() {
        assertEquals(0, StealItemDisplay.computeDisplayAmount(null, 10, data));
        assertEquals(0, StealItemDisplay.computeDisplayAmount(new ItemStack(Material.AIR), 10, data));
        ItemStack stack = new ItemStack(Material.STICK, 8);
        assertEquals(0, StealItemDisplay.computeDisplayAmount(stack, 1, data));
        assertEquals(8, StealItemDisplay.computeDisplayAmount(stack, 2, data));
        categories.when(() -> CategoryHandler.canRevealItem(data, stack)).thenReturn(false);
        assertEquals(0, StealItemDisplay.computeDisplayAmount(stack, 100, data));
        assertTrue(StealGui.isHiddenPane(StealItemDisplay.buildRepresentation(stack, 100, data)));
    }

    @Test void ordinaryRepresentationPreservesOriginalLoreAndShowsBudgetLimitWithoutReducingStack() {
        ItemStack stack = new ItemStack(Material.STICK, 8);
        var meta = stack.getItemMeta(); meta.setLore(List.of("Original description")); stack.setItemMeta(meta);
        ItemStack display = StealItemDisplay.buildRepresentation(stack, 5, data);
        assertNotSame(stack, display); assertEquals(8, display.getAmount());
        assertEquals(List.of("Original description"), stack.getItemMeta().getLore());
        List<String> lore = plain(display);
        assertEquals("Original description", lore.getFirst());
        assertTrue(lore.contains("Total Value: 16.00"));
        assertTrue(lore.contains("Can take up to 2 with current budget"));
        assertTrue(lore.contains("Click to take one")); assertTrue(lore.contains("Shift-Click to take all"));
        assertNull(StealItemDisplay.buildRepresentation(stack, 1, data));
        ItemStack single = new ItemStack(Material.STICK);
        List<String> singleLore = plain(StealItemDisplay.buildRepresentation(single, 10, data));
        assertFalse(singleLore.stream().anyMatch(s -> s.startsWith("Can take")));
        assertFalse(singleLore.stream().anyMatch(s -> s.startsWith("Shift-Click")));
    }

    @Test void bundleDisplayUsesOneSlotAndListsOnlyRevealableContents() {
        ItemStack inner = new ItemStack(Material.STICK, 2);
        ItemStack concealed = new ItemStack(Material.DIAMOND);
        categories.when(() -> CategoryHandler.canRevealItem(data, concealed)).thenReturn(false);
        ItemStack bundle = bundle(List.of(inner, concealed));
        assertEquals(1, StealItemDisplay.computeDisplayAmount(bundle, 2, data));
        ItemStack display = StealItemDisplay.buildRepresentation(bundle, 2, data);
        assertEquals(List.of(inner), ((BundleMeta)display.getItemMeta()).getItems());
        assertEquals(2, ((BundleMeta)bundle.getItemMeta()).getItems().size());
        List<String> lore = plain(display);
        assertTrue(lore.contains("Items:"));
        assertTrue(lore.stream().anyMatch(s -> s.contains("Stick")));
        assertFalse(lore.stream().anyMatch(s -> s.contains("Diamond")));
        assertTrue(lore.contains("Click to take one item")); assertTrue(lore.contains("Shift-Click to take all you can"));
        ItemStack empty = bundle(List.of());
        assertEquals(1, StealItemDisplay.computeDisplayAmount(empty, 2, data));
        assertEquals(0, StealItemDisplay.computeDisplayAmount(empty, 1, data));
        assertFalse(plain(StealItemDisplay.buildRepresentation(empty, 2, data)).contains("Items:"));
    }

    @Test void cluePreviewShowsGuaranteedFirstTakeAndSeparateAllChanceOnlyForLargerTake() {
        Cache.minCluesContainer = 1;
        var player = server.addPlayer();
        ItemStack stack = new ItemStack(Material.STICK, 3);
        var first = new StealItemDisplay.ChestCluePreviewContext(player, 0, 0, 0.2, 0, true);
        List<String> lore = plain(StealItemDisplay.buildRepresentation(stack, 10, data, first));
        assertTrue(lore.contains("First Take - Guaranteed Clue"));
        assertTrue(lore.stream().anyMatch(s -> s.startsWith("Clue chance: 100%")));
        assertTrue(lore.stream().anyMatch(s -> s.startsWith("Clue chance (all):")));
        var later = new StealItemDisplay.ChestCluePreviewContext(player, 0, 0, 0.2, 1, false);
        List<String> single = plain(StealItemDisplay.buildRepresentation(new ItemStack(Material.STICK), 10, data, later));
        assertFalse(single.contains("First Take - Guaranteed Clue"));
        assertEquals(1, single.stream().filter(s -> s.startsWith("Clue chance")).count());
        assertTrue(single.stream().anyMatch(s -> s.endsWith("(critical 0%)")));
    }

    @Test void onlyTheFourGuiMarkerTagsIdentifyStealPanes() {
        assertFalse(StealItemDisplay.isStealPane(null));
        assertFalse(StealItemDisplay.isStealPane(new ItemStack(Material.AIR)));
        assertFalse(StealItemDisplay.isStealPane(new ItemStack(Material.STICK)));
        for (var key : List.of(Keys.stealUnknown, Keys.stealFiller, Keys.stealNothing, Keys.stealHidden)) {
            ItemStack pane = new ItemStack(Material.PAPER); var meta = pane.getItemMeta();
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte)1); pane.setItemMeta(meta);
            assertTrue(StealItemDisplay.isStealPane(pane));
        }
        ItemStack ordinary = new ItemStack(Material.PAPER); var meta = ordinary.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.categoryId, PersistentDataType.STRING, "tools"); ordinary.setItemMeta(meta);
        assertFalse(StealItemDisplay.isStealPane(ordinary));
    }

    @Test void valuesRoundToTwoDecimalsAndTimersClampAndPadSeconds() {
        assertEquals("0.00", StealItemDisplay.formatValue(0));
        assertEquals("1.24", StealItemDisplay.formatValue(1.236));
        assertEquals("0:00", StealItemDisplay.formatTimeRemaining(-1000));
        assertEquals("0:00", StealItemDisplay.formatTimeRemaining(999));
        assertEquals("1:01", StealItemDisplay.formatTimeRemaining(61999));
        assertEquals("60:00", StealItemDisplay.formatTimeRemaining(3600000));
    }

    private ItemStack bundle(List<ItemStack> items) {
        ItemStack bundle = new ItemStack(Material.BUNDLE); BundleMeta meta = (BundleMeta)bundle.getItemMeta();
        meta.setItems(items); bundle.setItemMeta(meta); return bundle;
    }
    @SuppressWarnings("deprecation")
    private List<String> plain(ItemStack item) { return item.getItemMeta().getLore().stream().map(ChatColor::stripColor).toList(); }
}
