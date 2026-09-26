package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Faction;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class LockAccessTest {
    private final UUID owner = UUID.randomUUID();
    private Player opener;
    private OfflinePlayer ownerPlayer;
    private Guild ownerGuild;
    private Guild openerGuild;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<FactionManager> factions;

    @BeforeEach
    void setUp() {
        opener = mock(Player.class);
        when(opener.getUniqueId()).thenReturn(UUID.randomUUID());
        when(opener.getName()).thenReturn("opener");
        ownerPlayer = mock(OfflinePlayer.class);
        when(ownerPlayer.getName()).thenReturn("owner");
        ownerGuild = mock(Guild.class);
        openerGuild = mock(Guild.class);
        when(ownerGuild.getId()).thenReturn("owner-guild");
        when(openerGuild.getId()).thenReturn("opener-guild");
        bukkit = mockStatic(Bukkit.class);
        factions = mockStatic(FactionManager.class);
        bukkit.when(() -> Bukkit.getOfflinePlayer(owner)).thenReturn(ownerPlayer);
        factions.when(() -> FactionManager.getGuildByMember("owner")).thenReturn(ownerGuild);
        factions.when(() -> FactionManager.getGuildByMember("opener")).thenReturn(openerGuild);
    }

    @AfterEach
    void tearDown() {
        factions.close();
        bukkit.close();
    }

    @Test
    void publicUnownedAndPersonallyOwnedLocksAllowAccessWithoutGuildLookups() {
        assertTrue(LockAccess.canAccess(opener, owner, LockState.PUBLIC));
        assertTrue(LockAccess.canAccess(opener, null, LockState.PRIVATE));
        when(opener.getUniqueId()).thenReturn(owner);
        assertTrue(LockAccess.canAccess(opener, owner, LockState.PRIVATE));
        bukkit.verifyNoInteractions();
        factions.verifyNoInteractions();
    }

    @Test
    void privateLockRejectsOtherPlayersWithoutGuildLookups() {
        assertFalse(LockAccess.canAccess(opener, owner, LockState.PRIVATE));
        bukkit.verifyNoInteractions();
        factions.verifyNoInteractions();
    }

    @Test
    void unknownOwnerOrMissingGuildPreventsSharedAccess() {
        when(ownerPlayer.getName()).thenReturn(null);
        assertFalse(LockAccess.canAccess(opener, owner, LockState.GUILD));
        factions.verifyNoInteractions();
        when(ownerPlayer.getName()).thenReturn("owner");
        factions.when(() -> FactionManager.getGuildByMember("owner")).thenReturn(null);
        assertFalse(LockAccess.canAccess(opener, owner, LockState.GUILD));
        factions.when(() -> FactionManager.getGuildByMember("owner")).thenReturn(ownerGuild);
        factions.when(() -> FactionManager.getGuildByMember("opener")).thenReturn(null);
        assertFalse(LockAccess.canAccess(opener, owner, LockState.FACTION));
    }

    @Test
    void guildLockRequiresMatchingGuildIds() {
        assertFalse(LockAccess.canAccess(opener, owner, LockState.GUILD));
        when(openerGuild.getId()).thenReturn("owner-guild");
        assertTrue(LockAccess.canAccess(opener, owner, LockState.GUILD));
    }

    @Test
    void factionLockRequiresBothFactionsAndMatchingFactionIds() {
        assertFalse(LockAccess.canAccess(opener, owner, LockState.FACTION));
        Faction ownerFaction = mock(Faction.class);
        Faction openerFaction = mock(Faction.class);
        when(ownerFaction.getId()).thenReturn("north");
        when(openerFaction.getId()).thenReturn("south");
        when(ownerGuild.getFaction()).thenReturn(ownerFaction);
        assertFalse(LockAccess.canAccess(opener, owner, LockState.FACTION));
        when(openerGuild.getFaction()).thenReturn(openerFaction);
        assertFalse(LockAccess.canAccess(opener, owner, LockState.FACTION));
        when(openerFaction.getId()).thenReturn("north");
        assertTrue(LockAccess.canAccess(opener, owner, LockState.FACTION));
        assertFalse(LockAccess.canAccess(opener, owner, LockState.GUILD));
    }

    @Test
    void unknownLockStateDoesNotGrantSharedAccess() {
        assertFalse(LockAccess.canAccess(opener, owner, null));
    }

    @Test
    void chestMergingAcceptsOnlyUnownedOrPersonallyOwnedNeighbor() {
        UUID placer = UUID.randomUUID();
        assertTrue(ChestMergeRules.personallyOwns(placer, null));
        assertTrue(ChestMergeRules.personallyOwns(placer, placer));
        assertFalse(ChestMergeRules.personallyOwns(placer, owner));
    }
}
