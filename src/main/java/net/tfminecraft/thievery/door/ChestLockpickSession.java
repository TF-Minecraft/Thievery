package net.tfminecraft.thievery.door;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;

import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.LockpickDefinition;
import net.tfminecraft.thievery.steal.session.HiddenStealSession;
import net.tfminecraft.thievery.door.DoorLockpick;
import net.tfminecraft.thievery.steal.StealBudget;
import net.tfminecraft.thievery.steal.StealGui;

public class ChestLockpickSession extends HiddenStealSession {

    private final UUID thiefId;
    private final Block chestBlock;
    private final LockpickDefinition lockpickDef;
    private final int seizedCount;
    private final LockTypeProfile lockType;
    private final SeizedPins seizedPins;
    private final Set<Integer> markedGuiSlots = new HashSet<>();
    private int successfulClueDrops;
    private boolean lockpickBroken;

    public ChestLockpickSession(UUID thiefId, Block chestBlock, LockpickDefinition lockpickDef, int seizedCount,
            Inventory chestInventory, String targetKey, LockTypeProfile lockType) {
        super(new StealBudget(lockpickDef.getCapacity() * (lockType == null
                ? LockTypeProfile.IDENTITY.budgetMultiplier()
                : lockType.budgetMultiplier())),
                StealGui.Layout.create(chestInventory.getSize()), targetKey);
        this.thiefId = thiefId;
        this.chestBlock = chestBlock;
        this.lockpickDef = lockpickDef;
        this.lockType = lockType == null ? LockTypeProfile.IDENTITY : lockType;
        this.seizedPins = new SeizedPins(getLayout().getLogicalSlotToGuiSlot().values(), PinGrid.MENU_COLUMNS);
        // The first probe is always clear, so at most every other slot can hold a pin.
        int slots = getLayout().getLogicalSlotToGuiSlot().size();
        this.seizedCount = Math.max(0, Math.min(seizedCount, slots - 1));
    }

    public UUID getThiefId() {
        return thiefId;
    }

    public static double computeSuccessChance(int dexterity, double lockpickStrength) {
        return DoorLockpick.computeSuccessChance(dexterity, lockpickStrength, Parameters.chestBaseSuccessChance);
    }

    /**
     * Seized pins hidden in a chest: the old break chance spread over the chest's slots, scaled by lock type,
     * plus extra pins for each wrong cell on the pin grid.
     */
    public static int computeSeizedCount(int dexterity, double lockpickStrength, LockTypeProfile lockType,
            int slots, int gridMistakes) {
        double breakChance = 1.0 - computeSuccessChance(dexterity, lockpickStrength);
        double fromSkill = slots * Parameters.chestSeizedDensity * breakChance * lockType.breakChanceMultiplier();
        return (int) Math.round(fromSkill) + Math.max(0, gridMistakes) * Parameters.chestSeizedPerGridMistake;
    }

    public java.util.Set<Integer> getRevealedChestSlots() {
        java.util.Set<Integer> chestSlots = new java.util.HashSet<>();
        for (int guiSlot : getRevealedGuiSlots()) {
            Integer chestSlot = getLayout().getLogicalForGui(guiSlot);
            if (chestSlot != null) {
                chestSlots.add(chestSlot);
            }
        }
        return chestSlots;
    }

    public double getCapacityRemaining() {
        return getBudget().getRemaining();
    }

    public void addCapacityUsed(double value) {
        getBudget().addUsed(value);
    }

    public int getSuccessfulClueDrops() {
        return successfulClueDrops;
    }

    public void incrementSuccessfulClueDrops() {
        successfulClueDrops++;
    }

    public Block getChestBlock() {
        return chestBlock;
    }

    public LockpickDefinition getLockpickDef() {
        return lockpickDef;
    }

    public int getSeizedCount() {
        return seizedCount;
    }

    public SeizedPins getSeizedPins() {
        return seizedPins;
    }

    public boolean isMarked(int guiSlot) {
        return markedGuiSlots.contains(guiSlot);
    }

    /** Marks or unmarks a hidden slot as a suspected seized pin. Returns whether it is now marked. */
    public boolean toggleMarked(int guiSlot) {
        if (markedGuiSlots.remove(guiSlot)) {
            return false;
        }
        markedGuiSlots.add(guiSlot);
        return true;
    }

    /** Seized pins not yet marked, like a minesweeper mine counter. */
    public int getUnmarkedSeizedCount() {
        return Math.max(0, seizedCount - markedGuiSlots.size());
    }

    public LockTypeProfile getLockType() {
        return lockType;
    }

    public boolean isLockpickBroken() {
        return lockpickBroken;
    }

    public void markLockpickBroken() {
        lockpickBroken = true;
    }
}
