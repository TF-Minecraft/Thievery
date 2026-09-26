package net.tfminecraft.thievery.steal.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import net.tfminecraft.rpcharacters.grave.*;
import net.tfminecraft.thievery.steal.PlayerSlotMap;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;

class StealSourcesTest {
    @Test void containerReadsAndWritesExactlyTheSelectedInventorySlot() {
        var inventory=mock(Inventory.class); var item=mock(ItemStack.class); when(inventory.getItem(4)).thenReturn(item);
        var source=new ContainerStealSource(inventory); assertSame(inventory,source.getInventory()); assertSame(item,source.getItem(4));
        source.setItem(4,null); verify(inventory).setItem(4,null);
    }
    @Test void playerSourceUsesLogicalSlotMappingForReadsAndWrites() {
        var victim=mock(Player.class); var item=mock(ItemStack.class); var source=new PlayerStealSource(victim);
        try(var slots=mockStatic(PlayerSlotMap.class)) {
            slots.when(()->PlayerSlotMap.getItem(victim,40)).thenReturn(item); assertSame(victim,source.getVictim()); assertSame(item,source.getItem(40));
            source.setItem(40,null); slots.verify(()->PlayerSlotMap.setItem(victim,40,null));
        }
    }
    @Test void graveBoundsProtectStorageAndFlushRemovesEmptiedGraves() {
        var grave=mock(Grave.class); var manager=mock(GraveManager.class); var item=mock(ItemStack.class); when(grave.getItem(40)).thenReturn(item); var source=new GraveStealSource(grave);
        assertSame(grave,source.getGrave()); assertNull(source.getItem(-1)); assertNull(source.getItem(41)); source.setItem(-1,item); source.setItem(41,item); verifyNoInteractions(grave);
        assertSame(item,source.getItem(40)); source.setItem(40,null); verify(grave).setItem(40,null);
        try(var graves=mockStatic(GraveManager.class)) {
            graves.when(GraveManager::get).thenReturn(manager); source.flush(); var order=inOrder(grave,manager); order.verify(grave).flush(); order.verify(manager).removeIfEmpty(grave);
        }
    }
}
