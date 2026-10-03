package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Pin memory puzzle for chest lockpicking: remember the lit pins, then pick them all from memory.
 * Cells are numbered row by row.
 */
public final class PinGrid {

    /** As many as fit the dialog and the lockpick font's board. */
    public static final int MAX_COLUMNS = 9;
    public static final int MAX_ROWS = 6;

    public enum Pick {
        SET,
        MISS,
        IGNORED
    }

    private final int rows;
    private final int columns;
    private final Set<Integer> pins;
    private final Set<Integer> picked = new HashSet<>();
    private int mistakes;

    public PinGrid(int rows, int columns, int pinCount, Random random) {
        this.rows = Math.max(1, Math.min(MAX_ROWS, rows));
        this.columns = Math.max(1, Math.min(MAX_COLUMNS, columns));
        List<Integer> cells = new ArrayList<>(cellCount());
        for (int cell = 0; cell < cellCount(); cell++) {
            cells.add(cell);
        }
        Collections.shuffle(cells, random);
        int count = Math.max(1, Math.min(cellCount(), pinCount));
        this.pins = Set.copyOf(cells.subList(0, count));
    }

    public int rows() {
        return rows;
    }

    public int columns() {
        return columns;
    }

    public int cellCount() {
        return rows * columns;
    }

    public Set<Integer> pins() {
        return pins;
    }

    public boolean isPin(int cell) {
        return pins.contains(cell);
    }

    public boolean isPicked(int cell) {
        return picked.contains(cell);
    }

    public Pick pick(int cell) {
        if (cell < 0 || cell >= cellCount() || !picked.add(cell)) {
            return Pick.IGNORED;
        }
        if (isPin(cell)) {
            return Pick.SET;
        }
        mistakes++;
        return Pick.MISS;
    }

    public int mistakes() {
        return mistakes;
    }

    public int pinsLeft() {
        int left = pins.size();
        for (int cell : picked) {
            if (isPin(cell)) {
                left--;
            }
        }
        return left;
    }

    public boolean isSolved() {
        return pinsLeft() == 0;
    }
}
