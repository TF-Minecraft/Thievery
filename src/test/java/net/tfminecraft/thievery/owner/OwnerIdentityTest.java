package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.player.TraitChecker;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.tlibs.character.CharacterStatus;

class OwnerIdentityTest {
    MockedStatic<Characters> characters;
    MockedStatic<TraitChecker> traits;
    final UUID account = UUID.randomUUID();
    final UUID kira = UUID.randomUUID();
    Player player;

    @BeforeEach void setUp() {
        characters = mockStatic(Characters.class);
        traits = mockStatic(TraitChecker.class);
        traits.when(TraitChecker::characterApiAvailable).thenReturn(true);
        characters.when(Characters::available).thenReturn(true);
        characters.when(Characters::legacyHeirsReady).thenReturn(true);
        player = mock(Player.class);
        OwnerIdentity.resetProbe();
    }
    @AfterEach void tearDown() { traits.close(); characters.close(); OwnerIdentity.resetProbe(); }

    @Test void theHeirApiIsProbedOnceAndPresentOnThisTlibs() throws Exception {
        assertTrue(OwnerIdentity.probeHeirApi("net.tfminecraft.tlibs.character.Characters"));
        assertFalse(OwnerIdentity.probeHeirApi("net.tfminecraft.tlibs.character.Missing"));
        assertFalse(OwnerIdentity.probeHeirApi("java.lang.String"));
        assertTrue(OwnerIdentity.heirApiPresent());
        assertTrue(OwnerIdentity.heirApiPresent());
        assertTrue(OwnerIdentity.heirsReady());
        characters.when(Characters::legacyHeirsReady).thenReturn(false);
        assertFalse(OwnerIdentity.heirsReady());
        characters.when(Characters::legacyHeirsReady).thenReturn(true);
        java.lang.reflect.Field cached = OwnerIdentity.class.getDeclaredField("heirApi");
        cached.setAccessible(true);
        cached.set(null, Boolean.FALSE);
        assertFalse(OwnerIdentity.heirsReady(), "TLibs older than 2.4.0 never has ready heirs");
    }

    @Test void withoutTheCharacterApiOrAProviderNothingIsKnown() {
        traits.when(TraitChecker::characterApiAvailable).thenReturn(false);
        assertFalse(OwnerIdentity.available());
        assertFalse(OwnerIdentity.heirsReady());
        assertTrue(OwnerIdentity.currentCharacter(player).isEmpty());
        assertTrue(OwnerIdentity.info(kira).isEmpty());
        assertTrue(OwnerIdentity.heir(account).isEmpty());
        assertFalse(OwnerIdentity.accountKnown(account));
        traits.when(TraitChecker::characterApiAvailable).thenReturn(true);
        characters.when(Characters::available).thenReturn(false);
        assertFalse(OwnerIdentity.available());
        assertTrue(OwnerIdentity.currentCharacter(null).isEmpty());
        assertTrue(OwnerIdentity.info(null).isEmpty());
        assertTrue(OwnerIdentity.heir(null).isEmpty());
        assertFalse(OwnerIdentity.accountKnown(null));
        characters.verify(() -> Characters.info(any()), never());
    }

    @Test void theCurrentCharacterNeedsACurrentSession() {
        CharacterSession session = new CharacterSession(new CharacterRef(account, kira), 3);
        assertTrue(OwnerIdentity.currentCharacter(player).isEmpty());
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session));
        assertTrue(OwnerIdentity.currentCharacter(player).isEmpty());
        characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
        assertEquals(Optional.of(kira), OwnerIdentity.currentCharacter(player));
    }

    @Test void lookupsPassThroughToTlibs() {
        var info = FactionFixture.info(kira, account, CharacterStatus.DEAD);
        characters.when(() -> Characters.info(kira)).thenReturn(Optional.of(info));
        characters.when(() -> Characters.legacyHeir(account)).thenReturn(Optional.of(new CharacterRef(account, kira)));
        characters.when(() -> Characters.characters(account)).thenReturn(Optional.of(List.of()));
        assertEquals(Optional.of(info), OwnerIdentity.info(kira));
        assertEquals(Optional.of(kira), OwnerIdentity.heir(account));
        assertTrue(OwnerIdentity.accountKnown(account));
        assertFalse(OwnerIdentity.accountKnown(kira));
        characters.when(Characters::legacyHeirsReady).thenReturn(false);
        assertTrue(OwnerIdentity.heir(account).isEmpty());
    }

    @Test void someoneIsPlayingACharacterOnlyWhileTheirSessionIsCurrent() {
        Player other = mock(Player.class);
        CharacterSession session = new CharacterSession(new CharacterRef(account, kira), 3);
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(session));
        characters.when(() -> Characters.currentSession(other)).thenReturn(Optional.empty());
        characters.when(() -> Characters.isCurrent(session)).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(other, player));
            assertTrue(OwnerIdentity.playing(kira));
            assertFalse(OwnerIdentity.playing(UUID.randomUUID()));
            assertFalse(OwnerIdentity.playing(null));
        }
    }
}
