package net.tfminecraft.thievery.category;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.tfminecraft.tlibs.socket.GemSocketsNbtEditor;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
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
import net.tfminecraft.thievery.category.AcCraftRef;
import net.tfminecraft.thievery.category.ItemCategory;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.category.CategoryHandler;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.steal.StealItemDisplay;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.steal.StealTakeHandler;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class ItemValue {

    private ItemValue() {
    }



    public static double compute(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        if (ClueChecker.isClueItem(item)) {
            return 0;
        }
        if (DenarMoney.isMoney(item)) {
            return DenarMoney.stealPerItem(item);
        }
        double gems = gemAddon(item);
        if (MagicCraftRef.fromItem(item).isPresent()) {
            double base = categoryBase(item);
            int band = MagicCraftRef.highestAuraBand(item);
            return applyPercent(base, Cache.auraPercent(band)) + gems;
        }
        if (ThieveryBridge.isPluginReady()) {
            CraftProvenance provenance = ThieveryBridge.readProvenance(item);
            if (provenance != null) {
                double materials = compositionForProvenance(provenance);
                return applyPercent(materials, Cache.qualityPercent(provenance.getQualityId())) + gems;
            }
            Alloy alloy = ThieveryBridge.resolveAlloy(item);
            if (alloy != null) {
                return compositionForAlloy(alloy) + gems;
            }
        }
        return categoryBase(item) + gems;
    }

    public static double categoryWeightForIngredient(Ingredient ingredient) {
        if (ingredient == null) {
            return Cache.defaultItemValue;
        }
        String path = ingredient.getPath();
        if (path == null || path.isBlank()) {
            return Cache.defaultItemValue;
        }
        ItemStack preview = TLibs.getItemAPI().getCreator().getItemFromPath(path);
        if (preview == null) {
            return Cache.defaultItemValue;
        }
        return CategoryHandler.resolveItemWeight(preview);
    }

    public static boolean usesCompositionValue(ItemStack item) {
        if (!ThieveryBridge.isPluginReady() || item == null || item.getType().isAir()) {
            return false;
        }
        if (MagicCraftRef.fromItem(item).isPresent()) {
            return false;
        }
        return ThieveryBridge.readProvenance(item) != null
                || ThieveryBridge.resolveAlloy(item) != null;
    }

    private static double categoryBase(ItemStack item) {
        return CategoryHandler.resolveItemWeight(item);
    }

    public static double compositionForAlloy(Alloy alloy) {
        if (alloy == null || alloy.getData() == null) {
            return 0;
        }
        AlloyRecipe recipe = alloy.getData().getRecipe();
        if (recipe == null) {
            return 0;
        }
        double total = 0;
        Ingredient base = ThieveryBridge.getIngredientById(recipe.getBaseId());
        if (base != null) {
            total += categoryWeightForIngredient(base);
        }
        for (String catalystId : recipe.getCatalystIds()) {
            Ingredient catalyst = ThieveryBridge.getIngredientById(catalystId);
            if (catalyst != null) {
                total += categoryWeightForIngredient(catalyst);
            }
        }
        return total;
    }

    public static double compositionForProvenance(CraftProvenance provenance) {
        if (provenance == null) {
            return 0;
        }
        return sumProvenanceInputs(provenance.getInputs());
    }

    private static double applyPercent(double base, double percent) {
        return base * (1.0 + percent);
    }

    private static String formatPercent(double percent) {
        double shown = percent * 100.0;
        String sign = shown > 0 ? "+" : "";
        if (Math.abs(shown - Math.round(shown)) < 0.05) {
            return sign + (int) Math.round(shown) + "%";
        }
        return sign + String.format(java.util.Locale.ROOT, "%.1f", shown) + "%";
    }

    private static String auraNumeral(int band) {
        return switch (band) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "-";
        };
    }

    private static double sumProvenanceInputs(List<CraftInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (CraftInput input : inputs) {
            String kind = input.getKind().toLowerCase();
            if (kind.equals("ingredient")) {
                Ingredient ing = ThieveryBridge.getIngredientById(input.getId());
                if (ing != null) {
                    total += categoryWeightForIngredient(ing) * input.getAmount();
                }
            } else if (kind.equals("alloy")) {
                Alloy alloy = AlloyManager.getAlloyById(input.getId());
                if (alloy != null) {
                    total += compositionForAlloy(alloy) * input.getAmount();
                }
            }
        }
        return total;
    }

    private static double gemAddon(ItemStack item) {
        if (!ItemValue.hasType(item)) {
            return 0;
        }
        var sockets = GemSocketsNbtEditor.getSockets(item);
        if (sockets == null) {
            return 0;
        }
        double total = 0;
        for (GemstoneData gem : sockets.getGems()) {
            String path = "m." + gem.getMMOItemType().toLowerCase() + "." + gem.getMMOItemID().toLowerCase();
            total += CategoryLoader.getWeightForPath(path);
        }
        return total;
    }


    public static boolean hasType(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        return NBTItem.get(item).hasType();
    }

    public enum BundleTakeMode {
        ONE,
        GREEDY
    }

    public static boolean isBundle(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getItemMeta() instanceof BundleMeta;
    }

    public static boolean canRevealBundle(PlayerData thiefData, ItemStack bundle) {
        if (!isBundle(bundle)) {
            return CategoryHandler.canRevealItem(thiefData, bundle);
        }
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            return canRevealContainer(thiefData, bundle);
        }
        for (ItemStack inner : meta.getItems()) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            if (CategoryHandler.canRevealItem(thiefData, inner)) {
                return true;
            }
        }
        return canRevealContainer(thiefData, bundle);
    }

    public static double getInnerContentsValue(ItemStack bundle) {
        if (!isBundle(bundle)) {
            return CategoryHandler.getPerItemValue(bundle) * bundle.getAmount();
        }
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            return 0;
        }
        double total = 0;
        for (ItemStack inner : meta.getItems()) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            total += CategoryHandler.getPerItemValue(inner) * inner.getAmount();
        }
        return total;
    }

    public static double getContentsValue(ItemStack bundle) {
        if (!isBundle(bundle)) {
            return CategoryHandler.getTotalValue(bundle);
        }
        return CategoryHandler.getPerItemValue(bundle) + getInnerContentsValue(bundle);
    }

    public static double getRevealableContentsValue(PlayerData thiefData, ItemStack bundle) {
        if (!isBundle(bundle)) {
            return CategoryHandler.canRevealItem(thiefData, bundle)
                    ? CategoryHandler.getTotalValue(bundle) : 0;
        }
        double total = canRevealContainer(thiefData, bundle) ? CategoryHandler.getPerItemValue(bundle) : 0;
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            return total;
        }
        for (ItemStack inner : meta.getItems()) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                continue;
            }
            total += CategoryHandler.getPerItemValue(inner) * inner.getAmount();
        }
        return total;
    }

    public static ItemStack buildDisplayBundle(PlayerData thiefData, ItemStack realBundle) {
        if (!isBundle(realBundle)) {
            return null;
        }
        BundleMeta sourceMeta = (BundleMeta) realBundle.getItemMeta();
        if (sourceMeta == null) {
            return null;
        }

        List<ItemStack> revealable = new ArrayList<>();
        if (sourceMeta.hasItems()) {
            for (ItemStack inner : sourceMeta.getItems()) {
                if (inner == null || inner.getType().isAir()) {
                    continue;
                }
                if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                    continue;
                }
                revealable.add(inner.clone());
            }
        }
        if (revealable.isEmpty() && !canRevealContainer(thiefData, realBundle)) {
            return null;
        }

        ItemStack display = realBundle.clone();
        BundleMeta displayMeta = (BundleMeta) display.getItemMeta();
        if (displayMeta == null) {
            return null;
        }
        displayMeta.setItems(revealable.isEmpty() ? null : revealable);
        display.setItemMeta(displayMeta);
        return display;
    }

    public static List<String> getRevealableContentsLore(PlayerData thiefData, ItemStack bundle) {
        List<String> lore = new ArrayList<>();
        if (!isBundle(bundle)) {
            return lore;
        }
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            return lore;
        }
        for (ItemStack inner : meta.getItems()) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                continue;
            }
            lore.add(ThieveryTexts.formatDisplay(ThieveryTexts.WHITE + "- " + StringFormatter.getName(inner)
                    + " " + ThieveryTexts.MUTED + "x" + inner.getAmount()));
        }
        return lore;
    }

    public static boolean hasStealableContents(PlayerData thiefData, ItemStack bundle, double capacityRemaining) {
        return canStealAnything(thiefData, bundle, capacityRemaining);
    }

    public static boolean canStealAnything(PlayerData thiefData, ItemStack bundle, double capacityRemaining) {
        if (!isBundle(bundle)) {
            return false;
        }
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            return false;
        }
        for (ItemStack inner : meta.getItems()) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                continue;
            }
            double perItem = CategoryHandler.getPerItemValue(inner);
            if (perItem <= 0 || perItem <= capacityRemaining) {
                return true;
            }
        }
        return false;
    }

    public static boolean allInnersStealable(PlayerData thiefData, ItemStack bundle) {
        if (!isBundle(bundle)) {
            return false;
        }
        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            return true;
        }
        for (ItemStack inner : meta.getItems()) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                return false;
            }
        }
        return true;
    }

    public static boolean canFitBundleItem(Player player, ItemStack bundle) {
        return StealTakeHandler.maxFitInPlayerInventory(player, bundle, 1) >= 1;
    }

    public static double estimateOneTakeValue(ItemStack bundle, PlayerData thiefData, double capacityRemaining) {
        if (!isBundle(bundle)) {
            return CategoryHandler.getPerItemValue(bundle);
        }

        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            return 0;
        }

        double sum = 0;
        int count = 0;
        for (ItemStack inner : meta.getItems()) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                continue;
            }
            double perItem = CategoryHandler.getPerItemValue(inner);
            if (perItem > 0 && perItem > capacityRemaining) {
                continue;
            }
            sum += perItem > 0 ? perItem : 0;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    public static double estimateGreedyTakeValue(ItemStack bundle, Player player, PlayerData thiefData,
            double capacityRemaining) {
        if (!isBundle(bundle)) {
            return CategoryHandler.getTotalValue(bundle);
        }

        if (allInnersStealable(thiefData, bundle)
                && CategoryHandler.getTotalValue(bundle) <= capacityRemaining
                && canFitBundleItem(player, bundle)) {
            return CategoryHandler.getTotalValue(bundle);
        }

        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            return 0;
        }

        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack inner : meta.getItems()) {
            contents.add(inner == null ? null : inner.clone());
        }

        double budget = capacityRemaining;
        double valueTaken = 0;
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < contents.size(); i++) {
                ItemStack inner = contents.get(i);
                if (inner == null || inner.getType().isAir()) {
                    continue;
                }
                if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                    continue;
                }

                double perItem = CategoryHandler.getPerItemValue(inner);
                if (perItem > 0 && perItem > budget) {
                    continue;
                }

                if (StealTakeHandler.maxFitInPlayerInventory(player, inner, 1) < 1) {
                    return valueTaken;
                }

                if (inner.getAmount() <= 1) {
                    contents.set(i, null);
                } else {
                    inner.setAmount(inner.getAmount() - 1);
                }

                if (perItem > 0) {
                    budget -= perItem;
                }
                valueTaken += perItem > 0 ? perItem : 0;
                progress = true;
            }
        }

        return valueTaken;
    }

    public static BundleTakeResult takeFromBundle(ItemStack bundle, Player player, PlayerData thiefData,
            double capacityRemaining, BundleTakeMode mode) {
        if (!isBundle(bundle)) {
            return BundleTakeResult.none(bundle);
        }

        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null) {
            return BundleTakeResult.none(bundle);
        }

        if (mode == BundleTakeMode.GREEDY
                && allInnersStealable(thiefData, bundle)
                && CategoryHandler.getTotalValue(bundle) <= capacityRemaining
                && canFitBundleItem(player, bundle)) {
            ItemStack toGive = bundle.clone();
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(toGive);
            if (!leftovers.isEmpty()) {
                return BundleTakeResult.none(bundle);
            }
            double valueTaken = CategoryHandler.getTotalValue(bundle);
            return BundleTakeResult.removedFromSource(valueTaken);
        }

        if (!meta.hasItems()) {
            return BundleTakeResult.none(bundle);
        }

        List<ItemStack> contents = new ArrayList<>();
        for (ItemStack inner : meta.getItems()) {
            contents.add(inner == null ? null : inner.clone());
        }

        if (mode == BundleTakeMode.ONE) {
            return takeOneRandomInner(bundle, contents, player, thiefData, capacityRemaining);
        }

        return takeGreedyInners(bundle, contents, player, thiefData, capacityRemaining);
    }

    private static BundleTakeResult takeOneRandomInner(ItemStack bundle, List<ItemStack> contents, Player player,
            PlayerData thiefData, double capacityRemaining) {
        List<Integer> affordable = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            ItemStack inner = contents.get(i);
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                continue;
            }
            double perItem = CategoryHandler.getPerItemValue(inner);
            if (perItem > 0 && perItem > capacityRemaining) {
                continue;
            }
            affordable.add(i);
        }
        if (affordable.isEmpty()) {
            return BundleTakeResult.none(bundle);
        }

        int index = affordable.get(ThreadLocalRandom.current().nextInt(affordable.size()));
        ItemStack inner = contents.get(index);
        double perItem = CategoryHandler.getPerItemValue(inner);

        ItemStack toGive = inner.clone();
        toGive.setAmount(1);
        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(toGive);
        if (!leftovers.isEmpty()) {
            return BundleTakeResult.none(bundle);
        }

        if (inner.getAmount() <= 1) {
            contents.set(index, null);
        } else {
            inner.setAmount(inner.getAmount() - 1);
        }

        double valueTaken = perItem > 0 ? perItem : 0;
        return finish(bundle, contents, valueTaken, true);
    }

    private static BundleTakeResult takeGreedyInners(ItemStack bundle, List<ItemStack> contents, Player player,
            PlayerData thiefData, double capacityRemaining) {
        double budget = capacityRemaining;
        double valueTaken = 0;
        boolean anyTaken = false;

        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < contents.size(); i++) {
                ItemStack inner = contents.get(i);
                if (inner == null || inner.getType().isAir()) {
                    continue;
                }
                if (!CategoryHandler.canRevealItem(thiefData, inner)) {
                    continue;
                }

                double perItem = CategoryHandler.getPerItemValue(inner);
                if (perItem > 0 && perItem > budget) {
                    continue;
                }

                ItemStack toGive = inner.clone();
                toGive.setAmount(1);

                HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(toGive);
                if (!leftovers.isEmpty()) {
                    return finish(bundle, contents, valueTaken, anyTaken);
                }

                if (inner.getAmount() <= 1) {
                    contents.set(i, null);
                } else {
                    inner.setAmount(inner.getAmount() - 1);
                }

                if (perItem > 0) {
                    budget -= perItem;
                }
                valueTaken += perItem > 0 ? perItem : 0;
                anyTaken = true;
                progress = true;
            }
        }

        return finish(bundle, contents, valueTaken, anyTaken);
    }

    private static BundleTakeResult finish(ItemStack bundle, List<ItemStack> contents, double valueTaken,
            boolean anyTaken) {
        ItemStack updated = bundle.clone();
        BundleMeta meta = (BundleMeta) updated.getItemMeta();
        if (meta == null) {
            return new BundleTakeResult(bundle, valueTaken, anyTaken, false);
        }

        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack inner : contents) {
            if (inner != null && !inner.getType().isAir()) {
                remaining.add(inner);
            }
        }
        meta.setItems(remaining.isEmpty() ? null : remaining);
        updated.setItemMeta(meta);
        return new BundleTakeResult(updated, valueTaken, anyTaken, false);
    }

    private static boolean canRevealContainer(PlayerData thiefData, ItemStack bundle) {
        if (ClueChecker.isClueItem(bundle)) {
            return false;
        }
        var category = CategoryHandler.resolveCategory(bundle);
        if (category == null) {
            return true;
        }
        return thiefData.isCategoryActive(category.getId());
    }

    public static final class BundleTakeResult {
        private final ItemStack updatedBundle;
        private final double valueTaken;
        private final boolean anyTaken;
        private final boolean removedFromSource;

        private BundleTakeResult(ItemStack updatedBundle, double valueTaken, boolean anyTaken,
                boolean removedFromSource) {
            this.updatedBundle = updatedBundle;
            this.valueTaken = valueTaken;
            this.anyTaken = anyTaken;
            this.removedFromSource = removedFromSource;
        }

        public static BundleTakeResult none(ItemStack bundle) {
            return new BundleTakeResult(bundle, 0, false, false);
        }

        public static BundleTakeResult removedFromSource(double valueTaken) {
            return new BundleTakeResult(null, valueTaken, true, true);
        }

        public ItemStack getUpdatedBundle() {
            return updatedBundle;
        }

        public double getValueTaken() {
            return valueTaken;
        }

        public boolean isAnyTaken() {
            return anyTaken;
        }

        public boolean isRemovedFromSource() {
            return removedFromSource;
        }
    }

    private static final String DIVIDER = ThieveryTexts.DARK + "§m" + repeat('─', 28);
    private static final String HEADER = ThieveryTexts.DARK + "§m" + repeat('━', 28);


    public static List<String> buildReport(ItemStack item) {
        List<String> lines = new ArrayList<>();
        lines.add(ThieveryTexts.formatDisplay(HEADER));
        lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.ACCENT + "§lItem Value Inspector"));
        lines.add(ThieveryTexts.formatDisplay(HEADER));

        if (item == null || item.getType().isAir()) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.ERROR + "No item in hand."));
            lines.add(ThieveryTexts.formatDisplay(HEADER));
            return lines;
        }

        if (ClueChecker.isClueItem(item)) {
            lines.add(line("Item", StringFormatter.getName(item)));
            lines.add(line("Path", pathOf(item)));
            lines.add(ThieveryTexts.formatDisplay(DIVIDER));
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "Clue items are never stealable."));
            lines.add(valueLine("Total", 0));
            lines.add(ThieveryTexts.formatDisplay(HEADER));
            return lines;
        }

        if (ItemValue.isBundle(item)) {
            appendBundleReport(lines, item);
            lines.add(ThieveryTexts.formatDisplay(HEADER));
            return lines;
        }

        appendItemHeader(lines, item);
        lines.add(ThieveryTexts.formatDisplay(DIVIDER));

        double categoryBase = appendCategorySection(lines, item);
        double composition = appendCompositionSection(lines, item);
        double aura = appendAuraSection(lines, item, categoryBase);
        double gemAddon = appendGemSection(lines, item);

        double perItem = categoryBase + composition + aura + gemAddon;
        double computed = ItemValue.compute(item);
        if (Math.abs(perItem - computed) > 0.001) {
            perItem = computed;
        }

        lines.add(ThieveryTexts.formatDisplay(DIVIDER));
        lines.add(totalLine("Per item", perItem, true));
        if (item.getAmount() > 1) {
            lines.add(totalLine("Stack total (×" + item.getAmount() + ")", perItem * item.getAmount(), true));
        }
        lines.add(ThieveryTexts.formatDisplay(HEADER));
        return lines;
    }

    private static void appendBundleReport(List<String> lines, ItemStack bundle) {
        appendItemHeader(lines, bundle);
        lines.add(ThieveryTexts.formatDisplay(DIVIDER));
        lines.add(sectionTitle("Bundle", CategoryHandler.getPerItemValue(bundle)));

        BundleMeta meta = (BundleMeta) bundle.getItemMeta();
        if (meta == null || !meta.hasItems()) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Empty bundle shell only."));
            lines.add(totalLine("Total", CategoryHandler.getTotalValue(bundle), true));
            return;
        }

        double innerTotal = 0;
        for (ItemStack inner : meta.getItems()) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            double innerValue = CategoryHandler.getPerItemValue(inner) * inner.getAmount();
            innerTotal += innerValue;
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  • " + ThieveryTexts.WHITE
                    + StringFormatter.getName(inner) + ThieveryTexts.MUTED + " ×" + inner.getAmount()
                    + ThieveryTexts.MUTED + "  →  " + ThieveryTexts.ACCENT
                    + StealItemDisplay.formatValue(innerValue)));
        }
        double shell = CategoryHandler.getPerItemValue(bundle);
        lines.add(ThieveryTexts.formatDisplay(DIVIDER));
        lines.add(valueLine("Bundle shell", shell));
        lines.add(valueLine("Contents", innerTotal));
        lines.add(totalLine("Total", shell + innerTotal, true));
    }

    private static void appendItemHeader(List<String> lines, ItemStack item) {
        lines.add(line("Item", StringFormatter.getName(item)));
        lines.add(line("Path", pathOf(item)));
        lines.add(line("Type", itemTypeLabel(item)));
        if (item.getAmount() > 1) {
            lines.add(line("Amount", String.valueOf(item.getAmount())));
        }
        appendMatchingCategories(lines, item);
    }

    private static void appendMatchingCategories(List<String> lines, ItemStack item) {
        String primaryId = CategoryHandler.resolveBestEntry(item)
                .map(resolved -> resolved.category().getId())
                .orElse(null);
        List<String> matches = new ArrayList<>();
        for (ItemCategory category : CategoryLoader.getAsList()) {
            if (CategoryHandler.matches(category, item)) {
                String id = category.getId();
                if (id.equals(primaryId)) {
                    id = id + " (primary)";
                }
                matches.add(id);
            }
        }
        if (matches.isEmpty()) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "Categories: "
                    + ThieveryTexts.WHITE + "none (uncategorized)"));
        } else {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "Categories: "
                    + ThieveryTexts.INFO + String.join(ThieveryTexts.MUTED + ", " + ThieveryTexts.INFO, matches)));
        }
    }

    private static double appendCategorySection(List<String> lines, ItemStack item) {
        ItemCategory category = CategoryHandler.resolveCategory(item);
        AcCraftRef crafted = CategoryHandler.resolveCraftedMatch(item);
        GgCraftRef gg = CategoryHandler.resolveGgMatch(item);
        MagicCraftRef magic = CategoryHandler.resolveMagicMatch(item);
        boolean compositionOnly = usesCompositionValue(item);
        double base = compositionOnly ? 0 : CategoryHandler.resolveItemWeight(item);

        if (category == null && crafted == null && gg == null && magic == null) {
            if (compositionOnly) {
                lines.add(sectionTitle("Category match", 0));
                lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED
                        + "  Slug weight ignored - value is composition below"));
                return 0;
            }
            lines.add(sectionTitle("Category base", Cache.defaultItemValue));
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  No category match - using default_item_value"));
            return Cache.defaultItemValue;
        }

        lines.add(sectionTitle(compositionOnly ? "Category match" : "Category base", base));
        if (category != null) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Category: " + ThieveryTexts.INFO + category.getId()));
        }
        if (crafted != null) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Craft: " + ThieveryTexts.INFO
                    + crafted.getRawId() + ThieveryTexts.MUTED + " ("
                    + crafted.getStatTemplate() + " tier " + crafted.getTier() + ")"));
        }
        if (gg != null) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Craft: " + ThieveryTexts.INFO
                    + gg.getRawId() + ThieveryTexts.MUTED + " ("
                    + gg.getGunType() + " tier " + gg.getTier() + ")"));
        }
        if (magic != null) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Craft: " + ThieveryTexts.INFO
                    + magic.getRawId() + ThieveryTexts.MUTED + " ("
                    + magic.getGearType() + " tier " + magic.getTier() + ")"));
        }
        if (compositionOnly) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED
                    + "  Slug weight ignored - value is composition below"));
        }
        return base;
    }

    private static double appendAuraSection(List<String> lines, ItemStack item, double slugBase) {
        if (MagicCraftRef.fromItem(item).isEmpty()) {
            return 0;
        }
        int band = MagicCraftRef.highestAuraBand(item);
        double percent = Cache.auraPercent(band);
        double extra = slugBase * percent;
        lines.add(ThieveryTexts.formatDisplay(DIVIDER));
        lines.add(sectionTitle("Aura " + auraNumeral(band), extra));
        lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Aura "
                + ThieveryTexts.WHITE + auraNumeral(band)
                + ThieveryTexts.MUTED + "  →  " + ThieveryTexts.ACCENT + formatPercent(percent)));
        return extra;
    }

    private static double appendCompositionSection(List<String> lines, ItemStack item) {
        if (MagicCraftRef.fromItem(item).isPresent()) {
            return 0;
        }
        if (!ThieveryBridge.isPluginReady()) {
            return 0;
        }

        CraftProvenance provenance = ThieveryBridge.readProvenance(item);
        if (provenance != null) {
            return appendCraftedAc(lines, provenance);
        }

        Alloy alloy = ThieveryBridge.resolveAlloy(item);
        if (alloy != null) {
            return appendAlloyAc(lines, alloy);
        }

        return 0;
    }

    private static double appendAlloyAc(List<String> lines, Alloy alloy) {
        lines.add(ThieveryTexts.formatDisplay(DIVIDER));
        AlloyRecipe recipe = alloy.getData() != null ? alloy.getData().getRecipe() : null;
        double total = compositionForAlloy(alloy);

        lines.add(sectionTitle("Alloy", total));
        lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Alloy: " + ThieveryTexts.WHITE + alloy.getId()));
        if (recipe != null) {
            Ingredient base = ThieveryBridge.getIngredientById(recipe.getBaseId());
            if (base != null) {
                lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  • Base " + ThieveryTexts.WHITE
                        + recipe.getBaseId() + ThieveryTexts.MUTED + "  →  " + ThieveryTexts.ACCENT
                        + StealItemDisplay.formatValue(categoryWeightForIngredient(base))));
            }
            for (String catalystId : recipe.getCatalystIds()) {
                Ingredient catalyst = ThieveryBridge.getIngredientById(catalystId);
                if (catalyst != null) {
                    lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  • Catalyst " + ThieveryTexts.WHITE
                            + catalystId + ThieveryTexts.MUTED + "  →  " + ThieveryTexts.ACCENT
                            + StealItemDisplay.formatValue(categoryWeightForIngredient(catalyst))));
                }
            }
        }
        return total;
    }

    private static double appendCraftedAc(List<String> lines, CraftProvenance provenance) {
        List<String> details = new ArrayList<>();
        double materials = 0;

        CraftingRecipe recipe = ThieveryBridge.getRecipeById(provenance.getRecipeId());
        if (recipe != null) {
            details.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Recipe: " + ThieveryTexts.WHITE
                    + provenance.getRecipeId() + ThieveryTexts.MUTED + " (" + recipe.getCategoryId() + ")"));
        }

        for (CraftInput input : provenance.getInputs()) {
            String kind = input.getKind().toLowerCase();
            if (kind.equals("ingredient")) {
                Ingredient ing = ThieveryBridge.getIngredientById(input.getId());
                if (ing != null) {
                    double part = categoryWeightForIngredient(ing) * input.getAmount();
                    materials += part;
                    details.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  • " + ThieveryTexts.WHITE
                            + input.getId() + " ×" + input.getAmount() + ThieveryTexts.MUTED + "  →  "
                            + ThieveryTexts.ACCENT + StealItemDisplay.formatValue(part)));
                }
            } else if (kind.equals("alloy")) {
                Alloy alloy = AlloyManager.getAlloyById(input.getId());
                if (alloy != null) {
                    double part = compositionForAlloy(alloy) * input.getAmount();
                    materials += part;
                    details.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  • Alloy " + ThieveryTexts.WHITE
                            + input.getId() + " ×" + input.getAmount() + ThieveryTexts.MUTED + "  →  "
                            + ThieveryTexts.ACCENT + StealItemDisplay.formatValue(part)));
                }
            }
        }

        double percent = Cache.qualityPercent(provenance.getQualityId());
        Quality qualityDef = ThieveryBridge.getQualityById(provenance.getQualityId());
        if (qualityDef != null || percent != 0) {
            String label = qualityDef != null ? qualityDef.getName() : provenance.getQualityId();
            details.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Quality: " + ThieveryTexts.WHITE
                    + label + ThieveryTexts.MUTED + "  →  " + ThieveryTexts.ACCENT + formatPercent(percent)));
        }

        double extra = materials * percent;
        double total = applyPercent(materials, percent);
        lines.add(ThieveryTexts.formatDisplay(DIVIDER));
        lines.add(sectionTitle("Crafted", total));
        lines.addAll(details);
        if (extra != 0) {
            lines.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  Quality extra  →  "
                    + ThieveryTexts.ACCENT + StealItemDisplay.formatValue(extra)));
        }
        return total;
    }

    private static double appendGemSection(List<String> lines, ItemStack item) {
        if (!ItemValue.hasType(item)) {
            return 0;
        }
        var sockets = GemSocketsNbtEditor.getSockets(item);
        if (sockets == null || sockets.getGems().isEmpty()) {
            return 0;
        }

        List<String> details = new ArrayList<>();
        double total = 0;
        for (GemstoneData gem : sockets.getGems()) {
            String path = "m." + gem.getMMOItemType().toLowerCase() + "." + gem.getMMOItemID().toLowerCase();
            double gemValue = CategoryLoader.getWeightForPath(path);
            total += gemValue;

            String gemName = path;
            ItemStack probe = TLibs.getItemAPI().getCreator().getItemFromPath(path);
            if (probe != null) {
                gemName = StringFormatter.getName(probe);
            }

            ItemCategory gemCategory = probe != null ? CategoryHandler.resolveFirstMatch(probe) : null;
            String categoryNote = gemCategory != null
                    ? ThieveryTexts.MUTED + " [" + ThieveryTexts.INFO + gemCategory.getId() + ThieveryTexts.MUTED + "]"
                    : ThieveryTexts.MUTED + " [default]";

            details.add(ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + "  • " + ThieveryTexts.WHITE + gemName
                    + categoryNote + ThieveryTexts.MUTED + "  →  " + ThieveryTexts.ACCENT
                    + StealItemDisplay.formatValue(gemValue)));
            details.add(ThieveryTexts.formatDisplay(ThieveryTexts.DARK + "    " + path));
        }

        lines.add(ThieveryTexts.formatDisplay(DIVIDER));
        lines.add(sectionTitle("Socketed gems", total));
        lines.addAll(details);
        return total;
    }

    private static String pathOf(ItemStack item) {
        return TLibs.getItemAPI().getChecker().getAsStringPath(item);
    }

    private static String itemTypeLabel(ItemStack item) {
        if (ThieveryBridge.isPluginReady()) {
            if (ThieveryBridge.readProvenance(item) != null) {
                return "Crafted";
            }
            if (ThieveryBridge.resolveAlloy(item) != null) {
                return "Alloy";
            }
            Ingredient ing = ThieveryBridge.resolveIngredient(item);
            if (ing != null) {
                return "Material (" + ing.getIngredientData().getType().getId() + ")";
            }
        }
        if (ItemValue.hasType(item)) {
            var sockets = GemSocketsNbtEditor.getSockets(item);
            if (sockets != null && !sockets.getGems().isEmpty()) {
                return "MMO Item (socketed)";
            }
            return "MMO Item";
        }
        String path = pathOf(item);
        if (path.startsWith("m.")) {
            return "MMO Item";
        }
        if (path.startsWith("ia.")) {
            return "ItemsAdder";
        }
        return "Vanilla / Other";
    }

    private static String line(String label, String value) {
        return ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + label + ": " + ThieveryTexts.WHITE + value);
    }

    private static String sectionTitle(String label, double value) {
        return ThieveryTexts.formatDisplay(ThieveryTexts.WARN + label + repeat(' ', Math.max(1, 18 - label.length()))
                + ThieveryTexts.ACCENT + StealItemDisplay.formatValue(value));
    }

    private static String valueLine(String label, double value) {
        return ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + label + ": " + ThieveryTexts.ACCENT
                + StealItemDisplay.formatValue(value));
    }

    private static String totalLine(String label, double value, boolean bold) {
        String weight = bold ? "§l" : "";
        return ThieveryTexts.formatDisplay(ThieveryTexts.MUTED + label + ": " + weight + ThieveryTexts.ACCENT
                + StealItemDisplay.formatValue(value));
    }

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(count);
    }
}
