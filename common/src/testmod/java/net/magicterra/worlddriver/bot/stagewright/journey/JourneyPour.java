package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where to stand to empty a bucket into the mould, and how to get the body there.
 *
 * <p>Moved out of {@link JourneyPortalRung} the same mechanical way that rung was moved out of
 * {@code WorldDriverJourneyScenes}, and for the same reason: the file was at its 3000-line source
 * budget, so the next edit to it had to be a split, and a split is cheapest when nothing else is
 * riding on it. <b>Nothing changed in the move</b> — same methods, same order, same judgements; the
 * only edits are the qualifications a second file forces ({@code JourneyPortalRung.forgeCorridor},
 * {@code JourneyPortalRung.POUR_LINE}) and the handful of members the rung still calls, which are
 * package-private here rather than private.
 *
 * <p>This is the POUR half. {@link JourneyFill} is the scoop half and was split out first; the two
 * ask opposite questions of the same geometry, which is the boundary they are cut along —
 * {@code Fluid.NONE} against the target's backing here, {@code Fluid.SOURCE_ONLY} into the target's
 * own fluid there. {@link JourneySight} holds the one predicate both of them were getting wrong.
 */
final class JourneyPour {

    private JourneyPour() {}

    /**
     * Get the body up to the row it is about to pour into, building the step if there is none.
     *
     * <p>The alcove is hollow, so the only solid floor in it is the one seven cells down — and a
     * bucket aimed from there at a cell four rows up traces a line that leaves the frame's plane
     * before it reaches the backing. Worked through for this mould: from {@code y=51} the ray to the
     * backing of {@code y=55} enters the plane at {@code y=53} and lands on the backing of the
     * interior cell two rows low, so {@link #standToPour} rejects every candidate and the rung
     * stops on its own gate. Rows up to {@code y=54} are reachable from the floor and the top pair
     * is not, which is why the ladder has never yet been stopped by this: no run had ever cast
     * eight cells.
     *
     * <p>So the step is BUILT, out of the cobblestone the rung is already carrying, by the same
     * scripted tower that leaves the shaft — and taken down again by nothing, because the corridor
     * is where the next pours stand and {@link JourneyPortalRung#clearPourLine} owns that problem. Best-effort: a
     * body that cannot get up says so and lets the pour's own ray gate decide, which is the only
     * gate in this rung entitled to spend a bucket.
     *
     * <p><b>The real ray is asked before any prediction about where to stand.</b> This used to decide
     * on {@link #standToPour} alone, while {@link #aimThatLandsIn} — the shot {@code .picks} fires,
     * from the eye the body actually has — ran afterwards, inside the pour:
     * 权威的测试跑在它本该决定的那个决定之后。A raise spent for a cell the body could already pour
     * into is not free, because the tower behind it builds into whatever column it is given; see
     * {@link JourneyStairs#needsOpen} and the CHANGELOG for the staircase that paid for it.
     */
    static void standLevelWith(SceneContext ctx, JourneyRig rig, BlockPos target,
                                       Direction away, String tag, Runnable then) {
        int wantY = target.getY() - 1;
        if (rig.player().blockPosition().getY() >= wantY) { then.run(); return; }
        // THE REAL RAY FROM HERE FIRST, before any prediction about anywhere else — see the javadoc.
        if (aimThatLandsIn(ctx.level(), rig, target, away, tag + ".here") != null) {
            then.run();
            return;
        }
        // Only when the geometry says so. Every row up to y+3 above the floor already has a spot
        // with a clear line, and building a step for those would put cobblestone in the corridor
        // that the NEXT pour then has to stand around. Asking standToPour in verified-only mode is
        // the same question the pour is about to ask, so this cannot raise for a cell that would
        // have poured anyway.
        if (standToPour(ctx.level(), rig.player(), target, away, new java.util.LinkedHashMap<>(), true) != null) {
            then.run();
            return;
        }
        raiseTo(ctx, rig, target, away, wantY, true, tag, then);
    }

    /**
     * Build the step and stand on it — the part of {@link #standLevelWith} that is not a pour.
     *
     * <p>Separate because the caller decides WHETHER a raise is needed and the two callers do not
     * ask the same question. A pour needs a line to the target's backing or floor; a scoop needs a
     * line to the fluid IN the target. {@link JourneyPortalRung#riseToTakeItBack} had this wrong for exactly one
     * run: it went through {@code standLevelWith}, whose gate is the pour's, and that gate said a
     * pour spot exists — so the line printed {@code recover8.rise = 看不见 -9,61,37 里的水} and then
     * nothing was raised. A guard that names one action and tests another is the fourth of this
     * repo's four questions about a diagnostic, and this is what it looks like when it bites.
     */
    static void raiseTo(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                int wantY, boolean pouring, String tag, Runnable then) {
        raiseTo(ctx, rig, target, away, wantY, pouring, tag, 0, then);
    }

    /**
     * How many rows above {@code wantY} the body may still be standing and have the raise count as
     * finished. <b>A pour is served from slightly high and not from arbitrarily high</b>, and until
     * 2026-08-26 only the first half of that was written down: {@link JourneyRamp#buildTo}'s javadoc
     * says a pour 「is genuinely served from any row high enough」, which is true of one row and false
     * of five.
     *
     * <p>Cell ten of the real ladder of 2026-08-26 is what put a number on it. The raise asked for
     * {@code y=59} in column {@code 2,20}; an unstick tower answered by lifting the body to the
     * surface, and every gate downstream agreed it had arrived:
     *
     * <pre>
     * cast9.raiseTo.arrivedY   = 64（起 57，净升 7），脚下=grass_block
     * cast9.raiseColumnMissed  = 2, 64, 21 不在指定柱 2,20 上就算到了（walkToColumn 判到达用的是 5 格）
     * cast9.raisedY            = 64/59 … 射线是照 y=59 那一排验的，从这里打出去的不是验过的那条
     * cast9.picks.1            = 4, 63, 19 grass_block face=up → 落进 4, 64, 19
     *                            （眼睛 4.46/66.42/19.56 朝 yaw=-47.70 pitch=76.67）
     * </pre>
     *
     * <p><b>{@code pitch=76.67°} is the whole account.</b> From five rows up, the only line that
     * reaches the backing is one steep enough to hit the body's own footing first, so the pour landed
     * in the cell the body was standing in and the rung failed with nine of ten cells cast.
     * One row up the same line clears; that is why this is a bound and not a flip to {@code exactRow}
     * — flipping it would also kill the tolerance {@code JourneyRamp} argues for and I have no reading
     * against.
     */
    static final int POUR_ROW_SLACK = 1;

    /** How many times a raise may walk back down and try for its row again. One: the descent is a
     *  full {@code returnToTheForge}, and a second retry costs more budget than the cell is worth. */
    private static final int RAISE_ROW_TRIES = 1;

