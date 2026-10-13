package net.tfminecraft.thievery.estate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.plugin.Plugin;

import net.tfminecraft.thievery.door.LockState;

/**
 * Decided estates as the lock code sees them. Only Thievery types cross this interface, so the lock pass and
 * furniture never load a TLibs estate class; {@link EstateBridge} installs the TLibs source when TLibs 2.6.0 or
 * later is present, and {@link #NONE} otherwise.
 */
public interface LockEstates {

    /**
     * A decided estate whose guard holds (design E6). {@code property} and {@code guildLocks} are the receiving
     * characters, or null while that part waits or is frozen.
     */
    record Estate(UUID deathId, UUID testator, long revision, UUID property, UUID guildLocks) {

        /** The character a lock in this state goes to, or null. Doors have no state and go with property. */
        public UUID recipient(LockState state) {
            return state == LockState.GUILD || state == LockState.FACTION ? guildLocks : property;
        }
    }

    /**
     * One estate's answer after a boot's lock pass. {@code leftProperty} and {@code leftGuild} count the records
     * the testator still owns after the pass in each part, whoever that part goes to.
     */
    record Report(Estate estate, Kind kind, Map<String, Integer> counts, List<String> moved, String reason,
            int leftProperty, int leftGuild) {

        public enum Kind {
            DONE,
            RETRY,
            AT_RESTART
        }
    }

    /** Every decided estate whose guard holds now. */
    List<Estate> decided();

    /** The estate of this death if it is decided and its guard holds now. */
    Optional<Estate> current(UUID deathId);

    /** The decided estate of a dead owner whose guard holds now. */
    Optional<Estate> ofOwner(UUID characterId);

    /** Reports a boot outcome; false when no estate service received it. */
    boolean report(Report report);

    /** Registers the estate participant once the locks are loaded. */
    default void register(Plugin plugin) {}

    /** Used without the TLibs estate API: no estates, so locks take no part. */
    LockEstates NONE = new LockEstates() {
        @Override
        public List<Estate> decided() {
            return List.of();
        }

        @Override
        public Optional<Estate> current(UUID deathId) {
            return Optional.empty();
        }

        @Override
        public Optional<Estate> ofOwner(UUID characterId) {
            return Optional.empty();
        }

        @Override
        public boolean report(Report report) {
            return false;
        }
    };
}
