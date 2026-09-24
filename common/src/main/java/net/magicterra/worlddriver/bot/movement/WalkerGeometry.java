package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.world.SurvivalMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;

/** Pure static geometry / math helpers carved out of {@link Walker}. Every method is a
 *  side-effect-free function of its arguments (and the world view) — no Walker instance
 *  state — so they lift out verbatim. {@link Walker} static-imports them
 *  ({@code import static WalkerGeometry.*}) so every call site is unchanged. Visibility
 *  widened private->public only; bodies and doc comments preserved exactly. No behaviour change.
 *
 *  <p>The CLASS is public (its members already were) because {@code ServerPlayerBody} — in
 *  {@code bot.sim}, one package over — gates its ground jump on {@link #soleOnSolid}. That is the
 *  point of it living here: "is this body standing on something" must have exactly ONE answer in
 *  this repo, and an actuator asking a different one from {@link Walker#footingGuard} is how an
 *  executor and a guard come to disagree about the same tick. */
public final class WalkerGeometry {
    private WalkerGeometry() {}

    /** The 4 horizontal unit offsets, scanned when locating a vine's backing wall. */
    public static final Direction[] HORIZ = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * Yaw to face the SOLID wall a vine column hangs on, so a vine-climb press drives the
     * body INTO that wall and sustains horizontalCollision (the condition vanilla
     * {@code LivingEntity} requires to keep applying the +0.2 vine ascent — see
     * {@code vineClingFidelityProbe}). Returns {@code null} when no horizontal neighbour of
     * the climb column is solid (a free-standing/ladder-like climbable with no backing face),
     * so the caller falls back to the path-ahead bearing.
     *
     * A vine attaches to exactly one (or, in a corner, two) horizontal faces; that face's
     * neighbour cell is solid. We scan the foot cell's 4 horizontal neighbours and, to keep a
     * stable heading when a corner exposes two walls, prefer the candidate most aligned with
     * the path-ahead direction {@code (prefDx,prefDz)} (the bot's travel intent) before falling
     * back to the first solid neighbour found. Uses only {@link WorldView#isSolid}, so it stays
     * decoupled from Minecraft block-state types and works in the headless GameTest view.
     */
    public static Float vineWallYaw(WorldView world, BlockPos foot, double prefDx, double prefDz) {
        Direction best = null;
        double bestDot = -2.0;
        for (Direction d : HORIZ) {
            if (!world.isSolid(foot.relative(d))) continue;
            // Bias toward the wall the path wants us to face (corner disambiguation); a single
            // wall always wins regardless of prefDot since it is the only solid candidate.
            double dot = d.getStepX() * prefDx + d.getStepZ() * prefDz;
            if (best == null || dot > bestDot) { best = d; bestDot = dot; }
        }
        if (best == null) return null;
        // MC yaw: 0=+z(S), 90=-x(W), 180=-z(N), -90=+x(E); atan2(-dx,dz) faces offset (dx,dz).
        return (float) Math.toDegrees(Math.atan2(-best.getStepX(), best.getStepZ()));
    }

    /** A cell a walking bot can stand in on DRY ground — the land analogue of
     *  {@link #isOpenSurfaceWater}: standable (solid floor, clear body, no hazard) and
     *  not water (a water cell is the bee-line's domain, and a buoyant exit differs). */
    public static boolean isDryWalkable(WorldView w, BlockPos foot) {
        return w.canStandAt(foot) && !w.isWater(foot);
    }

    /** A cell a buoyant body can swim across at the surface: water at the foot, a CLEAR
     *  non-water head (the surface — not a submerged mid-column cell), neither a hazard. */
    public static boolean isOpenSurfaceWater(WorldView w, BlockPos foot) {
        BlockPos head = foot.offset(0, 1, 0);
        return w.isWater(foot) && !w.isWater(head) && w.isPassable(head)
                && !w.isHazard(foot) && !w.isHazard(head);
    }

    /** Find the breakable surface obstruction (lily pad / instabreak plant) the floating body
     *  actually OVERLAPS but the head-on pad scan in the drive missed. The player AABB is ~0.6
     *  wide (half-width 0.3), so at the surface the body can straddle up to four XZ columns; a pad
     *  in any of them blocks horizontal motion even though it isn't the single cell directly toward
     *  the waypoint. Scans the columns spanned by the body footprint ({@code x±0.3, z±0.3}) at the
     *  HEAD cell ({@code floor(p.y)+1} — where a pad resting on the water surface sits) and returns
     *  the one nearest the body centre. {@code isBreakableObstruction} is instabreak-by-hand only
     *  (destroySpeed 0, has a collision box, not a fluid) so solid terrain — a genuine wall — is never
     *  returned; null when no breakable cell is overlapped (a pad-free ram against real geometry, so
     *  the caller leaves it to the normal wedge recovery). */
    public static BlockPos nearestBodyPad(WorldView w, LivingEntity p) {
        int headY = BlockPos.containing(p.getX(), p.getY(), p.getZ()).getY() + 1;
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (double ox = -0.3; ox <= 0.3; ox += 0.6) {
            for (double oz = -0.3; oz <= 0.3; oz += 0.6) {
                BlockPos col = BlockPos.containing(p.getX() + ox, p.getY(), p.getZ() + oz);
                BlockPos c = new BlockPos(col.getX(), headY, col.getZ());
                if (!w.isBreakableObstruction(c)) continue;
                double dx = (c.getX() + 0.5) - p.getX(), dz = (c.getZ() + 0.5) - p.getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 < bestD2) { bestD2 = d2; best = c; }
            }
        }
        return best;
    }

    public static float angleDiff(float a, float b) { return ((b - a) % 360f + 540f) % 360f - 180f; }

    /**
     * How much of the body's own sole is resting on solid ground, in blocks² out of 0.36.
     *
     * <p><b>The reading every edge guard here was missing.</b> They all ask about a CELL — the foot
     * cell, its neighbours, the cell 0.6 blocks toward the waypoint — and a player is 0.6 wide, so
     * its support is whatever its bounding box happens to overlap. A body can be grounded with a
     * twentieth of one sole on the corner of a block, and every cell-shaped question about it comes
     * back clean: {@code foot.below()} is solid because the FEET position is still inside that
     * block, the cell ahead is solid because it is real ground, and the neighbours are whatever they
     * are. Measured on the nether crossing, one tick before an eleven-block drop into lava:
     * a solid contact area of {@code 0.0000/0.36} while {@code onGround} said true.
     *
     * <p>The row is {@code floor(minY − 1e-7)} — the row the sole SITS ON, which is the block below
     * for a body flush on a full cube and the block itself for one on a shorter shape. The cells are
     * enumerated OUTWARD (vanilla's own 1e-7), so a sliver of overlap counts as the sliver it is
     * rather than being rounded away: a body walking off a ledge really is held by 0.0004 of a block
     * for one tick, and a reading that discarded that would report "no support" about a body vanilla
     * still calls grounded.
     */
    public static double soleOnSolid(WorldView w, LivingEntity p) {
        double[] area = { 0 };
        eachSoleCell(p, (cell, cellArea) -> { if (w.isSolid(cell)) area[0] += cellArea; });
        return area[0];
    }

    /** How far a pillar-up has to lift the body before the cell it jumped from can be filled: one
     *  whole block, because that cell is where the block goes and vanilla refuses a placement that
     *  intersects the placer's own box. {@code TowerProcess} gates PLACING on exactly this rise. */
    public static final double PILLAR_RISE = 1.0;

    /**
     * Every cell that would stop this body from rising {@link #PILLAR_RISE} — empty when nothing does.
     *
     * <p><b>A body is 0.6 wide, so "the cell above the head" is not one cell.</b> Asking
     * {@code blockPosition().above(2)} answers about the column {@code floor(x), floor(z)} names, and
     * a body standing anywhere within 0.3 of a cell boundary also has to lift a corner of itself
     * through the NEIGHBOUR's cell. Measured 2026-08-19 by {@code wd.serverTowersUnderTheNeighboursCeiling},
     * whose two runs differ by 0.45 of a block in x and by nothing else: at {@code x=cx+0.05} the box
     * is {@code [cx-0.25, cx+0.35]}, one stone cell over {@code cx-1} clips the jump at {@code +0.20}
     * and the tower gains 0 of 4 courses; at {@code x=cx+0.5} the same stone is beside the box and it
     * gains 4 of 4. {@code blockPosition()} reads {@code cx} in both.
     *
     * <p>That is why this returns CELLS rather than a boolean: the process that finds the obstruction
     * cannot mine it ({@code TowerProcess} places, it never breaks), and the caller that can —
     * {@code JourneyShaft.ascendByTowering} — can only mine a cell somebody names.
     *
     * <p>The decision is vanilla's own ({@link Level#noCollision(net.minecraft.world.entity.Entity, AABB)}
     * over the box moved up by the rise); the scan below only puts names to it. They can disagree in
     * one direction — a hard-collision ENTITY blocks and has no cell — and the caller is
     * expected to say so rather than print an empty list as "nothing in the way".
     *
     * @return the blocking cells, lowest first, or an empty list when the rise is clear
     */
    public static List<BlockPos> pillarRiseBlockers(LivingEntity p) {
        return riseBlockers(p, PILLAR_RISE);
    }

    /**
     * {@link #pillarRiseBlockers} for a rise that is not a pillar's.
     *
     * <p>The scan is the question, the rise is the caller's. A tower asks about {@link #PILLAR_RISE}
     * because that is when the cell it jumped from frees up; drown-escape asks about half a block
     * because that is roughly the face a body rising at terminal buoyancy is about to meet, and a
     * lid a whole block higher is not in its way YET. Same geometry either way — the reason it is
     * shared is that both were written from the same measurement and one of them was written twice.
     *
     * @param rise how far up to sweep the body's own box, in blocks
     */
    public static List<BlockPos> riseBlockers(LivingEntity p, double rise) {
        Level lvl = p.level();
        AABB box = p.getBoundingBox().move(0.0, rise, 0.0);
        if (lvl.noCollision(p, box)) return List.of();
        VoxelShape want = Shapes.create(box);
        List<BlockPos> out = new ArrayList<>();
        for (int x = Mth.floor(box.minX + 1.0E-7); x <= Mth.floor(box.maxX - 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ + 1.0E-7); z <= Mth.floor(box.maxZ - 1.0E-7); z++) {
                for (int y = Mth.floor(box.minY + 1.0E-7); y <= Mth.floor(box.maxY - 1.0E-7); y++) {
                    BlockPos at = new BlockPos(x, y, z);
                    VoxelShape s = lvl.getBlockState(at).getCollisionShape(lvl, at);
                    if (s.isEmpty()) continue;
                    if (Shapes.joinIsNotEmpty(want, s.move(x, y, z), BooleanOp.AND)) out.add(at);
                }
            }
        }
        out.sort(Comparator.comparingInt(BlockPos::getY));
        return out;
    }

