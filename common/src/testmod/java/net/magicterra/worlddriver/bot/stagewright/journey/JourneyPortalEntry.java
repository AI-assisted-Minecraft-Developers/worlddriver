package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;

/**
 * Getting a body into a portal it has already lit — the whole of the ladder's NETHER rung.
 *
 * <p>Split out of {@code WorldDriverJourneyScenes} because that file is at its source budget, and
 * because the geometry below is worth testing without a forty-minute playthrough: everything above
 * {@link #stepInto} is pure world-reading, and {@code JourneyPortalEntryScenes} exercises it in an
 * arena.
 *
 * <h2>Why a lit portal is not automatically a portal you can walk into</h2>
 *
 * A portal column is six cells and the pathfinder will accept <b>at most one</b> of them as a
 * destination: {@code WorldView.canStandAt} wants a solid floor, and the floor of every cell but the
 * lowest is another portal block, which has no collision. So {@code Goal.Block(bottomCell)} is the
 * only goal shape that can even terminate — and it terminates only if some route reaches that one
 * cell.
 *
 * <p>On the ladder it does not. Rung 12 casts the frame by pouring lava at it across a flooded
 * alcove, and lava meeting water leaves <b>cobblestone</b> — in the alcove, one cell in front of the
 * doorway. Measured, run of 2026-08-19, portal at {@code 4,57,19} with the alcove at
 * {@code x in [2,3]}:
 *
 * <pre>
 * 3,57,19 = cobblestone   3,58,19 = cobblestone   3,59,19 = air (standable)
 * </pre>
 *
 * The bottom two rows were walled off, and this rung's baseline has {@code allowBreak=false}
 * ({@code BotConfig.applyGameTestBaseline} runs once at server start and this rung never calls
 * {@code JourneyRig.generousPathfinding}), so no route to the bottom cell existed at all. The rung
 * asked for it eight times, got the same answer eight times — {@code portal.leg.0} through
 * {@code portal.leg.7} byte-identical — and then blamed the transfer timer for a body that had never
 * been inside a portal.
 *
 * <h2>The top row is not a way in, and that is why "find the open row" is not enough</h2>
 *
 * The obvious repair — walk to the one row whose front is open, {@code 3,59,19}, and step east — was
 * written first and measured in {@code wd.portalEntryStepsInFromTheOpenRow}: sixty ticks of held
 * forward moved the body from {@code z=…99999.306} to {@code …99999.700} and stopped dead, with
 * {@code dm.z} at exactly {@code 0.000}. {@code 99999.700} is {@code 100000 − 0.3}: the front face of
 * a player's own hitbox, flush against something solid.
 *
 * <p>The something is the frame. A portal interior is three cells tall and a body is 1.8, so a body
 * standing in the TOP row has its head in the obsidian cap. Vanilla's own portals are entered at the
 * bottom or middle row for exactly this reason. So an entry cell is only an entry cell when
 * {@link #enterable} — the cell ABOVE it has to be free too — and on the ladder's geometry that
 * leaves rows 57 and 58, both of which were walled.
 *
 * <h2>The shape of the fix</h2>
 *
 * <ol>
 *   <li><b>Pick the row, not just the column, and require the body to fit.</b> {@link #find} looks
 *       for a cell it can STAND in beside a portal cell whose head is clear.</li>
 *   <li><b>Open one cell of the wall when nothing is open.</b> {@code find(…, true)} costs each
 *       candidate in blocks that would have to be mined and takes the cheapest. On the ladder's run
 *       that is ONE cobblestone, {@code 3,58,19} — the middle row's doorstep, whose floor
 *       ({@code 3,57,19}, more slag) is already solid and whose head ({@code 3,59,19}) is already
 *       air. The frame itself is never a candidate: {@link #diggable} refuses obsidian, and a rung
 *       that mined its own portal out would be worse than one that never got in.</li>
 *   <li><b>Walk to the doorstep, not into the portal.</b> The doorstep is standable by construction,
 *       so it is a goal A* can accept.</li>
 *   <li><b>Step in by hand.</b> The last block is not a pathfinding problem and never can be — the
 *       target cell has no floor. {@link #stepInto} aims at it and holds forward until the body's own
 *       cell reads {@code nether_portal}.</li>
 * </ol>
 *
 * <h2>What the failures must never say</h2>
 *
 * "stood in the portal and was not transferred" and "never reached the doorway" want opposite
 * fixes — an engine-side look at
 * {@code Entity.handlePortal}, or a look at the doorway's geometry — so they are two messages and
 * two branches, decided by {@code Attempt.everInPortal}. Every tick count in either of them is
 * summed from the level's own clock as the legs run, never from a budget: the message this replaces
 * claimed 1200 ticks on a scene that ran 451.
 */
public final class JourneyPortalEntry {

    private JourneyPortalEntry() {}

    // ================================================================== geometry ====

    /** How many portal cells one flood-fill follows. A vanilla portal maxes out at a 21x21 interior;
     *  this only has to cover the ladder's 2x3 and refuse to walk a corrupted world forever. */
    private static final int MAX_CELLS = 64;

    /** How hard a block may be before this rung declines to open it. Cobblestone is 2, stone 1.5,
     *  iron ore 3; obsidian is 50 and needs a diamond pickaxe the ladder does not own. */
    private static final float MAX_DIG_HARDNESS = 5.0f;

    /**
     * A cell the body can stand in, the portal cell it can walk into from there, and the blocks that
     * would have to be mined for that to be true.
     *
     * <p>{@code clear} empty means "walk there now"; anything in it is a cell of the alcove wall, in
     * the order it should be opened. Never the frame and never a portal cell — see {@link #diggable}.
     */
    public record Doorstep(BlockPos stand, BlockPos cell, List<BlockPos> clear) {}

