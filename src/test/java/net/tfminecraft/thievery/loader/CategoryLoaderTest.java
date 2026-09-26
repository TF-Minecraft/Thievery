package net.tfminecraft.thievery.loader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import net.Indyuce.mmoitems.api.Type;
import net.tfminecraft.advancedcrafting.objects.stats.StatTemplate;
import net.tfminecraft.advancedcrafting.utils.ThieveryBridge;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class CategoryLoaderTest {
    @TempDir Path temp;
    private MockedStatic<Thievery> thievery;
    private MockedStatic<ThieveryBridge> bridge;
    private MockedStatic<CategoryHandler> handler;
    private Logger logger;
    private final CategoryLoader loader = new CategoryLoader();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Thievery plugin = mock(Thievery.class);
        logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        thievery = mockStatic(Thievery.class);
        thievery.when(Thievery::getInstance).thenReturn(plugin);
        bridge = mockStatic(ThieveryBridge.class);
        handler = mockStatic(CategoryHandler.class);
        CategoryLoader.get().clear();
    }

    @AfterEach
    void tearDown() {
        CategoryLoader.get().clear();
        handler.close();
        bridge.close();
        thievery.close();
        MockBukkit.unmock();
    }

    @Test
    void loadingPreservesOrderFiltersLoadoutAndSelectsFirstMoneyCategory() throws Exception {
        assertNull(CategoryLoader.getMoneyCategory());
        load("""
                tools:
                  items: [v.stick]
                coins:
                  type: money
                  loadout: false
                gems:
                  type: MONEY
                """);
        assertEquals(List.of("tools", "coins", "gems"), CategoryLoader.getAsList().stream().map(ItemCategory::getId).toList());
        assertEquals(List.of("tools", "gems"), CategoryLoader.getLoadoutCategories().stream().map(ItemCategory::getId).toList());
        assertNull(CategoryLoader.getById("absent"));
        assertSame(CategoryLoader.getById("coins"), CategoryLoader.getMoneyCategory());
        verify(logger).warning(contains("Multiple money categories"));
        assertThrows(UnsupportedOperationException.class, () -> CategoryLoader.getAsList().clear());
        load("single:\n  type: money\n");
        assertEquals(1, CategoryLoader.get().size());
        assertEquals("single", CategoryLoader.getMoneyCategory().getId());
        load("");
        assertTrue(CategoryLoader.get().isEmpty());
    }

    @Test
    void duplicatePathsAndTypesWarnAcrossDifferentCategoriesOnly() throws Exception {
        Type type = mock(Type.class);
        handler.when(() -> CategoryHandler.lookupMmoType(anyString())).thenReturn(type);
        load("""
                first:
                  items: [v.stick, m.SWORD, ac_blade_tier_1, v.paper, m.WAND]
                FIRST:
                  items: [V.STICK, M.SWORD]
                second:
                  items: [v.stick, m.SWORD, gg_rifle_tier_1, magic_staff_tier_1, ac_metal_tier_1]
                """);
        verify(logger, times(2)).severe(contains("Item path 'v.stick'"));
        verify(logger, times(2)).severe(contains("MMOItems type 'm.SWORD'"));
        verify(logger, never()).severe(contains("and 'FIRST'"));
        verify(logger, never()).warning(anyString());
    }

    @Test
    void validatesCraftTemplatesOnlyWhenPluginReadyAndWarnsUnknownMmoTypes() throws Exception {
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        StatTemplate known = mock(StatTemplate.class);
        bridge.when(() -> ThieveryBridge.getStatTemplate("known")).thenReturn(known);
        Type sword = mock(Type.class);
        handler.when(() -> CategoryHandler.lookupMmoType("SWORD")).thenReturn(sword);
        load("""
                equipment:
                  items: [ac_missing_tier_1, ac_known_tier_1, gg_rifle_tier_1, magic_staff_tier_1, m.SWORD, m.UNKNOWN, v.stick]
                """);
        verify(logger).warning(contains("unknown AC stat template 'missing'"));
        verify(logger).warning(contains("unknown MMOItems type 'UNKNOWN'"));
        verify(logger, times(2)).warning(anyString());
        bridge.verify(() -> ThieveryBridge.getStatTemplate("known"));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(false);
        clearInvocations(logger);
        load("equipment:\n  items: [ac_missing_tier_1]\n");
        verifyNoInteractions(logger);
    }

    @Test
    void lookupMethodsDelegateWithoutChangingReferencesOrWeights() {
        GgCraftRef gun = GgCraftRef.parse("gg_rifle_tier_1").orElseThrow();
        MagicCraftRef magic = MagicCraftRef.parse("magic_staff_tier_1").orElseThrow();
        AcCraftRef craft = AcCraftRef.parse("ac_blade_tier_1").orElseThrow();
        handler.when(() -> CategoryHandler.getWeightForGgRef(gun)).thenReturn(2.0);
        handler.when(() -> CategoryHandler.getWeightForMagicRef(magic)).thenReturn(3.0);
        handler.when(() -> CategoryHandler.getWeightForCraftRef(craft)).thenReturn(4.0);
        handler.when(() -> CategoryHandler.getWeightForPath("v.stick")).thenReturn(5.0);
        assertEquals(Cache.defaultItemValue, CategoryLoader.getDefaultWeight());
        assertEquals(2, CategoryLoader.getWeightForGgRef(gun));
        assertEquals(3, CategoryLoader.getWeightForMagicRef(magic));
        assertEquals(4, CategoryLoader.getWeightForCraftRef(craft));
        assertEquals(5, CategoryLoader.getWeightForPath("v.stick"));
    }

    @Test
    void unreadableAndMalformedYamlClearOldCategoriesAndReportFailure() throws Exception {
        load("old: {}\n");
        PrintStream original = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(errors)) {
            System.setErr(capture);
            loader.load(temp.resolve("missing.yml").toFile());
            assertTrue(CategoryLoader.get().isEmpty());
            load("bad: [unterminated\n");
            assertTrue(CategoryLoader.get().isEmpty());
        } finally {
            System.setErr(original);
        }
        assertTrue(errors.toString().contains("FileNotFoundException"));
        assertTrue(errors.toString().contains("InvalidConfigurationException"));
    }

    private void load(String yaml) throws Exception {
        Path file = temp.resolve("categories.yml");
        Files.writeString(file, yaml);
        loader.load(file.toFile());
    }
}
