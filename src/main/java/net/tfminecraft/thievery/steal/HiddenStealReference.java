package net.tfminecraft.thievery.steal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.steal.session.HiddenStealSession;
import net.tfminecraft.thievery.steal.session.StealSession;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.thievery.steal.StealItemDisplay;
import net.tfminecraft.thievery.steal.source.StealSource;
import net.tfminecraft.thievery.steal.StealTakeHandler;

public abstract class HiddenStealReference extends StealReference {

    protected HiddenStealReference(java.util.UUID thiefId, net.tfminecraft.thievery.steal.StealGuiHolder.Kind kind) {
        super(thiefId, kind);
    }

    protected HiddenStealSession hiddenSession() {
        return (HiddenStealSession) getSession();
    }

    @Override
    protected void handleStealClick(InventoryClickEvent event, Player thief) {
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        HiddenStealSession session = hiddenSession();
        ItemStack clickedItem = event.getCurrentItem();
        int guiSlot = event.getSlot();
        Inventory guiInv = event.getView().getTopInventory();

        if (StealGui.isUnknownPane(clickedItem) && !session.isRevealed(guiSlot)) {
            revealSlot(thief, guiInv, guiSlot);
            return;
        }

        if (clickedItem == null || StealGui.isNonInteractivePane(clickedItem)
                || StealItemDisplay.isStealPane(clickedItem) && !session.isRevealed(guiSlot)) {
            return;
        }

        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.SHIFT_LEFT) {
            return;
        }

        if (!session.isRevealed(guiSlot)) {
            return;
        }

        Integer logicalSlot = session.getLayout().getLogicalForGui(guiSlot);
        if (logicalSlot == null) {
            return;
        }

        if (!validateTarget(thief)) {
            return;
        }

        int takeSlot = resolveTakeSlot(logicalSlot);
        if (takeSlot < 0) {
            return;
        }

        StealSource source = getSource(thief);
        if (source == null) {
            return;
        }

        StealTakeHandler.performTake(thief, source, session.getBudget(), takeSlot, guiInv, guiSlot, click,
                clickedItem, () -> refreshGui(thief, guiInv), getTakeCallback(thief));
    }

    protected int resolveTakeSlot(int logicalSlot) {
        return logicalSlot;
    }

    protected void revealSlot(Player thief, Inventory guiInv, int guiSlot) {
        HiddenStealSession session = hiddenSession();
        if (session.isRevealed(guiSlot)) {
            return;
        }
        Integer logicalSlot = session.getLayout().getLogicalForGui(guiSlot);
        if (logicalSlot == null) {
            return;
        }
        if (!validateTarget(thief)) {
            return;
        }
        if (!onBeforeReveal(thief, guiInv, guiSlot)) {
            return;
        }
        session.markRevealed(guiSlot);
        onAfterReveal(thief, guiInv, guiSlot);
        refreshGui(thief, guiInv);
    }

    protected abstract boolean validateTarget(Player thief);

    protected abstract boolean onBeforeReveal(Player thief, Inventory guiInv, int guiSlot);

    protected abstract void onAfterReveal(Player thief, Inventory guiInv, int guiSlot);

    public abstract void refreshGui(Player thief, Inventory guiInv);

    protected abstract StealSource getSource(Player thief);

    protected StealTakeHandler.TakeCallback getTakeCallback(Player thief) {
        return null;
    }
}
