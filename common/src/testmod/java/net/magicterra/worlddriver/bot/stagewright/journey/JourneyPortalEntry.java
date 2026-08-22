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
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
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
 * 3,57,19 = cobblestone   3,58,19 = cobblestone   3,59,19 = air (站得住)
 * </pre>
 *
 * The bottom two rows were walled off, and this rung's baseline has {@code allowBreak=false}
 * ({@code BotConfig.applyGameTestBaseline} runs once at server start and this rung never calls
 * {@code JourneyRig.generousPathfinding}), so no route to the bottom cell existed at all. The rung
 * asked for it eight times, got the same answer eight times — {@code portal.leg.0} through
 * {@code portal.leg.7} byte-identical — and then blamed the transfer timer for a body that had never
 * been inside a portal.
 *
 * <h2>The top row is not a way in, and that is why「找到开着的那一排」was not enough</h2>
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
 * 「站进去了没被送走」and「根本没走到门」want opposite fixes — an engine-side look at
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
     * <p>{@code clear} empty means「walk there now」; anything in it is a cell of the alcove wall, in
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
     * <h2>Two other「站得住」live in this package, and none of the three may be swapped for another</h2>
     *
     * They ask overlapping but different questions, because they are asked for different purposes.
     * Written down because「看起来该合」is exactly how a predicate gets reused into a scene it was
     * never meant to judge:
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
     * <p>The rule of thumb: this one is「A* 会不会走到这一格」, the room one is「这一格能不能挖」,
     * the station one is「站这儿能不能干活」. A caller that wants one of those three questions must
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

    /** Why {@link #standable} said no, in the order it asked — or 站得住. */
    private static String why(ServerLevel level, BlockPos foot) {
        if (level.getBlockState(foot).blocksMotion()) return "被占";
        if (hazard(level, foot)) return "有岩浆或火";
        BlockPos below = foot.below();
        if (!level.getBlockState(below).blocksMotion())
            return "脚下不实心(" + level.getBlockState(below).getBlock() + ")";
        if (hazard(level, below)) return "脚下是岩浆或火";
        BlockPos head = foot.above();
        if (level.getBlockState(head).blocksMotion())
            return "头顶被占(" + level.getBlockState(head).getBlock() + ")";
        if (hazard(level, head)) return "头顶是岩浆或火";
        return "站得住";
    }

    /**
     * Can a body OCCUPY this portal cell — the cell itself plus the head cell above it.
     *
     * <p>The clause the first cut of this class did not have, and the one that made「find the row
     * that is open」wrong rather than merely incomplete. A portal interior is three cells tall and a
     * body is 1.8, so the TOP row's head is the frame's obsidian cap: a body pushed at it walks to
     * {@code cellZ − 0.3} and stops, which is a reading no message in this rung distinguished from
     * 「did not move at all」. Vanilla's own portals are entered at the bottom or middle row for the
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
     * <p>Asked again after every leg rather than latched, because「走到了门口」is a claim about where
     * the body ENDED and the walker reports arrival for partial paths. A leg that stopped one cell
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
     *  digging — the question「can the body walk in right now」. */
    public static Doorstep find(ServerLevel level, BlockPos anyPortalCell, BlockPos from) {
        return find(level, anyPortalCell, from, false);
    }

    /**
     * The cheapest way into the portal, or null when there is none.
     *
     * <p>Ranked by <b>blocks that would have to be mined</b> first, then by the lowest entry row,
     * then by distance from the body. Cheapest-first rather than lowest-first because every block
     * this opens is a hole in a wall the rung below built, and「one cobblestone out of the middle
     * row's doorstep」is a smaller change to the world than「two out of the bottom row's」. Lowest
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
        if (column.isEmpty()) return "门洞是空的：" + (anyPortalCell == null ? "没给格子"
                : anyPortalCell.toShortString() + " 不是 nether_portal");
        Set<BlockPos> inside = Set.copyOf(column);
        StringBuilder sb = new StringBuilder(column.size() + " 格门洞");
        for (BlockPos cell : column) {
            sb.append(" | ").append(cell.toShortString())
              .append(enterable(level, cell) ? "(身体进得去)" : "(进不去：头顶 "
                      + cell.above().toShortString() + "=" + level.getBlockState(cell.above()).getBlock() + ")")
              .append("：");
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
     * either when the process ends — {@code ServerPlayerAvatar.step} re-applies {@code pendingSneak}
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

        @Override public void attach(BotState st) { }

        @Override
        public boolean tick(Avatar a, WorldView w, BotState st) {
            Player p = a.player();
            if (p != null && p.level().getBlockState(p.blockPosition()).is(Blocks.NETHER_PORTAL)) {
                // In. Stop pushing at once — the hold that follows is what re-arms vanilla's
                // one-tick portal flag, and a body still walking would drift out of the column it
                // just fell into.
                a.commandMove(0, 0);
                return true;
            }
            a.aimAtBlock(cell);
            a.commandJump(false);
            a.breakHold(false);
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
     * quotes a BUDGET instead of a count is a message that can be — and was — wrong: the run this
     * replaces reported「1200 tick」on a scene whose own total was 451.
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
        rig.evidence("portal.found", portal == null ? "无" : portal.toShortString());
        if (portal == null) {
            ctx.fail("身边 24 格内没有传送门方块 —— PORTAL_LIT 说点着了，这里却找不到，"
                    + "两者必有一个是假的（身体在 " + rig.player().blockPosition() + "）");
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
     * <p><b>Extracted because rung 17 wrote it again and got it wrong again.</b> The walk home from
     * the Nether ended with an eight-line 「settle onto the portal cell, then {@code await} for the
     * dimension to change」, which is the exact defect this rung's first three executions had: an
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
     * {@code portal.ticked}, which separates 「nobody was pushing it」 from 「the timer did not run」.
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
                ? "无 —— 门洞没有一格是身体进得去又有落脚格的（见 portal.doorway）"
                : "站 " + door.stand().toShortString() + " 迈进 " + door.cell().toShortString()
                        + "（门洞第 " + (door.cell().getY() - portal.getY() + 1) + " 排）"
                        + (door.clear().isEmpty() ? "，现在就能走进去" : "，先挖开 " + door.clear()));
        if (door == null) {
            neverGotIn(ctx, rig, portal, attempt, "门洞没有一格是身体进得去又有落脚格的");
            return;
        }
        if (!door.clear().isEmpty()) {
            clearTheDoorway(ctx, rig, portal, door, crossing, attempt, 0);
            return;
        }
        rig.attempting("走到门口 " + door.stand().toShortString() + " 再迈进传送门");
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
            rig.attempting("挖开门口的 " + cell.toShortString() + "（第 " + (done + 1) + "/"
                    + door.clear().size() + " 格）");
            final long began = level.getGameTime();
            rig.mineCellOrGiveUp(cell, CLEAR_TICKS, () -> {
                ServerLevel now = rig.player().serverLevel();
                attempt.waited += (int) (now.getGameTime() - began);
                rig.evidence("portal.clear." + done, cell.toShortString() + " → "
                        + now.getBlockState(cell).getBlock() + "（身体在 "
                        + rig.player().blockPosition().toShortString() + "，canBreak="
                        + rig.body().avatar().canBreak(cell) + "）");
                clearTheDoorway(ctx, rig, portal, door, crossing, attempt, done + 1);
            });
            return;
        }
        Doorstep open = find(level, portal, rig.player().blockPosition());
        rig.evidence("portal.doorstep.after", open == null
                ? "挖完还是没有能走进去的门口：" + survey(level, portal)
                : "站 " + open.stand().toShortString() + " 迈进 " + open.cell().toShortString());
        if (open == null) {
            neverGotIn(ctx, rig, portal, attempt,
                    "挖开了 " + door.clear() + " 之后门洞仍然没有能走进去的落脚格（见 portal.clear.*）");
            return;
        }
        rig.attempting("走到门口 " + open.stand().toShortString() + " 再迈进传送门");
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
     * portal.walk.1 = XZ 目标 3, 57, 20：2, 58, 20 → 3, 58, 20（挪了 1 格）end=arrived
     * portal.walk.3 = XZ 目标 3, 57, 20：3, 58, 20 → 3, 58, 20（挪了 0 格）end=arrived
     * </pre>
     *
     * {@code walk.3} is a no-op that reports success AND counts toward the two-still-legs terminator,
     * so half the budget was being spent on a shape that could not express「and be on that row」.
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

    /** What {@link #legGoal} actually picked, for the evidence row — a leg that says「XZ」when it
     *  asked a 3D question is a row that will mislead the next reader the way {@code walk.3} did. */
    static String legShape(BlockPos here, BlockPos stand, boolean flatTurn) {
        Goal picked = legGoal(here, stand, flatTurn);
        if (picked instanceof Goal.XZ) return "XZ";
        return flatTurn ? "3D（本轮该问 XZ，但身体已经在那一列上，XZ 问不出新东西）" : "3D";
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
            neverGotIn(ctx, rig, portal, attempt, "身体已经掉出世界");
            return;
        }
        ServerLevel level = rig.player().serverLevel();
        final BlockPos here = rig.player().blockPosition();
        BlockPos ready = stepFrom(level, here, portal);
        if (ready != null) { stepIn(ctx, rig, portal, ready, crossing, attempt); return; }
        if (left <= 0) {
            neverGotIn(ctx, rig, portal, attempt,
                    DOOR_LEGS + " 趟都没走到门口 " + door.stand().toShortString());
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
            // reading one row ABOVE its goal, and the doorstep test then said「不能就地迈进」about a
            // cell the body was a tenth of a second from standing on. Nothing but a tick of physics
            // answers that, and this rig only ticks a body while a process is registered.
            ServerLevel now = rig.player().serverLevel();
            int spent = (int) (now.getGameTime() - began);
            attempt.waited += spent;
            BlockPos at = rig.player().blockPosition();
            int moved = here.distManhattan(at);
            rig.evidence("portal.walk." + leg, shape + " 目标 "
                    + door.stand().toShortString() + "：" + here.toShortString() + " → "
                    + at.toShortString() + "（挪了 " + moved + " 格，花了 " + spent + " tick）"
                    + " " + JourneyLeg.walkerEnd(rig));
            if (moved == 0) attempt.stillLegs++; else attempt.stillLegs = 0;
            if (attempt.stillLegs >= 2) {
                neverGotIn(ctx, rig, portal, attempt, "连着两趟（XZ 和 3D 各一趟）一格没挪，停在 "
                        + at.toShortString() + " —— 再问一次也是同一个答案");
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
        rig.attempting("从 " + stand.toShortString() + " 迈进 " + cell.toShortString());
        final long began = rig.player().serverLevel().getGameTime();
        rig.settle(stepInto(cell, STEP_IN_TICKS), STEP_IN_TICKS + 20, () -> {
            ServerLevel now = rig.player().serverLevel();
            attempt.waited += (int) (now.getGameTime() - began);
            BlockPos at = rig.player().blockPosition();
            boolean in = now.getBlockState(at).is(Blocks.NETHER_PORTAL);
            // 潜行位一并记下来：走到位那一 tick 的刹车是 shiftKeyDown=true，而 vanilla 不许潜行的
            // 身体走下任何一个坎 —— 「推了 60 tick 一格没挪」和「推不动因为在潜行」长得一模一样。
            rig.evidence("portal.stepIn", stand.toShortString() + " 推向 " + cell.toShortString()
                    + " → 停在 " + at.toShortString() + " 站的是 " + now.getBlockState(at).getBlock()
                    + (in ? "（进去了）" : "（没进去）") + "，起步时 shiftKeyDown=" + braking
                    + "（推的每一 tick 都会清掉它）");
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
                ctx.fail("站进了传送门，等了 " + attempt.waited + " tick 没被送走：身体在 "
                        + at.toShortString() + "，那一格是 " + level.getBlockState(at).getBlock()
                        + "，维度仍是 " + rig.dimension() + " —— 这一段身体一直在被 tick"
                        + "（见 portal.ticked），所以不是没人推它，是传送计时器没走"
                        + "（该等 " + portalDelay(rig) + "；见 portal.leg.*）");
            } else {
                ctx.fail("身体一次都没站进传送门方块，共等了 " + attempt.waited + " tick：最后停在 "
                        + at.toShortString() + "（那一格是 " + level.getBlockState(at).getBlock()
                        + "），门在 " + portal.toShortString()
                        + " —— 这是「到不了传送门方块」，不是「站进去了没被送走」，两者要修的地方相反"
                        + "（见 portal.doorway / portal.doorstep / portal.walk.* / portal.leg.*）");
            }
            return;
        }
        final int leg = PORTAL_LEGS - legs;
        BotProcess run;
        String what;
        if (inPortal) {
            run = new HoldStill(PORTAL_LEG_TICKS);
            what = "站住等";
        } else {
            BlockPos ready = stepFrom(level, at, portal);
            if (ready != null) {
                run = stepInto(ready, PORTAL_LEG_TICKS);
                what = "就地迈进 " + ready.toShortString();
            } else {
                Doorstep door = find(level, portal, at);
                if (door == null) {
                    neverGotIn(ctx, rig, portal, attempt, "身体被挤出门洞后，门洞四周再没有站得住的落脚格");
                    return;
                }
                boolean flatTurn = (legs % 2) == 0;
                run = new IntentProcess(new Intent(legGoal(at, door.stand(), flatTurn)));
                what = legShape(at, door.stand(), flatTurn) + " 走回门口 " + door.stand().toShortString();
            }
        }
        final long began = level.getGameTime();
        rig.settle(run, PORTAL_LEG_TICKS, () -> {
            ServerLevel now = rig.player().serverLevel();
            int spent = (int) (now.getGameTime() - began);
            attempt.waited += spent;
            BlockPos ended = rig.player().blockPosition();
            rig.evidence("portal.leg." + leg, what + "：" + at.toShortString() + " → "
                    + ended.toShortString() + " 站的是 " + now.getBlockState(ended).getBlock()
                    + "，维度 " + rig.dimension() + "，这一腿 " + spent + " tick");
            if (!inPortal && ended.equals(at)) attempt.stillLegs++; else attempt.stillLegs = 0;
            if (attempt.stillLegs >= 2) {
                rig.evidence("portal.ticked", ticked(rig, attempt));
                neverGotIn(ctx, rig, portal, attempt, "连着两趟一格没挪，停在 " + ended.toShortString()
                        + " —— 再问一次也是同一个答案");
                return;
            }
            holdInThePortal(ctx, rig, portal, crossing, legs - 1, attempt);
        });
    }

    /**
     * The failure for a body that never got into the doorway at all.
     *
     * <p>Kept apart from the「stood in it and was not sent」message on purpose, and worded so the two
     * cannot be confused at a glance: one is answered by looking at {@code Entity.handlePortal}, the
     * other by looking at what is standing in front of the door.
     */
    private static void neverGotIn(SceneContext ctx, JourneyRig rig, BlockPos portal,
                                   Attempt attempt, String why) {
        ServerLevel level = rig.player().serverLevel();
        BlockPos at = rig.player().blockPosition();
        ctx.fail("走不进传送门：" + why + "。身体在 " + at.toShortString() + "（那一格是 "
                + level.getBlockState(at).getBlock() + "），门在 " + portal.toShortString()
                + "，一共等了 " + attempt.waited + " tick，其间一次都没站进过传送门方块"
                + " —— 这是「到不了传送门方块」，不是「站进去了没被送走」"
                + "（见 portal.doorway / portal.doorstep / portal.walk.*）");
    }

    /** Whether the body was being ticked while it waited — the one reading that separates「没人推它」
     *  from「计时器没走」, and the reason the transfer branch may say the second out loud. */
    private static String ticked(JourneyRig rig, Attempt attempt) {
        if (attempt.tickedFrom < 0) return "没进到站桩这一段（身体还没走到门口），无从谈起";
        int now = rig.player().tickCount;
        return "站桩这一段里身体被 tick 了 " + (now - attempt.tickedFrom) + " 次（tickCount "
                + attempt.tickedFrom + " → " + now + "）—— 全程有进程注册着，"
                + "所以「没人 tick 它，baseTick 没跑」这个解释可以排除";
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
                + " tick（invulnerable=" + creative + "，走的是"
                + (creative ? "创造" : "默认") + "那条 gamerule 分支）";
    }

    private static void arrivedInTheNether(SceneContext ctx, JourneyRig rig, BlockPos from) {
        rig.evidence("dimension", rig.dimension());
        BlockPos now = rig.player().blockPosition();
        rig.evidence("arrived.at", now.toShortString());
        rig.evidence("underfoot", String.valueOf(
                rig.player().serverLevel().getBlockState(now.below()).getBlock()));
        int wantX = Math.floorDiv(from.getX(), 8), wantZ = Math.floorDiv(from.getZ(), 8);
        int drift = Math.max(Math.abs(now.getX() - wantX), Math.abs(now.getZ() - wantZ));
        rig.evidence("scaled.expectedXZ", wantX + "," + wantZ + "（漂移 " + drift + " 格）");
        ctx.expect(rig.dimension()).as("the body is in the Nether")
                .isEqualTo("minecraft:the_nether");
        ctx.expect(drift).as("it arrived at the 8:1-scaled coordinate, not the raw one")
                .isAtMost(128);
        rig.noteAdvancement("minecraft:story/enter_the_nether");
        // Bank the doorway as DATA, not only as the sentence below. Rung 17 has to come back through
        // it, and the rungs in between wander hundreds of blocks away hunting blazes and endermen —
        // so "where the run came in" has to survive as a coordinate something can walk to.
        JourneyLedger.noteNetherPortal(now);
        rig.reach("从自己点亮的门走进下界，落在 " + now.toShortString()
                + "（地表门在 " + from.toShortString() + "，按 8:1 应在 "
                + wantX + "," + wantZ + "）");
    }
}
