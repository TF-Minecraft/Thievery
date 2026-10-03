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
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
    private double distance;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        MockBukkit.mock();
        distance = Parameters.chestDialDistance;
        Parameters.chestDialDistance = 2.4;
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
    }

    @AfterEach
    void tearDown() {
        Parameters.chestDialDistance = distance;
        MockBukkit.unmock();
    }

    private RingView open() {
        return RingView.open(plugin, viewer, 4, 3);
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

    /** The needle's dark edge and then the needle itself, both drawn as backdrops around a space. */
    private List<TextDisplay> bars() {
        List<TextDisplay> found = textsWith(" ");
        assertEquals(2, found.size());
        return found;
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-5);
        assertEquals(expected.y, actual.y, 1e-5);
        assertEquals(expected.z, actual.z, 1e-5);
    }

    @Test
    void ringFloatsAtFullSizeWithEveryPartHiddenFromOthers() {
        RingView view = open();
        verify(world, times(RingLayout.SIGHT_LINES * RingLayout.SIGHT_LINES)).rayTraceBlocks(any(Location.class),
                any(Vector.class), anyDouble(), eq(FluidCollisionMode.NEVER), eq(true));
        assertEquals(24 + 4 + 3 + 1 + 1 + 2, spawned.size());
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
        assertTrue(spawned.stream().noneMatch(d -> d instanceof ItemDisplay));
        TextDisplay edge = bars().get(0);
        TextDisplay needle = bars().get(1);
        verify(edge).setBackgroundColor(RingView.NEEDLE_EDGE);
        verify(needle).setBackgroundColor(RingView.NEEDLE);
        verify(needle).setShadowed(false);
        Transformation start = transforms.get(needle);
        assertVector(new Vector3f(RingView.NEEDLE_WIDTH / RingView.BACKDROP_WIDTH,
                RingView.NEEDLE_LENGTH / RingView.BACKDROP_HEIGHT, 1), start.getScale());
        assertVector(new Vector3f(0, RingView.NEEDLE_RADIUS - RingView.NEEDLE_LENGTH / 2, 0.02f), start.getTranslation());
        float wide = RingView.NEEDLE_WIDTH + 2 * RingView.NEEDLE_EDGE_WIDTH;
        assertEquals(wide / RingView.BACKDROP_WIDTH, transforms.get(edge).getScale().x, 1e-5);
        assertEquals(0.015f, transforms.get(edge).getTranslation().z, 1e-6);
    }

    @Test
    void aBlockInTheWayPullsTheRingCloserAndShrinksIt() {
        // A wall across z = 2.5, two blocks in front of the eye.
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean()))
                .thenAnswer(call -> {
                    Vector eye = call.<Location>getArgument(0).toVector();
                    Vector direction = call.getArgument(1);
                    assertEquals(1.0, direction.length(), 1e-9);
                    return new RayTraceResult(eye.add(direction.clone().multiply(2.0 / direction.getZ())));
                });
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
    void theNeedleAndItsEdgeGlideTogetherAcrossTheDotsAtAnyAngle() {
        RingView view = open();
        float half = RingView.NEEDLE_LENGTH / 2;
        view.pick(90, 2);
        for (TextDisplay bar : bars()) {
            verify(bar).setInterpolationDelay(0);
            verify(bar).setInterpolationDuration(2);
        }
        Transformation at = transforms.get(bars().get(1));
        assertEquals(RingView.NEEDLE_RADIUS - half, at.getTranslation().x, 1e-5);
        assertEquals(0, at.getTranslation().y, 1e-5);
        view.pick(180, 0);
        verify(bars().get(1)).setInterpolationDuration(0);
        assertEquals(-RingView.NEEDLE_RADIUS + half, transforms.get(bars().get(1)).getTranslation().y, 1e-5);
        for (double degrees = 0; degrees < 360; degrees += 30) {
            for (float edge : new float[] {0, RingView.NEEDLE_EDGE_WIDTH}) {
                Transformation turned = view.needleTransform(degrees, edge, 0.02f);
                // The backdrop grows up from its origin: turned, it lies along the radius, centred on the ring.
                Vector3f along = turned.getLeftRotation().transform(new Vector3f(0, 1, 0));
                Vector3f outward = RingLayout.onRing(degrees, 1);
                assertEquals(1, Math.abs(along.dot(outward)), 1e-5);
                float length = RingView.NEEDLE_LENGTH + 2 * edge;
                Vector3f middle = new Vector3f(turned.getTranslation())
                        .add(turned.getLeftRotation().transform(new Vector3f(0, length / 2, 0)));
                assertVector(RingLayout.onRing(degrees, RingView.NEEDLE_RADIUS).add(0, 0, 0.02f), middle);
            }
        }
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
    void burstsPuffColouredDustPastTheNeedleTipForTheViewerOnly() {
        RingView view = open();
        view.pick(90, 0);
        view.burst(RingView.GREEN, 3);
        ArgumentCaptor<Location> at = ArgumentCaptor.forClass(Location.class);
        ArgumentCaptor<Particle.DustOptions> dust = ArgumentCaptor.forClass(Particle.DustOptions.class);
        verify(viewer).spawnParticle(eq(Particle.DUST), at.capture(), eq(3), eq(0.03), eq(0.03), eq(0.03), eq(0.0),
                dust.capture());
        // Facing south, the ring's right is west, so a pointer at three o'clock sits towards -x.
        assertEquals(0.5 - RingView.BURST_RADIUS, at.getValue().getX(), 1e-5);
        assertEquals(RingView.GREEN.value(), dust.getValue().getColor().asRGB());
        assertEquals(RingView.BURST_SIZE, dust.getValue().getSize(), 1e-6);
        verify(world, never()).spawnParticle(any(Particle.class), any(Location.class), anyInt());
    }

    @Test
    void burstsClearTheDotsAndTheNeedle() {
        // The dust puffs out about a tenth of a block; it must start beyond the dots and the needle's outer tip.
        assertTrue(RingView.BURST_RADIUS - 0.05f > RingView.NEEDLE_RADIUS + RingView.NEEDLE_LENGTH / 2);
        assertTrue(RingView.BURST_RADIUS - 0.05f > RingLayout.RADIUS + 0.1f);
        float reach = RingView.BURST_RADIUS + 0.1f;
        assertTrue(reach < RingLayout.HALF_WIDTH && reach < RingLayout.TOP && -reach > RingLayout.BOTTOM,
                "bursts stay inside the outline kept clear of blocks");
    }

    @Test
    void aCloseRingShrinksItsBursts() {
        // A wall across z = 2.5 halves the ring, as in aBlockInTheWayPullsTheRingCloserAndShrinksIt.
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(), any(FluidCollisionMode.class), anyBoolean()))
                .thenAnswer(call -> {
                    Vector eye = call.<Location>getArgument(0).toVector();
                    Vector direction = call.getArgument(1);
                    return new RayTraceResult(eye.add(direction.clone().multiply(2.0 / direction.getZ())));
                });
        RingView view = open();
        view.pick(90, 0);
        view.burst(RingView.RED, 4);
        ArgumentCaptor<Location> at = ArgumentCaptor.forClass(Location.class);
        ArgumentCaptor<Particle.DustOptions> dust = ArgumentCaptor.forClass(Particle.DustOptions.class);
        verify(viewer).spawnParticle(eq(Particle.DUST), at.capture(), eq(4), eq(0.015), eq(0.015), eq(0.015),
                eq(0.0), dust.capture());
        assertEquals(0.5 - RingView.BURST_RADIUS * 0.5, at.getValue().getX(), 1e-5);
        assertEquals(RingView.RED.value(), dust.getValue().getColor().asRGB());
        assertEquals(RingView.BURST_SIZE * 0.5f, dust.getValue().getSize(), 1e-6);
    }

    @Test
    void removeTakesEveryPartDown() {
        RingView view = open();
        List<Display> parts = new ArrayList<>(spawned);
        view.remove();
        for (Display display : parts) {
            verify(display).remove();
        }
        view.remove();
        verify(parts.get(0), times(1)).remove();
    }
}
