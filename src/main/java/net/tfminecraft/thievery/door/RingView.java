package net.tfminecraft.thievery.door;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import net.tfminecraft.thievery.cache.Parameters;

/**
 * The floating lockpick ring: display entities that only the thief can see. The server only sends where the pick
 * should be a moment ahead; the client glides it there every frame, so it moves at the player's own frame rate.
 */
final class RingView {

    static final TextColor DIM = TextColor.color(0x4a4e57);
    static final TextColor ZONE = TextColor.color(0x5be37d);
    static final TextColor GOLD = TextColor.color(0xf2c53d);
    static final TextColor GREEN = TextColor.color(0x6fd34f);
    static final TextColor RED = TextColor.color(0xe0524c);
    static final Color PICK_GLOW = Color.fromRGB(0xf2c53d);
    static final float PICK_SIZE = 0.42f;
    static final float PICK_RADIUS = RingLayout.RADIUS + 0.11f;
    static final String DOT = "\u25cf";
    static final String PIN = "\u258e";
    static final float LIFT = 0.14f;

    private final Plugin plugin;
    private final Player viewer;
    private final Location centre;
    private final float scale;
    private final float yaw;
    private final float pitch;
    private final double tipDegrees;
    private final List<TextDisplay> dots = new ArrayList<>();
    private final List<TextDisplay> pins = new ArrayList<>();
    private final List<TextDisplay> slips = new ArrayList<>();
    private final List<Display> all = new ArrayList<>();
    private TextDisplay label;
    private ItemDisplay pick;
    private double pickDegrees;

    RingView(Plugin plugin, Player viewer, Location centre, float scale, float yaw, float pitch, double tipDegrees) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.centre = centre;
        this.scale = scale;
        this.yaw = yaw;
        this.pitch = pitch;
        this.tipDegrees = tipDegrees;
    }

    /** Floats the ring in front of the viewer's eyes, closer if a block is in the way. */
    static RingView open(Plugin plugin, Player viewer, ItemStack lockpick, int tumblers, int maxSlips) {
        Location eye = viewer.getEyeLocation();
        RayTraceResult hit = viewer.getWorld().rayTraceBlocks(eye, eye.getDirection(), Parameters.chestDialDistance,
                FluidCollisionMode.NEVER, true);
        double blocked = hit == null ? Double.MAX_VALUE : hit.getHitPosition().distance(eye.toVector());
        double distance = RingLayout.fitDistance(Parameters.chestDialDistance, blocked);
        float scale = (float) (distance / RingLayout.DISTANCE);
        // Lifted a little above the crosshair so the bottom of the ring clears the action bar.
        Location centre = eye.clone().add(eye.getDirection().multiply(distance))
                .add(RingLayout.worldOffset(eye.getYaw(), eye.getPitch(), new Vector3f(0, LIFT * scale, 0)));
        centre.setYaw(eye.getYaw() + 180f);
        centre.setPitch(-eye.getPitch());
        RingView view = new RingView(plugin, viewer, centre, scale, eye.getYaw(), eye.getPitch(),
                Parameters.chestDialPickTipDegrees);
        view.build(lockpick, tumblers, maxSlips);
        return view;
    }

    void build(ItemStack lockpick, int tumblers, int maxSlips) {
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
        pick = centre.getWorld().spawn(centre, ItemDisplay.class, display -> {
            ItemStack shown = lockpick.clone();
            shown.setAmount(1);
            display.setItemStack(shown);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setGlowing(true);
            display.setGlowColorOverride(PICK_GLOW);
            prepare(display);
            display.setTransformation(pickTransform(0));
        });
        reveal(pick);
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

    Transformation pickTransform(double degrees) {
        return place(RingLayout.onRing(degrees, PICK_RADIUS).add(0, 0, 0.02f), PICK_SIZE,
                RingLayout.pointInward(degrees, tipDegrees));
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

    /** Glides the pick to {@code degrees} over {@code ticks}; 0 snaps it there. */
    void pick(double degrees, int ticks) {
        pickDegrees = degrees;
        pick.setInterpolationDelay(0);
        pick.setInterpolationDuration(ticks);
        pick.setTransformation(pickTransform(degrees));
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

    /** A burst of particles at the pick that only the viewer sees. */
    void burst(Particle particle, int count) {
        Location at = centre.clone().add(RingLayout.worldOffset(yaw, pitch,
                RingLayout.onRing(pickDegrees, PICK_RADIUS).mul(scale)));
        viewer.spawnParticle(particle, at, count, 0.03, 0.03, 0.03, 0.01);
    }

    void remove() {
        for (Entity entity : all) {
            entity.remove();
        }
        all.clear();
    }

    List<Display> displays() {
        return all;
    }
}
