package net.tfminecraft.thievery.door;

import java.util.Random;

/**
 * Lockpick dial: a pick sweeps once round a twelve-step ring, and the thief presses the shown number key while
 * it is inside the green zone. Modelled on the NoPixel lockpick minigame.
 */
public final class Dial {

    public static final int ROWS = 6;
    public static final int MENU_SIZE = ROWS * PinGrid.MENU_COLUMNS;
    public static final int KEYS = 4;
    public static final int MAX_ZONE = 4;

    private static final int OFFSET = 1;
    // A rough circle in a 6x6 box, clockwise from twelve o'clock: {row, column}.
    private static final int[][] RING = {
            {0, 2}, {0, 3}, {1, 4}, {2, 5}, {3, 5}, {4, 4},
            {5, 3}, {5, 2}, {4, 1}, {3, 0}, {2, 0}, {1, 1}
    };
    public static final int STEPS = RING.length;

    public enum Press {
        HIT,
        EARLY,
        LATE,
        WRONG_KEY
    }

    private int key = 1;
    private int zoneStart = STEPS / 3;
    private int zoneLength = 1;
    private int ticksPerStep = 1;
    private int turnTicks;

    public static int slotOf(int step) {
        int[] cell = RING[Math.floorMod(step, STEPS)];
        return cell[0] * PinGrid.MENU_COLUMNS + OFFSET + cell[1];
    }

    /** Key caps 1 to 4 across the middle of the ring, in hotbar order. */
    public static int[] keySlots() {
        int row = 2 * PinGrid.MENU_COLUMNS + OFFSET;
        return new int[] {row + 1, row + 2, row + 3, row + 4};
    }

    /** Starts a new sweep with a fresh key, zone and speed. The zone sits between four and eight o'clock. */
    public void spin(Random random, int minStepTicks, int maxStepTicks, int zoneSteps) {
        zoneLength = Math.max(1, Math.min(MAX_ZONE, zoneSteps));
        zoneStart = STEPS / 3 + random.nextInt(STEPS / 3 + 1);
        key = 1 + random.nextInt(KEYS);
        int slowest = Math.max(1, minStepTicks);
        ticksPerStep = slowest + random.nextInt(Math.max(slowest, maxStepTicks) - slowest + 1);
        turnTicks = 0;
    }

    /** Moves the pick on by one tick. Returns false once it has gone all the way round. */
    public boolean advance() {
        turnTicks++;
        return position() < STEPS;
    }

    public int position() {
        return turnTicks / ticksPerStep;
    }

    public boolean inZone(int step) {
        return step >= zoneStart && step < zoneStart + zoneLength;
    }

    /**
     * Judges a key press against where the pick was {@code lagTicks} ago, which is what the thief saw when they
     * pressed it.
     */
    public Press press(int pressedKey, int lagTicks) {
        if (pressedKey != key) {
            return Press.WRONG_KEY;
        }
        int seen = Math.max(0, turnTicks - Math.max(0, lagTicks)) / ticksPerStep;
        if (seen < zoneStart) {
            return Press.EARLY;
        }
        if (seen >= zoneStart + zoneLength) {
            return Press.LATE;
        }
        return Press.HIT;
    }

    public int key() {
        return key;
    }

    public int zoneStart() {
        return zoneStart;
    }

    public int zoneLength() {
        return zoneLength;
    }

    public int ticksPerStep() {
        return ticksPerStep;
    }
}
