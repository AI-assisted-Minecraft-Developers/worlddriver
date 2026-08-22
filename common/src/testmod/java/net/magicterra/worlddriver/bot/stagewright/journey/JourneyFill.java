package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Getting fluid into a bucket: where to stand at a pool, what to aim at, and what a miss means.
 *
 * <p>Split out of {@link JourneyPortalRung} the same mechanical way {@link JourneyStairs} and
 * {@link JourneyForge} were, and for the same reason: that file is at its source budget and this is
 * the half of the rung that has nothing to do with the mould. Nothing changed in the move except
 * what the compiler forced — {@link #pinTheFillStation} takes the stairwell mouth as an argument
 * instead of reading the rung's field, and {@link #scoop} calls the rung's plant clear by name.
 *
 * <p>The rung pours; this fills. The two halves fail in opposite ways and the readings say so:
 * a pour that misses puts fluid in the wrong cell and still reports {@code CONSUME}, while a fill
 * that misses reports {@code PASS} or {@code FAIL} and leaves the bag exactly as it was — which is
 * why every fill here is judged on what it ADDED and never on what the bag holds.
 */
public final class JourneyFill {

    private JourneyFill() {}

    /** Where every lava fill stands, chosen once and used ten times. Null means none qualified and
     *  the fills fall back to picking a stand per trip — which is the thing this replaces. */
    private static BlockPos fillStation;

    /** How far from the stairwell's mouth a station may sit.
     *
     *  <p>Was five, on the reasoning that a near station is a short walk. That is the right GOAL and
     *  distance is the wrong measure of it: what makes a walk cheap is the ground it crosses, and
     *  five blocks of it can be the pool's own mouth. Measured on the archived {@code south}
     *  rehearsal (2026-08-16, {@code FAIL 12595t}): every cell within five of the stairwell mouth
     *  that could see a source had lava under the line to it, so the station HAD to be the one that
     *  drops the body in. The four that do not are seven and eight out — {@code -9,64,13},
     *  {@code -9,64,14}, {@code -8,64,13}, {@code -8,64,14} — which is what this number now has to
     *  reach. It is a widening only in company with {@link #lavaUnderTheWalk}: on its own it would
     *  just offer the ranking more cells over the same hole. */
    private static final int STATION_REACH = 8;

    /** How many sources a station must be able to see to be worth having at all. ONE, and the
     *  richest candidate wins — not ten, which is what the rung spends.
     *
     *  <p>Asked as ten it found nothing: run 39 measured {@code 够得着的源块不足 10=99}, ninety-nine
     *  cells that were dry, standable, near-bank and looking at the lake, all rejected, and the fills
     *  fell back to the per-trip choice that drowns the body. A station that sees six is not a
     *  station that fails on the seventh cast — the lake keeps flowing — and it is unconditionally
     *  better than the route it replaces. The count goes in the evidence so a run that finished on a
     *  thin one cannot read like a run that finished on a fat one. */
    private static final int STATION_SOURCES = 1;

    /**
     * Cut the fetch trip down to one walk the body makes ten times, instead of ten choices.
     *
     * <p>This is the staircase's lesson applied to the other end of the trip. {@link #standToFill}
     * ranks stands by straight-line distance from the body, and the straight line from the
     * stairwell's mouth to the far bank goes over the lake — so the walker took it, and six runs
     * running the body ended up UNDER the surface: {@code cast2.return=-10,60,20},
     * {@code climb.0 above=Block{minecraft:lava} onGround=false},
     * {@code climb.0.wouldOpenFluid=-10,62,20 挖开就会放出 lava —— 不挖}, {@code exit.gained=0/6}.
     * There is no recovering from inside a lake whose ceiling the climb is (correctly) forbidden to
     * mine, so the answer is not to go. A choice that is right sixty percent of the time fails a
     * ten-trip rung almost always; a route walked once and proved is walked ten times.
     *
     * <h2>Why the rim was never a candidate</h2>
     *
     * {@code standToFill} looks at {@code src.offset(±1, -2..+1, ±1)} — cells beside or just under a
     * source. The rim of a bowl-shaped lake is THREE above its surface, so no rim cell was ever in
     * that set, and every stand it could offer was down at the waterline on the far side. Standing
     * high and aiming DOWN is both in reach and on dry land, and it is the shape a player uses.
     *
     * <p>Chosen for the most sources in reach rather than the nearest, because the bank degrades:
     * each fill takes a source away, and a station that only ever had one is a station that works
     * once. Nothing is mined — the candidate has to be standable as it already is, so this cannot
     * breach the pool and the fluid guard is never even asked.
     *
     * <h2>Standable is not the same as standable ten times</h2>
     *
     * The body walks here ten times, so a cell that is legal to stand in but sits in a notch over the
     * lake is a cell it visits ten times and falls off once. That is measured, twice, on the
     * {@code south} rehearsal geometry ({@code station = -14, 65, 21}, whose east side is open air
     * down to the lava at {@code y=63}):
     *
     * <pre>
     * run A (FAIL 12595t)  trip 7  06:39:49 search-begin start=-9, 66, 21 goal=-14,65,21
     *                              06:39:55 footing guard: sole 0.0938 at -12,66,21
     *                              06:39:58 search-begin start=-13, 62, 19   ← in the lake
     *                              …sank to -15,59,19; ascendByTowering cannot pillar out of lava
     *                              (climb.0 above=lava … stuck (no Y gain)) holding 128 cobblestone
     * run B (FAIL 17410t)  trip 8  lava8.aimsAt … 眼睛 -13.70/63.62/22.84    ← filled from y=62
     *                              cast8.returnStuck3#1.gained = 4/4         ← pillared back out
     *                              cast8.returnStopped 停在 -14, 66, 21 …脚下 air  ← and wedged
     * </pre>
     *
     * <p>Two runs, two different deaths, one cell. Run B's is the plainer of the two: the body ends
     * up in the cell ABOVE the station with nothing under its feet, {@code soleOnSolid} at zero, and
     * vanilla's {@code maybeBackOffFromEdge} then shrinks every horizontal move to nothing — pinned
     * on the doorstep of the stand it was pinned to. Eleven {@code footing guard} lines in run A say
     * the same thing about the approach.
     *
     * <p>So a candidate is refused when the lake is a step away from it — {@link JourneyTerrain#onThePoolsLip},
     * the hazard half of the walker's own {@code lethalDropAdjacent}, asked at the cell AND at the
     * cell above it, because the body arrives there first and run B never got any further.
     *
     * <p><b>A preference, not a rule</b>, the same two-pass shape and for the same reason as
     * {@link #standToFill}: a bank the strict pass empties is a bank the fills would answer by
     * choosing per trip, which is the route that drowned six earlier runs. The evidence row says
     * which pass answered, so a station kept on the lip can never read like one chosen clear of it.
     *
     * <p>Refusing the ROUTE instead was tried first and measured inert: a check for lava under the
     * straight line from the mouth turned away four candidates and kept {@code -14,65,21}, which is
     * run B. The lake there is not under the walk, it is beside the destination.
     */
    static void pinTheFillStation(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY,
                                  BlockPos stairTop) {
        ServerLevel level = ctx.level();
        fillStation = null;
        if (stairTop == null) return;
        List<BlockPos> sources = new ArrayList<>();
        for (int dx = -12; dx <= 12; dx++)
            for (int dy = -4; dy <= 2; dy++)
                for (int dz = -12; dz <= 12; dz++) {
                    BlockPos c = lava.offset(dx, dy, dz);
                    if (level.getFluidState(c).isSource() && level.getBlockState(c).is(Blocks.LAVA))
                        sources.add(c.immutable());
                }
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        Pick strict = pickStation(level, rig, lava, surfaceY, stairTop, sources, why, true);
        Pick chosen = strict;
        String how = "脚边一步之内没有通向岩浆的空洞（严格判据）";
        if (strict == null) {
            Map<String, Integer> loose = new java.util.LinkedHashMap<>();
            chosen = pickStation(level, rig, lava, surfaceY, stairTop, sources, loose, false);
            if (chosen != null) {
                BlockPos over = JourneyTerrain.onThePoolsLip(level, chosen.foot());
                if (over == null) over = JourneyTerrain.onThePoolsLip(level, chosen.foot().above());
                how = "严格判据一格都没有，退回旧判据 —— 这一格在坑沿上（一步之外 " + over
                        + " 是岩浆），十趟里迟早有一趟掉下去，"
                        + "被它否掉的计数见「脚边就是通向岩浆的空洞」";
            }
            why.putAll(loose);
        }
        fillStation = chosen == null ? null : chosen.foot();
        rig.evidence("station", chosen == null
                ? "没找到固定装料点（湖边 " + STATION_REACH + " 格内没有站得住又看得见 "
                  + STATION_SOURCES + " 格源块的干地）—— 退回每趟各选一处，"
                  + "这正是把身体淹进湖里的那条路；否决计数 " + why
                : chosen.foot().toShortString() + "：够得着 " + chosen.seen() + " 格源块，距楼梯口 "
                  + Math.round(Math.sqrt(chosen.dist())) + " 格（十趟都站这里）；" + how
                  + "；否决计数 " + why);
    }

    /** A station candidate and the two numbers it was ranked on. */
    private record Pick(BlockPos foot, int seen, double dist) {}

    /**
     * One pass of the station scan. {@code refuseTheLip} is what separates the two.
     *
     * <p><b>Its「站得住」is the third in this package and the strictest, deliberately.</b> It asks
     * {@code getCollisionShape().isEmpty()} rather than {@code blocksMotion()}, and it refuses fluid
     * at the HEAD as well as at the foot — neither of which
     * {@code JourneyPortalEntry.standable} or {@code JourneyEndRungs.standingCellInTheRoom} does.
     * That is because this is not choosing somewhere to walk to or to dig into: it is choosing
     * somewhere to STAND AND AIM A BUCKET FROM, and a cell a body can occupy but cannot work from
     * is worthless here. See {@code standable}'s note for the clause-by-clause comparison of all
     * three, and do not substitute one for another.
     */
    private static Pick pickStation(ServerLevel level, JourneyRig rig, BlockPos lava, int surfaceY,
                                    BlockPos stairTop, List<BlockPos> sources,
                                    Map<String, Integer> why, boolean refuseTheLip) {
        Pick best = null;
        for (int dx = -STATION_REACH; dx <= STATION_REACH; dx++)
            for (int dz = -STATION_REACH; dz <= STATION_REACH; dz++)
                for (int y = lava.getY() + 1; y <= surfaceY + 1; y++) {
                    BlockPos foot = new BlockPos(stairTop.getX() + dx, y, stairTop.getZ() + dz);
                    if (!level.getBlockState(foot.below()).blocksMotion()) {
                        why.merge("脚下不实心", 1, Integer::sum); continue;
                    }
                    if (!level.getFluidState(foot).isEmpty()
                            || !level.getFluidState(foot.above()).isEmpty()) {
                        why.merge("站在流体里", 1, Integer::sum); continue;
                    }
                    if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()
                            || !level.getBlockState(foot.above())
                                    .getCollisionShape(level, foot.above()).isEmpty()) {
                        why.merge("落脚或头顶被占", 1, Integer::sum); continue;
                    }
                    if (acrossThePool(level, stairTop, foot)) {
                        why.merge("走过去要横穿岩浆", 1, Integer::sum); continue;
                    }
                    if (refuseTheLip && (JourneyTerrain.onThePoolsLip(level, foot) != null
                            || JourneyTerrain.onThePoolsLip(level, foot.above()) != null)) {
                        why.merge("脚边就是通向岩浆的空洞", 1, Integer::sum); continue;
                    }
                    int seen = sourcesInReachFrom(level, rig, foot, sources);
                    if (seen < STATION_SOURCES) {
                        why.merge("够得着的源块不足 " + STATION_SOURCES, 1, Integer::sum); continue;
                    }
                    double d = foot.distSqr(stairTop);
                    if (best == null || seen > best.seen()
                            || (seen == best.seen() && d < best.dist()))
                        best = new Pick(foot, seen, d);
                }
        return best;
    }

    /** How many lava sources a body standing here could actually fill from — same clip vanilla runs,
     *  so this counts fills and not merely neighbours. */
    private static int sourcesInReachFrom(ServerLevel level, JourneyRig rig, BlockPos foot,
                                          List<BlockPos> sources) {
        var eye = new net.minecraft.world.phys.Vec3(foot.getX() + 0.5,
                foot.getY() + rig.player().getEyeHeight(), foot.getZ() + 0.5);
        int seen = 0;
        for (BlockPos src : sources) {
            var aim = net.minecraft.world.phys.Vec3.atCenterOf(src);
            if (eye.distanceToSqr(aim) > BUCKET_REACH * BUCKET_REACH) continue;
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, rig.player()));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(src)) seen++;
        }
        return seen;
    }

    /**
     * Fill the (empty) bucket from a fluid source, standing beside it — and stop when it does not.
     *
     * <p>This used to record the {@code InteractionResult} and carry on regardless, which is how run
     * 21 poured nothing into cell zero and reported it as a casting failure. What actually happened
     * is one line above that: {@code lava0.result=FAIL}, the bucket still empty, and the pour then
     * ran with <b>cobblestone in the hand</b> ({@code cast0.hand=拿不到 minecraft:lava_bucket}).
     * A `PASS` from a block item is byte-identical to a bucket whose ray missed, so the rung's
     * verdict named the cast — three inferences from a fill nobody checked.
     *
     * <p>{@code FAIL} from an empty bucket means vanilla saw a block that is not a pickable source:
     * either the source is gone, or there is rock between the eyes and it. Those want opposite
     * responses, so the miss line records the range and what the ray actually stopped on, and the
     * retry re-targets the nearest source to WHERE THE BODY NOW IS rather than asking the same
     * question from the same cell — a retry that changes nothing is not a retry.
     */
    static void fillFrom(SceneContext ctx, JourneyRig rig, BlockPos src, String tag,
                         net.minecraft.world.item.Item wanted, Runnable then) {
        fillFrom(ctx, rig, src, tag, wanted, FILL_APPROACHES, then);
    }

    /** How many sources a fill may try before the rung stops. Three: one for a walk that ended
     *  short, one for a source another cast already spent, and one to be unlucky with. */
    private static final int FILL_APPROACHES = 3;

    private static void fillFrom(SceneContext ctx, JourneyRig rig, BlockPos src, String tag,
                                 net.minecraft.world.item.Item wanted, int tries, Runnable then) {
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(wanted));
        boolean lava = wanted == Items.LAVA_BUCKET;
        // DO NOT WALK TO THE LAKE IF THE LAKE IS ALREADY IN REACH.
        //
        // The staircase's mouth is cut beside the pool, so a body that has just climbed it is
        // usually two blocks from a source with a clear line to it — and the walk to a planned
        // stand on the far side crosses the pool's own rim. That crossing is not a slow route, it
        // is a drowning: measured in three consecutive runs, `lava2.spot=站 -13,64,21` and then
        // `cast2.return` beginning at `-10,60,20`, three blocks UNDER the surface, `climb.0
        // above=lava onGround=false`, and the scripted climb refusing to mine a ceiling with lava
        // behind it — correctly, and with nothing left to try. The trip that fetched the lava is
        // what buried the body, and it was a trip it did not need to make.
        //
        // First approach only: a fill that has already missed once needs a different question, and
        // asking this one again would hand back the same cell.
        //
        // ASKED WHERE THE BODY IS, NOT WHERE IT COMES TO REST — deliberately, and the other way
        // round has been tried. Settling first sounds strictly better (this clip is the reason the
        // fill does not walk, so it deserves a still body) and measured worse: a ten-tick settle
        // here gave `recover8` eight extra ticks of falling, `眼睛 y 61.65→58.06`, after which
        // nothing was in view, the fill walked, and the walk mined a cast frame cell on its way back
        // up. See HoldStill for the whole chain. A wrong answer from here costs one aim, which
        // `scoop` re-takes; a body four blocks lower costs the rung.
        if (tries == FILL_APPROACHES) {
            BlockPos inReach = visibleSourceNear(rig, lava, FILL_RESEARCH);
            if (inReach != null) {
                rig.evidence(tag + ".fromHere", rig.player().blockPosition().toShortString()
                        + " 已经看得见源块 " + inReach.toShortString() + "（够得着），不走过去了"
                        + "；" + eyeNow(rig));
                JourneyHands.holdForUse(rig, Items.BUCKET, tag);
                scoop(ctx, rig, src, inReach, tag, wanted, id, lava, tries, AIM_TRIES, then);
                return;
            }
        }
        // WHERE TO STAND is chosen before the walk, not discovered after it. `Goal.Near(src, 2)` puts
        // the body within two blocks of a source and says nothing about what is between them, so
        // whether the bucket filled came down to where the climb happened to emerge: the same code
        // filled at `-12,63,21` one run and reported `射线停在 -10,63,21 stone` the next, two runs
        // apart, with nothing changed. That is the pour's old bug on the other side of the trip, and
        // this is the pour's fix on the other side of the trip.
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        // THE STATION, for every lava fill, or nothing. `standToFill` still answers for the water
        // recover — that one happens inside the alcove the rung carved, where there is no lake to
        // walk into — but the fetch trip does not get to choose again. Choosing again is the bug.
        FillSpot spot = lava && fillStation != null ? new FillSpot(fillStation, src)
                : standToFill(ctx.level(), rig, src, lava, FILL_RESEARCH, why);
        rig.evidence(tag + ".spot", spot == null
                ? "没找到能看见源块的落脚点，退回 Near(" + src.toShortString() + ",2)；否决计数 " + why
                : (lava && fillStation != null ? "站固定装料点 " : "站 ")
                  + spot.stand().toShortString() + " 瞄 " + spot.source().toShortString());
        Goal where = spot == null ? new Goal.Near(src, 2) : new Goal.Block(spot.stand());
        // THE WATER RECOVER MAY NOT DIG ITS WAY THERE. Its source sits inside the frame the rung is
        // building, so the only thing between a floor-level body and it is the frame — and a walker
        // with `allowBreak` on treats that as terrain. Measured, single-bucket rehearsal 2026-08-17:
        // `recover8.spot = 没找到能看见源块的落脚点，退回 Near(-9,61,38,2)` and then
        // `frame.lost.1 = -9,60,38 浇成黑曜石之后又没了：现在是 water，丢在「recover8 从 -9,61,38 收水」
        // 这一步里；身体 -9,59,38 距 1.0 格` — the body one cell under the cell it had just cast, which
        // is where you stand after breaking it. Every cell was poured that run (`frame.cast=9/10（浇成过
        // 10 格，浇成之后又丢了 1 格）`) and the rung still failed, on a cell the fetch destroyed.
        //
        // The LAVA fetch keeps its digging: it walks across open ground to a lake, nowhere near the
        // mould, and taking the capability away there would only make an ordinary route fail. Same
        // distinction `digWithoutTunnelling` and the mould walk already draw.
        Intent walk = lava ? new Intent(where)
                : new Intent(where, List.of(), CapabilityProfile.ALL, List.of(new NoBreak()));
        rig.settle(new IntentProcess(walk), 1_500, () -> {
            // Re-ask from where the body ACTUALLY ended up. The plan above is what makes a good spot
            // likely; this is what makes the aim correct, because a walk that stopped a cell short
            // has a different set of sources in view and only the clip from here knows which.
            // WHAT THE STATION HAS LEFT. Each fill takes a source away, so the number that matters
            // across a ten-trip rung is not "did this one work" but how much margin the station
            // still has — a run whose last casts are down to one or two visible sources is a run
            // that got away with it, and reads identically to a comfortable one without this line.
            if (lava && fillStation != null && rig.player().blockPosition().equals(fillStation))
                rig.evidence(tag + ".stationSees",
                        sourcesInReachFrom(ctx.level(), rig, fillStation,
                                JourneyTerrain.lavaSourcesNear(ctx.level(), fillStation, 6,
                                        fillStation)) + " 格源块还够得着");
            BlockPos seen = visibleSourceNear(rig, lava, FILL_RESEARCH);
            BlockPos aim = seen != null ? seen : (spot == null ? src : spot.source());
            if (!aim.equals(src)) rig.evidence(tag + ".aim", src.toShortString() + " → "
                    + aim.toShortString() + "（计划的那格被挡住，改瞄看得见的一格）");
            JourneyHands.holdForUse(rig, Items.BUCKET, tag);
            scoop(ctx, rig, src, aim, tag, wanted, id, lava, tries, AIM_TRIES, then);
        });
    }

    /** How many times a fill may re-aim, or clear its own line, before it spends the attempt.
     *  Three, and each one changes something — see {@link #scoop}. */
    private static final int AIM_TRIES = 3;

    /**
     * The eye this ray actually starts from, to the centimetre, and the rotation it points along.
     *
     * <p>Printed on BOTH sides of a question two different rays answer. {@link #visibleSourceNear}
     * clips the segment eye→block-centre; {@code JourneyHands.aimedAt} traces
     * {@code directionFromRotation(xRot, yRot)} for {@code BUCKET_REACH} from the same eye. Those
     * are nominally the SAME line, and a single-bucket rehearsal had them disagree one cell apart:
     * {@code recover9.fromHere = -10,58,35 已经看得见源块 -10,61,38（够得着）} and, an instant
     * later, {@code recover9.aimsAt = -10,60,38 Block{minecraft:obsidian} 源块=false}.
     *
     * <p>Only two things can do that and the rows could not tell them apart, because both printed
     * the CELL and a cell is 1 m wide:
     * <ul>
     *   <li>the rotation is float-quantised — {@code aimAtBlock} stores degrees as {@code float} and
     *       the trace re-derives the direction from them, so it is not exactly at the centre;
     *   <li>the BODY MOVED between the two questions. {@code scoop} aims and then settles two ticks
     *       (it has to: {@code pick()} traces from the previous tick's rotation), and two ticks of
     *       falling or floating leave the stored rotation aiming from a position the body has left.
     * </ul>
     *
     * <p>A hundredth of a block separates those two answers, so that is what this prints. The second
     * is the same family as the two ray traps this repo has already paid for; the first is a rounding
     * error and would show as a body that did not move at all.
     */
    static String eyeNow(JourneyRig rig) {
        var fp = rig.player();
        var eye = fp.getEyePosition();
        return String.format(java.util.Locale.ROOT, "眼睛 %.2f/%.2f/%.2f 朝 yaw=%.2f pitch=%.2f",
                eye.x, eye.y, eye.z, fp.getYRot(), fp.getXRot());
    }

    /** How far the eye may drift across a settle before the drift itself is worth printing. Five
     *  centimetres: under that the pitch to a cell at arm's length shifts by far less than the width
     *  of a block face, and「挪了 0.00 格」on every fill is noise in a row that already has to carry
     *  a cell, a fluid and a rotation. */
    private static final double DRIFT_WORTH_A_ROW = 0.05;

    /**
     * How far the eye travelled across the settle the aim was taken after.
     *
     * <p>The measurement this whole fix is judged on, printed where it is checkable: {@code .aimsAt}
     * says where the ray goes and this says how much the body had moved since the question that
     * chose the target. Before the aim moved to AFTER the settle, that drift was the error in the
     * aim — {@code recover9} carried 0.54 blocks of it and put the ray a full cell low. Now the aim
     * is recomputed from the far side of it, so a large drift here is no longer an aiming bug; it is
     * a body that is falling, and the row says so rather than leaving it to be inferred from two
     * eye coordinates printed in different places.
     */
    private static String settleDrift(JourneyRig rig, net.minecraft.world.phys.Vec3 was) {
        double moved = was.distanceTo(rig.player().getEyePosition());
        return moved <= DRIFT_WORTH_A_ROW ? "" : String.format(java.util.Locale.ROOT,
                "；settle 这两 tick 里眼睛挪了 %.2f 格（y %.2f→%.2f）—— 瞄准是落定后重算的",
                moved, was.y, rig.player().getEyePosition().y);
    }

    /**
     * Aim, check where the ray actually goes, and only then use the bucket.
     *
     * <p>The pour has had this gate for a while and the fill did not, which is the whole of run 18's
     * failure: {@code lava0.spot} planned a stand from which the clip landed on the source,
     * {@code lava0.result=FAIL} an instant later, and {@code lava0.miss.3} explained why —
     * {@code 瞄 -11,63,21（现在是 lava），距 1.8m，射线停在 -11,64,22 Block{minecraft:gravel}}. Between
     * choosing the spot and using the bucket, <b>a gravel block fell into the line</b>. Nothing was
     * wrong with the plan; the world moved under it.
     *
     * <p>So the ray is predicted rather than assumed, and a prediction that misses gets one of two
     * answers, both of which change the world rather than repeat the question:
     * <ul>
     *   <li>the ray landed somewhere else and a DIFFERENT source is now visible — aim at that one;
     *   <li>the ray stopped on a solid block inside arm's reach — break it. At a lake's edge that
     *       block is gravel or a lip of stone, and breaking it is what a player does.
     * </ul>
     */
    private static void scoop(SceneContext ctx, JourneyRig rig, BlockPos src, BlockPos aim, String tag,
                              net.minecraft.world.item.Item wanted, String id, boolean lava,
                              int tries, int aims, Runnable then) {
        // SETTLE FIRST, THEN AIM, AND USE IN THE SAME INSTANT. The order is the fix; the ticks are
        // unchanged.
        //
        // `aimAtBlock` stores an ANGLE, not a target: it computes yaw/pitch from where the eye is
        // when it is called and writes them to the body. Everything downstream —
        // `JourneyHands.aimedAt` here, and `Item.getPlayerPOVHitResult` inside
        // `BucketItem.use` — re-derives a direction from those angles and starts it at the LIVE eye.
        // So a body that moves between the aim and the use fires a ray computed for a position it
        // has left, and neither reading can see that: both print a CELL, and a cell is a metre wide.
        //
        // Measured, single-bucket rehearsal 2026-08-15. `recover9` aimed at the water in
        // `-10,61,38` from eye y=60.16, then settled two ticks to y=59.62 and landed there
        // (`59.62 − 1.62 = 58.00`, an integer floor; `60.16 − 1.62 = 58.54` is mid-air), and the
        // stored pitch of −25.14 put the ray into `-10,60,38` — the obsidian one row below, which
        // the frame guard then correctly refused to mine, spending the attempt. Hand-checked with
        // `atan2`: the pitch to that source is −25.09 from the old eye and −33.03 from the new one,
        // and −33.03 from y=59.62 passes over `-10,60,38` at y≈61.2 and lands in the water. The aim
        // was never wrong about the target; it was wrong about where it was standing. Third of this
        // repo's ray-timing traps, after `pick()`'s previous-tick rotation and the bucket's own aim.
        //
        // The two ticks are NOT owed to `pick()`, which is what the comment here used to claim: the
        // bucket never goes through `pick()`. `Item.getPlayerPOVHitResult` reads `getXRot()` /
        // `getYRot()` / `getEyePosition()` live, so an aim, a prediction and a use in ONE tick all
        // see the same thing. What the ticks buy is physics — an unregistered body does not fall at
        // all — so they stay, and everything that depends on the aim moves to after them.
        //
        // Spending MORE of them is not the safer version of this; it is a different bug. See
        // HoldStill for the run where a ten-tick wait dropped the body four blocks.
        var eyeBeforeSettling = rig.player().getEyePosition();
        rig.settle(new HoldStill(2), 10, () -> {
            // BOTH bodies, because the very next line is a PREDICTION GATE on the server one.
            // `aimedAt(rig.player(), …)` rays the ServerPlayer; `rig.avatar()` on this topology is
            // the client. Aim only the client and this gate reads a body nobody pointed — and it
            // does not merely mis-report, it ACTS: `onTarget=false` sends the run into re-aim, into
            // `mineCellOrGiveUp` on a "blocker" that was never on the line, or into
            // `stepOutOfTheFrame`. All three change the world on a reading that was never about the
            // ray the use would fire. (The use itself needs no server aim — see `aimBoth`.)
            JourneyHands.aimBoth(rig, aim);
            ServerLevel level = ctx.level();
            var pre = JourneyHands.aimedAt(rig.player(), BUCKET_REACH, true);
            // A SOURCE, not merely the right cell with the right fluid in it. `BucketItem.use` clips
            // with `Fluid.SOURCE_ONLY` and returns PASS — doing nothing whatsoever — when that clip
            // finds none, and PASS is exactly what run 26 got: `recover1.result=PASS` beside
            // `射线停在 -10,57,38 Block{minecraft:water}` at 1.8 m. The cell was water and was not a
            // source, and nothing here could tell those apart, so a fill vanilla had refused outright
            // read as a fill that missed — and the retry then aimed at the same non-source again.
            var fluid = pre.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    ? level.getFluidState(pre.getBlockPos()) : null;
            rig.evidence(tag + ".aimsAt", (fluid == null ? String.valueOf(pre.getType())
                    : pre.getBlockPos().toShortString() + " "
                      + level.getBlockState(pre.getBlockPos()).getBlock()
                      + " 源块=" + fluid.isSource() + " 液位=" + fluid.getAmount()
                      + (pre.getBlockPos().equals(aim) ? "" : "（想瞄 " + aim.toShortString() + "）"))
                    + "；" + eyeNow(rig) + settleDrift(rig, eyeBeforeSettling));
            boolean onTarget = fluid != null && pre.getBlockPos().equals(aim) && fluid.isSource();
            if (!onTarget && aims > 0) {
                BlockPos again = visibleSourceNear(rig, lava, FILL_RESEARCH);
                if (again != null && !again.equals(aim)) {
                    rig.evidence(tag + ".reaim." + aims, aim.toShortString() + " → "
                            + again.toShortString() + "（射线没落在计划那格上）");
                    scoop(ctx, rig, src, again, tag, wanted, id, lava, tries, aims - 1, then);
                    return;
                }
                if (pre.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && level.getFluidState(pre.getBlockPos()).isEmpty()) {
                    BlockPos wall = pre.getBlockPos();
                    Runnable retry = () ->
                            scoop(ctx, rig, src, aim, tag, wanted, id, lava, tries, aims - 1, then);
                    // THE MOULD IS NOT A WALL. This branch answers a blocked sightline by mining the
                    // blocker, and down in the alcove the tallest thing between an eye and a cell is
                    // the frame the rung is there to build — measured, {@code recover9.clearedLine.3
                    // = -10,60,38 Block{minecraft:obsidian} 挡在眼睛和 -10,61,38 之间，敲掉它}, one
                    // cast cell taken back by the fill that came after it.
                    //
                    // <p>AND IT REALLY IS TAKEN BACK, which is the half that was worth checking
                    // before writing this. Obsidian needs a diamond pickaxe and this body carries
                    // stone, so "敲掉它" could have been a swing at nothing — three wasted aims and
                    // an innocent line. It is not: {@code ServerPlayerAvatar.breakHold} calls
                    // {@code Level#destroyBlock}, which has no tool-level gate at all (its own
                    // javadoc says so outright — this avatar "harvests obsidian with its fists"),
                    // so the swing lands and the frame cell is gone.
                    //
                    // Refused by COORDINATE, not by block id. The ten ring cells are a set this rung
                    // computed itself; a block-id test would also protect obsidian that has nothing
                    // to do with the frame, and would stop protecting a cell the moment something
                    // else got into it.
                    if (JourneyPortalRung.isFrameCell(wall)) {
                        rig.evidence(tag + ".frameOnLine." + aims, wall.toShortString() + " "
                                + level.getBlockState(wall).getBlock() + " 挡在眼睛和 "
                                + aim.toShortString() + " 之间，但它是门框格 —— 不敲，"
                                + "换个角度再看（身体 " + rig.player().blockPosition().toShortString()
                                + "，眼睛 y=" + String.format(java.util.Locale.ROOT, "%.2f",
                                        rig.player().getEyePosition().y) + "）");
                        stepOutOfTheFrame(ctx, rig, src, aim, tag, wanted, id, lava, tries, aims,
                                then);
                        return;
                    }
                    rig.evidence(tag + ".clearedLine." + aims, wall.toShortString() + " "
                            + level.getBlockState(wall).getBlock() + " 挡在眼睛和 "
                            + aim.toShortString() + " 之间，敲掉它");
                    // A PLANT is not a wall, and `mine` will not treat it as one. The lake's rim is
                    // hung with vines, and run 29 spent all three aims on the same one:
                    // `lava2.clearedLine.3/2/1 = -9,67,21 vine`, three identical lines, the vine
                    // still there each time — a retry that changes nothing. The same one-swing
                    // clear the pour uses breaks it, so use that whenever the blocker has no
                    // collider and keep `mine` for things that actually are walls.
                    if (level.getBlockState(wall).getCollisionShape(level, wall).isEmpty()) {
                        JourneyPortalRung.clearPlantOnLine(ctx, rig, aim, tag + ".line" + aims, retry);
                        return;
                    }
                    rig.mineCellOrGiveUp(wall, 600, retry);
                    return;
                }
            }
            spendTheBucket(ctx, rig, aim, tag, wanted, id, lava, tries, then);
        });
    }

    /**
     * Look PAST the frame rather than through it: walk to a cell the clip verifies, or give up.
     *
     * <p>The one response this may not make is the one it replaces. Everything else here is allowed
     * to change the world — that is what makes a retry a retry — but the frame is the rung's own
     * product, so the only thing left to change is where the eye is.
     *
     * <p>And when there is nowhere to move to, it says so and spends the attempt. That is
     * deliberate: a recursion that walks nowhere and asks the same question is exactly the
     *「retry that changes nothing」this file has already paid for twice, and a fill that reports
     * {@code .miss} with its full geometry is a better row than three identical ones.
     */
    private static void stepOutOfTheFrame(SceneContext ctx, JourneyRig rig, BlockPos src, BlockPos aim,
                                          String tag, net.minecraft.world.item.Item wanted, String id,
                                          boolean lava, int tries, int aims, Runnable then) {
        ServerLevel level = ctx.level();
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        FillSpot spot = standToFill(level, rig, aim, lava, FILL_RESEARCH, why);
        BlockPos here = rig.player().blockPosition();
        if (spot == null || spot.stand().equals(here)) {
            rig.evidence(tag + ".frameStuck." + aims, "门框挡着 " + aim.toShortString()
                    + "，而且没有别的落脚点看得见它（身体 " + here.toShortString()
                    + (spot == null ? "，一处都没验过" : "，验得过的就是脚下这一格")
                    + "）；否决计数 " + why);
            spendTheBucket(ctx, rig, aim, tag, wanted, id, lava, tries, then);
            return;
        }
        rig.evidence(tag + ".stepOut." + aims, here.toShortString() + " → " + spot.stand().toShortString()
                + "（从那里射线落得到 " + aim.toShortString() + "，不用敲门框）");
        rig.settle(new IntentProcess(new Intent(new Goal.Block(spot.stand()), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), 300,
                () -> scoop(ctx, rig, src, aim, tag, wanted, id, lava, tries, aims - 1, then));
    }

    /**
     * Aim taken, line accepted: use the bucket and judge it by what the bag GAINED.
     *
     * <p>Split out of {@link #scoop} so the frame guard above has somewhere to give up to. Every
     * exit from the aiming loop ends here exactly once, which is what keeps「the attempt was spent」
     * from ever meaning「the attempt vanished」.
     */
    private static void spendTheBucket(SceneContext ctx, JourneyRig rig, BlockPos aim,
                                       String tag, net.minecraft.world.item.Item wanted,
                                       String id, boolean lava, int tries, Runnable then) {
        ServerLevel level = ctx.level();
        // The bucket goes back in the hand HERE, not once at the top of the fill. Clearing the
        // line above is a MINE, and mining selects the best tool for the block — so the branch
        // that fixes the sightline is also the branch that swaps a stone pickaxe into the slot
        // the use is about to read. Measured in run 28: `recover1.clearedLine.3` broke the
        // cobblestone, `recover1.aimsAt=-10,57,38 water 源块=true 液位=8` said the ray was dead
        // on the source, and `recover1.result=PASS` — a pickaxe's use, indistinguishable from a
        // bucket that missed, which is the same trap `holdForUse` was written for.
        JourneyHands.holdForUse(rig, Items.BUCKET, tag);
        // WHAT THIS USE CHANGED, not what the bag happens to hold. `carrying(id) >= 1` is the
        // same claim as "this fill worked" only while the body can carry exactly one — and it
        // could, so the two were indistinguishable and the weaker one shipped. Carry two and the
        // second fill passes before it is attempted: the first bucket is already in the bag, so
        // the test is true whatever `useItemInHand` did, and a fill that missed reports success
        // and walks a full bucket short to a pour that will report「浇不出黑曜石」. Measure the
        // DELTA and that is impossible at any bucket count.
        int before = rig.carrying(id);
        rig.evidence(tag + ".result", String.valueOf(rig.avatar().useItemInHand()));
        // WAIT FOR THE ROUND TRIP BEFORE JUDGING — the mirror of aiming, not a contradiction of it.
        // The aim must be adjacent to the act on the body that ACTS; the OUTCOME is produced by
        // that client body and has to travel back before `rig.carrying` (the ServerPlayer's
        // inventory) or `ctx.level()` can see it. Judged in the use's own tick, a fill that worked
        // is byte-identical to one vanilla refused, and every branch below — retarget, and the
        // `ctx.fail` that ends the rung — then fires on a reading taken too early.
        //
        // Rung 12's own fill (JourneyPortalRung.scoopWater) died exactly this way on 2026-08-22
        // with `result=SUCCESS, water_bucket=0, cellAfter=water`: three rows that cannot describe
        // one moment. This site is the same shape and had the same hole.
        rig.settle(new HoldStill(3), 12, () -> {
            int after = rig.carrying(id);
            if (after > before) { then.run(); return; }
            // Only trustworthy since `scoop` started aiming BOTH bodies. This rays rig.player(),
            // the server body, and before that fix nothing had ever pointed it — so every
            // 「射线停在 …」 this row printed described a direction the use never took.
            var hit = JourneyHands.aimedAt(rig.player(), BUCKET_REACH, true);
            double range = rig.player().getEyePosition()
                    .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(aim));
            rig.evidence(tag + ".miss." + tries, String.format(java.util.Locale.ROOT,
                    "这一次没装上（%s %d→%d，已等过 3 tick 往返）；瞄 %s（现在是 %s），距 %.1fm，射线停在 %s",
                    id, before, after,
                    aim.toShortString(), level.getBlockState(aim).getBlock(), range,
                    hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                            ? hit.getBlockPos().toShortString() + " "
                              + level.getBlockState(hit.getBlockPos()).getBlock()
                            : String.valueOf(hit.getType())));
            // A DIFFERENT source, explicitly. The old line asked for "the nearest one" and got back
            // the cell that had just failed, so the guard below refused the retry and the rung died
            // with two of its three approaches unspent — measured as
            // 「瞄了 -11,63,21 没装上，改瞄 -11,63,21 仍然不行」.
            BlockPos other = nextSourceBesides(ctx, rig, lava, aim);
            if (tries > 1 && other != null) {
                rig.evidence(tag + ".retarget." + tries, aim.toShortString() + " → "
                        + other.toShortString());
                fillFrom(ctx, rig, other, tag, wanted, tries - 1, then);
                return;
            }
            ctx.fail("装不到 " + id + "：瞄了 " + aim.toShortString() + " 没装上，"
                    + (other == null ? "身边 " + FILL_RESEARCH + " 格内没有别的源块可换"
                                     : "改瞄 " + other + " 仍然不行")
                    + "；身边的源块：" + sourcesNear(level, rig.player().blockPosition(),
                            FILL_RESEARCH, lava)
                    + " —— 空着桶走下去只会把失败写成「浇不出黑曜石」，而真正的失败在这里"
                    + "（见 " + tag + ".miss.*）");
        });
    }

    /**
     * Fill EVERY empty bucket the body is carrying, in one visit to the pool.
     *
     * <p>The trip is what this rung fails in. Ten cells each did their own
     * {@code goUpToThePool → fillFrom → returnToTheForge}, and the three failures that have ended
     * runs — falling into the pit the fill itself left in the lake, not finding the way back to the
     * stairwell mouth, water in the doorway — all live on that walk and nowhere else. The pour is
     * cheap; the commute is the risk, and ten of them buy nothing that four do not.
     *
     * <p><b>Trips are decided by empty buckets, not by a flag.</b> This makes the count
     * {@code ceil(10 / buckets-that-can-hold-lava)}, so it degrades on its own: with the kit the
     * ladder can currently afford — one bucket, because {@code IRON_INGOTS_THE_KIT_COSTS} buys a
     * bucket and a flint-and-steel and no more — the loop below stops before its first iteration and
     * the rung walks the same ten trips it walks today, instruction for instruction. Give it four
     * buckets and the same code makes four trips. That is why this is not gated on the iron: nothing
     * has to land with it, and nothing breaks if the iron never arrives.
     *
     * <p><b>The first bucket is the one that matters, and only it may fail the rung.</b> It goes
     * through the full {@link #fillFrom} — three approaches, re-aiming, clearing its own sightline —
     * and keeps that method's verdict, because arriving at the mould empty-handed is exactly the
     * failure that gets written down as「浇不出黑曜石」three inferences away from its cause. Every
     * bucket after it is a bonus: it is attempted only when there is an empty bucket AND a source
     * already in view from where the body stands, and the first attempt that does not take ends the
     * loading. Coming home with two when three were possible costs one extra trip; failing the rung
     * over it would cost the run.
     */
    static void loadBuckets(SceneContext ctx, JourneyRig rig, BlockPos src, String tag,
                            Runnable then) {
        fillFrom(ctx, rig, src, tag, Items.LAVA_BUCKET, () -> topUpBuckets(ctx, rig, tag, 1, then));
    }

    /**
     * How many empty buckets a lava load must leave behind. One, for the water.
     *
     * <p>The cast spends a water source per cell and takes it back afterwards, and taking it back
     * needs an empty bucket in the bag at that moment. Filling every bucket with lava would usually
     * still work — the lava bucket empties itself into the cell one step before the recover — but
     * only when the pour lands. When it does not, the recover finds no bucket to hold, comes back
     * dry, and the NEXT cell fails with「开浇前手上没有水桶」: a pour that missed, reported one cell
     * late under another cell's name. Keeping one empty bucket out of the lava makes that
     * impossible, and costs at most one extra trip.
     */
    private static final int BUCKETS_KEPT_EMPTY_FOR_WATER = 1;

    /**
     * Top the load up while the body stands where the first fill already worked.
     *
     * <p>{@link #visibleSourceNear} is the gate rather than "is there lava nearby": it runs the same
     * {@code SOURCE_ONLY} clip {@code BucketItem.use} runs, so a cell it returns is a cell this
     * bucket fills from. That keeps the bonus fills honest — no walking, no re-aiming, no budget.
     */
    private static void topUpBuckets(SceneContext ctx, JourneyRig rig, String tag, int carried,
                                     Runnable then) {
        int empty = rig.carrying("minecraft:bucket");
        if (empty <= BUCKETS_KEPT_EMPTY_FOR_WATER) {
            noteLoad(rig, tag, carried, "空桶只剩 " + empty + " 个，留着收水");
            then.run();
            return;
        }
        // Ask, aim and use from ONE position, the way {@link #scoop} now does. The old shape here
        // clipped for a source, aimed, waited two ticks and only then used — three questions from up
        // to three different eyes, with the wait justified by a comment that blamed `pick()`. The
        // bucket does not go through `pick()`: `Item.getPlayerPOVHitResult` reads the rotation and
        // the eye live, so nothing here needs a tick between the aim and the use, and the aim must
        // not be separated from it — `aimAtBlock` stores an angle computed from wherever the eye
        // was. See `scoop` for the half-block measurement that says so.
        rig.settle(new HoldStill(2), 10, () -> {
            BlockPos more = visibleSourceNear(rig, true, FILL_RESEARCH);
            if (more == null) {
                noteLoad(rig, tag, carried, "站 " + rig.player().blockPosition().toShortString()
                        + " 再也看不见第 " + (carried + 1) + " 格源块（还有 " + empty + " 个空桶）");
                then.run();
                return;
            }
            rig.avatar().aimAtBlock(more);
            JourneyHands.holdForUse(rig, Items.BUCKET, tag + ".more" + carried);
            int before = rig.carrying("minecraft:lava_bucket");
            var result = rig.avatar().useItemInHand();
            int after = rig.carrying("minecraft:lava_bucket");
            if (after <= before) {
                noteLoad(rig, tag, carried, "第 " + (carried + 1) + " 桶没装上：瞄 "
                        + more.toShortString() + "，" + result + "，lava_bucket " + before + "→"
                        + after + " —— 带着已经装到的下去，不判红");
                then.run();
                return;
            }
            topUpBuckets(ctx, rig, tag, carried + 1, then);
        });
    }

    /** What one trip to the pool actually brought home. The number this change is judged on: the
     *  trips a run makes is {@code goUpToThePool}'s call count, and that only falls if this rises. */
    private static void noteLoad(JourneyRig rig, String tag, int carried, String why) {
        rig.evidence(tag + ".loaded", carried + " 桶岩浆（" + why + "）—— 这一趟够浇 "
                + carried + " 格，浇完才会再上来");
    }

    /**
     * Every source of the right fluid within reach of the body, listed.
     *
     * <p>The reading that separates "the bucket missed" from "there is nothing left to fill from",
     * and this rung has spent runs unable to tell those apart. It matters most on the recover: the
     * ten casts run on ONE water source, so a recover that comes back empty either aimed badly or
     * has just discovered that the cast spends the water — and only the second means the rung as
     * designed cannot finish.
     */
    private static String sourcesNear(ServerLevel level, BlockPos centre, int radius, boolean lava) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    var f = level.getFluidState(c);
                    if (!f.isSource()) continue;
                    if (f.is(net.minecraft.tags.FluidTags.LAVA) != lava) continue;
                    if (++n > 6) continue;
                    out.append(out.isEmpty() ? "" : " ").append(c.toShortString());
                }
        return n == 0 ? "一格也没有" : n + " 格（" + out + (n > 6 ? " …" : "") + "）";
    }

    /** The nearest source of the right fluid that is NOT the one just tried. */
    private static BlockPos nextSourceBesides(SceneContext ctx, JourneyRig rig, boolean lava,
                                              BlockPos tried) {
        BlockPos here = rig.player().blockPosition();
        if (!lava) {
            BlockPos w = JourneyTerrain.shallowWaterNear(rig, FILL_RESEARCH);
            return w == null || w.equals(tried) ? null : w;
        }
        for (BlockPos c : JourneyTerrain.lavaSourcesNear(ctx.level(), here, FILL_RESEARCH, here))
            if (!c.equals(tried)) return c;
        return null;
    }

    /** How far to look for another source when a fill did not take. Small: the body is standing at
     *  the pool it walked to, and a source further than this is a different walk, not a retry. */
    static final int FILL_RESEARCH = 8;

    /** Where to stand to fill a bucket, and which source to aim at from there. */
    private record FillSpot(BlockPos stand, BlockPos source) {}

    /** How many sources a fill spot may be searched around. The pool has seventy-five and they are
     *  sorted by how far the body has to walk, so the near dozen is the whole useful set. */
    private static final int FILL_SOURCES_TRIED = 16;

    /**
     * A cell beside the pool the body can STAND in, and a source it can provably reach from there.
     *
     * <p>The exact counterpart of {@link #standToPour}, and it is missing for the same reason that
     * one was: the rung asked the walker to get NEAR a coordinate and then hoped the geometry worked
     * out. It does not, at a lake's edge — a bucket clips from the eyes with {@code Fluid.SOURCE_ONLY}
     * and a finger of bank one cell wide is enough to stop it, so "there is lava two blocks away" and
     * "this bucket will fill" are different claims. Measured twice at 2.1 m and 1.9 m from live lava:
     * {@code 射线停在 -10,63,21 Block{minecraft:stone}}, bucket still empty.
     *
     * <p>So both halves are decided before the walk: a cell that is standable (feet and head clear of
     * blocks AND of fluid — this one stands next to lava) and from which the clip vanilla is about to
     * run lands on the source. Sources are tried nearest-first by how far the BODY must walk, so the
     * answer is also the cheapest trip.
     */
    /**
     * Is the straight line from the body to this stand over the pool?
     *
     * <p>A stand is chosen by how far the BODY has to go, and straight-line distance is the only
     * cheap measure of that — but a straight line across a lava lake is a route the walker will
     * genuinely try, and this rung's lake sits between the stairwell's mouth and the far bank.
     * Measured in four consecutive runs, and always the same shape: {@code lava2.spot=站 -13,64,21}
     * chosen from the mouth at {@code -9,66,21}, and the very next reading is the body at
     * {@code -10,60,20} — three blocks under the surface, {@code onGround=false}, with the scripted
     * climb correctly refusing to mine a ceiling that has lava behind it. There is nothing to
     * recover from down there, so the answer has to be not to go.
     *
     * <p>Sampled at half-block steps, and only across the MIDDLE of the line: both ends are supposed
     * to be beside lava — that is what a lava fill is — so a check that read the endpoints would
     * refuse every stand there is.
     */
    private static boolean acrossThePool(ServerLevel level, BlockPos from, BlockPos to) {
        double span = Math.sqrt(from.distSqr(to));
        int steps = (int) Math.max(1, Math.round(span * 2));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            double x = from.getX() + 0.5 + (to.getX() - from.getX()) * t;
            double y = from.getY() + (to.getY() - from.getY()) * t;
            double z = from.getZ() + 0.5 + (to.getZ() - from.getZ()) * t;
            if (span * t < 1.5 || span * (1 - t) < 1.5) continue;       // the two banks, not the pool
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos c = BlockPos.containing(x, y + dy, z);
                if (level.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA)) return true;
            }
        }
        return false;
    }

    private static FillSpot standToFill(ServerLevel level, JourneyRig rig, BlockPos pool, boolean lava,
                                        int radius, Map<String, Integer> why) {
        // PREFERENCE, not a rule. Asked as a rule it removed the only stands there were — run 37
        // measured `没找到能看见源块的落脚点` on every cast with `过去要横穿岩浆=4..6`, and the fill
        // then fell back to `Near(src,2)`, which is the arithmetic guess this whole method replaced.
        // A worse route beats no route; a nearer-bank route beats both.
        FillSpot nearSide = standToFill(level, rig, pool, lava, radius, new java.util.LinkedHashMap<>(), true);
        if (nearSide != null) return nearSide;
        return standToFill(level, rig, pool, lava, radius, why, false);
    }

    private static FillSpot standToFill(ServerLevel level, JourneyRig rig, BlockPos pool, boolean lava,
                                        int radius, Map<String, Integer> why, boolean avoidCrossing) {
        BlockPos from = rig.player().blockPosition();
        List<BlockPos> sources = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -4; dy <= 4; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = pool.offset(dx, dy, dz);
                    var fluid = level.getFluidState(c);
                    if (!fluid.isSource()) continue;
                    if (fluid.is(net.minecraft.tags.FluidTags.LAVA) != lava) continue;
                    if (lava && !level.getBlockState(c).is(Blocks.LAVA)) continue;
                    sources.add(c.immutable());
                }
        sources.sort(java.util.Comparator.comparingDouble(a -> a.distSqr(from)));
        FillSpot best = null;
        double bestD = Double.MAX_VALUE;
        int tried = 0;
        for (BlockPos src : sources) {
            if (++tried > FILL_SOURCES_TRIED) break;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;             // not IN the pool
                    // BELOW the source as well as level with it. Two rows down, because the cell a
                    // bucket has to take back is usually one ABOVE the floor the body stands on: the
                    // rung's own water sits in the frame's interior at y+1, and a search that only
                    // looked at the source's own level and higher answered "没找到能看见源块的落脚点"
                    // for a source two blocks away in a chamber the body was standing in.
                    for (int dy = -2; dy <= 1; dy++) {
                        BlockPos foot = src.offset(dx, dy, dz);
                        double d = foot.distSqr(from);
                        if (d >= bestD) continue;
                        if (!level.getBlockState(foot.below()).blocksMotion()) {
                            why.merge("脚下不实心", 1, Integer::sum); continue;
                        }
                        // Water underfoot is a wet floor, not a disqualification — and refusing it
                        // is what left the recover with nowhere to stand, because the bucket the
                        // rung is trying to take BACK is the thing that flooded the alcove. Lava is
                        // still a refusal: standing in it costs the body, not the bucket.
                        if (level.getFluidState(foot).is(net.minecraft.tags.FluidTags.LAVA)) {
                            why.merge("落脚格是岩浆", 1, Integer::sum); continue;
                        }
                        if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) {
                            why.merge("落脚格被占", 1, Integer::sum); continue;
                        }
                        BlockPos head = foot.above();
                        if (level.getFluidState(head).is(net.minecraft.tags.FluidTags.LAVA)) {
                            why.merge("头顶是岩浆", 1, Integer::sum); continue;
                        }
                        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
                            why.merge("头顶被占", 1, Integer::sum); continue;
                        }
                        if (avoidCrossing && lava && acrossThePool(level, from, foot)) {
                            why.merge("过去要横穿岩浆", 1, Integer::sum); continue;
                        }
                        var eye = new net.minecraft.world.phys.Vec3(foot.getX() + 0.5,
                                foot.getY() + rig.player().getEyeHeight(), foot.getZ() + 0.5);
                        var aim = net.minecraft.world.phys.Vec3.atCenterOf(src);
                        if (eye.distanceTo(aim) > BUCKET_REACH) {
                            why.merge("够不着源块", 1, Integer::sum); continue;
                        }
                        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                                net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, rig.player()));
                        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                            why.merge("射线没打到方块", 1, Integer::sum); continue;
                        }
                        if (!hit.getBlockPos().equals(src)) {
                            why.merge("射线停在 " + level.getBlockState(hit.getBlockPos()).getBlock(),
                                    1, Integer::sum);
                            continue;
                        }
                        bestD = d;
                        best = new FillSpot(foot, src);
                    }
                }
        }
        return best;
    }

    /** A survival player's block reach, which is what {@code Item.getPlayerPOVHitResult} traces with.
     *  {@link WorldDriverJourneyScenes#TUNNEL_REACH} is half a block longer and is the digging figure; using it here made a
     *  miss report a blocker that was never inside the bucket's own ray. */
    static final double BUCKET_REACH = 4.5;

    /**
     * The nearest source of the right fluid whose line from the body's eyes is CLEAR.
     *
     * <p>The question a bucket actually asks, and the one nothing was asking. {@code useItemInHand}
     * clips from the eyes with {@code Fluid.SOURCE_ONLY} and fills from whatever it lands on, so
     * "there is a source two blocks away" and "this bucket will fill" are different claims — the
     * second needs the cells between to be empty, and at a lake's edge they routinely are not.
     *
     * <p>Clipped per candidate rather than aimed-and-tried, so choosing costs no ticks and no bucket.
     * The same clip vanilla will do is done here first, which makes this a prediction rather than a
     * heuristic: a cell this returns is a cell the bucket fills from.
     */
    static BlockPos visibleSourceNear(JourneyRig rig, boolean lava, int radius) {
        ServerLevel level = rig.ctx().level();
        var fp = rig.player();
        net.minecraft.world.phys.Vec3 eye = fp.getEyePosition();
        BlockPos centre = fp.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    var fluid = level.getFluidState(c);
                    if (!fluid.isSource()) continue;
                    if (fluid.is(net.minecraft.tags.FluidTags.LAVA) != lava) continue;
                    if (lava && !level.getBlockState(c).is(Blocks.LAVA)) continue;
                    var target = net.minecraft.world.phys.Vec3.atCenterOf(c);
                    double d = eye.distanceToSqr(target);
                    if (d >= bestD || d > BUCKET_REACH * BUCKET_REACH) continue;
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, target,
                            net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, fp));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue;
                    if (!hit.getBlockPos().equals(c)) continue;
                    bestD = d;
                    best = c;
                }
        return best;
    }
}
