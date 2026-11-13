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
 * on the ping of the moment. Each sampler owns, per player:
 * <ul>
 * <li>the median window, dropped when they leave;</li>
 * <li>a ten-minute baseline of their lowest ping, which outlives the window so a ping held high stands out;</li>
 * <li>when they were last warned about.</li>
 * </ul>
 * The baseline and the warning time survive a relog and are pruned once ten minutes old, so memory stays bounded
 * to the players seen in the last ten minutes. Everything goes when the sampler stops.
 */
public final class PingSampler implements Listener {

    static final long INTERVAL_TICKS = 20L;
    /** How long a baseline and a warning time are remembered, and the warning interval. */
    static final long REMEMBER_MS = PingBaseline.MINUTES * PingBaseline.MINUTE_MS;
    /**
     * Samples after joining that never feed the baseline: Paper can report 0 until the first keep-alive answer.
     * A sample of 0 never feeds it either.
     */
    static final int BASELINE_SKIP_SAMPLES = 5;

    private final Map<UUID, PingWindow> windows = new HashMap<>();
    private final Map<UUID, PingBaseline> baselines = new HashMap<>();
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
        baselines.clear();
        warned.clear();
    }

    void sampleOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sample(player.getUniqueId(), player.getPing());
        }
        prune(LagCompensation.now());
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
        if (window.added() > BASELINE_SKIP_SAMPLES && ping > 0) {
            baselines.computeIfAbsent(playerId, ignored -> new PingBaseline()).add(LagCompensation.now(), ping);
        }
    }

    /** Forgets baselines with nothing from the last ten minutes and warning times ten minutes old. */
    void prune(long now) {
        baselines.values().removeIf(baseline -> baseline.lowest(now) < 0);
        warned.values().removeIf(last -> now - last >= REMEMBER_MS);
    }

    /** The lower of the player's median ping over the window and {@code current}: a spike never raises it. */
    int estimate(UUID playerId, int current) {
        PingWindow window = windows.get(playerId);
        int median = window == null ? current : window.median();
        return Math.min(median, current);
    }

    /** The player's lowest sampled ping in the median window, or -1 without samples. */
    int lowest(UUID playerId) {
        PingWindow window = windows.get(playerId);
        return window == null ? -1 : window.lowest();
    }

    /** The player's lowest ping over the last ten minutes (see {@link PingBaseline}), or -1 without one. */
    int baseline(UUID playerId, long now) {
        PingBaseline baseline = baselines.get(playerId);
        return baseline == null ? -1 : baseline.lowest(now);
    }

    /**
     * Whether an inflated-ping warning for this player may be logged at {@code now}: at most once every ten
     * minutes, relogs included. Saying yes starts the next interval.
     */
    boolean mayWarn(UUID playerId, long now) {
        Long last = warned.get(playerId);
        if (last != null && now - last < REMEMBER_MS) {
            return false;
        }
        warned.put(playerId, now);
        return true;
    }

    boolean hasSamples(UUID playerId) {
        return windows.containsKey(playerId);
    }

    boolean hasBaseline(UUID playerId) {
        return baselines.containsKey(playerId);
    }

    boolean hasWarned(UUID playerId) {
        return warned.containsKey(playerId);
    }

    /** Leaving drops the median window only; the baseline and the warning time expire by time. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        windows.remove(event.getPlayer().getUniqueId());
    }
}
