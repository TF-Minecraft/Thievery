package net.tfminecraft.thievery.player;

import java.util.LinkedHashSet;
import java.util.Set;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.ItemCategory;
import net.tfminecraft.thievery.loader.CategoryLoader;

public class LoadoutSession {

    public enum ToggleResult {
        TOGGLED_ON,
        TOGGLED_OFF,
        ALLOCATION_FULL,
        NOT_ENOUGH_BANK,
        UNKNOWN_CATEGORY
    }

    private final Set<String> savedActive;
    private final Set<String> draftActive;
    private final int startingBank;

    public LoadoutSession(Set<String> savedActive, Set<String> draftActive, int startingBank) {
        this.savedActive = new LinkedHashSet<>(savedActive);
        this.draftActive = new LinkedHashSet<>(draftActive);
        this.startingBank = startingBank;
    }

    public static LoadoutSession from(PlayerData playerData) {
        Set<String> active = new LinkedHashSet<>(playerData.getActiveCategories());
        return new LoadoutSession(active, active, playerData.getPoints());
    }

    public int getDraftBank() {
        return startingBank - getNewSelectionCost(draftActive);
    }

    public Set<String> getDraftActive() {
        return draftActive;
    }

    public int getDraftAllocated() {
        return PlayerData.getAllocatedCost(draftActive);
    }

    public boolean canConfirm() {
        return getDraftAllocated() <= Cache.categoryPoints;
    }

    public ToggleResult toggleCategory(String categoryId) {
        ItemCategory category = CategoryLoader.getById(categoryId);
        if (category == null) return ToggleResult.UNKNOWN_CATEGORY;

        if (draftActive.contains(categoryId)) {
            draftActive.remove(categoryId);
            return ToggleResult.TOGGLED_OFF;
        }

        if (getDraftAllocated() + category.getCost() > Cache.categoryPoints) {
            return ToggleResult.ALLOCATION_FULL;
        }

        if (!savedActive.contains(categoryId)) {
            int newSelectionCost = getNewSelectionCost(draftActive) + category.getCost();
            if (newSelectionCost > startingBank) {
                return ToggleResult.NOT_ENOUGH_BANK;
            }
        }

        draftActive.add(categoryId);
        return ToggleResult.TOGGLED_ON;
    }

    private int getNewSelectionCost(Set<String> active) {
        int total = 0;
        for (String id : active) {
            if (savedActive.contains(id)) continue;
            ItemCategory cat = CategoryLoader.getById(id);
            if (cat != null) {
                total += cat.getCost();
            }
        }
        return total;
    }
}
