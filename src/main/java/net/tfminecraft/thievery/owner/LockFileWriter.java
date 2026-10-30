package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import org.bukkit.Bukkit;

/**
 * Lock-store writes and deletions off the main thread. Each one still takes the {@link DurableFiles} sequence (temp
 * file, fsync, atomic move, directory sync), only later: the caller queues the bytes and moves on, and one writer
 * thread applies the queue in order. A file written again before its turn keeps its place and takes the newer bytes,
 * and is forced if any write it replaced was.
 *
 * <p>The queue is authoritative: until a write is on disk, {@link #read} and {@link #exists} answer from it, so every
 * reader sees the latest lock state at once. A write that fails is logged with the caller's message, as before, and
 * dropped unless a newer one for the file is waiting; reads then see the disk again, and the caller's {@code failed}
 * task runs on the main thread to drop what it cached from the lost write.
 *
 * <p>Until {@link #start} and after {@link #stop}, writes run on the calling thread, as does a new write while
 * {@link #maxQueued} files are already waiting, so a stalled disk never grows the queue without limit.
 * {@link #stop} returns only once everything queued, including writes queued while it waits, is on disk.
 */
public final class LockFileWriter {

    // A queued write (null bytes for a deletion). Fields are guarded by LOCK.
    private static final class Pending {
        final Path path;
        byte[] bytes;
        boolean sync;
        String failure;
        Runnable failed;
        boolean running;

        Pending(Path path) {
            this.path = path;
        }
    }

    /** The disk side of a write; tests swap it to hold the writer mid-write. */
    interface Disk {
        void write(Path path, byte[] bytes, boolean sync) throws IOException;

        void delete(Path path) throws IOException;
    }

    static final Disk DURABLE = new Disk() {
        @Override
        public void write(Path path, byte[] bytes, boolean sync) throws IOException {
            DurableFiles.write(path, bytes, sync);
        }

        @Override
        public void delete(Path path) throws IOException {
            DurableFiles.delete(path);
        }
    };

    static volatile Disk disk = DURABLE;

    /** Files waiting for the writer beyond which a new write runs on the calling thread instead. */
    static volatile int maxQueued = 1024;

    private static final Object LOCK = new Object();
    // Files whose latest write failed, by the change that recorded the failure. A newer write of the file removes the
    // entry, so a failure task that would run after that newer write is skipped.
    private static final Map<Path, Long> FAILED = new HashMap<>();
    // How many recent changes a stamp can be checked against.
    private static final int RECENT = 64;
    // The newest write of each file not yet on disk, by normalised path.
    private static final Map<Path, Pending> PENDING = new HashMap<>();
    private static final Path[] RECENT_PATHS = new Path[RECENT];
    private static long changes;
    // Null while writes run on the calling thread.
    private static ExecutorService worker;
    private static Executor mainThread = Runnable::run;
    // Null while writes run on the calling thread, which logs to Bukkit's logger.
    private static Logger log;

    private LockFileWriter() {}

