package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.stat.data.GemSocketsData;
import net.Indyuce.mmoitems.stat.data.GemstoneData;
import net.tfminecraft.advancedcrafting.managers.AlloyManager;
import net.tfminecraft.advancedcrafting.objects.alloys.Alloy;
import net.tfminecraft.advancedcrafting.objects.crafting.CraftingRecipe;
import net.tfminecraft.advancedcrafting.objects.crafting.Quality;
import net.tfminecraft.advancedcrafting.objects.data.AlloyRecipe;
import net.tfminecraft.advancedcrafting.objects.data.CraftInput;
import net.tfminecraft.advancedcrafting.objects.data.CraftProvenance;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.advancedcrafting.utils.ThieveryBridge;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.tlibs.socket.GemSocketsNbtEditor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class ItemValueTest {
    private final List<MockedStatic<?>> mocks = new ArrayList<>();
    private final Map<Field, Map<Object, Object>> tables = new HashMap<>();
    private MockedStatic<CategoryHandler> categories;
    private MockedStatic<CategoryLoader> loader;
    private MockedStatic<ThieveryBridge> bridge;
    private MockedStatic<MagicCraftRef> magic;
    private MockedStatic<ClueChecker> clues;
    private MockedStatic<DenarMoney> money;
    private MockedStatic<GemSocketsNbtEditor> sockets;
    private MockedStatic<AlloyManager> alloys;
    private NBTItem nbt;
    private ItemAPI api;
    private Locale previousLocale;
    private double previousDefault;

    private <T> MockedStatic<T> statics(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        mocks.add(mocked);
        return mocked;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockBukkit.mock();
        // Turkish case rules turn "INGREDIENT" into "ingredıent" unless comparisons use Locale.ROOT.
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        previousDefault = Cache.defaultItemValue;
        Cache.defaultItemValue = 0.1;
        for (String name : List.of("QUALITY_PERCENT", "AURA_PERCENT", "AURA_MINS")) {
            Field field = Cache.class.getDeclaredField(name);
            field.setAccessible(true);
            tables.put(field, new HashMap<>((Map<Object, Object>) field.get(null)));
        }
        Cache.putDefaultItemValueTables();
        categories = statics(CategoryHandler.class);
        categories.when(() -> CategoryHandler.resolveItemWeight(any())).thenReturn(0.1);
        loader = statics(CategoryLoader.class);
        bridge = statics(ThieveryBridge.class);
        magic = statics(MagicCraftRef.class);
        clues = statics(ClueChecker.class);
        money = statics(DenarMoney.class);
        sockets = statics(GemSocketsNbtEditor.class);
        alloys = statics(AlloyManager.class);
        nbt = mock(NBTItem.class);
        statics(NBTItem.class).when(() -> NBTItem.get(any(ItemStack.class))).thenReturn(nbt);
        api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        when(api.getCreator().getItemFromPath(anyString())).thenReturn(null);
        statics(TLibs.class).when(TLibs::getItemAPI).thenReturn(api);
        when(api.getChecker().getAsStringPath(any())).thenAnswer(call -> "v." + ((ItemStack) call.getArgument(0)).getType().name().toLowerCase(Locale.ROOT));
        statics(StringFormatter.class).when(() -> StringFormatter.getName(any(ItemStack.class)))
                .thenAnswer(call -> ((ItemStack) call.getArgument(0)).getType().name());
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void tearDown() throws Exception {
        for (int i = mocks.size() - 1; i >= 0; i--) mocks.get(i).close();
        for (Map.Entry<Field, Map<Object, Object>> entry : tables.entrySet()) {
            Map<Object, Object> table = (Map<Object, Object>) entry.getKey().get(null);
            table.clear();
            table.putAll(entry.getValue());
        }
        Cache.defaultItemValue = previousDefault;
        Locale.setDefault(previousLocale);
        MockBukkit.unmock();
    }

    private Ingredient ingredient(String id, Material type, double value) {
        Ingredient ingredient = mock(Ingredient.class);
        String path = "v." + type.name().toLowerCase(Locale.ROOT);
        when(ingredient.getPath()).thenReturn(path);
        ItemStack item = new ItemStack(type);
        when(api.getCreator().getItemFromPath(path)).thenReturn(item);
        categories.when(() -> CategoryHandler.resolveItemWeight(item)).thenReturn(value);
        bridge.when(() -> ThieveryBridge.getIngredientById(id)).thenReturn(ingredient);
        return ingredient;
    }

    private Alloy alloy(String id, String base, String... catalysts) {
        Alloy alloy = mock(Alloy.class, RETURNS_DEEP_STUBS);
        when(alloy.getId()).thenReturn(id);
        when(alloy.getData().getRecipe()).thenReturn(new AlloyRecipe(base, List.of(catalysts)));
        alloys.when(() -> AlloyManager.getAlloyById(id)).thenReturn(alloy);
        return alloy;
    }

    private CraftProvenance provenance(String quality, CraftInput... inputs) {
        return new CraftProvenance("recipe", quality, List.of(inputs), 1);
    }

    private String report(ItemStack item) {
        return String.join("\n", ItemValue.buildReport(item)).replaceAll("§.", "");
    }

    @Test
    void uncategorizedCraftReferencesStillExplainTheirConfiguredWeight() {
        ItemStack gun = new ItemStack(Material.CROSSBOW);
        GgCraftRef gunRef = GgCraftRef.parse("gg_rifle_tier_2").orElseThrow();
        categories.when(() -> CategoryHandler.resolveGgMatch(gun)).thenReturn(gunRef);
        categories.when(() -> CategoryHandler.resolveItemWeight(gun)).thenReturn(3.0);
        assertTrue(report(gun).contains("Craft: gg_rifle_tier_2 (rifle tier 2)"));
        assertTrue(report(gun).replaceAll(" +", " ").contains("Category base 3.00"));

        magic.when(() -> MagicCraftRef.parse("magic_staff_tier_2")).thenCallRealMethod();
        MagicCraftRef staffRef = MagicCraftRef.parse("magic_staff_tier_2").orElseThrow();
        ItemStack staff = new ItemStack(Material.STICK);
        categories.when(() -> CategoryHandler.resolveMagicMatch(staff)).thenReturn(staffRef);
        categories.when(() -> CategoryHandler.resolveItemWeight(staff)).thenReturn(4.0);
        magic.when(() -> MagicCraftRef.fromItem(staff)).thenReturn(Optional.of(staffRef));
        assertTrue(report(staff).contains("Craft: magic_staff_tier_2 (staff tier 2)"));
        assertTrue(report(staff).replaceAll(" +", " ").contains("Category base 4.00"));
    }

    @Test
    void legacyAlloysWithoutDataOrRecipeProduceAnHonestZeroCompositionReport() {
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        ItemStack item = new ItemStack(Material.IRON_INGOT);
        var legacyData = new com.google.gson.Gson().fromJson("{}",
                net.tfminecraft.advancedcrafting.objects.data.AlloyData.class);
        for (Alloy alloy : List.of(new Alloy("orphan", "Orphan", null),
                new Alloy("legacy", "Legacy", legacyData))) {
            bridge.when(() -> ThieveryBridge.resolveAlloy(item)).thenReturn(alloy);
            String text = report(item);
            assertTrue(text.contains("Alloy: " + alloy.getId()));
            assertTrue(text.contains("Per item: 0.00"));
            assertEquals(0, ItemValue.compute(item));
        }
    }

    @Test
    void emptyAndClueItemsHaveNoValueWhileMoneyUsesDenominationValue() {
        ItemStack item = new ItemStack(Material.PAPER);
        assertEquals(0, ItemValue.compute(null));
        assertEquals(0, ItemValue.compute(new ItemStack(Material.AIR)));
        clues.when(() -> ClueChecker.isClueItem(item)).thenReturn(true);
        assertEquals(0, ItemValue.compute(item));
        clues.when(() -> ClueChecker.isClueItem(item)).thenReturn(false);
        money.when(() -> DenarMoney.isMoney(item)).thenReturn(true);
        money.when(() -> DenarMoney.stealPerItem(item)).thenReturn(2.5);
        assertEquals(2.5, ItemValue.compute(item));
        bridge.verifyNoInteractions();
    }

    @Test
    void uncategorizedAndMatchedItemsUseTheirPerItemCategoryWeight() {
        ItemStack item = new ItemStack(Material.DIAMOND, 8);
        assertEquals(0.1, ItemValue.compute(item));
        categories.when(() -> CategoryHandler.resolveItemWeight(item)).thenReturn(4.0);
        assertEquals(4, ItemValue.compute(item));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        assertEquals(4, ItemValue.compute(item));
        assertFalse(ItemValue.usesCompositionValue(item));
    }

    @Test
    void ingredientWeightFallsBackForMissingDefinitionsPathsAndUnavailableItems() {
        assertEquals(0.1, ItemValue.categoryWeightForIngredient(null));
        Ingredient incomplete = mock(Ingredient.class);
        assertEquals(0.1, ItemValue.categoryWeightForIngredient(incomplete));
        when(incomplete.getPath()).thenReturn(" \t ");
        assertEquals(0.1, ItemValue.categoryWeightForIngredient(incomplete));
        when(incomplete.getPath()).thenReturn("m.material.missing");
        assertEquals(0.1, ItemValue.categoryWeightForIngredient(incomplete));
        Ingredient known = ingredient("iron", Material.IRON_INGOT, 2);
        assertEquals(2, ItemValue.categoryWeightForIngredient(known));
    }

    @Test
    void alloyValueSumsKnownBaseAndCatalystsAndMissingRecipeIsWorthZero() {
        assertEquals(0, ItemValue.compositionForAlloy(null));
        Alloy incomplete = mock(Alloy.class);
        assertEquals(0, ItemValue.compositionForAlloy(incomplete));
        Alloy recipeMissing = mock(Alloy.class, RETURNS_DEEP_STUBS);
        when(recipeMissing.getData().getRecipe()).thenReturn(null);
        assertEquals(0, ItemValue.compositionForAlloy(recipeMissing));
        ingredient("iron", Material.IRON_INGOT, 2);
        ingredient("gold", Material.GOLD_INGOT, 3);
        Alloy mixed = alloy("mixed", "iron", "gold", "missing");
        assertEquals(5, ItemValue.compositionForAlloy(mixed));
        assertEquals(3, ItemValue.compositionForAlloy(alloy("no-base", "missing", "gold")));
        ItemStack item = new ItemStack(Material.COPPER_INGOT);
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        bridge.when(() -> ThieveryBridge.resolveAlloy(item)).thenReturn(mixed);
        assertTrue(ItemValue.usesCompositionValue(item));
        assertEquals(5, ItemValue.compute(item));
        String report = report(item);
        assertTrue(report.contains("Type: Alloy"));
        assertTrue(report.contains("Alloy: mixed"));
        assertTrue(report.contains("Base iron"));
        assertTrue(report.contains("Catalyst gold"));
        assertTrue(report.contains("Per item: 5.00"));
    }

    @Test
    void alloyReportRecalculatesWhenItsBaseIngredientDefinitionIsRemoved() {
        ingredient("iron", Material.IRON_INGOT, 2);
        ingredient("gold", Material.GOLD_INGOT, 3);
        Alloy mixed = alloy("legacy-mixed", "iron", "gold");
        ItemStack item = new ItemStack(Material.COPPER_INGOT);
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        bridge.when(() -> ThieveryBridge.resolveAlloy(item)).thenReturn(mixed);
        assertEquals(5, ItemValue.compute(item));
        assertTrue(report(item).contains("Base iron"));

        bridge.when(() -> ThieveryBridge.getIngredientById("iron")).thenReturn(null);

        assertEquals(3, ItemValue.compositionForAlloy(mixed));
        assertEquals(3, ItemValue.compute(item));
        String report = report(item);
        assertTrue(report.contains("Alloy: legacy-mixed"));
        assertTrue(report.contains("Catalyst gold"));
        assertTrue(report.contains("Per item: 3.00"));
        assertFalse(report.contains("Base iron"));
        assertFalse(report.contains("Per item: 5.00"));
    }

    @Test
    void provenanceSumsAmountsOfIngredientsAndAlloysAndIgnoresUnknownInputs() {
        ingredient("iron", Material.IRON_INGOT, 2);
        ingredient("gold", Material.GOLD_INGOT, 3);
        alloy("mixed", "iron", "gold");
        CraftProvenance provenance = provenance("tempered",
                new CraftInput("INGREDIENT", "iron", 3, 1),
                new CraftInput("alloy", "mixed", 2, 1),
                new CraftInput("ingredient", "missing", 99, 1),
                new CraftInput("alloy", "missing", 99, 1),
                new CraftInput("future-kind", "iron", 99, 1));
        assertEquals(16, ItemValue.compositionForProvenance(provenance));
        assertEquals(0, ItemValue.compositionForProvenance(null));
        assertEquals(0, ItemValue.compositionForProvenance(provenance("tempered")));
        CraftProvenance legacy = mock(CraftProvenance.class);
        when(legacy.getInputs()).thenReturn(null);
        assertEquals(0, ItemValue.compositionForProvenance(legacy));
    }

    @Test
    void craftedValueReplacesCategoryWeightAndAppliesQualityToMaterials() {
        ingredient("iron", Material.IRON_INGOT, 2);
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        categories.when(() -> CategoryHandler.resolveItemWeight(sword)).thenReturn(99.0);
        ItemCategory category = mock(ItemCategory.class);
        when(category.getId()).thenReturn("weapons");
        categories.when(() -> CategoryHandler.resolveCategory(sword)).thenReturn(category);
        CraftProvenance provenance = provenance("polished", new CraftInput("ingredient", "iron", 3, 1));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        bridge.when(() -> ThieveryBridge.readProvenance(sword)).thenReturn(provenance);
        assertTrue(ItemValue.usesCompositionValue(sword));
        assertEquals(7.5, ItemValue.compute(sword));
        CraftingRecipe recipe = mock(CraftingRecipe.class);
        when(recipe.getCategoryId()).thenReturn("weapons");
        bridge.when(() -> ThieveryBridge.getRecipeById("recipe")).thenReturn(recipe);
        Quality quality = mock(Quality.class);
        when(quality.getName()).thenReturn("Polished steel");
        bridge.when(() -> ThieveryBridge.getQualityById("polished")).thenReturn(quality);
        String report = report(sword);
        assertTrue(report.contains("Type: Crafted"));
        assertTrue(report.contains("Slug weight ignored"));
        assertTrue(report.contains("Category: weapons"));
        assertTrue(report.contains("Recipe: recipe (weapons)"));
        assertTrue(report.contains("iron ×3"));
        assertTrue(report.contains("Quality: Polished steel  →  +25%"));
        assertTrue(report.contains("Quality extra  →  1.50"));
        assertTrue(report.contains("Per item: 7.50"));
    }

    @Test
    void uncategorizedCraftedItemsReportTheirReferenceAndMaterialValue() {
        ingredient("iron", Material.IRON_INGOT, 2);
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        CraftProvenance provenance = provenance("polished", new CraftInput("ingredient", "iron", 3, 2));
        CraftingRecipe recipe = mock(CraftingRecipe.class);
        when(recipe.getStatTemplateId()).thenReturn("swords");
        when(recipe.getCategoryId()).thenReturn("weapons");
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        bridge.when(() -> ThieveryBridge.readProvenance(sword)).thenReturn(provenance);
        bridge.when(() -> ThieveryBridge.getRecipeById("recipe")).thenReturn(recipe);
        bridge.when(() -> ThieveryBridge.resolveMajorityTier(recipe, provenance.getInputs())).thenReturn(2);
        categories.when(() -> CategoryHandler.resolveCraftedMatch(sword)).thenCallRealMethod();

        String text = report(sword);
        assertTrue(text.contains("Categories: none (uncategorized)"));
        assertTrue(text.contains("Craft: ac_swords_tier_2 (swords tier 2)"));
        assertTrue(text.contains("Slug weight ignored"));
        assertTrue(text.contains("Per item: 7.50"));
        assertEquals(7.5, ItemValue.compute(sword));
    }

    @Test
    void craftedReportHandlesAlloyInputsUnknownDefinitionsAndNegativeFractionalQuality() {
        ingredient("iron", Material.IRON_INGOT, 4);
        alloy("steel", "iron");
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        Cache.putQualityPercent("damaged", -0.125);
        CraftProvenance provenance = provenance("damaged", new CraftInput("alloy", "steel", 2, 1),
                new CraftInput("alloy", "missing", 1, 1), new CraftInput("ingredient", "missing", 1, 1),
                new CraftInput("future", "unknown", 1, 1));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        bridge.when(() -> ThieveryBridge.readProvenance(item)).thenReturn(provenance);
        String report = report(item);
        assertTrue(report.contains("Alloy steel ×2"));
        assertTrue(report.contains("Quality: damaged  →  -12.5%"));
        assertTrue(report.contains("Per item: 7.00"));
        bridge.when(() -> ThieveryBridge.readProvenance(item)).thenReturn(provenance("tempered", new CraftInput("alloy", "steel", 1, 1)));
        assertFalse(report(item).contains("Quality extra"));
        assertEquals(4, ItemValue.compute(item));
    }

    @Test
    void magicUsesCategoryAndAuraInsteadOfAdvancedCraftingComposition() {
        ItemStack item = new ItemStack(Material.STICK);
        MagicCraftRef ref = mock(MagicCraftRef.class);
        when(ref.getRawId()).thenReturn("magic.wand.2");
        when(ref.getGearType()).thenReturn("wand");
        when(ref.getTier()).thenReturn(2);
        magic.when(() -> MagicCraftRef.fromItem(item)).thenReturn(Optional.of(ref));
        categories.when(() -> CategoryHandler.resolveMagicMatch(item)).thenReturn(ref);
        categories.when(() -> CategoryHandler.resolveItemWeight(item)).thenReturn(10.0);
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        assertFalse(ItemValue.usesCompositionValue(item));
        for (int band = 0; band <= 4; band++) {
            int selected = band;
            magic.when(() -> MagicCraftRef.highestAuraBand(item)).thenReturn(selected);
            assertEquals(10 * (1 + Cache.auraPercent(band)), ItemValue.compute(item));
            String numeral = new String[]{"-", "I", "II", "III", "IV"}[band];
            assertTrue(report(item).contains("Aura " + numeral));
        }
        assertTrue(report(item).contains("magic.wand.2 (wand tier 2)"));
    }

    @Test
    void compositionIsUnavailableWithoutPluginOrForEmptyItems() {
        ItemStack item = new ItemStack(Material.STONE);
        assertFalse(ItemValue.usesCompositionValue(item));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        assertFalse(ItemValue.usesCompositionValue(null));
        assertFalse(ItemValue.usesCompositionValue(new ItemStack(Material.AIR)));
        assertFalse(ItemValue.usesCompositionValue(item));
    }

    @Test
    void socketedGemsAddTheirOwnCategoryWeightsWithoutQualityMultiplication() {
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ingredient("iron", Material.IRON_INGOT, 4);
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        bridge.when(() -> ThieveryBridge.readProvenance(sword)).thenReturn(provenance("polished", new CraftInput("ingredient", "iron", 1, 1)));
        when(nbt.hasType()).thenReturn(true);
        GemstoneData ruby = mock(GemstoneData.class);
        when(ruby.getMMOItemType()).thenReturn("GEM_STONE");
        when(ruby.getMMOItemID()).thenReturn("RUBY");
        GemstoneData unknown = mock(GemstoneData.class);
        when(unknown.getMMOItemType()).thenReturn("GEM_STONE");
        when(unknown.getMMOItemID()).thenReturn("UNKNOWN");
        GemSocketsData data = mock(GemSocketsData.class);
        when(data.getGems()).thenReturn(List.of(ruby, unknown));
        sockets.when(() -> GemSocketsNbtEditor.getSockets(sword)).thenReturn(data);
        loader.when(() -> CategoryLoader.getWeightForPath("m.gem_stone.ruby")).thenReturn(2.0);
        loader.when(() -> CategoryLoader.getWeightForPath("m.gem_stone.unknown")).thenReturn(0.1);
        ItemStack rubyProbe = new ItemStack(Material.REDSTONE);
        when(api.getCreator().getItemFromPath("m.gem_stone.ruby")).thenReturn(rubyProbe);
        ItemCategory gemCategory = mock(ItemCategory.class);
        when(gemCategory.getId()).thenReturn("gems");
        categories.when(() -> CategoryHandler.resolveFirstMatch(rubyProbe)).thenReturn(gemCategory);
        assertEquals(7.1, ItemValue.compute(sword), 1e-12);
        String report = report(sword);
        assertTrue(report.contains("Socketed gems"));
        assertTrue(report.contains("REDSTONE [gems]"));
        assertTrue(report.contains("m.gem_stone.unknown [default]"));
        assertTrue(report.contains("Per item: 7.10"));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(false);
        assertTrue(report(sword).contains("Type: MMO Item (socketed)"));
    }

    @Test
    void mmoItemsWithoutSocketsKeepBaseValueAndEmptyItemsHaveNoType() {
        assertFalse(ItemValue.hasType(null));
        assertFalse(ItemValue.hasType(new ItemStack(Material.AIR)));
        ItemStack item = new ItemStack(Material.DIAMOND);
        assertFalse(ItemValue.hasType(item));
        when(nbt.hasType()).thenReturn(true);
        assertTrue(ItemValue.hasType(item));
        assertEquals(0.1, ItemValue.compute(item));
        assertTrue(report(item).contains("Type: MMO Item"));
        GemSocketsData empty = mock(GemSocketsData.class);
        sockets.when(() -> GemSocketsNbtEditor.getSockets(item)).thenReturn(empty);
        assertEquals(0.1, ItemValue.compute(item));
        assertFalse(report(item).contains("Socketed gems"));
    }

    @Test
    void reportIdentifiesNoItemCluesUncategorizedStacksAndMoneyTotals() {
        assertTrue(report(null).contains("No item in hand."));
        assertTrue(report(new ItemStack(Material.AIR)).contains("No item in hand."));
        ItemStack paper = new ItemStack(Material.PAPER, 3);
        clues.when(() -> ClueChecker.isClueItem(paper)).thenReturn(true);
        String clueReport = report(paper);
        assertTrue(clueReport.contains("Clue items are never stealable."));
        assertTrue(clueReport.contains("Total: 0.00"));
        clues.when(() -> ClueChecker.isClueItem(paper)).thenReturn(false);
        String ordinary = report(paper);
        assertTrue(ordinary.contains("Categories: none (uncategorized)"));
        assertTrue(ordinary.contains("No category match - using default_item_value"));
        assertTrue(ordinary.contains("Amount: 3"));
        assertTrue(ordinary.contains("Stack total (×3): 0.30"));
        money.when(() -> DenarMoney.isMoney(paper)).thenReturn(true);
        money.when(() -> DenarMoney.stealPerItem(paper)).thenReturn(5.0);
        assertTrue(report(paper).contains("Stack total (×3): 15.00"));
    }

    @Test
    void reportListsPrimaryAndSecondaryCategoriesAndCraftMatchDetails() {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        ItemCategory primary = mock(ItemCategory.class);
        ItemCategory secondary = mock(ItemCategory.class);
        ItemCategory unmatched = mock(ItemCategory.class);
        when(primary.getId()).thenReturn("weapons");
        when(secondary.getId()).thenReturn("iron");
        loader.when(CategoryLoader::getAsList).thenReturn(List.of(primary, secondary, unmatched));
        categories.when(() -> CategoryHandler.matches(primary, item)).thenReturn(true);
        categories.when(() -> CategoryHandler.matches(secondary, item)).thenReturn(true);
        categories.when(() -> CategoryHandler.resolveBestEntry(item)).thenReturn(Optional.of(new CategoryHandler.ResolvedEntry(primary, new ItemCategory.CategoryItemEntry("v.iron_sword", 0.1), CategorySlugs.SlugSpecificity.EXACT_PATH)));
        categories.when(() -> CategoryHandler.resolveCategory(item)).thenReturn(primary);
        AcCraftRef craft = mock(AcCraftRef.class);
        when(craft.getRawId()).thenReturn("sword_2");
        when(craft.getStatTemplate()).thenReturn("sword");
        when(craft.getTier()).thenReturn(2);
        categories.when(() -> CategoryHandler.resolveCraftedMatch(item)).thenReturn(craft);
        GgCraftRef gun = mock(GgCraftRef.class);
        when(gun.getRawId()).thenReturn("pistol_3");
        when(gun.getGunType()).thenReturn("pistol");
        when(gun.getTier()).thenReturn(3);
        String report = report(item);
        assertTrue(report.contains("Categories: weapons (primary), iron"));
        assertTrue(report.contains("Category: weapons"));
        assertTrue(report.contains("sword_2 (sword tier 2)"));
        categories.when(() -> CategoryHandler.resolveCraftedMatch(item)).thenReturn(null);
        categories.when(() -> CategoryHandler.resolveGgMatch(item)).thenReturn(gun);
        assertTrue(report(item).contains("pistol_3 (pistol tier 3)"));
    }

    @Test
    void reportDistinguishesMaterialAndExternalItemSources() {
        ItemStack item = new ItemStack(Material.IRON_INGOT);
        assertTrue(report(item).contains("Type: Vanilla / Other"));
        when(api.getChecker().getAsStringPath(item)).thenReturn("ia.custom");
        assertTrue(report(item).contains("Type: ItemsAdder"));
        when(api.getChecker().getAsStringPath(item)).thenReturn("m.material.iron");
        assertTrue(report(item).contains("Type: MMO Item"));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        Ingredient ingredient = mock(Ingredient.class, RETURNS_DEEP_STUBS);
        when(ingredient.getIngredientData().getType().getId()).thenReturn("metal");
        bridge.when(() -> ThieveryBridge.resolveIngredient(item)).thenReturn(ingredient);
        assertTrue(report(item).contains("Type: Material (metal)"));
    }

    @Test
    void bundleReportSeparatesShellAndContentsAndExplainsEmptyBundles() {
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        categories.when(() -> CategoryHandler.getPerItemValue(bundle)).thenReturn(1.0);
        categories.when(() -> CategoryHandler.getTotalValue(bundle)).thenReturn(1.0);
        assertTrue(report(bundle).contains("Empty bundle shell only."));
        assertTrue(report(bundle).contains("Total: 1.00"));
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 3);
        categories.when(() -> CategoryHandler.getPerItemValue(diamonds)).thenReturn(2.0);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.setItems(List.of(diamonds));
        bundle.setItemMeta(meta);
        String report = report(bundle);
        assertTrue(report.contains("DIAMOND ×3  →  6.00"));
        assertTrue(report.contains("Bundle shell: 1.00"));
        assertTrue(report.contains("Contents: 6.00"));
        assertTrue(report.contains("Total: 7.00"));
    }

}
