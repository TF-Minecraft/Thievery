package net.tfminecraft.thievery.inspection;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

import net.tfminecraft.rpcharacters.profile.CharacterProfileViewEvent;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class HandsUpManager implements Listener, TabExecutor {
    private static final double MAX_DISTANCE_SQUARED = 16;
    private final Set<UUID> raisedHands = new HashSet<>();
    private final Map<Player, InspectionInventory> viewers = new HashMap<>();
    private HandsUpAnimation animation;

    public void setAnimation(HandsUpAnimation animation) {
        this.animation = animation;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ThieveryTexts.ERROR + "Only players can use this command.");
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(ThieveryTexts.WARN + "Use /handsup to allow or stop read-only inventory inspection.");
            return true;
        }
        if (raisedHands.contains(player.getUniqueId())) {
            clearPlayer(player);
            player.sendMessage(ThieveryTexts.SUCCESS + "You lowered your hands. Inventory inspection is off.");
        } else if (!player.isDead()) {
            if (animation == null || !animation.start(player)) {
                player.sendMessage(ThieveryTexts.ERROR
                        + "The hands-up pose could not start. Stand on the ground and try again."
                        + " Inventory inspection is still off.");
                return true;
            }
            raisedHands.add(player.getUniqueId());
            player.sendMessage(ThieveryTexts.SUCCESS
                    + "Hands up! Players can right-click you to inspect your inventory, but cannot take items."
                    + " Use /handsup again or sneak to stop.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)
                || !canInspect(event.getPlayer(), target)) {
            return;
        }
        // Claim both hand events before robbery/pickpocket targeting; open only once.
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            Player viewer = event.getPlayer();
            InspectionInventory inspection = new InspectionInventory(target);
            viewer.openInventory(inspection.getInventory());
            if (viewer.getOpenInventory().getTopInventory() == inspection.getInventory()) {
                viewers.put(viewer, inspection);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onProfile(CharacterProfileViewEvent event) {
        if (!event.isFromCommand() && event.getTarget() != null
                && raisedHands.contains(event.getTarget().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // Cancel the entire view early, including the viewer's own slots and outside clicks.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof InspectionInventory) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof InspectionInventory) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof InspectionInventory inspection) {
            viewers.remove(event.getPlayer(), inspection);
        }
    }

    public void refresh() {
        for (var entry : Map.copyOf(viewers).entrySet()) {
            Player viewer = entry.getKey();
            InspectionInventory inspection = entry.getValue();
            Player target = Bukkit.getPlayer(inspection.targetId);
            if (target == null || !canInspect(viewer, target)) {
                close(viewer);
            } else {
                inspection.refresh(target);
            }
        }
    }

    private boolean canInspect(Player viewer, Player target) {
        return raisedHands.contains(target.getUniqueId()) && !viewer.equals(target)
                && !target.isDead() && !viewer.isDead() && viewer.canSee(target)
                && viewer.getWorld().equals(target.getWorld())
                && viewer.getLocation().distanceSquared(target.getLocation()) <= MAX_DISTANCE_SQUARED;
    }

    public void clearPlayer(Player player) {
        boolean wasRaised = raisedHands.contains(player.getUniqueId());
        animationEnded(player);
        if (wasRaised) {
            animation.stop(player);
        }
    }

    public void animationEnded(Player player) {
        UUID id = player.getUniqueId();
        raisedHands.remove(id);
        for (var entry : Map.copyOf(viewers).entrySet()) {
            if (entry.getValue().targetId.equals(id) || entry.getKey().equals(player)) {
                close(entry.getKey());
            }
        }
    }

    private void close(Player viewer) {
        viewers.remove(viewer);
        viewer.closeInventory();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        clearPlayer(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        clearPlayer(event.getPlayer());
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (raisedHands.contains(player.getUniqueId())) {
                clearPlayer(player);
            }
        }
        raisedHands.clear();
        refresh();
    }
}
