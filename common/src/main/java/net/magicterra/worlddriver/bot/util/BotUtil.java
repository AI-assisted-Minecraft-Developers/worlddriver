package net.magicterra.worlddriver.bot.util;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Stateless conversion / parsing / threading helpers shared across the bot
 * package. Extracted from the former {@code BotApiImpl} god-class; callers
 * pull these in via {@code import static …BotUtil.*} so call sites stay
 * unqualified.
 */
public final class BotUtil {

    private BotUtil() {}

    /**
     * The cell an entity's position floors into — the bot's answer to "which cell is the body in".
     *
     * <p>This exact expression was written out inline in a dozen places (BotApiImpl,
     * ClutchController ×2, WalkerTickPrelude, Backfill/Bridge/Farm/Mine ×3/Sleep, RetreatChain)
     * while this helper already existed — a dozen hand-written floors is a dozen chances for the
     * next one to be written differently, and "the cell the body is in" is precisely the quantity
     * this repo has been bitten by having two answers to.
     *
     * <p><b>⛔ A caller holding a CLIENT-ONLY reference must take the three-double overload.</b>
     * This paragraph used to say the copies were byte-identical and folding them in therefore
     * changed nothing. The dedicated-server gate of 2026-08-26 refuted that in the only way it
     * could: three of the folded call sites held a {@code LocalPlayer}
     * ({@code RetreatChain#fleeFrom}, and BotApiImpl's waypoint and runAway handlers), and handing
     * one to this {@code Entity} parameter makes the VERIFIER prove {@code LocalPlayer <: Entity}
     * — which loads the class. {@code wd.retreatGateMatrix} and {@code wd.cancelRouting} went from
     * PASS to「Cannot load class net.minecraft.client.player.LocalPlayer in environment type
     * SERVER」. The hand-written floor did not do this: {@code p.getX()} is an invokevirtual
     * resolved lazily against the local's own type, and on a server that line never runs.
     * <b>Identical source, different class-loading.</b> "Byte-identical" is a claim about the
     * expression, not about the bytecode a widening call site emits.
     *
     * <p>That ⛔ has no exemptions: every caller left on this overload passes a value whose static
     * type is already {@code Entity}. It was briefly a list of three, and the list was deleted
     * rather than maintained — an exemption roster is a second thing to keep true, and it stays
     * true right up until the day the class it excuses starts being constructed server-side.
     * <b>Do not trust this sentence either</b>; it is one refactor from being stale. Re-derive it
     * by reading the compiled constant pools for a Methodref to {@code blockPosOf} whose descriptor
     * begins {@code (Lnet/minecraft/world/entity/Entity;)}, then typing each argument at its source.
     *
     * <p><b>Deliberately not {@code e.blockPosition()}.</b> That is a cached field vanilla
     * maintains inside {@code setPosRaw}, i.e. a second authority with its own update schedule.
     * It may well agree with this floor everywhere the bot asks, but swapping it in is a change
     * of SOURCE, not a rename, and belongs to a measurement rather than a tidy-up.
     */
    public static BlockPos blockPosOf(Entity e) {
        return blockPosOf(e.getX(), e.getY(), e.getZ());
    }

