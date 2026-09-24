package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * PORTAL_LIT — ten obsidian in a frame, at the lava's own level, then a flint-and-steel.
 *
 * <p>Moved out of {@code WorldDriverJourneyScenes} the same mechanical way {@link JourneyShaft} and
 * {@link JourneyTerrain} were, and for the same reason: the ladder file is over its source budget
 * and this is the one rung large enough to carry its own file. Nothing changed in the move. The
 * scene is still registered by the ladder, still runs on {@link JourneyRig}, and still borrows the
 * ladder's walking and aiming helpers — which is why a handful of those are package-private now
 * rather than private.
 *
 * <p>{@link JourneyForge} holds the mould's geometry and the rules about where it may go; this
 * holds the bot's half of it — hollow an alcove under the lake, then make ten round trips between
 * a pool twelve blocks up and a cell that is cut open one at a time.
 *
 * <p>The route those trips are walked on is no longer here: {@link JourneyStairwell} cuts the
 * staircase and walks it, which is what split this file when it in turn reached the budget. It said
 * "sink a shaft" until then, and the shaft had been gone for a dozen runs — see
 * {@code JourneyStairwell.digStairsDown} for the four bugs that replaced it with a flight.
 */
public final class JourneyPortalRung {

    private JourneyPortalRung() {}


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
    static void portalLit(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.PORTAL_LIT);
        if (WorldDriverJourneyScenes.requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();

        // The LAKE, not the fill point. firstLava is the one the OBSIDIAN rung empties into its
        // bucket, and a bucket takes the source block itself — five runs died here reporting zero
        // sources at a landmark that had genuinely ceased to exist. Falls back only so that an old
        // baked route still runs and still says which landmark it used.
        boolean haveLake = !JourneyRoute.lavaLake.equals(JourneyRoute.UNSURVEYED);
        BlockPos lava = haveLake ? JourneyRoute.lavaLake : JourneyRoute.firstLava;
        rig.evidence("lava.landmark", (haveLake
                ? "lavaLake " + lava.toShortString() + " (survey found " + JourneyRoute.lavaLakeSources
                  + " source blocks)"
                : "fell back to firstLava " + lava.toShortString()
                  + " — the survey found no lava lake with at least ten source blocks"));
        rig.evidence("bucket.before", rig.carrying("minecraft:bucket"));
        rig.evidence("flintAndSteel.before", rig.carrying("minecraft:flint_and_steel"));
        rig.evidence("cobblestone.before", rig.carrying("minecraft:cobblestone"));
        if (rig.carrying("minecraft:flint_and_steel") < 1) {
            ctx.fail("No flint and steel: PORTAL_KIT should have left one (currently 0)");
            return;
        }
        if (rig.carrying("minecraft:bucket") < 1 && rig.carrying("minecraft:water_bucket") < 1) {
            ctx.fail("No bucket: OBSIDIAN should have brought the empty bucket back after use"
                    + " (bucket=0, water_bucket=0, lava_bucket="
                    + rig.carrying("minecraft:lava_bucket") + ")");
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
            rig.evidence("water.alreadyCarried", "yes");
            then.run();
            return;
        }
        BlockPos water = JourneyTerrain.shallowWaterNear(rig, 24);
        if (water == null) {
            BlockPos w = JourneyRoute.firstWater;
            rig.attempting("No water nearby; walking to the surveyed water to fill the bucket");
            WorldDriverJourneyScenes.walkToColumn(rig, "water", w.getX(), w.getZ(), 0, 16_000,
                    () -> JourneyFill.scoopWater(ctx, rig,
                            JourneyTerrain.shallowWaterNear(rig, 12), then),
                    () -> ctx.fail("Could not reach firstWater " + w.toShortString()
                            + ": stopped at " + rig.player().blockPosition()));
            return;
        }
        JourneyFill.scoopWater(ctx, rig, water, then);
    }


    /**
     * The floor the mould is carved on — deliberately NOT the lava's own level.
     *
     * <p>The frame is five cells tall plus a row of cap notches, and the technique is to carve that
     * shape out of solid rock so every cell has a back for the buckets to aim at. Laid at the lava's
     * level that holds underground and fails at a surface lake: run 10 walked to this seed's only
     * usable lake, at <b>y=63</b>, cast the first two cells and died on the third with
     * a report that it wanted to place at -9,65,23 and found air there — the upper rows were open
 * sky, so the water ran off.
     *
     * <p>Seven below the lava puts all twelve cells in rock whatever the lake's depth, and costs a
     * seven-block climb per fill against the thirty-six the OBSIDIAN rung already climbs carrying
     * lava — well inside proven ground.
     */
    private static int forgeFloorY(BlockPos lava) {
        return lava.getY() - JourneyForge.BELOW_LAVA;
    }

    /** Every cell the alcove was hollowed out of — the space the bot walks in, and nothing else.
     *  {@link #clearPourLine} is allowed to break inside this and nowhere else, which is what stops
     *  a blocked pour from answering by digging a hole in the mould's own floor. */
    static Set<BlockPos> forgeCorridor = Set.of();

    /** The corridor cells the carve could not open. Not a failure list — a BASELINE: it is what
     *  makes "solid in the corridor" mean "something put it there" for every later reading. See
     *  {@link #tidyTheAlcove}. */
    private static Set<BlockPos> forgeStuck = Set.of();

    /**
     * Which way to run from the lava — one axis, never a diagonal.
     *
     * <p>Shared by the staircase and the mould so they cannot disagree. The staircase runs AWAY from
     * the pool and the mould's face is cut on the same side, which is what keeps the two of them from
     * meeting: the alcove sits at the foot of the last step, and every step above it is both higher
     * and further back.
     *
     * <p>Package-visible so a rehearsal can pick a standing spot BY the answer this returns, rather
     * than re-deriving it. A second copy of this rule is a second thing to keep in step, and the
     * whole point of the orientation parameter is that the staged side and the carved side agree.
     */
    static Direction awayFrom(BlockPos lava, BlockPos at) {
        int dx = Integer.signum(at.getX() - lava.getX());
        int dz = Integer.signum(at.getZ() - lava.getZ());
        return Math.abs(at.getX() - lava.getX()) >= Math.abs(at.getZ() - lava.getZ())
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /**
     * Where the opening walk aims — the bank, not the pool.
     *
     * <p>This walk used to be handed {@code XZ(lava.x, lava.z)}, the lake's own centre column, and
     * <b>it has never once arrived</b>: every archived rehearsal that carries the row reads
     * {@code lava.gotoEnd.1 = end=failed:…}, six of six, and {@code ARRIVED_WITHIN} passed each of
     * them off as an arrival because the wreck was inside five blocks of the goal. The two shapes
     * the wreck takes are this rung's two upstream deaths:
     *
     * <pre>
     * east FAIL 10608t  end=failed:no progress for 1200 ticks   stopped at -13, 66, 21   ← pinned on the rim
     * east FAIL 206t    end=failed:no path (expanded=1)         stopped at -12, 63, 20   ← in the pool
     * </pre>
     *
     * <p>The first is the crater's lip: {@code footing guard: sole 0.0000 … beside a lethal drop}
     * pins the bot in a sneak and vanilla then shrinks every horizontal move to nothing, so the three
     * walks of {@code stepOntoDiggableColumn} that follow are three identical questions from one
     * cell. The second is worse and needs no guard to explain it — {@code expanded=1} is a start
     * node the pathfinder judges lethal, at the lava's own row, so nothing downstream can plan at
     * all: that run died 206 ticks in with the back-off itself unable to move
     * ({@code shaft.backOff.2} recorded -12, 63, 20: it tried to back off to -16,24 and got no
     * further than that).
     *
     * <p>Both are the same mistake, and it is not a tolerance: <b>the destination was a cell no player
     * can occupy</b>, so where the walk ended was decided by how the walker gave up. Naming a bank
     * cell instead makes the landing a choice, and {@link JourneyTerrain#bankStandNear} makes it
     * with the same lip rule the loading station is already chosen by.
     *
     * <p>A preference and not a rule — a lake with no clear bank falls back to the loose scan and
     * then to the old destination, so this cannot leave the rung with less than it has today. The
     * evidence row says which of the three answered, because a run that walked to a chosen bank and
     * a run that walked at the pool must never read alike.
     */
    private static BlockPos pinTheApproach(SceneContext ctx, JourneyRig rig, BlockPos lava) {
        ServerLevel level = ctx.level();
        BlockPos from = rig.player().blockPosition();
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        BlockPos bank = JourneyTerrain.bankStandNear(level, lava, from, why, true);
        String how = "no opening towards the lava within one step of the feet (strict criterion)";
        if (bank == null) {
            Map<String, Integer> loose = new java.util.LinkedHashMap<>();
            bank = JourneyTerrain.bankStandNear(level, lava, from, loose, false);
            if (bank != null) {
                BlockPos over = JourneyTerrain.onThePoolsLip(level, bank);
                if (over == null) over = JourneyTerrain.onThePoolsLip(level, bank.above());
                how = "the strict criterion accepted no cell, so the loose criterion was used — this"
                        + " cell is on the pool's rim (one step away, " + over + " is lava); the cells"
                        + " the strict criterion rejected are counted under 'opening towards the lava"
                        + " beside the feet'";
            }
            why.putAll(loose);
        }
        rig.evidence("lava.bank", bank == null
                ? "no standable dry ground within " + JourneyTerrain.BANK_REACH
                  + " blocks of the lake — falling back to the lava column itself "
                  + lava.getX() + "," + lava.getZ() + " (the very route that walks the bot into the"
                  + " lake); rejection counts " + why
                : bank.toShortString() + ": "
                  + Math.round(Math.hypot(bank.getX() - lava.getX(), bank.getZ() - lava.getZ()))
                  + " blocks from the lava column, "
                  + Math.round(Math.hypot(bank.getX() - from.getX(), bank.getZ() - from.getZ()))
                  + " blocks from the bot; " + how + "; rejection counts " + why);
        return bank == null ? lava : bank;
    }

    /** Walk to the surveyed lava and sink to its level, reusing OBSIDIAN's own descent. */
    private static void descendToTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava) {
        rig.attempting("Carrying a water bucket to a standable cell beside the lava lake, then"
                + " digging a staircase down to the lava's level");
        BlockPos bank = pinTheApproach(ctx, rig, lava);
        JourneyStairwell.lavaPool = lava;
        // THE ROUTE, not only its end. See JourneyTerrain#poolsLipCells: a chosen bank cell did not
        // stop the walker planning along the rim and pinning the bot on it, because a destination
        // cannot steer a path. The set is built on the server thread; the search's own thread only
        // ever does a hash lookup against an immutable set.
        JourneyTerrain.RimTax tax = JourneyTerrain.avoidTheRim(ctx.level(), lava);
        rig.evidence("lava.rimTax", tax.story()
                + "; this walk, its midpoint walk, and every later staircase flight and every walk to"
                + " the loading point all carry this cost surcharge");
        WorldDriverJourneyScenes.walkToColumn(rig, "lava", bank.getX(), bank.getZ(), 0, 24_000,
                tax.bias(), () -> {
            BlockPos at = rig.player().blockPosition();
            final int surfaceY = JourneyTerrain.daylightY(rig, at);
            rig.evidence("forge.surfaceY", surfaceY + " (feet at y=" + at.getY() + ")");
            if (at.getY() <= forgeFloorY(lava) + 1) { carveTheForge(ctx, rig, lava, surfaceY); return; }
            ServerLevel level = ctx.level();
            Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
            // The rehearsal's staged column/side, both null on every climb — see
            // JourneyRehearsal#stagedShaftColumn. The mould's orientation is decided HERE and nowhere
            // earlier, which is why staging the bot's stand never turned it. A pinned column bypasses
            // the search outright, because the column a ladder actually used can be one this search
            // cannot reach: it rings outward from r=2 and the climb of 2026-08-16 used r=1.
            BlockPos pinned = JourneyRehearsal.stagedShaftColumn;
            BlockPos dig = pinned != null
                    ? new BlockPos(pinned.getX(), lava.getY(), pinned.getZ())
                    : JourneyTerrain.pickDigColumn(level, lava, surfaceY, rejected, List.of(),
                            JourneyRehearsal.stagedForgeSide);
            if (dig == null) {
                ctx.fail("No column around the lava column can be dug down (target "
                        + lava.toShortString() + ", surface y=" + surfaceY
                        + ") — rejection counts by reason: " + rejected);
                return;
            }
            WorldDriverJourneyScenes.stepOntoDiggableColumn(rig, dig, lava, surfaceY,
                    WorldDriverJourneyScenes.MAX_WALK_ATTEMPTS, () -> {
                BotConfig.allowPlace = false;
                BlockPos start = rig.player().blockPosition();
                JourneyStairwell.stairTop = start;
                JourneyStairs.reset(level, start);
                JourneyStairwell.stairWaits = 0;
                JourneyStairwell.stairWaitedAt = null;
                JourneyStairwell.stairDir = awayFrom(lava, start);
                int depth = Math.max(0, start.getY() - forgeFloorY(lava));
                int cap = depth * JourneyStairwell.STAIR_ATTEMPTS_PER_BLOCK + 40;
                rig.evidence("stairs.top", start.toShortString() + " heading "
                        + JourneyStairwell.stairDir + ", down "
                        + depth + " steps to y=" + forgeFloorY(lava) + " (" + cap + " attempts allowed)");
                JourneyStairwell.digStairsDown(ctx, rig, forgeFloorY(lava), cap, cap, () -> {
                    rig.evidence("forge.landedY", rig.player().blockPosition().getY());
                    // The baseline the twenty later audits are read against. A flight that is
                    // already faulty the moment it is cut is a digging bug; one that goes faulty
                    // later is a ferrying bug, and without this line the two read alike.
                    rig.evidence("stairs.asCut", JourneyStairs.report(ctx.level()));
                    carveTheForge(ctx, rig, lava, surfaceY);
                });
            }, () -> ctx.fail("Could not stand on a diggable column: wanted " + dig.getX() + ","
                    + dig.getZ() + ", stopped at " + rig.player().blockPosition()
                    // Deliberately asserts NOTHING about why. Its twin in WorldDriverJourneyScenes
                    // used to guess that the column was not solid at the lava's level, and was wrong
                    // on ladder-15; the four values that actually decide it are on shaft.stepStuck,
                    // and what each walk did is on shaft.stepEnd.*.
                    + " (for the cause, see the four values in shaft.stepStuck and the end of each"
                    + " walk in shaft.stepEnd.*)"));
        }, () -> ctx.fail("Could not reach the lava lake's bank: target " + bank.getX() + ","
                + bank.getZ() + " (lava column " + lava.getX() + "," + lava.getZ()
                + "; lava.bank records how it was chosen), stopped at "
                + rig.player().blockPosition()));
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
     * <p>The face is put on the side of the bot AWAY from the pool, so that nothing carved opens
     * into lava — the one mistake down here that ends the run rather than costing it a retry.
     */
    private static void carveTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY) {
        carveTheForge(ctx, rig, lava, surfaceY, FORGE_DEEPENINGS);
    }

