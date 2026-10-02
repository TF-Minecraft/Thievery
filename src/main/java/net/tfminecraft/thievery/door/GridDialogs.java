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

    static final int CELL_WIDTH = 24;
    static final int STATUS_WIDTH = 260;
    static final ClickCallback.Options ONE_CLICK = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(2)).build();

    private GridDialogs() {}

    static void show(Player player, GridScreen screen, IntConsumer onCell, Runnable onGiveUp) {
        List<ActionButton> buttons = new ArrayList<>(screen.cells().size());
        for (int cell = 0; cell < screen.cells().size(); cell++) {
            int clicked = cell;
            buttons.add(ActionButton.builder(icon(screen.cells().get(cell))).width(CELL_WIDTH)
                    .action(DialogAction.customClick((response, audience) -> onCell.accept(clicked), ONE_CLICK))
                    .build());
        }
        ActionButton giveUp = ActionButton.builder(Component.text("Give up", GridScreen.Cell.MISS.colour)).width(90)
                .action(DialogAction.customClick((response, audience) -> onGiveUp.run(), ONE_CLICK))
                .build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(screen.title())
                        .externalTitle(Component.text("Lockpicking"))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(List.of(DialogBody.plainMessage(screen.status(), STATUS_WIDTH)))
                        .build())
                .type(DialogType.multiAction(buttons, giveUp, screen.columns())));
        player.showDialog(dialog);
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
