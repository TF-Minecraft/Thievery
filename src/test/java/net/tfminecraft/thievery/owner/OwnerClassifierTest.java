package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.thievery.testsupport.FactionFixture;

class OwnerClassifierTest {
    FactionFixture identity;
    final UUID account = UUID.randomUUID();
    final UUID heir = UUID.randomUUID();
    @BeforeEach void setUp() { identity = new FactionFixture(); }
    @AfterEach void tearDown() { identity.close(); }

    @Test void theNilUuidIsTheServerBeforeAnyLookup() {
        identity.identity().when(OwnerIdentity::available).thenReturn(false);
        var server = OwnerClassifier.classify(LockOwner.NIL);
        assertEquals(LockOwner.server(), server.owner());
        assertTrue(server.definitive());
        identity.identity().verify(() -> OwnerIdentity.info(LockOwner.NIL), org.mockito.Mockito.never());
        identity.identity().verify(() -> OwnerIdentity.heir(LockOwner.NIL), org.mockito.Mockito.never());
    }

    @Test void charactersComeFirstThenHeirsThenUnresolved() {
        var character = OwnerClassifier.classify(account);
        assertEquals(LockOwner.character(account), character.owner());
        assertTrue(character.definitive());
        identity.identity().when(() -> OwnerIdentity.info(account)).thenReturn(Optional.empty());
        identity.identity().when(() -> OwnerIdentity.heir(account)).thenReturn(Optional.of(heir));
        var legacy = OwnerClassifier.classify(account);
        assertEquals(LockOwner.legacy(account), legacy.owner());
        assertEquals(heir, legacy.heir());
        assertTrue(legacy.definitive());
        identity.identity().when(() -> OwnerIdentity.heir(account)).thenReturn(Optional.empty());
        var orphan = OwnerClassifier.classify(account);
        assertEquals(LockOwner.unresolved(account), orphan.owner());
        assertTrue(orphan.definitive());
    }

    @Test void answersMadeWhileIdentityIsUnavailableOrUnknownAreNotDefinitive() {
        identity.identity().when(() -> OwnerIdentity.info(account)).thenReturn(Optional.empty());
        identity.identity().when(() -> OwnerIdentity.accountKnown(account)).thenReturn(false);
        assertFalse(OwnerClassifier.classify(account).definitive());
        identity.identity().when(() -> OwnerIdentity.accountKnown(account)).thenReturn(true);
        identity.identity().when(OwnerIdentity::heirsReady).thenReturn(false);
        assertFalse(OwnerClassifier.classify(account).definitive());
        identity.identity().when(OwnerIdentity::available).thenReturn(false);
        var down = OwnerClassifier.classify(account);
        assertEquals(LockOwner.unresolved(account), down.owner());
        assertFalse(down.definitive());
    }
}
