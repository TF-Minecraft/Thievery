package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class PinGridGameTest {
    record Shown(GridScreen screen, IntConsumer onCell, Runnable onGiveUp) {}

    private ServerMock server;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<RiskCalculator> risk;
    private LockPickManager lockPicks;
    private LockMinigameManager manager;
    private PlayerMock player;
    private Block chest;
    private final List<Shown> shown = new ArrayList<>();
    private final AtomicInteger solved = new AtomicInteger();
    private final AtomicInteger solvedMistakes = new AtomicInteger(-1);
    private final ParameterSnapshot saved = new ParameterSnapshot();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Thievery instance = mock(Thievery.class);
        when(instance.isEnabled()).thenReturn(true);
        when(instance.getName()).thenReturn("Thievery");
        when(instance.namespace()).thenReturn("thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        risk = mockStatic(RiskCalculator.class);
        saved.take();
        Parameters.chestMinigameEnabled = true;
        Parameters.chestDialChance = 0.0;
        Parameters.chestMinigameRows = 2;
        Parameters.chestMinigameColumns = 3;
        Parameters.chestMinigamePins = 2;
        Parameters.chestMinigameMistakesToFail = 2;
        Parameters.chestMinigamePrepareSeconds = 0.05;
        Parameters.chestMinigameMemoriseSeconds = 0.1;
        Parameters.chestMinigameRecallSeconds = 1.0;
        Parameters.chestMinigameRecallSecondsPerDexterity = 0.05;
        Parameters.chestMinigameFailBreakChance = 0.0;
        Parameters.lockpickFailCooldownMs = 60_000L;
        lockPicks = new LockPickManager();
        // Rolls of 0 shuffle the six cells to 1, 2, 3, 4, 5, 0: the pins are cells 1 and 2.
        manager = new LockMinigameManager(lockPicks, mock(Random.class));
        manager.gridScreens = (who, screen, onCell, onGiveUp) -> shown.add(new Shown(screen, onCell, onGiveUp));
        chest = server.addSimpleWorld("vault").getBlockAt(0, 64, 0);
        chest.setType(Material.CHEST);
        player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.TRIPWIRE_HOOK));
    }

    @AfterEach
    void tearDown() {
        saved.restore();
        risk.close();
        plugin.close();
        MockBukkit.unmock();
    }

    private PinGridGame start() {
        assertTrue(manager.start(player, chest, null, mistakes -> {
            solved.incrementAndGet();
            solvedMistakes.set(mistakes);
        }));
        return (PinGridGame) manager.game(player.getUniqueId());
    }

    private void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    private Shown last() {
        return shown.get(shown.size() - 1);
    }

    private static String text(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Ticks through the steady pause, the row scan and the memorise hold. */
    private void reachRecall(PinGridGame game) {
        ticks(1);
        assertEquals(PinGridGame.Phase.SCAN, game.phase);
        ticks(4);
        assertEquals(PinGridGame.Phase.MEMORISE, game.phase);
        ticks(2);
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
    }

    @Test
    void withThePackTheStatusIsATallyOfPips() {
        Parameters.chestGridPack = true;
        PinGridGame game = start();
        assertEquals(GridDialogs.tally(0, 2, 0, 2), last().screen().status());
        reachRecall(game);
        game.click(player, game.grid.pins().iterator().next());
        assertEquals(GridDialogs.tally(1, 2, 0, 2), last().screen().status());
    }

    @Test
    void pinsScanOnRowByRowHoldThenGoDark() {
        risk.when(() -> RiskCalculator.getDexterity(player)).thenReturn(10);
        PinGridGame game = start();
        assertEquals(30, game.recallTicks);
        assertTrue(game.bar.getPlayers().contains(player));
        assertEquals(1, shown.size());
        assertEquals("Steady your hands...", text(last().screen().title()));
        assertEquals(List.of(GridScreen.Cell.HIDDEN, GridScreen.Cell.HIDDEN, GridScreen.Cell.HIDDEN,
                GridScreen.Cell.HIDDEN, GridScreen.Cell.HIDDEN, GridScreen.Cell.HIDDEN), last().screen().cells());
        assertEquals(3, last().screen().columns());
        assertEquals("Pins set 0/2    Slips 0/2", text(last().screen().status()));

        ticks(1);
        assertEquals(BarColor.YELLOW, game.bar.getColor());
        ticks(1);
        assertEquals(2, shown.size());
        assertEquals("Memorise the pins · 1", text(last().screen().title()));
        assertEquals(GridScreen.Timer.MEMORISE, last().screen().timer());
        assertEquals(1.0, last().screen().timeLeft());
        assertEquals(GridScreen.Cell.LIT, last().screen().cells().get(1));
        assertEquals(GridScreen.Cell.LIT, last().screen().cells().get(2));
        assertEquals(GridScreen.Cell.HIDDEN, last().screen().cells().get(0));
        player.assertSoundHeard(Sound.BLOCK_NOTE_BLOCK_HAT);
        ticks(1);
        assertEquals(3, shown.size());
        assertEquals(2, game.scannedRows);
        ticks(2);
        assertEquals(PinGridGame.Phase.MEMORISE, game.phase);
        assertEquals(3, shown.size());
        assertEquals(GridScreen.Cell.LIT, game.screen().cells().get(1));
        ticks(2);
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
        assertEquals(4, shown.size());
        assertEquals("Set the pins · 2", text(last().screen().title()));
        assertEquals(PinGridGame.RED, last().screen().title().color());
        assertEquals(GridScreen.Timer.URGENT, last().screen().timer());
        assertFalse(last().screen().cells().contains(GridScreen.Cell.LIT));
        assertEquals(BarColor.GREEN, game.bar.getColor());
        player.assertSoundHeard(Sound.BLOCK_TRIPWIRE_CLICK_OFF);
    }

    @Test
    void settingEveryPinPlaysAScaleThenOpensTheLock() {
        PinGridGame game = start();
        shown.get(0).onCell().accept(1);
        reachRecall(game);
        assertEquals(4, shown.size());

        // A quick second click lands before the redrawn frame does, so it arrives from an older frame and still counts.
        shown.get(2).onCell().accept(1);
        assertEquals(5, shown.size());
        assertEquals(GridScreen.Cell.SET, last().screen().cells().get(1));
        assertEquals("Pins set 1/2    Slips 0/2", text(last().screen().status()));
        last().onCell().accept(1);
        assertEquals(5, shown.size());
        last().onCell().accept(4);
        assertEquals(GridScreen.Cell.MISS, last().screen().cells().get(4));
        player.assertSoundHeard(Sound.BLOCK_NOTE_BLOCK_BASS);
        last().onCell().accept(2);
        assertEquals(LockMinigame.Outcome.SOLVED, game.outcome);
        assertEquals("The lock gives way", text(last().screen().title()));
        assertEquals(GridScreen.Cell.SET, last().screen().cells().get(2));
        player.assertSoundHeard(Sound.BLOCK_NOTE_BLOCK_CHIME);
        player.assertSoundHeard(Sound.BLOCK_IRON_TRAPDOOR_OPEN);
        last().onCell().accept(0);
        last().onGiveUp().run();
        assertEquals(LockMinigame.Outcome.SOLVED, game.outcome);

        ticks(LockMinigame.SOLVED_TICKS - 1);
        assertEquals(0, solved.get());
        ticks(1);
        assertEquals(1, solved.get());
        assertEquals(1, solvedMistakes.get());
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
    }

    @Test
    void eachCorrectPinPlaysTheNextNoteOfTheScale() {
        Parameters.chestMinigamePins = 4;
        Player listener = spy(player);
        assertTrue(manager.start(listener, chest, null, mistakes -> {}));
        PinGridGame game = (PinGridGame) manager.game(player.getUniqueId());
        reachRecall(game);
        List<Integer> pins = new ArrayList<>(game.grid.pins());
        for (int pin : pins.subList(0, 3)) {
            last().onCell().accept(pin);
        }
        verify(listener).playSound(any(org.bukkit.Location.class), eq(Sound.BLOCK_NOTE_BLOCK_CHIME), eq(0.8f),
                eq((float) Math.pow(2, (0 - 12) / 12.0)));
        verify(listener).playSound(any(org.bukkit.Location.class), eq(Sound.BLOCK_NOTE_BLOCK_CHIME), eq(0.8f),
                eq((float) Math.pow(2, (2 - 12) / 12.0)));
        verify(listener).playSound(any(org.bukkit.Location.class), eq(Sound.BLOCK_NOTE_BLOCK_CHIME), eq(0.8f),
                eq((float) Math.pow(2, (4 - 12) / 12.0)));
    }

    @Test
    void tooManyWrongCellsRevealTheMissedPinsAndApplyThePenalties() {
        Parameters.chestMinigameFailBreakChance = 1.0;
        player.getInventory().getItemInMainHand().setAmount(2);
        PinGridGame game = start();
        reachRecall(game);
        last().onCell().accept(0);
        last().onCell().accept(3);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals("The pins slip", text(last().screen().title()));
        assertEquals(GridScreen.Cell.MISSED, last().screen().cells().get(1));
        assertEquals(GridScreen.Cell.MISS, last().screen().cells().get(3));
        assertEquals(BarColor.RED, game.bar.getColor());
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertEquals("§cThe pins slip and your lockpick snaps!", player.nextMessage());
        ticks(LockMinigame.FAILED_TICKS);
        assertFalse(manager.isPlaying(player.getUniqueId()));
        assertEquals(0, solved.get());
    }

    @Test
    void theRecallTimerCountsDownThenFails() {
        Parameters.chestMinigameRecallSeconds = 5.0;
        Parameters.chestMinigameRecallSecondsPerDexterity = 0.0;
        PinGridGame game = start();
        reachRecall(game);
        ticks(game.recallTicks - 61);
        assertEquals(BarColor.GREEN, game.bar.getColor());
        ticks(1);
        assertEquals(BarColor.RED, game.bar.getColor());
        ticks(59);
        assertEquals(LockMinigame.Outcome.NONE, game.outcome);
        assertTrue(game.bar.getProgress() < 0.05);
        ticks(1);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals("§cThe pins slip back into place.", player.nextMessage());
    }

    @Test
    void theCountdownIsDrawnInTheDialogEachSecondOrOftenForThePackStrip() {
        Parameters.chestMinigameMemoriseSeconds = 2.0;
        Parameters.chestMinigameRecallSeconds = 5.0;
        Parameters.chestMinigameRecallSecondsPerDexterity = 0.0;
        PinGridGame game = start();
        assertEquals(GridScreen.Timer.NONE, last().screen().timer());
        ticks(1 + 4);
        assertEquals(PinGridGame.Phase.MEMORISE, game.phase);
        int frames = shown.size();
        ticks(20);
        // Forty ticks of memorising: one redraw as the title's second ticks over.
        assertEquals(frames + 1, shown.size());
        assertEquals("Memorise the pins · 1", text(last().screen().title()));
        assertEquals(0.5, last().screen().timeLeft(), 1e-9);
        ticks(20);
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
        assertEquals(GridScreen.Timer.RECALL, last().screen().timer());
        assertEquals("Set the pins · 5", text(last().screen().title()));
        assertEquals(PinGridGame.GREEN, last().screen().title().color());

        Parameters.chestGridPack = true;
        frames = shown.size();
        ticks(PinGridGame.TIMER_REDRAW_TICKS * 3);
        assertEquals(frames + 3, shown.size());
        assertEquals(1.0 - 12 / 100.0, last().screen().timeLeft(), 1e-9);
        ticks(100 - 12 - 60);
        assertEquals(GridScreen.Timer.URGENT, last().screen().timer());
        assertEquals("Set the pins · 3", text(last().screen().title()));
        game.solve(player);
        assertEquals(GridScreen.Timer.NONE, game.screen().timer());
    }

    @Test
    void secondsRoundUpUntilTimeIsOut() {
        assertEquals(0, PinGridGame.seconds(0));
        assertEquals(0, PinGridGame.seconds(-5));
        assertEquals(1, PinGridGame.seconds(1));
        assertEquals(1, PinGridGame.seconds(20));
        assertEquals(2, PinGridGame.seconds(21));
    }

    @Test
    void theSteadyPauseHoldsUntilItsTimeRunsOutAndMovementKeysDoNothing() {
        Parameters.chestMinigamePrepareSeconds = 1.0;
        PinGridGame game = start();
        manager.onInput(new org.bukkit.event.player.PlayerInputEvent(player,
                RingDialGameTest.input(true, true, true, true, true)));
        ticks(19);
        assertEquals(PinGridGame.Phase.PREPARE, game.phase);
        assertEquals(1.0 / 20, game.bar.getProgress(), 1e-9);
        assertEquals(LockMinigame.Outcome.NONE, game.outcome);
        ticks(1);
        assertEquals(PinGridGame.Phase.SCAN, game.phase);
        assertFalse(game.holdsStill());
    }

    @Test
    void givingUpFromAnyFrameCountsAsAFailedAttempt() {
        PinGridGame game = start();
        reachRecall(game);
        shown.get(0).onGiveUp().run();
        assertEquals("§7You ease the pick back out.", player.nextMessage());
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertEquals("§cThe pins slip back into place.", player.nextMessage());
        last().onGiveUp().run();
        assertNull(player.nextMessage());
    }

    @Test
    void anOldDialogCannotTouchACancelledGameOrTheNextOne() {
        PinGridGame first = start();
        reachRecall(first);
        Shown old = last();
        manager.cancel(player.getUniqueId());
        assertEquals(LockMinigame.Outcome.NONE, first.outcome);
        old.onCell().accept(1);
        old.onGiveUp().run();
        assertFalse(first.grid.isPicked(1));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), first.targetId));

        PinGridGame second = start();
        reachRecall(second);
        old.onCell().accept(1);
        old.onGiveUp().run();
        assertEquals(LockMinigame.Outcome.NONE, second.outcome);
        assertEquals(GridScreen.Cell.HIDDEN, second.screen().cells().get(1));
    }

    @Test
    void cleanupClosesTheDialogAndToleratesAnOfflineThief() {
        Player watched = spy(player);
        assertTrue(manager.start(watched, chest, null, mistakes -> {}));
        PinGridGame game = (PinGridGame) manager.game(player.getUniqueId());
        game.cleanup(watched);
        verify(watched).closeDialog();
        assertDoesNotThrow(() -> game.cleanup(null));
        assertEquals(0, game.mistakes());
    }
}
