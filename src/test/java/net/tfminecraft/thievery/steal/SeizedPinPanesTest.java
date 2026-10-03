package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import net.tfminecraft.thievery.Thievery;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

class SeizedPinPanesTest {
    private ServerMock server;
    private MockedStatic<Thievery> plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Thievery instance = mock(Thievery.class);
        when(instance.getName()).thenReturn("Thievery");
        when(instance.namespace()).thenReturn("thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
    }

    @AfterEach
    void tearDown() {
        plugin.close();
        MockBukkit.unmock();
    }

    @Test
    @SuppressWarnings("deprecation")
    void countPanesShowTheNearbyPinsByColourAndStackSize() {
        assertEquals("No seized pins nearby", SeizedPinPanes.describe(0));
        assertEquals("1 seized pin nearby", SeizedPinPanes.describe(1));
        assertEquals("3 seized pins nearby", SeizedPinPanes.describe(3));
        Material[] expected = {
                Material.WHITE_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Material.LIME_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
                Material.ORANGE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE,
                Material.MAGENTA_STAINED_GLASS_PANE};
        for (int nearby = 0; nearby < expected.length; nearby++) {
            ItemStack pane = SeizedPinPanes.countPane(nearby);
            assertEquals(expected[nearby], pane.getType());
            assertEquals(Math.max(1, nearby), pane.getAmount());
            assertTrue(StealGui.isNothingPane(pane));
            assertTrue(StealGui.isNonInteractivePane(pane));
            assertTrue(pane.getItemMeta().getLore().getFirst().endsWith(SeizedPinPanes.describe(nearby)));
        }
        assertEquals("§7No seized pins nearby", SeizedPinPanes.countPane(0).getItemMeta().getLore().getFirst());
        assertEquals(Material.WHITE_STAINED_GLASS_PANE, SeizedPinPanes.countPane(-2).getType());
        assertEquals(1, SeizedPinPanes.countPane(-2).getAmount());
    }

    @Test
    @SuppressWarnings("deprecation")
    void markedAndSeizedPanesAreDistinctFromHiddenSlots() {
        ItemStack marked = SeizedPinPanes.markedPane();
        assertEquals(Material.RED_STAINED_GLASS_PANE, marked.getType());
        assertEquals("§cMarked as seized", marked.getItemMeta().getDisplayName());
        assertFalse(StealGui.isUnknownPane(marked));
        assertFalse(StealGui.isNonInteractivePane(marked));
        ItemStack seized = SeizedPinPanes.seizedPane();
        assertEquals(Material.IRON_BARS, seized.getType());
        assertEquals("§cSeized pin", seized.getItemMeta().getDisplayName());
        assertTrue(StealGui.isNonInteractivePane(seized));
    }

    @Test
    @SuppressWarnings("deprecation")
    void annotateTurnsEmptySlotsIntoCountsAndPrefixesItemLore() {
        Inventory gui = server.createInventory(null, 9);
        SeizedPinPanes.annotate(gui, 0, 2);
        assertNull(gui.getItem(0));

        gui.setItem(1, StealGui.createNothingPane());
        SeizedPinPanes.annotate(gui, 1, 2);
        assertEquals(Material.LIME_STAINED_GLASS_PANE, gui.getItem(1).getType());
        assertEquals(2, gui.getItem(1).getAmount());

        gui.setItem(2, new ItemStack(Material.DIAMOND, 3));
        SeizedPinPanes.annotate(gui, 2, 1);
        assertEquals(new ItemStack(Material.DIAMOND, 3).getType(), gui.getItem(2).getType());
        assertEquals(3, gui.getItem(2).getAmount());
        assertEquals(List.of("#d6cf691 seized pin nearby").size(), gui.getItem(2).getItemMeta().getLore().size());
        assertTrue(gui.getItem(2).getItemMeta().getLore().getFirst().endsWith("1 seized pin nearby"));

        ItemStack described = new ItemStack(Material.EMERALD);
        var meta = described.getItemMeta();
        meta.setLore(List.of("Value: 4"));
        described.setItemMeta(meta);
        gui.setItem(3, described);
        SeizedPinPanes.annotate(gui, 3, 0);
        List<String> lore = gui.getItem(3).getItemMeta().getLore();
        assertEquals(2, lore.size());
        assertEquals("§7No seized pins nearby", lore.get(0));
        assertEquals("Value: 4", lore.get(1));
    }
}
