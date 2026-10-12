package net.tfminecraft.thievery.inspection;

import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import dev.lone.itemsadder.api.CustomEntity;
import dev.lone.itemsadder.api.CustomPlayer;
import dev.lone.itemsadder.api.Events.PlayerEmoteEndEvent;
import dev.lone.itemsadder.api.Events.PlayerEmotePlayEvent;

/** Loaded only when ItemsAdder is enabled. */
public final class ItemsAdderHandsUpAnimation implements HandsUpAnimation, Listener {
    public static final String EMOTE = "tfmc_handsup";
    private final Plugin plugin;
    private final HandsUpManager manager;

    public ItemsAdderHandsUpAnimation(Plugin plugin, HandsUpManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean start(Player player) {
        try {
            CustomPlayer.playEmote(player, EMOTE);
            CustomPlayer animated = CustomPlayer.byAlreadySpawned(player);
            if (animated == null || !animated.isPlayingAnimation(EMOTE)) {
                return false;
            }
            applyActiveSkin(animated, player);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not start the hands-up emote.", exception);
            return false;
        }
    }

    @Override
    public void stop(Player player) {
        try {
            CustomPlayer animated = CustomPlayer.byAlreadySpawned(player);
            if (animated != null && animated.isPlayingAnimation(EMOTE)) {
                CustomPlayer.stopEmote(player);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not stop the hands-up emote.", exception);
        }
    }

    // ItemsAdder 4.0.18 skins emote renderers from the player name (its Mojang skin),
    // so push the profile textures active now through its internal changePlayerSkin.
    private void applyActiveSkin(CustomPlayer animated, Player player) {
        try {
            PlayerProfile profile = player.getPlayerProfile();
            if (profile == null) {
                return;
            }
            String textures = null;
            for (ProfileProperty property : profile.getProperties()) {
                if ("textures".equals(property.getName())) {
                    textures = property.getValue();
                    break;
                }
            }
            if (textures == null || textures.isEmpty()) {
                return;
            }
            java.lang.reflect.Field rendererField = CustomEntity.class.getDeclaredField("renderer");
            rendererField.setAccessible(true);
            Object renderer = rendererField.get(animated);
            if (renderer != null) {
                renderer.getClass().getMethod("changePlayerSkin", String.class).invoke(renderer, textures);
            }
        } catch (RuntimeException | ReflectiveOperationException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not apply the active skin to the hands-up emote.", exception);
        }
    }

    // ItemsAdder creates the renderer after this event, so skin it on the next tick.
    // This covers emotes started outside /handsup as well as the pose itself.
    @EventHandler(ignoreCancelled = true)
    public void onEmotePlay(PlayerEmotePlayEvent event) {
        Player player = event.getPlayer();
        String emote = event.getEmoteName();
        plugin.getServer().getScheduler().runTask(plugin, () -> skinPlayingEmote(player, emote));
    }

    private void skinPlayingEmote(Player player, String emote) {
        try {
            CustomPlayer animated = CustomPlayer.byAlreadySpawned(player);
            if (animated != null && animated.isPlayingAnimation(emote)) {
                applyActiveSkin(animated, player);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not apply the active skin to an emote.", exception);
        }
    }

    @EventHandler
    public void onEmoteEnd(PlayerEmoteEndEvent event) {
        if (EMOTE.equals(event.getEmoteName())) {
            // ItemsAdder emits this before it removes the renderer; do not stop it again.
            manager.animationEnded(event.getPlayer());
        }
    }
}
