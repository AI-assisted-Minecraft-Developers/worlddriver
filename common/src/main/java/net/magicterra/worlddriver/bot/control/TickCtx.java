package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.bot.rules.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Per-tick data flowing between the *Phase classes, grouped by the stage that
 * PRODUCES it (see docs/dev/movement-tick-phases.md). A fresh instance
 * is built every tick by the driver in {@link Navigator#tickInner}; nothing here survives
 * the tick — cross-tick state stays in {@link Navigator} fields.
 *
 * <p>Reading a phase's persist block now states its contract: a phase writes its OWN
 * product group and only re-publishes {@code frame} basics it recomputed; a phase's
 * rehydrate block names exactly what it consumes.
 */
public final class TickCtx {
    public final Frame frame = new Frame();
    public final StallVerdict stall = new StallVerdict();
    public final EdgeState edges = new EdgeState();
    public final AimPlan aim = new AimPlan();
    /** Why {@link DrivePhase}'s riser lead gave no lead this tick, for the walk-keys line only. */
    public String leadWhy = "";

    /** Produced by {@link PreludePhase}: the resolved bot + anchor cells every
     *  later phase keys off. {@code foot} is re-derived by phases that move the bot. */
    public static final class Frame {
        public LivingEntity p;
        public BlockPos foot;
        public BlockPos searchFoot;
    }

    /** Produced by {@link StallDetectPhase}: the stall/deviation verdict consumed
     *  by the repath/search stages. */
    public static final class StallVerdict {
        public double d;                  // goal.estimate(foot) this tick
        public boolean offPath;
        public boolean breakingEdge;      // a planned break edge is actively swinging
        public boolean dugLastTick;       // the block-less climb-out dig swung last tick (its flag is re-armed later this tick)
        public boolean wedged;
        public boolean fellOffPath;
        public boolean fellBelowRoute;
    }

    /** Produced by {@link EdgeGuardsPhase}: the committed
     *  edge under execution and its guard classifications, consumed by Aim/Drive. */
    public static final class EdgeState {
        public Move.Edge edge;
        public BlockPos wp;
        public boolean parkourEdge;
        public boolean bridging;
        public boolean placingEdge;
        public boolean steppingOffFall;
        public boolean steppingOffWaterFall;
    }

    /** Produced by {@link AimPhase}: the full aim/camera/drive-shaping plan the
     *  Drive stage executes. */
    public static final class AimPlan {
        public boolean aimAtWaypoint;
        public boolean reCentre;
        public String aimSrc;
        public float descentNodeYaw;
        public boolean dryDescent;
        public boolean flatWaterTrend;
        public boolean trendCam;
        public float aimYaw;
        public boolean diveUnderCap;
        public boolean diving;
        public double stepColDx;
        public double stepColDz;
        public boolean stepUpFreeze;
        public boolean pivotForStepUp;
        public boolean descendBrake;
    }
}
