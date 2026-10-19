package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.bot.RouteContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveApi;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.bot.arbitration.Chain;
import net.magicterra.worlddriver.bot.arbitration.DrownEscapeChain;
import net.magicterra.worlddriver.bot.arbitration.Priorities;
import net.magicterra.worlddriver.bot.arbitration.ProcessScheduler;
import net.magicterra.worlddriver.bot.PlayerBot;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Dogfooded worlddriver scenes — the Survival family: escape / bunker shelter / low-HP flee /
 * surface-dive / underwater-base traverse / drowning-escape / anti-suffocate / air-supply
 * observation.
 *
 * <p><b>Seven drive real tasks on a roster bot</b> ({@code wd.serverEscape} / {@code wd.serverBunker} /
 * {@code wd.serverEscapeSealedShelter} / {@code wd.serverLowHpEdgePin} / {@code wd.serverBunkerSlope},
 * plus the dive pair below) through {@code mc.bot.escape} / {@code bunker} / {@code runAway} /
 * {@code goto}, ticked in full by the scene ({@link SceneBot#tickUntilIdle}). Absolute {@code floorY=220}
 * is {@code origin.y + 20} and {@code floorY=200} is {@code origin.y}. Every scene clears the other bots
 * first and registers {@code ctx.cleanup} to drain every dug and placed block in its footprint (LIFO
 * air-scrub) — the persistent-world lesson.
 *
 * <p>{@code wd.antiSuffocateWaterNotSuffocating} plants three real blocks and reads {@code mc.debug.cells}'
 * {@code suffocates} for each; {@code wd.serverObserveAirSupply} reads {@code mc.observe.player}'s air for
 * the bot. {@code wd.drownEscapePreempt} still wires the real {@link DrownEscapeChain} and a placeholder
 * user chain into a {@link ProcessScheduler} by hand over a minted player — no api call builds a scheduler
 * around a stand-in chain. The pure state machines and gate tables that used to run here as scenes
 * (bunker anchor, drowning float, drowning escape, contact escape) are unit tests in {@code common/src/test}.
 *
 * <p><b>{@code wd.underwaterBase}</b> is the documented legacy HANG RECIDIVIST. Its actuation is
 * BOUNDED (planner {@code sliceMs=1} pinned tiny, node-budget-deterministic; executor capped at 600
 * ticks; ⛔ no {@code level.tick()}), so the historical hang — the {@code ChunkMap.processUnloads}
 * world-pollution recidivist that afflicts the persistent {@code run-gametest} world after a killed
 * run — is NOT inherent to this test's own actuation. It is ported (with the extra ×3 neoforge
 * dogfood validation the wave brief requires for this scene) rather than escape-hatched; its cleanup
 * air-scrubs the whole tank + chamber so no water leaks into a neighbouring slot. ⛔ No Survival scene
 * calls {@code level.tick()} (this family has none — the re-entrant {@code level.tick()} mines are all
 * in the Process wave, P4c Task 4).
 */
public final class WorldDriverSurvivalScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.serverEscape", 400, WorldDriverSurvivalScenes::serverEscapeScene),
                Scene.of("wd.serverBunker", 400, WorldDriverSurvivalScenes::serverBunkerScene),
                // Optional as of the break reach gate. The carve aims at the EXIT (y=224 from a
                // bot at y=221) instead of at the next block up, and that only ever worked
                // because Level#destroyBlock let the avatar mine through the two courses in
                // between. wd.serverBreakNeedsReach forbids it now.
                //
                // wd.buriedOre and wd.serverMineHarvestBuried went red the same way and are
                // required again, because MineProcess learned to peel one reachable block at a
                // time (MineProcess#firstBreakableToward). This escape runs a DIFFERENT digger and
                // has not learned it yet — that is the whole of the remaining gap, and it is the
                // change that promotes this back. Escaping a sealed shelter stays required
                // behaviour; what is optional is the claim that it works today.
                Scene.of("wd.serverEscapeSealedShelter", 400, WorldDriverSurvivalScenes::serverEscapeSealedShelterScene)
                        .withRequired(false),
                Scene.of("wd.serverLowHpEdgePin", 400, WorldDriverSurvivalScenes::serverLowHpEdgePinScene),
                Scene.of("wd.serverBunkerSlope", 400, WorldDriverSurvivalScenes::serverBunkerSlopeScene),
                Scene.of("wd.surfaceDive", 400, WorldDriverSurvivalScenes::surfaceDiveScene),
                Scene.of("wd.underwaterBase", 400, WorldDriverSurvivalScenes::underwaterBaseScene),
                Scene.of("wd.drownEscapePreempt", 400, WorldDriverSurvivalScenes::drownEscapePreemptScene),
                Scene.of("wd.serverObserveAirSupply", 200, WorldDriverSurvivalScenes::serverObserveAirSupplyScene),
                Scene.of("wd.antiSuffocateWaterNotSuffocating", 200, WorldDriverSurvivalScenes::antiSuffocateWaterNotSuffocatingScene),
                Scene.of("wd.serverAntiSuffocateBreaksTheHeadBlock", 200, ctx -> serverAntiSuffocateScene(ctx, true)),
                Scene.of("wd.serverAntiSuffocateDisarmedStaysBuried", 200, ctx -> serverAntiSuffocateScene(ctx, false)));
    }

    // ==================================================================================
    // wd.serverEscape — real EscapeProcess climbs out of a 1-wide stone pit.
    // ==================================================================================

    private static void serverEscapeScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -5; dx <= 5; dx++)
                for (int dy = -1; dy <= 5; dy++)
                    for (int dz = -5; dz <= 5; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Solid stone block floorY..floorY+3 (top surface = floorY+3, stand = floorY+4).
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++)
                for (int dy = 0; dy <= 3; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
        // Carve the 1-wide pit at the centre: air floorY+1..floorY+3, bot stands on floorY.
        for (int dy = 1; dy <= 3; dy++)
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz), Blocks.AIR.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));   // VERT_RISE fallback
        bot.escape(floorY + 4);
        bot.tickUntilIdle(800);

        boolean climbed = fp.getY() >= floorY + 3.0;   // up from the floorY+1 pit bottom to ~the rim
        boolean busy = bot.busy();
        int active = Harness.busy();
        TestMod.LOG.info("[wd.serverEscape] pos=({},{},{}) climbed={} busy={} active={}",
                fp.getX(), fp.getY(), fp.getZ(), climbed, busy, active);
        if (busy || active != 0)
            ctx.fail("server EscapeProcess did not finish+unregister: active="
                    + active + " y=" + fp.getY());
        if (!climbed)
            ctx.fail("server EscapeProcess did not climb out: y=" + fp.getY());
    }

    // ==================================================================================
    // wd.serverBunker — real BunkerProcess seals a dig-three, fill-one shelter shaft.
    // ==================================================================================

    private static void serverBunkerScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = -4; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Solid dirt block floorY-3..floorY+1 to dig into; carve the bot's 1×2 standing slot.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -3; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 2, cz), Blocks.AIR.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));   // server breaks drop nothing → pre-stock plug blocks
        bot.bunker(2);
        // The bunker holds at SEALED (never ends on its own), so the bot stays busy —
        // run a fixed window then inspect the world.
        bot.tickUntilIdle(800);

        // The shaft column the bot dug (down from floorY) must be plugged solid.
        int sealed = 0, shaftCells = 0;
        for (int dy = 0; dy >= -2; dy--) {
            shaftCells++;
            if (!level.getBlockState(new BlockPos(cx, floorY + dy, cz)).isAir()) sealed++;
        }
        TestMod.LOG.info("[wd.serverBunker] pos=({},{},{}) sealed={}/{} active={}",
                fp.getX(), fp.getY(), fp.getZ(), sealed, shaftCells, Harness.busy());
        if (sealed < shaftCells)
            ctx.fail("server BunkerProcess left the shaft open: sealed=" + sealed + "/" + shaftCells);
    }

    // ==================================================================================
    // wd.serverEscapeSealedShelter — real EscapeProcess carves out of a sealed 1×2 pocket.
    // ==================================================================================

    private static void serverEscapeSealedShelterScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -6; dx <= 6; dx++)
                for (int dy = -1; dy <= 7; dy++)
                    for (int dz = -6; dz <= 6; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Solid dirt mass floorY..floorY+5 (surface stand = floorY+6), wide enough for the
        // 5-step diagonal staircase out of the centre pocket.
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++)
                for (int dy = 0; dy <= 5; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
        // The sealed 1×2 pocket: air foot+head only — the roof above stays solid.
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 2, cz), Blocks.AIR.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", false);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));   // VERT_RISE fallback
        bot.escape(floorY + 6);
        bot.tickUntilIdle(800);

        boolean climbed = fp.getY() >= floorY + 5.0;
        String slotErr = bot.status().slots().escape().lastError().orElse(null);
        boolean busy = bot.busy();
        int active = Harness.busy();
        TestMod.LOG.info("[wd.serverEscapeSealedShelter] pos=({},{},{}) climbed={} busy={} active={} slotErr={}",
                fp.getX(), fp.getY(), fp.getZ(), climbed, busy, active, slotErr);
        if (busy || active != 0)
            ctx.fail("sealed-shelter escape did not finish+unregister (old STEP_UP ping-pong?): active="
                    + active + " y=" + fp.getY());
        if (!climbed)
            ctx.fail("sealed-shelter escape did not reach the surface: y=" + fp.getY() + " slotErr=" + slotErr);
        if (slotErr != null)
            ctx.fail("escape slot reports an error after a successful climb: " + slotErr);
    }

    // ==================================================================================
    // wd.serverLowHpEdgePin — real RunAwayProcess, 2-HP lethal-edge discipline (DEATH #3).
    // ==================================================================================

    private static void serverLowHpEdgePinScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -6; dx <= 18; dx++)
                for (int dy = 0; dy <= 16; dy++)
                    for (int dz = -6; dz <= 6; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Broad base slab — the lethal landing zone under the lip.
        for (int dx = -6; dx <= 18; dx++)
            for (int dz = -6; dz <= 6; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // Elevated runway heading +x: flat top floorY+12 (dx -2..6), a 2-step planned descent
        // (dx 7 → +11, dx 8 → +10), a 2-cell flat (dx 9..10 at +10), then the lethal 10-block
        // lip (dx ≥ 11 is open air down to the base slab).
        for (int dx = -2; dx <= 10; dx++) {
            int top = dx <= 6 ? 12 : dx == 7 ? 11 : 10;
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = 8; dy <= top; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
            // Side walls channel the flee along +x so the only route is the staircase.
            for (int dy = top + 1; dy <= top + 3; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz - 2), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + 2), Blocks.STONE.defaultBlockState());
            }
        }
        BlockPos from = new BlockPos(cx - 2, floorY + 13, cz);
        final int minDist = 10;   // satisfied at dx ≥ 8, right after the planned descent

        Harness.set("navigatorDebug", false);
        Harness.set("lowHealthCareful", 6.0);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 13, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.setHealth(2.0f);
        // Hungry enough that natural regeneration stays off (FoodData.tick heals a hurt player only
        // at food >= 18) and fed enough to sprint (> 6). The player runs FoodData.tick every step, and
        // at full food it would heal past lowHealthCareful long before the flee ends, switching off
        // the careful walk this scene is about.
        fp.getFoodData().setFoodLevel(17);
        bot.runAway(from, minDist);
        bot.tickUntilIdle(800);

        double ddx = fp.getX() - (cx - 2 + 0.5), ddz = fp.getZ() - (cz + 0.5);
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);
        boolean busy = bot.busy();
        int active = Harness.busy();
        TestMod.LOG.info("[wd.serverLowHpEdgePin] pos=({},{},{}) dist={} hp={} busy={} active={}",
                fp.getX(), fp.getY(), fp.getZ(), dist, fp.getHealth(), busy, active);
        if (fp.getHealth() < 2.0f)
            ctx.fail("low-HP flee took damage (fell off the planned descent / lip): hp="
                    + fp.getHealth() + " y=" + fp.getY());
        if (fp.getY() < floorY + 9.0)
            ctx.fail("low-HP flee left the elevated runway (fell): y=" + fp.getY());
        if (dist < minDist - 0.5)
            ctx.fail("low-HP flee did not reach min distance: dist=" + dist + " (need " + minDist + ")");
        if (busy || active != 0)
            ctx.fail("low-HP flee did not finish+unregister: active=" + active);
    }

    // ==================================================================================
    // wd.serverBunkerSlope — real BunkerProcess must enclose the niche, not punch a cliff
    // face (death#2 enclosure regression).
    // ==================================================================================

    private static void serverBunkerSlopeScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -4; dx <= 4; dx++)
                for (int dy = -5; dy <= 4; dy++)
                    for (int dz = -4; dz <= 4; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = -4; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
        // Standing slot at the centre.
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 2, cz), Blocks.AIR.defaultBlockState());
        // Open cliff face: everything at dz <= -2 is AIR — a NORTH niche at any depth this
        // arena reaches would open onto it through a 1-thick wall.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= -2; dz++)
                for (int dy = -4; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        bot.bunker(2);
        bot.tickUntilIdle(1200);

        BlockPos foot = fp.blockPosition();
        BlockPos head = foot.above();
        int openLateral = 0;
        StringBuilder open = new StringBuilder();
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            for (BlockPos cell : new BlockPos[]{foot.relative(d), head.relative(d)}) {
                if (level.getBlockState(cell).isAir()) { openLateral++; open.append(cell).append(' '); }
            }
        }
        boolean roofOpen = level.getBlockState(head.above()).isAir();
        TestMod.LOG.info("[wd.serverBunkerSlope] pos={} openLateral={} roofOpen={} open=[{}]",
                foot, openLateral, roofOpen, open);
        if (openLateral > 0 || roofOpen)
            ctx.fail("bunker pocket not enclosed: openLateral=" + openLateral + " roofOpen=" + roofOpen + " cells=" + open);
    }

    // ==================================================================================
    // wd.surfaceDive — A5 opt-in SurfaceDive: planner emits swimDownSurface + executor lands.
    // ==================================================================================

    private static void surfaceDiveScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // Ground-anchored: the scene origin IS the legacy per-test placement anchor.
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();
        final int depth = 8;                       // interior water depth
        final int surfaceY = floorY + depth;        // top water layer

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int y = floorY - 2; y <= surfaceY + 2; y++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Defensive full clear first.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY - 2; y <= surfaceY + 2; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // TWO-LAYER sealed bottom (void below the anchor).
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - 1, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // Stone shell walls (|dx|==2 or |dz|==2), floor+1 .. surface+1; 3x3 interior water core.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int y = floorY + 1; y <= surfaceY + 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState()
                                 : (y <= surfaceY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState()));
            }
        BlockPos bottomCell = new BlockPos(cx, floorY + 1, cz);   // first water cell ON the sealed floor
        GotoContracts.Target goal = Targets.near(bottomCell, 1);

        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        // The bot starts IN the water AT the surface — surfaceY-1: only the new opt-in
        // SurfaceDive can start the descent (SwimDown is isSubmergedFoot-gated).
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surfaceY - 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        BlockPos start = fp.blockPosition();

        // PLANNER-ONLY proof: the plan reaches the floor and its edge list actually contains
        // a swimDownSurface edge (not just SOME reachable route).
        DebugContracts.SinglePlan plan = divePlan(bot, goal);
        boolean hasSurfaceDive = hasSurfaceDive(plan);
        TestMod.LOG.info(
                "[wd.surfaceDive] plan.goalReached={} hasSurfaceDive={} start=({},{},{}) bottomCell={}",
                plan.goalReached(), hasSurfaceDive, start.getX(), start.getY(), start.getZ(), bottomCell);
        if (!plan.goalReached())
            ctx.fail("planner: dive-enabled plan did NOT reach the underwater goal");
        if (!hasSurfaceDive)
            ctx.fail("planner: dive-enabled plan reached the goal WITHOUT a swimDownSurface edge — "
                    + "the new opt-in move never fired");

        // EXECUTOR proof: the SAME route, walked for real.
        bot.goTo(goal, DIVE_ROUTE);
        bot.tickUntilIdle(600);

        double ddx = fp.getX() - (bottomCell.getX() + 0.5);
        double ddy = fp.getY() - bottomCell.getY();
        double ddz = fp.getZ() - (bottomCell.getZ() + 0.5);
        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        boolean busy = bot.busy();
        int active = Harness.busy();
        TestMod.LOG.info(
                "[wd.surfaceDive] pos=({},{},{}) busy={} active={} dist={}",
                fp.getX(), fp.getY(), fp.getZ(), busy, active, dist);
        if (busy || active != 0)
            ctx.fail("executor: dive process did not finish+unregister within 600t: "
                    + "busy=" + busy + " active=" + active);
        if (dist > 2.0)
            ctx.fail("executor: bot did not land within 2 of the underwater goal: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") dist=" + dist);
    }

    // ==================================================================================
    // wd.underwaterBase — the full "swim back to the underwater base" dive + submerged traverse
    // into an air-pocket chamber. LEGACY HANG RECIDIVIST — bounded actuation, ported with ×3 validation.
    // ==================================================================================

    private static void underwaterBaseScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();
        final int depth = 8;
        final int surfaceY = floorY + depth;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        // Scrub the whole tank + chamber (all water + stone → air) so nothing leaks between runs.
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 8; dx++)
                for (int y = floorY - 2; y <= surfaceY + 2; y++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Defensive clear, covering tank + chamber.
        for (int dx = -3; dx <= 8; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int y = floorY - 2; y <= surfaceY + 2; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        // TWO-LAYER sealed floor under the tank AND the chamber.
        for (int dx = -2; dx <= 7; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - 1, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        // Tank: stone shell, 3x3 water core floorY+1..surfaceY, rim one above the water.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                for (int y = floorY + 1; y <= surfaceY + 1; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            wall ? Blocks.STONE.defaultBlockState()
                                 : (y <= surfaceY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState()));
            }
        // Chamber shell east of the tank: east wall at dx=6, side walls dz=±2 over dx 3..5,
        // ceiling at floorY+5 — the tank's own east wall (dx=2) is the chamber's west wall.
        for (int dz = -2; dz <= 2; dz++)
            for (int y = floorY + 1; y <= floorY + 5; y++)
                level.setBlockAndUpdate(new BlockPos(cx + 6, y, cz + dz), Blocks.STONE.defaultBlockState());
        for (int dx = 3; dx <= 5; dx++)
            for (int y = floorY + 1; y <= floorY + 5; y++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz - 2), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + 2), Blocks.STONE.defaultBlockState());
            }
        for (int dx = 3; dx <= 5; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 5, cz + dz), Blocks.STONE.defaultBlockState());
        // Chamber interior: water ONLY at the doorway levels (floorY+1..+2), trapped air above.
        for (int dx = 3; dx <= 5; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = floorY + 1; y <= floorY + 2; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.WATER.defaultBlockState());
        // Doorway: carve the tank's east wall (dx=2) open at the bottom two water levels, full 3-wide.
        for (int dz = -1; dz <= 1; dz++)
            for (int y = floorY + 1; y <= floorY + 2; y++)
                level.setBlockAndUpdate(new BlockPos(cx + 2, y, cz + dz), Blocks.WATER.defaultBlockState());
        // The stand cell is where the bot ends up; the GOAL targets the AIR cell two above it.
        BlockPos standCell = new BlockPos(cx + 4, floorY + 1, cz);
        BlockPos goalCell = new BlockPos(cx + 4, floorY + 3, cz);   // AIR-pocket cell above the water
        GotoContracts.Target goal = Targets.near(goalCell, 2);

        Harness.set("navigatorDebug", false);
        // SMALL slice (NOT the usual unbounded pin): the executor's search must take MANY TICKS
        // to resolve so the arena reproduces the live PRE-PATH RACE. maxMs stays unbounded so
        // the search itself is still node-budget-deterministic.
        Harness.untimed(ctx);
        Harness.set("pathfinder.sliceMs", 1);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, surfaceY - 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        SimProbes.grantWaterEffects(fp);
        BlockPos start = fp.blockPosition();

        // Planner precheck: reachable, and still via a surface dive.
        DebugContracts.SinglePlan plan = divePlan(bot, goal);
        boolean hasSurfaceDive = hasSurfaceDive(plan);
        long finalCost = plan.search().finalCost();
        TestMod.LOG.info(
                "[wd.underwaterBase] plan.goalReached={} hasSurfaceDive={} finalCost={} start=({},{},{}) goalCell={}",
                plan.goalReached(), hasSurfaceDive, finalCost,
                start.getX(), start.getY(), start.getZ(), goalCell);
        if (!plan.goalReached())
            ctx.fail("planner: dive+traverse plan did NOT reach the chamber goal");
        if (!hasSurfaceDive)
            ctx.fail("planner: plan reached the chamber WITHOUT a swimDownSurface edge");
        // Dive water-tax relief gate: 640 (≈ 16 cells × 40) cleanly separates the relieved
        // route (~350) from the un-relieved one (past ~800).
        if (finalCost >= 640)
            ctx.fail("planner: dive plan finalCost=" + finalCost
                    + " ≥ 640 — the DIVE opt-in water-tax relief is not engaging (the un-relieved"
                    + " taxes starve the open-world live search into an overland dead-end)");

        bot.goTo(goal, DIVE_ROUTE);
        bot.tickUntilIdle(600);

        // Position asserts measure against the STAND cell (the water cell on the chamber floor).
        double ddx = fp.getX() - (standCell.getX() + 0.5);
        double ddy = fp.getY() - standCell.getY();
        double ddz = fp.getZ() - (standCell.getZ() + 0.5);
        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        boolean insideChamber = fp.getX() > cx + 2.5;   // past the tank's east wall plane
        boolean busy = bot.busy();
        int active = Harness.busy();
        TestMod.LOG.info(
                "[wd.underwaterBase] pos=({},{},{}) busy={} active={} dist={} insideChamber={}",
                fp.getX(), fp.getY(), fp.getZ(), busy, active, dist, insideChamber);
        if (busy || active != 0)
            ctx.fail("executor: dive+traverse process did not finish+unregister within 600t: busy="
                    + busy + " active=" + active
                    + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
        if (!insideChamber || dist > 2.0)
            ctx.fail("executor: bot did not end INSIDE the chamber near the goal: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") dist=" + dist
                    + " insideChamber=" + insideChamber);
    }

    /** The dive the two dive scenes plan and walk: diving opted in, never breaking, nothing else. Bold, so no
     *  mob tax rides along whatever {@code avoidMobs} says — the search profile is exactly dive + no-break. */
    private static final RouteContracts.Route DIVE_ROUTE = new RouteContracts.Route(List.of(),
            Optional.of(List.of(RouteContracts.TravelMode.DIVE)), RouteContracts.BreakPolicy.NEVER,
            RouteContracts.PlacePolicy.ALLOW, true, RouteContracts.Risk.BOLD, Optional.empty(), Optional.empty(),
            Optional.empty(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());

    /** One read-only search from where {@code bot} stands to {@code goal} along {@link #DIVE_ROUTE}, at the
     *  default node and time budgets. */
    private static DebugContracts.SinglePlan divePlan(SceneBot bot, GotoContracts.Target goal) {
        return bot.plan(new DebugContracts.PlanParams(Optional.of(bot.ref()), goal, Optional.empty(),
                Optional.of(DIVE_ROUTE), false, 40, Optional.empty(), Optional.empty()));
    }

    private static boolean hasSurfaceDive(DebugContracts.SinglePlan plan) {
        for (DebugContracts.PlanEdge e : plan.edges())
            if (e.move().map("swimDownSurface"::equals).orElse(false)) return true;
        return false;
    }

    // ==================================================================================
    // wd.drownEscapePreempt — REAL DrownEscapeChain + ProcessScheduler preempts an active
    // "user" chain in a flooded 1×1 shaft and floats the bot up (gap#76, live death #25).
    // ==================================================================================

    private static void drownEscapePreemptScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();
        final int depth = 6;                     // water column floorY+1 .. floorY+depth
        boolean oEnabled = Harness.flag("autoDrownEscape");
        int oEnter = Harness.integer("drownEscapeAirThreshold"), oRelease = Harness.integer("drownEscapeReleaseAir");
        Harness.set("autoDrownEscape", true);
        Harness.set("drownEscapeAirThreshold", 100);
        Harness.set("drownEscapeReleaseAir", 280);
        ctx.cleanup(() -> {
            Harness.set("autoDrownEscape", oEnabled);
            Harness.set("drownEscapeAirThreshold", oEnter);
            Harness.set("drownEscapeReleaseAir", oRelease);
        });
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -2; dx <= 2; dx++)
                for (int y = floorY - 1; y <= floorY + depth + 4; y++)
                    for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Defensive clear + sealed 2-layer floor + stone-shelled 1×1 water shaft, open at the top.
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int y = floorY + 1; y <= floorY + depth + 4; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY - 1, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int y = floorY + 1; y <= floorY + depth; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            (dx == 0 && dz == 0 ? Blocks.WATER : Blocks.STONE).defaultBlockState());

        PlayerBot driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();

        DrownEscapeChain drown = new DrownEscapeChain();
        drown.sensorForTest(fp::isUnderWater, fp::getAirSupply);
        final int[] userInterrupts = new int[1];
        Chain user = new Chain() {           // the "active process" placeholder (death-#25 mine shape)
            @Override public String name() { return "user"; }
            @Override public float priority(Body body, WorldView w, BotState st) { return Priorities.USER; }
            @Override public void tick(Body body, WorldView w, BotState st) { /* keeps digging */ }
            @Override public void onInterrupt(Body body, Chain by) { userInterrupts[0]++; }
        };
        ProcessScheduler sched = new ProcessScheduler();
        sched.register(drown);
        sched.register(user);
        BotState st = new BotState();

        int airSim = 150;
        fp.setAirSupply(airSim);
        List<String> flips = new ArrayList<>();
        String prevChain = "";
        int preemptTick = -1, releaseTick = -1, airAtPreempt = -1, minAir = Integer.MAX_VALUE;
        double yAtPreempt = -1, airAtRelease = -1;
        boolean underAtRelease = true;
        for (int t = 0; t < 1200 && releaseTick < 0; t++) {
            sched.tick(null, driver.world(), st);
            String cur = String.valueOf(sched.currentName());
            if (!cur.equals(prevChain)) { flips.add(cur + "@t" + t); prevChain = cur; }
            boolean escape = DrownEscapeChain.NAME.equals(sched.currentName());
            if (escape && preemptTick < 0) {
                preemptTick = t; airAtPreempt = fp.getAirSupply(); yAtPreempt = fp.getY();
            }
            if (preemptTick >= 0 && !escape && releaseTick < 0) {
                releaseTick = t; airAtRelease = fp.getAirSupply(); underAtRelease = fp.isUnderWater();
            }
            // Actuation mirror of DrownEscapeChain.tick's client keyJump hold.
            driver.avatar().commandJump(escape);
            driver.avatar().step();
            boolean eyeInWater = fp.isEyeInFluid(FluidTags.WATER);
            airSim = eyeInWater ? Math.max(airSim - 1, -20) : Math.min(airSim + 4, 300);
            fp.setAirSupply(airSim);
            minAir = Math.min(minAir, airSim);
        }
        TestMod.LOG.info(
                "[wd.drownEscapePreempt] flips={} preempt@t{} (air={}) release@t{} (air={} under={}) minAir={} "
                        + "y {}→{} interrupts={} phase={}",
                flips, preemptTick, airAtPreempt, releaseTick, airAtRelease, underAtRelease, minAir,
                yAtPreempt, fp.getY(), userInterrupts[0], drown.episodePhase());
        if (!flips.isEmpty() && !flips.get(0).startsWith("user@"))
            ctx.fail("gap#76: user chain should hold the channel while air is healthy: " + flips);
        if (preemptTick < 0)
            ctx.fail("gap#76 (live death #25): DrownEscapeChain NEVER preempted the active process while "
                    + "the bot drowned — minAir=" + minAir + " flips=" + flips);
        if (airAtPreempt > Harness.integer("drownEscapeAirThreshold"))
            ctx.fail("gap#76: preempted too early, air=" + airAtPreempt + " > threshold " + Harness.integer("drownEscapeAirThreshold"));
        if (userInterrupts[0] < 1)
            ctx.fail("gap#76: preemption must interrupt the user chain (onInterrupt)");
        if (minAir <= 0)
            ctx.fail("gap#76: air hit " + minAir + " — the escape did not beat the drown clock (death #25 shape)");
        if (releaseTick < 0)
            ctx.fail("gap#76: chain never released the channel after surfacing — hysteresis release broken (air="
                    + fp.getAirSupply() + " under=" + fp.isUnderWater() + ")");
        if (fp.getY() < yAtPreempt + 2.0)
            ctx.fail("gap#76: bot did not float up: y " + yAtPreempt + " → " + fp.getY());
        if (underAtRelease && airAtRelease < Harness.integer("drownEscapeReleaseAir"))
            ctx.fail("gap#76: released while still underwater below the release level (air=" + airAtRelease
                    + ") — hysteresis violated");
        if (!"user".equals(sched.currentName()))
            ctx.fail("gap#76: channel not handed back to the user task after release: " + sched.currentName());
        if (drown.episodePhase() != null)
            ctx.fail("gap#76: episode must clear on release: " + drown.episodePhase());
        if (flips.size() != 3)
            ctx.fail("gap#76: expected exactly user→drownEscape→user, no oscillation: " + flips);
    }

    // ==================================================================================
    // wd.serverObserveAirSupply — gap#70 observe.player carries air / maxAir over a FakePlayer.
    // ==================================================================================

    private static void serverObserveAirSupplyScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState()));

        Harness.set("navigatorDebug", false);
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.setAirSupply(42);

        // The bot's player by its profile name, as an agent asks for any player on the server.
        ObserveContracts.PlayerReading reading = Calls.now(ObserveApi.of(Calls.api()).player(
                new ObserveContracts.PlayerParams(Optional.of(fp.getGameProfile().getName()))));
        if (reading.player().isEmpty())
            ctx.fail("gap#70: mc.observe.player found no player named " + fp.getGameProfile().getName());
        ObserveContracts.Vitals vitals = reading.player().get().vitals();
        TestMod.LOG.info("[wd.serverObserveAirSupply] air={} maxAir={}", vitals.air(), vitals.maxAir());
        if (vitals.air() != 42)
            ctx.fail("gap#70: observe.player reports the wrong `air` — got " + vitals.air()
                    + ", wanted 42 (fp.setAirSupply(42))");
        if (vitals.maxAir() != fp.getMaxAirSupply())
            ctx.fail("gap#70: observe.player reports the wrong `maxAir` — got " + vitals.maxAir());
    }

    // ==================================================================================
    // wd.antiSuffocateWaterNotSuffocating — gap#80 AntiSuffocateGate.suffocates: stone yes,
    // water/air no (drowning is not suffocation). Plants three real blocks. 3 rows.
    // ==================================================================================

    // gap#80: AntiSuffocate#resolveHead's eligibility test must be vanilla's own
    // BlockState#isSuffocating (returns false for water — empty collision shape — but true for
    // stone), factored into AntiSuffocateGate#suffocates and read here through mc.debug.cells'
    // `suffocates` over three real blocks (AntiSuffocate.resolveHead itself needs a live client tick).
    private static void antiSuffocateWaterNotSuffocatingScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD)
            ctx.fail("mc.debug.cells without a bot reads the overworld, and this scene's level is " + level.dimension());
        BlockPos anchor = ctx.origin();
        BlockPos stone = anchor.above(1);
        BlockPos water = anchor.above(2);
        BlockPos air = anchor.above(3);
        ctx.cleanup(() -> {
            level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(water, Blocks.AIR.defaultBlockState());
        });
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(air, Blocks.AIR.defaultBlockState());
        List<BotDebugContracts.Cell> cells = Calls.now(DebugApi.of(Calls.api()).cells(new BotDebugContracts.CellsParams(
                Optional.empty(), List.of(Targets.pos(stone), Targets.pos(water), Targets.pos(air)), Optional.empty())))
                .cells();
        if (!cells.get(0).suffocates())
            ctx.fail("gap#80(a): an ordinary solid block (stone) must read as suffocating");
        if (cells.get(1).suffocates())
            ctx.fail("gap#80(b): WATER must NOT read as suffocating (drowning, not suffocation) — "
                    + "the live bug: resolveHead's old !isAir() check treated water as a valid break target");
        if (cells.get(2).suffocates())
            ctx.fail("gap#80(c): AIR must NOT read as suffocating (baseline sanity)");
    }

    /** Ticks the reflex gets to clear the head cell: a server-side break is instant, so one tick
     *  would do; the rest is room for the trigger, which reads the damage the wall deals. */
    private static final int SUFFOCATE_BUDGET = 60;

    /**
     * wd.serverAntiSuffocateBreaksTheHeadBlock / wd.serverAntiSuffocateDisarmedStaysBuried — an
     * idle server bot with a dirt block in its head cell, registered on {@link BotTicker} with no
     * task. Armed, its own brain's {@code AntiSuffocate} must break the block through the server
     * body's {@code continueDestroy}, the only call that reflex makes; disarmed, the block must stay,
     * which is what says the armed arm's clearing was the reflex and nothing else.
     */
    private static void serverAntiSuffocateScene(SceneContext ctx, boolean armed) {
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin();
        BlockPos foot = o.above(2);
        BlockPos head = foot.above();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(o.offset(dx, 1, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 2; dy <= 4; dy++)
                    level.setBlockAndUpdate(o.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
        Harness.pin(ctx);
        Harness.set("antiSuffocate", armed);
        Harness.set("allowBreak", true);
        Harness.clearBots(ctx);
        SceneBot bot = SceneBot.spawn(ctx, foot);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        bot.unpark();   // idle on the tick list: the server ticks its brain, reflexes included
        level.setBlockAndUpdate(head, Blocks.DIRT.defaultBlockState());
        ctx.record("setup.inWall", fp.isInWall());
        final int[] t = { 0 };
        ctx.await(() -> level.getBlockState(head).isAir() || ++t[0] >= SUFFOCATE_BUDGET)
                .within(SUFFOCATE_BUDGET + 100).then(() -> {
                    boolean cleared = level.getBlockState(head).isAir();
                    ctx.record("head.cleared", cleared + " after " + t[0] + "/" + SUFFOCATE_BUDGET + " ticks");
                    ctx.record("bot.health", fp.getHealth());
                    if (armed && !cleared)
                        ctx.fail("the head cell " + head.toShortString() + " is still "
                                + level.getBlockState(head).getBlock() + " after " + SUFFOCATE_BUDGET
                                + " ticks with antiSuffocate on: the idle server bot's reflex did not break it");
                    if (!armed && cleared)
                        ctx.fail("counter-arm failed: antiSuffocate is off, yet the head cell was cleared; "
                                + "something other than the reflex broke it, so the armed arm proves nothing");
                });
    }
}
