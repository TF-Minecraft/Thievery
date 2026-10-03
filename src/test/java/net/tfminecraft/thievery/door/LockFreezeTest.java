package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.utils.Keys;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.persistence.PersistentDataContainerMock;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class LockFreezeTest {
    private MockedStatic<Thievery> plugin;
    private Player player;
    private PersistentDataContainerMock data;
    private AttributeInstance jump;
    private float walkSpeed = 0.2f;
    private boolean flying;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Thievery instance = mock(Thievery.class);
        when(instance.getName()).thenReturn("Thievery");
        when(instance.namespace()).thenReturn("thievery");
        plugin = mockStatic(Thievery.class);
        plugin.when(Thievery::getInstance).thenReturn(instance);
        data = new PersistentDataContainerMock();
        jump = mock(AttributeInstance.class);
        player = mock(Player.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        when(player.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(jump);
        when(player.getWalkSpeed()).thenAnswer(call -> walkSpeed);
        doAnswer(call -> walkSpeed = call.getArgument(0)).when(player).setWalkSpeed(anyFloat());
        when(player.isFlying()).thenAnswer(call -> flying);
        doAnswer(call -> flying = call.getArgument(0)).when(player).setFlying(anyBoolean());
    }

    @AfterEach
    void tearDown() {
        plugin.close();
        MockBukkit.unmock();
    }

    @Test
    void freezingHoldsWalkAndJumpAndReleasingPutsThemBack() {
        assertFalse(LockFreeze.isFrozen(player));
        LockFreeze.freeze(player);
        assertTrue(LockFreeze.isFrozen(player));
        assertEquals(0f, walkSpeed);
        assertEquals(0.2f, data.get(Keys.lockpickWalkSpeed, PersistentDataType.FLOAT));
        ArgumentCaptor<AttributeModifier> modifier = ArgumentCaptor.forClass(AttributeModifier.class);
        verify(jump).addTransientModifier(modifier.capture());
        assertEquals(Keys.lockpickJump, modifier.getValue().getKey());
        assertEquals(-1.0, modifier.getValue().getAmount());
        assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1, modifier.getValue().getOperation());

        LockFreeze.release(player);
        assertFalse(LockFreeze.isFrozen(player));
        assertEquals(0.2f, walkSpeed);
        verify(jump).removeModifier(Keys.lockpickJump);
        verify(player, never()).setFlying(anyBoolean());
    }

    @Test
    void freezingTwiceKeepsTheFirstSpeedAndJumpModifier() {
        LockFreeze.freeze(player);
        when(jump.getModifier(Keys.lockpickJump)).thenReturn(mock(AttributeModifier.class));
        LockFreeze.freeze(player);
        verify(jump, times(1)).addTransientModifier(any());
        LockFreeze.release(player);
        assertEquals(0.2f, walkSpeed);
    }

    @Test
    void playersWithoutAJumpAttributeStillFreeze() {
        when(player.getAttribute(Attribute.JUMP_STRENGTH)).thenReturn(null);
        LockFreeze.freeze(player);
        assertEquals(0f, walkSpeed);
        LockFreeze.release(player);
        assertEquals(0.2f, walkSpeed);
    }

    @Test
    void flyingThievesAreSetDownAndLiftedAgainOnlyIfStillAllowed() {
        flying = true;
        LockFreeze.freeze(player);
        assertFalse(flying);
        assertTrue(data.has(Keys.lockpickFlying, PersistentDataType.BYTE));
        when(player.getAllowFlight()).thenReturn(true);
        LockFreeze.release(player);
        assertTrue(flying);
        assertFalse(data.has(Keys.lockpickFlying, PersistentDataType.BYTE));

        LockFreeze.freeze(player);
        when(player.getAllowFlight()).thenReturn(false);
        LockFreeze.release(player);
        assertFalse(flying);
    }

    @Test
    void aCrashLeftoverIsUndoneAndAFreshPlayerIsLeftAlone() {
        // As if the server stopped mid-pick: the stored speed survived, the transient jump block did not.
        data.set(Keys.lockpickWalkSpeed, PersistentDataType.FLOAT, 0.3f);
        walkSpeed = 0f;
        LockFreeze.release(player);
        assertEquals(0.3f, walkSpeed);
        clearInvocations(player);
        LockFreeze.release(player);
        verify(player, never()).setWalkSpeed(anyFloat());
    }
}