    private static void raiseTo(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                int wantY, boolean pouring, String tag, int attempt, Runnable then) {
        BlockPos verified = raiseColumn(ctx.level(), rig, target, away, wantY, pouring, tag);
        BlockPos col = verified != null ? verified : target.relative(away.getOpposite(), 1);
        BlockPos here = rig.player().blockPosition();
        // 「去这一柱」rather than「在这一柱上垒台阶」: a raise asked for by riseToTakeItBack may be a
        // pure column change on a body that is already at wantY, and a row that names a staircase
        // there would be describing work nobody does. What was built is JourneyRamp's own .flight /
        // .laid / .rampedY, which say it without being guessed at from here.
        rig.evidence(tag + ".raise", here.toShortString() + " → y=" + wantY
                + "（去 " + col.getX() + "," + col.getZ() + " 这一柱，不够高就在那儿垒台阶，"
                + (pouring ? "浇 " : "收 ") + target.toShortString() + " 得跟它同高）"
                + (verified != null
                        ? "：站上去射线" + (pouring ? "落得进目标格" : "打得到目标格里的液体") + "，钉住这一柱"
                        : "：没有一柱验得过射线，退回门框正后方那一柱，不钉"));
        // ALREADY IN IT — do not walk. The walk is what put the body one cell out of the column in
        // the first place (`raiseTo.arrivedDistance=1`), and a body standing in the right column has
        // nothing to gain from a leg that can only move it out of one. Same short-circuit the fill
        // and the pour both grew for the same reason.
        if (here.getX() == col.getX() && here.getZ() == col.getZ()) {
            raiseInColumn(rig, target, col, wantY, verified != null, pouring, tag, then);
            return;
        }
        Runnable arrived = () -> {
            // SAY SO WHEN THE ARRIVAL IS NOT AN ARRIVAL. `arrivedDistance` is a number nobody reads
            // as a verdict, and without this row a raise that started out of its own column looks
            // identical to one that started in it right up until `raisedY` reports a shortfall.
            BlockPos landed = rig.player().blockPosition();
            if (landed.getX() != col.getX() || landed.getZ() != col.getZ()) {
                rig.evidence(tag + ".raiseColumnMissed", landed.toShortString() + " 不在指定柱 "
                        + col.getX() + "," + col.getZ() + " 上就算到了（walkToColumn 判到达用的是 5 格）"
                        + " —— 接下来由塔的偏柱修正把身体带回这一柱");
            }
            // AND THE ROW, which the drift correction above does NOT fix — it walks the body back to
            // the column and has no opinion about height. `Goal.XZ.ignoresY()` is what lets the walk
            // arrive from any row at all, and an unstick tower is what actually carries the body up:
            // see POUR_ROW_SLACK for the cell this cost. Everything downstream of here reads the row
            // as correct, so the check has to be here, before the raise commits.
            // POURS ONLY. Every reading that bought this bound is a pour — cast9's pitch=76.67°,
            // above — and the scoop side has produced none. Running it there would not merely be
            // unjustified, it would PRE-EMPT the remedy that already works: a scoop that arrives a
            // row high is handled downstream by `buildTo` with exactRow=true, which lays a
            // staircase, costs one leg, and is what recover6 verified. A full `returnToTheForge` is
            // neither cheap nor verified, and because it runs first the working fix would never get
            // its turn again — so the scoop would look fixed while its real remedy went dead.
            int over = landed.getY() - wantY;
            if (pouring && over > POUR_ROW_SLACK && attempt < RAISE_ROW_TRIES) {
                rig.evidence(tag + ".raiseRowTooHigh", landed.toShortString() + " 比要站的排 y="
                        + wantY + " 高 " + over + " 排（容许 " + POUR_ROW_SLACK + "）—— 射线是照那一排验的，"
                        + "从这儿打出去的不是验过的那条；走回模腔重来一次（第 " + (attempt + 1)
                        + "/" + RAISE_ROW_TRIES + " 次）");
                JourneyStairwell.returnToTheForge(ctx, rig,
                        JourneyRamp.floorOf(JourneyPortalRung.forgeCorridor), tag + ".raiseRowRetry",
                        () -> raiseTo(ctx, rig, target, away, wantY, pouring, tag, attempt + 1, then));
                return;
            }
            if (pouring && over > POUR_ROW_SLACK) {
                // Retries spent and still high. Say so rather than letting `raisedY` be the only
                // trace, because that row reads as a note beside a raise that finished.
                rig.evidence(tag + ".raiseRowGaveUp", landed.toShortString() + " 仍比 y=" + wantY
                        + " 高 " + over + " 排，重来的机会用完了（" + RAISE_ROW_TRIES
                        + " 次）—— 下面这一浇多半会被射线闸拦下，失败记在浇上而不是记在这一排上");
            }
            if (!pouring && over > 0) {
                // The scoop's own row shortfall, recorded and NOT acted on here: `buildTo` owns it.
                // Without this row the hand-off is invisible and a scoop that arrives high looks
                // the same as one that arrives level.
                //
                // A WALK BACK WAS TRIED HERE AND MEASURED WORSE. On 2026-08-26 this branch ran a 3D
                // `Goal.Near(col at wantY, 1)` before handing over, on the theory that turning an
                // unmeasured shortfall (4 rows, body on the surface) into the measured one (1 row,
                // body in the alcove) would let `buildTo`'s staircase do its job. Three rehearsals:
                // it fired once and made the position WORSE — `scoopRowWalkedBack = 3,64,20 →
                // -2,66,18（比要站的排高 4 → 6 排，不在指定柱 3,20 上）` — never fired in the second,
                // and was below its own bound in the third. Zero runs improved.
                //
                // The account is the same objection this file already makes about the pour's own 3D
                // retry twenty lines down: that leg is well-formed because it starts PINNED AT THE
                // STAIR FOOT, one or two cells out. A goal issued from the surface is neither short
                // nor well-formed, and the walker answered it by leaving the column altogether. So
                // the remedy for arriving high is not to walk back after the fact — it is to stop
                // `walkToColumn`'s `Goal.XZ` from delivering the body to the surface in the first
                // place, which is a change to the goal and not to this hand-off.
                rig.evidence(tag + ".scoopRowHigh", landed.toShortString() + " 比要站的排 y=" + wantY
                        + " 高 " + over + " 排 —— 收水这一侧不走回程，交给下面 exactRow 的台阶处置");
            }
            raiseInColumn(rig, target, col, wantY, verified != null, pouring, tag, then);
        };
        Runnable stuck = () -> {
            rig.evidence(tag + ".raiseStuck", "走不到 " + col.getX() + "," + col.getZ()
                    + "，从当前高度浇（多半会被射线闸拦下）");
            then.run();
        };
        // A RETRY THAT ASKS THE SAME QUESTION IS NOT A RETRY. Until 2026-08-26 `raiseRowTooHigh`
        // above walked the body all the way back to the forge and then called this method again with
        // the same target, so the leg below re-ran with the same column, the same `walkToColumn` and
        // the same `Goal.XZ`. Rung 12's rehearsal that day measured both halves: the descent worked
        // — `raiseRowRetry.returnedY=57`, landing `1.83/57.00/19.52` — and the second ascent still
        // ended at `3,64,20`, `arrivedY=64（起 57，净升 7）`, on grass_block, six rows above a wantY
        // of 58. `Goal.XZ.ignoresY()` is the whole account: the surface belongs to the target column
        // too, and from a shaft floor it is that column's cheapest cell. So the retry could only ever
        // produce the answer that sent it back — it changed the body's position and nothing else the
        // question depended on.
        //
        // ONLY THE RETRY, AND ONLY POURING. A first attempt starts wherever the rung left the body,
        // often on the surface, and a 3D goal across that distance is a route nothing here has
        // measured. The retry starts pinned at the stair foot, one or two cells out, where a 3D goal
        // is short and well-formed. The scoop is excluded for the reason `raiseRowTooHigh` already
        // argues above: it has a remedy that works, and a new one running first would take its turn.
        //
        // Goal.Near, NOT Goal.Block. `Walker.snapGoalToStandable` (Walker.java:797) pulls an
        // unstandable Goal.Block to the nearest standable cell, and when `col` at wantY is occupied
        // that cell is the surface — which would rebuild this very defect inside the goal itself.
        // Near does no snapping. Its radius is POUR_ROW_SLACK so this leg and the `over` check above
        // are one bar in one place; a leg that cannot get inside it spends its budget and falls
        // through to `raiseRowGaveUp`, which is the honest outcome and the one that gate predicts.
        if (pouring && attempt > 0) {
            BlockPos want = new BlockPos(col.getX(), wantY, col.getZ());
            rig.evidence(tag + ".raiseTo3D", "重来这一趟改用三维目标 " + want.toShortString()
                    + "（半径 " + POUR_ROW_SLACK + "）—— 上一趟的 Goal.XZ 忽略 Y，"
                    + "把身体送上了同一柱的地表");
            rig.settle(new IntentProcess(new Intent(new Goal.Near(want, POUR_ROW_SLACK), List.of(),
                    CapabilityProfile.ALL, List.of(new NoBreak()))), 800, () -> {
                BlockPos landed3d = rig.player().blockPosition();
                rig.evidence(tag + ".raiseTo3D.landed", landed3d.toShortString() + "（想去 "
                        + want.toShortString() + "，距 " + String.format(java.util.Locale.ROOT, "%.2f",
                        Math.sqrt(landed3d.distSqr(want))) + " 格）");
                arrived.run();
            });
            return;
        }
        // THE EXACT COLUMN, radius 0. It was 1, and a radius-1 disk is not a rounding allowance here
        // — it is a different ray. Worse, `walkToColumn` judges arrival against its own
        // `ARRIVED_WITHIN` and not against the radius asked for, so the leg reports success from up
        // to five cells out: `recover8.rise.raiseTo.arrivedDistance=1` was an ARRIVAL, and the
        // pinned climb it handed over to then refused to place anything because the body was not in
        // the column. Asking for radius 0 at least makes the walker try for the cell the aim was
        // computed from; the tower's own drift correction is what finishes the job when it cannot.
        // AND IT MAY NOT DIG ITS WAY IN. This leg runs inside the alcove, where the only thing between
        // the body and the column is what this rung cut with its own pick — the same argument
        // `walkTheStairs`, the water fetch, `JourneyRamp#walkTo` and the pour's own approach all make.
        // A `Goal.XZ` makes it worse than the others: it ignores Y, so from atop the staircase the
        // cheapest route into a column below is to sink a shaft, and the 2026-08-25 rehearsal shows it
        // doing exactly that through `-1,58,20` — the support of the tread at `-1,59,20`.
        // PRICE THE WAY OUT OF THE ALCOVE, because `Goal.XZ` cannot see it. The goal ignores Y — its
        // own javadoc says so — and the surface cell of the target column belongs to that column too,
        // so from a shaft floor it is that column's CHEAPEST cell. Three bodies measured the same
        // ending on 2026-08-26: the real ladder's rung 12 poured at `4,60,20` with the body at
        // `-2,66,19`; the real-client rehearsal poured at `4,60,18` from `3,65,13`; five fake-player
        // rehearsals ended a raise at y=64..65. Every one of them is on the surface, and every one
        // fired a ray that was verified for a row inside the alcove.
        //
        // A TAX, NOT A CONSTRAINT, and for the reason JourneyTerrain#poolsLipCells already argues:
        // when the only route to the column really is over the top — a body that starts up there —
        // the route must stay available. LIP_TAX is the precedent's number: a plain edge is 10, so
        // 300 is thirty blocks of detour, which is more than the whole alcove is wide.
        //
        // The ceiling is the alcove's own, not `wantY`: legs inside the alcove legitimately move a
        // row or two above their target, and pricing those would tax the ordinary work. Above the
        // alcove there is nothing this leg wants at all.
        int alcoveCeiling = JourneyRamp.floorOf(JourneyPortalRung.forgeCorridor)
                + JourneyForge.ALCOVE_HEIGHT;
        rig.evidence(tag + ".raiseTo.ceilingTax", "壁龛天花板 y=" + alcoveCeiling
                + " 以上每踏一格加价 " + (int) JourneyTerrain.LIP_TAX
                + "（普通走一格是 10）—— Goal.XZ 忽略 Y，指定柱的地表格也属于那一柱，"
                + "从井底看它还是最便宜的一格；这条税就是为了让它不再是");
        WorldDriverJourneyScenes.walkToColumn(rig, tag + ".raiseTo", col.getX(), col.getZ(), 0, 800,
                WorldDriverJourneyScenes.MAX_WALK_ATTEMPTS,
                List.of((from, to, edge, goal, world) ->
                        to.getY() > alcoveCeiling ? JourneyTerrain.LIP_TAX : 0.0),
                List.of(new NoBreak()), arrived, stuck);
    }

