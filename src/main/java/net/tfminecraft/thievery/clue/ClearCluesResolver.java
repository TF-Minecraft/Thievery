package net.tfminecraft.thievery.clue;

import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;

import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.thievery.door.ChestOwners;
import net.tfminecraft.thievery.door.ContainerData;
import net.tfminecraft.thievery.door.LockAccess;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.door.DoorData;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.door.ContainerDataManager;
import net.tfminecraft.thievery.door.DoorDataManager;

public final class ClearCluesResolver {

    public enum Kind {
        DOOR,
        CONTAINER
    }

    public static final class ClearCluesTarget {
        private final Kind kind;
        private final Location canonicalLocation;
        private final LockOwner owner;
        private final LockState lockState;

        public ClearCluesTarget(Kind kind, Location canonicalLocation, LockOwner owner, LockState lockState) {
            this.kind = kind;
            this.canonicalLocation = canonicalLocation;
            this.owner = owner;
            this.lockState = lockState;
        }

        public Kind getKind() {
            return kind;
        }

        public Location getCanonicalLocation() {
            return canonicalLocation;
        }

        public LockOwner getOwner() {
            return owner;
        }

        public LockState getLockState() {
            return lockState;
        }
    }

    private static final ContainerDataManager containerDataManager = new ContainerDataManager();
    private static final DoorDataManager doorDataManager = new DoorDataManager();

    private ClearCluesResolver() {}

    public static Optional<ClearCluesTarget> resolve(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        if (isDoor(block)) {
            Location canonical = getDoorCanonicalLocation(block);
            DoorData doorData = doorDataManager.loadDoorData(canonical);
            LockOwner owner = doorData != null ? doorData.getOwner() : null;
            return Optional.of(new ClearCluesTarget(Kind.DOOR, canonical, owner, null));
        }
        if (block.getState() instanceof Container container) {
            return Optional.of(containerTarget(container));
        }
        return Optional.empty();
    }

    public static boolean canClear(Player player, ClearCluesTarget target, boolean admin) {
        if (admin) {
            return true;
        }
        if (target.getKind() == Kind.CONTAINER) {
            return canClearContainer(player, target);
        }
        return canClearDoor(player, target);
    }

    public static int clearLinkedClues(ClearCluesTarget target) {
        Location block = target.getCanonicalLocation();
        int removed = SpawnedClueManager.get().clearLinkedToBlock(block);
        if (target.getKind() == Kind.CONTAINER) {
            removed += clearChestPaperClues(block);
        }
        return removed;
    }

    // Public and unowned containers are anyone's to tidy; otherwise only the owning character.
    private static boolean canClearContainer(Player player, ClearCluesTarget target) {
        if (target.getLockState() == LockState.PUBLIC || target.getOwner() == null) {
            return true;
        }
        return LockAccess.isOwner(player, target.getOwner());
    }

    // The key is the door's credential; clue clearing goes to the owning character or its lock guild.
    private static boolean canClearDoor(Player player, ClearCluesTarget target) {
        LockOwner owner = target.getOwner();
        if (owner == null) {
            return true;
        }
        return LockAccess.isOwner(player, owner) || LockAccess.sharesLockGuild(player, owner);
    }

    private static int clearChestPaperClues(Location canonical) {
        Block block = canonical.getBlock();
        if (!(block.getState() instanceof Container container)) {
            return 0;
        }
        Inventory inv = container.getInventory();
        if (inv instanceof DoubleChestInventory doubleInv) {
            DoubleChest holder = (DoubleChest) doubleInv.getHolder();
            inv = holder.getInventory();
        }
        int removed = 0;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (ClueGiver.isClueItem(inv.getItem(slot))) {
                inv.setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }

    // A double chest is read from both halves: diverged owners act for nobody, and the stricter state applies.
    private static ClearCluesTarget containerTarget(Container container) {
        Inventory inv = container.getInventory();
        if (inv instanceof DoubleChestInventory doubleInv) {
            DoubleChest dc = (DoubleChest) doubleInv.getHolder();
            ContainerData left = containerDataManager.loadContainerData(
                    ((org.bukkit.block.Chest) dc.getLeftSide()).getLocation());
            ContainerData right = containerDataManager.loadContainerData(
                    ((org.bukkit.block.Chest) dc.getRightSide()).getLocation());
            ChestOwners owners = ChestOwners.of(left, right);
            LockState state = left.getLockState().ordinal() < right.getLockState().ordinal()
                    ? left.getLockState() : right.getLockState();
            return new ClearCluesTarget(Kind.CONTAINER, left.getLocation(),
                    owners.diverged() ? LockOwner.unreadable() : owners.owner(), state);
        }
        ContainerData data = containerDataManager.loadContainerData(container.getLocation());
        return new ClearCluesTarget(Kind.CONTAINER, data.getLocation(), data.getOwner(), data.getLockState());
    }

    private static boolean isDoor(Block block) {
        Material type = block.getType();
        if (type == Material.IRON_DOOR || type == Material.IRON_TRAPDOOR) {
            return false;
        }
        return Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type);
    }

    private static Location getDoorCanonicalLocation(Block block) {
        if (Tag.DOORS.isTagged(block.getType())) {
            Bisected bisected = (Bisected) block.getBlockData();
            if (bisected.getHalf() == Bisected.Half.TOP) {
                return block.getRelative(BlockFace.DOWN).getLocation();
            }
        }
        return block.getLocation();
    }
}
