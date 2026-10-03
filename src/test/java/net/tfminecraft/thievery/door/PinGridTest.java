package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PinGridTest {

    @Test
    void sizeAndPinCountAreClamped() {
        PinGrid grid = new PinGrid(6, 6, 14, new Random(1));
        assertEquals(6, grid.rows());
        assertEquals(6, grid.columns());
        assertEquals(36, grid.cellCount());
        assertEquals(14, grid.pins().size());
        PinGrid large = new PinGrid(10, 12, 500, new Random(2));
        assertEquals(PinGrid.MAX_ROWS, large.rows());
        assertEquals(PinGrid.MAX_COLUMNS, large.columns());
        assertEquals(54, large.pins().size());
        PinGrid small = new PinGrid(0, 0, 0, new Random(3));
        assertEquals(1, small.cellCount());
        assertEquals(Set.of(0), small.pins());
    }

    @Test
    void pickingSetsPinsCountsMistakesAndIgnoresRepeatsAndStrayCells() {
        PinGrid grid = new PinGrid(3, 3, 3, new Random(4));
        assertEquals(3, grid.pins().size());
        assertThrows(UnsupportedOperationException.class, () -> grid.pins().add(9));
        int miss = -1;
        for (int cell = 0; cell < grid.cellCount(); cell++) {
            if (!grid.isPin(cell)) {
                miss = cell;
                break;
            }
        }
        assertEquals(PinGrid.Pick.IGNORED, grid.pick(-1));
        assertEquals(PinGrid.Pick.IGNORED, grid.pick(9));
        assertFalse(grid.isPicked(miss));
        assertEquals(PinGrid.Pick.MISS, grid.pick(miss));
        assertTrue(grid.isPicked(miss));
        assertEquals(PinGrid.Pick.IGNORED, grid.pick(miss));
        assertEquals(1, grid.mistakes());
        assertEquals(3, grid.pinsLeft());
        int left = 3;
        for (int pin : grid.pins()) {
            assertFalse(grid.isSolved());
            assertEquals(PinGrid.Pick.SET, grid.pick(pin));
            assertEquals(--left, grid.pinsLeft());
            assertEquals(PinGrid.Pick.IGNORED, grid.pick(pin));
        }
        assertTrue(grid.isSolved());
        assertEquals(1, grid.mistakes());
    }
}
