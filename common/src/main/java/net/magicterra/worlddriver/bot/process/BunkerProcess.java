package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;

import java.util.Locale;

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.util.BotUtil.yawFor;

/**
 * Agent-invoked "dig three, fill one" bunker — a sand-SAFE emergency shelter. The Agent calls
 * {@code mc.bot.bunker{depth}} when it plans to (e.g. at sunset when exposed); it
 * is NOT an auto-reflex.
 *
 * <p>Why not the naive "dig down + place a block overhead"? In a desert that cap
 * would be SAND, and a sand block placed into the shaft has only air beneath it,
 * so it falls straight back down onto the bot's head → suffocation, roof still
 * open (the user's catch). Instead this digs down, carves a 2-tall HORIZONTAL
 * niche off the bottom, steps into it, and plugs the shaft behind with blocks
 * that have SUPPORT BELOW — so even sand stays put. The niche roof is undug
 * terrain (already supported), so nothing has to be placed overhead at all.
 *
 * <p>Result: a sealed 1×1 pocket offset from the shaft, no falling-block hazard.
 * Needs hand-droppable walls (sand/dirt/gravel — bare stone by hand drops nothing
 * to plug with, so it bails). Aborts on water/lava/bedrock. Drives through the
 * {@link Body} seam (break / place / tool / forward on the player's own input),
 * so it runs over a client LocalPlayer or a server FakePlayer alike.
 */
public final class BunkerProcess implements BotProcess {
    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private Hands hands;

    private enum Phase { DIG_DOWN, CARVE, STEP_IN, PLUG, SEALED, DONE }

    private final int depth;
    // Niche depth may grow past the requested `depth` when the niche head would
    // otherwise sit at/near the surface (open-sky roof) or be roofed by a falling
    // block — we dig deeper until the roof is a solid, non-falling block.
    private int effectiveDepth;
    private static final int MAX_EXTRA_DEPTH = 4;
    private Phase phase = Phase.DIG_DOWN;
    private int startY = Integer.MIN_VALUE;
    private int lastDepth = 0, digTicks = 0, actTicks = 0;
    /** Separate place-attempt timer for PLUG. Kept distinct from {@link #actTicks}
     *  (which the body-overlap shuffle branch burns up to breakTimeoutTicks*2) so a
     *  long shuffle can't make the FIRST real placement instantly trip the place
     *  timeout and abort the bunker DONE-but-UNSEALED. */
    private int plugTicks = 0;
    private Direction nicheDir = null;       // horizontal direction of the niche
    private BlockPos bottom = null;          // shaft-bottom foot cell
    /** gap#68-⑩: true once a real break or place has been attempted — distinguishes
     *  a zero-action bail (e.g. water at the dig site, first tick) from a run that
     *  actually touched the world before failing. Not yet surfaced synchronously in
     *  the verb response (the run is async; see BotApiImpl#bunker), but available on
     *  the instance for future status/telemetry wiring. */
    private boolean acted;
    /** True once {@link Phase#SEALED} has ever been reached this run — the ground
     *  truth for "did this bunker ever actually finish sealing" independent of
     *  whatever phase it happens to be sitting in when finish() runs. */
    private boolean sealedOk;
    /** True once the SEALED hold branch has stamped its verdict on the bunker slot.
     *  SEALED never returns true (it holds the channel until an external cancel), so
     *  finish() is unreachable for the success case — instead the hold branch stamps
     *  endReason/goalReached in place, ONCE, without reset() and without changing the
     *  return value. awaitable's slot fold then carries
     *  {active:true, endReason:"SEALED", goalReached:<enclosed>} = the caller can
     *  read "genuinely enclosed and holding position" even though the await itself times out (by design —
     *  the process keeps holding the pocket). */
    private boolean sealedVerdictStamped;

    public BunkerProcess(int depth) {
        this.depth = Math.max(1, Math.min(5, depth));
        this.effectiveDepth = this.depth;
    }

    /** A block that falls if unsupported (sand/red_sand/gravel/…) — unsafe as a
     *  niche roof, since carving the head leaves air beneath it and it drops in. */
    private static boolean isFalling(Level lvl, BlockPos pos) {
        return lvl != null && lvl.getBlockState(pos).getBlock() instanceof FallingBlock;
    }

