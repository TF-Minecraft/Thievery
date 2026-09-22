package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class StealIgnoreRules {

    private static List<String> nameContains = new ArrayList<>();

    private StealIgnoreRules() {}

    public static void load(List<String> configuredNameContains) {
        nameContains = configuredNameContains != null
                ? new ArrayList<>(configuredNameContains) : new ArrayList<>();
    }

    public static List<String> getNameContains() {
        return Collections.unmodifiableList(nameContains);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public static boolean isIgnored(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        if (nameContains.isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        String display = meta.hasDisplayName() ? meta.getDisplayName() : "";
        String plain = display + " " + (meta.hasLore() ? String.join(" ", meta.getLore()) : "");
        for (String fragment : nameContains) {
            if (fragment != null && !fragment.isBlank() && plain.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
