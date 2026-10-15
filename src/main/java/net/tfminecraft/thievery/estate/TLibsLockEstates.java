package net.tfminecraft.thievery.estate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;

/**
 * The TLibs estate API, loaded only after {@link EstateBridge} has probed it. Locks move only in the boot pass, so
 * {@link #settle} never moves anything: it answers from that boot's report, or {@code AT_RESTART}.
 */
final class TLibsLockEstates implements LockEstates, EstateParticipant {

    static final String ID = "thievery";

    // This boot's reports by death, so a later settle can answer for the records the pass observed.
    private final Map<UUID, Report> reports = new HashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Estate> decided() {
        List<Estate> estates = new ArrayList<>();
        for (EstateDecision decision : Estates.decided()) {
            if (Estates.guardHolds(decision)) {
                estates.add(estate(decision));
            }
        }
        return estates;
    }

    @Override
    public Optional<Estate> current(UUID deathId) {
        return Estates.decision(deathId).filter(Estates::guardHolds).map(TLibsLockEstates::estate);
    }

    @Override
    public Optional<Estate> ofOwner(UUID characterId) {
        if (!Estates.stateOf(characterId).equals(Optional.of(EstateState.DECIDED))) {
            return Optional.empty();
        }
        return Characters.deathId(characterId).flatMap(this::current);
    }

    @Override
    public boolean report(Report report) {
        Estate estate = report.estate();
        reports.put(estate.deathId(), report);
        return Estates.report(estate.deathId(), ID, estate.revision(), outcome(report));
    }

    @Override
    public void register(Plugin plugin) {
        Bukkit.getServicesManager().register(EstateParticipant.class, this, plugin, ServicePriority.Normal);
    }

    /** The locks the character owns now, for the will screen; see {@link LockHoldings}. */
    @Override
    public Holdings describe(UUID characterId) {
        LockHoldings.Summary summary = LockHoldings.describe(characterId);
        return summary.labels().isEmpty() ? Holdings.NONE : new Holdings(summary.counts(), summary.labels());
    }

    /**
     * DONE when no part going to a character still holds a record of the testator's, as this boot's pass observed
     * it. A dead testator gains no locks, so that answer also holds for a later revision. Otherwise the records
     * move at the next restart. A decision whose guard fails (revived, reverted or stale) gets RETRY.
     */
    @Override
    public Outcome settle(EstateDecision decision) {
        if (!Estates.guardHolds(decision)) {
            return Outcome.retry("the estate does not apply now: revived, reverted or a newer revision");
        }
        Report boot = reports.get(decision.deathId());
        boolean retried = boot == null || boot.kind() == Report.Kind.RETRY;
        boolean property = decision.property().isCharacter() && (retried || boot.leftProperty() > 0);
        boolean guild = decision.guildLocks().isCharacter() && (retried || boot.leftGuild() > 0);
        if (property || guild) {
            return Outcome.AT_RESTART;
        }
        return boot == null ? Outcome.done() : Outcome.done(boot.counts(), boot.moved());
    }

    static Outcome outcome(Report report) {
        return switch (report.kind()) {
            case DONE -> Outcome.done(report.counts(), report.moved());
            case RETRY -> Outcome.retry(report.reason());
            case AT_RESTART -> Outcome.AT_RESTART;
        };
    }

    static Estate estate(EstateDecision decision) {
        return new Estate(decision.deathId(), decision.testator().characterId(), decision.revision(),
                character(decision.property()), character(decision.guildLocks()));
    }

    private static UUID character(Recipient recipient) {
        return recipient.character().map(CharacterRef::characterId).orElse(null);
    }
}
