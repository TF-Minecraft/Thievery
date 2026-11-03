package net.tfminecraft.thievery.door;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.tfminecraft.thievery.owner.DurableFiles;

/**
 * Staff who turned {@code /thievery feedback} off. Admins get feedback alerts at join unless they are listed here, so
 * the choice survives a relog and a restart. One UUID per line; without a file nobody is listed.
 */
public final class FeedbackPreferences {

    private final Path file;
    private final Logger logger;
    private final Set<UUID> muted = new HashSet<>();

    /** Kept in memory only (nothing is read or written). */
    public static FeedbackPreferences inMemory() {
        return new FeedbackPreferences(null, null);
    }

    /** Loads {@code file} if it exists; a line that is not a UUID is skipped. */
    public static FeedbackPreferences load(Path file, Logger logger) {
        FeedbackPreferences preferences = new FeedbackPreferences(file, logger);
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    try {
                        preferences.muted.add(UUID.fromString(trimmed));
                    } catch (IllegalArgumentException invalid) {
                        logger.warning("[Thievery] Skipping a line that is not a UUID in " + file.getFileName()
                                + ": " + trimmed);
                    }
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "[Thievery] Could not read " + file + "; feedback starts on for all admins.",
                        e);
            }
        }
        return preferences;
    }

    private FeedbackPreferences(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public boolean isMuted(UUID uuid) {
        return muted.contains(uuid);
    }

    /** Records the choice and, when it changed, writes the file. A failed write keeps the choice until restart. */
    public void setMuted(UUID uuid, boolean value) {
        boolean changed = value ? muted.add(uuid) : muted.remove(uuid);
        if (changed && file != null) {
            save();
        }
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        for (UUID uuid : muted) {
            lines.add(uuid.toString());
        }
        Collections.sort(lines);
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        try {
            DurableFiles.write(file, text.toString().getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            logger.log(Level.WARNING, "[Thievery] Could not save " + file + "; the feedback choice lasts until restart.",
                    e);
        }
    }
}
