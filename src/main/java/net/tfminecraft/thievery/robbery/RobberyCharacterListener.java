package net.tfminecraft.thievery.robbery;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;

/** Kept apart from {@link RobberyManager} so Thievery still registers its listeners on older TLibs. */
public class RobberyCharacterListener implements Listener {

    private final RobberyManager robberyManager;

    public RobberyCharacterListener(RobberyManager robberyManager) {
        this.robberyManager = robberyManager;
    }

    @EventHandler
    public void onCharacterSessionEnd(CharacterSessionEndEvent event) {
        robberyManager.characterSessionEnded(event.getPlayer());
    }
}
