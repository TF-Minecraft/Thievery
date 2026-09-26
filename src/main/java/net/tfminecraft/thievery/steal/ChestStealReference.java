package net.tfminecraft.thievery.steal;

import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.door.ChestLockpickSession;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.player.RiskSource;
import net.tfminecraft.thievery.database.Database;
import net.tfminecraft.thievery.steal.StealGuiHolder;
import net.tfminecraft.thievery.steal.session.StealSession;
import net.tfminecraft.thievery.steal.source.ContainerStealSource;
import net.tfminecraft.thievery.clue.ClueDropper;
import net.tfminecraft.thievery.player.RiskCalculator;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.steal.StealItemDisplay;
import net.tfminecraft.thievery.steal.source.StealSource;
import net.tfminecraft.thievery.steal.StealTakeHandler;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public class ChestStealReference extends HiddenStealReference {

    private final ChestLockpickSession session;
    private final Runnable onClose;

    public ChestStealReference(ChestLockpickSession session, Runnable onClose) {
        super(session.getThiefId(), StealGuiHolder.Kind.CHEST);
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
        double lockpickStrength = session.getLockpickDef().getStrength();
        return StealGui.forChest(thiefData, dexterity, lockpickStrength, session.getBudget(),
                session.getNextRevealSuccessChance(), session.isLockpickBroken(),
                session.getLockType().criticalRisk());
    }

    @Override
    public void onOpen(Player thief, Inventory gui) {
        thief.sendMessage(ThieveryTexts.msg("§o" + ThieveryTexts.CRITICAL + "Click a slot to probe the container."));
    }

    @Override
    public void onClose(Player thief) {
        onClose.run();
    }

    @Override
    protected void revealSlot(Player thief, Inventory guiInv, int guiSlot) {
        if (session.isLockpickBroken()) {
            thief.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR
                    + "Your lockpick is broken. Take what you revealed or close the chest."));
            return;
        }
        super.revealSlot(thief, guiInv, guiSlot);
    }

    @Override
    protected boolean validateTarget(Player thief) {
        Block chestBlock = session.getChestBlock();
        if (!(chestBlock.getState() instanceof Container)) {
            StealManager.getInstance().endSession(thief.getUniqueId(), true);
            return false;
        }
        return true;
    }

    @Override
    protected boolean onBeforeReveal(Player thief, Inventory guiInv, int guiSlot) {
        int dexterity = RiskCalculator.getDexterity(thief);
        double lockpickStrength = session.getLockpickDef().getStrength();
        PlayerData thiefData = Thievery.getPlayerManager().get(thief.getUniqueId());
        thiefData.addRiskGain(dexterity, lockpickStrength, RiskSource.CHEST,
                session.getLockType().riskMultiplier());
        Database.savePlayerData(thiefData);

        if (Math.random() >= session.getNextRevealSuccessChance()) {
            breakLockpick(thief);
            session.markLockpickBroken();
            thief.playSound(thief.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            thief.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your lockpick broke!"));
            updateTitle(thief);
            return false;
        }
        return true;
    }

    @Override
    protected void onAfterReveal(Player thief, Inventory guiInv, int guiSlot) {
        thief.playSound(thief.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.4f, 0.8f);
        thief.playSound(thief.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.3f, 1.2f);
    }

    @Override
    public void refreshGui(Player thief, Inventory guiInv) {
        Block chestBlock = session.getChestBlock();
        if (!(chestBlock.getState() instanceof Container container)) {
            return;
        }
        Inventory chestInv = container.getInventory();
        PlayerData thiefData = Thievery.getPlayerManager().get(thief.getUniqueId());
        int dexterity = RiskCalculator.getDexterity(thief);
        double lockpickStrength = session.getLockpickDef().getStrength();
        StealItemDisplay.ChestCluePreviewContext cluePreview = buildCluePreviewContext(
                thief, thiefData, dexterity, lockpickStrength);

        for (int revealedGuiSlot : session.getRevealedGuiSlots()) {
            Integer chestSlot = session.getLayout().getLogicalForGui(revealedGuiSlot);
            if (chestSlot == null) {
                continue;
            }
            ItemStack realItem = chestInv.getItem(chestSlot);
            StealGui.placeRevealedSlot(guiInv, revealedGuiSlot, realItem, session.getBudget(),
                    thiefData, cluePreview);
        }
        updateTitle(thief);
    }

    @Override
    protected StealSource getSource(Player thief) {
        Block chestBlock = session.getChestBlock();
        if (!(chestBlock.getState() instanceof Container container)) {
            return null;
        }
        return new ContainerStealSource(container.getInventory());
    }

    @Override
    protected StealTakeHandler.TakeCallback getTakeCallback(Player thief) {
        int dexterity = RiskCalculator.getDexterity(thief);
        double lockpickStrength = session.getLockpickDef().getStrength();
        Block chestBlock = session.getChestBlock();
        return (taker, taken, valueTaken, fromBundle, chestSlot) -> {
            if (!(chestBlock.getState() instanceof Container container)) {
                return;
            }
            Inventory chestInv = container.getInventory();
            if (Cache.coreProtect) {
                Thievery.getCoreProtect().logContainerTransaction(taker.getName() + "_lockpick",
                        chestBlock.getLocation());
            }
            ClueDropper.tryDropChestClue(taker, session, chestBlock,
                    dexterity, lockpickStrength, valueTaken, fromBundle);
        };
    }

    private StealItemDisplay.ChestCluePreviewContext buildCluePreviewContext(Player player,
            PlayerData thiefData, int dexterity, double lockpickStrength) {
        thiefData.applyRiskDecay(dexterity);
        return new StealItemDisplay.ChestCluePreviewContext(
                player,
                dexterity,
                lockpickStrength,
                thiefData.getRisk(),
                session.getSuccessfulClueDrops(),
                session.getLockType().criticalRisk());
    }

    private void breakLockpick(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            return;
        }
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    }
}
