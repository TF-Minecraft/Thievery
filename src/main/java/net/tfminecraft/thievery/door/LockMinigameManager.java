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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * Runs the lock minigame that must be solved before a chest lockpick session opens: the pin grid dialog or, for
 * {@code dial-chance} of locks, the floating lockpick ring. Handles the fail cooldown, penalties, and everything
 * that can interrupt a pick: leaving, being hit, being moved, a crash, a reload.
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
    PinGridGame.GridScreens gridScreens = GridDialogs::show;
    RingDialGame.RingViews ringViews = (player, lockpick, tumblers, slips) ->
            RingView.open(Thievery.getInstance(), player, lockpick, tumblers, slips);

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
     * straight away when the minigame is off. Returns false when the thief cannot start: still on the fail
     * cooldown for this lock, or riding or gliding when the ring needs them still.
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
        boolean dial = mode == null ? random.nextDouble() < Parameters.chestDialChance : mode == Mode.DIAL;
        if (dial && (player.isInsideVehicle() || player.isGliding())) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You need both feet on the ground to work this lock."));
            return false;
        }
        cancel(playerId);

        int dexterity = RiskCalculator.getDexterity(player);
        LockMinigame game;
        if (dial) {
            ItemStack lockpick = player.getInventory().getItemInMainHand();
            game = new RingDialGame(this, playerId, target, targetId, random, lockpick, dexterity, ringViews, onSolved);
        } else {
            PinGrid grid = new PinGrid(Parameters.chestMinigameRows, Parameters.chestMinigameColumns,
                    Parameters.chestMinigamePins, random);
            int recallTicks = LockMinigame.ticks(Parameters.chestMinigameRecallSeconds
                    + dexterity * Parameters.chestMinigameRecallSecondsPerDexterity);
            game = new PinGridGame(this, playerId, target, targetId, grid, recallTicks, gridScreens, onSolved);
        }
        game.player = player;
        LockMinigame started = game;
        game.task = Bukkit.getScheduler().runTaskTimer(Thievery.getInstance(), () -> tick(player, started), 1L, 1L);
        games.put(playerId, game);
        game.bar.addPlayer(player);
        game.begin(player);
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

    public boolean isPlaying(UUID playerId) {
        return games.containsKey(playerId);
    }

    LockMinigame game(UUID playerId) {
        return games.get(playerId);
    }

    /** Ends a game without a penalty, for example on reload, shutdown or being pulled away. */
    public void cancel(UUID playerId) {
        LockMinigame game = games.get(playerId);
        if (game != null) {
            finish(game, game.player);
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

    /** The thief gave up: it counts as a failed attempt, the same as letting the pins slip. */
    void giveUp(Player player, LockMinigame game) {
        if (games.get(game.playerId) == game && game.outcome == LockMinigame.Outcome.NONE) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "You ease the pick back out."));
            game.fail(player);
        }
    }

    private LockMinigame playing(Player player) {
        LockMinigame game = games.get(player.getUniqueId());
        return game != null && game.outcome == LockMinigame.Outcome.NONE ? game : null;
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        LockMinigame game = playing(event.getPlayer());
        if (game != null) {
            game.input(event.getPlayer(), event.getInput());
        }
    }

    /** Walking out mid-pick counts as a failed attempt, so logging out is no free retry. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        LockMinigame game = playing(player);
        if (game != null) {
            penalise(player, game);
        }
        LockMinigame any = games.get(player.getUniqueId());
        if (any != null) {
            finish(any, player);
        }
    }

    /** Undoes a freeze left behind by a crash or restart mid-pick. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isPlaying(player.getUniqueId()) && LockFreeze.isFrozen(player)) {
            LockFreeze.release(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        LockMinigame game = playing(player);
        if (game != null && event.getFinalDamage() > 0) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You flinch and lose the pins."));
            game.fail(player);
        }
    }

    /** Being moved away mid-pick, by a command or another plugin, ends the attempt without a penalty. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isPlaying(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 1.0) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "You were pulled away from the lock."));
            cancel(player.getUniqueId());
        }
    }

    /**
     * Keeps a ring thief in place, so knockback, water or a nudge cannot drift them away; looking around is fine.
     * Only the horizontal position is held, so a thief who started mid-jump still lands instead of hovering until
     * the server kicks them for flying.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        LockMinigame game = games.get(event.getPlayer().getUniqueId());
        if (game == null || !game.holdsStill()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            Location held = from.clone();
            held.setY(to.getY());
            held.setYaw(to.getYaw());
            held.setPitch(to.getPitch());
            event.setTo(held);
        }
    }

    /**
     * Keeps the lockpick in hand while the ring is up: no scrolling the hotbar, dropping, swapping hands or moving
     * items, any of which could leave the solved lock with no pick to open it.
     */
    private boolean handsOnTheLock(org.bukkit.entity.HumanEntity player) {
        LockMinigame game = games.get(player.getUniqueId());
        return game != null && game.holdsStill();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHotbar(PlayerItemHeldEvent event) {
        if (handsOnTheLock(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (handsOnTheLock(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (handsOnTheLock(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** A frozen thief can still right-click a horse or a boat, which would carry them off with the ring. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.HumanEntity rider && handsOnTheLock(rider)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (handsOnTheLock(event.getWhoClicked())) {
            event.setCancelled(true);
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

    private void finish(LockMinigame game, Player player) {
        games.remove(game.playerId);
        game.task.cancel();
        game.bar.removeAll();
        game.cleanup(player);
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
