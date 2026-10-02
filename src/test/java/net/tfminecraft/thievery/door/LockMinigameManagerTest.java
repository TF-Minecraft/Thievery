package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class LockMinigameManagerTest {
    private ServerMock server;
    private MockedStatic<Thievery> plugin;
    private MockedStatic<RiskCalculator> risk;
    private LockPickManager lockPicks;
    private Random random;
    private LockMinigameManager manager;
    private PlayerMock player;
    private Block chest;
    private final AtomicInteger solved = new AtomicInteger();
    private final AtomicInteger solvedMistakes = new AtomicInteger(-1);
    private final java.util.function.IntConsumer onSolved = mistakes -> { solved.incrementAndGet(); solvedMistakes.set(mistakes); };
    private boolean enabled;
    private int rows, columns, pins, mistakes;
    private double prepare, memorise, recall, perDexterity, breakChance;
    private long failCooldown;
    private double dialChance;

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
        rows = Parameters.chestMinigameRows;
        columns = Parameters.chestMinigameColumns;
        pins = Parameters.chestMinigamePins;
        mistakes = Parameters.chestMinigameMistakesToFail;
        prepare = Parameters.chestMinigamePrepareSeconds;
        memorise = Parameters.chestMinigameMemoriseSeconds;
        recall = Parameters.chestMinigameRecallSeconds;
        perDexterity = Parameters.chestMinigameRecallSecondsPerDexterity;
        breakChance = Parameters.chestMinigameFailBreakChance;
        failCooldown = Parameters.lockpickFailCooldownMs;
        dialChance = Parameters.chestDialChance;
        Parameters.chestDialChance = 0.0;
        Parameters.chestMinigameEnabled = true;
        Parameters.chestMinigameRows = 2;
        Parameters.chestMinigameColumns = 3;
        Parameters.chestMinigamePins = 2;
        Parameters.chestMinigameMistakesToFail = 2;
        Parameters.chestMinigamePrepareSeconds = 0.05;
        Parameters.chestMinigameMemoriseSeconds = 0.1;
        Parameters.chestMinigameRecallSeconds = 1.0;
        Parameters.chestMinigameRecallSecondsPerDexterity = 0.05;
        Parameters.chestMinigameFailBreakChance = 0.5;
        Parameters.lockpickFailCooldownMs = 60_000L;
        lockPicks = new LockPickManager();
        random = mock(Random.class);
        when(random.nextDouble()).thenReturn(0.9);
        manager = new LockMinigameManager(lockPicks, random);
        World world = server.addSimpleWorld("vault");
        chest = world.getBlockAt(0, 64, 0);
        chest.setType(Material.CHEST);
        player = server.addPlayer();
        player.getInventory().setItemInMainHand(new ItemStack(Material.TRIPWIRE_HOOK));
    }

    @AfterEach
    void tearDown() {
        Parameters.chestMinigameEnabled = enabled;
        Parameters.chestMinigameRows = rows;
        Parameters.chestMinigameColumns = columns;
        Parameters.chestMinigamePins = pins;
        Parameters.chestMinigameMistakesToFail = mistakes;
        Parameters.chestMinigamePrepareSeconds = prepare;
        Parameters.chestMinigameMemoriseSeconds = memorise;
        Parameters.chestMinigameRecallSeconds = recall;
        Parameters.chestMinigameRecallSecondsPerDexterity = perDexterity;
        Parameters.chestMinigameFailBreakChance = breakChance;
        Parameters.lockpickFailCooldownMs = failCooldown;
        Parameters.chestDialChance = dialChance;
        risk.close();
        plugin.close();
        MockBukkit.unmock();
    }

    private PinGridGame start() {
        assertTrue(manager.start(player, chest, onSolved));
        return game();
    }

    private PinGridGame game() {
        return (PinGridGame) player.getOpenInventory().getTopInventory().getHolder();
    }

    private void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    /** Runs the prepare and memorise phases, returning the slots that were lit. */
    private List<Integer> memorise(PinGridGame game) {
        ticks(1);
        assertEquals(PinGridGame.Phase.MEMORISE, game.phase);
        List<Integer> lit = slotsOf(game.inventory, Material.LIME_STAINED_GLASS_PANE);
        ticks(2);
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
        assertTrue(slotsOf(game.inventory, Material.LIME_STAINED_GLASS_PANE).isEmpty());
        return lit;
    }

    private static List<Integer> slotsOf(Inventory inventory, Material material) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() == material) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private InventoryClickEvent click(int rawSlot) {
        return click(player.getOpenInventory(), rawSlot);
    }

    private InventoryClickEvent click(InventoryView view, int rawSlot) {
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        manager.onInventoryClick(event);
        return event;
    }

    private int missSlot(PinGridGame game, List<Integer> lit, int skip) {
        int found = 0;
        for (int slot = 0; slot < game.inventory.getSize(); slot++) {
            if (game.grid.cellAt(slot) >= 0 && !lit.contains(slot) && found++ == skip) {
                return slot;
            }
        }
        throw new AssertionError("no free cell");
    }

    private void close() {
        manager.onInventoryClose(new InventoryCloseEvent(player.getOpenInventory()));
    }

    @Test
    void targetIdsNameTheChestBlock() {
        assertEquals("chest:vault:0:64:0", LockMinigameManager.targetId(chest.getLocation()));
        assertEquals("", LockMinigameManager.targetId(null));
        assertEquals("", LockMinigameManager.targetId(new Location(null, 1, 2, 3)));
        assertEquals(1, LockMinigame.ticks(0));
        assertEquals(30, LockMinigame.ticks(1.5));
    }

    @Test
    void disabledMinigameOpensTheChestStraightAway() {
        Parameters.chestMinigameEnabled = false;
        assertTrue(manager.start(player, chest, onSolved));
        assertEquals(1, solved.get());
        assertEquals(0, solvedMistakes.get());
        assertFalse(manager.isPicking(chest));
    }

    @Test
    void staffCanForceEitherMinigameEvenWhenTheMinigameIsOff() {
        assertTrue(manager.start(player, chest, LockMinigameManager.Mode.DIAL, onSolved));
        assertInstanceOf(DialGame.class, player.getOpenInventory().getTopInventory().getHolder());
        manager.cancel(player.getUniqueId());
        Parameters.chestMinigameEnabled = false;
        Parameters.chestDialChance = 1.0;
        assertTrue(manager.start(player, chest, LockMinigameManager.Mode.GRID, onSolved));
        assertInstanceOf(PinGridGame.class, game());
        assertEquals(0, solved.get());
        manager.cancel(player.getUniqueId());
        assertTrue(manager.start(player, chest, null, onSolved));
        assertEquals(1, solved.get());
    }

    @Test
    void solvingEveryPinShowsSuccessThenRunsTheCallback() {
        risk.when(() -> RiskCalculator.getDexterity(player)).thenReturn(10);
        PinGridGame game = start();
        assertTrue(manager.isPicking(chest));
        assertFalse(manager.isPicking(chest.getRelative(1, 0, 0)));
        assertEquals(18, game.inventory.getSize());
        assertEquals(30, game.recallTicks);
        assertEquals(PinGridGame.Phase.PREPARE, game.phase);
        assertEquals(12, slotsOf(game.inventory, Material.BLACK_STAINED_GLASS_PANE).size());
        assertEquals(6, slotsOf(game.inventory, Material.GRAY_STAINED_GLASS_PANE).size());
        assertEquals(" ", game.inventory.getItem(0).getItemMeta().getDisplayName());
        assertTrue(game.bar.getPlayers().contains(player));

        assertTrue(click(game.grid.slotOf(0)).isCancelled());
        assertEquals(0, game.grid.mistakes());
        List<Integer> lit = memorise(game);
        assertEquals(2, lit.size());
        player.assertSoundHeard(Sound.BLOCK_NOTE_BLOCK_HAT);
        player.assertSoundHeard(Sound.BLOCK_TRIPWIRE_CLICK_OFF);
        assertEquals("§aSet the pins", player.getOpenInventory().getTitle());
        assertEquals(BarColor.GREEN, game.bar.getColor());

        assertTrue(click(0).isCancelled());
        assertTrue(click(18).isCancelled());
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
        click(lit.get(0));
        assertEquals(Material.LIME_STAINED_GLASS_PANE, game.inventory.getItem(lit.get(0)).getType());
        player.assertSoundHeard(Sound.BLOCK_TRIPWIRE_CLICK_ON);
        click(lit.get(0));
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
        ticks(1);
        assertTrue(game.bar.getProgress() < 1.0);
        click(missSlot(game, lit, 0));
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
        click(lit.get(1));
        assertEquals(LockMinigame.Outcome.SOLVED, game.outcome);
        assertEquals("§aThe lock gives way", player.getOpenInventory().getTitle());
        player.assertSoundHeard(Sound.BLOCK_IRON_TRAPDOOR_OPEN);

        close();
        assertTrue(manager.isPicking(chest));
        ticks(LockMinigame.SOLVED_TICKS - 1);
        assertEquals(0, solved.get());
        ticks(1);
        assertEquals(1, solved.get());
        assertEquals(1, solvedMistakes.get());
        assertFalse(manager.isPicking(chest));
        assertTrue(game.bar.getPlayers().isEmpty());
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        ticks(LockMinigame.SOLVED_TICKS);
        assertEquals(1, solved.get());
    }

    @Test
    void tooManyMistakesRevealsMissedPinsAppliesTheCooldownAndMayBreakThePick() {
        when(random.nextDouble()).thenReturn(0.1);
        player.getInventory().getItemInMainHand().setAmount(2);
        PinGridGame game = start();
        List<Integer> lit = memorise(game);
        click(lit.get(0));
        int first = missSlot(game, lit, 0);
        click(first);
        assertEquals(Material.RED_STAINED_GLASS_PANE, game.inventory.getItem(first).getType());
        player.assertSoundHeard(Sound.BLOCK_NOTE_BLOCK_BASS);
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
        click(missSlot(game, lit, 1));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals(Material.YELLOW_STAINED_GLASS_PANE, game.inventory.getItem(lit.get(1)).getType());
        assertEquals(Material.LIME_STAINED_GLASS_PANE, game.inventory.getItem(lit.get(0)).getType());
        assertEquals("§cThe pins slip", player.getOpenInventory().getTitle());
        assertEquals(BarColor.RED, game.bar.getColor());
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        player.assertSoundHeard(Sound.ENTITY_ITEM_BREAK);
        assertEquals("§cThe pins slip and your lockpick snaps!", player.nextMessage());
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));

        click(lit.get(1));
        ticks(LockMinigame.FAILED_TICKS - 1);
        assertSame(game, game());
        ticks(1);
        assertEquals(InventoryType.CRAFTING, player.getOpenInventory().getType());
        assertFalse(manager.isPicking(chest));
        assertEquals(0, solved.get());

        assertFalse(manager.start(player, chest, onSolved));
        String wait = player.nextMessage();
        assertTrue(wait.startsWith("§cYour hands are still shaking. Try this lock again in "), wait);
        assertTrue(wait.endsWith("s."), wait);
        assertFalse(manager.isPicking(chest));
    }

    @Test
    void runningOutOfTimeFailsWithACountdownAndALastPickSnaps() {
        Parameters.chestMinigameRecallSeconds = 5.0;
        Parameters.chestMinigameRecallSecondsPerDexterity = 0.0;
        when(random.nextDouble()).thenReturn(0.1);
        PinGridGame game = start();
        memorise(game);
        ticks(game.recallTicks - 61);
        assertEquals(BarColor.GREEN, game.bar.getColor());
        ticks(1);
        assertEquals(BarColor.RED, game.bar.getColor());
        ticks(59);
        assertEquals(PinGridGame.Phase.RECALL, game.phase);
        ticks(1);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
        player.assertSoundHeard(Sound.ENTITY_ITEM_BREAK);
    }

    @Test
    void failingWithoutABreakOrWithAnEmptyHandOnlySlipsThePins() {
        PinGridGame game = start();
        memorise(game);
        ticks(game.recallTicks);
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        assertEquals(Material.TRIPWIRE_HOOK, player.getInventory().getItemInMainHand().getType());
        assertEquals("§cThe pins slip back into place.", player.nextMessage());
        player.assertSoundHeard(Sound.BLOCK_CHEST_LOCKED);

        lockPicks.clearCooldown(player.getUniqueId());
        when(random.nextDouble()).thenReturn(0.1);
        player.getInventory().setItemInMainHand(null);
        start();
        close();
        assertFalse(manager.isPicking(chest));
        assertEquals("§cThe pins slip back into place.", player.nextMessage());
    }

    @Test
    void closingTheGridEarlyCountsAsAFailedAttempt() {
        when(random.nextDouble()).thenReturn(0.1);
        PinGridGame game = start();
        close();
        assertFalse(manager.isPicking(chest));
        assertTrue(game.task.isCancelled());
        assertTrue(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
        assertEquals("§cThe pins slip and your lockpick snaps!", player.nextMessage());
        assertEquals(PinGridGame.Phase.PREPARE, game.phase);

        lockPicks.clearCooldown(player.getUniqueId());
        game = start();
        List<Integer> lit = memorise(game);
        click(missSlot(game, lit, 0));
        click(missSlot(game, lit, 1));
        assertEquals(LockMinigame.Outcome.FAILED, game.outcome);
        player.nextMessage();
        close();
        assertFalse(manager.isPicking(chest));
        assertNull(player.nextMessage());
    }

    @Test
    void unrelatedClicksDragsAndClosesAreLeftAlone() {
        PinGridGame game = start();
        InventoryView view = player.getOpenInventory();

        Inventory other = server.createInventory(null, 9, "Other");
        PlayerMock bystander = server.addPlayer();
        bystander.openInventory(other);
        InventoryClickEvent plain = click(bystander.getOpenInventory(), 0);
        assertFalse(plain.isCancelled());
        manager.onInventoryDrag(new InventoryDragEvent(bystander.getOpenInventory(), null, new ItemStack(Material.STONE),
                false, java.util.Map.of()));
        manager.onInventoryClose(new InventoryCloseEvent(bystander.getOpenInventory()));

        InventoryDragEvent drag = new InventoryDragEvent(view, null, new ItemStack(Material.STONE), false,
                java.util.Map.of());
        manager.onInventoryDrag(drag);
        assertTrue(drag.isCancelled());

        InventoryView foreign = mock(InventoryView.class);
        HumanEntity human = mock(HumanEntity.class);
        when(foreign.getTopInventory()).thenReturn(game.inventory);
        when(foreign.getPlayer()).thenReturn(human);
        InventoryClickEvent humanClick = mock(InventoryClickEvent.class);
        when(humanClick.getView()).thenReturn(foreign);
        when(humanClick.getWhoClicked()).thenReturn(human);
        manager.onInventoryClick(humanClick);
        verify(humanClick).setCancelled(true);
        verify(humanClick, never()).getSlot();

        when(humanClick.getWhoClicked()).thenReturn(bystander);
        manager.onInventoryClick(humanClick);
        verify(humanClick, never()).getSlot();

        InventoryCloseEvent humanClose = mock(InventoryCloseEvent.class);
        when(humanClose.getPlayer()).thenReturn(human);
        manager.onInventoryClose(humanClose);
        when(humanClose.getPlayer()).thenReturn(player);
        when(humanClose.getInventory()).thenReturn(other);
        manager.onInventoryClose(humanClose);
        assertTrue(manager.isPicking(chest));
    }

    @Test
    void cancellingEndsGamesWithoutAPenalty() {
        PinGridGame first = start();
        PinGridGame second = start();
        assertNotSame(first, second);
        assertTrue(first.task.isCancelled());
        assertTrue(manager.isPicking(chest));
        manager.tick(player, first);
        assertEquals(PinGridGame.Phase.PREPARE, first.phase);

        PlayerMock other = server.addPlayer();
        Block barrel = chest.getRelative(2, 0, 0);
        barrel.setType(Material.BARREL);
        assertTrue(manager.start(other, barrel, onSolved));
        other.openInventory(server.createInventory(null, 9, "Elsewhere"));
        manager.cancelAll();
        assertFalse(manager.isPicking(chest));
        assertFalse(manager.isPicking(barrel));
        assertEquals("Elsewhere", other.getOpenInventory().getTitle());
        assertEquals(InventoryType.CRAFTING, player.getOpenInventory().getType());
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), second.targetId));
        manager.cancel(player.getUniqueId());
        assertEquals(0, solved.get());
    }

    @Test
    void brokenChestsAndDepartedThievesEndTheGameQuietly() {
        PinGridGame game = start();
        chest.setType(Material.STONE);
        ticks(1);
        assertFalse(manager.isPicking(chest));
        assertFalse(lockPicks.isOnCooldown(player.getUniqueId(), game.targetId));

        chest.setType(Material.CHEST);
        start();
        player.disconnect();
        ticks(1);
        assertFalse(manager.isPicking(chest));
        assertEquals(0, solved.get());
    }

    @Test
    void prepareHoldsTheGridBlankUntilItsTimeRunsOut() {
        Parameters.chestMinigamePrepareSeconds = 1.0;
        PinGridGame game = start();
        assertSame(game.inventory, game.getInventory());
        assertEquals("§7Steady your hands...", player.getOpenInventory().getTitle());
        ticks(19);
        assertEquals(PinGridGame.Phase.PREPARE, game.phase);
        assertEquals(1.0 / 20, game.bar.getProgress(), 1e-9);
        ticks(1);
        assertEquals(PinGridGame.Phase.MEMORISE, game.phase);
        assertEquals("§eMemorise the pins", player.getOpenInventory().getTitle());
    }

    @Test
    void phaseChangesSkipTheTitleWhenAnotherMenuIsOpen() {
        PinGridGame game = start();
        player.openInventory(server.createInventory(null, 9, "Elsewhere"));
        ticks(1);
        assertEquals(PinGridGame.Phase.MEMORISE, game.phase);
        assertEquals("Elsewhere", player.getOpenInventory().getTitle());
        assertEquals(2, slotsOf(game.inventory, Material.LIME_STAINED_GLASS_PANE).size());
    }
}
