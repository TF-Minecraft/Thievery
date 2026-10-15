package net.tfminecraft.thievery.estate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.LockStores;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.steal.FurnitureLockHelper;

/**
 * A character's locks as the will screen lists them, in Thievery and Bukkit types only so the lock code never loads
 * a TLibs estate class. Lock files come from {@link OwnedLocks} and furniture from its placed pieces, so nothing is
 * read from disk. The stores keep no block kind or world: {@link LockBlocks} looks at a block where its chunk is
 * loaded, and what it sees is remembered across restarts. Until a block is first seen it reads as a container or a
 * door, and a double chest as two.
 */
public final class LockHoldings {

    /** PRIVATE and PUBLIC records and doors, which go to the property recipient. */
    static final String PROPERTY = "locks";
    /** GUILD and FACTION records, which go to the guild-locks recipient. */
    static final String GUILD = "guild-locks";

    public record Summary(Map<String, Integer> counts, List<String> labels) {}

    // One line; world is null in the main world, at is null for a display whose entity is not loaded.
    private record Item(String kind, String world, LockBlocks.Position at, LockState state) {}

    private static final Comparator<Item> ORDER = Comparator.comparing(Item::kind)
            .thenComparing(Item::world, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Item::at, Comparator.nullsLast(Comparator.comparingInt(LockBlocks.Position::x)
                    .thenComparingInt(LockBlocks.Position::y).thenComparingInt(LockBlocks.Position::z)));

    private LockHoldings() {}

    /** Counts per estate part, with zero parts left out, and one line per lock sorted by kind and place. */
    public static Summary describe(UUID characterId) {
        List<World> worlds = Bukkit.getWorlds();
        String main = worlds.isEmpty() ? null : worlds.get(0).getName();
        List<OwnedLocks.Lock> locks = new ArrayList<>();
        for (OwnedLocks.Lock lock : OwnedLocks.of(characterId)) {
            locks.add(lock.store() == LockStores.Store.ENTITIES ? lock : LockBlocks.look(lock, worlds));
        }
        Set<String> secondHalves = secondHalves(locks);
        List<Item> items = new ArrayList<>();
        for (OwnedLocks.Lock lock : locks) {
            if (lock.store() == LockStores.Store.ENTITIES) {
                items.add(entity(lock, main));
            } else if (lock.store() == LockStores.Store.DOORS || !secondHalves.contains(lock.name())) {
                String kind = lock.kind() != null ? lock.kind()
                        : lock.store() == LockStores.Store.DOORS ? "Door" : "Container";
                items.add(new Item(kind, shown(lock.world(), main), LockBlocks.Position.parse(lock.name()),
                        lock.state()));
            }
        }
        if (Cache.interactibleFurniture) {
            for (FurnitureLockHelper.Owned piece : FurnitureLockHelper.ownedBy(characterId)) {
                items.add(new Item(LockBlocks.words(piece.id()), shown(OwnedLocks.world(piece.location()), main),
                        LockBlocks.Position.of(piece.location()), piece.state()));
            }
        }
        items.sort(ORDER);
        Map<String, Integer> counts = new TreeMap<>();
        List<String> labels = new ArrayList<>();
        for (Item item : items) {
            boolean guild = guild(item.state());
            counts.merge(guild ? GUILD : PROPERTY, 1, Integer::sum);
            labels.add(item.kind() + (item.at() == null ? " (not loaded)"
                    : " at " + item.at().x() + " " + item.at().y() + " " + item.at().z())
                    + (item.world() == null ? "" : " (" + item.world() + ")")
                    + (guild ? " (" + item.state().name().toLowerCase(Locale.ROOT) + ")" : ""));
        }
        return new Summary(counts, labels);
    }

    // A double chest is listed once, by the first half seen to name the other; the named half is left out. Halves in
    // different estate parts go to different recipients, so both are listed. A named half seen as another kind, in
    // another world or naming another partner is a different lock, so it is listed too.
    private static Set<String> secondHalves(List<OwnedLocks.Lock> locks) {
        Map<String, OwnedLocks.Lock> containers = new HashMap<>();
        for (OwnedLocks.Lock lock : locks) {
            if (lock.store() == LockStores.Store.CONTAINERS) {
                containers.put(lock.name(), lock);
            }
        }
        Set<String> second = new HashSet<>();
        for (OwnedLocks.Lock lock : locks) {
            OwnedLocks.Lock other = containers.get(lock.partner());
            if (other != null && guild(other.state()) == guild(lock.state()) && !second.contains(lock.name())
                    && (other.kind() == null || other.kind().equals(lock.kind()))
                    && (other.world() == null || other.world().equals(lock.world()))
                    && (other.partner() == null || other.partner().equals(lock.name()))) {
                second.add(other.name());
            }
        }
        return second;
    }

    private static boolean guild(LockState state) {
        return state == LockState.GUILD || state == LockState.FACTION;
    }

    private static Item entity(OwnedLocks.Lock lock, String main) {
        String name = lock.name();
        Entity entity = Bukkit.getEntity(UUID.fromString(name.substring(0, name.length() - ".json".length())));
        if (entity == null) {
            return new Item("Display", null, null, lock.state());
        }
        Location location = entity.getLocation();
        return new Item(LockBlocks.words(entity.getType().name()), shown(OwnedLocks.world(location), main),
                LockBlocks.Position.of(location), lock.state());
    }

    // The world named on a line: none for the main world or an unknown one.
    private static String shown(String world, String main) {
        return world == null || world.equals(main) ? null : world;
    }
}
