package net.tfminecraft.thievery.player;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

import net.tfminecraft.thievery.door.LockMinigameManager;
import net.tfminecraft.thievery.player.PickpocketSession;
import net.tfminecraft.thievery.player.PlayerTargetData;
import net.tfminecraft.thievery.loader.PickpocketLoader;
import net.tfminecraft.thievery.steal.PickpocketReference;
import net.tfminecraft.thievery.steal.StealManager;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.player.GuildAccessCooldown;
import net.tfminecraft.thievery.player.PlayerInteractCooldown;
import net.tfminecraft.thievery.steal.PlayerSlotMap;
import net.tfminecraft.thievery.steal.RobberyUtil;
import net.tfminecraft.thievery.steal.StealBudget;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.utils.EvilRpPlays;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public class PickpocketManager implements Listener {

    private final LockMinigameManager minigames;
    private final PlayerTargetDataManager targetDataManager = new PlayerTargetDataManager();
    private final Set<UUID> awaitingTarget = new java.util.HashSet<>();
    private final Map<UUID, PickpocketSession> sessionsByPickpocket = new HashMap<>();

    public PickpocketManager(LockMinigameManager minigames) {
        this.minigames = minigames;
    }

    public void startAwaitingTarget(Player pickpocket) {
        endSession(pickpocket.getUniqueId(), false);
        awaitingTarget.add(pickpocket.getUniqueId());
        pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Right-click a player within "
                + PickpocketLoader.getMaxDistance() + " blocks to pickpocket them."));
    }

    public boolean isAwaitingTarget(UUID pickpocketId) {
        return awaitingTarget.contains(pickpocketId);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player pickpocket = event.getPlayer();
        UUID pickpocketId = pickpocket.getUniqueId();

        if (!awaitingTarget.contains(pickpocketId)) {
            return;
        }
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Player victim)) {
            return;
        }
        if (!PlayerInteractCooldown.tryAcquire(pickpocketId)) {
            return;
        }
        if (victim.getUniqueId().equals(pickpocketId)) {
            pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You cannot pickpocket yourself."));
            return;
        }
        if (!RobberyUtil.isWithinRange(pickpocket, victim, PickpocketLoader.getMaxDistance())) {
            pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "They are too far away."));
            return;
        }
        if (minigames.isPlaying(pickpocketId)) {
            pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your hands are already busy."));
            return;
        }

        if (onGuildCooldown(pickpocket, targetDataManager.load(victim.getUniqueId()))) {
            return;
        }

        endSession(pickpocketId, false);
        // The pocket opens only once the pickpocket ring is filled.
        if (minigames.startPickpocket(pickpocket, victim, () -> openPocket(pickpocket, victim))) {
            awaitingTarget.remove(pickpocketId);
        }
    }

    private void openPocket(Player pickpocket, Player victim) {
        UUID pickpocketId = pickpocket.getUniqueId();
        // The mark may have walked off, or a guildmate picked them, while the ring was being filled.
        if (!victim.isOnline() || !RobberyUtil.isWithinRange(pickpocket, victim, PickpocketLoader.getMaxDistance())) {
            pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your mark is out of reach."));
            return;
        }
        PlayerTargetData targetData = targetDataManager.load(victim.getUniqueId());
        if (onGuildCooldown(pickpocket, targetData)) {
            return;
        }

        long now = System.currentTimeMillis();
        GuildAccessCooldown.recordAccessMillis(targetData.getPickpocketAccessMap(), pickpocketId, now);
        targetDataManager.save(targetData);

        StealGui.Layout layout = StealGui.Layout.create(PlayerSlotMap.MAIN_INV_SLOT_COUNT);
        StealBudget budget = new StealBudget(PickpocketLoader.getBudget());
        PickpocketSession session = new PickpocketSession(pickpocketId, victim.getUniqueId(), budget, layout);
        sessionsByPickpocket.put(pickpocketId, session);
        EvilRpPlays.record(pickpocket);

        PickpocketReference reference = new PickpocketReference(session, () -> sessionsByPickpocket.remove(pickpocketId));
        String title = reference.buildTitle(pickpocket);
        Inventory gui = StealGui.buildHiddenGui(reference.getHolder(), session.getLayout(), title);
        StealManager.getInstance().openSession(pickpocket, reference, gui);
    }

    /** Tells the thief and returns true when they or their guild picked this victim too recently. */
    private static boolean onGuildCooldown(Player pickpocket, PlayerTargetData targetData) {
        if (!GuildAccessCooldown.isOnCooldownMillis(targetData.getPickpocketAccessMap(), pickpocket,
                PickpocketLoader.getCooldownMillis())) {
            return false;
        }
        long remaining = GuildAccessCooldown.getMillisRemainingMillis(targetData.getPickpocketAccessMap(),
                pickpocket, PickpocketLoader.getCooldownMillis());
        pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your guild must wait "
                + GuildAccessCooldown.formatRemaining(remaining)
                + " before targeting them again."));
        return true;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        awaitingTarget.remove(playerId);
        PlayerInteractCooldown.clear(playerId);
        endSession(playerId, false);
    }

    private void endSession(UUID pickpocketId, boolean closeInventory) {
        sessionsByPickpocket.remove(pickpocketId);
        StealManager.getInstance().endSession(pickpocketId, closeInventory);
    }
}
