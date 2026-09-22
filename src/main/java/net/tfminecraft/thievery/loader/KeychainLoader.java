package net.tfminecraft.thievery.loader;

import java.util.Map;
import java.util.TreeMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;

public final class KeychainLoader {

    private static String itemPath = "v.brick";
    private static int maxKeys = 9;
    private static int loreLineStart = 4;
    private static final TreeMap<Integer, Integer> modelByCount = new TreeMap<>();

    private KeychainLoader() {}

    public static void load(FileConfiguration config) {
        itemPath = config.getString("keychain.item", "v.brick");
        maxKeys = config.getInt("keychain.max-keys", 9);
        loreLineStart = Math.max(1, config.getInt("keychain.lore-line-start", 4));

        modelByCount.clear();
        ConfigurationSection section = config.getConfigurationSection("keychain.model-by-count");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int threshold = Integer.parseInt(key);
                    modelByCount.put(threshold, section.getInt(key, 0));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (modelByCount.isEmpty()) {
            modelByCount.put(0, 0);
        }
    }

    public static String getItemPath() {
        return itemPath;
    }

    public static int getMaxKeys() {
        return maxKeys;
    }

    /** 1-based lore line where key count and stored keys are written. Lines before this are preserved from the item template. */
    public static int getLoreLineStart() {
        return loreLineStart;
    }

    public static int resolveModelData(int keyCount) {
        Map.Entry<Integer, Integer> entry = modelByCount.floorEntry(keyCount);
        return entry != null ? entry.getValue() : 0;
    }

    public static boolean matchesItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        return TLibs.getItemAPI().getChecker().checkItemWithPath(item, itemPath);
    }
}
