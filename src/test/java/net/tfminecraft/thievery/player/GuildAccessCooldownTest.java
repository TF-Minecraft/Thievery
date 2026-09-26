package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.*;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class GuildAccessCooldownTest {
    @Test void guildCooldownUsesMostRecentValidDateIgnoringSelfAndMalformedEntries() {
        var player=mock(Player.class); UUID self=UUID.randomUUID(), older=UUID.randomUUID(), newer=UUID.randomUUID(), bad=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(self); when(player.getName()).thenReturn("self"); var guild=mock(Guild.class);
        var accesses=new HashMap<UUID,String>(); accesses.put(self,"2026-01-02"); accesses.put(older,"2026-01-01"); accesses.put(newer,"2026-01-03"); accesses.put(bad,"invalid");
        try(var factions=mockStatic(FactionManager.class); var bukkit=mockStatic(Bukkit.class)) {
            assertNull(GuildAccessCooldown.getMostRecentGuildAccess(null,player)); assertNull(GuildAccessCooldown.getMostRecentGuildAccess(accesses,null));
            assertEquals("2026-01-02",GuildAccessCooldown.getMostRecentGuildAccess(accesses,player));
            factions.when(()->FactionManager.getGuildByMember("self")).thenReturn(guild); when(guild.getMembers()).thenReturn(List.of("SELF","older","newer","bad","missing"));
            for(var entry:Map.of("older",older,"newer",newer,"bad",bad,"missing",UUID.randomUUID()).entrySet()) { var offline=mock(OfflinePlayer.class); when(offline.getUniqueId()).thenReturn(entry.getValue()); bukkit.when(()->Bukkit.getOfflinePlayer(entry.getKey())).thenReturn(offline); }
            assertEquals("2026-01-03",GuildAccessCooldown.getMostRecentGuildAccess(accesses,player));
            accesses.put(self,"invalid"); assertEquals("2026-01-03",GuildAccessCooldown.getMostRecentGuildAccess(accesses,player));
            accesses.remove(self); assertEquals("2026-01-03",GuildAccessCooldown.getMostRecentGuildAccess(accesses,player));
        }
    }
    @Test void dateCooldownsReportFreshExpiredMissingAndInvalidHistory() {
        var player=mock(Player.class); UUID id=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        try(var factions=mockStatic(FactionManager.class)) {
            assertFalse(GuildAccessCooldown.isOnCooldown(Map.of(),player,3)); assertEquals(0,GuildAccessCooldown.getMillisRemaining(Map.of(),player,3));
            assertFalse(GuildAccessCooldown.isOnCooldown(Map.of(id,"bad"),player,3)); assertEquals(0,GuildAccessCooldown.getMillisRemaining(Map.of(id,"bad"),player,3));
            var today=Map.of(id,GuildAccessCooldown.today()); assertEquals(LocalDate.now().toString(),GuildAccessCooldown.today()); assertTrue(GuildAccessCooldown.isOnCooldown(today,player,3)); assertTrue(GuildAccessCooldown.getMillisRemaining(today,player,3)>0);
            var expired=Map.of(id,LocalDate.now().minusDays(10).toString()); assertFalse(GuildAccessCooldown.isOnCooldown(expired,player,3)); assertEquals(0,GuildAccessCooldown.getMillisRemaining(expired,player,3));
        }
    }
    @Test void epochCooldownsResolveGuildMaxAndRejectMalformedOrMissingValues() {
        var player=mock(Player.class); UUID id=UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(player.getName()).thenReturn("self"); var accesses=new HashMap<UUID,String>();
        try(var factions=mockStatic(FactionManager.class); var bukkit=mockStatic(Bukkit.class)) {
            assertEquals(0,GuildAccessCooldown.getMostRecentGuildAccessMillis(null,player)); assertEquals(0,GuildAccessCooldown.getMostRecentGuildAccessMillis(accesses,null));
            for(String bad:List.of(" ","invalid","-1")) { accesses.put(id,bad); assertFalse(GuildAccessCooldown.isOnCooldownMillis(accesses,player,60_000)); assertEquals(0,GuildAccessCooldown.getMillisRemainingMillis(accesses,player,60_000)); }
            accesses.clear(); assertEquals(0,GuildAccessCooldown.getMostRecentGuildAccessMillis(accesses,player));
            long now=System.currentTimeMillis(); accesses.put(id,Long.toString(now)); assertTrue(GuildAccessCooldown.isOnCooldownMillis(accesses,player,60_000)); assertTrue(GuildAccessCooldown.getMillisRemainingMillis(accesses,player,60_000)>50_000);
            accesses.put(id," 1 "); assertFalse(GuildAccessCooldown.isOnCooldownMillis(accesses,player,60_000)); assertEquals(0,GuildAccessCooldown.getMillisRemainingMillis(accesses,player,60_000));
            var guild=mock(Guild.class); when(guild.getMembers()).thenReturn(List.of("self","old","new")); factions.when(()->FactionManager.getGuildByMember("self")).thenReturn(guild);
            for(String name:List.of("old","new")) { UUID member=UUID.randomUUID(); var offline=mock(OfflinePlayer.class); when(offline.getUniqueId()).thenReturn(member); bukkit.when(()->Bukkit.getOfflinePlayer(name)).thenReturn(offline); accesses.put(member,name.equals("new")?Long.toString(now):"0"); }
            assertEquals(now,GuildAccessCooldown.getMostRecentGuildAccessMillis(accesses,player));
        }
    }
    @Test void recordingIgnoresMissingTargetsAndFormatsRemainingUnits() {
        var map=new HashMap<UUID,String>(); UUID id=UUID.randomUUID();
        GuildAccessCooldown.recordAccess(null,id,"date"); GuildAccessCooldown.recordAccess(map,null,"date"); GuildAccessCooldown.recordAccess(map,id,null); assertTrue(map.isEmpty());
        GuildAccessCooldown.recordAccess(map,id,"date"); assertEquals("date",map.get(id));
        GuildAccessCooldown.recordAccessMillis(null,id,1); GuildAccessCooldown.recordAccessMillis(map,null,1); GuildAccessCooldown.recordAccessMillis(map,id,123); assertEquals("123",map.get(id));
        assertEquals("2 day(s), 3 hour(s), and 4 minute(s)",GuildAccessCooldown.formatRemaining((2*24*60+3*60+4)*60_000L));
    }
}
