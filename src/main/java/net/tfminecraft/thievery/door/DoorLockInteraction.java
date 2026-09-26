package net.tfminecraft.thievery.door;

final class DoorLockInteraction {

    private DoorLockInteraction() {}

    /**
     * A locked door or fence gate can still be closed. A locked trapdoor cannot
     * be toggled without a key, including when its block data is already open.
     */
    static boolean allowsToggleWithoutKey(boolean open, boolean trapdoor) {
        return open && !trapdoor;
    }
}
