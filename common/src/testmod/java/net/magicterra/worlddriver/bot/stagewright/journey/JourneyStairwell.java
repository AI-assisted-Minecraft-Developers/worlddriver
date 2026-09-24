package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/**
 * The portal rung's staircase as a ROUTE — cut it, and walk it in both directions.
 *
 * <p>Moved out of {@link JourneyPortalRung} the same mechanical way {@link JourneyStairs} and
 * {@link JourneyDrain} were, and for the same reason: that file reached its 3000-line budget while
 * the rung's live edge — the casting and the doorway — is still in it. Nothing changed in the move.
 * Every member is verbatim, every evidence key is the same string, and the only edits are the
 * qualifications a second file forces ({@code JourneyPortalRung.forgeCorridor}) plus the members
 * the rung still calls, which are package-private here rather than private.
 *
 * <p>The seam is the one the rung's own halves already had: this side is WALKING — a flight cut out
 * of rock, and the twenty legs the ten casts spend going up it and coming back down — while what
 * stays is the MOULD, which walks nowhere. Three files now share the staircase: this one cuts it and
 * walks it, {@link JourneyStairs} holds its cells and asks whether it can still be walked, and
 * {@link JourneyDrain} asks what is standing in its foot.
 */
final class JourneyStairwell {

    private JourneyStairwell() {}

    /** Where the staircase starts and ends, and which way it runs.
     *
     * <p>Both ends are walked to BY NAME — {@link #goUpToThePool} asks for {@link #stairTop} and
     * {@link #returnToTheForge} for {@link #stairBottom} — which is the whole point of cutting a
     * staircase instead of a shaft: the two legs of every cast become one {@code IntentProcess} walk
     * each, with no scripted climb and no scripted descent to go wrong between them.
     *
     * <p>Package-private since the drain moved to {@link JourneyDrain}: the stair foot is the one
     * coordinate every file that reads it has to agree on, and a copy would be a second author
     * for it. */
    static BlockPos stairTop, stairBottom;

    /** Package-private since the mould moved to {@link JourneyPortalRung}: the carve takes the
     *  staircase's OWN direction rather than deriving a second answer, which is what keeps the
     *  alcove from being cut back across the way home. */
    static Direction stairDir = Direction.SOUTH;

    /** Every fourth step is waypoint enough: consecutive waypoints are four blocks apart INSIDE the
     *  stairwell, and there is no shorter way between two such cells that leaves it. */
    private static final int STAIR_WAYPOINT_STRIDE = 4;

