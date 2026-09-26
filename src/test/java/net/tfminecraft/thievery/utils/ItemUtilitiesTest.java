package net.tfminecraft.thievery.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.thievery.steal.StealIgnoreRules;
import net.tfminecraft.thievery.util.LegacyModelData;
import org.bukkit.Material;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class ItemUtilitiesTest {
    List<String> ignored;
    @BeforeEach void setup() { MockBukkit.mock(); ignored=new ArrayList<>(StealIgnoreRules.getNameContains()); }
    @AfterEach void close() { StealIgnoreRules.load(ignored); MockBukkit.unmock(); }
    @Test void ignoresConfiguredFragmentsInNamesAndLoreWithoutCaseFolding() {
        var config=new ArrayList<>(Arrays.asList(null," ","Slot")); StealIgnoreRules.load(config); config.clear();
        assertEquals(Arrays.asList(null," ","Slot"),StealIgnoreRules.getNameContains()); assertThrows(UnsupportedOperationException.class,()->StealIgnoreRules.getNameContains().clear());
        assertFalse(StealIgnoreRules.isIgnored(null)); assertFalse(StealIgnoreRules.isIgnored(new ItemStack(Material.AIR)));
        var plain=mock(ItemStack.class); when(plain.getType()).thenReturn(Material.STONE); assertFalse(StealIgnoreRules.isIgnored(plain));
        var item=new ItemStack(Material.STONE); assertFalse(StealIgnoreRules.isIgnored(item));
        var meta=item.getItemMeta(); meta.setDisplayName("Slot 1"); item.setItemMeta(meta); assertTrue(StealIgnoreRules.isIgnored(item));
        meta.setDisplayName("slot 1"); item.setItemMeta(meta); assertFalse(StealIgnoreRules.isIgnored(item));
        meta.setLore(List.of("Reserved Slot")); item.setItemMeta(meta); assertTrue(StealIgnoreRules.isIgnored(item));
        StealIgnoreRules.load(null); assertTrue(StealIgnoreRules.getNameContains().isEmpty()); assertFalse(StealIgnoreRules.isIgnored(item));
    }
    @Test void legacyModelSetterReplacesEntireComponentAndNullClearsIt() {
        var meta=mock(org.bukkit.inventory.meta.ItemMeta.class);
        var component=mock(org.bukkit.inventory.meta.components.CustomModelDataComponent.class);
        when(meta.getCustomModelDataComponent()).thenReturn(component);
        when(component.getFloats()).thenReturn(List.of());
        assertFalse(LegacyModelData.has(meta)); assertThrows(IllegalStateException.class,()->LegacyModelData.get(meta));
        when(component.getFloats()).thenReturn(List.of(42f,99f));
        assertTrue(LegacyModelData.has(meta)); assertEquals(42,LegacyModelData.get(meta));
        LegacyModelData.set(meta,42);
        verify(component).setFloats(List.of(42f)); verify(component).setFlags(List.of());
        verify(component).setStrings(List.of()); verify(component).setColors(List.of());
        verify(meta).setCustomModelDataComponent(component);
        LegacyModelData.set(meta,null); verify(meta).setCustomModelDataComponent(null);
    }
    @Test void chatAndGuiFormattingKeepTheirDifferentColorContracts() {
        assertNull(ThieveryTexts.formatDisplay(null)); assertEquals("",ThieveryTexts.formatDisplay(""));
        assertNull(ThieveryTexts.formatGui(null)); assertEquals("",ThieveryTexts.formatGui(""));
        assertEquals("§aHello #ffffff",ThieveryTexts.msg("&aHello #ffffff"));
        assertEquals("§x§f§f§f§f§f§fHello",ThieveryTexts.gui("#ffffffHello"));
    }
}
