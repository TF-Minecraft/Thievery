package net.tfminecraft.thievery.door;

import net.tfminecraft.thievery.owner.LockOwner;

/** The owner of a double chest, read from both halves. */
public record ChestOwners(LockOwner owner, boolean diverged) {

    /** Both halves unowned gives no owner; halves with two different owners are diverged and act for nobody. */
    public static ChestOwners of(ContainerData left, ContainerData right) {
        return of(left.getOwner(), right.getOwner());
    }

    public static ChestOwners of(LockOwner left, LockOwner right) {
        if (left != null && right != null && !left.equals(right)) {
            return new ChestOwners(null, true);
        }
        return new ChestOwners(left != null ? left : right, false);
    }

    /** The owner picks and clues are keyed to: diverged halves count as an unresolved, unpickable owner. */
    public LockOwner effective() {
        return diverged ? LockOwner.unreadable() : owner;
    }
}
