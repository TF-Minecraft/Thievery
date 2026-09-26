package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import net.tfminecraft.thievery.cache.Cache;

class MagicCraftRefTest {
    private static final NamespacedKey TYPE = NamespacedKey.fromString("magic:gear_archetype");
    private static final NamespacedKey TIER = NamespacedKey.fromString("magic:majority_tier");
    private static final NamespacedKey FILLS = NamespacedKey.fromString("magic:weapon_req_fill");
    private static final NamespacedKey BLOB = NamespacedKey.fromString("magic:weapon_req");

    @BeforeEach
    void configureAuraBands() {
        Cache.clearAuraMins();
        Cache.putAuraMin(1, 10);
        Cache.putAuraMin(2, 50);
        Cache.putAuraMin(3, 90);
    }

    @AfterEach
    void restoreDefaultAuraBands() {
        Cache.putDefaultItemValueTables();
    }

    @ParameterizedTest
    @ValueSource(strings = {"staff", "wand", "sword"})
    void canonicalizesEverySupportedType(String type) {
        MagicCraftRef ref = MagicCraftRef.parse("  MAGIC_" + type.toUpperCase(java.util.Locale.ROOT) + "_TIER_003  ").orElseThrow();
        assertEquals("magic_" + type + "_tier_3", ref.getRawId());
        assertEquals(type, ref.getGearType());
        assertEquals(3, ref.getTier());
        assertEquals(Character.toUpperCase(type.charAt(0)) + type.substring(1), ref.getDisplayName());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "staff_tier_1", "gg_staff_tier_1", "magic_staff", "magic_tier_1",
            "magic__tier_1", "magic_unknown_tier_1", "magic_staff_tier_1_tier_2", "magic_staff_tier_",
            "magic_staff_tier_x", "magic_staff_tier_1.5", "magic_staff_tier_2147483648",
            "magic_staff_tier_0", "magic_staff_tier_-1", "magic_staff_tier_ 1"})
    void rejectsMalformedIds(String id) {
        assertTrue(MagicCraftRef.parse(id).isEmpty());
    }

    @Test
    void acceptsTheFullPositiveIntegerRange() {
        assertEquals(Integer.MAX_VALUE, MagicCraftRef.parse("magic_staff_tier_2147483647").orElseThrow().getTier());
        assertEquals("magic_staff_tier_1", MagicCraftRef.parse("magic_staff_tier_+1").orElseThrow().getRawId());
    }

    @Test
    void equalityAndHashCodeUseCanonicalTypeAndTier() {
        MagicCraftRef ref = MagicCraftRef.parse("magic_staff_tier_1").orElseThrow();
        MagicCraftRef equivalent = MagicCraftRef.parse(" MAGIC_STAFF_TIER_01 ").orElseThrow();
        assertEquals(ref, ref);
        assertEquals(ref, equivalent);
        assertEquals(equivalent, ref);
        assertEquals(ref.hashCode(), equivalent.hashCode());
        assertNotEquals(ref, null);
        assertNotEquals(ref, ref.getRawId());
        assertNotEquals(ref, MagicCraftRef.parse("magic_staff_tier_2").orElseThrow());
        assertNotEquals(ref, MagicCraftRef.parse("magic_wand_tier_1").orElseThrow());
    }

    @Test
    void rejectsMissingItemsAirAndItemsWithoutMetadata() {
        assertTrue(MagicCraftRef.fromItem(null).isEmpty());
        assertEquals(0, MagicCraftRef.highestAuraBand(null));
        ItemStack air = mock(ItemStack.class);
        Material airType = mock(Material.class);
        when(airType.isAir()).thenReturn(true);
        when(air.getType()).thenReturn(airType);
        assertTrue(MagicCraftRef.fromItem(air).isEmpty());
        assertEquals(0, MagicCraftRef.highestAuraBand(air));
        verify(air, never()).getItemMeta();
        ItemStack plain = mock(ItemStack.class);
        Material plainType = mock(Material.class);
        when(plain.getType()).thenReturn(plainType);
        assertTrue(MagicCraftRef.fromItem(plain).isEmpty());
        assertEquals(0, MagicCraftRef.highestAuraBand(plain));
        verify(plain, never()).getItemMeta();
    }

    @Test
    void rejectsIncompleteInvalidAndUnsupportedMetadata() {
        assertTrue(MagicCraftRef.fromItem(gear(null, 1)).isEmpty());
        assertTrue(MagicCraftRef.fromItem(gear("  ", 1)).isEmpty());
        assertTrue(MagicCraftRef.fromItem(gear("staff", null)).isEmpty());
        assertTrue(MagicCraftRef.fromItem(gear("staff", 0)).isEmpty());
        assertTrue(MagicCraftRef.fromItem(gear("staff", -1)).isEmpty());
        assertTrue(MagicCraftRef.fromItem(gear("rifle", 1)).isEmpty());
    }

    @Test
    void readsCanonicalReferenceFromNamespacedTypedMetadata() {
        assertEquals(MagicCraftRef.parse("magic_sword_tier_4").orElseThrow(),
                MagicCraftRef.fromItem(gear("  SWORD  ", 4)).orElseThrow());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "fire", "fire:10", ":10:90", " :10:90", "fire:10:bad", "fire:10:"})
    void missingOrMalformedAuraMetadataHasNoBand(String blob) {
        assertEquals(0, MagicCraftRef.highestAuraBand(aura(null, blob)));
    }

    @Test
    void blobUsesHighestFillAndSkipsInvalidTokens() {
        assertEquals(3, MagicCraftRef.highestAuraBand(aura(null,
                "bad,fire:100:10,ice:100:90,water:100:50,bogus:100:nonsense")));
        assertEquals(0, MagicCraftRef.highestAuraBand(aura(null, "fire:100:9")));
        assertEquals(2, MagicCraftRef.highestAuraBand(aura(null, "fire:100:50")));
    }

    @Test
    void blobNormalizesIdsAndKeepsFirstValueForDuplicates() {
        assertEquals(1, MagicCraftRef.highestAuraBand(aura(null,
                " FIRE :ignored: 10 ,fire:ignored:90")));
    }

    @Test
    void typedFillsOverrideBlobValuesButBlobCanSupplyMissingElements() {
        PersistentDataContainer fills = mock(PersistentDataContainer.class);
        NamespacedKey fire = NamespacedKey.fromString("magic:fire");
        NamespacedKey ice = NamespacedKey.fromString("magic:ice");
        when(fills.getKeys()).thenReturn(Set.of(fire, ice));
        when(fills.get(fire, PersistentDataType.DOUBLE)).thenReturn(10.0);
        // A missing DOUBLE (including a key stored with another type) is ignored.
        when(fills.get(ice, PersistentDataType.DOUBLE)).thenReturn(null);
        assertEquals(1, MagicCraftRef.highestAuraBand(aura(fills, "fire:100:90")));
        assertEquals(2, MagicCraftRef.highestAuraBand(aura(fills, "fire:100:90,ice:100:50")));
    }

    @Test
    void typedFillsAloneChooseHighestBandAndEmptyContainerHasNoBand() {
        PersistentDataContainer fills = mock(PersistentDataContainer.class);
        NamespacedKey fire = NamespacedKey.fromString("magic:fire");
        NamespacedKey ice = NamespacedKey.fromString("magic:ice");
        when(fills.getKeys()).thenReturn(Set.of(fire, ice));
        when(fills.get(fire, PersistentDataType.DOUBLE)).thenReturn(90.0);
        when(fills.get(ice, PersistentDataType.DOUBLE)).thenReturn(10.0);
        assertEquals(3, MagicCraftRef.highestAuraBand(aura(fills, null)));
        when(fills.getKeys()).thenReturn(Set.of());
        assertEquals(0, MagicCraftRef.highestAuraBand(aura(fills, null)));
    }

    private static ItemStack gear(String type, Integer tier) {
        PersistentDataContainer root = mock(PersistentDataContainer.class);
        when(root.get(TYPE, PersistentDataType.STRING)).thenReturn(type);
        when(root.get(TIER, PersistentDataType.INTEGER)).thenReturn(tier);
        return item(root);
    }

    private static ItemStack aura(PersistentDataContainer fills, String blob) {
        PersistentDataContainer root = mock(PersistentDataContainer.class);
        when(root.get(FILLS, PersistentDataType.TAG_CONTAINER)).thenReturn(fills);
        when(root.get(BLOB, PersistentDataType.STRING)).thenReturn(blob);
        return item(root);
    }

    private static ItemStack item(PersistentDataContainer root) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        Material type = mock(Material.class);
        when(item.getType()).thenReturn(type);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(root);
        return item;
    }
}
