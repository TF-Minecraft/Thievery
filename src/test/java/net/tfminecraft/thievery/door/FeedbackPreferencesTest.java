package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedbackPreferencesTest {

    private static final Logger LOG = Logger.getLogger("FeedbackPreferencesTest");

    @TempDir Path dir;

    @Test void missingFileListsNobodyAndFirstChoiceCreatesIt() throws Exception {
        Path file = dir.resolve("feedback-off.txt");
        FeedbackPreferences prefs = FeedbackPreferences.load(file, LOG);
        UUID admin = UUID.randomUUID();
        assertFalse(prefs.isMuted(admin));
        prefs.setMuted(admin, true);
        assertTrue(prefs.isMuted(admin));
        assertEquals(List.of(admin.toString()), Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    @Test void choiceSurvivesReloadAndTurningBackOnRemovesIt() throws Exception {
        Path file = dir.resolve("feedback-off.txt");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        FeedbackPreferences first = FeedbackPreferences.load(file, LOG);
        first.setMuted(a, true);
        first.setMuted(b, true);
        FeedbackPreferences second = FeedbackPreferences.load(file, LOG);
        assertTrue(second.isMuted(a));
        assertTrue(second.isMuted(b));
        second.setMuted(a, false);
        FeedbackPreferences third = FeedbackPreferences.load(file, LOG);
        assertFalse(third.isMuted(a));
        assertTrue(third.isMuted(b));
    }

    @Test void blankAndInvalidLinesAreSkipped() throws Exception {
        Path file = dir.resolve("feedback-off.txt");
        UUID a = UUID.randomUUID();
        Files.writeString(file, "\n  " + a + "  \nnot-a-uuid\n", StandardCharsets.UTF_8);
        FeedbackPreferences prefs = FeedbackPreferences.load(file, LOG);
        assertTrue(prefs.isMuted(a));
    }

    @Test void unchangedChoiceDoesNotRewriteTheFile() throws Exception {
        Path file = dir.resolve("feedback-off.txt");
        FeedbackPreferences prefs = FeedbackPreferences.load(file, LOG);
        prefs.setMuted(UUID.randomUUID(), false);
        assertFalse(Files.exists(file));
    }

    @Test void unreadableFileStartsEmpty() throws Exception {
        Path file = dir.resolve("feedback-off.txt");
        Files.createDirectory(file);
        FeedbackPreferences prefs = FeedbackPreferences.load(file, LOG);
        assertFalse(prefs.isMuted(UUID.randomUUID()));
    }

    @Test void failedWriteKeepsTheChoiceInMemory() throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "file, not a directory");
        FeedbackPreferences prefs = FeedbackPreferences.load(blocker.resolve("feedback-off.txt"), LOG);
        UUID admin = UUID.randomUUID();
        prefs.setMuted(admin, true);
        assertTrue(prefs.isMuted(admin));
    }

    @Test void inMemoryWritesNothing() {
        FeedbackPreferences prefs = FeedbackPreferences.inMemory();
        UUID admin = UUID.randomUUID();
        prefs.setMuted(admin, true);
        assertTrue(prefs.isMuted(admin));
        prefs.setMuted(admin, false);
        assertFalse(prefs.isMuted(admin));
    }
}
