package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class RingLayoutTest {

    @Test
    void pointsGoClockwiseFromTwelve() {
        assertVector(new Vector3f(0, 1, 0), RingLayout.onRing(0, 1));
        assertVector(new Vector3f(1, 0, 0), RingLayout.onRing(90, 1));
        assertVector(new Vector3f(0, -2, 0), RingLayout.onRing(180, 2));
        assertVector(new Vector3f(-1, 0, 0), RingLayout.onRing(270, 1));
        assertEquals(0, RingLayout.dotAngle(0), 1e-9);
        assertEquals(15, RingLayout.dotAngle(1), 1e-9);
        assertEquals(345, RingLayout.dotAngle(RingLayout.DOTS - 1), 1e-9);
    }

    @Test
    void thePickTipAlwaysFacesTheCentre() {
        for (double tip : new double[] {0, 45}) {
            for (double degrees = 0; degrees < 360; degrees += 30) {
                Quaternionf turn = RingLayout.pointInward(degrees, tip);
                Vector3f tipDirection = turn.transform(RingLayout.onRing(tip, 1));
                Vector3f inward = RingLayout.onRing(degrees, 1).negate();
                assertVector(inward, tipDirection);
            }
        }
    }

    @Test
    void ringStaysWellShortOfWhateverIsInTheWay() {
        assertEquals(2.4, RingLayout.fitDistance(2.4, Double.MAX_VALUE), 1e-9);
        assertEquals(2.4, RingLayout.fitDistance(2.4, 5.0), 1e-9);
        assertEquals(1.2, RingLayout.fitDistance(2.4, 2.0), 1e-9);
        assertEquals(RingLayout.MIN_DISTANCE, RingLayout.fitDistance(2.4, 0.3), 1e-9);
    }

    @Test
    void ringPlaneMapsToTheViewersRightUpAndBack() {
        // Facing south (+z): right is west (-x), up is +y, towards the viewer is north (-z).
        assertVector(new Vector(-1, 0, 0), RingLayout.worldOffset(0, 0, new Vector3f(1, 0, 0)));
        assertVector(new Vector(0, 1, 0), RingLayout.worldOffset(0, 0, new Vector3f(0, 1, 0)));
        assertVector(new Vector(0, 0, -1), RingLayout.worldOffset(0, 0, new Vector3f(0, 0, 1)));
        // Facing east (yaw -90, +x): right is south (+z).
        assertVector(new Vector(0, 0, 1), RingLayout.worldOffset(-90, 0, new Vector3f(1, 0, 0)));
        // Looking straight down: up on the ring is the way the viewer faces.
        assertVector(new Vector(0, 0, 1), RingLayout.worldOffset(0, 90, new Vector3f(0, 1, 0)));
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-5);
        assertEquals(expected.y, actual.y, 1e-5);
        assertEquals(expected.z, actual.z, 1e-5);
    }

    private static void assertVector(Vector expected, Vector actual) {
        assertEquals(expected.getX(), actual.getX(), 1e-9);
        assertEquals(expected.getY(), actual.getY(), 1e-9);
        assertEquals(expected.getZ(), actual.getZ(), 1e-9);
    }
}
