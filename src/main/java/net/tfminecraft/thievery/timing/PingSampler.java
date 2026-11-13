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
 * on the ping of the moment. Each sampler owns its players' samples and inflated-ping warning times; both are
 * dropped when the player leaves or the sampler stops.
 */
public final class PingSampler implements Listener {

    static final long INTERVAL_TICKS = 20L;

    private final Map<UUID, PingWindow> windows = new HashMap<>();
    private final Map<UUID, Long> warned = new HashMap<>();
    private BukkitTask task;

    public void start(Plugin plugin) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleOnline, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        windows.clear();
        warned.clear();
    }

    void sampleOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sample(player.getUniqueId(), player.getPing());
        }
    }

    void sample(UUID playerId, int ping) {
        int size = Math.max(1, Parameters.lagPingWindowSeconds);
        PingWindow window = windows.get(playerId);
        if (window == null || window.capacity() != size) {
            // A new window size after a reload starts the history afresh.
            window = new PingWindow(size);
            windows.put(playerId, window);
        }
        window.add(ping);
    }

    /** The lower of the player's median ping over the window and {@code current}: a spike never raises it. */
    int estimate(UUID playerId, int current) {
        PingWindow window = windows.get(playerId);
        int median = window == null ? current : window.median();
        return Math.min(median, current);
    }

    /** The player's lowest sampled ping in the window, or -1 before the first sample. */
    int lowest(UUID playerId) {
        PingWindow window = windows.get(playerId);
        return window == null ? -1 : window.lowest();
    }

    /**
     * Whether an inflated-ping warning for this player may be logged at {@code now}: at most once per
     * {@code intervalMs}. Saying yes starts the next interval.
     */
    boolean mayWarn(UUID playerId, long now, long intervalMs) {
        Long last = warned.get(playerId);
        if (last != null && now - last < intervalMs) {
            return false;
        }
        warned.put(playerId, now);
        return true;
    }

    boolean tracks(UUID playerId) {
        return windows.containsKey(playerId) || warned.containsKey(playerId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        windows.remove(playerId);
        warned.remove(playerId);
    }
}
