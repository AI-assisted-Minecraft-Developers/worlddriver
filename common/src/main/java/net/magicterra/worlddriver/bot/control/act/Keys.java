package net.magicterra.worlddriver.bot.control.act;

import static net.magicterra.worlddriver.config.Mod.LOG;

import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/** The jump, sneak and forward keys the walk presses on its body, and the log of where each jump came from. */
public final class Keys {

    /** Jump-source lines emitted per navigator before the latch goes quiet. */
    private static final int JUMP_SRC_EVENTS = 6;

    private final Navigator nav;

    private int jumpSrcEvents;

    /** One-shot latch for the "the cap swallowed an event" line — see {@link #noteJumpSource}. */
    private boolean jumpSrcCapped;

    /**
     * Whether the PREVIOUS {@link #jump} call asked for a jump — the clock-free "entry to a held run" latch. See that
     * method's javadoc for why a game-time delta cannot do this job in a scene that runs its whole body inside one
     * server tick.
     */
    private boolean lastJumpAsk;

    /** The key channel of {@code nav}. */
    public Keys(Navigator nav) {
        this.nav = nav;
    }

    /**
     * The one funnel every jump request goes through — and now the one place that records WHERE a jump came from.
     *
     * <p>It was {@code static} and the latch is why it is not any more. Enumerating the disjuncts of
     * {@code DrivePhase}'s {@code boolean jump} to work out which one fired on the End platform was wrong three ways
     * over, and the shape of the error is worth keeping: that expression is <b>one of eleven</b> places that can set
     * the request. {@code WalkerTickClimb} alone has eight ({@code :158, :465, :727, :781, :847, :868, :930, :1021}),
     * and there are more in the drive's pillar-recover rung, the vine guard and the unstuck burst. Only five of them
     * label themselves with {@code jumpTag}. Reading the branch conditions of one of eleven and calling the survivor
     * "the only term that can fire" was never a measurement.
     *
     * <p>So the source is taken from the CALL SITE, not from a hand-kept list: a file and line from the stack cannot
     * fall out of date when a twelfth site appears, and a site that never labelled itself still names itself.
     * {@code jumpTag} is printed beside it as the human-readable branch when the site set one — {@code PreludePhase}
     * clears it every tick, so a stale label cannot be attributed to this tick — and {@code untagged} when it did not.
     *
     * <p>The drive's label chain ends in {@code other} rather than a bare {@code "swim"}. That is not decoration: a
     * chain whose last arm is a real branch name labels every unmatched case as that branch, so it can never report
     * that the labels have fallen behind the expression they describe. If {@code branch=other} is ever printed, the
     * chain is missing a term — fix the chain before believing any label it produced.
     *
     * <p>One line per EVENT (entry to a run of held ticks), capped at {@link #JUMP_SRC_EVENTS}: the navigator holds
     * jump for dozens of consecutive ticks and a per-tick line would be a hose. The stack walk happens only on the
     * lines that are actually emitted.
     *
     * <p><b>"Entry to a run" is measured against the PREVIOUS CALL, and must never be measured against the clock.</b>
     * It used to be {@code getGameTime() - jumpAskTick > 1}, and that is blind for a whole family of scenes: anything
     * that pumps the bot in a tight in-body loop ({@code wd.buriedOre} runs 800 {@code BotTicker.tickAll()} iterations
     * inside ONE server tick, as do the {@code wd.serverMine*} family, {@code wd.selfShaftDigUp} and every other
     * synchronous scene body) advances the navigator without advancing the clock, so {@code now} is CONSTANT for the
     * whole run. After the first emitted line {@code now - jumpAskTick == 0} is permanently false, the latch never
     * re-opens, and exactly one line is printed no matter how many separate jump events occurred — so "it happened
     * once" and "it happened forty times and only the first was printed" read identically. {@code lastJumpAsk} states
     * the same semantics without a clock: {@code PreludePhase} calls this once per navigator tick with the
     * {@code false} baseline and the branches override it, so "the previous call also asked" IS "the previous tick also
     * asked" — in a per-tick world and in a one-tick world alike. A held run still yields exactly one line in a real
     * world, which is the property the rehearsal and ladder logs depend on.
     *
     * <p>{@code seq=} is the event ordinal within the cap; when {@code t=} is frozen (the one-tick family) it is the
     * only thing that orders the lines. {@code seq=6/6} says the cap was REACHED, which is not the same claim as "more
     * happened" — the follow-on {@code seq=7+/6 cap reached} line is the one that says an event was actually swallowed,
     * and its ABSENCE after a {@code seq=6/6} means the bot really did stop asking.
     */
    public void jump(Body a, boolean v) {
        boolean newEvent = v && !lastJumpAsk;
        lastJumpAsk = v;
        if (newEvent) {
            noteJumpSource(a);
        }
        a.commandJump(v);
    }

