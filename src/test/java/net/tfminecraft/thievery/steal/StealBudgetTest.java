package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import net.tfminecraft.rpcharacters.utils.ClueGiver;
import net.tfminecraft.thievery.category.CategoryHandler;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class StealBudgetTest {
    @Test
    void tracksCumulativeUsageAndClampsRemainingAtZero() {
        StealBudget budget = new StealBudget(10.5);
        assertEquals(10.5, budget.getCapacity());
        assertEquals(0.0, budget.getUsed());
        assertEquals(10.5, budget.getRemaining());
        budget.addUsed(2.25);
        budget.addUsed(3.25);
        assertEquals(5.5, budget.getUsed());
        assertEquals(5.0, budget.getRemaining());
        budget.addUsed(5.0);
        assertEquals(0.0, budget.getRemaining());
        budget.addUsed(1.0);
        assertEquals(11.5, budget.getUsed());
        assertEquals(0.0, budget.getRemaining());
        assertEquals(10.5, budget.getCapacity());
    }

    @Test
    void negativeCapacityIsClampedToZero() {
        StealBudget budget = new StealBudget(-10);
        assertEquals(0.0, budget.getCapacity());
        assertEquals(0.0, budget.getRemaining());
        assertEquals(0.0, budget.getUsed());
    }

    @Test
    void emptyAndAirItemsCannotBeTakenAndDoNotInvokeIntegrations() {
        try (MockedStatic<ClueGiver> clues = mockStatic(ClueGiver.class);
                MockedStatic<CategoryHandler> categories = mockStatic(CategoryHandler.class)) {
            assertEquals(0, StealBudget.computeTakeableAmount(null, 100));
            assertEquals(0, StealBudget.computeTakeableAmount(item(Material.AIR, 64), 100));
            assertEquals(0, StealBudget.computeTakeableAmount(item(Material.VOID_AIR, 64), 100));
            clues.verifyNoInteractions();
            categories.verifyNoInteractions();
        }
    }

    @Test
    void cluesCannotBeTakenEvenWithUnlimitedBudget() {
        ItemStack item = item(Material.PAPER, 1);
        try (MockedStatic<ClueGiver> clues = mockStatic(ClueGiver.class);
                MockedStatic<CategoryHandler> categories = mockStatic(CategoryHandler.class)) {
            clues.when(() -> ClueGiver.isClueItem(item)).thenReturn(true);
            assertEquals(0, StealBudget.computeTakeableAmount(item, Double.POSITIVE_INFINITY));
            categories.verifyNoInteractions();
        }
    }

    @Test
    void nonpositiveValueAllowsWholeStackEvenWhenBudgetIsExhausted() {
        ItemStack item = item(Material.STONE, 64);
        try (MockedStatic<ClueGiver> clues = mockStatic(ClueGiver.class);
                MockedStatic<CategoryHandler> categories = mockStatic(CategoryHandler.class)) {
            for (double value : new double[] {0.0, -2.0}) {
                categories.when(() -> CategoryHandler.getPerItemValue(item)).thenReturn(value);
                assertEquals(64, StealBudget.computeTakeableAmount(item, 0));
                assertEquals(64, StealBudget.computeTakeableAmount(item, -1));
            }
        }
    }

    @Test
    void positiveValueFloorsAffordableAmountAndNeverExceedsStackSize() {
        ItemStack item = item(Material.DIAMOND, 4);
        try (MockedStatic<ClueGiver> clues = mockStatic(ClueGiver.class);
                MockedStatic<CategoryHandler> categories = mockStatic(CategoryHandler.class)) {
            categories.when(() -> CategoryHandler.getPerItemValue(item)).thenReturn(2.5);
            assertEquals(0, StealBudget.computeTakeableAmount(item, -2.5));
            assertEquals(0, StealBudget.computeTakeableAmount(item, 0));
            assertEquals(0, StealBudget.computeTakeableAmount(item, 2.49));
            assertEquals(1, StealBudget.computeTakeableAmount(item, 2.5));
            assertEquals(2, StealBudget.computeTakeableAmount(item, 7.49));
            assertEquals(3, StealBudget.computeTakeableAmount(item, 7.5));
            assertEquals(4, StealBudget.computeTakeableAmount(item, 10));
            assertEquals(4, StealBudget.computeTakeableAmount(item, 1_000));
            verify(item, never()).setAmount(anyInt());
        }
    }

    private static ItemStack item(Material material, int amount) {
        ItemStack item = mock(ItemStack.class);
        // Material.isAir() uses the live Paper registry; model that API boundary here.
        Material type = mock(Material.class);
        when(type.isAir()).thenReturn(material == Material.AIR
                || material == Material.CAVE_AIR || material == Material.VOID_AIR);
        when(item.getType()).thenReturn(type);
        when(item.getAmount()).thenReturn(amount);
        return item;
    }
}
