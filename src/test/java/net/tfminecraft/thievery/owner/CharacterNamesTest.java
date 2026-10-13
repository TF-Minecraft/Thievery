package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterStatus;

class CharacterNamesTest {
    static List<Object> answer = List.of();

    // Stands in for TLibs 2.5.0's Characters.findByName.
    public static List<Object> findByName(String name) {
        if (name.equals("boom")) {
            throw new IllegalStateException("off the main thread");
        }
        return answer;
    }

    FactionFixture identity;
    @BeforeEach void setUp() { identity = new FactionFixture(); }
    @AfterEach void tearDown() { identity.close(); CharacterNames.use(null); }

    @Test void theProbeFindsFindByNameOnlyWhereItExists() throws Exception {
        assertNull(CharacterNames.probe("net.tfminecraft.tlibs.character.Characters"), "TLibs 2.4 has no findByName");
        assertNull(CharacterNames.probe("net.tfminecraft.tlibs.character.Missing"));
        assertNotNull(CharacterNames.probe(CharacterNamesTest.class.getName()));
        CharacterNames.use(null);
        assertFalse(CharacterNames.available());
        assertTrue(CharacterNames.find("Kira").isEmpty());
    }

    @Test void onlyLivingCharactersMatchAndFailuresFindNobody() throws Exception {
        Method lookup = CharacterNamesTest.class.getMethod("findByName", String.class);
        CharacterNames.use(lookup);
        assertTrue(CharacterNames.available());
        CharacterInfo living = FactionFixture.info(UUID.randomUUID(), UUID.randomUUID(), CharacterStatus.ALIVE);
        CharacterInfo dead = FactionFixture.info(UUID.randomUUID(), UUID.randomUUID(), CharacterStatus.DEAD);
        answer = List.of(living, dead, "not a character");
        assertEquals(List.of(living), CharacterNames.find("Kira"));
        assertTrue(CharacterNames.find("boom").isEmpty());
        identity.identity().when(OwnerIdentity::available).thenReturn(false);
        assertTrue(CharacterNames.find("Kira").isEmpty());
    }

    @Test void theRealProbeRunsOnce() {
        CharacterNames.use(null);
        java.lang.reflect.Field probed;
        try {
            probed = CharacterNames.class.getDeclaredField("probed");
            probed.setAccessible(true);
            probed.setBoolean(null, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        assertFalse(CharacterNames.available());
        assertFalse(CharacterNames.available());
    }
}
