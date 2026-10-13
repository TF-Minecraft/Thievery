package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Locks staff need to look at: records the migration could not move to a character (kept in
 * {@code plugins/Thievery/migrations/quarantine.json} and retried every boot), unreadable records, files missing
 * after a replay, and double chests whose halves have different owners.
 */
public final class LockQuarantine {

    /** One listed record: its store path, the stored owner (if readable) and why it is listed. */
    public record Entry(String path, String owner, String reason) {}

    /** The file's shape. */
    public static final class Contents {
        public List<Entry> quarantined = new ArrayList<>();
        public List<Entry> unreadable = new ArrayList<>();
        public List<Entry> missing = new ArrayList<>();
    }

    /** Everything to list, including what was found since boot. */
    public record Snapshot(List<Entry> quarantined, List<Entry> unreadable, List<Entry> missing,
            List<String> diverged) {

        /** How many quarantined records each stored owner has, largest first. */
        public Map<String, Integer> perOwner() {
            Map<String, Integer> counts = new TreeMap<>();
            for (Entry entry : quarantined) {
                counts.merge(entry.owner(), 1, Integer::sum);
            }
            return counts;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Contents contents = new Contents();
    private static final Set<String> runtimeUnreadable = new LinkedHashSet<>();
    private static final Set<String> diverged = new LinkedHashSet<>();

    private LockQuarantine() {}

    public static Snapshot snapshot() {
        List<Entry> unreadable = new ArrayList<>(contents.unreadable);
        Set<String> listed = new LinkedHashSet<>();
        unreadable.forEach(entry -> listed.add(entry.path()));
        for (String path : runtimeUnreadable) {
            if (listed.add(path)) {
                unreadable.add(new Entry(path, null, "unreadable"));
            }
        }
        return new Snapshot(List.copyOf(contents.quarantined), List.copyOf(unreadable), List.copyOf(contents.missing),
                List.copyOf(diverged));
    }

    /** A record found unreadable while the server runs. */
    public static void noteUnreadable(Path path) {
        runtimeUnreadable.add(path.toString());
    }

    /** A double chest whose halves have different owners. */
    public static void noteDiverged(String where) {
        diverged.add(where);
    }

    /** Staff set a new owner on a listed lock. */
    public static void resolved(String where, Path path) {
        diverged.remove(where);
        runtimeUnreadable.removeIf(listed -> matches(listed, path));
        contents.quarantined.removeIf(entry -> matches(entry.path(), path));
        contents.unreadable.removeIf(entry -> matches(entry.path(), path));
    }

    // Listings name store files relative to their store; the server sees them under plugins/Thievery.
    private static boolean matches(String listed, Path path) {
        return path.toString().replace('\\', '/').endsWith(listed);
    }

    /** Replaces the listed records after a boot scan and writes the file durably. */
    public static void replace(Path file, Contents next) throws IOException {
        DurableFiles.write(file, GSON.toJson(next).getBytes(StandardCharsets.UTF_8), true);
        contents = next;
        runtimeUnreadable.clear();
    }

    /** The last written list, or an empty one when there is none or it cannot be read. */
    public static Contents read(Path file) {
        if (!Files.exists(file)) {
            return new Contents();
        }
        Contents read;
        try {
            read = GSON.fromJson(Files.readString(file), Contents.class);
        } catch (IOException | JsonParseException e) {
            return new Contents();
        }
        Contents contents = new Contents();
        if (read != null) {
            contents.quarantined = orEmpty(read.quarantined);
            contents.unreadable = orEmpty(read.unreadable);
            contents.missing = orEmpty(read.missing);
        }
        return contents;
    }

    private static List<Entry> orEmpty(List<Entry> entries) {
        return entries == null ? new ArrayList<>() : new ArrayList<>(entries);
    }

    /** For tests and plugin restarts. */
    public static void reset() {
        contents = new Contents();
        runtimeUnreadable.clear();
        diverged.clear();
    }
}
