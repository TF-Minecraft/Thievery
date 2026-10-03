package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Hidden seized pins in a chest probe menu. Probing one snaps the lockpick; every safe probe counts the
 * seized pins in the eight cells around it. Pins are placed on the first probe, away from that cell.
 */
public final class SeizedPins {

    private final Set<Integer> cells;
    private final int columns;
    private Set<Integer> seized = Set.of();
    private boolean placed;

    public SeizedPins(Collection<Integer> cells, int columns) {
        this.cells = Set.copyOf(cells);
        this.columns = Math.max(1, columns);
    }

    /** Places up to {@code count} pins, keeping the first probe and, when there is room, its neighbours clear. */
    public void place(int firstProbe, int count, Random random) {
        Set<Integer> clear = neighbours(firstProbe);
        clear.add(firstProbe);
        List<Integer> candidates = candidates(clear);
        if (candidates.size() < count) {
            candidates = candidates(Set.of(firstProbe));
        }
        Collections.shuffle(candidates, random);
        seized = Set.copyOf(candidates.subList(0, Math.max(0, Math.min(count, candidates.size()))));
        placed = true;
    }

    public boolean isPlaced() {
        return placed;
    }

    public boolean isSeized(int cell) {
        return seized.contains(cell);
    }

    public Set<Integer> seized() {
        return seized;
    }

    public int nearby(int cell) {
        int count = 0;
        for (int neighbour : neighbours(cell)) {
            if (seized.contains(neighbour)) {
                count++;
            }
        }
        return count;
    }

    Set<Integer> neighbours(int cell) {
        Set<Integer> around = new HashSet<>();
        int row = Math.floorDiv(cell, columns);
        int column = Math.floorMod(cell, columns);
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                int c = column + dc;
                int neighbour = (row + dr) * columns + c;
                if ((dr != 0 || dc != 0) && c >= 0 && c < columns && cells.contains(neighbour)) {
                    around.add(neighbour);
                }
            }
        }
        return around;
    }

    private List<Integer> candidates(Set<Integer> excluded) {
        List<Integer> candidates = new ArrayList<>();
        for (int cell : cells) {
            if (!excluded.contains(cell)) {
                candidates.add(cell);
            }
        }
        Collections.sort(candidates);
        return candidates;
    }
}
