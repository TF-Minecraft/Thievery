package net.tfminecraft.thievery.steal;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Player inventory slots that MMOInventory uses for worn accessories (rings, amulets, artifacts).
 *
 * <p>MMOInventory's {@code vanilla} inventories put those slots inside the normal 3x9 grid, so a
 * pickpocket would otherwise reach what the victim is wearing. The slots are read from MMOInventory's
 * own inventory files on every config load, so moved or added slots are picked up on the next reload.
 */
public final class WornAccessorySlots {

    private static volatile Set<Integer> slots = Set.of();

    private WornAccessorySlots() {}

    /**
     * Reads the worn slots from MMOInventory's {@code inventory/*.yml}. Nothing is protected while
     * MMOInventory is missing or disabled, since no slot is worn then.
     */
    public static void load(Plugin mmoInventory, Logger logger) {
        slots = mmoInventory == null || !mmoInventory.isEnabled()
                ? Set.of()
                : read(new File(mmoInventory.getDataFolder(), "inventory"), logger);
        if (!slots.isEmpty()) {
            logger.info("Pickpocketing skips the worn MMOInventory slots " + slots + ".");
        }
    }

    public static boolean isWorn(int playerSlot) {
        return slots.contains(playerSlot);
    }

    static Set<Integer> read(File inventoryFolder, Logger logger) {
        File[] files = inventoryFolder.listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            if (inventoryFolder.exists()) {
                logger.warning("Could not list the MMOInventory inventory folder " + inventoryFolder
                        + "; worn slots stay pickpocketable.");
            }
            return Set.of();
        }
        Set<Integer> found = new TreeSet<>();
        for (File file : files) {
            YamlConfiguration config = new YamlConfiguration();
            try {
                config.load(file);
            } catch (IOException | InvalidConfigurationException error) {
                logger.warning("Could not read the MMOInventory inventory " + file.getName()
                        + "; its slots stay pickpocketable: " + error.getMessage());
                continue;
            }
            addVanillaSlots(config, found);
        }
        return Collections.unmodifiableSet(found);
    }

    private static void addVanillaSlots(YamlConfiguration config, Set<Integer> found) {
        // Custom inventories live in their own menu; only vanilla ones put slots in the player's grid.
        if (!"vanilla".equalsIgnoreCase(config.getString("type"))
                || !config.getBoolean("enabled", true)) {
            return;
        }
        ConfigurationSection section = config.getConfigurationSection("slots");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection slot = section.getConfigurationSection(key);
            // MMOInventory needs a type on every slot; fill is the background, not a slot.
            String type = slot == null ? null : slot.getString("type");
            if (type == null || "fill".equalsIgnoreCase(type)) {
                continue;
            }
            int index = slot.getInt("slot", -1);
            if (index >= 0 && index < PlayerSlotMap.STORAGE_SLOTS) {
                found.add(index);
            }
        }
    }
}
