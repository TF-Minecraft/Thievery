package net.tfminecraft.thievery.robbery;

import java.util.UUID;

import net.tfminecraft.thievery.steal.session.StealSession;
import net.tfminecraft.thievery.steal.StealBudget;
import net.tfminecraft.thievery.steal.StealGui;
import net.tfminecraft.tlibs.character.CharacterSession;

public class RobberySession extends StealSession {

    public enum State {
        PENDING_ACCEPT,
        ACTIVE
    }

    private final UUID robberId;
    private final UUID victimId;
    private State state;
    private long acceptDeadlineMs;
    private long activeEndMs;
    private CharacterSession robberCharacter;
    private CharacterSession victimCharacter;

    public RobberySession(UUID robberId, UUID victimId, StealBudget budget, StealGui.Layout layout) {
        super(budget, layout);
        this.robberId = robberId;
        this.victimId = victimId;
        this.state = State.PENDING_ACCEPT;
    }

    public UUID getRobberId() {
        return robberId;
    }

    public UUID getVictimId() {
        return victimId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public long getAcceptDeadlineMs() {
        return acceptDeadlineMs;
    }

    public void setAcceptDeadlineMs(long acceptDeadlineMs) {
        this.acceptDeadlineMs = acceptDeadlineMs;
    }

    public long getActiveEndMs() {
        return activeEndMs;
    }

    public void setActiveEndMs(long activeEndMs) {
        this.activeEndMs = activeEndMs;
    }

    /** The character sessions both players were in when the request was made; null means none was active. */
    public void setCharacters(CharacterSession robberCharacter, CharacterSession victimCharacter) {
        this.robberCharacter = robberCharacter;
        this.victimCharacter = victimCharacter;
    }

    public CharacterSession getRobberCharacter() {
        return robberCharacter;
    }

    public CharacterSession getVictimCharacter() {
        return victimCharacter;
    }
}
