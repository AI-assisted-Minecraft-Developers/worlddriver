package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/** Per-delegated-tick bundle handed to {@link AscendMovement}. Constructed ONCE per delegated tick
 *  from live Walker state; the machine retains no reference across ticks except its own owned
 *  sub-state (which AscendMovement holds). See spec §3.2. */
public final class MovementContext {
    /** TEST SEAM (spec §8.3): total MovementContext instances ever constructed. The no-op arena
     *  asserts this does NOT move while walkerAscendMovement is OFF — proving the OFF branch never
     *  allocates a context. Volatile because gametest threads read it. */
    public static volatile long ALLOC_COUNT = 0;

    public final LivingEntity p;       // pose/velocity: getX/Y/Z, getDeltaMovement, onGround, horizontalCollision, isInWater
    public final WorldView world;      // isSolid/isPassable/isHazard for the BREAK phase (mirrors StairUpBreak.eval)
    public final Body avatar;          // selectTool/aimAtBlock/breakHold/placeOn/holdPillarBlock + commandForward/Jump/Sneak
    public final Move.Edge edge;       // current edge: to (stand cell), toBreak, toPlace, move name
    public final BlockPos foot;        // grounded foot cell this tick
    public final BlockPos node;        // path.get(step) — the stand-cell node (== edge.to for an ascent)
    public final int maxJumpUp;        // world.maxJumpUpBlocks()
    public final int maxStepUp;        // world.maxStepUpBlocks()
    public final BlockPos prevNode;    // path.get(step-1) or null — chainAscend peek
    public final BlockPos prevNode2;   // path.get(step-2) or null — chainAscend peek
    public final boolean digging;      // Walker's breakingEdge this tick — an active planned dig is progress (dead-zone watchdog exemption, #66: bare-hand stone is 150t+/block)

    public MovementContext(LivingEntity p, WorldView world, Body avatar, Move.Edge edge,
                           BlockPos foot, BlockPos node, int maxJumpUp, int maxStepUp,
                           BlockPos prevNode, BlockPos prevNode2, boolean digging) {
        ALLOC_COUNT++;
        this.p = p; this.world = world; this.avatar = avatar; this.edge = edge;
        this.foot = foot; this.node = node; this.maxJumpUp = maxJumpUp; this.maxStepUp = maxStepUp;
        this.prevNode = prevNode; this.prevNode2 = prevNode2; this.digging = digging;
    }
}
