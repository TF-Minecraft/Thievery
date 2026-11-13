package net.tfminecraft.thievery.timing;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.thievery.cache.Parameters;

/**
 * Samples every online player's ping once a second, so a click can be judged on their usual ping rather than
 * on the ping of the moment. A player's samples are dropped when they leave.
 */
public final class PingSampler implements Listener {

    static final long INTERVAL_TICKS = 20L;

    private static final Map<UUID, PingWindow> WINDOWS = new HashMap<>();

    private BukkitTask task;

    public void start(Plugin plugin) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, PingSampler::sampleOnline, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        WINDOWS.clear();
    }

    static void sampleOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sample(player.getUniqueId(), player.getPing());
        }
    }

    static void sample(UUID playerId, int ping) {
        int size = Math.max(1, Parameters.lagPingWindowSeconds);
        PingWindow window = WINDOWS.get(playerId);
        if (window == null || window.capacity() != size) {
            // A new window size after a reload starts the history afresh.
            window = new PingWindow(size);
            WINDOWS.put(playerId, window);
        }
        window.add(ping);
    }

    /** The lower of the player's median ping over the window and {@code current}: a spike never raises it. */
    static int estimate(UUID playerId, int current) {
        PingWindow window = WINDOWS.get(playerId);
        int median = window == null ? current : window.median();
        return Math.min(median, current);
    }

    static boolean tracks(UUID playerId) {
        return WINDOWS.containsKey(playerId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        WINDOWS.remove(event.getPlayer().getUniqueId());
    }
}