    private static void carveTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY,
                                      int deepenings) {
        BlockPos at = rig.player().blockPosition();
        // The staircase's own direction, not a fresh guess. They are the same axis by construction —
        // the stairs ran away from the pool and the bot is standing at their foot — but saying so
        // once removes the case where a bot that stopped a cell short computes the OTHER axis and
        // carves the mould back across its own way home.
        Direction away = JourneyStairwell.stairDir;
        rig.evidence("forge.away", away + " (the staircase's direction; seen from the lava, this"
                + " spot lies " + awayFrom(lava, at) + ")");
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
                        "y=" + at.getY() + " → " + deeper + ": " + bad);
                rig.attempting("The mould's shell is not dry; extending the staircase "
                        + FORGE_DEEPEN_BY + " steps further down");
                // Deepened as MORE STAIRCASE, not as a shaft. Sinking straight down here was the old
                // behaviour and it severed the route the moment it was used: the stairs ended at
                // y=56 and the alcove at y=51, with nothing walkable between them, which is exactly
                // the disconnection this whole redesign exists to remove.
                BotConfig.allowPlace = false;
                int cap = FORGE_DEEPEN_BY * JourneyStairwell.STAIR_ATTEMPTS_PER_BLOCK + 20;
                JourneyStairwell.digStairsDown(ctx, rig, deeper, cap, cap,
                        () -> carveTheForge(ctx, rig, lava, surfaceY, deepenings - 1));
                return;
            }
            ctx.fail("No placement of the mould holds: pushing out 2..8 blocks and digging down "
                    + (FORGE_DEEPENINGS * FORGE_DEEPEN_BY) + " blocks were all tried; the last"
                    + " obstruction was " + bad + " (bot at " + at + ")");
            return;
        }
        final int out = push;
        List<BlockPos> cells = JourneyForge.cells(at, away, push);
        // Remembered as a SET, because a retry needs to know what it is allowed to break. See
        // clearPourLine: a pour whose line is blocked may mine the blocker, and the difference
        // between "a stray block in the corridor" and "the alcove's own floor" is exactly this set.
        forgeCorridor = Set.copyOf(JourneyForge.corridor(at, away, push));
        // Cleared WITH the corridor it describes. A stuck list belongs to one excavation, and a
        // second spot's corridor can overlap the first's — carrying the old one across would tell
        // litterAt that a cell of the new alcove is rock nobody could break, when it was never tried.
        forgeStuck = Set.of();
        // Same reasoning, same instant: a step set that outlived its corridor would exempt a cell of
        // the NEW alcove from every sweep, on the strength of a flight built in a different hole.
        JourneyRamp.reset();
        BlockPos base = at.relative(away, push);
        // The lines the casts still to come have to see along — the second no-go list, registered
        // with the corridor for the same reason the first one is cleared with it. See JourneySight:
        // it is what lets a flight prefer a route that does not fill a cell a later pour has to
        // shoot through, and what names the borrow when there is no such route.
        JourneySight.mould(base, away);
        rig.evidence("forge.face", base.toShortString() + " facing " + away
                + " (away from the lava, pushed out " + push + " blocks, shaft bottom y=" + at.getY()
                + ", lava level y=" + lava.getY() + ")");
        // Corridor only. The twelve frame cells were checked for fluid above (via `cells`) but are
        // left SOLID here — each is opened in castCell just before it is filled, so that its floor
        // is still rock or already-cast obsidian at the moment the fluid lands in it.
        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos c : JourneyForge.corridor(at, away, push)) {
            if (level.getBlockState(c).isAir()) continue;
            todo.add(c);
        }
        rig.evidence("forge.toCarve", todo.size() + "/" + cells.size() + " cells");
        rig.attempting("Carving the casting alcove and the twelve frame cells");
        BotConfig.allowPlace = false;
        int swungBefore = rig.swungInPlace();
        int cobbleBefore = rig.carrying("minecraft:cobblestone");
        carveNext(ctx, rig, todo, 0, new ArrayList<>(), () -> {
            // Placing stays OFF from here to the last cast, and that is the fix run 16 asked for.
            // The alcove is finished: every cell the bot needs is already open, so anything the
            // pathfinder puts down inside it is pure obstruction — and it does not land harmlessly.
            // Measured: standToPour rejected all 140 candidates, reporting the head space occupied
            // on BOTH cells of the only line that can see the backing (-9,52,21 and -9,52,22) and
            // stray cobblestone at -10,53,21. That is the bot pillaring up its own shaft while
            // walking to a frame cell, and it walls off exactly the two spots the pour has to stand
            // in. The rung then had no
            // ray-verified spot at all, fell back to a standable one, and stopped on its own gate.
            //
            // The only place that still needs to build is the ascent, and climbOut turns it back on
            // for itself; returnToTheForge turns it off again on the way down.
            BotConfig.allowPlace = false;
            // NOT "DONE" WHEN IT IS NOT. The ladder run of 2026-08-15 printed `forge.carved` as done
            // directly under a `carve.stuck` row reporting 12 cells that would not break, and the
            // two rows were written by the same method one line apart. A caption that says the
            // excavation finished, beside a
            // measurement that says twelve of its cells are still rock, is a caption that can only
            // mislead.
            //
            // It reports rather than FAILS, and that is a decision the data forced. Stuck cells are
            // not uniformly fatal: the two rehearsals that cast 10/10 both carried four stuck cells
            // in `carve.stuck` at the alcove's ceiling, and the twelve that the ladder run carried
            // were at the top two rows as well — the cell that actually killed that run,
            // `7,56,19`, had been carved perfectly and was refilled afterwards. Failing here would
            // have ended three runs earlier than their real finding, which is the opposite of what a
            // gate is for.
            int carved = todo.size() - forgeStuck.size();
            rig.evidence("forge.carved", forgeStuck.isEmpty()
                    ? todo.size() + "/" + todo.size() + " cells, all open"
                    : carved + "/" + todo.size() + " cells open, " + forgeStuck.size()
                      + " would not break — see carve.stuck; the alcove is incomplete");
            // HOW MANY NEEDED A ROUTE AT ALL. `forge.carved` counts cells and cannot tell a carve
            // that walked to all of them from one that walked to none, and those are different
            // machines with different failure modes — the walk is what pillared the bot onto the
            // surface on 2026-08-16. A run where this number is near zero has NOT taken the fix.
            rig.evidence("forge.swung", (rig.swungInPlace() - swungBefore) + "/" + todo.size()
                    + " cells were broken in place (canBreak was already true, so no walk was needed)");
            // WHAT THE SKIPPED COLLECT COST. An in-place swing breaks the block but nothing walks
            // to the drop, so the alcove's cobblestone is now collected only by the avatar's own
            // pickup sweep. This rung SPENDS cobblestone (backings, steps, pillars), so how many
            // more blocks the bot holds after the carve is the number that says whether that trade
            // was affordable — measured as a delta, because the bag already held sixty-nine when
            // the rung started.
            rig.evidence("forge.cobblestone", cobbleBefore + " → "
                    + rig.carrying("minecraft:cobblestone") + " (net change across the alcove carve;"
                    + " an in-place break does not walk to its drop, so drops are collected only"
                    + " within the bot's own pickup range)");
            // Is the mould still a mould? The backings were solid when the spot was CHOSEN, and the
            // carve is the only thing that has happened since — but `allowBreak` stays on through it,
            // so the pathfinder is free to chew a way through the back wall while reaching a corridor
            // cell. A hole there is not a cosmetic loss: every bucket in this rung is aimed at the
            // backing, and a ray that passes through it puts the fluid a cell or more beyond, which
            // the rung would then report as "the cast does not work".
            String open = JourneyForge.firstOpenBacking(ctx.level(), at, away, out);
            rig.evidence("forge.backings", open == null ? "all fourteen backing cells are still solid"
                    : "dug through: " + open);
            if (open != null) {
                ctx.fail("Carving the mould dug through the wall behind the frame: " + open
                        + " — these cells were all solid when the spot was chosen, so the carve"
                        + " itself (allowBreak stays on throughout, and the pathfinder breaks walls"
                        + " on its own) opened the backing. Once a backing cell is empty, every"
                        + " bucket aimed at it passes through");
                return;
            }
            // HOME BEFORE THE FIRST CAST. Every other phase of this rung that can leave the alcove
            // ends by walking back into it — {@code JourneyStairwell.returnToTheForge} is called
            // after every fetch trip — and the carve, which leaves it more reliably than anything
            // else, did not.
            //
            // It leaves it because the alcove's ceiling is TWO BLOCKS UNDER THE GRASS: the mould's
            // top row is y=62 under a surface at y=64, so the cheapest way for `MineProcess` to
            // reach a cell it cannot swing at is to walk up the staircase and dig down from
            // outside. `forge.swung` says how often that happens — 63 of 67 cells were opened where
            // the bot stood and the remaining four were enough — and `carve.stuck`'s own key is
            // the giveaway, since it measures each cell's height against "the row the bot's feet
            // ended on" and that row read y=64 on runs whose alcove floor is y=56.
            //
            // Measured three times on the east arm, always the same two rows, never any others:
            //
            //   FAIL 8055t / FAIL 10608t   forge.carved=66/67  forge.swung=63/67  carve.stuck={-2=1}
            //                              cell.0.standMissed: wanted 3, 56, 19, stopped at 2, 65, 19
            //
            // The cast's own walk cannot fix it: `walkToStand` gets 300 ticks and NoBreak to cross
            // nine rows of rock it would have to go round by the stairs, so it reports a stand it
            // missed by 9.22 blocks and the dig then reports `canBreak=false` at 12 m — three
            // readings, none of which names the bot being outside. Walking home is not a widened
            // tolerance: it is the same walk, with the same audit and the same pillar-out recovery,
            // that the fetch trips have always used, asked at the one transition that skipped it. A
            // bot still in the alcove returns from its first line without moving.
            JourneyStairwell.returnToTheForge(ctx, rig, base.getY(), "forge",
                    () -> castTheFrame(ctx, rig, base, away, lava, surfaceY));
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
            // The baseline every later tidy is read against — see tidyTheAlcove. A corridor cell
            // that is solid AND in here was never opened; one that is solid and NOT in here arrived
            // after the carve, which is the only way the rung can tell its own scaffolding from the
            // rock it failed to break without guessing at block ids.
            forgeStuck = Set.copyOf(stuck);
            rig.evidence("carve.stuck", stuck.isEmpty() ? "none"
                    : stuck.size() + " cells would not break: " + describeStuck(rig, stuck));
            then.run();
            return;
        }
        BlockPos c = todo.get(i);
        if (ctx.level().getBlockState(c).isAir()) { carveNext(ctx, rig, todo, i + 1, stuck, then); return; }
        rig.mineCellOrGiveUp(c, 240, () -> {
            if (!ctx.level().getBlockState(c).isAir()) {
                if (stuck.isEmpty()) noteStuckCarve(rig, c);   // the FIRST one, and only that one
                stuck.add(c);
            }
            carveNext(ctx, rig, todo, i + 1, stuck, then);
        });
    }

    /**
     * Why the first corridor cell that would not open did not open.
     *
     * <p>{@code carve.stuck} has counted these for several runs and cannot say a word about the
     * cause: a cell the bot never got near, a cell it stood next to and ran out of budget on, and a
     * cell walled in on all six faces all arrive as the same coordinate in the same list. They want
     * completely different work — a different carve ORDER, a bigger number, a different standing spot
     * — so the list on its own can only support guesses, and this rung has paid for guesses before.
     *
     * <p>Three readings separate them, and they are the same three {@link #noteCellDig} uses on the
     * frame: how far away the bot was, what {@code canBreak} said, and how many of the six neighbours
     * are full solid faces. {@code canBreak=false} with 6/6 solid is the walled-in clause and an
     * ordering problem; {@code canBreak=false} at range is a bot that never arrived;
     * {@code canBreak=true} beside the cell is a budget that ran out.
     *
     * <p>The first only. Twelve of these would bury the one that matters, and they are consecutive
     * cells of one wall — whatever stopped the first almost certainly stopped its neighbours.
     */
    private static void noteStuckCarve(JourneyRig rig, BlockPos c) {
        ServerLevel level = rig.ctx().level();
        BlockPos at = rig.player().blockPosition();
        int solid = 0;
        StringBuilder around = new StringBuilder();
        for (Direction d : Direction.values()) {
            BlockPos n = c.relative(d);
            boolean s = level.getBlockState(n).isSolidRender(level, n);
            if (s) solid++;
            around.append(' ').append(d).append('=').append(level.getBlockState(n).getBlock())
                    .append(s ? "(solid)" : "");
        }
        rig.evidence("carve.firstStuck", String.format(java.util.Locale.ROOT,
                "%s=%s: bot at %s, %.1f blocks away, canBreak=%s, solid neighbours %d/6, holding %s;"
                        + " neighbours%s",
                c.toShortString(), level.getBlockState(c).getBlock(), at.toShortString(),
                Math.sqrt(at.distSqr(c)), rig.body().avatar().canBreak(c), solid,
                BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()), around));
    }

    /** Stuck cells summarised by height above the bot's floor — the shape of the failure matters
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
        // SAY WHAT THE KEY IS RELATIVE TO. "Height above the feet" is measured from wherever the
        // bot finished the carve, which is not the alcove floor and is not the same place twice — read without
        // that y the histogram put the ladder run's twelve stuck cells at "0 and 1", which reads as
        // the floor and is in fact the ceiling.
        return byHeight + " (key = height above y=" + floor + ", the row under the bot's feet when"
                + " the carve finished; value = cell count) at: " + where
                + (stuck.size() > 8 ? " …" : "");
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
        // Searched around the SURVEYED lava, not around the bot — and that is the fix for a run
        // that reported zero sources while standing in a chamber it had just carved. The shaft column is
        // chosen up to eight cells clear of the pool (it must not open into it), and then the alcove
        // is carved further away again, so by the time the casting starts the bot can be a dozen
        // blocks from the lava it came down for. The caller knows where the pool is; ask there.
        BlockPos here = rig.player().blockPosition();
        List<BlockPos> pool = JourneyTerrain.lavaSourcesNear(ctx.level(), lava, 16, here);   // may be widened below
        rig.evidence("pool.sources", pool.size() + " lava sources (" + RING.length + " needed)"
                + (pool.isEmpty() ? "" : ", nearest " + pool.get(0).toShortString() + " is "
                        + Math.round(Math.sqrt(pool.get(0).distSqr(here))) + " blocks from the bot"));
        // Widen before giving up. firstLava is the OBSIDIAN rung's fill point and a bucket takes the
        // source block itself, so the surveyed cell can simply be gone by now — measured, zero
        // sources within sixteen of it. The bot is already standing at lava level with its chunks
        // loaded, which is the one moment a wider look is cheap, so ask again from here before
        // declaring the rung impossible.
        if (pool.size() < RING.length) {
            List<BlockPos> wider = JourneyTerrain.lavaSourcesNear(ctx.level(), here, 40, here);
            rig.evidence("pool.widened", pool.size() + " → " + wider.size()
                    + " sources (within 40 blocks of the bot)"
                    + (wider.isEmpty() ? "" : ", nearest " + wider.get(0).toShortString() + " at "
                        + Math.round(Math.sqrt(wider.get(0).distSqr(here))) + " blocks"));
            if (wider.size() >= RING.length) pool = wider;
        }
        if (pool.size() < RING.length) {
            // Say where the lava ACTUALLY is before saying there is not enough of it. "0 within 16"
            // and "the nearest source is 40 blocks that way" are the same red row and want opposite
            // fixes — a wider search versus a different landmark.
            List<BlockPos> wider = JourneyTerrain.lavaSourcesNear(ctx.level(), here, 48, here);
            rig.evidence("pool.nearestAnywhere", wider.isEmpty() ? "none within 48 blocks"
                    : wider.get(0).toShortString() + " is "
                      + Math.round(Math.sqrt(wider.get(0).distSqr(here))) + " blocks from the bot; "
                      + wider.size() + " source blocks in total");
            rig.evidence("pool.column", JourneyTerrain.lavaColumnReport(ctx.level(), here, 24));
            ctx.fail("Not enough lava sources: only " + pool.size() + " source blocks within 16"
                    + " blocks of the surveyed point " + lava.toShortString() + ", and ten casts need"
                    + " ten. **firstLava is where OBSIDIAN fills its bucket, and each fill removes"
                    + " the source block itself** — this rung needs a lava lake with at least ten"
                    + " source blocks, which is a separate landmark, not the same point (bot at "
                    + here + ")");
            return;
        }
        JourneyFill.pinTheFillStation(ctx, rig, lava, surfaceY, JourneyStairwell.stairTop);
        rig.attempting("Casting ten obsidian blocks with one bucket (carrying the water along)");
        openTheFrameWatch(base, away);
        castCell(ctx, rig, base, away, pool, 0, () -> lightIt(ctx, rig, base, away, surfaceY));
    }

    // ---- the frame watch: CAST IS NOT KEPT ----

    /**
     * The ten cells of the frame being cast, whatever is standing in them right now.
     *
     * <p>Read through {@link #isFrameCell}, which is package-visible because the code that has to
     * ask is not in this file: a bucket whose sightline is blocked answers by MINING the blocker,
     * and down in the alcove the only thing tall enough to block one is the frame itself. See
     * {@link JourneyFill}'s clear-line branch.
     */
    private static Set<BlockPos> frameCells = Set.of();

    /** Ring cells this run has watched turn to obsidian, and is therefore entitled to still have. */
    private static final Set<BlockPos> frameCast = new java.util.LinkedHashSet<>();

    /** How many losses have been reported, so each gets its own evidence key. */
    private static int frameLosses;

    /** The last step that ended with every cast cell still obsidian — the other half of "when". */
    private static String frameLastSound = "before casting began";

    /** Is this one of the ten cells the frame is made of? */
    static boolean isFrameCell(BlockPos c) { return frameCells.contains(c); }

    private static void openTheFrameWatch(BlockPos base, Direction away) {
        Set<BlockPos> ring = new java.util.LinkedHashSet<>();
        for (int[] c : RING) ring.add(frameCell(base, away, c[0], c[1]).immutable());
        frameCells = Set.copyOf(ring);
        frameCast.clear();
        frameLosses = 0;
        frameLastSound = "before casting began";
    }

    /**
     * Check every cell already cast is STILL obsidian, and name the step that took one that is not.
     *
     * <p><b>Poured is not kept, and until this the rung could not tell the two apart.</b> The
     * rehearsal of 2026-08-15 recorded {@code CONSUME} for all ten casts, not one
     * {@code cast.missed.*} — so at the instant of each pour all ten cells WERE obsidian — and then
     * finished {@code frame.cast=6/10}. Four cells went missing after being cast and the only
     * reading that existed was the final count, which can date a loss to "somewhere in the ten
     * round trips" and no closer. One of the four left a trace ({@code recover9.clearedLine.3},
     * a fill breaking the frame to see past it); the other three left nothing at all.
     *
     * <p>So the frame is re-read after every step that can move a block, and a cell that has stopped
     * being obsidian is reported ONCE, with the step it disappeared inside and the last step it was
     * still whole after. That pair is the whole diagnosis: a count says four are gone, this says
     * which four, and between which two instructions.
     *
     * <p>Reported and dropped rather than reported and kept, so ten later checks do not each
     * re-announce the same cell. The running total goes on every row, which is what makes a second
     * loss legible as a second loss.
     */
    private static void auditFrame(JourneyRig rig, String step) {
        ServerLevel level = rig.ctx().level();
        for (var it = frameCast.iterator(); it.hasNext(); ) {
            BlockPos c = it.next();
            if (level.getBlockState(c).getBlock() == Blocks.OBSIDIAN) continue;
            it.remove();
            BlockPos at = rig.player().blockPosition();
            rig.evidence("frame.lost." + (++frameLosses), c.toShortString()
                    + " was cast to obsidian and is gone again: it is now "
                    + level.getBlockState(c).getBlock()
                    + ", lost during step '" + step + "' (last seen intact after '" + frameLastSound
                    + "'); bot at " + at.toShortString() + ", "
                    + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(at.distSqr(c)))
                    + " blocks away, holding "
                    + BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem())
                    + "; cast so far " + (frameCast.size() + frameLosses) + " cells, still standing "
                    + frameCast.size());
        }
        // Unconditionally, INCLUDING after a loss. The cells still standing were verifiably whole at
        // the end of this step, so this step is what the next loss should name as its last-seen —
        // freezing the marker on a loss would date every later loss to the same stale instruction.
        frameLastSound = step;
    }

    /** Run {@code then}, having first checked the frame survived {@code step}. */
    private static Runnable watchFrame(JourneyRig rig, String step, Runnable then) {
        return () -> { auditFrame(rig, step); then.run(); };
    }

    private static BlockPos wetCellFor(BlockPos base, Direction away, int dx, int dy) {
        return JourneyForge.wetCellFor(base, away, dx, dy);
    }

    private static void castCell(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                 List<BlockPos> pool, int i, Runnable then) {
        if (i >= RING.length) {
            auditFrame(rig, "after the last cell was finished");
            // BOTH NUMBERS. "6/10" alone is the row that started this: it cannot say whether four
            // cells never cast or four cast and were taken back, and those want opposite work.
            rig.evidence("frame.cast", countObsidian(ctx.level(), base, away) + "/" + RING.length
                    + " (" + (frameCast.size() + frameLosses) + " cells were cast, " + frameLosses
                    + " of them lost again afterwards — see frame.lost.*)");
            then.run();
            return;
        }
        BlockPos cell = frameCell(base, away, RING[i][0], RING[i][1]);
        BlockPos wet = wetCellFor(base, away, RING[i][0], RING[i][1]);
        // WHERE THE ROLL STOPPED — written at the START of each cell's chain, unconditionally, and
        // that ordering is the whole point. Every other per-cell row (`cast{i}.*`, `recover{i}.*`,
        // `tools.{i}`) is written by a step that has to SUCCEED far enough to write it, so a run
        // that dies mid-cell leaves a ragged set of keys and the reader has to infer how far the
        // ring got from which rows happen to be missing. Measured on the archived rung-12 ladder:
        // cells 0–7 were complete, `recover8` was ABSENT, and cell 9 had nothing at all — three
        // different key shapes for one question. With this row the answer is a subtraction: the
        // LOWEST absent index is the cell the run never began.
        //
        // `frame.cast` answers the same question only at the END, and a run that never reaches the
        // end never writes it. This one is written ten times or fewer, never zero.
        rig.evidence("frame.roll." + i, "starting " + cell.toShortString() + " (water cell "
                + wet.toShortString() + "); cast so far " + frameCast.size() + "/" + RING.length
                + ", lost after casting " + frameLosses);
        // What the rung has left to dig with, per cell. A snapped pickaxe and a cell the bot cannot
        // reach produce the same line — `opened.N=…=stone` — and they want opposite fixes. The kit is
        // two stone pickaxes (262 uses) on purpose, and this rung breaks roughly a hundred cells plus
        // whatever the ten descents re-mine, so "the tool ran out on cast eight" is a live possibility
        // that nothing was recording.
        rig.evidence("tools." + i, toolReport(rig));
        // WHICH ROUND TRIP LOST A BACKING. `forge.backings` says the mould was sound when it was
        // carved and a pour says it is not any more; between them lie ten trips, and without a
        // per-cast count the loss can only be dated to "somewhere in the casting". Silent while the
        // fourteen are intact — which a whole run has now been, so the silence is a real reading and
        // not a wire that was never connected. See mendBacking for what it costs when it is not.
        List<BlockPos> openBackings = JourneyForge.openBackings(ctx.level(), base, away);
        if (!openBackings.isEmpty()) {
            StringBuilder where = new StringBuilder();
            for (BlockPos b : openBackings)
                where.append(where.isEmpty() ? "" : " ").append(b.toShortString()).append('=')
                        .append(ctx.level().getBlockState(b).getBlock());
            rig.evidence("backings." + i, openBackings.size() + "/14 backing cells are no longer solid: "
                    + where);
        }
        // Open exactly these two, now. Everything else in the frame is still solid, which is what
        // gives this cell a floor — see forgeCorridor for why carving them all up front cast 0/10.
        // 1200, not 400. Twenty seconds has to cover pathing to the cell as well as breaking it, and
        // `mineCellOrGiveUp` carries on regardless when it runs out — so a budget that is merely tight
        // does not report itself, it reports a pour into rock two steps later. UNVERIFIED: this is a
        // plausible reason run 20 left `wet` as stone, not a confirmed one; the assertion below is
        // what will actually name the cause next run.
        reopen(ctx, rig, "cell." + i, cell, away, REOPEN_TRIES,
                watchFrame(rig, "cell." + i + " opening frame cell " + cell.toShortString(), () ->
                reopen(ctx, rig, "wet." + i, wet, away, REOPEN_TRIES,
                watchFrame(rig, "wet." + i + " opening water cell " + wet.toShortString(), () ->
                        tidyTheAlcove(ctx, rig, "tidy." + i,
                                watchFrame(rig, "tidy." + i
                                        + " clearing the bot's own blocks from the alcove", () ->
                                        castOpenedCell(ctx, rig, base, away, pool, i, cell, wet,
                                                then)))))));
    }

    /** How many times a cell may be opened before the rung accepts that it is shut. Four: one dig
     *  plus three refills, which is a gravel column three deep. */
    private static final int REOPEN_TRIES = 4;

    /**
     * Open a cell and KEEP it open — gravel falls, and a cell is only air until the tick after.
     *
     * <p>Run 30 cast six cells and stopped on the seventh with {@code opened.6=-8,59,38=air} two
     * lines above {@code cast6.before=-8,59,38=gravel}. Nothing had gone wrong with the dig: the cell
     * directly over that one is gravel, mining out from under it dropped it in, and the reading that
     * says the cell is open was taken in the same tick as the swing that opened it. The pour then
     * aimed at the target, hit the gravel standing in it, and put the lava a cell short —
     * {@code cast6.picks} recorded gravel at -8,59,38, face north, with the fluid landing in
     * -8,59,37.
     *
     * <p>So the check is: dig, let the world settle, look again. Fluids are left alone — a cell with
     * the rung's own water in it is not shut, and asking {@code mine} to break water spends the whole
     * budget on a no-op. When the tries run out the cell is described rather than mined, which is
     * {@link #noteCellDig}'s job and the reading that separates "walled in" from "out of reach".
     *
     * <p><b>"Refilled" is now a measurement, not a caption.</b> It used to be printed on every retry
     * that found the cell solid — which is also what a dig that never opened it looks like — so the
     * ladder run of 2026-08-12 reported that -9,56,34 had been refilled with granite falling from
     * above, three times, about a granite block that had never once been air. Granite is not a
     * {@code FallingBlock} and nothing fell; the dig simply failed, and the line named a mechanism
     * instead of saying so.
     *
     * <p><b>The bot stands in the corridor first.</b> {@code ServerWorldDriver.mine} is
     * {@code walker.setGoal(Near(cell, 2))} with breaking on, and a walker asked to get near a cell
     * in a wall will happily tunnel through the wall — which here is the mould. That is what the same
     * run did: it ended at {@code -10,59,34}, and {@code -10,59,34} is not a corridor cell at all, it
     * is an <b>interior cell of the portal's own doorway</b>, three of which the save shows opened.
     * The cell behind a frame cell is a corridor cell by construction, so the dig is aimed from
     * there, and the walk to it may not break anything.
     */
    private static void reopen(SceneContext ctx, JourneyRig rig, String tag, BlockPos cell,
                               Direction away, int tries, Runnable then) {
        reopen(ctx, rig, tag, cell, away, tries, false, then);
    }

    private static void reopen(SceneContext ctx, JourneyRig rig, String tag, BlockPos cell,
                               Direction away, int tries, boolean wasOpen, Runnable then) {
        ServerLevel level = ctx.level();
        if (level.getBlockState(cell).isAir() || !level.getFluidState(cell).isEmpty()) {
            then.run();
            return;
        }
        if (tries <= 0) { noteCellDig(rig, tag, cell, away); then.run(); return; }
        if (tries < REOPEN_TRIES)
            rig.evidence(tag + (wasOpen ? ".refilled." : ".stillShut.") + tries,
                    cell.toShortString() + "=" + level.getBlockState(cell).getBlock()
                    + (wasOpen ? ": was open and has been refilled (a falling block sits above this"
                                 + " cell); digging again"
                               : ": this cell was never open, not refilled — the dig did not break it;"
                                 + " trying again"));
        standBehind(rig, tag, cell, away, () ->
            digWithoutTunnelling(rig, cell, tries == REOPEN_TRIES ? 1_200 : 400,
                () -> rig.settle(new HoldStill(10), 30, () -> {
                    // Read the cell BETWEEN the swing and the settle, so "it opened and something
                    // dropped into it" and "it never opened" stop being the same reading.
                    boolean open = wasOpen || level.getBlockState(cell).isAir();
                    reopen(ctx, rig, tag, cell, away, tries - 1, open, then);
                })));
    }

    /**
     * Dig one cell of the mould WITHOUT letting the walk to it dig anything else.
     *
     * <p>{@code ServerWorldDriver.mine} is a walker goal plus a swing, and the walker plans with
     * {@code BotConfig.allowBreak} on for the whole casting phase — so when the cell it is sent to
     * has no walkable approach, it invents one THROUGH the mould. {@link #standBehind} was the first
     * answer to that and it only covers the case where a corridor stand exists; when it reports
     * {@code .noStand} the dig still runs, and the route it then takes is the one nothing was
     * watching.
     *
     * <p>Measured the first time the frame watch ran on a single-bucket rehearsal:
     * {@code frame.lost.1} reported that -9,60,38 had been cast to obsidian and was air again, lost
     * during the step that opened water cell -10,61,38 ({@code wet.9}), with the bot at -10,57,38.
     * The step is a dig of the NOTCH; the cell it cost is the
     * top-left ring cell two rows below it; and {@code -10,57,38} is not a corridor cell at all, it
     * is an interior cell of the portal's own doorway. The bot was inside the mould, having dug
     * its way up through it, exactly as {@link #reopen}'s note describes — and the audit is what
     * turned that from "four cells are missing" into one instruction with a coordinate.
     *
     * <p>Turning the pathfinder's breaking off does not disarm the dig: {@code allowBreak} prices
     * the WALK's breaks ({@code LevelWorldView.breakCost} returns infinity), while the target itself
     * is broken by {@code avatar.breakHold} once navigation stops, gated only by reach and exposure.
     * So a cell with an approach is still opened, and a cell without one now reports
     * {@code .stillShut} / {@code dig.*} instead of quietly paying for itself with a cast cell.
     */
    private static void digWithoutTunnelling(JourneyRig rig, BlockPos cell, int ticks, Runnable then) {
        boolean was = BotConfig.allowBreak;
        BotConfig.allowBreak = false;
        rig.mineCellOrGiveUp(cell, ticks, () -> {
            BotConfig.allowBreak = was;
            then.run();
        });
    }

    /**
     * Put the bot in the corridor cell directly behind {@code cell} before digging it.
     *
     * <p>Behind, because that cell is in {@link #forgeCorridor} by construction — the corridor is the
     * two ranks between the shaft and the frame plane, and every frame cell's own dx is inside the
     * corridor's width — so it is a place the rung has already hollowed and is entitled to stand in.
     * {@link NoBreak} on the walk is the whole point: the alternative is the walker inventing its own
     * route, and the route it invented went through the doorway.
     *
     * <p><b>Only when that cell has a floor</b>, and the guard is a measurement rather than caution.
     * The corridor is hollowed from the alcove floor to its ceiling, so the cell behind a frame cell
     * is standable for the BOTTOM row and for nothing above it: behind {@code -11,58,38} is
     * {@code -11,58,37}, which is air over {@code -11,57,37}, which is corridor and therefore also
     * air. Sending the bot there anyway is what the first version did, and it measurably made the
     * rung worse — the rehearsal that had been reaching cast 9 stopped at cell 5, having spent the
     * walk's whole budget failing to stand in mid-air and then digging from wherever that left it.
     *
     * <p>So the upper rows keep the behaviour they had: {@code mine}'s own {@code Near(cell, 2)}
     * goal, which reaches them from the floor when it can. What that goal cannot do is the thing
     * {@link #noteCellDig} now measures — {@code -11,56,36} to {@code -11,58,38} is 2.83 blocks, so
     * for a cell two rows up there is no standable cell inside the radius at all, and the dig never
     * arrives. That is the next cut in this rung and it wants a step to stand on, not a longer walk.
     *
     * <p><b>And one step, when one step is all that is missing.</b> {@code behind} sits level with the
     * cell (1.00 away) and {@code behind.below()} one row under it (1.41) — both inside the gate, and
     * both corridor cells for every row above the floor. Whichever of the two already has something
     * under it is walked to. When neither does, a single cobblestone goes into the lower one's own
     * support, which is a corridor cell resting on the untouched rock below the alcove floor, and the
     * bot steps up exactly one block onto it — ordinary walking, no tower, no drift.
     *
     * <p>That covers the frame's bottom three rows and stops there, on purpose. A cell four or five
     * rows up would need two or three blocks arranged as STAIRS, not stacked: a filled column is a
     * wall the bot cannot climb, and building a staircase in a corridor is a different piece of work
     * from placing one block. Those rows keep {@code mine}'s own goal and get told, by name, how many
     * blocks short they were — which is the reading the next attempt should start from rather than
     * the silence that was there before.
     *
     * <p>Best effort throughout. A bot that cannot get there still gets its dig attempted from
     * wherever it is, and {@link #noteCellDig} reports the geometry if it was not.
     */
    private static void standBehind(JourneyRig rig, String tag, BlockPos cell, Direction away,
                                    Runnable then) {
        standBehind(rig, tag, cell, away, LITTER_CLEARS, then);
    }

    /** How many blocking cells one stand may clear before it gives up and digs from where it is.
     *  Two: the stand is one cell and its head cell, and a third is a different finding. */
    private static final int LITTER_CLEARS = 2;

    private static void standBehind(JourneyRig rig, String tag, BlockPos cell, Direction away,
                                    int clears, Runnable then) {
        ServerLevel level = rig.ctx().level();
        BlockPos here = rig.player().blockPosition();
        if (forgeCorridor.isEmpty() || withinDigReach(here, cell)) { then.run(); return; }

        // THE JUDGE'S RADIUS, NOT ONLY THE EXECUTOR'S. {@code withinDigReach} is the executor's:
        // DIG_ARRIVE exists to match the {@code Goal.Near(cell, 2)} that mineCellOrGiveUp walks.
        // But what decides whether a swing lands is {@code Body.canBreak}, an eye-distance test
        // with a ceiling of 5.0 — so asking only the tighter number sends a bot that could already
        // swing off to build a staircase it does not need.
        //
        // Measured, rung 12's client rehearsal of 2026-08-26. The stand for the ninth cell was
        // refused at 2,58,20 for being 3.00 from 4,60,19 — an eye distance of roughly 2.4, well
        // inside canBreak. The ramp that followed then skipped its own flight (the bot was already
        // six rows up, in a different column), so every later swing came from the surface:
        // three attempts at 0,65,19 / 4,64,19 / 3,65,18, eye distances 7.36 / 5.13 / 6.61 against a
        // ceiling of 5.00, all canBreak=false, and the cell never opened. The nearest miss was
        // 0.13 blocks. Walking is what turned 2.4 into 5.13 — the same shape as the carve's own
        // finding that its walking step opened ZERO cells (see JourneyRig#mineCellOrGiveUp).
        //
        // The server avatar on purpose: it is the one breakItWhereItStands itself asks, so this
        // gate and the swing it green-lights cannot disagree. Costless when it refuses — the bot
        // falls through to exactly the stand-finding it would have done anyway.
        if (rig.body().avatar().canBreak(cell)) {
            rig.evidence(tag + ".swingFromHere", cell.toShortString() + " is reachable in place: bot at "
                    + here.toShortString() + ", cell-centre distance "
                    + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(here.distSqr(cell)))
                    + " exceeds DIG_ARRIVE=" + DIG_ARRIVE + ", but canBreak is true ⇒ no stand search"
                    + " and no ramp");
            then.run();
            return;
        }

        BlockPos behind = cell.relative(away.getOpposite());
        BlockPos lower = behind.below();
        // TAKE BACK WHAT THE DIG ITSELF PUT HERE, one cell, before deciding this stand is impossible.
        //
        // `MineProcess` reaches a cell above head height by pillaring, and it pillars with
        // `JourneyShaft.pillarBlock` — whichever of seven spoils the bot carries MOST of. Every
        // rehearsal is handed `cobblestone×64`, so for thirty runs that was cobblestone and
        // `tidyTheAlcove` swept it. A real climb arrives with what eleven rungs left: the ladder run
        // of 2026-08-15 arrived holding DIRT, and its first frame cell then read `canBreak=false`
        // with all six neighbours solid because the corridor cell behind it had become one of them —
        // `cell.0.noStand` reported 7,56,19 occupied by dirt, at floor level, in a chamber cut
        // through granite where dirt is not terrain. Three retries then re-asked an unchanged
        // question and the rung died five casts' worth of wall clock later, at the pour.
        //
        // ONE CELL, not a sweep. The wider version — clear every corridor cell solid that the carve
        // did not leave solid — was tried and regressed the rung twice from 2/2: gravel falls into a
        // seven-tall excavation and PLUGS the alcove floor, and those plugs are what the cast's water
        // drains through. See tidyTheAlcove for the measurement. What a dig needs is its own standing
        // cell back, and that is all this takes.
        BlockPos blocked = litterAt(level, behind, lower);
        if (blocked != null && clears > 0) {
            rig.evidence(tag + ".litter." + clears, blocked.toShortString() + "="
                    + level.getBlockState(blocked).getBlock()
                    + " was placed into the stand cell by the bot's own frame digging; breaking it"
                    + " before standing (it appeared after the carve, so it is not in carve.stuck)");
            rig.mineCellOrGiveUp(blocked, 300,
                    () -> standBehind(rig, tag, cell, away, clears - 1, then));
            return;
        }
        String whyBehind = whyNotStandable(level, behind);
        if (whyBehind == null) { walkToStand(rig, tag, cell, behind, then); return; }
        String whyLower = whyNotStandable(level, lower);
        if (whyLower == null) { walkToStand(rig, tag, cell, lower, then); return; }

        // One block, and only where it can rest on something. `lower`'s own support is the corridor
        // cell at the alcove's floor level, whose floor is the untouched rock the alcove was cut
        // into — so this is a step, not the first course of a pillar the bot would then have to
        // climb. Anywhere else and the honest answer is "not enough blocks", which is what it says.
        BlockPos step = lower.below();
        String whyStep = whyNotStep(level, step, here);
        if (whyStep != null) {
            rig.evidence(tag + ".noStand", cell.toShortString() + " is out of reach: bot at "
                    + here.toShortString() + ", "
                    + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(here.distSqr(cell)))
                    + " blocks away (>" + DIG_ARRIVE + "); cannot stand: " + whyBehind + "; " + whyLower
                    + "; cannot place a step: " + whyStep + " — building a staircase up instead");
            // A FLIGHT, because one brick is what this row has just finished saying is not enough.
            // The two cells a single step can reach are the frame's bottom three rows; from the
            // fourth row up the brick's own support is air as well, and the honest answer is a
            // staircase resting on the alcove's floor. See JourneyRamp for why it is walked rather
            // than towered.
            JourneyRamp.buildTo(rig, forgeCorridor, lower, tag + ".ramp",
                    () -> walkToStand(rig, tag, cell, lower, then));
            return;
        }
        // Both players (client and server-side) — `placeInto` places through the server. See
        // JourneyHands.holdBoth.
        boolean held = JourneyHands.holdBoth(rig, Items.COBBLESTONE);
        if (held) JourneyStairs.placeInto(level, rig, step);
        // THE WORLD, not the call. A placement can be refused for reasons the caller cannot see, and
        // a step that was never there leaves exactly the "the dig just did not work" row this rung
        // has already been misled by twice.
        boolean stood = level.getBlockState(step).blocksMotion();
        rig.evidence(tag + ".step", step.toShortString() + " support block placed for "
                + cell.toShortString() + " → "
                + (stood ? "standable (" + level.getBlockState(step).getBlock() + ")"
                         : (held ? "not placed (" + level.getBlockState(step).getBlock() + ")"
                                 : "no cobblestone in hand")));
        if (!stood) { then.run(); return; }
        walkToStand(rig, tag, cell, lower, then);
    }

    /** Walk to a chosen stand and say where the bot actually ended up — a walk that fell short and
     *  a walk that arrived produce identical digs otherwise, and only one of them is a bug. */
    private static void walkToStand(JourneyRig rig, String tag, BlockPos cell, BlockPos spot,
                                    Runnable then) {
        if (rig.player().blockPosition().equals(spot)) { then.run(); return; }
        rig.settle(new IntentProcess(new Intent(new Goal.Block(spot), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), 300, () -> {
            BlockPos now = rig.player().blockPosition();
            if (withinDigReach(now, cell)) { then.run(); return; }
            // THE CONTINUOUS POSITION, not only the cell. The run of 2026-08-13 reported
            // `cell.5.standMissed` as wanting -11,57,37 and stopping at -11,57,36 — the right
            // height and the near rank — and two very different positions produce that line: one that
            // never got onto the step, and one that IS on the step with its centre a hand's width
            // back, so that the cell its feet round to is the neighbour. A 0.6-wide box resting on
            // a block edge is the second, and the two want opposite fixes (a second step versus a
            // nudge). What is under the feet says which.
            ServerLevel lvl = rig.ctx().level();
            rig.evidence(tag + ".standMissed", "wanted " + spot.toShortString() + ", stopped at "
                    + now.toShortString() + ", still "
                    + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(now.distSqr(cell)))
                    + " blocks from " + cell.toShortString() + " (exact "
                    + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f",
                            rig.player().getX(), rig.player().getY(), rig.player().getZ())
                    + ", under the feet " + now.below().toShortString() + "="
                    + lvl.getBlockState(now.below()).getBlock()
                    + ", under the wanted stand " + spot.below().toShortString() + "="
                    + lvl.getBlockState(spot.below()).getBlock() + ")");
            then.run();
        });
    }

    /**
     * Why the bot cannot stand in {@code spot}, in the words of the clause that refused it — or
     * null when it can.
     *
     * <p>Four clauses, and the message used to name one of them for all four:
     * {@code cell.0.noStand} reported that neither -9,56,37 nor -9,55,37 had a floor, about the mould's BOTTOM
     * row, and an offline read of that run's saved world says {@code -9,55,37 = andesite} — a
     * perfectly good floor. So the row was false, and WHICH of the other three refused it is not
     * recoverable from the run: both remaining candidates are live in that alcove (the cell itself
     * occupied — gravel arrives in a seven-tall excavation on its own, and the staircase audit caught
     * exactly that at {@code -9,56,36} in the same run — or the head cell occupied). A row that names
     * a mechanism it did not test is worse than no row, because it ends the search; this one filed
     * the reading under "no floor" and it does not belong there.
     */
    private static String whyNotStandable(ServerLevel level, BlockPos spot) {
        if (!forgeCorridor.contains(spot)) return spot.toShortString() + " is not an alcove cell";
        if (level.getBlockState(spot).blocksMotion())
            return spot.toShortString() + " is occupied by " + level.getBlockState(spot).getBlock();
        if (level.getBlockState(spot.above()).blocksMotion())
            return spot.toShortString() + " has its head space " + spot.above().toShortString() + "="
                    + level.getBlockState(spot.above()).getBlock() + " occupied";
        if (!level.getBlockState(spot.below()).blocksMotion())
            return spot.toShortString() + " has " + spot.below().toShortString() + "="
                    + level.getBlockState(spot.below()).getBlock() + " under it, which is not a floor";
        return null;
    }

    /**
     * The first of a stand's own cells that this rung put a block into after carving it — or null.
     *
     * <p>Feet and head of both candidate stands, because either one seals the stand and the head is
     * the one the rehearsals actually hit ({@code cell.0.noStand} reported the head space of
     * -9,56,37, at -9,57,37, occupied by cobblestone). {@link #forgeStuck} is what makes this
     * answerable without guessing at block
     * ids: a corridor cell that is solid and was left solid by the carve is rock the pick could not
     * reach, and re-attempting it every cast is exactly the thrash the block-id test was protecting
     * against; a corridor cell that is solid and was NOT is something that arrived since, and the
     * only thing placing blocks down here is the rung's own digging.
     */
    private static BlockPos litterAt(ServerLevel level, BlockPos behind, BlockPos lower) {
        for (BlockPos c : List.of(behind, behind.above(), lower, lower.above())) {
            if (!forgeCorridor.contains(c)) continue;
            if (forgeStuck.contains(c)) continue;
            // A step of the flight is not something that "arrived since" — it is the floor a stand
            // one row up rests on, and `behind`'s own support is exactly the cell a taller cell's
            // landing was built over. Breaking it here would clear the stand this dig is about to
            // choose. Same exemption tidyTheAlcove carries, for the same reason — and NOT the one
            // clearPourLine carries any more: a step in a pour's line is a step that pour lent the
            // flight, and it goes back. See JourneySight#blockersOnTheLine.
            if (JourneyRamp.isStep(c)) continue;
            if (!level.getBlockState(c).blocksMotion()) continue;   // air, and the rung's own water
            return c;
        }
        return null;
    }

    /** Why one cobblestone will not turn {@code step} into a stand, or null when it will. Same
     *  discipline as {@link #whyNotStandable}: six clauses, six different sentences, because
     *  "there is already something there" and "a brick here would hang in mid-air" are the two the
     *  rung has actually met and they want completely different work. */
    private static String whyNotStep(ServerLevel level, BlockPos step, BlockPos here) {
        if (!forgeCorridor.contains(step)) return step.toShortString() + " is not an alcove cell";
        if (!level.getBlockState(step).isAir())
            return step.toShortString() + " is already " + level.getBlockState(step).getBlock();
        if (!level.getFluidState(step).isEmpty())
            return step.toShortString() + " contains fluid";
        if (!level.getBlockState(step.below()).blocksMotion())
            return step.toShortString() + " has " + step.below().toShortString() + "="
                    + level.getBlockState(step.below()).getBlock()
                    + " under it, which cannot support it — a single block would hang in mid-air;"
                    + " this cell needs a staircase, not one block";
        if (step.equals(here) || step.equals(here.above()))
            return step.toShortString() + " is occupied by the bot";
        double d = Math.sqrt(here.distSqr(step));
        if (d > JourneyStairs.MEND_REACH)
            return step.toShortString() + " is " + Math.round(d) + " blocks from the bot, out of reach";
        return null;
    }

    /** {@code ServerWorldDriver.mine} walks to {@code Goal.Near(cell, 2)}, so this is the radius the
     *  dig will and will not start inside. Named because it is the number the whole stand exists to
     *  satisfy: measured, {@code -11,56,36} to {@code -11,58,38} is 2.83 and the dig never began. */
    private static final int DIG_ARRIVE = 2;

    private static boolean withinDigReach(BlockPos from, BlockPos cell) {
        return from.distSqr(cell) <= (double) DIG_ARRIVE * DIG_ARRIVE;
    }

    /**
     * Take the bot's own scaffolding back out of the alcove before it pours into it.
     *
     * <p>{@code allowPlace} is off for the whole casting phase and the alcove fills with cobblestone
     * anyway, because the placer is not the pathfinder: {@code MineProcess} reaches a cell above head
     * height by planning a <b>pillar-up</b> stand, and the two frame cells opened at the top of every
     * {@code castCell} are exactly that shape. So each cast leaves a column or two behind, in the
     * only volume this rung has to stand and aim in.
     *
     * <p>Measured, run 29: cells 0–2 cast, and cell 3 at {@code -11,57,38} then had no ray-verified
     * spot at all — {@code -11,57,36}, {@code -11,58,36}, {@code -11,59,36}, {@code -11,58,37} and
     * {@code -10,58,37} were all cobblestone, so {@code standToPour} fell back to a standable cell
     * three columns away and its ray stopped on the litter: {@code cast3.picks} recorded
     * cobblestone at -10,58,37, face north, with the fluid landing in -10,58,36.
     * {@link #clearPourLine} cleans the LINE and that was
     * not enough; what a pour needs clear is the room.
     *
     * <p><b>Cobblestone only, and that is a MEASURED restriction rather than the original lazy one.</b>
     * The sweep was widened once — to "any corridor cell that is solid and not in {@link #forgeStuck}",
     * which is the honest reading of "anything solid in here arrived afterwards" — and it regressed
     * the rung twice in a row from a standing 2/2. The reason is a block nobody had thought of as
     * structural: <b>gravel falls into a seven-tall excavation and plugs the alcove's floor</b>, and
     * those plugs are what the cast's water drains away through instead of pooling.
     *
     * <p>Measured, both runs, same three cells: {@code tidy.0} removed {@code -7,56,36=gravel},
     * {@code -9,56,36=gravel}, {@code -8,57,36=gravel}, and from cast six onward
     * {@code drain.6} reported fluid still present after waiting 200 ticks, with -7,56,36 = water —
     * the very cell the gravel had been
     * cleared from. With the alcove wet three casts earlier than before, the bot then floated in it
     * ({@code climb.4…10 = -7,56,36 onGround=false water=true}) and the top-row pours failed on their
     * own flooded line. The two runs before the widening reported {@code drain.0…6} as the alcove
     * having drained.
     *
     * <p>So the widening is reverted and the case that motivated it is answered where it actually
     * bites: {@link #standBehind} clears the ONE corridor cell a dig needs, by the same
     * {@link #forgeStuck} baseline and without touching the floor. See its note for the ladder run
     * that could not open its first frame cell because {@code 7,56,19} had been pillared full of dirt.
     *
     * <p>Top down, so each cell is adjacent to air when its turn comes and the bot simply rides the
     * column down as it goes.
     */
    private static void tidyTheAlcove(SceneContext ctx, JourneyRig rig, String tag, Runnable then) {
        ServerLevel level = ctx.level();
        List<BlockPos> litter = new ArrayList<>();
        for (BlockPos c : forgeCorridor)
            // A STEP IS NOT LITTER. This sweep exists to take back the columns MineProcess pillars
            // up while reaching a cell over head height — blocks that arrived by accident, in the
            // volume the pours have to stand in. The flight JourneyRamp lays is the opposite: it IS
            // where the next pour stands, and sweeping it puts the top rows back out of reach one
            // cell after they were reached. Same distinction forgeStuck draws for the carve, and for
            // the same reason: "solid, and the rung put it there on purpose" is not a question a
            // block id can answer.
            if (level.getBlockState(c).getBlock() == Blocks.COBBLESTONE && !JourneyRamp.isStep(c))
                litter.add(c.immutable());
        if (litter.isEmpty()) { then.run(); return; }
        litter.sort((a, b) -> b.getY() - a.getY());
        // WITH THE BLOCK, now that it is no longer cobblestone by definition. What the bot pillars
        // with is whatever it happens to be carrying most of, so the id is the reading that says
        // which spoil this climb arrived on — and it is the one that would have named `dirt` in the
        // run above instead of leaving the corridor silently full of it.
        StringBuilder where = new StringBuilder();
        for (BlockPos c : litter)
            where.append(where.isEmpty() ? "" : " ").append(c.toShortString()).append('=')
                    .append(level.getBlockState(c).getBlock());
        rig.evidence(tag, litter.size() + " cells appeared after the carve and are being cleared"
                + " (MineProcess placed them itself while digging the frame): " + where);
        clearNext(rig, litter, 0, 240, then);
    }

    /** Every pickaxe in the bag with the uses it has left, commonest failure first. */
    private static String toolReport(JourneyRig rig) {
        var inv = rig.player().getInventory();
        StringBuilder out = new StringBuilder();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            var stack = inv.getItem(s);
            if (stack.isEmpty() || !stack.isDamageableItem()) continue;
            String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (!id.endsWith("_pickaxe")) continue;
            out.append(out.isEmpty() ? "" : "  ").append(id).append(' ')
                    .append(stack.getMaxDamage() - stack.getDamageValue()).append('/')
                    .append(stack.getMaxDamage());
        }
        return out.isEmpty() ? "no pickaxes left" : out.toString();
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
     *
     * <p><b>It no longer prints {@code mine.lastError} or {@code mine.endReason}, and that is a
     * correction rather than a trim.</b> Those two live in {@code botState().mine}, which only a
     * {@link net.magicterra.worlddriver.bot.process.MineProcess} ever writes — and this dig is not
     * one. {@code ServerWorldDriver.mine(BlockPos)} sets {@code mineTarget} and a walker goal and
     * explicitly clears {@code process}, so what the line reported was the LAST MineProcess to have
     * run, from somewhere else entirely. On the ladder run of 2026-08-12 it printed
     * {@code end=collect swept everything it could reach (broke 64/64 …)} beside a cell that had
     * never been touched, which reads as a dig that succeeded 64 times and failed once. What replaces
     * it is the geometry of THIS dig: where the bot stood, and whether that was even a cell the rung
     * hollowed — the run above ended inside the portal's own doorway and the line could not say so.
     */
    private static void noteCellDig(JourneyRig rig, String tag, BlockPos cell, Direction away) {
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
                    .append(level.getBlockState(n).isSolidRender(level, n) ? "(solid)" : "");
        }
        // WHERE THE BOT IS, in the rung's own vocabulary. A distance of 4.2 m alone cannot tell a
        // bot that stopped short in the corridor from one that tunnelled into the mould, and those
        // want opposite fixes.
        BlockPos behind = cell.relative(away.getOpposite());
        String where = forgeCorridor.contains(at) ? "inside the alcove"
                : at.equals(behind) ? "the alcove cell directly facing this cell"
                : "outside the alcove (not in any alcove cell) — most likely it dug its own way"
                  + " into the frame";
        rig.evidence("dig." + tag, String.format(java.util.Locale.ROOT,
                "%s is still %s: bot at %s (%s), %.1fm away, canBreak=%s, intended alcove stand"
                        + " %s=%s, holding %s; neighbours%s",
                cell.toShortString(), level.getBlockState(cell).getBlock(), at.toShortString(), where,
                eyes, rig.body().avatar().canBreak(cell),
                behind.toShortString(), level.getBlockState(behind).getBlock(),
                BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()), around));
    }

    private static void castOpenedCell(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                       List<BlockPos> pool, int i, BlockPos cell, BlockPos wet,
                                       Runnable then) {
        rig.evidence("opened." + i, cell.toShortString() + "=" + ctx.level().getBlockState(cell).getBlock()
                + " water cell " + wet.toShortString() + "=" + ctx.level().getBlockState(wet).getBlock());
        // Say it outright when a cell did not open. `mineCellOrGiveUp` is "dig, and carry on either
        // way" by design — which is right for an excavation and wrong here, where pouring into rock
        // is not a smaller version of pouring into a cavity. Run 20 poured water at a `wet` that was
        // still stone and the failure surfaced as `cobblestone` in the target, three inferences away
        // from the cause. Flowing lava meeting water gives cobblestone; a lava SOURCE meeting water
        // gives obsidian — so that reading also says the floor is now holding, and only the opening
        // is missing.
        if (!ctx.level().getBlockState(cell).isAir() || !ctx.level().getBlockState(wet).isAir()) {
            ctx.fail("Cell " + (i + 1) + " is about to be cast but was not opened: "
                    + cell.toShortString() + "=" + ctx.level().getBlockState(cell).getBlock()
                    + ", water cell " + wet.toShortString()
                    + "=" + ctx.level().getBlockState(wet).getBlock()
                    + " (both cells must be air; mineCellOrGiveUp continues silently when it"
                    + " cannot break a block)");
            return;
        }
        // A water bucket is what this cell is about to spend. Say so before spending the walk: the
        // recover fill one cell back is best-effort, so a bot that lost the water arrives here with
        // an empty bucket, places nothing, pours lava into a dry cell and reports "cast.missed" —
        // which reads as a casting bug and is really a fill that failed a cell ago.
        if (rig.carrying("minecraft:water_bucket") < 1) {
            ctx.fail("No water bucket in hand before casting cell " + (i + 1) + ": bucket="
                    + rig.carrying("minecraft:bucket")
                    + " water_bucket=0 lava_bucket=" + rig.carrying("minecraft:lava_bucket")
                    + " — the previous cell's recover step did not take the water back, and without"
                    + " water no obsidian can be cast");
            return;
        }
        // Water in, from the block behind it: a bucket fills the neighbour of the face its ray lands
        // on, and an air cell stops no ray. Standing level with the target keeps that ray horizontal.
        placeFluid(ctx, rig, wet, away, Items.WATER_BUCKET, "water" + i,
                watchFrame(rig, "water" + i + " placing water into " + wet.toShortString(), () -> {
            // Record where the water settled; do not fail on it. The claim is the obsidian, so let
            // the cast decide — `cast.missed.i` names any cell that did not turn.
            //
            // NOTE: this was relaxed on the theory that for the bottom pair the water falls into the
            // very cell about to be cast and lava poured there still yields obsidian. Run 17 ran all
            // ten cells on that assumption and returned `frame.cast=0/10` — so the theory is WRONG
            // and the problem is not this precondition. Nothing casts in a carved mould at all,
            // while the built arena mould casts 10/10. Keep the relaxation (the precondition was
            // never the blocker) but do not read it as evidence the geometry works.
            // ASK THE TARGET, do not guess about it. A row that claims the water most likely fell
            // into the target cell, followed by that cell's own state in brackets, was printed by
            // j48 with "now air" beside it — a row disagreeing with itself in its own parentheses.
            // The two cells are two independent readings; print both and let them say what they say.
            if (ctx.level().getFluidState(wet).isEmpty()) {
                boolean landed = !ctx.level().getFluidState(cell).isEmpty();
                rig.evidence("water.fell." + i, wet.toShortString() + " is empty; target cell "
                        + cell.toShortString() + " is now " + ctx.level().getBlockState(cell).getBlock()
                        + (landed ? " (contains fluid — the water fell into the target cell)"
                                  : " (no fluid either — both cells are empty, so this pour either"
                                    + " did not happen or flowed elsewhere)"));
            }
            BlockPos src = pool.get(Math.min(i, pool.size() - 1));
            // Reopened first, because the trip that fetched this lava is thousands of ticks long and
            // the cell was left open at the top of it. Gravel that has not finished falling by the
            // water pour has certainly finished by the time the lava comes back. (Cheap and still
            // right on a cell poured from a bucket already in the bag: the cell was opened moments
            // ago and the reopen finds nothing to do.)
            Runnable pour = () -> reopen(ctx, rig, "cast" + i + ".reopen", cell, away, REOPEN_TRIES,
                    watchFrame(rig, "cast" + i + ".reopen digging again before the pour "
                            + cell.toShortString(), () ->
                    placeFluid(ctx, rig, cell, away, Items.LAVA_BUCKET,
                    "cast" + i, () -> rig.settle(new HoldStill(3), 12, () -> {
                var got = ctx.level().getBlockState(cell).getBlock();
                // THE MOMENT THIS CELL BECAME OBSIDIAN, which is what makes every later check able
                // to say it stopped being obsidian. Recorded here rather than counted at the end
                // because the end can only say how many are left.
                if (got == Blocks.OBSIDIAN) frameCast.add(cell.immutable());
                if (got != Blocks.OBSIDIAN)
                    rig.evidence("cast.missed." + i, cell.toShortString() + " = " + got
                            + " (adjacent " + wet.toShortString() + " is "
                            + ctx.level().getBlockState(wet).getBlock() + ")");
                // Stop on the FIRST cell that will not cast. Nothing is forfeited: a frame missing
                // one cell can reach 9/10 at best, and `lightIt` fails on anything under ten — so
                // every run that would have continued was already a failing run. What it buys is the
                // clock. Run 17 spent 39 240 ticks (32 minutes) walking all ten cells to report
                // `0/10`, which is the same finding cell one had already made in about a minute, and
                // that cost is paid on every future attempt at this geometry.
                // ANY cell, not just the first. Stopping only on cell one lets cells two through ten
                // walk on, and Ladder-11 is what that costs. Cell six did not cast, `cast.missed.6`
                // said so, and the rung carried on into the water recovery — which cannot work,
                // because the lava the cast did not spend is still in the only bucket. The run died
                // on `recover6` reporting that no water_bucket could be filled, beside a ray that was
                // correct to the centimetre, and the reason string sent the next reader to the fill.
                //
                // Nothing is forfeited by stopping here either: `lightIt` needs ten of ten, so a frame
                // that has already missed one is a failing run whichever cell it was.
                if (got != Blocks.OBSIDIAN) {
                    ctx.fail("Cell " + (i + 1) + " did not cast to obsidian: " + cell.toShortString()
                            + " = " + got + " (water at " + wet.toShortString() + " = "
                            + ctx.level().getBlockState(wet).getBlock() + ") — a frame missing one"
                            + " of its ten cells cannot be lit, so the remaining cells are not"
                            + " attempted. "
                            + (i == 0 ? "The carved mould does not cast obsidian while the built"
                                        + " arena mould does: the difference is whether each cell"
                                        + " has a floor and a backing, not any single cell"
                                      : "The first " + i + " cells did cast, so this is not a defect"
                                        + " of the mould as a whole but of this cell's own stand,"
                                        + " ray or held item"));
                    return;
                }
                // The bucket is empty again, which is exactly what taking the water back needs —
                // and it is also what leaves the interior clear without a separate clean-up trip.
                // Strict, including on the last cell: water left standing in an interior cell is a
                // cell that cannot become portal, so `lightIt` would report 5/6 for a frame that is
                // actually complete.
                riseToTakeItBack(ctx, rig, wet, away, "recover" + i, () ->
                JourneyFill.fillFrom(ctx, rig, wet, "recover" + i, Items.WATER_BUCKET,
                        watchFrame(rig, "recover" + i + " taking the water back from "
                                + wet.toShortString(), () ->
                        JourneyDrain.drainTheAlcove(ctx, rig, i, JourneyDrain.legs(),
                        watchFrame(rig, "drain." + i + " waiting for the alcove to drain",
                        () -> castCell(ctx, rig, base, away, pool, i + 1, then))))));
            }))));
            // THE STAIRS ARE THE EXPENSIVE PART, so climb them only when there is nothing to pour.
            // A lava bucket does not survive the pour — it becomes obsidian and an empty bucket — so
            // this is the one fluid the rung cannot recycle the way it recycles its single water
            // source. What it CAN do is carry several at once, which turns ten commutes into
            // ceil(10 / buckets) of them. See loadBuckets for why that number needs no flag.
            int inBag = rig.carrying("minecraft:lava_bucket");
            if (inBag >= 1) {
                rig.evidence("lava" + i + ".fromBag", inBag + " lava buckets still in the inventory"
                        + " — no climb for this cell");
                pour.run();
                return;
            }
            // climb UP to the pool → fill every bucket → climb back DOWN to the mould → pour. Both
            // climbs are spelled out; neither was, and each cost a run to find. See
            // JourneyStairwell's goUpToThePool and returnToTheForge.
            JourneyStairwell.goUpToThePool(ctx, rig, src.getY(), "lava" + i,
                    watchFrame(rig, "lava" + i + " climbing the stairs to the lava pool", () ->
                    JourneyFill.loadBuckets(ctx, rig, src, "lava" + i,
                    watchFrame(rig, "lava" + i + " filling buckets at the pool", () ->
                    JourneyStairwell.returnToTheForge(ctx, rig, base.getY(), "cast" + i,
                    watchFrame(rig, "cast" + i + " descending the stairs back to the mould", pour))))));
        }));
    }

    /** Stand level with {@code target} and empty the held bucket into it, aiming at the solid block
     *  behind it. Level, because a steep ray enters the face a block low and lands in the wrong cell. */
    private static void placeFluid(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                   net.minecraft.world.item.Item held, String tag, Runnable then) {
        mendBacking(ctx, rig, target, away, tag, () ->
                JourneyPour.standLevelWith(ctx, rig, target, away, tag,
                        () -> placeFluid(ctx, rig, target, away, held, tag, JourneyPour.POUR_APPROACHES, then)));
    }

    /**
     * Put back the block this pour is about to aim at, when the digging has taken it out.
     *
     * <p>The mould is declared sound once, right after the carve, and the {@code forge.backings} row
     * reporting all fourteen backings solid is that declaration. Nothing re-asked it, and by the
     * ninth cast of the run of 2026-08-13 two backings were air — {@code -9,59,39} and
     * {@code -9,60,39}, read out of the saved world, both behind the column whose frame cells are dug
     * by a bot that pillars up into the doorway. Every bucket in this rung is aimed at the block BEHIND the cell it fills, so an air
     * backing is not a leak, it is an aim with nothing to stop it: {@code cast8.stand} rejected both
     * candidates because -9,60,39 was not solid and so could not put the fluid in front of it, fell
     * back to a merely standable cell, and
     * {@code cast8.picks} measured the ray reaching {@code -9,60,40} and dropping the lava into
     * {@code -9,60,39} — a cell BEHIND the frame.
     *
     * <p><b>It does not happen every run, which is exactly why it is worth mending rather than
     * hunting.</b> The next rehearsal on the same seed and the same geometry reached
     * {@code cast8.picks=-9,60,39 Block{minecraft:granite}} — the backing untouched — and cast all ten
     * without needing this at all. A cell that survives three runs in four is not a cell to reason
     * about from one sample; it is a cell to re-read before aiming at it.
     *
     * <p><b>Arm's length or nothing</b>, for the reason {@link JourneyStairs#mend} spells out: {@code placeOn}
     * goes straight to {@code gameMode.useItemOn}, which has no reach gate on this avatar, so without
     * the check a wall could be rebuilt through ten blocks of rock and read as a repair that worked.
     * Out of reach is reported and the pour's own ray gate still refuses to spend the bucket.
     */
    private static void mendBacking(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                    String tag, Runnable then) {
        ServerLevel level = ctx.level();
        BlockPos backing = target.relative(away);
        if (level.getBlockState(backing).isSolidRender(level, backing)) { then.run(); return; }
        BlockPos here = rig.player().blockPosition();
        double reach = Math.sqrt(here.distSqr(backing));
        String was = String.valueOf(level.getBlockState(backing).getBlock());
        if (reach > JourneyStairs.MEND_REACH) {
            rig.evidence(tag + ".backingGone", backing.toShortString() + "=" + was
                    + " is not solid (behind " + target.toShortString() + "); bot at "
                    + here.toShortString() + " is " + Math.round(reach) + " blocks away, out of reach"
                    + " to replace it — this bucket will pass through and land one cell further on");
            then.run();
            return;
        }
        // Both players (client and server-side) — `placeInto` places through the server. See
        // JourneyHands.holdBoth.
        boolean held = JourneyHands.holdBoth(rig, Items.COBBLESTONE);
        if (held) JourneyStairs.placeInto(level, rig, backing);
        // THE WORLD, not the call. Same reason the step and the stair mend read it back: a placement
        // can be refused for reasons the caller cannot see, and a backing that was never rebuilt
        // leaves exactly the "the cast just did not work" row this rung has been misled by twice.
        boolean solid = level.getBlockState(backing).isSolidRender(level, backing);
        rig.evidence(tag + ".backingMend", backing.toShortString() + " backing was " + was
                + " (behind " + target.toShortString() + ", opened while digging the frame) → "
                + (solid ? "replaced (" + level.getBlockState(backing).getBlock() + ")"
                         : (held ? "could not be replaced (now "
                                   + level.getBlockState(backing).getBlock() + ")"
                                 : "no cobblestone in hand")));
        then.run();
    }

    /**
     * Put the eye on a COLUMN the water can be seen from, before going to take it back.
     *
     * <p>A cast pours water into {@code wet} from a row {@link JourneyPour#standLevelWith} verified, then
     * fetches lava and pours THAT into the cell below — and the pour's own walk is free to drop the
     * bot to whatever cell has a floor, which in a hollow alcove is seven rows down. From there the
     * line to the water goes straight through the obsidian that was just cast into the cell between
     * them, and the fill's answer to a blocked line used to be to mine the blocker: measured,
     * {@code recover9.clearedLine.3} reported obsidian at -10,60,38 standing between the eye and
     * -10,61,38, and broke it. {@link JourneyFill} no longer does that; this is the other half, which is giving
     * it a line that is not blocked in the first place.
     *
     * <p><b>Only when the bot cannot already see water</b>, and that is a measurement rather than a
     * geometry rule. The same {@code SOURCE_ONLY} clip the bucket runs is asked first, so on every
     * cell whose recover already works this is a no-op and cannot perturb it — which matters,
     * because a single-bucket rehearsal casts all ten today and the top pair is the only geometry
     * where the frame HAS to stand between a floor-level eye and its own water.
     *
     * <p>It raises through {@link JourneyPour#raiseTo} and <b>not</b> through {@link JourneyPour#standLevelWith}, and that
     * distinction cost a run's worth of confusion on its own: {@code standLevelWith}'s gate is
     * {@code standToPour}, so it answered "a pour spot exists" to a question about a scoop and
     * skipped the raise, leaving a {@code recover8.rise} row above a bot that never moved.
     *
     * <h2>A height is not a column</h2>
     *
     * <p>This used to hold a second gate — {@code if (here.getY() >= wantY) return;} — and that gate
     * is what lost the real ladder of 2026-08-16 on its sixth cell. The archived run says so without
     * needing another one: {@code recover0..5} each printed {@code .fromHere}, the row
     * {@link JourneyFill#fillFrom} prints when the identical {@code SOURCE_ONLY} clip finds a source
     * in reach, and {@code recover6} printed {@code .spot} instead — the not-in-reach branch — from a
     * call made in the same tick, through {@code then.run()}, with nothing in between that could move
     * the bot. So the clip above answered <i>null</i> for cell six, the raise was skipped anyway, and
     * the only remaining exit is the height one. No {@code .rise} row exists in that run at all.
     *
     * <p>What the height gate could not see is that the bot was in the WRONG COLUMN. Cell six casts
     * {@code 4,59,18} and its water sits in the interior cell beside it, {@code 4,59,19}; the pour's
     * own flight left the bot at {@code 3,58,18} — {@code wantY} exactly, one column north of the
     * water — and from there the line to the water is a DIAGONAL that has to squeeze past the cell
     * the cast has just turned to obsidian. It does not:
     * {@code recover6.aimsAt} recorded obsidian at 4,59,18, not a source block, while aiming for
     * 4,59,19; and {@code standToFill} refuted the very same cell from its centre, counting one ray
     * stopped by obsidian — so this is not an artefact of where in its cell
     * the bot happened to be standing.
     *
     * <p>The column that works is the one directly behind the water, {@code 3,·,19}: from there the
     * ray is axis-aligned and cannot clip a neighbour. {@code standToFill} cannot offer it, because it
     * only returns cells that ALREADY have a floor and {@code 3,57,19} is air — but {@link JourneyPour#raiseTo}
     * can, because {@link JourneyPour#raiseColumn} asks the scoop's own clip without asking for a floor and
     * {@link JourneyRamp} then builds one. Height is therefore never again an answer to a question
     * about sightline: when the clip above says no water is visible, the raise runs, and it runs for
     * its column whether or not the row is already right.
     */
    private static void riseToTakeItBack(SceneContext ctx, JourneyRig rig, BlockPos wet,
                                         Direction away, String tag, Runnable then) {
        // ASKED WHERE THE BOT IS, mid-air or not, and that is not an oversight. This clip decides a
        // NO-OP, so a wrong "visible" answer costs the fill one aim that {@link JourneyFill#scoop}
        // re-takes from the far side of its own settle. Holding the bot still first was tried on
        // 2026-08-16 and cost far more than it saved: a ten-tick settle here and in the fill gave
        // `recover8` eight extra ticks of falling (eye y 61.65→58.06), after which the water was
        // genuinely out of sight, the fill walked, and the walk mined a cast frame cell to get back
        // up — `frame.lost.1` dated the loss to the step that took the water back from -9, 61, 38
        // in `recover8`. See HoldStill.
        if (JourneyFill.visibleSourceNear(rig, false, JourneyFill.FILL_RESEARCH) != null) {
            then.run();
            return;
        }
        BlockPos here = rig.player().blockPosition();
        int wantY = wet.getY() - 1;
        // WHICH OF THE TWO STATES, named in the row itself. "Cannot see" covers a bot that is too low and
        // a bot that is high enough and beside the wrong column, and those are different repairs —
        // the first wants a flight, the second wants one step sideways. A row naming only the first
        // would leave the wrong-column case with no row at all.
        rig.evidence(tag + ".rise", here.toShortString() + " cannot see the water in "
                + wet.toShortString() + " (feet at y=" + here.getY() + ", wanted row y=" + wantY + ", "
                + (here.getY() >= wantY
                        ? "the height is already sufficient — the column is wrong: from this column"
                          + " the ray would have to cross the freshly cast frame diagonally"
                        : (wantY - here.getY()) + " rows short, with the freshly cast frame in"
                          + " between")
                + ") — moving to a column with a view of the water before taking it back");
        JourneyPour.raiseTo(ctx, rig, wet, away, wantY, false, tag + ".rise", then);
    }

    private static void placeFluid(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                   net.minecraft.world.item.Item held, String tag, int tries,
                                   Runnable then) {
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        // EVERY ROW BELOW CARRIES ITS APPROACH NUMBER, for the reason the climb rows now carry their
        // caller. Three approaches wrote one set of keys and the last writer won, so the results file
        // showed `cast9.fromHere` from approach three beside `cast9.stand` and `cast9.picks` from
        // approach one — the bot at -9,56,36 in one row and a walk from -9,56,37 in the next, with
        // nothing saying they were different attempts. That cost a reading on 2026-08-17: it looked
        // like the short-circuit had fired and the walk had happened anyway.
        // IF IT CAN BE DONE FROM HERE, DO IT FROM HERE — before choosing anywhere to walk to.
        //
        // Otherwise the walk undoes the work that made the pour possible. Run 42's last cell:
        // `cast9.lift=-9,56,36 → y=59`, `cast9.liftedY=59/59`, the bot up its own pillar exactly
        // level with the cell — and then the retry chose a stand, walked to it, and reported
        // the bot at -10,57,35, two rows below the row it had just built to reach. The fill has had
        // this short-circuit since run 36 for the same reason; this is it on the pour side.
        JourneyPour.PourSpot spot = null;
        BlockPos already = JourneyPour.aimThatLandsIn(ctx.level(), rig, target, away, tag + "." + tries);
        if (already != null) {
            rig.evidence(tag + ".fromHere." + tries, rig.player().blockPosition().toShortString()
                    + " aiming in place at " + already.toShortString() + "; the fluid will land in "
                    + target.toShortString() + " (no walk); " + JourneyFill.eyeNow(rig));
            spot = new JourneyPour.PourSpot(rig.player().blockPosition(), already);
        }
        if (spot == null) spot = JourneyPour.standToPour(ctx.level(), rig.player(), target, away, why);
        if (spot == null) {
            ctx.fail("No stand in the mould can pour into " + target.toShortString()
                    + ": a stand needs a solid block under the feet, two clear cells overhead, and a"
                    + " ray that lands on the near face of the backing "
                    + target.relative(away).toShortString() + ", the top face of the floor "
                    + target.below().toShortString()
                    + ", or the facing side of a same-row side neighbour "
                    + target.relative(away.getClockWise()).toShortString()
                    + "/" + target.relative(away.getCounterClockWise()).toShortString()
                    + " — bot at " + rig.player().blockPosition()
                    + "; rejection counts by reason: " + why);
            return;
        }
        BlockPos goal = spot.stand();
        BlockPos backing = spot.aim();
        rig.evidence(tag + ".stand." + tries, goal.toShortString() + " aiming at " + backing.toShortString()
                + (backing.equals(target.below()) ? " (top face of the floor)" : " (near face of the backing)")
                + " rejection counts " + why);
        // NoBreak, like the other three walks in this family: {@code JourneyStairwell.walkTheStairs}
        // ("The walk may not dig"), {@code JourneyFill}'s water fetch and {@code JourneyRamp#walkTo}
        // all forbid digging for one reason: inside the alcove there is nothing between the bot
        // and its destination that this rung did not cut itself, so a dig is never the answer and
        // is always the rung eating its own work. Without it, the rehearsal of 2026-08-25 recorded
        // nine consecutive `stairsBroken` rows reporting all 11 steps intact, then `lava8`
        // reporting three treads whose supports had become air — `-3,60,20`, `-1,58,20`,
        // `0,57,20`, one row under the flight and one apart in x, which is the flight's own
        // diagonal. The log names the actor: the bot stood at `-1,56,21` digging `-1,57,20` then
        // `-1,58,20`, the feet and head cells of its next step, under `goal=Block[3,60,20]` — this
        // walk. It never arrived (it stopped at -2, 61, 20, five short), so the digging bought
        // nothing and the rung then died unable to climb the stairs.
        rig.settle(new IntentProcess(new Intent(new Goal.Block(goal), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), 1_200, () -> {
            JourneyHands.holdForUse(rig, held, tag);
            // RE-ASK FROM WHERE THE BOT ACTUALLY ENDED UP, as the fill does; it is the same bug on
            // the other side of the trip: the stand
            // and the aim are chosen together, so a walk that ends one cell off leaves the aim
            // answering a question about a position the bot is no longer in. Measured, run 39 cell one —
            // `water1.stand` chose -9,56,37 aiming at the near face of the backing -10,57,39 and, an
            // instant later, `water1.picks` had the bot at -9,57,37 with the fluid landing in
            // -9,58,37: the bot floated a block up between
            // choosing and pouring, and from there the backing is the wrong thing to aim at while
            // the target's floor would still have worked. Both are clipped from the real eye here,
            // so whichever one lands in the target is the one used.
            // …and the post-walk ask is tagged apart from the pre-walk one, because the whole point of
            // asking twice is that the bot is somewhere else now.
            BlockPos aimNow = JourneyPour.aimThatLandsIn(ctx.level(), rig, target, away,
                    tag + "." + tries + ".walked");
            if (aimNow != null && !aimNow.equals(backing))
                rig.evidence(tag + ".reaimed." + tries, backing.toShortString() + " → " + aimNow.toShortString()
                        + " (after the walk the bot is at " + rig.player().blockPosition().toShortString()
                        + ")");
            BlockPos planned = aimNow != null ? aimNow : backing;
            // Clear a plant off the line first. This rung's lake is at y=63 — on the SURFACE — so
            // unlike the underground forge it is standing in grass, and grass is REPLACEABLE: the
            // pour would not miss, it would succeed into the grass cell and be read as "no obsidian
            // here". Same swing the obsidian rung uses, and for the same reason mine cannot do it.
            clearPlantOnLine(ctx, rig, planned, tag, () -> rig.settle(new HoldStill(2), 10, () -> {
                // SETTLE FIRST, THEN AIM, THEN PREDICT AND USE — all from one eye. The order was the
                // other way round here long after `JourneyFill.scoop` was fixed for exactly this, and
                // the pour is where it still cost cells. `aimAtBlock` stores an ANGLE computed from
                // wherever the eye was; these two ticks are the ticks a player falls in.
                //
                // Measured, single-bucket rehearsal 2026-08-17, cell ten, approach three:
                // `cast9.fromHere.3` aimed in place from -9,57,36 at -10,60,39 with the fluid due to
                // land in -10,60,38, and then `cast9.picks.3` had the bot at -9,56,36 — a WHOLE BLOCK
                // of eye height between the
                // decision and the shot, and the ray duly entered the frame's plane one row low.
                // Approach two then repeated it inside one cell: same block position both times, the
                // bot floating in the alcove's own water, and the two rays still disagreed — sub-cell
                // motion, which is why the eye is now printed to the centimetre on both rows.
                //
                // So the aim is decided here, after the last settle, by the same closed loop
                // `aimThatLandsIn` runs — and what it returns is what fires.
                ServerLevel lvl = ctx.level();
                BlockPos settled = JourneyPour.aimThatLandsIn(ctx.level(), rig, target, away,
                        tag + "." + tries + ".settled");
                BlockPos at = settled != null ? settled : planned;
                // BOTH players: the gate below rays the CLIENT one, but the server's aim is owed to
                // every other server-side predicate this rung runs (see JourneyHands.aimBoth), and
                // the stakes are the same, because this gate's failure branch runs clearPourLine,
                // which mines.
                JourneyHands.aimBoth(rig, at);
                // Where the fluid is actually going to land, recorded BEFORE it is spent. A filled
                // bucket clips with `Fluid.NONE` and empties into the cell in front of the face it
                // hits, so this pick IS the destination — and without it a pour that succeeded into
                // the wrong cell is indistinguishable from a pour that did not work, which is the
                // shape of the last three rounds of this rung's investigation. `pourInto` has had
                // this instrument for a while; the ten casts that matter never did.
                // THE CLIENT'S RAY, because the client player is the one that fires. This read
                // `aimedAt(rig.player(), …)` — the SERVER's — until ladder5, and rung 12 cell 4 is what
                // that cost: the two players stood 0.06 blocks apart, the two rays picked different faces
                // (client `4,56,21 west`, server `4,56,22 up`), the gate cleared the server's, and the
                // cell check afterwards read `air`. The outcome names the client's line — the server's
                // would have dropped lava into `4,57,22` beside the water source at `4,57,21` and made
                // obsidian, which is how cells 1–3 were won. See JourneyHands#aimBoth for why the
                // server's eye cannot be the one predicted from: it is client-authoritative too, so
                // the snapshot read here is stale by the time the use packet is handled.
                //
                // On a dedicated server `rig.avatar()` IS `rig.body().avatar()` (JourneyRig#avatar), so
                // this argument is the same fake player the old line passed and the gate is unchanged
                // there — the six-topology gate is what proves that, not this comment.
                var hit = JourneyHands.aimedAt(rig.avatar().asPlayer(),
                        JourneyFill.BUCKET_REACH, false);
                BlockPos lands = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos().relative(hit.getDirection()) : null;
                rig.evidence(tag + ".picks." + tries, (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos().toShortString() + " " + lvl.getBlockState(hit.getBlockPos()).getBlock()
                          + " face=" + hit.getDirection() + " → lands in " + lands.toShortString()
                        : String.valueOf(hit.getType()))
                        + " (pouring into " + target.toShortString() + ", aiming at " + at.toShortString()
                        + "=" + lvl.getBlockState(at).getBlock()
                        + ", bot at " + rig.player().blockPosition().toShortString()
                        + ", " + JourneyFill.eyeNow(rig) + ")");
                rig.evidence(tag + ".before." + tries, target.toShortString() + "="
                        + lvl.getBlockState(target).getBlock());
                // Do not spend the bucket unless the ray lands where the plan says. This is the same
                // clip vanilla is about to do, so it is a PREDICTION and not a heuristic — which is
                // why it replaced a distance test: "within arm's length of the backing's centre" was
                // the first guard here and it rejected a pour at 4.4 m that would have worked, three
                // times, from a bot that never moved between attempts. What actually decides the
                // outcome is which cell the fluid lands in, and that is knowable exactly.
                if (lands == null || !lands.equals(target)) {
                    if (tries > 1) {
                        // Clear the line before asking again, because asking again on its own is a
                        // retry that changes nothing: standToPour is deterministic in the world it
                        // reads, so three approaches from a bot that only moved a block or two get
                        // three identical answers. What changes is the world — and the thing in the
                        // way is a block in a corridor the rung hollowed out itself.
                        clearPourLine(ctx, rig, target, away, tag + ".clear" + tries,
                                () -> JourneyPour.liftInPlace(ctx, rig, target, away, tag, tries,
                                () -> placeFluid(ctx, rig, target, away, held, tag, tries - 1, then)));
                        return;
                    }
                    // THE BLOCK THAT WAS ACTUALLY AIMED AT, not the one chosen before the walk. Those
                    // differ whenever the settled re-ask moved the aim, and quoting the stale one
                    // sends the reader to a geometry that was never fired.
                    //
                    // WHERE THE BOT IS RELATIVE TO THE STAND IT CHOSE, first, because that is the
                    // answer in every run this verdict has been read in and it was the one thing the
                    // verdict did not say. It used to open with the pour line, and a pour line is a
                    // list of what is in the way — so a reader who trusts it goes looking for who put
                    // a block there. Rung 12 of the 2026-08-27 ladder cost a full round exactly that
                    // way: the line named dirt at `1,60,20`, the dirt turned out to be native terrain,
                    // and the actual story was that the bot stood at `0,59,20` while the stand it had
                    // picked was `3,59,20` — three cells and two rows away, so no geometry computed at
                    // the stand described the shot that was fired. The line stays, at the end, where a
                    // secondary reading belongs.
                    boolean onItsStand = rig.player().blockPosition().equals(goal);
                    ctx.fail("Cannot pour into the intended cell " + target.toShortString()
                            + (onItsStand ? "" : " — the bot is not on the stand it chose: it chose "
                                    + goal.toShortString() + " but is standing at "
                                    + rig.player().blockPosition().toShortString()
                                    + ", " + String.format(java.util.Locale.ROOT, "%.2f",
                                            Math.sqrt(rig.player().blockPosition().distSqr(goal)))
                                    + " blocks off. The ray was verified from the chosen stand, not"
                                    + " from the cell the bot is in")
                            + " (aiming at " + at.toShortString()
                            + (at.equals(backing) ? "" : "; the aim when the stand was chosen was "
                                    + backing.toShortString())
                            + "); the ray would put the fluid into "
                            + (lands == null ? String.valueOf(hit.getType()) : lands.toShortString())
                            + ", bot at " + rig.player().blockPosition()
                            + "; the pour line holds " + pourLine(lvl, target, away)
                            + " — not poured; a pour would still report CONSUME, and this rung"
                            + " would then record the failure as 'no obsidian cast'");
                    return;
                }
                // WHAT THE BUCKET BECAME, not what the use returned. `result` was never able to
                // answer this — the comment eight lines up already says a pour that goes nowhere
                // still reports CONSUME — and on the client topology it is worse than uninformative:
                // the use runs on the client and every reading of it is taken from the server, so
                // judging in the use's own tick reads a world the packet has not reached.
                //
                // Measured, rung 12's client rehearsal 2026-08-22, and it took a purpose-built row
                // to see at all. The pour reported `water0.result=SUCCESS`, `water.fell.0` said the
                // wet cell was empty, and the rung walked on — then died two steps later on
                // `lava0.hand#2`, which could not find an empty bucket: its bucket stock read empty=0,
                // water=1, lava=0. THE BUCKET WAS
                // STILL FULL. Nothing had been poured; three separate rows had said otherwise, and
                // `water.fell`'s own wording (that the water had most likely fallen into the target
                // cell) shows it was inferring, not measuring — its test is
                // `getFluidState(wet).isEmpty()`, which cannot tell "the water flowed away" from "the
                // water was never placed".
                //
                // A spend is a state change of one object: bucket → water_bucket → bucket. Measure
                // that and none of the three ambiguities above can survive.
                //
                // IT IS ENFORCED, and the `.spent` gate at the end of this method is what fails the
                // rung. The check that fires first on an empty-handed cast is `castOpenedCell`'s
                // "no water bucket in hand before casting", and `cast.missed` fires after it; see the
                // `.spent` gate for why limping on was the more expensive option.
                java.util.function.Supplier<Integer> stock = () -> rig.carrying(
                        BuiltInRegistries.ITEM.getKey(held).toString());
                int before = stock.get();
                // Both players, at the instant of the use — the only moment at which the two halves
                // of a use can be compared. Everything else this rung records is one player at one
                // moment: `.picks` is the SERVER's ray (and it was right all along), `.result` is
                // the CLIENT's own return value, `.spent` is the SERVER after a round trip. The
                // question they could not answer between them is what the SERVER was holding when
                // the packet landed, which is what `holdBoth` now sets and this row now checks.
                // THE HAND, RE-ASSERTED AFTER THE LAST SETTLE — for the same reason the aim is.
                // `holdForUse` ran forty lines and ten ticks ago, upstream of the plant clearing and
                // of the settle, and ladder-11 cell six is the run where that gap mattered: both
                // players read `lava_bucket` at the hold and both read `dirt` at the use. See
                // JourneyHands.actingHolds for the mechanism (the raise's tower holds dirt, which
                // pushes the bucket out of the hotbar and turns the next hold into a two-author swap).
                //
                // Re-hold rather than fail outright: a slot that drifted back is exactly the case a
                // second hold fixes, and the row below says it happened either way. A run with no
                // `.handSlipped` row never had the problem — three states, not two.
                // AND SILENCE THE OTHER AUTHOR WHILE THE POUR HAPPENS — the same guard the obsidian
                // rung's pour carries, for the finding that closed it: re-gripping is as close to
                // the use as a caller can get, and ladder j46 measured the swap landing INSIDE the
                // tick the server processed the use (`handTrace.t0.server` lava_bucket at
                // gameTime=28476, `t1.server` cobblestone ×29 at 28477, `inv.selected` never
                // moving). The swap is `BotInteract.ensureHoldingPillarBlock`'s main-inventory tail;
                // its call sites all short-circuit on `BotConfig.allowPlace` first, so the flag is
                // what makes it unreachable rather than merely unlikely. The comment above already
                // named the tower's hold as the displacer — this is what stops it, rather than
                // re-taking the bucket after it has struck.
                boolean placeWas = BotConfig.allowPlace;
                BotConfig.allowPlace = false;
                ctx.cleanup(() -> BotConfig.allowPlace = placeWas);
                rig.evidence(tag + ".placeHeldOff", "placement disabled for the duration of the pour"
                        + " (previous value " + placeWas + ")");
                boolean gripped = JourneyHands.regripBeforeUse(rig, held, tag);
                JourneyHands.handsAtUse(rig, tag);
                // AND DO NOT SPEND A USE THAT CANNOT WORK. A bucket-less `useItemInHand` returns PASS
                // and changes nothing, which is byte-identical to a ray that missed — cell six spent
                // one on a stack of dirt, reported `cast6.result=PASS`, walked on, and died six steps
                // later on `recover6.hand`, which could not find an empty bucket (bucket stock
                // empty=0, water=0, lava=1) — a message about the wrong step entirely.
                if (!gripped) {
                    BotConfig.allowPlace = placeWas;
                    ctx.fail("The hand about to pour is not holding " + BuiltInRegistries.ITEM.getKey(held)
                            + ", and one re-grip did not fix it: " + JourneyHands.heldOnBoth(rig)
                            + "; " + JourneyHands.bucketStock(rig)
                            + " — not pouring. The use would only return PASS, and this rung would"
                            + " then record the failure as 'no obsidian cast' or, later, as 'could"
                            + " not fill water'");
                    return;
                }
                // THE CALIBRATION ROW, taken BEFORE the use, in the watcher's own format. It reads the
                // same instant `.atUse` does — nothing between the two lines ticks the server — so two
                // rows that disagree mean the INSTRUMENT is broken and nothing below may be read as a
                // fact about the world. See JourneyHands#handTrace; rung 11's pour has carried this
                // since j43b and rung 12's, the one that actually keeps failing, never had it.
                // AND ASK WHERE IT LANDS ONE MORE TIME, HERE, with nothing but the use after it.
                //
                // <b>This is an invariant assertion, not a fix — say so, because the obvious reading is
                // wrong.</b> "The gate is a hundred lines up, so the bot has moved since" does NOT hold
                // on the normal path: `regripBeforeUse` opens with `if (actingHolds(…)) return true`,
                // and everything else between the two points reads fields or writes evidence rows.
                // Nothing there ticks the server — which is exactly what `handTrace`'s own contract
                // asserts three lines below. Cell 4 of ladder5 was lost to the gate reading the wrong
                // ENTITY, not to it reading at the wrong MOMENT, and the entity swap above is the fix.
                //
                // So this row earns its place two ways and neither is "the bot sank": it covers the
                // `handSlipped` branch, which DOES settle and therefore ticks, and it turns "something
                // between the gate and the use moved the bot" from an assumption into a measurement.
                // If `.atUseGate` ever fires with `.handSlipped` absent, the no-tick claim above is
                // false and every reading between them has to be re-dated. It costs no tick to ask.
                //
                // Re-aim, then re-ask, and no retry branch: `clearPourLine`/`liftInPlace` both need
                // `BotConfig.allowPlace`, which is off from here down, so a repair launched from here
                // could not place. Naming the failure beats running a fix that cannot work.
                JourneyHands.aimBoth(rig, at);
                var atUseHit = JourneyHands.aimedAt(rig.avatar().asPlayer(), JourneyFill.BUCKET_REACH, false);
                BlockPos atUseLands = atUseHit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? atUseHit.getBlockPos().relative(atUseHit.getDirection()) : null;
                rig.evidence(tag + ".atUseGate." + tries,
                        (atUseHit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                                ? atUseHit.getBlockPos().toShortString() + " face=" + atUseHit.getDirection()
                                  + " → lands in " + atUseLands.toShortString()
                                : String.valueOf(atUseHit.getType()))
                        + " (asked again after re-aiming at " + at.toShortString()
                        + " immediately before the use; pouring into "
                        + target.toShortString() + ", bot at " + rig.player().blockPosition().toShortString()
                        + ", " + JourneyFill.eyeNow(rig) + ")");
                if (atUseLands == null || !atUseLands.equals(target)) {
                    BotConfig.allowPlace = placeWas;
                    ctx.fail("The ray drifted at the last moment before the pour: pouring into "
                            + target.toShortString() + ", but after re-aiming at " + at.toShortString()
                            + " immediately before the use the ray lands in "
                            + (atUseLands == null ? String.valueOf(atUseHit.getType())
                                                  : atUseLands.toShortString())
                            + ", bot at " + rig.player().blockPosition()
                            + " — the earlier gate passed, and no tick should run between the two"
                            + " points: check this rung for a `.handSlipped` row (present = the"
                            + " settle after re-gripping the bucket moved the bot; absent = something"
                            + " else ticked the server in between, so the timing of every reading"
                            + " between the two points must be re-established). Not poured: a pour"
                            + " would still report CONSUME, and this rung would then record the"
                            + " failure as 'no obsidian cast'");
                    return;
                }
                JourneyHands.handTrace(rig, tag, -1);
                rig.evidence(tag + ".result", String.valueOf(rig.hands().useItemInHand()));
                // THE HAND ON CONSECUTIVE SERVER TICKS. `.result` is the CLIENT's prediction and
                // `.spent` is the SERVER after the wait; between them sits the tick that decides this
                // cell — the one where the server processes the use packet and reads its OWN
                // `inventory.selected`. Nothing in this rung has ever sampled that, so "the hand was
                // right at the send and wrong at the handling" was indistinguishable from a refusal,
                // and j48 spent its whole rung-12 budget on that ambiguity.
                //
                // Ten ticks rather than the three this settle used to wait, and ten because that is
                // what rung 11's pour already waits — the same window, not a tighter one invented
                // here. TRACE_TICKS is 6 because the answer "the other author is merely slower" lives
                // on use+5, so the settle has to outlast the trace or the last sample never happens.
                // The longer wait is also strictly safer for the round trip `.spent` claims to have
                // waited out, and that row's own wording moves with the constant.
                int[] traced = {0};
                rig.settle(new HoldStill(POUR_SETTLE), 20, () -> {
                    if (traced[0] < JourneyHands.TRACE_TICKS) JourneyHands.handTrace(rig, tag, traced[0]++);
                }, () -> {
                    // The pour is over; everything downstream — the lift, the walk home — pillars.
                    BotConfig.allowPlace = placeWas;
                    // HOW MANY TICKS THE INSTRUMENT SAW, so silence can be read. Missing entirely ⇒
                    // the run never reached this pour (not triggered, evidence for neither side);
                    // present with 0 ⇒ the settle was skipped and the instrument never fired, so its
                    // silence is also not evidence; present with 6 ⇒ the trace rows are the answer.
                    rig.evidence(tag + ".handTrace.samples", "sampled " + traced[0] + "/"
                            + JourneyHands.TRACE_TICKS + " server ticks. t0 falls on the same server"
                            + " tick as useItemInHand — go by each row's gameTime, not by the tick"
                            + " index. t-1 is the calibration row taken before the packet is sent: it"
                            + " must agree with " + tag + ".atUse, and a disagreement means the"
                            + " instrument is broken.");
                    int after = stock.get();
                    rig.evidence(tag + ".spent", after < before
                            ? BuiltInRegistries.ITEM.getKey(held) + " " + before + "→" + after
                              + " (poured)"
                            : BuiltInRegistries.ITEM.getKey(held) + " " + before + "→" + after
                              + ", still not consumed after a " + POUR_SETTLE
                              + "-tick round trip — the bucket is still full; this pour did not happen");
                    // AND STOP, because everything downstream assumes the bucket is now empty.
                    //
                    // This row can report that the pour did not happen, and nothing downstream read
                    // it. Ladder j48 measured what that costs: `water6.spent =
                    // water_bucket 1→1` and the rung walked on to fetch lava with its only bucket
                    // still full of water, spent the next step re-aiming three times and clearing
                    // three sightlines while holding a stone pickaxe, and died unable to fill a
                    // lava_bucket. Six steps downstream, about the wrong one.
                    //
                    // A result message that names this step is worth more than a run that limps: the
                    // cell is uncast either way, and the ONLY difference is whether the reader is sent
                    // to the step that failed or to the one that inherited it.
                    if (after >= before) {
                        ctx.fail("This pour did not happen: "
                                + BuiltInRegistries.ITEM.getKey(held) + " " + before + "→" + after
                                + ", still not consumed after a " + POUR_SETTLE
                                + "-tick round trip — the bucket is still full. The client reported "
                                + rig.evidenceOf(tag + ".result")
                                + " and the server consumed nothing; the two numbers come from the"
                                + " two sides. Hand and aim for this pour: " + rig.evidenceOf(tag + ".atUse")
                                + ". To decide between 'the server's hand was already wrong when it"
                                + " handled the packet' and 'both sides held the bucket and the pour"
                                + " was refused', read the " + tag + ".handTrace.t*.server rows"
                                + " (check " + tag + ".handTrace.samples first to confirm the"
                                + " instrument fired) — " + tag + ".atUse only answers for the moment"
                                + " the packet was sent");
                        return;
                    }
                    then.run();
                });
            }));
        });
    }

    /**
     * How long a pour waits before judging whether its bucket emptied.
     *
     * <p>Ten, copied from rung 11's {@code pourInto} rather than picked here: the two pours ask the
     * same question of the same round trip, and inventing a second number would make "after an
     * N-tick round trip" mean two things in one results file. It is not three, because
     * {@link JourneyHands#TRACE_TICKS} samples six consecutive server ticks after the use, and a
     * settle that ends on tick three cannot produce the sixth sample.
     *
     * <p>Every message that quotes the wait quotes THIS constant. A row claiming a 3-tick wait beside
     * a settle that waited ten is the kind of stale literal that gets read as a measurement.
     */
    private static final int POUR_SETTLE = 10;


    /** How far back along its own line a pour may look. Three, which is one more than the usual
     *  {@code push} and one less than {@code standToPour}'s reach — far enough to cover the cells a
     *  bot standing in the corridor sees through, short of the alcove's back wall. */
    static final int POUR_LINE = 3;

    /** The cells the ray goes through on its way to the backing, and what is standing in them.
     *
     *  <p><b>Four rows</b> ({@code dy} −1..+2), the same window {@link #clearPourLine} clears and
     *  for the same reason — this row has to name every cell that method could be asked about, or a
     *  blocker at {@code dy=+2} shows up as a clear line here and an unexplained refusal there. One
     *  below the target is where the bot's feet go, the target's own is where the ray travels, and
     *  TWO above because the cast's own water floats the bot a block higher by the third cell.
     *
     *  <p>Naming which cell is not clear is the difference between "the pour does not work" and
     *  "there is a cobblestone at -9,52,22". */
    private static String pourLine(ServerLevel level, BlockPos target, Direction away) {
        StringBuilder out = new StringBuilder();
        for (int k = 1; k <= POUR_LINE; k++)
            for (int dy = -1; dy <= 2; dy++) {
                BlockPos c = target.relative(away.getOpposite(), k).above(dy);
                if (level.getBlockState(c).isAir()) continue;
                out.append(out.isEmpty() ? "" : " ").append(c.toShortString()).append('=')
                        .append(level.getBlockState(c).getBlock())
                        .append(forgeCorridor.contains(c) ? "(inside alcove)" : "(outside alcove)");
                // SOURCE OR FLOW, because "the pour line is flooded" and "something upstream is
                // still feeding it" are different problems with opposite remedies: a flow with no
                // source retreats on its own and the pour only has to wait, while a source has to be
                // taken back before anything downstream can stand. The same distinction
                // `JourneyStairs.report` prints for the stair foot, and the reading rung 12's cell
                // eight has been missing — its alcove is water at pour time and no row says whose.
                var fl = level.getFluidState(c);
                if (!fl.isEmpty())
                    out.append(fl.isSource() ? "(source)" : "(flow level=" + fl.getAmount() + ")");
            }
        return out.isEmpty() ? "all air" : out.toString();
    }

    /**
     * Mine whatever is standing in the pour's line, but only inside the alcove.
     *
     * <p><b>Four rows, not two.</b> One below the target (the cell the bot's feet go in), the
     * target's own (where the ray travels), and TWO above — because by the third cell the corridor is
     * flooded by the cast's own water and the bot floats a block higher, so the cell its head
     * occupies is two above its feet, not one. Measured, run 27: {@code standToPour} correctly
     * rejected the one standing spot with a clean line, because a floating bot would hit its head
     * on -10,58,37, and the
     * clear could not reach {@code y=58} to do anything about it, so the pour fell back to a cell one
     * column over and its ray hit cell zero's obsidian on the way past.
     *
     * <p>The answer to a pour that cannot see its backing, and it is deliberately not a search. The
     * corridor is a volume this rung hollowed out itself and recorded while doing it, so a solid
     * block inside it is by definition something that arrived afterwards — {@code allowPlace} is off
     * for the whole casting phase now, so this should find nothing, and finding something is itself
     * the report. Outside that set nothing is touched: one cell below the bottom frame row is the
     * mould's own floor, and answering a blocked ray by breaking it would drain every cast.
     *
     * <p><b>A flight step in the line is now taken back.</b> The rule and the measurement behind it
     * are {@link JourneySight#blockersOnTheLine}'s; what belongs here is what happens after: the cell
     * stops being a step. {@link JourneyRamp#steps} is the exemption list every sweep in this rung
     * consults, so an entry left behind for a cell that is now air would quietly exempt whatever
     * lands there next.
     */
    private static void clearPourLine(SceneContext ctx, JourneyRig rig, BlockPos target,
                                      Direction away, String tag, Runnable then) {
        ServerLevel level = ctx.level();
        List<BlockPos> blocked = JourneySight.blockersOnTheLine(level, forgeCorridor, target, away,
                POUR_LINE, rig.player());
        // WHICH OF THEM WERE THE RUNG'S OWN STEPS, named before they are spent. "1 cell to clear"
        // over a stray block and over a borrowed staircase step are the same sentence and want
        // opposite reading — the first is litter, the second is this rung handing back a cell it
        // filled on purpose two cells ago.
        StringBuilder borrowed = new StringBuilder();
        for (BlockPos c : blocked)
            if (JourneyRamp.isStep(c))
                borrowed.append(borrowed.isEmpty() ? "" : ", ").append(c.toShortString());
        rig.evidence(tag, blocked.isEmpty()
                ? "nothing clearable on the pour line (" + pourLine(level, target, away) + ")"
                : blocked.size() + " cells to clear: " + pourLine(level, target, away)
                  + (borrowed.isEmpty() ? ""
                        : "; of these, " + borrowed + " are the bot's own staircase steps — placed"
                          + " for an earlier cell's work, now blocking this cell's ray, so they are"
                          + " taken back (bot at " + rig.player().blockPosition().toShortString()
                          + " is not standing on them)"));
        clearNext(rig, blocked, 0, () -> {
            for (BlockPos c : blocked)
                if (!level.getBlockState(c).blocksMotion()) JourneyRamp.forget(c);
            then.run();
        });
    }

    private static void clearNext(JourneyRig rig, List<BlockPos> blocked, int i, Runnable then) {
        clearNext(rig, blocked, i, 600, then);
    }

    /** The per-cell budget is the caller's, because the two callers are not the same size. A pour's
     *  line is three or four cells and each one is genuinely in the way; the alcove sweep can be
     *  twenty, most of them already reachable, and a cell that will not open in four seconds there
     *  is one to walk past rather than one to spend twenty on ten times over. */
    private static void clearNext(JourneyRig rig, List<BlockPos> blocked, int i, int ticks,
                                  Runnable then) {
        if (i >= blocked.size()) { then.run(); return; }
        rig.mineCellOrGiveUp(blocked.get(i), ticks, () -> clearNext(rig, blocked, i + 1, ticks, then));
    }

    /** Break whatever no-collider block the aim ray stops on before {@code want}, then continue.
     *  One swing only: if the line is blocked by something solid, that is a placement problem and
     *  the caller's own evidence should say so rather than this quietly digging through it. */
    static void clearPlantOnLine(SceneContext ctx, JourneyRig rig, BlockPos want,
                                         String tag, Runnable then) {
        ServerLevel level = ctx.level();
        var hit = JourneyHands.aimedAt(rig.player(), WorldDriverJourneyScenes.TUNNEL_REACH, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || hit.getBlockPos().equals(want)
                || !level.getBlockState(hit.getBlockPos()).getCollisionShape(level, hit.getBlockPos()).isEmpty()) {
            then.run();
            return;
        }
        BlockPos plant = hit.getBlockPos();
        rig.evidence(tag + ".plantOnLine", plant.toShortString() + " "
                + level.getBlockState(plant).getBlock());
        // See JourneyHands.swingOffPlant. This site used to aim `rig.avatar()` and break
        // `rig.body().avatar()`, which is the shape that destroys nothing — the server's break reads
        // its own `aimTarget` field and the client aim never writes it.
        JourneyHands.swingOffPlant(rig, plant);
        rig.settle(new HoldStill(3), 12, () -> {
            // AFTER the settle, so this row can contradict the one above. It used to be written
            // before the swing, named `clearedPlant`, and reported the plant that was about to be
            // cleared — a row no failed clearing could ever falsify.
            rig.evidence(tag + ".plantAfter", String.valueOf(level.getBlockState(plant).getBlock()));
            rig.avatar().aimAtBlock(want);
            then.run();
        });
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
        // Reported on the way past whether or not it ever fired. A guard that only speaks when it
        // trips cannot be told apart from a guard that was never wired up, and this one has to
        // survive twenty steps of a rung nobody watches.
        rig.evidence("stairs.audit", "self-check " + JourneyStairs.tally()
                + ", at the end of casting " + JourneyStairs.report(level));
        if (cast < RING.length) {
            ctx.fail("The frame is incomplete: only " + cast + "/" + RING.length
                    + " obsidian blocks — a frame with a missing cell cannot be lit");
            return;
        }
        BlockPos hearth = frameCell(base, away, 0, 0);
        BlockPos doorway = hearth.above();
        rig.attempting("Clearing the doorway and lighting the portal");
        clearTheDoorway(ctx, rig, base, away, () -> strike(ctx, rig, base, away, hearth, doorway));
    }

    /**
     * Empty the six interior cells before striking, because casting fills two of them with slag.
     *
     * <p>A portal needs its doorway to be AIR, and this rung spends ten buckets of lava inside a
     * mould full of water: flowing lava that meets water is cobblestone, and it sets in whichever
     * cell the two met in. Measured, run 31 — {@code frame.cast=10/10}, {@code frame.obsidian=10/10},
     * the flint struck and {@code light.cellAfter=fire}, and {@code portal.cells=0/6}, because
     * {@code -9,58,38} and {@code -10,58,38} — the middle row of the doorway — had been cobblestone
     * since the fifth cast. Every reading about the frame was right and the door was bricked up.
     *
     * <p>Cheap to do and expensive to skip: the frame is finished by this point, so the six cells are
     * reachable from the alcove and nothing above them can fall in (the top pair is obsidian). The
     * evidence names what was in there, because "the cast leaves slag in the doorway" is a finding
     * about the mould's geometry and not a chore.
     *
     * <p><b>And slag is not the only thing that gets in — WATER does, and a pick cannot take it
     * out.</b> The first ladder run ever to cast all ten cells died here:
     * {@code portal.slag} listed two cells to clear, -9,57,38=water and -10,58,38=granite, then
     * {@code portal.doorway} reported -9,57,38=water still blocking. The granite went; the water was swung at six
     * hundred ticks' worth of nothing, because {@code mine} on a fluid cell is a no-op. It is fed
     * from the alcove — {@code drain.9}, reporting fluid still at -9,57,37 after 200 ticks, names the cell
     * immediately behind it — so the doorway is where this rung's long-standing wet alcove finally
     * stops being a cost and becomes the failure.
     *
     * <p>Three steps, in this order, because each one is pointless without the one before:
     * <b>dam</b> the corridor cell behind each interior cell when it holds fluid (a corridor cell is
     * this rung's own spoil heap and nothing downstream stands there), <b>wait</b> for what is
     * already inside to run out now that nothing feeds it, and only then <b>plug</b> whatever fluid
     * is left with a cobblestone so the existing pick can take it out as a block. A portal needs
     * {@code isEmpty()} in all six, and flowing water fails that exactly as hard as a source does.
     */
    private static void clearTheDoorway(SceneContext ctx, JourneyRig rig, BlockPos base,
                                        Direction away, Runnable then) {
        ServerLevel level = ctx.level();
        List<BlockPos> interior = new ArrayList<>();
        for (int ix = 0; ix <= 1; ix++)
            for (int iy = 1; iy <= 3; iy++) interior.add(frameCell(base, away, ix, iy));

        // DAM FIRST. Clearing a cell that something is still pouring into buys one tick of air.
        // Both players (client and server-side) — see JourneyHands.holdBoth.
        boolean held = JourneyHands.holdBoth(rig, Items.COBBLESTONE);
        StringBuilder dammed = new StringBuilder();
        for (BlockPos c : interior) {
            BlockPos behind = c.relative(away.getOpposite());
            if (!forgeCorridor.contains(behind)) continue;
            if (level.getFluidState(behind).isEmpty()) continue;
            boolean was = level.getFluidState(behind).isSource();
            if (held) JourneyStairs.placeInto(level, rig, behind);
            // READ IT BACK, and do not call it dammed until the world says so. A row that claims a
            // dam at -10,57,37 (flowing) and prints the water still standing there is the shape
            // of row this rung has been misled by twice. Best-effort is fine here (the wait and the
            // plug below carried that run to 6/6 anyway); claiming success is not.
            boolean now = level.getBlockState(behind).blocksMotion();
            dammed.append(dammed.isEmpty() ? "" : " ").append(behind.toShortString())
                    .append(was ? "(source)" : "(flowing)").append(now ? "→dammed " : "→not dammed, still ")
                    .append(level.getBlockState(behind).getBlock());
        }
        rig.evidence("portal.dam", dammed.isEmpty() ? "no fluid behind the doorway, nothing to dam"
                : (held ? "" : "no cobblestone in hand, cannot dam; ") + "alcove cells behind the doorway: "
                  + dammed);

        rig.settle(new HoldStill(20), DOORWAY_DRAIN_TICKS, () -> {
            List<BlockPos> slag = new ArrayList<>();
            StringBuilder what = new StringBuilder();
            for (BlockPos c : interior) {
                if (level.getBlockState(c).isAir() && level.getFluidState(c).isEmpty()) continue;
                // A FLUID BECOMES A BLOCK BEFORE IT BECOMES A JOB. `clearNext` mines, and mining
                // water is the six hundred ticks of nothing that killed the run above.
                if (!level.getFluidState(c).isEmpty()) {
                    // BEFORE THE PLUG, because plugging is what empties the cell. The first run of
                    // this read the fluid back after placing and printed a flowing
                    // `…material.EmptyFluid@1835b783` — the state it had just destroyed, under
                    // an object identity nobody can read. What the row is for is naming the fluid
                    // that was in the way.
                    boolean source = level.getFluidState(c).isSource();
                    String fluid = BuiltInRegistries.FLUID.getKey(level.getFluidState(c).getType())
                            .toString();
                    boolean plugged = JourneyHands.holdBoth(rig, Items.COBBLESTONE)
                            && JourneyStairs.placeInto(level, rig, c);
                    rig.evidence("portal.plug." + c.toShortString(),
                            (source ? "source " : "flowing ") + fluid
                            + " → " + (plugged ? "plugged as " + level.getBlockState(c).getBlock()
                                               + ", to be mined out as a block next"
                                             : "could not be plugged, and cannot be mined either"));
                }
                slag.add(c);
                what.append(what.isEmpty() ? "" : " ").append(c.toShortString()).append('=')
                        .append(level.getBlockState(c).getBlock());
            }
            rig.evidence("portal.slag", slag.isEmpty() ? "all six doorway cells are air"
                    : slag.size() + " cells to clear: " + what);
            if (slag.isEmpty()) { then.run(); return; }
            clearNext(rig, slag, 0, 600, () -> {
                StringBuilder left = new StringBuilder();
                for (BlockPos c : slag)
                    if (!level.getBlockState(c).isAir() || !level.getFluidState(c).isEmpty())
                        left.append(left.isEmpty() ? "" : " ").append(c.toShortString()).append('=')
                                .append(level.getBlockState(c).getBlock())
                                .append(level.getFluidState(c).isEmpty() ? ""
                                        : level.getFluidState(c).isSource() ? "(source)" : "(flowing)");
                rig.evidence("portal.doorway", left.isEmpty() ? "all six cells cleared"
                        : "still blocked: " + left);
                if (!left.isEmpty()) {
                    ctx.fail("The doorway could not be cleared: " + left + " — a portal needs six"
                            + " cells of air. Cobblestone is slag left where lava met water during"
                            + " casting; fluid is undrained alcove water flowing in from behind. The"
                            + " two need different remedies; check whether portal.dam or portal.plug"
                            + " failed");
                    return;
                }
                then.run();
            });
        });
    }

    /** How long to let the doorway run dry once its feeders are dammed. Water clears a cell in a
     *  handful of ticks when nothing replaces it, so this is generous by an order of magnitude on
     *  purpose: it is the difference between "the dam worked" and "the dam worked slowly", and only
     *  the first is worth a hundred ticks of a rung that has already spent eight thousand. */
    private static final int DOORWAY_DRAIN_TICKS = 120;

    private static void strike(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                               BlockPos hearth, BlockPos doorway) {
        ServerLevel level = ctx.level();
        rig.settle(new IntentProcess(new Intent(new Goal.Near(hearth, 3))), 1_500, () -> {
            JourneyHands.holdForUse(rig, Items.FLINT_AND_STEEL, "light");
            // Aim adjacent to the strike, not two ticks before it — see
            // JourneyHands#aimThenAct. This was the last aim-then-settle-then-use pair
            // left on the ladder, and it sits on the tick that lights the portal: on the dedicated
            // topology nothing rewrites a fake player's rotation between the two, so it has always
            // worked there and would have failed here for a reason belonging to the player's
            // rotation, not the strike.
            JourneyHands.aimThenAct(rig, hearth, () -> {
                rig.hands().useBlock(hearth, Direction.UP);
                rig.settle(new HoldStill(5), 20, () -> {
                    // PRINT THE VALUE, NOT THE PREDICATE — and print it HERE, not upstream.
                    // `portal.cells` is a count, and a count of zero names nothing. Rehearsal #7 read
                    // frame.obsidian=10/10, portal.slag reporting all six doorway cells as air,
                    // light.cellAfter=fire and
                    // portal.cells=0/6: every row right, and not one row saying what was in the other
                    // five cells. Those two upstream rows are also STALE by the time the flint moves —
                    // both are taken in clearTheDoorway, which then hands off to strike(), and strike()
                    // walks the bot to the hearth on a budget of 1500 ticks. This rung's mould is full
                    // of water by design and its alcove leaks into the doorway from behind (see this
                    // class's own note on portal.dam), so "six cells of air" a thousand ticks ago is not
                    // evidence about the tick that lit. Re-read both adjacent to the strike.
                    int lit = 0;
                    StringBuilder cells = new StringBuilder();
                    for (int ix = 0; ix <= 1; ix++)
                        for (int iy = 1; iy <= 3; iy++) {
                            BlockPos c = frameCell(base, away, ix, iy);
                            var st = level.getBlockState(c);
                            if (st.getBlock() == Blocks.NETHER_PORTAL) lit++;
                            cells.append(cells.isEmpty() ? "" : " ").append(c.toShortString())
                                    .append('=').append(st.getBlock())
                                    .append(level.getFluidState(c).isEmpty() ? ""
                                            : level.getFluidState(c).isSource() ? "(source)" : "(flowing)");
                        }
                    rig.evidence("portal.cells", lit + "/6");
                    rig.evidence("portal.cellsNow", cells.toString());
                    // WHICH CELL, not only how many. `frame.obsidianNow = 9/10` is what a lighting
                    // failure looks like from here — the fire lights and no portal forms, because a
                    // ring of nine is not a frame — and the count alone sends the next reader to
                    // re-derive the missing cell from `portal.cellsNow`, which lists the INTERIOR and
                    // therefore cannot name it at all. Measured, rehearsal 2026-08-26: `frame.cast =
                    // 10/10` with no cell lost after casting, beside `frame.obsidianNow = 9/10`, so the block
                    // went missing during `strike`'s walk and the run failed with `portal.cells 0/6`
                    // and `light.cellAfter = fire`. Naming the cell is what turns that into a place
                    // to look. The block it is NOW is part of the answer: air is something removing
                    // it, lava or cobblestone is the cast coming apart.
                    StringBuilder gone = new StringBuilder();
                    for (int[] rc : RING) {
                        BlockPos fc = frameCell(base, away, rc[0], rc[1]);
                        var fs = level.getBlockState(fc);
                        if (fs.getBlock() == Blocks.OBSIDIAN) continue;
                        gone.append(gone.isEmpty() ? "" : " ").append(fc.toShortString())
                                .append('=').append(fs.getBlock());
                    }
                    rig.evidence("frame.obsidianNow", countObsidian(level, base, away) + "/" + RING.length
                            + " (compare frame.obsidian: that count was read before the doorway was"
                            + " cleared, and up to 1500 ticks of walking in strike lie between them)"
                            + (gone.isEmpty() ? "" : " — missing: " + gone));
                    rig.evidence("light.cellAfter", String.valueOf(level.getBlockState(doorway).getBlock()));
                    rig.evidence("bucket.after", rig.carrying("minecraft:bucket")
                            + " empty / " + rig.carrying("minecraft:water_bucket") + " water");
                    ctx.expect(lit).as("the portal the bot carved, cast and struck is lit").isEqualTo(6);
                    rig.reach("cast ten obsidian blocks in place at y=" + doorway.getY() + " and lit "
                            + lit + " portal cells (carried one water bucket down the shaft; the"
                            + " water is still in the bucket after casting)");
                });
            });
        });
    }
}
