package net.tfminecraft.thievery.door;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import org.bukkit.entity.Player;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.object.ObjectContents;

import net.tfminecraft.thievery.cache.Parameters;

/** Shows the pin grid as a vanilla dialog: a button per cell and a "Give up" button, which is the only way out. */
final class GridDialogs {

    static final int CELL_WIDTH = 20;
    static final int STATUS_WIDTH = 260;
    /**
     * A game's click actions are made once and reused on every frame, since frames come every few ticks. A stale
     * click is turned away by the game, so the actions need no use limit, only to outlast any game.
     */
    static final ClickCallback.Options CLICKS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build();

    private GridDialogs() {}

    /** One game's frames, drawn with click actions made on the first frame. */
    static PinGridGame.GridFrames frames(IntConsumer onCell, Runnable onGiveUp) {
        List<DialogAction> cells = new ArrayList<>();
        DialogAction giveUp = DialogAction.customClick((response, audience) -> onGiveUp.run(), CLICKS);
        return (player, screen) -> {
            while (cells.size() < screen.cells().size()) {
                int cell = cells.size();
                cells.add(DialogAction.customClick((response, audience) -> onCell.accept(cell), CLICKS));
            }
            show(player, screen, cells, giveUp);
        };
    }

    private static void show(Player player, GridScreen screen, List<DialogAction> cellActions, DialogAction giveUpAction) {
        boolean pack = Parameters.chestGridPack;
        List<ActionButton> buttons = new ArrayList<>(screen.cells().size() + 1);
        for (int cell = 0; cell < screen.cells().size(); cell++) {
            Component label = pack
                    ? tile(screen.cells().get(cell), edges(cell, screen.columns()))
                    : icon(screen.cells().get(cell));
            if (pack && cell == Math.min(screen.columns(), screen.cells().size()) - 1) {
                // The top row's last tile is drawn after the rest of that row, so its strip lies over them all.
                label = label.append(timerStrip(screen));
            }
            buttons.add(ActionButton.builder(label).width(CELL_WIDTH).action(cellActions.get(cell)).build());
        }
        ActionButton giveUp = ActionButton.builder(pack ? giveUpStrip(screen.columns())
                        : Component.text("Give up", GridScreen.Cell.MISS.colour))
                .width(pack ? giveUpWidth(screen.columns()) : 90)
                .action(giveUpAction)
                .build();
        if (pack) {
            // The give-up button joins the grid as its last row, so its strip can close the board underneath:
            // the dialog clips anything drawn below its last row of buttons.
            buttons.add(giveUp);
        }
        ActionButton exit = pack ? null : giveUp;
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(screen.title())
                        .externalTitle(Component.text("Lockpicking"))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(List.of(DialogBody.plainMessage(screen.status(), STATUS_WIDTH)))
                        .build())
                .type(DialogType.multiAction(buttons, exit, screen.columns())));
        player.showDialog(dialog);
    }

    /** The server resource pack's lockpick font: board tiles, status pips and spaces. */
    static final Key FONT = Key.key("thievery", "lockpick");
    /** How far a tile reaches past its button into the gap between buttons, and past the outer buttons. */
    static final int MARGIN = 1;
    static final int EDGE = 5;
    static final int TOP = 1;
    static final int RIGHT = 2;
    static final int LEFT = 4;
    static final char TILES = '\ue100';
    static final char GIVE_UP_STRIPS = '\ue200';
    static final int GIVE_UP_WIDTH = 96;
    static final char TIMER_RUNS = '\ue300';
    static final char PIN = '\ue010';
    static final char PIN_SET = '\ue011';
    static final char SLIP = '\ue012';
    static final char SLIP_HIT = '\ue013';
    static final int PIP_GAP = 8;

    /** Which outer edges of the board a cell sits on, so its tile draws the rim there. The give-up strip below
     * closes the bottom. */
    static int edges(int cell, int columns) {
        int column = cell % columns;
        int edges = 0;
        if (cell < columns) edges |= TOP;
        if (column == columns - 1) edges |= RIGHT;
        if (column == 0) edges |= LEFT;
        return edges;
    }

    /**
     * The countdown as a strip along the top of the board, drawn from the top row's last button: the colour of the
     * running timer for the share of time left, then the empty track. Spaces bring the pen back to where it
     * started, so the label still measures nothing.
     */
    static Component timerStrip(GridScreen screen) {
        int columns = boardColumns(screen.columns());
        int width = columns * (CELL_WIDTH + 2) - 2;
        int lit = screen.timer() == GridScreen.Timer.NONE ? 0 : (int) Math.round(width * screen.timeLeft());
        int start = -(CELL_WIDTH / 2 + (columns - 1) * (CELL_WIDTH + 2));
        return glyphs(space(start) + runs(screen.timer().ordinal(), lit) + runs(0, width - lit) + space(-(start + width)));
    }

    /** {@code length} pixels of strip in one colour, from runs of power-of-two widths. */
    private static String runs(int colour, int length) {
        StringBuilder text = new StringBuilder();
        for (int power = 7; power >= 0; power--) {
            while (length >= 1 << power) {
                // A glyph advances one pixel past its width.
                text.append((char) (TIMER_RUNS + colour * 16 + power)).append(space(-1));
                length -= 1 << power;
            }
        }
        return text.toString();
    }

    /** The give-up button: as wide as the board allows, up to {@link #GIVE_UP_WIDTH}. */
    static int giveUpWidth(int columns) {
        return Math.min(GIVE_UP_WIDTH, boardColumns(columns) * (CELL_WIDTH + 2) - 2);
    }

    /**
     * The board's bottom row: a strip as wide as the board with its rim, around a red "Give up" plate on the
     * button, centred under the grid like the button itself.
     */
    static Component giveUpStrip(int columns) {
        int board = boardColumns(columns);
        int width = board * (CELL_WIDTH + 2) - 2 + 2 * (MARGIN + EDGE);
        int before = -(width / 2);
        int after = -(width + 1 + before);
        return glyphs(space(before) + (char) (GIVE_UP_STRIPS + board) + space(after));
    }

    private static int boardColumns(int columns) {
        return Math.max(1, Math.min(PinGrid.MAX_COLUMNS, columns));
    }

    /**
     * A cell's tile from the pack font: one glyph per look and set of board edges, covering its button but for
     * the button's one-pixel outline, which shows black, or white under the cursor. Spaces either side bring the
     * label's width to nothing, so the client centres it on the button without scrolling or clipping, and the
     * tile reaches over the gaps to its neighbours to make one board.
     */
    static Component tile(GridScreen.Cell cell, int edges) {
        int left = (edges & LEFT) != 0 ? EDGE : 0;
        int right = (edges & RIGHT) != 0 ? EDGE : 0;
        int width = CELL_WIDTH + 2 * MARGIN + left + right;
        int before = -(CELL_WIDTH / 2 + MARGIN + left);
        int after = -(width + 1 + before);
        char glyph = (char) (TILES + cell.ordinal() * 8 + edges);
        return glyphs(space(before) + glyph + space(after));
    }

    /** Pins set and slips as pips from the pack font. */
    static Component tally(int set, int pins, int slips, int maxSlips) {
        StringBuilder text = new StringBuilder();
        for (int pin = 0; pin < pins; pin++) {
            text.append(pin < set ? PIN_SET : PIN);
        }
        text.append(space(PIP_GAP));
        for (int slip = 0; slip < maxSlips; slip++) {
            text.append(slip < slips ? SLIP_HIT : SLIP);
        }
        return glyphs(text.toString());
    }

    /** A space of {@code advance} pixels in the pack font, which has spaces of up to 64 either way. */
    static String space(int advance) {
        StringBuilder spaces = new StringBuilder();
        int left = Math.abs(advance);
        while (left > 0) {
            int step = Math.min(64, left);
            spaces.append((char) ((advance < 0 ? 0xF000 : 0xF100) + step));
            left -= step;
        }
        return spaces.toString();
    }

    private static Component glyphs(String text) {
        return Component.text(text, NamedTextColor.WHITE).font(FONT).shadowColor(ShadowColor.none());
    }

    /** Button face colour, so the pad glyph in front of a texture cannot be seen. */
    static final TextColor BUTTON_FACE = TextColor.color(0x717171);
    static final String PAD = "\u02d9";

    /**
     * A cell's label. A dialog button whose label is only a texture draws nothing on 1.21.10 clients, so the
     * texture follows a one-pixel dot in the button's own face colour with no shadow. Textures take the text
     * colour as a tint, so they are drawn white.
     */
    static Component icon(GridScreen.Cell cell) {
        if (!Parameters.chestGridSprites) {
            return Component.text(cell.glyph, cell.colour);
        }
        return Component.text()
                .append(Component.text(PAD, BUTTON_FACE).shadowColor(ShadowColor.none()))
                .append(Component.object(ObjectContents.sprite(Key.key(cell.sprite))).color(NamedTextColor.WHITE))
                .build();
    }
}
