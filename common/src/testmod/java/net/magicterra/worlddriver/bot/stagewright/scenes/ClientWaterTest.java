package net.magicterra.worlddriver.bot.stagewright.scenes;

import static net.magicterra.worlddriver.bot.stagewright.scenes.ClientWalk.at;
import static net.magicterra.worlddriver.bot.stagewright.scenes.ClientWalk.twoDecimals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.ClientRig;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Water walks driven on the client's REAL player — the navigator, the physics and the reflexes that actually ship.
 * Every test here is red until the engine can do the thing; none is softened to what the navigator can already satisfy.
 *
 * <p>Each arena is a flat slab with a pool cut INTO it (water piled above the surface flows away between staging and
 * measuring), the player in the water, and one walk to a cell on dry ground. Unit tests ({@code OpenWaterLakeTest},
 * {@code SubmergedLandingTest}, {@code WaterBoundPlanTest}) cover the plans; these cover their execution on real client
 * physics. Swimming and digging ashore are timed by the benchmark's swimming and dig-ashore courses; what stays here is
 * placing a way out of the water, which every course turns off, and the two-high dig-out.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class ClientWaterTest {

    /** Top of the slab: the ground's foot cell is {@code GROUND + 1}. */
    private static final int GROUND = 0;

    private static final int HALF = 12;

    private static final int POOL_HALF = 4;

    private static final int POOL_DEPTH = 6;

    private static final int SYNC_TICKS = 30;

    private static final int SETTLE_TICKS = 10;

    private static final int LEG_TICKS = 1_200;

    private static final int CHANNEL_HALF = 4;

    private static final int FLOW_SYNC_TICKS = 80;

    private static final int FLOW_DRY_BY = 200;

    /**
     * The rim stands ONE block above the water surface: vanilla's swim-out boost cannot mount it, so the player must
     * place a foothold (it holds dirt). Digging the dirt rim instead is not a failure of geometry but of judgment, and
     * the budget is what says so: a swim to the wall, thirty ticks of stall, one placement and one hop fit in 150
     * ticks; the shortest floating dig does not.
     */
    @Test
    void clientOneHighBankPlaceOut(
            Game game, Client client, Player player, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        climbOut(game, client, player, plot, 1, Blocks.DIRT, POOL_DEPTH, 150, new ItemStack(Items.DIRT, 30));
    }

    /**
     * Two above the surface: one foothold lifts the feet only to the rim's lower course, so the pillar has to keep
     * going up its own wall-supported column and top out with a walk. Two placements and their hops fit in 250 ticks.
     */
    @Test
    void clientTwoHighBankPlaceOut(
            Game game, Client client, Player player, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        climbOut(game, client, player, plot, 2, Blocks.DIRT, POOL_DEPTH, 250, new ItemStack(Items.DIRT, 30));
    }

    /**
     * Two above with nothing to place: a staircase of two dirt cells, each dug afloat. The budget is two floating dirt
     * digs plus the hops between them, nothing more.
     */
    @Test
    void clientTwoHighBankDigOut(
            Game game, Client client, Player player, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        climbOut(game, client, player, plot, 2, Blocks.DIRT, POOL_DEPTH, 900);
    }

    /**
     * A one-deep channel fed by a single source, so the water the player stands in is FLOWING toward the far end; the
     * east bank is one course above the water. The player holds dirt. Getting out means placing a block into a flowing
     * cell while the current pushes the player off its column, which is the river-edge shape the ladder's towers kept
     * losing blocks to.
     */
    @Test
    void clientFlowingChannelPlaceOut(
            Game game, Client client, Player player, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        int depth = 1;
        stageSlab(plot, 1, Blocks.DIRT);
        // Channel x∈[-1,1], z∈[-CHANNEL_HALF,CHANNEL_HALF-1]: floor at GROUND-depth, air above it up to GROUND+1, one
        // source row at the far end whose water runs along the floor.
        plot.fill(-1, GROUND + 1 - depth, -CHANNEL_HALF, 1, GROUND + 1, CHANNEL_HALF - 1, Blocks.AIR);
        plot.fill(-1, GROUND + 1 - depth, -CHANNEL_HALF, 1, GROUND + 1 - depth, -CHANNEL_HALF, Blocks.WATER);
        final int goalY = plot.at(0, GROUND + 2, 0).getY();
        BlockPos start = plot.at(0, GROUND + 1 - depth, 0);
        final BlockPos goal = plot.at(4, GROUND + 2, 0);

        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        rig.hold(new ItemStack(Items.DIRT, 30));
        rig.settings().set(shipped());
        // The source needs ~5 ticks per cell to reach the far end; wait for the current to exist.
        rig.sync(FLOW_SYNC_TICKS);
        Vec3 flow = game.onServer(s -> s.overworld().getFluidState(start).getFlow(s.overworld(), start));
        if (!ClientWalk.sample(rig).inWater()) {
            fail("test setup invalid: the player is not in water after the sync: " + rig.where());
        }
        if (flow.length() < 0.01) {
            fail("test setup invalid: there is no water flow at the foot cell: " + flow);
        }

        ClientWalk walk = new ClientWalk(rig);
        int[] inWater = {0};
        int[] firstDry = {-1};
        double[] maxDrift = {0};
        walk.go(Targets.block(goal), LEG_TICKS, SETTLE_TICKS, s -> {
            if (s.inWater()) {
                inWater[0]++;
            }
            // Ashore = standing at the bank's level. isInWater reads false in a shallow flowing layer far from its
            // source, so "not in water and on the ground" is true on the floor of the trench, where the goto starts.
            if (firstDry[0] < 0 && s.onGround() && !s.inWater() && s.y() >= goalY - 0.05) {
                firstDry[0] = walk.tick(s);
            }
            maxDrift[0] = Math.max(maxDrift[0], Math.abs(s.z() - (start.getZ() + 0.5)));
        });
        ClientWalk.Sample end = ClientWalk.sample(rig);
        String progress = inWater[0] + " ticks in water, first landed on dry ground at tick " + at(firstDry[0])
                + ", drifted at most " + twoDecimals(maxDrift[0]) + " blocks downstream; " + rig.where();
        game.check(
                end.onGround() && !end.inWater() && end.y() >= goalY - 0.05,
                "A: the player ends standing on dry ground: " + progress);
        game.check(
                end.flat(goal) <= 1.5,
                "B: stops within 1.5 blocks of the goal cell: horizontal distance " + twoDecimals(end.flat(goal)) + "; "
                        + progress);
        game.check(
                firstDry[0] >= 0 && firstDry[0] <= FLOW_DRY_BY,
                "C: gets ashore within " + FLOW_DRY_BY + " ticks: " + progress);
    }

    /**
     * A pool {@code poolDepth} deep cut into the slab, its rim {@code rimRaise} courses of {@code rim} above the water
     * surface, the player one cell under the surface where the pool allows it (so it starts wet either way), and one
     * walk to the bank; dry ground by tick {@code dryBy}. A one-deep pool stands the player on the floor with its eyes
     * out of the water, the shape the planner used to treat as a floor it could jump off.
     */
    private static void climbOut(
            Game game,
            Client client,
            Player player,
            Plot plot,
            int rimRaise,
            Block rim,
            int poolDepth,
            int dryBy,
            ItemStack... hand) {
        stageSlab(plot, rimRaise, rim);
        plot.fill(-POOL_HALF, GROUND - poolDepth + 1, -POOL_HALF, POOL_HALF, GROUND, POOL_HALF, Blocks.WATER);
        if (rimRaise > 0) {
            plot.fill(-POOL_HALF, GROUND + 1, -POOL_HALF, POOL_HALF, GROUND + rimRaise, POOL_HALF, Blocks.AIR);
        }
        BlockPos start = plot.at(0, GROUND - Math.min(poolDepth - 1, 1), 0);
        BlockPos goal = plot.at(POOL_HALF + 2, GROUND + 1 + rimRaise, 0);
        final int goalY = goal.getY();

        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        if (hand.length > 0) {
            rig.hold(hand);
        }
        rig.settings().set(shipped());
        rig.sync(SYNC_TICKS);
        if (!ClientWalk.sample(rig).inWater()) {
            fail("test setup invalid: the player is not in water after a " + SYNC_TICKS + "-tick sync: " + rig.where());
        }

        ClientWalk walk = new ClientWalk(rig);
        int[] inWater = {0};
        int[] firstDry = {-1};
        double[] maxY = {ClientWalk.sample(rig).y()};
        double[] minGap = {Double.MAX_VALUE};
        // Judged once the player has come to rest, and sampled until then: a goto that ends on the last hop of a
        // step-up is still in the air for a few ticks, and "arrived" is about where it lands.
        walk.go(Targets.block(goal), LEG_TICKS, SETTLE_TICKS, s -> {
            if (s.inWater()) {
                inWater[0]++;
            } else if (firstDry[0] < 0 && s.onGround()) {
                firstDry[0] = walk.tick(s);
            }
            maxY[0] = Math.max(maxY[0], s.y());
            minGap[0] = Math.min(minGap[0], s.flat(goal));
        });
        ClientWalk.Sample end = ClientWalk.sample(rig);
        String progress = inWater[0] + " ticks in water, first landed on dry ground at tick " + at(firstDry[0])
                + ", highest y=" + twoDecimals(maxY[0]) + ", closest approach to the goal " + twoDecimals(minGap[0])
                + " blocks; "
                + rig.where();
        game.check(
                end.onGround() && !end.inWater() && end.y() >= goalY - 0.05,
                "A: the player ends standing on dry " + "ground (onGround, not in water, feet at bank foot level): "
                        + progress);
        game.check(
                end.flat(goal) <= 1.5,
                "B: the player stops within 1.5 blocks of the goal cell: horizontal distance "
                        + twoDecimals(end.flat(goal)) + "; " + progress);
        game.check(firstDry[0] >= 0 && firstDry[0] <= dryBy, "C: gets ashore within " + dryBy + " ticks: " + progress);
    }

    /**
     * The shipped water-exit defaults the pinned baseline turns off; each test here is what notices one going missing.
     */
    private static Map<String, Object> shipped() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("allowBreak", true);
        s.put("allowPlace", true);
        s.put("navigatorDebug", true);
        s.put("navigatorFootholdBeforeBankDig", true);
        s.put("navigatorClimbIntentFromSurface", true);
        s.put("navigatorPillarTopsOutAtFlushExit", true);
        s.put("navigatorPillarFacesTheBank", true);
        s.put("navigatorPillarWaitsBesideTheBank", true);
        s.put("navigatorHoldLastNodeUntilStanding", true);
        s.put("navigatorShallowWaterSideFoothold", true);
        s.put("navigatorClimbOutResyncsAim", true);
        s.put("navigatorOrbitBreaksAimLag", true);
        s.put("navigatorSurfaceSprintSwim", true);
        return s;
    }

    /** Stone from {@code GROUND - 8} to {@code GROUND}, then {@code rimRaise} courses of {@code rim}, air above. */
    private static void stageSlab(Plot plot, int rimRaise, Block rim) {
        plot.fill(-HALF, GROUND - POOL_DEPTH - 2, -HALF, HALF, GROUND, HALF, Blocks.STONE);
        if (rimRaise > 0) {
            plot.fill(-HALF, GROUND + 1, -HALF, HALF, GROUND + rimRaise, HALF, rim);
        }
    }
}
