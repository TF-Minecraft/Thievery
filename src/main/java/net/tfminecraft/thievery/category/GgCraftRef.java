package net.tfminecraft.thievery.category;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class GgCraftRef {

    private static final String PREFIX = "gg_";
    private static final String TIER_MARKER = "_tier_";
    private static final Set<String> TYPES = Set.of("rifle", "pistol", "shotgun", "launcher");

    private static final NamespacedKey GUN_TYPE = NamespacedKey.fromString("gunsandgadgets:gun_type");
    private static final NamespacedKey MAJORITY_TIER = NamespacedKey.fromString("gunsandgadgets:gg_majority_tier");

    private final String rawId;
    private final String gunType;
    private final int tier;

    private GgCraftRef(String rawId, String gunType, int tier) {
        this.rawId = rawId;
        this.gunType = gunType;
        this.tier = tier;
    }

    public static Optional<GgCraftRef> parse(String id) {
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
            return Optional.of(new GgCraftRef(canonical, typePart, tier));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<GgCraftRef> fromItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String type = pdc.get(GUN_TYPE, PersistentDataType.STRING);
        Integer tier = pdc.get(MAJORITY_TIER, PersistentDataType.INTEGER);
        if (type == null || type.isBlank() || tier == null || tier <= 0) {
            return Optional.empty();
        }
        return parse(PREFIX + type.trim().toLowerCase(Locale.ROOT) + TIER_MARKER + tier);
    }

    public String getRawId() {
        return rawId;
    }

    public String getGunType() {
        return gunType;
    }

    public int getTier() {
        return tier;
    }

    public String getDisplayName() {
        return Character.toUpperCase(gunType.charAt(0)) + gunType.substring(1);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof GgCraftRef other)) {
            return false;
        }
        return tier == other.tier && gunType.equals(other.gunType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gunType, tier);
    }
}
