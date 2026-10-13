package net.tfminecraft.thievery.owner;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurableFilesTest {
    @TempDir Path temp;

    @Test void writesReplaceAtomicallyAndLeaveNoTempFiles() throws Exception {
        Path target = temp.resolve("a/b/lock.json");
        DurableFiles.write(target, "one".getBytes(StandardCharsets.UTF_8), true);
        DurableFiles.write(target, "two".getBytes(StandardCharsets.UTF_8), false);
        assertEquals("two", Files.readString(target));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
        assertTrue(DurableFiles.delete(target));
        assertFalse(DurableFiles.delete(target));
        DurableFiles.syncDirectory(temp);
    }

    @Test void aFailedMoveLeavesTheOldFileAndNoTempFile() throws Exception {
        Path target = temp.resolve("busy");
        Files.createDirectories(target.resolve("child"));
        assertThrows(java.io.IOException.class, () -> DurableFiles.write(target, new byte[] {1}, true));
        assertTrue(Files.isDirectory(target));
        try (var files = Files.list(temp)) {
            assertEquals(1, files.count());
        }
    }

    @Test void hashesAreSha256Hex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", DurableFiles.sha256(new byte[0]));
    }
}
