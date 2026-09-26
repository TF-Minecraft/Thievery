package net.tfminecraft.thievery.category;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.thievery.cache.Cache;

public final class MagicCraftRef {

    private static final String PREFIX = "magic_";
    private static final String TIER_MARKER = "_tier_";
    private static final Set<String> TYPES = Set.of("staff", "wand", "sword");

    private static final NamespacedKey ARCHETYPE = NamespacedKey.fromString("magic:gear_archetype");
    private static final NamespacedKey MAJORITY_TIER = NamespacedKey.fromString("magic:majority_tier");
    private static final NamespacedKey WEAPON_REQ_FILL = NamespacedKey.fromString("magic:weapon_req_fill");
    private static final NamespacedKey WEAPON_REQ = NamespacedKey.fromString("magic:weapon_req");

    private final String rawId;
    private final String gearType;
    private final int tier;

    private MagicCraftRef(String rawId, String gearType, int tier) {
        this.rawId = rawId;
        this.gearType = gearType;
        this.tier = tier;
    }

    public static Optional<MagicCraftRef> parse(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String trimmed = id.trim();
        if (!trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return Optional.empty();
        }
        int tierIndex = trimmed.toLowerCase(Locale.ROOT).lastIndexOf(TIER_MARKER);
        if (tierIndex < PREFIX.length()) {
            return Optional.empty();
        }
        String typePart = trimmed.substring(PREFIX.length(), tierIndex).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(typePart)) {
            return Optional.empty();
        }
        String tierPart = trimmed.substring(tierIndex + TIER_MARKER.length());
        try {
            int tier = Integer.parseInt(tierPart);
            if (tier <= 0) {
                return Optional.empty();
            }
            String canonical = PREFIX + typePart + TIER_MARKER + tier;
            return Optional.of(new MagicCraftRef(canonical, typePart, tier));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<MagicCraftRef> fromItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String type = pdc.get(ARCHETYPE, PersistentDataType.STRING);
        Integer tier = pdc.get(MAJORITY_TIER, PersistentDataType.INTEGER);
        if (type == null || type.isBlank() || tier == null || tier <= 0) {
            return Optional.empty();
        }
        return parse(PREFIX + type.trim().toLowerCase(Locale.ROOT) + TIER_MARKER + tier);
    }

    public static int highestAuraBand(ItemStack item) {
        int best = 0;
        for (double fill : auraFills(item).values()) {
            best = Math.max(best, Cache.auraBand(fill));
        }
        return best;
    }

    private static Map<String, Double> auraFills(ItemStack item) {
        Map<String, Double> fills = new HashMap<>();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return fills;
        }
        PersistentDataContainer root = item.getItemMeta().getPersistentDataContainer();
        PersistentDataContainer fillContainer = root.get(WEAPON_REQ_FILL, PersistentDataType.TAG_CONTAINER);
        if (fillContainer != null) {
            for (NamespacedKey key : fillContainer.getKeys()) {
                Double value = fillContainer.get(key, PersistentDataType.DOUBLE);
                if (value != null) {
                    fills.put(key.getKey(), value);
                }
            }
        }
        parseBlob(fills, root.get(WEAPON_REQ, PersistentDataType.STRING));
        return fills;
    }

    private static void parseBlob(Map<String, Double> fills, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            String[] bits = part.split(":");
            if (bits.length < 3) {
                continue;
            }
            String id = bits[0].trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || fills.containsKey(id)) {
                continue;
            }
            try {
                fills.put(id, Double.parseDouble(bits[2].trim()));
            } catch (NumberFormatException ignored) {
                // skip bad token
            }
        }
    }

    public String getRawId() {
        return rawId;
    }

    public String getGearType() {
        return gearType;
    }

    public int getTier() {
        return tier;
    }

    public String getDisplayName() {
        return Character.toUpperCase(gearType.charAt(0)) + gearType.substring(1);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MagicCraftRef other)) {
            return false;
        }
        return tier == other.tier && gearType.equals(other.gearType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gearType, tier);
    }
}
