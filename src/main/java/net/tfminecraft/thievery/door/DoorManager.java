package net.tfminecraft.thievery.door;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.DoorData;
import net.tfminecraft.thievery.player.RiskSource;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.clue.ClueDropper;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.door.DoorLockpick;
import net.tfminecraft.thievery.key.KeychainHandler;
import net.tfminecraft.thievery.key.KeychainHandler.DoorKeyMatch;
import net.tfminecraft.thievery.key.KeychainHandler.DoorKeyPurpose;
import net.tfminecraft.thievery.key.KeyCopyHandler;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.ToolResolver;
import net.tfminecraft.thievery.utils.GuildChecker;
import net.tfminecraft.thievery.utils.Keys;

public class DoorManager implements Listener {

    private final DoorDataManager doorDataManager = new DoorDataManager();
    private final LockPickManager lockPickManager;
    private final Map<UUID, Long> lastInteract = new HashMap<>();

    public DoorManager(LockPickManager lockPickManager) {
        this.lockPickManager = lockPickManager;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        Block block = event.getClickedBlock();
        if (!isDoor(block)) return;

        Player player = event.getPlayer();
        Location canonical = getCanonicalLocation(block);
        ItemStack heldItem = player.getInventory().getItemInMainHand();

        if (ToolResolver.isDebugTool(heldItem)) {
            event.setCancelled(true);
            showDebugLockInfo(player, canonical);
            return;
        }

        boolean holdingLockingKey = ToolResolver.isLockingKey(heldItem);

        DoorData data = doorDataManager.loadDoorData(canonical);

        if (player.isSneaking() && holdingLockingKey) {
            long now = System.currentTimeMillis();
            if (now - lastInteract.getOrDefault(player.getUniqueId(), 0L) < 200) return;
            lastInteract.put(player.getUniqueId(), now);
            event.setCancelled(true);
            if (data == null) {
                String uuid = getOrCreateKeyUUID(heldItem);
                if (uuid == null) return;
                double strength = ToolResolver.getKeyStrength(heldItem);
                doorDataManager.saveDoorData(new DoorData(canonical, uuid, strength, player.getUniqueId()));
                player.sendTitle(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "Door locked."), "", 5, 30, 10);
                playLockToggleSound(canonical);
            } else {
                if (KeychainHandler.matchesDoor(heldItem, data.getKey(), DoorKeyPurpose.UNLOCK_OR_BREAK)) {
                    doorDataManager.deleteDoorData(canonical);
                    player.sendTitle(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "Door unlocked."), "", 5, 30, 10);
                    playLockToggleSound(canonical);
                } else {
                    player.sendTitle(ThieveryTexts.msg(ThieveryTexts.ERROR + "This key does not fit this lock."), "", 5, 30, 10);
                }
            }
            return;
        }

        if (player.isSneaking() && KeyCopyHandler.isPaperCopy(heldItem)) {
            event.setCancelled(true);
            player.sendTitle(ThieveryTexts.msg(ThieveryTexts.ERROR + "Paper keys can only open doors."), "", 5, 30, 10);
            return;
        }

        // Lockpick handling — must come before the generic locked-door block
        if (ToolResolver.isLockpick(heldItem) && data != null) {
            event.setCancelled(true);
            UUID uuid = player.getUniqueId();

            // Cannot lockpick a door that is already open
            if (isDoorOpen(block)) {
                return;
            }

            if (lockPickManager.isInSession(uuid)) {
                if (DoorLockpick.doorTargetId(canonical).equals(lockPickManager.getSessionTargetId(uuid))) {
                    handleSelectResult(player, lockPickManager.handleSelect(player), canonical);
                } else {
                    // Different target - cancel old session (penalize) and start fresh
                    lockPickManager.cancelSession(uuid, true);
                    startLockpicking(player, canonical, data);
                }
            } else {
                startLockpicking(player, canonical, data);
            }
            return;
        }

        if (data != null) {
            // Always allow closing an open door, even without the key
            if (isDoorOpen(block)) return;

            if (isUnlockWindowActive(data)) return;

            DoorKeyMatch match = KeychainHandler.resolveDoorMatch(heldItem, data.getKey(), DoorKeyPurpose.OPEN);
            if (match == null) {
                event.setCancelled(true);
                player.sendTitle(ThieveryTexts.msg(ThieveryTexts.ERROR + "This door is locked."), "", 5, 30, 10);
            } else if (match.isPaper()) {
                consumePaperKey(player, heldItem, data.getKey());
            }
        }
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();

        // Resolve which locked door (if any) this break would affect
        Location lockedDoor = getLockedDoorForBreak(block);
        if (lockedDoor == null) return;

        DoorData data = doorDataManager.loadDoorData(lockedDoor);
        if (data == null) {
            if (isDoor(block)) {
                doorDataManager.deleteDoorData(lockedDoor);
            }
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE) {
            scheduleLockRemovalAfterBreak(event, lockedDoor, block.getType());
            return;
        }

        if (KeychainHandler.matchesDoor(player.getInventory().getItemInMainHand(), data.getKey(),
                DoorKeyPurpose.UNLOCK_OR_BREAK)) {
            // Correct key held — allow break and always clean up door data
            doorDataManager.deleteDoorData(lockedDoor);
        } else {
            event.setCancelled(true);
            player.sendTitle(ThieveryTexts.msg(ThieveryTexts.ERROR + "This door is locked."), "", 5, 30, 10);
        }
    }

    /**
     * Returns the canonical (bottom-half) location of a locked door that would
     * be affected by breaking the given block, or null if no locked door is involved.
     * Covers: door block (top or bottom half), and the support block beneath the door.
     */
    private Location getLockedDoorForBreak(Block block) {
        // Case 1: the block itself is a door piece
        if (isDoor(block)) {
            return getCanonicalLocation(block);
        }

        // Case 2: the block above this one is the bottom half of a door (support block)
        Block above = block.getRelative(BlockFace.UP);
        if (Tag.DOORS.isTagged(above.getType()) && above.getType() != Material.IRON_DOOR) {
            Bisected bisected = (Bisected) above.getBlockData();
            if (bisected.getHalf() == Bisected.Half.BOTTOM) {
                return above.getLocation();
            }
        }

        return null;
    }

    // --- Helpers ---

    private boolean isDoorOpen(Block block) {
        if (block.getBlockData() instanceof Openable openable) {
            return openable.isOpen();
        }
        return false;
    }

    private boolean isDoor(Block block) {
        Material type = block.getType();
        if (type == Material.IRON_DOOR || type == Material.IRON_TRAPDOOR) return false;
        return Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type);
    }

    private Location getCanonicalLocation(Block block) {
        if (Tag.DOORS.isTagged(block.getType())) {
            Bisected bisected = (Bisected) block.getBlockData();
            if (bisected.getHalf() == Bisected.Half.TOP) {
                return block.getRelative(BlockFace.DOWN).getLocation();
            }
        }
        return block.getLocation();
    }

    private String getKeyUUID(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (!meta.getPersistentDataContainer().has(Keys.keyUUIDKey, PersistentDataType.STRING)) return null;
        return meta.getPersistentDataContainer().get(Keys.keyUUIDKey, PersistentDataType.STRING);
    }

    private String getOrCreateKeyUUID(ItemStack item) {
        String existing = getKeyUUID(item);
        if (existing != null) return existing;
        if (!ToolResolver.isMasterKey(item)) {
            return null;
        }
        String newUUID = UUID.randomUUID().toString();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.keyUUIDKey, PersistentDataType.STRING, newUUID);
        item.setItemMeta(meta);
        return newUUID;
    }

    private void consumePaperKey(Player player, ItemStack heldItem, String doorKeyUuid) {
        if (KeyCopyHandler.isPaperCopy(heldItem)) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                hand.setAmount(hand.getAmount() - 1);
            }
            return;
        }
        if (KeychainHandler.isKeychain(heldItem)) {
            ItemStack updated = KeychainHandler.consumePaperKeyForDoor(heldItem, doorKeyUuid);
            player.getInventory().setItemInMainHand(updated);
        }
    }

    // --- Lockpick helpers ---

    private void startLockpicking(Player player, Location canonical, DoorData data) {
        if (!DoorLockpick.isWithinDoorRange(player, canonical, Parameters.doorMaxDistance)) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You are too far from the door."));
            return;
        }
        if (!ClueChecker.hasEnoughClues(player)) {
            ClueChecker.sendInsufficientCluesMessage(player);
            return;
        }
        if (lockPickManager.isOnCooldown(player.getUniqueId(), canonical)) {
            long seconds = lockPickManager.getCooldownRemainingSeconds(player.getUniqueId(), canonical);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR
                    + "You must wait " + seconds + "s before lockpicking this door again."));
            return;
        }
        GuildChecker.LockpickAccessResult access = GuildChecker.checkLockpickAccess(data.getOwnerUUID());
        if (access.type == GuildChecker.LockpickAccessResult.Type.DENY) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + access.message));
            return;
        }
        if (access.type == GuildChecker.LockpickAccessResult.Type.WARN) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + access.message));
        }
        double debuffFactor = lockPickManager.getDebuffFactor(player.getUniqueId(), canonical);
        if (debuffFactor > 0) {
            int penalty = (int) Math.round(debuffFactor * 100);
            long seconds = lockPickManager.getCooldownRemainingSeconds(player.getUniqueId(), canonical);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Lockpicking with " + penalty + "% penalty (" + seconds + "s)"));
        }
        double lockpickStrength = ToolResolver.getLockpickStrength(player.getInventory().getItemInMainHand());
        double requiredStrength = data.getStrength() * Parameters.lockpickMinLockStrengthRatio;
        if (lockpickStrength < requiredStrength) {
            int requiredPercent = (int) Math.round(Parameters.lockpickMinLockStrengthRatio * 100);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your lockpick is too weak for this lock."
                    + " You need a pick at least " + requiredPercent + "% as strong as the lock."));
            return;
        }
        double effectiveStrength = data.getStrength() * (1.0 - Math.min(1.0, lockpickStrength) * Parameters.lockpickMaxReduction);
        int dexterity = RiskCalculator.getDexterity(player);

        net.tfminecraft.thievery.player.PlayerData thiefData = Thievery.getPlayerManager().get(player.getUniqueId());
        thiefData.addRiskGain(dexterity, lockpickStrength, RiskSource.DOOR);
        Database.savePlayerData(thiefData);

        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Walk away from the door to cancel."));
        lockPickManager.startDoorSession(player, canonical, effectiveStrength, dexterity, lockpickStrength);
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void handleSelectResult(Player player, LockPickManager.SelectResult result, Location canonical) {
        int dexterity = RiskCalculator.getDexterity(player);
        double lockpickStrength = ToolResolver.getLockpickStrength(player.getInventory().getItemInMainHand());
        DoorData doorData = doorDataManager.loadDoorData(canonical);
        UUID ownerUUID = doorData != null ? doorData.getOwnerUUID() : null;
        switch (result) {
            case SUCCESS -> {
                openDoor(canonical);
                if (doorData != null) {
                    doorData.setUnlockExpiryMs(System.currentTimeMillis() + Parameters.doorUnlockWindowMs);
                    doorDataManager.saveDoorData(doorData);
                }
                player.sendTitle(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "Picked!"), "", 5, 40, 10);
                canonical.getWorld().playSound(canonical, Sound.BLOCK_WOODEN_DOOR_OPEN, 0.15f, 1.2f);
                ClueDropper.tryDropDoorClue(player, canonical, ownerUUID, dexterity, lockpickStrength);
            }
            case FAIL -> {
                player.sendTitle(ThieveryTexts.msg(ThieveryTexts.ERROR + "Failed!"), "", 5, 40, 10);
                canonical.getWorld().playSound(canonical, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 4.5f, 0.8f);
                ClueDropper.tryDropDoorClue(player, canonical, ownerUUID, dexterity, lockpickStrength);
            }
            case BREAK -> {
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getAmount() > 1) {
                    held.setAmount(held.getAmount() - 1);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
                player.sendTitle(ThieveryTexts.msg(ThieveryTexts.ERROR + "Lockpick broke!"), "", 5, 40, 10);
                canonical.getWorld().playSound(canonical, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 4.5f, 0.8f);
                canonical.getWorld().playSound(canonical, Sound.ENTITY_ITEM_BREAK, 4.5f, 1f);
                ClueDropper.tryDropDoorClue(player, canonical, ownerUUID, dexterity, lockpickStrength);
            }
            default -> {}
        }
    }

    private void playLockToggleSound(Location canonical) {
        canonical.getWorld().playSound(canonical, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 1.0f);
    }

    private void showDebugLockInfo(Player player, Location canonical) {
        DoorData data = doorDataManager.loadDoorData(canonical);
        if (data == null || data.getOwnerUUID() == null) {
            player.sendMessage(ThieveryTexts.MUTED + "This door is not locked.");
            return;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(data.getOwnerUUID());
        String name = owner.getName() != null ? owner.getName() : data.getOwnerUUID().toString();
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.INFO + "Door was locked by " + ThieveryTexts.INFO + name));
    }

    private void scheduleLockRemovalAfterBreak(BlockBreakEvent event, Location lockedDoor, Material brokenType) {
        Bukkit.getScheduler().runTaskLater(Thievery.getInstance(), () -> {
            if (lockedDoor.getBlock().getType() == brokenType) {
                return;
            }
            doorDataManager.deleteDoorData(lockedDoor);
        }, 5L);
    }

    private boolean isUnlockWindowActive(DoorData data) {
        Long expiry = data.getUnlockExpiryMs();
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            data.setUnlockExpiryMs(null);
            doorDataManager.saveDoorData(data);
            return false;
        }
        return true;
    }

    private void openDoor(Location canonical) {
        Block bottom = canonical.getBlock();
        if (bottom.getBlockData() instanceof Openable openable) {
            openable.setOpen(true);
            bottom.setBlockData(openable);
        }
        // Sync top half for full doors
        if (Tag.DOORS.isTagged(bottom.getType())) {
            Block top = bottom.getRelative(BlockFace.UP);
            if (top.getBlockData() instanceof Openable topOpenable) {
                topOpenable.setOpen(true);
                top.setBlockData(topOpenable);
            }
        }
    }

}

