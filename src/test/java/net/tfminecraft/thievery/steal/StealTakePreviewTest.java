package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import net.tfminecraft.thievery.category.*;
import net.tfminecraft.thievery.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class StealTakePreviewTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void close() { MockBukkit.unmock(); }
    @Test void emptyItemsHaveNoPredictedTransfer() {
        assertEquals(new StealTakePreview.TakeValues(0,0,0,0,0),StealTakePreview.estimate(null,null,null,10));
        assertEquals(new StealTakePreview.TakeValues(0,0,0,0,0),StealTakePreview.estimate(null,null,new ItemStack(Material.AIR),10));
    }
    @Test void ordinaryPreviewUsesBudgetCapacityAndPerItemValue() {
        Player player=MockBukkit.getMock().addPlayer(); var item=new ItemStack(Material.DIAMOND,8);
        try(var values=mockStatic(ItemValue.class); var categories=mockStatic(CategoryHandler.class)) {
            categories.when(()->CategoryHandler.getPerItemValue(item)).thenReturn(2.0);
            assertEquals(new StealTakePreview.TakeValues(2,0,0,0,0),StealTakePreview.estimate(player,null,item,1));
            assertEquals(new StealTakePreview.TakeValues(2,10,5,5,5),StealTakePreview.estimate(player,null,item,10));
            for(int i=0;i<36;i++) player.getInventory().setItem(i,new ItemStack(Material.STONE,64));
            player.getInventory().setItem(0,new ItemStack(Material.DIAMOND,62));
            assertEquals(new StealTakePreview.TakeValues(2,4,2,5,2),StealTakePreview.estimate(player,null,item,10));
            categories.when(()->CategoryHandler.getPerItemValue(item)).thenReturn(0.0);
            assertEquals(new StealTakePreview.TakeValues(0,2,2,8,2),StealTakePreview.estimate(player,null,item,10));
        }
    }
    @Test void bundlePreviewReportsBothTakeModesWithoutMutatingItems() {
        Player player=MockBukkit.getMock().addPlayer(); var data=new PlayerData(UUID.randomUUID()); var item=new ItemStack(Material.BUNDLE); var before=item.clone();
        try(var values=mockStatic(ItemValue.class)) {
            values.when(()->ItemValue.isBundle(item)).thenReturn(true);
            values.when(()->ItemValue.estimateOneTakeValue(item,data,10)).thenReturn(2.0);
            values.when(()->ItemValue.estimateGreedyTakeValue(item,player,data,10)).thenReturn(7.0);
            assertEquals(new StealTakePreview.TakeValues(2,7,0,0,0),StealTakePreview.estimate(player,data,item,10)); assertEquals(before,item);
        }
    }
}
