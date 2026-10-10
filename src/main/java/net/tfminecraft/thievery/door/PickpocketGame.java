package net.tfminecraft.thievery.door;

import java.util.Random;
import java.util.UUID;
import java.util.function.IntConsumer;

import org.bukkit.Input;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import net.tfminecraft.thievery.loader.PickpocketLoader;
import net.tfminecraft.thievery.player.PickpocketVictimAlerter;
import net.tfminecraft.thievery.steal.RobberyUtil;
import net.tfminecraft.thievery.utils.ThieveryTexts;

/**
 * Pickpocket minigame, modelled on level 2 of the NoPixel lockpick. A ring floats in front of the thief and drains
 * while they mash jump to fill it. Filling it sets a phase and empties it for the next; the first phase is half
 * length, so it fills and drains twice as fast. Both must be filled before time runs out, and the mark must stay
 * within reach. A failed attempt alerts the mark.
 *
 * <p>Jump is read as movement input, like the lockpick ring's keys, and the thief is held still so it does nothing
 * else. The real game's mash key opens the inventory, which the server never hears about.
 */
final class PickpocketGame extends LockMinigame {

    static final int PHASES = 2;
    static final double[] LENGTHS = {0.5, 1.0};
    static final String[] NAMES = {"PHASE I", "PHASE II"};
    static final TextColor[] ACCENTS = {TextColor.color(0x2dd4e8), TextColor.color(0xf0a339)};
    static final TextColor[] TRACKS = {TextColor.color(0x24525c), TextColor.color(0x5c4320)};
    static final BarColor[] BARS = {BarColor.BLUE, BarColor.YELLOW};
    /** Percent a press adds to a full-length phase, give or take {@code PRESS_SPREAD / 2}. */
    static final double PRESS_GAIN = 2.05;
    static final double PRESS_SPREAD = 0.3;
    /**
     * Percent a full-length phase drains each second: the real game's base drain of 7 at level 2's 0.49725, with
     * its random surges smoothed into the same average, as it does itself.
     */
    static final double DRAIN_PER_SECOND = 3.5235;
    /** The drain rises and falls by this share on a slow wave. */
    static final double WAVE = 0.0337;
    static final double WAVE_HERTZ = 0.3;
    static final int PREPARE_TICKS = 20;
    /** Words in the middle are smaller than the percentage, so they fit inside the band. */
    static final float WORD_SIZE = 0.8f;
    /** The pause on a set phase before the next one starts. */
    static final int SET_TICKS = 10;

    static final Wording POCKET = new Wording("You flinch and your hand slips.", "You were pulled away from your mark.",
            "You draw your hand back.", "You're into their pocket", "Your mark felt that");

    enum Phase {
        LANDING,
        PREPARE,
        MASH,
        SET
    }

    /** The mark; null for a staff test. */
    final Player victim;
    final Random random;
    final GaugeViews views;
    final int limitTicks = ticks(PickpocketLoader.getMinigameTimeLimitSeconds());
    RingView view;
    Phase phase = Phase.LANDING;
    int ticksLeft = RingDialGame.LANDING_TICKS;
    int section;
    double progress;
    int elapsed;
    double wavePhase;
    /** The percentage last shown, so it is only sent when it changes. */
    int shown = -1;
    boolean jumping;
    boolean sneaking;

    PickpocketGame(LockMinigameManager manager, UUID playerId, String targetId, Player victim, Random random,
            GaugeViews views, IntConsumer onSolved) {
        super(manager, playerId, null, targetId, onSolved);
        this.victim = victim;
        this.random = random;
        this.views = views;
        this.wavePhase = random.nextDouble() * Math.PI * 2;
    }

    @Override
    Wording wording() {
        return POCKET;
    }

    @Override
    boolean targetGone() {
        return false;
    }

    @Override
    Sound solvedSound() {
        return Sound.ITEM_BUNDLE_REMOVE_ONE;
    }

    @Override
    void begin(Player player) {
        LockFreeze.freeze(player);
        Input input = player.getCurrentInput();
        jumping = input.isJump();
        sneaking = input.isSneak();
        status(ThieveryTexts.MUTED + "Pick their pocket", BarColor.WHITE);
        if (RingDialGame.settled(player)) {
            open(player);
        }
    }

