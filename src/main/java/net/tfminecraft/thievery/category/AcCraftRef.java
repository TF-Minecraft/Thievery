package net.tfminecraft.thievery.category;

import java.util.Objects;
import java.util.Optional;

public final class AcCraftRef {

    private static final String PREFIX = "ac_";
    private static final String TIER_MARKER = "_tier_";

    private final String rawId;
    private final String statTemplate;
    private final int tier;

    private AcCraftRef(String rawId, String statTemplate, int tier) {
        this.rawId = rawId;
        this.statTemplate = statTemplate;
        this.tier = tier;
    }

    public static Optional<AcCraftRef> parse(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String trimmed = id.trim();
        if (!trimmed.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int tierIndex = trimmed.lastIndexOf(TIER_MARKER);
        if (tierIndex < PREFIX.length()) {
            return Optional.empty();
        }
        String templatePart = trimmed.substring(PREFIX.length(), tierIndex);
        if (templatePart.isBlank()) {
            return Optional.empty();
        }
        String tierPart = trimmed.substring(tierIndex + TIER_MARKER.length());
        try {
            int tier = Integer.parseInt(tierPart);
            if (tier <= 0) {
                return Optional.empty();
            }
            return Optional.of(new AcCraftRef(trimmed, templatePart, tier));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public String getRawId() {
        return rawId;
    }

    public String getStatTemplate() {
        return statTemplate;
    }

    public int getTier() {
        return tier;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AcCraftRef other)) {
            return false;
        }
        return tier == other.tier && statTemplate.equalsIgnoreCase(other.statTemplate);
    }

    @Override
    public int hashCode() {
        // Match equalsIgnoreCase's character folding without depending on the
        // server locale (or expanding Unicode characters into several letters).
        int templateHash = statTemplate.codePoints()
                .map(Character::toUpperCase)
                .map(Character::toLowerCase)
                .reduce(0, (hash, codePoint) -> 31 * hash + codePoint);
        return Objects.hash(templateHash, tier);
    }
}
