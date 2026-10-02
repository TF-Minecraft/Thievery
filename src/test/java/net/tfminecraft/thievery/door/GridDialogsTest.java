package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.object.SpriteObjectContents;
import net.tfminecraft.thievery.cache.Parameters;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class GridDialogsTest {
    private MockedStatic<Dialog> dialogs;
    private MockedStatic<ActionButton> buttons;
    private MockedStatic<DialogBase> bases;
    private MockedStatic<DialogBody> bodies;
    private MockedStatic<DialogType> types;
    private MockedStatic<DialogAction> actions;
    private final List<Component> labels = new ArrayList<>();
    private final List<Integer> widths = new ArrayList<>();
    private final List<DialogActionCallback> callbacks = new ArrayList<>();
    private DialogBase.Builder base;
    private Dialog dialog;
    private Consumer<io.papermc.paper.registry.RegistryBuilderFactory<Dialog, ? extends DialogRegistryEntry.Builder>> recipe;
    private boolean sprites;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sprites = Parameters.chestGridSprites;
        Parameters.chestGridSprites = true;
        actions = mockStatic(DialogAction.class);
        actions.when(() -> DialogAction.customClick(any(DialogActionCallback.class), any())).thenAnswer(call -> {
            callbacks.add(call.getArgument(0));
            return mock(DialogAction.CustomClickAction.class);
        });
        buttons = mockStatic(ActionButton.class);
        buttons.when(() -> ActionButton.builder(any(Component.class))).thenAnswer(call -> {
            labels.add(call.getArgument(0));
            ActionButton.Builder builder = mock(ActionButton.Builder.class, RETURNS_SELF);
            when(builder.width(anyInt())).thenAnswer(width -> {
                widths.add(width.getArgument(0));
                return builder;
            });
            when(builder.build()).thenReturn(mock(ActionButton.class));
            return builder;
        });
        base = mock(DialogBase.Builder.class, RETURNS_SELF);
        when(base.build()).thenReturn(mock(DialogBase.class));
        bases = mockStatic(DialogBase.class);
        bases.when(() -> DialogBase.builder(any(Component.class))).thenReturn(base);
        bodies = mockStatic(DialogBody.class);
        bodies.when(() -> DialogBody.plainMessage(any(Component.class), anyInt())).thenReturn(mock(io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody.class));
        types = mockStatic(DialogType.class);
        types.when(() -> DialogType.multiAction(anyList(), any(), anyInt())).thenReturn(mock(MultiActionType.class));
        dialog = mock(Dialog.class);
        dialogs = mockStatic(Dialog.class);
        dialogs.when(() -> Dialog.create(any())).thenAnswer(call -> {
            recipe = call.getArgument(0);
            return dialog;
        });
    }

    @AfterEach
    void tearDown() {
        Parameters.chestGridSprites = sprites;
        for (MockedStatic<?> mocked : List.of(actions, buttons, bases, bodies, types, dialogs)) {
            mocked.close();
        }
    }

    private GridScreen screen() {
        return new GridScreen(Component.text("Set the pins"), Component.text("Pins set 0/2"),
                List.of(GridScreen.Cell.HIDDEN, GridScreen.Cell.LIT, GridScreen.Cell.SET, GridScreen.Cell.MISS),
                2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCellIsAButtonThatReportsItsCellAndGiveUpIsTheOnlyWayOut() {
        Player player = mock(Player.class);
        List<Integer> clicked = new ArrayList<>();
        int[] gaveUp = {0};
        GridDialogs.show(player, screen(), clicked::add, () -> gaveUp[0]++);
        verify(player).showDialog(dialog);
        assertEquals(5, labels.size());
        assertEquals(List.of(24, 24, 24, 24, 90), widths);
        assertEquals("Give up", ((TextComponent) labels.get(4)).content());
        assertEquals(5, callbacks.size());
        callbacks.get(2).accept(mock(io.papermc.paper.dialog.DialogResponseView.class), mock(Audience.class));
        callbacks.get(0).accept(mock(io.papermc.paper.dialog.DialogResponseView.class), mock(Audience.class));
        assertEquals(List.of(2, 0), clicked);
        callbacks.get(4).accept(mock(io.papermc.paper.dialog.DialogResponseView.class), mock(Audience.class));
        assertEquals(1, gaveUp[0]);
        actions.verify(() -> DialogAction.customClick(any(DialogActionCallback.class), eq(GridDialogs.ONE_CLICK)), times(5));

        DialogRegistryEntry.Builder entry = mock(DialogRegistryEntry.Builder.class, RETURNS_SELF);
        io.papermc.paper.registry.RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory =
                mock(io.papermc.paper.registry.RegistryBuilderFactory.class);
        when(factory.empty()).thenReturn(entry);
        recipe.accept(factory);
        verify(factory).empty();
        verify(entry).base(any());
        verify(entry).type(any());
        verify(base).canCloseWithEscape(false);
        verify(base).pause(false);
        verify(base).afterAction(DialogBase.DialogAfterAction.NONE);
        verify(base).externalTitle(Component.text("Lockpicking"));
        bases.verify(() -> DialogBase.builder(Component.text("Set the pins")));
        bodies.verify(() -> DialogBody.plainMessage(Component.text("Pins set 0/2"), GridDialogs.STATUS_WIDTH));
        ArgumentCaptor<List<ActionButton>> cells = ArgumentCaptor.forClass(List.class);
        types.verify(() -> DialogType.multiAction(cells.capture(), any(ActionButton.class), eq(2)));
        assertEquals(4, cells.getValue().size());
    }

    @Test
    void cellLabelsAreAHiddenPadAndAWhiteTextureOrAColouredSquare() {
        Component lit = GridDialogs.icon(GridScreen.Cell.LIT);
        assertEquals(2, lit.children().size());
        TextComponent pad = (TextComponent) lit.children().get(0);
        assertEquals(GridDialogs.PAD, pad.content());
        assertEquals(GridDialogs.BUTTON_FACE, pad.color());
        assertEquals(ShadowColor.none(), pad.shadowColor());
        ObjectComponent sprite = (ObjectComponent) lit.children().get(1);
        assertEquals(NamedTextColor.WHITE, sprite.color());
        assertEquals("block/redstone_lamp_on", ((SpriteObjectContents) sprite.contents()).sprite().value());
        for (GridScreen.Cell cell : GridScreen.Cell.values()) {
            ObjectComponent drawn = (ObjectComponent) GridDialogs.icon(cell).children().get(1);
            assertEquals(cell.sprite, ((SpriteObjectContents) drawn.contents()).sprite().value());
        }
        Parameters.chestGridSprites = false;
        assertEquals(Component.text("✖", GridScreen.Cell.MISS.colour), GridDialogs.icon(GridScreen.Cell.MISS));
    }

    @Test
    void screensCopyTheirCells() {
        List<GridScreen.Cell> cells = new ArrayList<>(List.of(GridScreen.Cell.HIDDEN));
        GridScreen screen = new GridScreen(Component.empty(), Component.empty(), cells, 1);
        cells.add(GridScreen.Cell.LIT);
        assertEquals(1, screen.cells().size());
        assertThrows(UnsupportedOperationException.class, () -> screen.cells().add(GridScreen.Cell.SET));
    }
}
