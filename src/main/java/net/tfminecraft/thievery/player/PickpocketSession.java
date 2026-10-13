package net.tfminecraft.thievery.player;

import java.util.UUID;

import net.tfminecraft.thievery.steal.session.HiddenStealSession;
import net.tfminecraft.thievery.steal.StealBudget;
import net.tfminecraft.thievery.steal.StealGui;

public class PickpocketSession extends HiddenStealSession {

    private final UUID pickpocketId;
    private final UUID victimId;

    public PickpocketSession(UUID pickpocketId, UUID victimId, String targetKey, StealBudget budget,
            StealGui.Layout layout) {
        super(budget, layout, targetKey);
        this.pickpocketId = pickpocketId;
        this.victimId = victimId;
    }

    public UUID getPickpocketId() {
        return pickpocketId;
    }

    public UUID getVictimId() {
        return victimId;
    }
}