    /** The same floor for a caller whose reference is a client-only type — see the warning on the
     *  overload above. Three doubles ask the verifier nothing about the holder's class, so this
     *  keeps one authority for「which cell」without dragging a client class onto a server. */
    public static BlockPos blockPosOf(double x, double y, double z) {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public static Map<String, Object> posMap(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    public static BlockPos readPos(Object o) { return Params.toPos(o); }

    // These forward to Params so a bot-package caller need not import it. Only the ones
    // something actually calls are kept: `doubleOr` and `parseStringList` sat here with
    // zero call sites while their neighbours had 3-42 each, because callers reach
    // Params.toDouble / Params.toStringList directly. A never-asked forwarder is not a
    // convenience, it is a second name for one function — add one back the day a caller
    // wants it, not in advance.
    public static int intOr(Object o, int dflt) { return Params.toInt(o, dflt); }
    public static int clamp(int v, int lo, int hi) { return Params.clamp(v, lo, hi); }

    public static Map<String, Object> unimplemented(String msg) {
        return Map.of("ok", false, "error", "unimplemented: " + msg);
    }

    // === Threading bridge ====================================================

    /**
     * Run {@code body} on the client thread and return its value.
     *
     * <p>The same hop as {@code DriverApi.onServerThread} ({@link ClientHop} runs
     * {@code ServerThreadHop} over the client's executor), so it is identical to it in the
     * respects that are observable to a caller:
     * <ul>
     *   <li><b>A timeout is a definite answer.</b> A task still queued when the wait runs out
     *       is withdrawn and never runs ({@code -32001}); one already running is reported as
     *       such ({@code -32002}). A caller told "failed" that retries cannot apply it twice.</li>
     *   <li><b>Bounded.</b> {@code mc.execute} only runs when the client drains its task
     *       queue; during shutdown, a hung level load, or a blocking modal it may never
     *       do so. The old unbounded {@code fut.get()} then parked the calling transport
     *       thread permanently — the request never returned and never errored.</li>
     *   <li><b>Transparent to exceptions.</b> The old code wrapped everything in
     *       {@code RuntimeException(e)}, so the same failure read as
     *       {@code IllegalArgumentException: bad param} when invoked from the client
     *       thread (the {@code isSameThread} fast path, which rethrows raw) but as
     *       {@code RuntimeException: ExecutionException: IllegalArgumentException: bad
     *       param} from any transport thread. Unwrapping the {@code ExecutionException}
     *       makes both paths report the same text — which is what the three-transport
     *       byte-identical parity assertion actually compares.</li>
     * </ul>
     */
    public static <T> T onClient(Supplier<T> body) {
        return ClientHop.call(body);
    }

    // === Camera smoothing (mc.bot.setting{smoothLook}) =======================

    /**
     * Step {@code cur} toward {@code target} by at most
     * {@link BotConfig#smoothLookDegPerTick} when {@link BotConfig#smoothLook}
     * is on; otherwise snap straight to the target (current behavior). Valid for
     * both yaw (wraps mod 360) and pitch (no wrap within ±90). Used by the
     * pathfinding Walker and the LookProcess; functional aiming snaps by calling
     * the rotation setters directly instead of going through here.
     */
    public static float smoothAngle(float cur, float target) {
        if (!BotConfig.smoothLook) return target;
        float max = Math.max(0.1f, BotConfig.smoothLookDegPerTick);
        float d = ((target - cur) % 360f + 540f) % 360f - 180f; // shortest signed delta
        if (Math.abs(d) <= max) return target;
        return cur + Math.copySign(max, d);
    }

    /**
     * Damage-on-contact blocks the pathfinder must route around. Lava is checked
     * by fluid tag; fire variants by {@link net.minecraft.tags.BlockTags#FIRE} so
     * datapack-added fire blocks are covered automatically. Remaining hazards are
     * listed by {@link net.minecraft.world.level.block.Block} reference for O(1)
     * lookup and so a modpack-added hazard can be added in a single line.
     */
    public static final Set<Block> HAZARD_BLOCKS = Set.of(
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW,
            Blocks.WITHER_ROSE,
            // §88: "impaled on a stalagmite" (C102-J2 death). Fall damage onto an
            // upward spike is DOUBLED and the executor drifts ±1 block during a
            // committed fall, so the contact ring must tax the spike's neighbourhood
            // — the block was simply missing from this list (same class as the
            // FLOWING_LAVA FluidTags blind spot).
            Blocks.POINTED_DRIPSTONE
    );

    /**
     * Does this block state damage a body that stands in it — the SINGLE author of that policy for
     * every {@link net.magicterra.worlddriver.bot.pathfinder.WorldView} backed by a real level
     * ({@code ClientWorldView}, {@code bot.world.ServerWorldView}, {@code bot.world.LevelWorldView}).
     * All three carried their own copy; the three copies happened to agree, which is precisely the
     * state that a fourth edit ends.
     *
     * <p><b>{@link FluidTags#LAVA}, never {@code Fluids.LAVA}.</b> {@code FluidState.is(Fluid)}
     * compares the exact fluid type, and a lava lake's EDGE and its falls are {@code FLOWING_LAVA}
     * — under the type compare every flowing cell read as「not a hazard」, so {@code canStandAt}
     * admitted feet-in-lava nodes, {@code dangerCost} charged nothing and the walker's
     * {@code hazardAhead} brake stayed blind (round54: the bot waded 6 s through a lava shore at
     * full sprint, enteredLava ×2). Fixing that meant editing the same four lines in three files;
     * that is the whole argument for this method.
     *
     * <p>Fire goes by {@link BlockTags#FIRE} so a datapack-added fire block is covered
     * automatically; everything else is {@link #HAZARD_BLOCKS} by block reference. The
     * user-configurable {@code BotConfig.extraHazardBlocks} (Baritone-style {@code blocksToAvoid})
     * is checked LAST so the built-ins stay short-circuit cheap.
     *
     * <p><b>Not every {@code isHazard} belongs here.</b> Two others answer deliberately
     * differently, and neither is a straggler to route in:
     * <ul>
     *   <li>{@code bot.debug.GridWorldView} answers a flat {@code false} — a synthetic grid for
     *       the path-debug tools, with no level to ask.</li>
     *   <li>{@code auto.ContactDamageEscape.isContactHazard} reuses {@link #HAZARD_BLOCKS} and
     *       {@link BlockTags#FIRE} but drops BOTH the lava clause and
     *       {@code extraHazardBlocks}. That reflex exists for CONTACT damage (cactus, magma,
     *       sweet berries) and lava has its own dedicated escape beside it, so folding lava in
     *       would make the two reflexes fight over the same body. The user list is omitted
     *       because {@code blocksToAvoid} means "route around", not "I am being hurt".</li>
     * </ul>
     * Both are narrower than this method on purpose; widening either one is a behaviour change,
     * not a cleanup.
     */
    public static boolean isHazardState(BlockState s) {
        if (s.getFluidState().is(FluidTags.LAVA)) return true;
        if (s.is(BlockTags.FIRE)) return true;
        if (HAZARD_BLOCKS.contains(s.getBlock())) return true;
        Set<String> extras = BotConfig.extraHazardBlocks;
        return !extras.isEmpty()
                && extras.contains(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
    }

    /** Cheap stand finder: 4 cardinals at same Y, then Y-1, then Y+1, then on
     *  top of the block. Water counts as passable. Used by the goto block
     *  selector — the more thorough SEARCHES in MineProcess / BboxFillProcess
     *  scan more cells and add reach checks tailored to mining/clearing, but
     *  they all judge a candidate cell with {@link #canStandHereStatic}. */
    public static BlockPos findStandAdjacent(Level lvl, BlockPos block) {
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : dxz) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (canStandHereStatic(lvl, cand)) return cand;
            }
        }
        BlockPos above = block.offset(0, 1, 0);
        if (canStandHereStatic(lvl, above)) return above;
        return null;
    }

