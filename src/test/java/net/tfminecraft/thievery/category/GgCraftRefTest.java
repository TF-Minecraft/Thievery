package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class GgCraftRefTest {
    private static final NamespacedKey TYPE = NamespacedKey.fromString("gunsandgadgets:gun_type");
    private static final NamespacedKey TIER = NamespacedKey.fromString("gunsandgadgets:gg_majority_tier");

    @ParameterizedTest
    @ValueSource(strings = {"rifle", "pistol", "shotgun", "launcher"})
    void canonicalizesEverySupportedType(String type) {
        GgCraftRef ref = GgCraftRef.parse("  GG_" + type.toUpperCase(java.util.Locale.ROOT) + "_TIER_003  ").orElseThrow();
        assertEquals("gg_" + type + "_tier_3", ref.getRawId());
        assertEquals(type, ref.getGunType());
        assertEquals(3, ref.getTier());
        assertEquals(Character.toUpperCase(type.charAt(0)) + type.substring(1), ref.getDisplayName());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "rifle_tier_1", "magic_rifle_tier_1", "gg_rifle", "gg_tier_1",
            "gg__tier_1", "gg_unknown_tier_1", "gg_rifle_tier_1_tier_2", "gg_rifle_tier_",
            "gg_rifle_tier_x", "gg_rifle_tier_1.5", "gg_rifle_tier_2147483648", "gg_rifle_tier_0",
            "gg_rifle_tier_-1", "gg_rifle_tier_ 1"})
    void rejectsMalformedIds(String id) {
        assertTrue(GgCraftRef.parse(id).isEmpty());
    }

    @Test
    void acceptsTheFullPositiveIntegerRange() {
        assertEquals(Integer.MAX_VALUE, GgCraftRef.parse("gg_rifle_tier_2147483647").orElseThrow().getTier());
        assertEquals("gg_rifle_tier_1", GgCraftRef.parse("gg_rifle_tier_+1").orElseThrow().getRawId());
    }

    @Test
    void equalityAndHashCodeUseCanonicalTypeAndTier() {
        GgCraftRef ref = GgCraftRef.parse("gg_rifle_tier_1").orElseThrow();
        GgCraftRef equivalent = GgCraftRef.parse(" GG_RIFLE_TIER_01 ").orElseThrow();
        assertEquals(ref, ref);
        assertEquals(ref, equivalent);
        assertEquals(equivalent, ref);
        assertEquals(ref.hashCode(), equivalent.hashCode());
        assertNotEquals(ref, null);
        assertNotEquals(ref, ref.getRawId());
        assertNotEquals(ref, GgCraftRef.parse("gg_rifle_tier_2").orElseThrow());
        assertNotEquals(ref, GgCraftRef.parse("gg_pistol_tier_1").orElseThrow());
    }

    @Test
    void rejectsMissingItemsAirAndItemsWithoutMetadata() {
        assertTrue(GgCraftRef.fromItem(null).isEmpty());
        ItemStack air = mock(ItemStack.class);
        Material airType = mock(Material.class);
        when(airType.isAir()).thenReturn(true);
        when(air.getType()).thenReturn(airType);
        assertTrue(GgCraftRef.fromItem(air).isEmpty());
        verify(air, never()).getItemMeta();
        ItemStack plain = mock(ItemStack.class);
        Material plainType = mock(Material.class);
        when(plain.getType()).thenReturn(plainType);
        assertTrue(GgCraftRef.fromItem(plain).isEmpty());
        verify(plain, never()).getItemMeta();
    }

    @Test
    void rejectsIncompleteInvalidAndUnsupportedMetadata() {
        assertTrue(GgCraftRef.fromItem(item(null, 1)).isEmpty());
        assertTrue(GgCraftRef.fromItem(item("  ", 1)).isEmpty());
        assertTrue(GgCraftRef.fromItem(item("rifle", null)).isEmpty());
        assertTrue(GgCraftRef.fromItem(item("rifle", 0)).isEmpty());
        assertTrue(GgCraftRef.fromItem(item("rifle", -1)).isEmpty());
        assertTrue(GgCraftRef.fromItem(item("wand", 1)).isEmpty());
    }

    @Test
    void readsCanonicalReferenceFromNamespacedTypedMetadata() {
        assertEquals(GgCraftRef.parse("gg_shotgun_tier_4").orElseThrow(),
                GgCraftRef.fromItem(item("  SHOTGUN  ", 4)).orElseThrow());
    }

    private static ItemStack item(String type, Integer tier) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        Material material = mock(Material.class);
        when(item.getType()).thenReturn(material);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(TYPE, PersistentDataType.STRING)).thenReturn(type);
        when(pdc.get(TIER, PersistentDataType.INTEGER)).thenReturn(tier);
        return item;
    }
}
