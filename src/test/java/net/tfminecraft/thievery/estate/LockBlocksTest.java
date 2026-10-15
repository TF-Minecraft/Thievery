package net.tfminecraft.thievery.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.Test;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockStores.Store;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.owner.OwnerCodec;

/** Learning locked blocks as their chunks load. */
class LockBlocksTest {
    final UUID alice = UUID.randomUUID();
    final Path root = Path.of("plugins/Thievery");

    void own(Store store, String name, LockState state, String world) {
        OwnedLocks.put(store, root.resolve((store == Store.DOORS ? "door-data/0_1/" : "data/0_1/") + name),
                new OwnerCodec.Stored(alice, LockOwner.Kind.CHARACTER), state, world);
    }

    static Chunk chunk(World world, int x, int z) {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        when(chunk.getWorld()).thenReturn(world);
        return chunk;
    }

    @Test
    void aChunkLoadLearnsTheLocksThatFitInItsWorld() {
        World nether = LockHoldingsTest.world("world_nether");
        Chest left = mock(Chest.class);
        when(left.getType()).thenReturn(Chest.Type.LEFT);
        when(left.getFacing()).thenReturn(BlockFace.NORTH);
        LockHoldingsTest.block(nether, 10, 64, 20, Material.CHEST, true, left);
        LockHoldingsTest.block(nether, 12, 64, 20, Material.STONE, false, mock(BlockData.class));
        own(Store.CONTAINERS, "10_64_20.json", LockState.PUBLIC, null);
        own(Store.DOORS, "11_64_20.json", null, "world");
        own(Store.CONTAINERS, "12_64_20.json", LockState.PUBLIC, null);
        own(Store.CONTAINERS, "40_64_20.json", LockState.PUBLIC, null);

        new LockBlocks().onChunkLoad(new ChunkLoadEvent(chunk(nether, 0, 1), false));

        assertEquals(List.of("world_nether/Chest/11_64_20.json", "null/null/null", "null/null/null", "world/null/null"),
                OwnedLocks.of(alice).stream().map(lock -> lock.world() + "/" + lock.kind() + "/" + lock.partner())
                        .toList(), "the chest is learnt; stone, another world's door and another chunk are not");
        verify(nether, never()).getBlockAt(11, 64, 20);
        verify(nether, never()).getBlockAt(40, 64, 20);
    }

    @Test
    void aChunkWithoutLocksReadsNoBlocks() {
        World world = LockHoldingsTest.world("world");
        own(Store.CONTAINERS, "10_64_20.json", LockState.PUBLIC, null);
        new LockBlocks().onChunkLoad(new ChunkLoadEvent(chunk(world, 9, 9), false));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        assertNull(OwnedLocks.of(alice).getFirst().kind());
    }
}
