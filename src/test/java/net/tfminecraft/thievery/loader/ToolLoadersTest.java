package net.tfminecraft.thievery.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.thievery.utils.ToolResolver;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToolLoadersTest {
    @org.junit.jupiter.api.BeforeEach void server() { org.mockbukkit.mockbukkit.MockBukkit.mock(); }
    @AfterEach void reset() {
        var empty = new YamlConfiguration();
        KeyLoader.load(empty); LockpickLoader.load(empty); KeychainLoader.load(empty); DoorLoader.load(empty);
        var copy = new YamlConfiguration();
        copy.set("key-copy.mold.input", "m.utils.clay_ball"); copy.set("key-copy.mold.output", "m.utils.key_mold");
        copy.set("key-copy.copy.input", "m.keys.copper_ingot"); copy.set("key-copy.copy.output", "m.keys.copper_key");
        copy.set("key-copy.paper.input", "m.keys.blank_paper"); copy.set("key-copy.paper.output", "m.keys.paper_key");
        KeyCopyLoader.load(copy);
        org.mockbukkit.mockbukkit.MockBukkit.unmock();
    }

    @Test void keyAndPickDefinitionsLoadDefaultsOverridesAndClearOnReload() {
        var config = new YamlConfiguration();
        config.createSection("keys.plain"); config.set("keys.strong.item", "v.iron_ingot"); config.set("keys.strong.strength", .8);
        config.createSection("lockpicks.plain"); config.set("lockpicks.strong.item", "v.stick");
        config.set("lockpicks.strong.strength", .7); config.set("lockpicks.strong.capacity", 50);
        KeyLoader.load(config); LockpickLoader.load(config);
        assertEquals(2, KeyLoader.getAsList().size()); assertEquals(2, LockpickLoader.getAsList().size());
        var plain = KeyLoader.getById("plain"); assertEquals("plain", plain.getId()); assertEquals("v.paper", plain.getItem()); assertEquals(1, plain.getStrength());
        var strong = KeyLoader.getById("strong"); assertEquals("v.iron_ingot", strong.getItem()); assertEquals(.8, strong.getStrength());
        var pick = LockpickLoader.getById("plain"); assertEquals("plain", pick.getId()); assertEquals("v.paper", pick.getItem()); assertEquals(0, pick.getStrength()); assertEquals(30, pick.getCapacity());
        var best = LockpickLoader.getById("strong"); assertEquals("v.stick", best.getItem()); assertEquals(.7, best.getStrength()); assertEquals(50, best.getCapacity());
        KeyLoader.getAsList().clear(); LockpickLoader.getAsList().clear();
        assertEquals(2, KeyLoader.getAsList().size()); assertEquals(2, LockpickLoader.getAsList().size());
        var item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.STICK);
        var air = mock(ItemStack.class); when(air.getType()).thenReturn(Material.AIR);
        try (var libs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS)) {
            assertNull(KeyLoader.resolve(null)); assertNull(KeyLoader.resolve(air)); assertNull(KeyLoader.resolve(item));
            assertNull(LockpickLoader.resolve(null)); assertNull(LockpickLoader.resolve(air)); assertNull(LockpickLoader.resolve(item));
            assertFalse(ToolResolver.isLockpick(item)); assertEquals(0, ToolResolver.getLockpickStrength(item));
            when(TLibs.getItemAPI().getChecker().checkItemWithPath(item, "v.iron_ingot")).thenReturn(true);
            when(TLibs.getItemAPI().getChecker().checkItemWithPath(item, "v.stick")).thenReturn(true);
            assertSame(strong, KeyLoader.resolve(item)); assertSame(best, LockpickLoader.resolve(item));
            assertTrue(ToolResolver.isLockpick(item)); assertEquals(.7, ToolResolver.getLockpickStrength(item));
            KeyLoader.load(new YamlConfiguration()); LockpickLoader.load(new YamlConfiguration());
            assertTrue(KeyLoader.getAsList().isEmpty()); assertTrue(LockpickLoader.getAsList().isEmpty());
            assertNull(KeyLoader.getById("plain")); assertNull(LockpickLoader.getById("plain"));
            assertNull(KeyLoader.resolve(item)); assertNull(LockpickLoader.resolve(item));
        }
    }

    @Test void keychainModelsUseFloorThresholdAndIgnoreMalformedNumbers() {
        var config = new YamlConfiguration();
        KeychainLoader.load(config);
        assertEquals("v.brick", KeychainLoader.getItemPath()); assertEquals(9, KeychainLoader.getMaxKeys());
        assertEquals(4, KeychainLoader.getLoreLineStart()); assertEquals(0, KeychainLoader.resolveModelData(100)); assertEquals(0, KeychainLoader.resolveModelData(-1));
        config.set("keychain.item", "v.paper"); config.set("keychain.max-keys", 5); config.set("keychain.lore-line-start", -2);
        config.set("keychain.model-by-count.2", 12); config.set("keychain.model-by-count.4", 14); config.set("keychain.model-by-count.invalid", 99);
        KeychainLoader.load(config);
        assertEquals("v.paper", KeychainLoader.getItemPath()); assertEquals(5, KeychainLoader.getMaxKeys()); assertEquals(1, KeychainLoader.getLoreLineStart());
        assertEquals(0, KeychainLoader.resolveModelData(1)); assertEquals(12, KeychainLoader.resolveModelData(2)); assertEquals(12, KeychainLoader.resolveModelData(3)); assertEquals(14, KeychainLoader.resolveModelData(9));
        var item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.PAPER);
        var air = mock(ItemStack.class); when(air.getType()).thenReturn(Material.AIR);
        try (var libs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS)) {
            assertFalse(KeychainLoader.matchesItem(null)); assertFalse(KeychainLoader.matchesItem(air)); assertFalse(KeychainLoader.matchesItem(item));
            when(TLibs.getItemAPI().getChecker().checkItemWithPath(item, "v.paper")).thenReturn(true);
            assertTrue(KeychainLoader.matchesItem(item));
        }
    }

    @Test void copyPathsAndDebugToolRejectEmptyItemsAndDelegateConfiguredMatches() {
        var config = new YamlConfiguration();
        config.set("key-copy.paper-cooldown-minutes", 12);
        for (String kind : new String[]{"mold", "copy", "paper"}) for (String io : new String[]{"input", "output"}) config.set("key-copy." + kind + "." + io, kind + io);
        config.set("doors.debug-tool", "v.stick");
        KeyCopyLoader.load(config); DoorLoader.load(config);
        assertEquals(12, KeyCopyLoader.getPaperCooldownMinutes()); assertEquals("v.stick", DoorLoader.getDebugToolPath());
        assertEquals("moldinput", KeyCopyLoader.getMoldInput()); assertEquals("moldoutput", KeyCopyLoader.getMoldOutput());
        assertEquals("copyinput", KeyCopyLoader.getCopyInput()); assertEquals("copyoutput", KeyCopyLoader.getCopyOutput());
        assertEquals("paperinput", KeyCopyLoader.getPaperInput()); assertEquals("paperoutput", KeyCopyLoader.getPaperOutput());
        var item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.STICK);
        var air = mock(ItemStack.class); when(air.getType()).thenReturn(Material.AIR);
        try (var libs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS)) {
            assertFalse(KeyCopyLoader.matchesMoldInput(null)); assertFalse(KeyCopyLoader.matchesMoldInput(air)); assertFalse(KeyCopyLoader.matchesMoldInput(item));
            assertFalse(ToolResolver.isDebugTool(null)); assertFalse(ToolResolver.isDebugTool(air)); assertFalse(ToolResolver.isDebugTool(item));
            when(TLibs.getItemAPI().getChecker().checkItemWithPath(eq(item), anyString())).thenReturn(true);
            assertTrue(KeyCopyLoader.matchesMoldInput(item)); assertTrue(KeyCopyLoader.matchesMoldOutput(item));
            assertTrue(KeyCopyLoader.matchesCopyInput(item)); assertTrue(KeyCopyLoader.matchesCopyOutput(item));
            assertTrue(KeyCopyLoader.matchesPaperInput(item)); assertTrue(KeyCopyLoader.matchesPaperOutput(item)); assertTrue(ToolResolver.isDebugTool(item));
            config.set("key-copy.mold.input", " "); config.set("doors.debug-tool", " ");
            KeyCopyLoader.load(config); DoorLoader.load(config);
            assertFalse(KeyCopyLoader.matchesMoldInput(item)); assertFalse(DoorLoader.matchesDebugTool(item));
        }
    }
}
