package net.tfminecraft.thievery.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.thievery.cache.*;
import net.tfminecraft.thievery.player.*;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class AccessUtilitiesTest {
    @Test void targetKeysUseGuildThenPlayerIdentityAndHandleUnknownOwners() {
        UUID id=UUID.randomUUID(); var owner=mock(OfflinePlayer.class); var guild=mock(Guild.class);
        try(var bukkit=mockStatic(Bukkit.class); var factions=mockStatic(FactionManager.class)) {
            assertEquals("none",TargetKeyResolver.resolve(null)); bukkit.when(()->Bukkit.getOfflinePlayer(id)).thenReturn(owner); assertEquals("none",TargetKeyResolver.resolve(id));
            when(owner.getName()).thenReturn("owner"); assertEquals("player:"+id,TargetKeyResolver.resolve(id));
            when(guild.getId()).thenReturn("guild-id"); factions.when(()->FactionManager.getGuildByMember("owner")).thenReturn(guild); assertEquals("guild:guild-id",TargetKeyResolver.resolve(id));
        }
    }
    @Test void onlineRequirementsAllowRefreshWarnWithinWindowAndDenyAfterwards() {
        boolean saved=Cache.requireOwnerOnline; UUID id=UUID.randomUUID(); var owner=mock(OfflinePlayer.class); var guild=mock(Guild.class);
        try(var bukkit=mockStatic(Bukkit.class); var factions=mockStatic(FactionManager.class); var targets=mockStatic(LockpickTargetCache.class)) {
            Cache.requireOwnerOnline=true; assertEquals(GuildChecker.LockpickAccessResult.Type.ALLOWED,GuildChecker.checkLockpickAccess(null).type);
            Cache.requireOwnerOnline=false; assertEquals(GuildChecker.LockpickAccessResult.Type.ALLOWED,GuildChecker.checkLockpickAccess(id).type);
            Cache.requireOwnerOnline=true; bukkit.when(()->Bukkit.getOfflinePlayer(id)).thenReturn(owner); assertEquals(GuildChecker.LockpickAccessResult.Type.ALLOWED,GuildChecker.checkLockpickAccess(id).type);
            when(owner.getName()).thenReturn("owner"); when(owner.isOnline()).thenReturn(true); assertEquals(GuildChecker.LockpickAccessResult.Type.ALLOWED,GuildChecker.checkLockpickAccess(id).type); targets.verify(()->LockpickTargetCache.refresh("player:"+id));
            when(owner.isOnline()).thenReturn(false); var denied=GuildChecker.checkLockpickAccess(id); assertEquals(GuildChecker.LockpickAccessResult.Type.DENY,denied.type); assertEquals("Cannot lockpick - owner is not online.",denied.message);
            targets.when(()->LockpickTargetCache.isActive("player:"+id)).thenReturn(true); targets.when(()->LockpickTargetCache.getRemainingMs("player:"+id)).thenReturn(125_000L);
            var warning=GuildChecker.checkLockpickAccess(id); assertEquals(GuildChecker.LockpickAccessResult.Type.WARN,warning.type); assertTrue(warning.message.contains("2m 5s"));
            when(guild.getId()).thenReturn("id"); when(guild.getName()).thenReturn("Guild"); when(guild.getMembers()).thenReturn(List.of("offline","online")); factions.when(()->FactionManager.getGuildByMember("owner")).thenReturn(guild);
            denied=GuildChecker.checkLockpickAccess(id); assertEquals("Cannot lockpick - Guild has no members online.",denied.message);
            var member=mock(Player.class); bukkit.when(()->Bukkit.getPlayerExact("online")).thenReturn(member);
            assertEquals(GuildChecker.LockpickAccessResult.Type.ALLOWED,GuildChecker.checkLockpickAccess(id).type); targets.verify(()->LockpickTargetCache.refresh("guild:id"));
        } finally { Cache.requireOwnerOnline=saved; }
    }
    @Test void traitRestrictionsRequireAnActiveCharacterWithAnyConfiguredTrait() {
        var player=mock(Player.class); var data=mock(net.tfminecraft.rpcharacters.objects.PlayerData.class); var character=mock(net.tfminecraft.rpcharacters.objects.RPCharacter.class); var trait=mock(net.tfminecraft.rpcharacters.objects.trait.Trait.class);
        var saved=Cache.traits;
        try(var players=mockStatic(net.tfminecraft.rpcharacters.managers.PlayerManager.class)) {
            assertTrue(TraitChecker.hasTraits(player,null)); assertTrue(TraitChecker.hasTraits(player,List.of()));
            players.when(()->net.tfminecraft.rpcharacters.managers.PlayerManager.get(player)).thenReturn(data);
            assertFalse(TraitChecker.hasTraits(player,List.of("thief"))); when(data.hasActiveCharacter()).thenReturn(true); when(data.getActiveCharacter()).thenReturn(character);
            assertFalse(TraitChecker.hasTraits(player,List.of("thief"))); when(character.getTraits()).thenReturn(List.of(trait)); when(trait.getId()).thenReturn("scholar"); assertFalse(TraitChecker.hasTraits(player,List.of("thief")));
            when(trait.getId()).thenReturn("thief"); Cache.traits=List.of("thief"); assertTrue(TraitChecker.hasRequiredTraits(player));
            TraitChecker.sendMissingTraitMessage(player); TraitChecker.sendMissingTraitMessage(player,"robbery"); verify(player).sendMessage("§cYou lack the needed character trait(s) for thievery!"); verify(player).sendMessage("§cYou lack the needed character trait(s) for robbery!");
        } finally { Cache.traits=saved; }
    }
    @Test void lockpickWindowStartsOnRefreshAndIsIsolatedByTarget() {
        String key=UUID.randomUUID().toString(); assertFalse(LockpickTargetCache.isActive(key)); assertEquals(0,LockpickTargetCache.getRemainingMs(key));
        LockpickTargetCache.refresh(key); assertTrue(LockpickTargetCache.isActive(key)); long remaining=LockpickTargetCache.getRemainingMs(key); assertTrue(remaining>590_000 && remaining<=600_000);
        assertFalse(LockpickTargetCache.isActive(key+"other")); LockpickTargetCache.refresh(key); assertTrue(LockpickTargetCache.getRemainingMs(key)>=remaining-1000);
    }
    @Test
    @SuppressWarnings("unchecked")
    void expiredLockpickWindowStopsGrantingOfflineAccessAndCanBeRefreshed() throws Exception {
        String key = UUID.randomUUID().toString();
        var field = LockpickTargetCache.class.getDeclaredField("cache");
        field.setAccessible(true);
        var cache = (Map<String, Long>) field.get(null);
        try {
            LockpickTargetCache.refresh(key);
            // Model a window created over ten minutes ago without a ten-minute test delay.
            cache.put(key, System.currentTimeMillis() - 1);
            assertEquals(0, LockpickTargetCache.getRemainingMs(key));
            assertFalse(LockpickTargetCache.isActive(key));
            assertFalse(cache.containsKey(key), "Expired entries must be evicted");
            LockpickTargetCache.refresh(key);
            assertTrue(LockpickTargetCache.isActive(key));
        } finally { cache.remove(key); }
    }
}
