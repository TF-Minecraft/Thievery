package net.tfminecraft.thievery.owner;

import java.util.Optional;
import java.util.UUID;

/**
 * The guild a lock follows, from SimpleFactions' {@code lockGuild}. Values cross back as plain strings so no
 * SimpleFactions type leaves the bridge.
 */
public interface LockGuilds {

    /** The guild's incarnation and its faction's ID (null when the guild has no faction). */
    record GuildInfo(String incarnation, String factionId) {}

    /** Whether lookups can answer now. While false, guild and faction locks deny and nothing is persisted. */
    boolean ready();

    /** The guild this character's locks follow: their current guild, or for the dead the guild at death. */
    Optional<GuildInfo> lockGuild(UUID characterId);

    /** Whether any member of that guild is playing now. */
    boolean anyMemberOnline(UUID characterId);

    /** Used when SimpleFactions or its {@code lockGuild} is missing: guild and faction locks deny everyone. */
    LockGuilds UNAVAILABLE = new LockGuilds() {
        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public Optional<GuildInfo> lockGuild(UUID characterId) {
            return Optional.empty();
        }

        @Override
        public boolean anyMemberOnline(UUID characterId) {
            return false;
        }
    };
}
