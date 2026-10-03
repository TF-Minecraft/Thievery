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
    private boolean pack;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sprites = Parameters.chestGridSprites;
        pack = Parameters.chestGridPack;
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
        Parameters.chestGridPack = pack;
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
        assertEquals(List.of(20, 20, 20, 20, 90), widths);
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
    @SuppressWarnings("unchecked")
    void thePackDrawsTheGridAsABoardClosedByTheGiveUpStrip() {
        Parameters.chestGridPack = true;
        int[] gaveUp = {0};
        GridDialogs.show(mock(Player.class), screen(), cell -> {}, () -> gaveUp[0]++);
        assertEquals(GridDialogs.tile(GridScreen.Cell.HIDDEN, GridDialogs.TOP | GridDialogs.LEFT), labels.get(0));
        assertEquals(GridDialogs.tile(GridScreen.Cell.LIT, GridDialogs.TOP | GridDialogs.RIGHT)
                .append(GridDialogs.timerStrip(screen())), labels.get(1));
        assertEquals(GridDialogs.tile(GridScreen.Cell.SET, GridDialogs.LEFT), labels.get(2));
        assertEquals(GridDialogs.tile(GridScreen.Cell.MISS, GridDialogs.RIGHT), labels.get(3));
        assertEquals(GridDialogs.giveUpStrip(2), labels.get(4));
        assertEquals(List.of(20, 20, 20, 20, 42), widths);
        DialogRegistryEntry.Builder entry = mock(DialogRegistryEntry.Builder.class, RETURNS_SELF);
        io.papermc.paper.registry.RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory =
                mock(io.papermc.paper.registry.RegistryBuilderFactory.class);
        when(factory.empty()).thenReturn(entry);
        recipe.accept(factory);
        ArgumentCaptor<List<ActionButton>> all = ArgumentCaptor.forClass(List.class);
        types.verify(() -> DialogType.multiAction(all.capture(), isNull(), eq(2)));
        assertEquals(5, all.getValue().size());
        callbacks.get(4).accept(mock(io.papermc.paper.dialog.DialogResponseView.class), mock(Audience.class));
        assertEquals(1, gaveUp[0]);
    }

    @Test
    void tilesSitOnTheirButtonAndMeasureNothing() {
        TextComponent corner = (TextComponent) GridDialogs.tile(GridScreen.Cell.LIT, GridDialogs.TOP | GridDialogs.LEFT);
        // A 27-pixel tile (20 + 1 each side + 5 of rim on the left) starts 16 left of the button's centre.
        assertEquals(GridDialogs.space(-16) + (char) (0xe100 + 8 + 5) + GridDialogs.space(-12), corner.content());
        assertEquals(GridDialogs.FONT, corner.font());
        assertEquals(NamedTextColor.WHITE, corner.color());
        assertEquals(ShadowColor.none(), corner.shadowColor());
        TextComponent inner = (TextComponent) GridDialogs.tile(GridScreen.Cell.MISSED, 0);
        assertEquals(GridDialogs.space(-11) + (char) (0xe100 + 32) + GridDialogs.space(-12), inner.content());
        TextComponent right = (TextComponent) GridDialogs.tile(GridScreen.Cell.SET, GridDialogs.RIGHT);
        assertEquals(GridDialogs.space(-11) + (char) (0xe100 + 16 + 2) + GridDialogs.space(-17), right.content());
    }

    @Test
    void edgesMarkTheBoardsTopAndSides() {
        // Three columns, two rows.
        assertEquals(GridDialogs.TOP | GridDialogs.LEFT, GridDialogs.edges(0, 3));
        assertEquals(GridDialogs.TOP, GridDialogs.edges(1, 3));
        assertEquals(GridDialogs.TOP | GridDialogs.RIGHT, GridDialogs.edges(2, 3));
        assertEquals(GridDialogs.LEFT, GridDialogs.edges(3, 3));
        assertEquals(0, GridDialogs.edges(4, 3));
        assertEquals(GridDialogs.RIGHT, GridDialogs.edges(5, 3));
        assertEquals(GridDialogs.TOP | GridDialogs.RIGHT | GridDialogs.LEFT, GridDialogs.edges(0, 1));
    }

    @Test
    void theTimerStripRunsFromTheBoardsLeftAndReturnsThePen() {
        GridScreen idle = new GridScreen(Component.empty(), Component.empty(),
                List.of(GridScreen.Cell.HIDDEN, GridScreen.Cell.HIDDEN), 2);
        assertEquals(GridScreen.Timer.NONE, idle.timer());
        // Two columns: the strip is 42 pixels of track, from 32 left of the last button's centre.
        String track = "\ue305" + GridDialogs.space(-1) + "\ue303" + GridDialogs.space(-1) + "\ue301" + GridDialogs.space(-1);
        assertEquals(GridDialogs.space(-32) + track + GridDialogs.space(-10),
                ((TextComponent) GridDialogs.timerStrip(idle)).content());
        GridScreen recall = new GridScreen(Component.empty(), Component.empty(), idle.cells(), 2,
                GridScreen.Timer.RECALL, 0.5);
        // Half lit: 21 green (16 + 4 + 1), then 21 of track.
        String green = "\ue324" + GridDialogs.space(-1) + "\ue322" + GridDialogs.space(-1) + "\ue320" + GridDialogs.space(-1);
        String rest = "\ue304" + GridDialogs.space(-1) + "\ue302" + GridDialogs.space(-1) + "\ue300" + GridDialogs.space(-1);
        assertEquals(GridDialogs.space(-32) + green + rest + GridDialogs.space(-10),
                ((TextComponent) GridDialogs.timerStrip(recall)).content());
        GridScreen full = new GridScreen(Component.empty(), Component.empty(), idle.cells(), 2,
                GridScreen.Timer.URGENT, 3.0);
        assertEquals(1.0, full.timeLeft());
        assertTrue(((TextComponent) GridDialogs.timerStrip(full)).content().contains("\ue335"));
        assertEquals(0.0, new GridScreen(Component.empty(), Component.empty(), idle.cells(), 2,
                GridScreen.Timer.MEMORISE, -1.0).timeLeft());
    }

    @Test
    void theGiveUpStripSpansTheBoardUnderAButtonCentredBelowTheGrid() {
        assertEquals(96, GridDialogs.giveUpWidth(6));
        assertEquals(64, GridDialogs.giveUpWidth(3));
        assertEquals(20, GridDialogs.giveUpWidth(1));
        assertEquals(20, GridDialogs.giveUpWidth(0));
        assertEquals(96, GridDialogs.giveUpWidth(12));
        // Six columns: a board 142 wide (130 of buttons and gaps, 6 of margin and rim each side), centred.
        TextComponent six = (TextComponent) GridDialogs.giveUpStrip(6);
        assertEquals(GridDialogs.space(-71) + (char) (0xe200 + 6) + GridDialogs.space(-72), six.content());
        assertEquals(GridDialogs.FONT, six.font());
        // Nine columns caps the strip at the widest board the pack draws.
        assertTrue(((TextComponent) GridDialogs.giveUpStrip(15)).content().contains(String.valueOf((char) (0xe200 + 9))));
    }

    @Test
    void theTallyIsPipsAndLongSpacesChain() {
        TextComponent tally = (TextComponent) GridDialogs.tally(2, 4, 1, 3);
        assertEquals("\ue011\ue011\ue010\ue010" + GridDialogs.space(GridDialogs.PIP_GAP) + "\ue013\ue012\ue012",
                tally.content());
        assertEquals(GridDialogs.FONT, tally.font());
        assertEquals("\uf105", GridDialogs.space(5));
        assertEquals("\uf00c", GridDialogs.space(-12));
        assertEquals("\uf140\uf11a", GridDialogs.space(90));
        assertEquals("\uf040\uf040\uf001", GridDialogs.space(-129));
        assertEquals("", GridDialogs.space(0));
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
