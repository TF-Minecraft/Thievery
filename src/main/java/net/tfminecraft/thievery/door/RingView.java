package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import net.tfminecraft.thievery.cache.Parameters;

/**
 * The floating lockpick ring: display entities that only the thief can see. The server only sends where the pointer
 * should be a moment ahead; the client glides it there every frame, so it moves at the player's own frame rate.
 *
 * <p>The same ring, built as a gauge, is the pickpocket ring: a band of dots that fills clockwise from twelve
 * o'clock, with the percentage, phase and key to press in the middle.
 */
final class RingView {

    static final TextColor DIM = TextColor.color(0x4a4e57);
    static final TextColor ZONE = TextColor.color(0x5be37d);
    static final TextColor GOLD = TextColor.color(0xf2c53d);
    static final TextColor GREEN = TextColor.color(0x6fd34f);
    static final TextColor RED = TextColor.color(0xe0524c);
    /**
     * The pointer is a needle across the dots: a white bar with a dark edge, drawn as the background of a text
     * display around a space. A flat quad keeps straight, sharp edges at any angle, where a font glyph or an item
     * sprite turns into a blur of pixels.
     */
    static final Color NEEDLE = Color.fromARGB(255, 255, 255, 255);
    static final Color NEEDLE_EDGE = Color.fromARGB(220, 16, 16, 20);
    static final float NEEDLE_WIDTH = 0.05f;
    static final float NEEDLE_LENGTH = 0.36f;
    static final float NEEDLE_EDGE_WIDTH = 0.02f;
    /** The needle's middle sits a little outside the dots, so more of it shows beyond the ring than inside. */
    static final float NEEDLE_RADIUS = RingLayout.RADIUS + 0.05f;
    /** Bursts go off just past the needle's outer tip, clear of the dots, so they never hide the zone. */
    static final float BURST_RADIUS = NEEDLE_RADIUS + NEEDLE_LENGTH / 2 + 0.08f;
    /** Dust size at full scale; it shrinks with the ring, where other particles stay one size and swamp a close ring. */
    static final float BURST_SIZE = 0.6f;
    /** A text display's background around one space, at scale 1: five pixels by ten, a fortieth of a block each. */
    static final float BACKDROP_WIDTH = 0.125f;
    static final float BACKDROP_HEIGHT = 0.25f;
    static final String DOT = "\u25cf";
    static final String PIN = "\u258e";
    static final float LIFT = 0.14f;
    /** The gauge's dots touch, so its band reads as one ring. */
    static final int GAUGE_DOTS = 40;
    static final String PIP = "\u25c6";

    private final Plugin plugin;
    private final Player viewer;
    private final Location centre;
    private final float scale;
    private final float yaw;
    private final float pitch;
    private final List<TextDisplay> dots = new ArrayList<>();
    private final List<TextDisplay> pins = new ArrayList<>();
    private final List<TextDisplay> slips = new ArrayList<>();
    private final List<Display> all = new ArrayList<>();
    private TextDisplay label;
    private TextDisplay caption;
    private int litDots;
    private TextColor litColour;
    private TextColor trackColour;
    private TextDisplay needle;
    private TextDisplay needleEdge;
    private double pointerDegrees;

    RingView(Plugin plugin, Player viewer, Location centre, float scale, float yaw, float pitch) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.centre = centre;
        this.scale = scale;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Floats the lockpick ring in front of the viewer's eyes, closer if a block is in the way. */
    static RingView open(Plugin plugin, Player viewer, int tumblers, int maxSlips) {
        RingView view = inFront(plugin, viewer, null);
        view.build(tumblers, maxSlips);
        return view;
    }

    /**
     * Floats the pickpocket gauge where {@link #open} would float the lockpick ring, but in front of {@code mark}
     * too, whose body would otherwise hide it. A null mark is a staff test.
     */
    static RingView openGauge(Plugin plugin, Player viewer, Entity mark, int phases) {
        RingView view = inFront(plugin, viewer, mark);
        view.buildGauge(phases);
        return view;
    }

    /** Where the ring floats: short of any block, and of {@code mark}'s body when there is one. */
    private static RingView inFront(Plugin plugin, Player viewer, Entity mark) {
        Location eye = viewer.getEyeLocation();
        World world = viewer.getWorld();
        double distance = RingLayout.fitDistance(Parameters.chestDialDistance, eye.getYaw(), eye.getPitch(), LIFT,
                (line, reach) -> {
                    Vector direction = line.clone().normalize();
                    RayTraceResult block = world.rayTraceBlocks(eye, direction, reach, FluidCollisionMode.NEVER, true);
                    RayTraceResult body = mark == null ? null
                            : mark.getBoundingBox().rayTrace(eye.toVector(), direction, reach);
                    return Math.min(along(eye, block), along(eye, body));
                });
        float scale = (float) (distance / RingLayout.DISTANCE);
        // Lifted a little above the crosshair so the bottom of the ring clears the action bar.
        Location centre = eye.clone().add(eye.getDirection().multiply(distance))
                .add(RingLayout.worldOffset(eye.getYaw(), eye.getPitch(), new Vector3f(0, LIFT * scale, 0)));
        centre.setYaw(eye.getYaw() + 180f);
        centre.setPitch(-eye.getPitch());
        return new RingView(plugin, viewer, centre, scale, eye.getYaw(), eye.getPitch());
    }

