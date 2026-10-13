package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.estate.LockEstates;
import net.tfminecraft.thievery.estate.LockEstates.Estate;
import net.tfminecraft.thievery.estate.LockEstates.Report;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.tlibs.character.CharacterStatus;

/** The estate half of the boot lock pass (design R10, M6, N4). */
class EstateLockPassTest {
    @TempDir Path temp;
    LockStores stores;
    Logger log;
    FactionFixture identity;
    FakeEstates estates;

    final UUID dead = UUID.randomUUID();
    final UUID heir = UUID.randomUUID();
    final UUID leader = UUID.randomUUID();
    final UUID other = UUID.randomUUID();
    final UUID account = UUID.randomUUID();
    final UUID thief = UUID.randomUUID();
    final UUID deathId = UUID.randomUUID();

    static class FakeEstates implements LockEstates {
        final List<Estate> decided = new ArrayList<>();
        final Map<UUID, Estate> current = new HashMap<>();

        FakeEstates add(Estate estate) {
            decided.add(estate);
            current.put(estate.deathId(), estate);
            return this;
        }

        @Override
        public List<Estate> decided() {
            return decided;
        }

        @Override
        public Optional<Estate> current(UUID deathId) {
            return Optional.ofNullable(current.get(deathId));
        }

        @Override
        public Optional<Estate> ofOwner(UUID characterId) {
            return Optional.empty();
        }

        @Override
        public boolean report(Report report) {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        stores = new LockStores(temp.resolve("data"), temp.resolve("door-data"), temp.resolve("entities"),
                temp.resolve("migrations"));
        log = mock(Logger.class);
        identity = new FactionFixture();
        // The dead testator is still a character, so a bare value of theirs only gains its marker.
        identity.identity().when(() -> OwnerIdentity.info(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return id.equals(account) ? Optional.empty()
                    : Optional.of(FactionFixture.info(id, account, id.equals(dead) ? CharacterStatus.DEAD : CharacterStatus.ALIVE));
        });
        identity.identity().when(() -> OwnerIdentity.heir(account)).thenReturn(Optional.of(dead));
        estates = new FakeEstates();
    }

    @AfterEach
    void tearDown() {
        identity.close();
    }

    Estate estate(UUID property, UUID guildLocks) {
        return new Estate(deathId, dead, 3, property, guildLocks);
    }

    LockMigration migration() {
        return new LockMigration(stores, log, estates);
    }

    Path container(String name, String json) throws IOException {
        return LockMigrationTest.write(temp.resolve("data/0_0").resolve(name), json);
    }

    String marked(UUID owner, String state) {
        return "{\"owner\":\"" + owner + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"" + state
                + "\",\"accessMap\":{\"" + thief + "\":\"2026-10-01\"}}";
    }

    String moved(UUID owner, String state) {
        return marked(owner, state);
    }

    static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    LockMigration.Journal journal(int number) throws IOException {
        return LockMigration.readJournal(temp.resolve("migrations").resolve(String.format("journal-%04d.json", number)));
    }

    Report only(LockMigration.Outcome outcome) {
        assertEquals(1, outcome.estates().size());
        return outcome.estates().getFirst();
    }

