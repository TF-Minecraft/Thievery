package net.tfminecraft.thievery.steal;

import java.util.HashMap;
import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.category.CategoryHandler;
import net.tfminecraft.thievery.category.ItemValue;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.steal.source.StealSource;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class StealTakeHandler {

    @FunctionalInterface
    public interface TakeCallback {
        void onAfterTake(Player player, ItemStack taken, double valueTaken, boolean fromBundle, int logicalSlot);
    }

    private StealTakeHandler() {}

    public static boolean performTake(Player robber, StealSource source, StealBudget budget, int logicalSlot,
            Inventory guiInv, int guiSlot, ClickType clickType, ItemStack clickedItem, Runnable refreshGui) {
        return performTake(robber, source, budget, logicalSlot, guiInv, guiSlot, clickType, clickedItem, refreshGui,
                null);
    }

    public static boolean performTake(Player robber, StealSource source, StealBudget budget, int logicalSlot,
            Inventory guiInv, int guiSlot, ClickType clickType, ItemStack clickedItem, Runnable refreshGui,
            TakeCallback callback) {
        ItemStack realItem = source.getItem(logicalSlot);
        if (realItem == null || realItem.getType().isAir()) {
            guiInv.setItem(guiSlot, null);
            robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "The item is no longer there."));
            return false;
        }
        if (ClueChecker.isClueItem(realItem)) {
            return false;
        }

        PlayerData thiefData = Thievery.getPlayerManager().get(robber.getUniqueId());

        if (ItemValue.isBundle(realItem)
                && ItemValue.hasStealableContents(thiefData, realItem, budget.getRemaining())) {
            return performBundleTake(robber, source, budget, logicalSlot, guiInv, guiSlot, realItem, thiefData,
                    clickType, refreshGui, callback);
        }

        int maxByBudget = StealBudget.computeTakeableAmount(realItem, budget.getRemaining());
        if (maxByBudget <= 0) {
            guiInv.setItem(guiSlot, null);
            refreshGui.run();
            return false;
        }

        ClickType effectiveClick = clickType;
        if (clickType == ClickType.SHIFT_LEFT && (clickedItem == null || clickedItem.getAmount() <= 1)) {
            effectiveClick = ClickType.LEFT;
        }

        int takeAmount;
        if (effectiveClick == ClickType.SHIFT_LEFT) {
            int maxFit = maxFitInPlayerInventory(robber, realItem, maxByBudget);
            if (maxFit <= 0) {
                robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You don't have enough inventory space!"));
                return false;
            }
            takeAmount = Math.min(realItem.getAmount(), Math.min(maxByBudget, maxFit));
        } else {
            takeAmount = Math.min(1, Math.min(realItem.getAmount(), maxByBudget));
            if (maxFitInPlayerInventory(robber, realItem, 1) < 1) {
                robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You don't have enough inventory space!"));
                return false;
            }
        }

        ItemStack toGive = realItem.clone();
        toGive.setAmount(takeAmount);

        HashMap<Integer, ItemStack> leftovers = robber.getInventory().addItem(toGive);
        if (!leftovers.isEmpty()) {
            robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You don't have enough inventory space!"));
            return false;
        }

        if (realItem.getAmount() <= takeAmount) {
            source.setItem(logicalSlot, null);
        } else {
            realItem.setAmount(realItem.getAmount() - takeAmount);
        }

        double valueTaken = CategoryHandler.getTotalValue(toGive);
        budget.addUsed(valueTaken);
        if (callback != null) {
            callback.onAfterTake(robber, toGive, valueTaken, false, logicalSlot);
        }
        refreshGui.run();
        return true;
    }

    private static boolean performBundleTake(Player robber, StealSource source, StealBudget budget, int logicalSlot,
            Inventory guiInv, int guiSlot, ItemStack realItem, PlayerData thiefData, ClickType clickType,
            Runnable refreshGui, TakeCallback callback) {
        ItemValue.BundleTakeMode mode = clickType == ClickType.SHIFT_LEFT
                ? ItemValue.BundleTakeMode.GREEDY
                : ItemValue.BundleTakeMode.ONE;

        ItemValue.BundleTakeResult result = ItemValue.takeFromBundle(
                realItem, robber, thiefData, budget.getRemaining(), mode);
        if (!result.isAnyTaken()) {
            robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You don't have enough inventory space!"));
            return false;
        }

        if (result.isRemovedFromSource()) {
            source.setItem(logicalSlot, null);
        } else {
            source.setItem(logicalSlot, result.getUpdatedBundle());
        }
        budget.addUsed(result.getValueTaken());
        if (callback != null) {
            callback.onAfterTake(robber, realItem, result.getValueTaken(), true, logicalSlot);
        }
        refreshGui.run();
        return true;
    }

    public static int maxFitInPlayerInventory(Player player, ItemStack prototype, int maxAttempt) {
        if (maxAttempt <= 0) {
            return 0;
        }
        int maxStack = prototype.getMaxStackSize();
        int fit = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                fit += maxStack;
            } else if (slot.isSimilar(prototype) && slot.getAmount() < maxStack) {
                fit += maxStack - slot.getAmount();
            }
            if (fit >= maxAttempt) {
                return maxAttempt;
            }
        }
        return Math.min(maxAttempt, fit);
    }
}
