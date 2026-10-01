package net.tfminecraft.thievery.door;

import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * Pin memory minigame: the lit pins show briefly, then hide, and the thief sets them all from memory before the
 * recall timer runs out. Modelled on the NoPixel thermite minigame.
 */
final class PinGridGame extends LockMinigame {

    static final int COUNTDOWN_SECONDS = 3;

    enum Phase {
        PREPARE,
        MEMORISE,
        RECALL
    }

    final PinGrid grid;
    final int recallTicks;
    Phase phase;
    int phaseTicks;
    int ticksLeft;

    PinGridGame(LockMinigameManager manager, UUID playerId, Block target, String targetId, PinGrid grid,
            int recallTicks, IntConsumer onSolved) {
        super(manager, playerId, target, targetId, grid.menuSize(), onSolved);
        this.grid = grid;
        this.recallTicks = recallTicks;
        setPhase(Phase.PREPARE, ticks(Parameters.chestMinigamePrepareSeconds));
    }

    private void setPhase(Phase next, int duration) {
        phase = next;
        phaseTicks = duration;
        ticksLeft = duration;
    }

    @Override
    void render() {
        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack hidden = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < grid.menuSize(); slot++) {
            inventory.setItem(slot, grid.cellAt(slot) < 0 ? filler : hidden);
        }
    }

    @Override
    void tickRunning(Player player) {
        ticksLeft--;
        if (phase == Phase.RECALL) {
            if (ticksLeft <= 0) {
                fail(player);
                return;
            }
            if (ticksLeft % 20 == 0 && ticksLeft <= COUNTDOWN_SECONDS * 20) {
                bar.setColor(BarColor.RED);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f);
            }
        } else if (ticksLeft <= 0) {
            if (phase == Phase.PREPARE) {
                setPhase(Phase.MEMORISE, ticks(Parameters.chestMinigameMemoriseSeconds));
                for (int cell : grid.pins()) {
                    inventory.setItem(grid.slotOf(cell), pane(Material.LIME_STAINED_GLASS_PANE));
                }
                show(player, ThieveryTexts.WARN + "Memorise the pins", BarColor.YELLOW);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.4f);
            } else {
                setPhase(Phase.RECALL, recallTicks);
                render();
                show(player, ThieveryTexts.SUCCESS + "Set the pins", BarColor.GREEN);
                player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_OFF, 0.6f, 0.8f);
            }
        }
        progress(ticksLeft / (double) phaseTicks);
    }

    @Override
    void click(Player player, InventoryClickEvent event) {
        if (phase != Phase.RECALL || event.getClickedInventory() != inventory) {
            return;
        }
        int cell = grid.cellAt(event.getSlot());
        switch (grid.pick(cell)) {
            case SET -> {
                inventory.setItem(grid.slotOf(cell), pane(Material.LIME_STAINED_GLASS_PANE));
                if (grid.isSolved()) {
                    solve(player);
                } else {
                    player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.6f, 1.6f);
                }
            }
            case MISS -> {
                inventory.setItem(grid.slotOf(cell), pane(Material.RED_STAINED_GLASS_PANE));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
                if (grid.mistakes() >= Parameters.chestMinigameMistakesToFail) {
                    fail(player);
                }
            }
            case IGNORED -> {
            }
        }
    }

    @Override
    int mistakes() {
        return grid.mistakes();
    }

    @Override
    void showFailure() {
        for (int cell : grid.pins()) {
            if (!grid.isPicked(cell)) {
                inventory.setItem(grid.slotOf(cell), pane(Material.YELLOW_STAINED_GLASS_PANE));
            }
        }
    }
}
