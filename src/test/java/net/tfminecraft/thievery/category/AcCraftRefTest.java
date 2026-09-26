package net.tfminecraft.thievery.category;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

class AcCraftRefTest {
    @Test
    void retainsTemplateSpellingAndRawTierWhileTrimmingOuterWhitespace() {
        AcCraftRef ref = AcCraftRef.parse("  ac_Long_Sword_tier_003  ").orElseThrow();
        assertEquals("ac_Long_Sword_tier_003", ref.getRawId());
        assertEquals("Long_Sword", ref.getStatTemplate());
        assertEquals(3, ref.getTier());
    }

    @Test
    void usesLastTierMarkerAndAcceptsPositiveIntegerRange() {
        assertEquals("blade_tier_1", AcCraftRef.parse("ac_blade_tier_1_tier_2").orElseThrow().getStatTemplate());
        assertEquals(Integer.MAX_VALUE, AcCraftRef.parse("ac_blade_tier_2147483647").orElseThrow().getTier());
        assertEquals(1, AcCraftRef.parse("ac_blade_tier_+1").orElseThrow().getTier());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "AC_blade_tier_1", "blade_tier_1", "ac_blade", "ac_tier_1",
            "ac__tier_1", "ac_ _tier_1", "ac_blade_TIER_1", "ac_blade_tier_", "ac_blade_tier_bad",
            "ac_blade_tier_1.5", "ac_blade_tier_2147483648", "ac_blade_tier_0", "ac_blade_tier_-1",
            "ac_blade_tier_ 1"})
    void rejectsMalformedIds(String id) {
        assertTrue(AcCraftRef.parse(id).isEmpty());
    }

    @Test
    void equalityIgnoresTemplateCaseAndTierFormatting() {
        AcCraftRef ref = AcCraftRef.parse("ac_Sword_tier_1").orElseThrow();
        AcCraftRef equivalent = AcCraftRef.parse("ac_sword_tier_001").orElseThrow();
        assertEquals(ref, ref);
        assertEquals(ref, equivalent);
        assertEquals(equivalent, ref);
        assertEquals(ref.hashCode(), equivalent.hashCode());
        assertNotEquals(ref, null);
        assertNotEquals(ref, ref.getRawId());
        assertNotEquals(ref, AcCraftRef.parse("ac_sword_tier_2").orElseThrow());
        assertNotEquals(ref, AcCraftRef.parse("ac_bow_tier_1").orElseThrow());
    }

    @ParameterizedTest
    @MethodSource("caseEquivalentTemplates")
    void equivalentTemplateIdsRemainUsableAsHashKeysAcrossLocales(String locale, String left, String right) {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(locale));
            AcCraftRef upper = AcCraftRef.parse("ac_" + left + "_tier_1").orElseThrow();
            AcCraftRef lower = AcCraftRef.parse("ac_" + right + "_tier_1").orElseThrow();
            Set<AcCraftRef> configured = new HashSet<>(Set.of(upper));
            assertEquals(upper, lower);
            assertAll(
                    () -> assertEquals(upper.hashCode(), lower.hashCode(), "Equal references must share a hash code"),
                    () -> assertTrue(configured.contains(lower), "A case variant must resolve the configured craft key"));
        } finally {
            Locale.setDefault(original);
        }
    }


    private static Stream<Arguments> caseEquivalentTemplates() {
        return Stream.of("en-US", "tr-TR").flatMap(locale -> Stream.of(
                new String[]{"IRON", "iron"},
                new String[]{"i", "İ"},
                new String[]{"i", "ı"},
                new String[]{"σ", "ς"})
                .map(pair -> Arguments.of(locale, pair[0], pair[1])));
    }

}
