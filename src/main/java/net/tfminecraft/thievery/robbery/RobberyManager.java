package net.tfminecraft.thievery.robbery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.player.PlayerTargetDataManager;
import net.tfminecraft.thievery.player.PlayerTargetData;
import net.tfminecraft.thievery.robbery.RobberySession;
import net.tfminecraft.thievery.robbery.RobberySession.State;
import net.tfminecraft.thievery.steal.StealGuiHolder;
import net.tfminecraft.thievery.loader.RobberyLoader;
import net.tfminecraft.thievery.steal.RobberyStealReference;
import net.tfminecraft.thievery.steal.StealManager;
import net.tfminecraft.thievery.player.GuildAccessCooldown;
import net.tfminecraft.thievery.player.PlayerInteractCooldown;
import net.tfminecraft.thievery.steal.PlayerSlotMap;
import net.tfminecraft.thievery.steal.RobberyUtil;
import net.tfminecraft.thievery.steal.StealBudget;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public class RobberyManager implements Listener {

    private final PlayerTargetDataManager targetDataManager = new PlayerTargetDataManager();
    private final Set<UUID> awaitingTarget = new HashSet<>();
    private final Map<UUID, RobberySession> sessionsByRobber = new HashMap<>();
    private final Map<UUID, RobberyStealReference> activeReferences = new HashMap<>();
    private final Map<UUID, Location> victimRestraintLocations = new HashMap<>();

    public void startAwaitingTarget(Player robber) {
        endSessionForRobber(robber.getUniqueId(), false);
        awaitingTarget.add(robber.getUniqueId());
        robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Right-click a player within "
                + RobberyLoader.getMaxDistance() + " blocks to begin a robbery."));
    }

    public boolean acceptRobbery(Player victim) {
        RobberySession session = findPendingSessionForVictim(victim.getUniqueId());
        if (session == null) {
            victim.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You have no pending robbery request."));
            return false;
        }
        if (System.currentTimeMillis() > session.getAcceptDeadlineMs()) {
            victim.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "That robbery request has expired."));
            endSession(session, false);
            return false;
        }

        Player robber = Bukkit.getPlayer(session.getRobberId());
        if (robber == null || !robber.isOnline()) {
            victim.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "The robber is no longer available."));
            endSession(session, false);
            return false;
        }
        if (!RobberyUtil.isWithinRange(robber, victim, RobberyLoader.getMaxDistance())) {
            victim.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You are too far from the robber to accept."));
            return false;
        }

        PlayerTargetData targetData = targetDataManager.load(victim.getUniqueId());
        GuildAccessCooldown.recordAccess(targetData.getRobberyAccessMap(), robber.getUniqueId(),
                GuildAccessCooldown.today());
        targetDataManager.save(targetData);

        openActiveRobbery(robber, victim, session);
        return true;
    }

    public boolean isAwaitingTarget(UUID robberId) {
        return awaitingTarget.contains(robberId);
    }

    public boolean isVictimRestrained(UUID victimId) {
        return victimRestraintLocations.containsKey(victimId);
    }

    public boolean isRobberInActiveSession(UUID robberId) {
        RobberySession session = sessionsByRobber.get(robberId);
        return session != null && session.getState() == State.ACTIVE;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        Player robber = event.getPlayer();
        if (!awaitingTarget.contains(robber.getUniqueId())) {
            return;
        }
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Player victim)) {
            return;
        }
        if (!PlayerInteractCooldown.tryAcquire(robber.getUniqueId())) {
            return;
        }
        if (victim.getUniqueId().equals(robber.getUniqueId())) {
            robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You cannot rob yourself."));
            return;
        }
        if (!RobberyUtil.isWithinRange(robber, victim, RobberyLoader.getMaxDistance())) {
            robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "That player is too far away."));
            return;
        }

        PlayerTargetData targetData = targetDataManager.load(victim.getUniqueId());
        if (GuildAccessCooldown.isOnCooldown(targetData.getRobberyAccessMap(), robber,
                RobberyLoader.getCooldownDays())) {
            long remaining = GuildAccessCooldown.getMillisRemaining(targetData.getRobberyAccessMap(), robber,
                    RobberyLoader.getCooldownDays());
            robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your guild must wait "
                    + GuildAccessCooldown.formatRemaining(remaining)
                    + " before targeting this player again."));
            return;
        }

        awaitingTarget.remove(robber.getUniqueId());
        endSessionForRobber(robber.getUniqueId(), false);

        StealGui.Layout layout = StealGui.Layout.createRobbery(PlayerSlotMap.TOTAL_LOGICAL_SLOTS);
        StealBudget budget = new StealBudget(RobberyLoader.getBudget());
        RobberySession session = new RobberySession(robber.getUniqueId(), victim.getUniqueId(), budget, layout);
        session.setAcceptDeadlineMs(System.currentTimeMillis()
                + RobberyLoader.getAcceptTimeoutSeconds() * 1000L);
        sessionsByRobber.put(robber.getUniqueId(), session);

        sendAcceptMessage(victim, robber);
        robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Waiting for " + victim.getName()
                + " to accept your robbery request..."));

        Bukkit.getScheduler().runTaskLater(Thievery.getInstance(), () -> {
            RobberySession current = sessionsByRobber.get(robber.getUniqueId());
            if (current == session && current.getState() == State.PENDING_ACCEPT
                    && System.currentTimeMillis() > current.getAcceptDeadlineMs()) {
                endSession(session, false);
                if (robber.isOnline()) {
                    robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your robbery request timed out."));
                }
                if (victim.isOnline()) {
                    victim.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "The robbery request has expired."));
                }
            }
        }, RobberyLoader.getAcceptTimeoutSeconds() * 20L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        StealGuiHolder holder = StealManager.getStealGuiHolder(event.getView().getTopInventory());
        if (holder != null && holder.getKind() == StealGuiHolder.Kind.ROBBERY) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (isVictimRestrained(playerId) || isRobberInActiveSession(playerId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (isVictimRestrained(id) || isRobberInActiveSession(id)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (isVictimRestrained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (isVictimRestrained(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isVictimRestrained(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!isVictimRestrained(victim.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        awaitingTarget.remove(playerId);
        PlayerInteractCooldown.clear(playerId);

        RobberySession asRobber = sessionsByRobber.get(playerId);
        if (asRobber != null) {
            endSession(asRobber, false);
        }

        RobberySession asVictim = findActiveSessionForVictim(playerId);
        if (asVictim != null) {
            PlayerSlotMap.dropAllExceptIgnored(player);
            endSession(asVictim, false);
        } else if (isVictimRestrained(playerId)) {
            PlayerSlotMap.dropAllExceptIgnored(player);
            victimRestraintLocations.remove(playerId);
        }
    }

    private void openActiveRobbery(Player robber, Player victim, RobberySession session) {
        session.setState(State.ACTIVE);
        session.setActiveEndMs(System.currentTimeMillis() + RobberyLoader.getDurationSeconds() * 1000L);
        victimRestraintLocations.put(victim.getUniqueId(), victim.getLocation().clone());

        RobberyStealReference reference = new RobberyStealReference(session,
                () -> endSession(session, true));
        activeReferences.put(robber.getUniqueId(), reference);
        Inventory gui = reference.buildInventory(robber, victim);
        StealManager.getInstance().openSession(robber, reference, gui);

        robber.sendMessage(ThieveryTexts.msg(ThieveryTexts.CRITICAL + "Robbery started! Take what you can before time runs out."));
        victim.sendMessage(ThieveryTexts.msg(ThieveryTexts.CRITICAL + "You accepted the robbery and cannot move until it ends."));
    }

    private void sendAcceptMessage(Player victim, Player robber) {
        Component message = LegacyComponentSerializer.legacySection().deserialize(
                ThieveryTexts.msg(ThieveryTexts.CRITICAL + robber.getName() + ThieveryTexts.WARN
                        + " wants to rob you. Click "));
        Component accept = LegacyComponentSerializer.legacySection()
                .deserialize(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "[ACCEPT]"))
                .clickEvent(ClickEvent.runCommand("/robbery accept"))
                .hoverEvent(HoverEvent.showText(Component.text("Accept the robbery request")));
        Component timeout = LegacyComponentSerializer.legacySection().deserialize(
                ThieveryTexts.msg(ThieveryTexts.WARN + " within "
                        + RobberyLoader.getAcceptTimeoutSeconds() + " seconds."));
        victim.sendMessage(Component.empty().append(message).append(accept).append(timeout));
    }

    private RobberySession findPendingSessionForVictim(UUID victimId) {
        for (RobberySession session : sessionsByRobber.values()) {
            if (session.getVictimId().equals(victimId) && session.getState() == State.PENDING_ACCEPT) {
                return session;
            }
        }
        return null;
    }

    private RobberySession findActiveSessionForVictim(UUID victimId) {
        for (RobberySession session : sessionsByRobber.values()) {
            if (session.getVictimId().equals(victimId) && session.getState() == State.ACTIVE) {
                return session;
            }
        }
        return null;
    }

    private void endSessionForRobber(UUID robberId, boolean notify) {
        RobberySession session = sessionsByRobber.remove(robberId);
        activeReferences.remove(robberId);
        StealManager.getInstance().endSession(robberId, false);
        if (session != null) {
            releaseVictim(session.getVictimId());
            if (notify) {
                Player victim = Bukkit.getPlayer(session.getVictimId());
                if (victim != null && victim.isOnline()) {
                    victim.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "The robbery has ended."));
                }
            }
        }
    }

    private void endSession(RobberySession session, boolean notifyVictim) {
        sessionsByRobber.remove(session.getRobberId());
        activeReferences.remove(session.getRobberId());
        StealManager.getInstance().endSession(session.getRobberId(), false);
        releaseVictim(session.getVictimId());
        if (notifyVictim) {
            Player victim = Bukkit.getPlayer(session.getVictimId());
            if (victim != null && victim.isOnline()) {
                victim.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "The robbery has ended."));
            }
        }
    }

    private void releaseVictim(UUID victimId) {
        victimRestraintLocations.remove(victimId);
    }
}
