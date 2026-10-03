package net.tfminecraft.thievery.door;

import java.util.Random;

/**
 * One pass of the pick round the lockpick ring. The pick starts at twelve o'clock and goes once round, clockwise
 * or anticlockwise, and the thief presses the shown movement key while it is inside the zone. Positions are
 * measured as progress through the pass, from 0 at the start to 1 at the end, so the zone means the same thing
 * in either direction.
 */
public final class Sweep {

    public static final int KEYS = 4;
    /** The zone never starts in the first third of the pass, so there is time to react, and ends by 90%. */
    static final double EARLIEST_ZONE = 1.0 / 3.0;
    static final double LATEST_ZONE_END = 0.9;

    public enum Result {
        HIT,
        EARLY,
        LATE
    }

    private final int periodTicks;
    private final double zoneFrom;
    private final double zoneTo;
    private final boolean clockwise;
    private final int key;

    public Sweep(int periodTicks, double zoneFrom, double zoneTo, boolean clockwise, int key) {
        this.periodTicks = Math.max(1, periodTicks);
        this.zoneFrom = zoneFrom;
        this.zoneTo = zoneTo;
        this.clockwise = clockwise;
        this.key = key;
    }

    /** Rolls a pass with a zone of {@code zoneWidth} (a share of the ring) somewhere after the first third. */
    public static Sweep roll(Random random, int minPeriodTicks, int maxPeriodTicks, double zoneWidth,
            boolean clockwise) {
        int shortest = Math.max(1, minPeriodTicks);
        int period = shortest + random.nextInt(Math.max(shortest, maxPeriodTicks) - shortest + 1);
        double width = Math.max(0.02, Math.min(0.25, zoneWidth));
        double latestStart = LATEST_ZONE_END - width;
        double from = EARLIEST_ZONE + random.nextDouble() * Math.max(0.0, latestStart - EARLIEST_ZONE);
        return new Sweep(period, from, from + width, clockwise, 1 + random.nextInt(KEYS));
    }

    public double progress(double ticks) {
        return Math.max(0.0, Math.min(1.0, ticks / periodTicks));
    }

    /** Clockwise degrees from twelve o'clock of a point {@code progress} of the way through the pass. */
    public double angleAt(double progress) {
        double degrees = 360.0 * progress;
        return clockwise ? degrees : (360.0 - degrees) % 360.0;
    }

    /** Progress at which the pick passes a ring position given in clockwise degrees from twelve o'clock. */
    public double progressAt(double degrees) {
        double turned = ((degrees % 360.0) + 360.0) % 360.0;
        return clockwise ? turned / 360.0 : ((360.0 - turned) % 360.0) / 360.0;
    }

    public boolean inZone(double progress) {
        return progress >= zoneFrom && progress < zoneTo;
    }

    public boolean isOver(int ticks) {
        return ticks >= periodTicks;
    }

    /**
     * Judges a press made {@code ticks} into the pass against where the pick was {@code lagTicks} earlier, which
     * is what the thief saw when they pressed.
     */
    public Result judge(int ticks, int lagTicks) {
        double seen = progress(Math.max(0, ticks - Math.max(0, lagTicks)));
        if (seen < zoneFrom) {
            return Result.EARLY;
        }
        if (seen >= zoneTo) {
            return Result.LATE;
        }
        return Result.HIT;
    }

    public int periodTicks() {
        return periodTicks;
    }

    public double zoneFrom() {
        return zoneFrom;
    }

    public double zoneTo() {
        return zoneTo;
    }

    public boolean clockwise() {
        return clockwise;
    }

    public int key() {
        return key;
    }
}
