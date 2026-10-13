package net.tfminecraft.thievery.door;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;

/** A read-only copy of a locked container, shown to staff while locks are in guard mode. */
final class GuardView implements InventoryHolder {

    private final Inventory inventory;

    private GuardView(Inventory source) {
        int size = Math.max(9, (source.getSize() + 8) / 9 * 9);
        inventory = Bukkit.createInventory(this, size, Component.text("Locked (read-only)"));
        ItemStack[] contents = source.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            inventory.setItem(slot, contents[slot] == null ? null : contents[slot].clone());
        }
    }

    static Inventory of(Inventory source) {
        return new GuardView(source).inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
