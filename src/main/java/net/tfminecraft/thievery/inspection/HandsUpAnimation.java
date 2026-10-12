package net.tfminecraft.thievery.inspection;

import org.bukkit.entity.Player;

/** Owns the visible pose paired with inspection consent. */
public interface HandsUpAnimation {
    boolean start(Player player);

    void stop(Player player);
}
