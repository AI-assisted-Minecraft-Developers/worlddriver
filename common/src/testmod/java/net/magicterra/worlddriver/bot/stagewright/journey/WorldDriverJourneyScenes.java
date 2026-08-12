package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.MineProcess;
import net.magicterra.worlddriver.bot.process.SmeltProcess;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * One vanilla playthrough, as a suite — worlddriver testing whether its own API can finish the game.
 *
 * <p>Every other scene family in this repo asks whether one verb works. This one asks the question
 * they add up to and none of them answers: <b>can a caller who knows exactly what to do actually get
 * from an empty inventory at world spawn to a dead ender dragon, using nothing but this driver?</b>
 * The answer has never been measured. 222 scenes are green and the ladder in {@link JourneyStage}
 * has never been climbed past its second rung.
 *
 * <h2>The rules this suite plays by</h2>
 *
 * <ul>
 *   <li><b>One world, one body, one run.</b> The stages are chapters, not tests. State is shared
 *       through {@link JourneyLedger} on purpose; see its class note for what that costs and how
 *       each cost is paid.</li>
 *   <li><b>Nothing is staged.</b> No give, no setblock, no fill, no teleport. Reaching a rung means
 *       the run played its way there. {@code JourneyLedger.stagingCalls()} measures the claim rather
 *       than asserting it.</li>
 *   <li><b>Every step is scripted.</b> The route is surveyed from the fixed seed
 *       ({@link JourneyRoute}), so a failure is the driver failing to execute a correct plan rather
 *       than a planner failing to find one.</li>
 *   <li><b>The frontier fails freely.</b> Rungs the engine has not climbed ship optional. The gate
 *       is {@link JourneyLedger#FLOOR}, checked once, by the verdict scene.</li>
 * </ul>
 *
 * <h2>Why this family is off by default</h2>
 *
 * A playthrough is long — hours, not the five minutes the six gates take — so registering it into
 * them would make every commit pay for it. It arms on {@code -Dworlddriver.journey=true}.
 *
 * <p>That is a silent-composition hole of exactly the kind {@code expected-scenes-*.txt} exists to
 * close: a family that registers nothing looks identical to a family that was deleted. So
 * {@link #armedMarker()} always registers. It runs in all six gates, costs one tick, and records
 * whether the ladder is armed and how many rungs it has — which means a journey family that
 * silently stopped registering shows up as a changed rung count in a gate that takes five minutes,
 * rather than as a green run of a suite that is no longer there.
 */
public final class WorldDriverJourneyScenes implements SceneProvider {

    /** Arms the ladder. Off in the six gates; on in the journey run task. */
    public static final String ARM_PROPERTY = "worlddriver.journey";

    /** Whether this run was asked to play the game. */
    public static boolean armed() {
        return Boolean.getBoolean(ARM_PROPERTY);
    }

    @Override
    public List<Scene> scenes() {
        List<Scene> out = new ArrayList<>();
        out.add(armedMarker());
        if (!armed()) return List.copyOf(out);

        // Ladder order is registration order AND name order, so the explicit list below and
        // Stages' name sort agree. A chain whose scenes could be reordered is not a chain.
        out.add(stage("wd.journey01Recon", JourneyStage.RECON, 2_400, WorldDriverJourneyScenes::recon));
        out.add(stage("wd.journey02Spawn", JourneyStage.SPAWN, 600, WorldDriverJourneyScenes::spawn));
        // 40 000, because this rung can now visit four trunks. 12 000 was sized when it visited
        // one and a bit, and a budget that is smaller than the plan turns "the swamp is thin"
        // into a timeout — the wrong sentence about the right world, which is the same mistake
        // the iron rung's budget already had to fix once.
        out.add(stage("wd.journey03Wood", JourneyStage.WOOD, 40_000, WorldDriverJourneyScenes::wood));
        out.add(stage("wd.journey04WoodTools", JourneyStage.WOOD_TOOLS, 6_000, WorldDriverJourneyScenes::woodTools));

        // ---- the frontier: declared, registered, and not yet scripted ----
        //
        // Registered rather than omitted, deliberately. A ladder that only lists the rungs somebody
        // has already built reports a complete climb every time the frontier is where it was, and
        // the whole point of this suite is to show where the frontier IS. Each of these runs, records
        // NOT_SCRIPTED against its stage, and lets everything above it report BLOCKED — so the
        // ledger reads as a map of the climb rather than as a list of passes.
        out.add(stage("wd.journey05StoneTools", JourneyStage.STONE_TOOLS, 40_000,
                WorldDriverJourneyScenes::stoneTools));
        // 30 000: the hunt walks out to the animal and now walks back to spawn, and the return
        // leg is the whole reason the rungs above it start somewhere they can navigate from.
        out.add(stage("wd.journey06Food", JourneyStage.FOOD, 30_000, WorldDriverJourneyScenes::food));
        out.add(unscripted("wd.journey07Bed", JourneyStage.BED));
        out.add(stage("wd.journey08Furnace", JourneyStage.FURNACE, 8_000,
                WorldDriverJourneyScenes::furnace));
        // 90 000, because this rung can dig TWICE: seed 5471's first vein is one ore, so a run that
        // needs the portal kit's four ingots walks to a second column, sinks an eleven-deep shaft
        // and climbs back out of it. A budget sized for one shaft turns "the terrain was thin" into
        // a timeout, which is the wrong sentence about the right world.
        out.add(stage("wd.journey09Iron", JourneyStage.IRON, 90_000, WorldDriverJourneyScenes::iron));
        out.add(stage("wd.journey10PortalKit", JourneyStage.PORTAL_KIT, 60_000,
                WorldDriverJourneyScenes::portalKit));
        // 100 000, because this rung's length is the seed's to decide: it walks to the lava the
        // survey found, sinks a shaft as deep as that lava is, and climbs the same height back
        // carrying it. A surface pool costs almost nothing and a deepslate pool costs all of this.
        out.add(stage("wd.journey11Obsidian", JourneyStage.OBSIDIAN, 100_000,
                WorldDriverJourneyScenes::obsidian));
        // 250 000, and the number is the trip bill rather than caution: this rung descends once,
        // carves a frame into the rock at the lava's own level, and then makes ten short walks
        // between the pool and the mould. Building at the SURFACE instead would cost ten climbs out
        // of a 36-block shaft, through the one mechanism this ladder already knows is unreliable.
        out.add(stage("wd.journey12PortalLit", JourneyStage.PORTAL_LIT, 250_000,
                WorldDriverJourneyScenes::portalLit));
        // 3 000: standing in a portal is an ~80-tick wait for a player, and the only other cost is
        // the couple of steps from where the striking left the body.
        out.add(stage("wd.journey13Nether", JourneyStage.NETHER, 3_000,
                WorldDriverJourneyScenes::nether));
        // 14-15 (nether) and 16-20 (the End) live in their own classes: this file is over the
        // 3000-line source budget, and those rungs are the two blocks that can leave cleanly.
        // Order is the ladder's contract, so they are appended exactly where their placeholders were.
        out.addAll(JourneyNetherRungs.rungs());
        out.addAll(JourneyEndRungs.rungs());

        out.add(Scene.of("wd.journey99Verdict", 200, WorldDriverJourneyScenes::verdict));

        // A rehearsal is the SAME ladder with one rung isolated and its preconditions put there by
        // hand — see JourneyRehearsal for why that is worth having and for the four things that keep
        // a green rehearsal from ever reading as a green climb. It rewrites the list built above
        // rather than registering a second one, so a rung added or re-budgeted here is rehearsable
        // without a parallel list to keep in step. Off unless its own property is set: a normal run
        // and all six gates never reach this line.
        JourneyStage rehearse = JourneyRehearsal.target();
        if (rehearse != null) return JourneyRehearsal.rewrite(out, rehearse);
        return List.copyOf(out);
    }

    /**
     * One rung, at the required-ness {@link JourneyStage#gating()} declares.
     *
     * <p>The scene's {@code required} flag is read from the stage rather than written here, so
     * promoting a rung is one edit in one place and cannot half-happen.
     */
    /**
     * A rung. Registered with {@code withArena(false)}, because the journey never enters its plot.
     *
     * <p>{@code JourneyRig}'s class note says it plainly: the harness force-loads each scene's arena
     * and the journey leaves it immediately — it plays at world spawn and walks for kilometres. So
     * every rung was inheriting a wait on ground it does not use, and that wait failed real ladders:
     * {@code only 0 of 9 arena chunks ever loaded} took rung 9 in one run and rungs 9 AND 10 in
     * another. Shrinking the arena is not the fix and is actively worse — {@code 0 of 1} never loads
     * either, since one chunk cannot have the loaded neighbours an entity-ticking promotion needs.
     *
     * <p>This drops only the WAIT; the plot is still force-loaded and still audited, and
     * {@code ctx.level()} is unchanged. Safe here specifically because no rung reads
     * {@code ctx.origin()} — the journey's coordinates all come from {@link JourneyRoute}.
     */
    private static Scene stage(String name, JourneyStage rung, int budget,
                               java.util.function.Consumer<SceneContext> body) {
        return Scene.of(name, budget, body).withRequired(rung.gating()).withArena(false);
    }

    /** A rung nobody has written the steps for yet. Never required — it is a placeholder for work,
     *  not a sensor for a bug. */
    private static Scene unscripted(String name, JourneyStage rung) {
        return Scene.of(name, 200, ctx -> {
            JourneyRig rig = JourneyRig.enter(ctx, rung);
            rig.attempting("尚未脚本化：这一级的写死步骤还没写");
            ctx.skip("NOT_SCRIPTED: " + rung.name() + "(" + rung.label() + ") 的写死步骤尚未编写");
        }).withRequired(false);
    }

    // =====================================================================================
    // The always-on marker.
    // =====================================================================================

    /**
     * Proof that the ladder is still here, whether or not this run climbs it.
     *
     * <p>Costs one tick and asserts nothing about the game. What it asserts is about the SUITE: the
     * ladder has this many rungs, this is the floor, and this run is or is not armed. A family that
     * silently stopped registering — a dropped service entry, a shadow merge that ate the
     * {@code META-INF/services} line — changes those numbers in a five-minute gate instead of
     * hiding behind a journey nobody ran.
     */
    private static Scene armedMarker() {
        return Scene.of("wd.journeyArmed", 100, ctx -> {
            ctx.record("journey.armed", armed());
            ctx.record("journey.rungs", JourneyStage.values().length);
            ctx.record("journey.floor", JourneyLedger.FLOOR.name());
            ctx.record("journey.summit", JourneyStage.summit().name());
            ctx.record("journey.surveyed", JourneyRoute.surveyed());
            ctx.expect(JourneyStage.values().length).as("ladder rungs").isGreaterThan(1);
            ctx.passNote("journey ladder " + (armed() ? "ARMED" : "disarmed")
                    + " — " + JourneyStage.values().length + " rungs, floor=" + JourneyLedger.FLOOR
                    + ", summit=" + JourneyStage.summit()
                    + ", route " + (JourneyRoute.surveyed() ? "surveyed" : "UNSURVEYED"));
        });
    }

    // =====================================================================================
    // 01 — recon. The survey, and the guard that keeps it honest.
    // =====================================================================================

    /**
     * Survey the fixed seed, and fail when a baked constant no longer describes it.
     *
     * <p>Two jobs in one scene because they are the same measurement read twice. On a world nobody
     * has surveyed it records what it found and passes loudly, so a human can paste the constants
     * into {@link JourneyRoute}. On a world that HAS been surveyed it re-derives the same values and
     * asserts they still hold — which is what turns every downstream stage's hardcoded coordinate
     * from a liability into a fixture.
     *
     * <p>The failure it exists to prevent is specific and expensive: a stale coordinate does not
     * report "the survey moved", it reports "the bot could not gather wood", and the investigation
     * goes looking for a bot bug that is not there.
     */
    private static void recon(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.RECON);
        JourneyLedger.reset(ctx.level().getGameTime());
        rig.attempting("侦察这颗种子的地标");

        ServerLevel level = ctx.level();
        long seed = level.getSeed();
        BlockPos spawn = level.getSharedSpawnPos();
        rig.evidence("seed", seed);
        rig.evidence("spawn", spawn.getX() + "," + spawn.getY() + "," + spawn.getZ());
        rig.evidence("spawn.biome", level.getBiome(spawn).unwrapKey()
                .map(k -> k.location().toString()).orElse("?"));
        rig.evidence("spawn.surfaceY", level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn).getY());

        // The seed is the one thing that must hold before any other constant means anything: a
        // survey of a different world is not a stale survey, it is a meaningless one.
        ctx.expect(seed).as("world seed (StageWright forces this in server.properties)")
                .isEqualTo(JourneyRoute.SEED);

        Map<String, JourneyRoute.Found> survey = JourneyRoute.survey(ctx);
        survey.forEach((name, found) -> rig.evidence("survey." + name, found.asRecord()));

        // NOT surveying the lava lake here — and the reason it is out is weaker than it first looked,
        // which is worth saying plainly rather than leaving a confident comment behind.
        //
        // JourneyRoute.surveyLavaLake exists and compiles. Two attempts to run it from here hit a
        // ten-minute wall, and the first reading was "the scan forces chunk generation and is too
        // slow". That reading is NOT established: the same wall was then hit by a run that executed
        // ZERO scenes, so most or all of it is this task's own startup and world provisioning —
        // `runJourneyServer` does not honour `-Pstagewright.scenes`, so there is no cheap way to
        // exercise rung 1 alone, and nothing here was ever timed in isolation.
        //
        // So this is parked, not diagnosed. Before wiring it in, time the scan on its own (an arena
        // scene can do it in ~200ms) instead of inferring its cost from a task that takes minutes to
        // reach its first line. See JourneyRoute.lavaLake for what still needs it and why.

        // Pasteable, because the alternative is a human transcribing coordinates out of a log.
        for (String line : JourneyRoute.asConstants(survey)) {
            WorldDriverCommon.LOG.info("[journey/recon] {}", line);
        }

        if (!JourneyRoute.surveyed()) {
            // First run on this world. Nothing to compare against yet — record and say so.
            rig.reach("首次侦察完成，常量待烘入 JourneyRoute（见日志 [journey/recon] 行）");
            return;
        }

        // Surveyed already: this is the staleness guard.
        ctx.check(spawn).as("world spawn").isEqualTo(JourneyRoute.spawn);
        checkLandmark(ctx, survey, "firstTree", JourneyRoute.firstTree);
        // secondTree is deliberately NOT checked, because it is no longer a claim — it is taken from
        // this run's own survey, right here. Five runs on this seed produced five answers, and both
        // repeated coordinates read as air on the following run, while firstTree three blocks away
        // never moved; see JourneyRoute#secondTree for the whole measurement. Adopting the survey's
        // answer is what keeps the wood rung working, and printing survey.secondTree every run is
        // what keeps the instability from disappearing just because nothing fails on it any more.
        JourneyRoute.Found second = survey.get("secondTree");
        JourneyRoute.secondTree = second == null || second.where() == null
                ? JourneyRoute.UNSURVEYED : second.where();
        checkLandmark(ctx, survey, "firstStone", JourneyRoute.firstStone);
        checkLandmark(ctx, survey, "firstIron", JourneyRoute.firstIron);
        checkLandmark(ctx, survey, "secondIron", JourneyRoute.secondIron);
        checkLandmark(ctx, survey, "secondIronDescent", JourneyRoute.secondIronDescent);
        checkLandmark(ctx, survey, "thirdIron", JourneyRoute.thirdIron);
        checkLandmark(ctx, survey, "thirdIronDescent", JourneyRoute.thirdIronDescent);
        checkLandmark(ctx, survey, "stoneDescent", JourneyRoute.stoneDescent);
        checkLandmark(ctx, survey, "firstGravel", JourneyRoute.firstGravel);
        checkLandmark(ctx, survey, "ironDescent", JourneyRoute.ironDescent);
        checkLandmark(ctx, survey, "firstWater", JourneyRoute.firstWater);
        // Lava is ADOPTED, not checked, and for a different reason than secondTree's. The band the
        // survey scans was widened — its ceiling used to sit below this swamp's own surface, so a
        // surface pool could not have been reported however close it was — and a baked constant
        // produced by the narrower search is not a claim about the seed, it is a claim about the old
        // question. Checking it would fail the rung that fixed it. What is recorded instead is the
        // move: `lava.movedFrom` says the widening changed the answer, and by how much, which is the
        // whole finding.
        JourneyRoute.Found lava = survey.get("firstLava");
        if (lava != null && lava.where() != null) {
            if (!lava.where().equals(JourneyRoute.firstLava)) {
                rig.evidence("lava.movedFrom", JourneyRoute.firstLava.toShortString()
                        + " → " + lava.where().toShortString());
            }
            JourneyRoute.firstLava = lava.where();
        }
        chooseAReachablePool(ctx, rig);
        checkLandmark(ctx, survey, "stronghold", JourneyRoute.stronghold);
        rig.reach("地标与烘入常量一致");
    }

    /**
     * Replace the surveyed lava with the nearest pool the ladder can actually sink a shaft beside.
     *
     * <p>The survey's nearest answer is not always a plan, and on this seed it is not: the closest
     * pool sits under the swamp's water table, and every one of the <b>280</b> columns within eight
     * blocks of it was rejected for having fluid in the twelve blocks below its own surface. A pool
     * you cannot dig next to is a coordinate. The ore landmarks learned this years earlier — see
     * {@link JourneyRoute#nearestUnderDryGround}, added when the nearest iron turned out to be under
     * a pond — and lava was surveyed without it.
     *
     * <p>Done at RECON, deliberately, and this is the cheaper half of the lesson. The obsidian rung
     * is the last rung: learning there that the terrain will not take a shaft costs a full run of
     * everything below it, twenty-five minutes, once per guess. Recon reads the same fact in about a
     * second, at minute one, and every candidate it rejected is recorded — which is how the rule
     * that rejected all 280 was identified as the broken thing rather than the terrain.
     */
    private static void chooseAReachablePool(SceneContext ctx, JourneyRig rig) {
        ServerLevel level = ctx.level();
        List<JourneyRoute.Found> pools = JourneyRoute.poolsInBand(level, level.getSharedSpawnPos(),
                "minecraft:lava", LAVA_POOL_RADIUS, LAVA_POOL_TOP, LAVA_POOL_BOTTOM,
                LAVA_POOLS_APART, LAVA_POOLS_TRIED);
        rig.evidence("lava.pools", pools.size() + " 处："
                + pools.stream().map(f -> f.where().toShortString()
                        + "(" + Math.round(f.distance()) + "m)").toList());
        chooseALavaLake(ctx, rig, pools);
        for (JourneyRoute.Found pool : pools) {
            int sky = JourneyTerrain.daylightAt(level, pool.where());
            Map<String, Integer> why = new java.util.LinkedHashMap<>();
            BlockPos dig = JourneyTerrain.pickDigColumn(level, pool.where(), sky, why);
            if (dig == null) {
                rig.evidence("lava.rejected." + pool.where().toShortString(), why.toString());
                continue;
            }
            JourneyRoute.firstLava = pool.where();
            rig.evidence("lava.chosen", pool.where().toShortString()
                    + "，" + Math.round(pool.distance()) + " 格外，地表 y=" + sky
                    + "，下挖柱 " + dig.getX() + "," + dig.getZ());
            return;
        }
        // Nothing usable. Not a recon failure — the ladder below this does not need lava — but the
        // obsidian rung's skip should be able to name the reason rather than discover it.
        rig.evidence("lava.chosen", "无：以上每一处旁边都下不去井");
    }

    /**
     * Pick the lake the PORTAL rung casts from — a different landmark from the fill point, and
     * keeping them separate is the entire lesson of five red runs.
     *
     * <p>{@code firstLava} is chosen for exactly one property: that a shaft can descend beside it.
     * The OBSIDIAN rung then <b>spends</b> it — filling a bucket takes the source block itself — so
     * the rung after it arrives to find, measured, <b>zero</b> sources within 48 blocks. Widening
     * that search 16 → 40 → 48 bought nothing, because the missing dimension was never the radius:
     * this seed's nearest pool is a one-cell pocket, and the pools that hold ten cells are the deep
     * ones at y ≈ -14..-36. Ten casts need ten distinct sources, so this rung needs the biggest
     * pool in the survey, not the closest.
     *
     * <p>Counted here rather than at use, per the standing rule that the caller knows this seed:
     * the rung should be handed a lake, not sent hunting for one forty blocks underground holding
     * a bucket it cannot refill.
     */
    private static void chooseALavaLake(SceneContext ctx, JourneyRig rig, List<JourneyRoute.Found> pools) {
        ServerLevel level = ctx.level();
        List<String> sizes = new ArrayList<>();
        BlockPos best = JourneyRoute.UNSURVEYED;
        int bestN = 0;
        for (JourneyRoute.Found pool : pools) {
            int n = countSourcesAround(level, pool.where(), 8, 4);
            // Big is not the same as reachable — this seed's LARGEST pool was already rejected as a
            // fill point because all 280 columns around it had fluid under them, and a lake the body
            // cannot dig down beside is a coordinate, not a landmark. Same gate as firstLava's.
            Map<String, Integer> why = new java.util.LinkedHashMap<>();
            boolean diggable = JourneyTerrain.pickDigColumn(level, pool.where(), JourneyTerrain.daylightAt(level, pool.where()), why) != null;
            sizes.add(pool.where().toShortString() + "=" + n + (diggable ? "" : "(下不去井)"));
            if (diggable && n > bestN) {
                bestN = n;
                best = pool.where();
            }
        }
        rig.evidence("lake.sizes", String.join("  ", sizes));
        if (bestN >= RING.length) {
            JourneyRoute.lavaLake = best;
            JourneyRoute.lavaLakeSources = bestN;
            rig.evidence("lake.chosen", best.toShortString() + " 有 " + bestN
                    + " 格源块（浇十块要十格；firstLava 那处会被 OBSIDIAN 级用掉）");
        } else {
            rig.evidence("lake.chosen", "无：最大的一处也只有 " + bestN + " 格源块，不够浇十块");
        }
    }

    /** Lava SOURCE cells in a box around {@code centre} — the count that decides whether a pool is
     *  a lake or a pocket. The pools were already generated by the survey that found them. */
    private static int countSourcesAround(ServerLevel level, BlockPos centre, int r, int h) {
        int n = 0;
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -h; dy <= h; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    if (level.getFluidState(c).isSource() && level.getBlockState(c).is(Blocks.LAVA)) n++;
                }
        return n;
    }

    /** How far out to look for a usable pool. Wider than the survey's own lava radius, because the
     *  nearest pool is not necessarily one this ladder can use and the next one may be some way on. */
    private static final int LAVA_POOL_RADIUS = 80;
    private static final int LAVA_POOL_TOP = 90;
    private static final int LAVA_POOL_BOTTOM = -60;

    /** How far apart two hits must be to count as different pools rather than one lake. */
    private static final int LAVA_POOLS_APART = 16;

    /** How many pools to test before giving up. Each test is a few hundred column checks. */
    private static final int LAVA_POOLS_TRIED = 12;
    /** Compare one surveyed landmark against its baked constant, softly — the first mismatch is
     *  rarely the informative one, and a stale survey usually moves several at once. */
    private static void checkLandmark(SceneContext ctx, Map<String, JourneyRoute.Found> survey,
                                      String name, BlockPos baked) {
        if (baked.equals(JourneyRoute.UNSURVEYED)) return;
        JourneyRoute.Found found = survey.get(name);
        ctx.check(found == null ? null : found.where()).as("surveyed " + name).isEqualTo(baked);
    }

    /**
     * Assert the baked coordinate still holds the KIND of thing it was baked for.
     *
     * <p>The weaker sibling of {@link #checkLandmark}, for a landmark whose survey is not
     * reproducible. Equality catches drift and also catches a search that saw less this time; this
     * catches only the first, which is the one that breaks a rung. A constant that no longer holds a
     * log is stale in the way that matters — the rung walks there and mines nothing — and a constant
     * that merely is not the closest log any more still works perfectly.
     */
    private static void checkLandmarkStillHolds(SceneContext ctx, String name, BlockPos baked,
                                                net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag) {
        if (baked.equals(JourneyRoute.UNSURVEYED)) return;
        var state = ctx.level().getBlockState(baked);
        ctx.check(state.is(tag)).as("baked " + name + " at " + baked.toShortString()
                + " still holds " + tag.location() + " (found " + state.getBlock() + ")").isTrue();
    }

    // =====================================================================================
    // 02 — spawn. A body, empty-handed, where the world puts a player.
    // =====================================================================================

    /**
     * Stand a body at world spawn with nothing in its pockets.
     *
     * <p>The rung everything else stands on, and the one place the run is allowed to create
     * anything. It asserts the starting conditions rather than assuming them: a body that began
     * holding a stone pickaxe would make every rung above it a lie, and an inventory that was not
     * checked is exactly how that happens.
     */
    private static void spawn(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.SPAWN);
        rig.attempting("在世界出生点创建空手身体");

        ServerWorldDriver body = rig.spawnBody();
        var fp = body.fakePlayer();

        int carried = 0;
        for (int i = 0; i < fp.getInventory().getContainerSize(); i++) {
            carried += fp.getInventory().getItem(i).getCount();
        }
        rig.evidence("spawn.pos", Math.round(fp.getX()) + "," + Math.round(fp.getY()) + "," + Math.round(fp.getZ()));
        rig.evidence("spawn.dimension", rig.dimension());
        rig.evidence("spawn.carriedItems", carried);

        ctx.expect(carried).as("items carried at spawn (a playthrough starts empty-handed)").isEqualTo(0);
        ctx.expect(rig.dimension()).as("spawn dimension").isEqualTo("minecraft:overworld");
        ctx.expect(fp.isAlive()).as("body is alive").isTrue();

        // Write down where the animals are, while it is still early enough to be true — and here
        // rather than in RECON, which is where this first went and where it cannot go: recon runs
        // BEFORE this rung, so it has no body, and every prey query is a query about the body's
        // surroundings (`nearestPreyTarget` centres on it, `seeAtLeast` pins ITS chunks). Recon
        // reads terrain, which needs only a level. This reads entities, which needs somewhere to
        // stand. Six runs died on `还没有身体 —— SPAWN 阶段没有成功创建 avatar` before that landed.
        surveyTheHerd(rig, () ->
                rig.reach("空手立于出生点 " + Math.round(fp.getX()) + "," + Math.round(fp.getZ())));
    }

    // =====================================================================================
    // 03 — wood. ROADMAP N0's first half, scripted against the surveyed tree.
    // =====================================================================================

    /**
     * Walk to the surveyed tree and cut it.
     *
     * <p>Two verbs, in the order an agent would use them: navigate to the known tree, then mine the
     * logs there. The mining radius is small on purpose — the tree's position is known, so a wide
     * search would be the planner doing work the script already did, and a failure would stop naming
     * the driver.
     */
    private static void wood(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.WOOD);
        if (requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();
        rig.attempting("走到已侦察的树并砍下原木");

        BlockPos tree = JourneyRoute.firstTree;
        rig.evidence("target.tree", tree.getX() + "," + tree.getY() + "," + tree.getZ());

        // Walk to the trunk's COLUMN. The surveyed cell is a log 4-5 blocks up the tree, and
        // Goal.Near judges 3D distance, so "within 3 of that log" means "get off the ground" —
        // a bot standing at the foot of the tree is already four blocks away by that measure
        // and keeps climbing to satisfy a goal it has effectively met. It passed for as long as
        // the pathfinder happened to pillar in time and then, unchanged, spent 1304 ticks
        // ending nine blocks off. Reaching UP is the miner's job (see wd.serverMineCanopyRadius);
        // navigation's job is the column. Same correction as the iron rung, same reason.
        rig.drive(new IntentProcess(new Intent(new Goal.XZ(tree.getX(), tree.getZ(), 2))), 8_000, () -> {
            BlockPos at = rig.player().blockPosition();
            double away = Math.hypot(at.getX() - tree.getX(), at.getZ() - tree.getZ());
            rig.evidence("arrived.horizontalDistance", Math.round(away));
            rig.evidence("arrived.y", at.getY());
            if (away > 5) {
                ctx.fail("走不到树下：目标柱 " + tree.getX() + "," + tree.getZ() + "，停在 " + at
                        + "（水平相距 " + Math.round(away) + " 格）");
                return;
            }
            rig.attempting("砍树：MineProcess 拿不到原木");
            // Quota and radius are sized by what the LADDER needs, not by what proves mining works.
            // Three logs is the real floor: 1 log = 4 planks, a crafting table eats 4, a wooden
            // pickaxe wants 3 planks + 2 sticks (2 more planks) = 5. Two logs is one plank short,
            // which is exactly how the first run of this stage failed — the wood leg passed on
            // `logs >= 1` and handed the tool leg an impossible bill. A stage's assertion has to be
            // the NEXT stage's precondition, or the chain reports its shortfall one rung too late.
            // The radius is widened for the same reason: a swamp oak's canopy is scattered, and six
            // blocks around one log reached only two of them.
            // Radius 24, not 12. The reach gate capped what ONE tree can give: the bot cannot climb,
            // so it takes the trunk logs at eye level and leaves the crown, and a swamp oak
            // yields two or three that way. Measured across seven runs the haul was 7, 6, 5, 5,
            // 4, 3 and 1 logs — the low end starves the tool rung. The fix is more TREES in
            // range, not a softer assertion: a player who cannot reach the crown walks to the
            // next trunk, and the assertion stays the next rung's real bill.
            rig.drive(new MineProcess(List.of(logIdAt(ctx, tree)), 6, 24), 8_000, () ->
                    fellSecondTreeIfShort(ctx, rig, () -> {
                int logs = totalLogs(rig);
                rig.evidence("logs", logs);
                // Per species, because the total is not what a craft can spend: the resolver plans
                // one plank variant, so seven logs split across oak and birch can still leave a
                // recipe one oak_log short. A single number here read as "plenty of wood" through
                // two failures that were really "plenty of the wrong wood".
                rig.evidence("logs.kinds", logKinds(rig));
                // Eight, and it has been three and five. Three was the tool rung's own bill, sized
                // against the wrong rung. Five was the bill through the FLOOR with two crafting-table
                // remakes costed in — a table (4 planks), sticks (2), a wooden pickaxe (3), two
                // replacements (8), seventeen of the twenty that five logs give. Then a run felled
                // exactly five, needed a THIRD remake, and died 缺 1 个 oak_log with 3 planks in the
                // bag. The recipes are fixed; the table tax is what varies, so the margin has to be
                // sized against the tax. An assertion that is not the next rungs' real bill just
                // moves where the shortfall gets reported.
                ctx.expect(logs).as("logs gathered (the ladder's bill through the furnace is "
                        + LOGS_THE_LADDER_NEEDS + ")").isAtLeast(LOGS_THE_LADDER_NEEDS);
                // There is no story/mine_wood. The first version of this line asked for one and
                // got UNREGISTERED — which is exactly why advancementStatus distinguishes "nobody
                // registered this id" from "not earned yet": a typo and an unearned advancement
                // are the same `false` otherwise. The tree's marker is the advancement tree's own
                // root, awarded for holding a crafting table, so it belongs on the rung below.
                rig.noteAdvancement("minecraft:story/root");
                rig.reach("砍到 " + logs + " 根原木");
            }));
        });
    }

    /**
     * Walk to the second surveyed trunk and cut that one too, when the first did not pay the bill.
     *
     * <p>Conditional rather than unconditional: one tree sometimes IS enough (hauls of 7 and 6 have
     * been measured), and a rung that always walks to a second tree spends a minute of every run
     * buying wood it already has. The condition is the assertion's own number, so the two cannot
     * drift apart.
     *
     * <p>Skips quietly while {@link JourneyRoute#secondTree} is UNSURVEYED, which is what it is on
     * the run that first prints it — the recon scene surveys, a human pastes the constant in, and
     * this leg starts working on the run after. Recording {@code wood.secondTree=UNSURVEYED} is what
     * keeps that state from reading as "the second tree had nothing".
     */
    private static void fellSecondTreeIfShort(SceneContext ctx, JourneyRig rig, Runnable then) {
        fellMoreTreesUntilPaid(ctx, rig,
                ctx.level().getBlockState(JourneyRoute.firstTree).getBlock(), EXTRA_TRUNKS, then);
    }

    /**
     * Keep walking to trunks until the bill is paid or the trunks run out.
     *
     * <p>One extra tree was not enough, and the run that proved it is worth keeping: the first trunk
     * gave <b>5</b> logs — exactly {@link #LOGS_THE_LADDER_NEEDS}, so the top-up never fired — and the
     * stone rung then died {@code 缺 1 个 oak_log} because it had to remake the crafting table three
     * times rather than the two the bill was sized for. Five logs is twenty planks and the run spent
     * seventeen; a third replacement table is four more. **A bill with no slack is a bill that only
     * works when nothing goes wrong**, and the table tax is exactly the thing that goes wrong.
     *
     * <p>So the target went up and the number of trunks it may visit went up with it. The second is
     * the surveyed one when there is a surveyed one; after that it takes the nearest trunk of the
     * SAME species at least {@link #TRUNK_MIN_AWAY} blocks off — far enough that it is a different
     * tree and not the crown the body could not reach on the one it just cut. Same species because
     * the resolver plans one plank variant, so a mixed haul is two piles and not one.
     */
    private static void fellMoreTreesUntilPaid(SceneContext ctx, JourneyRig rig,
                                               net.minecraft.world.level.block.Block species,
                                               int left, Runnable then) {
        int logs = totalLogs(rig);
        if (left == EXTRA_TRUNKS) rig.evidence("wood.firstTreeLogs", logs);
        if (logs >= LOGS_THE_LADDER_NEEDS || left <= 0) { then.run(); return; }

        int visit = EXTRA_TRUNKS - left + 2;          // the first tree was #1
        BlockPos next = left == EXTRA_TRUNKS && !JourneyRoute.secondTree.equals(JourneyRoute.UNSURVEYED)
                ? JourneyRoute.secondTree
                : nearestTrunkBeyond(rig, species, TRUNK_MIN_AWAY, TRUNK_SEARCH);
        if (next == null) {
            // Not a failure here — the assertion above owns the shortfall, and it can say how much
            // wood there was. This only says the ladder ran out of TREES rather than out of budget.
            rig.evidence("wood.noMoreTrunks", "带着 " + logs + " 根原木，方圆 " + TRUNK_SEARCH
                    + " 格内没有第 " + visit + " 棵 " + species.getName().getString());
            then.run();
            return;
        }
        rig.evidence("wood.tree" + visit, next.toShortString() + " (此前 " + logs + " 根)");
        rig.attempting("木头不够（" + logs + "/" + LOGS_THE_LADDER_NEEDS + "），走到第 " + visit + " 棵树");
        // Same XZ-column approach as the first tree, and for the same reason: Goal.Near judges 3D
        // distance, so aiming at a log five blocks up asks the body to leave the ground.
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(next.getX(), next.getZ(), 2))), 6_000,
                () -> rig.settle(new MineProcess(List.of(logIdAt(ctx, next)), 6, 24), 8_000,
                        () -> fellMoreTreesUntilPaid(ctx, rig, species, left - 1, then)));
    }

    /** How many trunks beyond the first the wood rung may visit. Three, because a swamp oak yields
     *  two to four to a body that cannot climb, and the bill is eight. */
    private static final int EXTRA_TRUNKS = 3;

    /** How far a trunk must be to count as a different TREE — the crown of the one just felled is
     *  still standing and still made of logs, and mining toward it buys nothing the reach gate
     *  already refused. */
    private static final int TRUNK_MIN_AWAY = 8;

    /** How far to look for the next trunk. */
    private static final int TRUNK_SEARCH = 40;

    /** The nearest log of one species at least {@code minAway} blocks off. */
    private static BlockPos nearestTrunkBeyond(JourneyRig rig,
                                               net.minecraft.world.level.block.Block species,
                                               int minAway, int radius) {
        ServerLevel level = rig.ctx().level();
        BlockPos from = rig.player().blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        double floor = (double) minAway * minAway;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos c = from.offset(dx, dy, dz);
                    if (level.getBlockState(c).getBlock() != species) continue;
                    double d = from.distSqr(c);
                    if (d < floor || d >= bestD) continue;
                    bestD = d;
                    best = c;
                }
            }
        }
        return best;
    }

    /**
     * The bill the wood rung is sized against — see the assertion for the arithmetic.
     *
     * <p>Eight, and the two raises it took to get there are the whole lesson. Three was the tool
     * rung's own bill, and it starved the rungs above it. Five was the bill through the furnace with
     * <b>two</b> crafting-table remakes costed in — seventeen planks of the twenty five logs give —
     * and a run that felled exactly five then died {@code 缺 1 个 oak_log} needing a third remake.
     * Eight is the same bill with slack: the table tax is the thing that varies, so the margin has
     * to be sized against the tax rather than against the recipes.
     */
    private static final int LOGS_THE_LADDER_NEEDS = 8;

    /**
     * Walk to a surface COLUMN, and try again from wherever the walker actually stopped.
     *
     * <p>The retry is not defensive padding. {@code IntentProcess} reports its goal reached for a
     * partial path — the failure {@code wd.serverWalkerArrivedShort} owns — so a single drive can
     * come back "done" with the body somewhere else entirely. Measured on the iron rung: the leg
     * ended cleanly with the body <b>88 blocks</b> from the column it was sent to, having crossed a
     * swamp, and the rung reported "cannot reach the descent point" for what was really "the walker
     * stopped early and nobody asked it to continue". Re-planning from the new position is what a
     * player does when they end up short, and each attempt starts from a strictly better place.
     *
     * <p>Each attempt is a {@link JourneyRig#settle} rather than a drive, so a leg that burns its
     * budget costs an attempt instead of the rung — the distance check below is what decides, and it
     * has a number to report either way. {@code <what>.walkAttempts} says how many it took, which is
     * the reading that says whether the walker is getting better or worse; a rung that quietly
     * needed three tries every run is a finding this would otherwise hide.
     *
     * <p><b>A retry that changes nothing is not a retry.</b> The re-plan above assumes each attempt
     * starts somewhere better, and it usually does — but a body can also be WEDGED: the iron rung
     * once ended a leg at {@code 78,63,96} with the descent column 22 blocks away, and then spent
     * two more attempts and four minutes issuing about ninety searches <i>from that same cell</i>,
     * every one of them burning its 100 000-node budget without finding a route. Three identical
     * questions get three identical answers. So an attempt that ends where it began does not simply
     * repeat: it aims at the MIDPOINT first, which asks the pathfinder a shorter question it may
     * well be able to answer, and then resumes. That is what a player does when a route will not
     * come — walk part of the way and look again — and it needs nothing from the engine.
     */
    private static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, Runnable onArrived, Runnable onStuck) {
        walkToColumn(rig, what, x, z, tolerance, budget, MAX_WALK_ATTEMPTS, onArrived, onStuck);
    }

    private static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, int left, Runnable onArrived, Runnable onStuck) {
        BlockPos before = rig.player().blockPosition();
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(x, z, tolerance))), budget, () -> {
            BlockPos at = rig.player().blockPosition();
            double away = Math.hypot(at.getX() - x, at.getZ() - z);
            int attempt = MAX_WALK_ATTEMPTS - left + 1;
            rig.evidence(what + ".arrivedDistance", Math.round(away));
            rig.evidence(what + ".walkAttempts", attempt);
            if (away <= ARRIVED_WITHIN) { onArrived.run(); return; }
            // What the walker itself said about the leg. Ninety searches in a row left no record of
            // WHY beyond the search-begin lines, so a wedge and a slow crossing read the same.
            rig.evidence(what + ".goto." + attempt,
                    "end=" + rig.body().botState().mc_goto.endReason
                            + " err=" + rig.body().botState().mc_goto.lastError);
            if (left <= 1) { onStuck.run(); return; }
            double moved = Math.hypot(at.getX() - before.getX(), at.getZ() - before.getZ());
            if (moved >= WEDGED_UNDER) {
                walkToColumn(rig, what, x, z, tolerance, budget, left - 1, onArrived, onStuck);
                return;
            }
            int mx = (at.getX() + x) / 2;
            int mz = (at.getZ() + z) / 2;
            rig.evidence(what + ".viaMidpoint", mx + "," + mz + " (卡在 " + at.toShortString() + ")");
            rig.settle(new IntentProcess(new Intent(new Goal.XZ(mx, mz, 3))), Math.max(600, budget / 2),
                    () -> walkToColumn(rig, what, x, z, tolerance, budget, left - 1, onArrived, onStuck));
        });
    }

    /** How far a leg must move for the next attempt to be a different question. Four blocks: a body
     *  that shuffled within its own cell has not found a new vantage point, and asking the same
     *  pathfinder the same question from it costs the whole leg's budget to learn nothing. */
    private static final int WEDGED_UNDER = 4;

    /** How many times a leg re-plans before the rung calls it unreachable. */
    private static final int MAX_WALK_ATTEMPTS = 3;

    /** Close enough to a target column to start working there. */
    private static final int ARRIVED_WITHIN = 5;

    /** Whatever kind of log the surveyed tree actually is — biome decides, the script must not. */
    private static String logIdAt(SceneContext ctx, BlockPos tree) {
        var block = ctx.level().getBlockState(tree).getBlock();
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "minecraft:oak_log" : id.toString();
    }

    /** Logs of every species the body is carrying — the wood stage does not care which tree it was. */
    /** The haul broken down by species — see the evidence line for why the total is not enough. */
    private static String logKinds(JourneyRig rig) {
        StringBuilder sb = new StringBuilder();
        for (String id : LOG_KINDS) {
            int n = rig.carrying(id);
            if (n == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(id.substring(id.indexOf(':') + 1)).append('=').append(n);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static final List<String> LOG_KINDS = List.of("minecraft:oak_log", "minecraft:birch_log",
            "minecraft:spruce_log", "minecraft:jungle_log", "minecraft:acacia_log",
            "minecraft:dark_oak_log", "minecraft:cherry_log", "minecraft:mangrove_log");

    private static int totalLogs(JourneyRig rig) {
        int total = 0;
        for (String id : LOG_KINDS) {
            total += rig.carrying(id);
        }
        return total;
    }

    // =====================================================================================
    // 04 — wooden tools. Planks, a table, a pickaxe: the rung that opens stone.
    // =====================================================================================

    /**
     * Turn logs into the wooden tier.
     *
     * <p>Driven through {@link CraftProcess}, which is the same process {@code mc.bot.craft} routes
     * to — so a failure here is a failure of the verb an agent would actually call. The pickaxe is
     * the assertion that matters: planks and sticks are intermediates, and a run that made them and
     * could not close the last step has not reached this rung.
     */
    private static void woodTools(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.WOOD_TOOLS);
        rig.generousPathfinding();
        rig.attempting("合成木镐（planks → sticks → crafting_table → wooden_pickaxe）");

        rig.drive(new CraftProcess("minecraft:crafting_table", 1), 4_000, () -> {
            rig.evidence("crafting_table", rig.carrying("minecraft:crafting_table"));
            rig.attempting("合成木镐：CraftProcess 走不完 3×3 路径");
            craftKeepingTheTable(rig, "minecraft:wooden_pickaxe", 6_000, () -> {
                int picks = rig.carrying("minecraft:wooden_pickaxe");
                rig.evidence("wooden_pickaxe", picks);
                // NOT story/upgrade_tools — that one is the STONE pickaxe ("Getting an Upgrade").
                // story/root is the crafting table this rung just made, which is the first
                // advancement a vanilla playthrough earns at all.
                rig.noteAdvancement("minecraft:story/root");
                ctx.expect(picks).as("wooden pickaxes crafted").isAtLeast(1);
                rig.reach("木镐 ×" + picks + " 到手，石器层已解锁");
            });
        });
    }

    // =====================================================================================
    // 05 — the stone tier. ROADMAP N2's first half.
    // =====================================================================================

    /**
     * Walk to the surveyed stone, mine it with the pickaxe the run made, and step up a tier.
     *
     * <p>The first rung whose tool came from the rung below it, which is the property a chain buys
     * that a stack of isolated scenes cannot: a wooden pickaxe is what makes stone drop cobblestone
     * rather than nothing, so this passing means the wood rung really delivered a working tool and
     * not merely an item with the right id.
     *
     * <p>Eight cobblestone is sized off what the rungs above need, on the lesson the wood leg
     * taught: a stone pickaxe is 3, a stone sword is 1, a furnace is 8. Asking for exactly a
     * pickaxe's worth would hand the furnace rung the same impossible bill the tool rung got.
     */
    private static void stoneTools(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.STONE_TOOLS);
        if (requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();
        rig.attempting("走到已侦察的石头");

        BlockPos stone = JourneyRoute.firstStone;
        BlockPos stoneShaft = JourneyRoute.stoneDescent;
        rig.evidence("target.stone", stone.getX() + "," + stone.getY() + "," + stone.getZ());
        rig.evidence("target.shaft", stoneShaft.getX() + "," + stoneShaft.getY() + "," + stoneShaft.getZ());
        rig.evidence("pickaxe.before", rig.carrying("minecraft:wooden_pickaxe"));

        // The column again, not the block: the surveyed stone is at y=59 under a surface of 63,
        // so Goal.Near(stone, 3) is the buried-target trap in its downward form. See the wood
        // and iron rungs for the same correction — approach horizontally, let the miner reach.
                    // Radius 0, not 2. A shaft is dug where the body STANDS, and the survey only
            // certified one column as dry: measured on the stone rung, a tolerance of two
            // put the body two cells off it and the hole filled with water on the fourth
            // pass (`shaft.4 below=water`), after which it floated instead of sinking and
            // mining water is a no-op. In a swamp, two blocks is the whole difference.
            rig.drive(new IntentProcess(new Intent(new Goal.XZ(stoneShaft.getX(), stoneShaft.getZ(), 0))), 8_000, () -> {
            BlockPos at = rig.player().blockPosition();
            double away = Math.hypot(at.getX() - stoneShaft.getX(), at.getZ() - stoneShaft.getZ());
            rig.evidence("arrived.horizontalDistance", Math.round(away));
            rig.evidence("arrived.y", at.getY());
            if (away > 5) {
                ctx.fail("走不到石头处：目标柱 " + stoneShaft.getX() + "," + stoneShaft.getZ() + "，停在 " + at
                        + "（水平相距 " + Math.round(away) + " 格）");
                return;
            }
            // Dig down to the stone's own level before mining it, the same scripted shaft the iron
            // rung uses. Not an optimisation — a correctness fix that arrived with honest mining.
            // The surveyed stone is four courses under a swamp surface, and now that the avatar can
            // no longer break what it cannot reach, standing on the grass means peeling four blocks
            // of overburden for EVERY stone in the vein. That is the same twenty-block haul done
            // five times over, and it timed out at 10 000 ticks with the leg still running. Sinking
            // once and mining sideways is what a player does, and it puts every drop at foot level.
            rig.attempting("挖竖井下到石层：身体没能随井下降");
            BotConfig.allowPlace = false;    // see the iron rung: a paving walker will not sink
            JourneyShaft.descendByMining(rig, stone.getY() + 1, () -> {
                BotConfig.allowPlace = true;
                rig.evidence("descent.landedY", rig.player().blockPosition().getY());

            rig.attempting("挖石头：MineProcess 拿不到圆石");
                // Quota 32 / radius 16. Ten with a radius of ten came back with five — a swamp surface
                // exposes very little stone, so the radius, not the quota, is what binds. Twenty then
                // came back with nineteen and still under-bought, because the bill is longer than it
                // looks: a stone pickaxe (3), the pillar back out of this shaft (one cobble per
                // course, and the shaft is nine deep), and the furnace (8). Paying the exit out of the
                // furnace's share is what stranded a run at the bottom of its own hole.
                rig.drive(new MineProcess(List.of("minecraft:stone"), 32, 16), 26_000, () -> {
                    int cobble = rig.carrying("minecraft:cobblestone");
                    rig.evidence("cobblestone", cobble);
                    rig.noteAdvancement("minecraft:story/mine_stone");
                    // Cobblestone, not stone: a wooden pickaxe drops the cobbled form, and a run that
                    // somehow ended holding `stone` would have been given it rather than have mined it.
                    ctx.expect(cobble).as("cobblestone mined (pickaxe 3 + exit pillar ~9 + furnace 8)")
                            .isAtLeast(20);
                    // Climb out FIRST, then craft. The order was the other way round and it cost a
                    // run: the body finished its shaft, kept the table in its bag, and the craft
                    // failed with
                    // `需要工作台（背包里有，但脚边没有可放置的空位——先清出一格）` —
                    // a one-wide shaft has no free cell to stand a table in. Nothing here needs to
                    // be done underground; the cobble is already in the bag, and a player climbs out
                    // and crafts on the grass. Doing it at the bottom also made the rung's outcome
                    // depend on how the last shaft course happened to be shaped, which is why it
                    // passed one run and failed the next on identical code.
                    JourneyShaft.climbOut(rig, JourneyRoute.stoneDescent.getY(), () -> {
                    rig.attempting("合成石镐：CraftProcess 走不完");
                    // The table check belongs here too, and its absence is what failed this rung
                    // once already: the wooden-pickaxe craft one rung below can eat the table, so
                    // the FIRST 3×3 craft after it is the one that finds itself without one. It is
                    // no longer spelled out here because craftKeepingTheTable does it for every
                    // craft on the ladder — which is the point: the guard stopped being something
                    // each rung had to remember.
                    craftKeepingTheTable(rig, "minecraft:stone_pickaxe", 6_000, () -> {
                        int picks = rig.carrying("minecraft:stone_pickaxe");
                        rig.evidence("stone_pickaxe", picks);
                        // The ingredients on hand and the crafter's own verdict. A run failed here
                        // holding 18 cobblestone, which rules out the obvious cause and leaves "the
                        // table, the sticks, or the process" — three answers this line separates and
                        // the cobble count alone cannot.
                        rig.evidence("sticks", rig.carrying("minecraft:stick"));
                        rig.evidence("planks", rig.carryingAnyOf(List.of("minecraft:oak_planks",
                                "minecraft:spruce_planks", "minecraft:birch_planks")));
                        rig.evidence("craftingTable", rig.carrying("minecraft:crafting_table"));
                        rig.evidence("craft.lastError", String.valueOf(rig.body().botState().craft.lastError));
                        rig.noteAdvancement("minecraft:story/upgrade_tools");
                        ctx.expect(picks).as("stone pickaxes crafted").isAtLeast(1);
                        rig.reach("石镐 ×" + picks + " 到手，剩余圆石 "
                                + rig.carrying("minecraft:cobblestone"));
                    });
                    });
                });
            });
        });
    }

    // =====================================================================================
    // 06 — food. The first rung whose target is alive.
    // =====================================================================================

    /**
     * Hunt something edible, kill it, collect the drop.
     *
     * <p>The first rung that cannot be scripted as a coordinate, and the reason is worth stating
     * because it bounds what "every step is scripted" can mean. Animals are placed by worldgen — so
     * on a fixed seed the run starts with the same herd in the same field — but they WANDER, and the
     * rungs below this one spend a hundred seconds of world ticks getting here. By the time this
     * body arrives the herd is somewhere else. A baked coordinate would be a coordinate for where a
     * sheep used to be.
     *
     * <p>So the script names a SPECIES rather than a place, which is what an agent with perfect
     * knowledge would actually say here — "there are cows in this swamp" — and is exactly the shape
     * of {@code mc.bot.combat}'s own {@code targetType} parameter. The engine still gets no help
     * finding it, which keeps the failure attributable.
     *
     * <p>Passive mobs exist here despite the suite pinning {@code doMobSpawning=false}: that rule
     * governs ONGOING spawning, not the animals worldgen placed when the chunk was made. So this
     * rung does not need {@link JourneyRig#liveWorld}, and deliberately does not ask for it — a
     * scene that turned mob spawning on would also be turning on the hostile mobs that this
     * invulnerable body cannot meaningfully fight.
     */
    private static void food(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.FOOD);
        rig.generousPathfinding();
        rig.attempting("找到并猎杀一只可食用动物");

        // Look further than the body walks. The travelling pin is two chunks, which is right for
        // walking and wrong for searching: an entity scan only sees loaded chunks, so a 96-block
        // search from a 32-block pin has two thirds of its radius empty by construction and says
        // "there are no animals" rather than "I cannot see that far". Measured, on a swamp that has
        // cows, from a body the stone rung had walked away from spawn.
        ctx.cleanup(JourneyRig::seeNormally);
        JourneyRig.seeAtLeast(PREY_SEARCH_CHUNKS);
        // A beat before looking: the ticket is applied on the await tick and the chunks it pulls in
        // have to arrive before anything in them exists to be found.
        rig.settle(new HoldStill(40), 100, () -> hunt(ctx, rig, true));
    }

    /**
     * Find prey, walking back to spawn once if there is none in view.
     *
     * <p>Where this rung STARTS is wherever the stone rung left the body, and that is not a fixed
     * place: it is the far end of whatever cobblestone the stone rung had to walk to. Seventeen runs
     * found a cow and the eighteenth found {@code [minecraft:cat, minecraft:frog]} — swamp fauna,
     * ninety-six blocks of it — because the body simply began somewhere else.
     *
     * <p>The retry therefore has to CHANGE THE QUESTION, which the walk-retry on this ladder already
     * learned once the hard way. Re-scanning from the same cell would ask the identical question and
     * get the identical answer. Spawn is the one cell this seed has an animal claim about, so the
     * body goes there and looks again — and only then is "no animals" a statement about the world.
     */
    /**
     * Write down where the animals are, while it is still early enough to be true.
     *
     * <p>Recon surveys terrain, and terrain stays put. This one does not — which is exactly why the
     * food rung needs it taken at the BOTTOM of the ladder rather than four rungs up. By the time
     * that rung runs, the wood and stone rungs have spent a couple of thousand ticks and the body is
     * wherever the last cobblestone was; what it can see from there is luck. The spawn rung looks
     * from spawn on tick one, which is as close to worldgen's own answer as this ladder can get.
     */
    private static void surveyTheHerd(JourneyRig rig, Runnable then) {
        // Widen the pin, THEN WAIT, then look. `seeAtLeast` only sets the radius: the ticket is
        // applied on an await tick and the chunks it pulls in have to arrive before anything in them
        // exists to be found. The first version of this scanned on the same line and reported
        // `survey.animal=无（96 格内没有掉落食物的动物）` from world spawn on tick one — while the
        // food rung four rungs later hunted a cow without trouble. That answer was not "there are no
        // animals", it was "I cannot see that far", which is the exact distinction the food rung's
        // own comment was already written to make. A survey that cannot see reports an empty world
        // and looks like a working feature.
        JourneyRig.seeAtLeast(PREY_SEARCH_CHUNKS);
        rig.settle(new HoldStill(40), 100, () -> {
            JourneyRig.Prey prey = rig.nearestPreyTarget(PREY_SEARCH_BLOCKS);
            JourneyRig.seeNormally();
            if (prey == null) {
                rig.evidence("survey.animal", "无（" + PREY_SEARCH_BLOCKS + " 格内没有掉落食物的动物，"
                        + "已按 " + PREY_SEARCH_CHUNKS + " 区块钉住并等到装载）");
            } else {
                rig.evidence("survey.animal", prey.species() + " @ " + prey.where().toShortString()
                        + "（" + Math.round(prey.distance()) + " 格）");
            }
            then.run();
        });
    }

    /** The second look, at {@link #PREY_SEARCH_WIDE} blocks. Walks to whatever it finds and hands
     *  back to the normal hunt, which then sees it inside the ordinary radius. */
    private static void huntWide(SceneContext ctx, JourneyRig rig) {
        JourneyRig.Prey far = rig.nearestPreyTarget(PREY_SEARCH_WIDE);
        rig.evidence("prey.wide", far == null
                ? "无（" + PREY_SEARCH_WIDE + " 格、" + PREY_SEARCH_WIDE_CHUNKS + " 区块也没有）"
                : far.species() + " @ " + far.where().toShortString()
                  + "（" + Math.round(far.distance()) + " 格）");
        if (far == null) { hunt(ctx, rig, false); return; }
        rig.attempting("走向 " + Math.round(far.distance()) + " 格外的 " + far.species());
        walkToColumn(rig, "prey", far.where().getX(), far.where().getZ(), 6, 16_000,
                () -> rig.settle(new HoldStill(20), 60, () -> hunt(ctx, rig, false)),
                () -> rig.settle(new HoldStill(20), 60, () -> hunt(ctx, rig, false)));
    }

    private static void hunt(SceneContext ctx, JourneyRig rig, boolean mayWalkHome) {
        JourneyRig.Prey prey = rig.nearestPreyTarget(PREY_SEARCH_BLOCKS);
        rig.evidence("animalsNearby", rig.animalsNearby(PREY_SEARCH_BLOCKS));
        if (prey == null && mayWalkHome) {
            // Look FURTHER from where the body is, rather than walking somewhere else to look.
            //
            // Two premises were tried and both were wrong, and the second was refuted by the very
            // survey written to support it. First: "spawn is where the animals are" — a run found
            // [cat, frog] within 96 blocks of the stone rung's endpoint AND within 96 of spawn.
            // Second: "then survey the herd at spawn on tick one and walk there" — with the chunk
            // pin applied and the load waited out, that survey reports 无 as well. **This seed has
            // no food animal within 96 blocks of spawn, at tick one or later.** The runs that eat
            // find their cow wherever the wood and stone rungs happened to carry the body, which is
            // 77 blocks from THERE and well over 96 from spawn.
            //
            // So the thing to change is the radius, not the standpoint. `seeAtLeast` pins chunks
            // around the body, so a wider scan is a bigger pin and one more wait — no walking, no
            // guess about which direction the grass is in.
            rig.evidence("prey.noneAt", rig.player().blockPosition().toShortString()
                    + " —— 附近只有 " + rig.animalsNearby(PREY_SEARCH_BLOCKS)
                    + "，改用 " + PREY_SEARCH_WIDE + " 格再找一次");
            JourneyRig.seeAtLeast(PREY_SEARCH_WIDE_CHUNKS);
            rig.settle(new HoldStill(40), 100, () -> huntWide(ctx, rig));
            return;
        }
        if (prey == null) {
            ctx.fail("方圆 " + PREY_SEARCH_BLOCKS + " 格内没有掉落食物的动物 —— 附近只有 "
                    + rig.animalsNearby(PREY_SEARCH_BLOCKS)
                    + "；出生点是沼泽，这一级可能需要一条先去草地群系的腿");
            return;
        }
        rig.evidence("prey", prey.species());
        rig.evidence("prey.distance", Math.round(prey.distance()));

        // Walk first, engage second. CombatProcess scans 32 blocks and gives up at once when nothing
        // matches, so handing it a species whose only instance is 40 blocks away is asking it to
        // fail — and it fails in two ticks, which reads like a broken verb rather than a bad script.
        rig.attempting("走向猎物 " + prey.species());
        rig.drive(new IntentProcess(new Intent(new Goal.Near(prey.where(), 6))), 8_000, () -> {
            rig.evidence("prey.arrived", rig.nearestPrey(24));
            rig.attempting("猎杀 " + prey.species() + "：CombatProcess 没能拿到生肉");
            rig.drive(new CombatProcess(CombatProcess.Mode.KILL, null, prey.species()), 6_000, () -> {
                int raw = rig.carryingAnyOf(RAW_FOODS);
                rig.evidence("rawFood", raw);
                ctx.expect(raw).as("raw food collected from the kill").isAtLeast(1);
                walkHome(rig, () -> rig.reach("猎到 " + prey.species() + "，得生肉 ×" + raw));
            });
        });
    }

    /** How far the food rung looks for something to eat. */
    /**
     * Walk back to world spawn, and record it when that fails.
     *
     * <p>This rung is the only one that goes where the TERRAIN says rather than where the route
     * says: the hunt follows an animal, and seed 5471's swamp puts the nearest cow tens of blocks
     * off in a direction nothing else on the ladder uses. Every rung above then starts from wherever
     * the chase ended, which is how the iron rung came to report "cannot reach the descent point"
     * from {@code 4,65,114} — <b>88 blocks</b> away, with the walker's own verdict
     * {@code goal unreachable from here}. That is not the iron rung's failure and it should not be
     * reported as one.
     *
     * <p>So the hunt ends where it began. Spawn is the anchor every surveyed landmark was measured
     * from and the one place the ladder knows is connected to the rest of its route.
     *
     * <p><b>Best-effort, and loud about it.</b> A body that got its food has climbed this rung
     * whether or not it found its way home, so a failed return does not fail FOOD — it records
     * {@code food.strandedAt}, which is what lets the NEXT rung's failure be traced to this one
     * instead of investigated on its own terms.
     */
    private static void walkHome(JourneyRig rig, Runnable then) {
        BlockPos home = rig.ctx().level().getSharedSpawnPos();
        BlockPos at = rig.player().blockPosition();
        rig.evidence("food.huntEndedAt", at.toShortString() + "，离出生点 "
                + Math.round(Math.hypot(at.getX() - home.getX(), at.getZ() - home.getZ())) + " 格");
        rig.attempting("打完猎回出生点，别把下一级留在荒野里");
        walkToColumn(rig, "home", home.getX(), home.getZ(), 3, 12_000,
                () -> {
                    rig.evidence("food.home", rig.player().blockPosition().toShortString());
                    then.run();
                },
                () -> {
                    BlockPos stuck = rig.player().blockPosition();
                    rig.evidence("food.strandedAt", stuck.toShortString() + "，离出生点 "
                            + Math.round(Math.hypot(stuck.getX() - home.getX(), stuck.getZ() - home.getZ()))
                            + " 格 —— 上面的每一级都会从这里出发");
                    then.run();
                });
    }

    private static final int PREY_SEARCH_BLOCKS = 96;

    /** Chunks pinned while it looks — enough that {@link #PREY_SEARCH_BLOCKS} is a real radius and
     *  not a promise the loaded world cannot keep. 96 blocks is six chunks; one more for the edge. */
    private static final int PREY_SEARCH_CHUNKS = 7;

    /** The second look's radius, used only when the first finds nothing. 176 blocks — eleven chunks
     *  — because the measured miss is not marginal: this seed's spawn has no food animal inside 96
     *  at all, and the cow a successful run eats sits 77 blocks from wherever the stone rung ended,
     *  which can be most of a hundred blocks from anywhere the failing run looked. Not the default,
     *  because pinning 23×23 chunks to answer a question 96 blocks usually answers is a cost every
     *  run would pay for the benefit of one. */
    private static final int PREY_SEARCH_WIDE = 176;

    private static final int PREY_SEARCH_WIDE_CHUNKS = 11;

    /** The raw drops a first kill can plausibly yield — species is the swamp's business, not ours. */
    private static final List<String> RAW_FOODS = List.of(
            "minecraft:beef", "minecraft:porkchop", "minecraft:chicken",
            "minecraft:mutton", "minecraft:rabbit", "minecraft:cod", "minecraft:salmon");

    // =====================================================================================
    // 08 — the furnace. Eight of the cobblestone the stone rung banked.
    // =====================================================================================

    /**
     * Craft a furnace from the cobblestone already in the bag.
     *
     * <p>No walking and no mining: the stone rung was sized to leave enough behind, so this rung is
     * purely a 3×3 craft. That makes it the cheapest possible regression sensor for the station-menu
     * seam — if {@code openStationMenu} ever breaks again, this is the rung that says so in eight
     * seconds rather than the iron rung saying it after a two-minute dig.
     */
    private static void furnace(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.FURNACE);
        rig.generousPathfinding();
        rig.attempting("用已有圆石合成熔炉");
        rig.evidence("cobblestone.before", rig.carrying("minecraft:cobblestone"));

        craftKeepingTheTable(rig, "minecraft:furnace", 6_000, () -> {
            int furnaces = rig.carrying("minecraft:furnace");
            rig.evidence("furnace", furnaces);
            // The two readings that made the last failure legible in one run instead of three. A
            // rung holding 24 cobblestone and crafting nothing is not a materials problem, and
            // "furnace=0" alone cannot say which of the station, the grid or the process it was.
            rig.evidence("craftingTable", rig.carrying("minecraft:crafting_table"));
            rig.evidence("craft.lastError", String.valueOf(rig.body().botState().craft.lastError));
            ctx.expect(furnaces).as("furnaces crafted").isAtLeast(1);
            reclaimTableIfLeftStanding(rig, () -> rig.reach("熔炉 ×" + furnaces + " 到手"));
        });
    }

    /**
     * Make sure the body owns a crafting table before a 3×3 craft needs one.
     *
     * <p>Not belt-and-braces: measured, the run <b>loses</b> its table. {@code CraftProcess} places
     * one when no table is in reach and reclaims it on the way out, and the reclaim is best-effort
     * by design (a craft is never failed over cleanup). The stone rung crafts at the bottom of its
     * own shaft, and the run climbed out with {@code craftingTable=0} — after which the furnace rung
     * sat on 24 cobblestone and crafted nothing.
     *
     * <p><b>Pick it up before paying for a new one.</b> Re-crafting alone was the first version, and
     * the arithmetic of a failed run is what argued it down: the stone rung ended holding 33
     * cobblestone, 2 sticks and {@code craft.lastError=缺 1 个 oak_log} — everything a stone pickaxe
     * needs except the table, from a run that had felled four logs. Sixteen planks, and the ledger
     * only balances if a table was bought TWICE (4 table + 2 sticks + 3 pickaxe + 4 table = 13, and
     * 3 were left). The tax is therefore roughly one log per craft, and the ladder has crafts all the
     * way to the portal — so "fell more wood" does not converge, it just moves which rung starves.
     *
     * <p>A reclaim that failed did not destroy the table; it left it on the ground where the craft
     * happened, which is exactly the shape {@link #collectByHand} was written for. One walk costs
     * nothing and usually returns it. Re-crafting stays as the fallback for the case where the drop
     * is genuinely gone, and the two are recorded under different keys — {@code recovered} versus
     * {@code remade} — because "the sweep dropped it and we picked it up" and "the sweep dropped it
     * somewhere unreachable" are different reports about {@code CraftProcess}, and a single
     * "we paid for a table" line cannot tell them apart.
     *
     * <p>Still deliberately not "fix the reclaim": that is an engine change, and these are steps a
     * player would take with the seed in front of them.
     */
    private static void ensureCraftingTable(JourneyRig rig, Runnable then) {
        if (rig.carrying("minecraft:crafting_table") > 0) { makeRoomForAStation(rig, then); return; }

        // Which of the two ways the reclaim failed? Measured, the walk-and-collect above found
        // nothing on either rung that needed it, and "no dropped table" has two very different
        // causes: it was never broken (still standing, and then this rung needs no table at all —
        // CraftProcess uses one in reach), or it was broken and the item went somewhere the drop
        // search cannot see. The inventory reads 0 in both cases, so the world has to be asked.
        BlockPos standing = rig.nearestBlock("minecraft:crafting_table", 32, 6);
        rig.evidence("craftingTable.standing", standing == null ? "none" : standing.toShortString());
        if (standing != null) {
            // Take it WITH you, rather than walking back to it and leaving it there again. Walking
            // back was the first version and it only moved the problem one rung along: the table
            // stays where the last craft happened, the body walks a hundred blocks to mine iron, and
            // the next rung finds `craftingTable.standing=none` and buys another one. Measured, that
            // tax is what ran a run out of wood at PORTAL_KIT holding four iron ingots. Mining it
            // back into the bag costs one block-break and ends the tax for every rung after this
            // one — which is also simply what a player does with their table.
            rig.attempting("取回还立着的工作台");
            rig.settle(new IntentProcess(new Intent(new Goal.Near(standing, 2))), 600,
                    () -> rig.mineBlock(standing, 600,
                            () -> collectByHand(rig, "minecraft:crafting_table", 1, () -> {
                                rig.evidence("craftingTable.reclaimed",
                                        rig.carrying("minecraft:crafting_table"));
                                then.run();
                            })));
            return;
        }

        rig.attempting("找回工作台：上一级把它留在地下了");
        collectByHand(rig, "minecraft:crafting_table", 1, () -> {
            if (rig.carrying("minecraft:crafting_table") > 0) {
                rig.evidence("craftingTable.recovered", true);
                makeRoomForAStation(rig, then);
                return;
            }
            rig.evidence("craftingTable.remade", true);
            rig.attempting("补做工作台：地上也没有，只能再买一张");
            rig.drive(new CraftProcess("minecraft:crafting_table", 1), 6_000, () -> {
                if (rig.carrying("minecraft:crafting_table") > 0) { makeRoomForAStation(rig, then); return; }
                // Out of wood, which is the only way this craft fails. Go and get some — that is
                // what a player does, and it is the answer the wood BILL has twice failed to be.
                rig.evidence("craftingTable.remadeError",
                        String.valueOf(rig.body().botState().craft.lastError));
                topUpWood(rig, () -> rig.drive(new CraftProcess("minecraft:crafting_table", 1), 6_000,
                        () -> {
                            rig.evidence("craftingTable.afterTopUp",
                                    rig.carrying("minecraft:crafting_table"));
                            makeRoomForAStation(rig, then);
                        }));
            });
        });
    }

    /**
     * Go and fell a tree, mid-ladder, because a craft ran out of wood.
     *
     * <p>The ladder has raised its wood bill twice — 3 → 5 → 8 — and been eaten through both times,
     * because what varies is not the recipes but the <b>crafting-table tax</b>: `CraftProcess`
     * places a table and reclaims it only best-effort, and a table that is neither standing within
     * 32 blocks nor lying as a drop is simply gone. Measured on one run: eight logs, thirty-two
     * planks, nine planks of actual recipes, and three planks left — the balance spent on tables the
     * ladder could not find again.
     *
     * <p>A bill cannot be sized against that, so this stops trying. When a craft fails for want of
     * wood the run walks to the nearest trunk and cuts it, exactly as a player would, and tries once
     * more. Same species as the route's first tree, because the recipe resolver commits to one plank
     * variant and a birch log does not answer {@code 缺 1 个 oak_log}; any species is better than
     * none, so a species miss falls back rather than failing.
     */
    private static void topUpWood(JourneyRig rig, Runnable then) {
        var species = rig.ctx().level().getBlockState(JourneyRoute.firstTree).getBlock();
        BlockPos trunk = nearestTrunkBeyond(rig, species, 0, TRUNK_SEARCH);
        rig.evidence("wood.topUpTarget", trunk == null ? "无" : trunk.toShortString());
        if (trunk == null) { then.run(); return; }
        rig.attempting("木头用光了，去砍一棵补上");
        BlockPos target = trunk;
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(target.getX(), target.getZ(), 2))), 6_000,
                () -> rig.settle(new MineProcess(List.of(logIdAt(rig.ctx(), target)), 6, 24), 8_000,
                        () -> {
                            rig.evidence("wood.toppedUp", totalLogs(rig) + " 根");
                            then.run();
                        }));
    }

    // =====================================================================================
    // 09 — iron. ROADMAP N3: mine the ore, smelt it, hold the ingot.
    // =====================================================================================

    /**
     * Mine the surveyed iron and smelt it into ingots.
     *
     * <p>The first rung that needs the world to TICK rather than merely to be written to. Everything
     * below it resolves inside the ticks its own processes take; a furnace has to cook, which is
     * wall-clock the scene has to wait out. That is what the journey's {@code drive}/{@code await}
     * are built for and what separates this suite from the 222 scenes that spin their avatars inside
     * a single server tick — a furnace in one of those never finishes, which is exactly why
     * {@code wd.serverSmeltStationOpens} can only assert the loading and not the smelting.
     *
     * <p>The ore is at y=55 against a surface of 63, so this is a real dig rather than a surface
     * pickup, and it is done with the stone pickaxe the run made three rungs ago — iron ore drops
     * nothing to a wooden one, so a pass here is also evidence that the tier gate is being honoured.
     */
    private static void iron(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.IRON);
        if (requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();

        rig.evidence("stonePickaxe.before", rig.carrying("minecraft:stone_pickaxe"));
        // The furnace this rung will need, counted BEFORE the dig rather than after the smelt
        // fails. A run reported "背包里没有可放置的熔炉" two rungs after FURNACE asserted it held
        // one, and holdItem searches the whole inventory — so "never had it here" and "had it
        // and lost it in between" are the two answers, and only this line separates them.
        rig.evidence("furnace.before", rig.carrying("minecraft:furnace"));
        if (JourneyRoute.ironDescent.equals(JourneyRoute.UNSURVEYED)) {
            rig.attempting("下挖点未标定：JourneyRoute.ironDescent 还是 UNSURVEYED");
            ctx.skip("UNSURVEYED: 先跑 wd.journey01Recon，把 ironDescent 常量烘进 JourneyRoute");
            return;
        }

        // Dig veins until the bill is paid or the survey runs out, rather than a fixed number of
        // them. One vein was never enough on this seed and TWO turned out not to be either: a run
        // took vein1.raw_iron=0 and vein2.raw_iron=3, smelted three, and the kit rung failed on
        // `缺 1 个 iron_ingot`. Veins here run one to three ore, so "how many veins" has no stable
        // answer — "enough ore" does, and it is the only number the rung above actually cares about.
        mineVeinsUntilPaid(ctx, rig, 0);
    }

    /**
     * Work surveyed veins one at a time until the kit's bill is met.
     *
     * <p>Read from {@link JourneyRoute} at call time rather than from a list built once, because
     * recon fills those constants in during THIS run and a list captured at class-init would hold
     * the UNSURVEYED placeholders forever.
     */
    private static void mineVeinsUntilPaid(SceneContext ctx, JourneyRig rig, int index) {
        BlockPos ore = switch (index) {
            case 0 -> JourneyRoute.firstIron;
            case 1 -> JourneyRoute.secondIron;
            case 2 -> JourneyRoute.thirdIron;
            default -> JourneyRoute.UNSURVEYED;
        };
        BlockPos shaft = switch (index) {
            case 0 -> JourneyRoute.ironDescent;
            case 1 -> JourneyRoute.secondIronDescent;
            case 2 -> JourneyRoute.thirdIronDescent;
            default -> JourneyRoute.UNSURVEYED;
        };
        if (shaft.equals(JourneyRoute.UNSURVEYED) || ore.equals(JourneyRoute.UNSURVEYED)) {
            // Out of veins. Smelt what there is: the rung's own claim is that the driver can mine
            // and smelt iron, and it can prove that with one ingot. Whether that is enough for the
            // rung ABOVE is the rung above's assertion to make, and it names the shortfall exactly.
            rig.evidence("iron.veinsWorked", index);
            smeltIron(ctx, rig);
            return;
        }
        String tag = "vein" + (index + 1);
        mineIronVein(ctx, rig, tag, ore, shaft, () -> {
            int raw = rig.carrying("minecraft:raw_iron");
            rig.evidence("raw_iron.after" + tag, raw);
            if (raw >= RAW_IRON_TO_MINE) {
                rig.evidence("iron.veinsWorked", index + 1);
                smeltIron(ctx, rig);
                return;
            }
            mineVeinsUntilPaid(ctx, rig, index + 1);
        });
    }

    /** What the portal kit above costs in ingots: a bucket (3) and a flint-and-steel (1). The iron
     *  rung stops digging once it has this much, because every extra vein is another shaft and
     *  another climb, and a rung that digs for materials nobody needs is spending the budget of the
     *  rungs above it. */
    private static final int IRON_INGOTS_THE_KIT_COSTS = 4;

    /** How much RAW iron to come home with, which is not the same number. A furnace load can end
     *  short of its input — a run reported {@code 部分完成：只炼出 5/6（燃料耗尽）} — so mining
     *  exactly the bill means arriving one ingot under it whenever the coal runs out first. One
     *  spare ore is the cheapest insurance the ladder has: the alternative is a second furnace trip
     *  four rungs deep, or a portal kit that fails for a reason three rungs below it. */
    private static final int RAW_IRON_TO_MINE = IRON_INGOTS_THE_KIT_COSTS + 1;

    /**
     * Walk to one surveyed vein, sink a shaft to it, mine it out, pick up what fell, climb back.
     *
     * <p>Everything here was learned the hard way and each correction is recorded where it bit.
     *
     * <p><b>Walk to the COLUMN, not to the block, and to the DRY column, not the ore's own.</b>
     * {@code Goal.Near(ore, 3)} judges 3D distance, so it reported "could not reach the iron" about
     * a body standing directly on top of it nine blocks up; and the ore's own column is under a
     * swamp pond, so a shaft cannot start there at all. Tolerance 0, not 2: a shaft is dug where the
     * body STANDS and the survey certifies one column — measured on the stone rung, a tolerance of
     * two put the body off the certified column and the hole filled with water on the fourth pass.
     *
     * <p><b>The descent is spelled out rather than searched.</b> {@code DescendProcess} walked the
     * body nineteen cells sideways for one block down and then refused ("no safe descent stride —
     * all cardinals + own column wet/hazard/unbreakable"): a staircase needs somewhere to step into
     * and a swamp does not have it. Letting {@code MineProcess} sink its own break-route reached the
     * ore and lost the drops, because a body on the surface breaking a block four down at arm's
     * length leaves the item at the bottom of a hole it cannot enter. So the rung digs block by
     * block and the body falls in after it, which is what a player does and what puts every drop at
     * foot level. No engine change — the driver's existing single-block mine, against coordinates
     * the caller already knows.
     */
    private static void mineIronVein(SceneContext ctx, JourneyRig rig, String tag,
                                     BlockPos ore, BlockPos shaft, Runnable then) {
        rig.attempting("走到已侦察的铁矿(" + tag + ")");
        rig.evidence(tag + ".iron", ore.getX() + "," + ore.getY() + "," + ore.getZ());
        rig.evidence(tag + ".shaft", shaft.getX() + "," + shaft.getY() + "," + shaft.getZ());
        walkToColumn(rig, tag, shaft.getX(), shaft.getZ(), 0, 10_000, () -> {
            rig.attempting("挖竖井下到矿层(" + tag + ")：身体没能随井下降");
            // Placing OFF for the descent, and this is the whole difference between a shaft that
            // sinks and one that does not. A walker permitted to place treats the hole it just dug
            // as terrain to bridge, and it is carrying dirt from four rungs of digging — so it
            // paves over its own shaft and steps around it.
            BotConfig.allowPlace = false;
            JourneyShaft.descendByMining(rig, ore.getY() + 1, () -> {
                BotConfig.allowPlace = true;    // the miner below wants it back to reach the vein
                BlockPos landed = rig.player().blockPosition();
                rig.evidence(tag + ".landedY", landed.getY());
                rig.evidence(tag + ".column", landed.getX() + "," + landed.getZ());
                mineOreHere(ctx, rig, tag, ore, () -> JourneyShaft.climbOut(rig, shaft.getY(), then));
            });
        }, () -> {
            BlockPos at = rig.player().blockPosition();
            ctx.fail("走不到下挖点(" + tag + ")：目标柱 " + shaft.getX() + "," + shaft.getZ()
                    + "，停在 " + at + "（水平相距 "
                    + Math.round(Math.hypot(at.getX() - shaft.getX(), at.getZ() - shaft.getZ()))
                    + " 格，已重规划 " + MAX_WALK_ATTEMPTS + " 次）");
        });
    }

    /** The sweep at the bottom of one shaft, plus the hand pickup that banks what it drops. */
    private static void mineOreHere(SceneContext ctx, JourneyRig rig, String tag, BlockPos ore,
                                    Runnable then) {
        // Quota 8, and radius 10. Do not raise the radius without reading this: MineProcess anchors
        // its scan on the BODY, re-centring every tick, so a quota the terrain cannot fill does not
        // end the sweep — it turns it into a walk. Measured at radius 14, the body left the vein at
        // 83,60,75 and drifted to 67,62,91, twenty-plus blocks out and back at the surface, on two
        // consecutive runs. The quota is a ceiling and the vein is the floor; the answer to a thin
        // vein is a SECOND vein, which is what JourneyRoute.secondIron is for.
        rig.evidence(tag + ".mineFrom", rig.player().blockPosition().toShortString());
        rig.attempting("挖铁矿(" + tag + ")：MineProcess 拿不到 raw_iron");
        // settle, not drive, and 6 000 rather than 14 000. The quota is a CEILING — the comment
        // above says why raising it does not buy ore — so a sweep that has not finished is a sweep
        // whose vein ran out, and on this seed that is the normal case rather than a fault. Under
        // drive it was fatal: the rung ended `await step exceeded within=14000 ticks` with the vein
        // mined and the ingots never attempted, having spent twelve minutes of a fourteen-minute run
        // walking. What this rung actually requires is asserted below and is not a quota — it is one
        // raw iron — so an unfinished sweep should cost the leg and let the second vein and the
        // smelt have their turn.
        rig.settle(new MineProcess(List.of("minecraft:iron_ore"), 8, 10), 6_000, () -> {
            // The decisive split when this rung fails: an ore still standing means the bot never
            // reached it (navigation/search), an ore turned to air with an empty bag means it broke
            // it and the drop was lost (tool or pickup). Different bugs, different files, and the
            // failure message cannot tell them apart without these lines.
            rig.evidence(tag + ".raw_iron", rig.carrying("minecraft:raw_iron"));
            rig.evidence(tag + ".ore.after", ctx.level().getBlockState(ore).getBlock().toString());
            rig.evidence(tag + ".held", rig.player().getMainHandItem().getItem().toString());
            rig.evidence(tag + ".mine.lastError", String.valueOf(rig.body().botState().mine.lastError));
            // lastError is null on the honest paths too — a mine that swept everything it could
            // reach and a mine that banked the lot both report no error. endReason tells them apart.
            rig.evidence(tag + ".mine.endReason", String.valueOf(rig.body().botState().mine.endReason));
            // Drops on the ground vs items in the bag. "Nothing was mined", "it was mined and never
            // dropped" and "it dropped and was never collected" all read as raw_iron=0, and they
            // live in three different files.
            rig.evidence(tag + ".raw_iron.onGround", rig.dropsNearby("minecraft:raw_iron", 48));
            collectByHand(rig, "minecraft:raw_iron", MAX_PICKUP_LEGS, tag, then);
        });
    }

    /** Cook everything the veins gave, and say what came out. */
    private static void smeltIron(SceneContext ctx, JourneyRig rig) {
        int raw = rig.carrying("minecraft:raw_iron");
        rig.evidence("raw_iron.afterPickup", raw);
        ctx.expect(raw).as("raw iron mined (a stone pickaxe is what makes it drop)").isAtLeast(1);

        // The furnace goes missing the way the crafting table does, and this rung is where it shows.
        // Measured: the rung STARTED with furnace.before=1, mined two veins, towered out of two
        // shafts, and reached the smelt with furnace.after=0 and
        // `smelt.lastError=需要熔炉（背包里没有可放置的熔炉）` — five raw iron and nothing to cook it in.
        // Nothing in this rung smelts before this line, so the furnace was spent by something that
        // wanted A PLACEABLE BLOCK rather than a furnace: the same chooser that had the body holding
        // `minecraft:oak_log` while it mined iron ore. Eight cobblestone rebuilds it and the exit
        // tower left 84, so the scripted answer is simply to check before relying on it.
        // Having the furnace is not the same as being able to set it down, and until now only the
        // CRAFT path asked the second question. Measured: a run reached here with
        // `furnace.carried=true, furnace.after=1` — the furnace plainly in the bag — and still got
        // `需要熔炉（背包里没有可放置的熔炉）`. That message names the wrong cause: `PlaceNearby`
        // holds the item through `holdItem`, which searches all 36 slots and found it; what it could
        // not find was a cell to put it in. The body was standing on the top of the one-wide pillar
        // it had just towered out of the shaft on — air on every side, air under every side.
        ensureCarrying(rig, "minecraft:furnace", "熔炉", () -> makeRoomForAStation(rig, () -> {
        rig.attempting("熔炼铁锭：SmeltProcess 走不完（炉子要真的烧）");
        rig.drive(new SmeltProcess("minecraft:raw_iron", raw, null), 12_000, () -> {
            int ingots = rig.carrying("minecraft:iron_ingot");
            rig.evidence("iron_ingot", ingots);
            rig.evidence("furnace.after", rig.carrying("minecraft:furnace"));
            rig.evidence("smelt.lastError", String.valueOf(rig.body().botState().smelt.lastError));
            rig.noteAdvancement("minecraft:story/smelt_iron");
            ctx.expect(ingots).as("iron ingots smelted").isAtLeast(1);
            rig.reach("铁锭 ×" + ingots + " 出炉");
        });
        }));
    }

    /**
     * Pick the table back up NOW, while the body is still standing next to it.
     *
     * <p>Chasing it later does not converge, and the ladder demonstrated that twice. Searching for a
     * standing table at the next craft works only while the body has not gone anywhere: once the
     * iron rung started working two and three veins, the next craft was a hundred blocks and several
     * shafts away, {@code craftingTable.standing} came back {@code none}, and the run bought another
     * table it could not afford — {@code 缺 1 个 oak_log} with five iron ingots in the bag. Widening
     * that search is a race the body always wins.
     *
     * <p>So the reclaim happens where the cost is fixed: immediately after the craft, one block
     * away. Silent when there is nothing standing, because {@code CraftProcess} does reclaim its own
     * table most of the time and this is the backstop for when it does not.
     */
    /**
     * Craft one thing, with a table to craft it on, and leave holding that table.
     *
     * <p>The three steps have all existed for a while and only three of the ladder's crafts did all
     * three. Every other craft — the planks, the sticks, the wooden pickaxe, the flint-and-steel —
     * placed a table, walked away, and bought another one next time. That is four planks a craft,
     * one log, and it is the tax that keeps running this ladder out of wood: measured, a run holding
     * <b>six raw iron</b> failed to smelt any because the furnace it had to re-craft needed a table,
     * the table needed four planks, and there was not one log left to make them —
     * {@code furnace.remadeError=缺 1 个 oak_log}.
     *
     * <p>Raising the wood bill treats the symptom and it has been raised twice already, 3 → 5 → 8.
     * The tax is what varies, so this closes it instead: <b>every</b> craft now goes
     * ensure-a-table → craft → take the table with you. The reclaim is one block-break at a fixed
     * cost immediately after the craft, which is the only moment the table is guaranteed to be
     * within reach — thirty seconds later the rung has walked a hundred blocks and the 32-block
     * search that would find it again is looking in the wrong place.
     */
    private static void craftKeepingTheTable(JourneyRig rig, String itemId, int budget, Runnable then) {
        craftKeepingTheTable(rig, itemId, budget, true, then);
    }

    private static void craftKeepingTheTable(JourneyRig rig, String itemId, int budget,
                                             boolean mayFetchWood, Runnable then) {
        ensureCraftingTable(rig, () -> rig.drive(new CraftProcess(itemId, 1), budget, () -> {
            String key = itemId.substring(itemId.indexOf(':') + 1);
            String error = String.valueOf(rig.body().botState().craft.lastError);
            rig.evidence(key + ".crafted", rig.carrying(itemId));
            rig.evidence(key + ".craftError", error);
            // One retry, and only for the one cause a retry can fix. "缺 … _log" is the recipe
            // resolver saying the bag is short of wood, and the ladder has a tree for that; every
            // other error would repeat identically, which is the mistake walkToColumn already made
            // once. See topUpWood for why the wood BILL cannot be the answer here.
            if (mayFetchWood && rig.carrying(itemId) == 0 && error.contains("_log")) {
                topUpWood(rig, () -> craftKeepingTheTable(rig, itemId, budget, false, then));
                return;
            }
            reclaimTableIfLeftStanding(rig, then);
        }));
    }

    private static void reclaimTableIfLeftStanding(JourneyRig rig, Runnable then) {
        if (rig.carrying("minecraft:crafting_table") > 0) { then.run(); return; }
        BlockPos standing = rig.nearestBlock("minecraft:crafting_table", 4, 3);
        if (standing == null) { then.run(); return; }
        rig.evidence("craftingTable.tookItAlong", standing.toShortString());
        rig.mineBlock(standing, 600,
                () -> collectByHand(rig, "minecraft:crafting_table", 1, then));
    }

    /**
     * Get the body somewhere a station can actually be placed.
     *
     * <p>Having the table is not the same as being able to use it. {@code CraftProcess} places one,
     * so it needs a neighbouring cell that is both EMPTY and SUPPORTED, and the first version of
     * this checked only the first half. That version reported "already room" and the craft failed
     * anyway with the same {@code 脚边没有可放置的空位}, because of where the ladder stands when it
     * climbs: {@code JourneyShaft.climbOut} towers up a one-wide pillar inside the shaft it dug, so the body ends
     * on a column with air on all four sides and air under all four sides. Plenty of space, nowhere
     * to put anything.
     *
     * <p>So the test is "empty with something under it", and the remedy is to step off the pillar
     * rather than to dig. Bounded, and each attempt walks a few blocks in a different direction:
     * the surface the shaft was sunk from is right there, so one short leg normally reaches it, and
     * a body that cannot find ground in three tries has a finding worth reporting rather than a
     * budget worth raising.
     */
    private static void makeRoomForAStation(JourneyRig rig, Runnable then) {
        makeRoomForAStation(rig, then, MAX_ROOM_ATTEMPTS);
    }

    /**
     * The question {@code PlaceNearby.place} actually asks — eight horizontal offsets across three
     * vertical layers, {@code canBeReplaced} over a support that is neither air nor replaceable.
     *
     * <p>This helper used to ask a different, stricter one: four orthogonal neighbours, foot level
     * only, {@code !blocksMotion()} over {@code blocksMotion()}. Twenty-four cells versus four. So it
     * declared {@code station.noGround} and sent the body walking in places where the real placer
     * would have succeeded immediately — visible in two green runs that carry `station.noGround`
     * beside a craft that worked anyway — and, worse, its walk could leave a good spot for a bad one.
     *
     * <p>Asking the same question as the code that will actually do the placing is the whole fix.
     * Two tests of the same condition that disagree are a bug generator: one of them is always wrong,
     * and which one is not knowable from the failure.
     */
    private static boolean placerWouldFindRoom(ServerLevel lvl, BlockPos foot) {
        int[][] off = {{0, -1}, {0, 1}, {1, 0}, {-1, 0}, {1, -1}, {1, 1}, {-1, -1}, {-1, 1}};
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] o : off) {
                BlockPos cell = foot.offset(o[0], dy, o[1]);
                var cs = lvl.getBlockState(cell);
                var bs = lvl.getBlockState(cell.below());
                if (!cs.canBeReplaced()) continue;
                if (bs.isAir() || bs.canBeReplaced()) continue;
                return true;
            }
        }
        return false;
    }

    /** A cell the body could stand in that ALSO has somewhere to put a station. Searched outward, so
     *  the nearest one wins; a swamp puts the body in water where every neighbouring support is more
     *  water, and the nearest bank is a short walk rather than a fixed compass step. */
    private static BlockPos groundWithRoomNear(ServerLevel lvl, BlockPos foot, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos c = foot.offset(dx, dy, dz);
                    if (lvl.getBlockState(c).blocksMotion()) continue;              // stand here
                    if (lvl.getBlockState(c.above()).blocksMotion()) continue;      // head room
                    if (!lvl.getBlockState(c.below()).blocksMotion()) continue;     // solid underfoot
                    if (!placerWouldFindRoom(lvl, c)) continue;                     // and room to place
                    double d = foot.distSqr(c);
                    if (d < bestD) { bestD = d; best = c; }
                }
            }
        }
        return best;
    }

    private static void makeRoomForAStation(JourneyRig rig, Runnable then, int left) {
        BlockPos foot = rig.player().blockPosition();
        ServerLevel lvl = (ServerLevel) rig.player().level();
        if (placerWouldFindRoom(lvl, foot)) { then.run(); return; }
        if (left <= 0) {
            // Walking has failed three times. Before giving up, DIG a niche — because the two
            // situations this helper serves want opposite remedies and it only ever had one.
            //
            // On a pillar top there is too much air and the answer is to walk to ground. At the
            // bottom of a one-wide shaft there is no air at all, walking cannot go anywhere (every
            // leg ends where it began, which is what `station.noGround` recorded three times), and
            // the answer is to cut a niche in the wall: a side cell that is solid, over a floor that
            // is solid, becomes an empty supported cell the moment it is mined.
            //
            // Measured — the iron rung's exit stalled at `exit.gained=2/22`, leaving the body at the
            // shaft bottom, and the furnace remake then failed on
            // `脚边没有可放置的空位——先清出一格`. The driver's own message says to clear a cell; this
            // is the ladder doing what it was told.
            for (BlockPos side : List.of(foot.north(), foot.south(), foot.east(), foot.west())) {
                if (lvl.getBlockState(side).blocksMotion()
                        && lvl.getBlockState(side.below()).blocksMotion()) {
                    rig.evidence("station.dugNiche", side.toShortString() + " "
                            + lvl.getBlockState(side).getBlock());
                    rig.mineBlock(side, 600, () -> rig.settle(new HoldStill(5), 20, then));
                    return;
                }
            }
            // Neither remedy applies: no ground to walk to and no wall to cut. The craft below will
            // report its own error, and this line is what says the body never had anywhere to begin.
            rig.evidence("station.noGround", foot.toShortString());
            then.run();
            return;
        }
        int step = MAX_ROOM_ATTEMPTS - left;
        // Walk to a cell that ANSWERS the question, not four blocks in a rotating compass direction.
        // The old version stepped +4x, -4x, -4z, +4z+4x by turn, which is a guess: measured, a run
        // stepped from `62,63,64` to `62,63,60` and reported `station.noGround` there too, having
        // moved from one bad cell to another. In a swamp that is the common case — the body is in
        // water, every neighbouring support is more water, and the nearest bank is wherever it is
        // rather than four blocks north.
        BlockPos spot = groundWithRoomNear(lvl, foot, ROOM_SEARCH);
        if (spot == null) {
            rig.evidence("station.noSpotWithin." + step, ROOM_SEARCH + " 格内没有放得下工作台的落脚点");
            makeRoomForAStation(rig, then, 0);          // straight to the niche/give-up branch
            return;
        }
        // Indexed, because evidence overwrites by name and three attempts under one key describe
        // only the last — the same defect the pickup keys already had.
        rig.evidence("station.steppingOff." + step, foot.toShortString() + " → " + spot.toShortString());
        rig.attempting("离开放不下东西的地方，走到 " + spot.toShortString());
        rig.settle(new IntentProcess(new Intent(new Goal.Block(spot))), 900,
                () -> makeRoomForAStation(rig, then, left - 1));
    }

    /** How many short legs the body gets to find ground a station can stand on. */
    private static final int MAX_ROOM_ATTEMPTS = 3;

    /** How far to look for that ground. Twelve blocks: far enough to leave a swamp pond or step off
     *  a pillar, short enough that the leg is a walk rather than an expedition. */
    private static final int ROOM_SEARCH = 12;

    /**
     * Make sure the body is CARRYING a station a process is about to place.
     *
     * <p>The sibling of {@link #ensureCraftingTable}, and the difference is which question the
     * process asks. A craft can use a table standing in reach, so that one may end with the body
     * next to it. {@code SmeltProcess} places its own furnace and says so —
     * {@code 需要熔炉（背包里没有可放置的熔炉）} — so for this one, standing nearby is not good enough
     * and the item has to be in the bag.
     *
     * <p>Three answers in the order they cost: already carried, lying on the ground where whatever
     * spent it dropped it, or bought again. Each is recorded under its own key, because "the station
     * survived", "we walked back for it" and "we paid for a new one" are three different reports
     * about how well the layer below holds on to what the ladder gives it, and a rung that only says
     * it eventually had a furnace hides which.
     */
    private static void ensureCarrying(JourneyRig rig, String itemId, String zh, Runnable then) {
        String key = itemId.substring(itemId.indexOf(':') + 1);
        if (rig.carrying(itemId) > 0) { rig.evidence(key + ".carried", true); then.run(); return; }
        BlockPos standing = rig.nearestBlock(itemId, 32, 6);
        rig.evidence(key + ".standing", standing == null ? "none" : standing.toShortString());
        if (standing != null) {
            // Mine it back rather than noting it and walking on. A station the body placed and
            // walked away from is not gone, and the inventory cannot tell that story — this is the
            // same reclaim the crafting table already does, and it was missing here for no better
            // reason than that the furnace's guard was written first.
            rig.attempting("取回还立着的" + zh);
            rig.settle(new IntentProcess(new Intent(new Goal.Near(standing, 2))), 600,
                    () -> rig.mineBlock(standing, 600, () -> collectByHand(rig, itemId, 1, () -> {
                        rig.evidence(key + ".reclaimed", rig.carrying(itemId));
                        then.run();
                    })));
            return;
        }
        rig.attempting("找回" + zh + "：上一段把它花掉了");
        collectByHand(rig, itemId, 1, () -> {
            if (rig.carrying(itemId) > 0) { rig.evidence(key + ".recovered", true); then.run(); return; }
            rig.evidence(key + ".remade", true);
            rig.attempting("补做" + zh);
            // Through the table guard, like every other craft on this ladder. Bare CraftProcess was
            // the bug: the iron rung re-crafted its furnace with no table in the bag and no free cell
            // to stand one in, the craft failed for want of a station, and the only trace was
            // `furnace.after=0` two lines later — an outcome with no reason attached, which reads as
            // "the craft verb is broken" rather than "it was never given what it needs".
            craftKeepingTheTable(rig, itemId, 6_000, () -> {
                rig.evidence(key + ".remadeCount", rig.carrying(itemId));
                then.run();
            });
        });
    }

    /**
     * Walk onto the drops a mine left behind, one at a time.
     *
     * <p>The scripted counterpart to {@code MineProcess}'s collect sweep, and it exists because the
     * sweep is the one leg of the mining chain the ladder keeps losing material to:
     * {@code broke 2/8, raw_iron=0, raw_iron.onGround=2, collect timed out after 240 ticks}, with
     * the body five blocks from ore it had broken itself.
     *
     * <p>This does not fix the sweep and is not meant to — {@code wd.serverMineHarvestBuried} owns
     * that. What it does is keep the rung's failure attributable: the scene READS the item's own
     * position out of the world and walks to it, so a pickup that still does not happen cannot be
     * blamed on a search. {@code pickup.walks} records how many legs it took, which is the number
     * that says whether the sweep is getting better or worse.
     *
     * <p>Best-effort by design. Each leg is a {@link JourneyRig#settle}, so a drop in a place the
     * walker cannot stand costs one leg rather than the whole rung.
     */
    private static void collectByHand(JourneyRig rig, String itemId, int legs, Runnable then) {
        int slash = itemId.indexOf(':');
        collectByHand(rig, itemId, legs, slash < 0 ? itemId : itemId.substring(slash + 1), then);
    }

    /**
     * As above, under a caller-chosen evidence key.
     *
     * <p>The key is not decoration. Evidence entries overwrite by name, so two veins collecting the
     * same item wrote {@code pickup.walks} / {@code pickup.target} over each other and the surviving
     * pair described only the LAST vein. A run then read {@code vein1.raw_iron=0,
     * vein1.raw_iron.onGround=2} — two ingots' worth lying where the body had just been — beside a
     * {@code pickup.target} ten blocks away at the other vein, which says nothing about whether
     * vein 1's collect ever walked anywhere. Same shape as the shaft and climb keys, which have
     * been indexed from the start for exactly this reason.
     */
    private static void collectByHand(JourneyRig rig, String itemId, int legs, String key, Runnable then) {
        if (legs <= 0) { leftOnTheGround(rig, itemId, key, then); return; }
        BlockPos drop = rig.nearestDrop(itemId, 32);
        if (drop == null) { leftOnTheGround(rig, itemId, key, then); return; }
        rig.evidence(key + ".pickup.walks", MAX_PICKUP_LEGS - legs + 1);
        rig.evidence(key + ".pickup.target", drop.toShortString());
        rig.settle(new IntentProcess(new Intent(new Goal.Block(drop))), 600,
                // A beat on the spot afterwards: vanilla gives a fresh drop a 10-tick pickup delay
                // and the magnet only fires while something is ticking the body.
                () -> rig.settle(new HoldStill(30), 50,
                        () -> collectByHand(rig, itemId, legs - 1, key, then)));
    }

    /** What the collect could not get, read after it stops rather than before it starts. A drop
     *  count taken only up front cannot tell "the walk reached it" from "it despawned while the
     *  body was at the next vein" — five minutes is a short life for an item and this ladder's
     *  mines are long. */
    private static void leftOnTheGround(JourneyRig rig, String itemId, String key, Runnable then) {
        rig.evidence(key + ".pickup.left", rig.dropsNearby(itemId, 32));
        then.run();
    }

    /** How many hand-walked pickup legs a rung gets. Three, because the ladder's mines break a
     *  handful of blocks and a drop that two legs cannot reach is a finding, not a budget problem. */
    private static final int MAX_PICKUP_LEGS = 3;


    // =====================================================================================
    // 10 — the portal kit. Buckets and a flint-and-steel: the last thing the overworld owes.
    // =====================================================================================

    /**
     * Craft the two buckets and the flint-and-steel a portal needs.
     *
     * <p>Three sub-goals, and only one of them is a craft the run has done before. The buckets are
     * six iron ingots through the 3×3 grid — the first rung whose bill is set by the rung BELOW it
     * rather than by the terrain in front of it, so a failure here is usually the iron rung's quota
     * speaking and the {@code iron_ingot.before} reading is what says so.
     *
     * <p>The flint is the interesting half, and the first place this ladder meets a <b>probabilistic
     * drop</b>. Gravel gives flint one time in ten; there is no other vanilla source, and no amount
     * of correct driving changes the odds. So the plan cannot be "break the gravel" — it has to be
     * "break enough gravel", and the quota is the plan. Sixty-four blocks leaves a 0.1% chance of
     * coming back empty, which is the honest way to script a die roll: budget for the tail rather
     * than assert the average and call the variance flakiness.
     *
     * <p>The gravel is under dry ground (the survey only accepts columns that are — see
     * {@link JourneyRoute#firstGravel}), so this rung sinks its own shaft the same way the stone and
     * iron rungs do, and leaves it the same way. The surface it climbs back to is READ on arrival
     * rather than surveyed: unlike the ore rungs there is no {@code gravelDescent} constant, and the
     * body's own standing height at the top of the column is the same number by a shorter route.
     */
    private static void portalKit(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.PORTAL_KIT);
        if (requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();

        BlockPos gravel = JourneyRoute.firstGravel;
        rig.evidence("target.gravel", gravel.getX() + "," + gravel.getY() + "," + gravel.getZ());
        rig.evidence("iron_ingot.before", rig.carrying("minecraft:iron_ingot"));
        rig.evidence("craftingTable.before", rig.carrying("minecraft:crafting_table"));
        if (gravel.equals(JourneyRoute.UNSURVEYED)) {
            rig.attempting("砾石堆未标定：JourneyRoute.firstGravel 还是 UNSURVEYED");
            ctx.skip("UNSURVEYED: 先跑 wd.journey01Recon，把 firstGravel 常量烘进 JourneyRoute");
            return;
        }

        rig.attempting("合成水桶：3 铁锭，铁不够就在这里说清楚");
        craftKeepingTheTable(rig, "minecraft:bucket", 8_000, () -> {
            int buckets = rig.carrying("minecraft:bucket");
            rig.evidence("bucket", buckets);
            rig.evidence("iron_ingot.afterBuckets", rig.carrying("minecraft:iron_ingot"));
            rig.evidence("craft.lastError", String.valueOf(rig.body().botState().craft.lastError));
            // ONE bucket, not the two the stage's first draft asked for, and the change is a
            // correction rather than a concession. A portal cast at a lava pool spends water: you
            // pour it over the sources and they turn to obsidian where they stand. The second bucket
            // belongs to the OTHER method — carrying lava to a site you chose — and this route does
            // not use it. Asking for six ingots when the plan spends three was a bill written before
            // anyone had measured the vein, and it cost the rung a whole run to say so.
            ctx.expect(buckets).as("bucket crafted (3 iron ingots; the iron rung is the supplier)")
                    .isAtLeast(1);
            // Before the walk to the gravel, for the same reason as everywhere else: the
            // flint-and-steel craft at the end of that leg is the one that would otherwise find
            // itself tableless, and by then this table is a hundred blocks behind.
            reclaimTableIfLeftStanding(rig, () -> gravelForFlint(ctx, rig, gravel));
        });
    }

    /** Walk to the surveyed gravel column, sink to it, break enough of it to roll a flint, climb out. */
    private static void gravelForFlint(SceneContext ctx, JourneyRig rig, BlockPos gravel) {
        rig.attempting("走到砾石堆的干燥柱");
        // Same bounded re-plan the ore rungs use, and this leg is where its absence showed last:
        // the run finished its iron shaft and set off for gravel 73 blocks away, the walker reported
        // done at y=53 — still down the shaft it had just climbed — and the rung called the gravel
        // unreachable. A single drive cannot tell "there is no route" from "I stopped early".
        walkToColumn(rig, "gravel", gravel.getX(), gravel.getZ(), 0, 14_000, () -> {
            BlockPos at = rig.player().blockPosition();
            rig.evidence("gravel.arrivedY", at.getY());
            final int surfaceY = JourneyTerrain.daylightY(rig, at);
            rig.attempting("挖竖井下到砾石层");
            BotConfig.allowPlace = false;    // as on the ore rungs: a paving walker will not sink
            JourneyShaft.descendByMining(rig, gravel.getY() + 1, () -> {
                BotConfig.allowPlace = true;
                rig.evidence("gravel.landedY", rig.player().blockPosition().getY());
                // Quota 64: flint is a 10% drop, so this is a dice-roll budget, not a haul target.
                // The assertion below is 1 — one flint is all a flint-and-steel costs, and asking
                // for more would be asking the random number generator for a favour.
                rig.attempting("挖砾石取燧石：10% 掉率，靠量不靠运气");
                rig.drive(new MineProcess(List.of("minecraft:gravel"), 64, 12), 24_000, () ->
                        // Same hand sweep the iron rung needs, and more load-bearing here: flint is
                        // a one-in-ten roll, so a single flint left lying on the floor is the whole
                        // rung. See collectByHand for why the ladder does this itself.
                        collectByHand(rig, "minecraft:flint", 3, () -> {
                    int flint = rig.carrying("minecraft:flint");
                    rig.evidence("flint", flint);
                    rig.evidence("gravel.collected", rig.carrying("minecraft:gravel"));
                    rig.evidence("mine.endReason", String.valueOf(rig.body().botState().mine.endReason));
                    // gravel.collected is the divisor for the die roll: zero flint with sixty gravel
                    // in the bag is bad luck, zero flint with zero gravel is a mining failure, and
                    // the two read identically without this line.
                    ctx.expect(flint).as("flint knapped out of gravel (10% a block — see gravel.collected)")
                            .isAtLeast(1);
                    rig.attempting("合成打火石：1 铁锭 + 1 燧石");
                    craftKeepingTheTable(rig, "minecraft:flint_and_steel", 8_000, () -> {
                        int fas = rig.carrying("minecraft:flint_and_steel");
                        rig.evidence("flint_and_steel", fas);
                        rig.evidence("iron_ingot.after", rig.carrying("minecraft:iron_ingot"));
                        rig.evidence("craft.lastError", String.valueOf(rig.body().botState().craft.lastError));
                        ctx.expect(fas).as("flint and steel crafted").isAtLeast(1);
                        JourneyShaft.climbOut(rig, surfaceY, () ->
                                rig.reach("桶 ×" + rig.carrying("minecraft:bucket")
                                        + "、打火石 ×" + fas + " 到手"));
                    });
                }));
            });
        }, () -> {
            BlockPos at = rig.player().blockPosition();
            ctx.fail("走不到砾石柱：目标 " + gravel.getX() + "," + gravel.getZ() + "，停在 " + at
                    + "（水平相距 "
                    + Math.round(Math.hypot(at.getX() - gravel.getX(), at.getZ() - gravel.getZ()))
                    + " 格，已重规划 " + MAX_WALK_ATTEMPTS + " 次）");
        });
    }

    // =====================================================================================
    // 11 — obsidian. The first rung whose subject is a fluid, and the deepest hole the ladder digs.
    // =====================================================================================

    /**
     * Fetch lava from wherever this seed keeps it and cast one obsidian block into standing water.
     *
     * <p><b>One block, not ten, and that is the rung rather than a shortcut.</b> Obsidian cannot be
     * carried: taking it back out of the world needs a diamond pickaxe, four rungs above anything
     * this route holds. So a portal frame is cast IN PLACE, and where the ten blocks go is a
     * question about the portal — {@code PORTAL_LIT}'s question. What this rung settles is the
     * capability underneath it: can the body reach this world's lava, hold it, come back, and turn
     * it into obsidian at a cell it named beforehand. {@code wd.serverCastsObsidian} already answered
     * that in eight blocks of arena; every part of the answer that the arena could not test is the
     * part between here and the lava.
     *
     * <p><b>Why the cast happens at the water rather than at the pool.</b> The portal's own plan is
     * the other way round — carry water DOWN once, leave it as a source at the build site, and let
     * the one bucket shuttle lava for all ten cells. That plan cannot be scripted here yet for a
     * reason worth writing down: water placed at the bottom of the shaft flows along any opening at
     * its own level, and the opening this rung has to make is the one to the lava. Water reaching the
     * pool converts the very source the bucket was going to draw from, so the two halves race, and
     * they race over a tunnel two or three cells long — about fifteen ticks. Pouring lava into water
     * that is already standing at the surface has no such race, and it measures the same four verbs.
     *
     * <p>The descent is however deep this world's lava is, which the survey answers and this rung
     * does not assume. Zero is a legal answer: a surface pool is reached by walking, and the shaft
     * and the climb both become no-ops. That symmetry earned itself immediately — the rung was
     * written against a 77-block descent, and the moment {@code JourneyRoute.LAVA_SEARCH_RADIUS} was
     * widened the seed's answer became a pool 36 blocks down. A rung that had hard-coded the depth
     * would have gone looking for the wrong hole.
     */
    private static void obsidian(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.OBSIDIAN);
        if (requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();

        BlockPos lava = JourneyRoute.firstLava;
        rig.evidence("target.lava", lava.getX() + "," + lava.getY() + "," + lava.getZ());
        rig.evidence("bucket.before", rig.carrying("minecraft:bucket"));
        if (lava.equals(JourneyRoute.UNSURVEYED)) {
            rig.attempting("岩浆未标定：JourneyRoute.firstLava 还是 UNSURVEYED");
            ctx.skip("UNSURVEYED: 先跑 wd.journey01Recon，让它把 firstLava 填进 JourneyRoute");
            return;
        }
        // The bucket is PORTAL_KIT's output, and saying so here is the difference between "the cast
        // failed" and "the rung below did not pay". Both readings are recorded because a bucket that
        // is already full of something is a third, different story.
        if (rig.carrying("minecraft:bucket") < 1) {
            ctx.fail("没有空桶：PORTAL_KIT 应当留下一个（bucket=0，water_bucket="
                    + rig.carrying("minecraft:water_bucket") + "，lava_bucket="
                    + rig.carrying("minecraft:lava_bucket") + "）");
            return;
        }

        // Start from daylight, whatever the rung below left behind. PORTAL_KIT ends by climbing out
        // of the gravel shaft, and when that climb aimed at the wrong number this rung began
        // fourteen blocks underground and reported a walk of 84 blocks as unreachable — which it was,
        // from the bottom of a hole. The rung below has been fixed; this is the guard that stops the
        // same shape of mistake being diagnosed here again.
        BlockPos here = rig.player().blockPosition();
        int sky = JourneyTerrain.daylightY(rig, here);
        if (here.getY() < sky - 2) {
            rig.evidence("start.underground", here.toShortString() + " → 地表 y=" + sky);
            rig.attempting("上一级把身体留在井里，先爬回地面再出发");
            JourneyShaft.climbOut(rig, sky, () -> walkToTheLava(ctx, rig, lava));
            return;
        }
        walkToTheLava(ctx, rig, lava);
    }

    private static void walkToTheLava(SceneContext ctx, JourneyRig rig, BlockPos lava) {
        rig.attempting("走到岩浆所在的柱子（先到地表）");
        walkToColumn(rig, "lava", lava.getX(), lava.getZ(), 0, 24_000, () -> {
            BlockPos at = rig.player().blockPosition();
            final int surfaceY = JourneyTerrain.daylightY(rig, at);
            rig.evidence("lava.surfaceY", surfaceY + "（脚下 y=" + at.getY() + "）");
            rig.evidence("lava.descentNeeded", surfaceY - lava.getY());
            sinkToLava(ctx, rig, lava, surfaceY);
        }, () -> {
            BlockPos at = rig.player().blockPosition();
            ctx.fail("走不到岩浆柱：目标 " + lava.getX() + "," + lava.getZ() + "，停在 " + at
                    + "（水平相距 "
                    + Math.round(Math.hypot(at.getX() - lava.getX(), at.getZ() - lava.getZ()))
                    + " 格，已重规划 " + MAX_WALK_ATTEMPTS + " 次）");
        });
    }

    /** Sink a shaft to the fluid's level, in a column chosen not to end inside it. */
    private static void sinkToLava(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY) {
        int feet = rig.player().blockPosition().getY();
        // A surface pool needs no shaft. Checked before anything else, because every line below this
        // is written for a descent and none of it is free.
        if (feet <= lava.getY() + 2) {
            rig.evidence("shaft.skipped", "已在岩浆层：feet=" + feet + " lava.y=" + lava.getY());
            reachLava(ctx, rig, MAX_TUNNEL_STEPS, () -> leaveWithTheLava(ctx, rig, surfaceY));
            return;
        }

        ServerLevel level = ctx.level();
        Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
        BlockPos dig = JourneyTerrain.pickDigColumn(level, lava, surfaceY, rejected);
        if (dig == null) {
            // Say which rule did the rejecting. "Nothing qualified" is a shrug; a tally is the next
            // change's evidence, and this rung has already spent one run per guess.
            ctx.fail("岩浆柱周围 8 格内没有可下挖的柱子（目标 " + lava.toShortString()
                    + "，地表 y=" + surfaceY + "）——各项否决计数：" + rejected);
            return;
        }
        rig.evidence("shaft.column", dig.getX() + "," + dig.getZ()
                + " (岩浆柱偏 " + Math.max(Math.abs(dig.getX() - lava.getX()),
                                          Math.abs(dig.getZ() - lava.getZ())) + " 格)");

        // Step onto a checked column before digging, and check the one the body is ACTUALLY on.
        // walkToColumn is the wrong tool here for a reason worth stating: it accepts an arrival
        // within ARRIVED_WITHIN blocks, which is right when the next step is a search and fatal when
        // the next step is a hole. Five blocks of slack over a lava pool is a shaft sunk into the
        // pool — the one outcome this whole rung is arranged to avoid.
        stepOntoDiggableColumn(rig, dig, lava, surfaceY, MAX_WALK_ATTEMPTS, () -> {
            rig.attempting("下挖 " + (rig.player().blockPosition().getY() - (lava.getY() + 1)) + " 格到岩浆层");
            BotConfig.allowPlace = false;      // as on every mining rung: a paving walker will not sink
            // The same generous cap the PORTAL rung gives its identical descent, and for the reason
            // recorded when that one was added: `shaftAttemptsFor` grants depth*3+20 = 128 for these
            // 36 blocks, and the attempts are eaten by the ticks between "the block broke" and "the
            // body has fallen" — visible as `broke=air` while `below=` is still solid. That cap was
            // called out then as sitting nearer its edge than this rung's green rows suggested, and
            // it was left alone; it has now failed a run at y=31 of a target y=27, four blocks short.
            // Two rungs running the same descent should not disagree about what it costs.
            int depth = Math.max(0, rig.player().blockPosition().getY() - (lava.getY() + 1));
            int cap = depth * 8 + 60;
            rig.evidence("shaft.descentCap", depth + " 格深，给 " + cap + " 次尝试（默认公式只给 "
                    + JourneyShaft.shaftAttemptsFor(depth) + "）");
            JourneyShaft.descendByMining(rig, lava.getY() + 1, cap, cap, () -> {
                BotConfig.allowPlace = true;
                rig.evidence("shaft.landedY", rig.player().blockPosition().getY());
                reachLava(ctx, rig, MAX_TUNNEL_STEPS, () -> leaveWithTheLava(ctx, rig, surfaceY));
            });
        }, () -> ctx.fail("站不到可下挖的柱子上：想去 " + dig.getX() + "," + dig.getZ()
                + "，停在 " + rig.player().blockPosition()
                + "（该柱在岩浆层不是实心, 或柱子里还有岩浆）"));
    }

    /**
     * Get the body onto a column a shaft may actually be sunk in, and prove it before digging.
     *
     * <p>The proof is done against the column the body is standing on, not the one it was sent to.
     * Those differ often enough — a walker stops where it can stand — and here the difference is the
     * whole risk: any column is fine as long as it has been checked and is not the pool's own.
     */
    private static void stepOntoDiggableColumn(JourneyRig rig, BlockPos dig, BlockPos lava,
                                               int surfaceY, int left, Runnable then, Runnable onStuck) {
        BlockPos at = rig.player().blockPosition();
        boolean overThePool = at.getX() == lava.getX() && at.getZ() == lava.getZ();
        if (!overThePool && JourneyTerrain.columnIsSafeToSink(rig.ctx().level(),
                new BlockPos(at.getX(), lava.getY(), at.getZ()), surfaceY)) {
            rig.evidence("shaft.standingOn", at.getX() + "," + at.getZ()
                    + (at.getX() == dig.getX() && at.getZ() == dig.getZ() ? " (选定柱)" : " (就近合格柱)"));
            then.run();
            return;
        }
        if (left <= 0) { onStuck.run(); return; }
        rig.evidence("shaft.stepping." + (MAX_WALK_ATTEMPTS - left + 1),
                at.toShortString() + " → " + dig.getX() + "," + dig.getZ()
                        + (overThePool ? " (正站在岩浆柱上)" : " (脚下柱子不合格)"));
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(dig.getX(), dig.getZ(), 0))), 1_200,
                () -> stepOntoDiggableColumn(rig, dig, lava, surfaceY, left - 1, then, onStuck));
    }

    /**
     * How many blocks of tunnel the rung will drive to get a clear look at the lava.
     *
     * <p>Eight, and the number is a budget rather than a plan: the shaft lands within a couple of
     * cells of the pool by construction, so a run that spends all eight has found something the
     * survey did not describe and should say so rather than dig on.
     */
    private static final int MAX_TUNNEL_STEPS = 16;

    /** How far a use reaches. Vanilla's item ray is five blocks from the eyes; the tunnel predicts
     *  against the same number so that "I can see it" and "I can fill from it" are one question. */
    private static final double TUNNEL_REACH = 5.0;

    /**
     * Get a clear line to a lava source, then fill the bucket from it.
     *
     * <p>The tunnel drives itself, and the trick is that it does not need a route: a bucket fills
     * along the ray the body is looking down, so <b>whatever that ray hits first IS the obstruction</b>.
     * Aim at the lava, ask vanilla's own pick what got in the way, mine that, look again. No
     * pathfinding, no plan, and it cannot wander — every block it breaks is on the line to the goal.
     */
    private static void reachLava(SceneContext ctx, JourneyRig rig, int left, Runnable then) {
        reachLava(ctx, rig, left, TUNNEL_CLIMB_BACKS, then);
    }

    /**
     * How many times the tunnel may climb back up after falling out of its own gallery.
     *
     * <p>Two, because the recovery is cheap and the thing it recovers from is not: a tunnel mines
     * horizontally through rock nobody surveyed, so it can break into a cave and the body drops.
     * Measured — the shaft landed at y=27, the tunnel took five steps, and the body finished at
     * <b>y=14</b>, thirteen blocks below its own gallery, with the pool at y=26 now outside the
     * eight-block source search. The rung reported "descended to the lava layer and cannot see a
     * lava source", which is true, and reads as a survey problem about a pool the previous three
     * runs had walked straight up to.
     */
    private static final int TUNNEL_CLIMB_BACKS = 2;

    /**
     * How far to look for ANY lava once the body has fallen somewhere it cannot see the surveyed
     * pool. Wider than the eight-block reach test, because this is a search rather than a check.
     *
     * <p>Twenty-four blocks, and the reason to prefer this over climbing is measured: the climb-back
     * has run twice and gained nothing both times. The second was unambiguous —
     * {@code climb.0.stalled=stuck (no Y gain in 60t — out of blocks?)} beside
     * {@code climb.0.state=onGround=true inWater=false y=14.00} and
     * {@code climb.0.stock=minecraft:cobblestone ×104}: solid ground, clear ceiling, block in hand,
     * and no gain. Whatever refuses that placement is not something this rung can see, so the rung
     * stops depending on it.
     */
    private static final int FALLBACK_LAVA_SEARCH = 24;

    private static void reachLava(SceneContext ctx, JourneyRig rig, int left, int climbBacks, Runnable then) {
        ServerLevel level = ctx.level();
        BlockPos src = JourneyTerrain.nearestLavaSource(level, rig.player().blockPosition(), 8);
        if (src == null) {
            // Look around before climbing. The rung's claim is "fetch lava and cast obsidian", not
            // "use THIS pool" — and a body that has just fallen through a cave roof is standing in a
            // cave, which at this depth is where lava lives. Walking to a pool twenty blocks away is
            // far cheaper than towering twelve blocks up a shaft that has already refused twice, and
            // it is what a player who fell in would do: look, then go.
            BlockPos other = JourneyTerrain.nearestLavaSource(level, rig.player().blockPosition(), FALLBACK_LAVA_SEARCH);
            // Budgeted on `left`, NOT on climbBacks. This search exists precisely because climbing
            // gains nothing — see FALLBACK_LAVA_SEARCH's own note, where two climbs in a row moved
            // the body zero blocks — so gating it behind the counter the climbs consume switches off
            // the remedy at exactly the moment it is needed. Measured: run 11 failed with
            // `已用完 2 次爬回机会` while standing in a cave at y=14, never once asking whether there
            // was lava within twenty-four blocks of it.
            //
            // `left` is the tunnel's own step budget, so this terminates; and it cannot spin, because
            // walking to within 3 of a SOURCE puts it inside the 8-block check at the top.
            if (other != null && (climbBacks > 0 || left > 0)) {
                rig.evidence("tunnel.otherPool", other.toShortString() + "（掉下来之后就近找到的）");
                rig.settle(new IntentProcess(new Intent(new Goal.Near(other, 3))), 2_000,
                        () -> reachLava(ctx, rig, left - 1, climbBacks, then));
                return;
            }
            // Fell out of the gallery? The pool has not moved — the body has. Climb back to its
            // level and carry on, rather than reporting a missing pool from underneath it.
            BlockPos pool = JourneyRoute.firstLava;
            int below = pool.getY() - rig.player().blockPosition().getY();
            if (climbBacks > 0 && below > 2) {
                rig.evidence("tunnel.fell", rig.player().blockPosition().toShortString()
                        + "，比岩浆层低 " + below + " 格（挖穿了洞顶）");
                BotConfig.allowPlace = true;
                JourneyShaft.ascendByTowering(rig, pool.getY() + 1, JourneyShaft.climbCoursesFor(below), JourneyShaft.climbCoursesFor(below),
                        () -> {
                            rig.evidence("tunnel.climbedBackTo", rig.player().blockPosition().toShortString());
                            reachLava(ctx, rig, left, climbBacks - 1, then);
                        });
                return;
            }
            // Say whether the wider search found nothing or was never allowed to run — the two read
            // identically in a red row and want opposite fixes.
            ctx.fail("下到岩浆层却看不到岩浆源：停在 " + rig.player().blockPosition()
                    + "，周围 8 格内没有 source 级岩浆（流动岩浆不能装桶）；"
                    + FALLBACK_LAVA_SEARCH + " 格内" + (other == null ? "也没有" : "有 "
                        + other.toShortString() + "，但步数预算用尽了")
                    + (below > 2 ? "；比岩浆层低 " + below + " 格，且已用完 " + TUNNEL_CLIMB_BACKS
                                   + " 次爬回机会" : ""));
            return;
        }
        rig.body().avatar().aimAtBlock(src);
        // The aim has to land before anything reads it — the same tick wd.serverCastsObsidian needed
        // between aiming and using, and for the same reason.
        rig.settle(new HoldStill(2), 10, () -> {
            var fp = rig.player();
            // partialTicks = 1.0F, and it is the difference between a working tunnel and a rung that
            // could not see the pool it was standing next to. Entity.pick INTERPOLATES: 0.0F traces
            // from the PREVIOUS tick's position, and a body that has walked 80 blocks and dropped 36
            // since then rays out of somewhere it used to be. Measured — the body at -4,27,57 aimed
            // at the lava and the pick answered `57,64,56 air`, a surface cell sixty blocks off,
            // through a five-block ray. 1.0F is the position it is actually at, and it is what
            // vanilla's own Item.getPlayerPOVHitResult uses, so it is also what the pour will see.
            var hit = aimedAt(fp, TUNNEL_REACH, true);
            int step = MAX_TUNNEL_STEPS - left;
            BlockPos blocking = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    ? hit.getBlockPos() : null;
            rig.evidence("tunnel." + step, String.format(java.util.Locale.ROOT,
                    "aim %s (%.0f/%.0f, %.1fm) → %s", src.toShortString(), fp.getYRot(), fp.getXRot(),
                    fp.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(src)),
                    blocking == null ? String.valueOf(hit.getType())
                        : blocking.toShortString() + " " + level.getBlockState(blocking).getBlock()));
            if (blocking != null && level.getFluidState(blocking).isSource()
                    && level.getBlockState(blocking).getBlock() == Blocks.LAVA) {
                fillFrom(ctx, rig, blocking, then);
                return;
            }
            if (left <= 0) {
                ctx.fail("挖了 " + MAX_TUNNEL_STEPS + " 格仍看不到岩浆源 " + src.toShortString()
                        + "：身体在 " + fp.blockPosition() + "，视线被 "
                        + (blocking == null ? "空" : level.getBlockState(blocking).getBlock()) + " 挡住");
                return;
            }
            // climbBacks is threaded, not re-defaulted: a budget that resets on every tunnel step is
            // not a budget, and a body that keeps falling into the same cave would climb forever.
            if (blocking != null && level.getBlockState(blocking).blocksMotion()) {
                rig.mineBlock(blocking, 2_000, () -> rig.settle(new HoldStill(10), 20,
                        () -> reachLava(ctx, rig, left - 1, climbBacks, then)));
                return;
            }
            // Nothing solid on the line and still no source in view: the body is looking past it,
            // not through rock. Close the distance instead of digging.
            rig.settle(new IntentProcess(new Intent(new Goal.Near(src, 2))), 600,
                    () -> reachLava(ctx, rig, left - 1, climbBacks, then));
        });
    }

    /**
     * The block a use would hit, clipped exactly the way vanilla's item path clips it.
     *
     * <p>Not {@code Entity.pick}, and the difference cost this rung a run. {@code pick} calls
     * {@code getViewYRot}, which {@code LivingEntity} overrides to return <b>{@code yHeadRot}</b> —
     * and {@code Avatar.aimAtBlock} sets {@code yRot}/{@code xRot} only. So the pick rays down a
     * direction nobody aimed: measured, the body at {@code -4,27,56} aiming at a pool at
     * {@code -6,26,54} produced hits marching away at {@code -4,28,57 → -3,28,57 → -2,27,58}, and
     * the self-driving tunnel dutifully mined eight blocks in the wrong direction.
     *
     * <p>{@code Item.getPlayerPOVHitResult} — what {@code BucketItem} actually uses — reads
     * {@code getXRot()}/{@code getYRot()} directly, so the POUR was never wrong; only the prediction
     * was. This reproduces that clip, which makes it the only thing that can honestly claim to say
     * what the use will see.
     *
     * <p>(That {@code aimAtBlock} leaves the head rotation behind is an engine-side finding in its
     * own right — anything reading head rotation sees a stale direction — and is logged as one
     * rather than fixed from a test.)
     */
    private static net.minecraft.world.phys.BlockHitResult aimedAt(net.minecraft.server.level.ServerPlayer fp,
                                                                  double range, boolean hitFluids) {
        net.minecraft.world.phys.Vec3 eye = fp.getEyePosition();
        net.minecraft.world.phys.Vec3 look =
                net.minecraft.world.phys.Vec3.directionFromRotation(fp.getXRot(), fp.getYRot());
        net.minecraft.world.phys.Vec3 end = eye.add(look.scale(range));
        return fp.level().clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                hitFluids ? net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY
                          : net.minecraft.world.level.ClipContext.Fluid.NONE, fp));
    }
    /**
     * Put a specific item in the main hand, and record what actually ended up there.
     *
     * <p>{@code useItemInHand} uses the SELECTED hotbar slot, not "the bucket in the bag". By the
     * time the ladder reaches the lava the body has mined a 36-block shaft, so the selected slot
     * holds a pickaxe — and a pickaxe's {@code use} returns {@code PASS} and changes nothing, which
     * is byte-identical to a bucket whose ray missed. Run 15 read {@code fill.result=PASS,
     * lava_bucket=0, fill.sourceAfter=lava} while the aim was dead on the source at 2.5 m, and the
     * only way to tell those two apart afterwards is this evidence line.
     */
    private static boolean holdForUse(JourneyRig rig, net.minecraft.world.item.Item item, String what) {
        boolean ok = rig.body().avatar().holdItem(item);
        rig.evidence(what + ".hand", (ok ? "" : "拿不到 " + BuiltInRegistries.ITEM.getKey(item) + "，手上是 ")
                + BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()));
        return ok;
    }

    /** Fill the bucket from a source the body can already see. */
    private static void fillFrom(SceneContext ctx, JourneyRig rig, BlockPos src, Runnable then) {
        rig.attempting("从 " + src.toShortString() + " 装一桶岩浆");
        holdForUse(rig, Items.BUCKET, "fill");
        rig.body().avatar().aimAtBlock(src);
        rig.settle(new HoldStill(2), 10, () -> {
            rig.evidence("fill.result", String.valueOf(rig.body().avatar().useItemInHand()));
            int filled = rig.carrying("minecraft:lava_bucket");
            rig.evidence("lava_bucket", filled);
            // Where the source went is the other half of the reading: a fill that worked empties the
            // cell, and a use vanilla refused leaves it exactly as it was.
            rig.evidence("fill.sourceAfter", String.valueOf(ctx.level().getBlockState(src).getBlock()));
            ctx.expect(filled).as("lava bucket filled from a source (see fill.result / fill.sourceAfter)")
                    .isAtLeast(1);
            then.run();
        });
    }

    // =====================================================================================
    // NETHER — walk into the portal the rung below lit, and come out somewhere else.
    // =====================================================================================

    /**
     * Step through and assert the body actually MOVED, not merely that the dimension changed.
     *
     * <p>{@code wd.serverEntersTheNether} exists because that distinction was worth a bug: vanilla
     * delivers the destination through {@code connection.teleport}, both loaders' fake players used
     * to swallow it, and the body arrived in the Nether holding its overworld coordinates — 87 501
     * blocks out, above the roof, standing on air, while a dimension check passed. So this rung
     * checks the 8:1 scaling too. If it ever regresses, the fortress rung above would search a world
     * nobody is standing in.
     */
    private static void nether(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.NETHER);
        BlockPos portal = rig.nearestBlock("minecraft:nether_portal", 24);
        rig.evidence("portal.found", portal == null ? "无" : portal.toShortString());
        if (portal == null) {
            ctx.fail("身边 24 格内没有传送门方块 —— PORTAL_LIT 说点着了，这里却找不到，"
                    + "两者必有一个是假的（身体在 " + rig.player().blockPosition() + "）");
            return;
        }
        final BlockPos from = rig.player().blockPosition();
        rig.attempting("走进传送门站住，等它把身体送过去");
        rig.settle(new IntentProcess(new Intent(new Goal.Block(portal))), 2_000, () -> {
            BlockPos at = rig.player().blockPosition();
            rig.evidence("stand.at", at.toShortString());
            rig.evidence("stand.in", String.valueOf(
                    rig.player().serverLevel().getBlockState(at).getBlock()));
            // A player's own portal wait is ~80 ticks; this budget is generous on purpose, because a
            // run that spends it all has found a body the timer never STARTS for, which is a
            // different finding from one it never fires for.
            rig.await(() -> !"minecraft:overworld".equals(rig.dimension()), 1_200, () -> {
                rig.evidence("dimension", rig.dimension());
                BlockPos now = rig.player().blockPosition();
                rig.evidence("arrived.at", now.toShortString());
                rig.evidence("underfoot", String.valueOf(
                        rig.player().serverLevel().getBlockState(now.below()).getBlock()));
                int wantX = Math.floorDiv(from.getX(), 8), wantZ = Math.floorDiv(from.getZ(), 8);
                int drift = Math.max(Math.abs(now.getX() - wantX), Math.abs(now.getZ() - wantZ));
                rig.evidence("scaled.expectedXZ", wantX + "," + wantZ + "（漂移 " + drift + " 格）");
                ctx.expect(rig.dimension()).as("the body is in the Nether")
                        .isEqualTo("minecraft:the_nether");
                ctx.expect(drift).as("it arrived at the 8:1-scaled coordinate, not the raw one")
                        .isAtMost(128);
                rig.noteAdvancement("minecraft:story/enter_the_nether");
                rig.reach("从自己点亮的门走进下界，落在 " + now.toShortString()
                        + "（地表门在 " + from.toShortString() + "，按 8:1 应在 "
                        + wantX + "," + wantZ + "）");
            });
        });
    }

    // =====================================================================================
    // PORTAL_LIT — ten obsidian in a frame, at the lava's own level, then a flint-and-steel.
    // =====================================================================================

    /** The mould's shape and the rules about where it may go — see {@link JourneyForge}. */
    private static final int[][] RING = JourneyForge.RING;

    /**
     * Build and light the portal, without a diamond pickaxe and without staging.
     *
     * <p>The technique is the one {@code wd.serverBuildsAndLightsAPortal} proves end to end, and the
     * three shapes it cost to find are worth restating where the rung uses them:
     *
     * <ul>
     *   <li><b>The water is carried, not left.</b> A source goes into the interior cell ADJACENT to
     *       the cell being cast, which reproduces the single-cast geometry for every cell and needs
     *       no flow at all — the conversion is a neighbour update, not a fluid tick. One bucket then
     *       suffices because it is empty exactly when it needs to be: after placing the water (go
     *       fetch lava) and again after pouring the lava (take the water back).</li>
     *   <li><b>The top pair cannot cast against the interior.</b> Vanilla looks above the lava and
     *       to its four sides, never below, so those two cast against a notch carved one block
     *       higher — which is why this rung hollows TWELVE cells and not ten.</li>
     *   <li><b>A scoop takes the source.</b> Ten casts need ten DISTINCT lava cells, so the rung
     *       enumerates the pool rather than returning to one spot.</li>
     * </ul>
     *
     * <p><b>Why underground.</b> Obsidian cannot be carried, so the frame is cast where it stands.
     * At the surface each of the ten fills would be a climb out of a 36-block shaft — the mechanism
     * this ladder has the least confidence in. At the lava's own level the rock is its own mould:
     * the frame is carved into a face, the stone behind it is the backing every bucket aims at, and
     * the ten walks are a few blocks each.
     */
    private static void portalLit(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.PORTAL_LIT);
        if (requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();

        // The LAKE, not the fill point. firstLava is the one the OBSIDIAN rung empties into its
        // bucket, and a bucket takes the source block itself — five runs died here reporting zero
        // sources at a landmark that had genuinely ceased to exist. Falls back only so that an old
        // baked route still runs and still says which landmark it used.
        boolean haveLake = !JourneyRoute.lavaLake.equals(JourneyRoute.UNSURVEYED);
        BlockPos lava = haveLake ? JourneyRoute.lavaLake : JourneyRoute.firstLava;
        rig.evidence("lava.landmark", (haveLake
                ? "lavaLake " + lava.toShortString() + "（勘测到 " + JourneyRoute.lavaLakeSources + " 格源块）"
                : "回退到 firstLava " + lava.toShortString() + " —— 没有勘测到够十格的岩浆湖"));
        rig.evidence("bucket.before", rig.carrying("minecraft:bucket"));
        rig.evidence("flintAndSteel.before", rig.carrying("minecraft:flint_and_steel"));
        rig.evidence("cobblestone.before", rig.carrying("minecraft:cobblestone"));
        if (rig.carrying("minecraft:flint_and_steel") < 1) {
            ctx.fail("没有打火石：PORTAL_KIT 应当留下一把（当前 0）");
            return;
        }
        if (rig.carrying("minecraft:bucket") < 1 && rig.carrying("minecraft:water_bucket") < 1) {
            ctx.fail("没有桶：OBSIDIAN 用完之后应当把空桶带回来（bucket=0, water_bucket=0, lava_bucket="
                    + rig.carrying("minecraft:lava_bucket") + "）");
            return;
        }

        // Water first, at the surface, while there is still water to be had: the whole rung below
        // ground runs on one source and there is none down there to go back for.
        fillWaterAtTheSurface(ctx, rig, () -> descendToTheForge(ctx, rig, lava));
    }

    /** Put water in the bucket before going under. OBSIDIAN ends beside standing water, so this is
     *  normally one aim away; the walk is the fallback for a run that ended somewhere else. */
    private static void fillWaterAtTheSurface(SceneContext ctx, JourneyRig rig, Runnable then) {
        if (rig.carrying("minecraft:water_bucket") >= 1) {
            rig.evidence("water.alreadyCarried", "是");
            then.run();
            return;
        }
        BlockPos water = JourneyTerrain.shallowWaterNear(rig, 24);
        if (water == null) {
            BlockPos w = JourneyRoute.firstWater;
            rig.attempting("身边没有水，走到勘测过的水域装水");
            walkToColumn(rig, "water", w.getX(), w.getZ(), 0, 16_000,
                    () -> scoopWater(ctx, rig, JourneyTerrain.shallowWaterNear(rig, 12), then),
                    () -> ctx.fail("走不到 firstWater " + w.toShortString()
                            + "：停在 " + rig.player().blockPosition()));
            return;
        }
        scoopWater(ctx, rig, water, then);
    }

    private static void scoopWater(SceneContext ctx, JourneyRig rig, BlockPos water, Runnable then) {
        if (water == null) {
            ctx.fail("装不到水：附近没有底下实心的水面（身体在 " + rig.player().blockPosition() + "）");
            return;
        }
        rig.attempting("装一桶水带下去 —— 底下没有水可回头取");
        rig.settle(new IntentProcess(new Intent(new Goal.Near(water, 2))), 2_000, () -> {
            BlockPos aim = JourneyTerrain.shallowWaterNear(rig, 8);
            if (aim == null) aim = water;
            holdForUse(rig, Items.BUCKET, "waterFill");
            rig.body().avatar().aimAtBlock(aim);
            final BlockPos at = aim;
            rig.settle(new HoldStill(2), 10, () -> {
                rig.evidence("waterFill.result", String.valueOf(rig.body().avatar().useItemInHand()));
                rig.evidence("water_bucket", rig.carrying("minecraft:water_bucket"));
                rig.evidence("waterFill.cellAfter", String.valueOf(ctx.level().getBlockState(at).getBlock()));
                if (rig.carrying("minecraft:water_bucket") < 1) {
                    ctx.fail("装水失败：瞄了 " + at.toShortString() + "，桶里还是空的 —— "
                            + "这一级底下全程靠这一桶水，装不上就没有下一步");
                    return;
                }
                then.run();
            });
        });
    }

    /** Walk to the surveyed lava and sink to its level, reusing OBSIDIAN's own descent. */
    /**
     * The floor the mould is carved on — deliberately NOT the lava's own level.
     *
     * <p>The frame is five cells tall plus a row of cap notches, and the technique is to carve that
     * shape out of solid rock so every cell has a back for the buckets to aim at. Laid at the lava's
     * level that holds underground and fails at a surface lake: run 10 walked to this seed's only
     * usable lake, at <b>y=63</b>, cast the first two cells and died on the third with
     * {@code 想放 -9,65,23 … 现在是 air} — the upper rows were open sky, so the water ran off.
     *
     * <p>Seven below the lava puts all twelve cells in rock whatever the lake's depth, and costs a
     * seven-block climb per fill against the thirty-six the OBSIDIAN rung already climbs carrying
     * lava — well inside proven ground.
     */
    private static int forgeFloorY(BlockPos lava) {
        return lava.getY() - JourneyForge.BELOW_LAVA;
    }

    /** The column the mould's shaft was sunk down, so the ten casts can come back down the same one.
     *  See {@link #returnToTheForge}. */
    private static int forgeShaftX, forgeShaftZ;

    /**
     * Come back down to the mould after a trip to the pool.
     *
     * <p>The leg the rung did not have, and the one that turned a working cast into a nine-block
     * miss. Every fill is a climb: the mould is cut below the lava and the pool is at the lava's own
     * level, so between "fill the bucket" and "pour it" the body has to descend a one-wide shaft it
     * has just PILLARED SHUT climbing up — the walker places blocks to rise and then cannot walk
     * back through them. Measured: {@code cast0.picks=-9,61,21 cobblestone face=up → 落进 -9,62,21}
     * with {@code 身体 -9,62,21}. The body poured the run's only bucket of lava into the shaft at its
     * own feet, eleven blocks above the cell it was aiming at, and {@code use} reported
     * {@code CONSUME}.
     *
     * <p>So the descent is spelled out rather than searched, the same way the shaft that made it is:
     * walk over the known column, then mine straight down. Over the COLUMN first and not from
     * wherever the fill ended, because digging down through the mould's own ceiling is the one way
     * this leg could make things worse than the walk it replaces.
     */
    /**
     * Climb from the mould up to the pool's level, the way the rung came down.
     *
     * <p>The other half of {@link #returnToTheForge}, and it is needed for the same reason: the two
     * ends of every cast are twelve blocks apart up a one-wide shaft, and the walker cannot find its
     * way up one. Measured — the body sat at {@code -7,51,21} issuing
     * {@code goal=Near[target=-10,63,20]} every three seconds, each search burning its whole
     * 100 000-node budget, which is exactly the shape of a wedge this ladder has hit before: three
     * identical questions get three identical answers.
     *
     * <p>{@link JourneyShaft#climbOut} is the scripted ascent that already exists for leaving a mine
     * shaft, so this is a walk to the shaft's mouth and then that. Nothing new is asked of the
     * engine; what was missing was the instruction.
     */
    private static void goUpToThePool(SceneContext ctx, JourneyRig rig, int poolY, String tag,
                                      Runnable then) {
        if (rig.player().blockPosition().getY() >= poolY - 1) { then.run(); return; }
        BlockPos mouth = new BlockPos(forgeShaftX, rig.player().blockPosition().getY(), forgeShaftZ);
        rig.evidence(tag + ".up", rig.player().blockPosition().toShortString() + " → 井口 "
                + forgeShaftX + "," + forgeShaftZ + "，爬到 y=" + poolY);
        // ON the shaft column, not near it. The ascent TOWERS, so wherever it starts is where a
        // column of cobblestone goes — and started one cell over it fills the corridor instead of
        // the shaft. That is not cosmetic: the pours stand in those cells, and the next cast then
        // finds nowhere to stand with a clear line (measured, `落脚格被占=130`). Up the shaft the
        // pillars are self-cleaning, because returnToTheForge mines straight back down through them.
        rig.settle(new IntentProcess(new Intent(new Goal.Block(mouth))), 1_500, () -> {
            // One more ask from wherever it stopped, then climb from where it is. Standing exactly
            // on the column is worth a second attempt and NOT worth the rung: a tower one cell over
            // costs the next cast a standing spot, which the pour's own ray gate will name, while
            // refusing to climb costs the whole rung for a body that is one block out of place.
            rig.settle(new IntentProcess(new Intent(new Goal.Block(
                    new BlockPos(forgeShaftX, rig.player().blockPosition().getY(), forgeShaftZ)))),
                    800, () -> {
                BlockPos here = rig.player().blockPosition();
                if (here.getX() != forgeShaftX || here.getZ() != forgeShaftZ) {
                    rig.evidence(tag + ".upOffColumn", here.toShortString() + " 不是井口 "
                            + forgeShaftX + "," + forgeShaftZ + "，起塔会把鹅卵石垒进模腔");
                }
                JourneyShaft.climbOut(rig, poolY, then);
            });
        });
    }

    private static void returnToTheForge(SceneContext ctx, JourneyRig rig, int floorY, String tag,
                                         Runnable then) {
        BlockPos at = rig.player().blockPosition();
        if (at.getY() <= floorY + 1) { then.run(); return; }
        rig.evidence(tag + ".return", at.toShortString() + " → 井口 " + forgeShaftX + ","
                + forgeShaftZ + "，再挖回 y=" + floorY);
        walkToColumn(rig, tag + ".shaft", forgeShaftX, forgeShaftZ, 1, 2_000, () -> {
            // `walkToColumn` calls five blocks "arrived", which is right for crossing a swamp and
            // wrong for standing over a hole: five blocks along `away` is the frame's own plane, and
            // digging down there opens the mould from above. Two is the whole of the corridor's
            // width, so a miss inside it lands the body in the chamber it was going to anyway.
            BlockPos here = rig.player().blockPosition();
            double off = Math.hypot(here.getX() - forgeShaftX, here.getZ() - forgeShaftZ);
            if (off > 2.0) {
                ctx.fail(String.format(java.util.Locale.ROOT,
                        "回井口差了 %.1f 格：想站 %d,%d，停在 %s —— 在这儿往下挖会从上面挖穿门框那一面",
                        off, forgeShaftX, forgeShaftZ, here.toShortString()));
                return;
            }
            BotConfig.allowPlace = false;          // a tower on the way DOWN is the bug, not the fix
            JourneyShaft.descendByMining(rig, floorY, () -> {
                BotConfig.allowPlace = true;
                rig.evidence(tag + ".returnedY", rig.player().blockPosition().getY());
                then.run();
            });
        }, () -> ctx.fail("装完岩浆回不到井口：想去 " + forgeShaftX + "," + forgeShaftZ
                + "，停在 " + rig.player().blockPosition() + " —— 在这儿往下挖会挖穿模腔的顶"));
    }

    private static void descendToTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava) {
        rig.attempting("背着一桶水走到岩浆柱并下到岩浆层");
        walkToColumn(rig, "lava", lava.getX(), lava.getZ(), 0, 24_000, () -> {
            BlockPos at = rig.player().blockPosition();
            final int surfaceY = JourneyTerrain.daylightY(rig, at);
            rig.evidence("forge.surfaceY", surfaceY + "（脚下 y=" + at.getY() + "）");
            if (at.getY() <= forgeFloorY(lava) + 1) { carveTheForge(ctx, rig, lava, surfaceY); return; }
            ServerLevel level = ctx.level();
            Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
            BlockPos dig = JourneyTerrain.pickDigColumn(level, lava, surfaceY, rejected);
            if (dig == null) {
                ctx.fail("岩浆柱周围没有可下挖的柱子（目标 " + lava.toShortString()
                        + "，地表 y=" + surfaceY + "）——各项否决计数：" + rejected);
                return;
            }
            stepOntoDiggableColumn(rig, dig, lava, surfaceY, MAX_WALK_ATTEMPTS, () -> {
                BotConfig.allowPlace = false;
                // Remember the column, because every one of the ten casts has to come back down it.
                // See returnToTheForge: this is the one line from the mould to the surface that is
                // known to be safe the whole way, and digging down anywhere else risks holing the
                // mould's own ceiling.
                forgeShaftX = rig.player().blockPosition().getX();
                forgeShaftZ = rig.player().blockPosition().getZ();
                // A cap of its own, not the shared default. OBSIDIAN's descent and this one are the
                // same 36 blocks and get the same 128 attempts from `shaftAttemptsFor`, and this one
                // ran out at 32 of 36: the wasted attempts are the ticks between "the block broke"
                // and "the body has fallen into the hole", which the evidence shows as `broke=air`
                // while `below=` is still solid. OBSIDIAN can afford to be tight because failing
                // costs it one rung; this rung is carrying the run's only bucket of water down a
                // hole it cannot re-dig, and it has a 250 000-tick budget to spend on getting there.
                int depth = Math.max(0, rig.player().blockPosition().getY() - forgeFloorY(lava));
                int cap = depth * 8 + 60;
                rig.evidence("forge.descentCap", depth + " 格深，给 " + cap + " 次尝试（默认公式只给 "
                        + (depth * 3 + 20) + "）");
                JourneyShaft.descendByMining(rig, forgeFloorY(lava), cap, cap, () -> {
                    BotConfig.allowPlace = true;
                    rig.evidence("forge.landedY", rig.player().blockPosition().getY());
                    carveTheForge(ctx, rig, lava, surfaceY);
                });
            }, () -> ctx.fail("站不到可下挖的柱子上：想去 " + dig.getX() + "," + dig.getZ()
                    + "，停在 " + rig.player().blockPosition()));
        }, () -> ctx.fail("走不到岩浆柱：目标 " + lava.getX() + "," + lava.getZ()
                + "，停在 " + rig.player().blockPosition()));
    }

    /**
     * How many times the forge may be cut deeper when its shell will not hold, and by how much.
     *
     * <p>Five blocks a go, three goes. Deepening is the ONLY move that answers a wet ceiling: pushing
     * the frame further along {@code away} makes the excavation wider under the same lake, which is
     * what the previous version did and why run 21 carved its alcove's roof out from under a surface
     * pool. Fifteen blocks of extra descent is inside what this rung already pays for the shaft, and
     * the loop stops rather than digging to bedrock because a shell that is still wet fifteen blocks
     * down is a different finding and should read as one.
     */
    private static final int FORGE_DEEPENINGS = 3;
    private static final int FORGE_DEEPEN_BY = 5;

    /**
     * Hollow the alcove the casting is done from, and the twelve cells of the frame in its far wall.
     *
     * <p>The face is put on the side of the body AWAY from the pool, so that nothing carved opens
     * into lava — the one mistake down here that ends the run rather than costing it a retry.
     */
    private static void carveTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY) {
        carveTheForge(ctx, rig, lava, surfaceY, FORGE_DEEPENINGS);
    }

    private static void carveTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY,
                                      int deepenings) {
        BlockPos at = rig.player().blockPosition();
        int dx = Integer.signum(at.getX() - lava.getX());
        int dz = Integer.signum(at.getZ() - lava.getZ());
        // One axis only: a diagonal face has no flat back for the buckets to aim at.
        Direction away = Math.abs(at.getX() - lava.getX()) >= Math.abs(at.getZ() - lava.getZ())
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        // Two questions decide where the mould goes, and only one of them used to be asked.
        //
        // HOW FAR OUT (`push`) answers "is there fluid in what I am about to dig". That is a real
        // question and this loop still asks it.
        //
        // HOW DEEP answers "is there fluid in what will be HOLDING IT IN", and nothing asked it. The
        // alcove is seven cells tall and its floor was fixed at seven below the lava, so beside a
        // SURFACE lake its ceiling is the lake's own floor. Run 21 carved exactly that: sixty-three
        // cells opened cleanly, and then cell zero read `-9,56,23 = lava` with the water cell beside
        // it lava too — the pool had come in through the roof, into a mould whose every cell had
        // tested dry. `JourneyForge.blocked` now asks both, and a wet shell is answered by digging
        // DEEPER, because pushing sideways only makes the excavation wider under the same lake.
        ServerLevel level = ctx.level();
        int push = 2;
        String bad = JourneyForge.blocked(level, at, away, push);
        while (bad != null && push < 8) {
            push++;
            bad = JourneyForge.blocked(level, at, away, push);
        }
        if (bad != null) {
            if (deepenings > 0) {
                int deeper = at.getY() - FORGE_DEEPEN_BY;
                rig.evidence("forge.deepen." + (FORGE_DEEPENINGS - deepenings + 1),
                        "y=" + at.getY() + " → " + deeper + "：" + bad);
                rig.attempting("模腔外壳不干，再往下挖 " + FORGE_DEEPEN_BY + " 格");
                BotConfig.allowPlace = false;
                JourneyShaft.descendByMining(rig, deeper, () -> {
                    BotConfig.allowPlace = true;
                    carveTheForge(ctx, rig, lava, surfaceY, deepenings - 1);
                });
                return;
            }
            ctx.fail("模腔怎么摆都不成立：外推 2..8 格、下挖 " + (FORGE_DEEPENINGS * FORGE_DEEPEN_BY)
                    + " 格都试过，最后一处 " + bad + "（身体在 " + at + "）");
            return;
        }
        final int out = push;
        List<BlockPos> cells = JourneyForge.cells(at, away, push);
        BlockPos base = at.relative(away, push);
        rig.evidence("forge.face", base.toShortString() + " 朝 " + away
                + "（背离岩浆，外推 " + push + " 格，井底 y=" + at.getY() + "，岩浆层 y=" + lava.getY() + "）");
        // Corridor only. The twelve frame cells were checked for fluid above (via `cells`) but are
        // left SOLID here — each is opened in castCell just before it is filled, so that its floor
        // is still rock or already-cast obsidian at the moment the fluid lands in it.
        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos c : JourneyForge.corridor(at, away, push)) {
            if (level.getBlockState(c).isAir()) continue;
            todo.add(c);
        }
        rig.evidence("forge.toCarve", todo.size() + "/" + cells.size() + " 格");
        rig.attempting("挖出浇筑用的壁龛和十二格门框");
        BotConfig.allowPlace = false;
        carveNext(ctx, rig, todo, 0, new ArrayList<>(), () -> {
            BotConfig.allowPlace = true;
            rig.evidence("forge.carved", "完成");
            // Is the mould still a mould? The backings were solid when the spot was CHOSEN, and the
            // carve is the only thing that has happened since — but `allowBreak` stays on through it,
            // so the pathfinder is free to chew a way through the back wall while reaching a corridor
            // cell. A hole there is not a cosmetic loss: every bucket in this rung is aimed at the
            // backing, and a ray that passes through it puts the fluid a cell or more beyond, which
            // the rung would then report as "the cast does not work".
            String open = JourneyForge.firstOpenBacking(ctx.level(), at, away, out);
            rig.evidence("forge.backings", open == null ? "十四格背板都还是实心" : "挖穿了：" + open);
            if (open != null) {
                ctx.fail("挖模腔时把门框背后挖穿了：" + open
                        + " —— 选址时这些格子都是实心的，是挖的过程（allowBreak 全程开着，"
                        + "寻路自己会破墙）把背板打通的。背板一旦是空的，瞄它的每一桶都会穿过去");
                return;
            }
            castTheFrame(ctx, rig, base, away, lava, surfaceY);
        });
    }

    private static BlockPos frameCell(BlockPos base, Direction away, int dx, int dy) {
        return JourneyForge.frameCell(base, away, dx, dy);
    }

    /**
     * Carve the list, and let a cell that will not open be DATA rather than death.
     *
     * <p>The first field run died here on {@code await step exceeded within=900} and recorded
     * nothing at all about which cell — {@code mineBlock} is drive-shaped, so its timeout ends the
     * rung before the line that would have named the block. Every cell now gets a bounded attempt
     * and the run carries on, so the failure that arrives at the end is a LIST of what could not be
     * reached, which is the thing a plan can be corrected from.
     */
    private static void carveNext(SceneContext ctx, JourneyRig rig, List<BlockPos> todo, int i,
                                  List<BlockPos> stuck, Runnable then) {
        if (i >= todo.size()) {
            rig.evidence("carve.stuck", stuck.isEmpty() ? "无"
                    : stuck.size() + " 格挖不动：" + describeStuck(rig, stuck));
            then.run();
            return;
        }
        BlockPos c = todo.get(i);
        if (ctx.level().getBlockState(c).isAir()) { carveNext(ctx, rig, todo, i + 1, stuck, then); return; }
        rig.mineCellOrGiveUp(c, 240, () -> {
            if (!ctx.level().getBlockState(c).isAir()) stuck.add(c);
            carveNext(ctx, rig, todo, i + 1, stuck, then);
        });
    }

    /** Stuck cells summarised by height above the body's floor — the shape of the failure matters
     *  more than the coordinates, because "everything above y+3" and "one awkward corner" want
     *  completely different fixes. */
    private static String describeStuck(JourneyRig rig, List<BlockPos> stuck) {
        int floor = rig.player().blockPosition().getY();
        Map<Integer, Integer> byHeight = new java.util.TreeMap<>();
        for (BlockPos c : stuck) byHeight.merge(c.getY() - floor, 1, Integer::sum);
        // The coordinates too, not only the histogram. "Two cells at floor level" reads as terrain
        // and "both of them the far edge of the corridor" reads as the carve ORDER, and the shape
        // alone cannot tell those apart — while a cell left standing inside the alcove is what
        // seals the frame cell behind it against `canBreak`.
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < Math.min(stuck.size(), 8); i++)
            where.append(i == 0 ? "" : " ").append(stuck.get(i).toShortString());
        return byHeight.toString() + "（键=离脚下的高度，值=格数）"
                + " 分别在：" + where + (stuck.size() > 8 ? " …" : "");
    }

    /**
     * Ten casts from one bucket, then the flint-and-steel.
     *
     * <p>The loop is the arena's, verbatim in shape: place the water in the interior cell adjacent
     * to the target, fetch lava from a pool cell nobody has spent yet, pour, take the water back.
     * The bucket is empty at both of the moments that need it to be.
     */
    private static void castTheFrame(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                     BlockPos lava, int surfaceY) {
        // Searched around the SURVEYED lava, not around the body — and that is the fix for a run
        // that reported "0 格" while standing in a chamber it had just carved. The shaft column is
        // chosen up to eight cells clear of the pool (it must not open into it), and then the alcove
        // is carved further away again, so by the time the casting starts the body can be a dozen
        // blocks from the lava it came down for. The caller knows where the pool is; ask there.
        BlockPos here = rig.player().blockPosition();
        List<BlockPos> pool = JourneyTerrain.lavaSourcesNear(ctx.level(), lava, 16, here);   // may be widened below
        rig.evidence("pool.sources", pool.size() + " 格岩浆源（需要 " + RING.length + "）"
                + (pool.isEmpty() ? "" : "，最近一格 " + pool.get(0).toShortString() + " 距身体 "
                        + Math.round(Math.sqrt(pool.get(0).distSqr(here))) + " 格"));
        // Widen before giving up. firstLava is the OBSIDIAN rung's fill point and a bucket takes the
        // source block itself, so the surveyed cell can simply be gone by now — measured, zero
        // sources within sixteen of it. The body is already standing at lava level with its chunks
        // loaded, which is the one moment a wider look is cheap, so ask again from here before
        // declaring the rung impossible.
        if (pool.size() < RING.length) {
            List<BlockPos> wider = JourneyTerrain.lavaSourcesNear(ctx.level(), here, 40, here);
            rig.evidence("pool.widened", pool.size() + " → " + wider.size() + " 格（以身体为心 40 格）"
                    + (wider.isEmpty() ? "" : "，最近 " + wider.get(0).toShortString() + " 距 "
                        + Math.round(Math.sqrt(wider.get(0).distSqr(here))) + " 格"));
            if (wider.size() >= RING.length) pool = wider;
        }
        if (pool.size() < RING.length) {
            // Say where the lava ACTUALLY is before saying there is not enough of it. "0 within 16"
            // and "the nearest source is 40 blocks that way" are the same red row and want opposite
            // fixes — a wider search versus a different landmark.
            List<BlockPos> wider = JourneyTerrain.lavaSourcesNear(ctx.level(), here, 48, here);
            rig.evidence("pool.nearestAnywhere", wider.isEmpty() ? "48 格内一格都没有"
                    : wider.get(0).toShortString() + " 距身体 "
                      + Math.round(Math.sqrt(wider.get(0).distSqr(here))) + " 格，共 "
                      + wider.size() + " 格源块");
            rig.evidence("pool.column", JourneyTerrain.lavaColumnReport(ctx.level(), here, 24));
            ctx.fail("岩浆源不够：以勘测点 " + lava.toShortString() + " 为心 16 格内只找到 "
                    + pool.size() + " 格源块，浇十块需要十格。**firstLava 是 OBSIDIAN 装桶用的那一处，"
                    + "装一次拿走的就是源块本身** —— 这一级需要的是一片有十格以上源块的岩浆湖，"
                    + "是一个独立的地标，不是同一个点（身体在 " + here + "）");
            return;
        }
        rig.attempting("一只桶浇十块黑曜石（水搬着走）");
        castCell(ctx, rig, base, away, pool, 0, () -> lightIt(ctx, rig, base, away, surfaceY));
    }


    private static BlockPos wetCellFor(BlockPos base, Direction away, int dx, int dy) {
        return JourneyForge.wetCellFor(base, away, dx, dy);
    }

    private static void castCell(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                 List<BlockPos> pool, int i, Runnable then) {
        if (i >= RING.length) {
            rig.evidence("frame.cast", countObsidian(ctx.level(), base, away) + "/" + RING.length);
            then.run();
            return;
        }
        BlockPos cell = frameCell(base, away, RING[i][0], RING[i][1]);
        BlockPos wet = wetCellFor(base, away, RING[i][0], RING[i][1]);
        // Open exactly these two, now. Everything else in the frame is still solid, which is what
        // gives this cell a floor — see forgeCorridor for why carving them all up front cast 0/10.
        // 1200, not 400. Twenty seconds has to cover pathing to the cell as well as breaking it, and
        // `mineCellOrGiveUp` carries on regardless when it runs out — so a budget that is merely tight
        // does not report itself, it reports a pour into rock two steps later. UNVERIFIED: this is a
        // plausible reason run 20 left `wet` as stone, not a confirmed one; the assertion below is
        // what will actually name the cause next run.
        rig.mineCellOrGiveUp(cell, 1_200, () -> {
            noteCellDig(rig, "cell." + i, cell);
            rig.mineCellOrGiveUp(wet, 1_200, () -> {
                noteCellDig(rig, "wet." + i, wet);
                castOpenedCell(ctx, rig, base, away, pool, i, cell, wet, then);
            });
        });
    }

    /**
     * Why a frame cell did or did not open, at the moment the digger let go of it.
     *
     * <p>{@code mineCellOrGiveUp} is "dig, and carry on either way" by design, and until now the
     * only thing carried was the outcome: {@code opened.1=-10,51,23=stone} says the cell is shut and
     * nothing at all about why. Two very different answers look the same from there — the process
     * ran out of its budget still swinging, or it stopped early because it could not get within
     * reach — and they want opposite fixes (a bigger number versus a different standing spot).
     *
     * <p>Recorded only for a cell that is still solid. Ten successful digs of two cells each would
     * bury the one that mattered, and this rung's evidence line is already the longest in the suite.
     */
    private static void noteCellDig(JourneyRig rig, String tag, BlockPos cell) {
        ServerLevel level = rig.ctx().level();
        if (level.getBlockState(cell).isAir()) return;
        BlockPos at = rig.player().blockPosition();
        double eyes = rig.player().getEyePosition()
                .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(cell));
        // The six neighbours, because `canBreak` has two clauses and they want opposite fixes. Out of
        // RANGE is a standing-spot problem; WALLED IN — every neighbour a full solid face, which is
        // the honest server-side form of "no ray could reach it" — is an ORDER problem, and the only
        // way to tell them apart is to say which faces are closed. Measured: `canBreak=false` at
        // 2.1 m with a pickaxe in hand, which rules the range clause out and names the other.
        StringBuilder around = new StringBuilder();
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            around.append(' ').append(d).append('=').append(level.getBlockState(n).getBlock())
                    .append(level.getBlockState(n).isSolidRender(level, n) ? "(实心)" : "");
        }
        rig.evidence("dig." + tag, String.format(java.util.Locale.ROOT,
                "%s 仍是 %s：身体 %s 距 %.1fm，canBreak=%s，mine.lastError=%s end=%s，手上 %s；六邻%s",
                cell.toShortString(), level.getBlockState(cell).getBlock(), at.toShortString(), eyes,
                rig.body().avatar().canBreak(cell),
                rig.body().botState().mine.lastError, rig.body().botState().mine.endReason,
                BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()), around));
    }

    private static void castOpenedCell(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                       List<BlockPos> pool, int i, BlockPos cell, BlockPos wet,
                                       Runnable then) {
        rig.evidence("opened." + i, cell.toShortString() + "=" + ctx.level().getBlockState(cell).getBlock()
                + " 水位 " + wet.toShortString() + "=" + ctx.level().getBlockState(wet).getBlock());
        // Say it outright when a cell did not open. `mineCellOrGiveUp` is "dig, and carry on either
        // way" by design — which is right for an excavation and wrong here, where pouring into rock
        // is not a smaller version of pouring into a cavity. Run 20 poured water at a `wet` that was
        // still stone and the failure surfaced as `cobblestone` in the target, three inferences away
        // from the cause. Flowing lava meeting water gives cobblestone; a lava SOURCE meeting water
        // gives obsidian — so that reading also says the floor is now holding, and only the opening
        // is missing.
        if (!ctx.level().getBlockState(cell).isAir() || !ctx.level().getBlockState(wet).isAir()) {
            ctx.fail("第 " + (i + 1) + " 格没挖开就要浇：" + cell.toShortString() + "="
                    + ctx.level().getBlockState(cell).getBlock() + "，水位 " + wet.toShortString()
                    + "=" + ctx.level().getBlockState(wet).getBlock()
                    + "（两格都必须是空气；mineCellOrGiveUp 挖不动会静默继续）");
            return;
        }
        // A water bucket is what this cell is about to spend. Say so before spending the walk: the
        // recover fill one cell back is best-effort, so a body that lost the water arrives here with
        // an empty bucket, places nothing, pours lava into a dry cell and reports "cast.missed" —
        // which reads as a casting bug and is really a fill that failed a cell ago.
        if (rig.carrying("minecraft:water_bucket") < 1) {
            ctx.fail("第 " + (i + 1) + " 格开浇前手上没有水桶：bucket=" + rig.carrying("minecraft:bucket")
                    + " water_bucket=0 lava_bucket=" + rig.carrying("minecraft:lava_bucket")
                    + " —— 上一格的 recover 没把水收回来，没有水就浇不出黑曜石");
            return;
        }
        // Water in, from the block behind it: a bucket fills the neighbour of the face its ray lands
        // on, and an air cell stops no ray. Standing level with the target keeps that ray horizontal.
        placeFluid(ctx, rig, wet, away, Items.WATER_BUCKET, "water" + i, () -> {
            // Record where the water settled; do not fail on it. The claim is the obsidian, so let
            // the cast decide — `cast.missed.i` names any cell that did not turn.
            //
            // NOTE: this was relaxed on the theory that for the bottom pair the water falls into the
            // very cell about to be cast and lava poured there still yields obsidian. Run 17 ran all
            // ten cells on that assumption and returned `frame.cast=0/10` — so the theory is WRONG
            // and the problem is not this precondition. Nothing casts in a carved mould at all,
            // while the built arena mould casts 10/10. Keep the relaxation (the precondition was
            // never the blocker) but do not read it as evidence the geometry works.
            if (ctx.level().getFluidState(wet).isEmpty())
                rig.evidence("water.fell." + i, wet.toShortString() + " 空了，水多半落进了目标格 "
                        + cell.toShortString() + "（现在是 " + ctx.level().getBlockState(cell).getBlock() + "）");
            BlockPos src = pool.get(Math.min(i, pool.size() - 1));
            // climb UP to the pool → fill → climb back DOWN to the mould → pour. Both climbs are
            // spelled out; neither was, and each cost a run to find. See goUpToThePool and
            // returnToTheForge.
            goUpToThePool(ctx, rig, src.getY(), "lava" + i, () ->
            fillFrom(ctx, rig, src, "lava" + i, Items.LAVA_BUCKET,
                    () -> returnToTheForge(ctx, rig, base.getY(), "cast" + i,
                    () -> placeFluid(ctx, rig, cell, away, Items.LAVA_BUCKET,
                    "cast" + i, () -> rig.settle(new HoldStill(3), 12, () -> {
                var got = ctx.level().getBlockState(cell).getBlock();
                if (got != Blocks.OBSIDIAN)
                    rig.evidence("cast.missed." + i, cell.toShortString() + " = " + got
                            + "（旁边 " + wet.toShortString() + " 是 "
                            + ctx.level().getBlockState(wet).getBlock() + "）");
                // Stop on the FIRST cell that will not cast. Nothing is forfeited: a frame missing
                // one cell can reach 9/10 at best, and `lightIt` fails on anything under ten — so
                // every run that would have continued was already a failing run. What it buys is the
                // clock. Run 17 spent 39 240 ticks (32 minutes) walking all ten cells to report
                // `0/10`, which is the same finding cell one had already made in about a minute, and
                // that cost is paid on every future attempt at this geometry.
                if (i == 0 && got != Blocks.OBSIDIAN) {
                    ctx.fail("第一格就没浇成黑曜石：" + cell.toShortString() + " = " + got
                            + "（水在 " + wet.toShortString() + " = "
                            + ctx.level().getBlockState(wet).getBlock() + "）—— 十格都会一样，"
                            + "不再走完。挖出来的模腔浇不出黑曜石，砌出来的竞技场模腔可以："
                            + "差别在每一格有没有底和背，不在某一格");
                    return;
                }
                // The bucket is empty again, which is exactly what taking the water back needs —
                // and it is also what leaves the interior clear without a separate clean-up trip.
                // Strict, including on the last cell: water left standing in an interior cell is a
                // cell that cannot become portal, so `lightIt` would report 5/6 for a frame that is
                // actually complete.
                fillFrom(ctx, rig, wet, "recover" + i, Items.WATER_BUCKET,
                        () -> castCell(ctx, rig, base, away, pool, i + 1, then));
            })))));
        });
    }

    /** Stand level with {@code target} and empty the held bucket into it, aiming at the solid block
     *  behind it. Level, because a steep ray enters the face a block low and lands in the wrong cell. */
    private static void placeFluid(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                   net.minecraft.world.item.Item held, String tag, Runnable then) {
        placeFluid(ctx, rig, target, away, held, tag, POUR_APPROACHES, then);
    }

    /** How many times a pour may re-walk at its cell before the rung stops. Two, plus the one it
     *  started with: this is a few blocks inside a chamber the body just carved, so a leg that ends
     *  out of reach three times is not a slow walk, it is a body that cannot get there. */
    private static final int POUR_APPROACHES = 3;

    /**
     * A cell the body can STAND in and from which this pour provably lands in {@code target}.
     *
     * <p>The spot used to be arithmetic — two blocks back along {@code away}, at the target's own
     * height — and level with the target is the right idea for the ray. It is the wrong idea for the
     * body: the alcove is hollow, so "level with a cell four rows up" is a cell with nothing under
     * it, and asking the walker to occupy thin air is what wedged a run at {@code -9,53,20}, three
     * blocks outside the corridor it had just carved, unable to move for three identical attempts.
     *
     * <p>So both halves are asked properly. <b>Standable</b> — feet and head clear, something solid
     * underfoot — and <b>useful</b>, meaning the same clip vanilla is about to run lands on the
     * backing's near face, which is what puts the fluid in {@code target} and nowhere else. Nearest
     * to the body wins, so a cell it is already standing in costs no walk at all.
     */
    private static BlockPos standToPour(ServerLevel level, JourneyRig rig, BlockPos target,
                                        Direction away, Map<String, Integer> why) {
        BlockPos backing = target.relative(away);
        BlockPos from = rig.player().blockPosition();
        BlockPos best = null, standable = null;
        double bestD = Double.MAX_VALUE, standableD = Double.MAX_VALUE;
        for (int back = 1; back <= 4; back++)
            for (int side = -2; side <= 2; side++)
                for (int dy = 0; dy >= -6; dy--) {
                    BlockPos foot = target.relative(away.getOpposite(), back)
                            .relative(away.getClockWise(), side).above(dy);
                    double d = foot.distSqr(from);
                    if (!level.getBlockState(foot.below()).blocksMotion()) {
                        why.merge("脚下不实心", 1, Integer::sum); continue;
                    }
                    if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) {
                        why.merge("落脚格被占", 1, Integer::sum); continue;
                    }
                    BlockPos head = foot.above();
                    if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
                        why.merge("头顶被占", 1, Integer::sum); continue;
                    }
                    // Standable, whatever the ray says. Kept separately so a body that can stand
                    // somewhere sensible is never left with nowhere to go because the ray test is
                    // stricter than it should be — the pour's own `.picks` gate still refuses to
                    // spend the bucket, so falling back here cannot cause a wrong-cell pour.
                    if (d < standableD) { standableD = d; standable = foot; }
                    // The eye a body standing here would have, and the clip a filled bucket runs
                    // from it. `Fluid.NONE`, because that is what a non-empty bucket uses.
                    var eye = new net.minecraft.world.phys.Vec3(foot.getX() + 0.5,
                            foot.getY() + rig.player().getEyeHeight(), foot.getZ() + 0.5);
                    var aim = net.minecraft.world.phys.Vec3.atCenterOf(backing);
                    if (eye.distanceTo(aim) > BUCKET_REACH) {
                        why.merge("够不着背板", 1, Integer::sum); continue;
                    }
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                            net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, rig.player()));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                        why.merge("射线没打到方块", 1, Integer::sum); continue;
                    }
                    if (!hit.getBlockPos().equals(backing)) {
                        why.merge("射线停在 " + hit.getBlockPos().toShortString() + " "
                                + level.getBlockState(hit.getBlockPos()).getBlock(), 1, Integer::sum);
                        continue;
                    }
                    if (!backing.relative(hit.getDirection()).equals(target)) {
                        why.merge("打中背板的 " + hit.getDirection() + " 面", 1, Integer::sum); continue;
                    }
                    if (d < bestD) { bestD = d; best = foot; }
                }
        return best != null ? best : standable;
    }

    private static void placeFluid(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                   net.minecraft.world.item.Item held, String tag, int tries,
                                   Runnable then) {
        BlockPos backing = target.relative(away);
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        BlockPos goal = standToPour(ctx.level(), rig, target, away, why);
        if (goal == null) {
            ctx.fail("模腔里没有能浇到 " + target.toShortString() + " 的落脚点："
                    + "要求脚下实心、头顶两格空、射线打在背板 " + backing.toShortString()
                    + " 的近面上 —— 身体在 " + rig.player().blockPosition()
                    + "，各项否决计数：" + why);
            return;
        }
        rig.evidence(tag + ".stand", goal.toShortString() + " 否决计数 " + why);
        rig.settle(new IntentProcess(new Intent(new Goal.Block(goal))), 1_200, () -> {
            holdForUse(rig, held, tag);
            rig.body().avatar().aimAtBlock(backing);
            // Clear a plant off the line first. This rung's lake is at y=63 — on the SURFACE — so
            // unlike the underground forge it is standing in grass, and grass is REPLACEABLE: the
            // pour would not miss, it would succeed into the grass cell and be read as "no obsidian
            // here". Same swing the obsidian rung uses, and for the same reason mine cannot do it.
            clearPlantOnLine(ctx, rig, backing, tag, () -> rig.settle(new HoldStill(2), 10, () -> {
                // Where the fluid is actually going to land, recorded BEFORE it is spent. A filled
                // bucket clips with `Fluid.NONE` and empties into the cell in front of the face it
                // hits, so this pick IS the destination — and without it a pour that succeeded into
                // the wrong cell is indistinguishable from a pour that did not work, which is the
                // shape of the last three rounds of this rung's investigation. `pourInto` has had
                // this instrument for a while; the ten casts that matter never did.
                ServerLevel lvl = ctx.level();
                var hit = aimedAt(rig.player(), BUCKET_REACH, false);
                BlockPos lands = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos().relative(hit.getDirection()) : null;
                rig.evidence(tag + ".picks", (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos().toShortString() + " " + lvl.getBlockState(hit.getBlockPos()).getBlock()
                          + " face=" + hit.getDirection() + " → 落进 " + lands.toShortString()
                        : String.valueOf(hit.getType()))
                        + "（想浇 " + target.toShortString() + "，背板 " + backing.toShortString()
                        + "=" + lvl.getBlockState(backing).getBlock()
                        + "，身体 " + rig.player().blockPosition().toShortString() + "）");
                rig.evidence(tag + ".before", target.toShortString() + "="
                        + lvl.getBlockState(target).getBlock());
                // Do not spend the bucket unless the ray lands where the plan says. This is the same
                // clip vanilla is about to do, so it is a PREDICTION and not a heuristic — which is
                // why it replaced a distance test: "within arm's length of the backing's centre" was
                // the first guard here and it rejected a pour at 4.4 m that would have worked, three
                // times, from a body that never moved between attempts. What actually decides the
                // outcome is which cell the fluid lands in, and that is knowable exactly.
                if (lands == null || !lands.equals(target)) {
                    if (tries > 1) {
                        placeFluid(ctx, rig, target, away, held, tag, tries - 1, then);
                        return;
                    }
                    ctx.fail("浇不到指定格：想浇 " + target.toShortString() + "（瞄背板 "
                            + backing.toShortString() + "），射线会把流体放进 "
                            + (lands == null ? String.valueOf(hit.getType()) : lands.toShortString())
                            + "，身体在 " + rig.player().blockPosition()
                            + " —— 没有倒；倒下去 use 照样报 CONSUME，"
                            + "然后这一级会把失败写成「浇不出黑曜石」");
                    return;
                }
                rig.evidence(tag + ".result", String.valueOf(rig.body().avatar().useItemInHand()));
                then.run();
            }));
        });
    }

    /** Break whatever no-collider block the aim ray stops on before {@code want}, then continue.
     *  One swing only: if the line is blocked by something solid, that is a placement problem and
     *  the caller's own evidence should say so rather than this quietly digging through it. */
    private static void clearPlantOnLine(SceneContext ctx, JourneyRig rig, BlockPos want,
                                         String tag, Runnable then) {
        ServerLevel level = ctx.level();
        var hit = aimedAt(rig.player(), TUNNEL_REACH, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || hit.getBlockPos().equals(want)
                || !level.getBlockState(hit.getBlockPos()).getCollisionShape(level, hit.getBlockPos()).isEmpty()) {
            then.run();
            return;
        }
        BlockPos plant = hit.getBlockPos();
        rig.evidence(tag + ".clearedPlant", plant.toShortString() + " "
                + level.getBlockState(plant).getBlock());
        var av = rig.body().avatar();
        av.aimAtBlock(plant);
        av.breakHold(true);
        av.continueDestroy(plant);
        av.breakHold(false);
        rig.settle(new HoldStill(3), 12, () -> {
            av.aimAtBlock(want);
            then.run();
        });
    }

    /**
     * Fill the (empty) bucket from a fluid source, standing beside it — and stop when it does not.
     *
     * <p>This used to record the {@code InteractionResult} and carry on regardless, which is how run
     * 21 poured nothing into cell zero and reported it as a casting failure. What actually happened
     * is one line above that: {@code lava0.result=FAIL}, the bucket still empty, and the pour then
     * ran with <b>cobblestone in the hand</b> ({@code cast0.hand=拿不到 minecraft:lava_bucket}).
     * A `PASS` from a block item is byte-identical to a bucket whose ray missed, so the rung's
     * verdict named the cast — three inferences from a fill nobody checked.
     *
     * <p>{@code FAIL} from an empty bucket means vanilla saw a block that is not a pickable source:
     * either the source is gone, or there is rock between the eyes and it. Those want opposite
     * responses, so the miss line records the range and what the ray actually stopped on, and the
     * retry re-targets the nearest source to WHERE THE BODY NOW IS rather than asking the same
     * question from the same cell — a retry that changes nothing is not a retry.
     */
    private static void fillFrom(SceneContext ctx, JourneyRig rig, BlockPos src, String tag,
                                 net.minecraft.world.item.Item wanted, Runnable then) {
        fillFrom(ctx, rig, src, tag, wanted, FILL_APPROACHES, then);
    }

    /** How many sources a fill may try before the rung stops. Three: one for a walk that ended
     *  short, one for a source another cast already spent, and one to be unlucky with. */
    private static final int FILL_APPROACHES = 3;

    private static void fillFrom(SceneContext ctx, JourneyRig rig, BlockPos src, String tag,
                                 net.minecraft.world.item.Item wanted, int tries, Runnable then) {
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(wanted));
        boolean lava = wanted == Items.LAVA_BUCKET;
        rig.settle(new IntentProcess(new Intent(new Goal.Near(src, 2))), 1_500, () -> {
            // Aim at a source the body can SEE, not at the one the plan named. `Goal.Near` puts the
            // body within two blocks of the target and says nothing about what is between them, and
            // at a lake's edge that is routinely rock: measured at 2.3 m from a source with
            // `射线停在 -10,63,21 Block{minecraft:stone}`, then again at 1.6 m from a different one,
            // stopped by the same finger of bank. A pool of seventy-five sources always has one with
            // a clear line, so the fix is to pick that one rather than to dig the bank away — which
            // would also let the lake into the ground the rung is standing on.
            BlockPos seen = visibleSourceNear(rig, lava, FILL_RESEARCH);
            BlockPos aim = seen == null ? src : seen;
            if (!aim.equals(src)) rig.evidence(tag + ".aim", src.toShortString() + " → "
                    + aim.toShortString() + "（计划的那格被挡住，改瞄看得见的一格）");
            holdForUse(rig, Items.BUCKET, tag);
            rig.body().avatar().aimAtBlock(aim);
            final BlockPos aimed = aim;
            rig.settle(new HoldStill(2), 10, () -> {
                rig.evidence(tag + ".result", String.valueOf(rig.body().avatar().useItemInHand()));
                if (rig.carrying(id) >= 1) { then.run(); return; }
                ServerLevel level = ctx.level();
                var hit = aimedAt(rig.player(), BUCKET_REACH, true);
                double range = rig.player().getEyePosition()
                        .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(aimed));
                rig.evidence(tag + ".miss." + tries, String.format(java.util.Locale.ROOT,
                        "桶里还是空的；瞄 %s（现在是 %s），距 %.1fm，射线停在 %s",
                        aimed.toShortString(), level.getBlockState(aimed).getBlock(), range,
                        hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                                ? hit.getBlockPos().toShortString() + " "
                                  + level.getBlockState(hit.getBlockPos()).getBlock()
                                : String.valueOf(hit.getType())));
                BlockPos other = lava
                        ? JourneyTerrain.nearestLavaSource(level, rig.player().blockPosition(), FILL_RESEARCH)
                        : JourneyTerrain.shallowWaterNear(rig, FILL_RESEARCH);
                if (tries > 1 && other != null && !other.equals(aimed)) {
                    rig.evidence(tag + ".retarget." + tries, aimed.toShortString() + " → "
                            + other.toShortString());
                    fillFrom(ctx, rig, other, tag, wanted, tries - 1, then);
                    return;
                }
                ctx.fail("装不到 " + id + "：瞄了 " + aimed.toShortString() + " 没装上，"
                        + (other == null ? "身边 " + FILL_RESEARCH + " 格内也没有别的源块"
                                         : "改瞄 " + other + " 仍然不行")
                        + " —— 空着桶走下去只会把失败写成「浇不出黑曜石」，而真正的失败在这里"
                        + "（见 " + tag + ".miss.*）");
            });
        });
    }

    /** How far to look for another source when a fill did not take. Small: the body is standing at
     *  the pool it walked to, and a source further than this is a different walk, not a retry. */
    private static final int FILL_RESEARCH = 8;

    /** A survival player's block reach, which is what {@code Item.getPlayerPOVHitResult} traces with.
     *  {@link #TUNNEL_REACH} is half a block longer and is the digging figure; using it here made a
     *  miss report a blocker that was never inside the bucket's own ray. */
    private static final double BUCKET_REACH = 4.5;

    /**
     * The nearest source of the right fluid whose line from the body's eyes is CLEAR.
     *
     * <p>The question a bucket actually asks, and the one nothing was asking. {@code useItemInHand}
     * clips from the eyes with {@code Fluid.SOURCE_ONLY} and fills from whatever it lands on, so
     * "there is a source two blocks away" and "this bucket will fill" are different claims — the
     * second needs the cells between to be empty, and at a lake's edge they routinely are not.
     *
     * <p>Clipped per candidate rather than aimed-and-tried, so choosing costs no ticks and no bucket.
     * The same clip vanilla will do is done here first, which makes this a prediction rather than a
     * heuristic: a cell this returns is a cell the bucket fills from.
     */
    private static BlockPos visibleSourceNear(JourneyRig rig, boolean lava, int radius) {
        ServerLevel level = rig.ctx().level();
        var fp = rig.player();
        net.minecraft.world.phys.Vec3 eye = fp.getEyePosition();
        BlockPos centre = fp.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    var fluid = level.getFluidState(c);
                    if (!fluid.isSource()) continue;
                    if (fluid.is(net.minecraft.tags.FluidTags.LAVA) != lava) continue;
                    if (lava && !level.getBlockState(c).is(Blocks.LAVA)) continue;
                    var target = net.minecraft.world.phys.Vec3.atCenterOf(c);
                    double d = eye.distanceToSqr(target);
                    if (d >= bestD || d > BUCKET_REACH * BUCKET_REACH) continue;
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, target,
                            net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, fp));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue;
                    if (!hit.getBlockPos().equals(c)) continue;
                    bestD = d;
                    best = c;
                }
        return best;
    }

    private static int countObsidian(ServerLevel level, BlockPos base, Direction away) {
        return JourneyForge.countObsidian(level, base, away);
    }

    /**
     * Strike the frame.
     *
     * <p>{@code FlintAndSteelItem} overrides {@code useOn} and has no {@code use}, so this must go
     * through {@code useBlock(cell, face)} — called the other way it returns {@code PASS} and the
     * world does not move. The fire lands at {@code clickedPos.relative(clickedFace)}, so the click
     * is on the frame's bottom-left obsidian with the face pointing UP into the interior.
     */
    private static void lightIt(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                int surfaceY) {
        ServerLevel level = ctx.level();
        int cast = countObsidian(level, base, away);
        rig.evidence("frame.obsidian", cast + "/" + RING.length);
        if (cast < RING.length) {
            ctx.fail("门框没浇满：只有 " + cast + "/" + RING.length + " 块黑曜石 —— 点不着一个缺角的门");
            return;
        }
        BlockPos hearth = frameCell(base, away, 0, 0);
        BlockPos doorway = hearth.above();
        rig.attempting("点火");
        rig.settle(new IntentProcess(new Intent(new Goal.Near(hearth, 3))), 1_500, () -> {
            holdForUse(rig, Items.FLINT_AND_STEEL, "light");
            rig.body().avatar().aimAtBlock(hearth);
            rig.settle(new HoldStill(2), 10, () -> {
                rig.body().avatar().useBlock(hearth, Direction.UP);
                rig.settle(new HoldStill(5), 20, () -> {
                    int lit = 0;
                    for (int ix = 0; ix <= 1; ix++)
                        for (int iy = 1; iy <= 3; iy++)
                            if (level.getBlockState(frameCell(base, away, ix, iy)).getBlock()
                                    == Blocks.NETHER_PORTAL) lit++;
                    rig.evidence("portal.cells", lit + "/6");
                    rig.evidence("light.cellAfter", String.valueOf(level.getBlockState(doorway).getBlock()));
                    rig.evidence("bucket.after", rig.carrying("minecraft:bucket")
                            + " 空 / " + rig.carrying("minecraft:water_bucket") + " 水");
                    ctx.expect(lit).as("the portal the body carved, cast and struck is lit").isEqualTo(6);
                    rig.reach("在 y=" + doorway.getY() + " 就地浇出十块黑曜石并点亮 " + lit
                            + " 格传送门（自带一桶水下井，浇完水还在桶里）");
                });
            });
        });
    }

    /** Climb back to daylight carrying the lava, then cast. */
    private static void leaveWithTheLava(SceneContext ctx, JourneyRig rig, int surfaceY) {
        rig.attempting("背着岩浆爬回地面");
        JourneyShaft.climbOut(rig, surfaceY, () -> {
            rig.evidence("lava_bucket.atSurface", rig.carrying("minecraft:lava_bucket"));
            castBesideWater(ctx, rig);
        });
    }

    /**
     * Pour the lava into standing water, at a cell chosen before the pour.
     *
     * <p>"Chosen before" is the whole assertion. Obsidian appearing SOMEWHERE after a bucket is
     * emptied proves the fluids met; obsidian appearing in the cell the rung named proves the body
     * put it there, and only the second is a capability a portal can be built on.
     */
    private static void castBesideWater(SceneContext ctx, JourneyRig rig) {
        rig.attempting("找一处底下是实心的浅水，把岩浆倒进去");
        BlockPos shallow = JourneyTerrain.shallowWaterNear(rig, 24);
        if (shallow != null) { approachAndPour(ctx, rig, shallow); return; }
        // Nothing underfoot: fall back on the surveyed water. A swamp normally makes this branch
        // dead code, which is exactly why it is worth recording when it is not.
        BlockPos w = JourneyRoute.firstWater;
        rig.evidence("cast.walkedToSurveyedWater", w.toShortString());
        walkToColumn(rig, "water", w.getX(), w.getZ(), 0, 16_000,
                () -> approachAndPour(ctx, rig, JourneyTerrain.shallowWaterNear(rig, 12)),
                () -> ctx.fail("走不到 firstWater " + w.toShortString()
                        + "：停在 " + rig.player().blockPosition()));
    }

    private static void approachAndPour(SceneContext ctx, JourneyRig rig, BlockPos water) {
        approachAndPour(ctx, rig, water, CAST_APPROACHES);
    }

    /** How many times the cast may walk at its target before saying it cannot get there. A pour is
     *  a five-block ray and {@code Goal.Near} can end short, so arriving is a thing to CHECK rather
     *  than a thing to assume — measured, a run reached the pour at {@code cast.range=12.12} with
     *  {@code cast.picks=MISS}, twelve blocks from water it had walked at once. */
    private static final int CAST_APPROACHES = 3;

    private static void approachAndPour(SceneContext ctx, JourneyRig rig, BlockPos water, int tries) {
        if (water == null) {
            ctx.fail("附近没有底下实心的水面：身体在 " + rig.player().blockPosition()
                    + "（深水没有落点，浇下去的岩浆会沉，铸出的黑曜石也拿不回来）");
            return;
        }
        rig.evidence("cast.target", water.toShortString());
        // Adjacent, not merely near. A pour is a ray, and a ray aimed at a cell below the bank's lip
        // hits the lip: measured in the arena, where a mould two cells away swallowed the bucket and
        // left the target empty while the use still reported CONSUME.
        rig.settle(new IntentProcess(new Intent(new Goal.Near(water, 2))), 2_000, () -> {
            BlockPos again = JourneyTerrain.shallowWaterNear(rig, 8);
            BlockPos aim = again == null ? water : again;
            // Did the walk actually ARRIVE? `Goal.Near` reporting done is not the same as being in
            // reach, and the pour has no way to tell the difference afterwards: out of range the ray
            // returns MISS, which the blocked-line guard then reports as an obstruction it cannot
            // clear. One rung's evidence read `cast.range=12.12, cast.picks=MISS` — nothing was in
            // the way at all, the target was simply eight blocks past the end of the ray.
            double range = rig.player().getEyePosition()
                    .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(aim));
            if (range > TUNNEL_REACH - 1.0 && tries > 0) {
                rig.evidence("cast.tooFar", String.format(java.util.Locale.ROOT,
                        "%.1fm > %.1fm，再走一次", range, TUNNEL_REACH - 1.0));
                approachAndPour(ctx, rig, aim, tries - 1);
                return;
            }
            pourInto(ctx, rig, aim);
        });
    }

    private static void pourInto(SceneContext ctx, JourneyRig rig, BlockPos target) {
        pourInto(ctx, rig, target, CAST_CLEARINGS);
    }

    /**
     * How many things the pour may clear out of its own line before giving up.
     *
     * <p>Two. The pour aims at the bed UNDER the water and the fluid lands in the cell in front of
     * whatever face the ray hits — so anything standing in that line silently moves the target one
     * cell. Measured: the rung chose `-15,62,42`, aimed at `-15,61,42`, and the pick came back
     * <b>`-15,62,42`</b> — the water cell itself, because it held <b>seagrass</b>. Seagrass has no
     * collision but it does have a {@code Block.OUTLINE} shape, which is what a bucket's clip uses.
     * The pour then reported `CONSUME`, the bucket emptied, and obsidian appeared at `-15,62,41`:
     * one cell short, cast against the near face of the plant. Swamp water is full of seagrass, so
     * this is terrain the ladder will meet every run, not an oddity.
     */
    private static final int CAST_CLEARINGS = 2;

    private static void pourInto(SceneContext ctx, JourneyRig rig, BlockPos target, int clearings) {
        ServerLevel level = ctx.level();
        var fp = rig.player();
        rig.evidence("cast.cell", target.toShortString());
        rig.evidence("cast.floor", String.valueOf(level.getBlockState(target.below()).getBlock()));
        rig.evidence("cast.range", String.format(java.util.Locale.ROOT, "%.2f",
                fp.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(target))));
        // Aimed at the FLOOR under the water, not at the water: a filled bucket ray-traces with
        // fluids ignored, so the block it lands on is the bed, and the fluid goes into the cell in
        // front of the face it hit — which is the water cell above.
        holdForUse(rig, Items.LAVA_BUCKET, "cast");
        rig.body().avatar().aimAtBlock(target.below());
        rig.settle(new HoldStill(2), 10, () -> {
            // hitFluids=false, matching what a FILLED bucket's own clip does. A pick that ignores
            // fluids is the only pick that predicts this pour.
            var hit = aimedAt(fp, TUNNEL_REACH, false);
            rig.evidence("cast.picks", hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    ? hit.getBlockPos().toShortString() + " " + level.getBlockState(hit.getBlockPos()).getBlock()
                      + " face=" + hit.getDirection()
                    : String.valueOf(hit.getType()));
            // Check the line BEFORE spending the bucket. The tunnel already works this way — mine
            // whatever the ray hits first, because that IS the obstruction — and the cast, which has
            // exactly one bucket of lava and no second try, did not. A pour down a blocked line is
            // not a failed pour: it is a SUCCESSFUL pour into the wrong cell, and by the time the
            // assertion reads the chosen cell the lava is gone.
            BlockPos bed = target.below();
            boolean onLine = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(bed);
            if (!onLine) {
                BlockPos inTheWay = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos() : null;
                if (clearings > 0 && inTheWay != null) {
                    // A plant stops the RAY but not the BODY. short_grass and seagrass have no
                    // collider — the body walks through them — yet `getPlayerPOVHitResult` clips on
                    // Block.OUTLINE, which a plant has, so they land square on the aiming line. And
                    // MineProcess will not remove them: measured, two clearings in a row left the
                    // same seagrass standing, and short_grass cost run 9 this rung.
                    //
                    // So swing at it directly, which is what a player does — aim, hold, destroy.
                    // This is the body's own verb, not staging: `staging.calls` stays 0 and the rung
                    // keeps its claim. Solid blockers still go through mine, where the drop matters.
                    boolean noCollider = level.getBlockState(inTheWay)
                            .getCollisionShape(level, inTheWay).isEmpty();
                    rig.evidence("cast.blockedBy", inTheWay.toShortString() + " "
                            + level.getBlockState(inTheWay).getBlock()
                            + (noCollider ? "（无碰撞箱的植物：直接挥手清掉，mine 清不动）"
                                          : "（挡在瞄准线上，先清掉）"));
                    if (noCollider) {
                        var av = rig.body().avatar();
                        av.aimAtBlock(inTheWay);
                        av.breakHold(true);
                        av.continueDestroy(inTheWay);
                        av.breakHold(false);
                        rig.settle(new HoldStill(3), 12, () -> {
                            rig.evidence("cast.cleared." + inTheWay.toShortString(),
                                    String.valueOf(level.getBlockState(inTheWay).getBlock()));
                            pourInto(ctx, rig, target, clearings - 1);
                        });
                        return;
                    }
                    rig.mineBlock(inTheWay, 600, () -> rig.settle(new HoldStill(5), 20,
                            () -> pourInto(ctx, rig, target, clearings - 1)));
                    return;
                }
                // Out of clearings and the line is still not the bed: STOP. Pouring anyway spends
                // the run's only bucket of lava into whatever the ray does hit, and the rung then
                // reports "casts obsidian in the chosen cell (false)" — which reads as the cast
                // being broken, while `obsidian.anywhere` sits one metre away proving it is not.
                // A failure that names the obstruction is worth more than one that names the goal.
                // (Measured: two clearings in a row failed to remove the same seagrass, so the
                // third attempt poured blind. Why mineBlock cannot break it is a separate finding;
                // not choosing that cell in the first place is the fix, and this is the backstop.)
                ctx.fail("浇筑瞄准线被挡住，且清不掉：想浇 " + target.toShortString()
                        + "（瞄 " + bed.toShortString() + "），射线停在 "
                        + (inTheWay == null ? String.valueOf(hit.getType())
                            : inTheWay.toShortString() + " " + level.getBlockState(inTheWay).getBlock())
                        + "。没有倒 —— 一桶岩浆只有一次机会，倒下去只会浇歪并且把失败写成"
                        + "\"浇不出黑曜石\"");
                return;
            }
            rig.evidence("cast.result", String.valueOf(rig.body().avatar().useItemInHand()));
            rig.settle(new HoldStill(10), 20, () -> {
                var got = level.getBlockState(target).getBlock();
                rig.evidence("cast.cellAfter", String.valueOf(got));
                rig.evidence("lava_bucket.after", rig.carrying("minecraft:lava_bucket"));
                rig.evidence("bucket.after", rig.carrying("minecraft:bucket"));
                // Where it went when it did not go here. An empty bucket with no obsidian in the
                // chosen cell is a misplacement; a full bucket is a refusal; and the two want
                // opposite fixes.
                BlockPos anywhere = rig.nearestBlock("minecraft:obsidian", 8, 4);
                rig.evidence("obsidian.anywhere", anywhere == null ? "无" : anywhere.toShortString());
                ctx.expect(got == Blocks.OBSIDIAN)
                        .as("lava poured into standing water casts obsidian in the chosen cell").isTrue();
                // The claim, and what the claim is NOT. A rung that fetched its lava, climbed one
                // block of thirty-six, and then found water in the cave it was already standing in
                // has cast obsidian — the stage's whole assertion — while the exit it was supposed
                // to prove never happened. Saying so on the green row is the difference between a
                // ladder and a scoreboard.
                String how = rig.player().blockPosition().getY() < 50
                        ? "（在地下 y=" + rig.player().blockPosition().getY() + " 浇的，没能爬回地面）"
                        : "";
                rig.reach("在 " + target.toShortString() + " 浇出黑曜石，桶已回到手上 ×"
                        + rig.carrying("minecraft:bucket") + how);
            });
        });
    }


    /** Stop a stage that needs surveyed coordinates before anyone has surveyed them. */
    private static boolean requireSurvey(SceneContext ctx, JourneyRig rig) {
        if (JourneyRoute.surveyed()) return false;
        rig.attempting("路线未标定：JourneyRoute 常量还是 UNSURVEYED");
        ctx.skip("UNSURVEYED: 先跑 wd.journey01Recon，把日志里的 [journey/recon] 常量烘进 JourneyRoute");
        return true;
    }

    // =====================================================================================
    // 99 — the verdict. One row that says how far this run got, and whether that is far enough.
    // =====================================================================================

    /**
     * The ratchet.
     *
     * <p>The only scene in the family that can turn a short climb into a RED, and it does so against
     * exactly one number: {@link JourneyLedger#FLOOR}. Everything above the floor is free to fail
     * while it is being built; everything at or below it is a promise that has already been kept
     * once, so failing it is a regression and reads as one.
     *
     * <p>It also tears the run down — the body, its chunk ticket, the driver registry — on every
     * path, because the journey deliberately leaves state alive between its stages and something has
     * to be the end of it.
     */
    private static void verdict(SceneContext ctx) {
        try {
            JourneyStage height = JourneyLedger.height();
            List<String> staging = JourneyLedger.stagingCalls();

            ctx.record("journey.height", height == null ? "NONE" : height.name());
            ctx.record("journey.floor", JourneyLedger.FLOOR.name());
            ctx.record("journey.summit", JourneyStage.summit().name());
            ctx.record("journey.stagingCalls", staging.size());
            JourneyLedger.report().forEach((rung, outcome) -> ctx.record("rung." + rung, outcome));

            // "全程零布景" as a measurement. A run that staged anything has not demonstrated a
            // playthrough, however many rungs it recorded, so this is checked before the height.
            ctx.expect(staging).as("staging calls (a playthrough stages nothing)").isEmpty();

            int reached = height == null ? -1 : height.ordinal();
            int floor = JourneyLedger.FLOOR.ordinal();
            if (reached < floor) {
                ctx.fail("journey 回退：本次只爬到 "
                        + (height == null ? "NONE" : height.name() + "(" + height.label() + ")")
                        + "，低于已承诺的地板 " + JourneyLedger.FLOOR.name()
                        + "(" + JourneyLedger.FLOOR.label() + ")。逐级结果见 rung.* 记录。");
                return;
            }
            ctx.passNote("journey 爬到 " + height.name() + "(" + height.label() + ")"
                    + "，地板 " + JourneyLedger.FLOOR.name()
                    + "，峰顶 " + JourneyStage.summit().name()
                    + "，布景调用 " + staging.size() + " 次");
        } finally {
            JourneyRig.teardown();
        }
    }
}
