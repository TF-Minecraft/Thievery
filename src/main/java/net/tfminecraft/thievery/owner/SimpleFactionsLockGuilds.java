package net.tfminecraft.thievery.owner;

import java.util.Optional;
import java.util.UUID;

import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.identity.Members;

/** The only Thievery class that sees SimpleFactions types on the ownership paths. */
final class SimpleFactionsLockGuilds implements LockGuilds {

    @Override
    public boolean ready() {
        return SimpleFactionsApi.ready();
    }

    @Override
    public Optional<GuildInfo> lockGuild(UUID characterId) {
        if (characterId == null) {
            return Optional.empty();
        }
        return SimpleFactionsApi.lockGuild(characterId).map(guild -> new GuildInfo(guild.getIncarnation(),
                guild.getFaction() == null ? null : guild.getFaction().getId()));
    }

    @Override
    public boolean anyMemberOnline(UUID characterId) {
        if (characterId == null) {
            return false;
        }
        Optional<Guild> guild = SimpleFactionsApi.lockGuild(characterId);
        if (guild.isEmpty()) {
            return false;
        }
        for (String member : guild.get().getMembers()) {
            if (Members.online(member).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
