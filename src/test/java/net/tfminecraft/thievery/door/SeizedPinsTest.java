package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import org.junit.jupiter.api.Test;

class SeizedPinsTest {

    private static Set<Integer> range(int from, int to) {
        return IntStream.range(from, to).boxed().collect(Collectors.toSet());
    }

    @Test
    void neighboursStayInsideTheMenuRowsAndKnownCells() {
        SeizedPins pins = new SeizedPins(range(0, 27), 9);
        assertEquals(Set.of(1, 9, 10), pins.neighbours(0));
        assertEquals(Set.of(7, 16, 17), pins.neighbours(8));
        assertEquals(Set.of(0, 1, 2, 9, 11, 18, 19, 20), pins.neighbours(10));
        assertEquals(Set.of(9, 10, 19), pins.neighbours(18));
        SeizedPins sparse = new SeizedPins(Set.of(0, 2, 10), 9);
        assertEquals(Set.of(10), sparse.neighbours(0));
        assertEquals(Set.of(0, 2), sparse.neighbours(10));
        assertEquals(Set.of(1), new SeizedPins(Set.of(0, 1), 0).neighbours(0));
    }

    @Test
    void firstProbeAndItsNeighboursStayClearWhenThereIsRoom() {
        for (int seed = 0; seed < 20; seed++) {
            SeizedPins pins = new SeizedPins(range(0, 27), 9);
            assertFalse(pins.isPlaced());
            pins.place(10, 5, new Random(seed));
            assertTrue(pins.isPlaced());
            assertEquals(5, pins.seized().size());
            for (int clear : Set.of(0, 1, 2, 9, 10, 11, 18, 19, 20)) {
                assertFalse(pins.isSeized(clear));
            }
            assertEquals(0, pins.nearby(10));
        }
        SeizedPins seeded = new SeizedPins(range(0, 27), 9);
        seeded.place(10, 5, new Random(7));
        SeizedPins again = new SeizedPins(range(0, 27), 9);
        again.place(10, 5, new Random(7));
        assertEquals(seeded.seized(), again.seized());
        assertThrows(UnsupportedOperationException.class, () -> seeded.seized().add(0));
    }

    @Test
    void crowdedLocksOnlyKeepTheFirstProbeClearAndCapTheCount() {
        SeizedPins pins = new SeizedPins(range(0, 9), 9);
        pins.place(4, 8, new Random(1));
        assertEquals(range(0, 9).stream().filter(cell -> cell != 4).collect(Collectors.toSet()), pins.seized());
        assertEquals(2, pins.nearby(4));
        SeizedPins capped = new SeizedPins(range(0, 9), 9);
        capped.place(4, 50, new Random(1));
        assertEquals(8, capped.seized().size());
        SeizedPins none = new SeizedPins(range(0, 9), 9);
        none.place(4, -3, new Random(1));
        assertTrue(none.isPlaced());
        assertTrue(none.seized().isEmpty());
    }

    @Test
    void nearbyCountsOnlySurroundingSeizedPins() {
        SeizedPins pins = new SeizedPins(range(0, 18), 9);
        pins.place(0, 17, new Random(2));
        assertFalse(pins.isSeized(0));
        assertTrue(pins.isSeized(1));
        assertEquals(3, pins.nearby(0));
        assertEquals(4, pins.nearby(10));
    }

    @Test
    void seizedCountSpreadsTheBreakChanceAcrossTheChestAndAddsSlips() {
        double density = Parameters.chestSeizedDensity;
        int perSlip = Parameters.chestSeizedPerSlip;
        double base = Parameters.chestBaseSuccessChance;
        double max = Parameters.maxSuccessChance;
        try (var risk = mockStatic(RiskCalculator.class)) {
            Parameters.chestSeizedDensity = 0.3;
            Parameters.chestSeizedPerSlip = 1;
            Parameters.chestBaseSuccessChance = 1.0;
            Parameters.maxSuccessChance = 0.95;
            risk.when(() -> RiskCalculator.getDexterityLerpValue(0)).thenReturn(1.0);
            risk.when(() -> RiskCalculator.getDexterityLerpValue(40)).thenReturn(4.0);
            // An iron pick (0.35) at Dexterity 0 breaks 65% of the time: 27 x 0.3 x 0.65 = 5.3.
            assertEquals(5, ChestLockpickSession.computeSeizedCount(0, 0.35, LockTypeProfile.IDENTITY, 27, 0));
            assertEquals(7, ChestLockpickSession.computeSeizedCount(0, 0.35, LockTypeProfile.IDENTITY, 27, 2));
            assertEquals(5, ChestLockpickSession.computeSeizedCount(0, 0.35, LockTypeProfile.IDENTITY, 27, -4));
            assertEquals(11, ChestLockpickSession.computeSeizedCount(0, 0.35, LockTypeProfile.IDENTITY, 54, 0));
            assertEquals(3, ChestLockpickSession.computeSeizedCount(0, 0.35, new LockTypeProfile(1, 1, true, 0.5), 27, 0));
            assertEquals(0, ChestLockpickSession.computeSeizedCount(40, 0.35, LockTypeProfile.IDENTITY, 27, 0));
            Parameters.chestSeizedPerSlip = 3;
            assertEquals(6, ChestLockpickSession.computeSeizedCount(40, 0.35, LockTypeProfile.IDENTITY, 27, 2));
        } finally {
            Parameters.chestSeizedDensity = density;
            Parameters.chestSeizedPerSlip = perSlip;
            Parameters.chestBaseSuccessChance = base;
            Parameters.maxSuccessChance = max;
        }
    }
}