    @Test
    void everyRecordMovesToTheRecipientOfItsPartWithItsStateAndCooldownsKept() throws Exception {
        estates.add(estate(heir, leader));
        Path privateChest = container("1_64_1.json", marked(dead, "PRIVATE"));
        Path publicChest = container("3_64_1.json", marked(dead, "PUBLIC"));
        Path guildChest = container("5_64_1.json", marked(dead, "GUILD"));
        Path factionChest = container("7_64_1.json", marked(dead, "FACTION"));
        Path door = LockMigrationTest.write(temp.resolve("door-data/0_0/9_64_1.json"),
                "{\"key\":\"k\",\"strength\":0.5,\"ownerUUID\":\"" + dead + "\",\"ownerKind\":\"CHARACTER\"}");
        UUID standId = UUID.randomUUID(), frameId = UUID.randomUUID();
        Path stand = LockMigrationTest.write(temp.resolve("entities/" + standId + ".json"),
                "{\"owner\":\"" + dead + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"PUBLIC\"}");
        Path frame = LockMigrationTest.write(temp.resolve("entities/" + frameId + ".json"),
                "{\"owner\":\"" + dead + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"FACTION\"}");
        Path someoneElse = container("20_64_1.json", marked(other, "PRIVATE"));
        Path server = container("22_64_1.json", "{\"owner\":\"00000000-0000-0000-0000-000000000000\",\"lockState\":\"PRIVATE\"}");
        Path unreadable = container("24_64_1.json", "{\"owner\":");

        LockMigration.Outcome outcome = migration().run();

        assertEquals(moved(heir, "PRIVATE"), read(privateChest));
        assertEquals(moved(heir, "PUBLIC"), read(publicChest));
        assertEquals(moved(leader, "GUILD"), read(guildChest));
        assertEquals(moved(leader, "FACTION"), read(factionChest));
        assertEquals("{\"key\":\"k\",\"strength\":0.5,\"ownerUUID\":\"" + heir + "\",\"ownerKind\":\"CHARACTER\"}", read(door));
        assertEquals("{\"owner\":\"" + heir + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"PUBLIC\"}", read(stand));
        assertEquals("{\"owner\":\"" + leader + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"FACTION\"}", read(frame));
        assertEquals(marked(other, "PRIVATE"), read(someoneElse));
        assertTrue(read(server).contains("SERVER"), "the 6a entry shares the pass");
        assertEquals("{\"owner\":", read(unreadable));
        assertEquals(1, outcome.migrated());
        assertEquals(7, outcome.estateMoved());

        LockMigration.Journal journal = journal(1);
        assertTrue(journal.completed);
        assertEquals(8, journal.entries.size());
        assertEquals("migrate", journal.entries.getFirst().kind);
        assertNull(journal.entries.getFirst().deathId);
        for (LockMigration.JournalEntry entry : journal.entries.subList(1, 8)) {
            assertEquals("estate", entry.kind);
            assertEquals(deathId.toString(), entry.deathId);
            assertEquals(dead.toString(), entry.previousOwner);
            assertEquals("CHARACTER", entry.ownerKind);
            assertEquals(entry.newHash, DurableFiles.sha256(Files.readAllBytes(stores.resolve(entry.path).path())));
        }
        try (ZipFile archive = new ZipFile(temp.resolve("migrations/backup/locks-0001.zip").toFile())) {
            assertEquals(10, archive.size(), "a pass with 6a entries archives the whole store");
        }

        Report report = only(outcome);
        assertEquals(Report.Kind.DONE, report.kind());
        assertEquals(Map.of("locks", 6, "doors", 1), report.counts());
        assertEquals(7, report.moved().size());
        assertTrue(report.moved().contains("door-data/0_0/9_64_1.json"));
        assertEquals(0, report.leftProperty());
        assertEquals(0, report.leftGuild());
        verify(log).info(contains("7 moved for 1 decided estates"));

        // A second boot finds nothing left and answers DONE again from the journal.
        LockMigration.Outcome second = migration().run();
        assertEquals(0, second.estateMoved());
        assertFalse(Files.exists(temp.resolve("migrations/journal-0002.json")));
        assertEquals(report.counts(), only(second).counts());
        assertEquals(Report.Kind.DONE, only(second).kind());
    }

    @Test
    void waitingAndFrozenPartsAreNotPlannedAndAnEstateOnlyPassArchivesWhatItJournals() throws Exception {
        estates.add(estate(heir, null));
        Path privateChest = container("1_64_1.json", marked(dead, "PRIVATE"));
        Path guildChest = container("5_64_1.json", marked(dead, "GUILD"));
        container("7_64_1.json", marked(other, "PRIVATE"));

        LockMigration.Outcome outcome = migration().run();

        assertEquals(moved(heir, "PRIVATE"), read(privateChest));
        assertEquals(marked(dead, "GUILD"), read(guildChest), "a waiting part stays with the testator");
        try (ZipFile archive = new ZipFile(temp.resolve("migrations/backup/locks-0001.zip").toFile())) {
            assertEquals(1, archive.size());
            assertNotNull(archive.getEntry("data/0_0/1_64_1.json"));
        }
        Report report = only(outcome);
        assertEquals(Report.Kind.DONE, report.kind(), "the waiting part holds the revive gate on its own");
        assertEquals(1, report.leftGuild());
        assertEquals("1\n", read(temp.resolve("entities/data-version")));

        // Once the guild part goes to a leader, the next boot moves it.
        estates.decided.clear();
        estates.add(new Estate(deathId, dead, 4, heir, leader));
        Report later = only(migration().run());
        assertEquals(moved(leader, "GUILD"), read(guildChest));
        assertEquals(Report.Kind.DONE, later.kind());
        assertEquals(List.of("data/0_0/1_64_1.json", "data/0_0/5_64_1.json"), later.moved());
        assertTrue(journal(2).completed);
    }

