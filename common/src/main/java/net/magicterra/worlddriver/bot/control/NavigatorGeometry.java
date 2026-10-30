package net.magicterra.worlddriver.bot.control;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.*;
import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.lineCell;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.Drops;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.BodyGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * Pure static geometry / math helpers carved out of {@link Navigator}. Every method is a side-effect-free function of
 * its arguments (and the world view) — no Navigator instance state — so they lift out verbatim. {@link Navigator}
 * static-imports them ({@code import static NavigatorGeometry.*}) so every call site is unchanged. Visibility widened
 * private->public only; bodies and doc comments preserved exactly. No behaviour change.
 *
 * <p>The geometry of the body's own box against the blocks — its sole, what stops it rising — is
 * {@link BodyGeometry}'s, below the navigator, because a body's actuators ask it too.
 */
public final class NavigatorGeometry {
    private NavigatorGeometry() {}

    /**
     * Is this pillarUp destination a FLOODED shaft — one the bot floats straight up through — rather than a water
     * SURFACE it must place a support to leave?
     *
     * <p><b>The single answer both callers ask.</b> It used to be computed twice. The planned pillar ({@link Pillar})
     * carved the surface case out of "flooded" (the {@code navigatorPillarSurfacePlace} branch, which is the flag's
     * only reader in the whole product); {@link ProgressPhase} computed plain {@code isWater(dest)} and never carved.
     * On the one geometry where the two disagree — destination water, air directly above — the pillar decided "place a
     * support" while Progress still called it a buoyant float, took the branch whose comment says an unfilled place
     * cell is not genuinely pending, and let bare height promote the step. Progress runs before the pillar, so the
     * pointer left the edge before the support existed.
     *
     * <p><b>Why the carve-out belongs to the shared answer and the pillarUp test does not.</b> The carve-out is about
     * GEOMETRY — what this cell is — so both callers need it. Whether the edge under the pointer is a pillarUp at all
     * is about WHICH EDGE, which only Progress has to ask because the pillar is already inside that branch.
     *
     * <p><b>Direction of the change, per call site.</b> The pillar: byte-identical — this is its old expression, moved.
     * Progress: STRICTER and never looser — the result can only go true→false, and each of the two places it feeds
     * tightens when it does (the pending-edge check reverts to {@code hasPendingEdge}, and the arrival gate reverts to
     * requiring {@code onGround()}). A pointer that used to advance mid-float now waits for the support, which is the
     * fix.
     */
    public static boolean floodedShaft(WorldView world, BlockPos dest) {
        if (!world.isWater(dest)) {
            return false;
        }
        // Surface, not shaft: water underfoot with AIR directly above is the water-bank climb-out.
        // Floating higher is physically impossible there, so the bot must crest and place.
        return !(ControlConfig.navigatorPillarSurfacePlace && !world.isWater(dest.above()));
    }

    /**
     * Have the feet risen clear of {@code cell}, so that vanilla can accept a block placed into it?
     *
     * <p><b>1.0, and it is not a tuning knob.</b> A player's AABB starts at {@code p.getY()} and the cell occupies
     * {@code [y, y+1]}; {@code Level#isUnobstructed} refuses any placement whose cell still intersects that box. So
     * {@code p.getY() >= cell.y + 1.0} is the exact boundary between "will be placed" and "will be silently refused" —
     * there is nothing to tune, and a looser number does not buy earlier placements, it buys refusals.
     *
     * <p><b>This is the FULL-CUBE boundary, and it is only that.</b> {@code isUnobstructed} tests the collision shape
     * of the state being PLACED, not the cell it goes in, so the number above is the boundary for a block whose
     * collision box is a whole cube. The climb-out places a different kind of block into a different kind of cell and
     * needs {@link WaterClimb#crestClearOf}; unifying the two is what regressed {@code wd.waterLowBank}, because for
     * that call site this predicate is not merely stricter — it is unsatisfiable (see there).
     */
    public static boolean feetClearOf(LivingEntity p, BlockPos cell) {
        return p.getY() >= cell.getY() + 1.0;
    }

