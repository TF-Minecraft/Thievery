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

    private final PlayerTargetDataManager targetDataManager = new PlayerTargetDataManager();
    private final Set<UUID> awaitingTarget = new java.util.HashSet<>();
    private final Map<UUID, PickpocketSession> sessionsByPickpocket = new HashMap<>();

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
            pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "That player is too far away."));
            return;
        }

        PlayerTargetData targetData = targetDataManager.load(victim.getUniqueId());
        if (GuildAccessCooldown.isOnCooldownMillis(targetData.getPickpocketAccessMap(), pickpocket,
                PickpocketLoader.getCooldownMillis())) {
            long remaining = GuildAccessCooldown.getMillisRemainingMillis(targetData.getPickpocketAccessMap(),
                    pickpocket, PickpocketLoader.getCooldownMillis());
            pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your guild must wait "
                    + GuildAccessCooldown.formatRemaining(remaining)
                    + " before targeting this player again."));
            return;
        }

        awaitingTarget.remove(pickpocketId);
        endSession(pickpocketId, false);

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
