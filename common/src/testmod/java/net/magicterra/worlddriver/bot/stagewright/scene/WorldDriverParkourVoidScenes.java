package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * A FLAT parkour leap over a LETHAL drop — the one execution surface the suite has never run.
 *
 * <h2>What was uncovered, and how a 222-scene GREEN hid it</h2>
 *
 * The manifest's only execution-level jump scene is {@code wd.parkourAscend}
 * ({@link WorldDriverTerrainScenes}), and it leaps a gap that lands <b>+1 higher</b>. That single
 * fact is what exempts it from the defect this family is built around: the sprint gate in
 * {@code WalkerTickDrive} used to read
 *
 * <pre>{@code
 * boolean parkourAscend = parkourEdge && wp.getY() > foot.getY();   // RISING leaps only
 * boolean sprint = ... && (!lethalNear || parkourAscend) && ...;    // now: (!lethalNear || parkourEdge)
 * }</pre>
 *
 * so a <b>rising</b> parkour keeps its sprint next to a killer edge and a <b>flat</b> one does not.
 * {@code lethalNear} is {@code lethalDropAdjacent(world, p, foot)} — ANY of the foot cell's eight
 * horizontal neighbours dropping further than {@link
 * net.magicterra.worlddriver.bot.world.SurvivalMath#survivableFall} — which on the rim of a small
 * platform over void is true on <i>every grounded tick</i>. A flat leap over void therefore launches
 * with the sprint channel closed, and no scene in the manifest has ever executed one.
 *
 * <p>Observed on the End arrival platform (rung 20 of the journey ladder): the body walked the 5x5
 * spawn platform toward the main island, met the first {@code parkour3} across two cells of void,
 * and fell out of the world. {@code h:0.1530 -> 0.1382} across the takeoff — exactly the x0.91 decay
 * of a body with no impulse at all, where a sprint-jump would show around 0.35.
 *
 * <h2>What these three scenes are, and what they are NOT</h2>
 *
 * They are <b>sensors, not fixes</b>. Nothing in this file touches the product. Two of the three
 * shipped RED and {@code withRequired(false)} for exactly that reason — <b>each was to be promoted
 * to required the first time it went green</b>, which is the whole point of writing the criterion
 * down before the fix rather than after. Both execution arms were promoted in the commit that
 * narrowed the sprint gate to {@code parkourEdge}; the planner arm ({@code wd.parkourVoidRunwayGate})
 * followed once it too had gone green on both loaders. All three are required now.
 *
 * <p>The family is deliberately a POSITIVE arm, a NEGATIVE arm and a PLANNER arm, because the
 * cheapest wrong fixes are all invisible to any one of them:
 *
 * <ul>
 *   <li>{@code wd.parkourVoidShortRunway} — the reproduction. Two cells of run-up, the End's own
 *       geometry.</li>
 *   <li>{@code wd.parkourVoidLongRunway} — the anti-overfit arm. <b>Without it, "forbid every
 *       parkour and always bridge instead" is a full-marks answer</b>, and the bot would pay 170
 *       cost and dozens of ticks for a gap a sprint-jump crosses for 32.</li>
 *   <li>{@code wd.parkourVoidRunwayGate} — planner-only, seconds, no execution budget. It pins the
 *       run-up threshold ITSELF as a criterion, which is the thing {@link Move#hasRunway} claims to
 *       model and does not: that predicate asks one question — "is the cell under the launch foot
 *       solid?" — and answers a one-cell pillar and a twelve-cell runway identically.</li>
 * </ul>
 *
 * <h2>Rig rules this family keeps</h2>
 *
 * <ul>
 *   <li><b>The drop must READ as lethal, or the scene measures nothing.</b> {@code lethalNear} is
 *       the subject here, and it is gated on {@code survivableFall} (about 22 blocks at full HP).
 *       The catch floor therefore hangs {@value #VOID_DEPTH} blocks under the pad: deep enough that
 *       the gap columns are unambiguously lethal, while leaving the required 30 clear cells directly
 *       below the void. A shallower rig would silently turn the sprint gate OFF and every scene here
 *       would pass while proving nothing.</li>
 *   <li><b>{@link SimProbes#grantWaterEffects} is safe to grant and IS granted</b>, matching
 *       {@code wd.parkourAscend}. It cannot weaken the gate above: {@code survivableFall(float
 *       health)} takes health alone and never reads a resistance modifier, so the buff keeps
 *       drowning/burning/starving out of a movement measurement without moving the threshold the
 *       measurement is about.</li>
 *   <li><b>Evidence is {@link SceneContext#record}ed, not logged.</b> The harness prints a scene's
 *       evidence map into the log only on FAIL; a PASS carries it in
 *       {@code stagewright-results.jsonl} instead. Every row here is written on both outcomes,
 *       because "the green run placed no blocks" is the reading that separates a leap from a
 *       bridge, and it is unavailable from a log line.</li>
 * </ul>
 */
public final class WorldDriverParkourVoidScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                // REQUIRED since the sprint gate was narrowed to `parkourEdge` — this arm was the
                // green half of the pair that measured the defect (takeoff h 0.1232 -> 0.2475: the
                // impulse fires when the body launches from BEHIND the lip), so it now guards
                // against the fix being reverted along with the long arm it made possible.
                Scene.of("wd.parkourVoidShortRunway", 200,
                        WorldDriverParkourVoidScenes::parkourVoidShortRunway),
                // REQUIRED, and the answer to its own open question is now on record: the run-up
                // did NOT buy the leap anything, it COST it. `lethalNear` reads only the CURRENT
                // foot cell's eight neighbours, so eleven blocks of runway behind the body change
                // it by not one bit — what they change is WHICH cell the body launches from, and a
                // fuller run-up puts it ON the lip, where the eight neighbours include void and the
                // old `parkourAscend` exemption (rising leaps only) let the gate close. Measured
                // 0.1563 -> 0.1400 across the takeoff: x0.896 air decay, no impulse, into the gap.
                // With the gate keyed on `parkourEdge` this is the anti-overfit arm it was written
                // to be — "forbid every parkour and always bridge" now fails it on place.spent.
                Scene.of("wd.parkourVoidLongRunway", 200,
                        WorldDriverParkourVoidScenes::parkourVoidLongRunway),
                // Records the CURRENT answer of an unmodelled run-up threshold; its short half is
                // expected RED. PROMOTE TO REQUIRED the first time it goes green.
                Scene.of("wd.parkourVoidRunwayGate", 200,
                        WorldDriverParkourVoidScenes::parkourVoidRunwayGate));
    }

    // ---------------------------------------------------------------- rig ----

    /** Cells of pure void between the launch lip and the landing deck — {@code parkour3}'s gap,
     *  which is what the End arrival platform actually presents. */
    private static final int GAP = 2;

    /** How far the landing deck runs past the gap, in cells. Long enough that the goal sits well
     *  clear of the lip, so "arrived" cannot be satisfied by hanging on the deck's first block. */
    private static final int DECK_LEN = 8;

    /** Depth of the catch floor below the pad. Two constraints meet here and both are load-bearing:
     *  the 30 cells directly under the gap must hold nothing solid (so the scene reproduces a void
     *  and not a pit), and the resulting column must still exceed {@code survivableFall} (about 22
     *  at full HP) so {@code lethalDropAdjacent} reads TRUE and the sprint gate under test is armed. */
    private static final int VOID_DEPTH = 32;

    /** Ticks allowed between "foot cell reaches the lip column" and "standing on the deck". A
     *  sprint-jump arc is about 12 ticks; a two-cell bridge is two aim+place rounds and an order of
     *  magnitude more. The budget sits far enough above the leap that a slow leap still passes and
     *  far enough below a bridge that no bridge can. */
    private static final int LEAP_TICKS = 40;

    /** The pad's own y. Standing y is one above; the catch floor is {@link #VOID_DEPTH} below. */
    private static int padY(SceneContext ctx) {
        return ctx.origin().getY() + 30;   // same altitude band as wd.parkourAscend
    }

    /** Empty the working box, then hang the catch floor. Everything this family asserts about is
     *  built afterwards, so nothing generated can be mistaken for the rig. */
    private static void clearAndCatch(ServerLevel level, int cx, int cz, int padY, int halfZ) {
        for (int dx = -14; dx <= 14; dx++)
            for (int dz = -halfZ; dz <= halfZ; dz++) {
                for (int dy = -VOID_DEPTH; dy <= 6; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, padY + dy, cz + dz),
                            Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, padY - VOID_DEPTH, cz + dz),
                        Blocks.STONE.defaultBlockState());
            }
    }

    /**
     * Launch pad {@code x in [cx, cx+padLen-1]}, {@link #GAP} cells of nothing, landing deck running
     * back from {@code cx-GAP-1}. All at {@code padY}, all {@code 2*halfW+1} cells wide in z.
     *
     * <p>The ONLY variable between the two execution scenes is {@code padLen}: same gap, same void
     * depth, same deck, same inventory, same switches. That is what lets one of them be evidence
     * about the other.
     */
    private static void buildPadGapDeck(ServerLevel level, int cx, int cz, int padY,
                                        int padLen, int halfW) {
        for (int dz = -halfW; dz <= halfW; dz++) {
            for (int dx = 0; dx < padLen; dx++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, padY, cz + dz),
                        Blocks.STONE.defaultBlockState());
            for (int i = 1; i <= DECK_LEN; i++)
                level.setBlockAndUpdate(new BlockPos(cx - GAP - i, padY, cz + dz),
                        Blocks.STONE.defaultBlockState());
        }
    }

    /** Total count of an item across the whole inventory — the honest measure of how many blocks a
     *  run spent, because a place spends a block whichever slot it came out of. */
    private static int carrying(ServerPlayer fp, Item item) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) if (st.is(item)) n += st.getCount();
        return n;
    }

    /** Solid cells still standing in the gap columns — the direct reading of "did it bridge", which
     *  the inventory delta alone cannot give (a block placed and then walked off still counts). */
    private static int gapSolidCells(ServerLevel level, int cx, int cz, int padY, int halfW) {
        int n = 0;
        for (int i = 1; i <= GAP; i++)
            for (int dz = -halfW; dz <= halfW; dz++)
                for (int dy = 0; dy <= 1; dy++)
                    if (!level.getBlockState(new BlockPos(cx - i, padY + dy, cz + dz)).isAir()) n++;
        return n;
    }

    // ------------------------------------------------------- execution arms ----

    /**
     * <b>The reproduction.</b> Two cells of run-up onto a lip, {@link #GAP} cells of void, a deck on
     * the far side — the End arrival platform's own geometry, at the same 100 -> 98 approach the
     * ladder measured.
     *
     * <h2>Two criteria, reported separately</h2>
     *
     * They are not one criterion in two halves, and merging them would print two opposite outcomes
     * as the same line:
     *
     * <ol>
     *   <li><b>Do not fall.</b> {@code minY > padY - 3}, the {@code fellInPit} idiom from
     *       {@code wd.parkourAscend}. A body that refuses the leap and stands on the lip forever
     *       satisfies this one.</li>
     *   <li><b>Get across.</b> The final position must be past the gap at deck level. A body that
     *       stalls on the lip satisfies (1) and fails this.</li>
     * </ol>
     *
     * <p><b>Jumping across and bridging across BOTH count as getting across</b>, deliberately:
     * {@code allowPlace} is ON with 256 cobblestone in the hotbar, so a fix that teaches the body to
     * bridge what it cannot leap is a legitimate answer to this scene. What is NOT an answer is
     * standing still, and what is definitely not an answer is walking off the edge. The arm that
     * refuses "always bridge" is {@link #parkourVoidLongRunway}, not this one.
     */
    private static void parkourVoidShortRunway(SceneContext ctx) {
        runVoidGap(ctx, "parkourVoidShortRunway", 5, 1, false);
    }

    /**
     * <b>The anti-overfit arm.</b> Same gap, same void, same deck, same inventory — a 12-cell launch
     * pad and ten cells of run-up instead of two.
     *
     * <h2>Why this arm has to exist</h2>
     *
     * Without it, <b>"forbid every parkour and bridge everything" scores full marks</b> on
     * {@link #parkourVoidShortRunway}: the body would never fall, would always arrive, and would pay
     * 170 cost and dozens of ticks per gap that a sprint-jump crosses for 32. So this arm asserts the
     * leap POSITIVELY, and none of its extra criteria is redundant:
     *
     * <ol>
     *   <li>it arrives at deck level without falling — the same two criteria as the short arm;</li>
     *   <li><b>it spent no blocks.</b> Not "a bridge would be slower" — it must not have bridged;</li>
     *   <li><b>it crossed within {@value #LEAP_TICKS} ticks of reaching the lip.</b> A 2-cell bridge
     *       is two rounds of aim-and-place, tens to hundreds of ticks. The block count alone is
     *       fooled by a body that places a block and then does not cross on it — the inventory says
     *       "bridged" while nothing was bridged. The clock is what distinguishes them.</li>
     * </ol>
     *
     * <p>The clock criterion is written so it <b>cannot be satisfied vacuously</b>: a run that never
     * reaches the deck has no crossing duration, and that reads as a violation rather than as
     * {@code 0 <= 40}. A criterion a stalled body passes is the {@code 0 == 0} assertion this repo
     * has already paid for once.
     *
     * <p><b>If this arm is RED, the premise "a long run-up gets across" is itself false</b> — which
     * is a finding about the product, not about the rig. The sprint gate is evaluated at the LIP, on
     * the foot cell, so run-up length may buy the body nothing at all. Do not lengthen the pad,
     * shrink the gap or widen the tolerance to make it green; the red IS the measurement.
     */
    private static void parkourVoidLongRunway(SceneContext ctx) {
        runVoidGap(ctx, "parkourVoidLongRunway", 12, 10, true);
    }

    /**
     * The shared body of both execution arms. The rig, the switches, the inventory and the sampling
     * are identical; {@code padLen} and {@code spawnDx} are the only inputs, so a difference between
     * the two scenes can only be the run-up.
     *
     * <p>Machinery copied from {@code wd.parkourAscend} on purpose — {@code createUnique} +
     * {@link LevelWorldView} + a synchronous {@code walker.tick / av.step} loop — so the two are
     * comparable readings of the same executor and flat-versus-rising is the only variable.
     *
     * @param assertLeap when true, also assert HOW the body crossed (no blocks spent, and fast
     *                   enough that a bridge cannot have produced it)
     */
    private static void runVoidGap(SceneContext ctx, String name, int padLen, int spawnDx,
                                   boolean assertLeap) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int padY = padY(ctx), standY = padY + 1;
        final int halfW = 1;                       // 3 cells wide: the End platform is not a plank

        clearAndCatch(level, cx, cz, padY, halfW + 1);
        buildPadGapDeck(level, cx, cz, padY, padLen, halfW);
        BlockPos goal = new BlockPos(cx - GAP - 6, standY, cz);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = true;               // bridging is a legitimate way across
        BotConfig.allowBreak = false;              // digging around it is not
        BotConfig.allowParkour4 = false;           // the gap is a parkour3; 4-cell leaps stay off
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + spawnDx + 0.5, standY, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        SimProbes.grantWaterEffects(fp);           // cannot move survivableFall — see class javadoc
        fp.getInventory().clearContent();
        for (int i = 0; i < 4; i++) fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        final int cobbleBefore = carrying(fp, Items.COBBLESTONE);

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(goal));

        Walker.Step s = Walker.Step.WALKING;
        double minY = fp.getY(), prevX = fp.getX(), prevH = 0.0;
        double takeoffX = Double.NaN, takeoffHBefore = Double.NaN, takeoffHAfter = Double.NaN;
        int takeoffTick = -1, tLip = -1, tDeck = -1, tFell = -1, t = 0;
        String gapMove = null, firstPlan = null;
        for (; t < 400 && s == Walker.Step.WALKING; t++) {
            s = walker.tick(av, w);
            av.step();
            double h = Math.hypot(fp.getDeltaMovement().x, fp.getDeltaMovement().z);
            minY = Math.min(minY, fp.getY());
            if (firstPlan == null && walker.pathLen() > 0) firstPlan = walker.planTally();
            if (tLip < 0 && Mth.floor(fp.getX()) <= cx) tLip = t;
            if (gapMove == null && fp.getX() < cx) gapMove = String.valueOf(walker.pathMove());
            if (takeoffTick < 0 && fp.getY() > standY + 0.05) {
                takeoffTick = t; takeoffX = prevX; takeoffHBefore = prevH; takeoffHAfter = h;
            }
            if (tDeck < 0 && Mth.floor(fp.getX()) <= cx - GAP - 1
                    && Math.abs(fp.getY() - standY) <= 0.4) tDeck = t;
            // Stop the moment the body is in the void. Not politeness: a shed body spends the rest
            // of the budget asking A* about terrain 30 blocks down, which is both expensive and a
            // reading about a falling body rather than about the leap.
            if (fp.getY() < padY - 3) { tFell = t; break; }
            prevX = fp.getX(); prevH = h;
        }

        int spent = cobbleBefore - carrying(fp, Items.COBBLESTONE);
        boolean crossed = fp.getX() < cx - GAP - 0.5 && Math.abs(fp.getY() - standY) <= 0.4;

        // EVERY row below is recorded on PASS as well as on FAIL: the harness only prints the
        // evidence map into the log when a scene fails, and "the green run placed no blocks" is
        // exactly the reading that separates a leap from a bridge.
        ctx.record("rig", String.format(Locale.ROOT,
                "pad y=%d stand y=%d | pad x=[%d,%d] (%d cells) | gap x=%d..%d "
                + "(%d clear cells below, catch floor y=%d) | deck x<=%d | spawn x=%.1f | goal %s",
                padY, standY, cx, cx + padLen - 1, padLen, cx - 1, cx - GAP, VOID_DEPTH - 1,
                padY - VOID_DEPTH, cx - GAP - 1, cx + spawnDx + 0.5, goal.toShortString()));
        ctx.record("takeoff.x", takeoffTick < 0 ? "never jumped" : String.format(Locale.ROOT,
                "%.4f (lip cell x=%d, so the jump fired %.4f cells short of the lip)",
                takeoffX, cx, takeoffX - cx));
        ctx.record("takeoff.h", takeoffTick < 0 ? "never jumped" : String.format(Locale.ROOT,
                "before %.4f -> after %.4f (a sprint-jump shows about 0.35; a x0.91 ratio is pure "
                + "air decay, i.e. no impulse at all)", takeoffHBefore, takeoffHAfter));
        ctx.record("takeoff.tick", takeoffTick);
        ctx.record("takeoff.walker", walker.parkourTakeoff());
        ctx.record("gap.move", gapMove == null ? "never got past the lip" : gapMove);
        ctx.record("gap.solidCells", gapSolidCells(level, cx, cz, padY, halfW));
        ctx.record("plan.first", firstPlan == null ? "none - the run never held a path" : firstPlan);
        ctx.record("plan.last", walker.planTally());
        ctx.record("place.tally", av.placeTally());
        ctx.record("place.spent", spent + " cobblestone (started with " + cobbleBefore + ")");
        ctx.record("canPlace", w.canPlace());
        ctx.record("placeableBlockCount", w.placeableBlockCount());
        ctx.record("minY", minY);
        ctx.record("ticks", String.format(Locale.ROOT,
                "ran %d | reached lip cell t=%s | stood on deck t=%s | left the pad t=%s", t,
                tLip < 0 ? "never" : String.valueOf(tLip), tDeck < 0 ? "never" : String.valueOf(tDeck),
                tFell < 0 ? "no" : String.valueOf(tFell)));
        ctx.record("end", String.format(Locale.ROOT, "step=%s pos=(%.3f,%.3f,%.3f)",
                s, fp.getX(), fp.getY(), fp.getZ()));
        WorldDriverCommon.LOG.info("[wd.{}] step={} pos=({},{},{}) minY={} crossed={} spent={} "
                + "takeoffX={} takeoffH={}->{} gapMove={} tLip={} tDeck={}",
                name, s, fp.getX(), fp.getY(), fp.getZ(), minY, crossed, spent,
                takeoffX, takeoffHBefore, takeoffHAfter, gapMove, tLip, tDeck);

        // Soft checks so BOTH verdicts are always reported: "fell into the void" and "stood still
        // on the lip" are opposite outcomes and one merged line prints them the same.
        ctx.check(minY).as("A must not fall: lowest y of the whole run (pad y=" + padY
                        + ", criterion minY > " + (padY - 3) + ")")
                .isGreaterThan(padY - 3);
        ctx.check(crossed).as("B must get across: final x="
                + String.format(Locale.ROOT, "%.3f", fp.getX())
                + " y=" + String.format(Locale.ROOT, "%.3f", fp.getY())
                + " (criterion x < " + (cx - GAP - 0.5) + " and |y - " + standY + "| <= 0.4;"
                + " leaping across and bridging across both count, standing still does not)").isTrue();

        if (!assertLeap) return;

        // The anti-overfit half. Only the long arm asserts HOW it got across.
        ctx.check(spent).as("C must have LEAPT, not bridged: cobblestone spent this run"
                + " (place.tally=" + av.placeTally() + ")").isEqualTo(0);
        ctx.check(tDeck >= 0 && tLip >= 0 && tDeck - tLip <= LEAP_TICKS)
                .as("D must have leapt FAST: from reaching the lip cell t="
                        + (tLip < 0 ? "never" : String.valueOf(tLip)) + " to standing on the deck t="
                        + (tDeck < 0 ? "never" : String.valueOf(tDeck)) + ", budget " + LEAP_TICKS
                        + " ticks (never reaching the deck is a violation too, otherwise a body"
                        + " stalled on the lip passes this as 0 <= " + LEAP_TICKS + ")").isTrue();
    }

    // --------------------------------------------------------- planner arm ----

    /**
     * <b>Planner-only, seconds, no execution budget.</b> The same gap twice, over a ONE-cell launch
     * pad and over a SIX-cell one, asking only what A* proposes.
     *
     * <h2>What it pins</h2>
     *
     * A leap over two cells of void is priced at {@code Parkour3}'s cost 32 — <b>the sprint-jump
     * maximum</b>. The only precondition the move enforces about the run-up is
     * {@link Move#hasRunway}, whose entire body is
     *
     * <pre>{@code
     * return w.isSolid(from.offset(0, -1, 0));
     * }</pre>
     *
     * i.e. "is the cell under the launch foot solid?" — which a 1x1 pillar top satisfies exactly as
     * well as a twelve-cell runway. <b>No parkour precondition in the family models a single joule
     * of kinetic energy.</b> So the planner proposes, from a standing start on an isolated block, a
     * leap costed for a body at full sprint.
     *
     * <p>This scene turns that into a criterion at planning cost rather than execution cost: it runs
     * two searches and asserts a DIFFERENCE between them. Its short half is expected RED today —
     * that is the current answer being written down, not a bug being introduced. Its long half must
     * stay green through any fix: a run-up model that refuses the 1-cell pad and also refuses the
     * 6-cell one has not modelled a run-up, it has deleted the move.
     *
     * <p>Rig note, following {@code wd.parkourGate}: <b>the inventory is cleared</b>, so
     * {@code LevelWorldView.canPlace()} is false and no bridge can appear in either plan. Without
     * that, the short half could go green by routing a bridge and would then be asserting nothing
     * about run-up at all.
     */
    private static void parkourVoidRunwayGate(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int padY = padY(ctx), standY = padY + 1;
        final int shortDz = -4, longDz = 4;        // two 1-wide lanes, 8 cells apart across the void

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowParkour4 = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        clearAndCatch(level, cx, cz, padY, 6);
        buildPadGapDeck(level, cx, cz + shortDz, padY, 1, 0);
        buildPadGapDeck(level, cx, cz + longDz, padY, 6, 0);

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, standY, cz + shortDz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(() -> fp.discard());
        fp.getInventory().clearContent();          // CRITICAL: no placeable block -> no bridge bypass
        LevelWorldView w = new LevelWorldView(level, fp);

        PathFinder.Result shortPlan = new PathFinder(w, SearchProfile.NONE).findPath(
                new BlockPos(cx, standY, cz + shortDz),
                new Goal.Block(new BlockPos(cx - GAP - 6, standY, cz + shortDz)));
        PathFinder.Result longPlan = new PathFinder(w, SearchProfile.NONE).findPath(
                new BlockPos(cx + 5, standY, cz + longDz),
                new Goal.Block(new BlockPos(cx - GAP - 6, standY, cz + longDz)));

        boolean shortLeaps = crossesGapByParkour(shortPlan, cx);
        boolean longLeaps = crossesGapByParkour(longPlan, cx);
        boolean shortRunway = Move.hasRunway(w, new BlockPos(cx, standY, cz + shortDz));
        boolean longRunway = Move.hasRunway(w, new BlockPos(cx, standY, cz + longDz));

        ctx.record("rig", "gap x=" + (cx - 1) + ".." + (cx - GAP) + " (" + (VOID_DEPTH - 1)
                + " clear cells below) | short arm z=" + (cz + shortDz) + " pad 1 cell | long arm z="
                + (cz + longDz) + " pad 6 cells | the two arms are identical but for the run-up");
        ctx.record("short.plan", planShape(shortPlan));
        ctx.record("long.plan", planShape(longPlan));
        ctx.record("short.crossesByParkour", shortLeaps);
        ctx.record("long.crossesByParkour", longLeaps);
        // The predicate the whole scene is about, printed for BOTH pads. It answers the same for a
        // one-cell pillar and a six-cell runway, which is the finding, not an aside.
        ctx.record("hasRunway.shortLip", shortRunway);
        ctx.record("hasRunway.longLip", longRunway);
        ctx.record("canPlace", w.canPlace());
        ctx.record("placeableBlockCount", w.placeableBlockCount());
        WorldDriverCommon.LOG.info("[wd.parkourVoidRunwayGate] short.reached={} short.parkour={} "
                + "long.reached={} long.parkour={} hasRunway(short)={} hasRunway(long)={}",
                shortPlan.goalReached(), shortLeaps, longPlan.goalReached(), longLeaps,
                shortRunway, longRunway);

        ctx.check(shortLeaps).as("A a 1-cell launch pad must NOT plan a parkour across the void"
                + " (a standing start, priced at the sprint-jump maximum 32; today Move.hasRunway"
                + " only asks whether the cell under the foot is solid, which models no momentum"
                + " whatsoever)").isFalse();
        ctx.check(longLeaps).as("B a 6-cell launch pad MUST plan a parkour across the void"
                + " (otherwise 'model the run-up' has deleted the move rather than given it a"
                + " precondition)").isTrue();
    }

    /** True when the plan gets from the pad side to the deck side in ONE parkour edge — i.e. it
     *  leaps the void rather than walking around it (there is nowhere to walk) or bridging it. */
    private static boolean crossesGapByParkour(PathFinder.Result res, int lipX) {
        List<BlockPos> path = res.path();
        List<Move.Edge> edges = res.edges();
        for (int i = 1; i < path.size() && i < edges.size(); i++) {
            Move.Edge e = edges.get(i);
            if (e == null || e.move == null || !e.move.startsWith("parkour")) continue;
            if (path.get(i - 1).getX() >= lipX && path.get(i).getX() <= lipX - GAP - 1) return true;
        }
        return false;
    }

    /** Node count, goal-reached, cost and the move histogram — the same shape
     *  {@code Walker#planTally} prints, for a result that never reaches a Walker. */
    private static String planShape(PathFinder.Result res) {
        java.util.Map<String, Integer> byMove = new java.util.TreeMap<>();
        for (Move.Edge e : res.edges())
            if (e != null && e.move != null) byMove.merge(e.move, 1, Integer::sum);
        return "goalReached=" + res.goalReached() + " nodes=" + res.path().size()
                + " cost=" + String.format(Locale.ROOT, "%.1f", res.finalCost())
                + " moves " + (byMove.isEmpty() ? "{}" : byMove.toString())
                + (res.path().isEmpty() ? ""
                        : " last=" + res.path().get(res.path().size() - 1).toShortString());
    }
}
