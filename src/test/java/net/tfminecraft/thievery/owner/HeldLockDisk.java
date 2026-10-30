package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Stands in for the lock writer's disk in tests: the first write or deletion waits until {@link #release}, files whose
 * names are {@link #fail failing} throw, and every operation is recorded in order with its fsync flag.
 */
public final class HeldLockDisk implements LockFileWriter.Disk {

    public final List<String> done = new CopyOnWriteArrayList<>();
    public final List<String> threads = new CopyOnWriteArrayList<>();
    private final Set<String> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final CountDownLatch holding = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile boolean first = true;

    /** Installs a disk that holds its first operation. */
    public static HeldLockDisk install() {
        HeldLockDisk disk = new HeldLockDisk();
        LockFileWriter.disk = disk;
        return disk;
    }

    /** Puts the real disk back and stops the writer, draining what is left. */
    public static void uninstall(HeldLockDisk disk) {
        if (disk != null) {
            disk.release();
        }
        LockFileWriter.stop();
        LockFileWriter.disk = LockFileWriter.DURABLE;
    }

    public HeldLockDisk fail(String fileName) {
        failing.add(fileName);
        return this;
    }

    public HeldLockDisk heal(String fileName) {
        failing.remove(fileName);
        return this;
    }

    /** Waits until the writer is holding its first operation. */
    public void awaitHolding() throws InterruptedException {
        if (!holding.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("the writer never started");
        }
    }

    public void release() {
        released.countDown();
    }

    @Override
    public void write(Path path, byte[] bytes, boolean sync) throws IOException {
        hold();
        String what = "write " + path.getFileName() + " " + new String(bytes, StandardCharsets.UTF_8) + " " + sync;
        refuse(path, what);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.write(path, bytes);
        done.add(what);
    }

    @Override
    public void delete(Path path) throws IOException {
        hold();
        refuse(path, "delete " + path.getFileName());
        Files.deleteIfExists(path);
        done.add("delete " + path.getFileName());
    }

    // A refused operation is recorded as failed.
    private void refuse(Path path, String what) throws IOException {
        if (failing.contains(path.getFileName().toString())) {
            done.add("failed " + what);
            throw new IOException("disk refused " + path.getFileName());
        }
    }

    private void hold() {
        threads.add(Thread.currentThread().getName());
        if (!first) {
            return;
        }
        first = false;
        holding.countDown();
        try {
            if (!released.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