    /** The 4 horizontal unit offsets, scanned when locating a vine's backing wall. */
    public static final Direction[] HORIZ = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * Yaw to face the SOLID wall a vine column hangs on, so a vine-climb press drives the bot INTO that wall and
     * sustains horizontalCollision (the condition vanilla {@code LivingEntity} requires to keep applying the +0.2 vine
     * ascent — see {@code vineClingFidelityProbe}). Returns {@code null} when no horizontal neighbour of the climb
     * column is solid (a free-standing/ladder-like climbable with no backing face), so the caller falls back to the
     * path-ahead bearing.
     *
     * <p>A vine attaches to exactly one (or, in a corner, two) horizontal faces; that face's neighbour cell is solid.
     * We scan the foot cell's 4 horizontal neighbours and, to keep a stable heading when a corner exposes two walls,
     * prefer the candidate most aligned with the path-ahead direction {@code (prefDx,prefDz)} (the bot's travel intent)
     * before falling back to the first solid neighbour found. Uses only {@link WorldView#isSolid}, so it stays
     * decoupled from Minecraft block-state types and works in the headless GameTest view.
     */
    public static Float vineWallYaw(WorldView world, BlockPos foot, double prefDx, double prefDz) {
        Direction best = null;
        double bestDot = -2.0;
        for (Direction d : HORIZ) {
            if (!world.isSolid(foot.relative(d))) {
                continue;
            }
            // Bias toward the wall the path wants us to face (corner disambiguation); a single
            // wall always wins regardless of prefDot since it is the only solid candidate.
            double dot = d.getStepX() * prefDx + d.getStepZ() * prefDz;
            if (best == null || dot > bestDot) {
                best = d;
                bestDot = dot;
            }
        }
        if (best == null) {
            return null;
        }
        // MC yaw: 0=+z(S), 90=-x(W), 180=-z(N), -90=+x(E); atan2(-dx,dz) faces offset (dx,dz).
        return (float) Math.toDegrees(Math.atan2(-best.getStepX(), best.getStepZ()));
    }

    /**
     * A cell a walking bot can stand in on DRY ground — the land analogue of {@link #isOpenSurfaceWater}: standable
     * (solid floor, player-height clearance, no hazard) and not water (a water cell is the bee-line's domain, and a
     * buoyant exit differs).
     */
    public static boolean isDryWalkable(WorldView w, BlockPos foot) {
        return w.canStandAt(foot) && !w.isWater(foot);
    }

    /**
     * A cell a buoyant bot can swim across at the surface: water at the foot, a CLEAR non-water head (the surface — not
     * a submerged mid-column cell), neither a hazard.
     */
    public static boolean isOpenSurfaceWater(WorldView w, BlockPos foot) {
        BlockPos head = foot.offset(0, 1, 0);
        return w.isWater(foot) && !w.isWater(head) && w.isPassable(head) && !w.isHazard(foot) && !w.isHazard(head);
    }

    public static float angleDiff(float a, float b) {
        return Mth.degreesDifference(a, b);
    }

    /**
     * An edge whose landing is below its start. A bot found BELOW such a node overshot it on the way down; climbing
     * back up to it is never the recovery (live 2026-09-28: a fall4 node left 3 above after an 11-block cliff drop
     * armed a pillar back up to it). Null-safe.
     */
    public static boolean isDescentEdge(Move.Edge edge) {
        String m = edge == null ? null : edge.move;
        return m != null
                && (m.startsWith("fall")
                        || m.startsWith("stepDown")
                        || m.startsWith("diagDown")
                        || m.startsWith("parkourDescend"));
    }

    /** {@link BodyGeometry#soleOnSolid} with this view's notion of solid. */
    public static double soleOnSolid(WorldView w, LivingEntity p) {
        return BodyGeometry.soleOnSolid(w::isSolid, p);
    }

    /** {@link BodyGeometry#soleRow} with this view's notion of solid. */
    public static String soleRow(WorldView w, LivingEntity p) {
        return BodyGeometry.soleRow(w::isSolid, p);
    }

    /** Horizontal neighbour offsets (4 cardinals + 4 diagonals) of the foot cell. */
    public static final int[][] EDGE_NEIGHBOURS = Drops.EDGE_NEIGHBOURS;

    /**
     * True if a LETHAL drop borders the cell the bot is standing on — any horizontal neighbour that is an open
     * foot-cell with no floor, falling to the next solid/ water surface deeper than {@link SurvivalMath#survivableFall}
     * at the bot's HP. Checking ALL neighbours (not just the heading) catches lateral/momentum drift off a lip while
     * walking ALONG it — the actual DEATH #8 mode. Vanilla sneak then pins the bot to this block in every direction.
     * Lethal-only, so it never blocks a legitimate planned step-down (those land within survivable, or in water).
     */
    public static boolean lethalDropAdjacent(WorldView world, LivingEntity p, BlockPos foot) {
        return dropAdjacentExceeds(world, foot, SurvivalMath.survivableFall(p.getHealth()));
    }

    /**
     * Like {@link #lethalDropAdjacent} but against an arbitrary depth {@code threshold} (a hazard anywhere in a fall
     * column always counts). Used for BOTH the lethal sneak-pin (threshold = survivableFall, ~23 blk at full HP —
     * higher with resist buffs) AND the steep-descent SPRINT brake (a small fixed threshold): a deep but SURVIVABLE
     * drop never trips the lethal pin, so sprint momentum carries the bot off the lip OFF-PATH and it falls 13-19 blk
     * into a fellOffPath deadlock (live 2026-06-24 random journey at -679,83: sprinted a diagDown, drifted -680→-677
     * off a 12-drop, 80 t stuck). The sprint brake just drops sprint there — it does NOT pin the bot (sneak), so a
     * legitimate planned step-down still proceeds.
     */
    public static boolean dropAdjacentExceeds(WorldView world, BlockPos foot, int threshold) {
        return Drops.adjacentExceeds(world, foot, threshold);
    }

    /**
     * The Chebyshev radius the recovery-hop gate USED to scan around the foot, kept because the rows that judged it
     * print it and a reader comparing two runs needs the old number in view.
     *
     * <p><b>Retired as a gate on 2026-08-26</b> in favour of {@link #hopSuppressed}. The reasoning it was built on is
     * still correct and is why the replacement exists: those hops launch a full sprint-jump arc along whatever the
     * current (often mid-slew) heading is — the bridge-battery sheds launched from one cell INSIDE a safe pad rim,
     * every neighbour floored, and sailed clean over the deck edge (t0 2026-07-20, breach@t=81/609) — so a
     * foot-ADJACENT scan is blind to them. What was wrong was the SHAPE of the answer: a guard whose reach is 2 against
     * a throw measured at 3.47 has a hole exactly one ring wide, and on a 5-wide platform the centre cell is the single
     * cell that sits in it. Widening it was measured and rejected (it relocates the hole into a three-minute stall);
     * re-centring the probe on where the arc lands closed both.
     *
     * <p>{@code lethalDropWithinHopRange}, the boolean over this radius, is gone with it — the ring and the CELL that
     * replaced it live in {@link #nearestLethalHopRing} and {@link #nearestLethalHopCell}, which the rows still print.
     */
    public static final int HOP_RANGE = 2;

    /**
     * The Chebyshev RING of the nearest lethal drop column around {@code foot}, scanning outward ring by ring to
     * {@code scanTo}, or {@code -1} when none is inside it.
     *
     * <p>Extracted from the retired {@code lethalDropWithinHopRange} rather than written beside it: the two must agree
     * by construction, because the gate's decision and the diagnostic that judges the gate cannot be allowed to
     * disagree about what "a lethal drop" is. The boolean is now this function thresholded at {@link #HOP_RANGE}, so
     * the only difference between them is how far they look. Visiting in ring order changes nothing for the boolean (an
     * OR over the same cell set) and is what lets the distance be reported at all.
     */
    public static int nearestLethalHopRing(WorldView world, LivingEntity p, BlockPos foot, int scanTo) {
        return ringOf(foot, nearestLethalHopCell(world, p, foot, scanTo));
    }

    /**
     * Chebyshev ring of {@code cell} around {@code foot}, or {@code -1} for a null cell. Shared so a caller that wants
     * BOTH the cell and its ring pays for one scan, not two.
     */
    public static int ringOf(BlockPos foot, BlockPos cell) {
        return cell == null ? -1 : Math.max(Math.abs(cell.getX() - foot.getX()), Math.abs(cell.getZ() - foot.getZ()));
    }

    /**
     * The CELL {@link #nearestLethalHopRing} found, or null. Same scan, one implementation, so the ring and the cell
     * can never disagree — the same argument that extracted the ring out of the boolean.
     *
     * <p>Wanted because the ring alone cannot answer the question the ring itself raised. Measured 2026-08-26, one
     * directed rehearsal, both sides inside twenty seconds: at ring 3 the gate lets the hop go (a ladder bot took that
     * one into a lava lake), and at ring 2 it holds — sixteen consecutive suppressions and a three-minute deterministic
     * stall beside the lava station, repeating the same coordinates every ~30 s. So WIDENING the radius does not fix
     * the hole, it relocates it into the stall. What separates the two is DIRECTION: an unaimed hop launched away from
     * the lethal cell is the escape the stall needs, and one launched at it is the death. The cell is what makes that
     * bearing computable.
     */
    public static BlockPos nearestLethalHopCell(WorldView world, LivingEntity p, BlockPos foot, int scanTo) {
        int threshold = SurvivalMath.survivableFall(p.getHealth());
        for (int r = 1; r <= scanTo; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    BlockPos cand = foot.offset(dx, 0, dz);
                    if (isLethalDropColumn(world, cand, threshold)) {
                        return cand;
                    }
                }
            }
        }
        return null;
    }

