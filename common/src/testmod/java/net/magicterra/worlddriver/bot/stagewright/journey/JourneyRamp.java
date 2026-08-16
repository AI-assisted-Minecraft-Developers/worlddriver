package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

/**
 * Steps up the inside of the mould's alcove — the thing a brick cannot be.
 *
 * <h2>Why a brick is not enough, and why a tower is not either</h2>
 *
 * <p>The alcove is a hollow box cut out of rock: seven rows tall, and the only floor in it is the
 * untouched rock one row under its bottom. So every cell above that bottom row has AIR underneath,
 * and the rung has said so about the same geometry from three different places —
 * {@code standBehind}'s {@code .noStand}, the pour's stand search and the fill's — all of them
 * ending in <i>「垫不了：… 撑不住 —— 一块砖会悬空」</i>. One brick placed two rows up rests on
 * nothing the body can then walk onto; what the top rows need is a FLIGHT, each step resting on the
 * step before it, walked up one course at a time.
 *
 * <p>The scripted tower ({@code JourneyShaft.ascendByTowering} → {@code TowerProcess}) is the tool
 * that already existed for gaining height, and on this geometry it does not work. Measured twice,
 * verbatim, on the real ladder — the run of 2026-08-16 and the one before it — with the body on dry
 * land, standing, holding the blocks:
 *
 * <pre>
 * cast6.lift#7.climb.1        = -9,57,36 above=air onGround=true water=false
 * cast6.lift#7.climb.1.with   = minecraft:cobblestone ×130
 * cast6.lift#7.climb.1.stalled= stuck (no Y gain in 60t — out of blocks?)
 * cast6.lift#7.climb.1.state  = onGround=true inWater=false y=57.00
 * cast6.lift#7.climb.1.stock  = minecraft:cobblestone ×130
 * cast6.lift#7.gained         = 1/2 block(s)
 * </pre>
 *
 * <p>Sixty ticks, six jump-and-place cycles, and the stock never moves: the placement is refused
 * every time and the builder's own guess ("out of blocks?") names the wrong thing, as it has three
 * times before in this repo. The one course it did gain, {@code climb.0}, was the course the body
 * spent IN WATER — where buoyancy lifts a body whether or not a block goes under it.
 *
 * <p>So this does not jump. Each step is placed by hand through {@code useItemOn} — the same call
 * {@link JourneyStairs#placeInto} makes, which this rung has already measured working in this very
 * alcove ({@code cell.4.step = -8,56,37 垫一格 … → 站得住了（cobblestone）}) — and then the body
 * WALKS up one ordinary +1 step. Nothing here is a {@code setBlock}, and every course reads its
 * result back off the world: a placement can be refused for reasons the caller cannot see, and a
 * step that was never there is exactly the「爬升就是不涨」row this rung has been misled by twice.
 *
 * <h2>The flight is planned before a single block is spent</h2>
 *
 * <p>A greedy staircase gets stuck on arithmetic nobody notices until a run is gone: the corridor is
 * five cells wide and {@code push} deep, so a straight diagonal run out of a MIDDLE column falls off
 * its own edge before it has risen four ({@code 0 → ±3} with only {@code ±2} to spend). The plan is
 * therefore a search — down from the landing, one row at a time, over the corridor's own floor plan,
 * turning corners between the two ranks when the width runs out. It costs one block per course,
 * because a step only has to be steppable, not to be a solid wedge.
 *
 * <p>Every cell of it is checked before anything is placed: inside the corridor, feet and head clear,
 * something to click against, and never a cell of the descent flight ({@link JourneyStairs#cells}) —
 * that flight is the only way back up to the lava, and a step built into it would seal the rung's own
 * route home.
 */
final class JourneyRamp {

    private JourneyRamp() {}

    /**
     * Cells this rung has deliberately placed as steps.
     *
     * <p>Read by {@code tidyTheAlcove} and {@code clearPourLine}, both of which exist to remove
     * blocks that arrived in the alcove by ACCIDENT — the columns {@code MineProcess} pillars up
     * while reaching a cell over head height. A step is the opposite of that: it is the floor the
     * next pour stands on, and sweeping it is what leaves the top rows unreachable again one cell
     * later. Kept as a set rather than re-derived, for the same reason {@code forgeStuck} is: "solid
     * and the rung put it there" is not a question a block id can answer.
     */
    private static final Set<BlockPos> steps = new LinkedHashSet<>();

