package net.tfminecraft.thievery.timing;

import java.util.UUID;
import java.util.function.LongSupplier;

/** Lets tests in other packages drive the lag compensation clock and ping samples. */
public final class LagTestSupport {

    private static final LongSupplier REAL_CLOCK = LagCompensation.clock;

    private LagTestSupport() {}

    public static void useClock(LongSupplier clock) {
        LagCompensation.clock = clock;
    }

    public static void sample(UUID playerId, int ping) {
        PingSampler.sample(playerId, ping);
    }

    /** Puts back the real clock and forgets every ping sample and the log file. */
    public static void reset() {
        LagCompensation.clock = REAL_CLOCK;
        new PingSampler().stop();
        LagCompensation.configureLog(null, null);
    }
}
