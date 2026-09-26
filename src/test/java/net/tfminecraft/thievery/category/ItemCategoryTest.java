package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.logging.Logger;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.utils.Keys;
import net.tfminecraft.tlibs.TLibs;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class ItemCategoryTest {
    private MockedStatic<Thievery> thievery;
    private Logger logger;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Thievery plugin = mock(Thievery.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getName()).thenReturn("Thievery");
        when(plugin.namespace()).thenReturn("thievery");
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getInstance).thenReturn(plugin);
    }

    @AfterEach
    void tearDown() {
        thievery.close();
        MockBukkit.unmock();
    }

    @Test
    void defaultsUseCategoryIdAndValue() {
        ItemCategory category = new ItemCategory("tools", new YamlConfiguration());
        assertEquals("tools", category.getId());
        assertEquals("tools", category.getName());
        assertEquals("v.paper", category.getIcon());
        assertEquals(1, category.getCost());
        assertEquals(1, category.getValue());
        assertEquals("", category.getType());
        assertFalse(category.isMoneyType());
        assertEquals(1, category.getAmountPerMoney());
        assertTrue(category.isLoadoutVisible());
        assertTrue(category.getItems().isEmpty());
    }

    @Test
    void configOverridesDefaultsAndParsesWeightsWithFallback() {
        var config = new YamlConfiguration();
        config.set("name", "&aCurrency");
        config.set("icon", "v.gold_ingot");
        config.set("cost", 7);
        config.set("value", 2.5);
        config.set("type", "MoNeY");
        config.set("amount_per_money", 0.25);
        config.set("loadout", false);
        config.set("match", "deprecated");
        config.set("items", List.of(" ", " v.paper ", "v.gold_ingot 4.25", "v.stick invalid", "v.iron_ingot 3 ignored"));
        ItemCategory category = new ItemCategory("coins", config);
        assertEquals("§aCurrency", category.getName());
        assertEquals("v.gold_ingot", category.getIcon());
        assertEquals(7, category.getCost());
        assertEquals(2.5, category.getValue());
        assertEquals("MoNeY", category.getType());
        assertTrue(category.isMoneyType());
        assertEquals(0.25, category.getAmountPerMoney());
        assertFalse(category.isLoadoutVisible());
        assertEquals(List.of("v.paper", "v.gold_ingot", "v.stick", "v.iron_ingot"),
                category.getItems().stream().map(ItemCategory.CategoryItemEntry::getSlug).toList());
        assertEquals(List.of(2.5, 4.25, 2.5, 3.0),
                category.getItems().stream().map(ItemCategory.CategoryItemEntry::getWeight).toList());
        assertThrows(UnsupportedOperationException.class, () -> category.getItems().clear());
        verify(logger).severe(contains("uses deprecated 'match'"));
    }

    @Test
    void iconHandlesMissingAndMetadataFreeItems() {
        ItemCategory category = new ItemCategory("tools", new YamlConfiguration());
        try (var libs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS)) {
            var creator = TLibs.getItemAPI().getCreator();
            when(creator.getItemFromPath("v.paper")).thenReturn(null);
            assertNull(category.getIconItem(false));
            ItemStack original = mock(ItemStack.class);
            ItemStack clone = mock(ItemStack.class);
            when(original.clone()).thenReturn(clone);
            when(creator.getItemFromPath("v.paper")).thenReturn(original);
            assertSame(clone, category.getIconItem(true));
            verify(clone, never()).setItemMeta(any());
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void iconClonesTemplateAndAddsNameCategoryTagDisplayLinesAndActiveState() {
        var config = new YamlConfiguration();
        config.set("name", "Tools");
        config.set("cost", 3);
        ItemCategory category = new ItemCategory("tools", config);
        ItemStack original = new ItemStack(Material.PAPER);
        try (var libs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS);
                var handler = mockStatic(CategoryHandler.class)) {
            when(TLibs.getItemAPI().getCreator().getItemFromPath("v.paper")).thenReturn(original);
            handler.when(() -> CategoryHandler.buildDisplayLines(category)).thenReturn(List.of("A tool", "Another tool"));
            ItemStack active = category.getIconItem(true);
            ItemStack inactive = category.getIconItem(false);
            assertNotSame(original, active);
            assertFalse(original.getItemMeta().hasDisplayName());
            assertFalse(original.getItemMeta().getPersistentDataContainer().has(Keys.categoryId));
            assertEquals("Tools", active.getItemMeta().getDisplayName());
            assertEquals("tools", active.getItemMeta().getPersistentDataContainer().get(Keys.categoryId, PersistentDataType.STRING));
            List<String> lore = active.getItemMeta().getLore();
            assertNotNull(lore);
            assertEquals(9, lore.size());
            assertEquals(List.of("A tool", "Another tool"), lore.subList(4, 6));
            assertTrue(lore.getFirst().contains("Cost: "));
            assertTrue(lore.getLast().endsWith("Active"));
            assertTrue(inactive.getItemMeta().getLore().getLast().endsWith("Inactive"));
        }
    }
}