    private static void raiseInColumn(JourneyRig rig, BlockPos target, BlockPos col, int wantY,
                                      boolean pin, boolean pouring, String tag, Runnable then) {
        Runnable done = () -> {
            BotConfig.allowPlace = false;          // the casting phase is place-free again
            // THE COLUMN AS WELL AS THE HEIGHT. `water9.raisedY=60/60` was a true statement about a
            // body two cells out of the column its aim had been computed for, and reading it alone
            // is what made a lost raise look like a finished one.
            BlockPos now = rig.player().blockPosition();
            // AND WHICH SIDE OF THE ROW. A scoop's column is verified with the eye at wantY exactly,
            // so「高了」is as wrong as「矮了」and reads nothing like it in the failure that follows:
            // one row high, the line into the water clips the frame cell above it and the run blames
            // the frame. `recover6.rise.raisedY = 59/58` said this and nobody could see it.
            String row = now.getY() == wantY ? ""
                    : now.getY() > wantY
                            ? "，比要站的排高 " + (now.getY() - wantY)
                              + " 排 —— 射线是照 y=" + wantY + " 那一排验的，从这里打出去的不是验过的那条"
                            : "，比要站的排矮 " + (wantY - now.getY()) + " 排";
            rig.evidence(tag + ".raisedY", now.getY() + "/" + wantY + "（停在 " + now.getX() + ","
                    + now.getZ() + "，指定柱 " + col.getX() + "," + col.getZ()
                    + (now.getX() == col.getX() && now.getZ() == col.getZ() ? "，同一柱"
                            : "，不是同一柱 —— 射线是照那一柱算的") + row + "）");
            then.run();
        };
        // THE STAIRCASE FIRST. It is the only one of the two that puts the body in the column it was
        // asked for by construction — a tower is pinned to a column only in the sense that it keeps
        // walking back to one — and it is the only one that works on dry alcove floor at all; see
        // JourneyRamp for the two runs where the tower gained one course of two holding 130 blocks.
        Runnable tower = () -> {
            // Pinned only when the column was CHOSEN by the ray. Falling back to the arithmetic
            // column means the rung does not know that column works, and pinning a guess buys
            // nothing while it can still cost the climb — so that path keeps the exit's own
            // adopt-on-drift policy.
            if (pin) JourneyShaft.climbOutInColumn(rig, wantY, col.getX(), col.getZ(), tag, done);
            else JourneyShaft.climbOut(rig, wantY, tag, done);
        };
        // EXACT ROW for a scoop, 「够高就行」 for a pour — see JourneyRamp#buildTo(…, exactRow, …).
        // A body that walked into the column one row high used to skip the flight entirely, so the
        // landing never got its floor and the scoop fired a ray verified for a row it was not on.
        JourneyRamp.buildTo(rig, JourneyPortalRung.forgeCorridor, new BlockPos(col.getX(), wantY, col.getZ()), !pouring,
                tag + ".ramp", () -> {
            // Handed on rather than replaced: the tower is what carried this rung out of its own
            // flood on 2026-08-17 (`cast9` 59/59), where the body floats and no placement is what
            // raises it. A flight that reached the row has nothing left for it to do.
            if (rig.player().blockPosition().getY() >= wantY) { done.run(); return; }
            footBeforeTower(rig, col, wantY, tag, tower, done);
        });
    }

