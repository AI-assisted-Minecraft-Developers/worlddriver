package net.magicterra.worlddriver.bot.control.route;

import java.util.List;
import net.minecraft.core.BlockPos;

public final class ArcShadow {
    public final PathProjection proj =
            new PathProjection(); // reusable arc-length projector (the carved-out, fully-readable core; the executor

    // only holds onto its result)
    public List<BlockPos> shadowPath; // path identity the shadow s tracks (reset s/ds when the path object changes)

    public double shadowS =
            Double.NaN; // last tick's cumulative arc-length (XZ, from path[0] to the foot's polyline projection); for

    // ds/dt +
    // monotonic-violation detection
    public long
            monoViol; // count of backward-snap events (ds < -0.5) — must stay ~0 on clean runs for the projection to be

    // trustworthy enough to drive in Phase 1
    // Phase-3 (navigatorArcLengthWedge): GROUNDED-RAM wedge timer measured in ARC-LENGTH, not 3D-new-low. The legacy
    // descentRamStuck/ascentRamSlide recoveries hang on noStepProgressTicks (a 3D-new-low counter) which the
    // buoyancy bob AND the sub-block ram-jitter (the bot sliding a few cm against a wall) ZERO every tick → the
    // gate never fills and the ram bobs ~5 s until the slow 100-tick wedge fires. The horizontal arc projection s
    // is immune to that vertical/jitter noise (a wall-ram makes no forward arc progress regardless of bob), so
    // ticks of |ds|<ARC_WEDGE_DS while horizontalCollision accumulate reliably → fold into fellOffPath far sooner.
    public int
            wedgeTicks; // consecutive ticks of ~zero arc-progress while ramming (bob/jitter-immune); reset on any real
    // ds or path change
}
