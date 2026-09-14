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

import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.util.BotUtil.yawFor;

/**
 * Staircase-dig DOWN to an absolute Y — the descent mirror of
 * {@link EscapeProcess}.
 *
 * <p>Why this exists: {@code goto y=N} descends via the A* pathfinder, which
 * prices block-breaking so heavily that in forested hill terrain it prefers
 * hunting a distant natural cave mouth over digging — and then stalls or
 * wanders (devil-bench day1_iron ep-003/006/008/009: four episodes, zero
 * successful descents to y16, one drowning en route). A veteran just digs a
 * straight staircase. This process does exactly that: pick a cardinal, clear
 * the three-cell stair notch ahead (transit head, transit feet, landing feet),
 * verify the landing floor is solid and dry, step down one block, repeat.
 *
 * <p>Safety per stride: every cell to be opened must be non-fluid, non-hazard
 * and finitely breakable; the landing floor must be solid, non-falling and
 * non-hazard — otherwise the direction is rejected and another cardinal tried
 * (rotating keeps the shaft compact instead of walking off). When no sideways
 * stride is safe, it falls back to digging its OWN floor cell one block down
 * (safe because the cell two below is verified solid first). A futility
 * watchdog and a cumulative-damage abort bound the worst case; every exit is
 * reported through {@link BotState#escape} (the shared vertical-move slot, so
 * {@code await_process('escape')} works unchanged for both directions).
 */
public final class DescendProcess implements BotProcess {
    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private Hands hands;

    private enum Phase { PICK, CARVE, STEP_DOWN, DIG_OWN }

    /** Dig until foot Y is at or below this. */
    private final int targetY;
    private static final int MAX_STEPS = 96;
    private static final int FUTILE_LIMIT = 4;
    /** HP lost since start that aborts the dig (mob in a breached cave, drip
     *  damage...) — mirrors MineProcess's cumulative-damage abort. */
    private static final float MAX_HP_DROP = 6f;

    private Phase phase = Phase.PICK;
    private Direction dir = null;
    private BlockPos base = null;         // foot cell this stride starts from
    private BlockPos actTarget = null;
    private int actTicks = 0, steps = 0;
    private int futileCycles = 0, stepsAtLastPick = -1;
    private float startHp = -1f;

    public DescendProcess(int targetY) { this.targetY = targetY; }

    private static void dbg(String m, Object... a) { if (BotConfig.walkerDebug) LOG.info("[descend] " + m, a); }

    @Override public String kind() { return "escape"; }

    @Override public void attach(BotState st) {
        BotState.ProcessSlot s = st.escape;
        s.active = true;
        s.goal = "descend to y=" + targetY;
        s.pathLen = MAX_STEPS;
        s.startedAtMs = System.currentTimeMillis();
        s.lastError = null;
    }

    @Override public String statusDetail() { return phase.name(); }

    private boolean done(Body a, BotState st, String error) {
        if (hands != null) hands.breakHold(false);
        a.releaseInputs();
        BotState.ProcessSlot s = st.escape;
        s.active = false;
        s.lastError = error;
        return true;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        if (p == null) return done(a, st, "no player");
        hands = a.hands().orElse(null);
        if (hands == null) return done(a, st, BodyReady.Reason.NO_HANDS);
        if (!BotConfig.allowBreak) return done(a, st, "allowBreak is off — descend mines every step");
        BlockPos foot = p.blockPosition();
        st.escape.pathStep = steps;
        st.escape.target = foot;
        if (startHp < 0) startHp = p.getHealth();
        if (startHp - p.getHealth() > MAX_HP_DROP) {
            return done(a, st, "aborted: lost " + (startHp - p.getHealth())
                    + " HP during descent at " + foot.toShortString());
        }
        if (foot.getY() <= targetY) {
            p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            dbg("DONE foot={} targetY={}", foot, targetY);
            return done(a, st, null);
        }
        if (steps >= MAX_STEPS) return done(a, st, "max steps (" + MAX_STEPS + ") reached at " + foot.toShortString());

        return switch (phase) {
            case PICK      -> pick(a, w, p, foot, st);
            case CARVE     -> carve(a, w, p, st);
            case STEP_DOWN -> stepDown(a, p, foot);
            case DIG_OWN   -> digOwn(a, w, p, foot, st);
        };
    }

    /** A stair notch in direction d from foot F opens three cells —
     *  A = F+d+1 (transit head), B = F+d (transit feet / standing head),
     *  C = F+d-1 (landing feet) — and lands on D = F+d-2 which must be a
     *  solid, non-falling, non-hazard floor. All opened cells must be dry,
     *  non-hazard and finitely breakable. */
    private boolean strideSafe(WorldView w, Level lvl, BlockPos foot, Direction d) {
        BlockPos b = foot.relative(d);
        BlockPos aCell = b.above();
        BlockPos c = b.below();
        BlockPos floor = c.below();
        for (BlockPos cell : new BlockPos[]{aCell, b, c}) {
            if (w.isHazard(cell) || w.isWater(cell)) return false;
            if (w.isSolid(cell) && Double.isInfinite(w.breakCost(cell))) return false;
        }
        return w.isSolid(floor) && !w.isHazard(floor) && !isFalling(lvl, floor);
    }

