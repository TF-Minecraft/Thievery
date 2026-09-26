package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import net.tfminecraft.advancedcrafting.objects.alloys.Alloy;
import net.tfminecraft.advancedcrafting.objects.crafting.CraftingRecipe;
import net.tfminecraft.advancedcrafting.objects.data.CraftProvenance;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.advancedcrafting.objects.ingredients.IngredientType;
import net.tfminecraft.advancedcrafting.objects.stats.StatTemplate;
import net.tfminecraft.advancedcrafting.utils.ThieveryBridge;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.item.Coin;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.tlibs.TLibs;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class CategoryHandlerTest {
    private MockedStatic<ThieveryBridge> bridge;
    private MockedStatic<TLibs> libs;
    private MockedStatic<ClueGiver> clues;
    private MockedStatic<NBTItem> nbt;
    private MMOItems previousMmoPlugin;

    @BeforeEach void setUp() {
        MockBukkit.mock();
        CategoryLoader.get().clear();
        previousMmoPlugin = MMOItems.plugin;
        MMOItems.plugin = null;
        bridge = mockStatic(ThieveryBridge.class);
        libs = mockStatic(TLibs.class, RETURNS_DEEP_STUBS);
        clues = mockStatic(ClueGiver.class);
        nbt = mockStatic(NBTItem.class, RETURNS_DEEP_STUBS);
        when(TLibs.getItemAPI().getCreator().getItemFromPath(anyString())).thenReturn(null);
        when(TLibs.getItemAPI().getChecker().getAsStringPath(any())).thenReturn("");
    }
    @AfterEach void tearDown() {
        nbt.close(); clues.close(); libs.close(); bridge.close();
        MMOItems.plugin = previousMmoPlugin;
        CategoryLoader.get().clear();
        MockBukkit.unmock();
    }

    @Test void invalidMaterialTierCannotAdvertiseAnAlloyExample() {
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        ItemCategory category = category("invalid-tier", "ac_metal_tier_0 3");
        assertTrue(CategoryHandler.buildDisplayLines(category).isEmpty());
    }

    @Test void missingAndAirItemsNeverMatchOrHaveValue() {
        ItemCategory category = category("tools", "v.stick 2");
        for (ItemStack item : new ItemStack[]{null, new ItemStack(Material.AIR)}) {
            assertFalse(CategoryHandler.matches(category, item));
            assertFalse(CategoryHandler.matchesDirect(category, item));
            assertFalse(CategoryHandler.matchesCraftInCategory(category, item));
            assertFalse(CategoryHandler.matchesDirectSlug("v.stick", item));
            assertTrue(CategoryHandler.resolveBestEntry(item).isEmpty());
            assertNull(CategoryHandler.resolveCategory(item));
            assertNull(CategoryHandler.resolveCraftCategory(item));
            assertEquals("", CategoryHandler.pathOf(item));
            assertEquals(0, CategoryHandler.getPerItemValue(item));
            assertEquals(0, CategoryHandler.getTotalValue(item));
        }
        var item = new ItemStack(Material.STICK);
        assertFalse(CategoryHandler.matches(null, item));
        assertFalse(CategoryHandler.matchesDirect(null, item));
        assertFalse(CategoryHandler.matchesCraftInCategory(null, item));
        assertFalse(CategoryHandler.matchesDirectSlug(null, item));
        assertFalse(CategoryHandler.matchesDirectSlug(" ", item));
        assertFalse(CategoryHandler.matchesDirectSlug("gg_rifle_tier_1", item));
        assertFalse(CategoryHandler.matches(category, item));
    }

    @Test void directResolutionRanksExactAboveFuzzyAboveMmoAndRetainsFirstTie() {
        var item = new ItemStack(Material.STICK);
        ItemCategory type = category("type", "m.TOOL 1");
        ItemCategory fuzzy = category("fuzzy", "m.tool.* 2");
        ItemCategory exact = category("exact", "m.tool.stick 3");
        category("duplicate", "m.tool.stick 9");
        matching(item, "m.tool.stick", "m.TOOL", "m.tool.*", "m.tool.stick");
        var resolved = CategoryHandler.resolveBestDirectEntry(item).orElseThrow();
        assertSame(exact, resolved.category());
        assertEquals(CategorySlugs.SlugSpecificity.EXACT_PATH, resolved.specificity());
        assertSame(exact, CategoryHandler.resolveFirstMatch(item));
        assertSame(exact, CategoryHandler.resolveCategory(item));
        assertEquals(3, CategoryHandler.resolveItemWeight(item));
        assertTrue(CategoryHandler.matches(type, item));
        assertTrue(CategoryHandler.matchesDirect(fuzzy, item));
        CategoryLoader.get().remove("exact"); CategoryLoader.get().remove("duplicate");
        assertSame(fuzzy, CategoryHandler.resolveFirstMatch(item));
        CategoryLoader.get().remove("fuzzy");
        assertSame(type, CategoryHandler.resolveFirstMatch(item));
        CategoryLoader.get().clear();
        assertNull(CategoryHandler.resolveFirstMatch(item));
        assertEquals(Cache.defaultItemValue, CategoryHandler.resolveItemWeight(item));
    }

    @Test void realGunAndMagicMetadataMatchCraftCategoriesAndUseTheirConfiguredWeights() {
        var gun = gear("gunsandgadgets:gun_type", "rifle", "gunsandgadgets:gg_majority_tier", 2);
        var magic = gear("magic:gear_archetype", "staff", "magic:majority_tier", 3);
        ItemCategory unrelated = category("unrelated", "v.paper", "gg_pistol_tier_2", "magic_wand_tier_3", "ac_blade_tier_1");
        ItemCategory guns = category("guns", "gg_rifle_tier_2 4");
        category("guns_later", "gg_rifle_tier_2 8");
        ItemCategory staves = category("staves", "magic_staff_tier_3 5");
        assertFalse(CategoryHandler.matchesCraftInCategory(unrelated, gun));
        assertFalse(CategoryHandler.matchesCraftInCategory(unrelated, magic));
        assertTrue(CategoryHandler.matches(guns, gun));
        assertTrue(CategoryHandler.matchesCraftInCategory(staves, magic));
        assertFalse(CategoryHandler.matchesDirect(guns, gun));
        assertFalse(CategoryHandler.matchesCraftInCategory(guns, magic));
        assertFalse(CategoryHandler.matchesCraftInCategory(guns, new ItemStack(Material.STONE)));
        assertSame(guns, CategoryHandler.resolveCraftCategory(gun));
        assertSame(staves, CategoryHandler.resolveCategory(magic));
        assertEquals(4, CategoryHandler.resolveItemWeight(gun));
        assertEquals(5, CategoryHandler.resolveItemWeight(magic));
        assertEquals("gg_rifle_tier_2", CategoryHandler.resolveGgMatch(gun).getRawId());
        assertEquals("magic_staff_tier_3", CategoryHandler.resolveMagicMatch(magic).getRawId());
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForGgRef(null));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForMagicRef(null));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForCraftRef(null));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForGgRef(GgCraftRef.parse("gg_rifle_tier_9").orElseThrow()));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForMagicRef(MagicCraftRef.parse("magic_staff_tier_9").orElseThrow()));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForCraftRef(AcCraftRef.parse("ac_blade_tier_9").orElseThrow()));
    }

    @Test void matchingCanonicalPathDoesNotOverrideIntegrationRejection() {
        ItemStack item = new ItemStack(Material.STICK);
        category("tools", "v.stick 2");
        when(TLibs.getItemAPI().getChecker().getAsStringPath(item)).thenReturn("v.stick");
        when(TLibs.getItemAPI().getChecker().checkItemWithPath(item, "v.stick")).thenReturn(false);

        assertTrue(CategoryHandler.resolveBestDirectEntry(item).isEmpty());
        assertEquals(Cache.defaultItemValue, CategoryHandler.resolveItemWeight(item));
    }

    @Test void registeredItemPathAliasRetainsFuzzySpecificity() {
        ItemStack item = new ItemStack(Material.STICK);
        ItemCategory category = category("tools", "custom.tools 2");
        matching(item, "v.stick", "custom.tools");

        var resolved = CategoryHandler.resolveBestDirectEntry(item).orElseThrow();

        assertSame(category, resolved.category());
        assertEquals(CategorySlugs.SlugSpecificity.FUZZY_PATH, resolved.specificity());
        assertEquals(2, CategoryHandler.weightForResolvedEntry(resolved));
    }

    @Test void matchingMaterialTierOutranksMmoTypeAndPreservesFirstMaterialTie() {
        ItemStack item = new ItemStack(Material.IRON_INGOT);
        category("type", "m.MATERIAL 1");
        category("wrongTier", "ac_metal_tier_1 9");
        ItemCategory material = category("material", "ac_metal_tier_2 3");
        category("laterMaterial", "ac_metal_tier_2 7");
        matching(item, "m.material.iron", "m.MATERIAL");
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        Ingredient iron = ingredient("iron", "metal", 2, true, true);
        bridge.when(() -> ThieveryBridge.resolveIngredient(item)).thenReturn(iron);

        var resolved = CategoryHandler.resolveBestDirectEntry(item).orElseThrow();

        assertSame(material, resolved.category());
        assertEquals(CategorySlugs.SlugSpecificity.MATERIAL_TIER, resolved.specificity());
        assertEquals(3, CategoryHandler.resolveItemWeight(item));
    }

    @Test void craftResolutionUsesRecipeProvenanceAndRejectsUnavailableOrIncompleteRecipes() {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        assertNull(CategoryHandler.resolveCraftedMatch(item));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        assertNull(CategoryHandler.resolveCraftedMatch(null));
        assertNull(CategoryHandler.resolveCraftedMatch(new ItemStack(Material.AIR)));
        assertNull(CategoryHandler.resolveCraftedMatch(item));
        CraftProvenance provenance = new CraftProvenance("blade", "tempered", List.of(), 1);
        bridge.when(() -> ThieveryBridge.readProvenance(item)).thenReturn(provenance);
        assertNull(CategoryHandler.resolveCraftedMatch(item));
        CraftingRecipe recipe = mock(CraftingRecipe.class);
        bridge.when(() -> ThieveryBridge.getRecipeById("blade")).thenReturn(recipe);
        assertNull(CategoryHandler.resolveCraftedMatch(item));
        when(recipe.getStatTemplateId()).thenReturn("blade");
        assertNull(CategoryHandler.resolveCraftedMatch(item));
        bridge.when(() -> ThieveryBridge.resolveMajorityTier(recipe, provenance.getInputs())).thenReturn(2);
        category("other", "v.paper", "ac_blade_tier_1");
        ItemCategory craft = category("craft", "v.paper", "ac_other_tier_2", "ac_blade_tier_2 6");
        category("later", "ac_blade_tier_2 9");
        assertEquals(AcCraftRef.parse("ac_blade_tier_2").orElseThrow(), CategoryHandler.resolveCraftedMatch(item));
        assertSame(craft, CategoryHandler.resolveCraftCategory(item));
        assertTrue(CategoryHandler.matchesCraftInCategory(craft, item));
        assertEquals(6, CategoryHandler.resolveItemWeight(item));
        assertFalse(CategoryHandler.matchesAnyActive(new PlayerData(UUID.randomUUID()), item));
    }

    @Test void directAndCraftResolutionChooseTheMoreSpecificMatch() {
        var gun = gear("gunsandgadgets:gun_type", "rifle", "gunsandgadgets:gg_majority_tier", 1);
        var craft = category("craft", "gg_rifle_tier_1 4");
        var type = category("type", "m.GUN 2");
        matching(gun, "m.gun.rifle", "m.GUN");
        assertSame(craft, CategoryHandler.resolveCategory(gun));
        var exact = category("exact", "m.gun.rifle 8");
        matching(gun, "m.gun.rifle", "m.gun.rifle");
        assertSame(exact, CategoryHandler.resolveCategory(gun));
        // Craft valuations continue to use the craft reference even when the display match is exact.
        assertEquals(4, CategoryHandler.resolveItemWeight(gun));
        CategoryLoader.get().remove("craft");
        assertSame(exact, CategoryHandler.resolveCategory(gun));
    }

    @Test void materialMatchingChecksAlloyAndIngredientTypesAndTiers() {
        var item = new ItemStack(Material.IRON_INGOT);
        assertFalse(CategoryHandler.matchesDirectSlug("ac_metal_tier_2", item));
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        assertFalse(CategoryHandler.matchesDirectSlug("ac_metal_tier_2", item));
        Alloy alloy = mock(Alloy.class, RETURNS_DEEP_STUBS);
        bridge.when(() -> ThieveryBridge.resolveAlloy(item)).thenReturn(alloy);
        when(alloy.getData().getType().getId()).thenReturn("metal");
        when(alloy.getData().getTier()).thenReturn(2);
        assertTrue(CategoryHandler.matchesDirectSlug("ac_metal_tier_2", item));
        assertFalse(CategoryHandler.matchesDirectSlug("ac_wood_tier_2", item));
        assertFalse(CategoryHandler.matchesDirectSlug("ac_metal_tier_3", item));
        when(alloy.getData()).thenReturn(null);
        Ingredient ingredient = ingredient("iron", "metal", 2, true, true);
        bridge.when(() -> ThieveryBridge.resolveIngredient(item)).thenReturn(ingredient);
        assertTrue(CategoryHandler.matchesDirectSlug("ac_metal_tier_2", item));
        assertFalse(CategoryHandler.matchesDirectSlug("ac_wood_tier_2", item));
        assertFalse(CategoryHandler.matchesDirectSlug("ac_metal_tier_3", item));
        when(ingredient.getIngredientData().hasTier()).thenReturn(false);
        assertFalse(CategoryHandler.matchesDirectSlug("ac_metal_tier_2", item));
    }

    @Test void activeCategoriesRevealMatchesAndUncategorizedItemsButNeverCluesOrInactiveCrafts() {
        var player = new PlayerData(UUID.randomUUID());
        var item = new ItemStack(Material.STICK);
        category("tools", "v.stick");
        matching(item, "v.stick", "v.stick");
        assertFalse(CategoryHandler.matchesAnyActive(player, item));
        player.setActiveCategories(List.of("deleted", "tools", "tools"));
        assertTrue(CategoryHandler.matchesAnyActive(player, item));
        assertEquals(Set.of("deleted", "tools"), CategoryHandler.getActiveCategoryIds(player));
        assertTrue(CategoryHandler.canRevealItem(player, item));
        assertTrue(CategoryHandler.matchesAnyActive(player, new ItemStack(Material.STONE)));
        clues.when(() -> ClueGiver.isClueItem(item)).thenReturn(true);
        assertFalse(CategoryHandler.matchesAnyActive(player, item));
        assertFalse(CategoryHandler.canRevealItem(player, item));
        assertEquals(0, CategoryHandler.getPerItemValue(item));
        clues.when(() -> ClueGiver.isClueItem(item)).thenReturn(false);
        player.setActiveCategories(List.of());
        assertTrue(CategoryHandler.matchesAnyActive(player, new ItemStack(Material.STONE)));
        assertFalse(CategoryHandler.matchesAnyActive(player, gear("gunsandgadgets:gun_type", "rifle", "gunsandgadgets:gg_majority_tier", 1)));
        assertFalse(CategoryHandler.matchesAnyActive(player, gear("magic:gear_archetype", "wand", "magic:majority_tier", 1)));
    }

    @Test void pathProbesAndStackOrBundleValuesUseConfiguredPerItemWeights() {
        var item = new ItemStack(Material.STICK, 3);
        category("tools", "v.stick 2");
        matching(item, "v.stick", "v.stick");
        when(TLibs.getItemAPI().getCreator().getItemFromPath("v.stick")).thenReturn(item);
        assertEquals(2, CategoryHandler.getWeightForPath("v.stick"));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForPath(null));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForPath(" "));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForPath("missing"));
        when(TLibs.getItemAPI().getCreator().getItemFromPath("unknown")).thenReturn(new ItemStack(Material.STONE));
        assertEquals(Cache.defaultItemValue, CategoryHandler.getWeightForPath("unknown"));
        assertEquals(Cache.defaultItemValue, CategoryHandler.weightForResolvedEntry(null));
        assertEquals(2, CategoryHandler.getPerItemValue(item));
        assertEquals(6, CategoryHandler.getTotalValue(item));
        var bundle = new ItemStack(Material.BUNDLE);
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        meta.setItems(List.of(item)); bundle.setItemMeta(meta);
        assertEquals(6 + Cache.defaultItemValue, CategoryHandler.getTotalValue(bundle), 0.0001);
        assertTrue(CategoryHandler.canRevealItem(new PlayerData(UUID.randomUUID()), bundle));
    }

    @Test void moneyUsesDedicatedCategoryPermissionsAndDenarConversion() {
        MockBukkit.createMockPlugin("DenarEconomy");
        var coinItem = new ItemStack(Material.GOLD_NUGGET);
        Coin coin = mock(Coin.class); when(coin.getValue()).thenReturn(5.0);
        try (var economy = mockStatic(DenarEconomy.class, RETURNS_DEEP_STUBS)) {
            when(DenarEconomy.getMoneyManager().getCoin(coinItem)).thenReturn(coin);
            assertTrue(CategoryHandler.resolveBestDirectEntry(coinItem).isEmpty());
            var player = new PlayerData(UUID.randomUUID());
            assertFalse(CategoryHandler.matchesAnyActive(player, coinItem));
            var config = new YamlConfiguration(); config.set("type", "money"); config.set("amount_per_money", 0.4); config.set("value", 7);
            ItemCategory money = new ItemCategory("money", config); CategoryLoader.get().put("money", money);
            assertTrue(CategoryHandler.matchesDirect(money, coinItem));
            assertFalse(CategoryHandler.matchesAnyActive(player, coinItem));
            player.setActiveCategories(List.of("money"));
            assertTrue(CategoryHandler.matchesAnyActive(player, coinItem));
            var resolved = CategoryHandler.resolveBestDirectEntry(coinItem).orElseThrow();
            assertSame(money, resolved.category()); assertNull(resolved.entry());
            assertEquals(7, CategoryHandler.weightForResolvedEntry(resolved));
            assertEquals(2, CategoryHandler.resolveItemWeight(coinItem));
            assertEquals(List.of("- Pouch and coins", "  Steal value: 0.40 per denar"), plain(CategoryHandler.buildDisplayLines(money)));
        }
    }

    @Test void displayLinesDescribeGunMagicPathsAndMissingCraftIntegration() {
        var named = new ItemStack(Material.PAPER);
        var meta = named.getItemMeta(); meta.setDisplayName("Named Paper"); named.setItemMeta(meta);
        when(TLibs.getItemAPI().getCreator().getItemFromPath("v.paper")).thenReturn(named);
        var category = category("all", "gg_rifle_tier_1 2", "magic_staff_tier_1 3", "v.paper 4", "unknown.path 5", "m.SWORD 6", "ac_blade_tier_1", "ac_metal_tier_1");
        List<String> lines = plain(CategoryHandler.buildDisplayLines(category));
        assertEquals(7, lines.size());
        assertTrue(lines.get(0).contains("Rifle")); assertTrue(lines.get(1).contains("Staff"));
        assertTrue(lines.get(2).contains("Named Paper")); assertTrue(lines.get(3).contains("unknown.path"));
        assertTrue(lines.get(4).contains("SWORD"));
        assertEquals("Crafting data unavailable", lines.get(5)); assertEquals(lines.get(5), lines.get(6));
    }

    @Test void materialDisplayFiltersIngredientsAndShowsRomanTierAlloysWithEstimatedValue() {
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        Ingredient base = ingredient("raw__iron", "metal", 2, true, true);
        Ingredient unranked = ingredient("unranked", "metal", 0, false, true);
        Ingredient nonBase = ingredient("decoration", "metal", 1, true, false);
        Ingredient wrongTier = ingredient("other", "metal", 3, true, true);
        Ingredient wood = ingredient("wood", "wood", 2, true, true);
        bridge.when(ThieveryBridge::getAllIngredients).thenReturn(List.of(wood, nonBase, unranked, wrongTier, base));
        var unrankedPreview = new ItemStack(Material.PAPER);
        category("unranked", "v.unranked 9");
        matching(unrankedPreview, "v.unranked", "v.unranked");
        when(TLibs.getItemAPI().getCreator().getItemFromPath("v.unranked")).thenReturn(unrankedPreview);
        IngredientType type = mock(IngredientType.class); when(type.getName()).thenReturn("Metals");
        bridge.when(() -> ThieveryBridge.getIngredientType("metal")).thenReturn(type);
        var category = category("metals", "ac_metal_tier_2");
        List<String> lines = plain(CategoryHandler.buildDisplayLines(category));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("Raw Iron")); assertTrue(lines.get(1).contains("Tier II Metals Alloys"));
        assertTrue(lines.stream().allMatch(s -> s.contains("value: " + Cache.defaultItemValue)));
        // Ingredient preview names take precedence over identifiers.
        var preview = new ItemStack(Material.IRON_INGOT); var meta = preview.getItemMeta(); meta.setDisplayName("Iron Bar"); preview.setItemMeta(meta);
        when(TLibs.getItemAPI().getCreator().getItemFromPath(base.getPath())).thenReturn(preview);
        assertTrue(plain(CategoryHandler.buildDisplayLines(category)).getFirst().contains("Iron Bar"));
        Ingredient decorative = ingredient("filigree", "metal", 2, true, false);
        bridge.when(ThieveryBridge::getAllIngredients).thenReturn(List.of(decorative));
        assertEquals(1, CategoryHandler.buildDisplayLines(category).size());
        // Configured material tiers have stable labels, including tiers beyond the named Roman range.
        for (int tier : new int[]{1, 3, 4, 5}) {
            Ingredient tierBase = ingredient("wood" + tier, "wood", tier, true, true);
            bridge.when(ThieveryBridge::getAllIngredients).thenReturn(List.of(tierBase));
            var configured = category("tier" + tier, "ac_wood_tier_" + tier);
            String numeral = Map.of(1,"I",3,"III",4,"IV",5,"5").get(tier);
            assertTrue(plain(CategoryHandler.buildDisplayLines(configured)).getLast().contains("Tier " + numeral + " wood Alloys"));
        }
    }

    @Test void craftDisplayUsesTemplateNamesAndEstimatesRecipeMaterialQuantities() {
        bridge.when(ThieveryBridge::isPluginReady).thenReturn(true);
        var ref = AcCraftRef.parse("ac__iron_s_tier_2").orElseThrow();
        assertTrue(plain(CategoryHandler.buildAcCraftRefLines(ref, 99)).getFirst().contains("Iron S"));
        StatTemplate template = mock(StatTemplate.class); when(template.getName()).thenReturn("Forged Blade");
        bridge.when(() -> ThieveryBridge.getStatTemplate("_iron_s")).thenReturn(template);
        CraftingRecipe recipe = mock(CraftingRecipe.class);
        when(recipe.getRecipe()).thenReturn(new HashMap<>(Map.of("metal", 3, "missing", 4)));
        bridge.when(() -> ThieveryBridge.findRecipeByStatTemplate("_iron_s")).thenReturn(recipe);
        List<Ingredient> ingredients = List.of(
                ingredient("wood", "wood", 2, true, true), ingredient("unranked", "metal", 0, false, false),
                ingredient("wrong", "metal", 3, true, false), ingredient("iron", "metal", 2, true, true));
        bridge.when(ThieveryBridge::getAllIngredients).thenReturn(ingredients);
        List<String> lines = plain(CategoryHandler.buildAcCraftRefLines(ref, 99));
        assertEquals(1, lines.size()); assertTrue(lines.getFirst().contains("Forged Blade"));
        assertTrue(lines.getFirst().contains("value: 0.3"));
    }

    @Test void mmoTypeLookupFallsBackToUppercaseAndDisplayUsesOnlyNonblankNames() {
        assertNull(CategoryHandler.lookupMmoType(null)); assertNull(CategoryHandler.lookupMmoType(" "));
        assertNull(CategoryHandler.lookupMmoType("SWORD"));
        assertEquals("v.stick", CategoryHandler.resolveMmoTypeDisplayName("v.stick"));
        MMOItems.plugin = mock(MMOItems.class, RETURNS_DEEP_STUBS);
        Type type = mock(Type.class);
        when(MMOItems.plugin.getTypes().get("sword")).thenReturn(null);
        when(MMOItems.plugin.getTypes().get("SWORD")).thenReturn(type);
        assertSame(type, CategoryHandler.lookupMmoType("sword"));
        assertSame(type, CategoryHandler.lookupMmoType("SWORD"));
        assertEquals("sword", CategoryHandler.resolveMmoTypeDisplayName("m.sword"));
        when(type.getName()).thenReturn(" "); assertEquals("sword", CategoryHandler.resolveMmoTypeDisplayName("m.sword"));
        when(type.getName()).thenReturn("Blades"); assertEquals("Blades", CategoryHandler.resolveMmoTypeDisplayName("m.sword"));
    }

    private ItemCategory category(String id, String... entries) {
        var config = new YamlConfiguration(); config.set("items", List.of(entries));
        ItemCategory category = new ItemCategory(id, config); CategoryLoader.get().put(id, category); return category;
    }
    private void matching(ItemStack item, String path, String... slugs) {
        when(TLibs.getItemAPI().getChecker().getAsStringPath(item)).thenReturn(path);
        for (String slug : slugs) when(TLibs.getItemAPI().getChecker().checkItemWithPath(item, slug)).thenReturn(true);
    }
    private ItemStack gear(String typeKey, String type, String tierKey, int tier) {
        var item = new ItemStack(Material.STICK); var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(NamespacedKey.fromString(typeKey), PersistentDataType.STRING, type);
        meta.getPersistentDataContainer().set(NamespacedKey.fromString(tierKey), PersistentDataType.INTEGER, tier);
        item.setItemMeta(meta); return item;
    }
    private Ingredient ingredient(String id, String type, int tier, boolean hasTier, boolean base) {
        Ingredient ingredient = mock(Ingredient.class, RETURNS_DEEP_STUBS);
        when(ingredient.getId()).thenReturn(id); when(ingredient.getPath()).thenReturn("v." + id);
        when(ingredient.getIngredientData().getType().getId()).thenReturn(type);
        when(ingredient.getIngredientData().hasTier()).thenReturn(hasTier);
        when(ingredient.getIngredientData().getTier()).thenReturn(tier);
        when(ingredient.getIngredientData().canBeBase()).thenReturn(base);
        return ingredient;
    }
    @SuppressWarnings("deprecation")
    private List<String> plain(List<String> lines) { return lines.stream().map(ChatColor::stripColor).toList(); }
}
