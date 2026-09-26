package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import net.tfminecraft.thievery.category.CategorySlugs.SlugSpecificity;

class CategorySlugsTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void emptySlugsHaveNoClassificationOrReference(String slug) {
        assertFalse(CategorySlugs.isMaterialSlug(slug));
        assertFalse(CategorySlugs.isAcSlug(slug));
        assertFalse(CategorySlugs.isGgSlug(slug));
        assertFalse(CategorySlugs.isMagicSlug(slug));
        assertFalse(CategorySlugs.isCraftSlug(slug));
        assertFalse(CategorySlugs.isPathSlug(slug));
        assertFalse(CategorySlugs.isMmoTypeSlug(slug));
        assertNull(CategorySlugs.mmoTypeId(slug));
        assertTrue(CategorySlugs.parseCraftRef(slug).isEmpty());
        assertTrue(CategorySlugs.parseGgCraftRef(slug).isEmpty());
        assertTrue(CategorySlugs.parseMagicCraftRef(slug).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"metal", "wood", "crystal", "leather", "feather", "wool"})
    void recognizesMaterialFamiliesBeforeCraftReferences(String type) {
        String slug = " ac_" + type + "_tier_2 ";
        assertTrue(CategorySlugs.isMaterialSlug(slug));
        assertTrue(CategorySlugs.isAcSlug(slug));
        assertFalse(CategorySlugs.isCraftSlug(slug));
        assertFalse(CategorySlugs.isPathSlug(slug));
        assertEquals(type, CategorySlugs.materialType(slug));
        assertEquals(2, CategorySlugs.materialTier(slug));
    }

    @Test
    void materialPatternIsCaseInsensitiveAndDoesNotImposeCraftTierValidation() {
        assertTrue(CategorySlugs.isMaterialSlug(" AC_METAL_TIER_00 "));
        assertEquals("METAL", CategorySlugs.materialType(" AC_METAL_TIER_00 "));
        assertEquals(0, CategorySlugs.materialTier(" AC_METAL_TIER_00 "));
        assertFalse(CategorySlugs.isMaterialSlug("ac_blade_tier_1"));
        assertNull(CategorySlugs.materialType("ac_blade_tier_1"));
        assertEquals(0, CategorySlugs.materialTier("ac_blade_tier_1"));
        assertFalse(CategorySlugs.isMaterialSlug("ac_metal_tier_-1"));
    }

    @Test
    void delegatesCraftParsingAndKeepsFamiliesDistinct() {
        assertEquals(AcCraftRef.parse("ac_blade_tier_1"), CategorySlugs.parseCraftRef(" ac_blade_tier_1 "));
        assertEquals(GgCraftRef.parse("gg_rifle_tier_1"), CategorySlugs.parseGgCraftRef(" GG_RIFLE_TIER_1 "));
        assertEquals(MagicCraftRef.parse("magic_staff_tier_1"), CategorySlugs.parseMagicCraftRef(" MAGIC_STAFF_TIER_1 "));
        assertTrue(CategorySlugs.parseCraftRef("v.stick").isEmpty());
        assertTrue(CategorySlugs.parseGgCraftRef("v.stick").isEmpty());
        assertTrue(CategorySlugs.parseMagicCraftRef("v.stick").isEmpty());
        for (String slug : new String[]{"ac_blade_tier_1", "gg_rifle_tier_1", "magic_staff_tier_1"}) {
            assertTrue(CategorySlugs.isCraftSlug(slug));
            assertFalse(CategorySlugs.isPathSlug(slug));
        }
        assertFalse(CategorySlugs.isCraftSlug("v.stick"));
        assertTrue(CategorySlugs.isPathSlug("v.stick"));
    }

    @Test
    void mmoTypeSlugsUseTwoComponentsAndPreserveTypeSpelling() {
        assertTrue(CategorySlugs.isMmoTypeSlug(" M.SWORD "));
        assertEquals("SWORD", CategorySlugs.mmoTypeId(" M.SWORD "));
        assertFalse(CategorySlugs.isPathSlug("m.SWORD"));
        for (String slug : new String[]{"v.SWORD", "m", "m.", "m. ", "m. .", "m.SWORD.ID"}) {
            assertFalse(CategorySlugs.isMmoTypeSlug(slug), slug);
            assertNull(CategorySlugs.mmoTypeId(slug), slug);
        }
    }

    @Test
    void specificityRanksOrderExactFuzzyCraftMaterialAndType() {
        assertEquals(4, SlugSpecificity.EXACT_PATH.getRank());
        assertEquals(3, SlugSpecificity.FUZZY_PATH.getRank());
        assertEquals(2, SlugSpecificity.CRAFT_REF.getRank());
        assertEquals(1, SlugSpecificity.MATERIAL_TIER.getRank());
        assertEquals(0, SlugSpecificity.MMO_TYPE.getRank());
    }
}
