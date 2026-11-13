package net.tfminecraft.thievery.timing;

import java.util.Arrays;

/** One player's last pings, sampled once a second. */
final class PingWindow {

    private final int[] samples;
    private int count;

    PingWindow(int capacity) {
        samples = new int[Math.max(1, capacity)];
    }

    int capacity() {
        return samples.length;
    }

    void add(int ping) {
        samples[count % samples.length] = Math.max(0, ping);
        count++;
    }

    /**
     * The median of the samples, the lower middle one of an even count; a window always holds at least one.
     * Paper's ping is already an average over about five seconds (to be confirmed on Dev); the median of half a
     * minute of it is not moved by a spike or a short burst of faked latency.
     */
    int median() {
        int[] sorted = kept();
        Arrays.sort(sorted);
        return sorted[(sorted.length - 1) / 2];
    }

    /** The lowest sample kept. */
    int lowest() {
        return Arrays.stream(kept()).min().getAsInt();
    }

    private int[] kept() {
        return Arrays.copyOf(samples, Math.min(count, samples.length));
    }
}