    private void open(Player player) {
        view = views.open(player, victim, PHASES);
        view.label(Component.text("Steady...", NamedTextColor.GRAY), WORD_SIZE);
        phase = Phase.PREPARE;
        ticksLeft = PREPARE_TICKS;
    }

    @Override
    void tickRunning(Player player) {
        if (phase == Phase.LANDING) {
            if (RingDialGame.settled(player) || --ticksLeft <= 0) {
                open(player);
            }
            return;
        }
        if (victim != null && (!victim.isOnline() || !RobberyUtil.isWithinRange(player, victim, PickpocketLoader.getMaxDistance()))) {
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.MUTED + "Your mark is out of reach."));
            manager.cancel(playerId);
            return;
        }
        if (phase == Phase.PREPARE) {
            if (--ticksLeft <= 0) {
                startPhase(0);
            }
            return;
        }
        elapsed++;
        if (elapsed >= limitTicks) {
            fail(player);
            return;
        }
        progress(1.0 - (double) elapsed / limitTicks);
        if (phase == Phase.SET) {
            if (--ticksLeft <= 0) {
                startPhase(section + 1);
            }
            return;
        }
        double wave = 1 + WAVE * Math.sin(2 * Math.PI * WAVE_HERTZ * elapsed / 20.0 + wavePhase);
        progress = Math.max(0.0, progress - DRAIN_PER_SECOND * wave / 20.0 / LENGTHS[section]);
        draw();
    }

    private void startPhase(int index) {
        section = index;
        progress = 0;
        shown = -1;
        phase = Phase.MASH;
        view.caption(Component.text(NAMES[index], ACCENTS[index]));
        view.pip(index, ACCENTS[index], false);
        bar.setColor(BARS[index]);
        bar.setTitle(ThieveryTexts.msg(ThieveryTexts.WARN + "Pick their pocket"));
        draw();
    }

    private void draw() {
        view.fill(progress / 100.0, ACCENTS[section], TRACKS[section]);
        if ((int) progress != shown) {
            shown = (int) progress;
            view.label(Component.text(shown, ACCENTS[section]), 1.6f);
        }
    }

    @Override
    void input(Player player, Input input) {
        boolean pressed = input.isJump() && !jumping;
        boolean quit = input.isSneak() && !sneaking;
        jumping = input.isJump();
        sneaking = input.isSneak();
        if (quit) {
            manager.giveUp(player, this);
        } else if (pressed && phase == Phase.MASH) {
            press(player);
        }
    }

    void press(Player player) {
        double gain = PRESS_GAIN * (1 - PRESS_SPREAD / 2 + random.nextDouble() * PRESS_SPREAD) / LENGTHS[section];
        progress = Math.min(100.0, progress + gain);
        player.playSound(player.getLocation(), Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.3f, 1.6f);
        if (progress < 100.0) {
            draw();
            return;
        }
        view.fill(1.0, RingView.GREEN, TRACKS[section]);
        view.label(Component.text(100, RingView.GREEN), 1.6f);
        view.pip(section, RingView.GREEN, true);
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5f, 1.8f);
        if (section + 1 >= PHASES) {
            solve(player);
            return;
        }
        phase = Phase.SET;
        ticksLeft = SET_TICKS;
        bar.setColor(BarColor.GREEN);
    }

    /** The thief must wait before trying this mark again, and the mark feels the hand. */
    @Override
    void penalise(Player player) {
        manager.lockPickManager.applyCooldown(playerId, targetId);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        if (victim != null && victim.isOnline()) {
            PickpocketVictimAlerter.alert(victim);
            player.sendMessage(ThieveryTexts.msg(ThieveryTexts.ERROR + "Your mark felt your hand!"));
        }
    }

    @Override
    int mistakes() {
        return 0;
    }

    @Override
    void showOutcome(Player player) {
        if (view == null) {
            return;
        }
        boolean solved = outcome == Outcome.SOLVED;
        view.caption(Component.empty());
        view.label(Component.text(solved ? "Got it" : "Noticed", solved ? RingView.GREEN : RingView.RED), WORD_SIZE);
        if (!solved) {
            view.fill(1.0, RingView.RED, RingView.RED);
        }
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

    /** Opens the floating gauge; the display entity implementation is {@link RingView#openGauge}. */
    interface GaugeViews {
        RingView open(Player player, Player mark, int phases);
    }
}