    /**
     * "where the bot is pointed" beside "where the lethal cell is", as one row.
     *
     * <p>MEASUREMENT ONLY — nothing branches on it yet, deliberately. Gating the hop on this bearing would introduce a
     * cone half-angle, and this repo's rule is that a free parameter needs a reading that isolates what it acts on
     * first (the same reason the gate's own boolean was turned into a printed ring rather than simply widened). What
     * this row has to establish before any cone exists: that the firing ring-3 hops point AT the hazard and the
     * suppressed ring-2 ones point AWAY. If they do not split that way, direction is the wrong lever and no threshold
     * on it would have helped.
     *
     * <p>Yaw and not the velocity vector: at the moment this fires the bot is stalled by definition — the measured
     * speeds are 0.003–0.040 against a walk of ~0.13 — so the delta is noise and the facing is the drive.
     *
     * <p>⚠️ <b>That last clause is wrong, and this row is the camera, not the drive.</b> {@code p.getYRot()} is the
     * CAMERA channel; the bot is pushed along {@code DrivePhase}'s {@code driveTargetYaw}, and the two are decoupled ON
     * PURPOSE — see {@link NavigatorConstants} ("camera = aimYaw, movement = driveTargetYaw, decoupled by AvatarInput's
     * impulse"), which exists precisely so a slewing camera does not drag the bot. A distribution taken over this row
     * therefore measures the wrong quantity: 2026-08-26's rehearsal split 137 suppressions 126-away/11-toward on THIS
     * angle, which cannot license a gate on the OTHER one. {@link #hopLandingRow} prints the drive angle beside it;
     * until a run has shown how far the two diverge at a stall, neither is a lever.
     */
    public static String hopBearingRow(LivingEntity p, BlockPos foot, BlockPos lethal) {
        if (lethal == null) {
            return "no lethal cell, no bearing computed";
        }
        double bx = (lethal.getX() + 0.5) - (foot.getX() + 0.5);
        double bz = (lethal.getZ() + 0.5) - (foot.getZ() + 0.5);
        // Minecraft yaw: 0 = +Z, 90 = -X. atan2(-dx, dz) puts a world bearing in the same frame.
        double bearing = Math.toDegrees(Math.atan2(-bx, bz));
        double delta = Math.abs(net.minecraft.util.Mth.wrapDegrees(bearing - p.getYRot()));
        return String.format(
                java.util.Locale.ROOT,
                "facing %.0f°, lethal cell %s at %.0f°, angle between %.0f° (%s)",
                net.minecraft.util.Mth.wrapDegrees(p.getYRot()),
                lethal.toShortString(),
                bearing,
                delta,
                delta <= 90 ? "facing it" : "facing away");
    }