    /** The niche must be carved INTO a solid mass, not through a thin wall: its far
     *  wall and both perpendicular side walls (at foot AND head height) must all be
     *  solid, or the "sealed pocket" has a lateral opening onto a slope/cave that a
     *  mob simply walks through (survival-run death#2: shelter on a hillside, niche
     *  punched through to the open slope, zombie walked in and beat the bot to death
     *  inside its own bunker). When no direction qualifies the existing deepen-and-
     *  retry path runs — a few blocks further down every niche is fully buried. */
    private static boolean nicheEmbedded(WorldView w, BlockPos n0, BlockPos n1, Direction d) {
        if (!w.isSolid(n0.relative(d)) || !w.isSolid(n1.relative(d))) return false;   // far wall
        Direction cw = d.getClockWise(), ccw = d.getCounterClockWise();
        return w.isSolid(n0.relative(cw)) && w.isSolid(n1.relative(cw))
            && w.isSolid(n0.relative(ccw)) && w.isSolid(n1.relative(ccw));
    }

    private static void dbg(String msg, Object... a) {
        if (BotConfig.walkerDebug) LOG.info("[bunker] " + msg, a);
    }

    @Override public String kind() { return "bunker"; }

    @Override public void attach(BotState st) {
        st.bunker.active = true;
        st.bunker.goal = "bunker depth=" + depth;
        st.bunker.startedAtMs = System.currentTimeMillis();
        st.bunker.lastError = null;
        st.bunker.goalReached = null;
        st.bunker.endReason = null;
        sealedVerdictStamped = false;
    }

