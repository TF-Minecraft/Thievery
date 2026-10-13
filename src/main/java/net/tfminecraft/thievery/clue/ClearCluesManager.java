package net.tfminecraft.thievery.clue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.clue.ClearCluesResolver;
import net.tfminecraft.thievery.clue.ClearCluesResolver.ClearCluesTarget;
import net.tfminecraft.thievery.owner.LockGuard;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public class ClearCluesManager implements Listener {

    private static final long AWAIT_TIMEOUT_MS = 30_000L;

    private final Set<UUID> awaitingClear = new HashSet<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new HashMap<>();

    public void startAwaiting(Player player) {
        UUID playerId = player.getUniqueId();
        endAwaiting(playerId);
        awaitingClear.add(playerId);
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Right-click a door or container to clear linked clues."));
        scheduleTimeout(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!awaitingClear.contains(playerId)) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }

        event.setCancelled(true);
        endAwaiting(playerId);
        if (LockGuard.guarded()) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + LockGuard.STARTING));
            return;
        }

        Optional<ClearCluesTarget> target = ClearCluesResolver.resolve(event.getClickedBlock());
        if (target.isEmpty()) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "That block is not a door or container."));
            return;
        }

        boolean admin = player.hasPermission("thievery.admin");
        if (!ClearCluesResolver.canClear(player, target.get(), admin)) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You cannot clear clues from this block."));
            return;
        }

        int removed = ClearCluesResolver.clearLinkedClues(target.get());
        player.sendMessage(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "[Thievery] Removed " + ThieveryTexts.WARN + removed
                + ThieveryTexts.SUCCESS + " clue(s)."));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        endAwaiting(event.getPlayer().getUniqueId());
    }

    private void scheduleTimeout(Player player) {
        UUID playerId = player.getUniqueId();
        BukkitTask task = Thievery.getInstance().getServer().getScheduler().runTaskLater(Thievery.getInstance(), () -> {
            if (!awaitingClear.contains(playerId)) {
                return;
            }
            endAwaiting(playerId);
            Player online = Thievery.getInstance().getServer().getPlayer(playerId);
            if (online != null && online.isOnline()) {
                online.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR
                        + "Clear clues mode expired. Run /thievery clearclues again."));
            }
        }, AWAIT_TIMEOUT_MS / 50L);
        timeoutTasks.put(playerId, task);
    }

    private void endAwaiting(UUID playerId) {
        awaitingClear.remove(playerId);
        BukkitTask task = timeoutTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }
}
