package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.MineProcess;
import net.minecraft.core.BlockPos;

/**
 * Rung 08 — the furnace. Eight of the cobblestone the stone rung banked.
 *
 * <p>Its own file for the same reason {@link JourneyBedRung}, {@link JourneyPortalRung} and
 * {@link JourneyNetherRungs} are: a rung is the unit a reader comes here holding. It moved out of
 * {@code WorldDriverJourneyScenes} when that file came within 85 lines of the 3000-line budget, and
 * the cut is along the rung boundary rather than at a convenient line number — every member kept its
 * body, its javadoc and its evidence keys, so a results file from before the move reads identically
 * to one from after.
 *
 * <p><b>What deliberately did NOT come along.</b> {@code ensureCraftingTable}, {@code topUpWood} and
 * {@code walkToTheTreesAndCut} were written under this rung's banner and are not this rung's: every
 * craft on the ladder reaches them through {@code WorldDriverJourneyScenes.craftKeepingTheTable}.
 * Moving them here would have put shared plumbing behind a rung's name, which is the same rot in the
 * other direction — so they stayed, under a banner that now says what they are.
 */
final class JourneyFurnaceRung {

    private JourneyFurnaceRung() { }

    /** What a furnace costs. Named because the top-up below and the stone rung's bill both quote it. */
    private static final int FURNACE_COBBLE = 8;

    /**
     * Craft a furnace from the cobblestone already in the bag, mining the shortfall only if there is one.
     *
     * <p><b>The happy path still does no walking and no mining</b>, and that is deliberate: a rung
     * that is purely a 3×3 craft is the cheapest possible regression sensor for the station-menu
     * seam — if a server body's table ever stops opening again, this is the rung that says so in eight
     * seconds rather than the iron rung saying it after a two-minute dig. The top-up is a no-op when
     * the bag is full enough, so that property survives.
     *
     * <p><b>Why a top-up exists at all.</b> The stone rung's bill (see its {@code isAtLeast(20)})
     * is「pickaxe 3 + exit pillar ~9 + furnace 8」— it budgets nothing for rungs 6 and 7, and
     * measured 2026-08-23 those two spent <b>ten</b> cobblestone between them: the stone rung banked
     * 14 and this rung opened holding 4. Raising the stone rung's quota would only move the guess;
     * asking here, where the requirement is known exactly, is the reading that cannot go stale.
     *
     * <p><b>Both branches record.</b> A row that appears only when the top-up fires cannot tell a
     * reader「it did not happen」from「it was not logged」, and the whole point of this row is to keep
     * the upstream leak visible instead of quietly paying for it.
     */
    static void furnace(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.FURNACE);
        rig.generousPathfinding();
        rig.attempting("用已有圆石合成熔炉");
        int before = rig.carrying("minecraft:cobblestone");
        rig.evidence("cobblestone.before", before);

        if (before >= FURNACE_COBBLE) {
            rig.evidence("furnace.topUp", "不需要 —— 开场 " + before + " ≥ " + FURNACE_COBBLE
                    + "（这一趟仍是纯合成传感器）");
            craftFurnace(ctx, rig);
            return;
        }

