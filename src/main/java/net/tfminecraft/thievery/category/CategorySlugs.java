package net.tfminecraft.thievery.category;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CategorySlugs {

    public enum SlugSpecificity {
        EXACT_PATH(4),
        FUZZY_PATH(3),
        CRAFT_REF(2),
        MATERIAL_TIER(1),
        MMO_TYPE(0);

        private final int rank;

        SlugSpecificity(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }
    }

    private static final Pattern MATERIAL = Pattern.compile(
            "^ac_(metal|wood|crystal|leather|feather|wool)_tier_(\\d+)$", Pattern.CASE_INSENSITIVE);

    private CategorySlugs() {
    }

    public static boolean isMaterialSlug(String slug) {
        return slug != null && MATERIAL.matcher(slug.trim()).matches();
    }

    public static boolean isAcSlug(String slug) {
        return slug != null && AcCraftRef.parse(slug.trim()).isPresent();
    }

    public static boolean isGgSlug(String slug) {
        return slug != null && GgCraftRef.parse(slug.trim()).isPresent();
    }

    public static boolean isMagicSlug(String slug) {
        return slug != null && MagicCraftRef.parse(slug.trim()).isPresent();
    }

    public static boolean isCraftSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        String trimmed = slug.trim();
        if (isGgSlug(trimmed) || isMagicSlug(trimmed)) {
            return true;
        }
        return isAcSlug(trimmed) && !isMaterialSlug(trimmed);
    }

    public static boolean isPathSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        String trimmed = slug.trim();
        if (isMmoTypeSlug(trimmed)) {
            return false;
        }
        return !isAcSlug(trimmed) && !isGgSlug(trimmed) && !isMagicSlug(trimmed);
    }

    public static boolean isMmoTypeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        String[] parts = slug.trim().split("\\.");
        return parts.length == 2 && parts[0].equalsIgnoreCase("m") && !parts[1].isBlank();
    }

    public static String mmoTypeId(String slug) {
        if (!isMmoTypeSlug(slug)) {
            return null;
        }
        return slug.trim().split("\\.")[1];
    }

    public static Optional<AcCraftRef> parseCraftRef(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return AcCraftRef.parse(slug.trim());
    }

    public static Optional<GgCraftRef> parseGgCraftRef(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return GgCraftRef.parse(slug.trim());
    }

    public static Optional<MagicCraftRef> parseMagicCraftRef(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return MagicCraftRef.parse(slug.trim());
    }

    public static String materialType(String slug) {
        Matcher matcher = MATERIAL.matcher(slug.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    public static int materialTier(String slug) {
        Matcher matcher = MATERIAL.matcher(slug.trim());
        return matcher.matches() ? Integer.parseInt(matcher.group(2)) : 0;
    }
}
