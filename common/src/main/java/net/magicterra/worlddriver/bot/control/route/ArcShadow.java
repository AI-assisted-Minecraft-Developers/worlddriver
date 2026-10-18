package net.magicterra.worlddriver.bot.control.route;

import java.util.List;
import net.minecraft.core.BlockPos;

public final class ArcShadow {
    public final PathProjection proj = new PathProjection();  // reusable arc-length projector (the carved-out, fully-readable core; the executor only holds onto its result)
    public List<BlockPos> shadowPath;    // path identity the shadow s tracks (reset s/ds when the path object changes)
    public double shadowS = Double.NaN;  // last tick's cumulative arc-length (XZ, from path[0] to the foot's polyline projection); for ds/dt + monotonic-violation detection
    public long monoViol;                // count of backward-snap events (ds < -0.5) — must stay ~0 on clean runs for the projection to be trustworthy enough to drive in Phase 1
    // Phase-3 (walkerArcLengthWedge): GROUNDED-RAM wedge timer measured in ARC-LENGTH, not 3D-new-low. The legacy
    // descentRamStuck/ascentRamSlide recoveries hang on noStepProgressTicks (a 3D-new-low counter) which the
    // buoyancy bob AND the sub-block ram-jitter (the bot sliding a few cm against a wall) ZERO every tick → the
    // gate never fills and the ram bobs ~5 s until the slow 100-tick wedge fires. The horizontal arc projection s
    // is immune to that vertical/jitter noise (a wall-ram makes no forward arc progress regardless of bob), so
    // ticks of |ds|<ARC_WEDGE_DS while horizontalCollision accumulate reliably → fold into fellOffPath far sooner.
    public int wedgeTicks;               // consecutive ticks of ~zero arc-progress while ramming (bob/jitter-immune); reset on any real ds or path change
    // Phase-3b (walkerArcProgressWedge): NET arc-length progress over a WINDOW — catches an OSCILLATING limit cycle
    // the per-tick ram wedge (wedgeTicks, needs hCol + per-tick |ds|<0.05) and the anti-churn net-XZ both miss.
    // A steep-face diagUp churn (live 2026-06-28 -815, replay-0006) has the bot bob-jumping airborne (no hCol), making
    // small per-tick FORWARD ds then sliding back — net arc-s ≈ 0 over the cycle yet per-tick |ds| > 0.05 (so the ram
    // wedge resets) and net-XZ swings 35 blocks laterally (so the anti-churn is fooled). arc-s is the projection ONTO
    // the path, immune to the lateral swing AND the vertical bob, so net arc-s over a window cleanly flags "no path
    // progress despite motion". Folds into the SAME fellOffPath recovery (fresh foot-search blacklists the
    // un-advanceable node + re-routes). Window-based so it does NOT need hCol or onGround.
    public double progBaseS = Double.NaN;  // proj.s at the start of the current progress window
    public int progWindowTicks;          // ticks elapsed in the current arc-progress window
    public boolean progStall;            // last completed window made < ARC_PROG_MIN net arc-s progress → stuck (consumed by fellOffPath)
}
