package net.tfminecraft.thievery.door;

import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * A chest lock or pickpocket minigame, played on a ring floating in front of the thief, who is held still.
 * Subclasses draw and run the puzzle; this class holds the boss bar and the end-of-game pause, and reports the
 * outcome to {@link LockMinigameManager}.
 */
public abstract class LockMinigame {

    static final int SOLVED_TICKS = 15;
    static final int FAILED_TICKS = 30;

    /** What the thief is told when the attempt is interrupted or ends; bar titles have no full stop. */
    record Wording(String flinched, String pulledAway, String gaveUp, String solved, String failed) {}

    static final Wording LOCK = new Wording("You flinch and lose the pins.", "You were pulled away from the lock.",
            "You ease the pick back out.", "The lock gives way", "The pins slip");

    enum Outcome {
        NONE,
        SOLVED,
        FAILED
    }

    final LockMinigameManager manager;
    final UUID playerId;
    /** The chest being picked; null for a pickpocket. */
    final Block target;
    final String targetId;
    final IntConsumer onSolved;
    final BossBar bar;
    /** The thief; kept so the game is always taken down for the same player it was opened for. */
    Player player;
    /** The lockpick in hand when the game began; a failed pick breaks it only if it is still the one held. */
    ItemStack pick;
    BukkitTask task;
    Outcome outcome = Outcome.NONE;
    int endTicks;
    /** The thief ran a command while the game was on, so a teleport that follows is their own doing. */
    boolean ranCommand;

    LockMinigame(LockMinigameManager manager, UUID playerId, Block target, String targetId, IntConsumer onSolved) {
        this.manager = manager;
        this.playerId = playerId;
        this.target = target;
        this.targetId = targetId;
        this.onSolved = onSolved;
        this.bar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID);
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
    abstract void input(Player player, Input input);

    Wording wording() {
        return LOCK;
    }

    /** Whether the chest has gone, so there is nothing left to pick. */
    boolean targetGone() {
        // No snapshot: a full chest's snapshot copies every item, and this runs every tick.
        return !(target.getState(false) instanceof Container);
    }

    /** A failed attempt: the lock fail cooldown and perhaps a snapped pick. */
    void penalise(Player player) {
        manager.penalise(player, this);
    }

    void solve(Player player) {
        outcome = Outcome.SOLVED;
        endTicks = SOLVED_TICKS;
        status(ThieveryTexts.SUCCESS + wording().solved(), BarColor.GREEN);
        showOutcome(player);
        player.playSound(player.getLocation(), solvedSound(), 0.8f, 1.2f);
    }

    void fail(Player player) {
        outcome = Outcome.FAILED;
        endTicks = FAILED_TICKS;
        status(ThieveryTexts.ERROR + wording().failed(), BarColor.RED);
        showOutcome(player);
        penalise(player);
    }

    Sound solvedSound() {
        return Sound.BLOCK_IRON_TRAPDOOR_OPEN;
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
