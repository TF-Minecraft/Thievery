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
    void withNothingInTheWayTheRingFloatsAtTheWantedDistance() {
        java.util.List<Double> reaches = new java.util.ArrayList<>();
        double distance = RingLayout.fitDistance(2.4, 30f, 20f, 0.14f, (line, reach) -> {
            reaches.add(reach);
            assertEquals(2.4 * line.length() / (RingLayout.DISTANCE * RingLayout.CLEARANCE), reach, 1e-9);
            return Double.POSITIVE_INFINITY;
        });
        assertEquals(2.4, distance, 1e-9);
        assertEquals(RingLayout.SIGHT_LINES * RingLayout.SIGHT_LINES, reaches.size());
    }

    @Test
    void aWallStraightAheadKeepsTheRingShortOfItAndTooCloseAWallHitsTheFloor() {
        // A wall two blocks ahead of an eye looking straight along +z.
        RingLayout.Obstacles wall = (line, reach) -> 2.0 / line.clone().normalize().getZ();
        assertEquals(1.2, RingLayout.fitDistance(2.4, 0f, 0f, 0f, wall), 1e-9);
        RingLayout.Obstacles touching = (line, reach) -> 0.2 / line.clone().normalize().getZ();
        assertEquals(RingLayout.MIN_DISTANCE, RingLayout.fitDistance(2.4, 0f, 0f, 0f, touching), 1e-9);
    }

    @Test
    void lookingDownOrUpAtABlockKeepsEveryPartOfTheRingOutOfIt() {
        for (float pitch : new float[] {-70f, -45f, -30f, -20f, -10f, 10f, 20f, 30f, 45f, 70f}) {
            for (double gap : new double[] {0.6, 0.75, 1.0, 1.5, 2.0, 2.5}) {
                // A floor gap blocks below the eye when looking down, a ceiling above it when looking up.
                double sign = Math.signum(pitch);
                RingLayout.Obstacles plane = (line, reach) -> {
                    double towards = -line.clone().normalize().getY() * sign;
                    return towards <= 0 ? Double.POSITIVE_INFINITY : gap / towards;
                };
                double distance = RingLayout.fitDistance(2.4, 15f, pitch, 0.14f, plane);
                double scale = distance / RingLayout.DISTANCE;
                Vector forward = RingLayout.worldOffset(15f, pitch, new Vector3f(0, 0, -1));
                for (float x = -RingLayout.HALF_WIDTH; x <= RingLayout.HALF_WIDTH; x += 0.1f) {
                    for (float y = RingLayout.BOTTOM; y <= RingLayout.TOP; y += 0.1f) {
                        Vector point = forward.clone().multiply(distance)
                                .add(RingLayout.worldOffset(15f, pitch, new Vector3f(x, y + 0.14f, 0)).multiply(scale));
                        assertTrue(-point.getY() * sign < gap, "pitch " + pitch + " gap " + gap + " point " + point);
                    }
                }
                // The old crosshair-only rule floated the ring at 60% of the way along the crosshair.
                double crosshair = gap / Math.sin(Math.toRadians(Math.abs(pitch))) * RingLayout.CLEARANCE;
                assertTrue(distance <= Math.min(2.4, crosshair) + 1e-9);
            }
        }
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
