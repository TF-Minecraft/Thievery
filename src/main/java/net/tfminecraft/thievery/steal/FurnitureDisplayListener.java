package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
import net.tfminecraft.thievery.utils.ToolResolver;

public class FurnitureDisplayListener implements Listener {

    private final DisplayStealManager displayStealManager;

    public FurnitureDisplayListener(DisplayStealManager displayStealManager) {
        this.displayStealManager = displayStealManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurniturePlace(FurniturePlaceEvent event) {
        Furniture furniture = event.getFurniture();
        if (!FurnitureLockHelper.isLockable(furniture) || !event.hasPlayer()) {
            return;
        }
        if (FurnitureLockHelper.getOwner(furniture) != null) {
            return;
        }
        Player player = event.getPlayer();
        FurnitureLockHelper.setOwner(furniture, player.getUniqueId());
        FurnitureLockHelper.setLockState(furniture, LockState.DEFAULT);
        DisplayStealManager.notifyLockStateChange(player, LockState.DEFAULT);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        Furniture furniture = event.getFurniture();
        if (!FurnitureLockHelper.isLockable(furniture) || !event.hasPlayer()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            event.setCancelled(true);
            DisplayStealManager.applyToggle(player, FurnitureLockHelper.getOwner(furniture), owner -> {
                FurnitureLockHelper.setOwner(furniture, owner);
                FurnitureLockHelper.setLockState(furniture, LockState.DEFAULT);
            }, () -> FurnitureLockHelper.rotateLockState(furniture));
            return;
        }
        if (!DisplayStealManager.canUse(player, FurnitureLockHelper.getOwner(furniture),
                FurnitureLockHelper.getLockState(furniture), true)) {
            event.setCancelled(true);
            DisplayStealManager.denyAccess(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        Furniture furniture = event.getFurniture();
        if (!FurnitureLockHelper.isLockable(furniture)) {
            return;
        }
        Player player = event.getPlayer();
        if (ToolResolver.isLockpick(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            Entity entity = Bukkit.getEntity(furniture.getEntityId());
            displayStealManager.handleLockpick(player, entity, FurnitureLockHelper.getOwner(furniture),
                    FurnitureLockHelper.getLockState(furniture), slotsFor(furniture, player));
            return;
        }
        if (!DisplayStealManager.canUse(player, FurnitureLockHelper.getOwner(furniture),
                FurnitureLockHelper.getLockState(furniture), false)) {
            event.setCancelled(true);
            DisplayStealManager.denyAccess(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSlotTake(FurnitureSlotItemTakeEvent event) {
        if (DisplayLoot.isDumping()) {
            return;
        }
        Furniture furniture = event.getFurniture();
        if (!FurnitureLockHelper.isLockable(furniture)) {
            return;
        }
        Player player = event.getPlayer();
        if (!DisplayStealManager.canUse(player, FurnitureLockHelper.getOwner(furniture),
                FurnitureLockHelper.getLockState(furniture), true)) {
            event.setCancelled(true);
            DisplayStealManager.denyAccess(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSlotAdd(FurnitureSlotItemAddEvent event) {
        Furniture furniture = event.getFurniture();
        if (!FurnitureLockHelper.isLockable(furniture)) {
            return;
        }
        Player player = event.getPlayer();
        if (!DisplayStealManager.canUse(player, FurnitureLockHelper.getOwner(furniture),
                FurnitureLockHelper.getLockState(furniture), true)) {
            event.setCancelled(true);
            DisplayStealManager.denyAccess(player);
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
