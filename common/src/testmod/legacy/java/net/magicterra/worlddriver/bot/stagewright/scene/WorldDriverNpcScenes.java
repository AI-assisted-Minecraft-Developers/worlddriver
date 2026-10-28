package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.GatherContracts;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.testcontent.DrivenPiglin;
import net.magicterra.worlddriver.testcontent.NpcContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The NPC bot on five terrains: down a staircase, over a one-block gap, across open water, out of
 * the water onto a bank, and up a ladder. A sixth scene orders it the work that needs hands.
 *
 * <p>The NPC is a driven piglin the roster adopts ({@code mc.bot.roster adopt}), so it walks as an
 * order by name walks any mob: a {@code mc.bot.goto} on its own bot, ticked by the server.
 *
 * <p><b>Two arms on one terrain.</b> The driven piglin walks the course first. Where a headless
 * server-side player may be created (the dedicated server), a server bot then walks the same course
 * with the same order, so a red on the NPC arm arrives with the player's reading beside it: both
 * failing is the rig, only the NPC failing is a difference between the two driven entities. On a
 * server a client hosts the NPC arm runs alone; the player arm's coverage is the dedicated gate's.
 *
 * <p>Break and place are off under the pinned baseline. The NPC has no hands, so a course that
 * needed either would measure the refusal rather than the movement.
 */
