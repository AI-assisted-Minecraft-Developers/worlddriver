package net.magicterra.worlddriver.bot.util;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Stateless conversion / parsing / threading helpers shared across the bot
 * package. Extracted from the former {@code BotApiImpl} god-class; callers
 * pull these in via {@code import static …BotUtil.*} so call sites stay
 * unqualified.
 */
public final class BotUtil {

    private BotUtil() {}

    public static BlockPos blockPosOf(Entity e) {
        return new BlockPos((int) Math.floor(e.getX()), (int) Math.floor(e.getY()), (int) Math.floor(e.getZ()));
    }

    public static Map<String, Object> posMap(BlockPos p) {
        return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ());
    }

    public static BlockPos readPos(Object o) { return Params.toPos(o); }

    public static int intOr(Object o, int dflt) { return Params.toInt(o, dflt); }
    public static double doubleOr(Object o, double dflt) { return Params.toDouble(o, dflt); }
    public static int clamp(int v, int lo, int hi) { return Params.clamp(v, lo, hi); }

    public static List<String> parseStringList(Object o) { return Params.toStringList(o); }

    public static Map<String, Object> unimplemented(String msg) {
        return Map.of("ok", false, "error", "unimplemented: " + msg);
    }

    // === Threading bridge ====================================================

    /** Default budget for waiting on a client-tick hop. Mirrors
     *  {@code DriverApi.SERVER_THREAD_TIMEOUT_MS} — the server-side twin of this
     *  bridge — so a stalled client surfaces as a clear error instead of parking
     *  the calling RPC/MCP thread forever. Override with
     *  {@code -Dworlddriver.clientThreadTimeoutMs=N}. */
    private static final long CLIENT_THREAD_TIMEOUT_MS =
            Long.getLong("worlddriver.clientThreadTimeoutMs", 8_000L);

    /**
     * Run {@code body} on the client thread and return its value.
     *
     * <p>Modelled on {@code DriverApi.onServerThread}, and deliberately identical to it
     * in the two respects that are observable to a caller:
     * <ul>
     *   <li><b>Bounded.</b> {@code mc.execute} only runs when the client drains its task
     *       queue; during shutdown, a hung level load, or a blocking modal it may never
     *       do so. The old unbounded {@code fut.get()} then parked the calling transport
     *       thread permanently — the request never returned and never errored.</li>
     *   <li><b>Transparent to exceptions.</b> The old code wrapped everything in
     *       {@code RuntimeException(e)}, so the same failure read as
     *       {@code IllegalArgumentException: bad param} when invoked from the client
     *       thread (the {@code isSameThread} fast path, which rethrows raw) but as
     *       {@code RuntimeException: ExecutionException: IllegalArgumentException: bad
     *       param} from any transport thread. Unwrapping the {@code ExecutionException}
     *       makes both paths report the same text — which is what the three-transport
     *       byte-identical parity assertion actually compares.</li>
     * </ul>
     */
    public static <T> T onClient(Supplier<T> body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return body.get();
        CompletableFuture<T> fut = new CompletableFuture<>();
        mc.execute(() -> {
            try { fut.complete(body.get()); }
            catch (Throwable t) { fut.completeExceptionally(t); }
        });
        try {
            return fut.get(CLIENT_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("client thread did not run task within "
                    + CLIENT_THREAD_TIMEOUT_MS + "ms (client busy, loading or paused)");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting on client thread");
        }
    }

    // === Camera smoothing (mc.bot.setting{smoothLook}) =======================

    /**
     * Step {@code cur} toward {@code target} by at most
     * {@link BotConfig#smoothLookDegPerTick} when {@link BotConfig#smoothLook}
     * is on; otherwise snap straight to the target (current behavior). Valid for
     * both yaw (wraps mod 360) and pitch (no wrap within ±90). Used by the
     * pathfinding Walker and the LookProcess; functional aiming snaps by calling
     * the rotation setters directly instead of going through here.
     */
    public static float smoothAngle(float cur, float target) {
        if (!BotConfig.smoothLook) return target;
        float max = Math.max(0.1f, BotConfig.smoothLookDegPerTick);
        float d = ((target - cur) % 360f + 540f) % 360f - 180f; // shortest signed delta
        if (Math.abs(d) <= max) return target;
        return cur + Math.copySign(max, d);
    }

    /**
     * Damage-on-contact blocks the pathfinder must route around. Lava is checked
     * by fluid tag; fire variants by {@link net.minecraft.tags.BlockTags#FIRE} so
     * datapack-added fire blocks are covered automatically. Remaining hazards are
     * listed by {@link net.minecraft.world.level.block.Block} reference for O(1)
     * lookup and so a modpack-added hazard can be added in a single line.
     */
    public static final Set<Block> HAZARD_BLOCKS = Set.of(
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW,
            Blocks.WITHER_ROSE,
            // §88: "impaled on a stalagmite" (C102-J2 death). Fall damage onto an
            // upward spike is DOUBLED and the executor drifts ±1 block during a
            // committed fall, so the contact ring must tax the spike's neighbourhood
            // — the block was simply missing from this list (same class as the
            // FLOWING_LAVA FluidTags blind spot).
            Blocks.POINTED_DRIPSTONE
    );

    /** Cheap stand finder: 4 cardinals at same Y, then Y-1, then Y+1, then on
     *  top of the block. Water counts as passable. Used by the goto block
     *  selector — the more thorough SEARCHES in MineProcess / BboxFillProcess
     *  scan more cells and add reach checks tailored to mining/clearing, but
     *  they all judge a candidate cell with {@link #canStandHereStatic}. */
    public static BlockPos findStandAdjacent(Level lvl, BlockPos block) {
        int[][] dxz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : dxz) {
                BlockPos cand = block.offset(d[0], dy, d[1]);
                if (canStandHereStatic(lvl, cand)) return cand;
            }
        }
        BlockPos above = block.offset(0, 1, 0);
        if (canStandHereStatic(lvl, above)) return above;
        return null;
    }

    /**
     * Can a body stand with its feet in {@code foot}: a floor that blocks motion under it, and
     * both body cells clear of anything that does (water excepted — a body wades).
     *
     * <p><b>The answer for most of the process family — not yet all of it.</b>
     * {@code BboxFillProcess}, {@code FarmProcess} and {@code MineProcess} each carried a
     * byte-identical private copy of this, which is how a stand test comes to mean three things:
     * the day somebody teaches one of them about a half-slab, the other two keep walking onto it.
     * {@code MineProcess} still refuses more than this — it additionally vetoes a cell lava
     * touches — and that is written there as {@code canStandHereStatic(...) && no lava}, so the
     * extra clause is visibly extra rather than a second opinion about the same question.
     *
     * <p><b>Two members never joined, and their copy has since diverged.</b>
     * {@code BuildProcess.canStand} and {@code BackfillProcess.canStand} are still private, still
     * byte-identical to each other, and are missing BOTH water clauses below — so a WATERLOGGED
     * cell (a waterlogged slab/stairs/fence: {@code blocksMotion()} true, fluid WATER) is standable
     * here and refused there. Those two are therefore STRICTER than the rest of the family, and a
     * build over a waterlogged surface reports {@code skipped} for a cell the goto selector would
     * happily walk to. Left as-is deliberately: adopting this method there LOOSENS two placement
     * paths, which is the direction that needs a measurement, not a tidy-up.
     *
     * <p>Cell-shaped, deliberately: this decides where to SEND a body, before it is there. Whether
     * a body already standing somewhere is actually supported is a different question with a
     * different answer — the body is 0.6 wide and can be held by a neighbour cell — and it belongs
     * to {@code WalkerGeometry.soleOnSolid}. Do not use one for the other.
     */
    public static boolean canStandHereStatic(Level lvl, BlockPos foot) {
        BlockState below = lvl.getBlockState(foot.offset(0, -1, 0));
        BlockState here = lvl.getBlockState(foot);
        BlockState head = lvl.getBlockState(foot.offset(0, 1, 0));
        if (!below.blocksMotion()) return false;
        if (here.blocksMotion() && !here.getFluidState().is(Fluids.WATER)) return false;
        if (head.blocksMotion() && !head.getFluidState().is(Fluids.WATER)) return false;
        return true;
    }

    /**
     * Where the eye WOULD be if a body stood with its feet in {@code foot} — the cell centre,
     * 1.62 up. For deciding, before the body is there, whether a candidate stand can reach a
     * block; a body that is already somewhere has {@code p.getEyePosition()} and must use it.
     *
     * <p>The three literals were written out twice ({@code MineProcess.findReachStand},
     * {@code BboxFillProcess.withinReach}) — the same hypothetical eye, so one place.
     *
     * <p><b>The radius is deliberately NOT part of this.</b> "Within reach" is asked in five
     * places and they do not all want the same margin — so every one now measures the same way
     * (eye to block centre, via {@link #eyeWithin}) and differs only in a number you can read:
     *
     * <table><caption>reach predicates, 2026-08-22</caption>
     * <tr><th>site</th><th>eye</th><th>radius</th></tr>
     * <tr><td>{@code ServerPlayerAvatar.canBreakFromHere} — <b>the authority</b>, and where
     *     {@link #blockReachToCentre} came from</td><td>the real eye</td>
     *     <td>{@code blockInteractionRange() + 0.5}</td></tr>
     * <tr><td>{@code WalkerTickPrelude} dig-claim release</td><td>the real eye</td>
     *     <td>{@link #blockReachToCentre} — a release gate must match the actuator exactly,
     *     or it either abandons reachable blocks or holds unreachable ones</td></tr>
     * <tr><td>{@code MineProcess.findReachStand}</td><td>this hypothetical eye</td>
     *     <td>{@code MAX_REACH} = 4.4, plus a collider ray</td></tr>
     * <tr><td>{@code BboxFillProcess.withinReach}</td><td>this hypothetical eye</td>
     *     <td>{@code FILL_STAND_REACH} = 4.0, no ray</td></tr>
     * <tr><td>{@code WalkerTickClimb} parkour-place</td><td>the real eye</td>
     *     <td>{@code PARKOUR_PLACE_REACH} = 4.0</td></tr>
     * </table>
     *
     * <p>The three short radii are margin bought on purpose, and buying margin on the way IN is
     * the safe direction: two of them pick a cell to WALK TO (the body will not be standing on
     * that centre when it arrives) and the third fires mid-leap off an already-stale eye. The two
     * that gate a LIVE interaction take the authority's number, because for those margin is not
     * safety — it is a false refusal.
     *
     * <p><b>What this table replaced</b> was a sixth answer that measured a different quantity:
     * the dig-claim release used {@code distToCenterSqr(p.position())}, i.e. from the FEET. See
     * {@link #eyeWithin} for why that is loose downward and tight upward rather than merely
     * imprecise.
     */
    public static Vec3 standingEye(BlockPos foot) {
        return new Vec3(foot.getX() + 0.5, foot.getY() + 1.62, foot.getZ() + 0.5);
    }

    /**
     * Is the centre of {@code block} within {@code reach} of {@code eye}? The one place that
     * decides "can this body operate on that cell", so the five call sites differ only in the
     * radius each passes — which is a visible number rather than a second opinion.
     *
     * <p><b>From the eye, never the feet.</b> The two are not the same measurement and swapping
     * them is not a rounding difference: a cell 5 BELOW the body is 5.0 from the feet but 6.6
     * from the eye, and a cell 5 ABOVE is 5.0 from the feet but only 3.4 from the eye. A
     * feet-based gate is therefore LOOSE downward and TIGHT upward — and tight-upward is exactly
     * the case {@code MineProcess.findReachStand} scans {@code dy} down to −5 to support (stand
     * under an overhead block and mine straight up, which its own comment calls "within the 4.5
     * reach" precisely because it measures from the eye).
     *
     * @param eye the eye position — {@code p.getEyePosition()} for a body that is already there,
     *            {@link #standingEye} for a candidate cell it has not walked to yet
     */
    public static boolean eyeWithin(Vec3 eye, BlockPos block, double reach) {
        return eye.distanceToSqr(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5)
                <= reach * reach;
    }

    /** {@link #eyeWithin(Vec3, BlockPos, double)} for a body that is already standing somewhere. */
    public static boolean eyeWithin(Player p, BlockPos block, double reach) {
        return eyeWithin(p.getEyePosition(), block, reach);
    }

    /**
     * The reach the GAME grants this body, measured to a block CENTRE — the number every
     * "can I still operate on that cell" gate should be comparing against.
     *
     * <p>{@code blockInteractionRange()} is the player's own attribute and is measured to the
     * nearest FACE; the half block converts it to the centre, erring outward so a gate never
     * rejects an interaction vanilla would allow. Lifted from
     * {@code ServerPlayerAvatar.canBreakFromHere}, which is where this repo first asked the game
     * instead of hardcoding a number.
     */
    public static double blockReachToCentre(Player p) {
        return p.blockInteractionRange() + 0.5;
    }

    // === Aiming (the process family) =========================================

    /**
     * Point the body's yaw, head, body and pitch at an exact world point.
     *
     * <p>The four rotation setters and the two {@code atan2} calls were copied into six process
     * classes; an aim that is written out by hand at every call site is how「瞄的是哪一点」quietly
     * comes to differ between two verbs that mean to do the same thing.
     *
     * <p><b>Not the same as {@code BotInteract.aimAtBlockSnap}, and they must not be merged.</b>
     * That one takes a {@code LocalPlayer} and additionally asks {@code LookController} to exempt
     * the tick from camera smoothing, because vanilla mining/interaction raycasts off the
     * CROSSHAIR and a lagged crosshair hits the wrong block. This one only sets the angles: its
     * callers act through {@code gameMode.useItemOn} with a hit result they computed themselves,
     * so the crosshair is not what decides, and it also has to work for a server-side {@code
     * Player} that has no client camera at all.
     */
    public static void aimAt(Player p, double tx, double ty, double tz) {
        Vec3 eye = p.getEyePosition();
        double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw);
        p.yHeadRot = yaw;
        p.yBodyRot = yaw;
        p.setXRot(pitch);
    }

    /**
     * The face of {@code block} pointing back at this body's eye — the side a ray from the eye
     * would land on, for callers that were not told which face to click.
     *
     * <p>The dominant axis of eye−centre wins, ties going to the earlier test ({@code y}, then
     * {@code x}); every {@code >=} below is load-bearing for a body standing exactly on an axis,
     * which is the common case for a bot that walks to a cell centre before interacting.
     *
     * <p><b>Here rather than in {@code BotInteract}, where it used to live alone.</b> Two
     * processes — {@code CraftProcess} and {@code SmeltProcess} — each carried a byte-identical
     * private copy under a javadoc explaining they had inlined it "to keep this process off the
     * client-only BotInteract so it loads on a dedicated server". <b>That reason was correct and
     * still is</b>: {@code BotInteract} names {@code Minecraft}, {@code LocalPlayer},
     * {@code KeyMapping} and {@code MultiPlayerGameMode}, and a dedicated server has none of them.
     * What was wrong was the conclusion that the only way out is a private copy each. This class
     * is where the process family's aiming already lives, and every one of those callers runs
     * under the dedicated-server gate today.
     *
     * <p>So: the parameter is {@link Player} and the body names no {@code net.minecraft.client}
     * type, not even as a local — that is the property that lets a server-side caller reach it,
     * and it is the property to preserve if this method ever grows.
     * {@code BotInteract.pickFaceTowardsPlayer} is now a one-line delegate, so its six
     * client-side callers are unchanged and there is still exactly one answer.
     */
    public static Direction faceTowardEye(BlockPos block, Player p) {
        Vec3 eye = p.getEyePosition();
        double dx = eye.x - (block.getX() + 0.5);
        double dy = eye.y - (block.getY() + 0.5);
        double dz = eye.z - (block.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx >= 0 ? Direction.EAST : Direction.WEST;
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Look at the centre of the face a placement clicks: {@code block} is where the new block is
     * to appear and {@code face} is the side of the supporting neighbour it grows off, so the
     * support sits opposite {@code face} and the point to aim at is half a block out from that
     * support's centre along {@code face}.
     */
    public static void aimAtSupportFace(Player p, BlockPos block, Direction face) {
        BlockPos support = block.offset(-face.getStepX(), -face.getStepY(), -face.getStepZ());
        aimAt(p, support.getX() + 0.5 + face.getStepX() * 0.5,
                 support.getY() + 0.5 + face.getStepY() * 0.5,
                 support.getZ() + 0.5 + face.getStepZ() * 0.5);
    }

    /**
     * The yaw that faces a cardinal direction, for the shaft-walking processes that steer by
     * setting a yaw and holding forward (bunker step-in, descend stair, escape climb-out).
     *
     * <p><b>Not {@link Direction#toYRot()}, on purpose.</b> That returns {@code 270f} for EAST
     * where this returns {@code -90f}. The two are the same angle and are NOT the same number,
     * and {@code setYRot} stores the number: rendering interpolates between the previous yaw and
     * this one by difference, so swapping {@code -90} for {@code 270} makes the body spin a full
     * turn where it used to snap. This body was identical in three processes; it was moved here
     * unchanged rather than replaced with the vanilla helper for exactly that reason.
     */
    public static float yawFor(Direction d) {
        return switch (d) {
            case SOUTH -> 0f;
            case WEST  -> 90f;
            case NORTH -> 180f;
            case EAST  -> -90f;
            default    -> 0f;
        };
    }
}