    /** Is this one of the steps this rung built on purpose? */
    static boolean isStep(BlockPos c) { return steps.contains(c); }

    /** Forget the previous mould's flight. Called where {@code forgeCorridor} is replaced — a step
     *  set that outlives its corridor would exempt a cell of the NEW alcove from every sweep. */
    static void reset() { steps.clear(); }

    /** How many courses one flight may be. Six is the alcove's full height, so a request past it is
     *  arithmetic gone wrong rather than a tall staircase. */
    private static final int MAX_COURSES = 6;

    /** How long one course's walk gets. A course is one +1 step onto the cell next door. */
    private static final int STEP_WALK_TICKS = 300;

    /**
     * Build a flight up to {@code landing} and walk the body onto it.
     *
     * <p>{@code landing} is where the body's FEET should end up — the caller has already decided
     * that, usually by asking which column's eye can see the cell it is about to pour into. Best
     * effort, and loudly: the body ends wherever the flight got to, {@code .rampedY} says how far
     * that was, and the caller's own ray gate is what decides whether it was far enough. This never
     * refuses to pour, and it never claims a course it did not build.
     */
    static void buildTo(JourneyRig rig, Set<BlockPos> corridor, BlockPos landing, String tag,
                        Runnable then) {
        buildTo(rig, corridor, landing, false, tag, then);
    }

    /**
     * As above, with {@code exactRow} deciding what "already there" means.
     *
     * <p><b>A body ABOVE its landing is not a body on it, and only the caller knows whether that
     * matters.</b> A POUR aims at the target's backing and is genuinely served from any row high
     * enough, so it keeps the {@code >=}. A SCOOP is not: its column is verified by
     * {@code JourneyPortalRung#raiseColumn} for ONE row — the eye is placed at exactly that row's
     * foot — so a body one row up fires a line nobody checked.
     *
     * <p>Measured on the real ladder of 2026-08-16, cell six of an {@code east} mould. The column
     * {@code 3,19} verified for {@code wantY=58}; {@code walkToColumn} is a {@code Goal.XZ} and has no
     * opinion about the row, so it delivered the body to {@code y=59}
     * ({@code recover6.rise.raisedY = 59/58}); this method then returned on {@code >=} without
     * building anything, which is why <b>no {@code .ramp.*} row exists in that run at all</b>. From one
     * row up, the line into the water at {@code 4,59,19} enters the frame cell {@code 4,60,19} and the
     * fill correctly refuses to break a frame — so the run reported {@code frameStuck} and the frame
     * took the blame for a row the body should never have been standing on. The landing itself was
     * free and merely floorless ({@code standToFill} vetoed 13 candidates as {@code 脚下不实心} against
     * 17 as {@code 落脚格被占}), which is exactly the work this flight exists to do.
     */
    static void buildTo(JourneyRig rig, Set<BlockPos> corridor, BlockPos landing, boolean exactRow,
                        String tag, Runnable then) {
        ServerLevel level = rig.ctx().level();
        BlockPos here = rig.player().blockPosition();
        if (exactRow ? here.getY() == landing.getY() : here.getY() >= landing.getY()) {
            then.run();
            return;
        }
        int floorY = floorOf(corridor);
        int courses = landing.getY() - floorY;
        if (corridor.isEmpty() || courses <= 0 || courses > MAX_COURSES) {
            rig.evidence(tag + ".noFlight", landing.toShortString() + " 要 " + courses
                    + " 级（壁龛地板 y=" + floorY + "，" + corridor.size() + " 格）—— 不在能修的范围里");
            then.run();
            return;
        }
        List<BlockPos> flight = plan(level, corridor, floorY, landing);
        if (flight == null) {
            // WHICH CLAUSE, not just "no" — see whyNoFlight. The four that can refuse a landing want
            // completely different work, and answering all of them with the route sentence is how a
            // row ends a search in the wrong place.
            rig.evidence(tag + ".noFlight", landing.toShortString() + " 修不出楼梯："
                    + whyNoFlight(level, corridor, floorY, landing));
            then.run();
            return;
        }
        rig.evidence(tag + ".flight", flight.size() + " 级：" + describe(flight)
                + "（壁龛地板 y=" + floorY + "，身体 " + here.toShortString() + "）");
        approach(rig, corridor, flight, () -> lay(rig, flight, 0, landing, tag, then));
    }

