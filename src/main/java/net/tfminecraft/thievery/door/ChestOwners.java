package net.tfminecraft.thievery.door;

import net.tfminecraft.thievery.owner.LockOwner;

/** The owner of a double chest, read from both halves. */
public record ChestOwners(LockOwner owner, boolean diverged) {

    /** Both halves unowned gives no owner; halves with two different owners are diverged and act for nobody. */
    public static ChestOwners of(ContainerData left, ContainerData right) {
        LockOwner a = left.getOwner();
        LockOwner b = right.getOwner();
        if (a != null && b != null && !a.equals(b)) {
            return new ChestOwners(null, true);
        }
        return new ChestOwners(a != null ? a : b, false);
    }
}
