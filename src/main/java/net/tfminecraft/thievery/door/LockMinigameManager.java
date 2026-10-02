package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * Runs the lock minigame that must be solved before a chest lockpick session opens: the pin grid or, for
 * {@code dial-chance} of locks, the lockpick dial. Handles the menu events, the fail cooldown and penalties.
 */
public class LockMinigameManager implements Listener {

    /** A lock minigame forced by staff testing; {@code null} means the configured random choice. */
    public enum Mode {
        GRID,
        DIAL
    }

    private final LockPickManager lockPickManager;
    private final Random random;
    private final Map<UUID, LockMinigame> games = new HashMap<>();

    public LockMinigameManager(LockPickManager lockPickManager) {
        this(lockPickManager, new Random());
    }

    LockMinigameManager(LockPickManager lockPickManager, Random random) {
        this.lockPickManager = lockPickManager;
        this.random = random;
    }

    public static String targetId(Location location) {
        if (location == null || location.getWorld() == null) {
            return "";
        }
        return "chest:" + location.getWorld().getName()
                + ":" + location.getBlockX()
                + ":" + location.getBlockY()
                + ":" + location.getBlockZ();
    }

    /**
     * Opens a lock minigame, then passes the number of mistakes to {@code onSolved} once it is solved. Passes 0
     * straight away when the minigame is off. Returns false when the thief is still on the fail cooldown for
     * this lock.
     */
    public boolean start(Player player, Block target, IntConsumer onSolved) {
        return start(player, target, null, onSolved);
    }

    /** As {@link #start(Player, Block, IntConsumer)}, but a forced {@code mode} runs even when the minigame is off. */
    public boolean start(Player player, Block target, Mode mode, IntConsumer onSolved) {
        if (mode == null && !Parameters.chestMinigameEnabled) {
            onSolved.accept(0);
            return true;
        }
        UUID playerId = player.getUniqueId();
        String targetId = targetId(target.getLocation());
        if (lockPickManager.isOnCooldown(playerId, targetId)) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your hands are still shaking. Try this lock again in "
                    + lockPickManager.getCooldownRemainingSeconds(playerId, targetId) + "s."));
            return false;
        }
        cancel(playerId);

        int dexterity = RiskCalculator.getDexterity(player);
        LockMinigame game;
        boolean dial = mode == null ? random.nextDouble() < Parameters.chestDialChance : mode == Mode.DIAL;
        if (dial) {
            game = new DialGame(this, playerId, target, targetId, random, dexterity, onSolved);
        } else {
            PinGrid grid = new PinGrid(Parameters.chestMinigameRows, Parameters.chestMinigameColumns,
                    Parameters.chestMinigamePins, random);
            int recallTicks = LockMinigame.ticks(Parameters.chestMinigameRecallSeconds
                    + dexterity * Parameters.chestMinigameRecallSecondsPerDexterity);
            game = new PinGridGame(this, playerId, target, targetId, grid, recallTicks, onSolved);
        }
        game.render();
        LockMinigame started = game;
        game.task = Bukkit.getScheduler().runTaskTimer(Thievery.getInstance(), () -> tick(player, started), 1L, 1L);
        games.put(playerId, game);
        player.openInventory(game.inventory);
        game.bar.addPlayer(player);
        return true;
    }

    public boolean isPicking(Block target) {
        for (LockMinigame game : games.values()) {
            if (game.target.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Ends a game without a penalty, for example on reload or shutdown. */
    public void cancel(UUID playerId) {
        LockMinigame game = games.get(playerId);
        if (game == null) {
            return;
        }
        finish(game);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.getOpenInventory().getTopInventory().getHolder() == game) {
            player.closeInventory();
        }
    }

    public void cancelAll() {
        for (UUID playerId : new ArrayList<>(games.keySet())) {
            cancel(playerId);
        }
    }

    void tick(Player player, LockMinigame game) {
        if (games.get(game.playerId) != game) {
            return;
        }
        if (!player.isOnline() || !(game.target.getState() instanceof Container)) {
            cancel(game.playerId);
            return;
        }
        if (game.outcome == LockMinigame.Outcome.NONE) {
            game.tickRunning(player);
            return;
        }
        if (--game.endTicks <= 0) {
            cancel(game.playerId);
            if (game.outcome == LockMinigame.Outcome.SOLVED) {
                game.onSolved.accept(game.mistakes());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof LockMinigame game)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || games.get(player.getUniqueId()) != game) {
            return;
        }
        if (game.outcome == LockMinigame.Outcome.NONE) {
            game.click(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof LockMinigame) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        LockMinigame game = games.get(player.getUniqueId());
        if (game == null || event.getInventory().getHolder() != game) {
            return;
        }
        // A solved lock still opens once the short success display ends.
        if (game.outcome != LockMinigame.Outcome.SOLVED) {
            finish(game);
            if (game.outcome == LockMinigame.Outcome.NONE) {
                penalise(player, game);
            }
        }
    }

    void penalise(Player player, LockMinigame game) {
        lockPickManager.applyCooldown(game.playerId, game.targetId);
        if (random.nextDouble() < Parameters.chestMinigameFailBreakChance && breakLockpick(player)) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "The pins slip and your lockpick snaps!"));
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.8f, 1f);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "The pins slip back into place."));
        }
    }

    private void finish(LockMinigame game) {
        games.remove(game.playerId);
        game.task.cancel();
        game.bar.removeAll();
    }

    private static boolean breakLockpick(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            return false;
        }
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        return true;
    }
}
