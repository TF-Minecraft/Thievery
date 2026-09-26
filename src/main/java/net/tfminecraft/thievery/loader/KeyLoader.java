package net.tfminecraft.thievery.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.thievery.key.KeyDefinition;

public final class KeyLoader {

    private KeyLoader() {}

    private static final HashMap<String, KeyDefinition> keys = new HashMap<>();

    public static void load(FileConfiguration config) {
        keys.clear();
        if (!config.isConfigurationSection("keys")) return;

        ConfigurationSection section = config.getConfigurationSection("keys");
        for (String key : section.getKeys(false)) {
            keys.put(key, new KeyDefinition(key, section.getConfigurationSection(key)));
        }
    }

    public static KeyDefinition getById(String id) {
        return keys.get(id);
    }

    public static List<KeyDefinition> getAsList() {
        return new ArrayList<>(keys.values());
    }

    public static KeyDefinition resolve(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        for (KeyDefinition key : keys.values()) {
            if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, key.getItem())) {
                return key;
            }
        }
        return null;
    }
}
