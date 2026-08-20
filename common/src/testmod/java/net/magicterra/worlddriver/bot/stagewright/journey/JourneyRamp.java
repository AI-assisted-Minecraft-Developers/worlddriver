package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Avatar;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

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
 * something to click against, and never a cell the descent flight needs open
 * ({@link JourneyStairs#needsOpen} — the step, its head room and the clearance a climb jumps through,
 * three per step and not the one {@link JourneyStairs#cells} lists). That flight is the only way back
 * up to the lava, and a step built into it does not merely seal the route home: the flight's own audit
 * finds it, mends it with the pick, and takes this raise out from under the body standing on it.
 *
 * <h2>The flight carries its own wall</h2>
 *
 * <p>It used to need one from the world. {@link #placeable} asked for a solid face beside each step,
 * and in a hollow alcove the top rows have none — which is not a rare shape here, it is the shape.
 * Counted over the eleven archived runs that carry this code, {@code buildTo} was called 63 times and
 * refused 51 of them before laying anything; <b>43 of those 51 printed the same sentence</b>, about
 * the landing's own support and its six air neighbours:
 *
 * <pre>
 * water8.ramp.noFlight = 6, 60, 19 修不出楼梯：这一格自己的垫脚 6, 59, 19 垫不了：
 *                        6, 59, 19 六邻没有能贴的实心面（放方块要贴着一个面点）：
 *                        down=air up=air north=air south=air west=air east=air
 * </pre>
 *
 * <p>A refusal here is not a fallback. The two things behind it are the scripted tower, which stalls
 * on this geometry, and the walker's own Y-level goal, which on the same run put the body seven
 * columns out of the one its aim had been computed for ({@code water8#3.endedIn = 0,19（起塔柱是
 * 6,19 —— 不是同一柱）}) — so the pour or the scoop that follows fires a ray nobody verified.
 *
 * <p>What the old test missed is that a flight is laid BOTTOM-UP, and one cell of it is always
 * face-adjacent to the course below. The steps are not: {@code support(i+1) - support(i)} is a
 * diagonal by construction, which is why a staircase alone can never hold itself up. But the block
 * directly UNDER a step — {@code shoulder = support.below()} — lies in the previous step's own row,
 * one cell along it, and is therefore face-adjacent to it. So the flight hands itself the face it
 * needs: lay the shoulder against the step below, then lay the step against the shoulder. The only
 * course that still borrows a face from the world is the bottom one, and its support rests on the
 * rock under the alcove, so it always has one.
 *
 * <p>Two blocks a course instead of one, out of the ninety-odd cobblestone this rung already carries.
 * Both go into {@link #steps}, because a shoulder is as much floor as the step on top of it and
 * {@code tidyTheAlcove} would otherwise sweep it as a stray pillar.
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
     *
     * <p><b>Membership is an exemption from a SWEEP, not a title deed.</b> {@code tidyTheAlcove} and
     * {@code standBehind}'s litter clear still honour it unconditionally — they are looking for
     * blocks nobody meant to place. {@link JourneySight#blockersOnTheLine} does not: a step standing
     * in the line of a pour that is due now was BORROWED from that pour, and it goes back unless the
     * body is resting on it. The run that named the difference is quoted in JourneySight; in one
     * sentence, the flight filled the cell the next cast had to stand in, and the exemption then
     * stopped anyone taking it out again.
     */
    private static final Set<BlockPos> steps = new LinkedHashSet<>();

    /** Is this one of the steps this rung built on purpose? */
    static boolean isStep(BlockPos c) { return steps.contains(c); }

    /** This cell is no longer a step. Called where one is taken back —
     *  {@link JourneyPortalRung#clearPourLine} hands a borrowed step to the pour whose line it was
     *  standing in. An entry left behind for a cell that is now air would exempt whatever arrives
     *  there next from every sweep in this rung, on the strength of a step that no longer exists. */
    static void forget(BlockPos c) { steps.remove(c); }

    /** Forget the previous mould's flight. Called where {@code forgeCorridor} is replaced — a step
     *  set that outlives its corridor would exempt a cell of the NEW alcove from every sweep. */
    static void reset() { steps.clear(); }

    /** The steps this flight owns right now, for a scene that has to state what it staged. */
    static java.util.Set<BlockPos> stepsNow() { return java.util.Set.copyOf(steps); }

    /** Record a cell as a step of this flight. The one place {@link #steps} grows, so a scene that
     *  stages a FINISHED flight registers it exactly the way {@link #lay} does — the same seam
     *  {@link JourneyStairs#cut} gives the staircase, and for the same reason: a staged fixture that
     *  registered itself by a private back door would be testing its own copy of the bookkeeping. */
    static void laid(BlockPos c) { steps.add(c.immutable()); }

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
     * {@code 3,19} verified for {@code wantY=58}; {@code walkToColumn} is a {@code Goal.XZ}, whose
     * heuristic ignores Y — see {@link net.magicterra.worlddriver.bot.Goal#ignoresY()}, which carries
     * the other half of this account: the same property that lets A* dive for free is what makes such
     * a goal ARRIVE without an opinion about the row. So it delivered the body to {@code y=59}
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
        approach(rig, corridor, flight, tag,
                () -> lay(rig, corridor, flight, 0, landing, false, tag, then));
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
     *
     * <p><b>Off the WHOLE flight, not just its bottom step.</b> The first version asked only that the
     * body not be standing in the cell it was about to fill, and that is one cell of a footprint with
     * many. Measured twice on the pinned east arm, 2026-08-17, byte-identical both runs: the body
     * stood at {@code 6,56,18} — the cell directly under the second course's step — so the loop broke
     * out, climbed onto the course below, and from there its own box reached into the very cell it
     * was placing:
     *
     * <pre>
     * cell.6.ramp.step.1 = 6, 57, 18 垫不上（现在是 air，贴得到实心面
     *                      （但身体自己的碰撞箱压在这一格里 —— vanilla 的 isUnobstructed 会拒，
     *                        身体精确位置 6.60/57.00/17.78）），身体 6, 57, 17
     * cell.6.ramp.laid   = 1/2 级垫好了
     * </pre>
     *
     * <p>A player's box is 0.6 wide, so 0.28 off centre is enough — and the walker leaves a body
     * wherever the last edge ended, not in the middle of a cell. The remedy is therefore not a
     * tolerance anywhere: it is to stand somewhere the flight does not pass through at all, which in
     * a five-wide corridor is an ordinary floor cell one rank over.
     *
     * <p><b>It says where it went, which it did not until 2026-08-20.</b> Cast nine of that run
     * printed {@code cast9.ramp.flight = 3 级：2, 56, 20 → …（身体 2, 56, 20）} and then
     * {@code cast9.ramp.laid = 0/3 级垫好了（身体 2, 56, 20）} — two readings, the same cell, the
     * body on the flight's own bottom support both times. So this method either found nothing to
     * walk to or walked and did not arrive, and the run could not say which: it wrote no row at all.
     * Both rows below are unconditional for that reason. They cost two lines of a results file and
     * they are the difference between「the loop gave up」and「the walk never moved」, which want
     * completely different work.
     */
    private static void approach(JourneyRig rig, Set<BlockPos> corridor, List<BlockPos> flight,
                                 String tag, Runnable then) {
        ServerLevel level = rig.ctx().level();
        BlockPos here = rig.player().blockPosition();
        BlockPos from = builderStand(level, corridor, flight);
        if (from == null) {
            rig.evidence(tag + ".stand", here.toShortString()
                    + " 壁龛里没有一格能当施工位（要实底、头脚都空、且不在这道楼梯的足迹上）—— 就地开垫"
                    + (onTheFlight(flight, here) || onTheFlight(flight, here.above())
                            ? "，而身体正压在足迹上" : ""));
            then.run();
            return;
        }
        if (here.equals(from)) { then.run(); return; }
        rig.evidence(tag + ".stand", here.toShortString() + " → " + from.toShortString()
                + (onTheFlight(flight, here) || onTheFlight(flight, here.above())
                        ? "（现在正压在这道楼梯的足迹上，不挪开第一级就垫不了）" : "（现在不在足迹上）"));
        walkTo(rig, from, () -> {
            BlockPos now = rig.player().blockPosition();
            if (!now.equals(from))
                rig.evidence(tag + ".standShort", "没走到 " + from.toShortString() + "，停在 "
                        + now.toShortString()
                        + (onTheFlight(flight, now) || onTheFlight(flight, now.above())
                                ? " —— 还压在足迹上" : ""));
            then.run();
        });
    }

    /**
     * A floor cell to build from: solid underfoot, clear for feet and head, and not a cell this
     * flight needs — neither a step nor a shoulder nor a cell the body will walk through.
     *
     * <p>Nearest to the bottom step wins, because reach is what decides how much of the flight one
     * stand can lay and {@link JourneyStairs#MEND_REACH} is only five. Null when the corridor has no
     * such cell, and the caller then does what it did before rather than refusing to build.
     */
    static BlockPos builderStand(ServerLevel level, Set<BlockPos> corridor,
                                 List<BlockPos> flight) {
        BlockPos bottom = flight.get(0).below();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : corridor) {
            if (!standable(level, corridor, c)) continue;
            if (!level.getBlockState(c.below()).blocksMotion()) continue;
            if (onTheFlight(flight, c) || onTheFlight(flight, c.above())) continue;
            double d = c.distSqr(bottom);
            if (d < bestD) { bestD = d; best = c.immutable(); }
        }
        return best;
    }

    /** Is this cell part of the flight's own footprint — a step, a shoulder, a stand or its head
     *  room? The one question {@link #approach} and {@link #lay} both have to ask about the body's
     *  position, and asking it about only the step is what cost a course a run. */
    private static boolean onTheFlight(List<BlockPos> flight, BlockPos c) {
        for (BlockPos stand : flight)
            if (stand.equals(c) || stand.above().equals(c)
                    || stand.below().equals(c) || stand.below(2).equals(c)) return true;
        return false;
    }

    /** One leg of ordinary walking inside the alcove. NoBreak throughout: the tallest thing on any
     *  route down here is the mould this rung is building, and a walker sent at a cell it cannot
     *  reach eats it — see {@code reopen}'s note for the run that lost three cast cells that way.
     *
     *  <p>Package-private because {@link JourneyPour#footBeforeTower} walks the same alcove under the
     *  same rule, and a second copy of「一段带 NoBreak 的短腿」would be a second place to forget it. */
    static void walkTo(JourneyRig rig, BlockPos spot, Runnable then) {
        rig.settle(new IntentProcess(new Intent(new Goal.Block(spot), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), STEP_WALK_TICKS,
                () -> rig.settle(new HoldStill(10), 30, then));
    }

    /**
     * Why one pass of {@link #layWhereItStands} stopped — and the reason a pass is a value now
     * rather than an {@code int}.
     *
     * <p>Two of these used to be one finding, "laid nothing", and {@link #lay} answered both with
     * 「walking changes nothing」. That answer is right for {@link #REFUSED} and wrong for
     * {@link #BODY_IN_THE_WAY}. The ladder run of 2026-08-20 printed both, and the rows are worth
     * reading side by side because they look identical from a distance:
     *
     * <pre>
     * cell.9.ramp.step.2   = 3, 58, 20 垫不上（… 六邻没有能贴的实心面），身体 0, 58, 19
     * cell.9.ramp.step.2#2 = 3, 58, 20 垫不上（… 六邻没有能贴的实心面），身体 2, 56, 20
     * cell.9.ramp.laid     = 2/3 级垫好了
     *
     * cast9.ramp.flight    = 3 级：2, 56, 20 → 3, 57, 20 → 2, 58, 20（… 身体 2, 56, 20）
     * cast9.ramp.laid      = 0/3 级垫好了（身体 2, 56, 20）
     * </pre>
     *
     * <p>The first pair is the SAME cell refused from two stands nine blocks apart — the {@code #2}
     * suffix is the rig's own duplicate-key marker — and it is the rule earning its keep: the walk
     * between them changed nothing about six air neighbours, and a third stand would not have
     * either. The second pair is the body standing on {@code flight.get(0).below()}, where one cell
     * sideways is the whole fix, and the rule refused to take it: {@code 0/3}, and the raise that
     * depended on it ended in the wrong column.
     */
    enum Stop {
        /** Every course is solid. Nothing left to lay from anywhere. */
        FINISHED,
        /** The next support is the cell the body occupies. Vanilla's {@code isUnobstructed} refuses
         *  a placement into it, and a body is the one obstacle that can walk away. */
        BODY_IN_THE_WAY,
        /** The next support is further than {@link JourneyStairs#MEND_REACH}. */
        OUT_OF_REACH,
        /** A placement was attempted and the world did not take it. Named by {@code .step.N}. */
        REFUSED
    }

    /** How far one pass of {@link #layWhereItStands} got and why it stopped. {@code at} is the
     *  support it stopped ON, or null when it {@link Stop#FINISHED}. */
    record Pass(int laid, Stop stop, BlockPos at) {}

    /**
     * Lay every step within arm's length of wherever the body is standing right now — one pass, no
     * walking, no rig.
     *
     * <p><b>The whole loop, split off from its driver so an arena can run it.</b> Everything above
     * this line was measured on the real ladder and nothing could be measured anywhere else: the
     * loop needed a {@link JourneyRig} for four services and only two of them were real. The two
     * that were not are a level and a body, which any scene has; {@code holdItem} and
     * {@code placeOn} come off the {@link Avatar} interface the rig hands out; and the evidence sink
     * is a {@link BiConsumer} the rig satisfies by method reference. What is left in {@link #lay} is
     * walking and recursion — see that method's note for the two lines a scene cannot reach.
     *
     * <p>Arm's length is the same {@link JourneyStairs#MEND_REACH} the stair mend and the backing
     * mend run on, and for the same reason: {@code placeOn} goes straight to
     * {@code gameMode.useItemOn}, which has no reach gate on this avatar, so without it a flight
     * could be built through ten blocks of rock and read as one the body earned.
     */
    static Pass layWhereItStands(ServerLevel level, ServerPlayer player, Avatar av,
                                 Set<BlockPos> corridor, List<BlockPos> flight, int from,
                                 BiConsumer<String, Object> evidence, String tag) {
        BlockPos body = player.blockPosition();
        int laid = from;
        while (laid < flight.size()) {
            BlockPos support = flight.get(laid).below();
            if (level.getBlockState(support).blocksMotion()) { laid++; continue; }
            if (support.equals(body) || support.equals(body.above()))
                return new Pass(laid, Stop.BODY_IN_THE_WAY, support.immutable());
            if (Math.sqrt(body.distSqr(support)) > JourneyStairs.MEND_REACH)
                return new Pass(laid, Stop.OUT_OF_REACH, support.immutable());
            // THE SHOULDER FIRST, and only when the world offers nothing else. It is the cell under
            // the step, which lies in the previous course's own row — so it is the one cell of this
            // flight that can be clicked against what the flight has already built. Laid on its own
            // terms: it is floor, not a step, so it is not what the body walks on, and a shoulder
            // that fails is not a course lost — the placement below reads the world either way.
            BlockPos shoulder = support.below();
            if (!placeable(level, support) && fillable(level, corridor, shoulder)
                    && !walkedThrough(flight, shoulder)
                    && !shoulder.equals(body) && !shoulder.equals(body.above())
                    && Math.sqrt(body.distSqr(shoulder)) <= JourneyStairs.MEND_REACH
                    && av.holdItem(Items.COBBLESTONE)) {
                JourneyStairs.placeInto(level, av, shoulder);
                if (level.getBlockState(shoulder).blocksMotion()) laid(shoulder);
            }
            boolean held = av.holdItem(Items.COBBLESTONE);
            if (held) JourneyStairs.placeInto(level, av, support);
            // THE WORLD, not the call — the same discipline the stair mend and the backing mend
            // already run on. `placeOn` reports nothing, and a step that was never laid produces the
            // identical row to one that was.
            if (!level.getBlockState(support).blocksMotion()) {
                evidence.accept(tag + ".step." + laid, support.toShortString() + " 垫不上（"
                        + (held ? whyNotLaid(level, player, support) : "手上没有圆石")
                        + "），身体 " + body.toShortString());
                return new Pass(laid, Stop.REFUSED, support.immutable());
            }
            laid(support);
            // NAMED AT THE MOMENT IT IS BORROWED. This is the row that would have made the ladder run
            // of 2026-08-20 a one-round diagnosis: `wet.8.ramp.flight` and `cast8.picks.1` were two
            // hundred lines apart and nothing said the cobblestone in the second was the fourth
            // course of the first. Silent when the flight owes nothing, so the row's presence IS the
            // reading — see JourneySight for why the reservation is redeemed rather than enforced.
            BlockPos owner = JourneySight.lineOwner(level, support);
            if (owner != null)
                evidence.accept(tag + ".borrowed." + laid, support.toShortString() + " 正在 "
                        + owner.toShortString() + " 那一格的浇筑射线上 —— 这一级没有别的落法，先借下来；"
                        + "等浇那一格时 clearPourLine 会把它收回去");
            laid++;
        }
        return new Pass(laid, Stop.FINISHED, null);
    }

    /**
     * Where to stand before asking the same pass again — or null when asking again is pointless.
     *
     * <p><b>A pass that laid nothing is not automatically a pass with nothing left to try.</b> The
     * rule this replaces was written for one shape and applied to two. It is kept, exactly, for
     * {@link Stop#REFUSED}: a placement the world would not take was refused for a reason a stand
     * does not change, and {@code cell.9.ramp.step.2}/{@code #2} is that measured twice from two
     * stands. It never held for {@link Stop#BODY_IN_THE_WAY}, where the obstruction is the body's
     * own 0.6-wide box and one cell sideways removes it — and that is the case the run of
     * 2026-08-20 died on, silently, at {@code 0/3}.
     *
     * <p><b>Once.</b> The step-aside is spent the moment a pass makes no progress, and only a pass
     * that DOES make progress hands it back ({@link #lay} passes {@code alreadyAside} as
     * 「this pass laid nothing」). So a body that cannot get off the flight asks twice and stops,
     * which is one more question than before and not a loop — the shape 「a retry that changes
     * nothing」 warns about is a retry with no bound, not a second attempt at a question whose
     * premise changed.
     *
     * <p>{@link Stop#OUT_OF_REACH} keeps the old answer on purpose. {@link #approach} has already
     * stood the body at {@link #builderStand}'s nearest cell, so a reach failure means that cell was
     * not near enough — and walking back to the same cell is the retry with no new information.
     */
    static BlockPos stepAsideFor(ServerLevel level, ServerPlayer player, Set<BlockPos> corridor,
                                 List<BlockPos> flight, Pass pass, int from, boolean alreadyAside) {
        if (pass.stop() == Stop.FINISHED) return null;
        BlockPos body = player.blockPosition();
        BlockPos aside = builderStand(level, corridor, flight);
        if (pass.laid() > from) {
            // STEP ASIDE RATHER THAN CLIMB. Climbing onto the course below is what put the body's own
            // box inside the next step's cell — see approach's note for the two runs that measured
            // it. A stand off the footprint is the same cell approach chose and is still legal; the
            // climb stays only as the fallback for a corridor that has no such cell, where doing
            // nothing would be worse than doing the thing that sometimes works.
            return aside != null && !aside.equals(body) ? aside : flight.get(pass.laid() - 1);
        }
        if (pass.stop() != Stop.BODY_IN_THE_WAY || alreadyAside) return null;
        return aside != null && !aside.equals(body) ? aside : null;
    }

    /**
     * Drive {@link #layWhereItStands}, walking between passes until it has nothing left to try.
     *
     * <p>Two lines here are covered by reading the diff and not by a test, and they are named rather
     * than glossed: the {@link #walkTo} that carries the body to the cell
     * {@link #stepAsideFor} names, and the recursion that re-enters with the pass's own
     * {@code laid}. {@code wd.rampStepsAsideWhenTheBodyIsInItsOwnStep} drives every other line of
     * this method — the pass, the decision, the one-shot latch — with the walk replaced by putting
     * the body in the named cell, because a scene cannot host a {@link JourneyRig} and a settle
     * needs one. That substitution is also why {@link #approach} now prints where it went: if the
     * WALK is what fails here, only the run can say so, and until 2026-08-20 it said nothing.
     */
    private static void lay(JourneyRig rig, Set<BlockPos> corridor, List<BlockPos> flight, int from,
                            BlockPos landing, boolean alreadyAside, String tag, Runnable then) {
        ServerLevel level = rig.ctx().level();
        Pass pass = layWhereItStands(level, rig.player(), rig.body().avatar(), corridor, flight,
                from, rig::evidence, tag);
        BlockPos to = stepAsideFor(level, rig.player(), corridor, flight, pass, from, alreadyAside);
        if (to == null) {
            rig.evidence(tag + ".laid", pass.laid() + "/" + flight.size() + " 级垫好了（身体 "
                    + rig.player().blockPosition().toShortString() + "，停在 " + pass.stop()
                    + (pass.at() == null ? "" : " " + pass.at().toShortString()) + "）");
            walkTo(rig, landing, () -> done(rig, landing, tag, then));
            return;
        }
        if (pass.laid() == from)
            rig.evidence(tag + ".aside", "这一趟一级没垫：身体 "
                    + rig.player().blockPosition().toShortString() + " 正压在 "
                    + pass.at().toShortString() + " 里（vanilla 的 isUnobstructed 会拒）—— 挪到 "
                    + to.toShortString() + " 再问一次，只问这一次");
        walkTo(rig, to, () -> lay(rig, corridor, flight, pass.laid(), landing,
                pass.laid() == from, tag, then));
    }

    /**
     * Is this cell one the flight itself needs open — a step's standing cell or its head room?
     *
     * <p>Asked only of the shoulder, and it is not defensive. A flight may turn back on itself: the
     * planner picks each course's direction independently, so two courses that go out and back leave
     * {@code support(i).below()} sitting exactly in {@code stand(i-2)}, and a longer fold puts it in
     * that stand's head room. Filling either seals the staircase the body is about to climb, from
     * underneath, after it has been paid for — the same shape of mistake as the sweep that took back
     * its own steps. The step above the shoulder then falls back to needing a real face, and a
     * refusal there costs a course rather than the route home.
     */
    private static boolean walkedThrough(List<BlockPos> flight, BlockPos c) {
        for (BlockPos stand : flight)
            if (stand.equals(c) || stand.above().equals(c)) return true;
        return false;
    }

    /**
     * Why a placement that was attempted did not take — asked of the world, not assumed.
     *
     * <p>This row used to say「六邻没有能贴的实心面」unconditionally, and it was wrong often enough to
     * end three rounds of this rung in the wrong place: {@code cell.6.ramp.step.1 = -8, 57, 37 垫不上
     * （…六邻没有能贴的实心面？），身体 -8, 57, 37} was printed about a cell the BODY WAS STANDING IN,
     * where the walls had nothing to do with it. Nine of the ten archived {@code .step.N} rows name a
     * cell face-adjacent to the body at its own feet row, which is where vanilla's
     * {@code isUnobstructed} refuses a placement it has every face it needs for — a player's box is
     * 0.6 wide, so a body a fifth of a cell off centre is inside the cell next door.
     *
     * <p>So both states are measured and named separately. They want opposite work: no face wants a
     * shoulder or a different route, a body in the way wants one step sideways.
     */
    private static String whyNotLaid(ServerLevel level, ServerPlayer fp, BlockPos cell) {
        String now = "现在是 " + level.getBlockState(cell).getBlock();
        if (!placeable(level, cell)) return now + "，六邻没有能贴的实心面（放方块要贴着一个面点）";
        boolean inTheWay = fp.getBoundingBox().intersects(new AABB(cell));
        return now + "，贴得到实心面（"
                + (inTheWay ? "但身体自己的碰撞箱压在这一格里 —— vanilla 的 isUnobstructed 会拒，"
                              + "身体精确位置 " + String.format("%.2f/%.2f/%.2f",
                                      fp.getX(), fp.getY(), fp.getZ())
                            : "身体也不压在这一格里 —— 拒绝的原因不在这两条里，去看 placeOn 那一侧")
                + "）";
    }

    /** The alcove's own floor row — the one course that rests on rock rather than on the course
     *  below it. Derived from the corridor rather than passed in, so it cannot disagree with the
     *  volume the rung actually hollowed. */
    static int floorOf(Set<BlockPos> corridor) {
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
        // TWO PASSES, and the second is not the first one giving up. Pass one refuses to FILL a cell
        // a cast still to come has to shoot through ({@link JourneySight#onALineToCome}) — the
        // sight-line half of the same no-go discipline JourneyStairs#needsOpen is the walkable half
        // of. Pass two drops that refusal, because on the geometry that named this rule there is no
        // route around it: the wet cell sits one row above the frame cell, so its landing rests on
        // exactly the cell the frame cell has to stand in, and refusing there would leave the water
        // pour with no way up at all — measured in wd.pourLineHasNoOtherWayUp. A flight that has to
        // borrow says so (see #lay) and clearPourLine hands the cell back when the pour asks.
        List<BlockPos> clear = planKeeping(level, corridor, floorY, landing, true);
        return clear != null ? clear : planKeeping(level, corridor, floorY, landing, false);
    }

    /** One pass of {@link #plan}, named so a scene can ask the two separately: with the reservation
     *  honoured there is no flight in this alcove, and without it there is exactly one whose top
     *  support is the reserved cell. That pair is the arithmetic behind「redeem, do not refuse」and it
     *  is measured in {@code wd.pourLineHasNoOtherWayUp} rather than argued. */
    static List<BlockPos> planKeeping(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                      BlockPos landing, boolean keepLinesClear) {
        List<BlockPos> found = new ArrayList<>();
        return walkDown(level, corridor, floorY, landing, found, keepLinesClear) ? found : null;
    }

    private static boolean walkDown(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                    BlockPos stand, List<BlockPos> found, boolean keepLinesClear) {
        if (!standable(level, corridor, stand)
                || !supportable(level, corridor, stand.below(), keepLinesClear))
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
            if (walkDown(level, corridor, floorY, stand.relative(d).below(), found, keepLinesClear)) {
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
     *
     * <p>A face to click against is asked for LAST and in two ways: one the world already provides,
     * or one the flight will provide itself by laying this step's shoulder first. See the class note
     * for why the second is not optimism — the shoulder is face-adjacent to the course below by
     * construction, so it is placeable the moment that course is, and {@link #lay} builds bottom-up.
     */
    private static boolean supportable(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        return supportable(level, corridor, c, false);
    }

    /** As above, with {@code keepLinesClear} asking pass one's stricter question — see {@link #plan}.
     *  A cell that is ALREADY solid is not affected by it: the reservation is about what this flight
     *  would FILL, and a block that is already standing in a line is that line's problem to clear,
     *  not this flight's to route around. */
    private static boolean supportable(ServerLevel level, Set<BlockPos> corridor, BlockPos c,
                                       boolean keepLinesClear) {
        if (level.getBlockState(c).blocksMotion()) return true;
        return fillable(level, corridor, c, keepLinesClear)
                && (placeable(level, c) || fillable(level, corridor, c.below(), keepLinesClear));
    }

    /** Is this a cell the rung is allowed to drop a cobblestone into? The membership half of
     *  {@link #supportable}, split out because the shoulder has to pass it too and must not be held
     *  to the face test — having no face is the whole reason a shoulder exists. */
    private static boolean fillable(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        return fillable(level, corridor, c, false);
    }

    /** As above, plus the sight-line half of the no-go discipline when {@code keepLinesClear}: a
     *  cell a cast still to come has to shoot through. Off by default because {@link #lay}'s shoulder
     *  is laid against a plan that has already been made — the route decision is {@link #plan}'s. */
    private static boolean fillable(ServerLevel level, Set<BlockPos> corridor, BlockPos c,
                                    boolean keepLinesClear) {
        if (keepLinesClear && JourneySight.onALineToCome(level, c)) return false;
        return corridor.contains(c) && !JourneyStairs.needsOpen(c)
                && level.getBlockState(c).canBeReplaced() && !level.getFluidState(c).isSource();
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

    /** Why the block under a step cannot go in first — the four ways {@link #fillable} says no. */
    private static String whyShoulder(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        if (!corridor.contains(c)) return "不是壁龛格";
        String flight = JourneyStairs.flightCell(c);
        if (flight != null) return flight + "，不能堵";
        if (!level.getBlockState(c).canBeReplaced())
            return "是 " + level.getBlockState(c).getBlock() + "，放不进去";
        if (level.getFluidState(c).isSource()) return "里是源块 —— 埋掉它这一级就收不回水桶了";
        return "本该垫得上 —— 这一行不该出现，去看 fillable";
    }

    /** Why one cell cannot hold a step — one sentence per clause, never one for all four. */
    private static String whySupport(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        if (!corridor.contains(c)) return c.toShortString() + " 不是壁龛格";
        String flight = JourneyStairs.flightCell(c);
        if (flight != null) return c.toShortString() + " " + flight + "，不能堵";
        if (!level.getBlockState(c).canBeReplaced())
            return c.toShortString() + " 是 " + level.getBlockState(c).getBlock() + "，放不进去";
        if (level.getFluidState(c).isSource())
            return c.toShortString() + " 里是源块 —— 埋掉它这一级就收不回水桶了";
        StringBuilder around = new StringBuilder();
        for (Direction d : Direction.values())
            around.append(' ').append(d).append('=').append(level.getBlockState(c.relative(d)).getBlock());
        // BOTH ROUTES, because both are now tried. Saying only the first is what made 43 of 51
        // refusals read as a fact about the alcove's walls when the walls were never the whole
        // question — see the class note. The shoulder's own clause says which of the four ways it is
        // barred, so a reader can tell a stair cell from a source from a cell outside the corridor.
        BlockPos shoulder = c.below();
        return c.toShortString() + " 六邻没有能贴的实心面（放方块要贴着一个面点）：" + around
                + "；垫肩 " + shoulder.toShortString() + " 也不能先垫上（" + whyShoulder(level, corridor, shoulder)
                + "）—— 两条路都断了";
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