    private void noteJumpSource(Body a) {
        LivingEntity p = a.entity();
        if (p == null) {
            return;
        }
        if (jumpSrcEvents >= JUMP_SRC_EVENTS) {
            // A silent cap and a bot that genuinely jumped exactly JUMP_SRC_EVENTS times print
            // IDENTICALLY — "seq=6/6" is the last line in both worlds. Say it once, at the first
            // event actually suppressed, so the difference is on the page. One-shot on purpose:
            // the whole reason the cap exists is that a hose of these lines is unreadable.
            if (!jumpSrcCapped) {
                jumpSrcCapped = true;
                LOG.info(
                        "[navigator] jump source: seq={}+/{} cap reached, later events are not logged (printed once)",
                        JUMP_SRC_EVENTS + 1,
                        JUMP_SRC_EVENTS);
            }
            return;
        }
        jumpSrcEvents++;
        long now = p.level().getGameTime();
        BlockPos foot = BlockPos.containing(p.getX(), p.getY(), p.getZ());
        BlockPos wp = nav.route.path() != null
                        && nav.route.step() >= 0
                        && nav.route.step() < nav.route.path().size()
                ? nav.route.path().get(nav.route.step())
                : null;
        String site = StackWalker.getInstance()
                .walk(s -> s.skip(2)
                        .map(f -> f.getFileName() + ":" + f.getLineNumber())
                        .findFirst()
                        .orElse("?"));
        // The water pair is here because a `swimColumn` tag on dry obsidian has three readings and
        // the tag alone cannot separate them: the BOT's own flag (p.isInWater(), what the branch
        // actually tests) and the WORLD's block at the foot are printed side by side, so a stale
        // flag, a genuinely wet cell, and a tag that disagrees with its own precondition are three
        // distinct rows instead of one ambiguous one.
        LOG.info(
                "[navigator] jump source: seq={}/{} t={} branch={} site={} foot={} exact=({}) waypoint={} wp.y-foot.y={} inWater={} underWater={} footBlock={} aboveFoot={}",
                jumpSrcEvents,
                JUMP_SRC_EVENTS,
                now,
                nav.jumpTag == null ? "untagged" : nav.jumpTag,
                site,
                foot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                wp == null ? "none" : wp.toShortString(),
                wp == null ? "?" : String.valueOf(wp.getY() - foot.getY()),
                p.isInWater(),
                p.isUnderWater(),
                p.level().getBlockState(foot).getBlock().toString(),
                p.level().getBlockState(foot.above()).getBlock().toString());
    }

    /** Commands the body's sneak key to {@code v} for this tick. */
    public static void sneak(Body a, boolean v) {
        a.commandSneak(v);
    }

    /**
     * Raw forward (keyUp equivalent) for the special branches that drive the impulse themselves (the main walk path
     * uses commandMove). v=false also zeroes strafe.
     */
    public static void forward(Body a, boolean v) {
        a.commandForward(v ? 1f : 0f);
    }
}
