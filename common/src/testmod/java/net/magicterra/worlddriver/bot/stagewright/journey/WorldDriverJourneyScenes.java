package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CostModifier;
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

// Split out of this file on 2026-08-23 when it hit the 3000-line budget. Imported rather than
// qualified so the nine call sites read exactly as they did before the move — a mechanical split
// should leave no diff in the code that USES it, or the next reader has to work out whether the
// behaviour moved too. See JourneyStation's class comment for where the seam was cut.
import static net.magicterra.worlddriver.bot.stagewright.journey.JourneyStation.makeRoomForAStation;
import static net.magicterra.worlddriver.bot.stagewright.journey.JourneyStation.reclaimTableIfLeftStanding;
import static net.magicterra.worlddriver.bot.stagewright.journey.JourneyStation.takeTableWhereItStands;

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

        // ---- every rung from here up has scripted steps ----
        //
        // It did not always. This block used to hold the frontier — rungs registered but not
        // written, each recording NOT_SCRIPTED and letting everything above it report BLOCKED, so
        // that the ledger read as a map of the climb rather than as a list of passes. That was the
        // right shape while there was a frontier, and it has one lasting cost worth remembering: a
        // NOT_SCRIPTED rung records PASS with skipped=true, so it counts as a pass in every summary
        // that does not look at the flag. The last of them (BED) was written on 2026-08-22.
        out.add(stage("wd.journey05StoneTools", JourneyStage.STONE_TOOLS, 40_000,
                WorldDriverJourneyScenes::stoneTools));
        // 30 000: the hunt walks out to the animal and now walks back to spawn, and the return
        // leg is the whole reason the rungs above it start somewhere they can navigate from.
        out.add(stage("wd.journey06Food", JourneyStage.FOOD, 30_000, WorldDriverJourneyScenes::food));
        // 26 000: this rung's length is the flock's to decide, not ours. It kills up to
        // WOOL_HUNT_ROUNDS sheep because three wool of ONE colour is what a bed wants and a sheep
        // drops one of whatever colour it happens to be, and each kill is a walk. Cheap when the
        // seed has no sheep at all — two scans and a finding.
        out.add(stage("wd.journey07Bed", JourneyStage.BED, 26_000, JourneyBedRung::bed));
        // 30 000, and the happy path still costs 33 ticks: a budget is a cap, not a duration, so the
        // "regression sensor that answers in eight seconds" property this rung was sized for is
        // untouched. What 8 000 could not afford was the one branch that has to leave the spot —
        // when the bag is short, the top-up may now walk to the surveyed stone, sink a shaft and
        // climb back out, which is the stone rung's own shape and cost it 3 720 ticks there.
        out.add(stage("wd.journey08Furnace", JourneyStage.FURNACE, 30_000,
                JourneyFurnaceRung::furnace));
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
                JourneyPortalRung::portalLit));
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
        // A CLIMB CHOOSES ITS OWN MOULD. The rehearsal's staged side is a static, so clearing it on
        // the ladder's first scene is what makes「climbs are unaffected」structural rather than a
        // claim about which gradle task ran. Cheap, and it cannot be forgotten the way an argument can.
        JourneyRehearsal.resetStagedForgeSide();
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
        if (bestN >= JourneyForge.RING.length) {
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

    // The weaker「still holds the KIND of thing it was baked for」sibling of checkLandmark was
    // written for a landmark whose survey is not reproducible, and never called: secondTree, the
    // only such landmark, is ADOPTED from this run's own survey above rather than checked at all.
    // A helper that looks like an assertion and runs never is worse than no helper, so it is gone.

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
        // And then remove what world GENERATION put on the route, for the same reason the herd is
        // surveyed here: this is the one moment the ladder is standing at spawn with nothing spent.
        // See JourneyPeace for why `doMobSpawning=false` never covered it — the run of 2026-08-22
        // died on rung 7 to a swamp-hut witch while the verdict row asserted「全程没有敌对生物」.
        surveyTheHerd(rig, () ->
                JourneyPeace.sweepStructureHostiles(ctx, rig, () ->
                        rig.reach("空手立于出生点 " + Math.round(fp.getX()) + "," + Math.round(fp.getZ()))));
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

    /** How long the walk back up to the treeline gets. Generous because it starts at the bottom of
     *  a mineshaft the run dug itself: the body has to climb out before it can go anywhere, and
     *  that climb is the expensive half — the surface walk that follows is a few hundred blocks of
     *  ordinary ground the route has already crossed once. */
    private static final int WOOD_RETURN_TICKS = 12_000;

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
    static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, Runnable onArrived, Runnable onStuck) {
        walkToColumn(rig, what, x, z, tolerance, budget, MAX_WALK_ATTEMPTS, List.of(),
                onArrived, onStuck);
    }

    /**
     * The same leg, with a per-intent cost bias every attempt of it carries.
     *
     * <p>{@code Intent} has taken a bias list since it was written — 「avoid a region, prefer a Y
     * band, leash to an anchor」 is its own javadoc — and nothing in this suite had ever passed one.
     * The rung that needed it is 12: the pathfinder's route to the lava runs along the crater's rim,
     * where the walker's footing guard sneak-pins the body at {@code sole = 0.0000} and it can then
     * never move, and no choice of DESTINATION can steer a route (measured — see
     * {@link JourneyTerrain#poolsLipCells}).
     *
     * <p>It is threaded onto the midpoint leg as well, and that is not tidiness: the midpoint of a
     * body wedged on the rim and a column on the far side <b>is the pool</b>, which the same run
     * printed as {@code lava.viaMidpoint = -10,20 (卡在 -13, 66, 21)}. An unbiased recovery from a
     * biased leg would walk into exactly what the leg was told to avoid.
     */
    static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, List<CostModifier> bias,
                                     Runnable onArrived, Runnable onStuck) {
        walkToColumn(rig, what, x, z, tolerance, budget, MAX_WALK_ATTEMPTS, bias, onArrived, onStuck);
    }

    static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, int left, Runnable onArrived, Runnable onStuck) {
        walkToColumn(rig, what, x, z, tolerance, budget, left, List.of(), onArrived, onStuck);
    }

    static void walkToColumn(JourneyRig rig, String what, int x, int z, int tolerance,
                                     int budget, int left, List<CostModifier> bias,
                                     Runnable onArrived, Runnable onStuck) {
        BlockPos before = rig.player().blockPosition();
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(x, z, tolerance), bias)), budget, () -> {
            BlockPos at = rig.player().blockPosition();
            double away = Math.hypot(at.getX() - x, at.getZ() - z);
            int attempt = MAX_WALK_ATTEMPTS - left + 1;
            rig.evidence(what + ".arrivedDistance", Math.round(away));
            rig.evidence(what + ".walkAttempts", attempt);
            // The goal is Goal.XZ — two dimensions, deliberately. So `arrivedDistance=0` is TRUE of a
            // body standing in the right column and thirteen blocks up its own pillar, and true again
            // of one at the bottom of a shaft. Neither the distance nor the attempt count can tell
            // those apart, and every rung after this one is planned as if the body were on the ground.
            // Recorded on BOTH branches (above the arrival test) because the give-up branch needs it
            // just as much. Evidence only — the judge is unchanged; the fix, when it comes, belongs to
            // whatever left the body up there, not here.
            rig.evidence(what + ".arrivedY", at.getY() + "（起 " + before.getY() + "，净升 "
                    + (at.getY() - before.getY()) + "），脚下="
                    + rig.player().level().getBlockState(at.below()));
            if (away <= ARRIVED_WITHIN) {
                // WHAT THE WALKER SAID, on the ARRIVAL path as well — the row this pair could not
                // carry. `arrivedDistance=4, walkAttempts=1` is the same two digits for two different
                // worlds: a body that walked here and stopped inside the tolerance, and a body the
                // walker GAVE UP ON four blocks out. Measured, the PORTAL_LIT rehearsal of
                // 2026-08-17: the leg ended `failed:no progress for 1200 ticks` with the body
                // sneak-pinned on the lava crater's lip at -13,66,21 (`[walker] footing guard: sole
                // 0.0000 … beside a lethal drop`), ARRIVED_WITHIN accepted it because 4 ≤ 5, and the
                // rung then spent every remaining tick asking that same body to walk five more
                // blocks — about 110 searches from one cell. The end reason existed the whole time
                // and was written only on the branch below, so the results file said nothing.
                rig.evidence(what + ".gotoEnd." + attempt,
                        JourneyLeg.walkerEnd(rig)
                                + "（判为到达：停在 " + at.toShortString() + "，距 " + x + "," + z
                                + " " + Math.round(away) + " 格，容差 " + ARRIVED_WITHIN + "）");
                onArrived.run();
                return;
            }
            // What the walker itself said about the leg. Ninety searches in a row left no record of
            // WHY beyond the search-begin lines, so a wedge and a slow crossing read the same.
            rig.evidence(what + ".goto." + attempt, JourneyLeg.walkerEnd(rig));
            if (left <= 1) { onStuck.run(); return; }
            double moved = Math.hypot(at.getX() - before.getX(), at.getZ() - before.getZ());
            if (moved >= WEDGED_UNDER) {
                walkToColumn(rig, what, x, z, tolerance, budget, left - 1, bias, onArrived, onStuck);
                return;
            }
            int mx = (at.getX() + x) / 2;
            int mz = (at.getZ() + z) / 2;
            rig.evidence(what + ".viaMidpoint", mx + "," + mz + " (卡在 " + at.toShortString() + ")");
            rig.settle(new IntentProcess(new Intent(new Goal.XZ(mx, mz, 3), bias)),
                    Math.max(600, budget / 2),
                    () -> walkToColumn(rig, what, x, z, tolerance, budget, left - 1, bias,
                            onArrived, onStuck));
        });
    }

    /** How far a leg must move for the next attempt to be a different question. Four blocks: a body
     *  that shuffled within its own cell has not found a new vantage point, and asking the same
     *  pathfinder the same question from it costs the whole leg's budget to learn nothing. */
    private static final int WEDGED_UNDER = 4;

    /** How many times a leg re-plans before the rung calls it unreachable. */
    static final int MAX_WALK_ATTEMPTS = 3;

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
                    JourneyShaft.climbOut(rig, JourneyRoute.stoneDescent.getY(), "stone.exit", () -> {
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
                        rig.evidence("craft.lastError", String.valueOf(rig.slotError("craft")));
                        rig.noteAdvancement("minecraft:story/upgrade_tools");
                        ctx.expect(picks).as("stone pickaxes crafted").isAtLeast(1);
                        // AND A SWORD, WHILE THE TABLE AND THE COBBLE ARE BOTH STILL HERE.
                        //
                        // The next two rungs are the ladder's first fights — FOOD kills an animal,
                        // BED kills three sheep — and until 2026-08-22 it walked into both with a
                        // pickaxe in hand, because no rung before them ever crafted a weapon. That
                        // was not a considered trade: the two ingredients (2 cobblestone, 1 stick)
                        // are in the bag at exactly this moment and nowhere later, since the table
                        // does not reliably survive the walk (`craftingTable=0` measured here).
                        //
                        // Best-effort on purpose: a missing sword must not fail the STONE_TOOLS rung,
                        // whose contract is the pickaxe. It gets recorded either way, so a later
                        // fight lost bare-handed can be traced back to this line rather than blamed
                        // on the combat verb.
                        // (craftKeepingTheTable always runs its continuation — a failed craft writes
                        //  stone_sword.crafted=0 and carries on, so this cannot turn the rung red.)
                        craftKeepingTheTable(rig, "minecraft:stone_sword", 6_000, () -> {
                            rig.reach("石镐 ×" + picks + " 到手，石剑 ×"
                                    + rig.carrying("minecraft:stone_sword") + "，剩余圆石 "
                                    + rig.carrying("minecraft:cobblestone"));
                        });
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
            // Weapon in hand BEFORE the swing. CombatProcess has no weapon picker — it swings
            // whatever the last dig left selected — so this rung fought its first animal with a
            // pickaxe until 2026-08-22, while every fight from the Nether onward was equipped.
            rig.evidence("weapon", rig.holdBestWeapon());
            rig.attempting("猎杀 " + prey.species() + "：CombatProcess 没能拿到生肉");
            // GO AND GET IT. Until 2026-08-23 this read `rawFood` the tick the fight finished and
            // never walked anywhere, so the meat this rung banked was only whatever the body happened
            // to step over mid-fight. A rehearsal killed four cows and banked four beef with one more
            // still lying on the ground — which PASSED, because the rung needs one. The leak was
            // invisible to its own assertion, and the identical leak at rung 9 (iron) and rung 14
            // (rods) is fatal. `collectByHand`'s note has said "two rungs needing the same walk is
            // what a shared rig is for" since rung 14 hit it; this is the third rung.
            rig.drive(new CombatProcess(CombatProcess.Mode.KILL, null, prey.species()), 6_000, () ->
              rig.collectAnyOf(RAW_FOODS, JourneyRig.MAX_PICKUP_LEGS, JourneyRig.MAX_PICKUP_LEGS,
                      "kill", () -> {
                int raw = rig.carryingAnyOf(RAW_FOODS);
                rig.evidence("rawFood", raw);
                // THREE causes of rawFood=0, and until 2026-08-23 not one row could tell them
                // apart. ladder-10 died here with the complete evidence being
                // `prey.arrived=minecraft:cow, weapon=minecraft:stone_sword, combat→跑完, rawFood=0`
                // — a body standing next to a cow with a sword, and a fight that "finished".
                // The three: never landed a hit; killed it and left the meat on the ground
                // (the mined-is-not-collected family); or fought something that was not the prey.
                // One row each, taken here rather than reasoned about later.
                //
                // These now read AFTER the collect above, so what they measure has shifted by one
                // step and the shift is the useful one: `kill.onGround` was "what nobody fetched"
                // and is now "what the collect could not reach" — a much narrower accusation. Both
                // rows still separate the three causes, because `rawFood > 0` is what says the meat
                // was collected at all.
                int onGround = 0;
                StringBuilder kinds = new StringBuilder();
                for (String id : RAW_FOODS) {
                    int n = rig.dropsNearby(id, 24);
                    if (n <= 0) continue;
                    onGround += n;
                    kinds.append(kinds.isEmpty() ? "" : "、").append(id).append("×").append(n);
                }
                rig.evidence("kill.onGround", onGround + " 件"
                        + (kinds.isEmpty() ? "（24 格内地上什么肉都没有）" : "（" + kinds + "）"));
                rig.evidence("kill.preyLeft", String.valueOf(rig.nearestPrey(24)));
                rig.evidence("kill.combatError", String.valueOf(rig.slotError("combat")));
                // `combatKills` is the AUTHORITY on whether anything died, and it was already being
                // counted — nobody had asked it. The reading that stood in for it was "no death line
                // in the log window", and that reading is worthless: the FOOD rehearsal of
                // 2026-08-23 killed a cow and banked three beef with the SAME zero death lines,
                // because a death is a channel event (`entity.death`) and never a log row. A count
                // that is always zero because nothing writes it looks exactly like a count that is
                // zero because nothing happened.
                // PER-ENGAGEMENT, not per-rung: `CombatChain.resetCounters` runs from `engage()` and
                // from a fresh auto-fight, i.e. on ENTER — verified, and that is the safe side (a
                // reset on release would zero the very numbers this line reads, the shape of
                // 「被自己描述的事件清掉的读数」). The cost of it being on enter is that a rung which
                // engages twice reports only the last engagement, so read this as "did the LAST
                // fight kill something", and pair a zero with `kill.swings` before concluding.
                rig.evidence("kill.kills", String.valueOf(rig.slotString("combat", "kills"))
                        + "（按交战清零，说的是最后一次交战）");
                // Swings vs kills splits the "no kill" case three ways in one pair of numbers:
                // 0 swings = the in-range branch never fired (a walk/approach problem, not a
                // combat one); swings > 0 with kills 0 = hits went out and did not finish it;
                // and `attackRefusal` names the gate when the swing itself was refused.
                rig.evidence("kill.swings", String.valueOf(rig.slotString("combat", "swings")));
                rig.evidence("kill.preyVitals", String.valueOf(rig.nearestPreyVitals(24)));
                ctx.expect(raw).as("raw food collected from the kill").isAtLeast(1);
                walkHome(rig, () -> rig.reach("猎到 " + prey.species() + "，得生肉 ×" + raw));
              }));
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
        walkHome(rig, "food", then);
    }

    /**
     * As above, under the calling rung's own evidence prefix.
     *
     * <p>Two rungs chase animals now — {@link #food} and {@link #bed} — and both have to hand the
     * body back somewhere the rungs above can navigate from. Sharing the walk but not the key
     * matters: a bed rung that recorded {@code food.strandedAt} would be describing the right cell
     * under the wrong rung's name, and the next reader would go and investigate a hunt that was
     * never the one that stranded it.
     */
    /**
     * How far below daylight is "in a hole" rather than "on uneven ground".
     *
     * <p>Eight, because that is more than the relief this route crosses on the surface and less
     * than the one measured fall: the iron rung's walk home ended 24 blocks under daylight. Set it
     * lower and every hillside triggers a climb the body does not need; set it higher and the next
     * cave that is merely deep enough to strand it goes unnoticed.
     */
    private static final int SUNK_BELOW = 8;

    static void walkHome(JourneyRig rig, String key, Runnable then) {
        BlockPos home = rig.ctx().level().getSharedSpawnPos();
        BlockPos at = rig.player().blockPosition();
        rig.evidence(key + ".huntEndedAt", at.toShortString() + "，离出生点 "
                + Math.round(Math.hypot(at.getX() - home.getX(), at.getZ() - home.getZ())) + " 格");
        rig.attempting("打完猎回出生点，别把下一级留在荒野里");
        Runnable arrived = () -> settleOntoHomeGround(rig, key, home, () -> {
            rig.evidence(key + ".home", rig.player().blockPosition().toShortString());
            then.run();
        });
        Runnable strand = () -> {
            BlockPos stuck = rig.player().blockPosition();
            rig.evidence(key + ".strandedAt", stuck.toShortString() + "，离出生点 "
                    + Math.round(Math.hypot(stuck.getX() - home.getX(), stuck.getZ() - home.getZ()))
                    + " 格 —— 上面的每一级都会从这里出发");
            then.run();
        };
        walkToColumn(rig, "home", home.getX(), home.getZ(), 3, 12_000, arrived,
                () -> {
                    // A HOLE IS NOT A DISTANCE PROBLEM, and the three attempts above treat it as
                    // one. Goal.XZ has no y term, so a cave heading roughly toward home is free
                    // progress to the cost function; measured on the iron rung 2026-08-23, the
                    // first leg ended `home.arrivedY = 40（起 64，净升 -24），脚下=water`, the second
                    // moved zero blocks in any axis, and the rung handed the body to PORTAL_KIT at
                    // y=44 — which then failed 59 blocks short and read as a walking bug.
                    //
                    // So ask the question the retries never ask: is the body UNDER something? The
                    // heightmap answers it without caring where the body stands
                    // (JourneyTerrain#daylightY, whose own javadoc records this failing two rungs
                    // downstream), and JourneyShaft#climbOut is the same routine that had already
                    // succeeded twice in this very run (`exit#2` 4/4, `exit#3` 15/15).
                    //
                    // One climb, then ONE more walk — not the full three. The rung had already
                    // spent 44966 ticks by this point, and a recovery that can double a rung's
                    // budget converts a stranded body into a timed-out one, which is worse: a
                    // timeout loses the rung, stranding only makes the next rung harder.
                    BlockPos stuck = rig.player().blockPosition();
                    int daylight = JourneyTerrain.daylightY(rig, stuck);
                    if (daylight - stuck.getY() < SUNK_BELOW) { strand.run(); return; }
                    rig.evidence(key + ".sunkOnTheWayHome", stuck.toShortString() + "，天光在 "
                            + daylight + "（低 " + (daylight - stuck.getY()) + " 格），脚下="
                            + rig.player().level().getBlockState(stuck.below())
                            + " —— 先垒上去再走");
                    JourneyShaft.climbOut(rig, daylight, key + "Home", () -> {
                        rig.evidence(key + ".climbedOutTo", rig.player().blockPosition().toShortString());
                        walkToColumn(rig, "homeAfterClimb", home.getX(), home.getZ(), 3, 12_000, 1,
                                arrived, strand);
                    });
                });
    }

    /**
     * Leave the next rung a body standing on the ground, not on top of what it built to get here.
     *
     * <p><b>The failure this is written from.</b> The bed rung 2026-08-24 ended
     * {@code home.arrivedY = 78（起 62，净升 16），脚下=Block{minecraft:cobblestone}} beside
     * {@code home.gotoEnd.1 = …预算用完时进程还在走…判为到达：停在 64,78,63，距 64,60 3 格，容差 5}.
     * The rung PASSED: {@link #walkToColumn}'s goal is a column, {@code Goal.XZ} has no y term, and
     * three blocks of horizontal error is inside the tolerance whatever the altitude. So the body
     * was handed to the furnace rung sixteen courses up a cobblestone tower it had pillared itself
     * — and both halves of that cost a rung. The tower <b>was</b> the missing cobblestone
     * ({@code cobblestone.before=7} where the stone rung banks twenty), and standing on it put
     * {@code MineProcess}'s {@code mineSearchVerticalRadius=8} scan band entirely above the terrain,
     * so a top-up that asked for one stone found no candidate at all and aborted in one tick.
     *
     * <p><b>Why the existing guard could not catch it.</b> {@code walkHome} already asks「is the
     * body under something」— but only on the STRAND branch, and only downward. A body can also
     * <i>arrive</i> wrong in either direction, which is the mirror of the one-axis mistake that
     * guard was itself written for.
     *
     * <p><b>Why the home column's heightmap is the right reference and the body's own is not.</b>
     * Once the tower exists it IS terrain, so {@code daylightY} at the body's column happily reports
     * the tower top. The home column is three blocks away and carries no tower, so it still reads
     * natural ground. The same reading also makes the natural-relief case safe rather than merely
     * tolerable: descending to the level of ground three blocks away cannot leave the body in a pit,
     * because that level is what it will walk out onto.
     *
     * <p>Both directions, and the elevation is recorded on every path — a row that appears only when
     * the recovery fires cannot tell「it was level」from「nobody looked」.
     */
    private static void settleOntoHomeGround(JourneyRig rig, String key, BlockPos home, Runnable then) {
        BlockPos at = rig.player().blockPosition();
        int ground = JourneyTerrain.daylightY(rig, home);
        int off = at.getY() - ground;
        rig.evidence(key + ".homeElevation", "身体 y=" + at.getY() + "，出生柱地面 y=" + ground
                + "（差 " + (off >= 0 ? "+" : "") + off + " 格），脚下="
                + rig.player().level().getBlockState(at.below()).getBlock());
        if (Math.abs(off) < SUNK_BELOW) { then.run(); return; }

        // Restore explicitly rather than trusting the scene pin: the pin does restore at scene end,
        // but this rung has rungs' worth of work left after the descent and a paving walker in the
        // middle of it is the pathology being fixed, not a tidy-up detail.
        boolean place = BotConfig.allowPlace;
        if (off > 0) {
            rig.attempting("从自己垒的塔上下来：拆塔的竖井挖不动");
            BotConfig.allowPlace = false;      // a paving walker will not sink — see the stone rung
            JourneyShaft.descendByMining(rig, ground, () -> {
                BotConfig.allowPlace = place;
                // Mining a tower back is not a side effect, it is the point: every course is a
                // block the walker spent, and the rungs above this one are the ones that needed it.
                rig.evidence(key + ".towerRecovered", "落到 y=" + rig.player().blockPosition().getY()
                        + "，圆石 " + rig.carrying("minecraft:cobblestone"));
                walkToColumn(rig, key + "AfterDescent", home.getX(), home.getZ(), 3, 12_000, 1,
                        then, then);
            }, wet -> {
                // Standing part-way down a wet column beats standing on the tower, so this ends the
                // descent and carries on rather than failing the rung: the rung's contract is the
                // bed, and this whole routine is about what the NEXT rung inherits.
                BotConfig.allowPlace = place;
                rig.evidence(key + ".towerDescentWet", "柱子中段有水，停在 " + wet.toShortString()
                        + " —— 不再往下挖，下一级从这里出发");
                then.run();
            });
            return;
        }
        rig.attempting("从坑里爬回地面：climbOut 上不去");
        JourneyShaft.climbOut(rig, ground, key + "HomeUp", () -> {
            rig.evidence(key + ".climbedBackTo", rig.player().blockPosition().toShortString());
            walkToColumn(rig, key + "AfterClimbBack", home.getX(), home.getZ(), 3, 12_000, 1,
                    then, then);
        });
    }

    static final int PREY_SEARCH_BLOCKS = 96;

    /** Chunks pinned while it looks — enough that {@link #PREY_SEARCH_BLOCKS} is a real radius and
     *  not a promise the loaded world cannot keep. 96 blocks is six chunks; one more for the edge. */
    static final int PREY_SEARCH_CHUNKS = 7;

    /** The second look's radius, used only when the first finds nothing. 176 blocks — eleven chunks
     *  — because the measured miss is not marginal: this seed's spawn has no food animal inside 96
     *  at all, and the cow a successful run eats sits 77 blocks from wherever the stone rung ended,
     *  which can be most of a hundred blocks from anywhere the failing run looked. Not the default,
     *  because pinning 23×23 chunks to answer a question 96 blocks usually answers is a cost every
     *  run would pay for the benefit of one. */
    static final int PREY_SEARCH_WIDE = 176;

    static final int PREY_SEARCH_WIDE_CHUNKS = 11;

    /** The raw drops a first kill can plausibly yield — species is the swamp's business, not ours. */
    private static final List<String> RAW_FOODS = List.of(
            "minecraft:beef", "minecraft:porkchop", "minecraft:chicken",
            "minecraft:mutton", "minecraft:rabbit", "minecraft:cod", "minecraft:salmon");

    // =====================================================================================
    // 07 — the bed. Three wool of ONE colour, and the colour is the whole difficulty.
    //      Its own file: see JourneyBedRung.
    // =====================================================================================

    // =====================================================================================
    // 08 — the furnace. Eight of the cobblestone the stone rung banked.
    //      Its own file: see JourneyFurnaceRung.
    // =====================================================================================

    // =====================================================================================
    // The crafting-table tax, and the wood that pays it. NOT a rung — every 3×3 craft on the
    // ladder reaches this through craftKeepingTheTable. It sat under rung 08's banner only
    // because the furnace rung is where the tax was first measured; the furnace itself moved
    // out on 2026-08-25 and this stayed, so the banner now says what the code is.
    // =====================================================================================

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
     * happened, which is exactly the shape {@link JourneyRig#collectByHand} was written for. One walk costs
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
                    () -> takeTableWhereItStands(rig, standing, () -> {
                        rig.evidence("craftingTable.reclaimed",
                                rig.carrying("minecraft:crafting_table"));
                        then.run();
                    }));
            return;
        }

        rig.attempting("找回工作台：上一级把它留在地下了");
        rig.collectByHand("minecraft:crafting_table", 1, () -> {
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
                        String.valueOf(rig.slotError("craft")));
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
        rig.evidence("wood.topUpTarget", trunk == null ? "无（身边这一带没有树干）" : trunk.toShortString());
        if (trunk == null) {
            // THE SCAN CANNOT SUCCEED WHERE THIS IS NEEDED. nearestTrunkBeyond looks around the
            // BODY with dy limited to ±8, and wood runs out precisely when the body has been
            // mining — 2026-08-22 measured it at y=42..54 with two shafts dug and six raw iron in
            // the bag, twenty-odd blocks below any canopy. So the band excludes every tree in the
            // world, the row printed 「无」, and the rung failed with 缺 1 个 oak_log while a forest
            // stood overhead. A remedy that is unreachable exactly when it is required is the same
            // shape as the run being out of wood in the first place.
            //
            // The answer is the one the rest of this ladder already uses: go to the LANDMARK. The
            // route surveyed the first tree, and its column is on the surface by definition.
            rig.evidence("wood.topUpFallback", "改用烘入的第一棵树 "
                    + JourneyRoute.firstTree.toShortString() + " 那一柱（地表），"
                    + "因为身边的扫描带是 ±8 格，而身体在地下");
            walkToTheTreesAndCut(rig, species, then);
            return;
        }
        rig.attempting("木头用光了，去砍一棵补上");
        BlockPos target = trunk;
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(target.getX(), target.getZ(), 2))), 6_000,
                () -> rig.settle(new MineProcess(List.of(logIdAt(rig.ctx(), target)), 6, 24), 8_000,
                        () -> {
                            rig.evidence("wood.toppedUp", totalLogs(rig) + " 根");
                            then.run();
                        }));
    }

    /**
     * Climb back to the surveyed treeline and cut whatever is standing there.
     *
     * <p>Two steps rather than one, and the second is not redundant: the baked coordinate names the
     * tree the WOOD rung already felled, so the block there is usually air by now. What survives is
     * the <i>place</i> — a forest column on the surface — and once the body is standing in it the
     * ordinary ±8 scan is looking at canopy instead of at stone.
     *
     * <p>Goal.XZ rather than Goal.Block for the same reason: the target is the column, not a cell,
     * and demanding the cell would fail on the stump of the run's own first tree.
     */
    private static void walkToTheTreesAndCut(JourneyRig rig,
                                             net.minecraft.world.level.block.Block species,
                                             Runnable then) {
        BlockPos grove = JourneyRoute.firstTree;
        rig.attempting("木头用光了，而身边扫不到树 —— 先回烘入的林地那一柱，再砍");
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(grove.getX(), grove.getZ(), 3))),
                WOOD_RETURN_TICKS, () -> {
            BlockPos here = rig.player().blockPosition();
            rig.evidence("wood.backAtTheGrove", here.toShortString()
                    + "（目标柱 " + grove.getX() + "," + grove.getZ() + "）");
            BlockPos trunk = nearestTrunkBeyond(rig, species, 0, TRUNK_SEARCH);
            rig.evidence("wood.topUpTarget2", trunk == null
                    ? "回到林地也没扫到树干 —— 这一带被砍光了，或者根本没走到"
                    : trunk.toShortString());
            if (trunk == null) { then.run(); return; }
            rig.settle(new MineProcess(List.of(logIdAt(rig.ctx(), trunk)), 6, 24), 8_000, () -> {
                rig.evidence("wood.toppedUp", totalLogs(rig) + " 根");
                then.run();
            });
        });
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
                mineOreHere(ctx, rig, tag, ore, () -> JourneyShaft.climbOut(rig, shaft.getY(), tag + ".exit", then));
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
            rig.evidence(tag + ".mine.lastError", String.valueOf(rig.slotError("mine")));
            // lastError is null on the honest paths too — a mine that swept everything it could
            // reach and a mine that banked the lot both report no error. endReason tells them apart.
            rig.evidence(tag + ".mine.endReason", String.valueOf(rig.slotEnd("mine")));
            // Drops on the ground vs items in the bag. "Nothing was mined", "it was mined and never
            // dropped" and "it dropped and was never collected" all read as raw_iron=0, and they
            // live in three different files.
            rig.evidence(tag + ".raw_iron.onGround", rig.dropsNearby("minecraft:raw_iron", 48));
            rig.collectByHand("minecraft:raw_iron", MAX_PICKUP_LEGS, tag, then);
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
        // LET THE SERVER CATCH UP BEFORE COUNTING. Measured 2026-08-23, ladder-7, two adjacent log
        // lines with nothing between them:
        //
        //   2074 [Render thread] [smelt] COLLECT: … made=6× iron_ingot taken=6
        //   2075 [Server thread] scene 'wd.journey09Iron' -> FAIL — iron ingots smelted (0)
        //
        // The smelt SUCCEEDED. Collecting from a furnace is client-side menu clicking; the server
        // applies it when the packets arrive. `rig.carrying` reads `driver.fakePlayer()` — the
        // SERVER player — so a read in the same tick as the collect sees the inventory from before
        // it. Six ingots, judged as zero, and every rung above BLOCKED behind it.
        //
        // The wait is a tick counter OR the count, never a bare condition: `within` expiring is
        // itself a FAIL in StageWright, so waiting only on「ingots appear」would convert an honest
        // "smelted nothing" into a step timeout and lose the message that names the cause.
        int[] settle = {0};
        rig.await(() -> rig.carrying("minecraft:iron_ingot") >= 1 || ++settle[0] >= 40, 80, () -> {
            int ingots = rig.carrying("minecraft:iron_ingot");
            rig.evidence("iron_ingot", ingots);
            rig.evidence("iron_ingot.serverSettleTicks", settle[0]);
            rig.evidence("furnace.after", rig.carrying("minecraft:furnace"));
            rig.evidence("smelt.lastError", String.valueOf(rig.slotError("smelt")));
            rig.noteAdvancement("minecraft:story/smelt_iron");
            ctx.expect(ingots).as("iron ingots smelted").isAtLeast(1);
            // Hand the body back somewhere the rungs above can navigate from — the rule the food
            // rung learned, and the one this rung never adopted even though the food rung's own
            // note cites THIS rung as the victim: "the iron rung came to report 'cannot reach the
            // descent point' from 4,65,114 — 88 blocks away … That is not the iron rung's failure
            // and it should not be reported as one." This rung sinks up to two shafts and ends
            // wherever the second vein was, which on one measured run was y=40 with the portal
            // rung then failing to walk 72 blocks to its gravel column.
            //
            // Best-effort, exactly like the food rung: a body that smelted its iron has climbed
            // this rung whether or not it found its way home, so a failed return records
            // `iron.strandedAt` rather than failing IRON. **That row is the point.** It is what
            // lets the next rung's failure be traced here instead of investigated on its own terms
            // — and it is worth having even on the runs where the walk succeeds, because then the
            // next rung's failure is provably NOT about where it started.
            walkHome(rig, "iron", () -> rig.reach("铁锭 ×" + ingots + " 出炉"));
        });
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
    static void craftKeepingTheTable(JourneyRig rig, String itemId, int budget, Runnable then) {
        craftKeepingTheTable(rig, itemId, budget, true, then);
    }

    private static void craftKeepingTheTable(JourneyRig rig, String itemId, int budget,
                                             boolean mayFetchWood, Runnable then) {
        String key = itemId.substring(itemId.indexOf(':') + 1);
        ensureCraftingTable(rig, () -> {
            // RESTATE THE NOTE HERE, because `attempting` means "what this rung would be failing for
            // FROM NOW ON" and the leg that set it last has, by the time this line runs, SUCCEEDED.
            // Measured on the furnace rung 2026-08-24: the verdict printed
            // 「FAILED —— 补做工作台：地上也没有，只能再买一张」 over evidence that reads
            // `craftingTable.remade=true, craftingTable=1, craftingTable.keptInBag=1` — the table
            // came back, and the row blamed the one leg that had worked. The terminal cause was
            // three rows further down (`craft.lastError=缺 1 个 cobblestone`), so the run's two
            // accounts of its own failure disagreed and the louder one was wrong.
            //
            // It belongs on THIS line rather than in each rung because `ensureCraftingTable` is the
            // only thing between a rung's own note and its craft, and it sets a note on three of its
            // four branches. Fixing it per-rung would be five copies of one sentence, four of which
            // would go stale the next time this helper grows a branch.
            rig.attempting("合成 " + itemId + "：CraftProcess 走不完");
            rig.drive(new CraftProcess(itemId, 1), budget, () -> {
                String error = String.valueOf(rig.slotError("craft"));
                rig.evidence(key + ".crafted", rig.carrying(itemId));
                rig.evidence(key + ".craftError", error);
                // One retry, and only for the one cause a retry can fix. "缺 … _log" is the recipe
                // resolver saying the bag is short of wood, and the ladder has a tree for that;
                // every other error would repeat identically, which is the mistake walkToColumn
                // already made once. See topUpWood for why the wood BILL cannot be the answer here.
                if (mayFetchWood && rig.carrying(itemId) == 0 && error.contains("_log")) {
                    topUpWood(rig, () -> craftKeepingTheTable(rig, itemId, budget, false, then));
                    return;
                }
                reclaimTableIfLeftStanding(rig, then);
            });
        });
    }

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
                    () -> rig.mineBlock(standing, 600, () -> rig.collectByHand(itemId, 1, () -> {
                        rig.evidence(key + ".reclaimed", rig.carrying(itemId));
                        then.run();
                    })));
            return;
        }
        rig.attempting("找回" + zh + "：上一段把它花掉了");
        rig.collectByHand(itemId, 1, () -> {
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
    // MOVED TO JourneyRig. It sat private here, so rung 14 could not reach it and did not collect at
    // all: seven blazes killed, zero rods banked, `dropsNearby=2 根掉在地上没捡` printed beside the
    // verdict. Two rungs needing the same walk is what the shared rig is for — and dropsNearby and
    // nearestDrop, the pair it completes, were already there.

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
    /** @see JourneyRig#MAX_PICKUP_LEGS */
    private static final int MAX_PICKUP_LEGS = JourneyRig.MAX_PICKUP_LEGS;


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
            rig.evidence("craft.lastError", String.valueOf(rig.slotError("craft")));
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
                        rig.collectByHand("minecraft:flint", 3, () -> {
                    int flint = rig.carrying("minecraft:flint");
                    rig.evidence("flint", flint);
                    rig.evidence("gravel.collected", rig.carrying("minecraft:gravel"));
                    rig.evidence("mine.endReason", String.valueOf(rig.slotEnd("mine")));
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
                        rig.evidence("craft.lastError", String.valueOf(rig.slotError("craft")));
                        ctx.expect(fas).as("flint and steel crafted").isAtLeast(1);
                        JourneyShaft.climbOut(rig, surfaceY, "kit.exit", () ->
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
            JourneyShaft.climbOut(rig, sky, "start.exit", () -> walkToTheLava(ctx, rig, lava));
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
            reachLava(ctx, rig, MAX_TUNNEL_STEPS, () -> JourneyCast.leaveWithTheLava(ctx, rig, surfaceY));
            return;
        }
        sinkInSomeColumn(ctx, rig, lava, surfaceY, new java.util.ArrayList<>(), WET_COLUMN_SWAPS);
    }

    /**
     * How many times this rung may abandon a wet column and sink the shaft somewhere else.
     *
     * <p>Two. The remedy has to be bounded — a swamp can wet every column within eight blocks, and
     * an unbounded loop would spend the rung's whole budget re-digging — and it has to be more than
     * one, because the columns {@code pickDigColumn} rings outward to are neighbours and neighbours
     * share their groundwater. Each swap costs a climb out and a fresh descent, which on this
     * seed's obsidian rung is about 36 blocks each way.
     */
    private static final int WET_COLUMN_SWAPS = 2;

    /**
     * Pick a column, prove it, sink the shaft — and start over somewhere else if the descent drowns.
     *
     * <p>Separate from {@link #sinkToLava} so the retry re-enters HERE. Re-entering at the top would
     * meet the「already at the fluid's level」short-circuit with a body that is at that level only
     * because it drowned part way down the wrong column, and skip the shaft entirely.
     */
    private static void sinkInSomeColumn(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY,
                                         List<BlockPos> wetColumns, int swapsLeft) {
        ServerLevel level = ctx.level();
        Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
        BlockPos dig = JourneyTerrain.pickDigColumn(level, lava, surfaceY, rejected, wetColumns);
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
        stepOntoDiggableColumn(rig, dig, lava, surfaceY, MAX_WALK_ATTEMPTS, wetColumns, () -> {
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
                reachLava(ctx, rig, MAX_TUNNEL_STEPS, () -> JourneyCast.leaveWithTheLava(ctx, rig, surfaceY));
            }, afloat -> swapWetColumn(ctx, rig, lava, surfaceY, wetColumns, swapsLeft, afloat));
        }, () -> ctx.fail("站不到可下挖的柱子上：想去 " + dig.getX() + "," + dig.getZ()
                + "，停在 " + rig.player().blockPosition()
                + "（该柱在岩浆层不是实心, 或柱子里还有岩浆）"));
    }

    /**
     * Abandon a column the descent drowned in, and sink the shaft in a different one.
     *
     * <p>This is the code that makes the descent's「这根柱子不干燥，换一根」true. It printed that
     * sentence and called {@code ctx.fail} for as long as it existed, which is the worst kind of
     * evidence row: one that names a remedy nothing performs. Two ladder runs ended on it.
     *
     * <p><b>Climb out first.</b> The body is floating in a hole it dug, and the next column is a
     * surface walk away — the same {@code climbOut} every mining rung already uses to leave a shaft,
     * which turns placing back on for the pillar. Recorded as {@code shaft.reColumn.N} with the cell
     * that drowned, so a run that swapped can never read as one that walked straight down.
     */
    private static void swapWetColumn(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY,
                                      List<BlockPos> wetColumns, int swapsLeft, BlockPos afloat) {
        int n = wetColumns.size() + 1;
        wetColumns.add(new BlockPos(afloat.getX(), lava.getY(), afloat.getZ()));
        // The two outcomes must not read alike. A row that says「换一根」when nothing will change
        // columns is the very defect this whole callback exists to remove, and writing the sentence
        // once with a suffix is how that happens by accident.
        rig.evidence("shaft.reColumn." + n, afloat.toShortString() + " 这一柱中段有水，身体浮起来了"
                + "（脚下 " + rig.ctx().level().getBlockState(afloat.below()).getBlock() + "）—— "
                + (swapsLeft <= 0
                        ? "换柱次数已用完，不再换，这一级到此为止"
                        : "爬回 y=" + surfaceY + " 换第 " + (n + 1) + " 根柱子重挖，还剩 "
                                + (swapsLeft - 1) + " 次换柱"));
        if (swapsLeft <= 0) {
            ctx.fail("竖井连着 " + n + " 根柱子都在中段见水：已换掉 " + wetColumns
                    + "（岩浆 " + lava.toShortString() + "，地表 y=" + surfaceY
                    + "）—— 这一片是含水层，不是一根柱子的运气");
            return;
        }
        rig.attempting("这一柱中段有水，爬回地面换一根重挖");
        JourneyShaft.climbOut(rig, surfaceY, "shaft.reColumn" + n + ".exit", () ->
                sinkInSomeColumn(ctx, rig, lava, surfaceY, wetColumns, swapsLeft - 1));
    }

    /**
     * Get the body onto a column a shaft may actually be sunk in, and prove it before digging.
     *
     * <p>The proof is done against the column the body is standing on, not the one it was sent to.
     * Those differ often enough — a walker stops where it can stand — and here the difference is the
     * whole risk: any column is fine as long as it has been checked and is not the pool's own.
     */
    static void stepOntoDiggableColumn(JourneyRig rig, BlockPos dig, BlockPos lava,
                                               int surfaceY, int left, Runnable then, Runnable onStuck) {
        stepOntoDiggableColumn(rig, dig, lava, surfaceY, left, List.of(), then, onStuck);
    }

    /**
     * The same, refusing to adopt a column an earlier descent drowned in.
     *
     * <p>Without the ban list this method is what turns a column swap into a loop, and quietly:
     * {@code columnIsSafeToSink} asks {@code whyNotDiggable}, which by design does not look at the
     * MIDDLE of a column — so the wet column the descent just abandoned still answers "fine", and a
     * body that climbed out of it and has not walked far enough yet gets sent straight back down it.
     */
    static void stepOntoDiggableColumn(JourneyRig rig, BlockPos dig, BlockPos lava,
                                               int surfaceY, int left, List<BlockPos> banned,
                                               Runnable then, Runnable onStuck) {
        stepOntoDiggableColumn(rig, dig, lava, surfaceY, left, banned, null, then, onStuck);
    }

    /**
     * @param lastFrom where the previous attempt's walk STARTED, or null for the first one — see the
     *        wedge branch below for why an attempt has to know that.
     */
    private static void stepOntoDiggableColumn(JourneyRig rig, BlockPos dig, BlockPos lava,
                                               int surfaceY, int left, List<BlockPos> banned,
                                               BlockPos lastFrom,
                                               Runnable then, Runnable onStuck) {
        BlockPos at = rig.player().blockPosition();
        boolean overThePool = at.getX() == lava.getX() && at.getZ() == lava.getZ();
        boolean abandoned = JourneyTerrain.sameColumn(banned, at);
        // ADOPTING WHERE YOU STAND IS WHAT DECIDES THE MOULD, and that is why staging a side had to
        // reach this line too. `pickDigColumn` proposes, but this short-circuit adopts the body's own
        // column whenever it qualifies — so on 2026-08-16 a rehearsal that asked for an east column
        // got one, never walked to it, and reported `shaft.standingOn = -8,17 (就近合格柱)` with the
        // mould facing north. Null on every climb (see JourneyRehearsal#stagedForgeSide), so a real
        // ladder still adopts exactly as before; a rehearsal that named a side refuses to adopt off it
        // and walks to the column that was chosen for that side.
        Direction wantSide = JourneyRehearsal.stagedForgeSide;
        BlockPos wantCol = JourneyRehearsal.stagedShaftColumn;
        boolean offTheStagedSide = wantCol != null
                ? at.getX() != wantCol.getX() || at.getZ() != wantCol.getZ()
                : wantSide != null && JourneyPortalRung.awayFrom(lava, at) != wantSide;
        if (!overThePool && !abandoned && !offTheStagedSide
                && JourneyTerrain.columnIsSafeToSink(rig.ctx().level(),
                new BlockPos(at.getX(), lava.getY(), at.getZ()), surfaceY)) {
            rig.evidence("shaft.standingOn", at.getX() + "," + at.getZ()
                    + (at.getX() == dig.getX() && at.getZ() == dig.getZ() ? " (选定柱)" : " (就近合格柱)"));
            then.run();
            return;
        }
        if (left <= 0) { onStuck.run(); return; }
        int attempt = MAX_WALK_ATTEMPTS - left + 1;
        rig.evidence("shaft.stepping." + attempt,
                at.toShortString() + " → " + dig.getX() + "," + dig.getZ()
                        + (overThePool ? " (正站在岩浆柱上)"
                                : abandoned ? " (正站在刚换掉的湿柱上)"
                                : offTheStagedSide
                                        ? wantCol != null
                                                ? " (排练把井柱钉在 " + wantCol.getX() + ","
                                                  + wantCol.getZ() + "，脚下这一柱不是它)"
                                                : " (排练指定了 " + wantSide + " 侧，脚下这一柱不在那一侧)"
                                : " (脚下柱子不合格)"));
        // A LEG THAT MOVED NOTHING MUST NOT BE ASKED AGAIN FROM THE SAME CELL. This method had no
        // wedge handling at all — `walkToColumn` has carried some since the iron rung issued ninety
        // searches from one cell — and the cost was measured on the PORTAL_LIT rehearsal of
        // 2026-08-17: three legs, three `shaft.stepping.N` rows all reading `-13, 66, 21 → -8,19`,
        // 3 600 ticks, and about 110 `[pathfinder] search-begin owner=goto start=-13, 66, 21` lines.
        // The searches SUCCEEDED (1.4 s apart, the cadence of `guardPinStreak >= 30 → path = null`);
        // what the body could not do was walk, because `[walker] footing guard: sole 0.0000 < 0.18 at
        // -13,66,21 beside a lethal drop → sneak-pin` had it held on the lava crater's lip, and
        // vanilla's sneak refuses every horizontal move that keeps a body off its floor.
        //
        // So the remedy is not more attempts — it is to ask from somewhere else, and the direction
        // matters. `walkToColumn`'s answer is the MIDPOINT, which is wrong here: the midpoint of a
        // body on the crater's lip and a column on the far rim is the pool. Away from the pool is
        // where the footing is, and stepping back from an edge before walking around it is what a
        // player does. Nothing is relaxed by this: the column asked for does not change.
        if (lastFrom != null
                && Math.hypot(at.getX() - lastFrom.getX(), at.getZ() - lastFrom.getZ()) < WEDGED_UNDER) {
            int sx = Integer.signum(at.getX() - lava.getX());
            int sz = Integer.signum(at.getZ() - lava.getZ());
            if (sx == 0 && sz == 0) sx = 1;      // standing on the pool's own column: any way out
            int rx = at.getX() + BACK_OFF_FROM_POOL * sx;
            int rz = at.getZ() + BACK_OFF_FROM_POOL * sz;
            rig.evidence("shaft.wedged." + attempt, at.toShortString()
                    + " 这一腿一格没挪（上一腿从 " + lastFrom.toShortString()
                    + " 起）—— 再问一次是同一个问题；先退到 " + rx + "," + rz + "（背对岩浆）站稳再问");
            rig.settle(new IntentProcess(new Intent(new Goal.XZ(rx, rz, 1))), 600, () -> {
                BlockPos back = rig.player().blockPosition();
                rig.evidence("shaft.backOff." + attempt, back.toShortString()
                        + (Math.hypot(back.getX() - rx, back.getZ() - rz) <= 1
                                ? "（退到了，从这里重问）"
                                : "（想退到 " + rx + "," + rz + "，只退到这里）"));
                walkAtTheColumn(rig, dig, lava, surfaceY, left, banned, then, onStuck);
            });
            return;
        }
        walkAtTheColumn(rig, dig, lava, surfaceY, left, banned, then, onStuck);
    }

    /** How far back from the pool a wedged approach retreats before asking again. Four blocks: the
     *  crater's lip is the cell the footing guard pins on and its neighbours, so anything shorter
     *  re-asks from inside the same hazard ring; anything longer walks back over ground the leg has
     *  to cross again anyway. */
    private static final int BACK_OFF_FROM_POOL = 4;

    /** One approach leg, recording where it started so the next one can tell a wedge from a walk. */
    private static void walkAtTheColumn(JourneyRig rig, BlockPos dig, BlockPos lava, int surfaceY,
                                        int left, List<BlockPos> banned,
                                        Runnable then, Runnable onStuck) {
        BlockPos from = rig.player().blockPosition();
        rig.settle(new IntentProcess(new Intent(new Goal.XZ(dig.getX(), dig.getZ(), 0))), 1_200,
                () -> stepOntoDiggableColumn(rig, dig, lava, surfaceY, left - 1, banned, from,
                        then, onStuck));
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
    static final double TUNNEL_REACH = 5.0;

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
                JourneyShaft.ascendByTowering(rig, pool.getY() + 1, JourneyShaft.climbCoursesFor(below),
                        JourneyShaft.climbCoursesFor(below), "tunnel.fell", () -> {
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
        // Aim INSIDE the settle, adjacent to the ray — see JourneyHands.aimThenAct for why the old
        // order (aim, settle two ticks, then read) could not survive on a client-authoritative body.
        JourneyHands.aimThenAct(rig, src, () -> {
            var fp = rig.player();
            // partialTicks = 1.0F, and it is the difference between a working tunnel and a rung that
            // could not see the pool it was standing next to. Entity.pick INTERPOLATES: 0.0F traces
            // from the PREVIOUS tick's position, and a body that has walked 80 blocks and dropped 36
            // since then rays out of somewhere it used to be. Measured — the body at -4,27,57 aimed
            // at the lava and the pick answered `57,64,56 air`, a surface cell sixty blocks off,
            // through a five-block ray. 1.0F is the position it is actually at, and it is what
            // vanilla's own Item.getPlayerPOVHitResult uses, so it is also what the pour will see.
            var hit = JourneyHands.aimedAt(fp, TUNNEL_REACH, true);
            int step = MAX_TUNNEL_STEPS - left;
            BlockPos blocking = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    ? hit.getBlockPos() : null;
            // The body's own cell is on the row, and it is not decoration. Without it the only way
            // to know where the ray started is to invert the pitch — and doing that by hand on the
            // rehearsal above produced three mutually inconsistent answers, because the body had
            // been falling. A step whose aim looks wrong and a step whose aim is right about a body
            // somewhere unexpected read identically, and they want opposite fixes.
            rig.evidence("tunnel." + step, String.format(java.util.Locale.ROOT,
                    "aim %s (%.0f/%.0f, %.1fm) 自 %s → %s", src.toShortString(),
                    fp.getYRot(), fp.getXRot(),
                    fp.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(src)),
                    fp.blockPosition().toShortString(),
                    blocking == null ? String.valueOf(hit.getType())
                        : blocking.toShortString() + " " + level.getBlockState(blocking).getBlock()));
            if (blocking != null && level.getFluidState(blocking).isSource()
                    && level.getBlockState(blocking).getBlock() == Blocks.LAVA) {
                // SEEING it is not REACHING it. This sighting ray runs to TUNNEL_REACH (5.0) because
                // that is the DIGGING figure — `destroyBlock` has no reach gate at all, so a tunnel
                // can carve as far as it can see. The bucket cannot: `Item.getPlayerPOVHitResult`
                // traces `blockInteractionRange()`, which {@link JourneyFill#BUCKET_REACH} already
                // records as 4.5, in a javadoc that says this exact thing about this exact constant.
                // ladder-13 stopped the tunnel the instant the source came into view and scooped from
                // where it stood: `fill.result=PASS`, `lava_bucket=0`, source untouched — vanilla's
                // silent miss return, which is byte-identical to a bucket that was never aimed.
                // RE-CLIP rather than compare distances: the metre figure on the tunnel row is to
                // the cell CENTRE while a ray stops at its near FACE (5.4 to the centre of a cell
                // whose face a 5.0 ray reached), so no threshold over that number asks the engine's
                // question. Asking it with the engine's own range is the only version that cannot
                // disagree with what fires.
                var canScoop = JourneyHands.aimedAt(fp, JourneyFill.BUCKET_REACH, true);
                boolean reaches = canScoop.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && canScoop.getBlockPos().equals(blocking);
                if (reaches) {
                    fillFrom(ctx, rig, blocking, then);
                    return;
                }
                rig.evidence("tunnel." + step + ".seenNotReached", String.format(java.util.Locale.ROOT,
                        "看见了 %s，但以桶自己的 %.1f 格再射一次%s —— 先走近，不舀。"
                        + "挖掘用 %.1f 格是因为 destroyBlock 根本没有距离闸，桶有",
                        blocking.toShortString(), JourneyFill.BUCKET_REACH,
                        canScoop.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                                ? "打到的是 " + canScoop.getBlockPos().toShortString()
                                : "什么都没打到", TUNNEL_REACH));
                if (left > 0) {
                    rig.settle(new IntentProcess(new Intent(new Goal.Near(blocking, 2))), 600,
                            () -> reachLava(ctx, rig, left - 1, climbBacks, then));
                    return;
                }
                ctx.fail("看得见岩浆源 " + blocking.toShortString() + " 却够不着它，"
                        + "而且走近的步数也用完了：身体在 " + fp.blockPosition()
                        + "。这不是「挖不到」，是隧道停在了看得见的那一步而不是够得着的那一步");
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

    /** Fill the bucket from a source the body can already see. */
    private static void fillFrom(SceneContext ctx, JourneyRig rig, BlockPos src, Runnable then) {
        rig.attempting("从 " + src.toShortString() + " 装一桶岩浆");
        JourneyHands.holdForUse(rig, Items.BUCKET, "fill");
        JourneyHands.aimThenAct(rig, src, () -> {
            // The same key again, on purpose. holdForUse told the CLIENT to select the bucket and
            // then read the SERVER's hand in the same breath, so its row is one packet early — the
            // rehearsal printed `fill.hand=minecraft:stone_pickaxe` beside `fill.result=SUCCESS`,
            // i.e. the row named a hand that was already stale when it was written. Writing the key
            // a second time after the settle turns StageWright's clash guard into the instrument:
            // it keeps a second value ONLY when it differs, so `fill.hand#2` appearing IS the proof
            // that the first reading was stale, and its absence is the proof that it was not.
            rig.evidence("fill.hand", String.valueOf(BuiltInRegistries.ITEM.getKey(
                    rig.player().getMainHandItem().getItem())));
            rig.evidence("fill.aim", String.format(java.util.Locale.ROOT, "%.0f/%.0f",
                    rig.player().getYRot(), rig.player().getXRot()));
            // BOTH BODIES AND BOTH RAYS, at the instant of the use — the third and last bucket site
            // to get this row. `fill.result=PASS` is vanilla's return for a ray that hit nothing,
            // and it is byte-identical to「the hand was wrong」and to「the source refused」; ladder-13
            // spent a whole run's evidence being inverted by hand to decide which. describeRay
            // prints MISS in words, with the range it traced, so the next one says it outright.
            JourneyHands.handsAtUse(rig, "fill");
            rig.evidence("fill.result", String.valueOf(rig.avatar().useItemInHand()));
            // WAIT before judging, and that is the mirror image of aimThenAct rather than a
            // contradiction of it. The aim must be written to the body that ACTS, with nothing
            // between; the OUTCOME is written by that same client body and has to travel back to
            // the server before `rig.carrying` — which reads the ServerPlayer's inventory — and
            // `ctx.level()` can see it. Judged in the use's own tick, a fill that worked reads
            // exactly like a fill vanilla refused: `lava_bucket=0`, source unchanged.
            rig.settle(new HoldStill(3), 12, () -> {
                int filled = rig.carrying("minecraft:lava_bucket");
                rig.evidence("lava_bucket", filled);
                // Where the source went is the other half of the reading: a fill that worked empties
                // the cell, and a use vanilla refused leaves it exactly as it was.
                rig.evidence("fill.sourceAfter",
                        String.valueOf(ctx.level().getBlockState(src).getBlock()));
                ctx.expect(filled)
                        .as("lava bucket filled from a source (see fill.result / fill.sourceAfter)")
                        .isAtLeast(1);
                then.run();
            });
        });
    }

    // =====================================================================================
    // NETHER — walk into the portal the rung below lit, and come out somewhere else.
    //
    // The whole rung lives in JourneyPortalEntry: the doorway geometry it needs is worth testing
    // without a forty-minute playthrough, and this file is at its source budget.
    // =====================================================================================

    private static void nether(SceneContext ctx) { JourneyPortalEntry.nether(ctx); }

    /** Stop a stage that needs surveyed coordinates before anyone has surveyed them. */
    static boolean requireSurvey(SceneContext ctx, JourneyRig rig) {
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
    /**
     * What a green ladder does NOT claim.
     *
     * <p>StageWright pins the world for every suite it runs — {@code WorldPin} freezes the clock at
     * midnight and turns off {@code doDaylightCycle}, {@code doWeatherCycle} and
     * {@code doMobSpawning}. Those pins were chosen so that ARENA scenes stop being decided by tick
     * alignment, and for a scene that resolves in one server tick they cost nothing. This ladder is
     * the one family they are wrong for: it runs for hours, and「全程零布景」describes the fixtures,
     * not the difficulty.
     *
     * <p>Un-pinning here would change what every other rung measures mid-climb, and the honest
     * version — a topology that runs the same ladder in a live world — is a separate run, not a
     * flag. A reader who takes DRAGON off this row and calls it "beat the game" has been misled by
     * omission, and the omission is ours.
     *
     * <p>⚠️ <b>This used to be a hardcoded constant, and its last clause was false.</b> It said the
     * third pin means「所以这一趟全程没有敌对生物」, and the run of 2026-08-22 killed the body with a
     * witch on rung 7 while printing that sentence in the same results file. The row now comes from
     * {@link JourneyPeace#worldPinReading}, which reads the rules that are actually set and names
     * the channel they do not cover; the removal it points at is done at SPAWN. A row that states a
     * rule must state its exception, or it is not a reading — it is a belief.
     */

    private static void verdict(SceneContext ctx) {
        try {
            JourneyStage height = JourneyLedger.height();
            List<String> staging = JourneyLedger.stagingCalls();

            ctx.record("journey.worldPin", JourneyPeace.worldPinReading(ctx));
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
                    + "，布景调用 " + staging.size() + " 次"
                    + "；" + JourneyPeace.worldPinReading(ctx));
        } finally {
            JourneyRig.teardown();
        }
    }
}