    @Test
    void aFrozenEstateOrOneWithNothingToMoveWritesNoJournalAndIsDone() throws Exception {
        UUID empty = UUID.randomUUID();
        estates.add(estate(null, null));
        estates.add(new Estate(UUID.randomUUID(), empty, 0, heir, leader));
        Path chest = container("1_64_1.json", marked(dead, "PRIVATE"));

        LockMigration.Outcome outcome = migration().run();

        assertEquals(marked(dead, "PRIVATE"), read(chest));
        assertFalse(Files.exists(temp.resolve("migrations/journal-0001.json")));
        assertEquals("1\n", read(temp.resolve("data/data-version")));
        assertEquals(2, outcome.estates().size());
        for (Report report : outcome.estates()) {
            assertEquals(Report.Kind.DONE, report.kind());
            assertEquals(Map.of(), report.counts());
            assertEquals(List.of(), report.moved());
        }
        assertEquals(1, outcome.estates().getFirst().leftProperty());
    }

    @Test
    void doubleChestHalvesMoveTogetherAndFilesThe6aPlannerClaimsWaitForTheNextBoot() throws Exception {
        UUID second = UUID.randomUUID();
        estates.add(estate(heir, leader));
        estates.add(new Estate(UUID.randomUUID(), second, 0, heir, leader));
        // A double chest of the testator's: both halves move.
        Path left = container("1_64_1.json", marked(dead, "PRIVATE"));
        Path right = container("2_64_1.json", marked(dead, "PRIVATE"));
        // A bare value of the testator's beside two marked ones: the 6a planner claims it, and the two wait.
        Path bare = container("10_64_1.json", "{\"owner\":\"" + dead + "\",\"lockState\":\"PRIVATE\"}");
        Path beside = container("11_64_1.json", marked(dead, "PRIVATE"));
        Path joined = container("12_64_1.json", marked(dead, "PRIVATE"));
        // An account whose heir is the testator, beside a chest of the testator's.
        Path legacy = container("10_64_5.json", "{\"owner\":\"" + account + "\",\"lockState\":\"GUILD\"}");
        Path nextToLegacy = LockMigrationTest.write(temp.resolve("data/0_1/10_64_6.json"), marked(dead, "GUILD"));
        // Another testator's chest beside the bare value moves anyway.
        Path neighbour = container("10_64_0.json", marked(second, "PRIVATE"));
        // A container file that names no block still moves.
        Path oddName = container("notes.json", marked(dead, "PUBLIC"));

        LockMigration.Outcome outcome = migration().run();

        assertEquals(moved(heir, "PRIVATE"), read(left));
        assertEquals(moved(heir, "PRIVATE"), read(right));
        assertEquals("{\"owner\":\"" + dead + "\",\"lockState\":\"PRIVATE\",\"ownerKind\":\"CHARACTER\"}", read(bare));
        assertEquals(marked(dead, "PRIVATE"), read(beside));
        assertEquals(marked(dead, "PRIVATE"), read(joined));
        assertTrue(read(legacy).contains(dead + "\""));
        assertEquals(marked(dead, "GUILD"), read(nextToLegacy));
        assertEquals(moved(heir, "PRIVATE"), read(neighbour));
        assertEquals(moved(heir, "PUBLIC"), read(oddName));
        assertEquals(2, outcome.migrated());

        Report first = outcome.estates().getFirst();
        assertEquals(Report.Kind.AT_RESTART, first.kind());
        assertEquals(3, first.leftProperty());
        assertEquals(2, first.leftGuild());
        assertEquals(List.of("data/0_0/1_64_1.json", "data/0_0/2_64_1.json", "data/0_0/notes.json"), first.moved());
        assertEquals(Report.Kind.DONE, outcome.estates().get(1).kind());

        Report after = migration().run().estates().getFirst();
        assertEquals(Report.Kind.DONE, after.kind());
        assertEquals(moved(heir, "PRIVATE"), read(beside));
        assertEquals(moved(heir, "PRIVATE"), read(joined));
        assertEquals("{\"owner\":\"" + heir + "\",\"lockState\":\"PRIVATE\",\"ownerKind\":\"CHARACTER\"}", read(bare));
        assertEquals(marked(leader, "GUILD"), read(nextToLegacy));
        assertEquals(Map.of("locks", 8), after.counts());
    }

