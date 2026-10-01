package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class DialGameTest {
    private ServerMock server;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<RiskCalculator> risk;
    private LockPickManager lockPicks;
    private LockMinigameManager manager;
    private PlayerMock player;
    private Block chest;
    private final AtomicInteger solved = new AtomicInteger();
    private final AtomicInteger solvedMistakes = new AtomicInteger(-1);
    private boolean enabled;
    private double dialChance, prepare, perDexterity, breakChance;
    private int tumblers, zone, minStep, maxStep, maxLag, mistakes;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Thievery instance = mock(Thievery.class);
        when(instance.isEnabled()).thenReturn(true);
        when(instance.getName()).thenReturn("Thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        risk = mockStatic(RiskCalculator.class);
        enabled = Parameters.chestMinigameEnabled;
        dialChance = Parameters.chestDialChance;
        prepare = Parameters.chestMinigamePrepareSeconds;
        perDexterity = Parameters.chestDialStepTicksPerDexterity;
        breakChance = Parameters.chestMinigameFailBreakChance;
        tumblers = Parameters.chestDialTumblers;
        zone = Parameters.chestDialZoneSteps;
        minStep = Parameters.chestDialMinStepTicks;
        maxStep = Parameters.chestDialMaxStepTicks;
        maxLag = Parameters.chestDialMaxLagTicks;
        mistakes = Parameters.chestMinigameMistakesToFail;
        Parameters.chestMinigameEnabled = true;
        Parameters.chestDialChance = 1.0;
        Parameters.chestMinigamePrepareSeconds = 0.05;
        Parameters.chestDialStepTicksPerDexterity = 0.025;
        Parameters.chestMinigameFailBreakChance = 0.0;
        Parameters.chestDialTumblers = 2;
        Parameters.chestDialZoneSteps = 2;
        Parameters.chestDialMinStepTicks = 2;
        Parameters.chestDialMaxStepTicks = 4;
        Parameters.chestDialMaxLagTicks = 6;
        Parameters.chestMinigameMistakesToFail = 3;
        lockPicks = new LockPickManager();
        // Rolls of 0: every sweep has key 1, a green zone on steps 4-5 and two ticks per step.
        Random random = mock(Random.class);
        when(random.nextDouble()).thenReturn(0.9);
        manager = new LockMinigameManager(lockPicks, random);
        chest = server.addSimpleWorld("vault").getBlockAt(0, 64, 0);
        chest.setType(Material.CHEST);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        Parameters.chestMinigameEnabled = enabled;
        Parameters.chestDialChance = dialChance;
        Parameters.chestMinigamePrepareSeconds = prepare;
        Parameters.chestDialStepTicksPerDexterity = perDexterity;
        Parameters.chestMinigameFailBreakChance = breakChance;
        Parameters.chestDialTumblers = tumblers;
        Parameters.chestDialZoneSteps = zone;
        Parameters.chestDialMinStepTicks = minStep;
        Parameters.chestDialMaxStepTicks = maxStep;
        Parameters.chestDialMaxLagTicks = maxLag;
        Parameters.chestMinigameMistakesToFail = mistakes;
        risk.close();
        plugin.close();
        MockBukkit.unmock();
    }

    private DialGame start() {
        assertTrue(manager.start(player, chest, mistakes -> {
            solved.incrementAndGet();
            solvedMistakes.set(mistakes);
        }));
        return assertInstanceOf(DialGame.class, player.getOpenInventory().getTopInventory().getHolder());
    }

    private void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    private InventoryClickEvent press(int key) {
        return press(ClickType.NUMBER_KEY, key - 1);
    }

    private InventoryClickEvent press(ClickType type, int hotbarButton) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                Dial.keySlots()[0], type, InventoryAction.HOTBAR_SWAP, hotbarButton);
        manager.onInventoryClick(event);
        return event;
    }

    private Material at(DialGame game, int slot) {
        return game.inventory.getItem(slot).getType();
    }

    @Test
    void settingEveryTumblerInTheGreenOpensTheLock() {
        DialGame game = start();
        assertEquals(54, game.inventory.getSize());
        for (int step = 0; step < Dial.STEPS; step++) {
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, at(game, Dial.slotOf(step)));
        }
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, at(game, Dial.keySlots()[0]));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, at(game, DialGame.TUMBLER_COLUMN));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, at(game, 9 + DialGame.TUMBLER_COLUMN));
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, at(game, 18 + DialGame.TUMBLER_COLUMN));
        press(1);
        assertEquals(DialGame.Phase.PREPARE, game.phase);

        ticks(1);
        assertEquals(DialGame.Phase.TURN, game.phase);
        assertEquals("§ePress 1 in the green", player.getOpenInventory().getTitle());
        assertEquals(1, game.inventory.getItem(Dial.keySlots()[3]).getAmount());
        assertEquals(Material.GOLD_NUGGET, at(game, Dial.keySlots()[3]));
        assertEquals(Material.TRIPWIRE_HOOK, at(game, Dial.slotOf(0)));
        assertEquals(Material.CYAN_STAINED_GLASS_PANE, at(game, Dial.slotOf(4)));
        assertEquals(Material.CYAN_STAINED_GLASS_PANE, at(game, Dial.slotOf(5)));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, at(game, Dial.slotOf(6)));
        assertEquals(2, game.dial.ticksPerStep());

        ticks(2);
        assertEquals(Material.TRIPWIRE_HOOK, at(game, Dial.slotOf(1)));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, at(game, Dial.slotOf(0)));
        ticks(6);
        assertEquals(Material.TRIPWIRE_HOOK, at(game, Dial.slotOf(4)));
        assertTrue(press(1).isCancelled());
        assertEquals(1, game.set);
        assertEquals(DialGame.Phase.PAUSE, game.phase);
        assertEquals("§aTumbler set", player.getOpenInventory().getTitle());
        assertEquals(Material.LIME_STAINED_GLASS_PANE, at(game, Dial.slotOf(5)));
        assertEquals(Material.LIME_STAINED_GLASS_PANE, at(game, DialGame.TUMBLER_COLUMN));
        assertEquals(0.5, game.bar.getProgress(), 1e-9);
        player.assertSoundHeard(Sound.BLOCK_TRIPWIRE_CLICK_ON);
        press(1);
        assertEquals(1, game.set);

        ticks(DialGame.PAUSE_TICKS - 1);
        assertEquals(DialGame.Phase.PAUSE, game.phase);
        ticks(1);
        assertEquals(DialGame.Phase.TURN, game.phase);
        ticks(10);
        press(1);
        assertEquals(2, game.set);
        assertEquals(LockMinigame.Outcome.SOLVED, game.outcome);
        assertEquals("§aThe lock gives way", player.getOpenInventory().getTitle());
        ticks(LockMinigame.SOLVED_TICKS);
        assertEquals(1, solved.get());
        assertEquals(0, solvedMistakes.get());
        assertFalse(manager.isPicking(chest));
    }

    @Test
    void earlyLateWrongAndMissedPressesAreSlipsThatFailTheLock() {
        DialGame game = start();
        ticks(1);
        press(ClickType.LEFT, -1);
        press(ClickType.NUMBER_KEY, 4);
        press(ClickType.NUMBER_KEY, -1);
        assertEquals(DialGame.Phase.TURN, game.phase);
        assertEquals(0, game.misses);

        press(1);
        assertEquals(1, game.misses);
        assertEquals("§cToo soon", player.getOpenInventory().getTitle());
        assertEquals(BarColor.RED, game.bar.getColor());
        assertEquals(Material.RED_STAINED_GLASS_PANE, at(game, Dial.slotOf(4)));
        assertEquals(Material.RED_STAINED_GLASS_PANE, at(game, DialGame.MISTAKE_COLUMN));
        player.assertSoundHeard(Sound.BLOCK_NOTE_BLOCK_BASS);

        ticks(DialGame.PAUSE_TICKS + 13);
        assertEquals(6, game.dial.position());
        press(1);
        assertEquals(2, game.misses);
        assertEquals("§cToo late", player.getOpenInventory().getTitle());
        assertEquals(Material.RED_STAINED_GLASS_PANE, at(game, 9 + DialGame.MISTAKE_COLUMN));

        ticks(DialGame.PAUSE_TICKS);
        press(2);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals(3, game.mistakes());
        assertEquals("§cThe pins slip", player.getOpenInventory().getTitle());
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertEquals("§cThe pins slip back into place.", player.nextMessage());
        ticks(LockMinigame.FAILED_TICKS);
        assertFalse(manager.isPicking(chest));
        assertEquals(0, solved.get());
    }

    @Test
    void letTheWrongKeyThroughThenWaitOutASweep() {
        Parameters.chestMinigameMistakesToFail = 2;
        DialGame game = start();
        ticks(1);
        press(3);
        assertEquals("§cWrong key", player.getOpenInventory().getTitle());
        ticks(DialGame.PAUSE_TICKS);
        assertEquals(DialGame.Phase.TURN, game.phase);
        ticks(Dial.STEPS * 2 - 1);
        assertEquals(LockMinigame.Outcome.NONE, game.outcome);
        ticks(1);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals(2, game.misses);
    }

    @Test
    void dexteritySlowsThePickAndAMissThenHitStillSeizesAPin() {
        risk.when(() -> RiskCalculator.getDexterity(player)).thenReturn(40);
        Parameters.chestDialTumblers = 1;
        DialGame game = start();
        ticks(1);
        assertEquals(3, game.dial.ticksPerStep());
        assertEquals(1, game.slowerSteps);
        press(1);
        ticks(DialGame.PAUSE_TICKS + 12);
        assertEquals(4, game.dial.position());
        press(1);
        assertEquals(LockMinigame.Outcome.SOLVED, game.outcome);
        ticks(LockMinigame.SOLVED_TICKS);
        assertEquals(1, solvedMistakes.get());
        game.showFailure();
        assertEquals(Material.LIME_STAINED_GLASS_PANE, at(game, DialGame.TUMBLER_COLUMN));
    }

    @Test
    void tallyColumnsStopAtTheMenuEdge() {
        Parameters.chestDialTumblers = 8;
        Parameters.chestMinigameMistakesToFail = 9;
        DialGame game = start();
        for (int row = 0; row < Dial.ROWS; row++) {
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, at(game, row * 9 + DialGame.TUMBLER_COLUMN));
        }
        ticks(1);
        for (int miss = 0; miss < 7; miss++) {
            press(2);
            ticks(DialGame.PAUSE_TICKS);
        }
        assertEquals(7, game.misses);
        for (int row = 0; row < Dial.ROWS; row++) {
            assertEquals(Material.RED_STAINED_GLASS_PANE, at(game, row * 9 + DialGame.MISTAKE_COLUMN));
        }
        assertEquals(new ItemStack(Material.BLACK_STAINED_GLASS_PANE).getType(), at(game, 1));
    }
}
