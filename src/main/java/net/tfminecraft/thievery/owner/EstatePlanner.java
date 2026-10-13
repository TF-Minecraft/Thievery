package net.tfminecraft.thievery.owner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.estate.LockEstates;
import net.tfminecraft.thievery.estate.LockEstates.Estate;
import net.tfminecraft.thievery.estate.LockEstates.Report;

/**
 * Plans the owner rewrites of decided estates on the lock pass's scan (design R10, M6, N4). For every decided
 * estate whose guard holds, each record the testator owns goes to the recipient of its part: PRIVATE and PUBLIC
 * containers and entities, and doors, to the property recipient; GUILD and FACTION ones to the guildLocks
 * recipient. Waiting or frozen parts are not planned. Only the owner changes, so state and thief cooldowns stay.
 *
 * <p>Only owners marked as characters are planned, so a markerless file the 6a planner claims is never planned
 * twice; it moves at the next boot. The halves of a double chest move together: a container next to one the 6a
 * planner is giving the same testator waits for the next boot too, with every container joined to it.
 *
 * <p>The planner runs in its own {@code try}. A failure leaves the pass with its 6a entries only and reports RETRY;
 * it never keeps locks in guard mode.
 */
final class EstatePlanner {

    private static final Pattern POSITION = Pattern.compile("(-?\\d{1,9})_(-?\\d{1,9})_(-?\\d{1,9})\\.json");

    /** A container's block, from its file name. */
    record Position(int x, int y, int z) {
        List<Position> neighbours() {
            return List.of(new Position(x + 1, y, z), new Position(x - 1, y, z), new Position(x, y, z + 1),
                    new Position(x, y, z - 1));
        }
    }

    /** The estates the pass saw, its moves, and the failure if the planner failed. */
    record Result(List<Estate> estates, List<LockMigration.Planned> moves, String failure) {

        /**
         * One report per estate, from the state after the pass: DONE only when no part going to a character still
         * holds a record of the testator's. Counts and paths come from every journal's applied estate entries.
         */
        List<Report> reports(LockStores stores, List<LockMigration.Scanned> scanned,
                List<LockMigration.Planned> migrations, List<LockMigration.JournalEntry> moved) {
            Map<UUID, int[]> left = left(scanned, migrations, moves);
            List<Report> reports = new ArrayList<>();
            for (Estate estate : estates) {
                int[] held = left.getOrDefault(estate.testator(), new int[2]);
                Set<String> paths = new LinkedHashSet<>();
                for (LockMigration.JournalEntry entry : moved) {
                    if (estate.deathId().toString().equals(entry.deathId)) {
                        paths.add(entry.path);
                    }
                }
                Map<String, Integer> counts = new TreeMap<>();
                for (String path : paths) {
                    counts.merge(countKey(stores.resolve(path).store()), 1, Integer::sum);
                }
                Report.Kind kind;
                String reason = null;
                if (failure != null) {
                    kind = Report.Kind.RETRY;
                    reason = "the estate lock planner failed: " + failure;
                } else if (estate.property() != null && held[0] > 0 || estate.guildLocks() != null && held[1] > 0) {
                    kind = Report.Kind.AT_RESTART;
                } else {
                    kind = Report.Kind.DONE;
                }
                reports.add(new Report(estate, kind, counts, List.copyOf(paths), reason, held[0], held[1]));
            }
            return reports;
        }
    }

    private EstatePlanner() {}

    static Result plan(LockEstates source, List<LockMigration.Scanned> scanned,
            List<LockMigration.Planned> migrations, Logger log) {
        List<Estate> estates = List.of();
        try {
            estates = List.copyOf(source.decided());
            return new Result(estates, moves(estates, scanned, migrations), null);
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "The estate lock planner failed, so this pass moves no estate locks. They move at"
                    + " a later restart.", e);
            return new Result(estates, List.of(), e.toString());
        }
    }

    private static List<LockMigration.Planned> moves(List<Estate> estates, List<LockMigration.Scanned> scanned,
            List<LockMigration.Planned> migrations) {
        Map<UUID, Estate> byTestator = new HashMap<>();
        for (Estate estate : estates) {
            byTestator.putIfAbsent(estate.testator(), estate);
        }
        List<LockMigration.Planned> moves = new ArrayList<>();
        Map<Position, LockMigration.Planned> containers = new HashMap<>();
        for (LockMigration.Scanned file : scanned) {
            if (file.owner() == null || file.owner().marker() != LockOwner.Kind.CHARACTER) {
                continue;
            }
            Estate estate = byTestator.get(file.owner().id());
            UUID recipient = estate == null ? null : estate.recipient(file.state());
            if (recipient == null) {
                continue;
            }
            LockMigration.Planned move = new LockMigration.Planned(file, LockOwner.character(recipient),
                    estate.deathId());
            moves.add(move);
            position(file).ifPresent(at -> containers.put(at, move));
        }

        // Containers the 6a planner gives a testator stay theirs this pass; so does every container joined to them.
        Map<Position, UUID> waiting = new HashMap<>();
        for (LockMigration.Planned migration : migrations) {
            if (byTestator.containsKey(migration.owner().id())) {
                position(migration.scanned()).ifPresent(at -> waiting.put(at, migration.owner().id()));
            }
        }
        Set<LockMigration.Planned> deferred = new HashSet<>();
        Deque<Position> queue = new ArrayDeque<>(waiting.keySet());
        while (!queue.isEmpty()) {
            Position at = queue.poll();
            UUID testator = waiting.get(at);
            for (Position next : at.neighbours()) {
                LockMigration.Planned move = containers.get(next);
                if (move != null && testator.equals(move.scanned().owner().id()) && deferred.add(move)) {
                    waiting.put(next, testator);
                    queue.add(next);
                }
            }
        }
        moves.removeAll(deferred);
        return moves;
    }

    // Records each character owns after the pass, per part: [0] property, [1] guildLocks.
    private static Map<UUID, int[]> left(List<LockMigration.Scanned> scanned, List<LockMigration.Planned> migrations,
            List<LockMigration.Planned> moves) {
        Map<LockMigration.Scanned, UUID> owners = new IdentityHashMap<>();
        for (LockMigration.Scanned file : scanned) {
            if (file.owner() != null && file.owner().marker() == LockOwner.Kind.CHARACTER) {
                owners.put(file, file.owner().id());
            }
        }
        for (LockMigration.Planned migration : migrations) {
            if (migration.owner().isCharacter()) {
                owners.put(migration.scanned(), migration.owner().id());
            }
        }
        for (LockMigration.Planned move : moves) {
            owners.put(move.scanned(), move.owner().id());
        }
        Map<UUID, int[]> left = new HashMap<>();
        owners.forEach((file, owner) -> left.computeIfAbsent(owner, id -> new int[2])[guild(file.state()) ? 1 : 0]++);
        return left;
    }

    // Counts read as possessions on the site ("3 locks, 1 door"): chests and displays are locks, doors are doors.
    static String countKey(LockStores.Store store) {
        return store == LockStores.Store.DOORS ? "doors" : "locks";
    }

    private static boolean guild(LockState state) {
        return state == LockState.GUILD || state == LockState.FACTION;
    }

    static Optional<Position> position(LockMigration.Scanned file) {
        if (file.file().store() != LockStores.Store.CONTAINERS) {
            return Optional.empty();
        }
        Matcher matcher = POSITION.matcher(file.file().path().getFileName().toString());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Position(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))));
    }
}
