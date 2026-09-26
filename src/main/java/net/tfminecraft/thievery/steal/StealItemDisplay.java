package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.CategoryHandler;
import net.tfminecraft.thievery.category.ItemValue;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.utils.Keys;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class StealItemDisplay {

    public record ChestCluePreviewContext(
            Player player,
            int dexterity,
            double lockpickStrength,
            double sessionRisk,
            int successfulClueDrops,
            boolean criticalRisk
    ) {}

    private StealItemDisplay() {}

    public static int computeDisplayAmount(ItemStack realItem, double budgetRemaining, PlayerData thiefData) {
        if (realItem == null || realItem.getType().isAir()) {
            return 0;
        }
        if (ItemValue.isBundle(realItem)) {
            if (ItemValue.hasStealableContents(thiefData, realItem, budgetRemaining)) {
                return 1;
            }
            int takeable = StealBudget.computeTakeableAmount(realItem, budgetRemaining);
            return takeable > 0 ? 1 : 0;
        }
        if (!CategoryHandler.canRevealItem(thiefData, realItem)) {
            return 0;
        }
        int takeable = StealBudget.computeTakeableAmount(realItem, budgetRemaining);
        if (takeable <= 0) {
            return 0;
        }
        return realItem.getAmount();
    }

    public static ItemStack buildRepresentation(ItemStack realItem, double budgetRemaining, PlayerData thiefData) {
        return buildRepresentation(realItem, budgetRemaining, thiefData, null);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static ItemStack buildRepresentation(ItemStack realItem, double budgetRemaining, PlayerData thiefData,
            ChestCluePreviewContext cluePreview) {
        if (!CategoryHandler.canRevealItem(thiefData, realItem)) {
            return StealGui.createHiddenPane();
        }

        int displayAmount = computeDisplayAmount(realItem, budgetRemaining, thiefData);
        if (displayAmount <= 0) {
            return null;
        }

        ItemStack display;
        if (ItemValue.isBundle(realItem)) {
            display = ItemValue.buildDisplayBundle(thiefData, realItem);
        } else {
            display = realItem.clone();
            display.setAmount(displayAmount);
        }

        ItemMeta meta = display.getItemMeta();

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        if (ItemValue.isBundle(realItem)) {
            List<String> contentsLore = ItemValue.getRevealableContentsLore(thiefData, realItem);
            if (!contentsLore.isEmpty()) {
                lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Items:"));
                lore.addAll(contentsLore);
                lore.add("");
            }
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.GUI_WARN + "Total Value: " + ThieveryTexts.GUI_SUCCESS
                    + formatValue(CategoryHandler.getTotalValue(realItem))));
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.GUI_WARN + "Click " + ThieveryTexts.MUTED + "to take one item"));
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.GUI_WARN + "Shift-Click " + ThieveryTexts.MUTED + "to take all you can"));
        } else {
            int takeable = StealBudget.computeTakeableAmount(realItem, budgetRemaining);
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.GUI_WARN + "Total Value: " + ThieveryTexts.GUI_SUCCESS
                    + formatValue(CategoryHandler.getTotalValue(display))));
            if (takeable < realItem.getAmount()) {
                lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Can take up to " + ThieveryTexts.GUI_INFO + takeable
                        + " " + ThieveryTexts.MUTED + "with current budget"));
            }
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.GUI_WARN + "Click " + ThieveryTexts.MUTED + "to take "
                    + ThieveryTexts.GUI_INFO + "one"));
            if (displayAmount > 1) {
                lore.add(ThieveryTexts.formatGui(ThieveryTexts.GUI_WARN + "Shift-Click " + ThieveryTexts.MUTED + "to take "
                        + ThieveryTexts.GUI_INFO + "all"));
            }
        }
        appendCluePreviewLore(lore, realItem, budgetRemaining, thiefData, cluePreview);
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private static void appendCluePreviewLore(List<String> lore, ItemStack realItem, double budgetRemaining,
            PlayerData thiefData, ChestCluePreviewContext cluePreview) {
        if (cluePreview == null) {
            return;
        }

        StealTakePreview.TakeValues values = StealTakePreview.estimate(
                cluePreview.player(), thiefData, realItem, budgetRemaining);

        boolean guaranteed = cluePreview.successfulClueDrops() < Cache.minCluesContainer;
        RiskCalculator.TakeCluePreview one = RiskCalculator.computeTakeCluePreview(
                cluePreview.sessionRisk(), values.valueOne(), cluePreview.dexterity(),
                cluePreview.lockpickStrength(), guaranteed, cluePreview.criticalRisk());
        RiskCalculator.TakeCluePreview all = RiskCalculator.computeTakeCluePreview(
                cluePreview.sessionRisk(), values.valueAll(), cluePreview.dexterity(),
                cluePreview.lockpickStrength(), guaranteed, cluePreview.criticalRisk());

        lore.add("");
        if (guaranteed) {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "First Take - Guaranteed Clue"));
        }
        lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Clue chance: "
                + formatClueLine(one.clueChance(), one.criticalOnTake())));
        if (shouldShowAllPreview(values)) {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Clue chance" + formatAllSuffix()
                    + formatClueLine(all.clueChance(), all.criticalOnTake())));
        }
    }

    private static String formatClueLine(double clueChance, double criticalOnTake) {
        return RiskCalculator.formatPercentWhole(clueChance)
                + ThieveryTexts.MUTED + " (critical "
                + RiskCalculator.formatPercentWhole(criticalOnTake) + ")";
    }

    private static boolean shouldShowAllPreview(StealTakePreview.TakeValues values) {
        return values.valueAll() > values.valueOne();
    }

    private static String formatAllSuffix() {
        return ThieveryTexts.MUTED + " (" + ThieveryTexts.GUI_WARN + "all" + ThieveryTexts.MUTED + ")" + ThieveryTexts.MUTED + ": ";
    }

    public static boolean isStealPane(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(Keys.stealUnknown, org.bukkit.persistence.PersistentDataType.BYTE)
                || pdc.has(Keys.stealFiller, org.bukkit.persistence.PersistentDataType.BYTE)
                || pdc.has(Keys.stealNothing, org.bukkit.persistence.PersistentDataType.BYTE)
                || pdc.has(Keys.stealHidden, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    public static String formatValue(double value) {
        return String.format("%.2f", value);
    }

    public static String formatTimeRemaining(long remainingMs) {
        long totalSeconds = Math.max(0L, remainingMs / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + String.format("%02d", seconds);
    }
}
