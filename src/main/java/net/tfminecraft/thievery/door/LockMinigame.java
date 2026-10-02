package net.tfminecraft.thievery.door;

import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * A chest lock minigame. Subclasses draw and run the puzzle; this class holds the boss bar and the end-of-game
 * pause, and reports the outcome to {@link LockMinigameManager}.
 */
public abstract class LockMinigame {

    static final int SOLVED_TICKS = 15;
    static final int FAILED_TICKS = 30;
    static final String PREPARE_TEXT = ThieveryTexts.MUTED + "Steady your hands...";

    enum Outcome {
        NONE,
        SOLVED,
        FAILED
    }

    final LockMinigameManager manager;
    final UUID playerId;
    final Block target;
    final String targetId;
    final IntConsumer onSolved;
    final BossBar bar;
    /** The thief; kept so the game is always taken down for the same player it was opened for. */
    Player player;
    BukkitTask task;
    Outcome outcome = Outcome.NONE;
    int endTicks;

    LockMinigame(LockMinigameManager manager, UUID playerId, Block target, String targetId, IntConsumer onSolved) {
        this.manager = manager;
        this.playerId = playerId;
        this.target = target;
        this.targetId = targetId;
        this.onSolved = onSolved;
        this.bar = Bukkit.createBossBar(ThieveryTexts.msg(PREPARE_TEXT), BarColor.WHITE, BarStyle.SOLID);
    }

    /** Opens the puzzle for the thief. */
    abstract void begin(Player player);

    /** Advances the puzzle by one tick while it is still being played. */
    abstract void tickRunning(Player player);

    /** Mistakes made so far; each one seizes an extra pin in the chest. */
    abstract int mistakes();

    /** Draws the solved or failed state; {@link #outcome} says which. */
    abstract void showOutcome(Player player);

    /** Takes the puzzle down. {@code player} is null when they have already left. */
    abstract void cleanup(Player player);

    /** Movement keys while the puzzle is open. */
    void input(Player player, Input input) {
    }

    /** Whether the thief must stay put while playing. */
    boolean holdsStill() {
        return false;
    }

    void solve(Player player) {
        outcome = Outcome.SOLVED;
        endTicks = SOLVED_TICKS;
        status(ThieveryTexts.SUCCESS + "The lock gives way", BarColor.GREEN);
        showOutcome(player);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.8f, 1.2f);
    }

    void fail(Player player) {
        outcome = Outcome.FAILED;
        endTicks = FAILED_TICKS;
        status(ThieveryTexts.ERROR + "The pins slip", BarColor.RED);
        showOutcome(player);
        manager.penalise(player, this);
    }

    void status(String text, BarColor colour) {
        bar.setTitle(ThieveryTexts.msg(text));
        bar.setColor(colour);
        bar.setProgress(1.0);
    }

    void progress(double fraction) {
        bar.setProgress(Math.max(0.0, Math.min(1.0, fraction)));
    }

    static int ticks(double seconds) {
        return Math.max(1, (int) Math.round(seconds * 20));
    }
}
