package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class LockFileWriterTest {
    @TempDir Path temp;
    private Logger log;
    private HeldLockDisk disk;
    // Failure tasks the writer hands to the main thread.
    private final List<Runnable> main = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        log = mock(Logger.class);
    }

    @AfterEach
    void tearDown() {
        LockFileWriter.maxQueued = 1024;
        HeldLockDisk.uninstall(disk);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws Exception {
        return new String(LockFileWriter.read(path), StandardCharsets.UTF_8);
    }

    private void startHeld() throws Exception {
        disk = HeldLockDisk.install();
        LockFileWriter.start(log, main::add);
    }

    @Test
    void untilStartedWritesAndDeletionsRunOnTheCallingThreadThroughTheDurableSequence() throws Exception {
        Path file = temp.resolve("a/lock.json");
        Path busy = temp.resolve("busy");
        Files.createDirectories(busy.resolve("child"));
        List<String> failed = new ArrayList<>();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(log);
            LockFileWriter.flush();
            LockFileWriter.write(file, bytes("one"), false, "Failed: ", null);
            assertEquals("one", Files.readString(file), "on disk before write returns");
            assertTrue(LockFileWriter.delete(file, "Failed: ", null));
            assertFalse(Files.exists(file));
            assertFalse(LockFileWriter.delete(file, "Failed: ", () -> failed.add("missing")), "nothing to delete");

            // A failure is logged with the caller's message and its task runs before the call returns.
            LockFileWriter.write(busy, bytes("x"), true, "Failed to save busy: ", () -> failed.add("save"));
            assertFalse(LockFileWriter.delete(busy, "Failed to delete busy: ", () -> failed.add("delete")));
            LockFileWriter.write(busy, bytes("x"), false, "Failed without a task: ", null);
            assertEquals(List.of("save", "delete"), failed);
            verify(log).warning(startsWith("Failed to save busy: "));
            verify(log).warning(startsWith("Failed to delete busy: "));
            verify(log).warning(startsWith("Failed without a task: "));
            assertTrue(Files.isDirectory(busy));
        }
        LockFileWriter.stop();
    }

    @Test
    void queuedWritesKeepTheirOrderTakeTheNewestBytesAndAreReadBackBeforeTheyReachTheDisk() throws Exception {
        Path a = temp.resolve("a.json");
        Path b = temp.resolve("b.json");
        Path c = temp.resolve("c.json");
        Files.writeString(c, "old");
        startHeld();
        LockFileWriter.start(log, Runnable::run); // already started: changes nothing
        LockFileWriter.write(a, bytes("a1"), false, "A: ", null);
        disk.awaitHolding();
        // a1 is being written, so the next write of a queues behind b; later ones merge into it and keep its fsync.
        LockFileWriter.write(b, bytes("b1"), false, "B: ", null);
        LockFileWriter.write(a, bytes("a2"), true, "A: ", null);
        LockFileWriter.write(a, bytes("a3"), false, "A: ", null);
        assertTrue(LockFileWriter.delete(c, "C: ", null));
        assertFalse(LockFileWriter.delete(c, "C: ", null), "already going");

        assertEquals("a3", read(a));
        assertEquals("b1", read(b));
        assertTrue(LockFileWriter.exists(a));
        assertTrue(LockFileWriter.exists(b));
        assertFalse(LockFileWriter.exists(c));
        assertThrows(NoSuchFileException.class, () -> LockFileWriter.read(c));
        assertFalse(Files.exists(a), "nothing has reached the disk yet");
        assertEquals("old", Files.readString(c));

        disk.release();
        LockFileWriter.flush();
        assertEquals(List.of("write a.json a1 false", "write b.json b1 false", "write a.json a3 true", "delete c.json"),
                disk.done);
        assertEquals("a3", Files.readString(a));
        assertEquals("a3", read(a), "read from disk once written");
        assertFalse(LockFileWriter.exists(c));
        assertTrue(disk.threads.stream().allMatch("Thievery lock writer"::equals), disk.threads.toString());
        assertTrue(main.isEmpty());
        verifyNoInteractions(log);
    }

    @Test
    void aDeletionAndAWriteOfTheSameWaitingFileMergeIntoTheLatestAndAreForced() throws Exception {
        Path hold = temp.resolve("hold.json");
        Path c = temp.resolve("c.json");
        Path d = temp.resolve("d.json");
        Files.writeString(c, "old");
        startHeld();
        LockFileWriter.write(hold, bytes("h"), false, "H: ", null);
        disk.awaitHolding();
        assertTrue(LockFileWriter.delete(c, "C: ", null));
        LockFileWriter.write(c, bytes("c2"), false, "C: ", null);
        LockFileWriter.write(d, bytes("d1"), false, "D: ", null);
        assertTrue(LockFileWriter.delete(d, "D: ", null));
        Path e = temp.resolve("e.json");
        LockFileWriter.write(e, bytes("e1"), false, "E: ", null);
        LockFileWriter.write(e, bytes("e2"), false, "E: ", null);
        assertEquals("c2", read(c));
        assertFalse(LockFileWriter.exists(d));
        disk.release();
        LockFileWriter.flush();
        assertEquals(List.of("write hold.json h false", "write c.json c2 true", "delete d.json",
                "write e.json e2 false"), disk.done, "merged writes are forced only when one of them was");
    }

    @Test
    void aFailedWriteIsLoggedAndOnlyTheLatestWriteOfItsFileHandsItsTaskToTheMainThread() throws Exception {
        Path a = temp.resolve("a.json");
        Files.writeString(a, "disk");
        startHeld();
        disk.fail("a.json");
        List<String> ran = new ArrayList<>();
        long before = LockFileWriter.stamp();
        LockFileWriter.write(a, bytes("a1"), true, "Failed a1: ", () -> ran.add("a1"));
        disk.awaitHolding();
        LockFileWriter.write(a, bytes("a2"), false, "Failed a2: ", () -> ran.add("a2"));
        long queued = LockFileWriter.stamp();
        disk.release();
        LockFileWriter.flush();
        // a1 failed with a newer write waiting, so only a2's failure reaches the main thread; a2 was forced for a1.
        assertEquals(List.of("failed write a.json a1 true", "failed write a.json a2 true"), disk.done);
        verify(log).warning("Failed a1: disk refused a.json");
        verify(log).warning("Failed a2: disk refused a.json");
        assertEquals(1, main.size());
        main.getFirst().run();
        assertEquals(List.of("a2"), ran);
        assertEquals("disk", read(a), "reads see the disk again");
        assertFalse(LockFileWriter.unchangedSince(a, before));
        assertFalse(LockFileWriter.unchangedSince(a, queued), "a failure counts as a change");

        main.clear();
        disk.done.clear();
        disk.heal("a.json");
        LockFileWriter.write(a, bytes("a3"), false, "Failed a3: ", null);
        LockFileWriter.flush();
        assertEquals(List.of("write a.json a3 false"), disk.done);
        disk.fail("a.json");
        assertTrue(LockFileWriter.delete(a, "Failed delete: ", () -> ran.add("delete")));
        LockFileWriter.write(temp.resolve("b.json"), bytes("b"), false, "B: ", null);
        LockFileWriter.flush();
        verify(log).warning("Failed delete: disk refused a.json");
        assertEquals(1, main.size());
        main.getFirst().run();
        assertEquals(List.of("a2", "delete"), ran);
        assertTrue(LockFileWriter.exists(a));
    }

    @Test
    void aFailureTaskIsSkippedOnceANewerWriteOfItsFileWasQueued() throws Exception {
        Path a = temp.resolve("a.json");
        startHeld();
        disk.release();
        disk.fail("a.json");
        List<String> ran = new ArrayList<>();
        LockFileWriter.write(a, bytes("a1"), true, "Failed a1: ", () -> ran.add("a1"));
        LockFileWriter.flush();
        assertEquals(1, main.size());
        disk.heal("a.json");
        LockFileWriter.write(a, bytes("a2"), false, "Failed a2: ", () -> ran.add("a2"));
        LockFileWriter.flush();
        main.getFirst().run();
        assertEquals(List.of(), ran, "the newer write already set the caches; the stale cleanup is dropped");
        assertEquals("a2", read(a));

        // A cleanup still pending when the writer stops is dropped with it.
        main.clear();
        disk.fail("a.json");
        LockFileWriter.write(a, bytes("a3"), false, "Failed a3: ", () -> ran.add("a3"));
        LockFileWriter.flush();
        LockFileWriter.stop();
        main.getFirst().run();
        assertEquals(List.of(), ran);
    }

    @Test
    void aFullQueueWritesNewFilesOnTheCallingThreadButNeverAFileTheWriterIsWriting() throws Exception {
        LockFileWriter.maxQueued = 2;
        Path a = temp.resolve("a.json");
        Path b = temp.resolve("b.json");
        Path c = temp.resolve("c.json");
        startHeld();
        LockFileWriter.write(a, bytes("a1"), false, "A: ", null);
        disk.awaitHolding();
        LockFileWriter.write(b, bytes("b1"), false, "B: ", null);
        LockFileWriter.write(c, bytes("c1"), false, "C: ", null);
        assertEquals("c1", Files.readString(c), "over the cap: written before write returns");
        assertEquals(Thread.currentThread().getName(), disk.threads.getLast());
        LockFileWriter.write(a, bytes("a2"), false, "A: ", null);
        assertEquals("a2", read(a));
        assertFalse(Files.exists(a), "a is being written, so its next write waits behind it");
        LockFileWriter.write(b, bytes("b2"), false, "B: ", null);
        assertFalse(Files.exists(b), "a waiting file takes the newer bytes without growing the queue");
        disk.release();
        LockFileWriter.flush();
        assertEquals(List.of("write c.json c1 false", "write a.json a1 false", "write b.json b2 false",
                "write a.json a2 false"), disk.done);
    }

    @Test
    void writesQueuedWhileStopWaitsReachTheDiskBeforeItReturns() throws Exception {
        Path a = temp.resolve("a.json");
        Path b = temp.resolve("b.json");
        startHeld();
        LockFileWriter.write(a, bytes("a"), true, "A: ", null);
        disk.awaitHolding();
        Thread stopping = new Thread(LockFileWriter::stop);
        stopping.start();
        stopping.join(200);
        assertTrue(stopping.isAlive());
        Thread writing = new Thread(() -> LockFileWriter.write(b, bytes("b"), true, "B: ", null));
        writing.start();
        writing.join(10_000);
        assertFalse(writing.isAlive(), "a write during stop never waits for it");
        assertEquals("b", read(b), "and is read back at once");
        assertTrue(stopping.isAlive(), "stop waits for it too");
        disk.release();
        stopping.join(10_000);
        assertFalse(stopping.isAlive());
        assertEquals(List.of("write a.json a true", "write b.json b true"), disk.done);
        assertEquals("b", Files.readString(b));
        assertTrue(disk.threads.stream().allMatch("Thievery lock writer"::equals), disk.threads.toString());
        verifyNoInteractions(log);
    }

    @Test
    void stopWaitsForEveryQueuedWriteThenWritesOnTheCallingThreadAgain() throws Exception {
        Path a = temp.resolve("a.json");
        Path b = temp.resolve("b.json");
        startHeld();
        LockFileWriter.write(a, bytes("a"), true, "A: ", null);
        disk.awaitHolding();
        LockFileWriter.write(b, bytes("b"), true, "B: ", null);
        Thread stopping = new Thread(LockFileWriter::stop);
        stopping.start();
        stopping.join(200);
        assertTrue(stopping.isAlive(), "stop waits while a write is held");
        disk.release();
        stopping.join(10_000);
        assertFalse(stopping.isAlive());
        assertEquals(List.of("write a.json a true", "write b.json b true"), disk.done);

        LockFileWriter.write(a, bytes("again"), false, "A: ", null);
        assertEquals("again", Files.readString(a), "written before write returns");
        assertEquals(Thread.currentThread().getName(), disk.threads.getLast());
    }

    @Test
    void interruptsNeverCutADrainShortAndAreKept() throws Exception {
        Path a = temp.resolve("a.json");
        startHeld();
        LockFileWriter.write(a, bytes("a"), false, "A: ", null);
        disk.awaitHolding();
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            disk.release();
        });
        releaser.start();
        Thread.currentThread().interrupt();
        LockFileWriter.flush();
        assertTrue(Thread.interrupted(), "the interrupt is restored");
        assertEquals(List.of("write a.json a false"), disk.done);

        LockFileWriter.write(a, bytes("b"), false, "A: ", null);
        Thread.currentThread().interrupt();
        LockFileWriter.stop();
        assertTrue(Thread.interrupted());
        assertEquals("b", Files.readString(a));
        releaser.join();
    }

    @Test
    void stampsTellWhetherAFileWasTouchedWithinTheRecentChanges() throws Exception {
        Path a = temp.resolve("a.json");
        Path other = temp.resolve("other.json");
        assertFalse(LockFileWriter.unchangedSince(a, -1), "an unknown stamp is never trusted");
        long stamp = LockFileWriter.stamp();
        assertTrue(LockFileWriter.unchangedSince(a, stamp));
        for (int i = 0; i < 64; i++) {
            LockFileWriter.write(other, bytes("o" + i), false, "O: ", null);
        }
        assertTrue(LockFileWriter.unchangedSince(a, stamp), "64 changes elsewhere are still remembered");
        assertFalse(LockFileWriter.unchangedSince(other, stamp));
        LockFileWriter.write(other, bytes("last"), false, "O: ", null);
        assertFalse(LockFileWriter.unchangedSince(a, stamp), "older changes are forgotten");
        long written = LockFileWriter.write(a, bytes("a"), false, "A: ", null);
        assertTrue(LockFileWriter.unchangedSince(a, written), "a file is unchanged since its own write");
        assertFalse(LockFileWriter.unchangedSince(a, written - 1));
    }
}
