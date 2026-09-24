package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * ending in <i>"cannot place a support: … nothing holds it up; a single block would float"</i>.
 * One brick placed two rows up rests on
 * nothing the bot can then walk onto; what the top rows need is a FLIGHT, each step resting on the
 * step before it, walked up one course at a time.
 *
 * <p>The scripted tower ({@code JourneyShaft.ascendByTowering} → {@code TowerProcess}) is the tool
 * that already existed for gaining height, and on this geometry it does not work. Measured twice,
 * verbatim, on the real ladder — the run of 2026-08-16 and the one before it — with the bot on dry
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
 * times before in this repo. The one course it did gain, {@code climb.0}, was the course the bot
 * spent IN WATER — where buoyancy lifts a player whether or not a block goes under it.
 *
 * <p>So this does not jump. Each step is placed by hand through {@code useItemOn} — the same call
 * {@link JourneyStairs#placeInto} makes, which this rung has already measured working in this very
 * alcove ({@code cell.4.step} placed one block at {@code -8,56,37} and the cell became standable on
 * cobblestone) — and then the bot WALKS up one ordinary +1 step. Nothing here is a
 * {@code setBlock}, and every course reads its result back off the world: a placement can be refused
 * for reasons the caller cannot see, and a step that was never there is exactly the "the climb gains
 * no height" row this rung has been misled by twice.
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
 * finds it, mends it with the pick, and takes this raise out from under the bot standing on it.
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
 * water8.ramp.noFlight = 6, 60, 19 cannot build a staircase: this cell's own support
 *                        6, 59, 19 cannot be placed: 6, 59, 19 has no solid face among its six
 *                        neighbours (a placement must click against a face):
 *                        down=air up=air north=air south=air west=air east=air
 * </pre>
 *
 * <p>A refusal here is not a fallback. The two things behind it are the scripted tower, which stalls
 * on this geometry, and the walker's own Y-level goal, which on the same run put the bot seven
 * columns out of the one its aim had been computed for ({@code water8#3.endedIn = 0,19}, while the
 * tower started in column 6,19, not the same column) — so the pour or the scoop that follows fires
 * a ray nobody verified.
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
     * bot is resting on it. The run that named the difference is quoted in JourneySight; in one
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
     * Build a flight up to {@code landing} and walk the bot onto it.
     *
     * <p>{@code landing} is where the bot's FEET should end up — the caller has already decided
     * that, usually by asking which column's eye can see the cell it is about to pour into. Best
     * effort, and loudly: the bot ends wherever the flight got to, {@code .rampedY} says how far
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
     * <p><b>A bot ABOVE its landing is not a bot on it, and only the caller knows whether that
     * matters.</b> A POUR aims at the target's backing and is genuinely served from any row high
     * enough, so it keeps the {@code >=}. A SCOOP is not: its column is verified by
     * {@code JourneyPortalRung#raiseColumn} for ONE row — the eye is placed at exactly that row's
     * foot — so a bot one row up fires a line nobody checked.
     *
     * <p>Measured on the real ladder of 2026-08-16, cell six of an {@code east} mould. The column
     * {@code 3,19} verified for {@code wantY=58}; {@code walkToColumn} is a {@code Goal.XZ}, whose
     * heuristic ignores Y — see {@link net.magicterra.worlddriver.bot.Goal#ignoresY()}, which carries
     * the other half of this account: the same property that lets A* dive for free is what makes such
     * a goal ARRIVE without an opinion about the row. So it delivered the bot to {@code y=59}
     * ({@code recover6.rise.raisedY = 59/58}); this method then returned on {@code >=} without
     * building anything, which is why <b>no {@code .ramp.*} row exists in that run at all</b>. From one
     * row up, the line into the water at {@code 4,59,19} enters the frame cell {@code 4,60,19} and the
     * fill correctly refuses to break a frame — so the run reported {@code frameStuck} and the frame
     * took the blame for a row the bot should never have been standing on. The landing itself was
     * free and merely floorless ({@code standToFill} vetoed 13 candidates for having no solid floor
     * underfoot against 17 for having the foot cell occupied), which is exactly the work this flight
     * exists to do.
     */
    static void buildTo(JourneyRig rig, Set<BlockPos> corridor, BlockPos landing, boolean exactRow,
                        String tag, Runnable then) {
        buildTo(rig, corridor, landing, exactRow, Integer.MAX_VALUE, false, tag, then);
    }

    /**
     * As above, with the {@code >=} arm bounded and optionally pinned to {@code landing}'s column.
     *
     * <p><b>Both bounds exist because the unbounded arm answered a bot that had not moved.</b> Cell
     * eight of the real ladder of 2026-08-26 is the reading. {@code liftInPlace} had already decided
     * the bot's own column could not fire this pour and said so — {@code water8.liftSideways.2}
     * reported that {@code 0,65,15} was high enough (y=60) but that column did not pass the check for
     * this pour, and that it would move sideways to a column that does rather than build upward — then
     * picked {@code 3,60,20} and asked for it. This method compared rows only, found {@code 65 >= 60},
     * and returned without building or walking anything:
     *
     * <pre>
     * water8.lift               = 0,65,15 → 3,60,20 (cannot reach the chosen foot cell; building a
     *                             staircase up to the level of 4,61,20)
     * water8.lift.flightSkipped = 0,65,15 already at the landing row or higher (**5 rows above**)
     *                             (landing 3,60,20, exactRow=false)
     * water8.liftedY            = 65/60
     * </pre>
     *
     * <p>The sideways move the caller asked for never happened, and {@code liftedY} recorded the
     * non-move as a lift that finished. From three columns out and five rows up the only line to the
     * backing is the steep one {@link JourneyPour#POUR_ROW_SLACK} already accounts for, so the pour
     * then picked the bot's own footing: {@code water8.picks.1 = 4,63,20 grass_block face=up → lands
     * in 4,64,20}.
     *
     * <p><b>Neither bound is on by default, because one caller legitimately depends on the skip.</b>
     * {@link JourneyPortalRung}'s {@code standBehind} follows this call with a {@code walkToStand} onto
     * the very landing it passed — for that caller "high enough, wrong column" is a walk, not a
     * staircase, and pinning the column would make it build flights it does not need. Only the pour
     * side asks for the bounds, and it is the only side with a reading that wants them.
     */
    static void buildTo(JourneyRig rig, Set<BlockPos> corridor, BlockPos landing, boolean exactRow,
                        int rowSlack, boolean sameColumn, String tag, Runnable then) {
        ServerLevel level = rig.ctx().level();
        BlockPos here = rig.player().blockPosition();
        int over = here.getY() - landing.getY();
        boolean rowOk = exactRow ? over == 0 : (over >= 0 && over <= rowSlack);
        boolean columnOk = !sameColumn
                || (here.getX() == landing.getX() && here.getZ() == landing.getZ());
        // WHY IT IS NOT SKIPPING, which the row below cannot say because it only ever runs when the
        // skip is taken. "Build a staircase that was needed anyway" and "build a staircase to bring
        // a bot that is already high enough back to its column" are two different findings, and
        // without this row the second reaches the results file looking like the first — the same
        // complaint 202-206 makes about the skip's own silence.
        if (!rowOk || !columnOk) {
            if (over >= 0 && (sameColumn || rowSlack != Integer.MAX_VALUE)) {
                rig.evidence(tag + ".flightNotSkipped", here.toShortString() + " is high enough ("
                        + over + " row(s) above, tolerance "
                        + (rowSlack == Integer.MAX_VALUE ? "unlimited" : rowSlack) + ") but "
                        + (columnOk ? "" : "is not in the landing column " + landing.getX() + "," + landing.getZ())
                        + (columnOk || rowOk ? "" : ", and ")
                        + (rowOk ? "" : "is above the tolerated rows")
                        + "; the ray was verified for that column and row, and the ray fired from here"
                        + " is not the verified one; not skipping, building the staircase to bring the"
                        + " bot over");
            }
        }
        if (rowOk && columnOk) {
            // "Nothing to build" and "cannot be built" are two findings, and without a row here this
            // exit writes neither, so a run that takes it has no `.ramp.*` row at all. That is exactly
            // the "no .ramp.* row exists in that run at all" the javadoc above points to: in recover6
            // the frame took the blame for a row the bot should never have been standing on.
            // `.noFlight` is the key for a refusal and must not record "not needed", or the next
            // reader cannot tell the two apart.
            // HOW FAR ABOVE, not just "above". The `>=` arm is a real tolerance with a real reason
            // (see this method's javadoc), but a row that prints the same words for "exactly there"
            // and "five rows too high" cannot tell the two apart — and cell ten of 2026-08-26 was the
            // second while reading like the first. The number is free; it costs a subtraction and it
            // is the only way this exit ever admits it let a bot through that could not fire its
            // verified ray.
            rig.evidence(tag + ".flightSkipped", here.toShortString() + " already "
                    + (exactRow ? "on the landing row" : "at the landing row or higher")
                    + (over > 0 ? " (**" + over + " rows above**)" : "")
                    + " (landing " + landing.toShortString() + ", exactRow=" + exactRow
                    + (rowSlack == Integer.MAX_VALUE ? "" : ", tolerance " + rowSlack + " row(s)")
                    + (sameColumn ? ", same column" : "")
                    + "); no staircase needed");
            then.run();
            return;
        }
        int floorY = floorOf(corridor);
        int courses = landing.getY() - floorY;
        if (corridor.isEmpty() || courses <= 0 || courses > MAX_COURSES) {
            rig.evidence(tag + ".noFlight", landing.toShortString() + " needs " + courses
                    + " step(s) (alcove floor y=" + floorY + ", " + corridor.size()
                    + " cells); outside the range that can be built");
            then.run();
            return;
        }
        List<BlockPos> flight = plan(level, corridor, floorY, landing);
        if (flight == null) {
            // WHICH CLAUSE, not just "no" — see whyNoFlight. The four that can refuse a landing want
            // completely different work, and answering all of them with the route sentence is how a
            // row ends a search in the wrong place.
            rig.evidence(tag + ".noFlight", landing.toShortString() + " cannot build a staircase: "
                    + whyNoFlight(level, corridor, floorY, landing));
            then.run();
            return;
        }
        rig.evidence(tag + ".flight", flight.size() + " step(s): " + supports(flight)
                + " (alcove floor y=" + floorY + ", bot at " + here.toShortString() + ")");
        approach(rig, corridor, flight, tag,
                () -> lay(rig, corridor, flight, 0, landing, false, -1, tag, then));
    }

    /**
     * Stand BESIDE the bottom step before laying it.
     *
     * <p>A block cannot be placed into the cell a player is standing in — vanilla's own
     * {@code isUnobstructed} refuses it — and the first version of this walked onto each step before
     * laying the next, which put the bot in exactly that cell often enough to lose a course.
     * Measured, rehearsal 2026-08-16: {@code cell.6.ramp.step.1} reported that {@code -8,57,37} could
     * not be placed (it was air, apparently with no solid face among its six neighbours) with the bot
     * at {@code -8,57,37} — the bot WAS the obstruction, and the row it printed blamed the walls.
     *
     * <p>So the bot works from the floor: {@link #lay} places every step it can reach from where it
     * stands and only then climbs. Nothing here is a walk the flight needs; it is a walk that makes
     * the flight buildable.
     *
     * <p><b>Off the WHOLE flight, not just its bottom step.</b> The first version asked only that the
     * bot not be standing in the cell it was about to fill, and that is one cell of a footprint with
     * many. Measured twice on the pinned east arm, 2026-08-17, byte-identical both runs: the bot
     * stood at {@code 6,56,18} — the cell directly under the second course's step — so the loop broke
     * out, climbed onto the course below, and from there its own box reached into the very cell it
     * was placing:
     *
     * <pre>
     * cell.6.ramp.step.1 = 6, 57, 18 could not be placed (now air, a solid face is available
     *                      (but the bot's own collision box overlaps this cell; vanilla's
     *                      isUnobstructed refuses it, exact bot position 6.60/57.00/17.78)),
     *                      bot at 6, 57, 17
     * cell.6.ramp.laid   = 1/2 steps laid
     * </pre>
     *
     * <p>A player's box is 0.6 wide, so 0.28 off centre is enough — and the walker leaves a bot
     * wherever the last edge ended, not in the middle of a cell. The remedy is therefore not a
     * tolerance anywhere: it is to stand somewhere the flight does not pass through at all, which in
     * a five-wide corridor is an ordinary floor cell one rank over.
     *
     * <p><b>It says where it went, which it did not until 2026-08-20.</b> Cast nine of that run
     * printed {@code cast9.ramp.flight = 3 steps: 2, 56, 20 → … (bot at 2, 56, 20)} and then
     * {@code cast9.ramp.laid = 0/3 steps laid (bot at 2, 56, 20)} — two readings, the same cell, the
     * bot on the flight's own bottom support both times. So this method either found nothing to
     * walk to or walked and did not arrive, and the run could not say which: it wrote no row at all.
     * Both rows below are unconditional for that reason. They cost two lines of a results file and
     * they are the difference between "the loop gave up" and "the walk never moved", which want
     * completely different work.
     */
    private static void approach(JourneyRig rig, Set<BlockPos> corridor, List<BlockPos> flight,
                                 String tag, Runnable then) {
        ServerLevel level = rig.ctx().level();
        BlockPos here = rig.player().blockPosition();
        BlockPos from = builderStand(level, corridor, flight);
        if (from == null) {
            rig.evidence(tag + ".stand", here.toShortString()
                    + " no cell in the alcove can serve as a builder stand (it needs a solid floor,"
                    + " clear feet and head cells, and must be off this staircase's footprint);"
                    + " laying from where the bot stands"
                    + (onTheFlight(flight, here) || onTheFlight(flight, here.above())
                            ? ", and the bot is standing on the footprint" : ""));
            then.run();
            return;
        }
        if (here.equals(from)) {
            // The third outcome, and until now the mute one — which is why "already on the builder
            // stand" and "walked but did not move" reached the results file as the same thing: no
            // `.stand` row either way. That is the exact pair the note above says these rows exist
            // to tell apart, so this branch writes one too and "both rows below are unconditional"
            // becomes true of the code rather than of the intention. No footprint clause here, unlike
            // the other two rows: `builderStand` refuses every cell on the flight, so `here == from`
            // is off the footprint by construction and a clause about it could never fire.
            rig.evidence(tag + ".stand", here.toShortString()
                    + " is already on the builder stand; no need to move");
            then.run();
            return;
        }
        rig.evidence(tag + ".stand", here.toShortString() + " → " + from.toShortString()
                + (onTheFlight(flight, here) || onTheFlight(flight, here.above())
                        ? " (currently on this staircase's footprint; the first step cannot be laid"
                          + " until the bot moves off it)"
                        : " (not on the footprint now)"));
        walkTo(rig, from, () -> {
            BlockPos now = rig.player().blockPosition();
            if (!now.equals(from)) {
                rig.evidence(tag + ".standShort", "did not reach " + from.toShortString()
                        + ", stopped at " + now.toShortString()
                        + (onTheFlight(flight, now) || onTheFlight(flight, now.above())
                                ? "; still on the footprint" : ""));
                // WHAT STOOD IN THE WAY, not merely that the walk fell short. The rehearsal of
                // 2026-08-26 spent a whole round on this row: it reported the bot stopping at
                // 3,64,18 en route to 2,56,18, the walker logged `MOVE-noMove … hCol=true` at
                // 2.3,64.0,19.4 — and the cell it collided with has ZERO mentions anywhere in the
                // run, so the obstruction could only be guessed at. A guess picked the water eight
                // rows below, which cannot produce a collision at y=64.
                //
                // Cheap because it is rare: `.stand` fires ~15 times a run and reaches here twice,
                // both on the alcove's descending segment. Two waypoints, since this is one segment;
                // the probe cuts its bands at the FIRST waypoint's row, which is where the bot still
                // is and therefore where the obstruction has to be.
                rig.evidence(tag + ".standShort.rows", rowsBetween(level, now, from));
                JourneyCorridorProbe.record(rig, tag + ".standShort", now,
                        new int[][] {{now.getX(), now.getY(), now.getZ()},
                                {from.getX(), from.getY(), from.getZ()}}, 0, 2);
            }
            then.run();
        });
    }

    /**
     * The three questions a walk that fell short leaves open, in one row: can the bot descend its
     * own column, is the target buried, and what is it pressed against right now.
     *
     * <p><b>This exists because the corridor maps cannot answer any of them.</b>
     * {@link JourneyCorridorProbe} cuts at the HIGHEST standable face in its band
     * ({@code standY} walks down from {@code ref + 4} and returns the first solid-with-air-above),
     * so a bot standing on the surface at y=63 hides an alcove at y=56 completely — the run of
     * 2026-08-26 read {@code n} (=63) for the target's own column and learned nothing about the
     * seven rows under it. The maps still earn their place for the lie of the land; this row is for
     * the vertical question they flatten away.
     *
     * <p>{@code #} is solid, {@code .} is not, both columns printed top-down over the same span so
     * the two strings line up character for character. Each solid row then repeats with its block
     * name, because {@code #} alone cannot separate the two answers this question needs kept apart:
     * cobblestone is a block some pass of this run laid, stone and dirt are terrain that was always
     * there. ⚠️ That is a family, <b>not</b> attribution — no placement row in this run carries
     * coordinates, so a cobblestone here narrows the suspect list and never names the author.
     */
    static String rowsBetween(ServerLevel level, BlockPos now, BlockPos target) {
        int hi = Math.max(now.getY(), target.getY());
        int lo = Math.min(now.getY(), target.getY());
        StringBuilder sb = new StringBuilder("y").append(hi).append("→").append(lo)
                .append(" bot column ").append(now.getX()).append(',').append(now.getZ()).append('=')
                .append(rowsOf(level, now.getX(), now.getZ(), lo, hi))
                .append(solidNames(level, now.getX(), now.getZ(), lo, hi))
                .append("; target column ").append(target.getX()).append(',').append(target.getZ())
                .append('=').append(rowsOf(level, target.getX(), target.getZ(), lo, hi))
                .append(solidNames(level, target.getX(), target.getZ(), lo, hi));
        // The horizontal neighbours are the ones a `hCol=true` actually reports against — the walk
        // that produced this row stopped with the bot pressed into one of them, and until now no
        // reading said which. Feet and head separately: a bot stopped by head clearance and one
        // stopped by a wall read identically from the outside.
        sb.append("; horizontal neighbours (feet/head)");
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = now.relative(d);
            sb.append(' ').append(d.getName()).append('=')
                    .append(nameOf(level, side)).append('/').append(nameOf(level, side.above()));
        }
        return sb.toString();
    }

    /** One column's solidity, top row first, over {@code [lo, hi]}. */
    private static String rowsOf(ServerLevel level, int x, int z, int lo, int hi) {
        StringBuilder sb = new StringBuilder();
        for (int y = hi; y >= lo; y--)
            sb.append(level.getBlockState(new BlockPos(x, y, z)).blocksMotion() ? '#' : '.');
        return sb.toString();
    }

    /**
     * The same column's solid rows again, by name. Prints {@code [no solid]} rather than an empty
     * bracket when nothing is solid, so "no solid rows" and "I forgot to print this" cannot read
     * alike.
     */
    private static String solidNames(ServerLevel level, int x, int z, int lo, int hi) {
        StringBuilder sb = new StringBuilder();
        for (int y = hi; y >= lo; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.getBlockState(p).blocksMotion()) continue;
            sb.append(sb.isEmpty() ? " [" : " ").append(y).append('=')
                    .append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath());
        }
        return sb.isEmpty() ? " [no solid]" : sb.append(']').toString();
    }

    /**
     * One cell as {@code #name} or {@code .name}. The leading character is the {@code blocksMotion}
     * verdict the walker itself collides against; the name is kept beside it rather than instead of
     * it, because a name alone would make the reader re-derive that verdict — and get it wrong for
     * the cells where the two disagree (grass does not block motion, mud blocks it at 14/16 height).
     */
    private static String nameOf(ServerLevel level, BlockPos p) {
        var state = level.getBlockState(p);
        return (state.blocksMotion() ? "#" : ".")
                + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /**
     * A floor cell to build from: solid underfoot, clear for feet and head, and not a cell this
     * flight needs — neither a step nor a shoulder nor a cell the bot will walk through.
     *
     * <p>Nearest to the bottom step wins, because reach is what decides how much of the flight one
     * stand can lay and {@link JourneyStairs#MEND_REACH} is only five. Null when the corridor has no
     * such cell, and the caller then does what it did before rather than refusing to build.
     */
    static BlockPos builderStand(ServerLevel level, Set<BlockPos> corridor,
                                 List<BlockPos> flight) {
        return builderStand(level, corridor, flight, null);
    }

    /**
     * The same search with one cell struck out — the cell a step-aside is trying to LEAVE.
     *
     * <p><b>Nearest-then-veto is a different search, and a worse one.</b> {@link #stepAsideFor} used
     * to take the plain nearest stand and hand back null when it turned out to be the cell the bot
     * already stood in, throwing away every other legal stand in the corridor. In this alcove the
     * bot's own cell and two or three of its neighbours all sit one cell from the bottom step, so
     * which of them "nearest" named was decided by {@code Set} iteration order, and
     * {@code Set.copyOf} salts that per JVM. {@code wd.rampSeesABodyOnlyPartlyInTheCell} measured
     * the result as a coin flip — nine runs, five red, with the staged bot and the step it was
     * blocking byte-identical in all nine.
     */
    static BlockPos builderStand(ServerLevel level, Set<BlockPos> corridor,
                                 List<BlockPos> flight, BlockPos exclude) {
        BlockPos bottom = flight.get(0).below();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos c : corridor) {
            if (c.equals(exclude)) continue;
            if (!standable(level, corridor, c)) continue;
            if (!level.getBlockState(c.below()).blocksMotion()) continue;
            if (onTheFlight(flight, c) || onTheFlight(flight, c.above())) continue;
            double d = c.distSqr(bottom);
            // Ties broken by position, not by iteration order. Two cells equally near the bottom
            // step are both legal, so the old strict `<` was not wrong — it was unrepeatable, and a
            // ladder that stands somewhere else each run cannot be compared with the run before it.
            if (best == null || d < bestD || (d == bestD && c.compareTo(best) < 0)) {
                bestD = d;
                best = c.immutable();
            }
        }
        return best;
    }

    /** Is this cell part of the flight's own footprint — a step, a shoulder, a stand or its head
     *  room? The one question {@link #approach} and {@link #lay} both have to ask about the bot's
     *  position, and asking it about only the step is what cost a course a run.
     *
     *  <p>Package-private because an arena that wants to seal the cells tied with the bot's own has
     *  to leave the flight's cells alone, and a second copy of this definition in a scene would be a
     *  second place for it to drift. */
    static boolean onTheFlight(List<BlockPos> flight, BlockPos c) {
        for (BlockPos stand : flight)
            if (stand.equals(c) || stand.above().equals(c)
                    || stand.below().equals(c) || stand.below(2).equals(c)) return true;
        return false;
    }

    /** One short walk of the ordinary kind inside the alcove. NoBreak throughout: the tallest thing on any
     *  route down here is the mould this rung is building, and a walker sent at a cell it cannot
     *  reach eats it — see {@code reopen}'s note for the run that lost three cast cells that way.
     *
     *  <p>Package-private because {@link JourneyPour#footBeforeTower} walks the same alcove under the
     *  same rule, and a second copy of "a short walk with NoBreak" would be a second place to forget it. */
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
     * "walking changes nothing". That answer is right for {@link #REFUSED} and wrong for
     * {@link #BODY_IN_THE_WAY}. The ladder run of 2026-08-20 printed both, and the rows are worth
     * reading side by side because they look identical from a distance:
     *
     * <pre>
     * cell.9.ramp.step.2   = 3, 58, 20 could not be placed (… no solid face among its six
     *                        neighbours), bot at 0, 58, 19
     * cell.9.ramp.step.2#2 = 3, 58, 20 could not be placed (… no solid face among its six
     *                        neighbours), bot at 2, 56, 20
     * cell.9.ramp.laid     = 2/3 steps laid
     *
     * cast9.ramp.flight    = 3 steps: 2, 56, 20 → 3, 57, 20 → 2, 58, 20 (… bot at 2, 56, 20)
     * cast9.ramp.laid      = 0/3 steps laid (bot at 2, 56, 20)
     * </pre>
     *
     * <p>The first pair is the SAME cell refused from two stands nine blocks apart — the {@code #2}
     * suffix is the rig's own duplicate-key marker — and it is the rule earning its keep: the walk
     * between them changed nothing about six air neighbours, and a third stand would not have
     * either. The second pair is the bot standing on {@code flight.get(0).below()}, where one cell
     * sideways is the whole fix, and the rule refused to take it: {@code 0/3}, and the raise that
     * depended on it ended in the wrong column.
     */
    enum Stop {
        /** Every course is solid. Nothing left to lay from anywhere. */
        FINISHED,
        /** The next support is a cell the bot's own box reaches into — see
         *  {@link #bodyIsInTheWay}, which is vanilla's question and NOT "the cell it stands in".
         *  Asking the narrower one cost the ring its tenth cell on 2026-08-26. Vanilla's
         *  {@code isUnobstructed} refuses a placement into it, and a bot is the one obstacle that
         *  can walk away. */
        BODY_IN_THE_WAY,
        /** The next support is further than {@link JourneyStairs#MEND_REACH}. */
        OUT_OF_REACH,
        /** A placement was attempted and the world did not take it. Named by {@code .step.N}. */
        REFUSED,
        /**
         * A placement was attempted and the cell is not solid <b>yet</b> — which for a client-driven
         * bot is not the same statement as {@link #REFUSED}, and until 2026-08-25 this code could
         * not tell them apart.
         *
         * <p>{@code placeOn} on a {@code LocalPlayer} goes to {@code gameMode.useItemOn}, which
         * <b>predicts locally and sends a packet</b>. The server has not run a tick yet, so a
         * {@code ServerLevel} read in the next statement is false by construction. On a
         * {@code JoinedBody} the same call lands server-side and the read is true — one helper, two
         * kinds of player, one of them silently mis-judged since the ladder switched to a real client.
         *
         * <p>Measured on ladder j54, cell {@code 3, 56, 18}:
         * <pre>
         * [06:46:58] [place] success … clicked=3, 55, 18 face=up    neighbour=3, 56, 18→cobblestone result=SUCCESS
         * [06:46:58] [place] refused … clicked=3, 56, 17 face=south neighbour=3, 56, 18→cobblestone result=FAIL
         * [06:47:01] [place] refused … clicked=3, 56, 18 face=south …
         * </pre>
         * (The {@code [place]} log lines are paraphrased in English here.)
         * The first face succeeded and the hand lost one cobblestone (×54→×53→×52, one per SUCCESS),
         * yet {@link JourneyStairs#placeInto} walked on to faces 2 and 3 — which it only does when
         * its own check said "not solid". Three seconds later that same block is what
         * {@code placeInto}'s own {@code blocksMotion} face test clicks AGAINST, and that test only
         * accepts a neighbour the
         * <i>ServerLevel</i> calls solid. The round trip completes; the verdict was one statement early.
         *
         * <p>The cost of getting this wrong was total: all nine ramp attempts of j54 reported
         * {@code 0/N}, and every one of the fourteen {@code .step.N} rows was {@code step.0} — the
         * pass could never credit a course it had just laid, only one that was already solid.
         */
        PENDING
    }

    /** How far one pass of {@link #layWhereItStands} got and why it stopped. {@code at} is the
     *  support it stopped ON, or null when it {@link Stop#FINISHED}. */
    record Pass(int laid, Stop stop, BlockPos at) {}

    /**
     * Ticks to let a placement reach the server and come back before its absence means anything.
     *
     * <p>Four, held for {@code PLACE_ROUND_TRIP * 2} ticks of budget, because the scoop next door
     * already settled this number by measurement — its failure row reads
     * {@code water_bucket 0→0} after already waiting a 3-tick round trip. An integrated server's
     * client and server share a
     * process and the packet lands on the very next tick; the margin is for the day this bot places
     * through {@code mc.execute} instead of straight off the calling thread.
     */
    private static final int PLACE_ROUND_TRIP = 4;

    /**
     * Lay every step within arm's length of wherever the bot is standing right now — one pass, no
     * walking, no rig.
     *
     * <p><b>The whole loop, split off from its driver so an arena can run it.</b> Everything above
     * this line was measured on the real ladder and nothing could be measured anywhere else: the
     * loop needed a {@link JourneyRig} for four services and only two of them were real. The two
     * that were not are a level and a player, which any scene has; {@code holdItem} and
     * {@code placeOn} come off the {@link Hands} the rig hands out; and the evidence sink
     * is a {@link BiConsumer} the rig satisfies by method reference. What is left in {@link #lay} is
     * walking and recursion — see that method's note for the two lines a scene cannot reach.
     *
     * <p>Arm's length is the same {@link JourneyStairs#MEND_REACH} the stair mend and the backing
     * mend run on, and for the same reason: {@code placeOn} goes straight to
     * {@code gameMode.useItemOn}, which has no reach gate on this avatar, so without it a flight
     * could be built through ten blocks of rock and read as one the bot earned.
     */
    static Pass layWhereItStands(ServerLevel level, ServerPlayer player, Hands av,
                                 Set<BlockPos> corridor, List<BlockPos> flight, int from,
                                 BiConsumer<String, Object> evidence, String tag) {
        return layWhereItStands(level, player, av, corridor, flight, from, evidence, tag, true);
    }

    /**
     * The same, with the choice of whether an unconfirmed placement may be deferred.
     *
     * <p>{@code settled=true} is the historic behaviour and what every scene gets: judge now, and an
     * attempted placement that did not land is {@link Stop#REFUSED}. That is correct for a bot that
     * places server-side, which is the kind of bot a scene drives.
     *
     * <p>{@code settled=false} is for {@link #lay}, which has a rig and can therefore let ticks pass.
     * It reports {@link Stop#PENDING} instead — <b>without</b> writing a {@code .step.N} row, because
     * the reason it would print is read from the same too-early world. See {@link Stop#PENDING}.
     */
    static Pass layWhereItStands(ServerLevel level, ServerPlayer player, Hands av,
                                 Set<BlockPos> corridor, List<BlockPos> flight, int from,
                                 BiConsumer<String, Object> evidence, String tag, boolean settled) {
        BlockPos body = player.blockPosition();
        int laid = from;
        while (laid < flight.size()) {
            BlockPos support = flight.get(laid).below();
            if (level.getBlockState(support).blocksMotion()) { laid++; continue; }
            if (bodyIsInTheWay(player, support))
                return new Pass(laid, Stop.BODY_IN_THE_WAY, support.immutable());
            if (Math.sqrt(body.distSqr(support)) > JourneyStairs.MEND_REACH)
                return new Pass(laid, Stop.OUT_OF_REACH, support.immutable());
            // THE SHOULDER FIRST, and only when the world offers nothing else. It is the cell under
            // the step, which lies in the previous course's own row — so it is the one cell of this
            // flight that can be clicked against what the flight has already built. Laid on its own
            // terms: it is floor, not a step, so it is not what the bot walks on, and a shoulder
            // that fails is not a course lost — the placement below reads the world either way.
            BlockPos shoulder = support.below();
            if (!placeable(level, support) && fillable(level, corridor, shoulder)
                    && !walkedThrough(flight, shoulder)
                    && !bodyIsInTheWay(player, shoulder)
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
            //
            // BUT THE WORLD HAS TO HAVE HEARD ABOUT IT FIRST. Asking `level` — the ServerLevel — in
            // the placement's own tick answers a question about the past when the bot places over
            // the wire. That is Stop.PENDING's whole subject; the deferral belongs to `lay`, which
            // owns the ticks, so all this can do is decline to call it a refusal.
            if (!level.getBlockState(support).blocksMotion()) {
                if (held && !settled) return new Pass(laid, Stop.PENDING, support.immutable());
                evidence.accept(tag + ".step." + laid, support.toShortString() + " could not be placed ("
                        + (held ? whyNotLaid(level, player, support) : "no cobblestone in hand")
                        + "), bot at " + body.toShortString());
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
                evidence.accept(tag + ".borrowed." + laid, support.toShortString() + " is on the pour"
                        + " ray of cell " + owner.toShortString() + "; this step has no other placement,"
                        + " so it is borrowed for now, and clearPourLine takes it back when that cell"
                        + " is poured");
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
     * stands. It never held for {@link Stop#BODY_IN_THE_WAY}, where the obstruction is the bot's
     * own 0.6-wide box and one cell sideways removes it — and that is the case the run of
     * 2026-08-20 died on, silently, at {@code 0/3}.
     *
     * <p><b>Once.</b> The step-aside is spent the moment a pass makes no progress, and only a pass
     * that DOES make progress hands it back ({@link #lay} passes {@code alreadyAside} as
     * "this pass laid nothing"). So a bot that cannot get off the flight asks twice and stops,
     * which is one more question than before and not a loop — the shape "a retry that changes
     * nothing" warns about is a retry with no bound, not a second attempt at a question whose
     * premise changed.
     *
     * <p>{@link Stop#OUT_OF_REACH} keeps the old answer on purpose. {@link #approach} has already
     * stood the bot at {@link #builderStand}'s nearest cell, so a reach failure means that cell was
     * not near enough — and walking back to the same cell is the retry with no new information.
     */
    static BlockPos stepAsideFor(ServerLevel level, ServerPlayer player, Set<BlockPos> corridor,
                                 List<BlockPos> flight, Pass pass, int from, boolean alreadyAside) {
        if (pass.stop() == Stop.FINISHED) return null;
        BlockPos body = player.blockPosition();
        // Struck out rather than vetoed afterwards: a step aside has to end somewhere the bot is
        // not, so the cell it stands in is not a candidate at all. Vetoing the winner instead threw
        // away the rest of the corridor whenever the tie fell on the bot's own cell.
        BlockPos aside = builderStand(level, corridor, flight, body);
        if (pass.laid() > from) {
            // STEP ASIDE RATHER THAN CLIMB. Climbing onto the course below is what put the bot's own
            // box inside the next step's cell — see approach's note for the two runs that measured
            // it. A stand off the footprint is still legal — the same cell approach chose, unless
            // approach chose the one the bot is in; the climb stays only as the fallback for a
            // corridor that has no such cell, where doing nothing would be worse than doing the
            // thing that sometimes works.
            return aside != null ? aside : flight.get(pass.laid() - 1);
        }
        if (pass.stop() != Stop.BODY_IN_THE_WAY || alreadyAside) return null;
        return aside;
    }

    /**
     * Drive {@link #layWhereItStands}, walking between passes until it has nothing left to try.
     *
     * <p>Two lines here are covered by reading the diff and not by a test, and they are named rather
     * than glossed: the {@link #walkTo} that carries the bot to the cell
     * {@link #stepAsideFor} names, and the recursion that re-enters with the pass's own
     * {@code laid}. {@code wd.rampStepsAsideWhenTheBodyIsInItsOwnStep} drives every other line of
     * this method — the pass, the decision, the one-shot latch — with the walk replaced by putting
     * the bot in the named cell, because a scene cannot host a {@link JourneyRig} and a settle
     * needs one. That substitution is also why {@link #approach} now prints where it went: if the
     * WALK is what fails here, only the run can say so, and until 2026-08-20 it said nothing.
     */
    private static void lay(JourneyRig rig, Set<BlockPos> corridor, List<BlockPos> flight, int from,
                            BlockPos landing, boolean alreadyAside, int settledAt, String tag,
                            Runnable then) {
        ServerLevel level = rig.ctx().level();
        Pass p = layWhereItStands(level, rig.player(), rig.hands(), corridor, flight,
                from, rig::evidence, tag, false);
        if (p.stop() == Stop.PENDING) {
            // ONE WAIT PER COURSE, not one per pass. Every course places exactly once, so a latch
            // spent on the first would leave every course after it judging as early as it did
            // before this existed — the fix would then read as working on course 0 and nowhere else.
            if (settledAt != p.laid()) {
                int course = p.laid();
                rig.settle(new HoldStill(PLACE_ROUND_TRIP), PLACE_ROUND_TRIP * 2,
                        () -> lay(rig, corridor, flight, course, landing, alreadyAside, course,
                                tag, then));
                return;
            }
            // IT HAS HAD ITS ROUND TRIP and the cell is still not solid. Only now is the reason
            // worth printing: whyNotLaid's "now X" clause reads the world at the moment it is asked,
            // so asked any earlier it names the state BEFORE the placement it is adjudicating. j54 has
            // three rows saying "now water" about cells the log shows turning to cobblestone.
            rig.evidence(tag + ".step." + p.laid(), p.at().toShortString() + " could not be placed ("
                    + whyNotLaid(level, rig.player(), p.at()) + "), bot at "
                    + rig.player().blockPosition().toShortString()
                    + " (already waited a " + (PLACE_ROUND_TRIP * 2)
                    + "-tick round trip, so this is not a placement still in flight)");
            p = new Pass(p.laid(), Stop.REFUSED, p.at());
        }
        final Pass pass = p;
        BlockPos to = stepAsideFor(level, rig.player(), corridor, flight, pass, from, alreadyAside);
        if (to == null) {
            rig.evidence(tag + ".laid", pass.laid() + "/" + flight.size() + " steps laid (bot at "
                    + rig.player().blockPosition().toShortString() + ", stopped at " + pass.stop()
                    + (pass.at() == null ? "" : " " + pass.at().toShortString()) + ")");
            walkTo(rig, landing, () -> climbTheFlight(rig, flight, landing,
                    pass.stop() == Stop.FINISHED, tag, then));
            return;
        }
        if (pass.laid() == from)
            rig.evidence(tag + ".aside", "this pass laid no step: the bot at "
                    + rig.player().blockPosition().toShortString() + " overlaps "
                    + pass.at().toShortString() + " (vanilla's isUnobstructed refuses it); moving to "
                    + to.toShortString() + " and asking once more, only once");
        walkTo(rig, to, () -> lay(rig, corridor, flight, pass.laid(), landing,
                pass.laid() == from, -1, tag, then));
    }

    /**
     * Is this cell one the flight itself needs open — a step's standing cell or its head room?
     *
     * <p>Asked only of the shoulder, and it is not defensive. A flight may turn back on itself: the
     * planner picks each course's direction independently, so two courses that go out and back leave
     * {@code support(i).below()} sitting exactly in {@code stand(i-2)}, and a longer fold puts it in
     * that stand's head room. Filling either seals the staircase the bot is about to climb, from
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
     * Whether the bot's own box reaches into {@code cell} — vanilla's question, not a cell name.
     *
     * <p><b>A player is 0.6 wide and a cell is 1.0, so the two questions are different questions.</b>
     * {@code isUnobstructed} refuses a placement whose block shape intersects an entity's bounding
     * box; for the full cube this lays, that is exactly "box ∩ cell ≠ ∅". A player standing at
     * {@code x=2.88} has its box over {@code x∈[2.58, 3.18]} and is therefore inside the cell at
     * {@code x=3} while {@code blockPosition()} still says {@code x=2}.
     *
     * <p>This predicate was already here, and only {@link #whyNotLaid} — an evidence STRING — asked
     * it. {@link #layWhereItStands} classified with {@code support.equals(body)} instead, so a bot
     * a fifth of a cell off centre was reported {@link Stop#REFUSED}, and {@link #stepAsideFor}
     * spends its one step-aside on {@link Stop#BODY_IN_THE_WAY} and nothing else. The remedy was
     * present, correct, and unreachable from the case it was written for. Measured, rehearsal
     * 2026-08-26, the last cell of the ring:
     *
     * <pre>
     * water9.ramp.step.0 = 3, 56, 18 could not be placed (now air, a solid face is available (but
     *                      the bot's own collision box overlaps this cell; vanilla's isUnobstructed
     *                      refuses it, exact bot position 2.88/56.00/18.78)), bot at 2, 56, 18
     * water9.ramp.laid   = 0/4 steps laid (bot at 2, 56, 18, stopped at REFUSED 3, 56, 18)
     * </pre>
     *
     * <p>The same run's cell six is the control: it stopped on {@link Stop#BODY_IN_THE_WAY} at
     * {@code 0/2}, got its step-aside, and the cell went on to pass. Nine of ten cells cast; the
     * one that did not is the one whose obstruction was 0.18 of a block outside its own cell.
     *
     * <p>The box is 1.8 tall, so this subsumes the {@code body.above()} term the cell test carried
     * separately — a standing player's box always reaches its head cell.
     */
    static boolean bodyIsInTheWay(ServerPlayer fp, BlockPos cell) {
        return fp.getBoundingBox().intersects(new AABB(cell));
    }

    /**
     * Why a placement that was attempted did not take — asked of the world, not assumed.
     *
     * <p>This row used to say "no solid face among the six neighbours" unconditionally, and it was
     * wrong often enough to end three rounds of this rung in the wrong place:
     * {@code cell.6.ramp.step.1}, reporting that {@code -8, 57, 37} could not be placed (apparently no
     * solid face among its six neighbours) with the bot at {@code -8, 57, 37}, was printed about a
     * cell the BOT WAS STANDING IN,
     * where the walls had nothing to do with it. Nine of the ten archived {@code .step.N} rows name a
     * cell face-adjacent to the bot at its own feet row, which is where vanilla's
     * {@code isUnobstructed} refuses a placement it has every face it needs for — a player's box is
     * 0.6 wide, so a player a fifth of a cell off centre is inside the cell next door.
     *
     * <p>So both states are measured and named separately. They want opposite work: no face wants a
     * shoulder or a different route, a bot in the way wants one step sideways.
     */
    private static String whyNotLaid(ServerLevel level, ServerPlayer fp, BlockPos cell) {
        String now = "now " + level.getBlockState(cell).getBlock();
        if (!placeable(level, cell))
            return now + ", no solid face among its six neighbours (a placement must click against a face)";
        boolean inTheWay = bodyIsInTheWay(fp, cell);
        if (inTheWay)
            return now + ", a solid face is available (but the bot's own collision box overlaps this"
                    + " cell; vanilla's isUnobstructed refuses it, exact bot position "
                    + String.format("%.2f/%.2f/%.2f", fp.getX(), fp.getY(), fp.getZ()) + ")";
        // THE THIRD QUESTION, and until now there was no third question.
        //
        // Ladder j54 measured NINE ramp attempts and `laid = 0/N` on every one of them — the stair
        // builder is this rung's only way of gaining height, and it never laid a single course all
        // run. Seven of the eight refusals printed "the reason for the refusal is neither of these
        // two; look at the placeOn side" and stopped there, so a total failure of the mechanism
        // produced no diagnosis at all. `placeOn`
        // still reports nothing; what follows is the cheapest set of questions that can tell the
        // remaining suspects apart WITHOUT changing any placement behaviour.
        //
        // Ordered by what j54 makes most likely. The hand comes first because "holding it" and "what
        // the server has in hand" have disagreed on this ladder before — `holdItem` returns true from the client's view
        // while the SWAP click is still in flight, which is the whole of J50 — and a use that fires
        // with the wrong stack is refused exactly like a use that is geometrically impossible.
        StringBuilder more = new StringBuilder();
        var hand = fp.getMainHandItem();
        more.append("server-side main hand=")
                .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(hand.getItem()))
                .append(" ×").append(hand.getCount());
        double reach = fp.getEyePosition()
                .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(cell));
        more.append("; eye to cell centre ").append(String.format("%.2f", reach)).append(" blocks");
        // NOT just the player: `isUnobstructed` refuses for ANY entity in the cell, and a dropped
        // cobblestone from the same flight is exactly the kind of thing that ends up standing in it.
        var others = level.getEntities(fp, new AABB(cell));
        if (!others.isEmpty())
            more.append("; this cell also holds ").append(others.size()).append(" entity(ies) (")
                    .append(others.get(0).getType()).append("); isUnobstructed refuses that as well");
        // WHICH FACES placeInto would have clicked, so "no face to click" and "clicked but refused"
        // stop reading alike.
        //
        // TWO TABLES, NEVER ONE. A face that exists and a face this bot can hit are different
        // findings wanting opposite work — no face at all means take another route, a face the eye
        // cannot reach means move the bot — and folding them into one row is what let
        // `wet.8.ramp.step.3`, which listed "down" as a face to place against, read as "there was a
        // face" about a support whose only
        // solid neighbour was the block directly BELOW it, whose top face is invisible from
        // underneath. The old row asked `blocksMotion()` on the neighbour and nothing else, so it
        // could not make the very distinction the line above says it exists to make.
        StringBuilder solid = new StringBuilder();
        StringBuilder hittable = new StringBuilder();
        var eye = JourneySight.eyeFor(fp, fp.blockPosition());
        for (Direction d : Direction.values()) {
            if (!level.getBlockState(cell.relative(d)).blocksMotion()) continue;
            solid.append(d).append(' ');
            if (canClick(level, fp, eye, cell, d)) hittable.append(d).append(' ');
        }
        more.append("; solid faces ").append(solid.length() == 0 ? "none" : solid.toString().trim())
                .append("; faces this bot's ray can reach ")
                .append(hittable.length() == 0 ? "none" : hittable.toString().trim());
        return now + ", and the bot does not overlap this cell; " + more;
    }

    /**
     * Would a click at the face between {@code cell} and its neighbour toward {@code d} actually land
     * on that neighbour, from where this bot's eye is right now?
     *
     * <p>The same clip a placement runs — {@code OUTLINE}/{@code Fluid.NONE} — and it accepts only a
     * hit on THAT block and THAT face. Anything else means the ray stopped somewhere first, which is
     * a placement the world never sees.
     *
     * <p><b>Aimed at the neighbour's CENTRE, not at the shared face.</b> The face is a boundary
     * plane, so a segment ending exactly on it leaves which block the traversal reports to rounding —
     * a knife edge in the one call whose answer this row is built on. A centre lands well inside, and
     * the ray still has to cross a face to get there, so {@code hit.getDirection()} names it just the
     * same. The predicate is therefore coarse in one known way: it says a face is reachable when the
     * ray reaches ANY point of it, where a real click aims at one point. That is the right side to be
     * coarse on for a post-mortem, and it is stated rather than left to be discovered.
     *
     * <p>Asked of the bot's CURRENT eye, because this is a post-mortem on the placement that just
     * failed and not a search for somewhere better to stand. {@link JourneyStairs#standToPour} and
     * friends do the second job; a row that quietly answered it instead would say a face is reachable
     * from a cell the bot is not in.
     */
    static boolean canClick(ServerLevel level, ServerPlayer body,
                            net.minecraft.world.phys.Vec3 eye, BlockPos cell, Direction d) {
        var aim = net.minecraft.world.phys.Vec3.atCenterOf(cell.relative(d));
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, body));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && hit.getBlockPos().equals(cell.relative(d))
                && hit.getDirection() == d.getOpposite();
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
     * Each entry is a cell the bot STANDS in; the block that has to go under it is its
     * {@code below()}. Consecutive entries are face-adjacent horizontally and one row apart, which is
     * exactly an ordinary walked step-up — no jump, no tower, nothing that needs {@code onGround} to
     * be trustworthy on this player.
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
     *  support is the reserved cell. That pair is the arithmetic behind "redeem, do not refuse" and it
     *  is measured in {@code wd.pourLineHasNoOtherWayUp} rather than argued. */
    static List<BlockPos> planKeeping(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                      BlockPos landing, boolean keepLinesClear) {
        return planKeeping(level, corridor, floorY, landing, keepLinesClear, false);
    }

    /**
     * The same, with the choice of whether a course may double back on the one two below it.
     *
     * <p>{@code mayFold=true} is what this planner did until 2026-08-25 and is now reachable only
     * from {@link #whyNoFlight}, which uses the pair as a discriminant. A landing that has a flight
     * ONLY when folds are allowed was refused by that rule and by nothing about the alcove's walls —
     * and the walls is where the route sentence would otherwise send the reader, which is the exact
     * mistake this method's own note records five instances of.
     */
    static List<BlockPos> planKeeping(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                      BlockPos landing, boolean keepLinesClear, boolean mayFold) {
        List<BlockPos> found = new ArrayList<>();
        return walkDown(level, corridor, floorY, landing, found, keepLinesClear, null, mayFold)
                ? found : null;
    }

    /**
     * One step of the descent. {@code banned} is the one horizontal this level may not take.
     *
     * <p><b>A FLIGHT CAN SEAL ITSELF, and the planner reads a world where none of it exists yet.</b>
     * {@link #standable} asks whether {@code stand} and {@code stand.above()} are clear, of a world
     * where every course is still air — so it can never see that the head room it just approved is
     * where a course two rows up is about to put its own support. The bot then cannot enter its own
     * staircase at course 0, and A* is right to route around it.
     *
     * <p>Measured, ladder {@code journey-n3} 2026-08-25, rung 12 cell {@code wet.8}. Landing
     * {@code 3,60,20}, floor {@code y=56}; the descent went NORTH, NORTH, SOUTH and planned stands
     * {@code 3,57,19 → 3,58,18 → 3,59,19 → 3,60,20}. Course 2's support is {@code 3,59,19.below() =
     * 3,58,19}, which is exactly {@code 3,57,19.above()} — course 0's head room. All five placements
     * went in ({@code [place]} rows with result SUCCESS ×5, shoulder included), the flight was complete, and the
     * {@code goto 3,60,20} that followed walked WEST out of the alcove and finished on the surface at
     * {@code -5,65,20}, 9.85 blocks off. Three retries never came back to the column.
     *
     * <p><b>The rule is local and it is exact.</b> With {@code s(k-1) = s(k).relative(d(k)).below()},
     * {@code support(k) = s(k) - ŷ} can only collide with {@code s(k-2).above() = s(k) + d(k) +
     * d(k-1) - ŷ}: every other stand or head-room cell of the flight differs from it in Y. So the
     * collision is exactly {@code d(k-1) == d(k).getOpposite()}, and refusing that one direction at
     * the moment the child chooses is both sufficient and free of false refusals — the child still
     * has its other three. Checking it on the way back out instead would discard a whole subtree that
     * had other routes left, and refuse flights that exist.
     */
    private static boolean walkDown(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                    BlockPos stand, List<BlockPos> found, boolean keepLinesClear,
                                    Direction banned, boolean mayFold) {
        if (!standable(level, corridor, stand)
                || !supportable(level, corridor, stand.below(), keepLinesClear))
            return false;
        if (stand.getY() == floorY + 1) {
            // The bottom course. Its own support rests on the rock under the alcove, and the bot
            // steps onto it from the floor — so the only thing left to ask is whether the floor
            // beside it is a cell the bot can be standing in when it does. Beside the SUPPORT, not
            // beside the standing cell: a step up starts from the row the block is in, and asking
            // one row too high finds air over air everywhere in a hollow alcove and refuses every
            // flight there is.
            if (!entrance(level, corridor, stand.below())) return false;
            found.add(0, stand);
            return true;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d == banned) continue;
            if (walkDown(level, corridor, floorY, stand.relative(d).below(), found, keepLinesClear,
                    mayFold ? null : d.getOpposite(), mayFold)) {
                found.add(stand);
                return true;
            }
        }
        return false;
    }

    /** Can a bot stand here — inside the corridor, feet and head clear? Fluid is allowed: the
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
     * the lava, so a step built into it would wall the bot into the mould it is casting. And a
     * fluid SOURCE is never buried: the alcove floods with the cast's own bucket, and that bucket
     * has to be scooped back before the next cell — a cobblestone dropped on the source is a water
     * bucket the rung can no longer recover, which surfaces four steps later as "no water bucket in hand".
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
     * {@code cell.8.ramp.step.1} reported that {@code -9,57,36} could not be placed (no solid face
     * among its six neighbours), with the back wall at
     * {@code -9,57,35} carved by {@code stair.14}. Without this the planner keeps choosing that route
     * and the flight dies two courses up, having spent the walk.
     */
    private static boolean placeable(ServerLevel level, BlockPos c) {
        for (Direction d : Direction.values())
            if (level.getBlockState(c.relative(d)).blocksMotion()) return true;
        return false;
    }

    /** Is there a floor cell beside the bottom step for the bot to step up FROM? Asked about the
     *  row the SUPPORT is in — that is the row the bot walks in before the first step. */
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
     * Rehearsal 2026-08-16 printed "not wide enough to turn" five times about landings whose support was simply
     * an air cell facing four more air cells, which is a row that ends a search in the wrong place.
     */
    private static String whyNoFlight(ServerLevel level, Set<BlockPos> corridor, int floorY,
                                      BlockPos landing) {
        if (!corridor.contains(landing) || !corridor.contains(landing.above()))
            return landing.toShortString() + " is not an alcove cell (or its head cell is not)";
        if (level.getBlockState(landing).blocksMotion())
            return landing.toShortString() + " is occupied by " + level.getBlockState(landing).getBlock();
        BlockPos support = landing.below();
        if (!supportable(level, corridor, support))
            return "this cell's own support " + support.toShortString() + " cannot be placed: "
                    + whySupport(level, corridor, support);
        // THE FOLD CLAUSE IS ASKED FIRST AND BY MEASUREMENT, not by argument: re-plan with the rule
        // dropped and see whether a flight appears. It is a separate finding wanting separate work —
        // a route that only folds wants the top courses handed to the path that raises the bot
        // along with the blocks, while a
        // route that has no wall wants a wider alcove — and answering both with the sentence below is
        // the same shape of mistake this method's note already records five instances of.
        if (planKeeping(level, corridor, floorY, landing, false, true) != null)
            return "from floor y=" + floorY + " to y=" + landing.getY()
                    + " the only remaining route folds back on itself (one step doubles back, so its"
                    + " support lands exactly in the head-room cell of the step two below, and the bot"
                    + " cannot even stand on step 0); the staircase cannot be built, and the top steps"
                    + " must use the path that raises the bot along with the blocks instead";
        return "the support is available, but there is no step-by-step route from floor y=" + floorY
                + " to y=" + landing.getY() + ": the alcove is five cells wide with " + corridor.size()
                + " cells, each step can move only one cell, and some step in between has no wall"
                + " to place against";
    }

    /** Why the block under a step cannot go in first — the four ways {@link #fillable} says no. */
    private static String whyShoulder(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        if (!corridor.contains(c)) return "not an alcove cell";
        String flight = JourneyStairs.flightCell(c);
        if (flight != null) return flight + ", so it must not be blocked";
        if (!level.getBlockState(c).canBeReplaced())
            return "is " + level.getBlockState(c).getBlock() + ", cannot place into it";
        if (level.getFluidState(c).isSource())
            return "holds a source block; burying it means this step can no longer recover the water bucket";
        return "should have been placeable; this row should never appear, check fillable";
    }

    /** Why one cell cannot hold a step — one sentence per clause, never one for all four. */
    private static String whySupport(ServerLevel level, Set<BlockPos> corridor, BlockPos c) {
        if (!corridor.contains(c)) return c.toShortString() + " is not an alcove cell";
        String flight = JourneyStairs.flightCell(c);
        if (flight != null) return c.toShortString() + " " + flight + ", so it must not be blocked";
        if (!level.getBlockState(c).canBeReplaced())
            return c.toShortString() + " is " + level.getBlockState(c).getBlock() + ", cannot place into it";
        if (level.getFluidState(c).isSource())
            return c.toShortString() + " holds a source block; burying it means this step can no longer"
                    + " recover the water bucket";
        StringBuilder around = new StringBuilder();
        for (Direction d : Direction.values())
            around.append(' ').append(d).append('=').append(level.getBlockState(c.relative(d)).getBlock());
        // BOTH ROUTES, because both are now tried. Saying only the first is what made 43 of 51
        // refusals read as a fact about the alcove's walls when the walls were never the whole
        // question — see the class note. The shoulder's own clause says which of the four ways it is
        // barred, so a reader can tell a stair cell from a source from a cell outside the corridor.
        BlockPos shoulder = c.below();
        return c.toShortString() + " has no solid face among its six neighbours (a placement must"
                + " click against a face):" + around
                + "; the shoulder " + shoulder.toShortString() + " cannot be placed first either ("
                + whyShoulder(level, corridor, shoulder) + "); both routes are blocked";
    }

    /**
     * The flight as a row of cells — <b>the SUPPORTS, not the stands</b>, because those are the cells
     * this rung places and the ones a {@code [place]} row can be lined up against.
     *
     * <p>Named because the difference costs a round every time. {@link #plan} says each entry is a
     * cell the bot stands in and it is right; this prints {@code below()} of each, so
     * {@code wet.8.ramp.flight = … → 3, 59, 20} is course 3's SUPPORT and its stand is
     * {@code 3,60,20}. Reading the row as stands puts every headroom question one row off, which is
     * exactly the reasoning that has to be right for {@link #walkDown}'s fold rule to be checkable
     * from a log.
     *
     * <p>Package-private, and named for what it returns rather than for what it does, because the
     * two scene files that assert against {@code ramp.flight} each carried a byte-identical copy
     * under exactly that name. Nothing here is a judgment — it only formats — so the scenes reading
     * one printer instead of three cannot make an assertion looser: it makes their evidence rows and
     * this rung's evidence rows the same rows.
     */
    static String supports(List<BlockPos> flight) {
        StringBuilder out = new StringBuilder();
        for (BlockPos s : flight)
            out.append(out.isEmpty() ? "" : " → ").append(s.below().toShortString());
        return out.toString();
    }

    /**
     * The courses still to be walked, in order — <b>the decision {@link #climbTheFlight} makes</b>,
     * separated out so a scene can drive it. Returns the tail of {@code flight} above the highest
     * course the bot is already standing on, and an EMPTY list when it is already on the last one.
     *
     * <p>Package-private and pure for the reason {@link #stepAsideFor} is: a settle needs a
     * {@link JourneyRig} and a scene cannot host one, so the only part of this climb a scene can judge
     * is the part that decides. {@code wd.rampClimbsTheFlightItJustLaid} asserts against this.
     *
     * <p>Exact-cell matching, deliberately, and not "every course at or below the bot's row". The
     * bot that this climb exists for is one row up and several columns OUT — {@code cast8.lift.rampedY
     * = 57/59} (stopped at 1, 57, 19, wanted foot cell 3, 59, 19, not the same column) — so a row
     * test would skip course 0
     * ({@code 2,57,18}, the same row) and send the bot at the course above it, which is the one cell
     * it cannot reach in a single step. The cell it is in is the only thing that says it is on the
     * staircase.
     */
    static List<BlockPos> coursesToClimb(List<BlockPos> flight, BlockPos body) {
        int start = 0;
        for (int i = 0; i < flight.size(); i++)
            if (flight.get(i).equals(body)) start = i + 1;
        return new ArrayList<>(flight.subList(start, flight.size()));
    }

    /**
     * Walk UP the staircase this rung just built, one course at a time.
     *
     * <h2>The flight was complete and the bot never got on it</h2>
     *
     * <p>{@link #lay} finishes by issuing one {@code Goal.Block(landing)} at the TOP of the flight.
     * That goal is answered by A*, which is free to route anywhere — and out of a hollow alcove the
     * cheapest route to a cell four rows up is very often over the rim and back down, i.e. not the
     * staircase at all. {@link #walkDown}'s own note records the first measurement of this on
     * 2026-08-25: five placements in, the flight complete, and the {@code goto 3,60,20} that followed
     * "walked WEST out of the alcove and finished on the surface at {@code -5,65,20}, 9.85 blocks
     * off". The fold rule that came out of that run fixed the PLANNER; this climb was never touched.
     *
     * <p>The real ladder of 2026-08-27 produced the second instance, and it is the one that stopped
     * the ring at nine cells of ten:
     *
     * <pre>
     * cast8.lift.flight   = 3 steps: 2, 56, 18 → 3, 57, 18 → 3, 58, 19 (alcove floor y=56, bot at 1, 57, 19)
     * cast8.lift.laid     = 3/3 steps laid (bot at 1, 57, 19, stopped at FINISHED)
     * cast8.lift.rampedY  = 57/59 (stopped at 1, 57, 19, wanted foot cell 3, 59, 19, not the same column)
     * cast8.liftTower     = the staircase could not be built past y=57; handing over to the tower as a fallback
     * cast8.lift#11.verdict = not built: landed in 0,19 instead of the assigned column 1,19; air
     *                         underfoot, not a floor
     * </pre>
     *
     * <p>Every course went in and the bot did not move one cell. The escalation to a tower that
     * follows is downstream of that: the tower drifted into {@code 0,19}, deadlocked on two mutually
     * inverse column rewrites, and the pour that inherited it fired from four cells outside the
     * alcove — three times, all three correctly refused by the pour's own ray gate.
     *
     * <p><b>Why a course at a time answers a question the single goal cannot.</b> Each course is one
     * step from the one below it, so the goal is a cell the walker either steps onto or does not; it
     * has no room to leave the alcove looking for a cheaper approach. It is the same argument
     * {@code JourneyStairwell}'s {@code flightLastStep} already makes about the last step of a
     * descent, and {@link JourneyPour#raiseTo}'s {@code ceilingTax} makes about {@code Goal.XZ}.
     *
     * <p><b>It costs nothing when the ordinary path works.</b> The single {@code walkTo(landing)}
     * still runs first and is still what normally arrives — {@code cast7.ramp.laid = 2/2 … FINISHED}
     * then {@code cast7.ramp.rampedY = 58/58 (… same column)} on the same run — and this climb only starts
     * when that one did not. A flight that is not {@link Stop#FINISHED} is not climbed at all: the
     * missing course is where the walk would stop anyway, and saying so is cheaper than walking into
     * it.
     *
     * <p><b>It stops at the first course it cannot reach</b>, rather than trying the rest from below
     * it. A course above an unreachable one is further away, not nearer, so continuing would be the
     * retry that asks the same question — and the row that says which course stopped it is what the
     * next run needs. {@link #done} then reports the position honestly, exactly as before.
     */
    private static void climbTheFlight(JourneyRig rig, List<BlockPos> flight, BlockPos landing,
                                       boolean complete, String tag, Runnable then) {
        BlockPos now = rig.player().blockPosition();
        if (now.equals(landing) || flight.isEmpty()) {
            done(rig, landing, tag, then);
            return;
        }
        if (!complete) {
            // NAMED, not silent. Without this row a flight that was never finished and one whose
            // climb was not attempted reach the results file as the same thing — a `laid` short of
            // the total and a `rampedY` shortfall — and they want different work.
            rig.evidence(tag + ".climbSkipped", now.toShortString() + " is not on the foot cell "
                    + landing.toShortString() + ", but this staircase is incomplete; not climbing step"
                    + " by step, because the missing step is where a walk would stop anyway");
            done(rig, landing, tag, then);
            return;
        }
        List<BlockPos> courses = coursesToClimb(flight, now);
        StringBuilder route = new StringBuilder();
        for (BlockPos c : courses)
            route.append(route.isEmpty() ? "" : " → ").append(c.toShortString());
        // THE STANDS, not `supports()`. That printer takes `below()` of each entry because a
        // `[place]` row can be lined up against a support, and these are the cells the bot walks
        // INTO — reading one row off is the mistake `supports`'s own javadoc says costs a round.
        rig.evidence(tag + ".climbFlight", now.toShortString() + " is not on the foot cell "
                + landing.toShortString() + ", and all " + flight.size()
                + " steps are laid; climbing step by step instead (" + courses.size()
                + " step(s) left, foot cells: " + route + ")");
        climbCourse(rig, courses, 0, landing, tag, then);
    }

    /** One course of {@link #climbTheFlight}, and the recursion that stops at the first miss. */
    private static void climbCourse(JourneyRig rig, List<BlockPos> courses, int i, BlockPos landing,
                                    String tag, Runnable then) {
        if (i >= courses.size()) {
            done(rig, landing, tag, then);
            return;
        }
        BlockPos course = courses.get(i);
        walkTo(rig, course, () -> {
            BlockPos now = rig.player().blockPosition();
            if (!now.equals(course)) {
                rig.evidence(tag + ".climbStopped." + i, "step " + i + " " + course.toShortString()
                        + " was not reached, stopped at " + now.toShortString() + " (underfoot "
                        + rig.ctx().level().getBlockState(now.below()).getBlock() + ", in "
                        + rig.ctx().level().getBlockState(now).getBlock()
                        + "); the steps above are only further away, so none are attempted");
                done(rig, landing, tag, then);
                return;
            }
            rig.evidence(tag + ".climbed." + i, course.toShortString() + " reached");
            climbCourse(rig, courses, i + 1, landing, tag, then);
        });
    }

    /** How far the flight got, as a fraction — the number to grep across runs. A raise that stopped
     *  one course short and one that never started read alike from a landing height alone. */
    private static void done(JourneyRig rig, BlockPos landing, String tag, Runnable then) {
        BlockPos now = rig.player().blockPosition();
        rig.evidence(tag + ".rampedY", now.getY() + "/" + landing.getY() + " (stopped at "
                + now.toShortString() + ", wanted foot cell " + landing.toShortString()
                + (now.getX() == landing.getX() && now.getZ() == landing.getZ() ? ", same column"
                        : ", not the same column; the ray was computed for that column") + ")");
        then.run();
    }
}
