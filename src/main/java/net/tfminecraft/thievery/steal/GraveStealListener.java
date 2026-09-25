package net.tfminecraft.thievery.steal;

import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.rpcharacters.grave.Grave;
import net.tfminecraft.rpcharacters.grave.GraveLootRules;
import net.tfminecraft.rpcharacters.grave.GraveManager;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.category.CategoryHandler;
import net.tfminecraft.thievery.category.ItemValue;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.steal.source.GraveStealSource;
import net.tfminecraft.thievery.utils.EvilRpPlays;
import net.tfminecraft.thievery.utils.ThieveryTexts;

public final class GraveStealListener implements Listener {

	private static final long STEAL_DEBOUNCE_MS = 250L;

	private final Map<UUID, Long> lastStealAt = new java.util.HashMap<>();
	private final Map<UUID, String> lastStealChest = new java.util.HashMap<>();

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		tryLoot(event.getPlayer(), event.getClickedBlock());
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
	public void onInventoryOpen(InventoryOpenEvent event) {
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}
		tryLoot(player, inventoryBlock(event.getInventory()));
	}

	private void tryLoot(Player player, Block block) {
		if (player == null || block == null) {
			return;
		}
		Grave grave = GraveManager.get().getAt(block);
		if (grave == null || grave.isOwner(player.getUniqueId())) {
			return;
		}
		String key = blockKey(block);
		long now = System.currentTimeMillis();
		Long last = lastStealAt.get(player.getUniqueId());
		if (last != null && now - last < STEAL_DEBOUNCE_MS
				&& key.equals(lastStealChest.get(player.getUniqueId()))) {
			return;
		}
		lastStealAt.put(player.getUniqueId(), now);
		lastStealChest.put(player.getUniqueId(), key);

		if (!GraveLootRules.canSteal(player, grave)) {
			return;
		}
		EvilRpPlays.record(player);
		loot(player, grave);
	}

	private void loot(Player player, Grave grave) {
		PlayerData thiefData = Thievery.getPlayerManager().get(player.getUniqueId());
		GraveStealSource source = new GraveStealSource(grave);
		int stacksTaken = 0;
		boolean inventoryFull = false;

		for (int slot = 0; slot < PlayerSlotMap.TOTAL_LOGICAL_SLOTS; slot++) {
			ItemStack realItem = source.getItem(slot);
			if (realItem == null || realItem.getType().isAir()) {
				continue;
			}

			if (ItemValue.isBundle(realItem)) {
				if (!ItemValue.hasStealableContents(thiefData, realItem, Double.POSITIVE_INFINITY)
						&& !CategoryHandler.canRevealItem(thiefData, realItem)) {
					continue;
				}
			} else if (!CategoryHandler.canRevealItem(thiefData, realItem)) {
				continue;
			}

			if (ItemValue.isBundle(realItem)
					&& ItemValue.hasStealableContents(thiefData, realItem, Double.POSITIVE_INFINITY)) {
				ItemValue.BundleTakeResult result = ItemValue.takeFromBundle(
						realItem, player, thiefData, Double.POSITIVE_INFINITY, ItemValue.BundleTakeMode.GREEDY);
				if (!result.isAnyTaken()) {
					if (StealTakeHandler.maxFitInPlayerInventory(player, realItem, 1) < 1) {
						inventoryFull = true;
						break;
					}
					continue;
				}
				if (result.isRemovedFromSource()) {
					source.setItem(slot, null);
				} else {
					source.setItem(slot, result.getUpdatedBundle());
				}
				stacksTaken++;
				continue;
			}

			int takeable = realItem.getAmount();
			if (takeable <= 0) {
				continue;
			}
			int fit = StealTakeHandler.maxFitInPlayerInventory(player, realItem, takeable);
			if (fit <= 0) {
				inventoryFull = true;
				break;
			}
			int takeAmount = Math.min(realItem.getAmount(), fit);
			ItemStack toGive = realItem.clone();
			toGive.setAmount(takeAmount);
			if (!player.getInventory().addItem(toGive).isEmpty()) {
				inventoryFull = true;
				break;
			}
			if (realItem.getAmount() <= takeAmount) {
				source.setItem(slot, null);
			} else {
				realItem.setAmount(realItem.getAmount() - takeAmount);
				source.setItem(slot, realItem);
			}
			stacksTaken++;
		}

		source.flush();

		if (stacksTaken > 0) {
			String summary = stacksTaken == 1 ? "1 stack" : stacksTaken + " stacks";
			player.sendMessage(ThieveryTexts.msg(ThieveryTexts.SUCCESS + "You took " + summary
					+ " from " + ownerName(grave) + "'s grave."));
			if (inventoryFull) {
				player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "Your inventory is full."));
			}
			return;
		}
		if (inventoryFull) {
			player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "You don't have enough inventory space!"));
			return;
		}
		player.sendMessage(ThieveryTexts.msg(ThieveryTexts.WARN + "There is nothing you can steal here."));
	}

	private static String ownerName(Grave grave) {
		if (grave.getOwner() == null) {
			return "someone";
		}
		String name = Bukkit.getOfflinePlayer(grave.getOwner()).getName();
		if (name == null || name.isBlank()) {
			return "someone";
		}
		return name;
	}

	private static String blockKey(Block block) {
		return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
	}

	private static Block inventoryBlock(Inventory inventory) {
		if (inventory == null) {
			return null;
		}
		InventoryHolder holder = inventory.getHolder();
		if (holder instanceof BlockState state) {
			return state.getBlock();
		}
		Location location = inventory.getLocation();
		return location != null ? location.getBlock() : null;
	}
}
