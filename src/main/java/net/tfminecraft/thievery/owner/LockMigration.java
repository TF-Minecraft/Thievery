package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The lock migration (rule 7). It runs synchronously on the main thread in {@code onEnable}, before any player can
 * join, and never later in the process.
 *
 * <p>Each pass scans every store file, classifies each distinct markerless owner once, takes one archive of the
 * stores, journals each file's original hash and the hash of the exact bytes it will write, rewrites the files by
 * atomic move, forces every directory written into, marks the journal completed and writes {@code data-version}.
 * The migration relies on the forced archive, journal and directories instead of forcing each file.
 *
 * <p>An incomplete journal is replayed at the next boot: files at their new hash are done, files at their original
 * hash are redone, missing files are listed for staff and never restored, and any other hash is torn, so the file
 * is taken from the archive and redone. A journal, completed or not, is never re-archived.
 */
public final class LockMigration {

    public static final int DATA_VERSION = 1;
    static final String DATA_VERSION_FILE = "data-version";
    static final String QUARANTINE_FILE = "quarantine.json";
    private static final Pattern JOURNAL = Pattern.compile("journal-(\\d{4,})\\.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One rewritten file. {@code newHash} is the hash of the bytes actually written. */
    static final class JournalEntry {
        String path;
        String originalHash;
        String newHash;
        String previousOwner;
        String owner;
        String ownerKind;
    }

    static final class Journal {
        int number;
        String createdAt;
        String archive;
        boolean completed;
        List<JournalEntry> entries = new ArrayList<>();
    }

    /** What a boot's migration did, for the log and the rehearsal counts. */
    public record Outcome(int migrated, int quarantined, int unreadable, int replayed, int missing, int files,
            long millis) {}

    private record Scanned(LockStores.StoreFile file, byte[] bytes, OwnerCodec.Stored owner) {}

    private record Planned(Scanned scanned, LockOwner owner) {}

    private final LockStores stores;
    private final Logger log;

    public LockMigration(LockStores stores, Logger log) {
        this.stores = stores;
        this.log = log;
    }

    /** Whether any store was written by a newer jar, or has a {@code data-version} this jar cannot read. */
    public boolean storeNewerThanJar() throws IOException {
        for (LockStores.Store store : LockStores.Store.values()) {
            Path file = stores.root(store).resolve(DATA_VERSION_FILE);
            if (!Files.exists(file)) {
                continue;
            }
            try {
                if (Integer.parseInt(Files.readString(file).trim()) > DATA_VERSION) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return false;
    }

    public Outcome run() throws IOException {
        long start = System.nanoTime();
        Path migrations = stores.migrations();
        Files.createDirectories(migrations);
        LockQuarantine.Contents previous = LockQuarantine.read(migrations.resolve(QUARANTINE_FILE));
        List<LockQuarantine.Entry> missing = new ArrayList<>();
        for (LockQuarantine.Entry entry : previous.missing) {
            if (!Files.exists(stores.resolve(entry.path()).path())) {
                missing.add(entry);
            }
        }

        TreeMap<Integer, Path> journals = journals();
        int replayed = 0;
        for (Path path : journals.values()) {
            Journal journal = readJournal(path);
            if (!journal.completed) {
                replay(path, journal, missing);
                replayed++;
            }
        }
        if (!journals.isEmpty()) {
            writeDataVersion();
        }

        List<Scanned> scanned = new ArrayList<>();
        List<LockQuarantine.Entry> unreadable = new ArrayList<>();
        Set<UUID> markerless = new LinkedHashSet<>();
        List<LockStores.StoreFile> files = stores.files();
        for (LockStores.StoreFile file : files) {
            byte[] bytes = Files.readAllBytes(file.path());
            try {
                OwnerCodec.Stored owner = decode(file.store(), bytes);
                scanned.add(new Scanned(file, bytes, owner));
                if (owner.markerless()) {
                    markerless.add(owner.id());
                }
            } catch (IllegalArgumentException e) {
                scanned.add(new Scanned(file, bytes, null));
                unreadable.add(new LockQuarantine.Entry(file.name(), null, "unreadable: " + e.getMessage()));
            }
        }

        Map<UUID, OwnerClassifier.Classification> classes = new HashMap<>();
        for (UUID id : markerless) {
            classes.put(id, OwnerClassifier.classify(id));
        }

        List<Planned> plan = new ArrayList<>();
        List<LockQuarantine.Entry> quarantined = new ArrayList<>();
        for (Scanned file : scanned) {
            if (file.owner() == null || !file.owner().markerless()) {
                continue;
            }
            OwnerClassifier.Classification classification = classes.get(file.owner().id());
            LockOwner target = target(classification);
            if (target == null) {
                quarantined.add(new LockQuarantine.Entry(file.file().name(), file.owner().id().toString(),
                        classification.definitive() ? "no reviewed heir" : "identity unknown"));
            } else {
                plan.add(new Planned(file, target));
            }
        }

        if (!plan.isEmpty()) {
            runPass(journals.isEmpty() ? 1 : journals.lastKey() + 1, scanned, plan);
        } else if (journals.isEmpty()) {
            writeDataVersion();
        }

        LockQuarantine.Contents next = new LockQuarantine.Contents();
        next.quarantined = quarantined;
        next.unreadable = unreadable;
        next.missing = missing;
        LockQuarantine.replace(migrations.resolve(QUARANTINE_FILE), next);

        Outcome outcome = new Outcome(plan.size(), quarantined.size(), unreadable.size(), replayed, missing.size(),
                files.size(), (System.nanoTime() - start) / 1_000_000L);
        log.info("Lock migration: " + outcome.files() + " lock files scanned, " + outcome.migrated() + " migrated, "
                + outcome.quarantined() + " quarantined, " + outcome.unreadable() + " unreadable, "
                + outcome.replayed() + " journals replayed, " + outcome.missing() + " missing after replay, in "
                + outcome.millis() + " ms.");
        return outcome;
    }

    // Character and server owners only gain their marker; legacy owners move to their heir. Null: quarantine.
    private static LockOwner target(OwnerClassifier.Classification classification) {
        if (!classification.definitive()) {
            return null;
        }
        return switch (classification.owner().kind()) {
            case CHARACTER, SERVER -> classification.owner();
            case LEGACY -> LockOwner.character(classification.heir());
            case UNRESOLVED -> null;
        };
    }

    private void runPass(int number, List<Scanned> scanned, List<Planned> plan) throws IOException {
        Path migrations = stores.migrations();
        String archiveName = String.format("backup/locks-%04d.zip", number);
        writeArchive(migrations.resolve(archiveName), scanned);

        Journal journal = new Journal();
        journal.number = number;
        journal.createdAt = Instant.now().toString();
        journal.archive = archiveName;
        Map<JournalEntry, byte[]> rewrites = new LinkedHashMap<>();
        for (Planned planned : plan) {
            Scanned file = planned.scanned();
            byte[] next = LockRecords.rewriteOwner(file.bytes(), file.file().store().ownerField(), planned.owner());
            JournalEntry entry = new JournalEntry();
            entry.path = file.file().name();
            entry.originalHash = DurableFiles.sha256(file.bytes());
            entry.newHash = DurableFiles.sha256(next);
            entry.previousOwner = file.owner().id().toString();
            entry.owner = planned.owner().id().toString();
            entry.ownerKind = planned.owner().kind().name();
            journal.entries.add(entry);
            rewrites.put(entry, next);
        }
        Path journalPath = migrations.resolve(String.format("journal-%04d.json", number));
        writeJournal(journalPath, journal);

        Set<Path> dirs = new LinkedHashSet<>();
        for (Map.Entry<JournalEntry, byte[]> rewrite : rewrites.entrySet()) {
            Path path = stores.resolve(rewrite.getKey().path).path();
            DurableFiles.write(path, rewrite.getValue(), false);
            dirs.add(path.toAbsolutePath().getParent());
        }
        complete(journalPath, journal, dirs);
    }

    private void replay(Path journalPath, Journal journal, List<LockQuarantine.Entry> missing) throws IOException {
        log.warning("Replaying unfinished lock migration " + journalPath.getFileName() + ".");
        Set<Path> dirs = new LinkedHashSet<>();
        try (ZipFile archive = new ZipFile(stores.migrations().resolve(journal.archive).toFile())) {
            for (JournalEntry entry : journal.entries) {
                LockStores.StoreFile file = stores.resolve(entry.path);
                if (!Files.exists(file.path())) {
                    missing.add(new LockQuarantine.Entry(entry.path, entry.previousOwner, "missing after replay"));
                    continue;
                }
                byte[] current = Files.readAllBytes(file.path());
                String hash = DurableFiles.sha256(current);
                if (hash.equals(entry.newHash)) {
                    continue;
                }
                if (!hash.equals(entry.originalHash)) {
                    // Torn: start again from the archived original.
                    current = archived(archive, entry);
                }
                byte[] next = LockRecords.rewriteOwner(current, file.store().ownerField(),
                        new LockOwner(LockOwner.Kind.valueOf(entry.ownerKind), UUID.fromString(entry.owner)));
                DurableFiles.write(file.path(), next, false);
                entry.newHash = DurableFiles.sha256(next);
                dirs.add(file.path().toAbsolutePath().getParent());
            }
        }
        complete(journalPath, journal, dirs);
    }

    private static byte[] archived(ZipFile archive, JournalEntry entry) throws IOException {
        ZipEntry zipped = archive.getEntry(entry.path);
        if (zipped == null) {
            throw new IOException("The migration archive has no copy of " + entry.path);
        }
        byte[] bytes = archive.getInputStream(zipped).readAllBytes();
        if (!DurableFiles.sha256(bytes).equals(entry.originalHash)) {
            throw new IOException("The migration archive's copy of " + entry.path + " does not match the journal");
        }
        return bytes;
    }

    private void complete(Path journalPath, Journal journal, Set<Path> dirs) throws IOException {
        for (Path dir : dirs) {
            DurableFiles.syncDirectory(dir);
        }
        journal.completed = true;
        writeJournal(journalPath, journal);
        writeDataVersion();
    }

    // The whole store as scanned, so the archive holds exactly the bytes the journal hashes.
    private static void writeArchive(Path archive, List<Scanned> scanned) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Scanned file : scanned) {
                zip.putNextEntry(new ZipEntry(file.file().name()));
                zip.write(file.bytes());
                zip.closeEntry();
            }
        }
        DurableFiles.write(archive, out.toByteArray(), true);
    }

