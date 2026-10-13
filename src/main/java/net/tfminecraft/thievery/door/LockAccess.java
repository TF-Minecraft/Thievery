package net.tfminecraft.thievery.door;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.thievery.owner.LockGuildBridge;
import net.tfminecraft.thievery.owner.LockGuilds;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.OwnerIdentity;

/** Who may use a lock. Staff overrides are applied by callers. */
public final class LockAccess {

    private LockAccess() {}

    /**
     * PUBLIC and unowned locks are open to all. Otherwise the owner is the owning character's current session,
     * guild locks follow the owner's {@code lockGuild} by incarnation and faction locks the faction of that guild.
     * Server, legacy, unresolved and unreadable owners deny everyone.
     */
    public static boolean canAccess(Player player, LockOwner owner, LockState lockState) {
        if (lockState == LockState.PUBLIC || owner == null) {
            return true;
        }
        if (isOwner(player, owner)) {
            return true;
        }
        if (lockState == LockState.PRIVATE || !owner.isCharacter()) {
            return false;
        }
        Optional<LockGuilds.GuildInfo> ownerGuild = guildOf(owner.id());
        Optional<LockGuilds.GuildInfo> openerGuild = OwnerIdentity.currentCharacter(player).flatMap(LockAccess::guildOf);
        if (ownerGuild.isEmpty() || openerGuild.isEmpty()) {
            return false;
        }
        if (lockState == LockState.GUILD) {
            return ownerGuild.get().incarnation().equals(openerGuild.get().incarnation());
        }
        String ownerFaction = ownerGuild.get().factionId();
        return ownerFaction != null && ownerFaction.equals(openerGuild.get().factionId());
    }

    /** Whether the player is playing the character that owns this lock right now. */
    public static boolean isOwner(Player player, LockOwner owner) {
        return owner != null && owner.isCharacter()
                && OwnerIdentity.currentCharacter(player).filter(owner.id()::equals).isPresent();
    }

    /** Whether the player's current character is in the guild the owner's locks follow (for door clues). */
    public static boolean sharesLockGuild(Player player, LockOwner owner) {
        if (owner == null || !owner.isCharacter()) {
            return false;
        }
        Optional<LockGuilds.GuildInfo> ownerGuild = guildOf(owner.id());
        Optional<LockGuilds.GuildInfo> playerGuild = OwnerIdentity.currentCharacter(player).flatMap(LockAccess::guildOf);
        return ownerGuild.isPresent() && playerGuild.isPresent()
                && ownerGuild.get().incarnation().equals(playerGuild.get().incarnation());
    }

    private static Optional<LockGuilds.GuildInfo> guildOf(UUID characterId) {
        LockGuilds guilds = LockGuildBridge.get();
        return guilds.ready() ? guilds.lockGuild(characterId) : Optional.empty();
    }
}
