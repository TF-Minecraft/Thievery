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
    static final double MIN_DISTANCE = 0.6;
    /** The ring floats at most this share of the way to whatever the thief is looking at, so it never sinks in. */
    static final double CLEARANCE = 0.6;

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
     * How far in front of the eye to float the ring: the configured distance, or well short of a block in the
     * way, such as the chest being picked, but never closer than {@link #MIN_DISTANCE}.
     */
    public static double fitDistance(double wanted, double blockDistance) {
        return Math.max(MIN_DISTANCE, Math.min(wanted, blockDistance * CLEARANCE));
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
