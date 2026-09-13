package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Per-tick data flowing between the WalkerTick* phases, grouped by the stage that
 * PRODUCES it (census 2026-07-19, docs/walker-tick-architecture.md). A fresh instance
 * is built every tick by the driver in {@link Walker#tickInner}; nothing here survives
 * the tick — cross-tick state stays in {@link Walker} fields.
 *
 * <p>Reading a phase's persist block now states its contract: a phase writes its OWN
 * product group and only re-publishes {@code frame} basics it recomputed; a phase's
 * rehydrate block names exactly what it consumes.
 */
final class WalkerTickCtx {
    final Frame frame = new Frame();
    final StallVerdict stall = new StallVerdict();
    final EdgeState edges = new EdgeState();
    final AimPlan aim = new AimPlan();

    /** Produced by {@link WalkerTickPrelude}: the resolved body + anchor cells every
     *  later phase keys off. {@code foot} is re-derived by phases that move the body. */
    static final class Frame {
        LivingEntity p;
        BlockPos foot;
        BlockPos searchFoot;
    }

    /** Produced by {@link WalkerTickStallDetect}: the stall/deviation verdict consumed
     *  by the repath/search stages. */
    static final class StallVerdict {
        double d;                  // goal.estimate(foot) this tick
        boolean offPath;
        boolean breakingEdge;      // a planned break edge is actively swinging
        boolean wedged;
        boolean fellOffPath;
        boolean fellBelowRoute;
    }

    /** Produced by {@link WalkerTickClimb}/{@link WalkerTickEdgeGuards}: the committed
     *  edge under execution and its guard classifications, consumed by Aim/Drive. */
    static final class EdgeState {
        Move.Edge edge;
        BlockPos wp;
        boolean parkourEdge;
        boolean bridging;
        boolean placingEdge;
        boolean steppingOffFall;
        boolean steppingOffWaterFall;
    }

    /** Produced by {@link WalkerTickAim}: the full aim/camera/drive-shaping plan the
     *  Drive stage executes. */
    static final class AimPlan {
        boolean aimAtWaypoint;
        boolean reCentre;
        String aimSrc;
        float descentNodeYaw;
        boolean dryDescent;
        boolean flatWaterTrend;
        boolean trendCam;
        float aimYaw;
        boolean diveUnderCap;
        boolean diving;
        double stepColDx;
        double stepColDz;
        boolean stepUpFreeze;
        boolean pivotForStepUp;
        boolean descendBrake;
    }
}
