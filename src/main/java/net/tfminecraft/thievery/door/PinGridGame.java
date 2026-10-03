package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * Pin memory minigame in a vanilla dialog, modelled on the NoPixel thermite minigame. The pins light up row by
 * row, hold, then go dark, and the thief clicks them all from memory before the recall timer runs out. Each
 * correct pin plays the next note of a pentatonic scale.
 */
final class PinGridGame extends LockMinigame {

    static final int COUNTDOWN_SECONDS = 3;
    static final int SCAN_TICKS_PER_ROW = 2;
    /**
     * The dialog hides the boss bar behind its blur, so a running countdown is drawn in the dialog: redrawn this
     * often for the board's timer strip, or each second for the countdown in the title.
     */
    static final int TIMER_REDRAW_TICKS = 4;
    /** Semitones of a major pentatonic scale over two octaves; each correct pin plays the next one. */
    static final int[] SCALE = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24};
    static final TextColor GOLD = TextColor.color(0xf2c53d);
    static final TextColor GREEN = TextColor.color(0x6fd34f);
    static final TextColor RED = TextColor.color(0xe0524c);
    static final TextColor DIM = TextColor.color(0x55585e);

    enum Phase {
        PREPARE,
        SCAN,
        MEMORISE,
        RECALL
    }

    final PinGrid grid;
    final int recallTicks;
    final GridScreens screens;
    Phase phase = Phase.PREPARE;
    int phaseTicks;
    int ticksLeft;
    int scannedRows;

    PinGridGame(LockMinigameManager manager, UUID playerId, Block target, String targetId, PinGrid grid,
            int recallTicks, GridScreens screens, IntConsumer onSolved) {
        super(manager, playerId, target, targetId, onSolved);
        this.grid = grid;
        this.recallTicks = recallTicks;
        this.screens = screens;
        setPhase(Phase.PREPARE, ticks(Parameters.chestMinigamePrepareSeconds));
    }

    private void setPhase(Phase next, int duration) {
        phase = next;
        phaseTicks = duration;
        ticksLeft = duration;
    }

    @Override
    void begin(Player player) {
        show(player);
    }

    @Override
    void tickRunning(Player player) {
        ticksLeft--;
        if (phase == Phase.RECALL) {
            if (ticksLeft <= 0) {
                fail(player);
                return;
            }
            if (ticksLeft % 20 == 0 && ticksLeft <= COUNTDOWN_SECONDS * 20) {
                bar.setColor(BarColor.RED);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f);
            }
            redrawTimer(player);
        } else if (phase == Phase.SCAN) {
            int rows = Math.min(grid.rows(), (phaseTicks - ticksLeft) / SCAN_TICKS_PER_ROW + 1);
            if (rows > scannedRows) {
                scannedRows = rows;
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.2f + rows * 0.08f);
                show(player);
            }
            if (ticksLeft <= 0) {
                setPhase(Phase.MEMORISE, ticks(Parameters.chestMinigameMemoriseSeconds));
            }
        } else if (ticksLeft <= 0) {
            if (phase == Phase.PREPARE) {
                setPhase(Phase.SCAN, grid.rows() * SCAN_TICKS_PER_ROW);
                status(ThieveryTexts.WARN + "Memorise the pins", BarColor.YELLOW);
            } else {
                setPhase(Phase.RECALL, recallTicks);
                status(ThieveryTexts.SUCCESS + "Set the pins", BarColor.GREEN);
                player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.6f, 0.8f);
                show(player);
            }
        } else if (phase == Phase.MEMORISE) {
            redrawTimer(player);
        }
        progress(ticksLeft / (double) phaseTicks);
    }

    private void redrawTimer(Player player) {
        if (ticksLeft % (Parameters.chestGridPack ? TIMER_REDRAW_TICKS : 20) == 0) {
            show(player);
        }
    }

    /**
     * A cell click from any of this game's dialog frames. A quick thief can click again before the redrawn frame
     * arrives, and a cell is the same cell on every frame, so clicks count whichever frame they came from as long
     * as the pins are being set and this is still the thief's game.
     */
    void click(Player player, int cell) {
        if (!current() || phase != Phase.RECALL) {
            return;
        }
        PinGrid.Pick pick = grid.pick(cell);
        if (pick == PinGrid.Pick.IGNORED) {
            return;
        }
        if (pick == PinGrid.Pick.SET) {
            int note = SCALE[Math.min(SCALE.length - 1, grid.pins().size() - grid.pinsLeft() - 1)];
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f,
                    (float) Math.pow(2, (note - 12) / 12.0));
            if (grid.isSolved()) {
                solve(player);
                return;
            }
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            if (grid.mistakes() >= Parameters.chestMinigameMistakesToFail) {
                fail(player);
                return;
            }
        }
        show(player);
    }

    void giveUp(Player player) {
        if (current()) {
            manager.giveUp(player, this);
        }
    }

    /** Still being played, and not ended or replaced, so a late click from an old dialog cannot reopen it. */
    private boolean current() {
        return outcome == Outcome.NONE && manager.game(playerId) == this;
    }

    void show(Player player) {
        screens.show(player, screen(), cell -> click(player, cell), () -> giveUp(player));
    }

    GridScreen screen() {
        List<GridScreen.Cell> cells = new ArrayList<>(grid.cellCount());
        for (int cell = 0; cell < grid.cellCount(); cell++) {
            cells.add(look(cell));
        }
        return new GridScreen(title(), status(), cells, grid.columns(), timer(), timeLeft());
    }

    private GridScreen.Cell look(int cell) {
        boolean pin = grid.isPin(cell);
        if (grid.isPicked(cell)) {
            return pin ? GridScreen.Cell.SET : GridScreen.Cell.MISS;
        }
        if (pin && outcome == Outcome.FAILED) {
            return GridScreen.Cell.MISSED;
        }
        boolean shown = phase == Phase.MEMORISE || phase == Phase.SCAN && cell / grid.columns() < scannedRows;
        // Pins of a solved or failed grid were caught above, so a lit pin can only belong to a running puzzle.
        return pin && shown ? GridScreen.Cell.LIT : GridScreen.Cell.HIDDEN;
    }

    private Component title() {
        if (outcome == Outcome.SOLVED) {
            return Component.text("The lock gives way", GREEN);
        }
        if (outcome == Outcome.FAILED) {
            return Component.text("The pins slip", RED);
        }
        return switch (phase) {
            case PREPARE -> Component.text("Steady your hands...", NamedTextColor.GRAY);
            case SCAN -> Component.text("Memorise the pins \u00b7 "
                    + seconds(ticks(Parameters.chestMinigameMemoriseSeconds)), GOLD);
            case MEMORISE -> Component.text("Memorise the pins \u00b7 " + seconds(ticksLeft), GOLD);
            case RECALL -> Component.text("Set the pins \u00b7 " + seconds(ticksLeft), urgent() ? RED : GREEN);
        };
    }

    /** Whole seconds left, rounded up, so the countdown shows 1 until time is out. */
    static int seconds(int ticks) {
        return Math.max(0, (ticks + 19) / 20);
    }

    private boolean urgent() {
        return ticksLeft <= COUNTDOWN_SECONDS * 20;
    }

    private GridScreen.Timer timer() {
        if (outcome != Outcome.NONE) {
            return GridScreen.Timer.NONE;
        }
        return switch (phase) {
            case PREPARE -> GridScreen.Timer.NONE;
            case SCAN, MEMORISE -> GridScreen.Timer.MEMORISE;
            case RECALL -> urgent() ? GridScreen.Timer.URGENT : GridScreen.Timer.RECALL;
        };
    }

    private double timeLeft() {
        return phase == Phase.SCAN ? 1.0 : ticksLeft / (double) phaseTicks;
    }

    private Component status() {
        int set = grid.pins().size() - grid.pinsLeft();
        int slips = grid.mistakes();
        if (Parameters.chestGridPack) {
            return GridDialogs.tally(set, grid.pins().size(), slips, Parameters.chestMinigameMistakesToFail);
        }
        return Component.text()
                .append(Component.text("Pins set " + set + "/" + grid.pins().size(), set > 0 ? GREEN : DIM))
                .append(Component.text("    Slips " + slips + "/" + Parameters.chestMinigameMistakesToFail,
                        slips > 0 ? RED : DIM))
                .build();
    }

    @Override
    int mistakes() {
        return grid.mistakes();
    }

    @Override
    void showOutcome(Player player) {
        show(player);
    }

    @Override
    void cleanup(Player player) {
        if (player != null) {
            player.closeDialog();
        }
    }

    /** Draws a grid frame; the dialog implementation is {@link GridDialogs#show}. */
    interface GridScreens {
        void show(Player player, GridScreen screen, IntConsumer onCell, Runnable onGiveUp);
    }
}
