package net.tfminecraft.thievery.inspection;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;

/** Display copies only; this inventory never writes back to its owner. */
final class InspectionInventory implements InventoryHolder {
    final UUID targetId;
    private final Inventory inventory;

    InspectionInventory(Player target) {
        targetId = target.getUniqueId();
        inventory = Bukkit.createInventory(this, 45, Component.text("Inventory (read-only)"));
        refresh(target);
    }

    void refresh(Player target) {
        for (int slot = 0; slot < 27; slot++) {
            copy(slot, target.getInventory().getItem(slot + 9));
        }
        for (int slot = 0; slot < 9; slot++) {
            copy(slot + 27, target.getInventory().getItem(slot));
        }
        copy(36, target.getInventory().getHelmet());
        copy(37, target.getInventory().getChestplate());
        copy(38, target.getInventory().getLeggings());
        copy(39, target.getInventory().getBoots());
        copy(40, target.getInventory().getItemInOffHand());
    }

    private void copy(int slot, ItemStack item) {
        if (!Objects.equals(inventory.getItem(slot), item)) {
            inventory.setItem(slot, item == null ? null : item.clone());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
