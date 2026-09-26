package net.tfminecraft.thievery.utils;

import org.bukkit.NamespacedKey;

import net.tfminecraft.thievery.Thievery;

public final class Keys {

    private Keys() {}
    public static final NamespacedKey keyUUIDKey = new NamespacedKey(Thievery.getInstance(), "door_key_uuid");
    public static final NamespacedKey categoryId = new NamespacedKey(Thievery.getInstance(), "category_id");
    public static final NamespacedKey stealUnknown = new NamespacedKey(Thievery.getInstance(), "steal_unknown");
    public static final NamespacedKey stealFiller = new NamespacedKey(Thievery.getInstance(), "steal_filler");
    public static final NamespacedKey stealNothing = new NamespacedKey(Thievery.getInstance(), "steal_nothing");
    public static final NamespacedKey stealHidden = new NamespacedKey(Thievery.getInstance(), "steal_hidden");
    public static final NamespacedKey stealRobberyPouch = new NamespacedKey(Thievery.getInstance(), "steal_robbery_pouch");
    public static final NamespacedKey keychainMarker = new NamespacedKey(Thievery.getInstance(), "keychain_marker");
    public static final NamespacedKey keychainKeys = new NamespacedKey(Thievery.getInstance(), "keychain_keys");
    public static final NamespacedKey keyMoldMarker = new NamespacedKey(Thievery.getInstance(), "key_mold_marker");
    public static final NamespacedKey keyCopyKind = new NamespacedKey(Thievery.getInstance(), "key_copy_kind");
    public static final NamespacedKey keySourceStrength = new NamespacedKey(Thievery.getInstance(), "key_source_strength");
    public static final NamespacedKey keySourceKeyId = new NamespacedKey(Thievery.getInstance(), "key_source_key_id");

    public static final String COPY_KIND_PERMANENT = "permanent";
    public static final String COPY_KIND_PAPER = "paper";
}
