package net.tfminecraft.thievery.door;

import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * A chest lock minigame shown in its own menu. Subclasses run the puzzle; this class holds the shared menu,
 * boss bar and end-of-game display, and reports the outcome to {@link LockMinigameManager}.
 */
public abstract class LockMinigame implements InventoryHolder {

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
    final Inventory inventory;
    final BossBar bar;
    BukkitTask task;
    Outcome outcome = Outcome.NONE;
    int endTicks;

    // Keep the existing legacy text representation for inventory and boss bar titles.
    @SuppressWarnings("deprecation")
    LockMinigame(LockMinigameManager manager, UUID playerId, Block target, String targetId, int menuSize,
            IntConsumer onSolved) {
        this.manager = manager;
        this.playerId = playerId;
        this.target = target;
        this.targetId = targetId;
        this.onSolved = onSolved;
        this.inventory = Bukkit.createInventory(this, menuSize, ThieveryTexts.gui(PREPARE_TEXT));
        this.bar = Bukkit.createBossBar(ThieveryTexts.msg(PREPARE_TEXT), BarColor.WHITE, BarStyle.SOLID);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Draws the opening menu. */
    abstract void render();

    /** Advances the puzzle by one tick while it is still being played. */
    abstract void tickRunning(Player player);

    /** Handles a click in the menu while the puzzle is still being played. The click is already cancelled. */
    abstract void click(Player player, InventoryClickEvent event);

    /** Mistakes made so far; each one seizes extra pins in the chest. */
    abstract int mistakes();

    /** Shows what the thief missed once the puzzle is failed. */
    abstract void showFailure();

    void solve(Player player) {
        outcome = Outcome.SOLVED;
        endTicks = SOLVED_TICKS;
        show(player, ThieveryTexts.SUCCESS + "The lock gives way", BarColor.GREEN);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.8f, 1.2f);
    }

    void fail(Player player) {
        outcome = Outcome.FAILED;
        endTicks = FAILED_TICKS;
        showFailure();
        show(player, ThieveryTexts.ERROR + "The pins slip", BarColor.RED);
        manager.penalise(player, this);
    }

    /** Sets the boss bar and, while this menu is open, the menu title. */
    // Keep the existing legacy text representation for inventory and boss bar titles.
    @SuppressWarnings("deprecation")
    void show(Player player, String text, BarColor colour) {
        bar.setTitle(ThieveryTexts.msg(text));
        bar.setColor(colour);
        bar.setProgress(1.0);
        if (player.getOpenInventory().getTopInventory() == inventory) {
            player.getOpenInventory().setTitle(ThieveryTexts.gui(text));
        }
    }

    void progress(double fraction) {
        bar.setProgress(Math.max(0.0, Math.min(1.0, fraction)));
    }

    static ItemStack pane(Material material) {
        return named(material, 1, " ");
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    static ItemStack named(Material material, int amount, String name) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    static int ticks(double seconds) {
        return Math.max(1, (int) Math.round(seconds * 20));
    }
}
