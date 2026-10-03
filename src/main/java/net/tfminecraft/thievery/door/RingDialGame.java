package net.tfminecraft.thievery.door;

import java.util.Random;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Input;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import net.tfminecraft.thievery.cache.Parameters;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * Lockpick ring minigame, modelled on the NoPixel lockpick. A ring floats in front of the thief and a pointer
 * sweeps round it. They press the movement key shown in the middle while the pointer is in the green zone. Each
 * set tumbler narrows the zone and, by default, reverses the sweep like a combination lock. One slip, by default,
 * and the pins give.
 *
 * <p>Keys are read as movement input, so they work with any keyboard layout or binding, and the thief is held
 * still so nothing moves on screen. The middle shows each player's own key for that movement.
 */
final class RingDialGame extends LockMinigame {

    /** The server sends the pointer's position this many ticks ahead and lets the client glide there. */
    static final int LEAD_TICKS = 2;
    static final int PAUSE_TICKS = 12;
    static final double MIN_ZONE_SHARE = 0.4;
    static final String[] KEYBINDS = {"key.forward", "key.left", "key.back", "key.right"};

    enum Phase {
        PREPARE,
        TURN,
        PAUSE
    }

    final Random random;
    final int dexterity;
    final RingViews views;
    final int tumblers = Parameters.chestDialTumblers;
    RingView view;
    Sweep sweep;
    Phase phase = Phase.PREPARE;
    int ticksLeft;
    int turnTicks;
    int lastNotch;
    int set;
    int slips;
    boolean[] held = new boolean[5];

    RingDialGame(LockMinigameManager manager, UUID playerId, Block target, String targetId, Random random,
            int dexterity, RingViews views, IntConsumer onSolved) {
        super(manager, playerId, target, targetId, onSolved);
        this.random = random;
        this.dexterity = Math.max(0, dexterity);
        this.views = views;
        this.ticksLeft = ticks(Parameters.chestMinigamePrepareSeconds);
    }

    @Override
    void begin(Player player) {
        LockFreeze.freeze(player);
        held = keys(player.getCurrentInput());
        view = views.open(player, tumblers, Parameters.chestDialMistakesToFail);
        view.label(Component.text("Steady...", NamedTextColor.GRAY), 1.1f);
        status(ThieveryTexts.MUTED + "Pick the lock", BarColor.WHITE);
    }

    @Override
    boolean holdsStill() {
        return true;
    }

    @Override
    void tickRunning(Player player) {
        if (phase != Phase.TURN) {
            if (--ticksLeft <= 0) {
                spin(player);
            }
            return;
        }
        turnTicks++;
        if (sweep.isOver(turnTicks)) {
            slip(player, "Too late");
            return;
        }
        if (turnTicks % LEAD_TICKS == 0) {
            view.pick(sweep.angleAt(sweep.progress(turnTicks + LEAD_TICKS)), LEAD_TICKS);
        }
        double progress = sweep.progress(turnTicks);
        int notch = (int) (progress * RingLayout.DOTS);
        if (notch != lastNotch) {
            lastNotch = notch;
            boolean sweet = sweep.inZone(progress);
            player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, sweet ? 0.35f : 0.15f,
                    sweet ? 1.9f : 1.2f);
        }
        progress(1.0 - progress);
    }

    private void spin(Player player) {
        double share = Math.max(MIN_ZONE_SHARE, 1.0 - Parameters.chestDialZoneShrinkPerTumbler * set);
        boolean clockwise = !Parameters.chestDialAlternate || set % 2 == 0;
        int slower = (int) Math.round(dexterity * Parameters.chestDialLapSecondsPerDexterity * 20);
        sweep = Sweep.roll(random, ticks(Parameters.chestDialMinLapSeconds) + slower,
                ticks(Parameters.chestDialMaxLapSeconds) + slower, Parameters.chestDialZoneWidth * share, clockwise);
        phase = Phase.TURN;
        turnTicks = 0;
        lastNotch = -1;
        view.zone(sweep, RingView.ZONE);
        view.label(Component.keybind(KEYBINDS[sweep.key() - 1], RingView.GOLD).decoration(TextDecoration.BOLD, true),
                2.2f);
        view.pick(sweep.angleAt(0), 0);
        status(ThieveryTexts.WARN + (clockwise ? "Pick the lock \u21bb" : "Pick the lock \u21ba"), BarColor.YELLOW);
    }

    @Override
    void input(Player player, Input input) {
        boolean[] now = keys(input);
        boolean[] was = held;
        held = now;
        if (now[4] && !was[4]) {
            manager.giveUp(player, this);
            return;
        }
        if (phase != Phase.TURN) {
            return;
        }
        int pressed = 0;
        int count = 0;
        for (int key = 0; key < Sweep.KEYS; key++) {
            if (now[key] && !was[key]) {
                pressed = key + 1;
                count++;
            }
        }
        if (count > 1) {
            slip(player, "Fumbled");
        } else if (count == 1) {
            press(player, pressed);
        }
    }

    /** Forward, left, back, right, then sneak. */
    static boolean[] keys(Input input) {
        return new boolean[] {input.isForward(), input.isLeft(), input.isBackward(), input.isRight(), input.isSneak()};
    }

    void press(Player player, int key) {
        if (key != sweep.key()) {
            slip(player, "Wrong key");
            return;
        }
        int lag = Math.min(Parameters.chestDialMaxLagTicks, Math.round(player.getPing() / 50.0f));
        Sweep.Result result = sweep.judge(turnTicks, lag);
        if (result != Sweep.Result.HIT) {
            slip(player, result == Sweep.Result.EARLY ? "Too soon" : "Too late");
            return;
        }
        view.pin(set);
        set++;
        view.zone(sweep, RingView.GREEN);
        view.burst(RingView.GREEN, 3);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 1.8f);
        if (set >= tumblers) {
            solve(player);
            return;
        }
        pause(player, Component.text("Set", RingView.GREEN), BarColor.GREEN);
    }

    private void slip(Player player, String why) {
        view.slip(slips);
        slips++;
        view.zone(sweep, RingView.RED);
        view.burst(RingView.RED, 4);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        if (slips >= Parameters.chestDialMistakesToFail) {
            fail(player);
            return;
        }
        pause(player, Component.text(why, RingView.RED), BarColor.RED);
    }

    private void pause(Player player, Component text, BarColor colour) {
        phase = Phase.PAUSE;
        ticksLeft = PAUSE_TICKS;
        view.pick(sweep.angleAt(sweep.progress(turnTicks)), 0);
        view.label(text, 1.1f);
        bar.setColor(colour);
    }

    @Override
    int mistakes() {
        return slips;
    }

    @Override
    void showOutcome(Player player) {
        TextColor colour = outcome == Outcome.SOLVED ? RingView.GREEN : RingView.RED;
        view.label(Component.text(outcome == Outcome.SOLVED ? "The lock gives way" : "The pins slip", colour), 0.9f);
    }

    @Override
    void cleanup(Player player) {
        if (view != null) {
            view.remove();
        }
        if (player != null) {
            LockFreeze.release(player);
        }
    }

    /** Opens the floating ring; the display entity implementation is {@link RingView#open}. */
    interface RingViews {
        RingView open(Player player, int tumblers, int maxSlips);
    }
}
