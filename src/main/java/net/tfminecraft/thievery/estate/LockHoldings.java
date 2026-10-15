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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Entity;

import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.LockStores;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.steal.FurnitureLockHelper;

/**
 * A character's locks as the will screen lists them, in Thievery and Bukkit types only so the lock code never loads
 * a TLibs estate class. Lock files come from {@link OwnedLocks} and furniture from its placed pieces, so nothing is
 * read from disk. The stores keep no block kind or world: a block is looked at only where its chunk is loaded, and
 * remembered once seen. Until then it reads as a container or a door, and a double chest as two.
 */
public final class LockHoldings {

    /** PRIVATE and PUBLIC records and doors, which go to the property recipient. */
    static final String PROPERTY = "locks";
    /** GUILD and FACTION records, which go to the guild-locks recipient. */
    static final String GUILD = "guild-locks";

    public record Summary(Map<String, Integer> counts, List<String> labels) {}

    record Position(int x, int y, int z) {
        static Position of(Location location) {
            return new Position(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        // Index names are always x_y_z.json.
        static Position parse(String name) {
            String[] parts = name.substring(0, name.length() - ".json".length()).split("_");
            return new Position(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
    }

    // One line; world is null in the main world, at is null for a display whose entity is not loaded.
    private record Item(String kind, String world, Position at, LockState state) {}

    private static final Comparator<Item> ORDER = Comparator.comparing(Item::kind)
            .thenComparing(Item::world, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Item::at, Comparator.nullsLast(Comparator.comparingInt(Position::x)
                    .thenComparingInt(Position::y).thenComparingInt(Position::z)));

    private LockHoldings() {}

    /** Counts per estate part, with zero parts left out, and one line per lock sorted by kind and place. */
    public static Summary describe(UUID characterId) {
        List<World> worlds = Bukkit.getWorlds();
        String main = worlds.isEmpty() ? null : worlds.get(0).getName();
        List<OwnedLocks.Lock> locks = new ArrayList<>();
        for (OwnedLocks.Lock lock : OwnedLocks.of(characterId)) {
            locks.add(lock.store() == LockStores.Store.ENTITIES ? lock : look(lock, worlds));
        }
        Set<String> secondHalves = secondHalves(locks);
        List<Item> items = new ArrayList<>();
        for (OwnedLocks.Lock lock : locks) {
            if (lock.store() == LockStores.Store.ENTITIES) {
                items.add(entity(lock, main));
            } else if (lock.store() == LockStores.Store.DOORS || !secondHalves.contains(lock.name())) {
                String kind = lock.kind() != null ? lock.kind()
                        : lock.store() == LockStores.Store.DOORS ? "Door" : "Container";
                items.add(new Item(kind, shown(lock.world(), main), Position.parse(lock.name()), lock.state()));
            }
        }
        if (Cache.interactibleFurniture) {
            for (FurnitureLockHelper.Owned piece : FurnitureLockHelper.ownedBy(characterId)) {
                items.add(new Item(words(piece.id()), shown(OwnedLocks.world(piece.location()), main),
                        Position.of(piece.location()), piece.state()));
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

    // Looks at the block in its own world, or in the first world where it fits, while that chunk is loaded.
    private static OwnedLocks.Lock look(OwnedLocks.Lock lock, List<World> worlds) {
        Position at = Position.parse(lock.name());
        for (World world : worlds) {
            if ((lock.world() == null || lock.world().equals(world.getName()))
                    && world.isChunkLoaded(at.x() >> 4, at.z() >> 4)) {
                Block block = world.getBlockAt(at.x(), at.y(), at.z());
                String kind = kind(lock.store(), block);
                if (kind != null) {
                    return OwnedLocks.seen(lock, world.getName(), kind, partner(block, at));
                }
            }
        }
        return lock;
    }

    /** The block's kind in plain words, or null when it is not the kind of block this store locks. */
    static String kind(LockStores.Store store, Block block) {
        String type = block.getType().name();
        if (store == LockStores.Store.DOORS) {
            return type.endsWith("_TRAPDOOR") ? "Trapdoor" : type.endsWith("_FENCE_GATE") ? "Gate"
                    : type.endsWith("_DOOR") ? "Door" : null;
        }
        if (!(block.getState(false) instanceof Container)) {
            return null;
        }
        return type.endsWith("SHULKER_BOX") ? "Shulker box" : words(type);
    }

    // The file of a double chest's other half: Minecraft joins a left half clockwise of its facing, a right half
    // anticlockwise.
    private static String partner(Block block, Position at) {
        if (!(block.getBlockData() instanceof Chest chest) || chest.getType() == Chest.Type.SINGLE) {
            return null;
        }
        BlockFace facing = chest.getFacing();
        int side = chest.getType() == Chest.Type.LEFT ? 1 : -1;
        return (at.x() - side * facing.getModZ()) + "_" + at.y() + "_" + (at.z() + side * facing.getModX()) + ".json";
    }

    // A double chest is listed once, by the first half seen to name the other; the named half is left out. Halves in
    // different estate parts go to different recipients, so both are listed.
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
            if (other != null && guild(other.state()) == guild(lock.state()) && !second.contains(lock.name())) {
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
        return new Item(words(entity.getType().name()), shown(OwnedLocks.world(location), main),
                Position.of(location), lock.state());
    }

    // The world named on a line: none for the main world or an unknown one.
    private static String shown(String world, String main) {
        return world == null || world.equals(main) ? null : world;
    }

    /** {@code TRAPPED_CHEST} as "Trapped chest", in British spelling. */
    static String words(String name) {
        String words = name.toLowerCase(Locale.ROOT).replace('_', ' ').replace("armor", "armour");
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
