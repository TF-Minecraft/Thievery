package net.tfminecraft.thievery.clue;

import static net.tfminecraft.thievery.testsupport.FactionFixture.co;
import net.tfminecraft.thievery.owner.LockOwner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.thievery.testsupport.FactionFixture;
import net.tfminecraft.thievery.door.*;
import net.tfminecraft.rpcharacters.managers.SpawnedClueManager;
import net.tfminecraft.rpcharacters.utils.ClueGiver;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class ClearCluesResolverTest {
    World world; Location location; DoorDataManager doors; ContainerDataManager containers; FactionFixture identity;
    @BeforeEach void setup() { identity=new FactionFixture(); MockBukkit.mock(); world=MockBukkit.getMock().addSimpleWorld("clues"); location=new Location(world,Math.abs(UUID.randomUUID().hashCode()%100000),64,0); doors=new DoorDataManager(); containers=new ContainerDataManager(); }
    @AfterEach void close() { doors.deleteDoorData(location); containers.deleteContainerData(location); MockBukkit.unmock(); identity.close(); }
    @Test void resolutionRecognizesSupportedDoorsAndCanonicalizesTheirTopHalf() {
        assertTrue(ClearCluesResolver.resolve(null).isEmpty()); var block=location.getBlock(); assertTrue(ClearCluesResolver.resolve(block).isEmpty());
        block.setType(Material.IRON_DOOR); assertTrue(ClearCluesResolver.resolve(block).isEmpty()); block.setType(Material.IRON_TRAPDOOR); assertTrue(ClearCluesResolver.resolve(block).isEmpty());
        block.setType(Material.OAK_DOOR); var owner=UUID.randomUUID(); doors.saveDoorData(new DoorData(location,"key",.5,co(owner)));
        var target=ClearCluesResolver.resolve(block).orElseThrow(); assertEquals(ClearCluesResolver.Kind.DOOR,target.getKind()); assertEquals(location,target.getCanonicalLocation()); assertEquals(co(owner),target.getOwner()); assertNull(target.getLockState());
        var top=location.clone().add(0,1,0).getBlock(); top.setType(Material.OAK_DOOR); var data=(Bisected)top.getBlockData(); data.setHalf(Bisected.Half.TOP); top.setBlockData(data); assertEquals(location,ClearCluesResolver.resolve(top).orElseThrow().getCanonicalLocation());
        doors.deleteDoorData(location); block.setType(Material.OAK_TRAPDOOR); assertNull(ClearCluesResolver.resolve(block).orElseThrow().getOwner()); block.setType(Material.OAK_FENCE_GATE); assertEquals(ClearCluesResolver.Kind.DOOR,ClearCluesResolver.resolve(block).orElseThrow().getKind());
    }
    @Test void onlyTheOwningCharacterClearsAPrivateContainerAndTheLockDecidesForGuildAndFactionOnes() {
        location.getBlock().setType(Material.CHEST); UUID owner=UUID.randomUUID(); var data=new ContainerData(location,co(owner)); data.setLockState(LockState.PRIVATE); containers.saveContainerData(data);
        var target=ClearCluesResolver.resolve(location.getBlock()).orElseThrow(); assertEquals(ClearCluesResolver.Kind.CONTAINER,target.getKind()); assertEquals(co(owner),target.getOwner()); assertEquals(LockState.PRIVATE,target.getLockState());
        var player=mock(Player.class); when(player.getUniqueId()).thenReturn(owner); identity.plays(player,UUID.randomUUID());
        assertTrue(ClearCluesResolver.canClear(player,target,true)); assertFalse(ClearCluesResolver.canClear(player,target,false), "an alt on the owner's account is a stranger");
        identity.plays(player,owner); assertTrue(ClearCluesResolver.canClear(player,target,false));
        var publicTarget=new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.CONTAINER,location,co(owner),LockState.PUBLIC); identity.plays(player,null); assertTrue(ClearCluesResolver.canClear(player,publicTarget,false));
        var unowned=new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.CONTAINER,location,null,LockState.PRIVATE); assertTrue(ClearCluesResolver.canClear(player,unowned,false));
        data.setLockState(LockState.GUILD); containers.saveContainerData(data); var guildTarget=ClearCluesResolver.resolve(location.getBlock()).orElseThrow();
        UUID guildmate=UUID.randomUUID(); identity.plays(player,guildmate);
        assertFalse(ClearCluesResolver.canClear(player,guildTarget,false), "no guild, no access");
        identity.guild(owner,"inc","realm"); identity.guild(guildmate,"inc","realm");
        assertTrue(ClearCluesResolver.canClear(player,guildTarget,false), "a guildmate the lock lets in may clear");
        identity.guild(guildmate,"other","realm"); assertFalse(ClearCluesResolver.canClear(player,guildTarget,false));
        data.setLockState(LockState.FACTION); containers.saveContainerData(data); var factionTarget=ClearCluesResolver.resolve(location.getBlock()).orElseThrow();
        assertTrue(ClearCluesResolver.canClear(player,factionTarget,false), "same faction, another guild");
        identity.guild(guildmate,"other","elsewhere"); assertFalse(ClearCluesResolver.canClear(player,factionTarget,false));
        UUID alt=UUID.randomUUID(); identity.plays(player,alt); assertFalse(ClearCluesResolver.canClear(player,factionTarget,false), "an unguilded alt is a stranger");
        identity.plays(player,owner); assertTrue(ClearCluesResolver.canClear(player,guildTarget,false));
        var guildDiverged=new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.CONTAINER,location,LockOwner.unreadable(),LockState.GUILD); assertFalse(ClearCluesResolver.canClear(player,guildDiverged,false));
        var server=new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.CONTAINER,location,LockOwner.server(),LockState.PRIVATE); assertFalse(ClearCluesResolver.canClear(player,server,false)); assertTrue(ClearCluesResolver.canClear(player,server,true));
    }
    @Test void doorOwnerAndLockGuildMembersMayClearButStrangersAndAltsCannot() {
        UUID owner=UUID.randomUUID(); var target=new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.DOOR,location,co(owner),null); var player=mock(Player.class); when(player.getUniqueId()).thenReturn(owner);
        assertTrue(ClearCluesResolver.canClear(player,new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.DOOR,location,null,null),false));
        UUID alt=UUID.randomUUID(); identity.plays(player,alt); assertFalse(ClearCluesResolver.canClear(player,target,false));
        identity.plays(player,owner); assertTrue(ClearCluesResolver.canClear(player,target,false));
        UUID guildmate=UUID.randomUUID(); identity.plays(player,guildmate); identity.guild(owner,"a",null); identity.guild(guildmate,"b",null); assertFalse(ClearCluesResolver.canClear(player,target,false));
        identity.guild(guildmate,"a",null); assertTrue(ClearCluesResolver.canClear(player,target,false));
        assertFalse(ClearCluesResolver.canClear(player,new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.DOOR,location,LockOwner.server(),null),false));
    }
    @Test void clearingRemovesLinkedHologramsAndOnlyClueItemsFromContainers() {
        location.getBlock().setType(Material.CHEST); var inventory=((Container)location.getBlock().getState()).getInventory(); var clue=new ItemStack(Material.PAPER); var loot=new ItemStack(Material.DIAMOND); inventory.setItem(0,clue); inventory.setItem(1,loot);
        var target=new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.CONTAINER,location,null,LockState.PUBLIC); var spawned=mock(SpawnedClueManager.class);
        try(var manager=mockStatic(SpawnedClueManager.class); var clues=mockStatic(ClueGiver.class)) {
            manager.when(SpawnedClueManager::get).thenReturn(spawned); when(spawned.clearLinkedToBlock(location)).thenReturn(2); clues.when(()->ClueGiver.isClueItem(clue)).thenReturn(true);
            assertEquals(3,ClearCluesResolver.clearLinkedClues(target)); assertNull(inventory.getItem(0)); assertEquals(loot,inventory.getItem(1));
            assertEquals(2,ClearCluesResolver.clearLinkedClues(new ClearCluesResolver.ClearCluesTarget(ClearCluesResolver.Kind.DOOR,location,null,null)));
            location.getBlock().setType(Material.STONE); assertEquals(2,ClearCluesResolver.clearLinkedClues(target));
        }
    }
    @Test void doubleChestTargetsReadBothHalvesAndDivergedOwnersActForNobody() {
        var chest=doubleChest(); UUID leftOwner=UUID.randomUUID(), rightOwner=UUID.randomUUID();
        try {
            var empty=ClearCluesResolver.resolve(chest.leftBlock()).orElseThrow();
            assertEquals(chest.leftLocation(),empty.getCanonicalLocation()); assertNull(empty.getOwner()); assertEquals(LockState.PUBLIC,empty.getLockState());
            var right=new ContainerData(chest.rightLocation(),co(rightOwner)); right.setLockState(LockState.PRIVATE); containers.saveContainerData(right);
            for (Block half:List.of(chest.leftBlock(),chest.rightBlock())) {
                var target=ClearCluesResolver.resolve(half).orElseThrow();
                assertEquals(chest.leftLocation(),target.getCanonicalLocation()); assertEquals(co(rightOwner),target.getOwner()); assertEquals(LockState.PRIVATE,target.getLockState());
            }
            var left=new ContainerData(chest.leftLocation(),co(rightOwner)); left.setLockState(LockState.PRIVATE); containers.saveContainerData(left);
            right.setLockState(LockState.PUBLIC); containers.saveContainerData(right);
            assertEquals(LockState.PRIVATE,ClearCluesResolver.resolve(chest.rightBlock()).orElseThrow().getLockState());
            left=new ContainerData(chest.leftLocation(),co(leftOwner)); left.setLockState(LockState.GUILD); containers.saveContainerData(left);
            var player=mock(Player.class); when(player.getUniqueId()).thenReturn(leftOwner);
            for (Block half:List.of(chest.leftBlock(),chest.rightBlock())) {
                var target=ClearCluesResolver.resolve(half).orElseThrow();
                assertEquals(LockOwner.unreadable(),target.getOwner()); assertEquals(LockState.GUILD,target.getLockState());
                assertFalse(ClearCluesResolver.canClear(player,target,false)); assertTrue(ClearCluesResolver.canClear(player,target,true));
            }
        } finally { containers.deleteContainerData(chest.leftLocation()); containers.deleteContainerData(chest.rightLocation()); }
    }
    @Test void clearingDoubleChestVisitsTheFullInventoryAndPreservesNonClueStacks() {
        var chest=doubleChest(); var first=new ItemStack(Material.PAPER); var last=new ItemStack(Material.PAPER,3); var loot=new ItemStack(Material.DIAMOND,64);
        for(int slot=0;slot<54;slot++) chest.fullInventory().setItem(slot,loot.clone());
        chest.fullInventory().setItem(0,first); chest.fullInventory().setItem(53,last);
        var target=ClearCluesResolver.resolve(chest.rightBlock()).orElseThrow(); var spawned=mock(SpawnedClueManager.class);
        try(var manager=mockStatic(SpawnedClueManager.class); var clues=mockStatic(ClueGiver.class)) {
            manager.when(SpawnedClueManager::get).thenReturn(spawned); when(spawned.clearLinkedToBlock(target.getCanonicalLocation())).thenReturn(2);
            clues.when(()->ClueGiver.isClueItem(first)).thenReturn(true); clues.when(()->ClueGiver.isClueItem(last)).thenReturn(true);
            assertEquals(4,ClearCluesResolver.clearLinkedClues(target));
            assertNull(chest.fullInventory().getItem(0)); assertNull(chest.fullInventory().getItem(53));
            for(int slot=1;slot<53;slot++) assertEquals(loot,chest.fullInventory().getItem(slot));
            verify(spawned).clearLinkedToBlock(target.getCanonicalLocation());
        } finally { containers.deleteContainerData(chest.leftLocation()); containers.deleteContainerData(chest.rightLocation()); }
    }
    record DoubleFixture(Block leftBlock,Block rightBlock,Location leftLocation,Location rightLocation,Inventory fullInventory) {}
    DoubleFixture doubleChest() {
        World mapped=mock(World.class); when(mapped.getName()).thenReturn("clues-double");
        when(mapped.getChunkAt(anyInt(),anyInt())).thenAnswer(call->world.getChunkAt((int)call.getArgument(0),(int)call.getArgument(1)));
        when(mapped.getChunkAt(any(Location.class))).thenAnswer(call->{Location at=call.getArgument(0);return world.getChunkAt(at.getBlockX()>>4,at.getBlockZ()>>4);});
        Location leftLocation=new Location(mapped,location.getBlockX(),64,0),rightLocation=leftLocation.clone().add(1,0,0);
        Block leftBlock=mock(Block.class),rightBlock=mock(Block.class); Chest left=mock(Chest.class),right=mock(Chest.class);
        var holder=mock(DoubleChest.class); var inventory=mock(DoubleChestInventory.class); Inventory full=MockBukkit.getMock().createInventory(null,54);
        when(mapped.getBlockAt(leftLocation.getBlockX(),64,0)).thenReturn(leftBlock); when(mapped.getBlockAt(rightLocation.getBlockX(),64,0)).thenReturn(rightBlock);
        when(mapped.getBlockAt(any(Location.class))).thenAnswer(call->{Location at=call.getArgument(0);return mapped.getBlockAt(at.getBlockX(),at.getBlockY(),at.getBlockZ());});
        when(leftBlock.getType()).thenReturn(Material.CHEST);when(rightBlock.getType()).thenReturn(Material.CHEST);
        when(leftBlock.getState()).thenReturn(left);when(rightBlock.getState()).thenReturn(right);
        when(leftBlock.getLocation()).thenReturn(leftLocation);when(rightBlock.getLocation()).thenReturn(rightLocation);
        when(left.getLocation()).thenReturn(leftLocation);when(right.getLocation()).thenReturn(rightLocation);
        when(left.getInventory()).thenReturn(inventory);when(right.getInventory()).thenReturn(inventory);
        when(inventory.getHolder(false)).thenReturn(holder);when(holder.getLeftSide(false)).thenReturn(left);when(holder.getRightSide(false)).thenReturn(right);when(holder.getInventory()).thenReturn(full);
        return new DoubleFixture(leftBlock,rightBlock,leftLocation,rightLocation,full);
    }

}