    @Test
    void aGuildLockStillWithTheTestatorAfterThePassWaitsForARestart() throws Exception {
        estates.add(estate(heir, leader));
        Path bare = container("1_64_1.json", "{\"owner\":\"" + dead + "\",\"lockState\":\"FACTION\"}");

        Report report = only(migration().run());

        assertEquals(Report.Kind.AT_RESTART, report.kind());
        assertEquals(0, report.leftProperty());
        assertEquals(1, report.leftGuild());
        assertEquals(Report.Kind.DONE, only(migration().run()).kind());
        assertTrue(read(bare).contains(leader.toString()));
    }

    @Test
    void aPlannerFailureLeavesThePassWithIts6aEntriesOnly() throws Exception {
        Path bare = container("1_64_1.json", "{\"owner\":\"" + other + "\",\"lockState\":\"PRIVATE\"}");
        Path chest = container("3_64_1.json", marked(dead, "PRIVATE"));
        LockEstates failing = new FakeEstates() {
            @Override
            public List<Estate> decided() {
                throw new IllegalStateException("estate service gone");
            }
        };
        LockMigration.Outcome outcome = new LockMigration(stores, log, failing).run();
        assertTrue(read(bare).contains("CHARACTER"));
        assertEquals(marked(dead, "PRIVATE"), read(chest));
        assertEquals(List.of("migrate"), journal(1).entries.stream().map(entry -> entry.kind).toList());
        assertEquals(0, outcome.estateMoved());
        assertEquals(List.of(), outcome.estates(), "no estate was known, so none is reported");
        verify(log).log(eq(Level.WARNING), contains("estate lock planner failed"), any(IllegalStateException.class));

        // A missing TLibs class is contained the same way.
        LockEstates missing = new FakeEstates() {
            @Override
            public List<Estate> decided() {
                throw new NoClassDefFoundError("net/tfminecraft/tlibs/character/estate/Estates");
            }
        };
        assertEquals(List.of(), new LockMigration(stores, log, missing).run().estates());
        assertEquals(marked(dead, "PRIVATE"), read(chest));
        verify(log).log(eq(Level.WARNING), contains("estate lock planner failed"), any(NoClassDefFoundError.class));
    }

    @Test
    void aPlannerFailureWithKnownEstatesReportsRetryForEach() throws Exception {
        estates.add(estate(heir, leader));
        @SuppressWarnings("unchecked")
        List<LockMigration.Scanned> scan = mock(List.class);
        when(scan.iterator()).thenThrow(new IllegalStateException("scan changed while planning"));

        EstatePlanner.Result result = EstatePlanner.plan(estates, scan, List.of(), log);

        assertEquals(List.of(), result.moves());
        assertTrue(result.failure().contains("scan changed while planning"));
        List<Report> reports = result.reports(stores, List.of(), List.of(), List.of());
        assertEquals(1, reports.size());
        assertEquals(Report.Kind.RETRY, reports.getFirst().kind());
        assertTrue(reports.getFirst().reason().startsWith("the estate lock planner failed: "));
    }

    @Test
    void aCrashedEstatePassIsReplayedWhileItsEstateStillApplies() throws Exception {
        estates.add(estate(heir, leader));
        Path first = container("1_64_1.json", marked(dead, "PRIVATE"));
        Path second = container("3_64_1.json", marked(dead, "GUILD"));
        Path third = container("5_64_1.json", marked(dead, "PUBLIC"));
        crashOnSecondWrite();
        assertEquals(moved(heir, "PRIVATE"), read(first));
        assertEquals(marked(dead, "GUILD"), read(second));
        Files.writeString(third, "{\"owner\":\"" + dead);

        LockMigration.Outcome replay = migration().run();

        assertEquals(1, replay.replayed());
        assertEquals(0, replay.estateMoved(), "the replay finished the pass");
        assertEquals(moved(heir, "PRIVATE"), read(first));
        assertEquals(moved(leader, "GUILD"), read(second));
        assertEquals(moved(heir, "PUBLIC"), read(third), "a torn file is taken from the archive and redone");
        assertTrue(journal(1).completed);
        Report report = only(replay);
        assertEquals(Report.Kind.DONE, report.kind());
        assertEquals(3, report.moved().size());
    }

