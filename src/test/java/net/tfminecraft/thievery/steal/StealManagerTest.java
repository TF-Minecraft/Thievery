package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.thievery.Thievery;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class StealManagerTest {
    private StealManager previous;
    @BeforeEach void rememberManager() { previous = StealManager.getInstance(); }
    @AfterEach void restoreManager() throws Exception {
        var instance = StealManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, previous);
    }
    Player player(UUID id) { var player=mock(Player.class,RETURNS_DEEP_STUBS); when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true); return player; }
    StealReference reference(UUID id) { var reference=mock(StealReference.class); when(reference.getThiefId()).thenReturn(id); when(reference.getKind()).thenReturn(StealGuiHolder.Kind.CHEST); return reference; }
    @Test void sessionsOpenThroughReferenceAndExplicitCloseRemovesBeforeClosingInventory() {
        var manager=new StealManager(); UUID id=UUID.randomUUID(); var player=player(id); var reference=reference(id); var gui=mock(Inventory.class);
        assertSame(manager,StealManager.getInstance()); assertFalse(manager.hasSession(id)); assertNull(manager.getSession(id));
        manager.openSession(player,reference,gui); assertSame(reference,manager.getSession(id)); var order=inOrder(reference,player); order.verify(reference).onOpen(player,gui); order.verify(player).openInventory(gui);
        try(var bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player); manager.endSession(id,true); assertFalse(manager.hasSession(id)); verify(player).closeInventory();
            manager.endSession(id,true); verify(player,times(1)).closeInventory(); manager.registerSession(reference); manager.endSession(id,false); verify(player,times(1)).closeInventory();
            manager.registerSession(reference); when(player.isOnline()).thenReturn(false); manager.endSession(id,true); verify(player,times(1)).closeInventory();
            manager.registerSession(reference); bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(null); manager.endSession(id,true); assertFalse(manager.hasSession(id));
        }
    }
    @Test void ticksOnlyOnlinePlayersViewingTheirMatchingTheftMenu() {
        var manager=new StealManager(); UUID id=UUID.randomUUID(); var player=player(id); var reference=reference(id); manager.registerSession(reference);
        try(var bukkit=mockStatic(Bukkit.class)) {
            manager.tickAll(); verify(reference,never()).tick(any()); bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player); when(player.isOnline()).thenReturn(false); manager.tickAll(); verify(reference,never()).tick(any()); when(player.isOnline()).thenReturn(true);
            var top=player.getOpenInventory().getTopInventory(); when(top.getHolder()).thenReturn(null); manager.tickAll();
            when(top.getHolder()).thenReturn(new StealGuiHolder(id,StealGuiHolder.Kind.ROBBERY)); manager.tickAll();
            when(top.getHolder()).thenReturn(new StealGuiHolder(UUID.randomUUID(),StealGuiHolder.Kind.CHEST)); manager.tickAll(); verify(reference,never()).tick(any());
            when(top.getHolder()).thenReturn(new StealGuiHolder(id,StealGuiHolder.Kind.CHEST)); doAnswer(call->{manager.endSession(id,false); return null;}).when(reference).tick(player); manager.tickAll(); verify(reference).tick(player); assertFalse(manager.hasSession(id));
        }
    }
    @Test void clickAndCloseEventsDispatchOnlyPlayerTheftInventoriesWithMatchingSessionKind() {
        var manager=new StealManager(); UUID id=UUID.randomUUID(); var player=player(id); var reference=reference(id);
        var click=mock(InventoryClickEvent.class,RETURNS_DEEP_STUBS); var close=mock(InventoryCloseEvent.class,RETURNS_DEEP_STUBS);
        var nonplayer=mock(HumanEntity.class); when(click.getWhoClicked()).thenReturn(nonplayer); when(close.getPlayer()).thenReturn(nonplayer); manager.onInventoryClick(click); manager.onInventoryClose(close);
        when(click.getWhoClicked()).thenReturn(player); when(close.getPlayer()).thenReturn(player); when(click.getView().getTopInventory().getHolder()).thenReturn(null); when(close.getView().getTopInventory().getHolder()).thenReturn(null); manager.onInventoryClick(click); manager.onInventoryClose(close);
        var holder=new StealGuiHolder(id,StealGuiHolder.Kind.CHEST); when(click.getView().getTopInventory().getHolder()).thenReturn(holder); when(close.getView().getTopInventory().getHolder()).thenReturn(holder); manager.onInventoryClick(click); manager.onInventoryClose(close);
        manager.registerSession(reference); when(reference.getKind()).thenReturn(StealGuiHolder.Kind.ROBBERY); manager.onInventoryClick(click); manager.onInventoryClose(close); verify(reference,never()).handleClick(any(),any()); verify(reference,never()).onClose(any());
        when(reference.getKind()).thenReturn(StealGuiHolder.Kind.CHEST); manager.onInventoryClick(click); verify(reference).handleClick(click,player); manager.onInventoryClose(close); verify(reference).onClose(player); assertFalse(manager.hasSession(id));
        assertNull(StealManager.getStealGuiHolder(null)); assertSame(holder,StealManager.getStealGuiHolder(click.getView().getTopInventory()));
    }
    @Test void updaterReplacesScheduledTaskAndStopsIdempotently() {
        var manager=mock(StealManager.class); var updater=new StealGuiUpdater(manager); var plugin=mock(Thievery.class,RETURNS_DEEP_STUBS); var task=mock(BukkitTask.class); var next=mock(BukkitTask.class);
        try(var plugins=mockStatic(Thievery.class)) {
            plugins.when(Thievery::getInstance).thenReturn(plugin); var scheduler=plugin.getServer().getScheduler(); when(scheduler.runTaskTimer(eq(plugin),any(Runnable.class),eq(20L),eq(20L))).thenReturn(task,next);
            updater.stop(); updater.start(); var callback=ArgumentCaptor.forClass(Runnable.class); verify(scheduler).runTaskTimer(eq(plugin),callback.capture(),eq(20L),eq(20L)); callback.getValue().run(); verify(manager).tickAll();
            updater.start(); verify(task).cancel(); updater.stop(); updater.stop(); verify(next,times(1)).cancel();
        }
    }
}
