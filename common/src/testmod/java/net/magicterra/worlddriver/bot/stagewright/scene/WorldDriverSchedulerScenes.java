package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugApi;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The scheduler family's one world scene. Its pure gate and chain-state matrices (retreat enter/release,
 * terminal labels, anti-suffocation, episode cancel, combat grace, frail gate, urgent bid, the held-process
 * lifecycle, cancel routing, AutoTool grace, nearest-first scan) are unit tests now:
 * {@code bot/arbitration/RetreatGateMatrixTest}, {@code ChainGateMatrixTest}, {@code DuskSecureLifecycleTest},
 * {@code CancelRoutingTest}, {@code bot/control/WalkVerdictArrivalTest},
 * {@code bot/behavior/reflex/ReflexGateMatrixTest} and {@code game/observe/NearestFirstScanBudgetTest}.
 */
public final class WorldDriverSchedulerScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.duskSecureHeldProcessLifecycle", 400, WorldDriverSchedulerScenes::duskSecureRearmWorldLeg));
    }

    /**
     * The re-arm incident in the world (gap#75-b), over a real bunker dig on a server bot. A bunker dig is
     * cut off mid-shaft (live: a user goto preempted duskSecure's dig and was then cancelled), leaving the
     * bot idle IN its half-dug, unsealed pit; a fresh bunker started from the pit floor — what duskSecure
     * does once re-armed — must finish the job: dig on, carve, step in, plug, genuinely SEALED.
     *
     * <p>The chain half — that the preemption arms a re-try and that the armed start gate waives the
     * shaft's own cornered reading — is {@code DuskSecureLifecycleTest}, which also reads the half-dug
     * shaft's HazardField. Here the dig is cut off with {@code mc.bot.cancel} and redone with
     * {@code mc.bot.bunker}; what the world must show is the same.
     */
    private static void duskSecureRearmWorldLeg(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", false);
        Harness.clearBots(ctx);
        // Scrub the whole dirt slab + dig shaft on any exit (#40 persistent-world lesson).
        ctx.cleanup(() -> {
            for (int dx = -4; dx <= 4; dx++)
                for (int dy = -8; dy <= 8; dy++)
                    for (int dz = -4; dz <= 4; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Dirt slab with a 1×2 standing slot at the centre (serverBunkerArena shape),
        // deep enough for the re-armed dig to deepen + carve an embedded niche.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -6; dy <= 1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.DIRT.defaultBlockState());
                for (int dy = 2; dy <= 6; dy++)   // defensive air box above the slab
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, floorY + 1, cz), Blocks.AIR.defaultBlockState());

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));  // server breaks drop nothing → pre-stock plug blocks

        // ① dusk: the dig starts (live seq1945) — run until mid-shaft.
        bunker(bot);
        int t = 0;
        for (; t < 400 && fp.blockPosition().getY() > floorY; t++) bot.tick();
        if (fp.blockPosition().getY() > floorY)
            ctx.fail("rig: dig never went a block down in " + t + " ticks");

        // ② cut off mid-dig (live: endReason=INTERRUPTED "preempted by user").
        bot.cancel();
        if (bot.busy())
            ctx.fail("rig: the cancel must drop the dig");

        // ③ the bot is idle IN the half-dug unsealed shaft. "Still exposed" ground truth as a direct
        // block scan — canSeeSky would read the light engine, which hasn't recomputed the freshly-carved
        // column within this synchronous tick (live it reads true: duskExposed kept firing).
        BlockPos foot = fp.blockPosition();
        List<BlockPos> above = new ArrayList<>();
        for (int y = foot.getY() + 1; y <= floorY + 6; y++) above.add(new BlockPos(foot.getX(), y, foot.getZ()));
        boolean openAbove = cells(bot, above).stream().noneMatch(BotDebugContracts.Cell::solid);
        TestMod.LOG.info("[wd.duskSecureHeldProcessLifecycle/rearmWorldLeg] cut off@t{} foot={} openAbove={}",
                t, foot, openAbove);
        if (!openAbove)
            ctx.fail("rig: the unsealed shaft must still be open to the sky — foot=" + foot);

        // ④ re-arm exactly as duskSecure would: a fresh bunker from the pit floor must finish the job —
        // dig on, carve, step in, plug: genuinely SEALED.
        bunker(bot);
        for (int i = 0; i < 800 && !"SEALED".equals(bunkerSlot(bot).endReason().orElse(null)); i++) bot.tick();
        ControlContracts.Slot slot = bunkerSlot(bot);
        boolean enclosed = enclosed(bot, fp.blockPosition());
        TestMod.LOG.info("[wd.duskSecureHeldProcessLifecycle/rearmWorldLeg] redo endReason={} lastErr={} foot={} enclosed={}",
                slot.endReason().orElse(null), slot.lastError().orElse(null), fp.blockPosition(), enclosed);
        if (!"SEALED".equals(slot.endReason().orElse(null)))
            ctx.fail("gap#75-b: re-armed bunker failed to seal — endReason="
                    + slot.endReason().orElse(null) + " lastError=" + slot.lastError().orElse(null));
        if (!enclosed)
            ctx.fail("gap#75-b: SEALED verdict but the pocket is not enclosed at " + fp.blockPosition());
    }

    /** {@code mc.bot.bunker} two deep, as duskSecure digs. */
    private static void bunker(SceneBot bot) {
        Calls.started("bunker", Calls.now(SceneBot.tasks().bunker(new BuildContracts.BunkerParams(bot.ref(), Optional.of(2), 0))));
    }

    private static ControlContracts.Slot bunkerSlot(SceneBot bot) {
        return bot.status().slots().bunker();
    }

    /** {@code cells} as the bot's own world view classifies them, through {@code mc.debug.cells}. */
    private static List<BotDebugContracts.Cell> cells(SceneBot bot, List<BlockPos> cells) {
        List<BlockPosition> at = cells.stream().map(Targets::pos).toList();
        return Calls.now(DebugApi.of(Calls.api()).cells(new BotDebugContracts.CellsParams(Optional.of(bot.ref()), at,
                Optional.empty()))).cells();
    }

    /**
     * The bunker's enclosure ground truth, read through the bot's world view: the 4 horizontal neighbours of
     * the foot cell, the 4 of the head cell, and the cell above the head all solid.
     */
    private static boolean enclosed(SceneBot bot, BlockPos foot) {
        BlockPos head = foot.above();
        return cells(bot, List.of(foot.north(), foot.south(), foot.east(), foot.west(),
                head.north(), head.south(), head.east(), head.west(), head.above()))
                .stream().allMatch(BotDebugContracts.Cell::solid);
    }
}