public final class WorldDriverNpcScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();

    /** Ticks with no order before the walk starts: after a spawn the first move finds no floor. */
    private static final int SETTLE_STEPS = 5;

    @Override
    public List<Scene> scenes() {
        // The NPC arm is ticked by the server, one walk tick per server tick, so a scene lasts as long as
        // its NPC walk may, plus the settle.
        return List.of(
                Scene.of("wd.npcWalksDownAStaircase", 500, WorldDriverNpcScenes::walksDownAStaircase),
                Scene.of("wd.npcJumpsAOneBlockGap", 400, WorldDriverNpcScenes::jumpsAOneBlockGap),
                Scene.of("wd.npcSwimsAcrossOpenWater", 500, WorldDriverNpcScenes::swimsAcrossOpenWater),
                Scene.of("wd.npcClimbsOutOfWater", 500, WorldDriverNpcScenes::climbsOutOfWater),
                Scene.of("wd.npcClimbsALadder", 500, WorldDriverNpcScenes::climbsALadder),
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
     * Work an entity without hands cannot do, ordered of the NPC by name: each order has to be refused
     * as {@code unsupported_capability} before anything starts and before it has changed a block. Break
     * and place are switched on, so the refusal comes from the NPC and not from the config.
     */
    private static void refusesWorkThatNeedsHands(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos o = ctx.origin().above(20);
        BlockPos lo = o.offset(-3, -1, -3), hi = o.offset(3, 3, 3);
        stage(ctx, lo, hi);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        fill(level, lo, o.offset(3, -1, 3), STONE);
        Npc npc = adoptNpc(ctx, o, "npc_hands");
        String ref = npc.ref();
        var tasks = SceneBot.tasks();
        Map<String, Responses.ActionResponse<?>> orders = new LinkedHashMap<>();
        orders.put("construct bridge", Calls.now(tasks.construct(new BuildContracts.ConstructParams(ref,
                new BuildContracts.Bridge(BuildContracts.BridgeDirection.EAST, 3, Optional.of("minecraft:cobblestone")), 0))));
        orders.put("construct tower", Calls.now(tasks.construct(new BuildContracts.ConstructParams(ref,
                new BuildContracts.Tower(Optional.empty(), Optional.of(o.getY() + 3), Optional.of("minecraft:cobblestone"), false), 0))));
        orders.put("mine", Calls.now(tasks.mine(new GatherContracts.MineParams(ref, List.of("minecraft:stone"),
                Optional.empty(), 1, 4, 0))));
        orders.put("craft", Calls.now(tasks.craft(new GatherContracts.CraftParams(ref, "minecraft:stick", 1, 0))));
        orders.put("smelt", Calls.now(tasks.smelt(new GatherContracts.SmeltParams(ref, "minecraft:raw_iron", 1,
                Optional.of("minecraft:coal"), 0))));
        orders.put("combat", Calls.now(tasks.combat(new CombatContracts.Params(ref, CombatContracts.Mode.KILL,
                Optional.of(new CombatContracts.FoeType("minecraft:zombie")), false, 0))));
        for (Map.Entry<String, Responses.ActionResponse<?>> order : orders.entrySet()) {
            Responses.ActionResponse<?> r = order.getValue();
            boolean refused = r instanceof Responses.Refused<?> no && no.reason() == Responses.RefusalReason.UNSUPPORTED_CAPABILITY;
            ctx.record(order.getKey(), String.valueOf(r));
            ctx.check(refused)
                    .as(order.getKey() + " on an entity without hands is refused as unsupported_capability before it starts: " + r)
                    .isTrue();
        }
        boolean busy = status(ref).busy();
        ctx.check(!busy).as("the NPC is on no task after the refusals: busy=" + busy).isTrue();
        long changed = BlockPos.betweenClosedStream(lo, hi)
                .filter(p -> !level.getBlockState(p).is(p.getY() < o.getY() ? Blocks.STONE : Blocks.AIR))
                .count();
        ctx.check(changed == 0).as("no block is changed before the request is refused: " + changed + " blocks changed").isTrue();
    }

    /** A driven piglin the roster adopted: {@code ref} is {@code mob:<name>}. */
    record Npc(DrivenPiglin mob, String ref) {}

    /**
     * A driven piglin standing in the centre of {@code foot}, adopted by the roster as {@code mob:<name>};
     * given up and discarded when the scene resolves. Legal on every topology: a mob is not a headless
     * player. Invulnerable like the bot players, so a scene measures the walks, not whether a fall or a
     * pool kills the NPC.
     */
    static Npc adoptNpc(SceneContext ctx, BlockPos foot, String name) {
        ServerLevel level = ctx.level();
        DrivenPiglin mob = NpcContent.DRIVEN_PIGLIN.get().create(level);
        if (mob == null) throw new SceneFailure("worlddriver:driven_piglin created no entity");
        mob.moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 0f, 0f);
        mob.setInvulnerable(true);
        level.addFreshEntity(mob);
        ctx.cleanup(mob::discard);
        Responses.ImmediateResponse r = Calls.now(ControlApi.of(Calls.api()).roster(
                new ControlContracts.Adopt(mob.getId(), Optional.of(name))));
        if (!(r instanceof Responses.Rostered rostered)) throw new SceneFailure("mc.bot.roster adopt " + name + " answered " + r);
        String ref = rostered.bot();
        ctx.cleanup(() -> Calls.now(ControlApi.of(Calls.api()).roster(new ControlContracts.Remove(ref))));
        return new Npc(mob, ref);
    }

    /** The bot {@code ref}'s own status, read on the server thread. */
    private static ControlContracts.BotStatus status(String ref) {
        Responses.ImmediateResponse r = Calls.now(ControlApi.of(Calls.api()).status(new ControlContracts.StatusParams(Optional.of(ref))));
        if (!(r instanceof Responses.Status s) || s.status().isEmpty()) throw new SceneFailure("mc.bot.status " + ref + " answered " + r);
        return s.status().get();
    }

    private record Leg(int ticks, boolean arrived, String ended) {}

    /**
     * Both arms on the course just staged; the NPC's arrival is the scene's verdict. The NPC walks on
     * the server's ticks (a mob moves in its own entity tick); the player arm then walks within one
     * scene tick, the bot ticked by hand.
     */
    private static void compare(SceneContext ctx, String what, BlockPos start, BlockPos goal, int maxTicks,
                                Predicate<LivingEntity> arrived) {
        ServerLevel level = ctx.level();
        Npc npc = adoptNpc(ctx, start, "npc");
        DrivenPiglin e = npc.mob();
        int[] settle = {0};
        ctx.await(() -> ++settle[0] >= SETTLE_STEPS).within(SETTLE_STEPS + 20).then(() -> {
            Calls.started("goto " + goal.toShortString(), Calls.now(SceneBot.tasks().goTo(new GotoContracts.Params(npc.ref(),
                    Optional.of(Targets.block(goal)), false, Optional.empty(), Optional.empty(), false, Optional.empty(), 0))));
            int[] t = {0};
            double[] minY = {e.getY()};
            boolean[] at = {false};
            ctx.await(() -> {
                t[0]++;
                minY[0] = Math.min(minY[0], e.getY());
                at[0] = arrived.test(e);
                return at[0] || t[0] >= maxTicks;
            }).within(maxTicks + 20).then(() -> {
                Leg n = new Leg(t[0], at[0], ended(t[0], e, minY[0], status(npc.ref()), at[0]));
                ctx.record("npc.drive", n.ended());
                Calls.now(ControlApi.of(Calls.api()).roster(new ControlContracts.Remove(npc.ref())));
                e.discard();

                Leg p = null;
                if (ctx.server() != null && ctx.server().isDedicatedServer()) {
                    SceneBot bot = SceneBot.spawn(ctx, level, start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
                    ServerPlayer fp = bot.player();
                    fp.getInventory().clearContent();
                    bot.step(SETTLE_STEPS);
                    bot.goTo(Targets.block(goal));
                    double lowest = fp.getY();
                    boolean there = false;
                    int i = 0;
                    while (i < maxTicks && !there) {
                        bot.tick();
                        i++;
                        lowest = Math.min(lowest, fp.getY());
                        there = arrived.test(fp);
                    }
                    p = new Leg(i, there, ended(i, fp, lowest, bot.status(), there));
                    ctx.record("player.drive", p.ended());
                    bot.cancel();
                }
                if (p != null && !p.arrived())
                    ctx.fail("THE RIG, not the NPC: the server-side player also failed to " + what
                            + " on the same terrain: " + p.ended());
                ctx.check(n.arrived()).as("the NPC entity manages to " + what + ": " + n.ended()).isTrue();
                ctx.passNote("NPC " + n.ticks() + " ticks" + (p == null ? "" : ", server-side player " + p.ticks() + " ticks"));
            });
        });
    }

    private static String ended(int ticks, LivingEntity e, double minY, ControlContracts.BotStatus status, boolean at) {
        ControlContracts.Slot go = status.slots().goTo();
        return String.format(Locale.ROOT, "%d ticks, entity=(%.2f,%.2f,%.2f), lowest y=%.2f, busy=%b, goto=%s, arrived=%b",
                ticks, e.getX(), e.getY(), e.getZ(), minY, status.busy(),
                go.endReason().orElse(go.active() ? "running" : "-"), at);
    }

    /** Pin the baseline with break and place off, and clear the footprint now and when the scene resolves. */
    private static void stage(SceneContext ctx, BlockPos lo, BlockPos hi) {
        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", true);
        Harness.untimed(ctx);
        ServerLevel level = ctx.level();
        fill(level, lo, hi, AIR);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
    }

    private static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlockAndUpdate(p, state);
    }
}
