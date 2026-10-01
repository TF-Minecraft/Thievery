package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PinGridTest {

    @Test
    void gridIsCentredInTheChestMenuAndMapsSlotsBothWays() {
        PinGrid grid = new PinGrid(6, 6, 14, new Random(1));
        assertEquals(6, grid.rows());
        assertEquals(6, grid.columns());
        assertEquals(36, grid.cellCount());
        assertEquals(54, grid.menuSize());
        assertEquals(1, grid.slotOf(0));
        assertEquals(6, grid.slotOf(5));
        assertEquals(10, grid.slotOf(6));
        assertEquals(51, grid.slotOf(35));
        Set<Integer> slots = new HashSet<>();
        for (int cell = 0; cell < grid.cellCount(); cell++) {
            assertEquals(cell, grid.cellAt(grid.slotOf(cell)));
            slots.add(grid.slotOf(cell));
        }
        assertEquals(36, slots.size());
        assertEquals(-1, grid.cellAt(-1));
        assertEquals(-1, grid.cellAt(54));
        assertEquals(-1, grid.cellAt(0));
        assertEquals(-1, grid.cellAt(7));
        assertEquals(-1, grid.cellAt(8));
    }

    @Test
    void sizeAndPinCountAreClampedToTheMenu() {
        PinGrid large = new PinGrid(10, 12, 500, new Random(2));
        assertEquals(PinGrid.MAX_ROWS, large.rows());
        assertEquals(PinGrid.MENU_COLUMNS, large.columns());
        assertEquals(54, large.pins().size());
        assertEquals(0, large.slotOf(0));
        PinGrid small = new PinGrid(0, 0, 0, new Random(3));
        assertEquals(1, small.cellCount());
        assertEquals(9, small.menuSize());
        assertEquals(Set.of(0), small.pins());
        assertEquals(4, small.slotOf(0));
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
