package net.tfminecraft.thievery.inspection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.lone.itemsadder.api.CustomPlayer;
import dev.lone.itemsadder.api.Events.PlayerEmoteEndEvent;
import dev.lone.itemsadder.api.Events.PlayerEmotePlayEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

class ItemsAdderHandsUpAnimationTest {
    private final Player player = mock(Player.class);
    private final HandsUpManager manager = mock(HandsUpManager.class);
    private final Plugin plugin = mock(Plugin.class);
    private final ItemsAdderHandsUpAnimation animation = new ItemsAdderHandsUpAnimation(plugin, manager);

    @Test
    void startRequiresTheRequestedAnimationToActuallyBePlaying() {
        CustomPlayer rendered = mock(CustomPlayer.class);
        try (var api = mockStatic(CustomPlayer.class)) {
            assertFalse(animation.start(player));
            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(rendered);
            assertFalse(animation.start(player));
            when(rendered.isPlayingAnimation(ItemsAdderHandsUpAnimation.EMOTE)).thenReturn(true);
            assertTrue(animation.start(player));
            api.verify(() -> CustomPlayer.playEmote(player, ItemsAdderHandsUpAnimation.EMOTE), times(3));
        }
    }

    @Test
    void startPushesActiveProfileTexturesToRenderer() throws Exception {
        CustomPlayer rendered = mock(CustomPlayer.class);
        when(rendered.isPlayingAnimation(ItemsAdderHandsUpAnimation.EMOTE)).thenReturn(true);
        itemsadder.m.dn renderer = mock(itemsadder.m.dn.class);
        java.lang.reflect.Field field = dev.lone.itemsadder.api.CustomEntity.class.getDeclaredField("renderer");
        field.setAccessible(true);
        field.set(rendered, renderer);
        com.destroystokyo.paper.profile.PlayerProfile profile =
                mock(com.destroystokyo.paper.profile.PlayerProfile.class);
        when(player.getPlayerProfile()).thenReturn(profile);
        when(profile.getProperties()).thenReturn(java.util.Set.of(
                new com.destroystokyo.paper.profile.ProfileProperty("textures", "active-textures-value", "sig")));
        try (var api = mockStatic(CustomPlayer.class)) {
            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(rendered);
            assertTrue(animation.start(player));
            verify(renderer).changePlayerSkin("active-textures-value");
        }
    }

    @Test
    void anyEmoteIsSkinnedFromTheActiveProfileOnTheNextTick() throws Exception {
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        PlayerEmotePlayEvent event = mock(PlayerEmotePlayEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEmoteName()).thenReturn("wave");

        CustomPlayer rendered = mock(CustomPlayer.class);
        itemsadder.m.dn renderer = mock(itemsadder.m.dn.class);
        java.lang.reflect.Field field = dev.lone.itemsadder.api.CustomEntity.class.getDeclaredField("renderer");
        field.setAccessible(true);
        field.set(rendered, renderer);
        com.destroystokyo.paper.profile.PlayerProfile profile =
                mock(com.destroystokyo.paper.profile.PlayerProfile.class);
        when(player.getPlayerProfile()).thenReturn(profile);
        when(profile.getProperties()).thenReturn(java.util.Set.of(
                new com.destroystokyo.paper.profile.ProfileProperty("textures", "wave-textures", "sig")));

        try (var api = mockStatic(CustomPlayer.class)) {
            animation.onEmotePlay(event);
            verify(scheduler).runTask(eq(plugin), task.capture());

            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(null);
            task.getValue().run();
            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(rendered);
            task.getValue().run();
            verify(renderer, never()).changePlayerSkin(anyString());

            when(rendered.isPlayingAnimation("wave")).thenReturn(true);
            task.getValue().run();
            verify(renderer).changePlayerSkin("wave-textures");

            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            api.when(() -> CustomPlayer.byAlreadySpawned(player))
                    .thenThrow(new IllegalStateException("disabled"));
            assertDoesNotThrow(task.getValue()::run);
            verify(plugin.getLogger()).log(any(java.util.logging.Level.class), anyString(), any(Throwable.class));
        }
    }

