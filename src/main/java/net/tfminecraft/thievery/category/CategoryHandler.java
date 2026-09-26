package net.tfminecraft.thievery.category;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.Type;
import net.tfminecraft.advancedcrafting.objects.alloys.Alloy;
import net.tfminecraft.advancedcrafting.objects.crafting.CraftingRecipe;
import net.tfminecraft.advancedcrafting.objects.data.CraftProvenance;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.advancedcrafting.objects.ingredients.IngredientType;
import net.tfminecraft.advancedcrafting.objects.stats.StatTemplate;
import net.tfminecraft.advancedcrafting.utils.ThieveryBridge;
import net.tfminecraft.thievery.category.CategorySlugs.SlugSpecificity;
import net.tfminecraft.thievery.category.ItemCategory.CategoryItemEntry;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.steal.StealItemDisplay;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class CategoryHandler {

    public record ResolvedEntry(
            ItemCategory category,
            CategoryItemEntry entry,
            SlugSpecificity specificity) {
    }

    private CategoryHandler() {
    }

    public static boolean matches(ItemCategory category, ItemStack item) {
        if (category == null || item == null || item.getType().isAir()) {
            return false;
        }
        return matchesDirect(category, item) || matchesCraftInCategory(category, item);
    }

    public static boolean matchesDirect(ItemCategory category, ItemStack item) {
        if (category == null || item == null || item.getType().isAir()) {
            return false;
        }
        if (category.isMoneyType()) {
            return DenarMoney.isMoney(item);
        }
        for (ItemCategory.CategoryItemEntry entry : category.getItems()) {
            if (matchesDirectSlug(entry.getSlug(), item)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesCraftInCategory(ItemCategory category, ItemStack item) {
        if (category == null || item == null || item.getType().isAir()) {
            return false;
        }
        AcCraftRef crafted = resolveCraftedMatch(item);
        GgCraftRef gg = resolveGgMatch(item);
        MagicCraftRef magic = resolveMagicMatch(item);
        if (crafted == null && gg == null && magic == null) {
            return false;
        }
        for (ItemCategory.CategoryItemEntry entry : category.getItems()) {
            if (crafted != null) {
                var parsed = CategorySlugs.parseCraftRef(entry.getSlug());
                if (parsed.isPresent() && parsed.get().equals(crafted)) {
                    return true;
                }
            }
            if (gg != null) {
                var parsedGg = CategorySlugs.parseGgCraftRef(entry.getSlug());
                if (parsedGg.isPresent() && parsedGg.get().equals(gg)) {
                    return true;
                }
            }
            if (magic != null) {
                var parsedMagic = CategorySlugs.parseMagicCraftRef(entry.getSlug());
                if (parsedMagic.isPresent() && parsedMagic.get().equals(magic)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean matchesAnyActive(PlayerData playerData, ItemStack item) {
        if (ClueChecker.isClueItem(item)) {
            return false;
        }

        if (DenarMoney.isMoney(item)) {
            ItemCategory money = CategoryLoader.getMoneyCategory();
            return money != null && playerData.isCategoryActive(money.getId());
        }

        for (String activeId : playerData.getActiveCategories()) {
            ItemCategory category = CategoryLoader.getById(activeId);
            if (category == null) {
                continue;
            }
            if (matches(category, item)) {
                return true;
            }
        }

        return resolveFirstMatch(item) == null && resolveCraftedMatch(item) == null
                && resolveGgMatch(item) == null && resolveMagicMatch(item) == null;
    }

    public static ItemCategory resolveFirstMatch(ItemStack item) {
        return resolveBestDirectEntry(item).map(ResolvedEntry::category).orElse(null);
    }

    public static Optional<ResolvedEntry> resolveBestDirectEntry(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        if (DenarMoney.isMoney(item)) {
            ItemCategory money = CategoryLoader.getMoneyCategory();
            if (money == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedEntry(money, null, SlugSpecificity.EXACT_PATH));
        }

        String itemPath = pathOf(item);
        ResolvedEntry best = null;
        for (ItemCategory category : CategoryLoader.getAsList()) {
            for (CategoryItemEntry entry : category.getItems()) {
                if (!matchesDirectSlug(entry.getSlug(), item)) {
                    continue;
                }
                SlugSpecificity specificity = directMatchSpecificity(entry.getSlug(), itemPath);
                ResolvedEntry candidate = new ResolvedEntry(category, entry, specificity);
                if (best == null || candidate.specificity().getRank() > best.specificity().getRank()) {
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Classifies a slug already accepted by matchesDirectSlug without repeating integration checks. */
    private static SlugSpecificity directMatchSpecificity(String slug, String itemPath) {
        if (CategorySlugs.isMaterialSlug(slug)) {
            return SlugSpecificity.MATERIAL_TIER;
        }
        if (CategorySlugs.isMmoTypeSlug(slug)) {
            return SlugSpecificity.MMO_TYPE;
        }
        return slug.trim().equalsIgnoreCase(itemPath) ? SlugSpecificity.EXACT_PATH : SlugSpecificity.FUZZY_PATH;
    }

    public static Optional<ResolvedEntry> resolveBestCraftEntry(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        AcCraftRef crafted = resolveCraftedMatch(item);
        GgCraftRef gg = resolveGgMatch(item);
        MagicCraftRef magic = resolveMagicMatch(item);
        if (crafted == null && gg == null && magic == null) {
            return Optional.empty();
        }

        // Craft references share one specificity, so the first configured match wins.
        for (ItemCategory category : CategoryLoader.getAsList()) {
            for (CategoryItemEntry entry : category.getItems()) {
                boolean matches = false;
                if (crafted != null) {
                    var parsed = CategorySlugs.parseCraftRef(entry.getSlug());
                    if (parsed.isPresent() && parsed.get().equals(crafted)) {
                        matches = true;
                    }
                }
                if (!matches && gg != null) {
                    var parsedGg = CategorySlugs.parseGgCraftRef(entry.getSlug());
                    if (parsedGg.isPresent() && parsedGg.get().equals(gg)) {
                        matches = true;
                    }
                }
                if (!matches && magic != null) {
                    var parsedMagic = CategorySlugs.parseMagicCraftRef(entry.getSlug());
                    if (parsedMagic.isPresent() && parsedMagic.get().equals(magic)) {
                        matches = true;
                    }
                }
                if (matches) {
                    return Optional.of(new ResolvedEntry(category, entry, SlugSpecificity.CRAFT_REF));
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<ResolvedEntry> resolveBestEntry(ItemStack item) {
        Optional<ResolvedEntry> direct = resolveBestDirectEntry(item);
        Optional<ResolvedEntry> craft = resolveBestCraftEntry(item);
        if (direct.isEmpty()) {
            return craft;
        }
        if (craft.isEmpty()) {
            return direct;
        }
        ResolvedEntry directEntry = direct.get();
        ResolvedEntry craftEntry = craft.get();
        if (directEntry.specificity().getRank() > craftEntry.specificity().getRank()) {
            return direct;
        }
        // Craft references have rank 2; direct matches have rank 0, 1, 3 or 4.
        return craft;
    }

    public static ItemCategory resolveCraftCategory(ItemStack item) {
        return resolveBestCraftEntry(item).map(ResolvedEntry::category).orElse(null);
    }

    public static double resolveItemWeight(ItemStack item) {
        if (DenarMoney.isMoney(item)) {
            return DenarMoney.stealPerItem(item);
        }
        AcCraftRef crafted = resolveCraftedMatch(item);
        if (crafted != null) {
            return CategoryLoader.getWeightForCraftRef(crafted);
        }
        GgCraftRef gg = resolveGgMatch(item);
        if (gg != null) {
            return CategoryLoader.getWeightForGgRef(gg);
        }
        MagicCraftRef magic = resolveMagicMatch(item);
        if (magic != null) {
            return CategoryLoader.getWeightForMagicRef(magic);
        }
        return weightForResolvedEntry(resolveBestDirectEntry(item).orElse(null));
    }

    public static double weightForResolvedEntry(ResolvedEntry resolved) {
        if (resolved == null) {
            return CategoryLoader.getDefaultWeight();
        }
        if (resolved.entry() != null) {
            return resolved.entry().getWeight();
        }
        return resolved.category().getValue();
    }

    public static String pathOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        return TLibs.getItemAPI().getChecker().getAsStringPath(item);
    }

    public static AcCraftRef resolveCraftedMatch(ItemStack item) {
        if (!ThieveryBridge.isPluginReady() || item == null || item.getType().isAir()) {
            return null;
        }
        CraftProvenance provenance = ThieveryBridge.readProvenance(item);
        if (provenance == null) {
            return null;
        }
        CraftingRecipe recipe = ThieveryBridge.getRecipeById(provenance.getRecipeId());
        if (recipe == null || recipe.getStatTemplateId() == null) {
            return null;
        }
        int tier = ThieveryBridge.resolveMajorityTier(recipe, provenance.getInputs());
        String templateId = recipe.getStatTemplateId();
        return AcCraftRef.parse("ac_" + templateId + "_tier_" + tier).orElse(null);
    }

    public static GgCraftRef resolveGgMatch(ItemStack item) {
        return GgCraftRef.fromItem(item).orElse(null);
    }

    public static MagicCraftRef resolveMagicMatch(ItemStack item) {
        return MagicCraftRef.fromItem(item).orElse(null);
    }

    public static boolean matchesDirectSlug(String slug, ItemStack item) {
        if (slug == null || slug.isBlank() || item == null || item.getType().isAir()) {
            return false;
        }
        if (CategorySlugs.isMaterialSlug(slug)) {
            return matchesAcMaterialSlug(slug, item);
        }
        if (CategorySlugs.isMmoTypeSlug(slug) || CategorySlugs.isPathSlug(slug)) {
            return TLibs.getItemAPI().getChecker().checkItemWithPath(item, slug);
        }
        return false;
    }

    private static boolean matchesAcMaterialSlug(String slug, ItemStack item) {
        if (!ThieveryBridge.isPluginReady()) {
            return false;
        }
        String wantedType = CategorySlugs.materialType(slug);
        int wantedTier = CategorySlugs.materialTier(slug);

        Alloy alloy = ThieveryBridge.resolveAlloy(item);
        if (alloy != null && alloy.getData() != null) {
            return alloy.getData().getType().getId().equalsIgnoreCase(wantedType)
                    && alloy.getData().getTier() == wantedTier;
        }

        Ingredient ingredient = ThieveryBridge.resolveIngredient(item);
        if (ingredient == null) {
            return false;
        }
        return ingredient.getIngredientData().getType().getId().equalsIgnoreCase(wantedType)
                && ingredient.getIngredientData().hasTier()
                && ingredient.getIngredientData().getTier() == wantedTier;
    }

    public static ItemCategory resolveCategory(ItemStack item) {
        return resolveBestEntry(item).map(ResolvedEntry::category).orElse(null);
    }

    public static double getWeightForGgRef(GgCraftRef ref) {
        if (ref == null) {
            return CategoryLoader.getDefaultWeight();
        }
        for (ItemCategory category : CategoryLoader.getAsList()) {
            for (CategoryItemEntry entry : category.getItems()) {
                var parsed = CategorySlugs.parseGgCraftRef(entry.getSlug());
                if (parsed.isPresent() && parsed.get().equals(ref)) {
                    return entry.getWeight();
                }
            }
        }
        return CategoryLoader.getDefaultWeight();
    }

    public static double getWeightForMagicRef(MagicCraftRef ref) {
        if (ref == null) {
            return CategoryLoader.getDefaultWeight();
        }
        for (ItemCategory category : CategoryLoader.getAsList()) {
            for (CategoryItemEntry entry : category.getItems()) {
                var parsed = CategorySlugs.parseMagicCraftRef(entry.getSlug());
                if (parsed.isPresent() && parsed.get().equals(ref)) {
                    return entry.getWeight();
                }
            }
        }
        return CategoryLoader.getDefaultWeight();
    }

    public static double getWeightForCraftRef(AcCraftRef ref) {
        if (ref == null) {
            return CategoryLoader.getDefaultWeight();
        }
        for (ItemCategory category : CategoryLoader.getAsList()) {
            for (CategoryItemEntry entry : category.getItems()) {
                var parsed = CategorySlugs.parseCraftRef(entry.getSlug());
                if (parsed.isPresent() && parsed.get().equals(ref)) {
                    return entry.getWeight();
                }
            }
        }
        return CategoryLoader.getDefaultWeight();
    }

    public static double getWeightForPath(String path) {
        if (path == null || path.isBlank()) {
            return CategoryLoader.getDefaultWeight();
        }
        ItemStack probe = TLibs.getItemAPI().getCreator().getItemFromPath(path);
        if (probe == null) {
            return CategoryLoader.getDefaultWeight();
        }
        return resolveBestDirectEntry(probe)
                .map(CategoryHandler::weightForResolvedEntry)
                .orElse(CategoryLoader.getDefaultWeight());
    }

    public static boolean canRevealItem(PlayerData playerData, ItemStack item) {
        if (ClueChecker.isClueItem(item)) {
            return false;
        }
        if (ItemValue.isBundle(item)) {
            return ItemValue.canRevealBundle(playerData, item);
        }
        return matchesAnyActive(playerData, item);
    }

    public static Set<String> getActiveCategoryIds(PlayerData playerData) {
        return playerData.getActiveCategories().stream().collect(Collectors.toSet());
    }

    public static double getPerItemValue(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        if (ClueChecker.isClueItem(item)) {
            return 0;
        }
        return ItemValue.compute(item);
    }

    public static double getTotalValue(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }

        if (ItemValue.isBundle(item)) {
            return ItemValue.getContentsValue(item);
        }

        return getPerItemValue(item) * item.getAmount();
    }

    public static List<String> buildDisplayLines(ItemCategory category) {
        List<String> lore = new ArrayList<>();
        if (category.isMoneyType()) {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.WHITE + "- Pouch and coins"));
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "  Steal value: "
                    + ThieveryTexts.GUI_SUCCESS + StealItemDisplay.formatValue(category.getAmountPerMoney())
                    + ThieveryTexts.MUTED + " per denar"));
            return lore;
        }
        for (ItemCategory.CategoryItemEntry entry : category.getItems()) {
            String slug = entry.getSlug();
            if (CategorySlugs.isMaterialSlug(slug)) {
                lore.addAll(buildMaterialSlugLines(slug, entry.getWeight()));
            } else if (CategorySlugs.isGgSlug(slug)) {
                CategorySlugs.parseGgCraftRef(slug).ifPresent(ref ->
                        lore.add(formatLine(ref.getDisplayName(), entry.getWeight())));
            } else if (CategorySlugs.isMagicSlug(slug)) {
                CategorySlugs.parseMagicCraftRef(slug).ifPresent(ref ->
                        lore.add(formatLine(ref.getDisplayName(), entry.getWeight())));
            } else if (CategorySlugs.isCraftSlug(slug)) {
                CategorySlugs.parseCraftRef(slug).ifPresent(ref ->
                        lore.addAll(buildAcCraftRefLines(ref, entry.getWeight())));
            } else if (CategorySlugs.isMmoTypeSlug(slug)) {
                lore.add(formatLine(resolveMmoTypeDisplayName(slug), entry.getWeight()));
            } else {
                ItemStack preview = TLibs.getItemAPI().getCreator().getItemFromPath(slug);
                String itemName = preview != null ? StringFormatter.getName(preview) : slug;
                lore.add(formatLine(itemName, entry.getWeight()));
            }
        }
        return lore;
    }

    private static List<String> buildMaterialSlugLines(String slug, double categoryBase) {
        List<String> lore = new ArrayList<>();
        if (!ThieveryBridge.isPluginReady()) {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Crafting data unavailable"));
            return lore;
        }

        String wantedType = CategorySlugs.materialType(slug);
        int wantedTier = CategorySlugs.materialTier(slug);
        IngredientType type = ThieveryBridge.getIngredientType(wantedType);

        for (Ingredient ingredient : ThieveryBridge.getAllIngredients()) {
            if (!ingredient.getIngredientData().getType().getId().equalsIgnoreCase(wantedType)) {
                continue;
            }
            if (!ingredient.getIngredientData().hasTier()
                    || ingredient.getIngredientData().getTier() != wantedTier) {
                continue;
            }
            String itemName = resolveIngredientName(ingredient);
            lore.add(formatLine(itemName, ItemValue.categoryWeightForIngredient(ingredient)));
        }

        Ingredient base = findBaseIngredient(wantedType, wantedTier);
        if (base != null) {
            String typeName = type != null ? type.getName() : wantedType;
            String label = "Tier " + toRoman(wantedTier) + " " + typeName + " Alloys";
            lore.add(formatLine(label, ItemValue.categoryWeightForIngredient(base)));
        }
        return lore;
    }

    private static Ingredient findBaseIngredient(String typeId, int tier) {
        if (tier <= 0) {
            return null;
        }
        for (Ingredient ingredient : ThieveryBridge.getAllIngredients()) {
            if (!ingredient.getIngredientData().getType().getId().equalsIgnoreCase(typeId)) {
                continue;
            }
            if (!ingredient.getIngredientData().canBeBase()) {
                continue;
            }
            if (ingredient.getIngredientData().hasTier()
                    && ingredient.getIngredientData().getTier() == tier) {
                return ingredient;
            }
        }
        return null;
    }

    public static List<String> buildAcCraftRefLines(AcCraftRef ref, double categoryBase) {
        List<String> lore = new ArrayList<>();
        if (!ThieveryBridge.isPluginReady()) {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Crafting data unavailable"));
            return lore;
        }

        StatTemplate template = ThieveryBridge.getStatTemplate(ref.getStatTemplate());
        String displayName = template != null ? template.getName() : formatId(ref.getStatTemplate());

        CraftingRecipe recipe = ThieveryBridge.findRecipeByStatTemplate(ref.getStatTemplate());
        double example = 0;
        if (recipe != null) {
            example = estimateCraftValue(recipe, ref.getTier());
        }

        lore.add(formatLine(displayName, example));
        return lore;
    }

    private static double estimateCraftValue(CraftingRecipe recipe, int tier) {
        double materialValue = 0;
        for (var entry : recipe.getRecipe().entrySet()) {
            String typeId = entry.getKey();
            int amount = entry.getValue();
            Ingredient reference = findReferenceIngredient(typeId, tier);
            if (reference != null) {
                materialValue += ItemValue.categoryWeightForIngredient(reference) * amount;
            }
        }
        return materialValue;
    }

    private static Ingredient findReferenceIngredient(String typeId, int tier) {
        for (Ingredient ingredient : ThieveryBridge.getAllIngredients()) {
            if (!ingredient.getIngredientData().getType().getId().equalsIgnoreCase(typeId)) {
                continue;
            }
            if (ingredient.getIngredientData().hasTier()
                    && ingredient.getIngredientData().getTier() == tier) {
                return ingredient;
            }
        }
        return null;
    }

    private static String resolveIngredientName(Ingredient ingredient) {
        ItemStack preview = TLibs.getItemAPI().getCreator().getItemFromPath(ingredient.getPath());
        if (preview != null) {
            return StringFormatter.getName(preview);
        }
        return formatId(ingredient.getId());
    }

    public static Type lookupMmoType(String typeId) {
        if (typeId == null || typeId.isBlank() || MMOItems.plugin == null) {
            return null;
        }
        var types = MMOItems.plugin.getTypes();
        Type type = types.get(typeId);
        if (type != null) {
            return type;
        }
        return types.get(typeId.toUpperCase(Locale.ROOT));
    }

    public static String resolveMmoTypeDisplayName(String slug) {
        String typeId = CategorySlugs.mmoTypeId(slug);
        if (typeId == null) {
            return slug;
        }
        Type type = lookupMmoType(typeId);
        if (type != null && type.getName() != null && !type.getName().isBlank()) {
            return type.getName();
        }
        return typeId;
    }

    private static String formatId(String id) {
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private static String formatLine(String itemName, double weight) {
        return ThieveryTexts.formatGui(ThieveryTexts.WHITE + "- " + itemName + " " + ThieveryTexts.MUTED + "("
                + ThieveryTexts.GUI_WARN + "value: " + ThieveryTexts.GUI_SUCCESS
                + StealItemDisplay.formatValue(weight) + ThieveryTexts.GUI_WARN + "/item"
                + ThieveryTexts.MUTED + ")");
    }

    private static String toRoman(int tier) {
        return switch (tier) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(tier);
        };
    }
}
