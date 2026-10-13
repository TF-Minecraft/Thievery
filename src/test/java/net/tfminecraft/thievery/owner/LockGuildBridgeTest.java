package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.identity.Members;
import net.tfminecraft.simplefactions.objects.Faction;

class LockGuildBridgeTest {
    @Test void withoutSimpleFactionsOrItsLockGuildGuildLocksDegrade() {
        PluginManager plugins = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            assertFalse(LockGuildBridge.probe());
            assertTrue(LockGuildBridge.degraded());
            assertSame(LockGuilds.UNAVAILABLE, LockGuildBridge.get());
            when(plugins.getPlugin("SimpleFactions")).thenReturn(mock(Plugin.class));
            assertTrue(LockGuildBridge.probe());
            assertFalse(LockGuildBridge.degraded());
            assertFalse(LockGuildBridge.probe("java.lang.String"), "installed but too old");
            assertTrue(LockGuildBridge.degraded());
        }
        assertTrue(LockGuildBridge.probeLockGuild("net.tfminecraft.simplefactions.api.SimpleFactionsApi"));
        assertFalse(LockGuildBridge.probeLockGuild("java.lang.String"), "an older SimpleFactions has no lockGuild");
        assertFalse(LockGuildBridge.probeLockGuild("net.tfminecraft.simplefactions.api.Missing"));
        assertFalse(LockGuildBridge.install(false, () -> { throw new AssertionError("never loaded"); }));
        LockGuildBridge.set(LockGuilds.UNAVAILABLE);
        assertTrue(LockGuildBridge.degraded());
    }

    @Test void theUnavailableSourceDeniesEverything() {
        assertFalse(LockGuilds.UNAVAILABLE.ready());
        assertTrue(LockGuilds.UNAVAILABLE.lockGuild(UUID.randomUUID()).isEmpty());
        assertFalse(LockGuilds.UNAVAILABLE.anyMemberOnline(UUID.randomUUID()));
    }

    @Test void theBridgeCrossesBackWithPlainIncarnationAndFactionValues() {
        UUID character = UUID.randomUUID();
        Guild guild = mock(Guild.class);
        Faction faction = mock(Faction.class);
        when(guild.getIncarnation()).thenReturn("inc-7");
        when(faction.getId()).thenReturn("realm");
        when(guild.getMembers()).thenReturn(List.of("away", "here"));
        var bridge = new SimpleFactionsLockGuilds();
        try (var api = mockStatic(SimpleFactionsApi.class); var members = mockStatic(Members.class)) {
            api.when(SimpleFactionsApi::ready).thenReturn(true);
            assertTrue(bridge.ready());
            api.when(() -> SimpleFactionsApi.lockGuild(character)).thenReturn(Optional.empty());
            assertTrue(bridge.lockGuild(character).isEmpty());
            assertFalse(bridge.anyMemberOnline(character));
            api.when(() -> SimpleFactionsApi.lockGuild(character)).thenReturn(Optional.of(guild));
            assertEquals(Optional.of(new LockGuilds.GuildInfo("inc-7", null)), bridge.lockGuild(character));
            when(guild.getFaction()).thenReturn(faction);
            assertEquals(Optional.of(new LockGuilds.GuildInfo("inc-7", "realm")), bridge.lockGuild(character));
            members.when(() -> Members.online(anyString())).thenReturn(Optional.empty());
            assertFalse(bridge.anyMemberOnline(character));
            members.when(() -> Members.online("here")).thenReturn(Optional.of(mock(Player.class)));
            assertTrue(bridge.anyMemberOnline(character));
            assertTrue(bridge.lockGuild(null).isEmpty());
            assertFalse(bridge.anyMemberOnline(null));
        }
    }
}
