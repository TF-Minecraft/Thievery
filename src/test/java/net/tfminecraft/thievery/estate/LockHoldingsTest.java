package net.tfminecraft.thievery.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.interactiblefurniture.InteractibleFurniture;
import net.tfminecraft.interactiblefurniture.furniture.Furniture;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.door.LockState;
import net.tfminecraft.thievery.owner.LockOwner;
import net.tfminecraft.thievery.owner.LockStores.Store;
import net.tfminecraft.thievery.owner.OwnedLocks;
import net.tfminecraft.thievery.owner.OwnerCodec;
import net.tfminecraft.thievery.steal.FurnitureLockHelper;

/** The will screen's lines for a character's locks. */
class LockHoldingsTest {
    MockedStatic<Bukkit> bukkit;
    World main;
    World nether;
    final UUID alice = UUID.randomUUID();
    final Path root = Path.of("plugins/Thievery");

    @BeforeEach
    void setUp() {
        main = world("world");
        nether = world("world_nether");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of(main, nether));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        Cache.interactibleFurniture = false;
    }

    static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    void own(Store store, String name, LockState state, String world) {
        String folder = switch (store) {
            case CONTAINERS -> "data/0_0/";
            case DOORS -> "door-data/0_0/";
            case ENTITIES -> "entities/";
        };
        OwnedLocks.put(store, root.resolve(folder + name), new OwnerCodec.Stored(alice, LockOwner.Kind.CHARACTER),
                state, world);
    }

    static Block block(World world, int x, int y, int z, Material type, boolean container, BlockData data) {
        when(world.isChunkLoaded(x >> 4, z >> 4)).thenReturn(true);
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(type);
        BlockState state = container ? mock(Container.class) : mock(BlockState.class);
        when(block.getState(false)).thenReturn(state);
        when(block.getBlockData()).thenReturn(data);
        when(world.getBlockAt(x, y, z)).thenReturn(block);
        return block;
    }

    static Chest chest(Chest.Type type, BlockFace facing) {
        Chest chest = mock(Chest.class);
        when(chest.getType()).thenReturn(type);
        when(chest.getFacing()).thenReturn(facing);
        return chest;
    }

    Entity entity(EntityType type, World world, int x, int y, int z) {
        UUID id = UUID.randomUUID();
        Entity entity = mock(Entity.class);
        when(entity.getType()).thenReturn(type);
        when(entity.getLocation()).thenReturn(new Location(world, x + 0.5, y, z + 0.5));
        bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(entity);
        own(Store.ENTITIES, id + ".json", LockState.PUBLIC, null);
        return entity;
    }

    @Test
    void listsEachLockByKindAndPlaceWithItsWorldAndPart() {
        // A double chest facing north: the left half joins east, the right half west. It is listed once.
        block(main, 10, 64, 20, Material.CHEST, true, chest(Chest.Type.LEFT, BlockFace.NORTH));
        block(main, 11, 64, 20, Material.CHEST, true, chest(Chest.Type.RIGHT, BlockFace.NORTH));
        own(Store.CONTAINERS, "10_64_20.json", LockState.PRIVATE, null);
        own(Store.CONTAINERS, "11_64_20.json", LockState.PRIVATE, null);
        // A half whose other half is someone else's, and a single chest.
        block(main, 80, 64, 20, Material.TRAPPED_CHEST, true, chest(Chest.Type.LEFT, BlockFace.EAST));
        own(Store.CONTAINERS, "80_64_20.json", LockState.PUBLIC, null);
        block(main, 70, 64, 20, Material.CHEST, true, chest(Chest.Type.SINGLE, BlockFace.SOUTH));
        own(Store.CONTAINERS, "70_64_20.json", LockState.PUBLIC, null);
        block(main, 30, 64, 20, Material.BARREL, true, mock(BlockData.class));
        own(Store.CONTAINERS, "30_64_20.json", LockState.GUILD, null);
        // Stone in the main world, so the shulker box at the same place in the nether is the lock.
        block(main, 40, 64, 20, Material.STONE, false, mock(BlockData.class));
        block(nether, 40, 64, 20, Material.RED_SHULKER_BOX, true, mock(BlockData.class));
        own(Store.CONTAINERS, "40_64_20.json", LockState.FACTION, null);
        // Written in the nether, whose chunk is not loaded: the main world's chest there is not looked at.
        Block elsewhere = block(main, 50, 64, 20, Material.CHEST, true, mock(BlockData.class));
        own(Store.CONTAINERS, "50_64_20.json", LockState.PUBLIC, "world_nether");
        own(Store.CONTAINERS, "100_64_20.json", LockState.PUBLIC, null);
        // Doors, trapdoors and gates; a door record at a chest reads as a plain door, and is not a chest half.
        block(main, 1, 64, 2, Material.OAK_DOOR, false, mock(BlockData.class));
        block(main, 2, 64, 2, Material.SPRUCE_TRAPDOOR, false, mock(BlockData.class));
        block(main, 3, 64, 2, Material.OAK_FENCE_GATE, false, mock(BlockData.class));
        own(Store.DOORS, "1_64_2.json", null, null);
        own(Store.DOORS, "2_64_2.json", null, null);
        own(Store.DOORS, "3_64_2.json", null, null);
        own(Store.DOORS, "11_64_20.json", null, null);
        own(Store.DOORS, "-4_64_2.json", null, "world");
        entity(EntityType.ARMOR_STAND, main, 5, 70, 5);
        entity(EntityType.ITEM_FRAME, nether, -6, 70, 5);
        own(Store.ENTITIES, "00000000-0000-0000-0000-000000000001.json", LockState.GUILD, null);
        own(Store.ENTITIES, "00000000-0000-0000-0000-000000000002.json", LockState.PUBLIC, null);
        Cache.interactibleFurniture = true;
        Furniture pedestal = mock(Furniture.class);
        when(pedestal.getId()).thenReturn("artifact_display");
        when(pedestal.getLoc()).thenReturn(new Location(main, 1, 2, 3));
        when(pedestal.getVariables()).thenReturn(new LinkedHashMap<>(Map.of(FurnitureLockHelper.OWNER_KEY,
                alice.toString(), FurnitureLockHelper.OWNER_KIND_KEY, "CHARACTER", FurnitureLockHelper.LOCK_STATE_KEY,
                "PRIVATE")));
        InteractibleFurniture plugin = mock(InteractibleFurniture.class, RETURNS_DEEP_STUBS);
        when(plugin.getFurnitureManager().getPlacedFurniture()).thenReturn(Map.of(UUID.randomUUID(), pedestal));
        Set<String> lockable = Parameters.lockableFurnitureIds;
        LockHoldings.Summary summary;
        try (MockedStatic<InteractibleFurniture> api = mockStatic(InteractibleFurniture.class)) {
            api.when(InteractibleFurniture::getInstance).thenReturn(plugin);
            Parameters.lockableFurnitureIds = Set.of("artifact_display");
            summary = LockHoldings.describe(alice);
        } finally {
            Parameters.lockableFurnitureIds = lockable;
        }

        assertEquals(List.of(
                "Armour stand at 5 70 5",
                "Artifact display at 1 2 3",
                "Barrel at 30 64 20 (guild)",
                "Chest at 10 64 20",
                "Chest at 70 64 20",
                "Container at 100 64 20",
                "Container at 50 64 20 (world_nether)",
                "Display (not loaded) (guild)",
                "Display (not loaded)",
                "Door at -4 64 2",
                "Door at 1 64 2",
                "Door at 11 64 20",
                "Gate at 3 64 2",
                "Item frame at -6 70 5 (world_nether)",
                "Shulker box at 40 64 20 (world_nether) (faction)",
                "Trapdoor at 2 64 2",
                "Trapped chest at 80 64 20"), summary.labels());
        assertEquals(Map.of("locks", 14, "guild-locks", 3), summary.counts());
        verify(elsewhere, never()).getType();

        // Once seen, a block keeps its kind and world while its chunk is unloaded.
        Cache.interactibleFurniture = false;
        for (World world : List.of(main, nether)) {
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        }
        List<String> unloaded = LockHoldings.describe(alice).labels();
        assertTrue(unloaded.contains("Chest at 10 64 20"));
        assertFalse(unloaded.contains("Chest at 11 64 20"), "the halves still name each other");
        assertTrue(unloaded.contains("Shulker box at 40 64 20 (world_nether) (faction)"));
        assertFalse(unloaded.contains("Artifact display at 1 2 3"), "furniture is read only with InteractibleFurniture");
    }

    @Test
    void aHalfNamingAnUnseenHalfStillListsTheChestOnce() {
        // The left half's chunk is loaded; the right half, across a chunk border, is not.
        block(main, 16, 64, 20, Material.CHEST, true, chest(Chest.Type.LEFT, BlockFace.SOUTH));
        own(Store.CONTAINERS, "15_64_20.json", LockState.PUBLIC, null);
        own(Store.CONTAINERS, "16_64_20.json", LockState.PUBLIC, null);
        LockHoldings.Summary summary = LockHoldings.describe(alice);
        assertEquals(List.of("Chest at 16 64 20"), summary.labels());
        assertEquals(Map.of("locks", 1), summary.counts());

        // A right half facing west joins the block to its south, which names it back.
        block(main, 32, 64, 20, Material.CHEST, true, chest(Chest.Type.RIGHT, BlockFace.WEST));
        block(main, 32, 64, 21, Material.CHEST, true, chest(Chest.Type.LEFT, BlockFace.WEST));
        own(Store.CONTAINERS, "32_64_21.json", LockState.PUBLIC, null);
        own(Store.CONTAINERS, "32_64_20.json", LockState.PUBLIC, null);
        assertEquals(List.of("Chest at 16 64 20", "Chest at 32 64 20"), LockHoldings.describe(alice).labels());
    }

    @Test
    void halvesInDifferentEstatePartsAreListedAndCountedApart() {
        // A chest placed beside a guild chest starts public; the halves go to different recipients.
        block(main, 10, 64, 20, Material.CHEST, true, chest(Chest.Type.LEFT, BlockFace.NORTH));
        block(main, 11, 64, 20, Material.CHEST, true, chest(Chest.Type.RIGHT, BlockFace.NORTH));
        own(Store.CONTAINERS, "10_64_20.json", LockState.GUILD, null);
        own(Store.CONTAINERS, "11_64_20.json", LockState.PUBLIC, null);
        LockHoldings.Summary split = LockHoldings.describe(alice);
        assertEquals(List.of("Chest at 10 64 20 (guild)", "Chest at 11 64 20"), split.labels());
        assertEquals(Map.of("locks", 1, "guild-locks", 1), split.counts());

        // Guild and faction halves are both in the guild part: one chest.
        own(Store.CONTAINERS, "11_64_20.json", LockState.FACTION, null);
        LockHoldings.Summary joined = LockHoldings.describe(alice);
        assertEquals(List.of("Chest at 10 64 20 (guild)"), joined.labels());
        assertEquals(Map.of("guild-locks", 1), joined.counts());
    }

    @Test
    void nothingOwnedDescribesNothingAndAMissingMainWorldNamesEveryWorld() {
        assertEquals(new LockHoldings.Summary(Map.of(), List.of()), LockHoldings.describe(alice));
        bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
        own(Store.CONTAINERS, "1_64_1.json", LockState.PRIVATE, "world");
        assertEquals(new LockHoldings.Summary(Map.of("locks", 1), List.of("Container at 1 64 1 (world)")),
                LockHoldings.describe(alice));
    }

    @Test
    void kindsReadAsPlainBritishWords() {
        assertEquals("Armour stand", LockHoldings.words("ARMOR_STAND"));
        assertEquals("Blast furnace", LockHoldings.words("BLAST_FURNACE"));
        assertEquals("Pedestal", LockHoldings.words("pedestal"));
        Block hopper = block(main, 0, 0, 0, Material.HOPPER, true, mock(BlockData.class));
        assertEquals("Hopper", LockHoldings.kind(Store.CONTAINERS, hopper));
        assertNull(LockHoldings.kind(Store.DOORS, hopper));
        assertNull(LockHoldings.kind(Store.CONTAINERS, block(main, 0, 1, 0, Material.AIR, false, null)));
    }
}