    void build(int tumblers, int maxSlips) {
        for (int dot = 0; dot < RingLayout.DOTS; dot++) {
            dots.add(text(Component.text(DOT, DIM), RingLayout.onRing(RingLayout.dotAngle(dot), RingLayout.RADIUS)
                    .add(0, -0.07f, 0), 0.75f));
        }
        for (int i = 0; i < tumblers; i++) {
            pins.add(text(Component.text(PIN, DIM), pinAt(i, tumblers, false), 1.4f));
        }
        for (int i = 0; i < maxSlips; i++) {
            slips.add(text(Component.text("\u25cb", DIM), slipAt(i, maxSlips), 0.55f));
        }
        text(Component.keybind("key.sneak").append(Component.text(" to give up")).color(DIM),
                new Vector3f(0, RingLayout.RADIUS + 0.5f, 0), 0.34f);
        label = text(Component.empty(), new Vector3f(0, -0.16f, 0.01f), 1.1f);
        needleEdge = bar(NEEDLE_EDGE, NEEDLE_EDGE_WIDTH, 0.015f);
        needle = bar(NEEDLE, 0, 0.02f);
    }

    /**
     * An empty gauge: a dim band, a pip above it for each phase, and in the middle the phase above the
     * percentage, which {@link #label} sets, above the key to mash.
     */
    void buildGauge(int phases) {
        trackColour = DIM;
        for (int dot = 0; dot < GAUGE_DOTS; dot++) {
            dots.add(text(Component.text(DOT, DIM), RingLayout.onRing(gaugeAngle(dot), RingLayout.RADIUS)
                    .add(0, -0.07f, 0), 0.75f));
        }
        for (int i = 0; i < phases; i++) {
            pins.add(text(Component.text(PIP, DIM), pinAt(i, phases, false), 0.7f));
        }
        text(Component.text("Mash ").append(Component.keybind("key.jump"))
                .append(Component.text(" \u00b7 ")).append(Component.keybind("key.sneak"))
                .append(Component.text(" to give up")).color(DIM), new Vector3f(0, RingLayout.RADIUS + 0.5f, 0), 0.34f);
        caption = text(Component.empty(), new Vector3f(0, 0.24f, 0.01f), 0.55f);
        label = text(Component.empty(), new Vector3f(0, -0.16f, 0.01f), 1.1f);
        text(Component.text("[", GOLD).append(Component.keybind("key.jump")).append(Component.text("]"))
                .decoration(TextDecoration.BOLD, true), new Vector3f(0, -0.48f, 0.01f), 0.45f);
    }

    static double gaugeAngle(int dot) {
        return 360.0 * dot / GAUGE_DOTS;
    }

    /**
     * Lights the first {@code share} of the gauge's band in {@code lit} and leaves the rest {@code track}.
     * Only dots that change are sent.
     */
    void fill(double share, TextColor lit, TextColor track) {
        int count = (int) Math.floor(Math.max(0.0, Math.min(1.0, share)) * GAUGE_DOTS);
        boolean recolour = !lit.equals(litColour) || !track.equals(trackColour);
        for (int dot = 0; dot < dots.size(); dot++) {
            boolean on = dot < count;
            if (recolour || on != dot < litDots) {
                dots.get(dot).text(Component.text(DOT, on ? lit : track));
            }
        }
        litDots = count;
        litColour = lit;
        trackColour = track;
    }

    /** The small text above the gauge's percentage. */
    void caption(Component content) {
        caption.text(content);
    }

    /** Colours a gauge pip; a finished phase's pip springs up. */
    void pip(int index, TextColor colour, boolean raised) {
        TextDisplay shown = pins.get(index);
        shown.text(Component.text(PIP, colour));
        shown.setInterpolationDelay(0);
        shown.setInterpolationDuration(4);
        shown.setTransformation(place(pinAt(index, pins.size(), raised), 0.7f, new Quaternionf()));
    }

    private static double along(Location eye, RayTraceResult hit) {
        return hit == null ? Double.POSITIVE_INFINITY : hit.getHitPosition().distance(eye.toVector());
    }

