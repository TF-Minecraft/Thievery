package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * Runs the pin memory minigame that must be solved before a chest lockpick session opens.
 */
public class PinGridManager implements Listener {

    static final int SOLVED_TICKS = 15;
    static final int FAILED_TICKS = 30;
    static final int COUNTDOWN_SECONDS = 3;

    enum Phase {
        PREPARE,
        MEMORISE,
        RECALL,
        SOLVED,
        FAILED
    }

    final class Game implements InventoryHolder {
        final UUID playerId;
        final Block target;
        final String targetId;
        final PinGrid grid;
        final int recallTicks;
        final Runnable onSolved;
        final Inventory inventory;
        final BossBar bar;
        Phase phase = Phase.PREPARE;
        int phaseTicks;
        int ticksLeft;
        BukkitTask task;

        // Keep the existing legacy text representation for inventory and boss bar titles.
        @SuppressWarnings("deprecation")
        Game(UUID playerId, Block target, String targetId, PinGrid grid, int recallTicks, Runnable onSolved) {
            this.playerId = playerId;
            this.target = target;
            this.targetId = targetId;
            this.grid = grid;
            this.recallTicks = recallTicks;
            this.onSolved = onSolved;
            this.inventory = Bukkit.createInventory(this, grid.menuSize(), title(ThieveryTexts.MUTED + "Steady your hands..."));
            this.bar = Bukkit.createBossBar(ThieveryTexts.msg(ThieveryTexts.MUTED + "Steady your hands..."),
                    BarColor.WHITE, BarStyle.SOLID);
            setPhase(Phase.PREPARE, ticks(Parameters.chestMinigamePrepareSeconds));
        }

