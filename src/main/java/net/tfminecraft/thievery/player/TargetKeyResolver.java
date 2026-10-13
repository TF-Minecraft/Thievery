package net.tfminecraft.thievery.player;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockGuilds;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.OwnerIdentity;

/**
 * Keys a theft's victim for clue bookkeeping and the lockpick window: {@code guild:<incarnation>}, otherwise
 * {@code player:<characterId>}. Keys written before character locks used other values and simply age out.
 */
public final class TargetKeyResolver {

    public static final String UNOWNED = "unowned";
    public static final String SERVER = "server";

    private TargetKeyResolver() {}

    /** Never "none": quarantined and unreadable owners key as {@code unresolved:<uuid>}. */
    public static String forLockOwner(LockOwner owner) {
        if (owner == null) {
            return UNOWNED;
        }
        return switch (owner.kind()) {
            case SERVER -> SERVER;
            case CHARACTER -> forCharacter(owner.id());
            case LEGACY, UNRESOLVED -> "unresolved:" + (owner.id() == null ? "unreadable" : owner.id());
        };
    }

    /** The victim's current character, or their account when they are not playing one. */
    public static String forVictim(Player victim) {
        Optional<UUID> character = OwnerIdentity.currentCharacter(victim);
        return character.map(TargetKeyResolver::forCharacter).orElse("unresolved:" + victim.getUniqueId());
    }

    private static String forCharacter(UUID characterId) {
        LockGuilds guilds = LockGuildBridge.get();
        Optional<LockGuilds.GuildInfo> guild = guilds.ready() ? guilds.lockGuild(characterId) : Optional.empty();
        return guild.map(info -> "guild:" + info.incarnation()).orElse("player:" + characterId);
    }
}
