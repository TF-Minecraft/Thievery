package net.tfminecraft.thievery.timing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.tfminecraft.thievery.cache.Parameters;

/**
 * Ping-fair timing for the lockpick minigames. A thief sees each frame half their ping after the server sends it,
 * and their click reaches the server half their ping after they make it, so the click is judged against the frame
 * sent their whole ping before it arrived: the one they were looking at. At 0 ping that is the newest frame, as
 * before. The rewind is the player's usual ping (see {@link PingSampler}), capped at
 * {@code lockpicking.lag-compensation.max-rewind-ms}.
 */
public final class LagCompensation {

    /** Milliseconds from a monotonic clock; tests replace it. */
    static LongSupplier clock = () -> System.nanoTime() / 1_000_000L;

    private static Path logFile;
    private static Logger logger;

    private LagCompensation() {}

    public static long now() {
        return clock.getAsLong();
    }

    /** How far back to judge a click, and the numbers that led to it. */
    public record Rewind(int ping, int estimate, long millis, boolean capped) {}

    public static Rewind rewind(Player player) {
        int ping = Math.max(0, player.getPing());
        if (!Parameters.lagCompensationEnabled) {
            return new Rewind(ping, ping, 0L, false);
        }
        int estimate = PingSampler.estimate(player.getUniqueId(), ping);
        long wanted = (long) estimate + Math.max(0L, Parameters.lagClientInputDelayMs);
        long cap = Math.max(0L, Parameters.lagMaxRewindMs);
        return new Rewind(ping, estimate, Math.min(cap, wanted), wanted > cap);
    }

    /** Where {@code debug-log} writes, normally {@code plugins/Thievery/logs/minigame-timing.log}. */
    public static void configureLog(Path file, Logger log) {
        logFile = file;
        logger = log;
    }

    /**
     * With {@code debug-log} on, appends one line per judged click: the player, the game, their ping now, the
     * estimate used, the rewind, how many frames (ticks, for the ring) back it reached, the result, the result
     * the newest frame would have given and whether the cap cut the rewind short.
     */
    public static void log(Player player, String game, Rewind rewind, double framesBack, String result,
            String unrewound) {
        if (!Parameters.lagDebugLog || logFile == null) {
            return;
        }
        String line = String.format(Locale.ROOT,
                "%s player=%s game=%s ping=%d estimate=%d rewind=%d framesBack=%.1f result=%s unrewound=%s capped=%b",
                Instant.now(), player.getName(), game, rewind.ping(), rewind.estimate(), rewind.millis(), framesBack,
                result, unrewound, rewind.capped());
        try {
            Files.createDirectories(logFile.toAbsolutePath().getParent());
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warning("Could not write the minigame timing log: " + e.getMessage());
        }
    }
}
