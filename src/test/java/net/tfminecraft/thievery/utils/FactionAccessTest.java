package net.tfminecraft.thievery.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.tlibs.character.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class FactionAccessTest {
    @Test void locksBelongToTheReviewedHeirAndCannotBeBorrowedByAnotherCharacterOnTheAccount() {
        UUID owner=UUID.randomUUID(); Player player=mock(Player.class); when(player.getUniqueId()).thenReturn(owner);
        var heir=new CharacterRef(owner,UUID.randomUUID()); var active=new CharacterSession(heir,1);
        try(var api=mockStatic(SimpleFactionsApi.class); var chars=mockStatic(Characters.class); var factions=mockStatic(FactionManager.class)) {
            assertFalse(FactionAccess.ready()); assertTrue(FactionAccess.heir(owner).isEmpty()); assertFalse(FactionAccess.owns(player,owner));
            api.when(SimpleFactionsApi::ready).thenReturn(true); assertFalse(FactionAccess.ready());
            chars.when(Characters::legacyHeirsReady).thenReturn(true); assertTrue(FactionAccess.ready());
            assertTrue(FactionAccess.heir(null).isEmpty()); assertNull(FactionAccess.ownerGuild(owner));
            assertFalse(FactionAccess.owns(null,owner)); assertFalse(FactionAccess.owns(player,null)); assertFalse(FactionAccess.owns(player,owner));
            chars.when(()->Characters.legacyHeir(owner)).thenReturn(Optional.of(heir));
            assertFalse(FactionAccess.owns(player,owner));
            chars.when(()->Characters.currentSession(player)).thenReturn(Optional.of(active));
            assertFalse(FactionAccess.owns(player,owner));
            chars.when(()->Characters.isCurrent(any(CharacterSession.class))).thenReturn(true);
            assertTrue(FactionAccess.owns(player,owner));
            chars.when(()->Characters.currentSession(player)).thenReturn(Optional.of(new CharacterSession(new CharacterRef(owner,UUID.randomUUID()),2)));
            assertFalse(FactionAccess.owns(player,owner));
            var guild=mock(Guild.class); factions.when(()->FactionManager.getGuildByMember(heir.characterId())).thenReturn(guild);
            assertSame(guild,FactionAccess.ownerGuild(owner));
        }
    }
}
