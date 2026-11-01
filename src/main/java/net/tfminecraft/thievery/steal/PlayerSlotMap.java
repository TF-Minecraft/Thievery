package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class PlayerSlotMap {

    public static final int STORAGE_SLOTS = 36;
    public static final int ARMOR_SLOTS = 4;
    public static final int MAIN_INV_START = 9;
    public static final int MAIN_INV_SLOT_COUNT = 27;
    public static final int TOTAL_LOGICAL_SLOTS = STORAGE_SLOTS + ARMOR_SLOTS + 1;

    private PlayerSlotMap() {}

    public static List<Integer> listLogicalSlots() {
        List<Integer> slots = new ArrayList<>(TOTAL_LOGICAL_SLOTS);
        for (int i = 0; i < TOTAL_LOGICAL_SLOTS; i++) {
            slots.add(i);
        }
        return slots;
    }

    public static List<Integer> listSearchableSlots(Player player) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < TOTAL_LOGICAL_SLOTS; i++) {
            ItemStack item = getItem(player, i);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (StealIgnoreRules.isIgnored(item)) {
                continue;
            }
            slots.add(i);
        }
        return slots;
    }

    public static int toPlayerSlot(int pickpocketLogical) {
        if (pickpocketLogical < 0 || pickpocketLogical >= MAIN_INV_SLOT_COUNT) {
            return -1;
        }
        return MAIN_INV_START + pickpocketLogical;
    }

    public static int toPickpocketLogical(int playerSlot) {
        if (playerSlot < MAIN_INV_START || playerSlot >= MAIN_INV_START + MAIN_INV_SLOT_COUNT) {
            return -1;
        }
        return playerSlot - MAIN_INV_START;
    }

    /** The player slot a pickpocket may take from, or -1 outside the main inventory or on a worn accessory. */
    public static int toPickpocketTakeSlot(int pickpocketLogical) {
        int playerSlot = toPlayerSlot(pickpocketLogical);
        if (playerSlot < 0 || WornAccessorySlots.isWorn(playerSlot)) {
            return -1;
        }
        return playerSlot;
    }

    public static List<Integer> listPickpocketSlots(Player player) {
        List<Integer> slots = new ArrayList<>();
        for (int pickpocketLogical = 0; pickpocketLogical < MAIN_INV_SLOT_COUNT; pickpocketLogical++) {
            int playerSlot = toPickpocketTakeSlot(pickpocketLogical);
            if (playerSlot < 0) {
                continue;
            }
            ItemStack item = getItem(player, playerSlot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (StealIgnoreRules.isIgnored(item)) {
                continue;
            }
            slots.add(pickpocketLogical);
        }
        return slots;
    }

    /** Returns what a pickpocket finds there; worn accessory slots always look empty. */
    public static ItemStack getPickpocketItem(Player player, int pickpocketLogical) {
        int playerSlot = toPickpocketTakeSlot(pickpocketLogical);
        if (playerSlot < 0) {
            return null;
        }
        return getItem(player, playerSlot);
    }

    public static void setPickpocketItem(Player player, int pickpocketLogical, ItemStack item) {
        int playerSlot = toPlayerSlot(pickpocketLogical);
        if (playerSlot < 0) {
            return;
        }
        setItem(player, playerSlot, item);
    }

    public static ItemStack getItem(Player player, int logicalSlot) {
        if (player == null || logicalSlot < 0 || logicalSlot >= TOTAL_LOGICAL_SLOTS) {
            return null;
        }
        PlayerInventory inv = player.getInventory();
        if (logicalSlot < STORAGE_SLOTS) {
            return inv.getStorageContents()[logicalSlot];
        }
        if (logicalSlot < STORAGE_SLOTS + ARMOR_SLOTS) {
            return inv.getArmorContents()[logicalSlot - STORAGE_SLOTS];
        }
        return inv.getItemInOffHand();
    }

    public static void setItem(Player player, int logicalSlot, ItemStack item) {
        if (player == null || logicalSlot < 0 || logicalSlot >= TOTAL_LOGICAL_SLOTS) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        if (logicalSlot < STORAGE_SLOTS) {
            inv.setItem(logicalSlot, item);
            return;
        }
        if (logicalSlot < STORAGE_SLOTS + ARMOR_SLOTS) {
            ItemStack[] armor = inv.getArmorContents();
            armor[logicalSlot - STORAGE_SLOTS] = item;
            inv.setArmorContents(armor);
            return;
        }
        inv.setItemInOffHand(item);
    }

    public static void dropAllExceptIgnored(Player player) {
        if (player == null) {
            return;
        }
        for (int logicalSlot = 0; logicalSlot < TOTAL_LOGICAL_SLOTS; logicalSlot++) {
            ItemStack item = getItem(player, logicalSlot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (StealIgnoreRules.isIgnored(item)) {
                continue;
            }
            setItem(player, logicalSlot, null);
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
    }
}
