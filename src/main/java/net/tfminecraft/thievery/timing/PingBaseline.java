package net.tfminecraft.thievery.timing;

/**
 * A player's lowest ping over the last ten minutes, kept as ten one-minute bucket minima, so it outlives the
 * 30-second median window: a ping held high for longer than the window still stands out against it. Each sample
 * is O(1).
 */
final class PingBaseline {

    static final int MINUTES = 10;
    static final long MINUTE_MS = 60_000L;
    /** An empty bucket's minute: far enough back to be stale, near enough that subtracting cannot overflow. */
    private static final long NEVER = Long.MIN_VALUE / 2;

    private final long[] minutes = new long[MINUTES];
    private final int[] lowest = new int[MINUTES];

    PingBaseline() {
        java.util.Arrays.fill(minutes, NEVER);
    }

    void add(long now, int ping) {
        long minute = Math.floorDiv(now, MINUTE_MS);
        int bucket = (int) Math.floorMod(minute, (long) MINUTES);
        if (minutes[bucket] != minute) {
            minutes[bucket] = minute;
            lowest[bucket] = ping;
        } else {
            lowest[bucket] = Math.min(lowest[bucket], ping);
        }
    }

    /** The lowest ping sampled in the last ten minutes up to {@code now}, or -1 when none is that recent. */
    int lowest(long now) {
        long minute = Math.floorDiv(now, MINUTE_MS);
        int result = -1;
        for (int bucket = 0; bucket < MINUTES; bucket++) {
            if (minute - minutes[bucket] < MINUTES && (result < 0 || lowest[bucket] < result)) {
                result = lowest[bucket];
            }
        }
        return result;
    }
}
