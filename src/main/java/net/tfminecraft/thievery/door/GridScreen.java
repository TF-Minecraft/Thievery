package net.tfminecraft.thievery.door;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/** One frame of the pin grid dialog: a title, a status line and how each cell looks. */
record GridScreen(Component title, Component status, List<Cell> cells, int columns) {

    /** How a grid cell is drawn: a block texture, or a coloured square when textures are off. */
    enum Cell {
        HIDDEN("block/redstone_lamp", "\u25a1", 0x55585e),
        LIT("block/redstone_lamp_on", "\u25a0", 0xf2c53d),
        SET("block/sea_lantern", "\u25a0", 0x6fd34f),
        MISS("block/redstone_block", "\u2716", 0xe0524c),
        MISSED("block/gold_block", "\u25a0", 0xd6a43a);

        final String sprite;
        final String glyph;
        final TextColor colour;

        Cell(String sprite, String glyph, int colour) {
            this.sprite = sprite;
            this.glyph = glyph;
            this.colour = TextColor.color(colour);
        }
    }

    GridScreen {
        cells = List.copyOf(cells);
    }
}
