package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.thievery.testsupport.FactionFixture;

class OwnerCodecTest {
    FactionFixture identity;
    @BeforeEach void setUp() { identity = new FactionFixture(); }
    @AfterEach void tearDown() { identity.close(); }

    @Test void storedOwnersParseStrictly() {
        UUID id = UUID.randomUUID();
        assertEquals(OwnerCodec.Stored.NONE, OwnerCodec.parse(null, null));
        assertFalse(OwnerCodec.Stored.NONE.owned());
        assertFalse(OwnerCodec.Stored.NONE.markerless());
        OwnerCodec.Stored bare = OwnerCodec.parse(id.toString(), null);
        assertTrue(bare.markerless());
        assertTrue(bare.owned());
        assertEquals(new OwnerCodec.Stored(id, LockOwner.Kind.CHARACTER), OwnerCodec.parse(id.toString(), "CHARACTER"));
        assertFalse(OwnerCodec.parse(id.toString(), "CHARACTER").markerless());
        assertEquals(new OwnerCodec.Stored(LockOwner.NIL, LockOwner.Kind.SERVER), OwnerCodec.parse(LockOwner.NIL.toString(), "SERVER"));
        assertEquals(id, OwnerCodec.parse(id.toString().toUpperCase(), null).id());
        for (String[] bad : new String[][] { {null, "CHARACTER"}, {"nope", null}, {"1-1-1-1-1", null}, {id.toString(), "ALIEN"},
                {id.toString(), "SERVER"}, {LockOwner.NIL.toString(), "CHARACTER"}, {id.toString(), "LEGACY"}, {id.toString(), "UNRESOLVED"} }) {
            assertThrows(IllegalArgumentException.class, () -> OwnerCodec.parse(bad[0], bad[1]), String.join(",", String.valueOf(bad[0]), String.valueOf(bad[1])));
        }
    }

    @Test void markedOwnersResolveWithoutLookupsAndBareOnesAreClassified() {
        UUID id = UUID.randomUUID();
        assertNull(OwnerCodec.resolve(OwnerCodec.Stored.NONE).owner());
        assertTrue(OwnerCodec.resolve(OwnerCodec.Stored.NONE).definitive());
        identity.identity().when(OwnerIdentity::available).thenReturn(false);
        assertEquals(LockOwner.character(id), OwnerCodec.resolve(new OwnerCodec.Stored(id, LockOwner.Kind.CHARACTER)).owner());
        assertEquals(LockOwner.server(), OwnerCodec.resolve(new OwnerCodec.Stored(LockOwner.NIL, LockOwner.Kind.SERVER)).owner());
        OwnerCodec.Resolved down = OwnerCodec.resolve(new OwnerCodec.Stored(id, null));
        assertEquals(LockOwner.unresolved(id), down.owner());
        assertFalse(down.definitive());
        identity.identity().when(OwnerIdentity::available).thenReturn(true);
        assertEquals(LockOwner.character(id), OwnerCodec.resolve(new OwnerCodec.Stored(id, null)).owner());
        identity.identity().when(() -> OwnerIdentity.info(id)).thenReturn(Optional.empty());
        assertEquals(LockOwner.unresolved(id), OwnerCodec.resolve(new OwnerCodec.Stored(id, null)).owner());
    }

    @Test void ownersAreStoredWithMarkersOnlyWhenResolvedAndUnreadableOnesNever() {
        UUID id = UUID.randomUUID();
        assertEquals(OwnerCodec.Stored.NONE, OwnerCodec.store(null));
        assertEquals(new OwnerCodec.Stored(id, LockOwner.Kind.CHARACTER), OwnerCodec.store(LockOwner.character(id)));
        assertEquals(new OwnerCodec.Stored(LockOwner.NIL, LockOwner.Kind.SERVER), OwnerCodec.store(LockOwner.server()));
        assertEquals(new OwnerCodec.Stored(id, null), OwnerCodec.store(LockOwner.legacy(id)));
        assertEquals(new OwnerCodec.Stored(id, null), OwnerCodec.store(LockOwner.unresolved(id)));
        assertThrows(IllegalStateException.class, () -> OwnerCodec.store(LockOwner.unreadable()));
    }
}
