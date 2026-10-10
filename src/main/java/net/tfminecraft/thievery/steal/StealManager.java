package net.tfminecraft.thievery.steal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import net.tfminecraft.thievery.steal.StealGuiHolder;

public class StealManager implements Listener {

    private static StealManager instance;

    private final Map<UUID, StealReference> sessions = new HashMap<>();

    public StealManager() {
        instance = this;
    }

    public static StealManager getInstance() {
        return instance;
    }

    public void registerSession(StealReference reference) {
        sessions.put(reference.getThiefId(), reference);
    }

    public boolean hasSession(UUID thiefId) {
        return sessions.containsKey(thiefId);
    }

    public StealReference getSession(UUID thiefId) {
        return sessions.get(thiefId);
    }

    public void endSession(UUID thiefId, boolean closeInventory) {
        StealReference reference = sessions.remove(thiefId);
        if (reference == null) {
            return;
        }
        if (closeInventory) {
            Player thief = Bukkit.getPlayer(thiefId);
            if (thief != null && thief.isOnline()) {
                thief.closeInventory();
            }
        }
    }

    public void openSession(Player thief, StealReference reference, Inventory gui) {
        registerSession(reference);
        reference.onOpen(thief, gui);
        thief.openInventory(gui);
    }

    public void tickAll() {
        for (StealReference reference : new ArrayList<>(sessions.values())) {
            Player thief = Bukkit.getPlayer(reference.getThiefId());
            if (thief == null || !thief.isOnline()) {
                continue;
            }
            if (!isViewingStealGui(thief, reference)) {
                continue;
            }
            reference.tick(thief);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        StealGuiHolder holder = getStealGuiHolder(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        StealReference reference = sessions.get(player.getUniqueId());
        if (reference == null || reference.getKind() != holder.getKind()) {
            return;
        }
        reference.handleClick(event, player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        StealGuiHolder holder = getStealGuiHolder(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        StealReference reference = sessions.get(player.getUniqueId());
        if (reference == null || reference.getKind() != holder.getKind()) {
            return;
        }
        sessions.remove(player.getUniqueId());
        reference.onClose(player);
    }

    public static StealGuiHolder getStealGuiHolder(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        // Runs on every inventory click and close, and every tick per session. getHolder() would copy a block
        // inventory (a barrel reloads all of its items) just to answer this.
        InventoryHolder inventoryHolder = inventory.getHolder(false);
        return inventoryHolder instanceof StealGuiHolder stealGuiHolder ? stealGuiHolder : null;
    }

    private static boolean isViewingStealGui(Player player, StealReference reference) {
        StealGuiHolder holder = getStealGuiHolder(player.getOpenInventory().getTopInventory());
        return holder != null && holder.getKind() == reference.getKind()
                && holder.getPlayerId().equals(reference.getThiefId());
    }
}
