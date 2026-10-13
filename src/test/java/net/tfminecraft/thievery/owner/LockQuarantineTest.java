package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockQuarantineTest {
    @TempDir Path temp;

    @Test void theListSurvivesAsAFileAndMergesWhatTheServerFindsWhileRunning() throws Exception {
        Path file = temp.resolve("migrations/quarantine.json");
        LockQuarantine.Contents contents = new LockQuarantine.Contents();
        contents.quarantined = List.of(new LockQuarantine.Entry("data/0_0/1_64_1.json", "acct-a", "no reviewed heir"),
                new LockQuarantine.Entry("data/0_0/2_64_1.json", "acct-a", "no reviewed heir"),
                new LockQuarantine.Entry("door-data/0_0/3_64_1.json", "acct-b", "identity unknown"));
        contents.unreadable = List.of(new LockQuarantine.Entry("data/0_0/9_64_1.json", null, "unreadable: not JSON"));
        contents.missing = List.of(new LockQuarantine.Entry("entities/x.json", "acct-c", "missing after replay"));
        LockQuarantine.Contents stored = new LockQuarantine.Contents();
        stored.quarantined = new java.util.ArrayList<>(contents.quarantined);
        stored.unreadable = new java.util.ArrayList<>(contents.unreadable);
        stored.missing = contents.missing;
        LockQuarantine.replace(file, stored);

        LockQuarantine.Contents read = LockQuarantine.read(file);
        assertEquals(contents.quarantined, read.quarantined);
        assertEquals(contents.unreadable, read.unreadable);
        assertEquals(contents.missing, read.missing);

        LockQuarantine.noteUnreadable(Path.of("data/0_0/9_64_1.json"));
        LockQuarantine.noteUnreadable(Path.of("plugins/Thievery/data/0_0/9_64_1.json"));
        LockQuarantine.noteUnreadable(Path.of("plugins/Thievery/entities/y.json"));
        LockQuarantine.noteUnreadable(Path.of("plugins/Thievery/entities/y.json"));
        LockQuarantine.noteDiverged("world 1 64 1 / world 2 64 1");
        LockQuarantine.Snapshot snapshot = LockQuarantine.snapshot();
        assertEquals(3, snapshot.quarantined().size());
        assertEquals(List.of("data/0_0/9_64_1.json", "plugins/Thievery/data/0_0/9_64_1.json", "plugins/Thievery/entities/y.json"),
                snapshot.unreadable().stream().map(LockQuarantine.Entry::path).toList());
        assertEquals(List.of("world 1 64 1 / world 2 64 1"), snapshot.diverged());
        assertEquals(Map.of("acct-a", 2, "acct-b", 1), snapshot.perOwner());

        LockQuarantine.resolved("world 1 64 1 / world 2 64 1", Path.of("plugins/Thievery/data/0_0/1_64_1.json"));
        LockQuarantine.resolved("world 9 64 1", Path.of("plugins/Thievery/data/0_0/9_64_1.json"));
        snapshot = LockQuarantine.snapshot();
        assertEquals(2, snapshot.quarantined().size());
        assertEquals(List.of("plugins/Thievery/entities/y.json"), snapshot.unreadable().stream().map(LockQuarantine.Entry::path).toList());
        assertTrue(snapshot.diverged().isEmpty());

        LockQuarantine.reset();
        assertTrue(LockQuarantine.snapshot().quarantined().isEmpty());
    }

    @Test void resolvingADoorKeepsAContainerListedAtTheSameCoordinates() throws Exception {
        LockQuarantine.Contents contents = new LockQuarantine.Contents();
        contents.quarantined.add(new LockQuarantine.Entry("data/0_0/1_64_1.json", "acct-a", "no reviewed heir"));
        contents.unreadable.add(new LockQuarantine.Entry("data/0_0/2_64_1.json", null, "unreadable: not JSON"));
        LockQuarantine.replace(temp.resolve("quarantine.json"), contents);

        LockQuarantine.resolved("world 1 64 1", Path.of("plugins/Thievery/door-data/0_0/1_64_1.json"));
        LockQuarantine.resolved("world 2 64 1", Path.of("plugins/Thievery/door-data/0_0/2_64_1.json"));
        LockQuarantine.Snapshot snapshot = LockQuarantine.snapshot();
        assertEquals(List.of("data/0_0/1_64_1.json"), snapshot.quarantined().stream().map(LockQuarantine.Entry::path).toList());
        assertEquals(List.of("data/0_0/2_64_1.json"), snapshot.unreadable().stream().map(LockQuarantine.Entry::path).toList());

        LockQuarantine.resolved("world 1 64 1", Path.of("plugins/Thievery/data/0_0/1_64_1.json"));
        LockQuarantine.resolved("world 2 64 1", Path.of("plugins/Thievery/data/0_0/2_64_1.json"));
        snapshot = LockQuarantine.snapshot();
        assertTrue(snapshot.quarantined().isEmpty());
        assertTrue(snapshot.unreadable().isEmpty());
        LockQuarantine.reset();
    }

    @Test void aMissingOrUnreadableFileReadsAsEmpty() throws Exception {
        assertTrue(LockQuarantine.read(temp.resolve("absent.json")).quarantined.isEmpty());
        Path broken = Files.writeString(temp.resolve("broken.json"), "{not json");
        assertTrue(LockQuarantine.read(broken).quarantined.isEmpty());
        Path empty = Files.writeString(temp.resolve("empty.json"), "");
        assertTrue(LockQuarantine.read(empty).missing.isEmpty());
        Path nulls = Files.writeString(temp.resolve("nulls.json"), "{\"quarantined\":null,\"unreadable\":null,\"missing\":null}");
        LockQuarantine.Contents cleaned = LockQuarantine.read(nulls);
        assertTrue(cleaned.quarantined.isEmpty() && cleaned.unreadable.isEmpty() && cleaned.missing.isEmpty());
        cleaned.missing.add(new LockQuarantine.Entry("a", null, "b"));
        Path directory = Files.createDirectories(temp.resolve("dir.json"));
        assertTrue(LockQuarantine.read(directory).unreadable.isEmpty());
    }
}
