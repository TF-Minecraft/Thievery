package net.tfminecraft.thievery.loader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.advancedcrafting.utils.ThieveryBridge;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.AcCraftRef;
import net.tfminecraft.thievery.category.CategoryHandler;
import net.tfminecraft.thievery.category.CategorySlugs;
import net.tfminecraft.thievery.category.GgCraftRef;
import net.tfminecraft.thievery.category.MagicCraftRef;
import net.tfminecraft.thievery.category.ItemCategory;
import net.tfminecraft.thievery.category.ItemCategory.CategoryItemEntry;

public class CategoryLoader implements LoaderInterface {

    private static final LinkedHashMap<String, ItemCategory> categories = new LinkedHashMap<>();

    public static Map<String, ItemCategory> get() {
        return categories;
    }

    public static List<ItemCategory> getAsList() {
        return List.copyOf(categories.values());
    }

    public static List<ItemCategory> getLoadoutCategories() {
        List<ItemCategory> loadout = new ArrayList<>();
        for (ItemCategory category : categories.values()) {
            if (category.isLoadoutVisible()) {
                loadout.add(category);
            }
        }
        return loadout;
    }

    public static ItemCategory getById(String id) {
        return categories.get(id);
    }

    public static ItemCategory getMoneyCategory() {
        ItemCategory found = null;
        for (ItemCategory category : categories.values()) {
            if (!category.isMoneyType()) {
                continue;
            }
            if (found != null) {
                Thievery.getInstance().getLogger().warning(
                        "[Thievery] Multiple money categories defined ('" + found.getId()
                                + "' and '" + category.getId() + "'); using '" + found.getId() + "'");
                return found;
            }
            found = category;
        }
        return found;
    }

    public static double getDefaultWeight() {
        return Cache.defaultItemValue;
    }

    public static double getWeightForGgRef(GgCraftRef ref) {
        return CategoryHandler.getWeightForGgRef(ref);
    }

    public static double getWeightForMagicRef(MagicCraftRef ref) {
        return CategoryHandler.getWeightForMagicRef(ref);
    }

    public static double getWeightForCraftRef(AcCraftRef ref) {
        return CategoryHandler.getWeightForCraftRef(ref);
    }

    public static double getWeightForPath(String path) {
        return CategoryHandler.getWeightForPath(path);
    }

    @Override
    public void load(File configFile) {
        categories.clear();

        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
            return;
        }

        Set<String> keys = config.getKeys(false);
        for (String key : keys) {
            ItemCategory category = new ItemCategory(key, config.getConfigurationSection(key));
            for (CategoryItemEntry entry : category.getItems()) {
                if (CategorySlugs.isPathSlug(entry.getSlug())) {
                    warnDuplicatePath(entry.getSlug(), key);
                } else if (CategorySlugs.isMmoTypeSlug(entry.getSlug())) {
                    warnDuplicateMmoType(entry.getSlug(), key);
                }
            }
            categories.put(key, category);
        }

        validateCraftSlugs();
        validateMmoTypeSlugs();
    }

    private static void validateCraftSlugs() {
        for (ItemCategory category : categories.values()) {
            for (CategoryItemEntry entry : category.getItems()) {
                if (!CategorySlugs.isCraftSlug(entry.getSlug())
                        || CategorySlugs.isGgSlug(entry.getSlug())
                        || CategorySlugs.isMagicSlug(entry.getSlug())) {
                    continue;
                }
                CategorySlugs.parseCraftRef(entry.getSlug()).ifPresent(ref -> {
                    if (ThieveryBridge.isPluginReady()
                            && ThieveryBridge.getStatTemplate(ref.getStatTemplate()) == null) {
                        Thievery.getInstance().getLogger().warning(
                                "[Thievery] Category '" + category.getId()
                                        + "' references unknown AC stat template '"
                                        + ref.getStatTemplate() + "' in '" + ref.getRawId() + "'");
                    }
                });
            }
        }
    }

    private static void validateMmoTypeSlugs() {
        for (ItemCategory category : categories.values()) {
            for (CategoryItemEntry entry : category.getItems()) {
                if (!CategorySlugs.isMmoTypeSlug(entry.getSlug())) {
                    continue;
                }
                String typeId = CategorySlugs.mmoTypeId(entry.getSlug());
                if (CategoryHandler.lookupMmoType(typeId) == null) {
                    Thievery.getInstance().getLogger().warning(
                            "[Thievery] Category '" + category.getId()
                                    + "' references unknown MMOItems type '" + typeId
                                    + "' in '" + entry.getSlug() + "'");
                }
            }
        }
    }

    private static void warnDuplicateMmoType(String slug, String categoryId) {
        for (ItemCategory existing : categories.values()) {
            for (CategoryItemEntry entry : existing.getItems()) {
                if (CategorySlugs.isMmoTypeSlug(entry.getSlug())
                        && entry.getSlug().equalsIgnoreCase(slug)
                        && !existing.getId().equalsIgnoreCase(categoryId)) {
                    Thievery.getInstance().getLogger().severe(
                            "[Thievery] MMOItems type '" + slug + "' is defined in both '"
                                    + existing.getId() + "' and '" + categoryId + "'");
                }
            }
        }
    }

    private static void warnDuplicatePath(String path, String categoryId) {
        for (ItemCategory existing : categories.values()) {
            for (CategoryItemEntry entry : existing.getItems()) {
                if (CategorySlugs.isPathSlug(entry.getSlug())
                        && entry.getSlug().equalsIgnoreCase(path)
                        && !existing.getId().equalsIgnoreCase(categoryId)) {
                    Thievery.getInstance().getLogger().severe(
                            "[Thievery] Item path '" + path + "' is defined in both '"
                                    + existing.getId() + "' and '" + categoryId + "'");
                }
            }
        }
    }
}
