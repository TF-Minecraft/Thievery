package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.estate.LockEstates;

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
 *
 * <p>The same pass moves the locks of decided estates (design R10, M6): {@link EstatePlanner} works on the same
 * scan and its entries join the same journal, marked {@code kind: estate} with their {@code deathId}. A pass with
 * estate entries only archives just the files it journals.
 */
public final class LockMigration {

    public static final int DATA_VERSION = 1;
    static final String DATA_VERSION_FILE = "data-version";
    static final String QUARANTINE_FILE = "quarantine.json";
    private static final Pattern JOURNAL = Pattern.compile("journal-(\\d{4,})\\.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final String MIGRATE = "migrate";
    static final String ESTATE = "estate";

    /**
     * One rewritten file. {@code newHash} is the hash of the bytes actually written. {@code kind} and
     * {@code deathId} are absent from journals written before estates, and from any an older jar completed.
     */
    static final class JournalEntry {
        String path;
        String originalHash;
        String newHash;
        String previousOwner;
        String owner;
        String ownerKind;
        String kind;
        String deathId;
        /** An estate entry whose estate no longer applied at replay: the file kept its original bytes. */
        Boolean skipped;

        boolean estate() {
            return ESTATE.equals(kind);
        }
    }

    static final class Journal {
        int number;
        String createdAt;
        String archive;
        boolean completed;
        List<JournalEntry> entries = new ArrayList<>();
    }

    /**
     * What a boot's migration did, for the log and the rehearsal counts. {@code estateMoved} counts this pass's
     * estate entries; {@code estates} holds one report per decided estate whose guard held.
     */
    public record Outcome(int migrated, int quarantined, int unreadable, int replayed, int missing, int files,
            long millis, int estateMoved, List<LockEstates.Report> estates) {}

    /** A scanned record. {@code owner} is null when unreadable; {@code state} is null for doors. */
    record Scanned(LockStores.StoreFile file, byte[] bytes, OwnerCodec.Stored owner, LockState state) {}

    /** A planned owner rewrite; {@code deathId} is set for estate moves. */
    record Planned(Scanned scanned, LockOwner owner, UUID deathId) {}

    record Decoded(OwnerCodec.Stored owner, LockState state) {}

    private final LockStores stores;
    private final Logger log;
    private final LockEstates estates;

    public LockMigration(LockStores stores, Logger log) {
        this(stores, log, LockEstates.NONE);
    }

    public LockMigration(LockStores stores, Logger log, LockEstates estates) {
        this.stores = stores;
        this.log = log;
        this.estates = estates;
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
        List<JournalEntry> estateMoves = new ArrayList<>();
        for (Path path : journals.values()) {
            Journal journal = readJournal(path);
            if (!journal.completed) {
                replay(path, journal, missing);
                replayed++;
            }
            estateMoves(journal.entries, estateMoves);
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
                Decoded decoded = decode(file.store(), bytes);
                scanned.add(new Scanned(file, bytes, decoded.owner(), decoded.state()));
                if (decoded.owner().markerless()) {
                    markerless.add(decoded.owner().id());
                }
            } catch (IllegalArgumentException e) {
                scanned.add(new Scanned(file, bytes, null, null));
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
                plan.add(new Planned(file, target, null));
            }
        }

        EstatePlanner.Result estatePlan = EstatePlanner.plan(estates, scanned, plan, log);
        if (!plan.isEmpty() || !estatePlan.moves().isEmpty()) {
            List<JournalEntry> written = runPass(journals.isEmpty() ? 1 : journals.lastKey() + 1, scanned, plan,
                    estatePlan.moves());
            estateMoves(written, estateMoves);
        } else if (journals.isEmpty()) {
            writeDataVersion();
        }

        index(scanned, plan, estatePlan.moves());

        LockQuarantine.Contents next = new LockQuarantine.Contents();
        next.quarantined = quarantined;
        next.unreadable = unreadable;
        next.missing = missing;
        LockQuarantine.replace(migrations.resolve(QUARANTINE_FILE), next);

        List<LockEstates.Report> reports = estatePlan.reports(stores, scanned, plan, estateMoves);
        Outcome outcome = new Outcome(plan.size(), quarantined.size(), unreadable.size(), replayed, missing.size(),
                files.size(), (System.nanoTime() - start) / 1_000_000L, estatePlan.moves().size(), reports);
        log.info("Lock migration: " + outcome.files() + " lock files scanned, " + outcome.migrated() + " migrated, "
                + outcome.quarantined() + " quarantined, " + outcome.unreadable() + " unreadable, "
                + outcome.replayed() + " journals replayed, " + outcome.missing() + " missing after replay, "
                + outcome.estateMoved() + " moved for " + reports.size() + " decided estates, in "
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

    // The will screen's index, from the files as this pass left them.
    private static void index(List<Scanned> scanned, List<Planned> plan, List<Planned> estateMoves) {
        OwnedLocks.clear();
        Map<Scanned, LockOwner> rewritten = new IdentityHashMap<>();
        for (Planned planned : plan) {
            rewritten.put(planned.scanned(), planned.owner());
        }
        for (Planned planned : estateMoves) {
            rewritten.put(planned.scanned(), planned.owner());
        }
        for (Scanned file : scanned) {
            LockOwner owner = rewritten.get(file);
            OwnedLocks.put(file.file().store(), file.file().path(),
                    owner == null ? file.owner() : OwnerCodec.store(owner), file.state(), null);
        }
    }

    // The estate entries a journal applied; skipped ones moved nothing.
    private static void estateMoves(List<JournalEntry> entries, List<JournalEntry> into) {
        for (JournalEntry entry : entries) {
            if (entry.estate() && entry.skipped == null) {
                into.add(entry);
            }
        }
    }

    private List<JournalEntry> runPass(int number, List<Scanned> scanned, List<Planned> plan,
            List<Planned> estateMoves) throws IOException {
        Path migrations = stores.migrations();
        String archiveName = String.format("backup/locks-%04d.zip", number);
        // A pass with 6a entries keeps the whole store as before; an estate-only pass archives what it journals.
        writeArchive(migrations.resolve(archiveName),
                plan.isEmpty() ? estateMoves.stream().map(Planned::scanned).toList() : scanned);

        Journal journal = new Journal();
        journal.number = number;
        journal.createdAt = Instant.now().toString();
        journal.archive = archiveName;
        Map<JournalEntry, byte[]> rewrites = new LinkedHashMap<>();
        List<Planned> all = new ArrayList<>(plan);
        all.addAll(estateMoves);
        for (Planned planned : all) {
            Scanned file = planned.scanned();
            byte[] next = LockRecords.rewriteOwner(file.bytes(), file.file().store().ownerField(), planned.owner());
            JournalEntry entry = new JournalEntry();
            entry.path = file.file().name();
            entry.originalHash = DurableFiles.sha256(file.bytes());
            entry.newHash = DurableFiles.sha256(next);
            entry.previousOwner = file.owner().id().toString();
            entry.owner = planned.owner().id().toString();
            entry.ownerKind = planned.owner().kind().name();
            entry.kind = planned.deathId() == null ? MIGRATE : ESTATE;
            entry.deathId = planned.deathId() == null ? null : planned.deathId().toString();
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
        return journal.entries;
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
                boolean torn = !hash.equals(entry.originalHash);
                if (torn) {
                    // Torn: start again from the archived original.
                    current = archived(archive, entry);
                }
                if (entry.estate() && !stillDue(entry)) {
                    // Revived, reverted or replaced since the crash: the testator keeps this lock.
                    log.warning("Estate lock move for " + entry.path + " skipped at replay: its estate no longer"
                            + " applies.");
                    if (torn) {
                        DurableFiles.write(file.path(), current, false);
                        dirs.add(file.path().toAbsolutePath().getParent());
                    }
                    entry.skipped = true;
                    entry.newHash = entry.originalHash;
                    continue;
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

    // Whether an estate entry's estate is still decided with its guard holding; any failure leaves the lock alone.
    private boolean stillDue(JournalEntry entry) {
        try {
            return estates.current(UUID.fromString(entry.deathId)).isPresent();
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "Could not check the estate of " + entry.path + " at replay.", e);
            return false;
        }
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

    static Decoded decode(LockStores.Store store, byte[] bytes) {
        return switch (store) {
            case CONTAINERS -> {
                LockRecords.Container container = LockRecords.decodeContainer(bytes);
                yield new Decoded(container.owner(), container.state());
            }
            case DOORS -> new Decoded(LockRecords.decodeDoor(bytes).owner(), null);
            case ENTITIES -> {
                LockRecords.Entity entity = LockRecords.decodeEntity(bytes);
                yield new Decoded(entity.owner(), entity.state());
            }
        };
    }
}
