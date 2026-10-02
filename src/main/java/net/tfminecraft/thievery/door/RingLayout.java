package net.tfminecraft.thievery.door;

import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Geometry of the floating lockpick ring. The ring is drawn in the display entities' own plane: x to the
 * viewer's right, y up, z towards the viewer. Angles are clockwise degrees from twelve o'clock.
 */
public final class RingLayout {

    public static final int DOTS = 24;
    public static final float RADIUS = 0.62f;
    /** The ring is sized for this distance; closer rings shrink so they look the same size. */
    public static final double DISTANCE = 2.4;
    static final double MIN_DISTANCE = 0.25;
    /** Every part of the ring floats at most this share of the way to the block behind it, so none of it sinks in. */
    static final double CLEARANCE = 0.6;
    /**
     * The ring's outline in its own plane at full size, with a margin: the pick swinging round the dots, the pins
     * and hint above them, and the widest label.
     */
    static final float HALF_WIDTH = 1.1f;
    static final float TOP = 1.3f;
    static final float BOTTOM = -0.95f;
    /** Sight lines checked across each side of the outline. */
    static final int SIGHT_LINES = 5;

    /** How far along a sight line from the eye the first block is, or infinity when none is within reach. */
    public interface Obstacles {
        double along(Vector direction, double reach);
    }

    private RingLayout() {}

    public static Vector3f onRing(double degrees, float radius) {
        double radians = Math.toRadians(degrees);
        return new Vector3f((float) (Math.sin(radians) * radius), (float) (Math.cos(radians) * radius), 0f);
    }

    public static double dotAngle(int dot) {
        return 360.0 * dot / DOTS;
    }

    /**
     * Turns the pick at {@code degrees} round the ring so its tip faces the centre. {@code tipDegrees} is where
     * the tip points when unturned; item sprites usually point to the top right, at 45.
     */
    public static Quaternionf pointInward(double degrees, double tipDegrees) {
        // Positive z rotation turns anticlockwise for the viewer, so turn by minus the clockwise angle needed.
        return new Quaternionf().rotateZ((float) Math.toRadians(tipDegrees - degrees - 180.0));
    }

    /**
     * How far in front of an eye at yaw and pitch to float the ring, lifted by {@code lift}: the wanted distance,
     * or well short of any block behind any part of it, but never closer than {@link #MIN_DISTANCE}.
     *
     * <p>Checking only the crosshair is not enough. Looking down at a chest, its lid comes closer towards the
     * bottom of the view, so the bottom of the ring would sink into it; looking up, the same happens at the top.
     * The ring shrinks with its distance, so each of its points stays on one sight line from the eye, and keeping
     * a grid of sight lines across the outline clear keeps the whole ring clear.
     */
    public static double fitDistance(double wanted, float yaw, float pitch, float lift, Obstacles obstacles) {
        Vector forward = worldOffset(yaw, pitch, new Vector3f(0, 0, -1)).multiply(DISTANCE);
        double distance = wanted;
        for (int column = 0; column < SIGHT_LINES; column++) {
            for (int row = 0; row < SIGHT_LINES; row++) {
                float x = -HALF_WIDTH + 2 * HALF_WIDTH * column / (SIGHT_LINES - 1);
                float y = BOTTOM + (TOP - BOTTOM) * row / (SIGHT_LINES - 1) + lift;
                // Where this point of the outline is when the ring is at full size and distance.
                Vector line = forward.clone().add(worldOffset(yaw, pitch, new Vector3f(x, y, 0)));
                double length = line.length();
                double blocked = obstacles.along(line, wanted * length / (DISTANCE * CLEARANCE));
                distance = Math.min(distance, blocked * CLEARANCE * DISTANCE / length);
            }
        }
        return Math.max(MIN_DISTANCE, distance);
    }

    /** World offset from the ring centre of a point in the ring plane, for a ring facing an eye at yaw and pitch. */
    public static Vector worldOffset(float yaw, float pitch, Vector3f local) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        // The viewer's right and up for a camera looking along (yaw, pitch), Minecraft style (yaw 0 = +z).
        Vector right = new Vector(-Math.cos(yawRad), 0, -Math.sin(yawRad));
        Vector forward = new Vector(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        Vector up = right.clone().crossProduct(forward);
        return right.multiply(local.x).add(up.multiply(local.y)).add(forward.multiply(-local.z));
    }
}