    /**
     * The flight as a list of waypoints, top-first when {@code down}.
     *
     * <p>Waypoints down the flight ITSELF, not just its two ends, and that is a measurement rather
     * than a precaution. Given {@code Goal.Block(stairBottom)} the walker takes the shortest line it
     * can see, and from the top of the stairs the shortest line is ACROSS THE SURFACE: measured
     * twice, the body walked to {@code -10,66,34} — ground level directly above the staircase at
     * z=34 — and then re-searched for a cell eight blocks below it through untouched rock, every two
     * seconds, until the leg ran out. Naming the mouth fixed the first half and the second half did
     * it again from the mouth. These are what make the staircase the route and not merely a hole
     * that happens to connect two places.
     *
     * <p><b>The flight ends on the lowest step a body can stand on, which is not always its bottom.</b>
     * See {@link JourneyStairs#lowestDryStep} for the nine casts that bought this: the mould's own
     * runoff floods the bottom step from the row the pour is aimed at, the pour climbs, and a waypoint
     * whose cell and head room are both water cannot be walked to at all — the walk stops four cells
     * short and the rung reads that as a failure to walk back into the mould. Ending one step early
     * costs nothing when the
     * bottom is dry, because then it IS the bottom.
     *
     * <p>Waypoints below the chosen end are dropped rather than kept: the stride can step past it
     * ({@code cells[8]} is below a terminal at {@code cells[7]}), and a route that visits a cell after
     * its own destination is a route back down into the water.
     */
    static List<BlockPos> stairRoute(ServerLevel level, boolean down) {
        List<BlockPos> cells = JourneyStairs.cells;
        int end = JourneyStairs.lowestDryStep(level);
        if (end < 0) end = cells.size() - 1;
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < end; i += STAIR_WAYPOINT_STRIDE) out.add(cells.get(i));
        BlockPos last = cells.get(end);
        if (out.isEmpty() || !out.get(out.size() - 1).equals(last)) out.add(last);
        if (!down) java.util.Collections.reverse(out);
        return out;
    }

    /**
     * Walk the waypoints in order, best effort. A leg that falls short is not failed here — the
     * caller checks the height it actually reached, which is the only thing that matters.
     *
     * <p><b>The walk may not dig.</b> {@link NoBreak} is on every one of these intents, and it is
     * there because of what happened without it. On the run that stopped this rung at four casts,
     * the body left the flight on a return leg, fell through the lake's one-block crust into the
     * cave below it, and — with the pathfinder free to plan digs — mined its way back to daylight
     * through {@code -9,64,22}: the block holding up its own second step. Every later ascent then
     * walked into a two-deep hole where a step used to be, and the rung reported that the bot could
     * not climb the stairs, about a staircase it had eaten itself.
     *
     * <p>These legs cross ground the rung cut with its own pick and nothing else, so a dig here is
     * never the answer to anything. A leg that genuinely cannot get through now stalls instead,
     * which {@link #walkHome}'s pillar-out recovery already handles and {@link JourneyStairs#faults} now
     * names.
     */
    private static void walkTheStairs(JourneyRig rig, List<BlockPos> route, int i, boolean down,
                                      JourneyTerrain.RimTax tax, Runnable then) {
        if (i >= route.size()) { then.run(); return; }
        BlockPos want = route.get(i);
        // The tax rides EVERY waypoint, not only the overland first one. Inside the stairwell it
        // costs nothing to carry: JourneyTerrain#onThePoolsLip only counts a cell whose neighbour
        // opens onto a column that falls to lava within LIP_DEPTH, and the flight is cut walking
        // AWAY from the pool with rock on both sides, so no step of it is in the set. Narrowing the
        // tax to i==0 would buy nothing and would go wrong the first time a fill breaks the wall.
        rig.settle(new IntentProcess(new Intent(new Goal.Block(want), tax.bias(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), 600, () -> {
            BlockPos got = rig.player().blockPosition();
            double off = Math.sqrt(got.distSqr(want));
            // BOTH CELLS, not just the one the bot is standing in. `cast5.returnStopped` reported the
            // bot stopped at -9,66,21 with cobblestone below and air in its cell, above its head and
            // in the jump clearance — four cells all clear, on a walk that moved zero blocks. They
            // were clear because the bot had climbed through them one waypoint earlier; the cell that
            // stopped it is the one it was trying to REACH, and that cell was not in the message.
            if (off > LEG_ARRIVED && flightShortfall == null)
                flightShortfall = "segment " + i + "/" + (route.size() - 1) + ": aimed for "
                        + want.toShortString() + ", stopped at " + got.toShortString() + ", "
                        + String.format("%.2f", off) + " blocks short - bot's cell: "
                        + cellStory(rig.ctx().level(), got, !down) + "; target cell: "
                        + cellStory(rig.ctx().level(), want, !down);
            walkTheStairs(rig, route, i + 1, down, tax, then);
        });
    }

    /**
     * How far from a waypoint still counts as having arrived. A body standing on the right cell
     * reads one cell off when its 0.6-wide box straddles the edge — the {@code cell.5.standMissed}
     * lesson — and a leg that stops a cell short has still walked the flight.
     *
     * <p><b>With {@code LEG_ARRIVED = 1.5}, the log has never distinguished whether the bot stepped
     * down the last step or not.</b> The
     * flight's last waypoint IS {@link #stairBottom}, and a body that stops in the cell directly above
     * it is exactly 1.0 away — inside this tolerance, so the leg reports arrival and
     * {@code returnStopped} never fires. Measured over four rehearsals: {@code returnedY} is 57 on
     * some casts and 56 on others, {@code returnStopped} appears zero times in any of them, and the
     * raise that follows starts from whichever row that was — which is the difference between the
     * cell pouring and the cell being lost. Three rounds of this rung's investigation went into water
     * models before anyone read this constant.
     *
     * <p><b>This is a fact about the ladder, not about the rehearsal.</b> The same coin is flipped on
     * a real climb by the same line, and the log says nothing there either. Narrowing the tolerance is
     * NOT the answer taken here: this is shared walking code and every caller would feel it. See
     * {@link #landOnFloor} for the rehearsal lever that makes the losing side reproducible first.
     */
    private static final double LEG_ARRIVED = 1.5;

    /**
     * The FIRST leg of the current flight that did not arrive, or null. Reset per flight.
     *
     * <p>Both ends of the flight used to read only the height the body finished at, which says the
     * walk failed and nothing about where. The run of 2026-08-15 failed the ascent with
     * {@code lava0.upEnded=-9,56,36} — the stair bottom, which is the FIRST of five waypoints — and
     * the message it produced (the bot could not climb the stairs and stopped at a given cell) reads
     * identically whether the bot never
     * left the alcove or climbed four fifths of the flight and stalled.
     */
    private static String flightShortfall;

    /**
     * What holds a cell up, what fills it, and its head room — plus, going UP only, the cell a body
     * must jump THROUGH, which is the last line of {@code StepUp.valid}.
     *
     * <p>{@code upward} is not decoration. Those four cells are {@code StepUp.valid}'s question, and
     * {@code StepUp} takes no part in a descent — so printing the jump clearance on the walk back
     * down answers a question nobody asked and reads like an all-clear. That is exactly how
     * `cast5.returnStopped` certified a bot that had not moved: jump clearance air, on a walk that
     * never needed to jump.
     */
    private static String cellStory(ServerLevel level, BlockPos foot, boolean upward) {
        String story = "below " + level.getBlockState(foot.below()).getBlock() + ", in cell "
                + level.getBlockState(foot).getBlock() + ", above "
                + level.getBlockState(foot.above()).getBlock();
        if (!upward) return story;
        BlockPos jump = foot.above(2);
        return story + ", jump clearance " + jump.toShortString()
                + "=" + level.getBlockState(jump).getBlock()
                + (level.getBlockState(jump).blocksMotion() ? " (blocked, cannot jump)" : "");
    }

    /**
     * Put back what the flight has lost, then walk it.
     *
     * <p>Both legs of every cast go through here rather than straight into {@link #walkTheStairs},
     * because the damage is done BETWEEN legs and the only cheap moment to notice is just before
     * the flight is used. {@link JourneyStairs} holds the audit and the repair.
     */
    private static void walkTheFlight(JourneyRig rig, String tag, boolean down, Runnable then) {
        flightShortfall = null;
        JourneyStairs.aboutToWalk(rig, tag);
        List<JourneyStairs.StairFault> faults = JourneyStairs.faults(rig.ctx().level());
        // UNCONDITIONAL. This row used to be written only when `faults` was non-empty, which made it
        // a row that success could not produce — and a pre-registered criterion of the form
        // "`stairsBroken` must read 0/N" was therefore unsatisfiable: a healthy run wrote nothing,
        // which is indistinguishable from an audit that never ran. Measured on ladder-11
        // (2026-08-23), where the `NoBreak` fix DID hold the flight and the only way to say so was
        // to triangulate from `forge.carved 67/67` and the body having walked back down. Twenty
        // quiet rows across a rung are a small price for a row that can say "checked, and fine".
        rig.evidence(tag + ".stairsBroken", JourneyStairs.report(rig.ctx().level()));
        // THE RIM, PRICED FOR THIS FLIGHT. The first waypoint of the down route is the stairwell
        // mouth and the body reaches it across open ground beside the lake — which is the leg that
        // killed ladder j39 (`cast1.return`, `-8,64,14` → `-8,66,19`, dead at `-8,66,10` of
        // `lava −4.0×3; onFire −1.0×2`). It ran untaxed because the tax was built inside the
        // approach and never left it, while the file's own recovery javadoc thirty lines down
        // already said in words that "the walk back crosses the lake's own rim".
        //
        // Recomputed per flight, not carried from the approach: see JourneyTerrain#avoidTheRim —
        // the fills and the cleared aim lines keep opening new ways in, so the count on this row is
        // expected to CLIMB across a rung's returns, and a flat one is the finding.
        JourneyTerrain.RimTax tax = JourneyTerrain.avoidTheRim(rig.ctx().level(), lavaPool);
        rig.evidence(tag + ".rimTax", tax.story());
        List<BlockPos> route = stairRoute(rig.ctx().level(), down);
        // SAY IT WHEN THE FLIGHT IS SHORTENED, and only then. The route ending one step high is the
        // difference between a leg that comes home and a leg that stops four cells short of a
        // waypoint made of water, and without this row the two produce identical logs.
        BlockPos bottom = JourneyStairs.cells.get(JourneyStairs.cells.size() - 1);
        BlockPos ends = down ? route.get(route.size() - 1) : route.get(0);
        if (!ends.equals(bottom))
            rig.evidence(tag + ".flightEnd", "terminal waypoint raised from the stair bottom "
                    + bottom.toShortString() + " to " + ends.toShortString()
                    + " - the stair bottom cannot be stood on: "
                    + cellStory(rig.ctx().level(), bottom, false));
        // THE LAST STEP IS WALKED, not tolerated — going down only. See finishTheFlight.
        Runnable done = down ? () -> finishTheFlight(rig, tag, ends, then) : then;
        if (faults.isEmpty()) { walkTheStairs(rig, route, 0, down, tax, done); return; }
        JourneyStairs.mend(rig, tag, faults, 0,
                () -> walkTheStairs(rig, route, 0, down, tax, done));
    }

    /**
     * Stand ON the cell the flight ends at, rather than within {@link #LEG_ARRIVED} of it.
     *
     * <p><b>The arrival tolerance and the height test disagree about the last step, and moving the
     * terminal up one row is what made them disagree fatally.</b> While the flight ended at
     * {@link #stairBottom} the disagreement was harmless: the tolerance ball around a terminal on the
     * floor row holds only cells at {@code floorY} and {@code floorY+1}, and {@link #walkHome}
     * accepts both. Ending one step early moves that ball up a row, and now HALF of it — the
     * terminal's own head room, and the step above it at 1.41 — is {@code floorY+2}, which
     * {@code walkHome} rejects as a failure to walk back into the mould.
     *
     * <p>Measured on the rung-12 rehearsal of 2026-08-25, first cast, which is where the flight first
     * shortens (the mould's runoff wets the bottom step on every return — the reclaim happens after
     * the descent, so the pour is live for the whole trip):
     *
     * <pre>
     * cast0.flightEnd  = terminal waypoint raised from the stair bottom 2, 56, 19 to 1, 57, 19
     *                    - the stair bottom cannot be stood on: … in cell water …
     * cast0.returnedY  = 58 (stair bottom y=56, bot at 1, 58, 19)   ← rejected
     * cast0.landing    = exact 1.09/58.00/19.51, onGround=true      ← standing, not falling
     *                    every segment arrived                      ← and the flight reported success
     * </pre>
     *
     * The bot was on the step ABOVE the terminal — {@code x=1.09}, a 0.6-wide box straddling two
     * columns, so {@code blockPosition()} rounds into the terminal's column while the feet rest on
     * {@code 0,58,19}. Three readings that each look like an all-clear on their own; only together do
     * they say "walked the whole flight and stopped one step short".
     *
     * <p><b>Not solved by narrowing {@link #LEG_ARRIVED}</b>, whose own javadoc refuses that: it is
     * shared walking code and every caller would feel it. Solved by walking the one step, which is
     * the same leg {@link #landOnFloor} stages for the rehearsal — that lever exists precisely
     * because this coin was already known to be flipping, and it aims at {@code stairBottom} rather
     * than at wherever the flight actually ends.
     *
     * <p>Silent when the body is already at or below the terminal's row: on a dry flight this is the
     * step the tolerance let it skip, and skipping it was never wrong there.
     *
     * <p><b>The first leg is not enough, and the ladder of 2026-08-25 is why.</b> It moved the body
     * from {@code 0,58,19} to {@code 1,58,19} — into the terminal's column, still a row above it —
     * on both casts, and printed {@code flightLastStepMissed} both times before walking on:
     *
     * <pre>
     * cast0/1.flightLastStep       = 0, 58, 19 → 1, 57, 19
     * cast0/1.flightLastStepMissed = 1, 58, 19 still not on the terminal waypoint 1, 57, 19
     *                                - below stone, in cell air, above air
     * cast0/1.returnedY            = 58                                    ← rejected, rung dead
     * </pre>
     *
     * The terminal is standable — the row says so. The body is balanced on the lip of the step above:
     * exact {@code x=1.20}, a 0.6-wide box spanning {@code [0.90, 1.50]}, overlapping the previous
     * step's tread {@code [0, 1]} by a tenth of a block, which is enough for {@code onGround}. It
     * needs one tenth more (box min ≥ the terminal column's edge) to lose that support and drop.
     *
     * <p><b>A second leg aimed one step further down was tried and REMOVED</b>, 2026-08-25.
     * {@code wd.journeyWalksOffTheLipOntoTheDryStep} refuted it twice over:
     *
     * <ul>
     *   <li><b>Constructively wrong target.</b> {@code ends} is {@link JourneyStairs#lowestDryStep},
     *       so by definition every step below it is wet — the step below is the very water the
     *       terminal was raised to avoid ({@code Block{minecraft:water}} in the cell on its own
     *       row).</li>
     *   <li><b>Actively harmful.</b> Measured: the walk spent its whole 200-tick budget
     *       ({@code end=unavailable/...}: the process was still running when the budget ran out)
     *       walking the bot two cells BACK and one row UP the staircase — {@code 100511,218} to
     *       {@code 100509,219}. It turned "one row above the terminal" into "two", which is strictly
     *       worse for the {@code walkHome} that follows.</li>
     * </ul>
     *
     * <p>The reason the earlier version of this note gave for the second leg — that re-asking for the
     * terminal is a no-op because 0.58 &lt; {@link #LEG_ARRIVED} — was <b>wrong about the
     * mechanism</b>: {@code Goal.Block.reached} is {@code p.equals(target)}, an exact cell and not a
     * ball. What the first leg actually reports is {@code end=path-consumed} with no error and the bot
     * still on the lip: <b>a path was produced and walked to its end, and the body never arrived</b>.
     * That is TODO J47's signature, and this scene is its second witness — a dry one, with no
     * floating body anywhere in it, which is what makes it worth more than the water case it
     * consolidates with.
     *
     * <p>So the one leg stays, and it stays because of what it RECORDS: {@code flightLastStep} says
     * the step was needed, {@code flightLastStepEnd} says what the walker did about it, and
     * {@code flightLastStepMissed} says it did not land. Before those rows existed, a step that was
     * never needed and a step that was needed and refused left the same log.
     */
    static void finishTheFlight(JourneyRig rig, String tag, BlockPos ends, Runnable then) {
        BlockPos here = rig.player().blockPosition();
        if (down(here, ends)) { then.run(); return; }
        rig.evidence(tag + ".flightLastStep", here.toShortString() + " → " + ends.toShortString()
                + " (the " + LEG_ARRIVED + "-block arrival tolerance counted this step as arrived;"
                + " walking it to the end here; " + landingStory(rig) + ")");
        // TURN THE BODY BEFORE ASKING IT TO WALK. This is the whole step, and every term of it was
        // measured on `wd.journeyWalksOffTheLipOntoTheDryStep` with walker rows on:
        //
        //   path = 0:99999,218[-] 1:99998,218[walk] 2:99999,217[stepDown]   goalReached=true
        //   t=1..3  node=99999,217  yaw=0  bear=-90  yawErr=-90  driveYaw=0  up=true
        //           p=(99999.20,218.00,100000.50 → .60 → .70)   hCol=true
        //
        // A* plans it, the walker presses forward — and the bot walks 90° off, into +z, until it
        // hits a wall. The heading never turns because `WalkerTickAim` holds it: the aim vector to
        // the node is (0.30, 0.00), and `YAW_DEADZONE_SQ = 0.25` (half a block) makes
        // `targetYaw = p.getYRot()` — the bot's CURRENT yaw, whatever it is. That dead-zone is
        // correct and is not being fought here: its javadoc names this very manoeuvre ("during a
        // vertical manoeuvre the bot sits almost directly over its target column, so adx/adz hover
        // near zero and atan2 on that sub-block noise snaps the yaw ±90 every tick").
        // It holds the current heading — so the fix is to make the current heading the right one.
        //
        // `aimBoth` and not a bare `setYRot`, because the ladder's judge drives a client player and
        // a server-side rotation does not survive the next packet — see JourneyHands' own note.
        float yawWas = rig.player().getYRot();
        JourneyHands.aimBoth(rig, ends);
        rig.evidence(tag + ".flightLastStepAim", String.format(java.util.Locale.ROOT,
                "yaw %.1f° → %.1f° (aimed at the centre of terminal waypoint %s; the dead zone holds"
                + " the bot's current yaw, so the turn has to happen before the walk starts, not be"
                + " left to the walker mid-walk)",
                yawWas, rig.player().getYRot(), ends.toShortString()));
        rig.settle(lastStep(ends), LAST_STEP_TICKS, () -> {
            BlockPos got = rig.player().blockPosition();
            // WHAT THE WALKER SAID, on both outcomes. `settle` walks carry no `end=`/`err=` of their
            // own the way `drive` walks do, so a walk that ended because A* found no path and one
            // that ended having walked read identically — which is how "a repeated walk does not
            // move" stood as a mechanism for a whole ladder without ever being checked.
            rig.evidence(tag + ".flightLastStepEnd", JourneyLeg.walkerEnd(rig));
            // ⚠️ NOT `down(got, ends)` ON ITS OWN. That reads the BLOCK row, and a bot's feet cross
            // into the terminal's row while it is still most of a block above that row's FLOOR —
            // it is the cell index that is low enough, not the bot. Measured across eight rung-12
            // rehearsals, byte-identical: `flightLastStepEnd=path-consumed`, `down` true at
            // `1,57,20`, and `landing` an instant later reading `exact 1.53/57.92/20.51,
            // onGround=false` — 0.92 of a block still to fall.
            // The pour that follows decides its aim from THAT eye (`fromHere.3`, eye y=59.54, which
            // validates a shot at the target's own floor) and fires 0.69 of a block lower
            // (`picks.3`, eye y=58.85), by which point the same ray enters the backing through its
            // WEST face instead of its top and the lava lands one cell short, in `3,56,19`. The wait
            // below already existed; it never ran, because this line had already judged the bot low
            // enough.
            //
            // The ladder run is the control, and it only reached the wait by accident: its last-step
            // walk ran out of budget (`end=unavailable`), leaving `got` at `ends.above()`, so it
            // waited, landed at `57.00`, and its FIRST shot hit — `1.84/58.62/20.50`, landing in
            // `4,57,19`.
            //
            // The quantity is a POSITION on purpose. `onGround` is rejected twelve lines down for a
            // reason that still holds — it describes the previous `move()`, not what is underfoot —
            // and that objection does not touch a coordinate.
            double afootBy = rig.player().getY() - ends.getY();
            boolean afoot = afootBy > SETTLED_SLACK;
            if (down(got, ends) && !afoot) { then.run(); return; }
            // A BOT ONE ROW ABOVE THE TERMINAL MAY BE FALLING INTO IT, and this callback is the
            // wrong tick to ask. Measured on the rung-12 rehearsal of 2026-08-25, whose last walker
            // row before this judgment put the bot at cell 1,58,20, exact (1.454,58.000,20.500),
            // cur2=0.002, solid-under-feet fraction 0.0000, cause=within — horizontally on the
            // terminal's centre with nothing under the feet — and one second later the bot was two
            // rows lower and walking on. The step HAD been walked. Judging it a miss cost
            // `returnedY=58`, which bought a `returnStuck` tower that then ate the rung's whole
            // 6503-tick budget in the pour's own water.
            //
            // Narrow on purpose: only a bot standing in the terminal's OWN head room waits. Any
            // other cell means the bot is somewhere else, and giving it time would be widening the
            // criterion rather than reading it at the right moment.
            //
            // Not `onGround`: that same row read `onGround=true` beside a solid-under-feet fraction of
            // 0.0000, because it describes the previous `move()` and not what is under the bot now.
            if (got.equals(ends.above()) || afoot) {
                rig.settle(new HoldStill(LAND_TICKS), LAND_TICKS * 2, () -> {
                    BlockPos after = rig.player().blockPosition();
                    // WHETHER THE WAIT CHANGED THE ANSWER, always. A step that was already walked
                    // and one where the bot is genuinely balanced on the lip above must not leave
                    // the same log — that is the whole reason this branch is allowed to exist.
                    // PRINT THE HEIGHT, NOT ONLY THE CELL. `down(after, ends)` was ALREADY true on
                    // the `afoot` path — the bot was inside the terminal and falling — so a row that
                    // only says it landed in the terminal cannot tell "waited until it landed" from
                    // "was never moving", and those are the two readings this branch exists to
                    // separate. The number that decides it is the bot's exact Y above the terminal
                    // row's floor: 0.92 → 0.00 is a fall that finished, 0.92 → 0.92 is a bot
                    // resting on something at that height.
                    rig.evidence(tag + ".flightLastStepSettled", got.toShortString() + " → "
                            + after.toShortString() + " (waited " + LAND_TICKS + " ticks for the fall"
                            + " to land; "
                            + String.format(java.util.Locale.ROOT,
                                    "height above the terminal row's floor %.2f → %.2f blocks, on entry %s",
                                    afootBy, rig.player().getY() - ends.getY(),
                                    afoot ? "falling (the case this row exists for)"
                                          : "resting on the lip of the step above")
                            + ") - " + (down(after, ends)
                                    ? "landed in the terminal waypoint; the step had been walked all along"
                                    : "did not move; the bot really is resting on the lip of the step above"));
                    sayMissedUnlessLanded(rig, tag, after, ends, then);
                });
                return;
            }
            sayMissedUnlessLanded(rig, tag, got, ends, then);
        });
    }

    /** How long a body falling INTO the terminal gets to land before {@link #finishTheFlight} asks
     *  again. Three times the fall of one block from rest, and the leg it follows has already spent
     *  its own budget — so this cannot pass off a walk that never happened as one that did. */
    private static final int LAND_TICKS = 20;

    /** How far above the terminal row's floor a body may rest and still count as having arrived.
     *
     *  <p>A quarter of a block: every vanilla thing a body can legitimately stand on inside a cell
     *  either sits at the floor or a full step above it, so nothing benign lands in between — while
     *  the case this separates was measured at <b>0.92</b>, a body whose feet had entered the row
     *  and had almost the whole block left to fall. Deliberately not {@code onGround}: that flag
     *  describes the previous {@code move()} (see {@link #finishTheFlight}'s own note), and a body
     *  that has just been placed reads {@code true} while resting on nothing. */
    private static final double SETTLED_SLACK = 0.25;

    /** SAY SO WHEN IT DID NOT LAND. A leg that quietly fails leaves {@code returnedY} to report the
     *  same row it would have reported without {@link #finishTheFlight}, and the reader cannot tell a
     *  step that was never needed from one that was needed and refused. */
    private static void sayMissedUnlessLanded(JourneyRig rig, String tag, BlockPos got, BlockPos ends,
                                              Runnable then) {
        if (!down(got, ends))
            rig.evidence(tag + ".flightLastStepMissed", got.toShortString()
                    + " still not on the terminal waypoint " + ends.toShortString() + " - "
                    + cellStory(rig.ctx().level(), ends, false) + "; " + landingStory(rig));
        then.run();
    }

    /** Is the body at the terminal, or already past it downward? The flight only ever needs to get
     *  DOWN to a row — {@link #walkHome} judges the row and not the cell — so a body that fell past
     *  the terminal has nothing left to walk. */
    private static boolean down(BlockPos here, BlockPos ends) {
        return here.equals(ends) || here.getY() <= ends.getY();
    }

    /** One step of the flight, walked rather than pathed around: no breaking, because the cells are
     *  the rung's own staircase and a leg that mines its way down destroys the way back up. */
    private static IntentProcess lastStep(BlockPos to) {
        return new IntentProcess(new Intent(new Goal.Block(to), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak())));
    }

    /** How long the last step-down gets. One ordinary +(-1) step inside a flight the body has just
     *  walked the whole of — the same reasoning, and the same number, as {@link #FLOOR_LEG_TICKS}. */
    private static final int LAST_STEP_TICKS = 200;

    /**
     * Rehearsal only: finish the last step-down the flight's arrival tolerance let it skip.
     *
     * <p><b>What it pins.</b> The row this rung's raise starts from is decided by whether the descent
     * actually stepped into {@link #stairBottom} or stopped in the cell above it — and
     * {@link #LEG_ARRIVED} passes both off as arrivals, so the two look identical in the log and turn
     * up roughly one run in three at eleven minutes a run. Landing on the floor row is the losing side
     * ({@code cast8#1.fromY=56}, {@code climb.0 onGround=false}, washed off three times, the pin
     * adopted twice, the pour's ray gate correctly refusing the diagonal that made), so it is the side
     * that has to be reachable on demand — the same argument that bought {@code -PforgeAway} and
     * {@code -PshaftColumn}.
     *
     * <p><b>Nothing is edited: not a block, not a fluid.</b> This walks. That is deliberate and it is
     * the whole difference from the version before it, which cleared the water out of the stairwell's
     * foot on the theory that a flooded foot floats the body one row up. <b>That theory is dead</b>,
     * and its own staging rows are what killed it: on eight of ten casts the lever found exactly ONE
     * wet cell (one cell drained, {@code [2,56,19]}) — the two cells above it were already air — and a body
     * cannot float in a cell of air, yet {@code returnedY} was 57 all eight times. The correlation
     * failed at both ends too: the leg that cleared nothing at all returned 56, the one that cleared
     * two returned 56, and the ones that cleared one or three returned 57. Backfill is real (a live
     * connected body sits one cell away, {@code drain.6/7/9 = 3,56,19 = water}) and it explains only
     * why clearing did not stick — never which row the body ended on. So this lever cannot be eaten by
     * backfill, because it does not fight the water at all.
     *
     * <p><b>Two locks, the same two {@link JourneyStairs#aboutToWalk} and
     * {@link JourneyShaft#floodTheColumnOnce} carry</b>: off unless asked for, and refused outright
     * when no rung is being rehearsed — so on the real ladder this is unreachable, not merely unused.
     * It is a {@link JourneyLedger#staged} call as well, so a run that somehow did it anyway could
     * never report {@code staging.calls=0}.
     *
     * <p><b>It does not fix the coin.</b> The ladder flips it too, from the same line, just as
     * silently; this only makes the losing side reproducible so the remedy can be measured against it.
     * See {@link #LEG_ARRIVED}.
     *
     * <p><b>How to tell it worked, and it is not "the property was read".</b> The downstream quantity is
     * {@code cast*.returnedY}: with the flag on, EVERY cast must report the floor row and
     * {@code cast8#1.fromY} must be that row. The other half is the run with the flag OFF, which must
     * still show the natural 57/56 mixture — a lever that quietly moved the baseline would pass the
     * first half and fail nothing, which is how {@code -PforgeAway} once ran four directions and wrote
     * the same {@code forge.away} four times, and how the water version passed every check except the
     * one that mattered.
     */
    private static void landOnFloor(JourneyRig rig, String tag, Runnable then) {
        if (!Boolean.getBoolean("worlddriver.journey.landOnFloor")) { then.run(); return; }
        if (JourneyRehearsal.target() == null || stairBottom == null) {
            // ARMED AND UNABLE, which is not the same as OFF. The exit above is the flag-off
            // baseline and stays silent on purpose — it is what the javadoc says the measurement is
            // read against. This one is the lever switched ON and doing nothing, and it used to
            // leave the same trace as the baseline: none. A run comparing the two would then have
            // scored an unarmed lever as a working one that changed nothing.
            //
            // WHICH of the two is missing, not just "not done": a rehearsal that never set a target
            // and a return that never recorded its bottom step are different bugs with different
            // fixes.
            String missing = JourneyRehearsal.target() == null
                    ? (stairBottom == null
                            ? "the rehearsal has no target rung, and no stair bottom was recorded"
                            : "the rehearsal has no target rung")
                    : "no stair bottom was recorded";
            rig.evidence(tag + ".floorLegUnarmed",
                    "landOnFloor is on but cannot add the final step-down walk: " + missing);
            then.run();
            return;
        }
        BlockPos here = rig.player().blockPosition();
        if (here.equals(stairBottom)) {
            JourneyLedger.staged("rehearsal: the return already ended on the bottom step "
                    + stairBottom.toShortString() + " — nothing to finish");
            rig.evidence(tag + ".floorLeg", "already on the stair bottom " + stairBottom.toShortString()
                    + ", no extra step-down walk needed (rehearsal only)");
            then.run();
            return;
        }
        JourneyLedger.staged("rehearsal: walked the last step-down that LEG_ARRIVED=1.5 let the flight"
                + " skip, " + here.toShortString() + " → " + stairBottom.toShortString());
        rig.evidence(tag + ".floorLeg", here.toShortString() + " → " + stairBottom.toShortString()
                + " (rehearsal only: the last segment's 1.5-block arrival tolerance counts stopping on"
                + " the step above as arrived, so this walks that step to the end; no block and no"
                + " fluid is changed)");
        rig.settle(new IntentProcess(new Intent(new Goal.Block(stairBottom), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), FLOOR_LEG_TICKS,
                () -> rig.settle(new HoldStill(10), 30, () -> {
            // SAY SO WHEN THE WALK DID NOT LAND IT. A lever that silently fails reads like one that
            // worked: rows that only report what the lever did never say that the bot's row did not
            // change. And this particular miss is worth more than the lever — it is the reading
            // that finally names what is in the bottom step's cell when the bot cannot get into it.
            if (!rig.player().blockPosition().equals(stairBottom)) {
                rig.evidence(tag + ".floorLegMissed", rig.player().blockPosition().toShortString()
                        + " still not on the stair bottom " + stairBottom.toShortString()
                        + " after the extra step-down walk - " + landingStory(rig));
            }
            then.run();
        }));
    }

    /** How long the staged step-down gets. One ordinary +(-1) step inside a flight the body has just
     *  walked the whole of, so a leg that needs longer than this is not slow, it is blocked — and the
     *  {@code .floorLegMissed} row is the answer worth having. */
    private static final int FLOOR_LEG_TICKS = 200;

    /**
     * Where the body actually ended the descent, and what is under it — always, for every return.
     *
     * <p><b>Zero behaviour, and the most valuable line of this change.</b> {@code returnedY} prints a
     * BlockPos, and a BlockPos cannot tell "standing on the bottom step" from "floating one cell above
     * it" from "in mid-air and not yet landed" — three worlds that want three different remedies.
     * Three rounds of this rung went
     * into water models that all fitted the rows there were, and every one of them would have died in
     * one run against {@code onGround} plus the two cells' block and fluid states. The precise
     * position matters for the same reason it did in {@code JourneyRamp#approach}: a 0.6-wide box a
     * fifth of a cell off centre rests on the cell next door, so "bot at cell 2,57,19" and "x=2.37"
     * are different facts and only the second one says what is holding it up.
     */
    private static String landingStory(JourneyRig rig) {
        ServerLevel level = rig.ctx().level();
        var body = rig.player();
        return String.format(java.util.Locale.ROOT,
                "exact %.2f/%.2f/%.2f, onGround=%s, inWater=%s; stair bottom %s=%s%s, above it %s=%s%s",
                body.getX(), body.getY(), body.getZ(), body.onGround(), body.isInWater(),
                stairBottom.toShortString(), level.getBlockState(stairBottom).getBlock(),
                fluidStory(level, stairBottom), stairBottom.above().toShortString(),
                level.getBlockState(stairBottom.above()).getBlock(),
                fluidStory(level, stairBottom.above()));
    }

    /** The fluid in one cell, named rather than left to a Fluid's own toString — and "no fluid" when
     *  there is none, because an absent fluid is a reading too. */
    private static String fluidStory(ServerLevel level, BlockPos c) {
        var fluid = level.getFluidState(c);
        if (fluid.isEmpty()) return " (no fluid)";
        return " (" + (fluid.isSource() ? "source " : "flowing ")
                + BuiltInRegistries.FLUID.getKey(fluid.getType()) + ")";
    }

    /**
     * Cut the way down as a STAIRCASE, and let the walker use it in both directions.
     *
     * <h2>What this replaces, and why</h2>
     *
     * A one-wide vertical shaft. It was the cheapest hole to dig and it has no route: the walker
     * cannot climb one, so both legs of every cast had to be scripted, and each scripted leg failed
     * in its own way. Four separate bugs, all of them the shaft's:
     *
     * <ul>
     *   <li><b>The tower drifted.</b> {@code TowerProcess} places under the body and jumps, and where
     *       the body lands is pinned to nothing. Measured: {@code -9,51,21} to {@code -9,54,23} in
     *       three courses — two cells into the frame's own plane, which it then mined out and filled
     *       with cobblestone on the way up.</li>
     *   <li><b>The drift breached the lake.</b> Off the shaft the tower had to mine fresh rock, and
     *       twelve blocks up that rock is the lava the mould is cut under. {@code drain.0} reported
     *       fluid still present after a 200-tick wait, {@code -7,54,21 = lava}, in an alcove far
     *       below it, with the corridor set to stone where the lava met the cast's own water.</li>
     *   <li><b>The descent needed the bot exactly over the hole.</b> The return reported that the bot
     *       had ended up outside the alcove, stopped at {@code -9,60,20}, one cell off and nine
     *       blocks up, with nowhere legal to dig.</li>
     *   <li><b>And when it dug anyway, it dug outside the alcove</b> — sixty passes of
     *       {@code below=stone → broke=air} at {@code -8,51,20}, because the lava that the breach had
     *       let in kept flowing back and setting.</li>
     * </ul>
     *
     * <p>A staircase costs more blocks and more ticks than a shaft and it is worth it: this rung has
     * a 250 000-tick budget and has never spent a third of it. What it buys is that the descent, the
     * ascent and the return are the same three cells of ordinary walking, so none of the four
     * failures above has anywhere to happen.
     *
     * <h2>The shape</h2>
     *
     * One block along {@code stairDir}, one block down, per step. Three cells are cut for each: the
     * step itself, the cell above it (head room standing there) and the cell above that. The third is
     * not spare — going back UP, the body jumps from a step to the one behind it, and a jump needs
     * clearance two above the feet it starts from. Leaving it out gives a staircase that descends
     * perfectly and cannot be climbed, which is the same rung failure wearing a different hat.
     *
     * <p>Nothing is cut that touches a fluid, checked cell by cell before the pick swings — the
     * lesson of the breach above, applied to the leg that does the digging rather than to the leg
     * that discovered it.
     */
    static void digStairsDown(SceneContext ctx, JourneyRig rig, int targetY, int budget,
                              int cap, Runnable then) {
        BlockPos body = rig.player().blockPosition();
        // THE BOTTOM IS THE BODY'S, ALWAYS. `carveTheForge` hollows the alcove from
        // `blockPosition()`, so a bottom declared for a cell the body is merely OVER puts the alcove
        // a row above its own staircase. Measured twice on the south geometry, 2026-08-17, when this
        // read the anchored cell instead: `stairs.bottom = -9, 56, 31` against `forge.landedY = 57`,
        // and the second of those runs then failed on the first waypoint of the first ascent —
        // `segment 0/3: aimed for -9, 56, 31, stopped at -9, 61, 32` — because the two no longer met.
        if (body.getY() <= targetY) {
            stairBottom = body;
            rig.evidence("stairs.bottom", body.toShortString() + " (heading " + stairDir + ", top at "
                    + (stairTop == null ? "?" : stairTop.toShortString()) + ")");
            then.run();
            return;
        }
        // WHERE THE FLIGHT IS, not where the body reads. A body one row over the step it has just cut
        // is falling into it, and a course measured from there stacks two steps in one column — see
        // JourneyStairs#courseFrom for the pair that made the audit unsatisfiable on both arms.
        //
        // Only WHILE THE FLIGHT IS STILL ABOVE THE FLOOR, though. Anchoring the last course would aim
        // it below the target, and the first version of this instead treated the anchored cell as the
        // bottom and waited for the body to drop into it — which it never did, because the body was
        // standing on a step that had not opened. The run spent its whole 40000-tick budget in that
        // hold and produced no `stairs.bottom` at all, while the course the ORIGINAL code would have
        // cut (`-9, 57, 31 → -9, 56, 32`, one cell along the other axis) is the one every healthy
        // south run in the archive got to the floor by.
        BlockPos anchored = JourneyStairs.courseFrom(body);
        final BlockPos at = anchored.getY() <= targetY ? body : anchored;
        if (budget <= 0) {
            ctx.fail("staircase did not reach the bottom: target y=" + targetY + ", still at "
                    + at.toShortString() + " after " + cap + " step attempts (heading " + stairDir + ")");
            return;
        }
        int step = cap - budget;
        // ONE STEP, OR TWO WHEN ONE HAS ALREADY BEEN REFUSED. See noteStairWedge: a walk that ends
        // `path-consumed` with the body half a block short of a step it has verified open is the
        // walker calling a partial path an arrival, and asking it again is the retry that changes
        // nothing — measured as eighty identical legs, twice.
        //
        // Cutting the NEXT step too and aiming at THAT changes the question rather than relaxing it:
        // the goal moves from one cell across and one down — close enough that the walker's own
        // arrival tolerance swallows it — to two and two, which no tolerance can call reached from
        // here. Every cell of both steps is still cut, so the flight the return legs walk is the
        // same flight; the body simply does not stop on the first of them.
        int stride = wedgedHere(at) && at.getY() - 2 >= targetY ? 2 : 1;
        BlockPos foot = at.relative(stairDir, stride).below(stride);
        List<BlockPos> cut = new ArrayList<>();
        for (int s = 1; s <= stride; s++) {
            BlockPos f = at.relative(stairDir, s).below(s);
            cut.add(f);
            cut.add(f.above());
            cut.add(f.above(2));
        }
        for (BlockPos c : cut) {
            String wet = JourneyShaft.fluidTouching(ctx.level(), c);
            if (wet != null) {
                ctx.fail("cannot dig the staircase further: opening " + c.toShortString()
                        + " would release " + wet + " (bot at " + at.toShortString()
                        + ", digging down toward " + stairDir + " to y=" + targetY
                        + ") - there is fluid behind this stretch of rock, so it must not be opened");
                return;
            }
        }
        rig.evidence("stair." + step, at.toShortString() + " → " + foot.toShortString()
                + (stride > 1 ? " (the previous step was refused three times, so this attempt cuts"
                        + " two steps at once and aims directly at the second)" : ""));
        if (!at.equals(body))
            // SAY WHICH OF THE TWO, do not assert the falling one. The anchor is unconditional now,
            // so this fires whenever the body is anywhere other than the deepest step — including the
            // case it was built for, a body that has not moved off the course above yet, which is NOT
            // "falling into" anything. Claiming that would be a row asserting a mechanism it never
            // checked, the trap this rung has paid for repeatedly.
            rig.evidence("stair." + step + ".fromStep", "bot reads as " + body.toShortString()
                    + "; this step is measured from the deepest step of the flight, "
                    + at.toShortString() + " ("
                    + (body.getX() == at.getX() && body.getZ() == at.getZ()
                            ? "same column, bot " + (body.getY() - at.getY())
                                    + " row(s) above it, falling in"
                            : "bot is still in another column - most likely it has not stepped down yet")
                    + ") - otherwise the next step would land in the same column, or re-cut the step above");
        for (int s = 1; s <= stride; s++) JourneyStairs.cut(at.relative(stairDir, s).below(s));
        cutStairCells(rig, cut, 0, () ->
                rig.settle(new IntentProcess(new Intent(new Goal.Block(foot))), 300, () -> {
            BlockPos now = rig.player().blockPosition();
            if (now.getY() >= at.getY()) {
                // The cells are open and the body has not stepped into them yet. That is a settle,
                // not a failure — the same "breaking the floor is not falling through it" the shaft
                // descent learned — so give it the tick and ask again. It is `courseFrom`, not this
                // branch, that decides where the retry measures from: this comment used to claim the
                // same step was retried and the retry re-read `blockPosition()`, so a body that had
                // drifted one cell sideways into the new step's head room cut a course out of THAT.
                rig.evidence("stair." + step + ".waited", now.toShortString() + " has not stepped down yet");
                noteStairWedge(rig, now, foot);
                rig.settle(new HoldStill(20), 40,
                        () -> digStairsDown(ctx, rig, targetY, budget - 1, cap, then));
                return;
            }
            stairWaits = 0;
            digStairsDown(ctx, rig, targetY, budget - 1, cap, then);
        }));
    }


    /** Consecutive "has not stepped down yet" waits taken from the same cell — see
     *  {@link #noteStairWedge}.
     *
     *  <p>Package-private for the one writer outside this file: the descent resets both of them
     *  together, right before the first {@link #digStairsDown}, and a counter left over from the
     *  previous staircase would make the first step of the next one cut two treads at once. */
    static int stairWaits;
    static BlockPos stairWaitedAt;

    /** How many identical waits it takes before one of them is worth explaining — and before the
     *  step is cut differently. Three: one is the settle this branch was written for, two is a slow
     *  world tick, and three is a body that is not going to step down at all. */
    private static final int STAIR_WEDGE_WAITS = 3;

    /** Has this exact cell already refused the step {@link #STAIR_WEDGE_WAITS} times? */
    private static boolean wedgedHere(BlockPos at) {
        return at.equals(stairWaitedAt) && stairWaits >= STAIR_WEDGE_WAITS;
    }

    /**
     * Why the flight's next step is not being taken — written once, not eighty times.
     *
     * <p>{@code stair.N.waited} says the body is still on the step above and nothing else, and the
     * run of 2026-08-17 printed <b>eighty of them</b>, byte-identical
     * ({@code stair.0..79 = -8, 66, 19 → -7, 65, 19},
     * {@code stair.N.waited = -8, 66, 19 has not stepped down yet}), before failing because the
     * staircase did not reach the bottom: still at {@code -8, 66, 19} after 80 step attempts. Eighty
     * rows, one sentence, and
     * at least four worlds produce it: the three cells were never cut, the step below has no floor so
     * the goal cell is not standable at all, a route exists and the body cannot walk it, or the body
     * is simply still falling. They want four different answers and the row could not pick.
     *
     * <p>So this prints the walker's own end reason beside the four cells that decide whether the
     * step exists — and the capability flags, because a goal the body would have to BREAK its way to
     * is reachable or not depending on a global this rung turns off on its way down.
     */
    private static void noteStairWedge(JourneyRig rig, BlockPos now, BlockPos foot) {
        if (!now.equals(stairWaitedAt)) { stairWaitedAt = now; stairWaits = 0; }
        if (++stairWaits != STAIR_WEDGE_WAITS) return;
        ServerLevel level = rig.ctx().level();
        rig.evidence("stair.wedged", String.format(java.util.Locale.ROOT,
                "%s did not move a single cell over %d consecutive walks (exact %.2f/%.2f/%.2f);"
                + " target %s; %s; the step's four cells: support %s=%s, foot %s=%s, head %s=%s,"
                + " jump clearance %s=%s; canBreak(foot)=%s, allowBreak=%s allowPlace=%s",
                now.toShortString(), STAIR_WEDGE_WAITS,
                rig.player().getX(), rig.player().getY(), rig.player().getZ(),
                foot.toShortString(),
                JourneyLeg.walkerEnd(rig),
                foot.below().toShortString(), level.getBlockState(foot.below()).getBlock(),
                foot.toShortString(), level.getBlockState(foot).getBlock(),
                foot.above().toShortString(), level.getBlockState(foot.above()).getBlock(),
                foot.above(2).toShortString(), level.getBlockState(foot.above(2)).getBlock(),
                rig.body().avatar().canBreak(foot),
                BotConfig.allowBreak, BotConfig.allowPlace));
    }

    private static void cutStairCells(JourneyRig rig, List<BlockPos> cells, int i, Runnable then) {
        if (i >= cells.size()) { then.run(); return; }
        // Top down. The cell two above the step is the one the body can already see; opening it
        // first keeps every following cell adjacent to air, which is what `canBreak` asks for.
        BlockPos c = cells.get(cells.size() - 1 - i);
        if (rig.ctx().level().getBlockState(c).isAir()) { cutStairCells(rig, cells, i + 1, then); return; }
        rig.mineCellOrGiveUp(c, 600, () -> cutStairCells(rig, cells, i + 1, then));
    }

    /** Attempts per block of depth. Four, because a step is three mines and a walk, and a body that
     *  has not stepped down yet costs one of its own. */
    static final int STAIR_ATTEMPTS_PER_BLOCK = 4;

    /**
     * Walk up to the pool. An ordinary walk, up an ordinary staircase.
     *
     * <p>{@link JourneyShaft#climbOut} used to do this and does not any more — see
     * {@link #digStairsDown} for the four bugs that were all really one bug. The goal is the
     * staircase's own top cell rather than the lake or a Y level, because a named waypoint is a
     * question the pathfinder can answer in one search, and "get to y=63 somehow" is the question
     * that burnt a whole 100 000-node budget every three seconds.
     */
    static void goUpToThePool(SceneContext ctx, JourneyRig rig, int poolY, String tag,
                              Runnable then) {
        if (rig.player().blockPosition().getY() >= poolY - 1) { then.run(); return; }
        if (stairTop == null) {
            ctx.fail("no stair top coordinate: descendToTheForge did not record one, so the bot"
                    + " cannot climb up to fill the bucket with lava");
            return;
        }
        // THE CELL THE ASCENT STARTS ON, told by the same function the DESCENT's end is told by.
        // The descent gets a check here and the ascent does not: `flightEnd` lifts the down route's
        // last waypoint OFF the stair foot when that cell cannot be stood on (the stair bottom
        // reported as unstandable, with water in the cell), while the ascent simply begins wherever
        // the bot is — and on the fatal
        // shape of `lava2.upStopped` that is the very same flooded cell, 2,56,20. Whether starting
        // there is what stops the climb is not yet decided; what IS decided is that the two ends of
        // one staircase were reporting to different standards, so the ascent could not be compared
        // with the descent that was fixed. ⛔ This is the READING only — do not copy the descent's
        // lift up here before it has said something.
        BlockPos from = rig.player().blockPosition();
        rig.evidence(tag + ".up", from.toShortString() + " → stair top " + stairTop.toShortString()
                + "; starting cell: " + cellStory(rig.ctx().level(), from, true));
        walkTheFlight(rig, tag, false, () -> {
            BlockPos here = rig.player().blockPosition();
            rig.evidence(tag + ".upEnded", here.toShortString() + " (stair top "
                    + stairTop.toShortString() + ")");
            if (flightShortfall != null) rig.evidence(tag + ".upStopped", flightShortfall);
            if (here.getY() < poolY - 1) {
                // The audit, not a guess. The two guesses this message used to make — "a step is
                // blocked?" "it cannot make the jump?" — were both wrong the one time the message
                // was read: the flight had lost the block UNDER a step, which is invisible from
                // above because the cell reads air whether or not anything holds it up.
                ctx.fail("could not climb the stairs: stopped at " + here.toShortString()
                        + ", stair top " + stairTop.toShortString() + " is at y=" + stairTop.getY()
                        + " - " + (flightShortfall == null ? "every segment arrived" : flightShortfall)
                        + "; stair audit: " + JourneyStairs.report(ctx.level())
                        + " (audits this rung: " + JourneyStairs.tally() + ")");
                return;
            }
            then.run();
        });
    }

    /**
     * Walk back down to the mould. The mirror of {@link #goUpToThePool}, and equally unremarkable.
     *
     * <p>Placing stays OFF across it, which is the one thing this leg still has to say: everything
     * downstream is a pour, and a pathfinder that paves the alcove on its way in takes away the cell
     * the pour has to stand in.
     */
    static void returnToTheForge(SceneContext ctx, JourneyRig rig, int floorY, String tag,
                                 Runnable then) {
        returnToTheForge(ctx, rig, floorY, tag, RETURN_TRIES, then);
    }

    /** How many times the return may try. Three: one for the walk and two for a body that fell into
     *  the hole its own bucket left in the lake — see the recovery leg below. */
    private static final int RETURN_TRIES = 3;

    private static void returnToTheForge(SceneContext ctx, JourneyRig rig, int floorY, String tag,
                                         int tries, Runnable then) {
        BotConfig.allowPlace = false;
        BlockPos at = rig.player().blockPosition();
        // HOME IS THE ALCOVE, not a height. Height was a fine proxy while the only way to be in the
        // mould was to be standing on its floor, and `liftInPlace` broke that: a body up a
        // one-block pillar beside the cell it just poured into is at y=58 in a mould whose floor is
        // y=56, so run 41 — which had cast NINE cells, every fill from the station and not one
        // drowning — reported that the bot could not walk back into the mould and had stopped at
        // -11,58,36, about a bot already standing in it.
        if (at.getY() <= floorY + 1 || JourneyPortalRung.forgeCorridor.contains(at)) { then.run(); return; }
        if (stairBottom == null) {
            ctx.fail("no stair bottom coordinate: descendToTheForge did not record one, so the bot"
                    + " cannot return to the mould");
            return;
        }
        rig.evidence(tag + ".return", at.toShortString() + " → stairwell mouth " + stairTop.toShortString()
                + " → stair bottom " + stairBottom.toShortString() + " (mould floor y=" + floorY + ")");
        // LET THE LAKE CLOSE FIRST. A filled bucket takes a source out and leaves an air cell in the
        // middle of the lava, and air is what the pathfinder plans through — so the route home ran
        // straight into the hole the fill had just made, and the lava flowed back in on top of the
        // body. Sixty ticks is longer than lava takes to spread one cell, so by the time the route
        // is planned the pit reads as what it is.
        rig.settle(new HoldStill(60), 80, () -> walkHome(ctx, rig, floorY, tag, tries, then));
    }

    private static void walkHome(SceneContext ctx, JourneyRig rig, int floorY, String tag,
                                 int tries, Runnable then) {
        // VIA THE STAIRWELL MOUTH, not straight at the bottom. The bottom is ten blocks down at the
        // far end of fifteen steps, and asked for directly the walker takes the shortest line it can
        // see — overland. Measured: the body walked to -9,66,34, which is the surface directly ABOVE
        // the staircase at z=34, and then re-searched for -9,57,36 every two seconds from a spot
        // separated from it by eight blocks of untouched rock. The stairs are a corridor and a
        // corridor is entered at its mouth; naming the mouth turns one impossible search into two
        // easy ones.
        walkTheFlight(rig, tag, true, () -> landOnFloor(rig, tag, () -> {
            BlockPos here = rig.player().blockPosition();
            rig.evidence(tag + ".returnedY", here.getY() + " (stair bottom y=" + stairBottom.getY()
                    + ", bot at " + here.toShortString() + ")");
            // ALWAYS, flag or no flag. See landingStory: a BlockPos alone cannot tell standing on the
            // bottom step from floating over it from still falling into it.
            rig.evidence(tag + ".landing", landingStory(rig));
            // AND WHETHER THE FOOT OF THE FLIGHT IS UNDER WATER, as its own row. See stairFootStory:
            // it is buried inside `landing` today, at the end of a format string about the body, and
            // the one run that needed it read past it.
            rig.evidence(tag + ".stairFoot", JourneyDrain.stairFootStory(ctx.level()));
            if (flightShortfall != null) rig.evidence(tag + ".returnStopped", flightShortfall);
            if (here.getY() > floorY + 1) {
                // A BODY THAT CANNOT WALK TO THE STAIRS IS USUALLY IN A HOLE IT DUG ITSELF.
                //
                // Filling a bucket takes a lava SOURCE out of the lake, which leaves a pit where a
                // source used to be — and the walk back crosses the lake's own rim. Run 33 measured
                // it at the third cast: `lava2.aimsAt=-11,63,21` on a lava source block, `CONSUME`, and then
                // `cast2.return` starting from `-11,60,21` — three blocks under the surface, inside
                // the pit, with placing off so it could not build its way out. Every waypoint of the
                // flight then failed in turn and the rung reported the last one.
                //
                // So the recovery is the one thing the descent deliberately forbids: let it place.
                // That is safe HERE and nowhere else — the mouth is ten blocks above the alcove and
                // fifteen across, so a pillar built to climb out of the lake cannot land in the room
                // the pours have to stand in, and the leg turns placing off again on its way down.
                if (tries > 1) {
                    rig.evidence(tag + ".returnStuck." + tries, here.toShortString()
                            + " cannot reach the stairs (most likely fell into the pit its own bucket"
                            + " emptied in the lava) - pillaring back up to ground level y="
                            + stairTop.getY() + " with placing allowed, then walking to the stairwell mouth "
                            + stairTop.toShortString());
                    // TOWER, not walk. Asking the walker again is a retry that changes nothing: it
                    // failed because there is no walkable route out of a pit, and run 34 measured
                    // exactly that — `cast2.returnStuck.2` at `-10,60,21`, then two thousand ticks of
                    // pathing, then the same cell. What gets a body out of a hole is the scripted
                    // pillar the shaft already owns, and it needs placing, which is why this leg is
                    // the one place in the casting phase that turns it back on.
                    BotConfig.allowPlace = true;
                    JourneyShaft.climbOut(rig, stairTop.getY(), tag + ".returnStuck" + tries, () ->
                        rig.settle(new IntentProcess(new Intent(new Goal.Block(stairTop))), 2_000, () -> {
                            rig.evidence(tag + ".backToMouth", rig.player().blockPosition().toShortString()
                                    + " (stairwell mouth " + stairTop.toShortString() + ")");
                            returnToTheForge(ctx, rig, floorY, tag, tries - 1, then);
                        }));
                    return;
                }
                ctx.fail("could not walk back into the mould: stopped at " + here.toShortString()
                        + ", stair bottom " + stairBottom.toShortString() + " is at y=" + stairBottom.getY()
                        + " - stopped halfway with a bucket of lava, and pouring it here would only pour"
                        + " it into the staircase. "
                        + (flightShortfall == null ? "every segment arrived" : flightShortfall)
                        + "; stair audit: " + JourneyStairs.report(ctx.level()));
                return;
            }
            then.run();
        }));
    }

    /**
     * The lake this rung is working, kept for the legs that run after the approach.
     *
     * <p>The rim tax needs a centre, and only {@code JourneyPortalRung.descendToTheForge} is handed
     * one. The flight between the stairwell and the fill station runs a dozen times per rung out of
     * {@link #walkTheStairs}, which has no lava in scope and until 2026-08-24 therefore priced the
     * crater at nothing — see {@link JourneyTerrain#avoidTheRim}. Null outside the rung, and the
     * helper answers {@code List.of()} for a null pool rather than throwing, so a flight that
     * somehow runs before the descent walks untaxed exactly as it did before.
     */
    static BlockPos lavaPool;
}
