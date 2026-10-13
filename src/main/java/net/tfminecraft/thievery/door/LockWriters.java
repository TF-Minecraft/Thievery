package net.tfminecraft.thievery.door;

import org.bukkit.entity.Player;

import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.OwnerIdentity;

/** Who a new lock belongs to (rule 2). Writers act for the player's current character only. */
public final class LockWriters {

    public static final String STAFF = "thievery.admin";
    public static final String NEED_CHARACTER = "Play a character to claim or change locks.";

    private LockWriters() {}

    /** Placing a lockable: the current character, the server for staff without one, otherwise left unowned (null). */
    public static LockOwner placementOwner(Player player) {
        LockOwner claim = claimOwner(player);
        if (claim != null) {
            return claim;
        }
        return player.hasPermission(STAFF) ? LockOwner.server() : null;
    }

    /** Claiming, rotating or locking: only a current character; null means the action is refused. */
    public static LockOwner claimOwner(Player player) {
        return OwnerIdentity.currentCharacter(player).map(LockOwner::character).orElse(null);
    }

    /** The state a new lock starts in: server locks are private, everything else public. */
    public static LockState initialState(LockOwner owner) {
        return owner.isServer() ? LockState.PRIVATE : LockState.DEFAULT;
    }
}
