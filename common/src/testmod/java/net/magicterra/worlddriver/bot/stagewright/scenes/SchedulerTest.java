package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

/**
 * The scheduler family's one world test. Its pure gate and chain-state matrices (retreat enter/release, terminal
 * labels, anti-suffocation, episode cancel, combat grace, frail gate, urgent bid, the held-process lifecycle, cancel
 * routing, AutoTool grace, nearest-first scan) are unit tests: {@code bot/arbitration/RetreatGateMatrixTest},
 * {@code ChainGateMatrixTest}, {@code DuskSecureLifecycleTest}, {@code CancelRoutingTest},
 * {@code bot/control/WalkVerdictArrivalTest}, {@code bot/behavior/reflex/ReflexGateMatrixTest} and
 * {@code game/observe/NearestFirstScanBudgetTest}.
 */
@Scene("wd.scenes")
class SchedulerTest {

    /**
     * The re-arm incident in the world (gap#75-b), over a real bunker dig on a server bot. A bunker dig is cut off
     * mid-shaft (live: a user goto preempted duskSecure's dig and was then cancelled), leaving the bot idle IN its
     * half-dug, unsealed pit; a fresh bunker started from the pit floor — what duskSecure does once re-armed — must
     * finish the job: dig on, carve, step in, plug, genuinely SEALED.
     *
     * <p>The chain half — that the preemption arms a re-try and that the armed start gate waives the shaft's own
     * cornered reading — is {@code DuskSecureLifecycleTest}, which also reads the half-dug shaft's HazardField. Here
     * the dig is cut off with {@code mc.bot.cancel} and redone with {@code mc.bot.bunker}; what the world must show is
     * the same.
     */
    @Test
    void duskSecureHeldProcessLifecycle(Game game, @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot) {
        Settings.pin(game).set("allowBreak", true).set("allowPlace", true).set("navigatorDebug", false);
        final int floorY = 0;

        // Dirt slab with a 1×2 standing slot at the centre (serverBunkerArena shape), deep enough for the re-armed
        // dig to deepen and carve an embedded niche.
        plot.fill(-3, floorY - 6, -3, 3, floorY + 1, 3, Blocks.DIRT);
        plot.place(0, floorY + 1, 0, Blocks.AIR);

        SceneBot bot = SceneBot.spawn(game, plot.at(0, floorY + 1, 0));
        bot.give(Items.DIRT, 64); // server breaks drop nothing → pre-stock plug blocks
        int floor = plot.at(0, floorY, 0).getY();

        // The whole incident inside one server task, as the old scene ran it: no world tick between the bot's own.
        game.runOnServer(s -> {
            // ① dusk: the dig starts (live seq1945) — run until mid-shaft.
            bot.bunker(2);
            int t = 0;
            for (; t < 400 && bot.player().blockPosition().getY() > floor; t++) {
                bot.tick();
            }
            assertTrue(
                    bot.player().blockPosition().getY() <= floor,
                    "rig: the dig never went a block down in " + t + " ticks");

            // ② cut off mid-dig (live: endReason=INTERRUPTED "preempted by user").
            bot.cancel();
            assertFalse(bot.busy(), "rig: the cancel must drop the dig");

            // ③ the bot is idle IN the half-dug unsealed shaft. "Still exposed" ground truth as a direct block scan —
            // canSeeSky would read the light engine, which hasn't recomputed the freshly-carved column within this
            // synchronous tick (live it reads true: duskExposed kept firing).
            BlockPos foot = bot.player().blockPosition();
            List<BlockPos> above = new ArrayList<>();
            for (int y = foot.getY() + 1; y <= floor + 6; y++) {
                above.add(new BlockPos(foot.getX(), y, foot.getZ()));
            }
            assertTrue(
                    bot.cells(above).stream().noneMatch(BotDebugContracts.Cell::solid),
                    "rig: the unsealed shaft must still be open to the sky — foot=" + foot + ", cut off at tick " + t);

            // ④ re-arm exactly as duskSecure would: a fresh bunker from the pit floor must finish the job — dig on,
            // carve, step in, plug: genuinely SEALED.
            bot.bunker(2);
            for (int i = 0;
                    i < 800 && !"SEALED".equals(bunkerSlot(bot).endReason().orElse(null));
                    i++) {
                bot.tick();
            }
            ControlContracts.Slot slot = bunkerSlot(bot);
            BlockPos end = bot.player().blockPosition();
            assertTrue(
                    "SEALED".equals(slot.endReason().orElse(null)),
                    "gap#75-b: the re-armed bunker failed to seal — " + "endReason="
                            + slot.endReason().orElse(null) + " lastError="
                            + slot.lastError().orElse(null));
            assertTrue(enclosed(bot, end), "gap#75-b: SEALED verdict but the pocket is not enclosed at " + end);
        });
    }

    private static ControlContracts.Slot bunkerSlot(SceneBot bot) {
        return bot.status().slots().bunker();
    }

    /**
     * The bunker's enclosure ground truth, read through the bot's world view: the 4 horizontal neighbours of the foot
     * cell, the 4 of the head cell, and the cell above the head all solid.
     */
    private static boolean enclosed(SceneBot bot, BlockPos foot) {
        BlockPos head = foot.above();
        return bot
                .cells(List.of(
                        foot.north(),
                        foot.south(),
                        foot.east(),
                        foot.west(),
                        head.north(),
                        head.south(),
                        head.east(),
                        head.west(),
                        head.above()))
                .stream()
                .allMatch(BotDebugContracts.Cell::solid);
    }
}
