package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.BridgeProcess;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.magicterra.worlddriver.bot.process.CraftProcess;
import net.magicterra.worlddriver.bot.process.MineProcess;
import net.magicterra.worlddriver.bot.process.SmeltProcess;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.stagewright.LivingBody;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The NPC body on five terrains: down a staircase, over a one-block gap, across open water, out of
 * the water onto a bank, and up a ladder. A sixth scene orders it the work that needs hands.
 *
 * <p><b>Two arms on one terrain.</b> The driven piglin walks the course first. Where a headless
 * player body may be minted (the dedicated server), a {@code ServerPlayerBody} then walks the same
 * course with the same walker, so a red on the NPC arm arrives with the player's reading beside it:
 * both failing is the rig, only the NPC failing is a difference between the bodies. On a server a
 * client hosts the NPC arm runs alone; the player arm's coverage is the dedicated gate's.
 *
 * <p>Break and place are off under the pinned baseline. The NPC has no hands, so a course that
 * needed either would measure the refusal rather than the legs.
 */
public final class WorldDriverNpcScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    /** Steps with no input before the walker starts: after a spawn the first move finds no floor. */
    private static final int SETTLE_STEPS = 5;

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.npcWalksDownAStaircase", 200, WorldDriverNpcScenes::walksDownAStaircase),
                Scene.of("wd.npcJumpsAOneBlockGap", 200, WorldDriverNpcScenes::jumpsAOneBlockGap),
                Scene.of("wd.npcSwimsAcrossOpenWater", 200, WorldDriverNpcScenes::swimsAcrossOpenWater),
                Scene.of("wd.npcClimbsOutOfWater", 200, WorldDriverNpcScenes::climbsOutOfWater),
                Scene.of("wd.npcClimbsALadder", 200, WorldDriverNpcScenes::climbsALadder),
                Scene.of("wd.npcRefusesWorkThatNeedsHands", 40, WorldDriverNpcScenes::refusesWorkThatNeedsHands));
    }

    /** Six treads, each one block along +x and one block down, onto a landing. */
    private static void walksDownAStaircase(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin().above(20);
        final int steps = 6;
        stage(ctx, o.offset(-2, -steps - 1, -2), o.offset(steps + 3, 4, 2));
        for (int i = 0; i <= steps; i++) fill(level, o.offset(i, -steps - 1, -1), o.offset(i, -i, 1), STONE);
        fill(level, o.offset(steps + 1, -steps - 1, -1), o.offset(steps + 2, -steps, 1), STONE);
        BlockPos bottom = o.offset(steps + 1, -steps + 1, 0);
        compare(ctx, "walk down a staircase of " + steps + " steps", o.above(), bottom, 400,
                e -> e.blockPosition().equals(bottom) && e.onGround());
    }

    /** A one-block gap across a three-wide walk, over a pit four deep that nothing climbs out of. */
    private static void jumpsAOneBlockGap(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin().above(20);
        stage(ctx, o.offset(-4, -5, -2), o.offset(6, 4, 2));
        fill(level, o.offset(-3, -4, -1), o.offset(5, -4, 1), STONE);
        fill(level, o.offset(-3, 0, -1), o.offset(0, 0, 1), STONE);
        fill(level, o.offset(2, 0, -1), o.offset(5, 0, 1), STONE);
        BlockPos goal = o.offset(4, 1, 0);
        compare(ctx, "jump across a one-block-wide gap", o.offset(-2, 1, 0), goal, 300,
                e -> e.blockPosition().getX() >= o.getX() + 2 && e.onGround() && e.getY() >= goal.getY() - 0.01);
    }

    /** Eight cells of water three deep between two banks; the course ends in the last water cell. */
    private static void swimsAcrossOpenWater(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin().above(20);
        final int span = 8;
        stage(ctx, o.offset(-4, -4, -3), o.offset(span + 4, 4, 3));
        fill(level, o.offset(-3, -3, -2), o.offset(span + 3, -3, 2), STONE);
        fill(level, o.offset(-3, -2, -2), o.offset(span + 3, 0, -2), STONE);
        fill(level, o.offset(-3, -2, 2), o.offset(span + 3, 0, 2), STONE);
        fill(level, o.offset(-3, -2, -1), o.offset(-1, 0, 1), STONE);
        fill(level, o.offset(span, -2, -1), o.offset(span + 3, 0, 1), STONE);
        fill(level, o.offset(0, -2, -1), o.offset(span - 1, 0, 1), WATER);
        BlockPos goal = o.offset(span - 1, 0, 0);
        compare(ctx, "swim across " + span + " blocks of open water", o.offset(-2, 1, 0), goal, 400,
                e -> e.blockPosition().getX() >= goal.getX() && e.isInWater());
    }

    /** A pool three deep with a bank flush with the water's top block; the course starts in the water. */
    private static void climbsOutOfWater(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin().above(20);
        stage(ctx, o.offset(-4, -4, -4), o.offset(4, 4, 7));
        fill(level, o.offset(-3, -3, -3), o.offset(3, -3, 6), STONE);
        fill(level, o.offset(-3, -2, -3), o.offset(3, 0, -3), STONE);
        fill(level, o.offset(-3, -2, -3), o.offset(-3, 0, 6), STONE);
        fill(level, o.offset(3, -2, -3), o.offset(3, 0, 6), STONE);
        fill(level, o.offset(-2, -2, 2), o.offset(2, 0, 6), STONE);
        fill(level, o.offset(-2, -2, -2), o.offset(2, 0, 1), WATER);
        BlockPos goal = o.offset(0, 1, 4);
        compare(ctx, "climb out of the water onto a flush bank", o, goal, 400,
                e -> e.blockPosition().getZ() >= o.getZ() + 3 && e.onGround() && e.getY() >= goal.getY() - 0.01);
    }

    /** A ladder five high on the west face of a block whose top is the platform. */
    private static void climbsALadder(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin().above(20);
        final int rise = 5;
        stage(ctx, o.offset(-3, -1, -3), o.offset(4, rise + 4, 3));
        fill(level, o.offset(-2, 0, -2), o.offset(3, 0, 2), STONE);
        fill(level, o.offset(1, 1, -1), o.offset(3, rise, 1), STONE);
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
        for (int y = 1; y <= rise; y++) level.setBlockAndUpdate(o.offset(0, y, 0), ladder);
        BlockPos goal = o.offset(2, rise + 1, 0);
        compare(ctx, "climb a ladder " + rise + " blocks high", o.offset(-1, 1, 0), goal, 400,
                e -> e.getY() >= goal.getY() - 0.01 && e.onGround() && e.blockPosition().getX() >= o.getX() + 1);
    }

    /**
     * Work a body without hands cannot do, ordered of the NPC: each order has to end on its first tick
     * with {@code no_hands}, before it has changed a block. Break and place are switched on, so the
     * refusal is the body's and not the config's.
     */
    private static void refusesWorkThatNeedsHands(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin().above(20);
        BlockPos lo = o.offset(-3, -1, -3), hi = o.offset(3, 3, 3);
        stage(ctx, lo, hi);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        fill(level, lo, o.offset(3, -1, 3), STONE);
        LivingBody npc = SceneBody.npc(ctx, o);
        LevelWorldView view = LevelWorldView.forBody(level, npc.entity());
        List<BotProcess> orders = List.of(
                new BridgeProcess(Direction.EAST, 3, "minecraft:cobblestone"),
                new TowerProcess(o.getY() + 3, "minecraft:cobblestone"),
                new MineProcess(List.of("minecraft:stone"), 1, 4),
                new CraftProcess("minecraft:stick", 1),
                new SmeltProcess("minecraft:raw_iron", 1, "minecraft:coal"),
                new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:zombie"));
        for (BotProcess order : orders) {
            BotState st = new BotState();
            order.attach(st);
            boolean done = order.tick(npc, view, st);
            BotState.ProcessSlot slot = switch (order.kind()) {
                case "mine" -> st.mine;
                case "craft" -> st.craft;
                case "smelt" -> st.smelt;
                case "combat" -> st.combat;
                default -> st.builder;
            };
            String error = slot.lastError;
            String name = order.getClass().getSimpleName();
            // Recorded, not judged: the combat slot's flag belongs to CombatChain, which rewrites it
            // from engaged() every tick, so the process leaves it as attach set it.
            ctx.record(name, "done=" + done + " lastError=" + error + " active=" + slot.active);
            ctx.check(done && BodyReady.Reason.NO_HANDS.equals(error))
                    .as(name + " on an entity without hands ends with no_hands on its first tick: done=" + done + " lastError=" + error)
                    .isTrue();
        }
        long changed = BlockPos.betweenClosedStream(lo, hi)
                .filter(p -> !level.getBlockState(p).is(p.getY() < o.getY() ? Blocks.STONE : Blocks.AIR))
                .count();
        ctx.check(changed == 0).as("no block is changed before the request is refused: " + changed + " blocks changed").isTrue();
    }

    private record Leg(int ticks, boolean arrived, String ended) {}

    /** Both arms on the course just staged; the NPC's arrival is the scene's verdict. */
    private static void compare(SceneContext ctx, String what, BlockPos start, BlockPos goal, int maxTicks,
                                Predicate<LivingEntity> arrived) {
        ServerLevel level = ctx.level();
        LivingBody npc = SceneBody.npc(ctx, start);
        Leg n = drive(npc, npc::step, LevelWorldView.forBody(level, npc.entity()), goal, maxTicks, arrived);
        ctx.record("npc.drive", n.ended());
        npc.entity().discard();

        Leg p = null;
        if (ctx.server() != null && ctx.server().isDedicatedServer()) {
            ServerPlayerBody av = SceneBody.avatar(ctx, level, start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
            ServerPlayer fp = av.fakePlayer();
            ctx.cleanup(fp::discard);
            fp.getInventory().clearContent();
            p = drive(av, av::step, new LevelWorldView(level, fp), goal, maxTicks, arrived);
            ctx.record("player.drive", p.ended());
            fp.discard();
        }
        if (p != null && !p.arrived())
            ctx.fail("THE RIG, not the NPC: the server-side player also failed to " + what
                    + " on the same terrain: " + p.ended());
        ctx.check(n.arrived()).as("the NPC entity manages to " + what + ": " + n.ended()).isTrue();
        ctx.passNote("NPC " + n.ticks() + " ticks" + (p == null ? "" : ", server-side player " + p.ticks() + " ticks"));
    }

    private static Leg drive(Body body, Runnable step, LevelWorldView view, BlockPos goal, int maxTicks,
                             Predicate<LivingEntity> arrived) {
        LivingEntity e = body.entity();
        for (int i = 0; i < SETTLE_STEPS; i++) step.run();
        Walker walker = new Walker("scene");
        walker.setGoal(new Goal.Block(goal));
        Walker.Step s = Walker.Step.WALKING;
        double minY = e.getY();
        boolean at = false;
        int t = 0;
        while (t < maxTicks && !at) {
            s = walker.tick(body, view);
            step.run();
            t++;
            minY = Math.min(minY, e.getY());
            at = arrived.test(e);
        }
        return new Leg(t, at, String.format(Locale.ROOT, "%d ticks, entity=(%.2f,%.2f,%.2f), lowest y=%.2f, walker=%s, arrived=%b",
                t, e.getX(), e.getY(), e.getZ(), minY, s, at));
    }

    /** Pin the baseline with break and place off, and clear the footprint now and when the scene resolves. */
    private static void stage(SceneContext ctx, BlockPos lo, BlockPos hi) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerLevel level = ctx.level();
        fill(level, lo, hi, AIR);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
    }

    private static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlockAndUpdate(p, state);
    }
}
