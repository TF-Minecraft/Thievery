package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.google.gson.Gson;

import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.tlibs.character.CharacterStatus;

class LockMigrationTest {
    @TempDir Path temp;
    LockStores stores;
    Logger log;
    FactionFixture identity;
    final Set<UUID> characters = new HashSet<>();
    final Map<UUID, UUID> heirs = new HashMap<>();
    final Set<UUID> unknownAccounts = new HashSet<>();

    final UUID kira = UUID.randomUUID();
    final UUID steve = UUID.randomUUID();
    final UUID steveHeir = UUID.randomUUID();
    final UUID orphan = UUID.randomUUID();
    final UUID unknown = UUID.randomUUID();
    final UUID thief = UUID.randomUUID();
    final UUID otherThief = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        stores = new LockStores(temp.resolve("data"), temp.resolve("door-data"), temp.resolve("entities"),
                temp.resolve("migrations"));
        log = mock(Logger.class);
        identity = new FactionFixture();
        characters.add(kira);
        characters.add(steveHeir);
        heirs.put(steve, steveHeir);
        unknownAccounts.add(unknown);
        identity.identity().when(() -> OwnerIdentity.info(any())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            return characters.contains(id) ? Optional.of(FactionFixture.info(id, steve, CharacterStatus.ALIVE)) : Optional.empty();
        });
        identity.identity().when(() -> OwnerIdentity.heir(any())).thenAnswer(call -> Optional.ofNullable(heirs.get(call.<UUID>getArgument(0))));
        identity.identity().when(() -> OwnerIdentity.accountKnown(any())).thenAnswer(call -> !unknownAccounts.contains(call.<UUID>getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        identity.close();
    }

    Path container(String chunk, String name, String json) throws IOException {
        return write(temp.resolve("data").resolve(chunk).resolve(name), json);
    }

    Path door(String name, String json) throws IOException {
        return write(temp.resolve("door-data").resolve("0_0").resolve(name), json);
    }

    Path entity(UUID id, String json) throws IOException {
        return write(temp.resolve("entities").resolve(id + ".json"), json);
    }

    static Path write(Path path, String json) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
        return path;
    }

    static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    LockMigration migration() {
        return new LockMigration(stores, log);
    }

    LockMigration.Journal journal(int number) throws IOException {
        return LockMigration.readJournal(temp.resolve("migrations").resolve(String.format("journal-%04d.json", number)));
    }

    // Old jars wrote accessMap from a HashMap; the rewrite must keep those bytes in their original order.
    String oldContainer(UUID owner, String state) {
        return "{\"owner\":\"" + owner + "\",\"lockState\":\"" + state + "\",\"accessMap\":{\"" + thief
                + "\":\"2026-10-01\",\"" + otherThief + "\":\"2026-10-02\"}}";
    }

    @Test
    void aFullPassMovesOwnersJournalsExactBytesAndQuarantinesWhatItCannotMove() throws Exception {
        Path character = container("0_0", "1_64_1.json", oldContainer(kira, "PRIVATE"));
        Path legacy = container("0_0", "2_64_1.json", oldContainer(steve, "GUILD"));
        Path server = container("0_0", "3_64_1.json", "{\"owner\":\"00000000-0000-0000-0000-000000000000\",\"lockState\":\"PRIVATE\"}");
        Path orphaned = container("1_0", "17_64_1.json", "{\"owner\":\"" + orphan + "\",\"lockState\":\"PRIVATE\"}");
        Path unknownOwner = container("1_0", "18_64_1.json", "{\"owner\":\"" + unknown + "\",\"lockState\":\"FACTION\"}");
        Path garbage = container("1_0", "19_64_1.json", "{\"owner\":");
        Path marked = container("1_0", "20_64_1.json", "{\"owner\":\"" + kira + "\",\"ownerKind\":\"CHARACTER\",\"lockState\":\"PUBLIC\"}");
        Path unowned = container("1_0", "21_64_1.json", "{\"lockState\":\"PUBLIC\",\"accessMap\":{}}");
        Path legacyDoor = door("4_64_4.json", "{\"key\":\"k\",\"strength\":0.5,\"ownerUUID\":\"" + steve + "\",\"unlockExpiryMs\":12}");
        UUID stand = UUID.randomUUID();
        Path legacyEntity = entity(stand, "{\"owner\":\"" + steve + "\",\"lockState\":\"FACTION\"}");
        Files.writeString(temp.resolve("data").resolve("0_0").resolve("notes.txt"), "ignored");
        String before = read(character);

        LockMigration.Outcome outcome = migration().run();

        assertEquals(5, outcome.migrated());
        assertEquals(2, outcome.quarantined());
        assertEquals(1, outcome.unreadable());
        assertEquals(0, outcome.replayed());
        assertEquals(10, outcome.files());
        assertEquals(before.replace("}}", "},\"ownerKind\":\"CHARACTER\"}"), read(character));
        assertEquals("{\"owner\":\"" + steveHeir + "\",\"lockState\":\"GUILD\",\"accessMap\":{\"" + thief
                + "\":\"2026-10-01\",\"" + otherThief + "\":\"2026-10-02\"},\"ownerKind\":\"CHARACTER\"}", read(legacy));
        assertEquals("{\"owner\":\"00000000-0000-0000-0000-000000000000\",\"lockState\":\"PRIVATE\",\"ownerKind\":\"SERVER\"}", read(server));
        assertEquals("{\"key\":\"k\",\"strength\":0.5,\"ownerUUID\":\"" + steveHeir + "\",\"unlockExpiryMs\":12,\"ownerKind\":\"CHARACTER\"}", read(legacyDoor));
        assertEquals("{\"owner\":\"" + steveHeir + "\",\"lockState\":\"FACTION\",\"ownerKind\":\"CHARACTER\"}", read(legacyEntity));
        assertEquals("{\"owner\":\"" + orphan + "\",\"lockState\":\"PRIVATE\"}", read(orphaned));
        assertEquals("{\"owner\":\"" + unknown + "\",\"lockState\":\"FACTION\"}", read(unknownOwner));
        assertEquals("{\"owner\":", read(garbage));
        assertTrue(read(marked).startsWith("{\"owner\":\"" + kira + "\",\"ownerKind\""));
        assertEquals("{\"lockState\":\"PUBLIC\",\"accessMap\":{}}", read(unowned));

        LockMigration.Journal journal = journal(1);
        assertTrue(journal.completed);
        assertEquals("backup/locks-0001.zip", journal.archive);
        assertEquals(5, journal.entries.size());
        for (LockMigration.JournalEntry entry : journal.entries) {
            Path file = stores.resolve(entry.path).path();
            assertEquals(entry.newHash, DurableFiles.sha256(Files.readAllBytes(file)), entry.path);
            assertNotEquals(entry.originalHash, entry.newHash);
            assertEquals("CHARACTER".equals(entry.ownerKind) || "SERVER".equals(entry.ownerKind), true);
        }
        LockMigration.JournalEntry legacyEntry = journal.entries.stream().filter(e -> e.path.equals("data/0_0/2_64_1.json")).findFirst().orElseThrow();
        assertEquals(steve.toString(), legacyEntry.previousOwner);
        assertEquals(steveHeir.toString(), legacyEntry.owner);
        assertEquals(DurableFiles.sha256(oldContainer(steve, "GUILD").getBytes(StandardCharsets.UTF_8)), legacyEntry.originalHash);

        try (ZipFile archive = new ZipFile(temp.resolve("migrations/backup/locks-0001.zip").toFile())) {
            assertEquals(10, archive.size());
            assertEquals(oldContainer(steve, "GUILD"), new String(archive.getInputStream(archive.getEntry("data/0_0/2_64_1.json")).readAllBytes()));
            assertEquals("{\"owner\":", new String(archive.getInputStream(archive.getEntry("data/1_0/19_64_1.json")).readAllBytes()));
            assertNotNull(archive.getEntry("entities/" + stand + ".json"));
        }
        for (String store : List.of("data", "door-data", "entities")) {
            assertEquals("1\n", read(temp.resolve(store).resolve("data-version")));
        }
        LockQuarantine.Contents quarantine = LockQuarantine.read(temp.resolve("migrations/quarantine.json"));
        assertEquals(List.of(new LockQuarantine.Entry("data/1_0/17_64_1.json", orphan.toString(), "no reviewed heir"),
                new LockQuarantine.Entry("data/1_0/18_64_1.json", unknown.toString(), "identity unknown")), quarantine.quarantined);
        assertEquals(1, quarantine.unreadable.size());
        assertEquals("data/1_0/19_64_1.json", quarantine.unreadable.getFirst().path());
        assertEquals(Map.of(orphan.toString(), 1, unknown.toString(), 1), LockQuarantine.snapshot().perOwner());
        verify(log).info(contains("5 migrated, 2 quarantined, 1 unreadable"));
    }

    @Test
    void aSecondBootChangesNothingAndNeverRetakesTheArchive() throws Exception {
        container("0_0", "1_64_1.json", oldContainer(steve, "PRIVATE"));
        container("0_0", "2_64_1.json", "{\"owner\":\"" + orphan + "\",\"lockState\":\"PRIVATE\"}");
        migration().run();
        Path archive = temp.resolve("migrations/backup/locks-0001.zip");
        byte[] archived = Files.readAllBytes(archive);
        String journal = read(temp.resolve("migrations/journal-0001.json"));
        String rewritten = read(temp.resolve("data/0_0/1_64_1.json"));

        LockMigration.Outcome second = migration().run();

        assertEquals(0, second.migrated());
        assertEquals(1, second.quarantined());
        assertArrayEquals(archived, Files.readAllBytes(archive));
        assertEquals(journal, read(temp.resolve("migrations/journal-0001.json")));
        assertEquals(rewritten, read(temp.resolve("data/0_0/1_64_1.json")));
        assertFalse(Files.exists(temp.resolve("migrations/journal-0002.json")));
    }

    @Test
    void quarantinedRecordsAreRetriedEveryBootAndStragglersGoThroughANewJournalledPass() throws Exception {
        Path orphaned = container("0_0", "1_64_1.json", "{\"owner\":\"" + orphan + "\",\"lockState\":\"PRIVATE\"}");
        migration().run();
        assertFalse(Files.exists(temp.resolve("migrations/journal-0001.json")), "nothing to move, nothing journalled");
        assertEquals("1\n", read(temp.resolve("data/data-version")));

        UUID orphanHeir = UUID.randomUUID();
        heirs.put(orphan, orphanHeir);
        Path straggler = container("0_0", "2_64_1.json", oldContainer(steve, "FACTION"));
        LockMigration.Outcome retried = migration().run();

        assertEquals(2, retried.migrated());
        assertEquals(0, retried.quarantined());
        assertTrue(read(orphaned).contains(orphanHeir.toString()));
        assertTrue(read(straggler).contains(steveHeir + "\",\"lockState\":\"FACTION\""));
        assertTrue(journal(1).completed);
        assertTrue(Files.exists(temp.resolve("migrations/backup/locks-0001.zip")));
        assertTrue(LockQuarantine.read(temp.resolve("migrations/quarantine.json")).quarantined.isEmpty());

        container("0_0", "3_64_1.json", oldContainer(steve, "PRIVATE"));
        migration().run();
        assertTrue(journal(2).completed);
        assertTrue(Files.exists(temp.resolve("migrations/backup/locks-0002.zip")));
    }

    @Test
    void classificationsMadeWhileIdentityIsDownAreNeverWritten() throws Exception {
        Path character = container("0_0", "1_64_1.json", oldContainer(kira, "PRIVATE"));
        identity.identity().when(OwnerIdentity::available).thenReturn(false);
        LockMigration.Outcome outcome = migration().run();
        assertEquals(0, outcome.migrated());
        assertEquals(1, outcome.quarantined());
        assertEquals(oldContainer(kira, "PRIVATE"), read(character));
        assertEquals("identity unknown", LockQuarantine.snapshot().quarantined().getFirst().reason());
    }

    @Test
    void anExceptionMidPassLeavesAnIncompleteJournalThatTheNextBootReplays() throws Exception {
        Path first = container("0_0", "1_64_1.json", oldContainer(steve, "PRIVATE"));
        Path second = container("0_0", "2_64_1.json", oldContainer(kira, "GUILD"));
        Path third = container("0_0", "3_64_1.json", oldContainer(steve, "FACTION"));
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
        assertFalse(journal(1).completed);
        assertFalse(Files.exists(temp.resolve("data/data-version")));
        byte[] archived = Files.readAllBytes(temp.resolve("migrations/backup/locks-0001.zip"));
        assertTrue(read(first).contains(steveHeir.toString()));
        assertEquals(oldContainer(kira, "GUILD"), read(second));
        assertEquals(oldContainer(steve, "FACTION"), read(third));

        LockMigration.Outcome replay = migration().run();

        assertEquals(1, replay.replayed());
        assertEquals(0, replay.migrated(), "the replay finished the pass, so the scan finds nothing left");
        assertTrue(journal(1).completed);
        assertArrayEquals(archived, Files.readAllBytes(temp.resolve("migrations/backup/locks-0001.zip")));
        for (LockMigration.JournalEntry entry : journal(1).entries) {
            assertEquals(entry.newHash, DurableFiles.sha256(Files.readAllBytes(stores.resolve(entry.path).path())));
        }
        assertTrue(read(second).endsWith(",\"ownerKind\":\"CHARACTER\"}"));
        assertEquals("1\n", read(temp.resolve("data/data-version")));
        verify(log).warning(contains("Replaying unfinished lock migration journal-0001.json"));
    }

    @Test
    void replayMarksDoneRedoesOriginalsListsMissingFilesAndRestoresTornOnesFromTheArchive() throws Exception {
        Path done = container("0_0", "1_64_1.json", oldContainer(steve, "PRIVATE"));
        Path redo = container("0_0", "2_64_1.json", oldContainer(steve, "GUILD"));
        Path missing = container("0_0", "3_64_1.json", oldContainer(steve, "FACTION"));
        Path torn = container("0_0", "4_64_1.json", oldContainer(steve, "PUBLIC"));
        migration().run();
        String doneBytes = read(done);
        String tornExpected = read(torn);
        Path journalPath = temp.resolve("migrations/journal-0001.json");
        LockMigration.Journal journal = journal(1);
        journal.completed = false;
        Files.writeString(journalPath, new Gson().toJson(journal));
        Files.writeString(redo, oldContainer(steve, "GUILD"));
        Files.delete(missing);
        Files.writeString(torn, "{\"owner\":\"" + steve);

        LockMigration.Outcome outcome = migration().run();

        assertEquals(1, outcome.replayed());
        assertEquals(1, outcome.missing());
        assertEquals(doneBytes, read(done));
        assertTrue(read(redo).contains(steveHeir.toString()));
        assertEquals(tornExpected, read(torn));
        assertFalse(Files.exists(missing), "a missing file is never restored");
        LockQuarantine.Contents quarantine = LockQuarantine.read(temp.resolve("migrations/quarantine.json"));
        assertEquals(List.of(new LockQuarantine.Entry("data/0_0/3_64_1.json", steve.toString(), "missing after replay")), quarantine.missing);
        assertTrue(journal(1).completed);

        // The listing survives later boots until the file comes back.
        assertEquals(1, migration().run().missing());
        container("0_0", "3_64_1.json", "{\"lockState\":\"PUBLIC\"}");
        assertEquals(0, migration().run().missing());
    }

    @Test
    void aCompletedJournalWithoutDataVersionOnlyWritesDataVersion() throws Exception {
        container("0_0", "1_64_1.json", oldContainer(steve, "PRIVATE"));
        migration().run();
        byte[] archived = Files.readAllBytes(temp.resolve("migrations/backup/locks-0001.zip"));
        for (String store : List.of("data", "door-data", "entities")) {
            Files.delete(temp.resolve(store).resolve("data-version"));
        }
        Files.writeString(temp.resolve("door-data/data-version"), "0\n");

        LockMigration.Outcome outcome = migration().run();

        assertEquals(0, outcome.migrated());
        assertEquals("1\n", read(temp.resolve("data/data-version")));
        assertEquals("1\n", read(temp.resolve("door-data/data-version")));
        assertArrayEquals(archived, Files.readAllBytes(temp.resolve("migrations/backup/locks-0001.zip")));
        assertFalse(Files.exists(temp.resolve("migrations/journal-0002.json")));
    }

    @Test
    void anEmptyStoreGetsItsDataVersionWithoutAJournal() throws Exception {
        LockMigration.Outcome outcome = migration().run();
        assertEquals(0, outcome.files());
        assertEquals("1\n", read(temp.resolve("entities/data-version")));
        try (var files = Files.list(temp.resolve("migrations"))) {
            assertEquals(List.of("quarantine.json"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void storesWrittenByANewerJarOrWithAnUnreadableVersionAreRefused() throws Exception {
        LockMigration migration = migration();
        assertFalse(migration.storeNewerThanJar());
        write(temp.resolve("data/data-version"), "1\n");
        assertFalse(migration.storeNewerThanJar());
        write(temp.resolve("entities/data-version"), "2\n");
        assertTrue(migration.storeNewerThanJar());
        write(temp.resolve("entities/data-version"), "two");
        assertTrue(migration.storeNewerThanJar());
    }

    @Test
    void replayFailsLoudlyWhenTheArchiveOrJournalCannotBeTrusted() throws Exception {
        Path torn = container("0_0", "1_64_1.json", oldContainer(steve, "PRIVATE"));
        migration().run();
        Path journalPath = temp.resolve("migrations/journal-0001.json");
        LockMigration.Journal journal = journal(1);
        journal.completed = false;
        journal.entries.getFirst().originalHash = "0".repeat(64);
        Files.writeString(journalPath, new Gson().toJson(journal));
        Files.writeString(torn, "torn");
        assertThrows(IOException.class, () -> migration().run());

        journal.entries.getFirst().path = "data/0_0/9_64_1.json";
        container("0_0", "9_64_1.json", "torn");
        Files.writeString(journalPath, new Gson().toJson(journal));
        IOException missingCopy = assertThrows(IOException.class, () -> migration().run());
        assertTrue(missingCopy.getMessage().contains("no copy"));

        Files.writeString(journalPath, "null");
        assertThrows(IOException.class, () -> migration().run());
        Files.writeString(journalPath, "{\"archive\":\"backup/locks-0001.zip\",\"entries\":null}");
        assertThrows(IOException.class, () -> migration().run());
        Files.writeString(journalPath, "{\"entries\":[]}");
        assertThrows(IOException.class, () -> migration().run());
    }
}