        void setPhase(Phase next, int duration) {
            phase = next;
            phaseTicks = duration;
            ticksLeft = duration;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final LockPickManager lockPickManager;
    private final Random random;
    private final Map<UUID, Game> games = new HashMap<>();

    public PinGridManager(LockPickManager lockPickManager) {
        this(lockPickManager, new Random());
    }

    PinGridManager(LockPickManager lockPickManager, Random random) {
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
     * Opens the pin grid, then runs {@code onSolved} once every pin is set. Runs it straight away when the
     * minigame is off. Returns false when the thief is still on the fail cooldown for this lock.
     */
    public boolean start(Player player, Block target, Runnable onSolved) {
        if (!Parameters.chestMinigameEnabled) {
            onSolved.run();
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

        PinGrid grid = new PinGrid(Parameters.chestMinigameRows, Parameters.chestMinigameColumns,
                Parameters.chestMinigamePins, random);
        int dexterity = RiskCalculator.getDexterity(player);
        int recallTicks = ticks(Parameters.chestMinigameRecallSeconds
                + dexterity * Parameters.chestMinigameRecallSecondsPerDexterity);
        Game game = new Game(playerId, target, targetId, grid, recallTicks, onSolved);
        render(game);
        game.task = Bukkit.getScheduler().runTaskTimer(Thievery.getInstance(), () -> tick(player, game), 1L, 1L);
        games.put(playerId, game);
        player.openInventory(game.inventory);
        game.bar.addPlayer(player);
        return true;
    }

    public boolean isPicking(Block target) {
        for (Game game : games.values()) {
            if (game.target.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** Ends a game without a penalty, for example on reload or shutdown. */
    public void cancel(UUID playerId) {
        Game game = games.get(playerId);
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

    void tick(Player player, Game game) {
        if (games.get(game.playerId) != game) {
            return;
        }
        if (!player.isOnline() || !(game.target.getState() instanceof Container)) {
            cancel(game.playerId);
            return;
        }
        game.ticksLeft--;
        if (game.phase == Phase.SOLVED || game.phase == Phase.FAILED) {
            if (game.ticksLeft <= 0) {
                cancel(game.playerId);
                if (game.phase == Phase.SOLVED) {
                    game.onSolved.run();
                }
            }
            return;
        }
        if (game.phase == Phase.RECALL) {
            if (game.ticksLeft <= 0) {
                fail(player, game);
                return;
            }
            if (game.ticksLeft % 20 == 0 && game.ticksLeft <= COUNTDOWN_SECONDS * 20) {
                game.bar.setColor(BarColor.RED);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f);
            }
        } else if (game.ticksLeft <= 0) {
            if (game.phase == Phase.PREPARE) {
                game.setPhase(Phase.MEMORISE, ticks(Parameters.chestMinigameMemoriseSeconds));
                showPhase(player, game, ThieveryTexts.WARN + "Memorise the pins", BarColor.YELLOW);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
            } else {
                game.setPhase(Phase.RECALL, game.recallTicks);
                showPhase(player, game, ThieveryTexts.SUCCESS + "Set the pins", BarColor.GREEN);
                player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.6f, 0.8f);
            }
        }
        game.bar.setProgress(Math.max(0.0, Math.min(1.0, game.ticksLeft / (double) game.phaseTicks)));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Game game)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || games.get(player.getUniqueId()) != game) {
            return;
        }
        if (game.phase != Phase.RECALL || event.getClickedInventory() != game.inventory) {
            return;
        }
        int cell = game.grid.cellAt(event.getSlot());
        switch (game.grid.pick(cell)) {
            case SET -> {
                game.inventory.setItem(game.grid.slotOf(cell), pane(Material.LIME_STAINED_GLASS_PANE));
                if (game.grid.isSolved()) {
                    game.setPhase(Phase.SOLVED, SOLVED_TICKS);
                    showPhase(player, game, ThieveryTexts.SUCCESS + "The lock gives way", BarColor.GREEN);
                    player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.8f, 1.2f);
                } else {
                    player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.6f, 1.6f);
                }
            }
            case MISS -> {
                game.inventory.setItem(game.grid.slotOf(cell), pane(Material.RED_STAINED_GLASS_PANE));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                if (game.grid.mistakes() >= Parameters.chestMinigameMistakesToFail) {
                    fail(player, game);
                }
            }
            case IGNORED -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Game) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Game game = games.get(player.getUniqueId());
        if (game == null || event.getInventory().getHolder() != game) {
            return;
        }
        switch (game.phase) {
            // A solved lock still opens once the short success display ends.
            case SOLVED -> {
            }
            case FAILED -> finish(game);
            default -> {
                finish(game);
                penalise(player, game);
            }
        }
    }

    private void fail(Player player, Game game) {
        game.setPhase(Phase.FAILED, FAILED_TICKS);
        for (int cell : game.grid.pins()) {
            if (!game.grid.isPicked(cell)) {
                game.inventory.setItem(game.grid.slotOf(cell), pane(Material.YELLOW_STAINED_GLASS_PANE));
            }
        }
        showPhase(player, game, ThieveryTexts.ERROR + "The pins slip", BarColor.RED);
        penalise(player, game);
    }

    private void penalise(Player player, Game game) {
        lockPickManager.applyCooldown(game.playerId, game.targetId);
        if (random.nextDouble() < Parameters.chestMinigameFailBreakChance && breakLockpick(player)) {
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "The pins slip and your lockpick snaps!"));
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.8f, 1f);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "The pins slip back into place."));
        }
    }

    private void finish(Game game) {
        games.remove(game.playerId);
        game.task.cancel();
        game.bar.removeAll();
    }

    // Keep the existing legacy text representation for inventory and boss bar titles.
    @SuppressWarnings("deprecation")
    private void showPhase(Player player, Game game, String text) {
        game.bar.setTitle(ThieveryTexts.msg(text));
        if (game.phase == Phase.MEMORISE) {
            for (int cell : game.grid.pins()) {
                game.inventory.setItem(game.grid.slotOf(cell), pane(Material.LIME_STAINED_GLASS_PANE));
            }
        } else if (game.phase == Phase.RECALL) {
            render(game);
        }
        if (player.getOpenInventory().getTopInventory() == game.inventory) {
            player.getOpenInventory().setTitle(title(text));
        }
    }

    private void showPhase(Player player, Game game, String text, BarColor color) {
        game.bar.setColor(color);
        game.bar.setProgress(1.0);
        showPhase(player, game, text);
    }

    private static void render(Game game) {
        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack hidden = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < game.grid.menuSize(); slot++) {
            game.inventory.setItem(slot, game.grid.cellAt(slot) < 0 ? filler : hidden);
        }
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

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private static ItemStack pane(Material material) {
        ItemStack pane = new ItemStack(material);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(" ");
        pane.setItemMeta(meta);
        return pane;
    }

    private static String title(String text) {
        return ThieveryTexts.gui(text);
    }

    static int ticks(double seconds) {
        return Math.max(1, (int) Math.round(seconds * 20));
    }
}
