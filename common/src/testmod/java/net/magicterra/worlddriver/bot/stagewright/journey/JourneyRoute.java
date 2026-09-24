package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.SceneContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What is where, in the one world this suite plays.
 *
 * <p>The seed is fixed — {@code 5471}, forced into {@code server.properties} by StageWright's
 * {@code RunDirectory} and matched by {@code ClientDirector.WORLD_SEED} so all three topologies get
 * the same ground. So the landscape is a constant, and this class is where that constant is written
 * down.
 *
 * <h2>Why the route is surveyed rather than searched</h2>
 *
 * The journey scripts every step against known coordinates: walk to <i>this</i> tree, mine
 * <i>that</i> ore, pour water <i>here</i>. It plays the part of an agent that knows this world
 * perfectly, and it does that deliberately, because it changes what a failure means. A stage that
 * searched for its own materials would fail when the search failed, and a search failure is a
 * planner bug — interesting, but not what this suite is for. With the coordinates given, the only
 * thing left that can fail is worlddriver's ability to <b>execute</b>: to walk there, to break that,
 * to place this, to open that. Those are the failures worth a gate.
 *
 * <p>The corollary is that these constants are load-bearing, and stale ones are the most expensive
 * kind of wrong: a coordinate that no longer holds a tree does not report "the survey is stale", it
 * reports "the bot could not gather wood". {@code wd.journey01Recon} exists to stop that — it
 * re-derives every constant here from the live world and fails when one has moved.
 *
 * <h2>Filling this in</h2>
 *
 * Every field starts {@link #UNSURVEYED}. The recon scene records what it found and passes with a
 * loud note; you copy the recorded values in here, and from then on recon is a guard rather than a
 * discovery. That order matters — a survey written from a wiki or from another seed's world is the
 * exact failure this class is built to make impossible.
 */
public final class JourneyRoute {

    /** The seed StageWright forces. Asserted by recon: everything here is a statement about it. */
    public static final long SEED = 5471L;

    /** A coordinate nobody has surveyed yet. Recon reports these as "to be baked", not as failures. */
    public static final BlockPos UNSURVEYED = new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    private JourneyRoute() {}

    // =====================================================================================
    // Surveyed constants. ⛔ Do not hand-write these — paste them from a wd.journey01Recon run.
    // =====================================================================================

    /**
     * Where the world puts the player. Recon asserts the live spawn equals this.
     *
     * <p>Surveyed 2026-08-08: a <b>swamp</b>, which is a hard opening and worth knowing before
     * reading any failure above. Water is six blocks away and the ground is mostly at y=62-63, so
     * "the bot fell in" and "the bot is swimming" are live hypotheses here in a way they would not
     * be on a plains spawn. The shared spawn point sits at y=68 while the surface heightmap says 63
     * — the bot is placed on the surface, not on the spawn point.
     */
    public static BlockPos spawn = new BlockPos(64, 68, 60);

    /** The biome at spawn. */
    public static String spawnBiome = "minecraft:swamp";

    /**
     * The nearest log the wood stage cuts — three blocks from spawn, and five blocks UP.
     *
     * <p>y=68 against a surface of 63 means this is canopy, not trunk: swamp oaks spread. That is
     * the honest first test of the wood stage, and deliberately not corrected to a trunk block —
     * reaching a log that is above head height is the job.
     */
    public static BlockPos firstTree = new BlockPos(65, 68, 63);

    /**
     * A second tree, at least twelve blocks from the first — <b>surveyed per run, not baked</b>.
     *
     * <p>One tree is not one tree's worth of wood. The bot cannot climb, so it takes the trunk at
     * eye level and leaves the crown, and a swamp oak yields two to four that way — measured hauls
     * from {@link #firstTree} were 4, then 3, then 2 on consecutive runs. Three logs is twelve
     * planks, and the ladder's bill through the furnace is a table (4), sticks (2), a wooden pickaxe
     * (3) and a replacement table for each craft that finds itself without one. Runs died of exactly
     * that arithmetic, a rung apart, reporting a shortfall of one oak_log. So the wood rung walks to a
     * second trunk, which is what a player does.
     *
     * <p><b>This is the one landmark that could not be baked, and it is not for want of trying.</b>
     * Five consecutive runs on the same seed and the same build surveyed it at (63,66,77),
     * (52,66,55), (63,66,77), (52,66,55) and (47,68,54); baking either of the repeats and asking
     * recon whether it still held a log came back {@code found Block&#123;minecraft:air&#125;} on
     * the next run. {@link #firstTree}, three blocks from spawn, is stable across every one of those
     * runs — so what is unstable is not the world but what a scan sees a chunk or two out, and
     * forcing chunks further than the search reaches did not settle it.
     *
     * <p>Which leaves two honest options: bake a coordinate that is wrong half the time, or take
     * this one from the run's own survey. It takes it from the survey, assigned by the recon scene.
     * That is a real departure from the ladder's rule — every other coordinate is a written claim a
     * later run can check — so it is confined to this field, and {@code survey.secondTree} stays in
     * every recon record precisely so the instability keeps being visible rather than becoming
     * invisible the moment it stopped failing anything. The root cause is open.
     */
    public static BlockPos secondTree = UNSURVEYED;

    /**
     * The nearest exposed stone the stone stage mines.
     *
     * <p>It was (72,59,74) until the dry test grew from a cross to a 5×5 — see {@link #dryCross}.
     * That column is dry by the old measure and floods on the third pass by the new one, and the
     * nearest column that survives the wider test lands one cell off the iron rung's. Two rungs
     * digging next to each other is a coincidence of this seed, not a plan: they sink separate
     * shafts, and the stone rung fills its own back in with nothing.
     */
    public static BlockPos firstStone = new BlockPos(83, 59, 76);

    /** The nearest surface coal the furnace stage burns. */
    public static BlockPos firstCoal = new BlockPos(62, 54, 71);

    /**
     * The iron ore the iron stage mines — the nearest one a shaft can reach, not the nearest one.
     *
     * <p>The nearest is at (64,55,60), thirteen blocks from spawn and under a pond; see
     * {@link #nearestUnderDryGround} for why that coordinate produced a plan nothing could execute.
     * This one is twenty-six blocks out and four below its own dry surface.
     */
    public static BlockPos firstIron = new BlockPos(83, 59, 75);

    /**
     * The surface cell the iron stage digs down FROM — {@link #firstIron}'s own column, at ground.
     *
     * <p>Trivially the same column now that the ore is chosen for being under diggable ground, and
     * kept as a separate constant anyway, because the two are separate facts: the rung walks to a
     * surface cell and digs down to an ore, and conflating them is what produced the first version
     * of this rung — a plan that named where the iron was and never said how to get under it. When
     * the ore had to be taken as given, the answer came out eighteen blocks away.
     */
    public static BlockPos ironDescent = new BlockPos(83, 63, 75);

    /** The surface cell the stone stage digs down FROM — see {@link #ironDescent}, same shape. */
    public static BlockPos stoneDescent = new BlockPos(83, 63, 76);

    /** A second iron vein, at least twelve blocks from the first. UNSURVEYED until recon prints it —
     *  see the survey's own note for why one vein is not enough ore on this seed. */
    public static BlockPos secondIron = new BlockPos(92, 53, 83);

    /** The surface cell the iron stage digs down FROM for {@link #secondIron}. */
    public static BlockPos secondIronDescent = new BlockPos(92, 64, 83);


    /**
     * A third iron vein, clear of both the others.
     *
     * <p>Two veins are not four ingots either, and four is what the portal kit costs. Measured:
     * a run took {@code vein1.raw_iron=0} and {@code vein2.raw_iron=3}, smelted three, and the kit
     * rung failed on a shortfall of one iron_ingot, holding a bucket and six flint it did not need. This
     * seed's veins run one to three ore, so "how many veins" is the wrong question to answer once —
     * the rung digs until it has the bill or runs out of surveyed veins, and this is the third.
     *
     * <p>It stayed {@code UNSURVEYED} for several runs while recon printed it every single time,
     * and that gap is the whole reason the shortfall looked like flakiness: the loop was written to
     * work three veins and the ladder only ever owned two, so a run whose first vein lost its drops
     * had nowhere left to go and the failure surfaced a rung later as an ingot count. **A surveyed
     * landmark that is never baked is not a landmark.** Note it sits 59 blocks out, past
     * {@code nearestUnderDryGround}'s usual 48 — see {@code THIRD_VEIN_RADIUS}.
     */
    public static BlockPos thirdIron = new BlockPos(10, 58, 82);

    /** The surface cell the iron stage digs down FROM for {@link #thirdIron}. */
    public static BlockPos thirdIronDescent = new BlockPos(10, 65, 82);
    /** Gravel, for the flint half of a flint-and-steel. UNSURVEYED until recon prints it. */
    public static BlockPos firstGravel = new BlockPos(29, 60, 76);

    /** Open water, for the bucket and for casting obsidian. */
    public static BlockPos firstWater = new BlockPos(64, 62, 60);

    /**
     * A lava pool, for the obsidian cast — ROADMAP N4's subject.
     *
     * <p>It was {@link #UNSURVEYED} for a while, and the reason is worth keeping: the first survey
     * asked the surface question — 48 blocks out, an 8-block band around each column's top — and a
     * swamp surface truthfully has no lava. That is a correct answer to a question nobody wanted
     * asked. Lava is a DEPTH question, so {@link #nearestInBand} scans an absolute band instead, and
     * the answer came back at once and identically on two consecutive runs.
     *
     * <p><b>y = 27, 74 blocks out — and it is the second answer this constant has had.</b> The first
     * was {@code (84, -14, 47)}: a 77-block descent, accepted for two runs as this seed's terrain.
     * It was the SEARCH's terrain. Widening {@link #LAVA_SEARCH_RADIUS} from 48 to 80 found a pool
     * 36 blocks down instead, and nothing in the first answer hinted that a shallower one existed —
     * see that constant for why a horizontal radius makes a depth question answer the wrong one.
     *
     * <p>And then a third answer, for a different reason again: <b>(68, 27, -1) is not diggable.</b>
     * It sits under the swamp's water table, and all 280 columns within eight blocks of it were
     * rejected for having fluid in the twelve blocks below their own surface. Recon therefore does
     * not take the nearest pool at all any more — it enumerates the nearest distinct pools and takes
     * the first one the obsidian rung's own column test accepts, which on this seed is
     * {@code (-6, 26, 54)}, 82 blocks out. Same lesson as {@link #nearestUnderDryGround}: the
     * nearest instance of a thing and the nearest USABLE instance are different surveys, and only
     * the second is a route.
     *
     * <p>Adopted per run rather than checked, for all of the above: a constant baked by an earlier
     * question is a claim about that question. Recon assigns what it chooses and records
     * {@code lava.chosen} and every rejected pool's reason, so the value here is documentation of
     * the last answer rather than an assertion about the next.
     */
    public static BlockPos firstLava = new BlockPos(-6, 26, 54);

    /**
     * A lava LAKE — ten or more source blocks in one place — as opposed to {@link #firstLava}, which
     * is the single cell the obsidian rung fills its bucket from.
     *
     * <p>These are two landmarks and it took four field runs to learn that they cannot be one.
     * Filling a bucket <b>takes the source block</b>, so by the time the portal rung arrives at
     * {@code firstLava} the cell is air — measured, {@code pool.sources=0} within sixteen blocks of
     * it. The portal rung needs ten DISTINCT sources for ten casts, so it needs somewhere that has
     * ten to begin with.
     */
    public static BlockPos lavaLake = UNSURVEYED;

    /** How many source blocks {@link #lavaLake} had when it was surveyed. Recorded because "a lake"
     *  is a claim with a number in it, and the rung's whole bill is that number. */
    public static int lavaLakeSources = 0;

    /** The stronghold's location, from {@code /locate} — 1745 blocks out, across open world. */
    public static BlockPos stronghold = new BlockPos(-1168, 64, 1296);

    /** The nearest ruined portal — 620 blocks out. Not on the critical path, but it is obsidian
     *  somebody else already cast, and a scripted run may legitimately prefer it to ROADMAP N4. */
    public static BlockPos ruinedPortal = new BlockPos(-384, 64, -368);

    /**
     * The nearest NETHER FORTRESS, <b>in nether coordinates</b> — where BLAZE_ROD walks.
     *
     * <p><b>Only X and Z are a claim.</b> The generator answers a structure query with
     * {@code StructurePlacement.getLocatePos}, which fills Y in from the placement's own offset
     * (zero for a fortress) and not from anything in the world — the same {@code ~} that
     * {@code /locate} prints. A rung that trusted this Y would walk to a coordinate under the
     * bedrock floor. The walk is therefore an {@link net.magicterra.worlddriver.bot.Goal.XZ}, and
     * the rung re-derives its own Y once it is standing there and the chunks are real.
     *
     * <p><b>Nether coordinates, not overworld ones.</b> This is the one landmark in this class that
     * lives in another world, and mixing the two is the exact failure {@code changeDimension}
     * already produced once — a bot that arrived 87 501 blocks out because a number meant for one
     * dimension was used in the other. {@link #netherwards} is the only conversion; a caller with
     * an overworld position uses {@link #surveyNetherFortress}, which applies it.
     */
    public static BlockPos netherFortress = UNSURVEYED;

    /** What the last {@link #surveyNetherFortress} cost, in milliseconds, or -1 before the first
     *  one. Kept because {@link Located} makes the price part of the answer — see its note. */
    public static long netherFortressMs = -1;

    /**
     * Find the densest cluster of lava SOURCE blocks near spawn, and how many cells it has.
     *
     * <p>Deliberately a survey and not a search-at-use: the caller is assumed to know this seed, so
     * the rung that needs a lake should be handed one rather than hunting for it forty blocks under
     * the ground with a bucket of water it cannot re-fill.
     */
    public static java.util.Map.Entry<BlockPos, Integer> surveyLavaLake(net.minecraft.server.level.ServerLevel level,
                                                                       BlockPos near, int radius) {
        java.util.Set<Long> sources = new java.util.HashSet<>();
        java.util.List<BlockPos> list = new java.util.ArrayList<>();
        // Bounded tightly on purpose. The first version swept 64 blocks around spawn over y 5..40 —
        // roughly 600 000 fluid lookups, each able to force chunk GENERATION — and blew a ten-minute
        // budget on the ladder's very first rung. A survey that costs more than the run it informs
        // is not a survey. This one is centred on ground already known to hold lava.
        int y0 = Math.max(level.getMinBuildHeight() + 1, near.getY() - 12);
        int y1 = Math.min(level.getMaxBuildHeight() - 1, near.getY() + 12);
        for (int x = near.getX() - radius; x <= near.getX() + radius; x++)
            for (int z = near.getZ() - radius; z <= near.getZ() + radius; z++)
                for (int y = y0; y <= y1; y++) {
                    BlockPos c = new BlockPos(x, y, z);
                    if (level.getFluidState(c).isSource()
                            && level.getBlockState(c).is(net.minecraft.world.level.block.Blocks.LAVA)) {
                        sources.add(c.asLong());
                        list.add(c);
                    }
                }
        BlockPos best = UNSURVEYED;
        int bestN = 0;
        for (BlockPos c : list) {
            int n = 0;
            for (int dx = -5; dx <= 5; dx++)
                for (int dy = -3; dy <= 3; dy++)
                    for (int dz = -5; dz <= 5; dz++)
                        if (sources.contains(new BlockPos(c.getX() + dx, c.getY() + dy, c.getZ() + dz).asLong())) n++;
            if (n > bestN) { bestN = n; best = c; }
        }
        return java.util.Map.entry(best, bestN);
    }

    /** Whether every constant above has been filled in. */
    public static boolean surveyed() {
        return !spawn.equals(UNSURVEYED) && !firstTree.equals(UNSURVEYED);
    }

    // =====================================================================================
    // Survey machinery — what recon runs to derive the constants above.
    // =====================================================================================

    /** One found thing: what it was and where, or absent. */
    public record Found(String what, BlockPos where, double distance) {

        /** The form recon records and a human pastes back into the constants above. */
        public String asConstant() {
            return where == null ? "UNSURVEYED;  // not found — " + what
                    : "new BlockPos(" + where.getX() + ", " + where.getY() + ", " + where.getZ() + ")"
                      + "  // " + String.format(java.util.Locale.ROOT, "%.0f", distance) + " blocks";
        }

        /** What a record line should say about this, found or not — the reason travels either way. */
        public String asRecord() {
            return where == null ? "NOT_FOUND(" + what + ")"
                    : where.getX() + "," + where.getY() + "," + where.getZ()
                      + " (" + Math.round(distance) + "m)";
        }
    }


    /**
     * The nearest block of a kind in an ABSOLUTE height band, rather than a band around the surface.
     *
     * <p>{@link #nearest} scans relative to each column's surface, which is the right shape for
     * everything a route walks to and the wrong shape for the one thing it has to dig for. Lava is
     * not near the surface of a swamp — the first survey looked 48 blocks out with an 8-block
     * surface band, found none, and recorded {@code UNSURVEYED}, which is a true answer to a question
     * nobody wanted asked. What ROADMAP N4 needs is the nearest lava the run can reach at ANY depth,
     * so the caller states the depths.
     *
     * <p>Scanned top-down within the band and stopped at the first hit per column, so the answer is
     * the SHALLOWEST lava in the nearest column rather than the deepest — a portal cast pays for
     * every block of descent twice, once down and once back up.
     */
    public static Found nearestInBand(ServerLevel level, BlockPos centre, String blockId,
                                      int radius, int yMax, int yMin) {
        Block wanted = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                // Cheap rejection before the column scan: the column cannot beat the best answer if
                // its horizontal distance alone already does not. A full band scan is ~100 sections
                // per column, so skipping a column is worth far more than skipping a block.
                double flat = Math.hypot(x - centre.getX(), z - centre.getZ());
                if (flat >= bestDist) continue;
                for (int y = yMax; y >= yMin; y--) {
                    at.set(x, y, z);
                    if (level.getBlockState(at).getBlock() != wanted) continue;
                    double d = Math.sqrt(centre.distSqr(at));
                    if (d < bestDist) { bestDist = d; best = at.immutable(); }
                    break;
                }
            }
        }
        return new Found(blockId, best, best == null ? -1 : bestDist);
    }

    /**
     * Every distinct pool of a fluid in a height band, nearest first.
     *
     * <p>{@link #nearestInBand} answers "where is the closest lava", which turned out to be the
     * wrong question by exactly the margin that matters: seed 5471's nearest pool sits under the
     * swamp's water table, and <b>all 280 columns within eight blocks of it</b> were rejected for
     * having fluid in the twelve blocks below their own surface. A pool you cannot sink a shaft
     * beside is a coordinate, not a route — the same lesson the ore landmarks learned when the
     * nearest iron turned out to be under a pond.
     *
     * <p>So the survey offers a LIST and lets the caller apply its own test of usability. Pools
     * within {@code apart} blocks of an already-chosen one are folded together, because a lava lake
     * is hundreds of cells and two hundred candidates that are all the same lake is not a choice.
     */
    public static List<Found> poolsInBand(ServerLevel level, BlockPos centre, String blockId,
                                          int radius, int yMax, int yMin, int apart, int limit) {
        Block wanted = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
        List<Found> hits = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                for (int y = yMax; y >= yMin; y--) {
                    at.set(x, y, z);
                    if (level.getBlockState(at).getBlock() != wanted) continue;
                    hits.add(new Found(blockId, at.immutable(), Math.sqrt(centre.distSqr(at))));
                    break;                       // shallowest hit in this column only
                }
            }
        }
        hits.sort(java.util.Comparator.comparingDouble(Found::distance));
        List<Found> pools = new ArrayList<>();
        for (Found hit : hits) {
            if (pools.size() >= limit) break;
            boolean sameLake = false;
            for (Found kept : pools) {
                if (kept.where().distSqr(hit.where()) < (double) apart * apart) { sameLake = true; break; }
            }
            if (!sameLake) pools.add(hit);
        }
        return pools;
    }

    /**
     * Load a square of chunks and hold them, so a scan reads terrain rather than emptiness.
     *
     * <p>Without this a scan "succeeds" over ungenerated chunks and reports that the world contains
     * no wood — the quiet failure that every ticket in this package exists to prevent.
     */
    public static void loadAround(ServerLevel level, BlockPos centre, int chunkRadius) {
        ChunkPos c = new ChunkPos(centre);
        for (int cx = c.x - chunkRadius; cx <= c.x + chunkRadius; cx++) {
            for (int cz = c.z - chunkRadius; cz <= c.z + chunkRadius; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    /**
     * The nearest block of a kind within a box around {@code centre}, or a {@code Found} with no
     * position.
     *
     * <p>Scans columns outward from the centre and, per column, a band around the surface rather
     * than the whole height. A full-height scan of the same footprint is forty times the work and
     * finds the same surface features; the things it would additionally find — deep ores — are not
     * what a scripted playthrough walks to first.
     *
     * @param band how far above and below the surface to look in each column
     */
    public static Found nearest(ServerLevel level, BlockPos centre, String blockId, int radius, int band) {
        return nearest(level, centre, blockId, radius, band, null, 0);
    }

    /** The same, ignoring columns within {@code minAway} of {@code avoid} — see the avoiding
     *  {@link #firstOf} for why a second search needs to be told what the first one already took. */
    public static Found nearest(ServerLevel level, BlockPos centre, String blockId, int radius,
                                int band, BlockPos avoid, int minAway) {
        Block wanted = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                if (avoid != null && Math.hypot(x - avoid.getX(), z - avoid.getZ()) < minAway) continue;
                int surface = level.getHeightmapPos(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
                for (int y = surface + band; y >= surface - band; y--) {
                    BlockPos at = new BlockPos(x, y, z);
                    if (level.getBlockState(at).getBlock() != wanted) continue;
                    double d = Math.sqrt(centre.distSqr(at));
                    if (d < bestDist) { bestDist = d; best = at; }
                    break;                                  // one hit per column is enough
                }
            }
        }
        return new Found(blockId, best, best == null ? -1 : bestDist);
    }

    /**
     * The nearest ore you can actually get to — one whose own column can be dug straight down.
     *
     * <p>{@link #nearest} answers "where is the closest iron", and that turned out to be the wrong
     * question. Seed 5471's closest iron is thirteen blocks from spawn and directly under a swamp
     * pond, so the plan built on it could not be executed at any price: the shaft floods. Surveying
     * for a dry shaft SEPARATELY then produced a descent point eighteen blocks from the ore, which
     * trades the flooded shaft for a long blind horizontal tunnel and a mine scan wide enough to
     * bump the scanner's own cell budget. Asking for the two together — an ore under diggable ground
     * — is one search, one answer, and a shaft that lands on the target.
     *
     * <p>"Diggable" is {@link #dryCross}: the ore's column and its four cardinals free of fluid all
     * the way down, which is the footprint a staircase occupies.
     */
    public static Found nearestUnderDryGround(ServerLevel level, BlockPos centre, String blockId,
                                              int radius, int band) {
        return nearestUnderDryGround(level, centre, blockId, radius, band, List.of(), 0);
    }

    /**
     * As above, but skipping everything within {@code minAway} of {@code avoid}.
     *
     * <p>For the second of a thing. Seed 5471's first iron vein is one ore deep — the rung mined it
     * out, banked {@code raw_iron=2} and reported {@code broke 1/8, no reachable target}, which is
     * not a driver failure and not something a bigger quota can fix. A portal kit costs four ingots,
     * so the route needs a SECOND vein, and "second" has to mean a different vein rather than
     * another block of the same one — hence a distance floor rather than a simple exclusion.
     */
    public static Found nearestUnderDryGround(ServerLevel level, BlockPos centre, String blockId,
                                              int radius, int band, BlockPos avoid, int minAway) {
        return nearestUnderDryGround(level, centre, blockId, radius, band,
                avoid == null ? List.of() : List.of(avoid), minAway);
    }

    /**
     * As above, clear of EVERY point in {@code avoid}.
     *
     * <p>A list rather than one point, because the Nth of a thing has to be clear of all N-1 before
     * it and avoiding only the most recent silently returns an earlier one. Measured: a third iron
     * vein surveyed while avoiding only the second came back as {@code (83,59,75)} — the FIRST
     * vein's own coordinate — so the rung would have sunk a second shaft into a hole it had already
     * mined out, and reported the terrain as barren.
     */
    public static Found nearestUnderDryGround(ServerLevel level, BlockPos centre, String blockId,
                                              int radius, int band, List<BlockPos> avoid, int minAway) {
        Block wanted = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centre.getX() + dx, z = centre.getZ() + dz;
                boolean tooClose = false;
                for (BlockPos a : avoid) {
                    if (Math.hypot(x - a.getX(), z - a.getZ()) < minAway) { tooClose = true; break; }
                }
                if (tooClose) continue;
                int surface = level.getHeightmapPos(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
                for (int y = surface + band; y >= surface - band; y--) {
                    BlockPos at = new BlockPos(x, y, z);
                    if (level.getBlockState(at).getBlock() != wanted) continue;
                    double d = Math.sqrt(centre.distSqr(at));
                    if (d < bestDist && dryCross(level, x, z, y)) { bestDist = d; best = at; }
                    break;                                  // one hit per column is enough
                }
            }
        }
        return new Found(blockId + " (under diggable ground)", best, best == null ? -1 : bestDist);
    }

    /**
     * The nearest column you can actually sink a shaft in, to reach {@code depth}.
     *
     * <p>Surveying where the ore IS turned out to be half a plan. Seed 5471 spawns in a swamp and
     * its first iron sits under a pond: the descent verb reported <i>"no safe descent stride at
     * 64,62,63 — all cardinals + own column wet/hazard/unbreakable"</i> and refused, correctly.
     * Digging down through standing water floods the shaft and drowns the digger, and a driver that
     * did it anyway would be the bug. So the route surveys the DRY column too, and the iron rung
     * walks to that instead of to the ore's own column.
     *
     * <p>Rings outward from the target's column so the answer is the nearest qualifying one, and a
     * column qualifies only if every cell from the surface down to {@code depth} is free of fluid —
     * checking just the top cell would pick a dry bank over a buried aquifer.
     *
     * <p><b>The column and its four cardinals, not the column alone.</b> A first version surveyed a
     * single dry column and the descent still failed: what it digs is a STAIRCASE, so each stride
     * needs a dry neighbour to step into, and in a swamp the nearest dry column is a needle
     * surrounded by pond. The bot walked off it looking for a stride and reported the same
     * everything-is-wet refusal ten blocks away. Surveying for the shape the verb needs — a dry
     * cross — is the difference between a coordinate that satisfies the question asked and one that
     * satisfies the work.
     *
     * @param maxRadius how far from the target's column to look before giving up
     */
    public static Found dryDescentNear(ServerLevel level, BlockPos target, int depth, int maxRadius) {
        for (int r = 0; r <= maxRadius; r++) {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // this ring only
                    int x = target.getX() + dx, z = target.getZ() + dz;
                    int surface = level.getHeightmapPos(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
                    if (surface <= depth) continue;                            // already at/below the ore
                    if (!dryCross(level, x, z, depth)) continue;
                    BlockPos stand = new BlockPos(x, groundUnder(level, x, z, depth, surface), z);
                    double d = Math.sqrt(target.distSqr(stand));
                    if (d < bestDist) { bestDist = d; best = stand; }
                }
            }
            if (best != null) return new Found("dryDescent", best, bestDist);
        }
        return new Found("dryDescent", null, -1);
    }

    /**
     * The cell a player would stand in, given a heightmap {@code surface} that may be a treetop.
     *
     * <p>{@code MOTION_BLOCKING_NO_LEAVES} counts logs, so over a forested column it answers with
     * the top of the trunk — and the first survey duly reported {@code ironDescent} six blocks up
     * the tree the wood rung had not cut yet. Only X and Z of this constant are load-bearing (the
     * rung walks to the column and then digs), so that was harmless and unreadable, which is the
     * combination that misleads a later reader rather than failing in front of them.
     */
    private static int groundUnder(ServerLevel level, int x, int z, int depth, int surface) {
        int y = surface;
        while (y > depth) {
            BlockState under = level.getBlockState(new BlockPos(x, y - 1, z));
            if (under.blocksMotion() && !under.is(BlockTags.LOGS) && !under.is(BlockTags.LEAVES)) break;
            y--;
        }
        return y;
    }

    /**
     * A 5×5 of columns, all dry down to {@code depth} — the footprint a shaft actually occupies
     * plus the walls that could leak into it.
     *
     * <p>It was a cross once, sized for the staircase {@code DescendProcess} cuts. The scripted
     * shaft that replaced that verb is not a staircase and its footprint is wider than a cell: a
     * player box is 0.6 wide, so a player standing near an edge is held up by a NEIGHBOURING cell and
     * the digger has to break that one too. That makes the hole up to 2×2, whose walls are the
     * ring outside it — and a cross does not certify that ring. Measured: {@code 72,63,74} passed
     * the cross, the shaft broke its centre and then one corner, and groundwater from a column the
     * cross never looked at filled the hole on the third pass and stayed for the rest of the run.
     *
     * <p>Widening the survey is the cheap half of that fix; {@code supportUnder} not mistaking
     * water for a floor is the other.
     */
    private static boolean dryCross(ServerLevel level, int x, int z, int depth) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (!dryColumn(level, x + dx, z + dz, depth)) return false;
        return true;
    }

    /**
     * No fluid anywhere in one column between its own surface and {@code depth}.
     *
     * <p>Public because the obsidian rung needs the same test and there must not be a second
     * definition of "dry". It picks its own shaft column at runtime rather than from a surveyed
     * constant, and the first run that did so chose a column under a swamp pond: the bot floated,
     * {@code supportUnder} answered "water" 122 times, and the rung reported "the block broke but
     * the bot did not sink" for a bot that was swimming. That is the same failure the ore
     * landmarks are surveyed to avoid — see {@link #nearestUnderDryGround} — so it uses the same
     * measurement rather than a fresh one.
     */
    public static boolean dryColumn(ServerLevel level, int x, int z, int depth) {
        int surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
        for (int y = surface + 1; y >= depth; y--) {
            if (!level.getBlockState(new BlockPos(x, y, z)).getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /**
     * Ask the game where the nearest structure is, through {@code /locate}.
     *
     * <p>Through a command rather than the generator API because the command is positioned: the
     * journey plays at world spawn and the scene's own origin is a grid cell hundreds of blocks
     * away, so a locate centred on the origin would answer a question nobody asked. Returns a
     * {@code Found} with no position when the structure is not within the game's own search bound —
     * "there is none near here" is an answer, and for a fixed seed it is a stable one.
     */
    public static Found locate(SceneContext ctx, BlockPos from, String structureId) {
        String text;
        try {
            text = ctx.command("execute positioned " + from.getX() + " " + from.getY() + " "
                    + from.getZ() + " run locate structure " + structureId).text();
        } catch (RuntimeException e) {
            // The reason is carried, not dropped. A malformed id and an absent structure both end
            // up here, and collapsing them to a bare NOT_FOUND is how the first survey came to
            // claim seed 5471 has no village — it has several; `minecraft:village` is simply not a
            // structure id. A survey that cannot tell "I asked wrong" from "it is not there" is
            // worse than one that found nothing.
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Found(structureId + " [" + why.replace('\n', ' ') + "]", null, -1);
        }
        BlockPos at = parseLocate(text);
        return new Found(structureId, at, at == null ? -1 : Math.sqrt(from.distSqr(at)));
    }

    /**
     * Pull the coordinates out of {@code /locate}'s reply.
     *
     * <p>The reply reads "The nearest X is at [123, ~, -456] (789 blocks away)". Parsed by pulling
     * the first bracketed triple rather than by matching the sentence, because the sentence is
     * translatable and the bracket is not. A reply this cannot parse answers null, and recon reports
     * that as a not-found rather than crashing — a structure search that changed its output format
     * should not read as a world that lost its stronghold.
     */
    static BlockPos parseLocate(String text) {
        int open = text.indexOf('[');
        int close = text.indexOf(']', open + 1);
        if (open < 0 || close < 0) return null;
        String[] parts = text.substring(open + 1, close).split(",");
        if (parts.length != 3) return null;
        try {
            int x = Integer.parseInt(parts[0].trim());
            String yRaw = parts[1].trim();
            int y = "~".equals(yRaw) ? 64 : Integer.parseInt(yRaw);
            int z = Integer.parseInt(parts[2].trim());
            return new BlockPos(x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =====================================================================================
    // Structures in ANOTHER dimension — the half /locate cannot answer from here.
    // =====================================================================================

    /**
     * How long any survey in this class may take before it is itself the problem.
     *
     * <p>Five seconds, and the number is a scar rather than a preference. The first lava survey
     * swept 64 blocks around spawn over y 5..40 — roughly 600 000 fluid lookups, each able to force
     * chunk GENERATION — and blew a <b>ten-minute</b> budget on the ladder's very first rung, so the
     * run that was supposed to measure the driver measured the survey instead. A survey that costs
     * more than the run it informs is a failure, so from here on the price travels with the answer
     * and a caller can assert on it.
     */
    public static final long SURVEY_MS_BUDGET = 5_000L;

    /**
     * A structure survey: what it found, what asking cost, and how far it was allowed to look.
     *
     * <p>All three, because two of them decide what the first one MEANS. A {@code NOT_FOUND} inside
     * the bound is a statement about the seed; the same {@code NOT_FOUND} at the bound is a
     * statement about the search — the distinction that cost the lava landmark two runs at the wrong
     * depth (see {@code LAVA_SEARCH_RADIUS}). And a hit that took nine seconds to find is not a
     * usable landmark for a ladder whose rungs are timed.
     */
    public record Located(Found found, long millis, int rings) {

        /** Whether this answer cost more than {@link #SURVEY_MS_BUDGET}. */
        public boolean overBudget() { return millis > SURVEY_MS_BUDGET; }

        /** What a record line should say — the answer and its price, never one without the other. */
        public String asRecord() {
            return found.asRecord() + " (took " + millis + " ms, searched up to " + rings
                    + " rings of placement regions)"
                    + (overBudget() ? " ⚠ over the survey budget of " + SURVEY_MS_BUDGET + " ms" : "");
        }
    }

    /**
     * The nether cell an overworld cell maps onto — the 8:1 rule a portal obeys.
     *
     * <p>Public and named because the alternative is the conversion being written inline at each
     * call site, and this ladder has already paid for that once: a bot reached the Nether holding
     * its raw overworld X and landed 87 501 blocks from where it should have been. A coordinate that
     * crosses a dimension boundary should cross it through one function.
     */
    public static BlockPos netherwards(BlockPos overworld) {
        return new BlockPos(Math.floorDiv(overworld.getX(), 8), 64, Math.floorDiv(overworld.getZ(), 8));
    }

    /**
     * Where the nearest nether fortress is, asked from the overworld, and what asking cost.
     *
     * <p><b>Why not {@code /locate}.</b> {@link #locate} runs a command, and a command runs in the
     * source's dimension — which for every scene in this suite is the overworld. There is no nether
     * fortress in the overworld, so the honest answer to the command is "none", and that answer
     * would have been baked as a fact about seed 5471. {@code execute in the_nether run locate}
     * would work and brings its own trap (the source keeps its overworld POSITION, so the search is
     * centred 8× too far out); the generator API takes both the dimension and the centre as
     * arguments and has neither problem.
     *
     * <p>Assigns {@link #netherFortress} and {@link #netherFortressMs} when it finds one, the way
     * recon adopts {@code secondTree}: a landmark in a dimension nothing else in this class visits
     * has no earlier value for a later run to check against, so the survey's answer IS the constant
     * until somebody bakes one.
     *
     * @param overworldFrom where the run is standing in the OVERWORLD — converted by
     *                      {@link #netherwards}, because the fortress is looked for near where this
     *                      run's own portal comes out, not near the nether origin
     */
    public static Located surveyNetherFortress(SceneContext ctx, BlockPos overworldFrom) {
        return surveyNetherFortressFrom(ctx, netherwards(overworldFrom));
    }

    /** {@link #surveyNetherFortress} for a caller whose centre is ALREADY in nether coordinates —
     *  a rung that is standing in the Nether and asking about the ground under its own feet. */
    public static Located surveyNetherFortressFrom(SceneContext ctx, BlockPos netherFrom) {
        Located out = surveyStructure(ctx, net.minecraft.world.level.Level.NETHER,
                net.minecraft.world.level.levelgen.structure.BuiltinStructures.FORTRESS,
                "minecraft:fortress", netherFrom, FORTRESS_SEARCH_RINGS);
        netherFortressMs = out.millis();
        if (out.found().where() != null) netherFortress = out.found().where();
        return out;
    }

    /**
     * Ask the generator where the nearest instance of one structure is, in a named dimension.
     *
     * <p>{@code ServerLevel.findNearestMapStructure} takes a {@code TagKey}, which is the wrong
     * shape for asking about exactly one structure — there is no tag containing only the fortress,
     * and inventing one to ask a question is a data pack. The generator's own overload takes a
     * {@code HolderSet}, so a set of one is built straight from the structure registry.
     *
     * <p><b>{@code skipExistingChunks} is false on purpose.</b> True means "only tell me about a
     * structure in a chunk that has not generated yet", which is what a treasure map wants and the
     * opposite of what a route wants: it would go quiet about the fortress the moment the run walked
     * near enough to load it.
     *
     * @param rings how far out to look, counted in PLACEMENT REGIONS — see {@link #FORTRESS_SEARCH_RINGS}
     */
    public static Located surveyStructure(SceneContext ctx,
                                          net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> where,
                                          net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.structure.Structure> what,
                                          String label, BlockPos from, int rings) {
        long startedNs = System.nanoTime();
        ServerLevel level = ctx.level().getServer().getLevel(where);
        if (level == null) {
            return new Located(new Found(label + " [dimension " + where.location() + " is not loaded]", null, -1),
                    millisSince(startedNs), rings);
        }
        BlockPos at;
        try {
            var registry = level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            var only = net.minecraft.core.HolderSet.direct(registry.getHolderOrThrow(what));
            var hit = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, only, from, rings, false);
            at = hit == null ? null : hit.getFirst();
        } catch (RuntimeException | LinkageError e) {
            // Carried, not dropped — the same lesson `locate` learned. A structure id nobody
            // registered and a seed that genuinely has none are two different findings, and a
            // survey that reports them identically is how "seed 5471 has no village" got written
            // down about a seed with several.
            String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Located(new Found(label + " [" + why.replace('\n', ' ') + "]", null, -1),
                    millisSince(startedNs), rings);
        }
        return new Located(
                new Found(label, at, at == null ? -1 : Math.sqrt(from.distSqr(at))),
                millisSince(startedNs), rings);
    }

    private static long millisSince(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    /**
     * How far out the fortress search may look, <b>counted in placement regions, not chunks</b>.
     *
     * <p>The unit is the trap, and it is invisible from the call site: {@code findNearestMapStructure}
     * multiplies this by the structure set's own {@code spacing} before it touches a coordinate, so
     * for the nether complexes (spacing 27 chunks) twelve rings is roughly 5 200 blocks and not the
     * twelve chunks it reads as. Passing {@code /locate}'s default of 100 here would be a search
     * 43 000 blocks wide.
     *
     * <p>Twelve is bounded rather than generous, and the cost is bounded a second way by the search
     * itself: it scans ring by ring and returns at the FIRST ring that yields anything, so a run only
     * pays for the empty rings that are genuinely empty. A fortress shares its placement with the
     * bastions and takes roughly two regions in five, which puts the usual answer in ring 0 or 1.
     */
    private static final int FORTRESS_SEARCH_RINGS = 12;

    /**
     * How far around spawn the survey forces chunks before it reads any of them.
     *
     * <p>Two more than the widest search reaches. The margin is the point: a chunk is only finished
     * once its neighbours exist, so the outermost ring of whatever gets loaded is the ring a scan
     * cannot trust, and a search that stops exactly where the loading stopped is reading that ring.
     */
    private static final int SURVEY_CHUNK_RADIUS = 6;

    /** How far out the THIRD iron vein may be. The first two are baked and recon checks them, so
     *  their radius must not move; this one is found fresh each run and may range further, which is
     *  what it takes for a third to exist at all on this seed. */
    private static final int THIRD_VEIN_RADIUS = 80;

    /**
     * How far out to look for lava. Wider than the walking landmarks, because there is no choice: a
     * swamp surface has none, and the run has to reach whatever the seed does have.
     *
     * <p><b>Forty-eight cost ROADMAP N4 half its bill, and nobody could have seen it.</b> The search
     * is horizontal — {@code radius} bounds dx and dz, never y — so at 48 it was answering "the
     * nearest lava in a 97-block-wide box", and reporting the answer as though it were the nearest
     * lava. Measured: at 48 it said {@code (84, -14, 47)}, a 77-block descent; at 80 it says
     * {@code (68, 27, -1)}, thirty-six. The second pool is 61 blocks out in z and was never a
     * candidate. Both answers are correct and only one is useful, and the run that dug 77 blocks
     * would have had no way to know a shallower pool existed — which is the whole argument for a
     * search that reaches past the first thing it can find.
     *
     * <p>Eighty, which is {@link #THIRD_VEIN_RADIUS} — the widest reach already proven to sit inside
     * what {@link #SURVEY_CHUNK_RADIUS} loads. Going further is not free and not safe: a scan that
     * reaches past the loaded square does not report "no lava out there", it reports whatever
     * ungenerated chunks say, which is nothing, in exactly the shape of a real answer.
     */
    private static final int LAVA_SEARCH_RADIUS = 80;

    /**
     * Top of the lava band.
     *
     * <p>It was 50, on the reasoning that "surface lava would have been found by the ordinary surface
     * scan already". That is wrong: the surface scan looks for the landmarks the ladder walks to, and
     * lava has never been in its list. 50 also sits below this swamp's own y≈63 surface, so a surface
     * lava lake was excluded by construction — a hole in the search nobody would have found by
     * reading its output, because a missing answer and an excluded one look identical.
     *
     * <p>Raising it changed nothing on this seed — what moved the answer was
     * {@link #LAVA_SEARCH_RADIUS}, and both pools this seed offers are underground — so this is a
     * correction to the QUESTION rather than to any measurement. Ninety now, which is above any
     * overworld surface. The rung is written to the answer either way: the descent is however many
     * blocks separate the bot from the lava, and zero is a legal value.
     */
    private static final int LAVA_SEARCH_TOP = 90;

    /** Bottom of the lava band: the deepslate lava level, which every seed has. Reaching it is an
     *  expedition rather than a rung, so a hit here is a finding about the BILL, not a plan. */
    private static final int LAVA_SEARCH_BOTTOM = -60;

    /** Everything a survey found, in the order the ladder needs it. */
    public static Map<String, Found> survey(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos spawnPos = level.getSharedSpawnPos();
        // Six chunks, not four, and the number is not a guess: the widest search below reaches 64
        // blocks (the gravel), which is four chunks, and a scan that loads exactly as far as it
        // looks reads the boundary chunks before they are finished. Measured, on ONE seed with ONE
        // build: two consecutive runs surveyed the second tree at 13m and at 17m. The 17m answer is
        // not a different tree, it is the same search failing to see the nearer one — and a survey
        // that is not reproducible makes every baked constant below it unfalsifiable, because
        // checkLandmark then flags the world rather than the drift.
        loadAround(level, spawnPos, SURVEY_CHUNK_RADIUS);

        Map<String, Found> out = new LinkedHashMap<>();
        Found tree = firstOf(level, spawnPos, LOG_IDS, 48, 8);
        out.put("firstTree", tree);
        // Measured from SPAWN like the first, not from the first tree: the wood rung walks from
        // spawn to one trunk and then to the other, so what matters is that both are near the bot's
        // starting point, not that they are near each other.
        //
        // And restricted to the FIRST tree's own kind, which is not fussiness. A swamp mixes oak and
        // birch, so the nearest second tree is often the other species — and measured, a run that
        // felled 7 logs still failed its next craft on a shortfall of one oak_log while holding birch, because
        // CraftProcess's resolver commits to one plank variant instead of treating the planks tag as
        // the recipe does. Two species is therefore two separate piles for planning purposes, and
        // seven logs in two piles buys less than five in one. Same-kind is the scripted way round
        // that; widening the resolver is an engine change and belongs to whoever owns the recipe
        // walk.
        out.put("secondTree", tree.where() == null ? new Found("log", null, -1)
                : nearest(level, spawnPos, tree.what(), 48, 8, tree.where(), 12));
        // Not the NEAREST stone — the nearest stone a shaft can reach, same correction the iron
        // rung already needed. A swamp puts water everywhere, and a bot standing in it floats
        // instead of dropping into the hole it just dug: measured, the first shaft step broke the
        // dirt clean through and the bot stayed at the same y for twelve steps.
        Found stone = nearestUnderDryGround(level, spawnPos, "minecraft:stone", 48, 4);
        out.put("firstStone", stone);
        out.put("stoneDescent", stone.where() == null
                ? new Found("dryDescent", null, -1)
                : dryDescentNear(level, stone.where(), stone.where().getY(), 32));
        out.put("firstCoal", nearest(level, spawnPos, "minecraft:coal_ore", 48, 12));
        // Not the NEAREST iron — the nearest iron a shaft can reach. See nearestUnderDryGround.
        Found iron = nearestUnderDryGround(level, spawnPos, "minecraft:iron_ore", 48, 12);
        out.put("firstIron", iron);
        // Where to dig down FROM, not only what to dig down TO. Dry by construction now that the
        // ore was chosen for it, so this is the ore's own column — kept as a separate constant
        // because the rung walks to the SURFACE and the ore is what it walks down to.
        out.put("ironDescent", iron.where() == null
                ? new Found("dryDescent", null, -1)
                : dryDescentNear(level, iron.where(), iron.where().getY(), 32));
        // A SECOND vein, because the first one is not enough ore. Measured: the iron rung mined the
        // first out and reported `broke 1/8, no reachable target` with two ingots banked, and the
        // portal kit above costs four. That is the terrain, not the driver — the answer a caller who
        // knows this seed would give is "there is more iron over there", so the survey says where.
        Found iron2 = iron.where() == null ? new Found("minecraft:iron_ore (second vein)", null, -1)
                : nearestUnderDryGround(level, spawnPos, "minecraft:iron_ore", 48, 12, iron.where(), 12);
        out.put("secondIron", iron2);
        out.put("secondIronDescent", iron2.where() == null
                ? new Found("dryDescent", null, -1)
                : dryDescentNear(level, iron2.where(), iron2.where().getY(), 32));
        // And a THIRD, kept clear of BOTH — avoiding only the second returns the first, measured.
        //
        // Searched wider than the other two, at THIRD_VEIN_RADIUS. At 48 blocks it came back
        // NOT_FOUND, which is a fact about the search rather than about the seed: iron is common,
        // and 48 blocks of a swamp simply holds two veins that are under diggable dry ground and
        // twelve apart. The first two keep their own radius deliberately — they are baked constants
        // that recon checks, and widening their search could move them.
        Found iron3 = iron2.where() == null ? new Found("minecraft:iron_ore (third vein)", null, -1)
                : nearestUnderDryGround(level, spawnPos, "minecraft:iron_ore", THIRD_VEIN_RADIUS, 12,
                        List.of(iron.where(), iron2.where()), 12);
        out.put("thirdIron", iron3);
        out.put("thirdIronDescent", iron3.where() == null
                ? new Found("dryDescent", null, -1)
                : dryDescentNear(level, iron3.where(), iron3.where().getY(), 32));
        // Gravel, because flint comes from it and PORTAL_KIT needs a flint-and-steel. Surveyed
        // under diggable ground for the same reason the ore is: a deposit under a pond is a
        // coordinate, not a plan.
        out.put("firstGravel", nearestUnderDryGround(level, spawnPos, "minecraft:gravel", 64, 12));
        out.put("firstWater", nearest(level, spawnPos, "minecraft:water", 48, 4));
        // Lava is a depth question, not a surface one — see nearestInBand. The band runs from just
        // under the surface down to the deepslate lava level, because both are real answers with
        // very different bills: a cave pool at y=10 is a rung, y=-54 is an expedition.
        out.put("firstLava", nearestInBand(level, spawnPos, "minecraft:lava",
                LAVA_SEARCH_RADIUS, LAVA_SEARCH_TOP, LAVA_SEARCH_BOTTOM));
        out.put("stronghold", locate(ctx, spawnPos, "minecraft:stronghold"));
        // A TAG, not a structure id. There is no `minecraft:village` — there are village_plains,
        // village_desert, village_savanna and three more, and `/locate structure minecraft:village`
        // is a syntax error. The first survey asked for the id and this reported NOT_FOUND, which
        // is the worst possible answer: a malformed question and a missing village are the same
        // string, and "seed 5471 has no village" is a claim somebody would have built on.
        out.put("village", locate(ctx, spawnPos, "#minecraft:village"));
        out.put("ruinedPortal", locate(ctx, spawnPos, "minecraft:ruined_portal"));
        return out;
    }

    /** The log kinds a first tree might be, in no particular order — biome decides which is there. */
    private static final List<String> LOG_IDS = List.of(
            "minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log",
            "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
            "minecraft:cherry_log", "minecraft:mangrove_log");

    /** The nearest of several block kinds — for "a tree", which is a different block per biome. */
    private static Found firstOf(ServerLevel level, BlockPos centre, List<String> ids, int radius, int band) {
        return firstOf(level, centre, ids, radius, band, null, 0);
    }

    /**
     * The same, skipping anything within {@code minAway} of {@code avoid}.
     *
     * <p>For the second of a thing, which for this ladder means the second TREE — same reason the
     * second iron vein needed it. A search that is merely "nearest" run twice returns the same
     * answer twice, and the caller that wants a second one is asking because the first is spent.
     * A tree's canopy is several blocks across, so the separation has to exceed one crown or the
     * "second" tree is the first tree's other side.
     */
    private static Found firstOf(ServerLevel level, BlockPos centre, List<String> ids, int radius,
                                 int band, BlockPos avoid, int minAway) {
        Found best = new Found("log", null, -1);
        for (String id : ids) {
            Found f = nearest(level, centre, id, radius, band, avoid, minAway);
            if (f.where() == null) continue;
            if (best.where() == null || f.distance() < best.distance()) best = f;
        }
        return best;
    }

    /** The survey rendered as pasteable Java, for the recon scene's log. */
    public static List<String> asConstants(Map<String, Found> survey) {
        List<String> out = new ArrayList<>();
        survey.forEach((name, found) -> out.add("    " + name + " = " + found.asConstant() + ";"));
        return out;
    }
}
