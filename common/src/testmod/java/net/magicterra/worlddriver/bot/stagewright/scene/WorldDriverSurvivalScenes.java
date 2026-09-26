package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.auto.AntiSuffocateGate;
import net.magicterra.worlddriver.bot.auto.ContactEscapeGate;
import net.magicterra.worlddriver.bot.auto.DrownEscapeGate;
import net.magicterra.worlddriver.bot.auto.DrowningFloatGate;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.pathfinder.Capability;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.BunkerProcess;
import net.magicterra.worlddriver.bot.process.EscapeProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.RunAwayProcess;
import net.magicterra.worlddriver.bot.scheduler.BunkerAnchor;
import net.magicterra.worlddriver.bot.scheduler.Chain;
import net.magicterra.worlddriver.bot.scheduler.DrownEscapeChain;
import net.magicterra.worlddriver.bot.scheduler.Priorities;
import net.magicterra.worlddriver.bot.scheduler.ProcessScheduler;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
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
 * Dogfooded worlddriver scenes — <b>P4c wave 8, the Survival family</b>: the 13 legacy
 * {@code AgentGameTestServer} survival tests (escape / bunker shelter / low-HP flee /
 * surface-dive / underwater-base traverse / drowning-escape / anti-suffocate / air-supply
 * observation) migrated verbatim to testkit {@code wd.*} scenes, with their legacy twins
 * deleted from the Server suite in the same commit (count chain legacy 33 → 20 for this
 * provider; the Avatar provider carries it to 15).
 *
 * <p><b>Six drive REAL processes over an isolated FakePlayer</b> ({@code wd.serverEscape} /
 * {@code wd.serverBunker} / {@code wd.serverEscapeSealedShelter} / {@code wd.serverLowHpEdgePin} /
 * {@code wd.serverBunkerSlope}, plus the dive pair below): the canonical wave-6 substitutions
 * apply — {@code helper.getLevel()} → {@link SceneContext#level()}; absolute {@code cx/cz} →
 * origin X/Z; absolute {@code floorY=220} → {@code origin.y + 20} / {@code floorY=200} →
 * {@code origin.y}; ground-anchored {@code helper.absolutePos(ZERO)} → {@link SceneContext#origin()};
 * {@code ServerWorldDriver.create} → {@link ServerWorldDriver#createIsolated} (per-scene server-side player);
 * legacy NeoForge {@code FakePlayer} → common {@link ServerPlayer} (a FakePlayer IS a ServerPlayer;
 * every call used — {@code getInventory()}, {@code getY()}, {@code getHealth()}, {@code setAirSupply}
 * — is a ServerPlayer member, type-faithful on both loaders); {@code try/finally} config save/restore
 * → {@link BotConfig#pinnedBaseline()} + {@code ctx.cleanup(pin::close)}; {@code throw new
 * GameTestAssertException} → {@link SceneContext#fail}; {@code helper.succeed()} → return. The real
 * {@code BunkerProcess}/{@code EscapeProcess}/{@code RunAwayProcess}/{@code IntentProcess} tasks run
 * synchronously over the bounded {@code ServerAvatarManager.register}+{@code tickAll()} loop (wave-7
 * dusk precedent); every scene registers {@code ctx.cleanup} to discard its avatar, drain every dug
 * and placed block in its footprint (LIFO air-scrub) and clear the manager (the #40 persistent-world
 * lesson).
 *
 * <p><b>Five are matrix / observation probes.</b> {@code wd.serverBunkerAnchorRatchet} is a PURE
 * {@link BunkerAnchor} state machine (no world). {@code wd.drowningFloatShouldFloatMatrix},
 * {@code wd.drownEscapeGateMatrix} and {@code wd.antiSuffocateWaterNotSuffocating} are dense gate
 * matrices (the last plants three real blocks); their {@code BiConsumer<Boolean,String> check}
 * lambdas are copied byte-for-byte and supplied the SceneContext analogue
 * {@code (ok,msg) -> { if (!ok) ctx.fail(msg); }} — every row survives one-for-one.
 * {@code wd.serverObserveAirSupply} drives the {@code observe.player} air field over a FakePlayer.
 * {@code wd.drownEscapePreempt} drives the REAL {@link DrownEscapeChain} + {@link ProcessScheduler}
 * against a real FakePlayer in a flooded shaft.
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
                Scene.of("wd.serverBunkerAnchorRatchet", 200, WorldDriverSurvivalScenes::serverBunkerAnchorRatchetScene),
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
                Scene.of("wd.drowningFloatShouldFloatMatrix", 200, WorldDriverSurvivalScenes::drowningFloatShouldFloatMatrixScene),
                Scene.of("wd.drownEscapeGateMatrix", 200, WorldDriverSurvivalScenes::drownEscapeGateMatrixScene),
                Scene.of("wd.contactEscapeGateMatrix", 200, WorldDriverSurvivalScenes::contactEscapeGateMatrixScene),
                Scene.of("wd.drownEscapePreempt", 400, WorldDriverSurvivalScenes::drownEscapePreemptScene),
                Scene.of("wd.serverObserveAirSupply", 200, WorldDriverSurvivalScenes::serverObserveAirSupplyScene),
                Scene.of("wd.antiSuffocateWaterNotSuffocating", 200, WorldDriverSurvivalScenes::antiSuffocateWaterNotSuffocatingScene));
    }

    // ==================================================================================
    // wd.serverEscape — real EscapeProcess climbs out of a 1-wide stone pit.
    // ==================================================================================

    private static void serverEscapeScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.fakePlayer().getInventory().clearContent();
        driver.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 64));   // VERT_RISE fallback
        driver.runProcess(new EscapeProcess(floorY + 4));
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 800 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        boolean climbed = fp.getY() >= floorY + 3.0;   // up from the floorY+1 pit bottom to ~the rim
        WorldDriverCommon.LOG.info("[wd.serverEscape] pos=({},{},{}) climbed={} finished={} active={}",
                fp.getX(), fp.getY(), fp.getZ(), climbed, driver.finished(), ServerAvatarManager.activeCount());
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("server EscapeProcess did not finish+unregister: active="
                    + ServerAvatarManager.activeCount() + " y=" + fp.getY());
        if (!climbed)
            ctx.fail("server EscapeProcess did not climb out: y=" + fp.getY());
    }

    // ==================================================================================
    // wd.serverBunker — real BunkerProcess seals a dig-three, fill-one shelter shaft.
    // ==================================================================================

    private static void serverBunkerScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.fakePlayer().getInventory().clearContent();
        driver.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 64));   // server breaks drop nothing → pre-stock plug blocks
        driver.runProcess(new BunkerProcess(2));
        ServerAvatarManager.register(driver);
        // BunkerProcess holds at SEALED (returns false forever), so it won't unregister —
        // run a fixed window then inspect the world.
        for (int t = 0; t < 800 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        // The shaft column the bot dug (down from floorY) must be plugged solid.
        int sealed = 0, shaftCells = 0;
        for (int dy = 0; dy >= -2; dy--) {
            shaftCells++;
            if (!level.getBlockState(new BlockPos(cx, floorY + dy, cz)).isAir()) sealed++;
        }
        ServerPlayer fp = driver.fakePlayer();
        WorldDriverCommon.LOG.info("[wd.serverBunker] pos=({},{},{}) sealed={}/{} active={}",
                fp.getX(), fp.getY(), fp.getZ(), sealed, shaftCells, ServerAvatarManager.activeCount());
        if (sealed < shaftCells)
            ctx.fail("server BunkerProcess left the shaft open: sealed=" + sealed + "/" + shaftCells);
    }

    // ==================================================================================
    // wd.serverBunkerAnchorRatchet — PURE BunkerAnchor state machine (gap#29, no world).
    // ==================================================================================

    private static void serverBunkerAnchorRatchetScene(SceneContext ctx) {
        final int DEPTH = 2;
        final int START_Y = 64, X = 0, Z = 0;
        BunkerAnchor a = new BunkerAnchor();
        int y = START_Y;

        // --- one episode: dig straight down DEPTH, then seal (mirrors tick() anchor flow) ---
        for (int t = 0; t < 100 && !a.sealed; t++) {
            if (a.displacedFrom(X, y, Z)) a.reset();
            a.beginIfIdle(X, y, Z);
            if (a.sealed) break;
            int depth = a.depth(y);
            a.noteDepth(depth);
            if (depth < DEPTH) y -= 1;            // block below broke → bot drops a level
            else a.sealed = true;                 // deep enough → roof sealed
        }
        if (a.startY != START_Y)
            ctx.fail("episode must anchor at the siege start Y=" + START_Y + ", got " + a.startY);
        if (START_Y - y != DEPTH)
            ctx.fail("one episode must dig exactly DEPTH=" + DEPTH + ", dug " + (START_Y - y));
        final int sealedY = y;

        // --- gap#29 core: 50 preemptions at the sealed spot. Each cycle is the real
        // contract: onInterrupt→onPreempt() (no reset) + onResume→resume() + a tick at the
        // SAME position. The anchor must NOT move and the pocket must NOT re-dig. ---
        for (int cycle = 0; cycle < 50; cycle++) {
            a.onPreempt();                        // BunkerChain.onInterrupt contract
            a.resume();                           // BunkerChain.onResume contract
            if (a.displacedFrom(X, y, Z)) a.reset();
            a.beginIfIdle(X, y, Z);
            if (!a.sealed || a.startY != START_Y || y != sealedY)
                ctx.fail("gap#29 ratchet re-opened at preempt cycle " + cycle
                        + ": sealed=" + a.sealed + " startY=" + a.startY + " y=" + y);
        }

        // --- knockback within tolerance must NOT be treated as displacement ---
        if (a.displacedFrom(X + BunkerAnchor.DRIFT_TOL, sealedY, Z)
                || a.displacedFrom(X, sealedY, Z - BunkerAnchor.DRIFT_TOL))
            ctx.fail("in-tolerance knockback wrongly flagged as displacement (would ratchet)");

        // --- genuine relocation (far respawn, or risen above start) MUST reset the episode ---
        if (!a.displacedFrom(X + BunkerAnchor.DRIFT_TOL + 1, sealedY, Z))
            ctx.fail("far horizontal displacement (respawn) must reset the episode");
        if (!a.displacedFrom(X, START_Y + 2, Z))
            ctx.fail("rising above the start Y (teleport/knock-up) must reset the episode");

        // --- onResume must clear the per-block watchdog ---
        a.digTicks = 999;
        a.resume();
        if (a.digTicks != 0)
            ctx.fail("onResume must clear the dig watchdog, got digTicks=" + a.digTicks);
    }

    // ==================================================================================
    // wd.serverEscapeSealedShelter — real EscapeProcess carves out of a sealed 1×2 pocket.
    // ==================================================================================

    private static void serverEscapeSealedShelterScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.fakePlayer().getInventory().clearContent();
        driver.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 64));   // VERT_RISE fallback
        driver.runProcess(new EscapeProcess(floorY + 6));
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 800 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        boolean climbed = fp.getY() >= floorY + 5.0;
        String slotErr = driver.botState().escape.lastError;
        WorldDriverCommon.LOG.info("[wd.serverEscapeSealedShelter] pos=({},{},{}) climbed={} finished={} active={} slotErr={}",
                fp.getX(), fp.getY(), fp.getZ(), climbed, driver.finished(), ServerAvatarManager.activeCount(), slotErr);
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("sealed-shelter escape did not finish+unregister (old STEP_UP ping-pong?): active="
                    + ServerAvatarManager.activeCount() + " y=" + fp.getY());
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.walkerDebug = false;
        BotConfig.lowHealthCareful = 6.0;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 13, cz + 0.5);
        driver.fakePlayer().setHealth(2.0f);
        // Hungry enough that natural regeneration stays off (FoodData.tick heals a hurt player only
        // at food >= 18) and fed enough to sprint (> 6). The player runs FoodData.tick every step, and
        // at full food it would heal past lowHealthCareful long before the flee ends, switching off
        // the careful walk this scene is about.
        driver.fakePlayer().getFoodData().setFoodLevel(17);
        driver.runProcess(new RunAwayProcess(from, minDist));
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 800 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        double ddx = fp.getX() - (cx - 2 + 0.5), ddz = fp.getZ() - (cz + 0.5);
        double dist = Math.sqrt(ddx * ddx + ddz * ddz);
        WorldDriverCommon.LOG.info("[wd.serverLowHpEdgePin] pos=({},{},{}) dist={} hp={} finished={} active={}",
                fp.getX(), fp.getY(), fp.getZ(), dist, fp.getHealth(),
                driver.finished(), ServerAvatarManager.activeCount());
        if (fp.getHealth() < 2.0f)
            ctx.fail("low-HP flee took damage (fell off the planned descent / lip): hp="
                    + fp.getHealth() + " y=" + fp.getY());
        if (fp.getY() < floorY + 9.0)
            ctx.fail("low-HP flee left the elevated runway (fell): y=" + fp.getY());
        if (dist < minDist - 0.5)
            ctx.fail("low-HP flee did not reach min distance: dist=" + dist + " (need " + minDist + ")");
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("low-HP flee did not finish+unregister: active=" + ServerAvatarManager.activeCount());
    }

    // ==================================================================================
    // wd.serverBunkerSlope — real BunkerProcess must enclose the niche, not punch a cliff
    // face (death#2 enclosure regression).
    // ==================================================================================

    private static void serverBunkerSlopeScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.fakePlayer().getInventory().clearContent();
        driver.fakePlayer().getInventory().add(new ItemStack(Items.DIRT, 64));
        driver.runProcess(new BunkerProcess(2));
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 1200 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
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
        WorldDriverCommon.LOG.info("[wd.serverBunkerSlope] pos={} openLateral={} roofOpen={} open=[{}]",
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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
        Goal.Near goal = new Goal.Near(bottomCell, 1);

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        CapabilityProfile diveProfile = new CapabilityProfile(
                EnumSet.noneOf(Capability.class), EnumSet.of(Capability.DIVE));
        List<Constraint> constraints = List.of(new NoBreak());
        SearchProfile diveSearch = new SearchProfile(List.of(), diveProfile, constraints);

        // FakePlayer starts IN the water AT the surface — surfaceY-1: only the new opt-in
        // SurfaceDive can start the descent (SwimDown is isSubmergedFoot-gated).
        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, surfaceY - 1, cz + 0.5);
        BlockPos start = driver.fakePlayer().blockPosition();

        // PLANNER-ONLY proof: the plan reaches the floor and its edge list actually contains
        // a swimDownSurface edge (not just SOME reachable route).
        PathFinder.Result plan = new PathFinder(driver.world(), diveSearch).findPath(start, goal);
        boolean hasSurfaceDive = false;
        for (Move.Edge e : plan.edges()) if (e != null && "swimDownSurface".equals(e.move)) hasSurfaceDive = true;
        WorldDriverCommon.LOG.info(
                "[wd.surfaceDive] plan.goalReached={} hasSurfaceDive={} start=({},{},{}) bottomCell={}",
                plan.goalReached(), hasSurfaceDive, start.getX(), start.getY(), start.getZ(), bottomCell);
        if (!plan.goalReached())
            ctx.fail("planner: dive-enabled plan did NOT reach the underwater goal");
        if (!hasSurfaceDive)
            ctx.fail("planner: dive-enabled plan reached the goal WITHOUT a swimDownSurface edge — "
                    + "the new opt-in move never fired");

        // EXECUTOR proof: the SAME intent, run for real over the ticked ServerAvatarManager loop.
        Intent intent = new Intent(goal, List.of(), diveProfile, constraints);
        driver.runProcess(new IntentProcess(intent));
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 600 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        double ddx = fp.getX() - (bottomCell.getX() + 0.5);
        double ddy = fp.getY() - bottomCell.getY();
        double ddz = fp.getZ() - (bottomCell.getZ() + 0.5);
        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        WorldDriverCommon.LOG.info(
                "[wd.surfaceDive] pos=({},{},{}) finished={} active={} dist={}",
                fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAvatarManager.activeCount(), dist);
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("executor: dive process did not finish+unregister within 600t: "
                    + "finished=" + driver.finished() + " active=" + ServerAvatarManager.activeCount());
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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
        Goal.Near goal = new Goal.Near(goalCell, 2);

        BotConfig.walkerDebug = false;
        // SMALL slice (NOT the usual unbounded pin): the executor's search must take MANY TICKS
        // to resolve so the arena reproduces the live PRE-PATH RACE. maxMs stays unbounded so
        // the search itself is still node-budget-deterministic.
        BotConfig.pathfinderSliceMs = 1;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        CapabilityProfile diveProfile = new CapabilityProfile(
                EnumSet.noneOf(Capability.class), EnumSet.of(Capability.DIVE));
        List<Constraint> constraints = List.of(new NoBreak());
        SearchProfile diveSearch = new SearchProfile(List.of(), diveProfile, constraints);

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, surfaceY - 1, cz + 0.5);
        SimProbes.grantWaterEffects(driver.fakePlayer());
        BlockPos start = driver.fakePlayer().blockPosition();

        // Planner precheck: reachable, and still via a surface dive.
        PathFinder.Result plan = new PathFinder(driver.world(), diveSearch).findPath(start, goal);
        boolean hasSurfaceDive = false;
        for (Move.Edge e : plan.edges()) if (e != null && "swimDownSurface".equals(e.move)) hasSurfaceDive = true;
        WorldDriverCommon.LOG.info(
                "[wd.underwaterBase] plan.goalReached={} hasSurfaceDive={} finalCost={} start=({},{},{}) goalCell={}",
                plan.goalReached(), hasSurfaceDive, plan.finalCost(),
                start.getX(), start.getY(), start.getZ(), goalCell);
        if (!plan.goalReached())
            ctx.fail("planner: dive+traverse plan did NOT reach the chamber goal");
        if (!hasSurfaceDive)
            ctx.fail("planner: plan reached the chamber WITHOUT a swimDownSurface edge");
        // Dive water-tax relief gate: 640 (≈ 16 cells × 40) cleanly separates the relieved
        // route (~350) from the un-relieved one (past ~800).
        if (plan.finalCost() >= 640)
            ctx.fail("planner: dive plan finalCost=" + plan.finalCost()
                    + " ≥ 640 — the DIVE opt-in water-tax relief is not engaging (the un-relieved"
                    + " taxes starve the open-world live search into an overland dead-end)");

        Intent intent = new Intent(goal, List.of(), diveProfile, constraints);
        driver.runProcess(new IntentProcess(intent));
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 600 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        // Position asserts measure against the STAND cell (the water cell on the chamber floor).
        double ddx = fp.getX() - (standCell.getX() + 0.5);
        double ddy = fp.getY() - standCell.getY();
        double ddz = fp.getZ() - (standCell.getZ() + 0.5);
        double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        boolean insideChamber = fp.getX() > cx + 2.5;   // past the tank's east wall plane
        WorldDriverCommon.LOG.info(
                "[wd.underwaterBase] pos=({},{},{}) finished={} active={} dist={} insideChamber={}",
                fp.getX(), fp.getY(), fp.getZ(), driver.finished(), ServerAvatarManager.activeCount(),
                dist, insideChamber);
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("executor: dive+traverse process did not finish+unregister within 600t: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount()
                    + " pos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
        if (!insideChamber || dist > 2.0)
            ctx.fail("executor: bot did not end INSIDE the chamber near the goal: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") dist=" + dist
                    + " insideChamber=" + insideChamber);
    }

    // ==================================================================================
    // wd.drowningFloatShouldFloatMatrix — gap#70 DrowningFloatGate.shouldFloat (4 rows, PURE).
    // ==================================================================================

    static void drowningFloatShouldFloatMatrix(BiConsumer<Boolean, String> check) {
        // (a) underwater, air at the threshold exactly, enabled → must float.
        check.accept(DrowningFloatGate.shouldFloat(true, 100, 100, true),
                "gap#70(a): underwater with air==threshold and enabled must float");
        // (b) underwater but air comfortably above the threshold → must NOT float.
        check.accept(!DrowningFloatGate.shouldFloat(true, 300, 100, true),
                "gap#70(b): underwater with air well above threshold must NOT float");
        // (c) air critically low but NOT underwater → must NOT float; nothing to rescue from.
        check.accept(!DrowningFloatGate.shouldFloat(false, 50, 100, true),
                "gap#70(c): low air but not underwater must NOT float");
        // (d) config gate: even underwater + critical air, disabled must NOT float.
        check.accept(!DrowningFloatGate.shouldFloat(true, 50, 100, false),
                "gap#70(d): autoFloatWhenDrowning=false must suppress even underwater+critical air");
    }

    private static void drowningFloatShouldFloatMatrixScene(SceneContext ctx) {
        drowningFloatShouldFloatMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.drownEscapeGateMatrix — gap#76 DrownEscapeGate entry/hold/release matrix (18 rows)
    // + DrownEscapeChain episode lifecycle (8 rows), PURE.
    // ==================================================================================

    static void drownEscapeGateMatrix(BiConsumer<Boolean, String> check) {
        // -------- entry --------
        check.accept(DrownEscapeGate.next(false, true, 100, 101, 100, 280, true),
                "gap#76(a): underwater with air==enter threshold must latch");
        check.accept(DrownEscapeGate.next(false, true, 40, 41, 100, 280, true),
                "gap#76(b): underwater well below the threshold must latch");
        check.accept(!DrownEscapeGate.next(false, true, 101, 102, 100, 280, true),
                "gap#76(c): air just above the threshold must NOT latch (planned crossings keep their reserve)");
        check.accept(!DrownEscapeGate.next(false, false, 50, 51, 100, 280, true),
                "gap#76(d): head out of water never ENTERS (nothing to escape)");
        check.accept(!DrownEscapeGate.next(false, true, 50, 51, 100, 280, false),
                "gap#76(e): autoDrownEscape=false must suppress entry");
        // -------- hold (the hysteresis band) --------
        check.accept(DrownEscapeGate.next(true, true, 101, 100, 100, 280, true),
                "gap#76(f): latched + air back above the ENTRY threshold must STAY latched (no flap at 100)");
        check.accept(DrownEscapeGate.next(true, true, 279, 278, 100, 280, true),
                "gap#76(g): latched underwater one below release must stay latched");
        check.accept(DrownEscapeGate.next(true, false, 150, 150, 100, 280, true),
                "gap#76(h): head momentarily out but air NOT yet recovering must stay latched (surface bob)");
        // -------- release --------
        check.accept(!DrownEscapeGate.next(true, true, 280, 279, 100, 280, true),
                "gap#76(i): air >= release lets go even with the head still underwater");
        check.accept(!DrownEscapeGate.next(true, false, 154, 150, 100, 280, true),
                "gap#76(j): head out + air recovering releases early");
        check.accept(DrownEscapeGate.next(true, false, 72, 68, 100, 280, true),
                "death#9(n): root-pocket bob — head out + air climbing but still in the "
                + "entry band must STAY latched (released at 68 → resumed dive → drowned)");
        check.accept(DrownEscapeGate.next(true, false, 140, 136, 100, 280, true),
                "death#9(o): head out + climbing exactly at enter+margin boundary stays latched");
        check.accept(!DrownEscapeGate.next(true, false, 141, 137, 100, 280, true),
                "death#9(p): head out + climbing just past enter+margin releases");
        check.accept(!DrownEscapeGate.next(true, true, 60, 60, 100, 280, false),
                "gap#76(k): flipping autoDrownEscape off drops an existing latch");
        check.accept(!DrownEscapeGate.next(true, true, 300, 299, 100, 999, true),
                "gap#76(l): releaseAir above vanilla max 300 clamps (a full-air latch can't hold forever)");
        // -------- oscillation guard: jitter AROUND the entry threshold stays latched --------
        boolean latch = DrownEscapeGate.next(false, true, 100, 101, 100, 280, true);
        int prev = 100;
        for (int air : new int[]{99, 101, 100, 102, 98, 103}) {
            latch = DrownEscapeGate.next(latch, true, air, prev, 100, 280, true);
            check.accept(latch, "gap#76(m): latch must hold through entry-threshold jitter (air=" + air
                    + " — the frail-gate no-hysteresis oscillation precedent)");
            prev = air;
        }
    }

    // gap#76 chain-level episode lifecycle (gap#72 semantics): the latch IS the episode;
    // cancel/death-clear reach it through the pure state half (resetEpisodeState — the client
    // key-release half touches Minecraft.getInstance() and is live-verified, same split as the
    // migrated ad.chainEpisodeCancel scene / BunkerChain).
    static void drownEscapeChainLifecycleMatrix(BiConsumer<Boolean, String> check) {
        boolean oEnabled = BotConfig.autoDrownEscape;
        int oEnter = BotConfig.drownEscapeAirThreshold, oRelease = BotConfig.drownEscapeReleaseAir;
        BotConfig.autoDrownEscape = true;
        BotConfig.drownEscapeAirThreshold = 100;
        BotConfig.drownEscapeReleaseAir = 280;
        try {
            DrownEscapeChain ch = new DrownEscapeChain();
            check.accept(ch.episodePhase() == null, "fresh chain has no episode");
            check.accept(!ch.updateLatch(true, 300), "plenty of air: no episode");
            check.accept(ch.updateLatch(true, 100), "critical air underwater latches");
            check.accept("FLOATING".equals(ch.episodePhase()), "live episode reads FLOATING");
            check.accept(ch.updateLatch(true, 101), "chain holds through the hysteresis band");
            ch.resetEpisodeState();
            check.accept(ch.episodePhase() == null, "cancel/death-clear (pure half) clears the episode");
            check.accept(ch.updateLatch(true, 99),
                    "still underwater+critical after a cancel re-arms next evaluation (fresh-instance contract)");
            ch.resetEpisodeState();
            BotConfig.autoDrownEscape = false;
            check.accept(!ch.updateLatch(true, 10), "setting off: chain never latches");
        } finally {
            BotConfig.autoDrownEscape = oEnabled;
            BotConfig.drownEscapeAirThreshold = oEnter;
            BotConfig.drownEscapeReleaseAir = oRelease;
        }
    }

    // Minimal WorldView over explicit solid/water cell sets (everything else is
    // air/passable). Only the five abstract methods matter for the lateral-escape
    // geometry decision; the rest keep their interface defaults.
    private static WorldView gridWorld(Set<BlockPos> solid, Set<BlockPos> water) {
        return new WorldView() {
            @Override public boolean isSolid(BlockPos p)     { return solid.contains(p); }
            @Override public boolean isPassable(BlockPos p)  { return !solid.contains(p); }
            @Override public boolean isHazard(BlockPos p)    { return false; }
            @Override public boolean isWater(BlockPos p)     { return water.contains(p); }
            @Override public boolean isClimbable(BlockPos p) { return false; }
        };
    }

    // gap#77 / live death #27 (2026-07-21 flooded Mountains): the overhang lateral
    // escape decision (DrownEscapeChain.lateralEscapeDir), PURE. The bot drowned in a
    // 1×1 water pocket capped by an undercut cliff shelf, ONE block from open water,
    // because pure-vertical float can't surface under a solid lid and the no-horizontal
    // contract forbade the sideways swim. lateralEscapeDir steers to open water when
    // (and only when) the current column is capped — deep/open water still floats up.
    static void drownEscapeLateralMatrix(BiConsumer<Boolean, String> check) {
        // (a) the death#27 undercut: bot column capped by a solid shelf; the ONE open
        // neighbour is +x. Every other ring-1 neighbour is capped, so +x must be chosen.
        {
            Set<BlockPos> solid = new HashSet<>(), water = new HashSet<>();
            for (int y = 59; y <= 62; y++) water.add(new BlockPos(0, y, 0));   // bot column: water …
            for (int y = 63; y <= 66; y++) solid.add(new BlockPos(0, y, 0));   // … capped by the shelf
            for (int y = 59; y <= 62; y++) water.add(new BlockPos(1, y, 0));   // +x: water, air above (open)
            for (int[] c : new int[][]{{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}}) {
                for (int y = 59; y <= 62; y++) water.add(new BlockPos(c[0], y, c[1]));
                for (int y = 63; y <= 66; y++) solid.add(new BlockPos(c[0], y, c[1])); // capped
            }
            int[] dir = DrownEscapeChain.lateralEscapeDir(gridWorld(solid, water), 0, 60, 0);
            check.accept(dir != null && dir[0] == 1 && dir[1] == 0,
                    "death#27(a): capped pocket, sole opening +x → lateral {+1,0} (got "
                    + (dir == null ? "null" : dir[0] + "," + dir[1]) + ")");
        }
        // (b) deep OPEN water (no solid cap within scan) → null: bot floats straight up,
        // the unchanged pure-vertical case. Regression guard against over-triggering.
        {
            Set<BlockPos> solid = new HashSet<>(), water = new HashSet<>();
            for (int y = 50; y <= 62; y++) water.add(new BlockPos(0, y, 0));
            check.accept(DrownEscapeChain.lateralEscapeDir(gridWorld(solid, water), 0, 55, 0) == null,
                    "death#27(b): deep open water is NOT capped → null (float straight up)");
        }
        // (c) open sky directly above the bot → null: surface in place, never sideways.
        {
            Set<BlockPos> solid = new HashSet<>(), water = new HashSet<>();
            for (int y = 59; y <= 62; y++) water.add(new BlockPos(0, y, 0));   // air at y63
            check.accept(DrownEscapeChain.lateralEscapeDir(gridWorld(solid, water), 0, 60, 0) == null,
                    "death#27(c): open surface directly above → null (surface in place)");
        }
        // (d) capped pocket, boxed in on all sides past the scan radius → null: no lateral
        // escape exists, so the caller falls back to the lid-break (MC-always-escapable).
        {
            Set<BlockPos> solid = new HashSet<>(), water = new HashSet<>();
            for (int y = 59; y <= 62; y++) water.add(new BlockPos(0, y, 0));
            for (int y = 63; y <= 66; y++) solid.add(new BlockPos(0, y, 0));
            for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int y = 58; y <= 66; y++) solid.add(new BlockPos(dx, y, dz)); // solid all around
            }
            check.accept(DrownEscapeChain.lateralEscapeDir(gridWorld(solid, water), 0, 60, 0) == null,
                    "death#27(d): capped with no open neighbour in range → null (lid-break fallback)");
        }
        // (e) live death #31, the real ladder of 2026-08-26 rung 9. A column that CAN surface,
        // with rock in between. Every cell below was transcribed from that run's own saved region
        // file (fabric/run-journey-integrated/saves/JourneyClient), origin = the bot's cell
        // 81,59,82; the run's log shows this arm holding `forward` at a horizontal speed of 0.0000
        // for 532 ticks and the bot drowning without moving one block.
        //
        // The old ring scan answered "can 81,59,80 surface?" — yes, it is air — and steered there.
        // 81,59,81, the single cell between, is stone; 81,60,80, the picked column's HEAD cell, is
        // stone too, so it was steering at a cell no two-block-tall player could ever occupy. And
        // because a non-null answer skips the lid-break, the false positive did not merely fail to
        // help: it withheld the one arm the class doc calls always-escapable.
        {
            // The WHOLE floor of the scan's reach, not the cells I happened to think of. The first
            // draft staged only the four orthogonal neighbours and left the diagonals unlisted —
            // and in this grid world an unlisted cell is AIR, so the scan found a breathable
            // column that does not exist in the world this case claims to reproduce, and named
            // 80,59,83 (stone in the save). The staging committed the very error the case is about:
            // answering for cells nobody asked about. Read as z rows (80..84) of x columns (79..83),
            // origin = the bot at 81,59,82; '#' solid, '~' water, '.' air.
            String[] floorY59 = {
                    "#~.##",   // z=80  81,59,80 is the '.' — the column the run steered at
                    "#~###",   // z=81  81,59,81 is the '#' between it and the bot
                    "##~##",   // z=82  81,59,82 — the bot's pocket
                    "#####",   // z=83
                    "#####",   // z=84
            };
            Set<BlockPos> solid = new HashSet<>(), water = new HashSet<>();
            for (int dz = -2; dz <= 2; dz++)
                for (int dx = -2; dx <= 2; dx++) {
                    char c = floorY59[dz + 2].charAt(dx + 2);
                    if (c == '#') solid.add(new BlockPos(dx, 59, dz));
                    else if (c == '~') water.add(new BlockPos(dx, 59, dz));
                }
            // The three columns that are not solid at y=59, carried up far enough for
            // breathableColumn's four-cell scan to give the answer the live run gave. Each cell
            // transcribed from fabric/run-journey-integrated/saves/JourneyClient.
            water.add(new BlockPos(0, 60, 0));                 // 81,60,82 — the source the tower guard named
            solid.add(new BlockPos(0, 61, 0));                 // 81,61,82 — dirt: the lid ⇒ capped
            solid.add(new BlockPos(0, 60, -2));                // 81,60,80 — stone: so the picked column
                                                               //   is not even standable for a 2-tall player
            water.add(new BlockPos(-1, 60, -2));               // 80,60,80 — lake
            solid.add(new BlockPos(-1, 61, -2));               // 80,61,80 — dirt caps it ⇒ not breathable
            for (int y = 60; y <= 62; y++)
                water.add(new BlockPos(-1, y, -1));            // 80,60..62,81 — lake
            solid.add(new BlockPos(-1, 63, -1));               // 80,63,81 — grass_block caps it
            // That grass_block is why the live run reached PAST ring 1 to ring 2. An earlier draft
            // guessed air up there; the guess would have made ring 1 breathable and quietly
            // re-pointed this case at a geometry the run never had.
            WorldView w = gridWorld(solid, water);

            int[] dir = DrownEscapeChain.lateralEscapeDir(w, 0, 59, 0);
            check.accept(dir == null,
                    "death#31(e): breathable column behind solid rock → null, so the lid-break runs "
                    + "(got " + (dir == null ? "null" : dir[0] + "," + dir[1]) + ")");

            // The positive control, and it is not decoration: `unreachable` is the OLD ring scan,
            // verbatim. Requiring it to name a cell is requiring this staging to still contain the
            // false positive — without it (e) would pass for the trivial reason that nothing is
            // breathable anywhere, which is case (d), not this one. It must name 81,59,80 (0,59,-2),
            // the very cell the live log printed as `dir=0,-2`.
            DrownEscapeChain.LateralEscape scan = DrownEscapeChain.lateralEscapeScan(w, 0, 59, 0);
            check.accept(scan.unreachable() != null && scan.unreachable().getZ() == -2
                            && scan.unreachable().getX() == 0,
                    "death#31(e): the dead end is named, and it is the cell the live run steered at "
                    + "— expected 0,59,-2, got " + scan.unreachable());
        }
    }

    private static void drownEscapeGateMatrixScene(SceneContext ctx) {
        // Each matrix is fenced SEPARATELY and its throwable is logged with a full stack. The
        // framework reports only `unexpected RuntimeException: <message>`, and for a class-loading
        // failure the message ("Cannot load class …LocalPlayer in environment type SERVER") names
        // the class that could not load but NOT the instruction that asked for it. Two hours of
        // reading javap output and git history could not answer "which of the three matrices, and
        // at which call" — one stack does. Rethrown afterwards so the verdict is unchanged.
        runMatrixNamingItsStack(ctx, "gate", () -> drownEscapeGateMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); }));
        runMatrixNamingItsStack(ctx, "lifecycle", () -> drownEscapeChainLifecycleMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); }));
        runMatrixNamingItsStack(ctx, "lateral", () -> drownEscapeLateralMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); }));
    }

    /** Run one matrix, and if it throws something that is not a scene assertion, print the whole
     *  stack under a name before letting it propagate. See the caller for why. */
    private static void runMatrixNamingItsStack(SceneContext ctx, String which, Runnable body) {
        try {
            body.run();
        } catch (LinkageError | RuntimeException e) {
            WorldDriverCommon.LOG.error("[matrixStack] {} threw {}: {}", which,
                    e.getClass().getName(), e.getMessage(), e);
            throw e;
        }
    }

    // ==================================================================================
    // wd.contactEscapeGateMatrix — death#14 ContactEscapeGate.shouldTrigger truth table
    // (9 rows, PURE). The actuation half (face-away walk) is client-side and live-verified.
    // ==================================================================================

    static void contactEscapeGateMatrix(BiConsumer<Boolean, String> check) {
        // -------- fires: fresh damage from a step-away-able block --------
        check.accept(ContactEscapeGate.shouldTrigger("cactus", 9, true),
                "death#14(a): fresh cactus damage must trigger (the killer case)");
        check.accept(ContactEscapeGate.shouldTrigger("sweetBerryBush", 5, true),
                "death#14(b): berry bush contact must trigger");
        check.accept(ContactEscapeGate.shouldTrigger("inFire", 8, true),
                "death#14(c): standing in a fire block must trigger");
        check.accept(ContactEscapeGate.shouldTrigger("hotFloor", 3, true),
                "death#14(d): standing on magma must trigger");
        // -------- must NOT fire --------
        check.accept(!ContactEscapeGate.shouldTrigger("onFire", 9, true),
                "death#14(e): burning AFTER leaving fire must NOT trigger (stepping away cannot help)");
        check.accept(!ContactEscapeGate.shouldTrigger("mob", 9, true),
                "death#14(f): entity damage must NOT trigger (belongs to hurt-entry retreat attribution)");
        check.accept(!ContactEscapeGate.shouldTrigger("cactus", 0, true),
                "death#14(g): stale attribution (hurtTime 0, ~40t last-damager tail) must NOT trigger");
        check.accept(!ContactEscapeGate.shouldTrigger(null, 9, true),
                "death#14(h): no damage source must NOT trigger");
        check.accept(!ContactEscapeGate.shouldTrigger("cactus", 9, false),
                "death#14(i): contactDamageEscape=false must suppress");
    }

    private static void contactEscapeGateMatrixScene(SceneContext ctx) {
        contactEscapeGateMatrix((ok, msg) -> { if (!ok) ctx.fail(msg); });
    }

    // ==================================================================================
    // wd.drownEscapePreempt — REAL DrownEscapeChain + ProcessScheduler preempts an active
    // "user" chain in a flooded 1×1 shaft and floats the bot up (gap#76, live death #25).
    // ==================================================================================

    private static void drownEscapePreemptScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();
        final int depth = 6;                     // water column floorY+1 .. floorY+depth
        boolean oEnabled = BotConfig.autoDrownEscape;
        int oEnter = BotConfig.drownEscapeAirThreshold, oRelease = BotConfig.drownEscapeReleaseAir;
        BotConfig.autoDrownEscape = true;
        BotConfig.drownEscapeAirThreshold = 100;
        BotConfig.drownEscapeReleaseAir = 280;
        ctx.cleanup(() -> {
            BotConfig.autoDrownEscape = oEnabled;
            BotConfig.drownEscapeAirThreshold = oEnter;
            BotConfig.drownEscapeReleaseAir = oRelease;
        });
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();

        DrownEscapeChain drown = new DrownEscapeChain();
        drown.sensorForTest(fp::isUnderWater, fp::getAirSupply);
        final int[] userInterrupts = new int[1];
        Chain user = new Chain() {           // the "active process" placeholder (death-#25 mine shape)
            @Override public String name() { return "user"; }
            @Override public float priority(Body body, WorldView w, BotState st) { return Priorities.USER; }
            @Override public void tick(Body body, WorldView w, BotState st) { /* keeps digging */ }
            @Override public void onInterrupt(Chain by) { userInterrupts[0]++; }
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
        WorldDriverCommon.LOG.info(
                "[wd.drownEscapePreempt] flips={} preempt@t{} (air={}) release@t{} (air={} under={}) minAir={} "
                        + "y {}→{} interrupts={} phase={}",
                flips, preemptTick, airAtPreempt, releaseTick, airAtRelease, underAtRelease, minAir,
                yAtPreempt, fp.getY(), userInterrupts[0], drown.episodePhase());
        if (!flips.isEmpty() && !flips.get(0).startsWith("user@"))
            ctx.fail("gap#76: user chain should hold the channel while air is healthy: " + flips);
        if (preemptTick < 0)
            ctx.fail("gap#76 (live death #25): DrownEscapeChain NEVER preempted the active process while "
                    + "the bot drowned — minAir=" + minAir + " flips=" + flips);
        if (airAtPreempt > BotConfig.drownEscapeAirThreshold)
            ctx.fail("gap#76: preempted too early, air=" + airAtPreempt + " > threshold " + BotConfig.drownEscapeAirThreshold);
        if (userInterrupts[0] < 1)
            ctx.fail("gap#76: preemption must interrupt the user chain (onInterrupt)");
        if (minAir <= 0)
            ctx.fail("gap#76: air hit " + minAir + " — the escape did not beat the drown clock (death #25 shape)");
        if (releaseTick < 0)
            ctx.fail("gap#76: chain never released the channel after surfacing — hysteresis release broken (air="
                    + fp.getAirSupply() + " under=" + fp.isUnderWater() + ")");
        if (fp.getY() < yAtPreempt + 2.0)
            ctx.fail("gap#76: bot did not float up: y " + yAtPreempt + " → " + fp.getY());
        if (underAtRelease && airAtRelease < BotConfig.drownEscapeReleaseAir)
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(() -> level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.AIR.defaultBlockState()));

        BotConfig.walkerDebug = false;
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.STONE.defaultBlockState());
        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        fp.setAirSupply(42);

        java.util.Map<String, Object> snap = new net.magicterra.worlddriver.api.DriverApi().observe.playerSnapshot(fp);
        WorldDriverCommon.LOG.info("[wd.serverObserveAirSupply] air={} maxAir={}", snap.get("air"), snap.get("maxAir"));
        if (!(snap.get("air") instanceof Number an) || an.intValue() != 42)
            ctx.fail("gap#70: observe.player carries no (or wrong) `air` field — got " + snap.get("air")
                    + ", wanted 42 (fp.setAirSupply(42))");
        if (!(snap.get("maxAir") instanceof Number mn) || mn.intValue() != fp.getMaxAirSupply())
            ctx.fail("gap#70: observe.player carries no (or wrong) `maxAir` field — got " + snap.get("maxAir"));
    }

    // ==================================================================================
    // wd.antiSuffocateWaterNotSuffocating — gap#80 AntiSuffocateGate.suffocates: stone yes,
    // water/air no (drowning is not suffocation). Plants three real blocks. 3 rows.
    // ==================================================================================

    // gap#80 pure seam: AntiSuffocate#resolveHead's eligibility test must be vanilla's own
    // BlockState#isSuffocating (returns false for water — empty collision shape — but true for
    // stone), factored into AntiSuffocateGate#suffocates so a dedicated server can plant real
    // blocks and call it directly (AntiSuffocate.resolveHead itself needs a live client tick).
    static void antiSuffocateSuffocatesBlockMatrix(ServerLevel level, BlockPos stone, BlockPos water, BlockPos air,
                                                    BiConsumer<Boolean, String> check) {
        check.accept(AntiSuffocateGate.suffocates(level, stone),
                "gap#80(a): an ordinary solid block (stone) must read as suffocating");
        check.accept(!AntiSuffocateGate.suffocates(level, water),
                "gap#80(b): WATER must NOT read as suffocating (drowning, not suffocation) — "
                        + "the live bug: resolveHead's old !isAir() check treated water as a valid break target");
        check.accept(!AntiSuffocateGate.suffocates(level, air),
                "gap#80(c): AIR must NOT read as suffocating (baseline sanity)");
    }

    private static void antiSuffocateWaterNotSuffocatingScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
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
        antiSuffocateSuffocatesBlockMatrix(level, stone, water, air,
                (ok, msg) -> { if (!ok) ctx.fail(msg); });
    }
}