    /**
     * Can a body stand with its feet in {@code foot}: a floor that blocks motion under it, and
     * both body cells clear of anything that does, with a water exception.
     *
     * <p>That exception reaches less than it reads. {@code Blocks.WATER.blocksMotion()} is
     * {@code false}, so a plain water cell is already clear and never tests the
     * {@code !is(Fluids.WATER)} clause at all. The clause only ever fires for a WATERLOGGED
     * SOLID — a waterlogged slab, stairs or fence, which blocks motion AND reports fluid
     * WATER — and its effect there is to admit that cell as body space. So this is not "a body
     * wades"; it is "a waterlogged block does not count as an obstruction".
     *
     * <p><b>The answer for most of the process family — not yet all of it.</b>
     * {@code BboxFillProcess}, {@code FarmProcess} and {@code MineProcess} each carried a
     * byte-identical private copy of this, which is how a stand test comes to mean three things:
     * the day somebody teaches one of them about a half-slab, the other two keep walking onto it.
     * {@code MineProcess} still refuses more than this — it additionally vetoes a cell lava
     * touches — and that is written there as {@code canStandHereStatic(...) && no lava}, so the
     * extra clause is visibly extra rather than a second opinion about the same question.
     *
     * <p><b>Two members never joined, and their copy has since diverged.</b>
     * {@code BuildProcess.canStand} and {@code BackfillProcess.canStand} are still private, still
     * byte-identical to each other, and are missing BOTH water clauses below — so a WATERLOGGED
     * cell (a waterlogged slab/stairs/fence: {@code blocksMotion()} true, fluid WATER) is standable
     * here and refused there. Those two are therefore STRICTER than the rest of the family, and a
     * build over a waterlogged surface reports {@code skipped} for a cell the goto selector would
     * happily walk to. Left as-is deliberately: adopting this method there LOOSENS two placement
     * paths, which is the direction that needs a measurement, not a tidy-up.
     *
     * <p>Cell-shaped, deliberately: this decides where to SEND a body, before it is there. Whether
     * a body already standing somewhere is actually supported is a different question with a
     * different answer — the body is 0.6 wide and can be held by a neighbour cell — and it belongs
     * to {@code WalkerGeometry.soleOnSolid}. Do not use one for the other.
     *
     * <p><b>It has no hazard clause, and the paragraphs above account for every divergence except
     * that one.</b> {@code WorldView.canStandAt} — the pathfinder's own answer to this same
     * question — refuses {@code isHazard} on the floor, the foot cell AND the head cell. This
     * method refuses none of the three, and the gap is not cosmetic in either direction:
     * <ul>
     *   <li>MAGMA_BLOCK is in {@link #HAZARD_BLOCKS} and {@code blocksMotion()} is true, so it
     *       passes here as a FLOOR and is refused there.</li>
     *   <li>Lava {@code blocksMotion()} is false, so a foot cell FULL OF LAVA over a solid floor
     *       passes all three clauses here and is refused there.</li>
     * </ul>
     * That is the validation-cell-vs-execution-cell shape this repo keeps paying for: the one
     * consumer that hands the result straight to the Walker — {@link #findStandAdjacent} →
     * {@code GoalResolver.findNearestStandForBlock} → {@code Goal.Block} — picks a cell A* can
     * never expand, so the goal is unreachable by construction and the run burns its node budget
     * on best-effort paths instead of failing. {@code MineProcess} is the only caller that noticed;
     * its {@code && no lava} is described above as a digger's EXTRA refusal, and reads that way,
     * but it is really this method's missing clause patched at one call site out of four.
     *
     * <p>Adopting the hazard clause TIGHTENS every consumer, which is the safe direction here
     * precisely because the cells it would newly refuse are cells the planner already refuses —
     * the selector's answer for them was never usable. Not done in the same pass that wrote this
     * down: it changes what {@code goto block:} and the two fill verbs select, so it wants its own
     * gate run and its own attribution, not a ride on a comment commit.
     */
    public static boolean canStandHereStatic(Level lvl, BlockPos foot) {
        BlockState below = lvl.getBlockState(foot.offset(0, -1, 0));
        BlockState here = lvl.getBlockState(foot);
        BlockState head = lvl.getBlockState(foot.offset(0, 1, 0));
        if (!below.blocksMotion()) return false;
        if (here.blocksMotion() && !here.getFluidState().is(Fluids.WATER)) return false;
        if (head.blocksMotion() && !head.getFluidState().is(Fluids.WATER)) return false;
        return true;
    }

