package net.tfminecraft.thievery.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.estate.LockEstates.Estate;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockStores;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.owner.OwnerCodec;
import net.tfminecraft.thievery.estate.LockEstates.Report;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.EstateState;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;

class TLibsLockEstatesTest {
    MockedStatic<Estates> estates;
    MockedStatic<Characters> characters;
    TLibsLockEstates source;

    final UUID account = UUID.randomUUID();
    final UUID dead = UUID.randomUUID();
    final UUID heir = UUID.randomUUID();
    final UUID leader = UUID.randomUUID();
    final UUID deathId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        estates = mockStatic(Estates.class);
        characters = mockStatic(Characters.class);
        source = new TLibsLockEstates();
    }

    @AfterEach
    void tearDown() {
        characters.close();
        estates.close();
    }

    Recipient to(UUID character) {
        return Recipient.character(new CharacterRef(account, character));
    }

    EstateDecision decision(UUID death, Recipient property, Recipient guildLocks, long revision) {
        return new EstateDecision(death, new CharacterRef(account, dead), Optional.empty(), Instant.EPOCH,
                Optional.empty(), Optional.empty(), Optional.empty(), Recipient.FROZEN, property, guildLocks,
                Recipient.SENDER, revision);
    }

    Report report(Report.Kind kind, int leftProperty, int leftGuild) {
        return new Report(new Estate(deathId, dead, 2, heir, leader), kind, Map.of("locks", 2),
                List.of("data/0_0/1_64_1.json", "data/0_0/2_64_1.json"), kind == Report.Kind.RETRY ? "failed" : null,
                leftProperty, leftGuild);
    }

    @Test
    void decidedEstatesWhoseGuardHoldsBecomeLockEstatesWithCharacterRecipientsOnly() {
        EstateDecision held = decision(deathId, to(heir), Recipient.WAITING, 2);
        EstateDecision revived = decision(UUID.randomUUID(), to(heir), to(leader), 0);
        estates.when(Estates::decided).thenReturn(List.of(held, revived));
        estates.when(() -> Estates.guardHolds(held)).thenReturn(true);

        assertEquals(List.of(new Estate(deathId, dead, 2, heir, null)), source.decided());
        Estate frozen = TLibsLockEstates.estate(decision(deathId, Recipient.FROZEN, to(leader), 1));
        assertNull(frozen.property());
        assertEquals(leader, frozen.recipient(LockState.GUILD));
        assertEquals(leader, frozen.recipient(LockState.FACTION));
        assertNull(frozen.recipient(LockState.PRIVATE));
        assertNull(frozen.recipient(null), "doors go with property");
    }

    @Test
    void currentAndOwnerLookupsNeedADecidedEstateWhoseGuardHolds() {
        EstateDecision held = decision(deathId, to(heir), to(leader), 1);
        estates.when(() -> Estates.decision(deathId)).thenReturn(Optional.of(held));
        estates.when(() -> Estates.decision(argThat(id -> !deathId.equals(id)))).thenReturn(Optional.empty());
        estates.when(() -> Estates.guardHolds(held)).thenReturn(true);
        assertEquals(Optional.of(new Estate(deathId, dead, 1, heir, leader)), source.current(deathId));
        assertEquals(Optional.empty(), source.current(UUID.randomUUID()));

        estates.when(() -> Estates.stateOf(dead)).thenReturn(Optional.of(EstateState.DECIDED));
        characters.when(() -> Characters.deathId(dead)).thenReturn(Optional.of(deathId));
        assertEquals(deathId, source.ofOwner(dead).orElseThrow().deathId());

        estates.when(() -> Estates.guardHolds(held)).thenReturn(false);
        assertEquals(Optional.empty(), source.current(deathId), "revived, reverted or a newer revision");
        estates.when(() -> Estates.stateOf(dead)).thenReturn(Optional.of(EstateState.OPEN));
        assertEquals(Optional.empty(), source.ofOwner(dead));
        estates.when(() -> Estates.stateOf(heir)).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), source.ofOwner(heir));
        characters.verify(() -> Characters.deathId(heir), never());
    }

    @Test
    void reportsCarryTheirRevisionAndMapToOutcomes() {
        estates.when(() -> Estates.report(any(), any(), anyLong(), any())).thenReturn(true);
        assertTrue(source.report(report(Report.Kind.DONE, 0, 0)));
        estates.verify(() -> Estates.report(deathId, "thievery", 2L,
                Outcome.done(Map.of("locks", 2), List.of("data/0_0/1_64_1.json", "data/0_0/2_64_1.json"))));
        assertEquals(Outcome.retry("failed"), TLibsLockEstates.outcome(report(Report.Kind.RETRY, 0, 0)));
        assertEquals(Outcome.AT_RESTART, TLibsLockEstates.outcome(report(Report.Kind.AT_RESTART, 1, 0)));
        assertEquals("thievery", source.id());
    }

    @Test
    void settleAtRuntimeAnswersFromTheBootPassOrAtRestart() {
        estates.when(() -> Estates.guardHolds(any())).thenReturn(true);
        // Nothing seen at boot: a part going to a character waits for a restart; frozen and waiting parts are done.
        assertEquals(Outcome.AT_RESTART, source.settle(decision(deathId, to(heir), Recipient.FROZEN, 0)));
        assertEquals(Outcome.AT_RESTART, source.settle(decision(deathId, Recipient.WAITING, to(leader), 0)));
        assertEquals(Outcome.done(), source.settle(decision(deathId, Recipient.FROZEN, Recipient.WAITING, 0)));

        estates.when(() -> Estates.report(any(), any(), anyLong(), any())).thenReturn(false);
        // Boot moved the property and left two guild locks for a waiting part.
        assertFalse(source.report(report(Report.Kind.DONE, 0, 2)), "no estate service received it");
        Outcome done = Outcome.done(Map.of("locks", 2), List.of("data/0_0/1_64_1.json", "data/0_0/2_64_1.json"));
        assertEquals(done, source.settle(decision(deathId, to(heir), Recipient.WAITING, 2)));
        // A later revision gives those guild locks to a leader: they move at the next restart.
        assertEquals(Outcome.AT_RESTART, source.settle(decision(deathId, to(heir), to(leader), 3)));
        // Or freezes them: nothing more to move.
        assertEquals(done, source.settle(decision(deathId, to(heir), Recipient.FROZEN, 3)));

        // Boot left property records behind, or the planner failed.
        source.report(report(Report.Kind.AT_RESTART, 1, 0));
        assertEquals(Outcome.AT_RESTART, source.settle(decision(deathId, to(heir), Recipient.WAITING, 2)));
        assertEquals(done, source.settle(decision(deathId, Recipient.FROZEN, to(leader), 2)));
        source.report(report(Report.Kind.RETRY, 0, 0));
        assertEquals(Outcome.AT_RESTART, source.settle(decision(deathId, Recipient.FROZEN, to(leader), 2)));
        assertEquals(Outcome.AT_RESTART, source.settle(decision(deathId, to(heir), Recipient.FROZEN, 2)));
        assertEquals(done, source.settle(decision(deathId, Recipient.FROZEN, Recipient.FROZEN, 2)));
    }

    @Test
    void settleNeverAnswersDoneForADecisionWhoseGuardFails() {
        estates.when(() -> Estates.report(any(), any(), anyLong(), any())).thenReturn(true);
        source.report(report(Report.Kind.DONE, 0, 0));
        EstateDecision reverted = decision(deathId, to(heir), to(leader), 2);
        estates.when(() -> Estates.guardHolds(reverted)).thenReturn(false);
        Outcome outcome = source.settle(reverted);
        assertEquals(Outcome.Kind.RETRY, outcome.kind());
        assertTrue(((Outcome.Retry) outcome).reason().contains("revived, reverted"));
        estates.when(() -> Estates.guardHolds(reverted)).thenReturn(true);
        assertEquals(Outcome.Kind.DONE, source.settle(reverted).kind());
    }

    @Test
    void describeListsTheLocksTheCharacterOwnsNow() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertSame(Holdings.NONE, source.describe(heir));
            OwnedLocks.put(LockStores.Store.CONTAINERS, Path.of("plugins/Thievery/data/0_0/1_64_1.json"),
                    new OwnerCodec.Stored(heir, LockOwner.Kind.CHARACTER), LockState.FACTION, null);
            assertEquals(new Holdings(Map.of("guild-locks", 1), List.of("Container at 1 64 1 (faction)")),
                    source.describe(heir));
        }
    }

    @Test
    void registersAsAnEstateParticipant() {
        ServicesManager services = mock(ServicesManager.class);
        Plugin plugin = mock(Plugin.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getServicesManager).thenReturn(services);
            source.register(plugin);
        }
        verify(services).register(EstateParticipant.class, source, plugin, ServicePriority.Normal);
    }
}