    /** Surfaced as {@code activeProcessDetail} in mc.bot.status so the agent can
     *  tell a working bunker (DIG_DOWN/CARVE/STEP_IN/PLUG) from a SEALED one
     *  (success — hold until dawn, then cancel to break out) or a DONE one
     *  (finished; if it bailed the pocket may be UNSEALED — verify the shaft is
     *  plugged before trusting it). SEALED is the only "safe to walk away" state. */
    @Override public String statusDetail() { return phase.name(); }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        if (p == null) return finish(st, w, a, "no-player", "player entity unavailable — no action taken");
        hands = a.hands().orElse(null);
        if (hands == null) return finish(st, w, a, "no-hands", BodyReady.Reason.NO_HANDS);
        BlockPos foot = p.blockPosition();
        if (startY == Integer.MIN_VALUE) {
            startY = foot.getY();
            // Center on the column so the shaft/niche cells are unambiguous.
            p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);
            // Water near the dig footprint floods a 1-wide shaft and traps the bot
            // (the spawn-water trap that stranded it at HP 3.6). But the low flats
            // around spawn (y61-63) have the water table just 1-2 below the surface,
            // so a blanket "abort if water within depth+2" left the bot with NO night
            // shelter anywhere in the flats (the night death-loop). Instead, AUTO-FIT
            // the niche depth to sit ABOVE the water table: take the deepest d ≤ the
            // requested depth where the shaft [foot..foot-d], the stand block
            // (foot-d-1) and the shaft's horizontal neighbours are all water-free.
            // Only when even a 1-deep niche is wet (truly in/at the water) do we abort
            // and ask the Agent to relocate.
            Direction[] horiz = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            int fit = 0;
            for (int d = 1; d <= effectiveDepth; d++) {
                boolean dry = !w.isWater(foot.offset(0, -d, 0))        // dug floor cell
                           && !w.isWater(foot.offset(0, -d - 1, 0));   // block the bot stands on
                for (int dy = 0; dy <= d && dry; dy++) {
                    BlockPos cell = foot.offset(0, -dy, 0);
                    for (Direction dir : horiz) {
                        if (w.isWater(cell.relative(dir))) { dry = false; break; }
                    }
                }
                if (dry) fit = d; else break;   // first wet depth caps the fit (deeper is wetter)
            }
            if (fit == 0) {
                dbg("ABORT bunker: water at/around even the shallowest niche at {}", foot);
                a.releaseInputs();
                return finish(st, w, a, "unsafe-site", "water/hazard at dig site — no action taken");
            }
            if (fit < effectiveDepth) dbg("bunker: water table → shrink depth {}→{}", effectiveDepth, fit);
            effectiveDepth = fit;
        }
        // Hold horizontal position while working (except the deliberate STEP_IN walk).
        if (phase != Phase.STEP_IN) p.setDeltaMovement(0, p.getDeltaMovement().y, 0);

        switch (phase) {
            case DIG_DOWN: return digDown(a, w, st, p, foot);
            case CARVE:    return carve(a, w, st, p);
            case STEP_IN:  return stepIn(a, w, p, foot);
            case PLUG:     return plug(a, w, st, p);
            // Stay SEALED after a successful plug: keep holding the channel (this is
            // a BunkerProcess, so UserTaskChain reports BUNKER priority 300 > combat
            // 60 — see GAP #20) so autoFight/idle can't walk the bot out of its
            // pocket and get it killed at night (GAP #22). Released only when the
            // Agent calls mc.bot.cancel (typically at dawn, then it breaks out).
            // The success verdict is stamped HERE (once, no reset, return unchanged)
            // because this branch never reaches finish() — see sealedVerdictStamped.
            case SEALED:
                if (!sealedVerdictStamped) {
                    st.bunker.endReason = "SEALED";
                    st.bunker.goalReached = enclosed(w, a);
                    sealedVerdictStamped = true;
                }
                a.releaseInputs(); return false;
            default:       a.releaseInputs(); return finish(st, w, a, phase.name(), null);
        }
    }

    /** Stamps the honest terminal verdict on the bunker slot before every real
     *  {@code return true} exit (gap#68-⑩). {@code goalReached} is block-level
     *  enclosure ground truth — NOT {@code hazardSummary.cornered} (that means
     *  "besieged into a corner" and was measured false during a genuinely SEALED
     *  state on day60 live). Always returns {@code true} so call sites can just
     *  {@code return finish(...)} without altering their control flow. */
    private boolean finish(BotState st, WorldView w, Body a, String endReason, String err) {
        st.bunker.endReason = endReason;
        if (err != null) st.bunker.lastError = err;
        boolean sealedNow = phase == Phase.SEALED || (phase == Phase.DONE && sealedOk);
        st.bunker.goalReached = sealedNow && enclosed(w, a);
        st.bunker.reset();
        // A null err also ends a bunker that ran out of plug blocks, which is not a shelter.
        failure = err != null ? err
                : Boolean.TRUE.equals(st.bunker.goalReached) ? null
                : "not enclosed (ended in " + endReason + ")";
        return true;
    }

    private String failure;

    @Override public String failure() { return failure; }

    /** Block-level enclosure ground truth: the 4 horizontal neighbors of the FOOT
     *  cell, the head cell's 4 horizontal neighbors, and the cell above the head are
     *  all solid. hazardSummary.cornered means "driven into a corner by hostiles" and
     *  must not be used as an enclosure assertion. */
    private static boolean enclosed(WorldView w, Body a) {
        if (a.entity() == null) return false;
        return enclosed(w, a.entity().blockPosition());
    }

    /** Position-keyed enclosure check — public single source (gap#72-③): also the
     *  "am I in a sealed pocket" signal for {@code RetreatChain}'s sealed-pocket
     *  exemption, so the reflex and the bunker agree on what "sealed" means. Being
     *  a live block read it self-verifies that the pocket is still intact: a stale SEALED slot over a
     *  since-breached pocket reads {@code false} here. */
    public static boolean enclosed(WorldView w, BlockPos foot) {
        BlockPos head = foot.above();
        return w.isSolid(foot.north()) && w.isSolid(foot.south()) && w.isSolid(foot.east()) && w.isSolid(foot.west())
            && w.isSolid(head.north()) && w.isSolid(head.south()) && w.isSolid(head.east()) && w.isSolid(head.west())
            && w.isSolid(head.above());
    }

    private boolean digDown(Body a, WorldView w, BotState st, LivingEntity p, BlockPos foot) {
        int d = startY - foot.getY();
        if (d != lastDepth) { lastDepth = d; digTicks = 0; }
        if (d >= effectiveDepth) {
            bottom = foot.immutable();
            phase = Phase.CARVE;
            actTicks = 0;
            hands.breakHold(false);
            dbg("DIG_DOWN done bottom={} (dug {} down from y={})", bottom, effectiveDepth, startY);
            return false;
        }
        BlockPos below = foot.below();
        if (w.isWater(foot) || w.isWater(foot.offset(0, 1, 0))
                || w.isWater(below) || w.isHazard(below) || w.isHazard(foot.offset(0, 1, 0))) {
            hands.breakHold(false); a.releaseInputs();
            return finish(st, w, a, "unsafe-mid-dig", "hazard opened mid-dig");   // unsafe
        }
        if (!w.isSolid(below)) return false;                                   // mid-fall, settle
        hands.selectTool(below);
        a.aimAtBlock(below);
        hands.breakHold(true);
        hands.continueDestroy(below);
        acted = true;
        if (++digTicks > BotConfig.breakTimeoutTicks) {
            hands.breakHold(false); a.releaseInputs();
            return finish(st, w, a, "dig-timeout", "break timeout (unbreakable below?)");
        }
        return false;
    }

    private boolean carve(Body a, WorldView w, BotState st, LivingEntity p) {
        if (nicheDir == null) {
            // Pick a cardinal whose 2-tall niche is a solid (diggable) wall with a
            // solid floor (so we can stand), a SOLID NON-FALLING ROOF (n1.above() —
            // else the pocket is open to the sky or roofed by sand that falls in),
            // and won't immediately flood.
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                BlockPos n0 = bottom.relative(d);
                BlockPos n1 = n0.above();
                BlockPos roof = n1.above();
                if (w.isSolid(n0) && w.isSolid(n1) && w.isSolid(n0.below())
                        && w.isSolid(roof) && !isFalling(p.level(), roof)
                        && !w.isWater(n0) && !w.isWater(n1) && !w.isHazard(n0) && !w.isHazard(n1)
                        && nicheEmbedded(w, n0, n1, d)) {
                    nicheDir = d; break;
                }
            }
            if (nicheDir == null) {
                // No direction has a solid non-falling roof at this depth — the
                // niche head sits at/near the surface (open sky) or under falling
                // sand. Dig ONE block deeper and retry so the niche tucks under
                // real terrain. Bail only once we've exhausted the depth budget
                // (e.g. pure sand over un-mineable stone — genuinely no safe roof).
                if (effectiveDepth - depth < MAX_EXTRA_DEPTH) {
                    effectiveDepth++;
                    phase = Phase.DIG_DOWN; bottom = null; actTicks = 0;
                    dbg("CARVE no solid non-falling roof → deepen to effDepth={}", effectiveDepth);
                    return false;
                }
                dbg("CARVE no solid non-falling roof within depth budget → BAIL (would be open-air)");
                a.releaseInputs();
                return finish(st, w, a, "no-safe-roof", "no solid non-falling roof within depth budget — terrain unsuitable for bunker");
            }
            dbg("CARVE niche dir={} roof={} (n0={})", nicheDir,
                    bottom.relative(nicheDir).above().above(), bottom.relative(nicheDir));
        }
        BlockPos n0 = bottom.relative(nicheDir);
        BlockPos n1 = n0.above();
        BlockPos target = w.isSolid(n1) ? n1 : (w.isSolid(n0) ? n0 : null);   // clear head first, then foot
        if (target == null) { dbg("CARVE done dir={} → STEP_IN", nicheDir); phase = Phase.STEP_IN; actTicks = 0; hands.breakHold(false); return false; }
        hands.selectTool(target);
        a.aimAtBlock(target);
        hands.breakHold(true);
        hands.continueDestroy(target);
        acted = true;
        if (++actTicks > BotConfig.breakTimeoutTicks * 2) {
            hands.breakHold(false); a.releaseInputs();
            return finish(st, w, a, "act-timeout", "seal/carve timeout");
        }
        return false;
    }

    private boolean stepIn(Body a, WorldView w, LivingEntity p, BlockPos foot) {
        hands.breakHold(false);
        BlockPos n0 = bottom.relative(nicheDir);
        // Must enter the niche FULLY — pressed against its back wall — before
        // plugging. A blockPos-only match (foot.z == n0.z) fires while the bot
        // is still straddling the shaft/niche boundary (e.g. center z=297.76),
        // leaving its 0.6-wide hitbox overlapping the shaft cell so PLUG can't
        // place there → bunker ends unsealed. Gate on distance from the shaft
        // centre instead: ≥0.85 means the bot has cleared the shaft column.
        double shaftDx = p.getX() - (bottom.getX() + 0.5);
        double shaftDz = p.getZ() - (bottom.getZ() + 0.5);
        double distFromShaft = Math.sqrt(shaftDx * shaftDx + shaftDz * shaftDz);
        boolean inNiche = foot.getX() == n0.getX() && foot.getZ() == n0.getZ()
                && distFromShaft >= 0.85;
        if (inNiche) {
            a.releaseInputs();
            dbg("STEP_IN done foot={} distFromShaft={} → PLUG", foot, fmt(distFromShaft));
            phase = Phase.PLUG; actTicks = 0; plugTicks = 0;
            return false;
        }
        // Face the niche and walk in.
        p.setYRot(yawFor(nicheDir));
        p.setXRot(0f);
        a.commandForward(1f);
        if (actTicks % 5 == 0)
            dbg("STEP_IN walking pos=({},{}) foot={} n0={} distFromShaft={} t={}",
                    fmt(p.getX()), fmt(p.getZ()), foot, n0, fmt(distFromShaft), actTicks);
        if (++actTicks > 80) {            // ~4s to shuffle one block; give up if stuck
            a.releaseInputs();
            dbg("STEP_IN TIMEOUT distFromShaft={} → PLUG (may be blocked)", fmt(distFromShaft));
            phase = Phase.PLUG; actTicks = 0; plugTicks = 0;
        }
        return false;
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    private boolean plug(Body a, WorldView w, BotState st, LivingEntity p) {
        a.commandForward(0f);
        // Plug the shaft column the bot vacated: bottom foot then the cell above.
        // Both gain support from below (floor / the foot-plug) so even sand holds.
        BlockPos p0 = bottom;             // old foot, has solid floor under it
        BlockPos p1 = bottom.above();     // old head, supported by p0 once placed
        BlockPos target = !w.isSolid(p0) ? p0 : (!w.isSolid(p1) ? p1 : null);
        if (target == null) {
            dbg("PLUG sealed (p0={},p1={} both solid) → SEALED-hold", p0, p1);
            phase = Phase.SEALED; sealedOk = true; a.releaseInputs(); return false;   // sealed → hold the pocket (GAP #22)
        }
        if (!hands.holdPlaceable()) {
            dbg("PLUG no placeable block in hand → DONE UNSEALED target={}", target);
            phase = Phase.DONE; a.releaseInputs();
            return finish(st, w, a, phase.name(), null); // nothing to plug with
        }
        // Guard: if the bot's own hitbox still overlaps the cell we're filling,
        // placement silently fails forever. Detect it and keep shuffling into
        // the niche instead of burning the timeout unsealed.
        if (p.getBoundingBox().intersects(new AABB(target))) {
            p.setYRot(yawFor(nicheDir));
            a.commandForward(1f);
            dbg("PLUG body overlaps target={} pos=({},{}) → shuffle deeper", target, fmt(p.getX()), fmt(p.getZ()));
            if (++actTicks > BotConfig.breakTimeoutTicks * 2) {
                dbg("PLUG give up (still overlapping) → DONE UNSEALED");
                phase = Phase.DONE; a.releaseInputs();
                return finish(st, w, a, phase.name(), null);
            }
            return false;
        }
        a.commandForward(0f);
        a.aimAtBlock(target);
        hands.place(w, target);
        acted = true;
        dbg("PLUG place target={} solidNow={} t={}", target, w.isSolid(target), actTicks);
        if (++plugTicks > BotConfig.breakTimeoutTicks) {
            dbg("PLUG TIMEOUT target={} solid={} → DONE", target, w.isSolid(target));
            phase = Phase.DONE; a.releaseInputs();
            return finish(st, w, a, phase.name(), null);
        }
        return false;
    }

    /** Yaw that faces the given cardinal (MC: 0=+Z south, 90=-X west, 180=-Z north, -90=+X east). */
}
