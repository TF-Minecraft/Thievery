package net.tfminecraft.thievery.timing;

/**
 * The last few frames a minigame sent to one thief: when each was sent and what it showed. A click is judged
 * against the frame the thief was looking at when they clicked, found by going back their ping from the moment
 * the click arrived.
 */
public final class FrameHistory {

    /** Twenty-four ticks cover 1.2 s, past the largest max-rewind-ms of 1 s (the default cap is 300 ms). */
    public static final int DEFAULT_CAPACITY = 24;

    private final long[] times;
    private final double[] values;
    private int count;
    private long firstTime;

    public FrameHistory() {
        this(DEFAULT_CAPACITY);
    }

    public FrameHistory(int capacity) {
        int size = Math.max(2, capacity);
        times = new long[size];
        values = new double[size];
    }

    /** Forgets every frame, so clicks are not ready until the next one is sent. */
    public void clear() {
        count = 0;
    }

    public void record(long time, double value) {
        if (count == 0) {
            firstTime = time;
        }
        int index = count % times.length;
        times[index] = time;
        values[index] = value;
        count++;
    }

    /**
     * How many frames before the newest one the latest frame sent at or before {@code time} is, or -1 when
     * {@code time} comes before the first frame, so the thief could not have seen anything yet. When the frame
     * has already left the history, the oldest one kept stands in for it.
     */
    public int framesBack(long time) {
        if (count == 0 || time < firstTime) {
            return -1;
        }
        int kept = Math.min(count, times.length);
        for (int back = 0; back < kept - 1; back++) {
            if (times[index(back)] <= time) {
                return back;
            }
        }
        return kept - 1;
    }

    /** What the frame {@code back} frames before the newest one showed. */
    public double value(int back) {
        return values[index(back)];
    }

    /**
     * What the thief saw at {@code time}, gliding between the two frames either side of it, as the client glides
     * the lockpick ring's needle; NaN before the first frame. After the newest frame it is the newest frame.
     */
    public double valueAt(long time) {
        int back = framesBack(time);
        if (back < 0) {
            return Double.NaN;
        }
        if (back == 0) {
            return value(0);
        }
        long olderTime = times[index(back)];
        if (time <= olderTime) {
            return value(back);
        }
        long newerTime = times[index(back - 1)];
        double share = (time - olderTime) / (double) (newerTime - olderTime);
        return value(back) + (value(back - 1) - value(back)) * share;
    }

    private int index(int back) {
        return (count - 1 - back) % times.length;
    }
}