    /**
     * The radii, in blocks along the drive bearing, that {@link #hopSuppressed} probes for a lethal landing column —
     * and that {@link #hopLandingRow} prints. <b>ONE array</b>, consumed through one {@link #hopLandingCells}, because
     * a gate and the row that judges the gate must not be able to disagree about which cells they mean (the same
     * argument that made the ring and the cell share a scan).
     *
     * <p><b>Why the far end is 4 and not one point at 3.</b> The arc length is a MEASUREMENT with spread, not a
     * constant. Measured 2026-08-26 off the ladder's rung 12: a hop logged at {@code (-4.854,66,22.577)} put the bot at
     * {@code (-8.257,66,23.234)} twelve ticks later — <b>3.47 blocks</b>, against the "~3" that the retired gate's
     * javadoc had carried in prose. A bot that launches slowed lands short, one that launches sprinting lands past, so
     * a single point probe would be a coin flip on the one decision that costs a life.
     *
     * <p><b>Why the near end is 1.</b> A hop that barely leaves the ground lands one block out — back inside the ring
     * the old gate covered. Dropping r=1 would trade the old gate's whole purpose for the new one's reach.
     *
     * <p><b>Why half-block steps.</b> Whole-block steps along a diagonal bearing SKIP cells: the ray clips a corner and
     * the two samples either side of it both land in neighbours. Harmless against rung 12's 72-source lava lake, blind
     * against a one-cell shaft — and the cost of closing it is four more array entries, which is sampling density, not
     * a tuned parameter.
     */
    private static final double[] HOP_ARC_SAMPLES = {1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0};