    /**
     * Every {@code nether_portal} cell connected to {@code any}, in reading order (y, then x, then z).
     *
     * <p>Flood-filled rather than walked as a column, because the doorway is two cells wide and the
     * only open row on the ladder's run was reachable from the OTHER column than the one
     * {@code nearestBlock} happened to return.
     */
    public static List<BlockPos> cells(ServerLevel level, BlockPos any) {
        List<BlockPos> out = new ArrayList<>();
        if (any == null || !level.getBlockState(any).is(Blocks.NETHER_PORTAL)) return out;
        Set<BlockPos> seen = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(any.immutable());
        queue.add(any.immutable());
        while (!queue.isEmpty() && seen.size() <= MAX_CELLS) {
            BlockPos at = queue.poll();
            out.add(at);
            for (Direction d : Direction.values()) {
                BlockPos n = at.relative(d);
                if (seen.contains(n)) continue;
                if (!level.getBlockState(n).is(Blocks.NETHER_PORTAL)) continue;
                seen.add(n.immutable());
                queue.add(n.immutable());
            }
        }
        out.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY())
                : a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX())
                : Integer.compare(a.getZ(), b.getZ()));
        return out;
    }

    /**
     * Can a body stand with its feet here — the pathfinder's own predicate, read off the level.
     *
     * <p>Deliberately the same four questions {@code WorldView.canStandAt} asks on
     * {@code LevelWorldView}: a solid non-hazard floor, a passable non-hazard foot, a passable
     * non-hazard head. A doorstep chosen by a LOOSER rule is a doorstep A* will refuse to walk to,
     * and the rung would then be retrying a goal for a different reason than the one it just fixed.
     *
     * <h2>Two other "standable" predicates live in this package, and none of the three may be
     * swapped for another</h2>
     *
     * They ask overlapping but different questions, because they are asked for different purposes.
     * Written down because "these look like they should be merged" is exactly how a predicate gets
     * reused into a scene it was never meant to judge:
     *
     * <ul>
     *   <li><b>This one</b> — the only one that asks about HAZARDS (lava/fire under, at and above
     *       the foot). It says nothing about water: a doorstep in shallow water is walkable and A*
     *       agrees, so refusing it would put this out of step with the planner it exists to match.</li>
     *   <li><b>{@code JourneyEndRungs.standingCellInTheRoom}</b> — the only one that refuses a cell
     *       whose FLOOR is fluid. That is not a stricter version of this: it is the stronghold's
     *       portal room, where the floor under the frame is vanilla's own lava pool, and a shaft
     *       landing there destroys what the next rung came for. It asks nothing about fire, because
     *       there is none there.</li>
     *   <li><b>{@code JourneyFill.pickStation}</b> — the only one that uses {@code getCollisionShape}
     *       rather than {@code blocksMotion}, and the only one that refuses fluid at the HEAD. It is
     *       picking a place to STAND AND AIM A BUCKET, so a cell a body could technically occupy but
     *       not work from is no use to it.</li>
     * </ul>
     *
     * <p>The rule of thumb: this one is "will A* walk to this cell", the room one is "can this cell
     * be dug", the station one is "can work be done from here". A caller that wants one of those
     * three questions must
     * call the one that asks it, not the nearest one to hand.
     */
    public static boolean standable(ServerLevel level, BlockPos foot) {
        BlockPos below = foot.below();
        if (!level.getBlockState(below).blocksMotion() || hazard(level, below)) return false;
        if (level.getBlockState(foot).blocksMotion() || hazard(level, foot)) return false;
        BlockPos head = foot.above();
        return !level.getBlockState(head).blocksMotion() && !hazard(level, head);
    }

    private static boolean hazard(ServerLevel level, BlockPos p) {
        return level.getFluidState(p).is(FluidTags.LAVA) || level.getBlockState(p).is(BlockTags.FIRE);
    }

    /** Why {@link #standable} said no, in the order it asked — or "standable". */
    private static String why(ServerLevel level, BlockPos foot) {
        if (level.getBlockState(foot).blocksMotion()) return "occupied";
        if (hazard(level, foot)) return "lava or fire";
        BlockPos below = foot.below();
        if (!level.getBlockState(below).blocksMotion())
            return "not solid underfoot(" + level.getBlockState(below).getBlock() + ")";
        if (hazard(level, below)) return "lava or fire underfoot";
        BlockPos head = foot.above();
        if (level.getBlockState(head).blocksMotion())
            return "head cell occupied(" + level.getBlockState(head).getBlock() + ")";
        if (hazard(level, head)) return "lava or fire at the head";
        return "standable";
    }

    /**
     * Can a body OCCUPY this portal cell — the cell itself plus the head cell above it.
     *
     * <p>This clause is what makes "find the row that is open" wrong rather than merely incomplete.
     * A portal interior is three cells tall and a
     * body is 1.8, so the TOP row's head is the frame's obsidian cap: a body pushed at it walks to
     * {@code cellZ − 0.3} and stops, which without this clause no message in this rung could tell
     * apart from "did not move at all". Vanilla's own portals are entered at the bottom or middle row for the
     * same reason.
     */
    public static boolean enterable(ServerLevel level, BlockPos cell) {
        if (!level.getBlockState(cell).is(Blocks.NETHER_PORTAL)) return false;
        BlockPos head = cell.above();
        return !level.getBlockState(head).blocksMotion() && !hazard(level, head);
    }

    /**
     * The portal cell this exact cell can step sideways into, or null.
     *
     * <p>Asked again after every leg rather than latched, because "reached the doorstep" is a claim
     * about where the bot ENDED and the walker reports arrival for partial paths. A leg that stopped one cell
     * short of the planned doorstep but landed on a different usable one is a success, and a leg that
     * reports arrival without having moved is not.
     */
    public static BlockPos stepFrom(ServerLevel level, BlockPos stand, BlockPos anyPortalCell) {
        if (stand == null || !standable(level, stand)) return null;
        Set<BlockPos> column = Set.copyOf(cells(level, anyPortalCell));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = stand.relative(d);
            if (column.contains(n) && enterable(level, n)) return n;
        }
        return null;
    }

    /** {@link #find(ServerLevel, BlockPos, BlockPos, boolean)} restricted to a way in that needs no
     *  digging — the question "can the bot walk in right now". */
    public static Doorstep find(ServerLevel level, BlockPos anyPortalCell, BlockPos from) {
        return find(level, anyPortalCell, from, false);
    }

    /**
     * The cheapest way into the portal, or null when there is none.
     *
     * <p>Ranked by <b>blocks that would have to be mined</b> first, then by the lowest entry row,
     * then by distance from the body. Cheapest-first rather than lowest-first because every block
     * this opens is a hole in a wall the rung below built, and "one cobblestone out of the middle
     * row's doorstep" is a smaller change to the world than "two out of the bottom row's". Lowest
     * breaks the tie because entering low means walking into the cell the body ends up in, while
     * entering high means falling down the column — one more thing that can end somewhere unplanned.
     *
     * @param mayDig allow candidates whose doorstep is currently blocked by {@link #diggable} rock
     */
    public static Doorstep find(ServerLevel level, BlockPos anyPortalCell, BlockPos from, boolean mayDig) {
        Doorstep best = null;
        long bestDist = Long.MAX_VALUE;
        for (BlockPos cell : cells(level, anyPortalCell)) {
            if (!enterable(level, cell)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos stand = cell.relative(d);
                if (level.getBlockState(stand).is(Blocks.NETHER_PORTAL)) continue;
                List<BlockPos> clear = clearFor(level, stand, mayDig);
                if (clear == null) continue;
                long dist = from == null ? 0L : (long) stand.distSqr(from);
                if (best != null) {
                    if (clear.size() > best.clear().size()) continue;
                    if (clear.size() == best.clear().size()) {
                        if (cell.getY() > best.cell().getY()) continue;
                        if (cell.getY() == best.cell().getY() && dist >= bestDist) continue;
                    }
                }
                best = new Doorstep(stand.immutable(), cell.immutable(), clear);
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * What would have to be removed for {@code stand} to be a place this body can stand — empty when
     * it already is, null when no amount of digging would do it.
     *
     * <p>The FLOOR is never in the list. This rung carries no building material it can spare and has
     * no reason to: a doorstep with nothing under it is a different candidate's problem, not a hole
     * to be filled. The head cell is, because a one-cell-high gap in front of a portal is exactly
     * what the casting's slag leaves and exactly what a body cannot walk through.
     */
    private static List<BlockPos> clearFor(ServerLevel level, BlockPos stand, boolean mayDig) {
        BlockPos below = stand.below();
        if (!level.getBlockState(below).blocksMotion() || hazard(level, below)) return null;
        List<BlockPos> clear = new ArrayList<>(2);
        for (BlockPos c : List.of(stand, stand.above())) {
            if (hazard(level, c)) return null;
            if (!level.getBlockState(c).blocksMotion()) continue;
            if (!mayDig || !diggable(level, c)) return null;
            clear.add(c.immutable());
        }
        return clear;
    }

    /**
     * Soft enough to open with what this rung is carrying, and not the portal's own frame.
     *
     * <p>The obsidian refusal is the load-bearing half. Every cell of the frame borders the doorway,
     * so the frame is always the geometrically cheapest thing to remove — and removing any of it puts
     * the portal out. A rung that mined its way in through its own portal would report a green walk
     * and leave the ladder with nothing to walk through.
     */
    private static boolean diggable(ServerLevel level, BlockPos p) {
        if (level.getBlockState(p).is(Blocks.OBSIDIAN)) return false;
        if (level.getBlockState(p).is(Blocks.NETHER_PORTAL)) return false;
        if (!level.getFluidState(p).isEmpty()) return false;
        float hardness = level.getBlockState(p).getDestroySpeed(level, p);
        return hardness >= 0f && hardness <= MAX_DIG_HARDNESS;
    }

    /**
     * Every approach cell of every portal cell, standable or with the reason it is not.
     *
     * <p>The row that was missing. The run this class was written from recorded
     * {@code stand.in = Block{minecraft:air}} beside a failure message about the transfer timer, and
     * nothing anywhere said that the front of the doorway was cobblestone — which is the entire
     * finding. Portal cells are skipped as neighbours: they are the doorway, not a way into it.
     */
    public static String survey(ServerLevel level, BlockPos anyPortalCell) {
        List<BlockPos> column = cells(level, anyPortalCell);
        if (column.isEmpty()) return "the doorway is empty: " + (anyPortalCell == null ? "no cell given"
                : anyPortalCell.toShortString() + " is not nether_portal");
        Set<BlockPos> inside = Set.copyOf(column);
        StringBuilder sb = new StringBuilder(column.size() + " doorway cells");
        for (BlockPos cell : column) {
            sb.append(" | ").append(cell.toShortString())
              .append(enterable(level, cell) ? "(the bot fits)" : "(the bot does not fit: head "
                      + cell.above().toShortString() + "=" + level.getBlockState(cell.above()).getBlock() + ")")
              .append(": ");
            boolean first = true;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = cell.relative(d);
                if (inside.contains(n)) continue;
                if (!first) sb.append(" ");
                first = false;
                sb.append(n.toShortString()).append("=").append(level.getBlockState(n).getBlock())
                        .append("(").append(why(level, n)).append(")");
            }
        }
        return sb.toString();
    }

    // =============================================================== stepping in ====

    /**
     * Hold forward until the body's own cell is a portal block.
     *
     * <p>The move the pathfinder structurally cannot make: its destination has no floor, so
     * {@code canStandAt} refuses it and no {@code Goal} can name it. One block of walking with the
     * aim held is what a player does, and it is all this needs to be.
     *
     * <p>Re-aimed every tick. An aim is an angle, not a target — the body moves while it walks, so a
     * yaw computed once is a yaw for where the body used to be.
     *
     * <p><b>It releases the sneak the walk left behind, and that one line is the difference between
     * this working and not working at all.</b> {@code WalkerTickDrive} brakes into its goal with
     * {@code avatarSneak(a, brakeSneak)} + {@code p.setShiftKeyDown(brakeSneak)}, and nothing clears
     * either when the process ends — {@code ServerPlayerBody.step} re-applies {@code pendingSneak}
     * on every step, so the flag survives into whatever runs next. Vanilla's
     * {@code Player.maybeBackOffFromEdge} refuses to let a shifting body walk off ANY edge, and a
     * portal cell with no floor is precisely an edge. Measured in
     * {@code wd.portalEntryStepsInFromTheOpenRow} before this line: the walk arrived at the doorstep
     * in 11 ticks, then sixty ticks of held forward moved the body zero cells.
     */
    public static BotProcess stepInto(BlockPos cell, int ticks) { return new StepInto(cell, ticks); }

    private static final class StepInto implements BotProcess {
        private final BlockPos cell;
        private final int ticks;
        private int elapsed;

        StepInto(BlockPos cell, int ticks) { this.cell = cell; this.ticks = ticks; }

        @Override public String kind() { return "portalStepIn"; }
        @Override public String failure() { return null; }

        @Override public void attach(BotState st) { }

        @Override
        public boolean tick(Body a, WorldView w, BotState st) {
            LivingEntity p = a.entity();
            if (p != null && p.level().getBlockState(p.blockPosition()).is(Blocks.NETHER_PORTAL)) {
                // In. Stop pushing at once — the hold that follows is what re-arms vanilla's
                // one-tick portal flag, and a body still walking would drift out of the column it
                // just fell into.
                a.commandMove(0, 0);
                return true;
            }
            a.aimAtBlock(cell);
            a.commandJump(false);
            a.hands().ifPresent(h -> h.breakHold(false));
            // The brake the arriving walk latched, every tick, because step() re-applies it every
            // tick. See the javadoc: a shifting body will not step off a ledge, and this move is one.
            a.commandSneak(false);
            if (p != null) p.setShiftKeyDown(false);
            a.commandForward(1f);
            return ++elapsed >= ticks;
        }
    }

    // =================================================================== the rung ====

    /** Walk legs to the doorstep, and how long each may take. */
    private static final int DOOR_LEGS = 6;
    private static final int DOOR_LEG_TICKS = 400;

    /** One push across the last block. Twelve ticks is the walk; the rest is headroom for a body
     *  that has to shuffle off a corner first. */
    static final int STEP_IN_TICKS = 60;

    /** One cell of the doorstep. Generous because a cell out of arm's reach is walked to. */
    private static final int CLEAR_TICKS = 400;

    /** Physics after a walk leg, so a body that stepped off a ledge is on the floor before anything
     *  reads where it is. Ten covers a two-block drop with room to spare. */
    private static final int LAND_TICKS = 10;

    /** How many legs a body gets to be taken by a portal it is standing in, and how long each is.
     *  Eight of 150 ticks is 1200 — a player's own wait is 80 at most and 1 for an invulnerable
     *  body, so this is more than an order of magnitude of headroom. */
    private static final int PORTAL_LEGS = 8;
    private static final int PORTAL_LEG_TICKS = 150;

    /**
     * What the whole entry has actually spent and seen.
     *
     * <p>Threaded through every leg because both failure messages quote it, and a message that
     * quotes a BUDGET instead of a count is a message that can be — and was — wrong: a
     * budget-quoting message reported "1200 ticks" on a scene whose own total was 451.
     */
    private static final class Attempt {
        int waited;
        boolean everInPortal;
        int tickedFrom = -1;
        int stillLegs;
    }

    /**
     * One crossing: the world the body is LEAVING, and what happens once it is out.
     *
     * <p>These two are the only direction-specific things in this whole file. Everything else — the
     * doorway survey, choosing a doorstep, digging one open, the walk legs, the push across the last
     * block, the hold under {@link HoldStill}, the drift recovery, and both failure messages — is
     * geometry and driving that a body going the other way needs identically.
     *
     * <p>Naming the departure world rather than the arrival one is deliberate: {@code changeDimension}
     * can drop a body somewhere unexpected ({@code changed-worlds-but-not-places}), so "is it still
     * where it started" is the one question that stays true no matter where it lands, and the
     * arrival assertions belong to the caller that knows what it was hoping for.
     */
    public record Crossing(String from, Runnable onCrossed) {}

    /**
     * Step through and assert the body actually MOVED, not merely that the dimension changed.
     *
     * <p>{@code wd.serverEntersTheNether} exists because that distinction was worth a bug: vanilla
     * delivers the destination through {@code connection.teleport}, both loaders' fake players used
     * to swallow it, and the body arrived in the Nether holding its overworld coordinates — 87 501
     * blocks out, above the roof, standing on air, while a dimension check passed. So this rung
     * checks the 8:1 scaling too. If it ever regresses, the fortress rung above would search a world
     * nobody is standing in.
     */
    static void nether(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.NETHER);
        BlockPos portal = rig.nearestBlock("minecraft:nether_portal", 24);
        rig.evidence("portal.found", portal == null ? "none" : portal.toShortString());
        if (portal == null) {
            ctx.fail("no portal block within 24 blocks: PORTAL_LIT reported the portal lit, but none"
                    + " is found here, so one of the two is wrong (bot at "
                    + rig.player().blockPosition() + ")");
            return;
        }
        final BlockPos from = rig.player().blockPosition();
        crossThrough(ctx, rig, portal, new Crossing("minecraft:overworld",
                () -> arrivedInTheNether(ctx, rig, from)));
    }

    /**
     * Get this body through that portal, from wherever it is standing, and run {@code onCrossed}
     * once it is out — or fail here, saying which of the two failures it was.
     *
     * <p><b>Shared because a second copy gets it wrong again.</b> Rung 17's walk home from the
     * Nether, written separately, ended with an eight-line "settle onto the portal cell, then
     * {@code await} for the dimension to change", which is the exact defect this rung's first three
     * executions had: an
     * {@code IntentProcess} already at its goal finishes on tick one, {@code settle} unregisters the
     * driver the moment it does, and a body nobody ticks never calls {@code move()} — the only thing
     * that re-arms vanilla's one-tick portal flag. Rung 17's run of 2026-08-22 stood inside a
     * {@code nether_portal} block at {@code 105,93,7} for 1600 ticks and was never taken:
     *
     * <pre>{@code
     * return.portalAfterClimb = 105, 93, 7   ← it found the door and stood in it
     * return.at               = 105, 93, 7   ← and was still there, still in the_nether
     * }</pre>
     *
     * <p>A second copy of a driving loop is a second copy of every bug the first one has already
     * paid for. Both directions now share this one, so rung 17 also inherits what it had never
     * asked for: a doorway survey, digging a blocked doorstep open, a body that drifts out being
     * walked back in, and — the reading that made the original diagnosis possible —
     * {@code portal.ticked}, which separates "nobody was pushing it" from "the timer did not run".
     */
    public static void crossThrough(SceneContext ctx, JourneyRig rig, BlockPos portal,
                                    Crossing crossing) {
        ServerLevel level = rig.player().serverLevel();
        final BlockPos from = rig.player().blockPosition();
        rig.evidence("portal.expectedTicks", portalDelay(rig));
        rig.evidence("portal.doorway", survey(level, portal));
        Attempt attempt = new Attempt();
        // Walk in if anything is open; otherwise take the cheapest way in that a dig would make. The
        // two are asked separately so the evidence says which one this run needed.
        Doorstep walkIn = find(level, portal, from);
        Doorstep door = walkIn != null ? walkIn : find(level, portal, from, true);
        rig.evidence("portal.doorstep", door == null
                ? "none: no doorway cell both fits the bot and has a standing cell beside it (see portal.doorway)"
                : "stand at " + door.stand().toShortString() + ", step into " + door.cell().toShortString()
                        + " (doorway row " + (door.cell().getY() - portal.getY() + 1) + ")"
                        + (door.clear().isEmpty() ? ", can walk in now" : ", dig out " + door.clear() + " first"));
        if (door == null) {
            neverGotIn(ctx, rig, portal, attempt,
                    "no doorway cell both fits the bot and has a standing cell beside it");
            return;
        }
        if (!door.clear().isEmpty()) {
            clearTheDoorway(ctx, rig, portal, door, crossing, attempt, 0);
            return;
        }
        rig.attempting("walk to the doorstep " + door.stand().toShortString() + " and step into the portal");
        walkToTheDoorstep(ctx, rig, portal, door, crossing, attempt, DOOR_LEGS);
    }

    /**
     * Open the doorstep the rung chose, one cell at a time, then look again.
     *
     * <p>{@code mineCellOrGiveUp} rather than {@code mineBlock}: a cell that cannot be reached must
     * cost this leg and not the rung, because the reading that matters afterwards is the WORLD —
     * whether the cell opened — and a framework timeout produces no reading at all. The doorstep is
     * re-derived from scratch after the digging rather than assumed, so a dig that opened something
     * other than what it aimed at cannot be mistaken for one that worked.
     */
    private static void clearTheDoorway(SceneContext ctx, JourneyRig rig, BlockPos portal,
                                        Doorstep door, Crossing crossing, Attempt attempt,
                                        int done) {
        ServerLevel level = rig.player().serverLevel();
        if (done < door.clear().size()) {
            BlockPos cell = door.clear().get(done);
            rig.attempting("dig out the doorstep cell " + cell.toShortString() + " (cell " + (done + 1) + "/"
                    + door.clear().size() + ")");
            final long began = level.getGameTime();
            rig.mineCellOrGiveUp(cell, CLEAR_TICKS, () -> {
                ServerLevel now = rig.player().serverLevel();
                attempt.waited += (int) (now.getGameTime() - began);
                rig.evidence("portal.clear." + done, cell.toShortString() + " → "
                        + now.getBlockState(cell).getBlock() + " (bot at "
                        + rig.player().blockPosition().toShortString() + ", canBreak="
                        + rig.body().avatar().canBreak(cell) + ")");
                clearTheDoorway(ctx, rig, portal, door, crossing, attempt, done + 1);
            });
            return;
        }
        Doorstep open = find(level, portal, rig.player().blockPosition());
        rig.evidence("portal.doorstep.after", open == null
                ? "still no doorstep to walk in from after digging: " + survey(level, portal)
                : "stand at " + open.stand().toShortString() + ", step into " + open.cell().toShortString());
        if (open == null) {
            neverGotIn(ctx, rig, portal, attempt,
                    "after digging out " + door.clear() + " the doorway still has no standing cell to"
                            + " walk in from (see portal.clear.*)");
            return;
        }
        rig.attempting("walk to the doorstep " + open.stand().toShortString() + " and step into the portal");
        walkToTheDoorstep(ctx, rig, portal, open, crossing, attempt, DOOR_LEGS);
    }

    /**
     * The goal one walk leg asks for — {@code Goal.XZ} on a flat turn, {@code Goal.Block} otherwise,
     * <b>except that a column the body is already standing in is not a question.</b>
     *
     * <p>The legs alternate SHAPE because a retry that asks the identical question gets the identical
     * answer. But {@code Goal.XZ} {@link Goal#ignoresY() ignores Y by construction}, so a body one row
     * ABOVE the doorstep, in its column, satisfies it where it stands: the walker's own
     * {@code goal.reached(foot)} fires on tick one, it returns ARRIVED having moved nothing, and the
     * leg is spent asking something whose answer was already yes. Measured on the ladder
     * (2026-08-20 15:20), doorstep {@code 3,57,20}, body {@code 3,58,20}:
     *
     * <pre>
     * portal.walk.1 = XZ goal 3, 57, 20: 2, 58, 20 → 3, 58, 20 (moved 1 block) end=arrived
     * portal.walk.3 = XZ goal 3, 57, 20: 3, 58, 20 → 3, 58, 20 (moved 0 blocks) end=arrived
     * </pre>
     *
     * {@code walk.3} is a no-op that reports success AND counts toward the two-still-legs terminator,
     * so half the budget was being spent on a shape that could not express "and be on that row".
     *
     * <p><b>The alternation is kept, not replaced.</b> A body that is NOT in the column still gets the
     * XZ leg — that is the case the alternation was adopted for, where dropping the Y requirement is a
     * genuinely different and easier question. Only the degenerate turn is converted. Adding a THIRD
     * shape would have left the degenerate one in the rotation.
     */
    static Goal legGoal(BlockPos here, BlockPos stand, boolean flatTurn) {
        Goal.XZ column = new Goal.XZ(stand.getX(), stand.getZ(), 0);
        return flatTurn && here != null && !column.reached(here) ? column : new Goal.Block(stand);
    }

    /** What {@link #legGoal} actually picked, for the evidence row — a walk that says "XZ" when it
     *  asked a 3D question is a row that will mislead the next reader the way {@code walk.3} did. */
    static String legShape(BlockPos here, BlockPos stand, boolean flatTurn) {
        Goal picked = legGoal(here, stand, flatTurn);
        if (picked instanceof Goal.XZ) return "XZ";
        return flatTurn ? "3D (this turn would ask XZ, but the bot is already in that column, so XZ"
                + " cannot ask anything new)" : "3D";
    }

    /**
     * Walk to the doorstep, re-planning from wherever each leg ends, <b>alternating the goal
     * SHAPE</b>.
     *
     * <p>A retry that asks the identical question gets the identical answer — this ladder has already
     * spent one run on ninety repeats of one 22-block query and another on eight repeats of this one.
     * {@code Goal.XZ} ignores Y and asks only for the column; {@code Goal.Block} is 3D and asks for
     * the row as well. Either shape alone has been observed to stall, so the rounds alternate, and
     * two consecutive legs that move the body zero cells end the walk rather than spend the rest of
     * the budget re-asking.
     *
     * <p>The shape each leg actually asks for comes from {@link #legGoal}, which drops a flat turn
     * whose column the body is ALREADY standing in — that turn is a no-op that reports arrival, and
     * it counts toward the still-leg terminator. See that method for the ladder rows.
     */
    private static void walkToTheDoorstep(SceneContext ctx, JourneyRig rig, BlockPos portal,
                                          Doorstep door, Crossing crossing, Attempt attempt,
                                          int left) {
        if (rig.lostTheWorld() != null) {
            neverGotIn(ctx, rig, portal, attempt, "the bot has already fallen out of the world");
            return;
        }
        ServerLevel level = rig.player().serverLevel();
        final BlockPos here = rig.player().blockPosition();
        BlockPos ready = stepFrom(level, here, portal);
        if (ready != null) { stepIn(ctx, rig, portal, ready, crossing, attempt); return; }
        if (left <= 0) {
            neverGotIn(ctx, rig, portal, attempt,
                    "none of " + DOOR_LEGS + " walks reached the doorstep " + door.stand().toShortString());
            return;
        }
        final boolean flatTurn = (left % 2) == 0;
        Goal goal = legGoal(here, door.stand(), flatTurn);
        final String shape = legShape(here, door.stand(), flatTurn);
        final long began = level.getGameTime();
        final int leg = DOOR_LEGS - left + 1;
        rig.settle(new IntentProcess(new Intent(goal)), DOOR_LEG_TICKS,
                () -> rig.settle(new HoldStill(LAND_TICKS), LAND_TICKS + 10, () -> {
            // LET IT LAND BEFORE JUDGING IT. The last edge into a doorstep one row down is a step
            // off a ledge, and `settle` ends on the tick the process reports finished — which is
            // while the body is still in the air over the cell it is arriving at. Measured in
            // wd.portalEntryDigsIntoTheRowItFits: the walk ended `path-consumed` with the body
            // reading one row ABOVE its goal, and the doorstep test then said "cannot step in from
            // here" about a cell the bot was a tenth of a second from standing on. Nothing but a
            // tick of physics answers that, and this rig only ticks a bot while a process is registered.
            ServerLevel now = rig.player().serverLevel();
            int spent = (int) (now.getGameTime() - began);
            attempt.waited += spent;
            BlockPos at = rig.player().blockPosition();
            int moved = here.distManhattan(at);
            rig.evidence("portal.walk." + leg, shape + " goal "
                    + door.stand().toShortString() + ": " + here.toShortString() + " → "
                    + at.toShortString() + " (moved " + moved + " blocks, took " + spent + " ticks)"
                    + " " + JourneyLeg.walkerEnd(rig));
            if (moved == 0) attempt.stillLegs++; else attempt.stillLegs = 0;
            if (attempt.stillLegs >= 2) {
                neverGotIn(ctx, rig, portal, attempt, "two consecutive walks (one XZ, one 3D) did not"
                        + " move a single block, stopped at " + at.toShortString()
                        + "; asking again would give the same answer");
                return;
            }
            walkToTheDoorstep(ctx, rig, portal, door, crossing, attempt, left - 1);
        }));
    }

    /** Push across the last block, then hold and let vanilla's timer run. */
    private static void stepIn(SceneContext ctx, JourneyRig rig, BlockPos portal, BlockPos cell,
                               Crossing crossing, Attempt attempt) {
        final BlockPos stand = rig.player().blockPosition();
        final boolean braking = rig.player().isShiftKeyDown();
        rig.attempting("step from " + stand.toShortString() + " into " + cell.toShortString());
        final long began = rig.player().serverLevel().getGameTime();
        rig.settle(stepInto(cell, STEP_IN_TICKS), STEP_IN_TICKS + 20, () -> {
            ServerLevel now = rig.player().serverLevel();
            attempt.waited += (int) (now.getGameTime() - began);
            BlockPos at = rig.player().blockPosition();
            boolean in = now.getBlockState(at).is(Blocks.NETHER_PORTAL);
            // Record the sneak flag too: the brake on the arrival tick is shiftKeyDown=true, and
            // vanilla does not let a sneaking player step off any ledge, so "pushed for 60 ticks and
            // did not move" and "could not move because it was sneaking" look identical otherwise.
            rig.evidence("portal.stepIn", stand.toShortString() + " pushed toward " + cell.toShortString()
                    + " → stopped at " + at.toShortString() + " standing in " + now.getBlockState(at).getBlock()
                    + (in ? " (entered)" : " (did not enter)") + ", shiftKeyDown at the start=" + braking
                    + " (every pushing tick clears it)");
            attempt.tickedFrom = rig.player().tickCount;
            holdInThePortal(ctx, rig, portal, crossing, PORTAL_LEGS, attempt);
        });
    }

    /**
     * Stand in the portal and be taken — and, when the body has drifted out, get it back in the way
     * it got in the first time.
     *
     * <p><b>HOLD the body, do not merely put it there.</b> {@code settle} ends the instant its
     * process reports finished and unregisters the driver, and an {@code IntentProcess} already at
     * its goal finishes on tick one. That is what made the first three executions of this rung run
     * 27 ticks while their message claimed 1200.
     *
     * <p>It matters because of how vanilla notices a portal: {@code Entity.move} to
     * {@code tryCheckInsideBlocks} to {@code NetherPortalBlock.entityInside} to
     * {@code Entity.setAsInsidePortal} to {@code PortalProcessor.setAsInsidePortalThisTick(true)},
     * and {@code processPortalTeleportation} returns immediately unless that flag is set, CLEARING it
     * as it goes. It is a one-tick flag re-armed by a {@code move()} every tick, and this avatar's
     * {@code move()} comes from the {@code travel()} in its own step — which only runs while a
     * process is registered. {@link HoldStill} is the process that does nothing and keeps being
     * ticked, and {@code portal.ticked} is the reading that proves it did.
     */
    private static void holdInThePortal(SceneContext ctx, JourneyRig rig, BlockPos portal,
                                        Crossing crossing, int legs, Attempt attempt) {
        if (!crossing.from().equals(rig.dimension())) {
            // Written on the way OUT too, not only on the two failure paths below. A reading that
            // exists only when the scene fails cannot certify the scene that passes: the run that
            // finally crossed had no `portal.ticked` at all, so "how was the portal crossed this time" had to be
            // reconstructed from heartbeats. Costs one row; buys the pass its own evidence.
            rig.evidence("portal.ticked", ticked(rig, attempt));
            crossing.onCrossed().run();
            return;
        }
        ServerLevel level = rig.player().serverLevel();
        final BlockPos at = rig.player().blockPosition();
        final boolean inPortal = level.getBlockState(at).is(Blocks.NETHER_PORTAL);
        if (inPortal) attempt.everInPortal = true;
        if (legs <= 0) {
            rig.evidence("portal.ticked", ticked(rig, attempt));
            if (attempt.everInPortal) {
                ctx.fail("stood in the portal and was not transferred after waiting " + attempt.waited
                        + " ticks: bot at " + at.toShortString() + ", that cell is "
                        + level.getBlockState(at).getBlock() + ", dimension still " + rig.dimension()
                        + ". The bot was ticked throughout (see portal.ticked), so it was not left"
                        + " unpushed; the transfer timer did not run (expected wait " + portalDelay(rig)
                        + "; see portal.leg.*)");
            } else {
                ctx.fail("the bot never stood in a portal block, waited " + attempt.waited
                        + " ticks in total: last stopped at " + at.toShortString() + " (that cell is "
                        + level.getBlockState(at).getBlock() + "), portal at " + portal.toShortString()
                        + ". This is \"could not reach a portal block\", not \"stood in it and was not"
                        + " transferred\"; the two need fixes in opposite places"
                        + " (see portal.doorway / portal.doorstep / portal.walk.* / portal.leg.*)");
            }
            return;
        }
        final int leg = PORTAL_LEGS - legs;
        BotProcess run;
        String what;
        if (inPortal) {
            run = new HoldStill(PORTAL_LEG_TICKS);
            what = "hold still and wait";
        } else {
            BlockPos ready = stepFrom(level, at, portal);
            if (ready != null) {
                run = stepInto(ready, PORTAL_LEG_TICKS);
                what = "step in from here into " + ready.toShortString();
            } else {
                Doorstep door = find(level, portal, at);
                if (door == null) {
                    neverGotIn(ctx, rig, portal, attempt, "after the bot was pushed out of the doorway,"
                            + " no standable cell remained around it");
                    return;
                }
                boolean flatTurn = (legs % 2) == 0;
                run = new IntentProcess(new Intent(legGoal(at, door.stand(), flatTurn)));
                what = legShape(at, door.stand(), flatTurn) + " walk back to the doorstep "
                        + door.stand().toShortString();
            }
        }
        final long began = level.getGameTime();
        rig.settle(run, PORTAL_LEG_TICKS, () -> {
            ServerLevel now = rig.player().serverLevel();
            int spent = (int) (now.getGameTime() - began);
            attempt.waited += spent;
            BlockPos ended = rig.player().blockPosition();
            rig.evidence("portal.leg." + leg, what + ": " + at.toShortString() + " → "
                    + ended.toShortString() + " standing in " + now.getBlockState(ended).getBlock()
                    + ", dimension " + rig.dimension() + ", this step took " + spent + " ticks");
            if (!inPortal && ended.equals(at)) attempt.stillLegs++; else attempt.stillLegs = 0;
            if (attempt.stillLegs >= 2) {
                rig.evidence("portal.ticked", ticked(rig, attempt));
                neverGotIn(ctx, rig, portal, attempt, "two consecutive steps did not move a single"
                        + " block, stopped at " + ended.toShortString()
                        + "; asking again would give the same answer");
                return;
            }
            holdInThePortal(ctx, rig, portal, crossing, legs - 1, attempt);
        });
    }

    /**
     * The failure for a body that never got into the doorway at all.
     *
     * <p>Kept apart from the "stood in it and was not sent" message on purpose, and worded so the two
     * cannot be confused at a glance: one is answered by looking at {@code Entity.handlePortal}, the
     * other by looking at what is standing in front of the door.
     */
    private static void neverGotIn(SceneContext ctx, JourneyRig rig, BlockPos portal,
                                   Attempt attempt, String why) {
        ServerLevel level = rig.player().serverLevel();
        BlockPos at = rig.player().blockPosition();
        ctx.fail("could not walk into the portal: " + why + ". Bot at " + at.toShortString()
                + " (that cell is " + level.getBlockState(at).getBlock() + "), portal at "
                + portal.toShortString() + ", waited " + attempt.waited + " ticks in total without"
                + " ever standing in a portal block. This is \"could not reach a portal block\", not"
                + " \"stood in it and was not transferred\""
                + " (see portal.doorway / portal.doorstep / portal.walk.*)");
    }

    /** Whether the bot was being ticked while it waited — the one reading that separates "nobody
     *  pushed it" from "the timer did not run", and the reason the transfer branch may say the
     *  second out loud. */
    private static String ticked(JourneyRig rig, Attempt attempt) {
        if (attempt.tickedFrom < 0) return "never reached the hold-in-the-portal phase (the bot had"
                + " not reached the doorstep yet), so there is nothing to report";
        int now = rig.player().tickCount;
        return "during the hold the bot was ticked " + (now - attempt.tickedFrom) + " times (tickCount "
                + attempt.tickedFrom + " → " + now + "); a process was registered throughout, so the"
                + " explanation \"nobody ticked it and baseTick did not run\" is ruled out";
    }

    /** What vanilla will make this body wait, and which gamerule branch decides it. An invulnerable
     *  body takes the CREATIVE branch, so the expected wait is one tick rather than eighty — worth
     *  printing beside any transfer, because "it worked" reads the same on either branch and only
     *  one of them is what the real ladder's body will get. */
    private static String portalDelay(JourneyRig rig) {
        var rules = rig.player().serverLevel().getGameRules();
        boolean creative = rig.player().getAbilities().invulnerable;
        return (creative ? rules.getInt(GameRules.RULE_PLAYERS_NETHER_PORTAL_CREATIVE_DELAY)
                         : rules.getInt(GameRules.RULE_PLAYERS_NETHER_PORTAL_DEFAULT_DELAY))
                + " ticks (invulnerable=" + creative + ", using the "
                + (creative ? "creative" : "default") + " gamerule branch)";
    }

    private static void arrivedInTheNether(SceneContext ctx, JourneyRig rig, BlockPos from) {
        rig.evidence("dimension", rig.dimension());
        BlockPos now = rig.player().blockPosition();
        rig.evidence("arrived.at", now.toShortString());
        rig.evidence("underfoot", String.valueOf(
                rig.player().serverLevel().getBlockState(now.below()).getBlock()));
        int wantX = Math.floorDiv(from.getX(), 8), wantZ = Math.floorDiv(from.getZ(), 8);
        int drift = Math.max(Math.abs(now.getX() - wantX), Math.abs(now.getZ() - wantZ));
        rig.evidence("scaled.expectedXZ", wantX + "," + wantZ + " (drift " + drift + " blocks)");
        ctx.expect(rig.dimension()).as("the body is in the Nether")
                .isEqualTo("minecraft:the_nether");
        ctx.expect(drift).as("it arrived at the 8:1-scaled coordinate, not the raw one")
                .isAtMost(128);
        rig.noteAdvancement("minecraft:story/enter_the_nether");
        // Bank the doorway as DATA, not only as the sentence below. Rung 17 has to come back through
        // it, and the rungs in between wander hundreds of blocks away hunting blazes and endermen —
        // so "where the run came in" has to survive as a coordinate something can walk to.
        JourneyLedger.noteNetherPortal(now);
        rig.reach("entered the Nether through the portal it lit itself, landed at " + now.toShortString()
                + " (overworld portal at " + from.toShortString() + ", expected at "
                + wantX + "," + wantZ + " by 8:1 scaling)");
    }
}
