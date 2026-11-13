package net.tfminecraft.thievery.timing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.tfminecraft.thievery.cache.Parameters;

/**
 * Ping-fair timing for the lockpick minigames. A thief sees each frame half their ping after the server sends it,
 * and their click reaches the server half their ping after they make it, so the click is judged against the frame
 * sent their whole ping before it arrived: the one they were looking at. At 0 ping that is the newest frame, as
 * before. The rewind is the player's usual ping (see {@link PingSampler}), capped at
 * {@code lockpicking.lag-compensation.max-rewind-ms}.
 *
 * <p>Residual risk: a modified client can hold its ping high (by answering keep-alives late) for long enough to
 * move the median, and then click up to the cap after seeing a good frame. Clicks judged with a usual ping near
 * the cap, or far above the player's own lowest sample, are flagged in the debug log and warned about in the
 * server log at most once per player every ten minutes, so staff can review them.
 */
public final class LagCompensation {

    /** Milliseconds from a monotonic clock; tests replace it. */
    static LongSupplier clock = () -> System.nanoTime() / 1_000_000L;

    static final long WARN_INTERVAL_MS = 10L * 60L * 1000L;
    /** A usual ping at or above this share of the cap is flagged. */
    static final double NEAR_CAP_SHARE = 0.9;
    /** A usual ping more than this many times the lowest sample, and this much above it, is flagged. */
    static final int INFLATION_FACTOR = 2;
    static final int INFLATION_MARGIN_MS = 100;

    private static PingSampler sampler = new PingSampler();
    private static Path logFile;
    private static Logger logger = Logger.getLogger("Thievery");
    private static boolean logFailed;

    private LagCompensation() {}

    public static long now() {
        return clock.getAsLong();
    }

    /**
     * Uses {@code pingSampler}'s samples, writes the debug log to {@code file} (normally
     * {@code plugins/Thievery/logs/minigame-timing.log}; null for none) and warns through {@code log}. The log's
     * folder is made here, once.
     */
    public static void configure(PingSampler pingSampler, Path file, Logger log) {
        sampler = pingSampler;
        logFile = file;
        logger = log;
        logFailed = false;
        if (file != null) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
            } catch (IOException e) {
                logFailure(e);
            }
        }
    }

    /** How far back to judge a click, and the numbers that led to it. */
    public record Rewind(int ping, int estimate, long millis, boolean capped) {}

    /** Cheap enough to call every tick: one ping read and one median of at most a few dozen samples. */
    public static Rewind rewind(Player player) {
        int ping = Math.max(0, player.getPing());
        if (!Parameters.lagCompensationEnabled) {
            return new Rewind(ping, ping, 0L, false);
        }
        int estimate = sampler.estimate(player.getUniqueId(), ping);
        long wanted = (long) estimate + Parameters.lagClientInputDelayMs;
        long cap = Parameters.lagMaxRewindMs;
        return new Rewind(ping, estimate, Math.min(cap, wanted), wanted > cap);
    }

    /**
     * Records one judged click. A click judged with a suspicious usual ping (see {@link #flagged}) is warned about
     * in the server log at most once per player every ten minutes. With {@code debug-log} on, appends one line:
     * the player, the game, their ping now, the estimate used, their lowest sample, the rewind, how many frames
     * (ticks, for the ring) back it reached, the result, the result the newest frame would have given (worked out
     * only then), whether the cap cut the rewind short and the flag.
     */
    public static void judged(Player player, String game, Rewind rewind, double framesBack, String result,
            Supplier<String> unrewound) {
        int lowest = sampler.lowest(player.getUniqueId());
        boolean flagged = flagged(rewind, lowest);
        if (flagged && sampler.mayWarn(player.getUniqueId(), now(), WARN_INTERVAL_MS)) {
            logger.warning(String.format(Locale.ROOT, "Lockpick timing: %s was judged on a usual ping of %d ms"
                    + " (lowest sample %d ms, rewind %d ms). Possible ping inflation; turn on"
                    + " lockpicking.lag-compensation.debug-log to review.", player.getName(), rewind.estimate(),
                    lowest, rewind.millis()));
        }
        if (!Parameters.lagDebugLog || logFile == null) {
            return;
        }
        String line = String.format(Locale.ROOT,
                "%s player=%s game=%s ping=%d estimate=%d lowest=%d rewind=%d framesBack=%.1f result=%s"
                        + " unrewound=%s capped=%b flagged=%b",
                Instant.now(), player.getName(), game, rewind.ping(), rewind.estimate(), lowest, rewind.millis(),
                framesBack, result, unrewound.get(), rewind.capped(), flagged);
        try {
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logFailure(e);
        }
    }

    /**
     * Whether a click was rewound on a usual ping worth a staff look: at or near the cap, or more than twice the
     * player's lowest sample in the window and over 100 ms above it. A click that was not rewound is never
     * flagged; there was nothing to gain.
     */
    static boolean flagged(Rewind rewind, int lowest) {
        if (rewind.millis() == 0) {
            return false;
        }
        if (rewind.estimate() >= Parameters.lagMaxRewindMs * NEAR_CAP_SHARE) {
            return true;
        }
        return lowest >= 0 && rewind.estimate() > INFLATION_FACTOR * lowest
                && rewind.estimate() > lowest + INFLATION_MARGIN_MS;
    }

    /** Warns once about a log that cannot be written, until it is configured again. */
    private static void logFailure(IOException e) {
        if (!logFailed) {
            logFailed = true;
            logger.warning("Could not write the minigame timing log: " + e.getMessage());
        }
    }
}
