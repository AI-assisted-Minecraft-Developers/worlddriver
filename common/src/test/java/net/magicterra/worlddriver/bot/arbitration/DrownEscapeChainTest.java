package net.magicterra.worlddriver.bot.arbitration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ArbitrationConfig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The drowning escape chain's pure halves: its episode lifecycle (the latch IS the episode; cancel and death-clear
 * reach it through {@code resetEpisodeState}) and the overhang lateral escape decision
 * ({@link DrownEscapeChain#lateralEscapeDir}), which steers sideways only when the bot's column is capped and a
 * breathable column is actually reachable.
 */
class DrownEscapeChainTest {
    private boolean enabledBefore;
    private int enterBefore, releaseBefore;

    @BeforeEach
    void keepSettings() {
        enabledBefore = ArbitrationConfig.autoDrownEscape;
        enterBefore = ControlConfig.drownEscapeAirThreshold;
        releaseBefore = ArbitrationConfig.drownEscapeReleaseAir;
    }

    @AfterEach
    void restoreSettings() {
        ArbitrationConfig.autoDrownEscape = enabledBefore;
        ControlConfig.drownEscapeAirThreshold = enterBefore;
        ArbitrationConfig.drownEscapeReleaseAir = releaseBefore;
    }

    @Test
    void theLatchIsTheEpisode() {
        ArbitrationConfig.autoDrownEscape = true;
        ControlConfig.drownEscapeAirThreshold = 100;
        ArbitrationConfig.drownEscapeReleaseAir = 280;

        DrownEscapeChain ch = new DrownEscapeChain();
        assertNull(ch.episodePhase(), "fresh chain has no episode");
        assertFalse(ch.updateLatch(true, 300), "plenty of air: no episode");
        assertTrue(ch.updateLatch(true, 100), "critical air underwater latches");
        assertEquals("FLOATING", ch.episodePhase(), "live episode reads FLOATING");
        assertTrue(ch.updateLatch(true, 101), "chain holds through the hysteresis band");
        ch.resetEpisodeState();
        assertNull(ch.episodePhase(), "cancel/death-clear (pure half) clears the episode");
        assertTrue(
                ch.updateLatch(true, 99),
                "still underwater+critical after a cancel re-arms next evaluation (fresh-instance contract)");
        ch.resetEpisodeState();
        ArbitrationConfig.autoDrownEscape = false;
        assertFalse(ch.updateLatch(true, 10), "setting off: chain never latches");
    }

    /**
     * The scheduler hands the channel from a user task to the escape when air runs low, and back once the bot has
     * surfaced and breathed: one preemption, one release, no oscillation (gap#76, live death #25). The body is a column
     * of water the bot rises through while the escape holds the channel and sinks back into while it does not; the air
     * drains a point a tick with the eyes under and refills four a tick above, as vanilla's.
     */
    @Test
    void theEscapePreemptsTheUserTaskAndHandsTheChannelBack() {
        ArbitrationConfig.autoDrownEscape = true;
        ControlConfig.drownEscapeAirThreshold = 100;
        ArbitrationConfig.drownEscapeReleaseAir = 280;

        double surface = 6.0; // eyes above this row are out of the water
        double[] eye = {1.0};
        int[] air = {150};
        DrownEscapeChain drown = new DrownEscapeChain();
        drown.sensorForTest(() -> eye[0] < surface, () -> air[0]);
        int[] interrupts = new int[1];
        Chain user = new Chain() { // the mining task the bot drowned in
                    @Override
                    public String name() {
                        return "user";
                    }

                    @Override
                    public float priority(Body body, WorldView w, BotState st) {
                        return Priorities.USER;
                    }

                    @Override
                    public void tick(Body body, WorldView w, BotState st) {}

                    @Override
                    public void onInterrupt(Body body, Chain by) {
                        interrupts[0]++;
                    }
                };
        ProcessScheduler sched = new ProcessScheduler();
        sched.register(drown);
        sched.register(user);
        BotState st = new BotState();

        List<String> flips = new ArrayList<>();
        String prev = "";
        int preemptAir = -1, releaseAir = -1, minAir = Integer.MAX_VALUE;
        boolean underAtRelease = true;
        for (int t = 0; t < 1200 && releaseAir < 0; t++) {
            sched.tick(null, null, st);
            String cur = String.valueOf(sched.currentName());
            if (!cur.equals(prev)) {
                flips.add(cur);
                prev = cur;
            }
            boolean escape = DrownEscapeChain.NAME.equals(sched.currentName());
            if (escape && preemptAir < 0) {
                preemptAir = air[0];
            }
            if (preemptAir >= 0 && !escape) {
                releaseAir = air[0];
                underAtRelease = eye[0] < surface;
            }
            // The escape's held jump floats the bot; with it let go the bot settles back toward the bottom.
            eye[0] = escape ? Math.min(eye[0] + 0.1, surface + 0.5) : Math.max(eye[0] - 0.05, 1.0);
            air[0] = eye[0] < surface ? Math.max(air[0] - 1, -20) : Math.min(air[0] + 4, 300);
            minAir = Math.min(minAir, air[0]);
        }

        assertEquals(List.of("user", DrownEscapeChain.NAME, "user"), flips, "user, the escape, user: no oscillation");
        assertTrue(
                preemptAir <= ControlConfig.drownEscapeAirThreshold,
                "preempted at air " + preemptAir + ", above the threshold");
        assertEquals(1, interrupts[0], "the preemption interrupts the user task once");
        assertTrue(minAir > 0, "air reached " + minAir + ": the escape did not beat the drown clock");
        assertTrue(releaseAir >= 0, "the escape never released the channel after surfacing");
        assertFalse(
                underAtRelease && releaseAir < ArbitrationConfig.drownEscapeReleaseAir,
                "released underwater below the release level (air=" + releaseAir + ")");
        assertNull(drown.episodePhase(), "the episode clears on release");
    }

    /** A world of explicit solid and water cells; everything else is air. */
    private static WorldView gridWorld(Set<BlockPos> solid, Set<BlockPos> water) {
        return new WorldView() {
            @Override
            public boolean isSolid(BlockPos p) {
                return solid.contains(p);
            }

            @Override
            public boolean isPassable(BlockPos p) {
                return !solid.contains(p);
            }

            @Override
            public boolean isHazard(BlockPos p) {
                return false;
            }

            @Override
            public boolean isWater(BlockPos p) {
                return water.contains(p);
            }

            @Override
            public boolean isClimbable(BlockPos p) {
                return false;
            }
        };
    }

    /** The undercut that drowned a bot one block from open water: column capped, the one open neighbour +x. */
    @Test
    void aCappedPocketSteersToItsOnlyOpening() {
        Set<BlockPos> solid = new HashSet<>(), water = new HashSet<>();
        for (int y = 59; y <= 62; y++) {
            water.add(new BlockPos(0, y, 0)); // bot column: water …
        }
        for (int y = 63; y <= 66; y++) {
            solid.add(new BlockPos(0, y, 0)); // … capped by the shelf
        }
        for (int y = 59; y <= 62; y++) {
            water.add(new BlockPos(1, y, 0)); // +x: water, air above (open)
        }
        for (int[] c : new int[][] {{-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            for (int y = 59; y <= 62; y++) {
                water.add(new BlockPos(c[0], y, c[1]));
            }
            for (int y = 63; y <= 66; y++) {
                solid.add(new BlockPos(c[0], y, c[1])); // capped
            }
        }
        int[] dir = DrownEscapeChain.lateralEscapeDir(gridWorld(solid, water), 0, 60, 0);
        assertTrue(
                dir != null && dir[0] == 1 && dir[1] == 0,
                "capped pocket, sole opening +x → lateral {+1,0} (got " + (dir == null ? "null" : dir[0] + "," + dir[1])
                        + ")");
    }

    @Test
    void openWaterOrOpenSkyFloatsStraightUp() {
        Set<BlockPos> deep = new HashSet<>();
        for (int y = 50; y <= 62; y++) {
            deep.add(new BlockPos(0, y, 0));
        }
        assertNull(
                DrownEscapeChain.lateralEscapeDir(gridWorld(new HashSet<>(), deep), 0, 55, 0),
                "deep open water is NOT capped → null (float straight up)");

        Set<BlockPos> shallow = new HashSet<>();
        for (int y = 59; y <= 62; y++) {
            shallow.add(new BlockPos(0, y, 0)); // air at y63
        }
        assertNull(
                DrownEscapeChain.lateralEscapeDir(gridWorld(new HashSet<>(), shallow), 0, 60, 0),
                "open surface directly above → null (surface in place)");
    }

    @Test
    void aBoxedCappedPocketFallsBackToTheLidBreak() {
        Set<BlockPos> solid = new HashSet<>(), water = new HashSet<>();
        for (int y = 59; y <= 62; y++) {
            water.add(new BlockPos(0, y, 0));
        }
        for (int y = 63; y <= 66; y++) {
            solid.add(new BlockPos(0, y, 0));
        }
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int y = 58; y <= 66; y++) {
                    solid.add(new BlockPos(dx, y, dz)); // solid all around
                }
            }
        }
        assertNull(
                DrownEscapeChain.lateralEscapeDir(gridWorld(solid, water), 0, 60, 0),
                "capped with no open neighbour in range → null (lid-break fallback)");
    }

    /**
     * A column that CAN surface with rock in between, transcribed from a live run's saved region (origin = the bot's
     * cell 81,59,82). The old ring scan steered at 81,59,80 — air, but behind the stone at 81,59,81 and under stone at
     * its head — and so withheld the lid-break, the one arm that always escapes.
     */
    @Test
    void aBreathableColumnBehindRockIsNotAnEscape() {
        // z rows (80..84) of x columns (79..83) at y=59; '#' solid, '~' water, '.' air.
        String[] floorY59 = {
            "#~.##", // z=80  81,59,80 is the '.' — the column the run steered at
            "#~###", // z=81  81,59,81 is the '#' between it and the bot
            "##~##", // z=82  81,59,82 — the bot's pocket
            "#####", // z=83
            "#####", // z=84
        };
        Set<BlockPos> solid = new HashSet<>(), water = new HashSet<>();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                char c = floorY59[dz + 2].charAt(dx + 2);
                if (c == '#') {
                    solid.add(new BlockPos(dx, 59, dz));
                } else if (c == '~') {
                    water.add(new BlockPos(dx, 59, dz));
                }
            }
        }
        water.add(new BlockPos(0, 60, 0)); // 81,60,82 — water over the bot
        solid.add(new BlockPos(0, 61, 0)); // 81,61,82 — dirt: the lid ⇒ capped
        solid.add(new BlockPos(0, 60, -2)); // 81,60,80 — stone: the picked column is not standable
        water.add(new BlockPos(-1, 60, -2)); // 80,60,80 — lake
        solid.add(new BlockPos(-1, 61, -2)); // 80,61,80 — dirt caps it ⇒ not breathable
        for (int y = 60; y <= 62; y++) {
            water.add(new BlockPos(-1, y, -1)); // 80,60..62,81 — lake
        }
        solid.add(new BlockPos(-1, 63, -1)); // 80,63,81 — grass_block caps it
        WorldView w = gridWorld(solid, water);

        int[] dir = DrownEscapeChain.lateralEscapeDir(w, 0, 59, 0);
        assertNull(
                dir,
                "breathable column behind solid rock → null, so the lid-break runs (got "
                        + (dir == null ? "null" : dir[0] + "," + dir[1]) + ")");

        // The positive control: `unreachable` is the old ring scan. It must still name the dead end the live
        // run steered at, or the case above passes only because nothing is breathable anywhere.
        DrownEscapeChain.LateralEscape scan = DrownEscapeChain.lateralEscapeScan(w, 0, 59, 0);
        assertNotNull(scan.unreachable(), "the dead end is named");
        assertTrue(
                scan.unreachable().getX() == 0 && scan.unreachable().getZ() == -2,
                "the dead end is the column the live run steered at — expected x=0 z=-2, got " + scan.unreachable());
    }
}
