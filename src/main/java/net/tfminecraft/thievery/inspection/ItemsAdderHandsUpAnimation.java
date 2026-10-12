package net.tfminecraft.thievery.inspection;

import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import dev.lone.itemsadder.api.CustomPlayer;
import dev.lone.itemsadder.api.Events.PlayerEmoteEndEvent;

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
            return animated != null && animated.isPlayingAnimation(EMOTE);
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

    @EventHandler
    public void onEmoteEnd(PlayerEmoteEndEvent event) {
        if (EMOTE.equals(event.getEmoteName())) {
            // ItemsAdder emits this before it removes the renderer; do not stop it again.
            manager.animationEnded(event.getPlayer());
        }
    }
}
