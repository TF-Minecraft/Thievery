package net.tfminecraft.thievery.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.thievery.player.LockpickDefinition;

public class LockpickLoader {

    private static final HashMap<String, LockpickDefinition> lockpicks = new HashMap<>();

    public static void load(FileConfiguration config) {
        lockpicks.clear();
        if (!config.isConfigurationSection("lockpicks")) return;

        ConfigurationSection section = config.getConfigurationSection("lockpicks");
        for (String key : section.getKeys(false)) {
            lockpicks.put(key, new LockpickDefinition(key, section.getConfigurationSection(key)));
        }
    }

    public static LockpickDefinition getById(String id) {
        return lockpicks.get(id);
    }

    public static List<LockpickDefinition> getAsList() {
        return new ArrayList<>(lockpicks.values());
    }

    public static LockpickDefinition resolve(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        for (LockpickDefinition lockpick : lockpicks.values()) {
            if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, lockpick.getItem())) {
                return lockpick;
            }
        }
        return null;
    }
}
