package net.tfminecraft.thievery.steal.source;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.grave.Grave;
import net.tfminecraft.rpcharacters.grave.GraveManager;
import net.tfminecraft.thievery.steal.PlayerSlotMap;

public final class GraveStealSource implements StealSource {

	private final Grave grave;

	public GraveStealSource(Grave grave) {
		this.grave = grave;
	}

	@Override
	public ItemStack getItem(int logicalSlot) {
		if (logicalSlot < 0 || logicalSlot >= PlayerSlotMap.TOTAL_LOGICAL_SLOTS) {
			return null;
		}
		return grave.getItem(logicalSlot);
	}

	@Override
	public void setItem(int logicalSlot, ItemStack item) {
		if (logicalSlot < 0 || logicalSlot >= PlayerSlotMap.TOTAL_LOGICAL_SLOTS) {
			return;
		}
		grave.setItem(logicalSlot, item);
	}

	public void flush() {
		grave.flush();
		GraveManager.get().removeIfEmpty(grave);
	}

	public Grave getGrave() {
		return grave;
	}
}