    /**
     * Stand BESIDE the bottom step before laying it.
     *
     * <p>A block cannot be placed into the cell a body is standing in — vanilla's own
     * {@code isUnobstructed} refuses it — and the first version of this walked onto each step before
     * laying the next, which put the body in exactly that cell often enough to lose a course.
     * Measured, rehearsal 2026-08-16: {@code cell.6.ramp.step.1 = -8,57,37 垫不上（现在是 air，
     * 六邻没有能贴的实心面？），身体 -8,57,37} — the body WAS the obstruction, and the row it printed
     * blamed the walls.
     *
     * <p>So the body works from the floor: {@link #lay} places every step it can reach from where it
     * stands and only then climbs. Nothing here is a walk the flight needs; it is a walk that makes
     * the flight buildable.
     */
    private static void approach(JourneyRig rig, Set<BlockPos> corridor, List<BlockPos> flight,
                                 Runnable then) {
        ServerLevel level = rig.ctx().level();
        BlockPos support = flight.get(0).below();
        if (level.getBlockState(support).blocksMotion()) { then.run(); return; }
        BlockPos from = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = support.relative(d);
            if (standable(level, corridor, n) && level.getBlockState(n.below()).blocksMotion()) {
                from = n;
                break;
            }
        }
        if (from == null || rig.player().blockPosition().equals(from)) { then.run(); return; }
        walkTo(rig, from, then);
    }

    /** One leg of ordinary walking inside the alcove. NoBreak throughout: the tallest thing on any
     *  route down here is the mould this rung is building, and a walker sent at a cell it cannot
     *  reach eats it — see {@code reopen}'s note for the run that lost three cast cells that way. */
    private static void walkTo(JourneyRig rig, BlockPos spot, Runnable then) {
        rig.settle(new IntentProcess(new Intent(new Goal.Block(spot), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), STEP_WALK_TICKS,
                () -> rig.settle(new HoldStill(10), 30, then));
    }

    /**
     * Lay every step within arm's length, then climb what was laid and lay the rest.
     *
     * <p>Arm's length is the same {@link JourneyStairs#MEND_REACH} the stair mend and the backing
     * mend run on, and for the same reason: {@code placeOn} goes straight to
     * {@code gameMode.useItemOn}, which has no reach gate on this avatar, so without it a flight
     * could be built through ten blocks of rock and read as one the body earned.
     *
     * <p>A pass that lays nothing new stops rather than walking again — walking changes nothing when
     * the block that refused is the one the next stand rests on, and this rung has paid for retries
     * that re-asked an unchanged question before.
     */
    private static void lay(JourneyRig rig, List<BlockPos> flight, int from, BlockPos landing,
                            String tag, Runnable then) {
        ServerLevel level = rig.ctx().level();
        BlockPos body = rig.player().blockPosition();
        int laid = from;
        while (laid < flight.size()) {
            BlockPos support = flight.get(laid).below();
            if (level.getBlockState(support).blocksMotion()) { laid++; continue; }
            if (support.equals(body) || support.equals(body.above())) break;
            if (Math.sqrt(body.distSqr(support)) > JourneyStairs.MEND_REACH) break;
            boolean held = rig.body().avatar().holdItem(Items.COBBLESTONE);
            if (held) JourneyStairs.placeInto(level, rig, support);
            // THE WORLD, not the call — the same discipline the stair mend and the backing mend
            // already run on. `placeOn` reports nothing, and a step that was never laid produces the
            // identical row to one that was.
            if (!level.getBlockState(support).blocksMotion()) {
                rig.evidence(tag + ".step." + laid, support.toShortString() + " 垫不上（"
                        + (held ? "现在是 " + level.getBlockState(support).getBlock()
                                + "，六邻没有能贴的实心面" : "手上没有圆石")
                        + "），身体 " + body.toShortString());
                break;
            }
            steps.add(support.immutable());
            laid++;
        }
        if (laid >= flight.size() || laid == from) {
            rig.evidence(tag + ".laid", laid + "/" + flight.size() + " 级垫好了（身体 "
                    + body.toShortString() + "）");
            walkTo(rig, landing, () -> done(rig, landing, tag, then));
            return;
        }
        int next = laid;
        walkTo(rig, flight.get(next - 1), () -> lay(rig, flight, next, landing, tag, then));
    }

    /** The alcove's own floor row — the one course that rests on rock rather than on the course
     *  below it. Derived from the corridor rather than passed in, so it cannot disagree with the
     *  volume the rung actually hollowed. */
    private static int floorOf(Set<BlockPos> corridor) {
        int min = Integer.MAX_VALUE;
        for (BlockPos c : corridor) min = Math.min(min, c.getY());
        return min;
    }

    /**
     * The flight, bottom step first — or null when the corridor cannot hold one.
     *
     * <p>Searched DOWN from the landing, because the landing is the one cell that is not negotiable.
     * Each entry is a cell the body STANDS in; the block that has to go under it is its
     * {@code below()}. Consecutive entries are face-adjacent horizontally and one row apart, which is
     * exactly an ordinary walked step-up — no jump, no tower, nothing that needs {@code onGround} to
     * be trustworthy on this body.
     */
    private static List<BlockPos> plan(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                       BlockPos landing) {
        List<BlockPos> found = new ArrayList<>();
        return walkDown(level, corridor, floorY, landing, found) ? found : null;
    }

    private static boolean walkDown(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                    BlockPos stand, List<BlockPos> found) {
        if (!standable(level, corridor, stand) || !supportable(level, corridor, stand.below()))
            return false;
        if (stand.getY() == floorY + 1) {
            // The bottom course. Its own support rests on the rock under the alcove, and the body
            // steps onto it from the floor — so the only thing left to ask is whether the floor
            // beside it is a cell the body can be standing in when it does. Beside the SUPPORT, not
            // beside the standing cell: a step up starts from the row the block is in, and asking
            // one row too high finds air over air everywhere in a hollow alcove and refuses every
            // flight there is.
            if (!entrance(level, corridor, stand.below())) return false;
            found.add(0, stand);
            return true;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (walkDown(level, corridor, floorY, stand.relative(d).below(), found)) {
                found.add(stand);
                return true;
            }
        }
        return false;
    }

    /** Can a body stand here — inside the corridor, feet and head clear? Fluid is allowed: the
     *  alcove floods with the cast's own water and a step under a puddle is still a step. */
    private static boolean standable(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        return corridor.contains(c) && corridor.contains(c.above())
                && !level.getBlockState(c).blocksMotion()
                && !level.getBlockState(c.above()).blocksMotion();
    }

    /**
     * Can a step go here — already solid, or a corridor cell one cobblestone would fill?
     *
     * <p>Two exclusions, and neither is caution. The descent flight is the rung's only route back to
     * the lava, so a step built into it would wall the body into the mould it is casting. And a
     * fluid SOURCE is never buried: the alcove floods with the cast's own bucket, and that bucket
     * has to be scooped back before the next cell — a cobblestone dropped on the source is a water
     * bucket the rung can no longer recover, which surfaces four steps later as「手上没有水桶」.
     * Flowing water is fair game; it is on its way out anyway.
     */
    private static boolean supportable(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        if (level.getBlockState(c).blocksMotion()) return true;
        return corridor.contains(c) && !JourneyStairs.cells.contains(c)
                && level.getBlockState(c).canBeReplaced() && !level.getFluidState(c).isSource()
                && placeable(level, c);
    }

    /**
     * Is there anything here to click a block against?
     *
     * <p>Asked at PLAN time, and it is the difference between a flight and a wish. A step of a
     * diagonal flight is diagonal to the step below it — never face-adjacent — so nothing the flight
     * itself lays can ever hold the next one up. What holds them is the alcove's own walls: the rock
     * behind the back rank, the frame's plane in front of the near one, the side walls at the
     * corridor's edges, and the floor under the bottom course.
     *
     * <p>Not every cell has one, which is exactly why this is checked rather than assumed. The
     * descent flight cuts a notch through the back wall on its way in, and the two cells behind it
     * are therefore open air: measured, rehearsal 2026-08-16,
     * {@code cell.8.ramp.step.1 = -9,57,36 垫不上（六邻没有能贴的实心面）} with the back wall at
     * {@code -9,57,35} carved by {@code stair.14}. Without this the planner keeps choosing that route
     * and the flight dies two courses up, having spent the walk.
     */
    private static boolean placeable(ServerLevel level, BlockPos c) {
        for (Direction d : Direction.values())
            if (level.getBlockState(c.relative(d)).blocksMotion()) return true;
        return false;
    }

    /** Is there a floor cell beside the bottom step for the body to step up FROM? Asked about the
     *  row the SUPPORT is in — that is the row the body walks in before the first step. */
    private static boolean entrance(ServerLevel level, Set<BlockPos> corridor, BlockPos support) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = support.relative(d);
            if (standable(level, corridor, n) && level.getBlockState(n.below()).blocksMotion())
                return true;
        }
        return false;
    }

    /**
     * Which refusal applies, for a row that would otherwise only say "no".
     *
     * <p>The landing's OWN support is asked separately from the route, and that separation is the
     * whole value of this method. They are the two things a refusal can mean and they want opposite
     * work — a support with nothing to click against wants a different landing, a route that cannot
     * turn wants a wider alcove — and the first version answered both with the route sentence.
     * Rehearsal 2026-08-16 printed「宽度不够转身」five times about landings whose support was simply
     * an air cell facing four more air cells, which is a row that ends a search in the wrong place.
     */
    private static String whyNoFlight(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                      BlockPos landing) {
        if (!corridor.contains(landing) || !corridor.contains(landing.above()))
            return landing.toShortString() + " 不是壁龛格（或头顶那格不是）";
        if (level.getBlockState(landing).blocksMotion())
            return landing.toShortString() + " 被 " + level.getBlockState(landing).getBlock() + " 占着";
        BlockPos support = landing.below();
        if (!supportable(level, corridor, support))
            return "这一格自己的垫脚 " + support.toShortString() + " 垫不了："
                    + whySupport(level, corridor, support);
        return "垫脚有了，但从地板 y=" + floorY + " 一级一级走不到 y=" + landing.getY()
                + "：壁龛五格宽、" + corridor.size() + " 格，每一级只能挪一格，中间某一级没有能贴的墙";
    }

    /** Why one cell cannot hold a step — one sentence per clause, never one for all four. */
    private static String whySupport(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        if (!corridor.contains(c)) return c.toShortString() + " 不是壁龛格";
        if (JourneyStairs.cells.contains(c)) return c.toShortString() + " 是下井楼梯的一级，不能堵";
        if (!level.getBlockState(c).canBeReplaced())
            return c.toShortString() + " 是 " + level.getBlockState(c).getBlock() + "，放不进去";
        if (level.getFluidState(c).isSource())
            return c.toShortString() + " 里是源块 —— 埋掉它这一级就收不回水桶了";
        StringBuilder around = new StringBuilder();
        for (Direction d : Direction.values())
            around.append(' ').append(d).append('=').append(level.getBlockState(c.relative(d)).getBlock());
        return c.toShortString() + " 六邻没有能贴的实心面（放方块要贴着一个面点）：" + around;
    }

    private static String describe(List<BlockPos> flight) {
        StringBuilder out = new StringBuilder();
        for (BlockPos s : flight)
            out.append(out.isEmpty() ? "" : " → ").append(s.below().toShortString());
        return out.toString();
    }

    /** How far the flight got, as a fraction — the number to grep across runs. A raise that stopped
     *  one course short and one that never started read alike from a landing height alone. */
    private static void done(JourneyRig rig, BlockPos landing, String tag, Runnable then) {
        BlockPos now = rig.player().blockPosition();
        rig.evidence(tag + ".rampedY", now.getY() + "/" + landing.getY() + "（停在 "
                + now.toShortString() + "，要的落脚格 " + landing.toShortString()
                + (now.getX() == landing.getX() && now.getZ() == landing.getZ() ? "，同一柱"
                        : "，不是同一柱 —— 射线是照那一柱算的") + "）");
        then.run();
    }
}
