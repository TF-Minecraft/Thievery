package net.tfminecraft.thievery.owner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Where the three lock stores and the migration files live. */
public final class LockStores {

    public enum Store {
        CONTAINERS("data", LockRecords.CONTAINER_OWNER, true),
        DOORS("door-data", LockRecords.DOOR_OWNER, true),
        ENTITIES("entities", LockRecords.ENTITY_OWNER, false);

        final String prefix;
        final String ownerField;
        final boolean chunked;

        Store(String prefix, String ownerField, boolean chunked) {
            this.prefix = prefix;
            this.ownerField = ownerField;
            this.chunked = chunked;
        }

        public String ownerField() {
            return ownerField;
        }
    }

    /** A store file named by its store and its path inside it, such as {@code data/0_0/1_64_2.json}. */
    public record StoreFile(Store store, String name, Path path) {}

    private final Path containers;
    private final Path doors;
    private final Path entities;
    private final Path migrations;

    public LockStores(Path containers, Path doors, Path entities, Path migrations) {
        this.containers = containers;
        this.doors = doors;
        this.entities = entities;
        this.migrations = migrations;
    }

    /** The paths the data managers use. Containers and doors are relative to the server directory, as before. */
    public static LockStores defaults(File dataFolder) {
        return new LockStores(Path.of("plugins/Thievery/data"), Path.of("plugins/Thievery/door-data"),
                dataFolder.toPath().resolve("entities"), dataFolder.toPath().resolve("migrations"));
    }

    public Path root(Store store) {
        return switch (store) {
            case CONTAINERS -> containers;
            case DOORS -> doors;
            case ENTITIES -> entities;
        };
    }

    public Path migrations() {
        return migrations;
    }

    /** Resolves a name written by {@link #files()} back to its file. */
    public StoreFile resolve(String name) {
        for (Store store : Store.values()) {
            if (name.startsWith(store.prefix + "/")) {
                String inner = name.substring(store.prefix.length() + 1);
                return new StoreFile(store, name, root(store).resolve(inner));
            }
        }
        throw new IllegalArgumentException("Not a lock store file: " + name);
    }

    /** Every lock record, in a stable order. */
    public List<StoreFile> files() throws IOException {
        List<StoreFile> files = new ArrayList<>();
        for (Store store : Store.values()) {
            Path root = root(store);
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> dirs = store.chunked ? list(root, Files::isDirectory) : List.of(root);
            for (Path dir : dirs) {
                for (Path file : list(dir, path -> Files.isRegularFile(path)
                        && path.getFileName().toString().endsWith(".json"))) {
                    String inner = root.relativize(file).toString().replace(File.separatorChar, '/');
                    files.add(new StoreFile(store, store.prefix + "/" + inner, file));
                }
            }
        }
        return files;
    }

    private static List<Path> list(Path dir, java.util.function.Predicate<Path> filter) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(filter).sorted().toList();
        }
    }
}
