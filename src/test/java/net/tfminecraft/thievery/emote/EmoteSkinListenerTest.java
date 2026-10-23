package net.tfminecraft.thievery.emote;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.lone.itemsadder.api.CustomEntity;
import dev.lone.itemsadder.api.CustomPlayer;
import dev.lone.itemsadder.api.Events.PlayerEmotePlayEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmoteSkinListenerTest {
    private final Player player = mock(Player.class);
    private final Plugin plugin = mock(Plugin.class);
    private final Logger logger = mock(Logger.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final PlayerProfile profile = mock(PlayerProfile.class);
    private final CustomPlayer rendered = mock(CustomPlayer.class);
    private final EmoteSkinListener listener = new EmoteSkinListener(plugin);

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.getPlayerProfile()).thenReturn(profile);
    }

    /** Fires the emote event and returns the task ItemsAdder's renderer is skinned in on the next tick. */
    private Runnable play(String emote) {
        PlayerEmotePlayEvent event = mock(PlayerEmotePlayEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEmoteName()).thenReturn(emote);
        listener.onEmotePlay(event);
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(plugin), task.capture());
        return task.getValue();
    }

    private itemsadder.m.dn renderer() throws Exception {
        itemsadder.m.dn renderer = mock(itemsadder.m.dn.class);
        Field field = CustomEntity.class.getDeclaredField("renderer");
        field.setAccessible(true);
        field.set(rendered, renderer);
        return renderer;
    }

    @Test
    void anyEmoteIsSkinnedFromTheActiveProfileOnTheNextTick() throws Exception {
        itemsadder.m.dn renderer = renderer();
        when(profile.getProperties()).thenReturn(Set.of(new ProfileProperty("textures", "wave-textures", "sig")));
        try (var api = mockStatic(CustomPlayer.class)) {
            Runnable task = play("wave");
            api.verifyNoInteractions();

            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(null);
            task.run();
            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(rendered);
            task.run();
            verify(renderer, never()).changePlayerSkin(anyString());

            when(rendered.isPlayingAnimation("wave")).thenReturn(true);
            task.run();
            verify(renderer).changePlayerSkin("wave-textures");

            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenThrow(new IllegalStateException("disabled"));
            assertDoesNotThrow(task::run);
            verify(logger).log(any(Level.class), anyString(), any(Throwable.class));
        }
    }

    @Test
    void missingProfileOrTexturesAndAMissingRendererLeaveTheSkinAlone() throws Exception {
        when(rendered.isPlayingAnimation("wave")).thenReturn(true);
        var other = new ProfileProperty("other", "x", "sig");
        try (var api = mockStatic(CustomPlayer.class)) {
            api.when(() -> CustomPlayer.byAlreadySpawned(player)).thenReturn(rendered);
            Runnable task = play("wave");
            when(player.getPlayerProfile()).thenReturn(null);
            task.run();
            when(player.getPlayerProfile()).thenReturn(profile);
            when(profile.getProperties())
                    .thenReturn(new LinkedHashSet<>(List.of(other, new ProfileProperty("textures", "", "sig"))));
            task.run();
            when(profile.getProperties()).thenReturn(Set.of(other));
            task.run();
            // No renderer yet: the textures are found but there is nothing to push them to.
            when(profile.getProperties()).thenReturn(Set.of(new ProfileProperty("textures", "value", "sig")));
            task.run();
            verifyNoInteractions(logger);

            itemsadder.m.dn renderer = renderer();
            doThrow(new IllegalStateException("gone")).when(renderer).changePlayerSkin("value");
            assertDoesNotThrow(task::run);
            verify(logger).log(any(Level.class), anyString(), any(Throwable.class));
        }
    }
}