    private void writeJournal(Path path, Journal journal) throws IOException {
        DurableFiles.write(path, GSON.toJson(journal).getBytes(StandardCharsets.UTF_8), true);
    }

    static Journal readJournal(Path path) throws IOException {
        Journal journal = GSON.fromJson(Files.readString(path), Journal.class);
        if (journal == null || journal.entries == null || journal.archive == null) {
            throw new IOException("Unreadable lock migration journal " + path);
        }
        return journal;
    }

    private void writeDataVersion() throws IOException {
        byte[] version = (DATA_VERSION + "\n").getBytes(StandardCharsets.UTF_8);
        for (LockStores.Store store : LockStores.Store.values()) {
            Path file = stores.root(store).resolve(DATA_VERSION_FILE);
            if (!Files.exists(file) || !java.util.Arrays.equals(Files.readAllBytes(file), version)) {
                DurableFiles.write(file, version, true);
            }
        }
    }

    // Journals by pass number, oldest first.
    private TreeMap<Integer, Path> journals() throws IOException {
        TreeMap<Integer, Path> journals = new TreeMap<>();
        try (Stream<Path> files = Files.list(stores.migrations())) {
            for (Path path : (Iterable<Path>) files::iterator) {
                Matcher matcher = JOURNAL.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    journals.put(Integer.parseInt(matcher.group(1)), path);
                }
            }
        }
        return journals;
    }

    private static OwnerCodec.Stored decode(LockStores.Store store, byte[] bytes) {
        return switch (store) {
            case CONTAINERS -> LockRecords.decodeContainer(bytes).owner();
            case DOORS -> LockRecords.decodeDoor(bytes).owner();
            case ENTITIES -> LockRecords.decodeEntity(bytes).owner();
        };
    }
}
