package net.tfminecraft.thievery.inspection;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;

/** Registered only when the TLibs character API is available. */
public final class HandsUpCharacterListener implements Listener {
    private final HandsUpManager manager;

    public HandsUpCharacterListener(HandsUpManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onCharacterSessionEnd(CharacterSessionEndEvent event) {
        manager.clearPlayer(event.getPlayer());
    }
}
