package net.magicterra.worlddriver.bot.control;

import java.util.concurrent.atomic.AtomicLong;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Per-delegated-tick bundle handed to {@link AscendMovement}. Constructed ONCE per delegated tick from live Navigator
 * state; the machine retains no reference across ticks except its own owned sub-state (which AscendMovement holds). See
 * spec §3.2.
 */
public final class MovementContext {
    /**
     * TEST SEAM (spec §8.3): total MovementContext instances ever constructed. The no-op arena asserts this does NOT
     * move while navigatorAscendMovement is OFF — proving the OFF branch never allocates a context. Atomic because
     * gametest threads read it.
     */
    public static final AtomicLong ALLOC_COUNT = new AtomicLong();

    public final LivingEntity
            player; // pose/velocity: getX/Y/Z, getDeltaMovement, onGround, horizontalCollision, isInWater

    public final WorldView world; // isSolid/isPassable/isHazard for the BREAK phase (mirrors StairUpBreak.eval)

    public final Body avatar; // selectTool/aimAtBlock/breakHold/placeOn/holdPillarBlock + commandForward/Jump/Sneak

    public final Move.Edge edge; // current edge: to (stand cell), toBreak, toPlace, move name

    public final BlockPos foot; // grounded foot cell this tick

    public final BlockPos node; // path.get(step) — the stand-cell node (== edge.to for an ascent)

    public final boolean
            digging; // Navigator's breakingEdge this tick — an active planned dig is progress (dead-zone watchdog
    // exemption, #66: bare-hand stone is 150t+/block)

    /** Snapshots one tick's inputs for the controllers. Each argument becomes one public field, in the order given. */
    public MovementContext(
            LivingEntity player,
            WorldView world,
            Body avatar,
            Move.Edge edge,
            BlockPos foot,
            BlockPos node,
            boolean digging) {
        ALLOC_COUNT.incrementAndGet();
        this.player = player;
        this.world = world;
        this.avatar = avatar;
        this.edge = edge;
        this.foot = foot;
        this.node = node;
        this.digging = digging;
    }
}