    @Test
    void aReplayLeavesTheTestatorTheLocksOfAnEstateThatNoLongerApplies() throws Exception {
        estates.add(estate(heir, leader));
        Path first = container("1_64_1.json", marked(dead, "PRIVATE"));
        Path second = container("3_64_1.json", marked(dead, "GUILD"));
        Path third = container("5_64_1.json", marked(dead, "PUBLIC"));
        crashOnSecondWrite();
        Files.writeString(third, "{\"owner\":\"" + dead);
        // The testator was revived before the restart.
        estates.decided.clear();
        estates.current.clear();

        LockMigration.Outcome replay = migration().run();

        assertEquals(moved(heir, "PRIVATE"), read(first), "a committed move stays");
        assertEquals(marked(dead, "GUILD"), read(second));
        assertEquals(marked(dead, "PUBLIC"), read(third), "a torn file is restored, not moved");
        LockMigration.Journal journal = journal(1);
        assertTrue(journal.completed);
        assertNull(journal.entries.get(0).skipped);
        for (LockMigration.JournalEntry entry : journal.entries.subList(1, 3)) {
            assertEquals(Boolean.TRUE, entry.skipped);
            assertEquals(entry.originalHash, entry.newHash);
        }
        assertEquals(List.of(), replay.estates());
        verify(log, times(2)).warning(contains("skipped at replay"));
    }

    @Test
    void aReplayThatCannotCheckTheEstateLeavesTheLockAlone() throws Exception {
        estates.add(estate(heir, leader));
        container("1_64_1.json", marked(dead, "PRIVATE"));
        Path second = container("3_64_1.json", marked(dead, "GUILD"));
        crashOnSecondWrite();
        LockEstates broken = new FakeEstates() {
            @Override
            public Optional<Estate> current(UUID id) {
                throw new NoSuchMethodError("Estates.decision");
            }
        };

        new LockMigration(stores, log, broken).run();

        assertEquals(marked(dead, "GUILD"), read(second));
        assertEquals(Boolean.TRUE, journal(1).entries.get(1).skipped);
        verify(log).log(eq(Level.WARNING), contains("Could not check the estate of data/0_0/3_64_1.json"),
                any(NoSuchMethodError.class));
    }

    @Test
    void entriesWithoutAKindAreMigrationsAndJournalsKeepEstateFieldsForOldJars() throws Exception {
        estates.add(estate(heir, leader));
        container("1_64_1.json", marked(dead, "PRIVATE"));
        migration().run();
        Path path = temp.resolve("migrations/journal-0001.json");
        String text = read(path);
        assertTrue(text.contains("\"kind\": \"estate\""));
        assertTrue(text.contains("\"deathId\": \"" + deathId + "\""));
        assertFalse(text.contains("skipped"));
        // An older jar that completes this journal drops kind and deathId; the entry no longer counts as moved.
        LockMigration.Journal journal = journal(1);
        journal.entries.forEach(entry -> {
            entry.kind = null;
            entry.deathId = null;
        });
        Files.writeString(path, new com.google.gson.Gson().toJson(journal));
        Report report = only(migration().run());
        assertEquals(List.of(), report.moved());
        assertEquals(Report.Kind.DONE, report.kind());
    }

    // Runs a pass whose second lock-file write fails, leaving an incomplete journal.
    private void crashOnSecondWrite() {
        int[] writes = {0};
        try (MockedStatic<DurableFiles> durable = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            durable.when(() -> DurableFiles.write(argThat(path -> path.toString().contains("/data/0_0/")), any(), eq(false)))
                    .thenAnswer(call -> {
                        if (++writes[0] == 2) {
                            throw new IOException("disk full");
                        }
                        return call.callRealMethod();
                    });
            assertThrows(IOException.class, () -> migration().run());
        }
    }
}