    private TextDisplay bar(Color colour, float edge, float depth) {
        TextDisplay display = centre.getWorld().spawn(centre, TextDisplay.class, spawned -> {
            spawned.text(Component.text(" "));
            spawned.setBackgroundColor(colour);
            spawned.setShadowed(false);
            prepare(spawned);
            spawned.setTransformation(needleTransform(0, edge, depth));
        });
        reveal(display);
        return display;
    }

    private TextDisplay text(Component content, Vector3f at, float size) {
        TextDisplay display = centre.getWorld().spawn(centre, TextDisplay.class, spawned -> {
            spawned.text(content);
            spawned.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            spawned.setShadowed(true);
            prepare(spawned);
            spawned.setTransformation(place(at, size, new Quaternionf()));
        });
        reveal(display);
        return display;
    }

    private void prepare(Display display) {
        display.setBillboard(Display.Billboard.FIXED);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setPersistent(false);
        display.setVisibleByDefault(false);
    }

    private void reveal(Display display) {
        viewer.showEntity(plugin, display);
        all.add(display);
    }

    private Transformation place(Vector3f at, float size, Quaternionf turn) {
        float s = size * scale;
        return new Transformation(new Vector3f(at).mul(scale), turn, new Vector3f(s, s, s), new Quaternionf());
    }

    /**
     * The needle at {@code degrees}, lying along the radius with its middle on {@link #NEEDLE_RADIUS}, widened by
     * {@code edge} on every side and {@code depth} towards the viewer. The backdrop grows up from the display's
     * origin, so the origin is set back by half the needle's length along the turned needle.
     */
    Transformation needleTransform(double degrees, float edge, float depth) {
        Quaternionf turn = RingLayout.pointInward(degrees, 180);
        float width = NEEDLE_WIDTH + 2 * edge;
        float length = NEEDLE_LENGTH + 2 * edge;
        Vector3f half = turn.transform(new Vector3f(0, length / 2, 0));
        Vector3f at = RingLayout.onRing(degrees, NEEDLE_RADIUS).sub(half).add(0, 0, depth).mul(scale);
        return new Transformation(at, turn,
                new Vector3f(width / BACKDROP_WIDTH * scale, length / BACKDROP_HEIGHT * scale, scale), new Quaternionf());
    }

    static Vector3f pinAt(int index, int count, boolean raised) {
        float x = (index - (count - 1) / 2f) * 0.13f;
        return new Vector3f(x, RingLayout.RADIUS + 0.22f + (raised ? 0.08f : 0f), 0);
    }

    static Vector3f slipAt(int index, int count) {
        float x = (index - (count - 1) / 2f) * 0.1f;
        return new Vector3f(x, RingLayout.RADIUS + 0.08f, 0);
    }

    /** Lights the dots the zone covers for this pass. */
    void zone(Sweep sweep, TextColor colour) {
        for (int dot = 0; dot < dots.size(); dot++) {
            boolean in = sweep.inZone(sweep.progressAt(RingLayout.dotAngle(dot)));
            dots.get(dot).text(Component.text(DOT, in ? colour : DIM));
        }
    }

    /** Glides the needle to {@code degrees} over {@code ticks}; 0 snaps it there. */
    void pick(double degrees, int ticks) {
        pointerDegrees = degrees;
        glide(needleEdge, needleTransform(degrees, NEEDLE_EDGE_WIDTH, 0.015f), ticks);
        glide(needle, needleTransform(degrees, 0, 0.02f), ticks);
    }

    private static void glide(Display display, Transformation to, int ticks) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);
        display.setTransformation(to);
    }

    void label(Component content, float size) {
        label.text(content);
        label.setInterpolationDelay(0);
        label.setInterpolationDuration(3);
        label.setTransformation(place(new Vector3f(0, -0.16f * size, 0.01f), size, new Quaternionf()));
    }

    /** Springs a tumbler pin up as it sets. */
    void pin(int index) {
        TextDisplay shown = pins.get(index);
        shown.text(Component.text(PIN, GOLD));
        shown.setInterpolationDelay(0);
        shown.setInterpolationDuration(4);
        shown.setTransformation(place(pinAt(index, pins.size(), true), 1.4f, new Quaternionf()));
    }

    void slip(int index) {
        if (index < slips.size()) {
            slips.get(index).text(Component.text("\u2716", RED));
        }
    }

    /** A puff of coloured dust beyond the pointer's tip that only the viewer sees. */
    void burst(TextColor colour, int count) {
        Location at = centre.clone().add(RingLayout.worldOffset(yaw, pitch,
                RingLayout.onRing(pointerDegrees, BURST_RADIUS).mul(scale)));
        double spread = 0.03 * scale;
        viewer.spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0,
                new Particle.DustOptions(Color.fromRGB(colour.value()), BURST_SIZE * scale));
    }

    void remove() {
        for (Entity entity : all) {
            entity.remove();
        }
        all.clear();
    }
}