    /**
     * The columns {@link #HOP_ARC_SAMPLES} names along {@code driveYaw}, in order and with duplicates kept (half-block
     * steps repeat a cell whenever the ray crosses it slowly). Both the gate and the row read this — see
     * {@link #HOP_ARC_SAMPLES} on why that matters.
     */
    private static List<BlockPos> hopLandingCells(LivingEntity p, BlockPos foot, float driveYaw) {
        double r = Math.toRadians(driveYaw);
        double dx = -Math.sin(r), dz = Math.cos(r); // Minecraft yaw: 0 = +Z, 90 = -X
        List<BlockPos> out = new ArrayList<>(HOP_ARC_SAMPLES.length);
        for (double d : HOP_ARC_SAMPLES) {
            out.add(BlockPos.containing(p.getX() + dx * d, foot.getY(), p.getZ() + dz * d));
        }
        return out;
    }

    /**
     * Should an unaimed recovery hop be held? — <b>the gate</b>, true when the arc would come down in a lethal column
     * along the bearing the bot is actually driven on.
     *
     * <p><b>This REPLACES the ring test rather than joining it.</b> Both conjunctions were considered and both are
     * wrong: {@code ring && landing} lets a lethal cell at ring 3 through, which is precisely the hop the rung-12
     * ladder bot rode into a lava lake; {@code ring || landing} only ever suppresses more, which deepens the stall this
     * change exists to lift. The landing probe SUBSUMES the ring test — a lethal column at Chebyshev ≤2 that lies in
     * the direction of travel is caught at r=1.0–2.0 — and the cells behind the bot, which the ring test also caught
     * and which no hop can reach, are exactly what it stops suppressing.
     *
     * <p><b>Backtested A/B, same command both arms (2026-08-26, {@code -Prehearse=PORTAL_LIT -PforgeAway=east
     * -PshaftColumn=-8,20}), both PASS.</b> Control (the ring) 208 hop rows, treatment (this) 188:
     *
     * <pre>
     *                     ring gate      landing probe
     *   fired / held        77 / 131       183 / 5
     *   longest held run          37             1
     *   held at -7,64,16          87             5
     * </pre>
     *
     * 146 of the released hops are ones the ring would have held (123 at ring 2, 23 at ring 1), and the deterministic
     * stall — the same coordinates every ~30 s for three minutes — is gone. Zero deaths in either arm. ⚠️ The treatment
     * arm also ran LONGER (16214 ticks against 14478 / 14609 / 15019 for three control runs); that is one sample
     * against three and is recorded, not explained — this rung is known to produce different colours from the same
     * command.
     *
     * <p><b>What is NOT measured, and must not be written up as if it were.</b> That same run produced <b>no</b> firing
     * hop pointed AT a lethal cell, so the death side has no direct sample. It rests on two independent
     * RECONSTRUCTIONS: the ladder's fatal hop back-solves to a bearing of 140.7°, whose landing columns are the lake;
     * and the one "toward" ring-3 firing an earlier rehearsal did produce ({@code t=13294}) came with camera and drive
     * within a few degrees. No run has yet shown this rule suppressing a hop that would otherwise have killed the bot —
     * which is why the row stays: the next real occasion files its own evidence.
     *
     * <p><b>Known limitation.</b> This reads the bearing at LAUNCH, and the navigator keeps steering through the arc —
     * the ladder hop above travelled along a heading its launch angle did not name. So it predicts where a trajectory
     * that is still being driven would land, not where the bot ends up. A hop released here can still be steered into
     * the cell it was cleared of.
     *
     * <p>{@code driveYaw} must be {@code DrivePhase}'s {@code driveTargetYaw} (or, on the burst path,
     * {@link Recovery}'s burst heading) — the channel the impulse is rotated onto. Passing {@code p.getYRot()} would
     * reproduce {@link #hopBearingRow}'s mistake.
     */
    public static boolean hopSuppressed(WorldView world, LivingEntity p, BlockPos foot, float driveYaw) {
        int threshold = SurvivalMath.survivableFall(p.getHealth());
        for (BlockPos cand : hopLandingCells(p, foot, driveYaw)) {
            if (isLethalDropColumn(world, cand, threshold)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cells {@link #hopSuppressed} looked at and what it saw — the row that lets a reader check the gate instead of
     * trusting it.
     *
     * <p>Prints the drive angle beside the camera angle and their difference, because the two are decoupled by design
     * and an earlier version of this instrument measured the wrong one (see {@link #hopBearingRow}). Duplicate cells
     * are collapsed so half-block sampling does not turn one column into three entries.
     */
    public static String hopLandingRow(WorldView world, LivingEntity p, BlockPos foot, float driveYaw) {
        int threshold = SurvivalMath.survivableFall(p.getHealth());
        StringBuilder cells = new StringBuilder();
        BlockPos prev = null;
        boolean anyLethal = false;
        for (BlockPos cand : hopLandingCells(p, foot, driveYaw)) {
            if (cand.equals(prev)) {
                continue;
            }
            prev = cand;
            boolean lethal = isLethalDropColumn(world, cand, threshold);
            anyLethal |= lethal;
            if (cells.length() > 0) {
                cells.append(", ");
            }
            cells.append(cand.toShortString()).append(lethal ? " lethal" : " safe");
        }
        return String.format(
                java.util.Locale.ROOT,
                "drive %.0f° (camera %.0f°, difference %.0f°); landing columns %s ⇒ landing rule: %s",
                Mth.wrapDegrees(driveYaw),
                Mth.wrapDegrees(p.getYRot()),
                Math.abs(Mth.wrapDegrees(driveYaw - p.getYRot())),
                cells,
                anyLethal ? "suppress" : "allow");
    }

    /**
     * One column's worth of {@link #nearestLethalHopCell}'s scan: open foot cell, no floor, and the fall below it
     * either hazardous anywhere or deeper than {@code threshold}. Lifted verbatim from that loop's body.
     * ({@link #dropAdjacentExceeds} carries its own copy of this test and is deliberately left alone — it is on the
     * footing-guard and sprint-brake paths, and folding it in would put an unrelated blast radius into a diagnostic
     * change.)
     */
    private static boolean isLethalDropColumn(WorldView world, BlockPos n, int threshold) {
        if (world.isSolid(n) || world.isWater(n)) {
            return false;
        }
        BlockPos below = n.below();
        if (world.isHazard(below)) {
            return true;
        }
        if (world.isSolid(below) || world.isWater(below)) {
            return false;
        }
        int fall = 1;
        BlockPos pr = below.below();
        while (fall <= threshold + 2 && !world.isSolid(pr) && !world.isWater(pr)) {
            if (world.isHazard(pr)) {
                return true;
            }
            fall++;
            pr = pr.below();
        }
        return fall > threshold;
    }

    /**
     * True if a DEEP floating-water cell ({@link WorldView#isFloatingWater}) lies under any part of the body's width
     * (±0.3) within {@code reach} blocks along its horizontal velocity, at foot level or down an open fall column of
     * {@code ≤maxDrop}. The water analogue of {@link #dropAdjacentExceeds}, which counts only DRY drops: a buoyant bot
     * that drifts into such a cell floats and cannot climb out, so the drift brake drops sprint to keep it on the dry
     * line.
     *
     * <p>Why a reach, not the adjacent cell: a deep pocket sits against a TALL bank whose face is stepped, so the
     * grounded bot on the upper steps is 2 cells from the water (live -870: foot x-871, water from x-869) and loses
     * {@code onGround} only once it has already drifted off.
     *
     * <p>Why along the velocity, not a ring round the foot: the ring took the sprint for the length of every bank and
     * pool the walk merely passed (live 2026-09-28 R1, 1551,65,-139..-143 beside a one-wide stream and 1565,64,-113
     * beside a pool: 0.20-0.22 a tick, 747 vetoed ticks over three runs). Below 0.05 a tick nothing is drifting
     * anywhere.
     */
    public static boolean deepWaterAlongVelocity(
            WorldView world, double px, double pz, int footY, double vx, double vz, double reach, int maxDrop) {
        double v = Math.hypot(vx, vz);
        if (v < 0.05) {
            return false;
        }
        double ux = vx / v, uz = vz / v;
        BlockPos last = null;
        for (double s = 0.25; s <= reach; s += 0.25) {
            for (double w = -0.3; w <= 0.3; w += 0.3) {
                BlockPos c = BlockPos.containing(px + ux * s - uz * w, footY, pz + uz * s + ux * w);
                if (c.equals(last)) {
                    continue;
                }
                last = c;
                if (deepWaterColumn(world, c, maxDrop)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Deep water at {@code n} or under it within {@code maxDrop}, before any floor. */
    private static boolean deepWaterColumn(WorldView world, BlockPos n, int maxDrop) {
        if (world.isSolid(n)) {
            return false; // a wall/step face — no drift through it
        }
        if (world.isFloatingWater(n)) {
            return true; // deep water at foot level within reach
        }
        if (world.isWater(n)) {
            return false; // shallow water at foot level (floor below) — a safe splash
            // Open cell: scan the fall column. Deep water under it (within maxDrop) = a drift-in hazard;
            // a solid/shallow floor first = a DRY drop (left to the lethal / steep-descent brakes), not ours.
        }
        BlockPos c = n.below();
        for (int d = 1; d <= maxDrop; d++) {
            if (world.isFloatingWater(c)) {
                return true;
            }
            if (world.isSolid(c) || world.isWater(c)) {
                return false; // hit a floor (or shallow splash) before any deep water
            }
            c = c.below();
        }
        return false;
    }

    /**
     * True if the straight horizontal line {@code a}→{@code b} is a CLEAR OPEN-WATER corridor — every sampled cell (and
     * the one above it, head room) is water or passable air, with NO solid block in the way. The open-water analogue of
     * {@link PathSmoothing#losWalkable}: losWalkable accepts a DRY walkway (solid floor below + clear foot/head),
     * whereas this insists the corridor itself be water/air — so it admits ONLY a buoyant swim across open water, never
     * a bank, ledge or dry path. Used by the deep-water-float bee-line anchor-gate exemption to verify a far
     * continuation node is reachable by a straight swim before accepting an otherwise mis-anchored segment. Same
     * per-cell sampling as losWalkable (cells the swim would cross, excluding the start).
     */
    public static boolean isOpenWaterLine(WorldView w, BlockPos a, BlockPos b) {
        int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        if (steps == 0) {
            return true;
        }
        for (int s = 1; s <= steps; s++) {
            BlockPos c = lineCell(a, b, s, steps);
            // foot cell + head cell must each be water OR (passable air, not a wall); a solid block in the
            // corridor (a bank / pillar) fails it. Hazards are never an open-water swim.
            if (w.isHazard(c) || w.isHazard(c.above())) {
                return false;
            }
            if (!(w.isWater(c) || w.isPassable(c))) {
                return false;
            }
            if (!(w.isWater(c.above()) || w.isPassable(c.above()))) {
                return false;
            }
            // Reject a DRY corridor: at least the foot cell should be water somewhere along the line
            // (an all-air line above ground is a fall, not a swim). Require water at THIS cell or below it
            // within 1 (the floating foot rides ~1 above the surface crossing nodes).
            if (!w.isWater(c) && !w.isWater(c.below())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Total blocks a path would PLACE — the sum of each edge's toPlace size. Used by the block-budget reroute (checking
     * before bridging that there are enough blocks) to compare against inventory.
     */
    public static int countPlaceEdges(List<Move.Edge> edges) {
        int n = 0;
        for (Move.Edge e : edges) {
            if (e != null && e.toPlace != null) {
                n += e.toPlace.size();
            }
        }
        return n;
    }
}