    @Test
    void startSkinPushSkipsMissingDataAndSurvivesRendererFailures() throws Exception {
        CustomPlayer rendered = mock(CustomPlayer.class);
        when(rendered.isPlayingAnimation(ItemsAdderHandsUpAnimation.EMOTE)).thenReturn(true);
        com.destroystokyo.paper.profile.PlayerProfile profile =
                mock(com.destroystokyo.paper.profile.PlayerProfile.class);
        when(player.getPlayerProfile()).thenReturn(profile);
        var other = new com.destroystokyo.paper.profile.ProfileProperty("other", "x", "sig");
        var emptyTextures = new com.destroystokyo.paper.profile.ProfileProperty("textures", "", "sig");
        var textures = new com.destroystokyo.paper.profile.ProfileProperty("textures", "value", "sig");
        try (var api = mockStatic(CustomPlayer.class)) {
            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(rendered);
            when(profile.getProperties())
                    .thenReturn(new java.util.LinkedHashSet<>(java.util.List.of(other, emptyTextures)));
            assertTrue(animation.start(player));
            when(profile.getProperties()).thenReturn(java.util.Set.of(other));
            assertTrue(animation.start(player));
            when(profile.getProperties()).thenReturn(java.util.Set.of(textures));
            assertTrue(animation.start(player));
            itemsadder.m.dn renderer = mock(itemsadder.m.dn.class);
            doThrow(new IllegalStateException("gone")).when(renderer).changePlayerSkin("value");
            java.lang.reflect.Field field = dev.lone.itemsadder.api.CustomEntity.class.getDeclaredField("renderer");
            field.setAccessible(true);
            field.set(rendered, renderer);
            when(plugin.getLogger()).thenReturn(mock(Logger.class));
            assertTrue(animation.start(player));
            verify(plugin.getLogger()).log(any(java.util.logging.Level.class), anyString(), any(Throwable.class));
        }
    }

    @Test
    void stopLeavesOtherEmotesAlone() {
        CustomPlayer rendered = mock(CustomPlayer.class);
        try (var api = mockStatic(CustomPlayer.class)) {
            animation.stop(player);
            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(rendered);
            animation.stop(player);
            api.verify(() -> CustomPlayer.stopEmote(player), never());
            when(rendered.isPlayingAnimation(ItemsAdderHandsUpAnimation.EMOTE)).thenReturn(true);
            animation.stop(player);
            api.verify(() -> CustomPlayer.stopEmote(player));
        }
    }

    @Test
    void runtimeFailuresFailClosedAndDoNotBreakCleanup() {
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        try (var api = mockStatic(CustomPlayer.class)) {
            api.when(() -> CustomPlayer.playEmote(player, ItemsAdderHandsUpAnimation.EMOTE))
                    .thenThrow(new IllegalStateException("unavailable"));
            assertFalse(animation.start(player));
            api.when(() -> CustomPlayer.byAlreadySpawned(player))
                    .thenThrow(new IllegalStateException("disabled"));
            assertDoesNotThrow(() -> animation.stop(player));
        }
        verify(plugin.getLogger(), times(2)).log(any(java.util.logging.Level.class), anyString(), any(Throwable.class));
    }

    @Test
    void onlyOurEmoteEndRevokesConsentWithoutReenteringItemsAdderStop() {
        PlayerEmoteEndEvent event = mock(PlayerEmoteEndEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEmoteName()).thenReturn("wave");
        animation.onEmoteEnd(event);
        verifyNoInteractions(manager);
        when(event.getEmoteName()).thenReturn(ItemsAdderHandsUpAnimation.EMOTE);
        try (var api = mockStatic(CustomPlayer.class)) {
            animation.onEmoteEnd(event);
            verify(manager).animationEnded(player);
            api.verifyNoInteractions();
        }
    }
}
