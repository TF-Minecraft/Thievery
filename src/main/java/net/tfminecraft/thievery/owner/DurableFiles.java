package net.tfminecraft.thievery.owner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import com.google.common.hash.Hashing;

/** Lock-file writes: a temp file in the same directory moved into place, with optional fsync (rule 5). */
public final class DurableFiles {

    private DurableFiles() {}

    /**
     * Writes {@code bytes} to {@code target} through a temp file and an atomic move. With {@code sync}, the temp
     * file is forced before the move and the directory after it.
     */
    public static void write(Path target, byte[] bytes, boolean sync) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "." + target.getFileName(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                if (sync) {
                    channel.force(true);
                }
            }
            // Same directory, so the rename is atomic on the server's file systems.
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        if (sync) {
            syncDirectory(dir);
        }
    }

    /** Deletes a file and, if it existed, forces its directory. */
    public static boolean delete(Path target) throws IOException {
        if (!Files.deleteIfExists(target)) {
            return false;
        }
        syncDirectory(target.toAbsolutePath().getParent());
        return true;
    }

    /** Forces a directory so renames and deletions inside it survive a crash. */
    public static void syncDirectory(Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    public static String sha256(byte[] bytes) {
        return Hashing.sha256().hashBytes(bytes).toString();
    }
}
