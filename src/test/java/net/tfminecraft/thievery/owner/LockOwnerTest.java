package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class LockOwnerTest {
    @Test void kindsCarryTheirIdAndOnlyCharactersAndTheServerAreResolvedAndMarked() {
        UUID id = UUID.randomUUID();
        LockOwner character = LockOwner.character(id);
        assertTrue(character.isCharacter());
        assertFalse(character.isServer());
        assertTrue(character.isResolved());
        assertEquals("CHARACTER", character.persistedKind());
        assertEquals(new LockOwner(LockOwner.Kind.CHARACTER, id), character);

        assertSame(LockOwner.server(), LockOwner.server());
        assertEquals(LockOwner.NIL, LockOwner.server().id());
        assertTrue(LockOwner.server().isServer());
        assertTrue(LockOwner.server().isResolved());
        assertEquals("SERVER", LockOwner.server().persistedKind());

        for (LockOwner unresolved : new LockOwner[] { LockOwner.legacy(id), LockOwner.unresolved(id), LockOwner.unreadable() }) {
            assertFalse(unresolved.isResolved());
            assertFalse(unresolved.isCharacter());
            assertNull(unresolved.persistedKind());
        }
        assertNull(LockOwner.unreadable().id());
        assertNotEquals(LockOwner.legacy(id), LockOwner.character(id));
    }

    @Test void onlyUnresolvedOwnersMayLackAnId() {
        assertThrows(NullPointerException.class, () -> LockOwner.character(null));
        assertThrows(NullPointerException.class, () -> LockOwner.legacy(null));
        assertThrows(NullPointerException.class, () -> new LockOwner(null, UUID.randomUUID()));
        assertNull(LockOwner.unresolved(null).id());
    }
}
