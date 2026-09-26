package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.category.*;
import net.tfminecraft.thievery.clue.ClueChecker;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.steal.source.StealSource;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class StealTakeHandlerTest {
    Player robber; StealSource source; Inventory gui; Runnable refresh; StealBudget budget; PlayerData data;
    MockedStatic<Thievery> plugin; MockedStatic<ClueChecker> clues; MockedStatic<ItemValue> values; MockedStatic<CategoryHandler> categories;
    @BeforeEach void setup() {
        MockBukkit.mock(); robber=MockBukkit.getMock().addPlayer(); source=mock(StealSource.class); gui=MockBukkit.getMock().createInventory(null,9); refresh=mock(Runnable.class); budget=new StealBudget(10); data=new PlayerData(robber.getUniqueId());
        plugin=mockStatic(Thievery.class,RETURNS_DEEP_STUBS); when(Thievery.getPlayerManager().get(robber.getUniqueId())).thenReturn(data);
        clues=mockStatic(ClueChecker.class); values=mockStatic(ItemValue.class); categories=mockStatic(CategoryHandler.class);
        categories.when(()->CategoryHandler.getPerItemValue(any())).thenReturn(2.0);
        categories.when(()->CategoryHandler.getTotalValue(any())).thenAnswer(call->((ItemStack)call.getArgument(0)).getAmount()*2.0);
    }
    @AfterEach void close() { categories.close(); values.close(); clues.close(); plugin.close(); MockBukkit.unmock(); }
    boolean take(ClickType click,ItemStack shown,StealTakeHandler.TakeCallback callback) { return StealTakeHandler.performTake(robber,source,budget,3,gui,1,click,shown,refresh,callback); }
    ItemStack sourceItem(int amount) { var item=new ItemStack(Material.DIAMOND,amount); when(source.getItem(3)).thenReturn(item); return item; }

    @Test void absentAndClueItemsNeverTransferOrSpendBudget() {
        gui.setItem(1,new ItemStack(Material.DIAMOND)); assertFalse(take(ClickType.LEFT,null,null)); assertNull(gui.getItem(1));
        when(source.getItem(3)).thenReturn(new ItemStack(Material.AIR)); assertFalse(take(ClickType.LEFT,null,null));
        var clue=sourceItem(2); clues.when(()->ClueChecker.isClueItem(clue)).thenReturn(true); assertFalse(take(ClickType.LEFT,clue,null));
        assertEquals(0,budget.getUsed()); verify(source,never()).setItem(anyInt(),any()); verifyNoInteractions(refresh); assertTrue(robber.getInventory().isEmpty());
    }
    @Test void leftClickTakesOneAndCallbackSeesActualValueAndSlot() {
        var real=sourceItem(4); var callback=mock(StealTakeHandler.TakeCallback.class);
        assertTrue(take(ClickType.LEFT,real,callback)); assertEquals(3,real.getAmount()); assertEquals(1,robber.getInventory().getItem(0).getAmount()); assertEquals(2,budget.getUsed());
        verify(callback).onAfterTake(eq(robber),argThat(item->item.getType()==Material.DIAMOND && item.getAmount()==1),eq(2.0),eq(false),eq(3)); verify(refresh).run();
    }
    @Test void shiftClickRespectsBudgetAndRemovesDepletedSource() {
        var real=sourceItem(9); assertTrue(take(ClickType.SHIFT_LEFT,real,null)); assertEquals(4,real.getAmount()); assertEquals(5,robber.getInventory().getItem(0).getAmount()); assertEquals(10,budget.getUsed());
        budget=new StealBudget(20); var last=sourceItem(2); assertTrue(StealTakeHandler.performTake(robber,source,budget,3,gui,1,ClickType.SHIFT_LEFT,last,refresh)); verify(source).setItem(3,null);
    }
    @Test void shiftClickOnSingleOrMissingDisplayBehavesAsSingleTake() {
        var real=sourceItem(5); assertTrue(take(ClickType.SHIFT_LEFT,null,null)); assertEquals(4,real.getAmount());
        assertTrue(take(ClickType.SHIFT_LEFT,new ItemStack(Material.DIAMOND),null)); assertEquals(3,real.getAmount()); assertEquals(4,budget.getUsed());
    }
    @Test void unaffordableItemRefreshesGuiWithoutMutatingInventory() {
        var real=sourceItem(2); budget=new StealBudget(1); gui.setItem(1,real);
        assertFalse(take(ClickType.LEFT,real,null)); assertNull(gui.getItem(1)); assertEquals(2,real.getAmount()); assertTrue(robber.getInventory().isEmpty()); verify(refresh).run();
    }
    @Test void fullInventoryRejectsSingleAndShiftTakeWithoutSideEffects() {
        for(int i=0;i<36;i++) robber.getInventory().setItem(i,new ItemStack(Material.STONE,64));
        var real=sourceItem(4); assertFalse(take(ClickType.LEFT,real,null)); assertFalse(take(ClickType.SHIFT_LEFT,real,null)); assertEquals(4,real.getAmount()); assertEquals(0,budget.getUsed()); verifyNoInteractions(refresh);
        robber.getInventory().setItem(0,new ItemStack(Material.DIAMOND,63)); assertTrue(take(ClickType.SHIFT_LEFT,real,null)); assertEquals(3,real.getAmount()); assertEquals(64,robber.getInventory().getItem(0).getAmount());
    }
    @Test void capacityCountsOnlyEmptySlotsAndCompatiblePartialStacks() {
        var player=mock(Player.class); var inventory=mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory);
        var item=new ItemStack(Material.DIAMOND,5);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{new ItemStack(Material.STONE,2),new ItemStack(Material.DIAMOND,60),new ItemStack(Material.DIAMOND,64)});
        assertEquals(0,StealTakeHandler.maxFitInPlayerInventory(player,item,0)); assertEquals(4,StealTakeHandler.maxFitInPlayerInventory(player,item,10)); assertEquals(2,StealTakeHandler.maxFitInPlayerInventory(player,item,2));
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{new ItemStack(Material.AIR),null}); assertEquals(128,StealTakeHandler.maxFitInPlayerInventory(player,item,200));
    }
    @Test void inventoryInsertionFailureDoesNotChargeBudgetOrChangeSource() {
        var real=sourceItem(4); var player=mock(Player.class); var inventory=mock(PlayerInventory.class); when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null}); when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>(Map.of(0,new ItemStack(Material.DIAMOND))));
        assertFalse(StealTakeHandler.performTake(player,source,budget,3,gui,1,ClickType.LEFT,real,refresh)); assertEquals(4,real.getAmount()); assertEquals(0,budget.getUsed()); verifyNoInteractions(refresh);
    }
    @Test void bundleTransferUsesClickModeUpdatedBundleAndReportedValue() {
        var bundle=sourceItem(1); values.when(()->ItemValue.isBundle(bundle)).thenReturn(true); values.when(()->ItemValue.hasStealableContents(data,bundle,10)).thenReturn(true);
        var result=mock(ItemValue.BundleTakeResult.class); values.when(()->ItemValue.takeFromBundle(bundle,robber,data,10,ItemValue.BundleTakeMode.ONE)).thenReturn(result);
        assertFalse(take(ClickType.LEFT,bundle,null)); verifyNoInteractions(refresh); assertEquals(0,budget.getUsed());
        when(result.isAnyTaken()).thenReturn(true); when(result.getValueTaken()).thenReturn(3.0); var updated=new ItemStack(Material.BUNDLE); when(result.getUpdatedBundle()).thenReturn(updated);
        var callback=mock(StealTakeHandler.TakeCallback.class); assertTrue(take(ClickType.LEFT,bundle,callback)); verify(source).setItem(3,updated); verify(callback).onAfterTake(robber,bundle,3,true,3); assertEquals(3,budget.getUsed());
        budget=new StealBudget(10); when(result.isRemovedFromSource()).thenReturn(true); values.when(()->ItemValue.takeFromBundle(bundle,robber,data,10,ItemValue.BundleTakeMode.GREEDY)).thenReturn(result);
        assertTrue(take(ClickType.SHIFT_LEFT,bundle,null)); verify(source).setItem(3,null);
    }
    @Test void bundleWithoutStealableContentsFallsBackToTakingContainerItself() {
        var bundle=sourceItem(1); values.when(()->ItemValue.isBundle(bundle)).thenReturn(true);
        assertTrue(take(ClickType.LEFT,bundle,null)); assertEquals(2,budget.getUsed()); verify(source).setItem(3,null);
    }
}
