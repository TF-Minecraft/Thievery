package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.player.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

class DoorLockpickTest {
    @Test void successAndRampedBreakProbabilitiesClampInputsAndRespectDexterity() {
        double maximum=Parameters.maxSuccessChance;
        try(var risk=mockStatic(RiskCalculator.class)) {
            Parameters.maxSuccessChance=.95; risk.when(()->RiskCalculator.getDexterityLerpValue(20)).thenReturn(1.5);
            assertEquals(.6,DoorLockpick.computeSuccessChance(20,.5,.8),1e-12);
            assertEquals(0,DoorLockpick.computeSuccessChance(20,-1,.8)); assertEquals(.95,DoorLockpick.computeSuccessChance(20,2,.8));
            assertEquals(0,DoorLockpick.computeSuccessChance(20,.5,-.8));
            assertEquals(.04,DoorLockpick.computeRampedBreakChance(.6,0,.1),1e-12); assertEquals(.2,DoorLockpick.computeRampedBreakChance(.6,5,.1),1e-12);
            assertEquals(.4,DoorLockpick.computeRampedBreakChance(.6,99,.1),1e-12); assertEquals(.8,DoorLockpick.computeRampedSuccessChance(.6,5,.1),1e-12); assertEquals(0,DoorLockpick.computeRampedBreakChance(2,1,.1));
            assertEquals(1,DoorLockpick.computeRampedBreakChance(-2,10,.1));
            assertEquals(DoorLockpick.computeSuccessChance(20,.5,Parameters.chestBaseSuccessChance),ChestLockpickSession.computeSuccessChance(20,.5));
        } finally { Parameters.maxSuccessChance=maximum; }
    }
    @Test void targetIdsAndDoorCentersUseCanonicalBlocksWithoutMutatingLocations() {
        var world=mock(World.class); when(world.getName()).thenReturn("world"); var location=new Location(world,1,2,3); var center=DoorLockpick.getDoorCenter(location);
        assertEquals(new Location(world,1.5,2.5,3.5),center); assertEquals(new Location(world,1,2,3),location);
        assertEquals("door:world:1:2:3",DoorLockpick.doorTargetId(location)); assertEquals("",DoorLockpick.doorTargetId(null)); assertEquals("",DoorLockpick.doorTargetId(new Location(null,0,0,0)));
        UUID id=UUID.randomUUID(); assertEquals("entity:"+id,DoorLockpick.entityTargetId(id)); assertEquals("",DoorLockpick.entityTargetId(null));
        var player=mock(Player.class); when(player.getLocation()).thenReturn(center.clone().add(3,0,0)); assertTrue(DoorLockpick.isWithinDoorRange(player,location,3)); assertFalse(DoorLockpick.isWithinDoorRange(player,location,2.99));
        var anchor=new DoorLockpick.DoorProximityAnchor(location); assertSame(location,anchor.getDoorLocation()); assertEquals(Parameters.doorMaxDistance>=3,anchor.isInRange(player)); anchor.onOutOfRange(player); verify(player).sendMessage("§cLockpicking cancelled - you moved too far from the door.");
    }
    @Test void entityProximityRejectsRemovedTargetsAndDistantPlayers() {
        var world=mock(World.class); var player=mock(Player.class); when(player.getLocation()).thenReturn(new Location(world,0,0,0));
        assertFalse(new DoorLockpick.EntityProximityAnchor(null).isInRange(player)); var entity=mock(Entity.class); var anchor=new DoorLockpick.EntityProximityAnchor(entity); assertSame(entity,anchor.getEntity()); assertFalse(anchor.isInRange(player));
        when(entity.isValid()).thenReturn(true); when(entity.getLocation()).thenReturn(new Location(world,0,0,0)); assertTrue(anchor.isInRange(player));
        when(entity.getLocation()).thenReturn(new Location(world,100,0,0)); assertFalse(anchor.isInRange(player)); anchor.onOutOfRange(player); verify(player).sendMessage("§cLockpicking cancelled - you moved too far.");
    }
    @Test void chestSessionTracksRevealsBudgetCluesAndBrokenTools() {
        UUID id=UUID.randomUUID(); var block=mock(Block.class); var pick=mock(LockpickDefinition.class); when(pick.getCapacity()).thenReturn(10); var inventory=mock(Inventory.class); when(inventory.getSize()).thenReturn(9);
        var defaults=new ChestLockpickSession(id,block,pick,.8,inventory,"door",null); assertEquals(LockTypeProfile.IDENTITY,defaults.getLockType()); assertEquals(10,defaults.getCapacityRemaining());
        var session=new ChestLockpickSession(id,block,pick,.8,inventory,"door",new LockTypeProfile(2,1,false,3));
        assertEquals(id,session.getThiefId()); assertSame(block,session.getChestBlock()); assertSame(pick,session.getLockpickDef()); assertEquals(.8,session.getSuccessChance()); assertEquals(20,session.getCapacityRemaining());
        session.addCapacityUsed(3); assertEquals(17,session.getCapacityRemaining()); assertEquals(1,session.getNextRevealAttempt());
        session.markRevealed(session.getLayout().getGuiSlotForLogical(2)); assertEquals(java.util.Set.of(2),session.getRevealedChestSlots()); assertEquals(2,session.getNextRevealAttempt());
        assertEquals(Math.min(1,DoorLockpick.computeRampedBreakChance(.8,2,Parameters.chestBreakChanceRampPerSlot)*3),session.getNextRevealBreakChance(),1e-12); assertEquals(1-session.getNextRevealBreakChance(),session.getNextRevealSuccessChance(),1e-12);
        assertEquals(0,session.getSuccessfulClueDrops()); session.incrementSuccessfulClueDrops(); assertEquals(1,session.getSuccessfulClueDrops()); assertFalse(session.isLockpickBroken()); session.markLockpickBroken(); assertTrue(session.isLockpickBroken());
    }
}