    /**
     * First horizontal direction a body at {@code foot} can be shoved into: both body cells
     * {@code open}, no {@code hazard} in the foot, head OR floor cell, preferring a direction that
     * also has a floor (a cell whose {@code open} test FAILS one below the feet) so an escape does
     * not trade a hazard for a ledge. Null when every cardinal is refused — the caller decides what
     * a fully ringed body does.
     *
     * <p>Both escape reflexes ask this, each from its own copy until now. The copies had already
     * drifted once, in the clause that matters most: {@code hazard(f.below())} was in
     * {@code LavaProximityEscape}'s copy from the day it was written and missing from
     * {@code ContactDamageEscape}'s — and that copy's ONLY caller is the branch whose own comment
     * names「the hazard is directly BELOW (magma floor)」as the case it exists for. So an escape off
     * a magma slab was free to answer with the next cell of the same slab, re-trigger next tick one
     * cell over, and read as a working reflex the world keeps beating.
     *
     * <p><b>Two predicates, not one.</b> The hazard test is the difference anyone would expect
     * between the two callers. {@code open} is the one a merge would have flattened silently:
     * the contact caller asks {@code getCollisionShape(...).isEmpty()}, the lava caller asks
     * {@code !blocksMotion()}, and those disagree about thin-collision cells (carpet, pressure
     * plate, lily pad) — the contact caller refuses to step onto one, the lava caller steps over
     * it. Choosing either for both moves a live gate, and moves the contact one LOOSER, which is
     * the direction that needs a measurement rather than a tidy-up. Each caller still hands in its
     * own; only the skeleton is shared.
     *
     * <p>Takes the foot {@code BlockPos} rather than the player on purpose. A {@code LocalPlayer}
     * parameter is why neither copy could ever be exercised off a client: any scene that would
     * cover them SKIPS on a dedicated server, so the drift above went unnoticed by every gate.
     */
    public static Direction stepAwayCardinal(BlockPos foot, Predicate<BlockPos> hazard,
                                             Predicate<BlockPos> open) {
        Direction firstClear = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos f = foot.relative(d);
            if (hazard.test(f) || hazard.test(f.above()) || hazard.test(f.below())) continue;
            if (!open.test(f) || !open.test(f.above())) continue;
            if (firstClear == null) firstClear = d;
            if (!open.test(f.below())) return d;   // solid floor → best
        }
        return firstClear;
    }

    /**
     * The nearest {@code block} this body could use from where it stands: the closest one in a
     * {@code radiusH}×{@code radiusV} box around the feet whose CENTRE is inside {@code reach}
     * of the eye, or null.
     *
     * <p><b>The other half of a pair that has already been merged once.</b>
     * {@code CraftProcess.findTable} and {@code SmeltProcess.findFurnace} carried this loop
     * twice over, identical but for the block tested and both priced at {@code REACH = 4.3}.
     * The PLACE half of the same two verbs was merged into {@code PlaceNearby} only after the
     * copies had already diverged twice — its javadoc records gap#61 fixing the table's scan
     * and gap#62 then finding the furnace still on the pre-evolution version. The FIND half was
     * left in two copies in those same two files. Merged before it gets its own gap number.
     *
     * <p><b>Deliberately not routed through {@link #eyeWithin}.</b> Both originals ranked with a
     * STRICT {@code <} against a running best seeded at {@code reach²}, so a cell at exactly
     * {@code reach} was refused; {@code eyeWithin} is {@code <=} and would admit it. The
     * difference is one floating-point equality wide and would almost never show, which is
     * precisely why swapping it in as a tidy-up would be the wrong trade: it LOOSENS a gate for
     * no gain, and a loosening nobody can observe is a loosening nobody can attribute later.
     * Same measurement as the rest of the family (eye → block centre); only the comparison is
     * kept as it was.
     *
     * @param radiusH horizontal half-width of the scan box, in cells
     * @param radiusV vertical half-height of the scan box, in cells
     */
    public static BlockPos nearestBlockWithinReach(Player p, Level lvl, Block block,
                                                   double reach, int radiusH, int radiusV) {
        BlockPos base = p.blockPosition();
        BlockPos best = null;
        double bestD = reach * reach;
        Vec3 eye = p.getEyePosition();
        for (int dx = -radiusH; dx <= radiusH; dx++)
            for (int dy = -radiusV; dy <= radiusV; dy++)
                for (int dz = -radiusH; dz <= radiusH; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (!lvl.getBlockState(pos).is(block)) continue;
                    double d = eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < bestD) { bestD = d; best = pos; }
                }
        return best;
    }

    /**
     * Where the eye WOULD be if a body stood with its feet in {@code foot} — the cell centre,
     * 1.62 up. For deciding, before the body is there, whether a candidate stand can reach a
     * block; a body that is already somewhere has {@code p.getEyePosition()} and must use it.
     *
     * <p>The three literals were written out twice ({@code MineProcess.findReachStand},
     * {@code BboxFillProcess.withinReach}) — the same hypothetical eye, so one place.
     *
     * <p><b>The radius is deliberately NOT part of this.</b> "Within reach" is asked in SEVEN
     * places and they do not all want the same margin — so every one measures the same way
     * (eye to block centre) and differs only in a number you can read:
     *
     * <p><b>This table said "five" and listed five for a day, and it was wrong when it was
     * written</b> — the two station scans below were already there, already measuring eye to
     * block centre, already carrying a radius of their own. A table of divergences is worth
     * having only if it is the WHOLE list; a partial one is worse than none, because the next
     * reader takes "five places" as the search being finished. Count from a grep, not from
     * this paragraph, before adding a row.
     *
     * <table><caption>reach predicates, 2026-08-23</caption>
     * <tr><th>site</th><th>eye</th><th>radius</th></tr>
     * <tr><td>{@code ServerPlayerBody.canBreakFromHere} — <b>the authority</b>, and where
     *     {@link #blockReachToCentre} came from</td><td>the real eye</td>
     *     <td>{@code blockInteractionRange() + 0.5}</td></tr>
     * <tr><td>{@code WalkerTickPrelude} dig-claim release</td><td>the real eye</td>
     *     <td>{@link #blockReachToCentre} — a release gate must match the actuator exactly,
     *     or it either abandons reachable blocks or holds unreachable ones</td></tr>
     * <tr><td>{@code MineProcess.findReachStand}</td><td>this hypothetical eye</td>
     *     <td>{@code MAX_REACH} = 4.4, plus a collider ray</td></tr>
     * <tr><td>{@code BboxFillProcess.withinReach}</td><td>this hypothetical eye</td>
     *     <td>{@code FILL_STAND_REACH} = 4.0, no ray</td></tr>
     * <tr><td>{@code WalkerTickClimb} parkour-place</td><td>the real eye</td>
     *     <td>{@code PARKOUR_PLACE_REACH} = 4.0</td></tr>
     * <tr><td>{@code CraftProcess.findTable}</td><td>the real eye</td>
     *     <td>{@code CraftProcess.REACH} = 4.3, via {@link #nearestBlockWithinReach}</td></tr>
     * <tr><td>{@code SmeltProcess.findFurnace}</td><td>the real eye</td>
     *     <td>{@code SmeltProcess.REACH} = 4.3, same helper, same number</td></tr>
     * </table>
     *
     * <p>The two 4.3s are a RANKING cutoff, not a release gate: they pick which already-placed
     * station to walk up to, and the body then re-approaches it. Being a hair tighter than the
     * authority costs at most one extra {@code PlaceNearby} call, which is why they were never
     * a defect — only never written down.
     *
     * <p>The three short radii are margin bought on purpose, and buying margin on the way IN is
     * the safe direction: two of them pick a cell to WALK TO (the body will not be standing on
     * that centre when it arrives) and the third fires mid-leap off an already-stale eye. The two
     * that gate a LIVE interaction take the authority's number, because for those margin is not
     * safety — it is a false refusal.
     *
     * <p><b>What this table replaced</b> was a sixth answer that measured a different quantity:
     * the dig-claim release used {@code distToCenterSqr(p.position())}, i.e. from the FEET. See
     * {@link #eyeWithin} for why that is loose downward and tight upward rather than merely
     * imprecise.
     */
    public static Vec3 standingEye(BlockPos foot) {
        return new Vec3(foot.getX() + 0.5, foot.getY() + 1.62, foot.getZ() + 0.5);
    }

    /**
     * Is the centre of {@code block} within {@code reach} of {@code eye}? The one place that
     * decides "can this body operate on that cell", so the five call sites differ only in the
     * radius each passes — which is a visible number rather than a second opinion.
     *
     * <p><b>From the eye, never the feet.</b> The two are not the same measurement and swapping
     * them is not a rounding difference: a cell 5 BELOW the body is 5.0 from the feet but 6.6
     * from the eye, and a cell 5 ABOVE is 5.0 from the feet but only 3.4 from the eye. A
     * feet-based gate is therefore LOOSE downward and TIGHT upward — and tight-upward is exactly
     * the case {@code MineProcess.findReachStand} scans {@code dy} down to −5 to support (stand
     * under an overhead block and mine straight up, which its own comment calls "within the 4.5
     * reach" precisely because it measures from the eye).
     *
     * @param eye the eye position — {@code p.getEyePosition()} for a body that is already there,
     *            {@link #standingEye} for a candidate cell it has not walked to yet
     */
    public static boolean eyeWithin(Vec3 eye, BlockPos block, double reach) {
        return eye.distanceToSqr(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5)
                <= reach * reach;
    }

    /** {@link #eyeWithin(Vec3, BlockPos, double)} for a body that is already standing somewhere. */
    public static boolean eyeWithin(LivingEntity p, BlockPos block, double reach) {
        return eyeWithin(p.getEyePosition(), block, reach);
    }

    /**
     * The reach the GAME grants this body, measured to a block CENTRE — the number every
     * "can I still operate on that cell" gate should be comparing against.
     *
     * <p>{@code blockInteractionRange()} is the player's own attribute and is measured to the
     * nearest FACE; the half block converts it to the centre, erring outward so a gate never
     * rejects an interaction vanilla would allow. Lifted from
     * {@code ServerPlayerBody.canBreakFromHere}, which is where this repo first asked the game
     * instead of hardcoding a number.
     *
     * <p>Only players carry {@code BLOCK_INTERACTION_RANGE} in their attribute map, and asking
     * {@code getAttributeValue} for an attribute the entity's supplier never registered throws.
     * A non-player body is therefore given the attribute's vanilla default rather than a lookup.
     */
    public static double blockReachToCentre(LivingEntity p) {
        double faceReach = p instanceof Player pl ? pl.blockInteractionRange() : 4.5;
        return faceReach + 0.5;
    }

    // === Aiming (the process family) =========================================

    /**
     * Point the body's yaw, head, body and pitch at an exact world point.
     *
     * <p>The four rotation setters and the two {@code atan2} calls were copied into six process
     * classes; an aim that is written out by hand at every call site is how「瞄的是哪一点」quietly
     * comes to differ between two verbs that mean to do the same thing.
     *
     * <p><b>Not the same as {@code BotInteract.aimAtBlockSnap}, and they must not be merged.</b>
     * That one takes a {@code LocalPlayer} and additionally asks {@code LookController} to exempt
     * the tick from camera smoothing, because vanilla mining/interaction raycasts off the
     * CROSSHAIR and a lagged crosshair hits the wrong block. This one only sets the angles: its
     * callers act through {@code gameMode.useItemOn} with a hit result they computed themselves,
     * so the crosshair is not what decides, and it also has to work for a server-side {@code
     * Player} that has no client camera at all.
     */
    public static void aimAt(LivingEntity p, double tx, double ty, double tz) {
        Vec3 eye = p.getEyePosition();
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitch);
    }

    /**
     * The face of {@code block} pointing back at this body's eye — the side a ray from the eye
     * would land on, for callers that were not told which face to click.
     *
     * <p>The dominant axis of eye−centre wins, ties going to the earlier test ({@code y}, then
     * {@code x}); every {@code >=} below is load-bearing for a body standing exactly on an axis,
     * which is the common case for a bot that walks to a cell centre before interacting.
     *
     * <p><b>Here rather than in {@code BotInteract}, where it used to live alone.</b> Two
     * processes — {@code CraftProcess} and {@code SmeltProcess} — each carried a byte-identical
     * private copy under a javadoc explaining they had inlined it "to keep this process off the
     * client-only BotInteract so it loads on a dedicated server". <b>That reason was correct and
     * still is</b>: {@code BotInteract} names {@code Minecraft}, {@code LocalPlayer},
     * {@code KeyMapping} and {@code MultiPlayerGameMode}, and a dedicated server has none of them.
     * What was wrong was the conclusion that the only way out is a private copy each. This class
     * is where the process family's aiming already lives, and every one of those callers runs
     * under the dedicated-server gate today.
     *
     * <p>So: the parameter is {@link Player} and the body names no {@code net.minecraft.client}
     * type, not even as a local — that is the property that lets a server-side caller reach it,
     * and it is the property to preserve if this method ever grows.
     * {@code BotInteract.pickFaceTowardsPlayer} is now a one-line delegate, so its
     * client-side callers are unchanged and there is still exactly one answer. Count them
     * with a grep, not from here: this sentence and the delegate's own said "six" while
     * there were five, in both places, which is exactly the two-copies-drift-together
     * failure the rest of this javadoc is about.
     */
    public static Direction faceTowardEye(BlockPos block, Player p) {
        Vec3 eye = p.getEyePosition();
        double dx = eye.x - (block.getX() + 0.5);
        double dy = eye.y - (block.getY() + 0.5);
        double dz = eye.z - (block.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Walk the last fraction of a cell to the stand's centre before placing, or report that the
     * body is already centred enough to click.
     *
     * <p>{@code WalkerConstants.REACH_DIST_SQ = 0.45} lets a leg ARRIVE ~0.67 short of the stand cell's
     * centre. Even sneaking — hull half-width 0.3 — that is not enough clearance from the placement
     * target when the stand is adjacent to it, and vanilla's {@code Level.isUnobstructed} refuses.
     * So the last quarter of a block is walked here, by the placer, rather than asked of the
     * pathfinder: hold forward until within 0.25 of the centre on X/Z, then release and click.
     *
     * <p>Both placement verbs need this and both carried their own copy — identical down to the
     * 0.25 threshold, the yaw formula and the three rotation writes, differing only in the comments
     * around them ({@code BackfillProcess}'s said "mirror of BuildProcess fix", which is how a copy
     * announces itself). An approach gate is exactly the kind that gets tuned once: move the
     * threshold for one verb and the other goes on refusing placements nobody can explain.
     *
     * @return true while still closing in — the caller must yield the tick; false once centred, in
     *         which case forward has already been released and the placement may proceed.
     */
    public static boolean stepToStandCentre(LivingEntity p, Body a, BlockPos stand) {
        double dxToCenter = (stand.getX() + 0.5) - p.getX();
        double dzToCenter = (stand.getZ() + 0.5) - p.getZ();
        if (Math.sqrt(dxToCenter * dxToCenter + dzToCenter * dzToCenter) > 0.25) {
            // Re-aim forward at the stand centre; the caller re-faces the support next tick.
            float yaw = (float) Math.toDegrees(Math.atan2(-dxToCenter, dzToCenter));
            p.setYRot(yaw);
            p.yHeadRot = yaw;
            p.yBodyRot = yaw;
            a.commandForward(1f);
            return true;
        }
        a.commandForward(0f);
        return false;
    }

    /**
     * Look at the centre of the face a placement clicks: {@code block} is where the new block is
     * to appear and {@code face} is the side of the supporting neighbour it grows off, so the
     * support sits opposite {@code face} and the point to aim at is half a block out from that
     * support's centre along {@code face}.
     */
    public static void aimAtSupportFace(LivingEntity p, BlockPos block, Direction face) {
        BlockPos support = block.offset(-face.getStepX(), -face.getStepY(), -face.getStepZ());
        aimAt(p, support.getX() + 0.5 + face.getStepX() * 0.5,
                 support.getY() + 0.5 + face.getStepY() * 0.5,
                 support.getZ() + 0.5 + face.getStepZ() * 0.5);
    }

    /**
     * The yaw that faces a cardinal direction, for the shaft-walking processes that steer by
     * setting a yaw and holding forward (bunker step-in, descend stair, escape climb-out).
     *
     * <p><b>Not {@link Direction#toYRot()}, on purpose.</b> That returns {@code 270f} for EAST
     * where this returns {@code -90f}. The two are the same angle and are NOT the same number,
     * and {@code setYRot} stores the number: rendering interpolates between the previous yaw and
     * this one by difference, so swapping {@code -90} for {@code 270} makes the body spin a full
     * turn where it used to snap. This body was identical in three processes; it was moved here
     * unchanged rather than replaced with the vanilla helper for exactly that reason.
     */
    public static float yawFor(Direction d) {
        return switch (d) {
            case SOUTH -> 0f;
            case WEST  -> 90f;
            case NORTH -> 180f;
            case EAST  -> -90f;
            default    -> 0f;
        };
    }
}
