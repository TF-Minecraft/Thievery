package net.tfminecraft.thievery.door;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.tfminecraft.thievery.cache.Parameters;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

class RingViewTest {
    private Plugin plugin;
    private Player viewer;
    private World world;
    private final List<Display> spawned = new ArrayList<>();
    private final Map<Display, Component> texts = new HashMap<>();
    private final Map<Display, Transformation> transforms = new HashMap<>();
    private ItemStack lockpick;
    private double distance;
    private double tip;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        MockBukkit.mock();
        distance = Parameters.chestDialDistance;
        tip = Parameters.chestDialPickTipDegrees;
        Parameters.chestDialDistance = 2.4;
        Parameters.chestDialPickTipDegrees = 45;
        plugin = mock(Plugin.class);
        viewer = mock(Player.class);
        world = mock(World.class);
        when(viewer.getWorld()).thenReturn(world);
        when(viewer.getEyeLocation()).thenReturn(new Location(world, 0.5, 65.62, 0.5, 0f, 0f));
        when(world.spawn(any(Location.class), any(Class.class), any(Consumer.class))).thenAnswer(call -> {
            Class<? extends Display> type = call.getArgument(1);
            Display display = mock(type);
            if (display instanceof TextDisplay text) {
                doAnswer(set -> texts.put(text, set.getArgument(0))).when(text).text(any(Component.class));
            }
            doAnswer(set -> transforms.put(display, set.getArgument(0))).when(display).setTransformation(any());
            when(display.getLocation()).thenReturn(call.getArgument(0));
            ((Consumer) call.getArgument(2)).accept(display);
            spawned.add(display);
            return display;
        });
        lockpick = new ItemStack(Material.TRIPWIRE_HOOK, 3);
    }

    @AfterEach
    void tearDown() {
        Parameters.chestDialDistance = distance;
        Parameters.chestDialPickTipDegrees = tip;
        MockBukkit.unmock();
    }

    private RingView open() {
        return RingView.open(plugin, viewer, lockpick, 4, 3);
    }

    private List<TextDisplay> textsWith(String glyph) {
        List<TextDisplay> found = new ArrayList<>();
        for (Display display : spawned) {
            if (display instanceof TextDisplay text && texts.get(text) instanceof Component c
                    && glyph.equals(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c))) {
                found.add(text);
            }
        }
        return found;
    }

    private ItemDisplay pick() {
        return (ItemDisplay) spawned.stream().filter(d -> d instanceof ItemDisplay).findFirst().orElseThrow();
    }

    @Test
    void ringFloatsAtFullSizeWithEveryPartHiddenFromOthers() {
        RingView view = open();
        verify(world).rayTraceBlocks(any(Location.class), any(Vector.class), eq(2.4), eq(FluidCollisionMode.NEVER), eq(true));
        assertEquals(24 + 4 + 3 + 1 + 1 + 1, spawned.size());
        assertEquals(spawned, view.displays());
        ArgumentCaptor<Location> where = ArgumentCaptor.forClass(Location.class);
        verify(world, atLeastOnce()).spawn(where.capture(), any(Class.class), any(Consumer.class));
        Location centre = where.getValue();
        assertEquals(0.5, centre.getX(), 1e-6);
        assertEquals(65.62 + RingView.LIFT, centre.getY(), 1e-6);
        assertEquals(0.5 + 2.4, centre.getZ(), 1e-6);
        assertEquals(180f, centre.getYaw(), 1e-6);
        for (Display display : spawned) {
            verify(display).setVisibleByDefault(false);
            verify(display).setPersistent(false);
            verify(display).setBillboard(Display.Billboard.FIXED);
            verify(display).setBrightness(new Display.Brightness(15, 15));
            verify(viewer).showEntity(plugin, display);
        }
        assertEquals(24, textsWith(RingView.DOT).size());
        assertEquals(4, textsWith(RingView.PIN).size());
        assertEquals(3, textsWith("○").size());
        ItemDisplay pick = pick();
        ArgumentCaptor<ItemStack> shown = ArgumentCaptor.forClass(ItemStack.class);
        verify(pick).setItemStack(shown.capture());
        assertEquals(Material.TRIPWIRE_HOOK, shown.getValue().getType());
        assertEquals(1, shown.getValue().getAmount());
        assertEquals(3, lockpick.getAmount());
        verify(pick).setGlowing(true);
        verify(pick).setGlowColorOverride(RingView.PICK_GLOW);
        verify(pick).setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        assertEquals(new Vector3f(RingView.PICK_SIZE, RingView.PICK_SIZE, RingView.PICK_SIZE), transforms.get(pick).getScale());
    }

    @Test
    void aBlockInTheWayPullsTheRingCloserAndShrinksIt() {
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean()))
                .thenReturn(new RayTraceResult(new Vector(0.5, 65.62, 2.5)));
        open();
        ArgumentCaptor<Location> where = ArgumentCaptor.forClass(Location.class);
        verify(world, atLeastOnce()).spawn(where.capture(), any(Class.class), any(Consumer.class));
        assertEquals(0.5 + 1.2, where.getValue().getZ(), 1e-6);
        assertEquals(65.62 + RingView.LIFT * 0.5, where.getValue().getY(), 1e-6);
        TextDisplay firstDot = textsWith(RingView.DOT).get(0);
        assertEquals(new Vector3f(0, (RingLayout.RADIUS - 0.07f) * 0.5f, 0), transforms.get(firstDot).getTranslation());
        assertEquals(0.75f * 0.5f, transforms.get(firstDot).getScale().x, 1e-6);
    }

    @Test
    void zoneDotsLightForTheSweepInEitherDirection() {
        RingView view = open();
        List<TextDisplay> dots = textsWith(RingView.DOT);
        Sweep clockwise = new Sweep(40, 0.25, 0.375, true, 1);
        view.zone(clockwise, RingView.ZONE);
        assertEquals(List.of(6, 7, 8), lit(dots, RingView.ZONE));
        Sweep back = new Sweep(40, 0.25, 0.375, false, 1);
        view.zone(back, RingView.RED);
        assertEquals(List.of(16, 17, 18), lit(dots, RingView.RED));
    }

    private List<Integer> lit(List<TextDisplay> dots, TextColor colour) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < dots.size(); i++) {
            if (colour.equals(texts.get(dots.get(i)).color())) {
                found.add(i);
            }
        }
        return found;
    }

    @Test
    void thePickGlidesOrSnapsAndPointsInward() {
        RingView view = open();
        ItemDisplay pick = pick();
        view.pick(90, 2);
        verify(pick).setInterpolationDelay(0);
        verify(pick).setInterpolationDuration(2);
        Transformation at = transforms.get(pick);
        assertEquals(RingView.PICK_RADIUS, at.getTranslation().x, 1e-5);
        assertEquals(0, at.getTranslation().y, 1e-5);
        assertEquals(RingLayout.pointInward(90, 45), at.getLeftRotation());
        view.pick(180, 0);
        verify(pick).setInterpolationDuration(0);
        assertEquals(-RingView.PICK_RADIUS, transforms.get(pick).getTranslation().y, 1e-5);
    }

    @Test
    void labelPinsAndSlipsChange() {
        RingView view = open();
        List<TextDisplay> pins = textsWith(RingView.PIN);
        List<TextDisplay> slips = textsWith("○");
        TextDisplay label = spawned.stream().filter(d -> d instanceof TextDisplay t && Component.empty().equals(texts.get(t)))
                .map(TextDisplay.class::cast).findFirst().orElseThrow();
        view.label(Component.text("Set"), 2f);
        assertEquals(Component.text("Set"), texts.get(label));
        assertEquals(2f, transforms.get(label).getScale().x, 1e-6);
        verify(label).setInterpolationDuration(3);
        view.pin(1);
        assertEquals(RingView.GOLD, texts.get(pins.get(1)).color());
        assertEquals(RingView.pinAt(1, 4, true), transforms.get(pins.get(1)).getTranslation());
        verify(pins.get(1)).setInterpolationDuration(4);
        view.slip(0);
        assertEquals(RingView.RED, texts.get(slips.get(0)).color());
        view.slip(5);
        assertEquals(RingView.DIM, texts.get(slips.get(2)).color());
    }

    @Test
    void burstsAppearAtThePickForTheViewerOnly() {
        RingView view = open();
        view.pick(90, 0);
        view.burst(Particle.HAPPY_VILLAGER, 3);
        ArgumentCaptor<Location> at = ArgumentCaptor.forClass(Location.class);
        verify(viewer).spawnParticle(eq(Particle.HAPPY_VILLAGER), at.capture(), eq(3), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        // Facing south, the ring's right is west, so a pick at three o'clock sits towards -x.
        assertEquals(0.5 - RingView.PICK_RADIUS, at.getValue().getX(), 1e-5);
        verify(world, never()).spawnParticle(any(Particle.class), any(Location.class), anyInt());
    }

    @Test
    void removeTakesEveryPartDown() {
        RingView view = open();
        List<Display> parts = new ArrayList<>(spawned);
        view.remove();
        for (Display display : parts) {
            verify(display).remove();
        }
        assertTrue(view.displays().isEmpty());
        view.remove();
        verify(parts.get(0), times(1)).remove();
    }
}
