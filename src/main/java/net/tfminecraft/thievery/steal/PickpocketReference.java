package net.tfminecraft.thievery.steal;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.player.PickpocketSession;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.RiskSource;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.steal.StealGuiHolder;
import net.tfminecraft.thievery.loader.PickpocketLoader;
import net.tfminecraft.thievery.steal.session.StealSession;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.player.PickpocketVictimAlerter;
import net.tfminecraft.thievery.steal.PlayerSlotMap;
import net.tfminecraft.thievery.steal.source.PlayerStealSource;
import net.tfminecraft.thievery.steal.RobberyUtil;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.steal.source.StealSource;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public class PickpocketReference extends HiddenStealReference {

    private final PickpocketSession session;
    private final Runnable onClose;
    private BukkitTask distanceWatch;

    public PickpocketReference(PickpocketSession session, Runnable onClose) {
        super(session.getPickpocketId(), StealGuiHolder.Kind.PICKPOCKET);
        this.session = session;
        this.onClose = onClose;
    }

    @Override
    public StealSession getSession() {
        return session;
    }

    @Override
    public String buildTitle(Player thief) {
        int dexterity = RiskCalculator.getDexterity(thief);
        PlayerData thiefData = Thievery.getPlayerManager().get(thief.getUniqueId());
        return StealGui.forPickpocket(thiefData, dexterity, session.getBudget());
    }

    @Override
    public void onOpen(Player thief, Inventory gui) {
        thief.sendMessage(ThieveryTexts.msg("§o" + ThieveryTexts.CRITICAL
                + "Click a slot to probe their pockets."));
        startDistanceWatch(thief);
    }

    @Override
    public void onClose(Player thief) {
        cancelDistanceWatch();
        onClose.run();
    }

    @Override
    protected int resolveTakeSlot(int logicalSlot) {
        return PlayerSlotMap.toPlayerSlot(logicalSlot);
    }

    @Override
    protected boolean validateTarget(Player thief) {
        Player victim = Bukkit.getPlayer(session.getVictimId());
        if (victim == null || !victim.isOnline()) {
            thief.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your target is no longer available."));
            StealManager.getInstance().endSession(thief.getUniqueId(), true);
            return false;
        }
        return true;
    }

    @Override
    protected boolean onBeforeReveal(Player thief, Inventory guiInv, int guiSlot) {
        Player victim = Bukkit.getPlayer(session.getVictimId());
        if (victim == null) {
            return false;
        }

        int dexterity = RiskCalculator.getDexterity(thief);
        PlayerData thiefData = Thievery.getPlayerManager().get(thief.getUniqueId());
        thiefData.addRiskGain(dexterity, 0, RiskSource.PICKPOCKET);
        Database.savePlayerData(thiefData);

        PickpocketVictimAlerter.tryAlert(thief, victim, thiefData, session.getTargetKey(), dexterity);
        return true;
    }

    @Override
    protected void onAfterReveal(Player thief, Inventory guiInv, int guiSlot) {
        thief.playSound(thief.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.4f, 0.8f);
        thief.playSound(thief.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.3f, 1.2f);
    }

    @Override
    public void refreshGui(Player thief, Inventory guiInv) {
        Player victim = Bukkit.getPlayer(session.getVictimId());
        if (victim == null) {
            return;
        }
        PlayerData thiefData = Thievery.getPlayerManager().get(thief.getUniqueId());
        for (int revealedGuiSlot : session.getRevealedGuiSlots()) {
            Integer logicalSlot = session.getLayout().getLogicalForGui(revealedGuiSlot);
            if (logicalSlot == null) {
                continue;
            }
            ItemStack realItem = PlayerSlotMap.getPickpocketItem(victim, logicalSlot);
            StealGui.placeRevealedSlot(guiInv, revealedGuiSlot, realItem, session.getBudget(), thiefData);
        }
        updateTitle(thief);
    }

    @Override
    protected StealSource getSource(Player thief) {
        Player victim = Bukkit.getPlayer(session.getVictimId());
        if (victim == null) {
            return null;
        }
        return new PlayerStealSource(victim);
    }

    private void startDistanceWatch(Player pickpocket) {
        cancelDistanceWatch();
        Player victim = Bukkit.getPlayer(session.getVictimId());
        if (victim == null) {
            return;
        }
        UUID pickpocketId = pickpocket.getUniqueId();
        distanceWatch = Bukkit.getScheduler().runTaskTimer(Thievery.getInstance(), () -> {
            if (!StealManager.getInstance().hasSession(pickpocketId)) {
                cancelDistanceWatch();
                return;
            }
            if (!victim.isOnline()
                    || !RobberyUtil.isWithinRange(pickpocket, victim, PickpocketLoader.getMaxDistance())) {
                if (pickpocket.isOnline()) {
                    pickpocket.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your target moved too far away."));
                    pickpocket.closeInventory();
                }
                StealManager.getInstance().endSession(pickpocketId, false);
            }
        }, 20L, 20L);
    }

    private void cancelDistanceWatch() {
        if (distanceWatch != null) {
            distanceWatch.cancel();
            distanceWatch = null;
        }
    }
}
