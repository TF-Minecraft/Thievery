package net.tfminecraft.thievery.clue;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;

import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.thievery.door.ContainerData;
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
        private final UUID ownerUuid;
        private final LockState lockState;

        public ClearCluesTarget(Kind kind, Location canonicalLocation, UUID ownerUuid, LockState lockState) {
            this.kind = kind;
            this.canonicalLocation = canonicalLocation;
            this.ownerUuid = ownerUuid;
            this.lockState = lockState;
        }

        public Kind getKind() {
            return kind;
        }

        public Location getCanonicalLocation() {
            return canonicalLocation;
        }

        public UUID getOwnerUuid() {
            return ownerUuid;
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
            UUID owner = doorData != null ? doorData.getOwnerUUID() : null;
            return Optional.of(new ClearCluesTarget(Kind.DOOR, canonical, owner, null));
        }
        if (block.getState() instanceof Container) {
            ContainerData data = loadContainerDataForBlock(block);
            return Optional.of(new ClearCluesTarget(Kind.CONTAINER, data.getLocation(),
                    data.getOwner(), data.getLockState()));
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

    private static boolean canClearContainer(Player player, ClearCluesTarget target) {
        LockState lockState = target.getLockState();
        if (lockState == LockState.PUBLIC || target.getOwnerUuid() == null) {
            return true;
        }
        ContainerData data = containerDataManager.loadContainerData(target.getCanonicalLocation());
        if (lockState == LockState.PRIVATE) {
            return data.owns(player);
        }
        return data.canAccess(player);
    }

    private static boolean canClearDoor(Player player, ClearCluesTarget target) {
        UUID ownerUuid = target.getOwnerUuid();
        if (ownerUuid == null) {
            return true;
        }
        if (ownerUuid.equals(player.getUniqueId())) {
            return true;
        }
        return sharesGuildWithOwner(player, ownerUuid);
    }

    private static boolean sharesGuildWithOwner(Player player, UUID ownerUuid) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerUuid);
        if (owner.getName() == null || player.getName() == null) {
            return false;
        }
        Guild ownerGuild = FactionManager.getGuildByMember(owner.getName());
        Guild playerGuild = FactionManager.getGuildByMember(player.getName());
        if (ownerGuild == null || playerGuild == null) {
            return false;
        }
        return ownerGuild.getId().equals(playerGuild.getId());
    }

    private static int clearChestPaperClues(Location canonical) {
        Block block = canonical.getBlock();
        if (!(block.getState() instanceof Container container)) {
            return 0;
        }
        Inventory inv = container.getInventory();
        if (inv instanceof DoubleChestInventory doubleInv) {
            DoubleChest holder = (DoubleChest) doubleInv.getHolder();
            if (holder != null) {
                inv = holder.getInventory();
            }
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

    private static ContainerData loadContainerDataForBlock(Block block) {
        if (!(block.getState() instanceof Container container)) {
            return containerDataManager.loadContainerData(block.getLocation());
        }
        Inventory inv = container.getInventory();
        if (inv instanceof DoubleChestInventory doubleInv) {
            DoubleChest dc = (DoubleChest) doubleInv.getHolder();
            if (dc != null) {
                Location leftLoc = ((org.bukkit.block.Chest) dc.getLeftSide()).getLocation();
                ContainerData left = containerDataManager.loadContainerData(leftLoc);
                if (left.getOwner() != null) {
                    return left;
                }
                return containerDataManager.loadContainerData(
                        ((org.bukkit.block.Chest) dc.getRightSide()).getLocation());
            }
        }
        return containerDataManager.loadContainerData(block.getLocation());
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
