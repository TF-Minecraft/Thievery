package net.tfminecraft.thievery.timing;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Lets tests in other packages drive the lag compensation clock and ping samples. */
public final class LagTestSupport {

    private static final LongSupplier REAL_CLOCK = LagCompensation.clock;
    private static PingSampler sampler = new PingSampler();

    private LagTestSupport() {}

    public static void useClock(LongSupplier clock) {
        LagCompensation.clock = clock;
    }

    /** Adds a once-a-second sample to the sampler lag compensation uses. */
    public static void sample(UUID playerId, int ping) {
        sampler.sample(playerId, ping);
    }

    /** Writes the debug log to {@code file} (null for none) and warnings to {@code log}. */
    public static void useLog(java.nio.file.Path file, Logger log) {
        LagCompensation.configure(sampler, file, log);
    }

    /** Puts back the real clock, a fresh sampler with no samples and no debug log file. */
    public static void reset() {
        LagCompensation.clock = REAL_CLOCK;
        sampler = new PingSampler();
        LagCompensation.configure(sampler, null, Logger.getAnonymousLogger());
    }
}