        int need = FURNACE_COBBLE - before;
        // PHRASED AS A SHORTFALL, NOT AS A DELIVERY. This row used to read「补料 N 块」— perfect
        // tense — and was written BEFORE the mine ran. Measured 2026-08-24 it said that over
        // `furnace.topUp.after=7`: nothing had been added, and the sentence describing the run's
        // INTENT was the one a reader quoted as its OUTCOME. What it achieved is recorded below,
        // after the only code that can know it.
        rig.evidence("furnace.topUp", "差 " + need + " 块 —— 开场 " + before + "，不足 "
                + FURNACE_COBBLE + "；上游漏了料，这一趟不是纯合成");
        // Does the band the scan will sweep even HOLD stone? Same vertical radius MineProcess uses,
        // so this asks the scan's own question rather than a nearby one — and it is the reading that
        // separates the two ways the cheap attempt can come back empty. Measured, the failing run
        // had the body sixteen courses up a tower at y=78 over terrain at 63, which put the whole
        // ±8 band in the air: "none" here means the world under the body's feet was never in scope,
        // and a coordinate means there WAS a candidate and something else refused it.
        rig.evidence("furnace.topUp.stoneInBand", String.valueOf(
                rig.nearestBlock("minecraft:stone", 32, BotConfig.mineSearchVerticalRadius)));
        // Radius 32, not the stone rung's 16: that one mined from the bottom of its own shaft where
        // stone is everywhere, and its comment already records that at a swamp SURFACE「the radius,
        // not the quota, is what binds」. This is the cheap attempt on purpose — if it comes back
        // short the evidence says so by name, and the fallback below is the stone rung's proven
        // shape rather than another guess.
        rig.attempting("补圆石：地表 32 格内挖不到 " + need + " 块石头");
        rig.drive(new MineProcess(List.of("minecraft:stone"), need, 32), 12_000, () -> {
            int after = rig.carrying("minecraft:cobblestone");
            rig.evidence("furnace.topUp.after", after);
            rig.evidence("furnace.topUp.mined", (after - before) + " 块（要 " + need + "）");
            // The miner's own reason for stopping. Its absence is why the failing run could not say
            // whether the scan saw nothing, could not reach what it saw, or lacked the tool — three
            // answers that read identically as「跑完」in journey.helm.endings.
            rig.evidence("furnace.topUp.error", String.valueOf(rig.slotError("mine")));
            if (after >= FURNACE_COBBLE) { craftFurnace(ctx, rig); return; }
            topUpAtTheSurveyedStone(ctx, rig, FURNACE_COBBLE - after);
        });
    }

    /**
     * The top-up's second answer: go where the survey certified there is stone.
     *
     * <p>The cheap attempt above mines from wherever the rung below happened to leave the body, and
     * that is exactly the assumption that failed — a body standing above the terrain has no stone in
     * the scan's ±8 band no matter how wide the horizontal radius is. This is the same four steps
     * the stone rung runs and passes with (walk to the column, sink, mine, climb out), on the same
     * surveyed coordinates, so it inherits that rung's corrections rather than re-deriving them:
     * a {@code Goal.XZ} with tolerance 0 because the survey certified one column dry, and
     * {@code allowPlace=false} through the descent because a paving walker will not sink.
     *
     * <p><b>The quota is computed, not guessed,</b> because the stone rung already paid for guessing
     * it: 「paying the exit out of the furnace's share is what stranded a run at the bottom of its
     * own hole」. Climbing out costs about one block per course, so the bill is the shortfall plus
     * the depth this shaft will sink plus a margin.
     *
     * <p>Insurance, and expected never to fire once the bed rung stops leaving the body on a tower.
     * A run in which these rows are absent is a run that did not need them — which is why the
     * shortfall row above is written whether or not this is reached.
     */
    private static void topUpAtTheSurveyedStone(SceneContext ctx, JourneyRig rig, int shortfall) {
        BlockPos stone = JourneyRoute.firstStone;
        BlockPos shaft = JourneyRoute.stoneDescent;
        rig.evidence("furnace.stone.shortfall", shortfall);
        rig.attempting("去勘测过的石头补料：走不到柱 " + shaft.getX() + "," + shaft.getZ());
        rig.drive(new IntentProcess(new Intent(new Goal.XZ(shaft.getX(), shaft.getZ(), 0))), 8_000, () -> {
            BlockPos at = rig.player().blockPosition();
            double away = Math.hypot(at.getX() - shaft.getX(), at.getZ() - shaft.getZ());
            rig.evidence("furnace.stone.arrived", at.toShortString() + "，离柱 " + Math.round(away) + " 格");
            if (away > 5) {
                // Do NOT fail here. The rung's contract is a furnace, and the craft below reports
                // the shortfall by name; failing on「走不到」would replace the real bill with a
                // walking diagnosis and lose the cobblestone count that says how short it was.
                craftFurnace(ctx, rig);
                return;
            }
            int depth = Math.max(0, at.getY() - (stone.getY() + 1));
            int quota = shortfall + depth + 4;
            rig.evidence("furnace.stone.quota", quota + " = 缺 " + shortfall + " + 井深 " + depth + " + 余量 4");
            rig.attempting("挖竖井下到石层：身体没能随井下降");
            boolean place = BotConfig.allowPlace;
            BotConfig.allowPlace = false;
            JourneyShaft.descendByMining(rig, stone.getY() + 1, () -> {
                BotConfig.allowPlace = place;
                rig.evidence("furnace.stone.landedY", rig.player().blockPosition().getY());
                rig.attempting("挖石头：MineProcess 拿不到圆石");
                rig.drive(new MineProcess(List.of("minecraft:stone"), quota, 16), 16_000, () -> {
                    rig.evidence("furnace.stone.cobble", rig.carrying("minecraft:cobblestone"));
                    rig.evidence("furnace.stone.error", String.valueOf(rig.slotError("mine")));
                    // Climb out BEFORE crafting, for the reason the stone rung records: a one-wide
                    // shaft has no free cell to stand a table in.
                    JourneyShaft.climbOut(rig, shaft.getY(), "furnace.stone.exit",
                            () -> craftFurnace(ctx, rig));
                });
            });
        });
    }

    /** The 3×3 craft itself — the part that was this rung before it grew a top-up. */
    private static void craftFurnace(SceneContext ctx, JourneyRig rig) {
        WorldDriverJourneyScenes.craftKeepingTheTable(rig, "minecraft:furnace", 6_000, () -> {
            int furnaces = rig.carrying("minecraft:furnace");
            rig.evidence("furnace", furnaces);
            // The two readings that made the last failure legible in one run instead of three. A
            // rung holding 24 cobblestone and crafting nothing is not a materials problem, and
            // "furnace=0" alone cannot say which of the station, the grid or the process it was.
            rig.evidence("craftingTable", rig.carrying("minecraft:crafting_table"));
            rig.evidence("craft.lastError", String.valueOf(rig.slotError("craft")));
            ctx.expect(furnaces).as("furnaces crafted").isAtLeast(1);
            JourneyStation.reclaimTableIfLeftStanding(rig, () -> rig.reach("熔炉 ×" + furnaces + " 到手"));
        });
    }
}
