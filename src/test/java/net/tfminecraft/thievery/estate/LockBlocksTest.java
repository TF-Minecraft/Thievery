package net.tfminecraft.thievery.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.world.ChunkLoadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockStores.Store;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.owner.OwnerCodec;

/** Learning locked blocks as their chunks load. */
class LockBlocksTest {
    final UUID alice = UUID.randomUUID();
    final Path root = Path.of("plugins/Thievery");
    MockedStatic<Bukkit> bukkit;
    World main;
    World nether;

    @BeforeEach
    void setUp() {
        main = LockHoldingsTest.world("world");
        nether = LockHoldingsTest.world("world_nether");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(main, nether));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

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

    void load(World world, int x, int z) {
        new LockBlocks().onChunkLoad(new ChunkLoadEvent(chunk(world, x, z), false));
    }

    List<String> seen() {
        return OwnedLocks.of(alice).stream()
                .map(lock -> lock.world() + "/" + lock.bound() + "/" + lock.kind() + "/" + lock.partner()).toList();
    }

    @Test
    void aChunkLoadLearnsTheLocksThatFitInItsWorld() {
        Chest left = mock(Chest.class);
        when(left.getType()).thenReturn(Chest.Type.LEFT);
        when(left.getFacing()).thenReturn(BlockFace.NORTH);
        LockHoldingsTest.block(nether, 10, 64, 20, Material.CHEST, true, left);
        LockHoldingsTest.block(nether, 12, 64, 20, Material.STONE, false, mock(BlockData.class));
        own(Store.CONTAINERS, "10_64_20.json", LockState.PUBLIC, null);
        own(Store.DOORS, "11_64_20.json", null, "world");
        own(Store.CONTAINERS, "12_64_20.json", LockState.PUBLIC, null);
        own(Store.CONTAINERS, "40_64_20.json", LockState.PUBLIC, null);

        load(nether, 0, 1);

        assertEquals(List.of("world_nether/false/Chest/11_64_20.json", "null/false/null/null",
                "null/false/null/null", "world/true/null/null"), seen(),
                "the chest is sighted; stone, a door bound to another world and another chunk are not");
        verify(nether, never()).getBlockAt(11, 64, 20);
        verify(nether, never()).getBlockAt(40, 64, 20);
    }

    @Test
    void anEarlierWorldOverridesASightingButALaterOneNeverDisplacesItUntested() {
        LockHoldingsTest.block(nether, 10, 64, 20, Material.BARREL, true, mock(BlockData.class));
        own(Store.CONTAINERS, "10_64_20.json", LockState.PRIVATE, null);
        load(nether, 0, 1);
        assertEquals(List.of("world_nether/false/Barrel/null"), seen());

        // The main world's chunk loads with a chest there: the main world comes first, as on the will screen.
        LockHoldingsTest.block(main, 10, 64, 20, Material.CHEST, true, mock(BlockData.class));
        load(main, 0, 1);
        assertEquals(List.of("world/false/Chest/null"), seen());
        assertEquals(List.of("Chest at 10 64 20"), LockHoldings.describe(alice).labels());

        // With the main world's chunk unloaded again, the nether loading does not move it back.
        when(main.isChunkLoaded(0, 1)).thenReturn(false);
        load(nether, 0, 1);
        assertEquals(List.of("world/false/Chest/null"), seen());
    }

    @Test
    void aSightingWhoseBlockNoLongerFitsIsForgottenButABoundWorldStays() {
        LockHoldingsTest.block(main, 10, 64, 20, Material.STONE, false, mock(BlockData.class));
        LockHoldingsTest.block(main, 11, 64, 20, Material.STONE, false, mock(BlockData.class));
        own(Store.CONTAINERS, "10_64_20.json", LockState.PRIVATE, null);
        own(Store.CONTAINERS, "11_64_20.json", LockState.PRIVATE, "world");
        for (OwnedLocks.Lock lock : OwnedLocks.of(alice)) {
            OwnedLocks.seen(lock, "world", "Chest", null);
        }
        load(main, 0, 1);
        assertEquals(List.of("null/false/null/null", "world/true/Chest/null"), seen());
    }

    @Test
    void aChunkWithoutLocksReadsNoBlocks() {
        own(Store.CONTAINERS, "10_64_20.json", LockState.PUBLIC, null);
        load(main, 9, 9);
        verify(main, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        bukkit.verify(Bukkit::getWorlds, never());
        assertNull(OwnedLocks.of(alice).getFirst().kind());
    }
}