    /** The row {@link #soleOnSolid} sums over: {@code floor(minY − 1e-7)}, the row the sole SITS ON
     *  — the block below for a body flush on a full cube, the block itself for one on a shorter
     *  shape. Named so a caller can print or reason about the row without re-deriving the epsilon. */
    public static int soleRowY(LivingEntity p) {
        return Mth.floor(p.getBoundingBox().minY - 1.0E-7);
    }

    /** What {@link #eachSoleCell} hands back: one cell of the sole row and how much of the body's
     *  0.6x0.6 footprint lies over it. */
    @FunctionalInterface
    public interface SoleCellVisitor {
        void cell(BlockPos at, double area);
    }

    /**
     * Every cell of {@link #soleRowY}'s row that the body's own bounding box overlaps, with that
     * cell's share of the footprint.
     *
     * <p><b>The single enumeration.</b> {@link #soleOnSolid}, {@link #soleRow} and
     * {@link BlastFooting#refuseSwing} all run through here, so "which cell is the bot standing on" has exactly one
     * answer in this repo — the rule this class's header states, applied to the one piece of it
     * that used to be copied by eye. Cells are walked OUTWARD with vanilla's own {@code 1e-7}, so a
     * sliver of overlap is reported as the sliver it is (a body walking off a ledge really is held
     * by 0.0004 of a block for one tick) rather than being rounded away, and the x-outer/z-inner
     * order is what {@link #soleRow}'s printed evidence has always used.
     */
    public static void eachSoleCell(LivingEntity p, SoleCellVisitor v) {
        AABB box = p.getBoundingBox();
        int y = Mth.floor(box.minY - 1.0E-7);
        for (int x = Mth.floor(box.minX - 1.0E-7); x <= Mth.floor(box.maxX + 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ - 1.0E-7); z <= Mth.floor(box.maxZ + 1.0E-7); z++) {
                v.cell(new BlockPos(x, y, z),
                        Math.max(0, Math.min(box.maxX, x + 1.0) - Math.max(box.minX, x))
                      * Math.max(0, Math.min(box.maxZ, z + 1.0) - Math.max(box.minZ, z)));
            }
        }
    }

    /**
     * The cells {@link #soleOnSolid} sums over, written out — the row index, each column, and what
     * that column contributes.
     *
     * <p>Exists because a bare area is not falsifiable. {@code soleOnSolid=0.0000} is consistent with
     * three different worlds — the bot is not in the cell the block coordinate suggests, the terrain
     * is not what the arena's comments say, or the row being read is not the row a reader assumed —
     * and they want different fixes. The area alone cannot separate them; the row and the per-column
     * contributions can, and they are the very numbers the sum is built from, so no second opinion
     * about "standing" is introduced by asking. Same enumeration, same 1e-7 outward epsilon: change
     * one and this print changes with it.
     */
    public static String soleRow(WorldView w, LivingEntity p) {
        StringBuilder sb = new StringBuilder("rowY=").append(soleRowY(p));
        // JourneyCrossingScenes tests the row for "solid" to tell a bot perched on a corner from
        // one in mid-air.
        eachSoleCell(p, (cell, area) -> sb.append(String.format(java.util.Locale.ROOT,
                " [%d,%d]%s %.4f", cell.getX(), cell.getZ(), w.isSolid(cell) ? "solid" : "open", area)));
        return sb.toString();
    }

    /** Horizontal neighbour offsets (4 cardinals + 4 diagonals) of the foot cell. */
    public static final int[][] EDGE_NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** True if a LETHAL drop borders the cell the bot is standing on — any horizontal
     *  neighbour that is an open foot-cell with no floor, falling to the next solid/
     *  water surface deeper than {@link SurvivalMath#survivableFall} at the bot's HP.
     *  Checking ALL neighbours (not just the heading) catches lateral/momentum drift
     *  off a lip while walking ALONG it — the actual DEATH #8 mode. Vanilla sneak then
     *  pins the body to this block in every direction. Lethal-only, so it never blocks
     *  a legitimate planned step-down (those land within survivable, or in water). */
    public static boolean lethalDropAdjacent(WorldView world, LivingEntity p, BlockPos foot) {
        return dropAdjacentExceeds(world, foot, SurvivalMath.survivableFall(p.getHealth()));
    }

    /** Like {@link #lethalDropAdjacent} but against an arbitrary depth {@code threshold}
     *  (a hazard anywhere in a fall column always counts). Used for BOTH the lethal
     *  sneak-pin (threshold = survivableFall, ~23 blk at full HP — higher with resist
     *  buffs) AND the steep-descent SPRINT brake (a small fixed threshold): a deep but
     *  SURVIVABLE drop never trips the lethal pin, so sprint momentum carries the body off
     *  the lip OFF-PATH and it falls 13-19 blk into a fellOffPath deadlock (live 2026-06-24
     *  random journey at -679,83: sprinted a diagDown, drifted -680→-677 off a 12-drop, 80 t
     *  stuck). The sprint brake just drops sprint there — it does NOT pin the body (sneak),
     *  so a legitimate planned step-down still proceeds. */
    public static boolean dropAdjacentExceeds(WorldView world, BlockPos foot, int threshold) {
        for (int[] o : EDGE_NEIGHBOURS) {
            BlockPos n = foot.offset(o[0], 0, o[1]);
            // A drop needs the foot-cell AND the cell below it both open (no floor).
            // A present floor = flat walk or a safe 1-block step-down; water = a splash.
            if (world.isSolid(n) || world.isWater(n)) continue;
            BlockPos below = n.below();
            if (world.isHazard(below)) return true;
            if (world.isSolid(below) || world.isWater(below)) continue;
            int fall = 1;
            BlockPos pr = below.below();
            while (fall <= threshold + 2 && !world.isSolid(pr) && !world.isWater(pr)) {
                // Lava is neither solid nor water, so the height scan used to fall
                // THROUGH it to the lake floor — a 2-deep lava pocket measured as a
                // "survivable" 2-block drop, edgeBrake stayed off, sprint stayed on,
                // and downhill momentum slid the bot in (round52: enteredLava ×3).
                // Any hazard in the fall column is lethal regardless of height.
                if (world.isHazard(pr)) return true;
                fall++;
                pr = pr.below();
            }
            if (fall > threshold) return true;
        }
        return false;
    }

    /**
     * The Chebyshev radius the recovery-hop gate USED to scan around the foot, kept because the
     * rows that judged it print it and a reader comparing two runs needs the old number in view.
     *
     * <p><b>Retired as a gate on 2026-08-26</b> in favour of {@link #hopSuppressed}. The reasoning
     * it was built on is still correct and is why the replacement exists: those hops launch a full
     * sprint-jump arc along whatever the current (often mid-slew) heading is — the bridge-battery
     * sheds launched from one cell INSIDE a safe pad rim, every neighbour floored, and sailed clean
     * over the deck edge (t0 2026-07-20, breach@t=81/609) — so a foot-ADJACENT scan is blind to
     * them. What was wrong was the SHAPE of the answer: a guard whose reach is 2 against a throw
     * measured at 3.47 has a hole exactly one ring wide, and on a 5-wide platform the centre cell
     * is the single cell that sits in it. Widening it was measured and rejected (it relocates the
     * hole into a three-minute stall); re-centring the probe on where the arc lands closed both.
     *
     * <p>{@code lethalDropWithinHopRange}, the boolean over this radius, is gone with it — the
     * ring and the CELL that replaced it live in {@link #nearestLethalHopRing} and
     * {@link #nearestLethalHopCell}, which the rows still print.
     */
    public static final int HOP_RANGE = 2;

    /**
     * The Chebyshev RING of the nearest lethal drop column around {@code foot}, scanning outward
     * ring by ring to {@code scanTo}, or {@code -1} when none is inside it.
     *
     * <p>Extracted from the retired {@code lethalDropWithinHopRange} rather than written beside it: the two
     * must agree by construction, because the gate's decision and the diagnostic that judges the
     * gate cannot be allowed to disagree about what "a lethal drop" is. The boolean is now this
     * function thresholded at {@link #HOP_RANGE}, so the only difference between them is how far
     * they look. Visiting in ring order changes nothing for the boolean (an OR over the same cell
     * set) and is what lets the distance be reported at all.
     */
    public static int nearestLethalHopRing(WorldView world, LivingEntity p, BlockPos foot, int scanTo) {
        return ringOf(foot, nearestLethalHopCell(world, p, foot, scanTo));
    }

    /** Chebyshev ring of {@code cell} around {@code foot}, or {@code -1} for a null cell. Shared so
     *  a caller that wants BOTH the cell and its ring pays for one scan, not two. */
    public static int ringOf(BlockPos foot, BlockPos cell) {
        return cell == null ? -1
                : Math.max(Math.abs(cell.getX() - foot.getX()), Math.abs(cell.getZ() - foot.getZ()));
    }

    /**
     * The CELL {@link #nearestLethalHopRing} found, or null. Same scan, one implementation, so the
     * ring and the cell can never disagree — the same argument that extracted the ring out of the
     * boolean.
     *
     * <p>Wanted because the ring alone cannot answer the question the ring itself raised. Measured
     * 2026-08-26, one directed rehearsal, both sides inside twenty seconds: at ring 3 the gate lets
     * the hop go (a ladder body took that one into a lava lake), and at ring 2 it holds — sixteen
     * consecutive suppressions and a three-minute deterministic stall beside the lava station,
     * repeating the same coordinates every ~30 s. So WIDENING the radius does not fix the hole, it
     * relocates it into the stall. What separates the two is DIRECTION: an unaimed hop launched
     * away from the lethal cell is the escape the stall needs, and one launched at it is the death.
     * The cell is what makes that bearing computable.
     */
    public static BlockPos nearestLethalHopCell(WorldView world, LivingEntity p, BlockPos foot, int scanTo) {
        int threshold = SurvivalMath.survivableFall(p.getHealth());
        for (int r = 1; r <= scanTo; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos cand = foot.offset(dx, 0, dz);
                    if (isLethalDropColumn(world, cand, threshold)) return cand;
                }
        return null;
    }

    /**
     * "where the body is pointed" beside "where the lethal cell is", as one row.
     *
     * <p>MEASUREMENT ONLY — nothing branches on it yet, deliberately. Gating the hop on this
     * bearing would introduce a cone half-angle, and this repo's rule is that a free parameter
     * needs a reading that isolates what it acts on first (the same reason the gate's own boolean
     * was turned into a printed ring rather than simply widened). What this row has to establish
     * before any cone exists: that the firing ring-3 hops point AT the hazard and the suppressed
     * ring-2 ones point AWAY. If they do not split that way, direction is the wrong lever and no
     * threshold on it would have helped.
     *
     * <p>Yaw and not the velocity vector: at the moment this fires the body is stalled by
     * definition — the measured speeds are 0.003–0.040 against a walk of ~0.13 — so the delta is
     * noise and the facing is the drive.
     *
     * <p>⚠️ <b>That last clause is wrong, and this row is the camera, not the drive.</b>
     * {@code p.getYRot()} is the CAMERA channel; the body is pushed along {@code
     * WalkerTickDrive}'s {@code driveTargetYaw}, and the two are decoupled ON PURPOSE — see
     * {@link WalkerConstants} ("camera = aimYaw, movement = driveTargetYaw, decoupled by
     * AvatarInput's impulse"), which exists precisely so a slewing camera does not drag the body.
     * A distribution taken over this row therefore measures the wrong quantity: 2026-08-26's
     * rehearsal split 137 suppressions 126-away/11-toward on THIS angle, which cannot license a
     * gate on the OTHER one. {@link #hopLandingRow} prints the drive angle beside it; until a run
     * has shown how far the two diverge at a stall, neither is a lever.
     */
    public static String hopBearingRow(LivingEntity p, BlockPos foot, BlockPos lethal) {
        if (lethal == null) return "no lethal cell, no bearing computed";
        double bx = (lethal.getX() + 0.5) - (foot.getX() + 0.5);
        double bz = (lethal.getZ() + 0.5) - (foot.getZ() + 0.5);
        // Minecraft yaw: 0 = +Z, 90 = -X. atan2(-dx, dz) puts a world bearing in the same frame.
        double bearing = Math.toDegrees(Math.atan2(-bx, bz));
        double delta = Math.abs(net.minecraft.util.Mth.wrapDegrees(bearing - p.getYRot()));
        return String.format(java.util.Locale.ROOT,
                "facing %.0f°, lethal cell %s at %.0f°, angle between %.0f° (%s)",
                net.minecraft.util.Mth.wrapDegrees(p.getYRot()), lethal.toShortString(), bearing,
                delta, delta <= 90 ? "facing it" : "facing away");
    }

    /**
     * The radii, in blocks along the drive bearing, that {@link #hopSuppressed} probes for a lethal
     * landing column — and that {@link #hopLandingRow} prints. <b>ONE array</b>, consumed through
     * one {@link #hopLandingCells}, because a gate and the row that judges the gate must not be
     * able to disagree about which cells they mean (the same argument that made the ring and the
     * cell share a scan).
     *
     * <p><b>Why the far end is 4 and not one point at 3.</b> The arc length is a MEASUREMENT with
     * spread, not a constant. Measured 2026-08-26 off the ladder's rung 12: a hop logged at
     * {@code (-4.854,66,22.577)} put the body at {@code (-8.257,66,23.234)} twelve ticks later —
     * <b>3.47 blocks</b>, against the "~3" that the retired gate's javadoc had carried in prose. A body that launches slowed lands short, one that launches sprinting lands
     * past, so a single point probe would be a coin flip on the one decision that costs a life.
     *
     * <p><b>Why the near end is 1.</b> A hop that barely leaves the ground lands one block out —
     * back inside the ring the old gate covered. Dropping r=1 would trade the old gate's whole
     * purpose for the new one's reach.
     *
     * <p><b>Why half-block steps.</b> Whole-block steps along a diagonal bearing SKIP cells: the
     * ray clips a corner and the two samples either side of it both land in neighbours. Harmless
     * against rung 12's 72-source lava lake, blind against a one-cell shaft — and the cost of
     * closing it is four more array entries, which is sampling density, not a tuned parameter.
     */
    private static final double[] HOP_ARC_SAMPLES = {1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0};

    /** The columns {@link #HOP_ARC_SAMPLES} names along {@code driveYaw}, in order and with
     *  duplicates kept (half-block steps repeat a cell whenever the ray crosses it slowly). Both
     *  the gate and the row read this — see {@link #HOP_ARC_SAMPLES} on why that matters. */
    private static List<BlockPos> hopLandingCells(LivingEntity p, BlockPos foot, float driveYaw) {
        double r = Math.toRadians(driveYaw);
        double dx = -Math.sin(r), dz = Math.cos(r);           // Minecraft yaw: 0 = +Z, 90 = -X
        List<BlockPos> out = new ArrayList<>(HOP_ARC_SAMPLES.length);
        for (double d : HOP_ARC_SAMPLES)
            out.add(BlockPos.containing(p.getX() + dx * d, foot.getY(), p.getZ() + dz * d));
        return out;
    }

    /**
     * Should an unaimed recovery hop be held? — <b>the gate</b>, true when the arc would come down
     * in a lethal column along the bearing the body is actually driven on.
     *
     * <p><b>This REPLACES the ring test rather than joining it.</b> Both conjunctions were
     * considered and both are wrong: {@code ring && landing} lets a lethal cell at ring 3 through,
     * which is precisely the hop a rung-12 ladder body rode into a lava lake; {@code ring ||
     * landing} only ever suppresses more, which deepens the stall this change exists to lift. The
     * landing probe SUBSUMES the ring test — a lethal column at Chebyshev ≤2 that lies in the
     * direction of travel is caught at r=1.0–2.0 — and the cells behind the body, which the ring
     * test also caught and which no hop can reach, are exactly what it stops suppressing.
     *
     * <p><b>Backtested A/B, same command both arms (2026-08-26, {@code -Prehearse=PORTAL_LIT
     * -PforgeAway=east -PshaftColumn=-8,20}), both PASS.</b> Control (the ring) 208 hop rows,
     * treatment (this) 188:
     *
     * <pre>
     *                     ring gate      landing probe
     *   fired / held        77 / 131       183 / 5
     *   longest held run          37             1
     *   held at -7,64,16          87             5
     * </pre>
     *
     * 146 of the released hops are ones the ring would have held (123 at ring 2, 23 at ring 1), and
     * the deterministic stall — the same coordinates every ~30 s for three minutes — is gone. Zero
     * deaths in either arm. ⚠️ The treatment arm also ran LONGER (16214 ticks against 14478 /
     * 14609 / 15019 for three control runs); that is one sample against three and is recorded, not
     * explained — this rung is known to produce different colours from the same command.
     *
     * <p><b>What is NOT measured, and must not be written up as if it were.</b> That same run
     * produced <b>no</b> firing hop pointed AT a lethal cell, so the death side has no direct
     * sample. It rests on two independent RECONSTRUCTIONS: the ladder's fatal hop back-solves to a
     * bearing of 140.7°, whose landing columns are the lake; and the one "toward" ring-3 firing an
     * earlier rehearsal did produce ({@code t=13294}) came with camera and drive within a few
     * degrees. No run has yet shown this rule suppressing a hop that would otherwise have killed
     * the body — which is why the row stays: the next real occasion files its own evidence.
     *
     * <p><b>Known limitation.</b> This reads the bearing at LAUNCH, and the walker keeps steering
     * through the arc — the ladder hop above travelled along a heading its launch angle did not
     * name. So it predicts where a trajectory that is still being driven would land, not where the
     * body ends up. A hop released here can still be steered into the cell it was cleared of.
     *
     * <p>{@code driveYaw} must be {@code WalkerTickDrive}'s {@code driveTargetYaw} (or, on the
     * burst path, {@code unstuck.burstYaw}) — the channel the impulse is rotated onto. Passing
     * {@code p.getYRot()} would reproduce {@link #hopBearingRow}'s mistake.
     */
    public static boolean hopSuppressed(WorldView world, LivingEntity p, BlockPos foot, float driveYaw) {
        int threshold = SurvivalMath.survivableFall(p.getHealth());
        for (BlockPos cand : hopLandingCells(p, foot, driveYaw))
            if (isLethalDropColumn(world, cand, threshold)) return true;
        return false;
    }

    /**
     * The cells {@link #hopSuppressed} looked at and what it saw — the row that lets a reader
     * check the gate instead of trusting it.
     *
     * <p>Prints the drive angle beside the camera angle and their difference, because the two are
     * decoupled by design and an earlier version of this instrument measured the wrong one (see
     * {@link #hopBearingRow}). Duplicate cells are collapsed so half-block sampling does not turn
     * one column into three entries.
     */
    public static String hopLandingRow(WorldView world, LivingEntity p, BlockPos foot, float driveYaw) {
        int threshold = SurvivalMath.survivableFall(p.getHealth());
        StringBuilder cells = new StringBuilder();
        BlockPos prev = null;
        boolean anyLethal = false;
        for (BlockPos cand : hopLandingCells(p, foot, driveYaw)) {
            if (cand.equals(prev)) continue;
            prev = cand;
            boolean lethal = isLethalDropColumn(world, cand, threshold);
            anyLethal |= lethal;
            if (cells.length() > 0) cells.append(", ");
            cells.append(cand.toShortString()).append(lethal ? " lethal" : " safe");
        }
        return String.format(java.util.Locale.ROOT,
                "drive %.0f° (camera %.0f°, difference %.0f°); landing columns %s ⇒ landing rule: %s",
                Mth.wrapDegrees(driveYaw), Mth.wrapDegrees(p.getYRot()),
                Math.abs(Mth.wrapDegrees(driveYaw - p.getYRot())), cells,
                anyLethal ? "suppress" : "allow");
    }

    /** One column's worth of {@link #nearestLethalHopCell}'s scan: open foot cell, no floor, and the
     *  fall below it either hazardous anywhere or deeper than {@code threshold}. Lifted verbatim
     *  from that loop's body. ({@link #dropAdjacentExceeds} carries its own copy of this test and is
     *  deliberately left alone — it is on the footing-guard and sprint-brake paths, and folding it in
     *  would put an unrelated blast radius into a diagnostic change.) */
    private static boolean isLethalDropColumn(WorldView world, BlockPos n, int threshold) {
        if (world.isSolid(n) || world.isWater(n)) return false;
        BlockPos below = n.below();
        if (world.isHazard(below)) return true;
        if (world.isSolid(below) || world.isWater(below)) return false;
        int fall = 1;
        BlockPos pr = below.below();
        while (fall <= threshold + 2 && !world.isSolid(pr) && !world.isWater(pr)) {
            if (world.isHazard(pr)) return true;
            fall++;
            pr = pr.below();
        }
        return fall > threshold;
    }

    /** Y-MISLABELED-RISER RAM detector (executor-side, see {@code levelRiserJump} below).
     *  A* can label an edge a LEVEL {@code walk} (Move dy=0) whose DESTINATION floor is actually
     *  +1 — a mislabeled ridge step. The bot, expecting level ground, sprints in, walks off the
     *  lower approach block and drops onto the cell ONE below the node ({@code upDy==1}), then rams
     *  the +1 riser it never squared up for (hCol=true, hSpd≈0). The destination column is standable
     *  (so {@code canStandAt} passed, the move was legal) — only the dy LABEL was wrong.
     *  <p>Detect the riser the body is pressed against: take the unit step toward the node and
     *  sample the cell one block ahead at the GROUNDED FOOT level (= node.y-1 when {@code upDy==1}).
     *  It is a mountable +1 riser iff that forward cell is solid ({@code canStandOn} — a real floor
     *  to stand on, not a fence/cocoa nub), the cell above it (foot+1) is passable (the jump arc
     *  clears), and foot+2 is passable (head room once standing on the riser, feet at node.y). Pure
     *  geometry; the caller additionally gates on the walk-mislabel signature + a CONFIRMED grounded
     *  ram (horizontalCollision + stepRamStuck), so a clean level walk over flat ground — where the
     *  forward foot cell is air — is INERT (never fires, no bunny-hop). */
    public static boolean forwardRiserMountable(WorldView world, BlockPos foot, double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return false;
        int fx = (int) Math.round(dx / len);
        int fz = (int) Math.round(dz / len);
        if (fx == 0 && fz == 0) return false;
        BlockPos riser = foot.offset(fx, 0, fz);                 // the +1 block dead ahead at foot level
        return world.canStandOn(riser)                          // a solid top to stand on after the hop
                && world.isPassable(riser.offset(0, 1, 0))      // jump arc clears the cell above the riser
                && world.isPassable(riser.offset(0, 2, 0));     // head room once standing on the riser
    }

    /** True if a DEEP floating-water cell ({@link WorldView#isFloatingWater}: water with
     *  water directly below — ≥2 deep, no floor in jump range) lies within {@code radius}
     *  blocks horizontally of the foot AND within a short ({@code ≤maxDrop}) open fall column
     *  from foot level, with a clear (un-walled) drop to it. The water analogue of
     *  {@link #dropAdjacentExceeds}: that helper counts only DRY drops (it {@code continue}s
     *  past any water as a harmless splash) and only the 8 immediate neighbours, so a descent
     *  that borders a deep pocket SET BACK behind a stepped/sloped bank face is invisible to
     *  both the lethal sneak-pin and the steep-descent sprint brake. A buoyant body that drifts
     *  into such a cell floats and cannot climb out, so the deep-water drift brake drops sprint
     *  to keep the body on the dry staircase line.
     *  <p>Why a horizontal RADIUS (not just the 8 neighbours like the dry brakes): a dry cliff
     *  edge IS an immediate neighbour, but a deep pocket sits against a TALL bank whose face is
     *  stepped, so while the bot is still GROUNDED on the upper steps the open water is 2 cells
     *  away (live -870: grounded foot x-871, the floating-water column begins at x-869/-868) and
     *  the bot only goes airborne — losing the {@code onGround} precondition — once it has
     *  already drifted off. A 2-cell reach catches the water before the launch, matching the
     *  ~1.5-2 blocks of lateral overshoot a sprint adds on a descent.
     *  <p>For each cell in the ring: skip a SOLID cell (a wall the body can't drift through);
     *  if it is itself floating water at foot level, hit. Otherwise scan its fall column — deep
     *  water within {@code maxDrop} = a drift-in hazard; a solid or SHALLOW-water floor first
     *  (1-deep splash, not floating water) terminates the column as a safe landing, so it never
     *  fires walking a 1-deep ford or onto a flush bank. */
    public static boolean deepWaterDropAdjacent(WorldView world, BlockPos foot, int radius, int maxDrop) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos n = foot.offset(dx, 0, dz);
                if (world.isSolid(n)) continue;                 // a wall/step face — no drift through it
                if (world.isFloatingWater(n)) return true;      // deep water at foot level within reach
                if (world.isWater(n)) continue;                 // shallow water at foot level (floor below) — a safe splash
                // Open cell: scan the fall column. Deep water under it (within maxDrop) = a
                // drift-in hazard; a solid/shallow floor first = a DRY drop (left to the lethal /
                // steep-descent brakes), not ours.
                BlockPos c = n.below();
                for (int d = 1; d <= maxDrop; d++) {
                    if (world.isFloatingWater(c)) return true;
                    if (world.isSolid(c) || world.isWater(c)) break;   // hit a floor (or shallow splash) before any deep water
                    c = c.below();
                }
            }
        }
        return false;
    }

    /** True if the straight horizontal line {@code a}→{@code b} is a CLEAR OPEN-WATER corridor — every
     *  sampled cell (and the one above it, head room) is water or passable air, with NO solid block in the
     *  way. The open-water analogue of {@link PathSmoothing#losWalkable}: losWalkable accepts a DRY
     *  walkway (solid floor below + clear foot/head), whereas this insists the corridor itself be
     *  water/air — so it admits ONLY a buoyant swim across open water, never a bank, ledge or dry path.
     *  Used by the deep-water-float bee-line anchor-gate exemption to verify a far continuation node is
     *  reachable by a straight swim before accepting an otherwise mis-anchored segment. Same per-cell
     *  sampling as losWalkable (cells the swim would cross, excluding the start). */
    public static boolean isOpenWaterLine(WorldView w, BlockPos a, BlockPos b) {
        int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        if (steps == 0) return true;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
            int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * t);
            int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
            BlockPos c = new BlockPos(x, y, z);
            // foot cell + head cell must each be water OR (passable air, not a wall); a solid block in the
            // corridor (a bank / pillar) fails it. Hazards are never an open-water swim.
            if (w.isHazard(c) || w.isHazard(c.above())) return false;
            if (!(w.isWater(c) || w.isPassable(c))) return false;
            if (!(w.isWater(c.above()) || w.isPassable(c.above()))) return false;
            // Reject a DRY corridor: at least the foot cell should be water somewhere along the line
            // (an all-air line above ground is a fall, not a swim). Require water at THIS cell or below it
            // within 1 (the floating foot rides ~1 above the surface crossing nodes).
            if (!w.isWater(c) && !w.isWater(c.below())) return false;
        }
        return true;
    }

    /** Total blocks a path would PLACE — the sum of each edge's toPlace size. Used
     *  by the block-budget reroute (checking before bridging that there are enough blocks) to compare against inventory. */
    public static int countPlaceEdges(List<Move.Edge> edges) {
        int n = 0;
        for (Move.Edge e : edges) if (e != null && e.toPlace != null) n += e.toPlace.size();
        return n;
    }

    /** True when the foot's horizontal distance² to {@code node} is in the step-advance DEAD-ZONE
     *  (REACH_DIST_SQ, OVERSHOOT_RESYNC_SQ) — too far for {@code within}, too near for the horizontal
     *  overshoot re-sync. Sole caller is the {@code walkerVerticalResync} gate (so the cur2 maths only
     *  runs when that flag is on). */
    public static boolean deadZoneCur2(BlockPos node, LivingEntity p) {
        double dx = (node.getX() + 0.5) - p.getX();
        double dz = (node.getZ() + 0.5) - p.getZ();
        double cur2 = dx * dx + dz * dz;
        return cur2 > REACH_DIST_SQ && cur2 < OVERSHOOT_RESYNC_SQ;
    }

}