    /** Moves writes onto one writer thread, which logs failures to {@code log}; {@code mainThread} runs their tasks. */
    public static void start(Logger log, Executor mainThread) {
        synchronized (LOCK) {
            if (worker != null) {
                return;
            }
            LockFileWriter.log = log;
            LockFileWriter.mainThread = mainThread;
            worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Thievery lock writer");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    /**
     * Waits until everything queued is on disk, then writes on the calling thread again. Writes queued while it waits
     * are drained too: the switch happens only once no write is waiting or running, so a write on the calling thread
     * never races the writer for a file.
     */
    public static void stop() {
        ExecutorService target;
        while (true) {
            synchronized (LOCK) {
                target = worker;
                if (target == null || PENDING.isEmpty()) {
                    worker = null;
                    mainThread = Runnable::run;
                    log = null;
                    FAILED.clear();
                    break;
                }
            }
            drain(target);
        }
        if (target != null) {
            target.shutdown();
        }
    }

    /** Waits until everything queued so far is on disk or has failed. */
    public static void flush() {
        ExecutorService target;
        synchronized (LOCK) {
            target = worker;
        }
        if (target != null) {
            drain(target);
        }
    }

    // The writer runs its queue in order, so a marker behind it runs once the queue before it is done. Interrupts are
    // held until then, as returning early could lose writes at shutdown.
    private static void drain(ExecutorService target) {
        CountDownLatch done = new CountDownLatch(1);
        target.execute(done::countDown);
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Queues {@code bytes} for {@code path}, forced to disk when {@code sync}. A failure is logged as {@code failure}
     * followed by the error, and {@code failed} (may be null) then runs on the main thread, unless a newer write of the
     * file has been queued by then. Returns the stamp at which the file holds these bytes, for {@link #unchangedSince}.
     */
    public static long write(Path path, byte[] bytes, boolean sync, String failure, Runnable failed) {
        return queue(path, bytes.clone(), sync, failure, failed).stamp;
    }

    /**
     * Queues the deletion of {@code path} when it exists, as {@link #write} queues a write. Returns false when there
     * was nothing to delete, or when a deletion run on the calling thread failed.
     */
    public static boolean delete(Path path, String failure, Runnable failed) {
        return exists(path) && queue(path, null, true, failure, failed).applied;
    }

    private record Queued(long stamp, boolean applied) {}

    private static Queued queue(Path path, byte[] bytes, boolean sync, String failure, Runnable failed) {
        Pending task;
        long stamp;
        synchronized (LOCK) {
            Path key = key(path);
            stamp = changed(key);
            FAILED.remove(key);
            Pending current = PENDING.get(key);
            if (current != null && !current.running) {
                fill(current, bytes, sync || current.sync, failure, failed);
                return new Queued(stamp, true);
            }
            task = new Pending(path);
            fill(task, bytes, sync, failure, failed);
            PENDING.put(key, task);
            // A file being written queues behind itself even when the queue is full, so the calling thread never writes
            // a file the writer is writing; that adds at most the one running file to the cap.
            if (worker != null && (current != null || PENDING.size() <= maxQueued)) {
                // Under the lock, so stop() never shuts the writer down between this check and the submission.
                worker.execute(() -> apply(task));
                return new Queued(stamp, true);
            }
        }
        return new Queued(stamp, apply(task));
    }

    private static void fill(Pending task, byte[] bytes, boolean sync, String failure, Runnable failed) {
        task.bytes = bytes;
        task.sync = sync;
        task.failure = failure;
        task.failed = failed;
    }

    private static boolean apply(Pending task) {
        byte[] bytes;
        boolean sync;
        synchronized (LOCK) {
            task.running = true;
            bytes = task.bytes;
            sync = task.sync;
        }
        Exception error = null;
        try {
            if (bytes == null) {
                disk.delete(task.path);
            } else {
                disk.write(task.path, bytes, sync);
            }
        } catch (IOException | RuntimeException e) {
            error = e;
        }
        Runnable failed = null;
        Logger logger;
        Executor main;
        Path key = key(task.path);
        long failedAt = 0;
        synchronized (LOCK) {
            logger = log;
            main = mainThread;
            Pending latest = PENDING.get(key);
            if (latest == task) {
                PENDING.remove(key);
                failed = task.failed;
            } else if (error != null) {
                // The newer write replaces the whole file, so it carries this one's change; force it if this was.
                latest.sync |= sync;
            }
            if (error != null) {
                failedAt = changed(key);
                if (failed != null) {
                    FAILED.put(key, failedAt);
                }
            }
        }
        if (error == null) {
            return true;
        }
        (logger == null ? Bukkit.getLogger() : logger).warning(task.failure + error.getMessage());
        if (failed != null) {
            Runnable cleanup = failed;
            long at = failedAt;
            main.execute(() -> runIfLatest(key, at, cleanup));
        }
        return false;
    }

    // A failure task runs only while no newer write of its file has been queued. That write has already brought the
    // caches up to date with itself, and the older task would bind them to the failed write's world again.
    private static void runIfLatest(Path key, long failedAt, Runnable failed) {
        boolean latest;
        synchronized (LOCK) {
            latest = FAILED.remove(key, failedAt);
        }
        if (latest) {
            failed.run();
        }
    }

    /** The file as the queue will leave it; a deletion still queued reads as a missing file. */
    public static byte[] read(Path path) throws IOException {
        synchronized (LOCK) {
            Pending pending = PENDING.get(key(path));
            if (pending != null) {
                if (pending.bytes == null) {
                    throw new NoSuchFileException(path.toString());
                }
                return pending.bytes.clone();
            }
        }
        return Files.readAllBytes(path);
    }

    /**
     * Like {@link #read}, but null when the file is or will be gone, including one whose only queued write failed
     * since the caller last looked.
     */
    public static byte[] readIfExists(Path path) throws IOException {
        try {
            return read(path);
        } catch (NoSuchFileException gone) {
            return null;
        }
    }

    /** Whether the file exists once the queue is written. */
    public static boolean exists(Path path) {
        synchronized (LOCK) {
            Pending pending = PENDING.get(key(path));
            if (pending != null) {
                return pending.bytes != null;
            }
        }
        return Files.exists(path);
    }

    /** A stamp for {@link #unchangedSince}, taken before a read. */
    public static long stamp() {
        synchronized (LOCK) {
            return changes;
        }
    }

    /**
     * Whether no write or failure has touched the file since {@code stamp}, so what was read then still stands. Only
     * the last few changes are remembered: anything older, or a negative stamp, answers false.
     */
    public static boolean unchangedSince(Path path, long stamp) {
        synchronized (LOCK) {
            if (stamp < 0 || changes - stamp > RECENT) {
                return false;
            }
            Path key = key(path);
            for (long change = stamp + 1; change <= changes; change++) {
                if (key.equals(RECENT_PATHS[(int) (change % RECENT)])) {
                    return false;
                }
            }
            return true;
        }
    }

    private static long changed(Path key) {
        changes++;
        RECENT_PATHS[(int) (changes % RECENT)] = key;
        return changes;
    }

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
