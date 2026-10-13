package net.tfminecraft.thievery.door;

import net.tfminecraft.thievery.owner.LockOwner;

public final class ChestMergeRules {

    private ChestMergeRules() {}

    /** Unowned neighbours are allowed; otherwise the neighbour must have exactly the owner the new chest gets. */
    public static boolean personallyOwns(LockOwner placer, LockOwner neighborOwner) {
        return neighborOwner == null || neighborOwner.equals(placer);
    }
}
