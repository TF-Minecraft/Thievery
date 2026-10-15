package net.tfminecraft.thievery.estate;

import java.util.List;
import java.util.Locale;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import net.tfminecraft.thievery.owner.LockStores;
import net.tfminecraft.thievery.owner.OwnedLocks;

/**
 * Looks at the blocks of held lock files to learn their world, kind and double-chest partner, which the stores do not
 * keep. A file has no world, so a block counts only when it is the kind its store locks: a container for a container
 * record, a door, trapdoor or gate for a door record. A record seen in one world is looked for only there. Blocks are
 * looked at when their chunk loads and when the will screen lists them, never by loading a chunk.
 */
public final class LockBlocks implements Listener {

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

    /** Learns the locks of a loaded chunk; a chunk without locks costs one map lookup. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (OwnedLocks.Lock lock : OwnedLocks.inChunk(chunk.getX(), chunk.getZ())) {
            sight(lock, chunk.getWorld());
        }
    }

    /** The record as seen in the first world, in order, whose loaded chunk holds a block of its kind. */
    static OwnedLocks.Lock look(OwnedLocks.Lock lock, List<World> worlds) {
        Position at = Position.parse(lock.name());
        for (World world : worlds) {
            if (world.isChunkLoaded(at.x() >> 4, at.z() >> 4)) {
                OwnedLocks.Lock seen = sight(lock, world);
                if (seen != null) {
                    return seen;
                }
            }
        }
        return lock;
    }

    // The record as seen in this world, whose chunk is loaded; null when it is another world's or the block does not
    // fit its store.
    static OwnedLocks.Lock sight(OwnedLocks.Lock lock, World world) {
        if (lock.world() != null && !lock.world().equals(world.getName())) {
            return null;
        }
        Position at = Position.parse(lock.name());
        Block block = world.getBlockAt(at.x(), at.y(), at.z());
        String kind = kind(lock.store(), block);
        return kind == null ? null : OwnedLocks.seen(lock, world.getName(), kind, partner(block, at));
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

    /** {@code TRAPPED_CHEST} as "Trapped chest", in British spelling. */
    static String words(String name) {
        String words = name.toLowerCase(Locale.ROOT).replace('_', ' ').replace("armor", "armour");
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