    /**
     * The tower does not start on the flooded floor row — this leg moves the body off it first.
     *
     * <p><b>One key separates the two east runs of 2026-08-17</b>, and everything else in the failing
     * one hangs off it:
     *
     * <pre>
     * e7  cast8#1.fromY = 57   climb.0 = 2,57,19 onGround=true   rise 2, one drift,     CONSUME
     * e8  cast8#1.fromY = 56   climb.0 = 2,56,19 onGround=false  rise 3, washedOff ×3,  FAIL
     * </pre>
     *
     * The alcove's floor row is the row the cast's own bucket floods: the body that lands there
     * floats ({@code cast7.fromHere} measured it at {@code y=56.41} in a cell whose floor is at
     * {@code 57.0}), and a floating body is the easiest thing in the game to push off a one-block
     * pillar. e8's tower duly placed its first block, ended the course at {@code y=56.94} — a course
     * that gained nothing — and was washed off; two failed drift corrections later it had adopted
     * {@code 2,17} for a ray computed in {@code 2,19}, and the pour's gate correctly refused the
     * diagonal that made. Every link in that chain is downstream of the row the climb started on.
     *
     * <p><b>Scoped to what this rung does BEFORE delegating, deliberately.</b> "Do not let the tower
     * build in water" is the same fix one layer down, and {@code TowerProcess} is shared bot code with
     * callers that have nothing to do with a flooded alcove — changing it there would spread the
     * behaviour past anything this rung can attribute. Nothing here relaxes
     * {@link JourneyStairs#needsOpen} (no cell is filled at all), touches the drift policy, widens a
     * ray gate or adds a retry.
     *
     * <p><b>Read back off the world, not remembered.</b> The cells with something solid under them
     * are the flight {@link JourneyRamp} has already laid for the earlier cells of this same mould, so
     * asking the world is both cheaper and truer than keeping a list — a step that was swept, mended
     * or never laid answers correctly here for free.
     *
     * <p>Best-effort in the same sense the rest of this leg is: a body that cannot walk out of the
     * flood is handed to the tower exactly as before, and the row that says so names the flood rather
     * than leaving the next reader with {@code washedOff} and no upstream.
     *
     * <h2>「够高」is not「站得住」, and this leg used to accept the first for the second</h2>
     *
     * <p>The hand-off asked {@code now.getY() >= wantY} alone, so a walk that overshot into a
     * half-floating cell counted as a finished raise — the very state the leg exists to leave. What
     * "standing" is asked OF matters as much: <b>{@code onGround} is not usable on this body</b>. The
     * baseline rehearsal recorded eight byte-identical rows of {@code onGround=true, inWater=false}
     * for a body whose own floor cell was flowing water, and the run before it recorded
     * {@code onGround=false} for a body at rest — it lies in both directions. So the question is put
     * to the WORLD: something that {@code blocksMotion} under the feet — via
     * {@link JourneyShaft#supportUnder}, which falls back to the four corners of the footprint,
     * because a 0.6-wide box a fifth of a cell off centre rests on the cell next door — and no fluid
     * in the body's own cell. {@code onGround} is still printed, labelled as the unreliable reading it
     * is.
     */
    private static void footBeforeTower(JourneyRig rig, BlockPos col, int wantY, String tag,
                                        Runnable tower, Runnable done) {
        ServerLevel level = rig.ctx().level();
        BlockPos here = rig.player().blockPosition();
        int floorY = JourneyRamp.floorOf(JourneyPortalRung.forgeCorridor);
        // THE FLOODED FLOOR ROW, both halves. A dry alcove floor is where the tower has always worked
        // from, and a body already above the floor is e7 — this must be a no-op for both, or it is
        // not one variable.
        if (here.getY() > floorY || level.getFluidState(here).isEmpty()) { tower.run(); return; }
        BlockPos step = footingAbove(level, floorY, wantY, col);
        if (step == null) {
            rig.evidence(tag + ".towerFromFlood", here.toShortString() + " 站在被淹的壁龛地板（y="
                    + floorY + "，这一格是 " + level.getBlockState(here).getBlock()
                    + "）—— y=" + (floorY + 1) + "…" + wantY
                    + " 之间没有一格脚下是实心的，只能从这一排起塔；接下来的 washedOff／改柱都是从这里开始的");
            tower.run();
            return;
        }
        rig.evidence(tag + ".footing", here.toShortString() + " → " + step.toShortString()
                + "（塔不从被淹的那一排起步：先走到脚下有实底的那一排，脚下是 "
                + level.getBlockState(step.below()).getBlock() + "）");
        JourneyRamp.walkTo(rig, step, () -> {
            BlockPos now = rig.player().blockPosition();
            // TWO QUESTIONS, ANSWERED SEPARATELY — see #standsAt for why the second one exists and
            // why it is not asked of `onGround`.
            boolean tall = now.getY() >= wantY;
            BlockPos under = JourneyShaft.supportUnder(rig, now);
            boolean solid = level.getBlockState(under).blocksMotion();
            boolean dry = level.getFluidState(now).isEmpty();
            // WHERE IT ACTUALLY LANDED, and which of the two questions let it through. `walkTo` is
            // best-effort like every other leg down here, and the first version of this row could
            // only show that the walk had missed — not which half of the gate then passed it.
            rig.evidence(tag + ".footedOn", now.toShortString() + "（要的落脚格 "
                    + step.toShortString() + "）够高吗："
                    + (tall ? "够（" + now.getY() + "≥" + wantY + "）"
                            : "不够（" + now.getY() + "<" + wantY + "）")
                    + "；站得住吗：" + (solid && dry ? "站得住" : "站不住")
                    + "（脚下 " + under.toShortString() + "=" + level.getBlockState(under).getBlock()
                    + (solid ? "，实心" : "，不实心") + "；身体这一格 "
                    + (dry ? "无流体" : "有流体 " + level.getBlockState(now).getBlock()) + "）"
                    + "；onGround=" + rig.player().onGround()
                    + "（只作参考 —— 这具身体在流动水面上也报 true）");
            // BOTH, not just the height. The first version asked only「够高吗」and therefore called a
            // landing with water under it and no ground contact a finished raise — which is exactly
            // the state this whole leg exists to get the body OUT of. Measured twice, byte-identical
            // (rehearsals of 2026-08-17, A2 and A3): `footedOn = 3,60,17（脚下是 water，
            // onGround=false）` was accepted, the raise reported `raisedY = 60/59`, and the cell was
            // then poured from a half-floating body on an unverified ray. That pour was luck; the
            // `.picks` gate refusing two shots before it is what kept it honest.
            // THE TWO ROWS THAT TELL THE REMAINING STORIES APART — read the javadoc on #wouldLandOn
            // for why this is a query and not a wait.
            rig.evidence(tag + ".footWalk", here.toShortString() + " → " + now.toShortString()
                    + "（要的落脚格 " + step.toShortString() + "，差 "
                    + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(now.distSqr(step)))
                    + " 格；" + (now.equals(here) ? "一格没挪 —— 这一腿根本没走成"
                            : "挪了 " + String.format(java.util.Locale.ROOT, "%.2f",
                                    Math.sqrt(now.distSqr(here))) + " 格") + "）");
            BlockPos landOn = wouldLandOn(rig, level, now);
            rig.evidence(tag + ".wouldLandOn", (landOn == null
                    ? "身下 " + FALL_SCAN + " 格内没有实心支撑 —— 掉下去也停不住"
                    : landOn.toShortString() + (landOn.equals(step)
                            ? "（正是要的落脚格 —— 病在判读的时机，不在这一腿的终点）"
                            : "（不是要的落脚格 " + step.toShortString()
                              + " —— 病在这一腿的终点，不在判读的时机）"))
                    + (dry ? "" : "；但身体这一格有流体，浮着就不会落下去 —— 这一行说的是「万一下落」"));
            if (tall && solid && dry) { done.run(); return; }
            tower.run();
        });
    }

    /** How far down to look for the floor the body would land on. Twelve: the alcove is seven rows
     *  tall and this is asked from at most one row above its top, so anything further down is outside
     *  the room and not an answer to the question. */
    private static final int FALL_SCAN = 12;

    /**
     * Where this body would come to rest if it simply fell — <b>asked, never waited for</b>.
     *
     * <h2>The general move: ask the world a question instead of running an experiment</h2>
     *
     * <p>The question this answers is「这具身体正在往哪落」, and the obvious way to get it is to wait
     * and look. <b>That way is closed here</b>, and closed by measurement rather than by taste:
     * {@link HoldStill}'s own note carries the archived negative — a longer settle was tried in this
     * very alcove and reverted, because flowing water means「at rest」is a state this rung never
     * reaches, and because the extra ticks are themselves a mover ({@code recover8.ask.settled = 等了
     * 10 tick 身体还在动（…共挪了 3.60 格）}, after which the body was on the floor row and its walk
     * back mined a cast cell out of the frame).
     *
     * <p>So the reading is taken from the BLOCKS instead: gravity is deterministic and the floor is
     * already in the world, so the landing can be computed without letting a single tick pass. <b>A
     * query changes nothing, which is exactly what makes it safe to ask in the middle of a leg that a
     * wait would corrupt.</b> Reach for this shape whenever the honest reading would otherwise cost
     * ticks in a system where ticks are one of the variables.
     *
     * <p>All four corners of the footprint, highest support wins, because that is what a 0.6-wide box
     * lands on — the same reason {@link JourneyShaft#supportUnder} looks there. Null when nothing
     * within {@link #FALL_SCAN} would stop the body, which is itself an answer.
     */
    private static BlockPos wouldLandOn(JourneyRig rig, ServerLevel level, BlockPos from) {
        var box = rig.player().getBoundingBox();
        int best = Integer.MIN_VALUE;
        for (int x : new int[]{net.minecraft.util.Mth.floor(box.minX),
                               net.minecraft.util.Mth.floor(box.maxX)})
            for (int z : new int[]{net.minecraft.util.Mth.floor(box.minZ),
                                   net.minecraft.util.Mth.floor(box.maxZ)})
                for (int y = from.getY() - 1; y >= from.getY() - FALL_SCAN; y--)
                    if (level.getBlockState(new BlockPos(x, y, z)).blocksMotion()) {
                        best = Math.max(best, y);
                        break;
                    }
        // The feet land one row above whatever stopped them, in the column the body is falling down —
        // a fall is straight, so x/z come from the body and only y comes from the support.
        return best == Integer.MIN_VALUE ? null : new BlockPos(from.getX(), best + 1, from.getZ());
    }

    /**
     * The highest cell above the flooded floor row, no higher than {@code wantY}, that a body can
     * stand in with something solid directly under its feet.
     *
     * <p>Highest first because a course the tower does not have to build is a course the water cannot
     * wash off, and this rung's own flight often reaches {@code wantY} outright. Nearest to the pinned
     * column breaks ties, so the tower that may still follow starts as close to its own column as the
     * alcove allows — this does not CHOOSE a column and it does not move the pin; the climb is still
     * asked for {@code col}, and {@code raisedY} still says which column the body ended in.
     */
    private static BlockPos footingAbove(ServerLevel level, int floorY, int wantY, BlockPos col) {
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (BlockPos c : JourneyPortalRung.forgeCorridor) {
            if (c.getY() <= floorY || c.getY() > wantY) continue;
            if (!JourneyPortalRung.forgeCorridor.contains(c.above())) continue;
            if (!level.getBlockState(c.below()).blocksMotion()) continue;
            if (level.getBlockState(c).blocksMotion()
                    || level.getBlockState(c.above()).blocksMotion()) continue;
            long dx = c.getX() - col.getX(), dz = c.getZ() - col.getZ();
            long d = dx * dx + dz * dz;
            if (best == null || c.getY() > best.getY() || (c.getY() == best.getY() && d < bestD)) {
                best = c.immutable();
                bestD = d;
            }
        }
        return best;
    }

    /**
     * Which column to build the step in — one whose eye can actually see the target's backing.
     *
     * <p>It used to be arithmetic: one cell back along {@code away} from the target. That column is
     * a good guess and it is not a checked one, and when the body cannot reach it the climb starts
     * somewhere else and the aim silently becomes a different aim. Run 43's tenth cell went that way
     * — the arithmetic column was {@code x=-10}, the body could only get to {@code x=-9} (there is
     * no floor at {@code y=57} anywhere else in a hollow alcove), and the tower then drifted to
     * {@code x=-8} and {@code x=-7} and adopted it.
     *
     * <p>So ask the question the BUCKET is going to ask, one row down: standing HERE at
     * {@code wantY}, does the same clip vanilla runs do what this leg needs? Nearest wins and the
     * body's own column is at distance zero, so a column that already works costs no walk at all —
     * which for that run is the fix, because {@code x=-9} verifies.
     *
     * <p>{@code pouring} picks WHICH clip, and it is not a stylistic parameter: a pour wants the ray
     * to stop on the target's backing or floor ({@link #pourLandsFrom}, {@code Fluid.NONE}), a scoop
     * wants it to reach the fluid inside the target itself ({@link #scoopSeesFrom},
     * {@code Fluid.SOURCE_ONLY}). They disagree exactly where it matters — over a freshly cast cell,
     * the pour question passes and the scoop question does not.
     *
     * <p>Corridor cells only, feet and head both, so the column is inside the volume this rung
     * hollowed out and the head has somewhere to go. Null when none of them verify, and the caller
     * says so rather than pretending.
     *
     * <p><b>The flight's own column comes last, not first.</b> Distance decides ties and the body is
     * standing at the foot of the stairs when it asks — {@code returnToTheForge} just put it there —
     * so the stair column wins at distance zero every time, and that column is the one place in the
     * alcove where a raise cannot happen: {@code JourneyRamp} refuses it outright
     * ({@code cast6.ramp.noFlight = … 2, 57, 20 是下井楼梯 2, 56, 20 那一级的头顶格，不能堵}) and the
     * tower drowns in the water the mould drains down those very stairs
     * ({@code cast6#1.climb.0..8.washedOff = 水把身体冲下柱子了}, nine legs, {@code placed=0}).
     *
     * <p>Demoted rather than vetoed, deliberately: a stair column that verifies is still better than
     * nothing, and vetoing it would turn a bad raise into no raise at all. The order is the whole fix.
     *
     * <p><b>Measured, PORTAL_LIT rehearsal 2026-08-23</b> — the run this was written for cast eight of
     * ten cells and then could not walk back into its own shaft. The chain is one line long once the
     * column is named: the fallback towers of cells six and seven ended off their pinned column, the
     * next one put cobblestone in tread {@code 1, 57, 20} ({@code cast8.stairsBroken#2 = 1/11 级坏了}),
     * the foot flooded two cells deep, and {@code cast8.returnStopped#2} stopped 2.24 blocks short of
     * a stair foot whose feet AND head cells were both water. {@code towerColumnAfterDrift} honours a
     * pinned column on the flight「because the pour's own ray gate will judge where it lands」— true of
     * the pour, and the eighth cell is where that premise met a WALK instead.
     */
    private static BlockPos raiseColumn(ServerLevel level, JourneyRig rig, BlockPos target,
                                        Direction away, int wantY, boolean pouring, String tag) {
        BlockPos here = rig.player().blockPosition();
        BlockPos best = null, onFlight = null;
        long bestD = Long.MAX_VALUE, flightD = Long.MAX_VALUE;
        // THE CENSUS THIS SEARCH HAS NEVER PRINTED. `.raiseOffTheFlight` names the column that won
        // and nothing about the ones that lost, so a reader holding only that row reaches for the
        // nearest veto map in the same results file — `standToPour`'s. That one answers a DIFFERENT
        // question over a DIFFERENT candidate set: it scans the target's own row for a place to pour
        // from, this scans `wantY` for a place to raise to. Measured, ladder run 9: the stand map
        // named `射线停在 4,60,19 dirt=2`, and a fix aimed at clearing those cells would have been
        // aimed with the wrong instrument — the raise's own vetoes were never in the file at all.
        int outside = 0, occupied = 0, vetoed = 0, verified = 0, onFlightOk = 0;
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        for (int back = 1; back <= JourneyPortalRung.POUR_LINE; back++)
            for (int side = -2; side <= 2; side++) {
                BlockPos foot = target.relative(away.getOpposite(), back)
                        .relative(away.getClockWise(), side).above(wantY - target.getY());
                if (!JourneyPortalRung.forgeCorridor.contains(foot) || !JourneyPortalRung.forgeCorridor.contains(foot.above())) { outside++; continue; }
                // A LANDING HAS TO BE A PLACE A BODY CAN BE. This asked only whether the eye at that
                // cell would see the backing, which is true of a cell full of cobblestone — and by
                // the ninth cast some of them are: the raise for the notch one row up rests its own
                // top step in exactly the cell the ring cell below it wants to stand in. Measured,
                // rehearsal 2026-08-16: `wet.9` ramped to -10,60,37 over a step at -10,59,37, and
                // `cast9.lift` then chose -10,59,37 and reported「被 cobblestone 占着」.
                if (level.getBlockState(foot).blocksMotion()
                        || level.getBlockState(foot.above()).blocksMotion()) { occupied++; continue; }
                // ONE MAP PER CANDIDATE, merged only when that candidate actually lost. The two aims
                // `pourLandsFrom` tries are fixed by the target, not by the foot, so a shared map
                // collects the first aim's veto even when the second aim carries the cell — and when
                // `target.relative(away)` is not solid, EVERY candidate contributes that one reason,
                // which prints「射线否决 0；否决点名 {…=50}」and reads as a search that found nothing.
                Map<String, Integer> mine = new java.util.LinkedHashMap<>();
                if (!(pouring ? pourLandsFrom(level, rig.player(), foot, target, away, mine)
                              : scoopSeesFrom(level, rig.player(), foot, target))) {
                    if (!pouring)
                        mine.merge("取水射线看不到 " + target.toShortString() + " 里的源", 1, Integer::sum);
                    mine.forEach((k, v) -> why.merge(k, v, Integer::sum));
                    vetoed++;
                    continue;
                }
                long dx = foot.getX() - here.getX(), dz = foot.getZ() - here.getZ();
                long d = dx * dx + dz * dz;
                // COUNTED APART, because「only the flight verified」is the empty-candidate-set case
                // wearing a 1: a lumped `验得过 1` next to a stair column being chosen reads as a
                // ranking bug, which is a different disease with a different fix.
                if (JourneyStairs.stepInColumn(level, foot.getX(), foot.getZ()) != null) {
                    onFlightOk++;
                    if (d < flightD) { flightD = d; onFlight = foot.immutable(); }
                    continue;
                }
                verified++;
                if (d < bestD) { bestD = d; best = foot.immutable(); }
            }
        // ALWAYS, both when the search found plenty and when it found nothing: an empty candidate set
        // and a search that never ran read identically once only the winner is printed.
        rig.evidence(tag + ".raiseVeto", "抬升候选（wantY=" + wantY + "，" + (pouring ? "为浇" : "为取")
                + "）：验得过 " + verified + "（另有楼梯柱 " + onFlightOk + " 柱也验得过）"
                + "，射线否决 " + vetoed + "，落脚或头顶被占 " + occupied
                + "，不在壁龛内 " + outside
                + (why.isEmpty() ? "" : "；否决点名 " + why));
        // Say which way the order went, and say it whichever way it went — a row that only appears
        // when the flight was avoided cannot tell「there was nowhere else」from「this never ran」.
        if (onFlight != null)
            rig.evidence(tag + ".raiseOffTheFlight", best != null
                    ? "楼梯那一柱 " + onFlight.getX() + "," + onFlight.getZ() + " 也验得过射线，"
                      + "但它是下井楼梯（垒不了台阶、塔在水里会被冲下来），改用 "
                      + best.getX() + "," + best.getZ() + "（落脚 " + best.toShortString() + "）"
                    : "只有楼梯那一柱 " + onFlight.getX() + "," + onFlight.getZ()
                      + " 验得过射线，别无选择 —— 仍然用它，抬升多半会被冲下来");
        return best != null ? best : onFlight;
    }

    /** Would a body standing at {@code foot} be able to FILL from the fluid in {@code target}? The
     *  same {@code SOURCE_ONLY} clip {@code BucketItem.use} runs — the scoop's counterpart to
     *  {@link #pourLandsFrom}, and the reason a raise has to be told which of the two it is for. */
    private static boolean scoopSeesFrom(ServerLevel level, ServerPlayer body, BlockPos foot,
                                         BlockPos target) {
        var eye = JourneySight.eyeFor(body, foot);
        var to = net.minecraft.world.phys.Vec3.atCenterOf(target);
        if (eye.distanceTo(to) > JourneyFill.BUCKET_REACH) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, to,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, body));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && hit.getBlockPos().equals(target);
    }

    /** Would a bucket emptied by a body standing at {@code foot} land in {@code target}? The same
     *  clip {@link #standToAimAt} runs, from the eye that body WOULD have — a prediction about a
     *  cell the rung is about to build a floor under, which is why it cannot ask for one. */
    private static boolean pourLandsFrom(ServerLevel level, ServerPlayer body, BlockPos foot,
                                         BlockPos target, Direction away) {
        return pourLandsFrom(level, body, foot, target, away, new java.util.LinkedHashMap<>());
    }

    /** As above, and it hands back WHY it said no. The two single-cell callers ask about one cell
     *  they have already chosen, so the reason has nowhere to go; {@link #raiseColumn} asks about
     *  fifty and the distribution across them is the whole reading. */
    private static boolean pourLandsFrom(ServerLevel level, ServerPlayer body, BlockPos foot,
                                         BlockPos target, Direction away, Map<String, Integer> why) {
        // THE WHOLE CELL, exactly as a stand is judged — see JourneySight. A column is chosen once and
        // then PINNED («换柱等于换射线，不许改»), so a column that only verifies from its own centre
        // commits the pour to a shot the body cannot reproduce, and the pin is what stops it being
        // re-chosen. Measured on the east arm, twice, byte-identical: the last cell's centre eye
        // (2.5, 60.62, 19.5) crosses x=4 at z=20.00 EXACTLY — a block corner, tie-broken into the
        // target — while the eye that fired, (2.70, 60.62, 19.50), crosses at z=19.96 and stops on
        // the obsidian this rung cast one cell earlier.
        boolean afloat = !level.getFluidState(foot).isEmpty();
        for (BlockPos aim : List.of(target.relative(away), target.below())) {
            if (!level.getBlockState(aim).isSolidRender(level, aim)) {
                why.merge(aim.toShortString() + " 不是实心的，弹不出流体", 1, Integer::sum);
                continue;
            }
            if (JourneySight.pourGrade(level, body, foot, afloat, aim, target, why)
                    == JourneySight.ANYWHERE) return true;
        }
        return false;
    }

    /**
     * Last resort before a pour gives up: pillar up where the body IS, rather than where it should be.
     *
     * <p>{@link #standLevelWith} builds a step when the geometry says no spot can see the target, and
     * it is right about the geometry — but it is asked before the walk, and the walk is what fails.
     * Run 40 cell six: {@code water6.stand=-9,56,37} verified, so no step was built, and the body
     * then ended at {@code -8,56,37} one cell east and stayed there through both retries, its ray
     * landing in {@code -9,58,38} every time. The one good cell existed and the walker could not
     * reach it, which no amount of re-choosing fixes.
     *
     * <p>Lifting to the target's own row is what makes the backing aim horizontal wherever the body
     * happens to be standing. The cobblestone is left behind on purpose — the pours after this one
     * stand on it, and {@link JourneyRamp} is what keeps {@link JourneyPortalRung#tidyTheAlcove} from sweeping it.
     *
     * <p><b>A staircase, not a pillar, and the column is chosen by the ray.</b> Two things were
     * wrong with towering straight up from wherever the body was. The tower does not work on this
     * geometry — see {@link JourneyRamp}'s note for the two verbatim reproductions of
     * {@code climb.1.stalled} on dry land with 130 cobblestone in hand — and even a tower that
     * worked would put the eye in the BODY's column rather than in one whose ray reaches the
     * backing. The real ladder of 2026-08-16 measured exactly that second half: {@code cast6} lifted
     * in {@code x=-9} for a target in {@code x=-8}, and the diagonal that makes grazed the corner of
     * the obsidian it had cast two rows below ({@code picks=-8,58,38 obsidian → 落进 -9,58,38}). So
     * the landing is {@link #raiseColumn}'s answer — the same clip the bucket will run, asked from
     * the eye a body standing there WOULD have — and the flight is built to reach it.
     *
     * <p>The tower is still run behind it, and only behind it: it has carried this rung before (the
     * rehearsal of 2026-08-17 lifted {@code cast9} 59/59 out of the alcove's own flood, where a body
     * floats and a placement is not what raises it), so a flight that falls short hands over rather
     * than ending the cast.
     */
    static void liftInPlace(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                    String tag, int tries, Runnable then) {
        int wantY = target.getY() - 1;
        if (tries > 2) {
            // SAY THAT THIS ROUND DID NOT LIFT AT ALL. Every other exit here writes a row, so the
            // mute one made「先让重走自己试一次」and「抬了，没帮上」arrive in the results file as the
            // same thing — no `.lift*` row — and those two want opposite next steps. It fires at
            // most once per pour: POUR_APPROACHES is 3 and `tries` counts down, so only the first
            // re-approach is held back. Its own key, not `.liftSkipped`: that one means the lift was
            // asked and could not help, which is a finding, while this is policy.
            rig.evidence(tag + ".liftHeld." + tries, "第 " + (POUR_APPROACHES - tries + 1)
                    + " 次重走不抬升（tries=" + tries + "/" + POUR_APPROACHES
                    + "）—— 先让重走自己试一次；这一轮之后的失败算不到抬升头上");
            then.run();
            return;
        }
        // THE QUESTION IS THE COLUMN, NOT THE HEIGHT. This used to return whenever the body was at
        // `wantY` or above — 「already high enough, nothing to lift」— and that is a statement about
        // one axis in answer to a failure that lives in two. The caller only reaches here because the
        // pour's ray gate REFUSED, and a body can be dead on the right row and in the wrong column.
        //
        // Measured, PORTAL_LIT rehearsal 2026-08-23: `water8.raisedY = 60/60（停在 3,21，指定柱
        // 3,20，不是同一柱）`. The tower drifted one cell and `driftKeptPinned` adopted it; from there
        // the ray put the water in `3,61,21` instead of `4,61,20`. Three approaches then produced
        // `water8.stand.3 / .2 / .1` byte-identical, with `clear3` and `clear2` both reporting
        // 「浇线上没有可清的方块」 — this early return is why nothing between them changed anything.
        //
        // Ask instead whether the cell the body is IN would land the pour. When it would, a lift
        // genuinely cannot help (the miss is sub-cell: the centre eye this predicate uses is not the
        // eye that fires — see JourneySight) and the old behaviour is kept, out loud. When it would
        // not, the lift's own `raiseColumn` already knows which column does, and at equal height that
        // makes this a lateral move rather than a climb.
        BlockPos at = rig.player().blockPosition();
        if (at.getY() >= wantY && pourLandsFrom(ctx.level(), rig.player(), at, target, away)) {
            rig.evidence(tag + ".liftSkipped." + tries, at.toShortString()
                    + " 已经在 y=" + wantY + " 那一排，而且这一格自己验得过这一浇 —— 抬升帮不上忙，"
                    + "差的是格内位置（格心眼不是开火的那只眼）");
            then.run();
            return;
        }
        if (at.getY() >= wantY)
            rig.evidence(tag + ".liftSideways." + tries, at.toShortString()
                    + " 高度够了（y=" + wantY + "）但这一柱验不过这一浇 —— 平移到验得过的那一柱，"
                    + "不是往上垒");
        // DIRECTLY BEHIND FIRST, then whatever else verifies. `raiseColumn` ranks by distance and the
        // body's own column is at distance zero, so on a lift it always wins — and the shot from the
        // body's column to a target one cell sideways is the diagonal this whole rung keeps losing
        // cells to: it crosses the frame's plane at a block CORNER, where the segment clip and the
        // fired ray tie-break opposite ways (see aimThatLandsIn). The cell one back and one down
        // from the target is the only geometry that makes the backing shot horizontal, which is what
        // placeFluid's own contract asks for.
        BlockPos behindLow = target.relative(away.getOpposite()).below();
        BlockPos verified = JourneyPortalRung.forgeCorridor.contains(behindLow) && JourneyPortalRung.forgeCorridor.contains(behindLow.above())
                && !ctx.level().getBlockState(behindLow).blocksMotion()
                && !ctx.level().getBlockState(behindLow.above()).blocksMotion()
                && pourLandsFrom(ctx.level(), rig.player(), behindLow, target, away)
                ? behindLow : raiseColumn(ctx.level(), rig, target, away, wantY, true, tag + ".lift");
        BlockPos here = rig.player().blockPosition();
        BlockPos landing = verified != null ? verified : new BlockPos(here.getX(), wantY, here.getZ());
        rig.evidence(tag + ".lift", here.toShortString() + " → " + landing.toShortString()
                + "（走不到选定的落脚格，修一段楼梯上到和 " + target.toShortString() + " 同高）"
                + (verified != null ? "：站上去射线落得进目标格"
                        : "：没有一柱验得过射线，就在身体这一柱上修，不钉"));
        // AND SAY SO WHEN THE LIFT IS THE BODY'S OWN CELL. With no verified column and the body
        // already on the row, `landing` is where the body is standing, so the flight has nothing to
        // build and the approach that follows re-asks a deterministic question in an unchanged world.
        // That is the whole shape of `a-retry-that-changes-nothing`, and it costs an approach each
        // time it happens silently.
        if (landing.equals(here)) {
            rig.evidence(tag + ".liftIsHere." + tries, here.toShortString()
                    + " 就是要修到的那一格 —— 这一次抬升什么也不会改，"
                    + "接下来那一次进近问的是同一个世界里的同一个问题");
            then.run();
            return;
        }
        // BOUNDED, AND PINNED TO THE COLUMN. The unbounded arm answered `liftSideways` — a body this
        // method had just found unable to fire from its own column — with「已经到了落点那一排或更高」
        // and built nothing, so the sideways move named one row above never happened and `liftedY`
        // recorded it as a lift that finished. Both bounds are read from this rung's own evidence;
        // see JourneyRamp#buildTo(…, rowSlack, sameColumn, …).
        JourneyRamp.buildTo(rig, JourneyPortalRung.forgeCorridor, landing, false,
                POUR_ROW_SLACK, true, tag + ".lift", () -> {
            // THE COLUMN TOO, because this row is what the caller reads as「抬升成功了」and the row
            // alone cannot carry that. A lift is asked for precisely when the body's own column
            // cannot fire the pour, so a body that ends at the right height in the wrong column has
            // not been lifted — it has been left. `liftedY=65/60` said only「排到了」for exactly such
            // a body on 2026-08-26; the bounds above stop it happening, this says so when it does.
            BlockPos ended = rig.player().blockPosition();
            if (ended.getY() >= wantY) {
                boolean inColumn = ended.getX() == landing.getX() && ended.getZ() == landing.getZ();
                rig.evidence(tag + ".liftedY", ended.getY() + "/" + wantY
                        + (inColumn ? "（在落点那一柱上）"
                                : "（停在 " + ended.getX() + "," + ended.getZ() + "，落点柱 "
                                        + landing.getX() + "," + landing.getZ()
                                        + " —— 排够了但柱不对，射线是照那一柱验的）"));
                then.run();
                return;
            }
            rig.evidence(tag + ".liftTower", "楼梯到 y=" + rig.player().blockPosition().getY()
                    + " 就修不上去了，交给塔兜底");
            BotConfig.allowPlace = true;
            JourneyShaft.climbOut(rig, wantY, tag + ".lift", () -> {
                BotConfig.allowPlace = false;
                rig.evidence(tag + ".liftedY", rig.player().blockPosition().getY() + "/" + wantY);
                then.run();
            });
        });
    }

    /** How many times a pour may re-walk at its cell before the rung stops. Two, plus the one it
     *  started with: this is a few blocks inside a chamber the body just carved, so a leg that ends
     *  out of reach three times is not a slow walk, it is a body that cannot get there. */
    static final int POUR_APPROACHES = 3;

    /**
     * A cell the body can STAND in and from which this pour provably lands in {@code target}.
     *
     * <p>The spot used to be arithmetic — two blocks back along {@code away}, at the target's own
     * height — and level with the target is the right idea for the ray. It is the wrong idea for the
     * body: the alcove is hollow, so "level with a cell four rows up" is a cell with nothing under
     * it, and asking the walker to occupy thin air is what wedged a run at {@code -9,53,20}, three
     * blocks outside the corridor it had just carved, unable to move for three identical attempts.
     *
     * <p>So both halves are asked properly. <b>Standable</b> — feet and head clear, something solid
     * underfoot — and <b>useful</b>, meaning the same clip vanilla is about to run lands on the
     * backing's near face, which is what puts the fluid in {@code target} and nowhere else. Nearest
     * to the body wins, so a cell it is already standing in costs no walk at all.
     */
    static PourSpot standToPour(ServerLevel level, ServerPlayer body, BlockPos target,
                                Direction away, Map<String, Integer> why) {
        return standToPour(level, body, target, away, why, false);
    }

    /** Where to stand and what to aim at — one answer, because the two are chosen together. */
    record PourSpot(BlockPos stand, BlockPos aim) {}

    /**
     * {@code verifiedOnly} drops the standable fallback, which is what makes this answerable as a
     * QUESTION — "is there anywhere down here with a clear line to this cell" — rather than only as
     * a place to walk to. {@link #standLevelWith} asks it that way.
     *
     * <h2>Two aims, because a floating body cannot use the first one</h2>
     *
     * The backing is the natural thing to aim at and it needs the eye almost exactly level with the
     * target: the ray has to cross the frame's plane inside the target's own row, and the plane is
     * two blocks away, so a body one block too high enters the row ABOVE and the fluid lands there.
     * That is not a hypothetical — the alcove floods with the cast's own water, a body in water
     * floats one block, and run 29's cell three recorded exactly it twice
     * ({@code 射线停在 -11,58,38 granite}) with no verified spot left over.
     *
     * <p>So when the backing yields nothing, aim at the target's FLOOR instead and hit its top face:
     * the fluid still lands in the target, and looking down at a block one row below is precisely
     * what a body standing a block too high can do. That the floor is solid is not an assumption —
     * it is {@link JourneyForge}'s first invariant, which is why the ring is cast in the order it is.
     * The exception is the top pair, whose floor is an interior cell opened three casts earlier;
     * there this finds nothing and {@link #standLevelWith} still has to build the step.
     */
    private static PourSpot standToPour(ServerLevel level, ServerPlayer body, BlockPos target,
                                        Direction away, Map<String, Integer> why,
                                        boolean verifiedOnly) {
        BlockPos standable = firstStandable(level, body, target, away);
        for (BlockPos aim : List.of(target.relative(away), target.below())) {
            if (!level.getBlockState(aim).isSolidRender(level, aim)) {
                why.merge(aim.toShortString() + " 不是实心的，弹不出流体", 1, Integer::sum);
                continue;
            }
            BlockPos best = standToAimAt(level, body, target, away, aim, why, verifiedOnly);
            if (best != null) return new PourSpot(best, aim);
        }
        return standable == null || verifiedOnly ? null
                : new PourSpot(standable, target.relative(away));
    }

    /**
     * Which block, aimed at from where the body is STANDING RIGHT NOW, puts the fluid in the target.
     *
     * <p>The same two candidates {@link #standToPour} weighs — the backing's near face and the
     * target's own floor — and the answer is decided by <b>the ray the bucket is actually going to
     * fire</b>, not by the segment that chose the candidate. Null when neither survives that, so the
     * caller's own gate can refuse to spend the bucket.
     *
     * <h2>Why the two rays are not the same ray, even standing still</h2>
     *
     * The segment version clips {@code eye → atCenterOf(aim)} in doubles. The bucket does not: it
     * reads {@code getXRot()}/{@code getYRot()}, which {@code aimAtBlock} stored as <b>floats</b>
     * computed from the same eye, and re-derives a direction from them through {@code Mth}'s
     * lookup-table trigonometry. Nominally the same line; not bit-for-bit the same line.
     *
     * <p>Normally that costs a ten-thousandth of a block and decides nothing. It decides a whole cell
     * when the line runs along a block EDGE, and the real ladder of 2026-08-16 hit exactly that at
     * cell three:
     *
     * <pre>
     * cast3.fromHere = -9,56,32 就地瞄 -11,57,34，流体会落进 -11,57,33（不走了）
     * cast3.picks    = -10,57,34 granite face=north → 落进 -10,57,33
     * </pre>
     *
     * Hand-computed from those two rows, and stated as the likely mechanism rather than as a measured
     * one, because the rows print CELLS and the arithmetic needs the sub-cell position: a body at the
     * centre of {@code -9,56,32} has its eye at {@code (-8.5, 57.62, 32.5)}, the aim's centre is
     * {@code (-10.5, 57.5, 34.5)}, so {@code dx = -2.0} and {@code dz = +2.0} — {@code yaw} is
     * exactly 45° and the whole segment lies on the plane {@code x + z = 24}, which is the diagonal
     * through block corners. Every cell boundary it crosses, it crosses at a corner, and which of the
     * four cells meeting there counts as hit is then decided by the last bit of the direction vector.
     * Two rays that differ in that bit tie-break opposite ways, one cell apart, which is exactly the
     * shape of the recorded disagreement.
     *
     * <p>Note what this is NOT: the fill's stale-aim trap, where the body moved between the question
     * and the shot. Here it is one tick and one eye. The measurement that ruled quantisation out for
     * that one was taken on a much steeper aim and does not carry over — an eye-drift row of 0.00
     * says nothing about a ray riding an edge.
     *
     * <p>And the fix does not rest on the mechanism being right. Whatever splits the two rays, the
     * one that decides is now the one that fires.
     *
     * <p>So the candidate is not accepted on the segment's word. It is aimed at for real, and the
     * SAME {@code aimedAt} the {@code .picks} gate runs a moment later has to agree; a candidate that
     * disagrees is skipped and the next one tried, which is why the two-element list matters — the
     * backing is a nearly horizontal shot and the floor is a steep one, and an edge-riding geometry
     * is very unlikely to be shared by both.
     */
    static BlockPos aimThatLandsIn(ServerLevel level, JourneyRig rig, BlockPos target,
                                           Direction away, String tag) {
        var eye = rig.player().getEyePosition();
        int candidate = 0;
        // NAME THE NULL. Every reject below used to `continue` in silence, and the method returned
        // null having written nothing — so a reader could not tell "never called" from "called, and
        // both candidates failed". Measured across ten rehearsals of the portal rung: not one
        // `.walked` / `.settled` row existed anywhere, and the honest reading of that zero is the
        // second one. It matters because of what the caller does next: `placeFluid` computes
        // `at = settled != null ? settled : planned` and fires the PRE-WALK aim when this returns
        // null — a guaranteed miss, and the shape of `浇不到 4,57,19` in three of those ten runs.
        // The row is written on the way out whether or not anyone is looking, for the reason
        // JourneyPortalRung's stairs audit gives: a diagnosis that only speaks when someone already
        // suspects it is not evidence.
        StringBuilder no = new StringBuilder();
        for (BlockPos aim : List.of(target.relative(away), target.below())) {
            candidate++;
            String what = "候选" + candidate + " " + aim.toShortString();
            if (!level.getBlockState(aim).isSolidRender(level, aim)) {
                no.append(what).append("=不是实心渲染(")
                        .append(level.getBlockState(aim).getBlock()).append(") ");
                continue;
            }
            var to = net.minecraft.world.phys.Vec3.atCenterOf(aim);
            if (eye.distanceTo(to) > JourneyFill.BUCKET_REACH) {
                no.append(what).append(String.format(java.util.Locale.ROOT, "=够不着(%.2f>%.2f) ",
                        eye.distanceTo(to), JourneyFill.BUCKET_REACH));
                continue;
            }
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, to,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, rig.player()));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                no.append(what).append("=线段没打到方块(").append(hit.getType()).append(") ");
                continue;
            }
            if (!hit.getBlockPos().equals(aim)) {
                no.append(what).append("=线段先撞上 ").append(hit.getBlockPos().toShortString())
                        .append('=').append(level.getBlockState(hit.getBlockPos()).getBlock())
                        .append(' ');
                continue;
            }
            if (!aim.relative(hit.getDirection()).equals(target)) {
                no.append(what).append("=打中了但面朝 ").append(hit.getDirection())
                        .append("，流体会落进 ")
                        .append(aim.relative(hit.getDirection()).toShortString()).append(' ');
                continue;
            }
            // THE SHOT, not the prediction of it. Aiming here is not a side effect to apologise for:
            // the caller's very next act is to aim at whatever this returns, so the body ends up
            // pointing at the candidate either way — this only makes the decision and the aim the
            // same act.
            var fired = fire(rig.avatar(), rig.player(), aim);
            BlockPos into = landedIn(fired);
            if (target.equals(into)) return aim;
            // Named, and named per candidate. A row that only said「没有能浇的落脚点」would send the
            // next reader looking at the geometry, which is fine — and this one says the geometry was
            // fine and the two rays disagreed, which is a different search entirely.
            rig.evidence(tag + ".aimForked." + candidate, "线段 clip 说瞄 "
                    + aim.toShortString() + " 会落进 " + target.toShortString()
                    + "，但存成角度之后真正的射线落进 "
                    + (into == null ? String.valueOf(fired.getType()) : into.toShortString())
                    + " —— 换下一个候选（身体 " + rig.player().blockPosition().toShortString()
                    + "，眼睛 " + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", eye.x, eye.y, eye.z)
                    + " 朝 yaw=" + String.format(java.util.Locale.ROOT, "%.2f", rig.player().getYRot())
                    + " pitch=" + String.format(java.util.Locale.ROOT, "%.2f", rig.player().getXRot()) + "）");
            no.append(what).append("=两条射线不一致，见 aimForked.").append(candidate).append(' ');
        }
        rig.evidence(tag + ".noAim", "从这儿没有能落进 " + target.toShortString() + " 的瞄法："
                + no + "（身体 " + rig.player().blockPosition().toShortString() + "，眼睛 "
                + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f", eye.x, eye.y, eye.z)
                + " 朝 yaw=" + String.format(java.util.Locale.ROOT, "%.2f", rig.player().getYRot())
                + " pitch=" + String.format(java.util.Locale.ROOT, "%.2f", rig.player().getXRot()) + "）");
        return null;
    }

    /**
     * Aim at {@code at} and take the shot the bucket would take — the ray that DECIDES, not the
     * segment that chose the candidate. See {@link #aimThatLandsIn} for why the two are not the same
     * line even from a body standing still.
     *
     * <p>Two calls rather than one because both halves are read: the hit's own type is the only
     * thing that distinguishes「射线没打到方块」from a landing, and {@link #landedIn} throws that
     * away. Package-private so an isolated arena can fire the production shot at a staged mould
     * instead of hand-rolling a clip beside it.
     */
    static net.minecraft.world.phys.BlockHitResult fire(net.magicterra.worlddriver.bot.movement.Avatar av,
                                                        ServerPlayer body, BlockPos at) {
        av.aimAtBlock(at);
        return JourneyHands.aimedAt(body, JourneyFill.BUCKET_REACH, false);
    }

    /** Which cell a filled bucket would empty into, given that shot — the cell in front of the face
     *  it hit, or null when it hit nothing. */
    static BlockPos landedIn(net.minecraft.world.phys.BlockHitResult fired) {
        return fired.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? fired.getBlockPos().relative(fired.getDirection()) : null;
    }

    /** The nearest cell the body could stand in at all, ray or no ray. Kept apart from the aim scan
     *  so a body is never left with nowhere to go because the ray test is stricter than it should be
     *  — the pour's own {@code .picks} gate still refuses to spend the bucket, so falling back here
     *  cannot cause a wrong-cell pour. */
    private static BlockPos firstStandable(ServerLevel level, ServerPlayer body, BlockPos target,
                                           Direction away) {
        BlockPos from = body.blockPosition();
        BlockPos standable = null;
        double standableD = Double.MAX_VALUE;
        for (BlockPos foot : standCandidates(target, away)) {
            if (!level.getBlockState(foot.below()).blocksMotion()) continue;
            if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) continue;
            BlockPos head = foot.above();
            if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) continue;
            double d = foot.distSqr(from);
            if (d < standableD) { standableD = d; standable = foot; }
        }
        return standable;
    }

    /**
     * Every foot cell a pour weighs, in one place.
     *
     * <p>The bounds were written out twice — here and in {@link #standToAimAt} — and the two had to
     * agree for the standable fallback to mean anything. They also have to be readable from OUTSIDE,
     * because can be answered by no veto histogram: it counts REASONS, not cells, so a row
     * that names a cell says nothing about which candidate cast that vote. Rung 12 spent a round
     * reading {@code 4, 60, 18} as though the stand printed beside it had produced it. See
     * {@code wd.pourLineBlockedByTheStepTheScoopLeft}, which walks this list and attributes every
     * vote to the cell that cast it.
     *
     * <p>Four back, two either side, seven rows down: the alcove is {@code push}-deep and five wide,
     * so this is the whole of it plus the reach a body has from the rank behind.
     */
    static List<BlockPos> standCandidates(BlockPos target, Direction away) {
        List<BlockPos> out = new java.util.ArrayList<>();
        for (int back = 1; back <= 4; back++)
            for (int side = -2; side <= 2; side++)
                for (int dy = 0; dy >= -6; dy--)
                    out.add(target.relative(away.getOpposite(), back)
                            .relative(away.getClockWise(), side).above(dy));
        return out;
    }

    /**
     * How one candidate foot weighs, and the veto it writes when it does not.
     *
     * <p>The body of {@link #standToAimAt}'s loop, extracted so the same scan can be asked cell by
     * cell — a scene that has to say WHICH cell produced a veto cannot get that from the histogram,
     * and re-implementing the four guards beside it would be an imitation rather than the subject.
     */
    static int gradeFoot(ServerLevel level, ServerPlayer body, BlockPos foot, BlockPos backing,
                         BlockPos target, Map<String, Integer> why) {
        if (!level.getBlockState(foot.below()).blocksMotion()) {
            why.merge("脚下不实心", 1, Integer::sum);
            return JourneySight.REFUSED;
        }
        if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) {
            why.merge("落脚格被占", 1, Integer::sum);
            return JourneySight.REFUSED;
        }
        BlockPos head = foot.above();
        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
            why.merge("头顶被占", 1, Integer::sum);
            return JourneySight.REFUSED;
        }
        // A BODY IN WATER FLOATS, and the whole aim turns on one block of height.
        //
        // The cast's own bucket floods the corridor - the source sits in an interior cell open to it
        // - so by the third cell the row the pours stand in is water. The body then does not stand in
        // the cell this loop picked; it bobs a block above it. Measured, cell 2:
        // `water2.stand=-9,56,37` chosen and `water2.picks=...body -9,57,37` an instant later, and
        // from that extra block the ray to a backing two away enters the plane one row high -
        // `落进 -9,58,37` for a target at `-9,57,38`. Nothing was wrong with the choice; the body was
        // not where the choice assumed. So predict the float instead of assuming it away, and require
        // the extra headroom the floating body actually occupies.
        boolean afloat = !level.getFluidState(foot).isEmpty();
        if (afloat) {
            BlockPos over = foot.above(2);
            if (!level.getBlockState(over).getCollisionShape(level, over).isEmpty()) {
                why.merge("浮起来会顶到 " + over.toShortString(), 1, Integer::sum);
                return JourneySight.REFUSED;
            }
        }
        // THE WHOLE CELL, not the one point in it a body is never at. See JourneySight for the eye
        // this used to test and the eye that fired a tick later, three tenths of a block apart and
        // one column of crossings apart with it.
        return JourneySight.pourGrade(level, body, foot, afloat, backing, target, why);
    }

    private static BlockPos standToAimAt(ServerLevel level, ServerPlayer body, BlockPos target,
                                         Direction away, BlockPos backing, Map<String, Integer> why,
                                         boolean robustOnly) {
        BlockPos from = body.blockPosition();
        BlockPos best = null, loose = null;
        double bestD = Double.MAX_VALUE, looseD = Double.MAX_VALUE;
        for (BlockPos foot : standCandidates(target, away)) {
            double d = foot.distSqr(from);
            int grade = gradeFoot(level, body, foot, backing, target, why);
            if (grade == JourneySight.REFUSED) continue;
            if (grade == JourneySight.ANYWHERE) {
                if (d < bestD) { bestD = d; best = foot; }
            } else if (d < looseD) { looseD = d; loose = foot; }
        }
        // A CENTRE-ONLY SPOT IS A PLACE TO WALK TO AND NOT AN ANSWER TO「要不要垒台阶」.
        // standLevelWith asks in verified-only mode and skips the raise on a yes, so a maybe there
        // costs the cell; the pour asks for somewhere to stand, and its .picks gate is what spends
        // the bucket, so a maybe there costs at most one approach.
        return best != null ? best : (robustOnly ? null : loose);
    }
}
