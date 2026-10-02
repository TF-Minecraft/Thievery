package net.tfminecraft.thievery.door;

import java.util.Random;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * Lockpick dial minigame: set each tumbler by pressing the shown number key while the pick sweeps through the
 * green zone. Pressing too soon, too late, the wrong key, or not at all counts as a mistake.
 */
final class DialGame extends LockMinigame {

    static final int PAUSE_TICKS = 10;
    static final int TUMBLER_COLUMN = 8;
    static final int MISTAKE_COLUMN = 0;

    enum Phase {
        PREPARE,
        TURN,
        PAUSE
    }

    final Dial dial = new Dial();
    final Random random;
    final int tumblers;
    final int slowerSteps;
    Phase phase = Phase.PREPARE;
    int ticksLeft;
    int set;
    int misses;

    DialGame(LockMinigameManager manager, UUID playerId, Block target, String targetId, Random random,
            int dexterity, IntConsumer onSolved) {
        super(manager, playerId, target, targetId, Dial.MENU_SIZE, onSolved);
        this.random = random;
        this.tumblers = Parameters.chestDialTumblers;
        this.slowerSteps = (int) (Math.max(0, dexterity) * Parameters.chestDialStepTicksPerDexterity);
        this.ticksLeft = ticks(Parameters.chestMinigamePrepareSeconds);
    }

    @Override
    void render() {
        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < Dial.MENU_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        drawRing(null);
        drawTally();
    }

    @Override
    void tickRunning(Player player) {
        if (phase == Phase.TURN) {
            if (!dial.advance()) {
                miss(player, "Too late");
                return;
            }
            drawRing(null);
            return;
        }
        if (--ticksLeft <= 0) {
            spin(player);
        }
    }

    private void spin(Player player) {
        dial.spin(random, Parameters.chestDialMinStepTicks + slowerSteps,
                Parameters.chestDialMaxStepTicks + slowerSteps, Parameters.chestDialZoneSteps);
        phase = Phase.TURN;
        // A stack of one shows no number, so the caps sit in hotbar order and the key to press is gold.
        int[] caps = Dial.keySlots();
        for (int key = 1; key <= Dial.KEYS; key++) {
            boolean press = key == dial.key();
            inventory.setItem(caps[key - 1], named(press ? Material.GOLD_NUGGET : Material.IRON_NUGGET, key,
                    ThieveryTexts.gui(press ? ThieveryTexts.WARN + "Hover here and press " + key
                            : ThieveryTexts.MUTED + "Key " + key)));
        }
        drawRing(null);
        show(player, ThieveryTexts.WARN + "Press " + dial.key() + " in the green", BarColor.YELLOW);
        progress(set / (double) tumblers);
    }

    @Override
    void click(Player player, InventoryClickEvent event) {
        if (phase != Phase.TURN || event.getClick() != ClickType.NUMBER_KEY) {
            return;
        }
        int key = event.getHotbarButton() + 1;
        if (key < 1 || key > Dial.KEYS) {
            return;
        }
        int lag = Math.min(Parameters.chestDialMaxLagTicks, Math.round(player.getPing() / 50.0f));
        Dial.Press press = dial.press(key, lag);
        if (press != Dial.Press.HIT) {
            miss(player, press == Dial.Press.EARLY ? "Too soon" : press == Dial.Press.LATE ? "Too late" : "Wrong key");
            return;
        }
        set++;
        drawRing(Material.LIME_STAINED_GLASS_PANE);
        drawTally();
        if (set >= tumblers) {
            solve(player);
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.7f, 1.6f);
        pause(player, ThieveryTexts.SUCCESS + "Tumbler set", BarColor.GREEN);
    }

    private void miss(Player player, String reason) {
        misses++;
        drawRing(Material.RED_STAINED_GLASS_PANE);
        drawTally();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        if (misses >= Parameters.chestMinigameMistakesToFail) {
            fail(player);
            return;
        }
        pause(player, ThieveryTexts.ERROR + reason, BarColor.RED);
    }

    private void pause(Player player, String text, BarColor colour) {
        phase = Phase.PAUSE;
        ticksLeft = PAUSE_TICKS;
        show(player, text, colour);
        progress(set / (double) tumblers);
    }

    /** Draws the ring, the pick and the green zone. {@code zone} overrides the zone colour to flash a result. */
    private void drawRing(Material zone) {
        boolean turning = phase != Phase.PREPARE;
        ItemStack ring = pane(Material.GRAY_STAINED_GLASS_PANE);
        ItemStack green = pane(zone == null ? Material.CYAN_STAINED_GLASS_PANE : zone);
        ItemStack pick = named(Material.TRIPWIRE_HOOK, 1, ThieveryTexts.gui(ThieveryTexts.WHITE + "Pick"));
        for (int step = 0; step < Dial.STEPS; step++) {
            ItemStack shown = ring;
            if (turning && step == dial.position()) {
                shown = pick;
            } else if (turning && dial.inZone(step)) {
                shown = green;
            }
            inventory.setItem(Dial.slotOf(step), shown);
        }
    }

    /** Set tumblers down the right edge, mistakes down the left. */
    private void drawTally() {
        for (int row = 0; row < Math.min(Dial.ROWS, tumblers); row++) {
            inventory.setItem(row * PinGrid.MENU_COLUMNS + TUMBLER_COLUMN, named(
                    row < set ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE, 1,
                    ThieveryTexts.gui(ThieveryTexts.MUTED + "Tumbler " + (row + 1) + " of " + tumblers)));
        }
        for (int row = 0; row < Math.min(Dial.ROWS, misses); row++) {
            inventory.setItem(row * PinGrid.MENU_COLUMNS + MISTAKE_COLUMN, named(Material.RED_STAINED_GLASS_PANE, 1,
                    ThieveryTexts.gui(ThieveryTexts.ERROR + "Slip " + (row + 1))));
        }
    }

    @Override
    int mistakes() {
        return misses;
    }

    @Override
    void showFailure() {
        // The red zone flash and the slips down the left edge already show what went wrong.
    }
}
