package net.tfminecraft.thievery.clue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.thievery.Thievery;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.mockito.*;

class ClearCluesManagerTest {
    MockedStatic<Thievery> plugins; MockedStatic<ClearCluesResolver> resolver;
    Thievery plugin; Player player; ClearCluesManager manager; BukkitScheduler scheduler; List<Runnable> timeouts; List<BukkitTask> tasks;
    @BeforeEach void setup() {
        plugins=mockStatic(Thievery.class); resolver=mockStatic(ClearCluesResolver.class); plugin=mock(Thievery.class,RETURNS_DEEP_STUBS); plugins.when(Thievery::getInstance).thenReturn(plugin);
        player=mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); scheduler=plugin.getServer().getScheduler(); timeouts=new ArrayList<>(); tasks=new ArrayList<>(); manager=new ClearCluesManager();
        when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),eq(600L))).thenAnswer(call->{timeouts.add(call.getArgument(1)); var task=mock(BukkitTask.class); tasks.add(task); return task;});
    }
    @AfterEach void close() { resolver.close(); plugins.close(); }
    PlayerInteractEvent event(Action action,Block block) { var event=mock(PlayerInteractEvent.class); when(event.getAction()).thenReturn(action); when(event.getPlayer()).thenReturn(player); when(event.getClickedBlock()).thenReturn(block); return event; }
    @Test void onlyAwaitedBlockRightClicksConsumeSelectionMode() {
        var block=mock(Block.class); var before=event(Action.RIGHT_CLICK_BLOCK,block); manager.onPlayerInteract(before); verify(before,never()).setCancelled(true);
        manager.startAwaiting(player); var left=event(Action.LEFT_CLICK_BLOCK,block); manager.onPlayerInteract(left); verify(left,never()).setCancelled(true);
        var missing=event(Action.RIGHT_CLICK_BLOCK,null); manager.onPlayerInteract(missing); verify(missing,never()).setCancelled(true);
        var selected=event(Action.RIGHT_CLICK_BLOCK,block); resolver.when(()->ClearCluesResolver.resolve(block)).thenReturn(Optional.empty()); manager.onPlayerInteract(selected);
        verify(selected).setCancelled(true); verify(tasks.getFirst()).cancel(); verify(player).sendMessage("§cThat block is not a door or container.");
        clearInvocations(selected); manager.onPlayerInteract(selected); verify(selected,never()).setCancelled(true);
    }
    @Test void deniedTargetsAreNotClearedAndAuthorizedTargetsReportRemovedCount() {
        var block=mock(Block.class); var target=mock(ClearCluesResolver.ClearCluesTarget.class); resolver.when(()->ClearCluesResolver.resolve(block)).thenReturn(Optional.of(target));
        manager.startAwaiting(player); manager.onPlayerInteract(event(Action.RIGHT_CLICK_BLOCK,block)); verify(player).sendMessage("§cYou cannot clear clues from this block."); resolver.verify(()->ClearCluesResolver.clearLinkedClues(target),never());
        when(player.hasPermission("thievery.admin")).thenReturn(true); resolver.when(()->ClearCluesResolver.canClear(player,target,true)).thenReturn(true); resolver.when(()->ClearCluesResolver.clearLinkedClues(target)).thenReturn(3);
        manager.startAwaiting(player); manager.onPlayerInteract(event(Action.RIGHT_CLICK_BLOCK,block)); verify(player).sendMessage("§a[Thievery] Removed §e3§a clue(s).");
    }
    @Test void restartCancelsOldTimerAndQuittingCancelsCurrentMode() {
        manager.startAwaiting(player); manager.startAwaiting(player); verify(tasks.getFirst()).cancel();
        manager.onPlayerQuit(new PlayerQuitEvent(player,(String)null)); verify(tasks.get(1)).cancel(); clearInvocations(player);
        timeouts.get(1).run(); verify(player,never()).sendMessage(anyString());
    }
    @Test void timeoutNotifiesOnlyStillOnlinePlayers() {
        when(plugin.getServer().getPlayer(player.getUniqueId())).thenReturn(player); when(player.isOnline()).thenReturn(true);
        manager.startAwaiting(player); timeouts.getFirst().run(); verify(tasks.getFirst()).cancel(); verify(player).sendMessage("§cClear clues mode expired. Run /thievery clearclues again.");
        clearInvocations(player); when(player.isOnline()).thenReturn(false); manager.startAwaiting(player); timeouts.get(1).run(); verify(player,never()).sendMessage(contains("expired"));
        when(plugin.getServer().getPlayer(player.getUniqueId())).thenReturn(null); manager.startAwaiting(player); timeouts.get(2).run(); verify(player,never()).sendMessage(contains("expired"));
    }
}
