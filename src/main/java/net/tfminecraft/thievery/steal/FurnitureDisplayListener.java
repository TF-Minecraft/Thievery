package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.events.FurnitureBreakEvent;
import net.tfminecraft.interactiblefurniture.events.FurnitureInteractEvent;
import net.tfminecraft.interactiblefurniture.events.FurniturePlaceEvent;
import net.tfminecraft.interactiblefurniture.events.FurnitureSlotItemAddEvent;
import net.tfminecraft.interactiblefurniture.events.FurnitureSlotItemTakeEvent;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.interactiblefurniture.furniture.PlacedSlot;
import net.tfminecraft.interactiblefurniture.furniture.SlotDefinition;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.door.LockWriters;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.utils.ThieveryTexts;
import net.tfminecraft.thievery.utils.ToolResolver;

public class FurnitureDisplayListener implements Listener {

    private final DisplayStealManager displayStealManager;

    public FurnitureDisplayListener(DisplayStealManager displayStealManager) {
        this.displayStealManager = displayStealManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        LockGuard.failClosed(event, "a furniture placement", () -> handlePlace(event));
    }

    private void handlePlace(FurniturePlaceEvent event) {
        Furniture furniture = event.getFurniture();
        if (!FurnitureLockHelper.isLockable(furniture) || !event.hasPlayer()) {
            return;
        }
        Player player = event.getPlayer();
        if (LockGuard.guarded()) {
            event.setCancelled(true);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + LockGuard.STARTING));
            return;
        }
        LockOwner owner = LockWriters.placementOwner(player);
        if (FurnitureLockHelper.getOwner(furniture) != null || owner == null) {
            return;
        }
        FurnitureLockHelper.setOwner(furniture, owner);
        FurnitureLockHelper.setLockState(furniture, LockWriters.initialState(owner));
        DisplayStealManager.notifyLockStateChange(player, LockWriters.initialState(owner));
    }

    // Upgrades a bare owner on first interaction, then reads the lock afresh.
    private static FurnitureLockHelper.FurnitureLock lockOf(Furniture furniture) {
        FurnitureLockHelper.upgrade(furniture);
        return FurnitureLockHelper.read(furniture);
    }

    private static boolean deniedDuringGuard(Player player, FurnitureLockHelper.FurnitureLock lock) {
        return LockGuard.guarded() && lock.owner() != null && lock.state() != LockState.PUBLIC
                && !player.hasPermission(LockWriters.STAFF);
    }

    // CoreProtect drops interactions on air, so prefer the block the furniture was placed on.
    static Location supportLocation(Furniture furniture) {
        return furniture.getOriginBlockLocation().orElseGet(
                () -> furniture.getLoc().getBlock().getRelative(BlockFace.DOWN).getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        LockGuard.failClosed(event, "a furniture break", () -> handleBreak(event));
    }

    private void handleBreak(FurnitureBreakEvent event) {
        Furniture furniture = event.getFurniture();
        if (!FurnitureLockHelper.isLockable(furniture) || !event.hasPlayer()) {
            return;
        }
        Player player = event.getPlayer();
        if (LockGuard.guarded() && !player.isSneaking()) {
            event.setCancelled(true);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + LockGuard.STARTING));
            return;
        }
        FurnitureLockHelper.FurnitureLock lock = lockOf(furniture);
        if (player.isSneaking()) {
            event.setCancelled(true);
            DisplayStealManager.applyToggle(player, () -> supportLocation(furniture), lock.owner(), owner -> {
                FurnitureLockHelper.setOwner(furniture, owner);
                FurnitureLockHelper.setLockState(furniture, LockState.DEFAULT);
            }, () -> FurnitureLockHelper.rotateLockState(furniture));
            return;
        }
        if (!DisplayStealManager.canUse(player, lock.owner(), lock.state(), true)) {
            event.setCancelled(true);
            DisplayStealManager.denyAccess(player, lock.state(), false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        LockGuard.failClosed(event, "a furniture use", () -> handleInteract(event));
    }

    private void handleInteract(FurnitureInteractEvent event) {
        Furniture furniture = event.getFurniture();
        if (!FurnitureLockHelper.isLockable(furniture)) {
            return;
        }
        Player player = event.getPlayer();
        FurnitureLockHelper.FurnitureLock lock = lockOf(furniture);
        if (ToolResolver.isLockpick(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            Entity entity = Bukkit.getEntity(furniture.getEntityId());
            displayStealManager.handleLockpick(player, entity, lock.owner(), lock.state(), slotsFor(furniture, player));
            return;
        }
        checkUse(event, player, lock, false, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSlotTake(FurnitureSlotItemTakeEvent event) {
        if (DisplayLoot.isDumping()) {
            return;
        }
        LockGuard.failClosed(event, "a furniture slot take", () -> handleSlot(event, event.getFurniture(),
                event.getPlayer(), true));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSlotAdd(FurnitureSlotItemAddEvent event) {
        LockGuard.failClosed(event, "a furniture slot add", () -> handleSlot(event, event.getFurniture(),
                event.getPlayer(), false));
    }

    private static void handleSlot(Cancellable event, Furniture furniture, Player player, boolean pickable) {
        if (!FurnitureLockHelper.isLockable(furniture)) {
            return;
        }
        checkUse(event, player, lockOf(furniture), true, pickable);
    }

    private static void checkUse(Cancellable event, Player player, FurnitureLockHelper.FurnitureLock lock,
            boolean notifyBypass, boolean pickable) {
        if (deniedDuringGuard(player, lock)) {
            event.setCancelled(true);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + LockGuard.STARTING));
            return;
        }
        if (!DisplayStealManager.canUse(player, lock.owner(), lock.state(), notifyBypass)) {
            event.setCancelled(true);
            DisplayStealManager.denyAccess(player, lock.state(), pickable);
        }
    }

    private static List<DisplayLoot.DisplaySlot> slotsFor(Furniture furniture, Player player) {
        List<DisplayLoot.DisplaySlot> slots = new ArrayList<>();
        for (PlacedSlot placed : furniture.getActiveSlots().values()) {
            slots.add(new FurnitureSlot(furniture, placed, player));
        }
        return slots;
    }

    private static final class FurnitureSlot implements DisplayLoot.DisplaySlot {
        private final Furniture furniture;
        private final PlacedSlot placed;
        private final Player player;

        FurnitureSlot(Furniture furniture, PlacedSlot placed, Player player) {
            this.furniture = furniture;
            this.placed = placed;
            this.player = player;
        }

        @Override
        public ItemStack get() {
            return placed.getCurrentItem();
        }

        @Override
        public boolean take(ItemStack taken) {
            ItemStack current = placed.getCurrentItem();
            if (current == null || current.getType().isAir()) {
                return false;
            }
            SlotDefinition definition = placed.getDefinition();
            FurnitureSlotItemTakeEvent takeEvent = new FurnitureSlotItemTakeEvent(
                    player, furniture, definition, taken.clone());
            Bukkit.getPluginManager().callEvent(takeEvent);
            if (takeEvent.isCancelled()) {
                return false;
            }
            if (current.getAmount() <= taken.getAmount()) {
                furniture.removeActiveSlot(placed.getId());
            } else {
                ItemStack remaining = current.clone();
                remaining.setAmount(current.getAmount() - taken.getAmount());
                placed.setCurrentItem(remaining);
            }
            InteractibleFurniture.getInstance().getFurnitureManager().persistFurniture(furniture);
            return true;
        }
    }
}
