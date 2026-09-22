package net.tfminecraft.thievery.category;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.Keys;

public class ItemCategory {

    public static final class CategoryItemEntry {
        private final String slug;
        private final double weight;

        public CategoryItemEntry(String slug, double weight) {
            this.slug = slug;
            this.weight = weight;
        }

        public String getSlug() {
            return slug;
        }

        public double getWeight() {
            return weight;
        }
    }

    public static final String TYPE_MONEY = "money";

    private final String id;
    private final String name;
    private final String icon;
    private final int cost;
    private final double value;
    private final String type;
    private final double amountPerMoney;
    private final boolean loadoutVisible;
    private final List<CategoryItemEntry> items = new ArrayList<>();

    public ItemCategory(String key, ConfigurationSection config) {
        id = key;
        name = StringFormatter.formatHex(config.getString("name", key));
        icon = config.getString("icon", "v.paper");
        cost = config.getInt("cost", 1);
        value = config.getDouble("value", 1.0);
        type = config.getString("type", "");
        if (config.contains("amount_per_money")) {
            amountPerMoney = config.getDouble("amount_per_money");
        } else {
            amountPerMoney = value;
        }
        if (config.contains("loadout")) {
            loadoutVisible = config.getBoolean("loadout");
        } else {
            loadoutVisible = true;
        }
        if (config.contains("match")) {
            Thievery.getInstance().getLogger().severe("[Thievery] Category '" + key
                    + "' uses deprecated 'match' — remove it.");
        }

        for (String entry : config.getStringList("items")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            String slug = parts[0];
            double weight = value;
            if (parts.length > 1) {
                try {
                    weight = Double.parseDouble(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            items.add(new CategoryItemEntry(slug, weight));
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIcon() {
        return icon;
    }

    public int getCost() {
        return cost;
    }

    public double getValue() {
        return value;
    }

    public String getType() {
        return type;
    }

    public boolean isMoneyType() {
        return TYPE_MONEY.equalsIgnoreCase(type);
    }

    public double getAmountPerMoney() {
        return amountPerMoney;
    }

    public boolean isLoadoutVisible() {
        return loadoutVisible;
    }

    public List<CategoryItemEntry> getItems() {
        return Collections.unmodifiableList(items);
    }

    public ItemStack getIconItem(boolean active) {
        ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(icon);
        if (item == null) {
            return null;
        }

        item = item.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(name);
        meta.getPersistentDataContainer().set(Keys.categoryId, PersistentDataType.STRING, id);

        List<String> lore = new ArrayList<>();
        lore.add(ThieveryTexts.formatGui(ThieveryTexts.GUI_WARN + "Cost: " + ThieveryTexts.GUI_SUCCESS + cost));
        lore.add(" ");
        lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "------------------------"));
        lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Items:"));
        lore.addAll(CategoryHandler.buildDisplayLines(this));
        lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "------------------------"));
        lore.add(" ");
        if (active) {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.GUI_SUCCESS + "Active"));
        } else {
            lore.add(ThieveryTexts.formatGui(ThieveryTexts.MUTED + "Inactive"));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