    private boolean pick(Body a, WorldView w, LivingEntity p, BlockPos foot, BotState st) {
        if (steps == stepsAtLastPick) {
            if (++futileCycles >= FUTILE_LIMIT) {
                return done(a, st, "futile: " + FUTILE_LIMIT
                        + " re-picks with no step progress at " + foot.toShortString());
            }
        } else {
            futileCycles = 0;
            stepsAtLastPick = steps;
        }
        p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        // Keep the current direction while it stays safe: a straight staircase
        // reads as one clean tunnel and never spirals around its own shaft.
        Direction best = null;
        if (dir != null && strideSafe(w, p.level(), foot, dir)) {
            best = dir;
        } else {
            for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST,
                                               Direction.SOUTH, Direction.WEST}) {
                if (strideSafe(w, p.level(), foot, d)) { best = d; break; }
            }
        }
        if (best == null) {
            // No sideways stride is safe — dig the bot's own floor cell if the
            // cell two below is a solid dry landing (a bounded 1-block drop).
            BlockPos below = foot.below();
            BlockPos below2 = below.below();
            boolean ownSafe = w.isSolid(below)
                    && !Double.isInfinite(w.breakCost(below))
                    && !w.isHazard(below) && !w.isWater(below)
                    && w.isSolid(below2) && !w.isHazard(below2)
                    && !isFalling(p.level(), below2);
            if (ownSafe) {
                base = foot.immutable();
                phase = Phase.DIG_OWN;
                actTicks = 0;
                actTarget = null;
                dbg("PICK no safe stride → DIG_OWN at {}", foot);
                return false;
            }
            return done(a, st, "no safe descent stride at " + foot.toShortString()
                    + " (all cardinals + own column wet/hazard/unbreakable)");
        }
        dir = best;
        base = foot.immutable();
        phase = Phase.CARVE;
        actTicks = 0;
        actTarget = null;
        dbg("PICK dir={} from foot={}", dir, foot);
        return false;
    }

    private boolean carve(Body a, WorldView w, LivingEntity p, BotState st) {
        BlockPos b = base.relative(dir);
        BlockPos aCell = b.above();
        BlockPos c = b.below();
        // Top-down so falling gravel above can't refill a just-cleared cell.
        BlockPos target = w.isSolid(aCell) ? aCell
                        : w.isSolid(b) ? b
                        : w.isSolid(c) ? c : null;
        if (target == null) {
            dbg("CARVE clear dir={} → STEP_DOWN", dir);
            phase = Phase.STEP_DOWN;
            actTicks = 0;
            actTarget = null;
            hands.breakHold(false);
            return false;
        }
        if (Double.isInfinite(w.breakCost(target))) {
            return done(a, st, "unbreakable block in stride at " + target.toShortString());
        }
        if (!target.equals(actTarget)) { actTarget = target; actTicks = 0; }
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        hands.selectTool(target);
        a.aimAtBlock(target);
        hands.breakHold(true);
        hands.continueDestroy(target);
        if (++actTicks > BotConfig.breakTimeoutTicks * 3) {
            return done(a, st, "carve timeout at " + target.toShortString());
        }
        return false;
    }

    private boolean stepDown(Body a, LivingEntity p, BlockPos foot) {
        hands.breakHold(false);
        BlockPos destFeet = base.relative(dir).below();
        boolean atDest = foot.getX() == destFeet.getX() && foot.getZ() == destFeet.getZ()
                && foot.getY() <= destFeet.getY();
        if (atDest && p.onGround()) {
            p.setPos(destFeet.getX() + 0.5, p.getY(), destFeet.getZ() + 0.5);
            p.setDeltaMovement(0, Math.min(0, p.getDeltaMovement().y), 0);
            a.releaseInputs();
            steps++;
            dbg("STEP_DOWN arrived foot={} (step {}) → PICK", foot, steps);
            phase = Phase.PICK;
            return false;
        }
        p.setYRot(yawFor(dir));
        p.setXRot(30f);           // look slightly down the stair, never straight down
        a.commandForward(1f);
        a.commandJump(false);
        if (++actTicks > 100) {   // ~5s; geometry shifted (gravel?) → re-pick
            a.releaseInputs();
            dbg("STEP_DOWN stall foot={} dest={} → re-PICK", foot, destFeet);
            phase = Phase.PICK;
        }
        return false;
    }

    private boolean digOwn(Body a, WorldView w, LivingEntity p, BlockPos foot, BotState st) {
        BlockPos below = base.below();
        if (!w.isSolid(below)) {
            // Cleared — gravity takes the bot down one; wait for landing.
            hands.breakHold(false);
            if (p.onGround() && foot.getY() < base.getY()) {
                steps++;
                dbg("DIG_OWN landed foot={} (step {}) → PICK", foot, steps);
                phase = Phase.PICK;
            } else if (++actTicks > 60) {
                a.releaseInputs();
                phase = Phase.PICK;
            }
            return false;
        }
        if (!below.equals(actTarget)) { actTarget = below; actTicks = 0; }
        p.setPos(base.getX() + 0.5, p.getY(), base.getZ() + 0.5);
        p.setDeltaMovement(0, p.getDeltaMovement().y, 0);
        hands.selectTool(below);
        a.aimAtBlock(below);
        hands.breakHold(true);
        hands.continueDestroy(below);
        if (++actTicks > BotConfig.breakTimeoutTicks * 3) {
            return done(a, st, "own-floor dig timeout at " + below.toShortString());
        }
        return false;
    }

    private static boolean isFalling(Level lvl, BlockPos pos) {
        return lvl != null && lvl.getBlockState(pos).getBlock() instanceof FallingBlock;
    }

    /** Yaw facing the given cardinal (MC: 0=+Z south, 90=-X west, 180=-Z north, -90=+X east). */
}
