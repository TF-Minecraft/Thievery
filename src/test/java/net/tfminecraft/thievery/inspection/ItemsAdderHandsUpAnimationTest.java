package net.tfminecraft.thievery.inspection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.lone.itemsadder.api.CustomPlayer;
import dev.lone.itemsadder.api.Events.PlayerEmoteEndEvent;
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
