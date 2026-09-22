package net.tfminecraft.thievery.steal;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.thievery.category.CategoryHandler;

public final class StealBudget {

    private final double capacity;
    private double used;

    public StealBudget(double capacity) {
        this.capacity = Math.max(0.0, capacity);
    }

    public static int computeTakeableAmount(ItemStack realItem, double capacityRemaining) {
        if (realItem == null || realItem.getType().isAir()) {
            return 0;
        }
        if (ClueGiver.isClueItem(realItem)) {
            return 0;
        }
        double perItem = CategoryHandler.getPerItemValue(realItem);
        if (perItem <= 0) {
            return realItem.getAmount();
        }
        int maxByBudget = (int) Math.floor(capacityRemaining / perItem);
        if (maxByBudget <= 0) {
            return 0;
        }
        return Math.min(realItem.getAmount(), maxByBudget);
    }

    public double getCapacity() {
        return capacity;
    }

    public double getRemaining() {
        return Math.max(0.0, capacity - used);
    }

    public double getUsed() {
        return used;
    }

    public void addUsed(double value) {
        used += value;
    }
}
