package net.tfminecraft.thievery.testsupport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockGuilds;

/** An in-memory lockGuild source: character -> guild incarnation and faction. */
public final class FakeGuilds implements LockGuilds {
    public boolean ready = true;
    private final Map<UUID, GuildInfo> guilds = new HashMap<>();
    private final Set<String> online = new HashSet<>();

    public static FakeGuilds install() {
        FakeGuilds fake = new FakeGuilds();
        LockGuildBridge.set(fake);
        return fake;
    }

    public FakeGuilds member(UUID characterId, String incarnation, String factionId) {
        guilds.put(characterId, new GuildInfo(incarnation, factionId));
        return this;
    }

    public FakeGuilds leave(UUID characterId) {
        guilds.remove(characterId);
        return this;
    }

    public FakeGuilds online(String incarnation, boolean on) {
        if (on) online.add(incarnation); else online.remove(incarnation);
        return this;
    }

    @Override public boolean ready() { return ready; }

    @Override public Optional<GuildInfo> lockGuild(UUID characterId) {
        return Optional.ofNullable(guilds.get(characterId));
    }

    @Override public boolean anyMemberOnline(UUID characterId) {
        return lockGuild(characterId).map(info -> online.contains(info.incarnation())).orElse(false);
    }
}
