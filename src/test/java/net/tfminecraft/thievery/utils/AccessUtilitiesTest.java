package net.tfminecraft.thievery.utils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.thievery.cache.*;
import net.tfminecraft.thievery.player.*;
import net.tfminecraft.tlibs.character.*;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.bukkit.plugin.ServicePriority;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

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
    @Test void traitRestrictionsRequireAReadyCharacterWithAnyExactlyConfiguredTrait() {
        var saved = Cache.traits;
        try (CharacterFixture characters = new CharacterFixture()) {
            Player player = characters.player;
            assertTrue(TraitChecker.hasTraits(player, null));
            assertTrue(TraitChecker.hasTraits(player, List.of()));
            verifyNoInteractions(characters.identity);
            assertFalse(TraitChecker.hasTraits(player, List.of("thief")));
            characters.ready(UUID.randomUUID(), 1);
            assertFalse(TraitChecker.hasTraits(player, List.of("thief")));
            characters.ready(UUID.randomUUID(), 2, "scholar");
            assertFalse(TraitChecker.hasTraits(player, List.of("thief")));
            characters.ready(UUID.randomUUID(), 3, "scholar", "thief");
            assertFalse(TraitChecker.hasTraits(player, List.of("THIEF")));
            Cache.traits = List.of("thief", "assassin");
            assertTrue(TraitChecker.hasRequiredTraits(player));
            assertTrue(TraitChecker.hasLockpickingTraits(player));
            verify(player, never()).sendMessage(anyString());
            TraitChecker.sendMissingTraitMessage(player);
            TraitChecker.sendMissingTraitMessage(player, "robbery");
            verify(player).sendMessage("§cYou lack the training for thievery!");
            verify(player).sendMessage("§cYou lack the training for robbery!");
        } finally { Cache.traits = saved; }
    }

    @Test void lockpickingExplainsOnlyAKnownReadyCharactersMissingTraining() {
        var saved = Cache.traits;
        try (CharacterFixture characters = new CharacterFixture()) {
            Cache.traits = List.of("thief");
            characters.ready(UUID.randomUUID(), 1, "scholar");
            assertFalse(TraitChecker.hasLockpickingTraits(characters.player));
            verify(characters.player).sendMessage("§cYou lack the training to pick locks!");
            verify(characters.identity).currentSession(characters.player);
            verify(characters.identity).info(characters.session.ref().characterId());
            verifyNoMoreInteractions(characters.identity);
        } finally { Cache.traits = saved; }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "pending", "unknown", "dead", "missing"})
    void unavailableOrInvalidCharacterSnapshotsDenySilently(String condition) {
        var saved = Cache.traits;
        try (CharacterFixture characters = new CharacterFixture()) {
            Cache.traits = List.of("thief");
            characters.ready(UUID.randomUUID(), 1, "thief");
            CharacterRef ref = characters.session.ref();
            switch (condition) {
                case "absent" -> characters.unregister();
                case "pending" -> characters.session = null;
                case "unknown" -> characters.snapshots.clear();
                case "dead" -> characters.snapshots.put(ref.characterId(), characters.info(ref, CharacterStatus.DEAD, "thief"));
                case "missing" -> characters.snapshots.put(ref.characterId(), characters.info(ref, CharacterStatus.MISSING, "thief"));
            }
            assertFalse(TraitChecker.hasTraits(characters.player, Cache.traits));
            assertFalse(TraitChecker.hasLockpickingTraits(characters.player));
            verify(characters.player, never()).sendMessage(anyString());
        } finally { Cache.traits = saved; }
    }

    @Test void traitChecksReadFreshSnapshotsAcrossSwitchBackAndProviderRemoval() {
        var saved = Cache.traits;
        try (CharacterFixture characters = new CharacterFixture()) {
            Cache.traits = List.of("thief");
            UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
            characters.ready(alice, 1, "thief");
            assertTrue(TraitChecker.hasRequiredTraits(characters.player));
            characters.ready(bob, 2, "scholar");
            assertFalse(TraitChecker.hasRequiredTraits(characters.player));
            characters.ready(alice, 3, "scholar");
            assertFalse(TraitChecker.hasRequiredTraits(characters.player));
            characters.snapshots.put(alice, characters.info(characters.session.ref(), CharacterStatus.ALIVE, "thief"));
            assertTrue(TraitChecker.hasRequiredTraits(characters.player));
            characters.unregister();
            assertFalse(TraitChecker.hasRequiredTraits(characters.player));
            Cache.traits = null;
            assertTrue(TraitChecker.hasLockpickingTraits(characters.player));
            Cache.traits = List.of();
            assertTrue(TraitChecker.hasRequiredTraits(characters.player));
            assertTrue(TraitChecker.hasLockpickingTraits(characters.player));
            verify(characters.player, never()).sendMessage(anyString());
        } finally { Cache.traits = saved; }
    }

    @Test void missingCharacterApiDeniesConfiguredTraitsWithoutTouchingTLibs() {
        var saved = Cache.traits;
        try (CharacterFixture characters = new CharacterFixture()) {
            characters.ready(UUID.randomUUID(), 1, "thief");
            try (var old = mockStatic(Characters.class)) {
                old.when(Characters::available).thenThrow(new NoClassDefFoundError("CharacterIdentity"));
                assertFalse(TraitChecker.detectCharacterApi());
                assertFalse(TraitChecker.characterApiAvailable());
                Cache.traits = List.of("thief");
                assertNull(TraitChecker.currentSession(characters.player));
                assertFalse(TraitChecker.hasRequiredTraits(characters.player));
                assertFalse(TraitChecker.hasLockpickingTraits(characters.player));
                Cache.traits = List.of();
                assertTrue(TraitChecker.hasLockpickingTraits(characters.player));
                old.verify(Characters::available);
                old.verifyNoMoreInteractions();
            }
            verify(characters.player, never()).sendMessage(anyString());
            assertTrue(TraitChecker.detectCharacterApi());
            assertSame(characters.session, TraitChecker.currentSession(characters.player));
        } finally {
            Cache.traits = saved;
            try (var current = mockStatic(Characters.class)) { TraitChecker.detectCharacterApi(); }
        }
    }

    @Test void configuredTraitIdsAreLowerCasedToMatchCharacterTraits() {
        assertEquals(List.of("thief", "bandit"), TraitChecker.normalise(List.of("Thief", "BANDIT")));
    }

    private static final class CharacterFixture implements AutoCloseable {
        private final ServerMock server = MockBukkit.mock();
        private final Player player = mock(Player.class);
        private final CharacterIdentity identity = mock(CharacterIdentity.class);
        private final Map<UUID, CharacterInfo> snapshots = new HashMap<>();
        private final UUID ownerId = UUID.randomUUID();
        private CharacterSession session;

        private CharacterFixture() {
            when(player.getUniqueId()).thenReturn(ownerId);
            when(identity.currentSession(player)).thenAnswer(call -> Optional.ofNullable(session));
            when(identity.info(any(UUID.class))).thenAnswer(call -> Optional.ofNullable(snapshots.get(call.getArgument(0))));
            server.getServicesManager().register(CharacterIdentity.class, identity, MockBukkit.createMockPlugin(), ServicePriority.Normal);
        }

        private void ready(UUID characterId, long generation, String... traits) {
            session = new CharacterSession(new CharacterRef(ownerId, characterId), generation);
            snapshots.put(characterId, info(session.ref(), CharacterStatus.ALIVE, traits));
        }

        private CharacterInfo info(CharacterRef ref, CharacterStatus status, String... traits) {
            return new CharacterInfo(ref, "Character", status, null, new LinkedHashSet<>(List.of(traits)), 0, null);
        }

        private void unregister() {
            server.getServicesManager().unregister(CharacterIdentity.class, identity);
        }

        @Override public void close() { MockBukkit.unmock(); }
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
