package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRun;
import net.magicterra.worlddriver.bot.stagewright.kit.HumanBest;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse.Goal;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The bot against the human on the benchmark's natural-terrain route ({@code docs/dev/benchmark.md}), on the real
 * client's player, in a new world made from the course's seed with default settings, as the bench's save was. The run
 * is at the course file's own positions, so the class has its scene to itself: the walk changes the terrain it crosses,
 * and the next run gets a new world.
 */
@Scene("wd.crossCountryCourse")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_CLOCK)
@ResourceLock(Locks.TICK_RATE)
class TerrainCourseTest {

    /** {@code scripts/bench/courses/cross-country.json}; the shipped settings, no {@code botSettings}. */
    static final RouteCourse CROSS_COUNTRY = new RouteCourse(
            "cross-country",
            new Vec3(1574.5, 66, -101.5),
            160f,
            List.of(),
            new Goal(new Vec3(1385, 116, -625), 3, true),
            Map.of(),
            600 * CourseRecorder.TICKS_PER_SECOND);

    /** {@code cross-country}'s {@code terrain.box}, x and z: the chunks generated before the run. */
    static final int MIN_X = 1370;

    static final int MAX_X = 1590;

    static final int MIN_Z = -640;

    static final int MAX_Z = -88;

    /** Enough for water to fall from the surface to the bottom of the world and spread out there. */
    private static final int SETTLE_ROUNDS = 1024;

    /**
     * {@code cross-country}'s human baseline: the best of its three finished human runs, 142.575 s, which is also the
     * one with the fewest errors, 35 (three stalls, thirty-one bumps and one fall).
     */
    static final HumanBest CROSS_COUNTRY_HUMAN = new HumanBest("cross-country", 142.575, 35);

    /**
     * About 620 blocks over hills to the top of a cliff, with the route kit and every move the bot ships with: the bot
     * is faster than the human's best run and makes no more errors than the human's fewest.
     */
    @Test
    void crossCountryBeatsTheHumanBest(Game game, Client client, Player player) {
        long began = System.nanoTime();
        int settled = generateCourse(game);
        TestMod.LOG.info(
                "[course] cross-country: generated the course and settled {} fluid ticks before the clock in {} ms",
                settled,
                (System.nanoTime() - began) / 1_000_000);
        CourseRun run = CROSS_COUNTRY.runBot(game, client, player);
        CROSS_COUNTRY_HUMAN.judge(game, run, CROSS_COUNTRY.score(run));
    }

    /**
     * Generate every chunk of the course before the clock starts: the bench's save had them on disk, and a chunk
     * generated while the player walks into it reaches the client later than one loaded from disk. A ring of one chunk
     * more round the course, so that {@link #settleFluids} post-processes each course chunk with its neighbours there,
     * as the game does. Then their fluids are settled ({@link #settleFluids}).
     *
     * <p>A status at a time, one chunk per hop: a chunk asked for at full status at once, or a column of them at one
     * status, holds the server thread past the watchdog's minute on a slow machine. The loop's fixed order keeps where
     * the water starts the same from run to run; worker threads generating all at once did not.
     *
     * @return the fluid ticks settled
     */
    static int generateCourse(Game game) {
        for (ChunkStatus status : ChunkStatus.getStatusList()) {
            for (int cx = SectionPos.blockToSectionCoord(MIN_X) - 1;
                    cx <= SectionPos.blockToSectionCoord(MAX_X) + 1;
                    cx++) {
                for (int cz = SectionPos.blockToSectionCoord(MIN_Z) - 1;
                        cz <= SectionPos.blockToSectionCoord(MAX_Z) + 1;
                        cz++) {
                    int x = cx;
                    int z = cz;
                    game.runOnServer(s -> s.overworld().getChunk(x, z, status, true));
                }
            }
        }
        return game.onServer(s -> settleFluids(s.overworld()));
    }

