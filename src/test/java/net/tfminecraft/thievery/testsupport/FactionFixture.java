package net.tfminecraft.thievery.testsupport;

import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.OwnerIdentity;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;

/**
 * Ready identity for interaction tests: by default each player plays the character whose ID is their account
 * UUID, so {@code owner(player)} is that player's character. Tests switch characters with {@link #plays}.
 */
public final class FactionFixture implements AutoCloseable {
    private final MockedStatic<OwnerIdentity> identity = mockStatic(OwnerIdentity.class);
    private final Map<Player, UUID> playing = new HashMap<>();
    private final Map<UUID, UUID> accounts = new HashMap<>();
    public final FakeGuilds guilds = FakeGuilds.install();

    public FactionFixture() {
        identity.when(OwnerIdentity::available).thenReturn(true);
        identity.when(OwnerIdentity::heirsReady).thenReturn(true);
        identity.when(OwnerIdentity::heirApiPresent).thenReturn(true);
        identity.when(() -> OwnerIdentity.currentCharacter(any())).thenAnswer(call -> {
            Player player = call.getArgument(0);
            if (player == null) return Optional.empty();
            if (playing.containsKey(player)) return Optional.ofNullable(playing.get(player));
            return Optional.ofNullable(player.getUniqueId());
        });
        identity.when(() -> OwnerIdentity.info(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return id == null ? Optional.empty() : Optional.of(info(id, accounts.getOrDefault(id, id), CharacterStatus.ALIVE));
        });
        identity.when(() -> OwnerIdentity.heir(any())).thenReturn(Optional.empty());
        identity.when(() -> OwnerIdentity.accountKnown(any())).thenReturn(true);
        identity.when(() -> OwnerIdentity.playing(any())).thenReturn(false);
    }

    public static CharacterInfo info(UUID characterId, UUID account, CharacterStatus status) {
        return new CharacterInfo(new CharacterRef(account, characterId), "Char-" + characterId.toString().substring(0, 4),
                status, null, Set.of(), 0, Instant.EPOCH);
    }

    /** A character owner, or null for null: lets older tests keep passing plain IDs. */
    public static LockOwner co(UUID characterId) {
        return characterId == null ? null : LockOwner.character(characterId);
    }

    /** The lock owner a player's default character stamps. */
    public static LockOwner owner(Player player) {
        return LockOwner.character(player.getUniqueId());
    }

    /** Switch the player to another character (null: no current session). */
    public FactionFixture plays(Player player, UUID characterId) {
        playing.put(player, characterId);
        return this;
    }

    public MockedStatic<OwnerIdentity> identity() {
        return identity;
    }

    public void guild(UUID owner, String incarnation, String factionId) {
        guilds.member(owner, incarnation, factionId);
    }

    @Override public void close() { identity.close(); }
}