    /**
     * Let the course's water finish flowing before the clock starts. A chunk fresh from the generator keeps a list of
     * cells to post-process, its fluids among them, and the game works through it only when the chunk starts ticking
     * near a player; the water then spreads a cell every five ticks. On cross-country it was still spreading down the
     * slope at x 1517-1521, z -226..-221 ten seconds into the run, so the plan made from (1536, 73, -184) saw a
     * different shore each run and took the slow line by 1518 whenever the water had not yet reached (1520, 69, -222).
     * Settling it first makes every run start from the same world state.
     *
     * <p>This does the game's work for each course chunk itself, so its own pass later finds nothing left: it releases
     * the fluid ticks the generator scheduled (they wait in the chunk, out of the tick queue, until it starts ticking,
     * and while they wait no other tick can be scheduled at their cells) and post-processes the chunk. Only the fluid
     * ticks: the block ticks waiting beside them (sand, gravel) stay for the game to release, so none come due all at
     * once on the chunk's first tick. Then it runs the fluid ticks in rounds that stand in for game ticks: each runs
     * every tick then queued in the course chunks, in drain order, and what they schedule runs in the next round.
     * Delays are ignored, which only matters where water meets lava.
     *
     * <p>The ring round the course is not post-processed, since its own neighbours are not generated, and its water is
     * stopped instead: its waiting fluid ticks are released and dropped with every tick scheduled there each round, so
     * neither its own water nor what the course pushes into it is left moving to flow back across the course edge.
     * Water there stays where it stood until a block beside it changes; the start and the goal are each at least
     * fifteen blocks inside the course chunks.
     *
     * <p>One hop to the server thread for all of it: the chunks are held only by the short tickets that loading them
     * took, so the tick containers gathered here are not kept past it.
     *
     * @return the fluid ticks run
     */
    static int settleFluids(ServerLevel level) {
        List<LevelChunkTicks<Fluid>> containers = new ArrayList<>();
        int minCx = SectionPos.blockToSectionCoord(MIN_X);
        int maxCx = SectionPos.blockToSectionCoord(MAX_X);
        int minCz = SectionPos.blockToSectionCoord(MIN_Z);
        int maxCz = SectionPos.blockToSectionCoord(MAX_Z);
        for (int cx = minCx - 1; cx <= maxCx + 1; cx++) {
            for (int cz = minCz - 1; cz <= maxCz + 1; cz++) {
                if (level.getChunk(cx, cz, ChunkStatus.FULL, true) instanceof LevelChunk chunk
                        && chunk.getFluidTicks() instanceof LevelChunkTicks<Fluid> ticks) {
                    ticks.unpack(level.getGameTime());
                    if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) {
                        chunk.postProcessGeneration();
                        containers.add(ticks);
                    }
                }
            }
        }
        // The course chunks and the ring: clearing it drops the ring's ticks along with the ones about to run.
        BoundingBox box = new BoundingBox(
                SectionPos.sectionToBlockCoord(minCx - 1),
                level.getMinBuildHeight(),
                SectionPos.sectionToBlockCoord(minCz - 1),
                SectionPos.sectionToBlockCoord(maxCx + 1, 15),
                level.getMaxBuildHeight(),
                SectionPos.sectionToBlockCoord(maxCz + 1, 15));
        int run = 0;
        for (int round = 0; round < SETTLE_ROUNDS; round++) {
            List<ScheduledTick<Fluid>> due = new ArrayList<>();
            for (LevelChunkTicks<Fluid> ticks : containers) {
                ticks.getAll().forEach(due::add);
            }
            level.getFluidTicks().clearArea(box);
            if (due.isEmpty()) {
                return run;
            }
            due.sort(ScheduledTick.DRAIN_ORDER);
            for (ScheduledTick<Fluid> t : due) {
                FluidState state = level.getFluidState(t.pos());
                if (state.getType() == t.type()) {
                    state.tick(level, t.pos());
                }
                run++;
            }
        }
        TestMod.LOG.warn("[course] cross-country: fluids still moving after {} rounds", SETTLE_ROUNDS);
        return run;
    }
}
