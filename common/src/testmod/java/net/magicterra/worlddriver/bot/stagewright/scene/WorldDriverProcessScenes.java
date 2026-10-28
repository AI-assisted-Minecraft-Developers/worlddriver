package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.bot.BuildContracts;
import net.magicterra.worlddriver.api.methods.bot.CombatContracts;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.Routes;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The headless server-side task arenas: goto, mine, flee, build, look, follow, combat, bridge, tower and the
 * log-cost waiver, each on a bot spawned through {@code mc.bot.roster} and driven by the {@code mc.bot.*} verb
 * that owns the task. A scene either runs everything inside its own tick (start the verb, then
 * {@link SceneBot#tick} until the bot is idle) or starts the verb and lets the server tick the bot, awaiting
 * {@code !bot.busy()}. What a task reports is read from {@code mc.bot.status} slots; what the body holds or
 * looks at, from {@code mc.debug.body}. Every bot is spawned on a dedicated server only.
 *
 * <p><b>Fresh entities.</b> The follow, combat and forbid-dig scenes summon an entity and wait (bounded) until
 * it is queryable before driving, rather than calling {@code level.tick()}, which re-enters the chunk map.
 * StageWright starts a scene only once its arena is entity-ticking, so those waits resolve on the first poll;
 * they stay as the guard that says so.
 *
 * <p><b>{@code wd.serverCombat} controlled-combat rig.</b> The zombie is {@code setNoAi(true)} +
 * {@code setPersistenceRequired()} + max {@code KNOCKBACK_RESISTANCE}, and it is re-pinned to its
 * cell each drive iteration. The drive loop runs up to 1500 bot ticks inside one server tick, so the
 * scene ticks the zombie itself ({@code zombie.tick()}) each iteration to clear its hurt-cooldown; a
 * target ticked once stays invulnerable after the first hit. It cannot sun-burn to a false fire-kill
 * because StageWright freezes the whole run at midnight.
 *
 * <p><b>Three subject families live in sibling classes.</b> This file grew past the 3000-line source
 * budget, so — the same mechanical seam-split the {@code *Phase} phase classes did for
 * {@code Navigator} — {@link WorldDriverPortalScenes} (obsidian cast / portal frame / lighting / nether
 * transit / end portal), {@link WorldDriverMobFightScenes} (blaze rod / dragon / flying blaze /
 * ender pearl / end crystal) and {@link WorldDriverSmeltDeliveryScenes} (the one scene that lets a
 * furnace cook) were moved out verbatim. {@link #scenes()} calls each one's {@code register} at the
 * exact position its scenes held in the single list, so the registered set AND its order are
 * unchanged. {@link #buildFloor}, {@link #entityBox} and {@link #countItem} stayed here and are
 * package-private because more than one family calls them; every helper with only one family's worth
 * of callers moved with it.
 */
public final class WorldDriverProcessScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        List<Scene> out = new ArrayList<>(List.of(
                Scene.of("wd.serverDriver", 400, WorldDriverProcessScenes::serverDriverScene),
                Scene.of("wd.serverMine", 400, WorldDriverProcessScenes::serverMineScene),
                Scene.of("wd.serverFlee", 400, WorldDriverProcessScenes::serverFleeScene),
                Scene.of("wd.serverMineHarvest", 1_500, WorldDriverProcessScenes::serverMineHarvestScene),
                // The SAME circuit with two of the three ores buried — the field shape, since the
                // journey's iron rung mines ore four blocks under the surface. It shipped optional
                // and red on purpose while a drop at the bottom of a self-dug hole was
                // unretrievable, precisely so the cliff would not get written in as the
                // requirement (wd.serverSmeltStationOpens is the scene that had to be renamed for
                // doing that). Required as of the change that fixed it: the drops were never in a
                // hole at all, they were sealed inside rock the avatar had no business mining
                // through, and the miner now peels its way down instead.
                Scene.of("wd.serverMineHarvestBuried", 1_500, WorldDriverProcessScenes::serverMineHarvestBuriedScene),
                // A bot that digs the block out from under itself and rides it down. It shipped
                // optional and red as the smallest statement of why the scene above was red, and
                // outlived that explanation — the buried drops turned out to be sealed in rock
                // rather than lying at the bottom of a hole. It has since gone green on both
                // loaders and is required under promote-on-first-green; the descent it measures is
                // the miner's peel-one-block-at-a-time path, so a regression there lands here.
                Scene.of("wd.serverSelfShaftDescends", 1_200, WorldDriverProcessScenes::serverSelfShaftDescends),
                // Pure navigation, no mining: can the navigator path to the bottom of a pit? That is
                // the question under wd.serverMineHarvestBuried, and mining it first means a
                // failure could belong to either half. Required, because if this is red the
                // buried-drop story is a Navigator story and every fix aimed at MineProcess is aimed
                // at the wrong file.
                Scene.of("wd.serverBreakNeedsReach", 400, WorldDriverProcessScenes::serverBreakNeedsReach),
                Scene.of("wd.serverWalkIntoAPit", 900, WorldDriverProcessScenes::serverWalkIntoAPit),
                // The same pit, with the miner's own permissions. MineProcess sweeps with breaking
                // AND placing on, and a navigator allowed to place can treat a hole as terrain to
                // bridge rather than a place to stand — so "the navigator can reach a pit" and "the
                // collector can reach a pit" are not the same claim. Measured green on both
                // loaders, so it is now required: the armed navigator must keep choosing to descend.
                Scene.of("wd.serverWalkIntoAPitArmed", 900, WorldDriverProcessScenes::serverWalkIntoAPitArmed),
                Scene.of("wd.serverMineNoTool", 600, WorldDriverProcessScenes::serverMineNoToolScene),
                Scene.of("wd.serverNavigatorDeepslateNoTool", 600, WorldDriverProcessScenes::serverNavigatorDeepslateNoToolScene),
                Scene.of("wd.serverForbidDigWall", 400, WorldDriverProcessScenes::serverForbidDigWallScene),
                Scene.of("wd.serverBuild", 600, WorldDriverProcessScenes::serverBuildScene),
                Scene.of("wd.serverLookRaycast", 200, WorldDriverProcessScenes::serverLookRaycastScene),
                Scene.of("wd.serverFollow", 400, WorldDriverProcessScenes::serverFollowScene),
                Scene.of("wd.serverCombatCollectDrops", 400, WorldDriverProcessScenes::serverCombatCollectDropsScene),
                Scene.of("wd.serverLook", 400, WorldDriverProcessScenes::serverLookScene),
                // The trunk-tax waiver's state machine. Three scenes, none of which asks about a
                // price — see the block above them for why the multiplication is not testable on
                // this topology and which layer owns that half.
                Scene.of("wd.mineLogWaiverEngages", 800,
                        WorldDriverProcessScenes::mineLogWaiverEngagesScene),
                Scene.of("wd.mineStoneLeavesTheWaiverOff", 800,
                        WorldDriverProcessScenes::mineStoneLeavesTheWaiverOffScene),
                Scene.of("wd.mineLogWaiverReleasedOnSupersede", 600,
                        WorldDriverProcessScenes::mineLogWaiverReleasedOnSupersedeScene),
                Scene.of("wd.serverBridgePillarStart", 400, WorldDriverProcessScenes::serverBridgePillarStartScene),
                // Required from the day it was written, because it passed the day it was written.
                // It exists because the journey reported the opposite — exit.fromY=54 ->
                // exit.toY=55, one block in 1200 ticks — and an isolated arena said the bot leaves
                // a four-deep shaft in 46 ticks. That gap is now known to be about the journey's
                // budget and terrain, not about a missing capability, which is exactly the sort of
                // thing a stalled rung four scenes downstream cannot tell you.
                Scene.of("wd.serverPillarsOutOfAPit", 1_500, WorldDriverProcessScenes::serverPillarsOutOfAPit),
                // The same question at the depth a real mining rung digs to, and driven by the
                // process the journey now scripts rather than by the navigator. It shipped optional —
                // the four-deep arena above proves a capability, not this one, and the journey had
                // measured a NINE-deep shaft moving the bot one block in 6 000 ticks — and was
                // promoted in the run that first saw it green (135 ticks), which is what keeps a
                // frontier from sliding back.
                Scene.of("wd.serverTowersOutOfADeepShaft", 1_500,
                        WorldDriverProcessScenes::serverTowersOutOfADeepShaft)));
        WorldDriverPortalScenes.register(out);
        WorldDriverMobFightScenes.register(out);
        WorldDriverSmeltDeliveryScenes.register(out);
        return List.copyOf(out);
    }

    /** Which of the rig's ores are still standing, for a failure message that says so. */
    private static String standingLabel(ServerLevel level, List<BlockPos> ores) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos at : ores)
            sb.append(sb.length() == 0 ? "" : ",").append(level.getBlockState(at).is(Blocks.IRON_ORE) ? "ore" : "air");
        return sb.toString();
    }

    /** A ±24-block cube around the arena centre — the entity-visibility poll box. Used only to detect
     *  when a freshly-added entity has entered the level's queryable section index (wave-5 precedent). */
    static AABB entityBox(int cx, int floorY, int cz) {
        return new AABB(cx - 24, floorY - 24, cz - 24, cx + 24, floorY + 24, cz + 24);
    }

    // ==================================================================================
    // wd.serverDriver — headless driver: a server bot walks + steps up a +1 ledge to a cell,
    // through mc.bot.goto.
    // ==================================================================================

    private static void serverDriverScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -1; dz <= 10; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 10; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        // +1 ledge for the back half → exercises walk + stepUp via the server driver.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = 6; dz <= 10; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 1, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, floorY + 2, cz + 9);   // foot on the ledge

        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        bot.goTo(Targets.block(goal));
        if (Harness.busy() != 1) { ctx.fail("the goto did not put the bot to work: busy=" + Harness.busy()); return; }

        bot.tickUntilIdle(200);

        ServerPlayer fp = bot.player();
        boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 9 + 0.5)) < 1.5
                && fp.getY() >= floorY + 2 - 0.4;
        TestMod.LOG.info("[wd.serverDriver] end={} pos=({},{},{}) busy={} reached={}",
                bot.status().lastProcessEnd(), fp.getX(), fp.getY(), fp.getZ(), Harness.busy(), reached);
        if (bot.busy() || Harness.busy() != 0)
            ctx.fail("server driver did not finish and go idle: busy=" + Harness.busy());
        if (!reached)
            ctx.fail("server-driven agent did not reach the goal: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") end=" + bot.status().lastProcessEnd());
    }

    // ==================================================================================
    // wd.serverMine — Phase 2 task: navigate + MINE a target block (no client).
    // ==================================================================================

    private static void serverMineScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -5; dx <= 5; dx++)
                for (int dy = 0; dy <= 18; dy++)
                    for (int dz = -5; dz <= 5; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        SceneArena.buildFloor(level, cx, cz, floorY);
        BlockPos target = new BlockPos(cx + 3, floorY + 1, cz);   // a block on the floor, away from the bot
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx - 3 + 0.5, floorY + 1, cz + 0.5);
        bot.mineCell(target);
        bot.tickUntilIdle(200);

        boolean mined = level.getBlockState(target).isAir();
        ServerPlayer fp = bot.player();
        TestMod.LOG.info("[wd.serverMine] end={} pos=({},{},{}) busy={} mined={}",
                bot.status().lastProcessEnd(), fp.getX(), fp.getY(), fp.getZ(), Harness.busy(), mined);
        if (!mined)
            ctx.fail("server agent did not mine the target (still " + level.getBlockState(target) + ")");
        if (bot.busy() || Harness.busy() != 0)
            ctx.fail("mine task did not finish and go idle: busy=" + Harness.busy());
    }

    // ==================================================================================
    // wd.serverProcess — the server bot walks a flat run to a cell through mc.bot.goto.
    // ==================================================================================

    // ==================================================================================
    // wd.serverFlee — the server bot runs mc.bot.runAway (stateful) to a minimum distance.
    // ==================================================================================

    private static void serverFleeScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20, R = 10;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -R; dx <= R; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -R; dz <= R; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -R; dx <= R; dx++)
            for (int dz = -R; dz <= R; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos from = new BlockPos(cx, floorY + 1, cz);
        final int minDist = 6;

        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        bot.runAway(from, minDist);
        bot.tickUntilIdle(200);

        ServerPlayer fp = bot.player();
        double dx = fp.getX() - (cx + 0.5), dz = fp.getZ() - (cz + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean fled = dist >= minDist - 0.5;
        TestMod.LOG.info("[wd.serverFlee] end={} pos=({},{},{}) dist={} busy={} fled={}",
                bot.status().lastProcessEnd(), fp.getX(), fp.getY(), fp.getZ(), dist, Harness.busy(), fled);
        if (!fled)
            ctx.fail("server runAway did not reach min flee distance: dist="
                    + dist + " (need " + minDist + ")");
        if (bot.busy() || Harness.busy() != 0)
            ctx.fail("flee did not finish and go idle: busy=" + Harness.busy());
    }

    // ==================================================================================
    // wd.serverMineProcess — the server bot runs mc.bot.mine for a whole 3-stone quota holding a
    // pickaxe.
    // ==================================================================================

    // ==================================================================================
    // wd.serverMineHarvest — the assertion this family never made: mining must put the
    // HARVEST IN THE BAG, not merely turn the block to air.
    // ==================================================================================

    /**
     * The sibling scenes above assert that the target block stops being there. Every one of
     * them passes on a bot that mines all day and acquires nothing, and for a long time that
     * is exactly what the headless avatar did — {@code wd.serverCombatCollectDrops} even says
     * so out loud ("pickup fidelity on a FakePlayer is not this scene's contract"). Nothing
     * owned the other half, so nothing caught it. This scene owns it: <b>PASS means an item
     * that did not exist before is in the inventory afterwards.</b>
     *
     * <p><b>Real ticks, not a spin.</b> Most of the family drives its task inside one server
     * tick. That cannot work here: a mined block drops an {@link ItemEntity} with a 10-tick
     * pickup delay, and an entity only counts that delay down when the LEVEL ticks it. Spun
     * in-scene, the drop is never pickable and the scene would fail for a reason that has
     * nothing to do with the code under test. So the verb is started and the bot left to the
     * server's own tick, and the scene awaits the bot going idle.
     *
     * <p><b>The rig reproduces the hazard, not a friendly case.</b> The pickaxe starts in the
     * BAG (slot 20, not the hotbar) and the hotbar starts holding DIRT, because that is the
     * state the journey's iron rung actually reaches: {@code holdPlaceable()} grabs the first
     * placeable in the hotbar while bridging, so by the time the ore breaks the hand holds the
     * dirt it just tunnelled through. A rig that pre-selects the pickaxe would be green while
     * the real path is broken.
     *
     * <p><b>Diagnostics live in the fail message</b> (late-suite {@code LOG.info} is dropped on
     * shutdown), and they deliberately separate the two ways this can fail: {@code drops=} counts
     * raw-iron ItemEntities still lying in the arena. Drops present + bag empty is a COLLECT/
     * pickup defect; neither present is a drop defect. Naming which one it is at failure time is
     * the point of measuring both.
     *
     * <p><b>Footprint audit</b> (origin-relative, default 3×3 window dx/dz [−16,+31]): floor and
     * clear span dx [−2,+7], dz [−2,+2] — inside the window at the default radius.
     */
    private static void serverMineHarvestScene(SceneContext ctx) { mineHarvest(ctx, false); }

    /** The buried half — see {@link #mineHarvest}. Optional: it is red on arrival and says why. */
    private static void serverMineHarvestBuriedScene(SceneContext ctx) { mineHarvest(ctx, true); }

    /**
     * Dig the block under your own feet and end up one block lower.
     *
     * <p>The most ordinary thing a player does underground, and this bot cannot do it. The journey's
     * iron rung found it the expensive way: the ore is four blocks under the surface, so the route
     * scripted a shaft — break the block below, fall in, repeat — and the trace showed twelve steps
     * with the bot at a constant {@code y=63}, shuffling sideways one cell at a time.
     *
     * <p>Two separate facts have to hold and the failure message names which one broke.
     * <b>The block must break</b>: {@code mc.bot.mine} on one cell digs it from where the bot
     * stands when it is in reach, as it is here, so the actuator runs at once.
     * <b>The bot must then descend into the hole</b>: the single-block mine ends on the tick the
     * block turns to air, so the descent is driven explicitly, with the emptied cell as the goal.
     *
     * <p>A goto to that cell rather than to a height: a height goal was tried on the journey
     * and it descends to the wrong place — "be at y=60" is satisfied anywhere, and the navigator took
     * the cheapest way down it could find, landing six blocks off the ore column. A shaft is a
     * column, and only a goal naming the column keeps the bot over its own hole.
     */
    private static void serverSelfShaftDescends(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = -4; dy <= 2; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        // A solid slab with nowhere to walk down to. That is the point: a staircase has somewhere to
        // step INTO and this deliberately does not, because the field shape the journey hit — a
        // swamp with the ore straight down — does not either.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -4; dy <= 0; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            Blocks.STONE.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);    // placing would let it pillar back up and muddy the reading
        Harness.set("navigatorDebug", false);
        Harness.set("pathfinder.sliceMs", 20);
        Harness.set("pathfinder.maxMs", 2000);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        fp.getInventory().selected = 0;

        BlockPos under = new BlockPos(cx, floorY, cz);
        BlockPos deeper = under.below();
        final int startY = fp.blockPosition().getY();

        // TWO courses, not one, and the reason is the bug this scene missed the first time: on a
        // bot that had ever run another task, a later mine was silently ignored and the stale task
        // ran instead, finishing against its already-met goal. A one-course version mines on a
        // fresh bot, which is the single ordering where that cannot bite; the journey found it only
        // after four rungs of tasks had run on the same bot. So the second course is mined AFTER a
        // goto has owned this bot.
        bot.mineCell(under);

        ctx.await(() -> !bot.busy()).within(400).then(() -> {
            boolean brokeFirst = level.getBlockState(under).isAir();
            bot.goTo(Targets.block(under));

            ctx.await(() -> !bot.busy()).within(600).then(() -> {
                int midY = fp.blockPosition().getY();
                bot.mineCell(deeper);

                ctx.await(() -> !bot.busy()).within(400).then(() -> {
                    BlockPos at = fp.blockPosition();
                    String diag = " [diag under=" + level.getBlockState(under).getBlock()
                            + " deeper=" + level.getBlockState(deeper).getBlock()
                            + " startY=" + startY + " midY=" + midY + " endY=" + at.getY()
                            + " bot@" + (at.getX() - cx) + "," + (at.getY() - floorY)
                            + "," + (at.getZ() - cz)
                            + " onGround=" + fp.onGround()
                            + " end=" + bot.status().lastProcessEnd() + "]";
                    ctx.expect(brokeFirst)
                            .as("the block under the bot broke — if this is false the descent was"
                                    + " never even attempted and the rest of the message is noise" + diag)
                            .isEqualTo(true);
                    ctx.expect(midY < startY)
                            .as("the bot followed its own shaft down; a player who digs the block"
                                    + " beneath them falls in, and every drop from that dig is down"
                                    + " there with it" + diag)
                            .isEqualTo(true);
                    ctx.expect(level.getBlockState(deeper).isAir())
                            .as("a mine started AFTER another task actually mines — a stale task left"
                                    + " running turns every later mine into a no-op that still"
                                    + " reports success" + diag)
                            .isEqualTo(true);
                });
            });
        });
    }

    /**
     * The avatar must not mine what a player could not have touched.
     *
     * <p>Three targets in one slab, at once, so a fix that trades one for another cannot pass:
     * a block <b>sealed</b> in stone (all six faces solid), a block <b>far</b> away but exposed,
     * and a block <b>adjacent</b> to the bot. Only the third may break.
     *
     * <p>This is the contract behind {@code wd.serverMineHarvestBuried}. Without it
     * {@code Level#destroyBlock} breaks anything the avatar aims at, at any distance, through any
     * amount of rock — and the drop from a sealed block lands in a 1×1×1 pocket
     * ({@code openSides=0}) that no pathfinder can ever reach. Two rounds of work went into the
     * navigator and the collect sweep before anyone measured the pocket.
     */
    private static void serverBreakNeedsReach(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 12; dx++)
                for (int dy = -4; dy <= 2; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });
        for (int dx = -3; dx <= 12; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -4; dy <= 0; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            Blocks.STONE.defaultBlockState());

        BlockPos sealed = new BlockPos(cx, floorY - 2, cz);          // buried, six solid faces
        BlockPos far = new BlockPos(cx + 10, floorY, cz);            // exposed, ten blocks away
        BlockPos adjacent = new BlockPos(cx + 1, floorY, cz);        // the one a player can reach
        for (BlockPos at : List.of(sealed, far, adjacent))
            level.setBlockAndUpdate(at, Blocks.IRON_ORE.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", false);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().items.set(0, new ItemStack(Items.DIAMOND_PICKAXE));  // never the excuse
        fp.getInventory().selected = 0;

        // Aim and hold at each, generously — a slow-mine needs ticks, and giving the two that must
        // NOT break more ticks than the one that must is the point.
        for (BlockPos at : List.of(sealed, far, adjacent)) {
            bot.press(bot.keys().toolFor(Targets.pos(at)).aimAt(Targets.pos(at)).attack(true));
            bot.step(60);
        }
        bot.release();

        String diag = " [diag sealed=" + level.getBlockState(sealed).getBlock()
                + " far=" + level.getBlockState(far).getBlock()
                + " adjacent=" + level.getBlockState(adjacent).getBlock()
                + " eye=" + String.format("%.1f", fp.getEyePosition().y)
                + " range=" + String.format("%.1f", fp.blockInteractionRange()) + "]";
        ctx.expect(level.getBlockState(sealed).is(Blocks.IRON_ORE))
                .as("a block walled in on all six faces must survive — no ray from any eye can hit"
                        + " it, and its drop would be sealed where nothing can collect it" + diag)
                .isEqualTo(true);
        ctx.expect(level.getBlockState(far).is(Blocks.IRON_ORE))
                .as("a block ten blocks away must survive — vanilla's own server rejects the dig on"
                        + " distance alone" + diag)
                .isEqualTo(true);
        ctx.expect(level.getBlockState(adjacent).isAir())
                .as("and the block right next to the bot must still break, or the gate has simply"
                        + " turned mining off" + diag)
                .isEqualTo(true);
    }

    /**
     * Stand at the bottom of a four-deep shaft with blocks in the bag and get out.
     *
     * <p>The exit half of {@code wd.serverWalkIntoAPit}. Going down was never the problem; coming
     * back up is. Once the reach gate stopped the avatar mining through rock, every mining rung had
     * to dig a shaft, and the journey then measured what happens next: {@code exit.fromY=54 ->
     * exit.toY=55}, one block in 1200 ticks, with 17 cobblestone in the inventory and placing
     * allowed. The rung above it inherited a bot in a pit and spent its whole budget walking
     * nowhere.
     *
     * <p>Isolated deliberately. A stalled food rung four scenes downstream is a terrible place to
     * learn this, and "the navigator never planned a pillar" and "it planned one and the avatar could
     * not execute the jump-and-place" are different bugs — {@code PillarUp} exists as a pathfinder
     * move, so this scene's failure message carries the path length, which separates them.
     */
    private static void serverPillarsOutOfAPit(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = -6; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        // Solid ground with a one-wide, four-deep shaft in the middle of it — the exact hole a
        // mining rung leaves behind.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -5; dy <= 0; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            Blocks.STONE.defaultBlockState());
        for (int dy = -4; dy <= 0; dy++)
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz), Blocks.AIR.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);      // pillaring is the point; forbidding it would be circular
        Harness.set("navigatorDebug", false);
        Harness.set("pathfinder.sliceMs", 20);
        Harness.set("pathfinder.maxMs", 2000);

        final int pitFloorY = floorY - 4;
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, pitFloorY, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 32));
        fp.getInventory().selected = 0;

        final int startY = fp.blockPosition().getY();
        bot.goTo(Targets.level(floorY + 1));

        ctx.await(() -> !bot.busy()).within(1_200).then(() -> {
            BlockPos at = fp.blockPosition();
            ControlContracts.Slot walk = bot.gotoSlot();
            String diag = " [diag startY=" + startY + " endY=" + at.getY()
                    + " surfaceY=" + (floorY + 1)
                    + " climbed=" + (at.getY() - startY)
                    + " bot@" + (at.getX() - cx) + "," + (at.getY() - floorY) + "," + (at.getZ() - cz)
                    + " cobble=" + fp.getInventory().countItem(Items.COBBLESTONE)
                    + " end=" + bot.status().lastProcessEnd()
                    + " path=" + walk.pathStep() + "/" + walk.pathLen()
                    + " endReason=" + walk.endReason().orElse(null) + "]";
            ctx.expect(at.getY() >= floorY)
                    .as("the bot pillared out of the shaft it would have dug — a miner that cannot"
                            + " leave its own hole strands every rung after it" + diag)
                    .isEqualTo(true);
            // NOT "it spent blocks pillaring". That was the first version of this assertion and it
            // was wrong in the instructive way: the bot got out in 46 ticks having spent nothing,
            // because with breaking allowed it cut a staircase through the shaft wall — which is
            // what a player with a pickaxe does, and is a better answer than pillaring. Demanding
            // the pillar would have written one implementation into the requirement and reported a
            // capability as missing while watching it work.
            ctx.expect(at.getX() != cx || at.getZ() != cz || at.getY() >= floorY)
                    .as("and it is genuinely out — not still standing in the shaft column at the"
                            + " depth it started" + diag)
                    .isEqualTo(true);
        });
    }

    /**
     * Nine deep, one wide, and a ceiling — the shaft an honest mining rung actually leaves.
     *
     * <p>{@link #serverPillarsOutOfAPit} is four deep and open to the sky, and it passes in 46
     * ticks. The journey's stone rung sinks nine courses and then mines sideways, so the bot ends
     * under its own roof, and there the navigator measured one block of climb in 6 000 ticks. Two
     * things differ at once — depth, and the overhang — so this scene reproduces both and drives
     * the exit the way the journey now scripts it: clear {@code feet+2}, then
     * {@code mc.bot.construct} a one-course tower, repeat.
     *
     * <p>The overhang is the interesting half. A tower does not break; it jumps and
     * fills the cell it left. Under a roof that is a jump into rock, reported as "stuck (no Y gain)"
     * — a message that reads like a missing capability and is really a missing step in the plan.
     */
    private static void serverTowersOutOfADeepShaft(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = -11; dy <= 3; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = -10; dy <= 0; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            Blocks.STONE.defaultBlockState());
        // The shaft, and then one cell of sideways working at the bottom — which is what puts a
        // roof over the bot's head. Standing in the alcove, the column home is a step away and
        // the way up is through stone.
        for (int dy = -9; dy <= 0; dy++)
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz), Blocks.AIR.defaultBlockState());
        for (int dy = -9; dy <= -8; dy++)
            level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + dy, cz), Blocks.AIR.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", false);
        Harness.set("pathfinder.sliceMs", 20);
        Harness.set("pathfinder.maxMs", 2000);

        final int shaftFloorY = floorY - 9;
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 1.5, shaftFloorY, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        // Slot 0, because that is the constraint a tower not told to reach into the bag has: it
        // only scans the hotbar, and a run whose cobblestone had settled into the main inventory
        // would report "no placeable block in hotbar" while carrying thirty of them.
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 32));
        fp.getInventory().selected = 0;
        // A stone pickaxe, because the ceiling is stone and the exit is only scriptable if the
        // bot can clear it in a sensible number of ticks.
        fp.getInventory().items.set(1, new ItemStack(Items.STONE_PICKAXE));

        final int startY = fp.blockPosition().getY();
        towerOneCourse(ctx, bot, floorY + 1, 40, () -> {
            BlockPos at = fp.blockPosition();
            String diag = " [diag startY=" + startY + " endY=" + at.getY()
                    + " surfaceY=" + (floorY + 1)
                    + " climbed=" + (at.getY() - startY)
                    + " bot@" + (at.getX() - cx) + "," + (at.getY() - floorY) + "," + (at.getZ() - cz)
                    + " cobble=" + fp.getInventory().countItem(Items.COBBLESTONE)
                    + " builder=" + bot.status().slots().builder().lastError().orElse(null) + "]";
            ctx.expect(at.getY() > floorY)
                    .as("the bot climbed nine courses out of a roofed shaft — the exit a mining"
                            + " rung has to make before the next rung can go anywhere" + diag)
                    .isEqualTo(true);
        });
    }

    /** One course of the scripted exit: clear {@code feet+2} if it is solid, else tower one block.
     *  Recursive rather than looped for the same reason the journey's version is — a course is two
     *  waits, and the bot has to move between them. */
    private static void towerOneCourse(SceneContext ctx, SceneBot bot, int surfaceY, int budget, Runnable then) {
        ServerPlayer fp = bot.player();
        BlockPos at = fp.blockPosition();
        if (at.getY() >= surfaceY || budget <= 0) { then.run(); return; }
        BlockPos ceiling = at.above(2);
        if (fp.level().getBlockState(ceiling).blocksMotion()) {
            bot.mineCell(ceiling);
            ctx.await(() -> !bot.busy()).within(400)
                    .then(() -> towerOneCourse(ctx, bot, surfaceY, budget - 1, then));
            return;
        }
        // Land before jumping, exactly as the journey's version does. A tower waits for onGround
        // before it starts and counts stuck ticks from zero, so a bot still settling out of the
        // mine that preceded it burns its whole patience falling and reports "out of blocks?" while
        // holding thirty-two cobblestone. Stand still for 40 ticks, as the journey does — an arena
        // that models the routine with a different settle is not modelling the routine.
        if (!fp.onGround()) {
            bot.cancel();
            int until = ctx.ticks() + 40;
            ctx.await(() -> ctx.ticks() >= until).within(60)
                    .then(() -> towerOneCourse(ctx, bot, surfaceY, budget - 1, then));
            return;
        }
        bot.tower(at.getY() + 1, "minecraft:cobblestone", false);
        ctx.await(() -> !bot.busy()).within(200).then(() -> {
            if (fp.blockPosition().getY() <= at.getY()) { then.run(); return; }
            towerOneCourse(ctx, bot, surfaceY, budget - 1, then);
        });
    }

    /**
     * Walk to the bottom of a two-deep, one-wide pit six blocks away.
     *
     * <p>The navigation half of {@code wd.serverMineHarvestBuried}, on its own. That scene mines
     * three ores, two of them buried, and then fails to collect the drops that fell into the holes;
     * its verdict reads {@code collect timed out after 240 ticks} with {@code collectPath=0/0} —
     * the collect navigator searched for the whole budget and never produced a path, and never said
     * FAILED either. "Cannot path into a pit" and "can path but the collect logic asks for the
     * wrong cell" produce exactly that same line, and they live in different files.
     *
     * <p>So the pit is dug by the harness rather than by the bot, there is no item and no mining,
     * and the only verb under test is {@code mc.bot.goto} to the pit floor's cell. Whatever this
     * scene says is unambiguous.
     */
    private static void serverWalkIntoAPit(SceneContext ctx) { walkIntoAPit(ctx, false); }

    /** {@link #serverWalkIntoAPit} with the permissions a mine's collect sweep actually runs under. */
    private static void serverWalkIntoAPitArmed(SceneContext ctx) { walkIntoAPit(ctx, true); }

    private static void walkIntoAPit(SceneContext ctx, boolean armed) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -2; dx <= 9; dx++)
                for (int dy = -3; dy <= 2; dy++)
                    for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });

        for (int dx = -2; dx <= 9; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = -3; dy <= 0; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                            Blocks.STONE.defaultBlockState());

        // Exactly the hole a bot leaves when it digs down to an ore one course under the floor:
        // one wide, two deep, walls on all four sides.
        BlockPos pitBottom = new BlockPos(cx + 6, floorY - 1, cz);
        level.setBlockAndUpdate(new BlockPos(cx + 6, floorY, cz), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pitBottom, Blocks.AIR.defaultBlockState());

        // Unarmed: nothing to dig or pave with, so the only answer the navigator can give is about
        // the pit. Armed: exactly what MineProcess.COLLECT runs under, dirt in hand included.
        Harness.set("allowBreak", armed);
        Harness.set("allowPlace", armed);
        Harness.set("navigatorDebug", false);
        Harness.set("pathfinder.sliceMs", 20);
        Harness.set("pathfinder.maxMs", 2000);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        if (armed) {
            fp.getInventory().items.set(0, new ItemStack(Items.DIRT, 16));   // holdPlaceable's pick
            fp.getInventory().items.set(1, new ItemStack(Items.STONE_PICKAXE));
            fp.getInventory().selected = 0;
        }

        bot.goTo(Targets.block(pitBottom));

        ctx.await(() -> !bot.busy()).within(700).then(() -> {
            BlockPos at = fp.blockPosition();
            double away = Math.sqrt(at.distSqr(pitBottom));
            ControlContracts.Slot walk = bot.gotoSlot();
            String diag = " [diag pit=" + (pitBottom.getX() - cx) + "," + (pitBottom.getY() - floorY)
                    + "," + (pitBottom.getZ() - cz)
                    + " bot@" + (at.getX() - cx) + "," + (at.getY() - floorY) + "," + (at.getZ() - cz)
                    + " dist=" + String.format("%.1f", away)
                    + " end=" + bot.status().lastProcessEnd()
                    + " pathStep=" + walk.pathStep() + "/" + walk.pathLen()
                    + " endReason=" + walk.endReason().orElse(null)
                    + " lastError=" + walk.lastError().orElse(null) + "]";
            ctx.expect(at.getY() <= pitBottom.getY())
                    .as("the bot got down into the pit — a drop at the bottom of a two-deep hole"
                            + " is only unreachable if this is false" + diag)
                    .isEqualTo(true);
            ctx.expect(away <= 1.5)
                    .as("and it got to the pit's own cell, which is where vanilla's pickup magnet"
                            + " would reach an item lying there" + diag)
                    .isEqualTo(true);
        });
    }

    /** How many of a cell's six neighbours are non-solid — 0 means the item is walled in. */
    private static int openSides(ServerLevel level, BlockPos cell) {
        int open = 0;
        for (Direction d : Direction.values())
            if (!level.getBlockState(cell.relative(d)).isSolidRender(level, cell.relative(d))) open++;
        return open;
    }

    private static void mineHarvest(SceneContext ctx, boolean buried) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (ItemEntity stray : level.getEntitiesOfClass(ItemEntity.class, entityBox(cx, floorY, cz)))
                stray.discard();
            for (int dx = -2; dx <= 10; dx++)
                for (int dy = -3; dy <= 2; dy++)
                    for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // DIRT floor (not a target, and not a pickaxe block) over three courses of STONE, so the
        // two buried ores can be dug down to and the drops land in the hole the bot just made.
        for (int dx = -2; dx <= 10; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= -1; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.DIRT.defaultBlockState());
            }
        // Three ores, spread out and at three depths — one at eye level, one a course down, one
        // two. Collecting them is a CIRCUIT, not a reach: the bot walks, digs, and has to come
        // back for what fell into each hole. A single exposed ore at arm's length was enough to
        // catch the pickup-delay bug and blind to the one after it, where three ores were mined
        // in the field and all three drops stayed on the ground.
        BlockPos ore = new BlockPos(cx + 3, floorY + 1, cz);
        BlockPos oreB = new BlockPos(cx + 6, buried ? floorY - 1 : floorY + 1, cz);
        BlockPos oreC = new BlockPos(cx + 9, buried ? floorY - 2 : floorY + 1, cz);
        level.setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
        level.setBlockAndUpdate(oreB, Blocks.IRON_ORE.defaultBlockState());
        level.setBlockAndUpdate(oreC, Blocks.IRON_ORE.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", true);      // the hazard: placing is what makes the hand hold dirt
        Harness.set("navigatorDebug", false);
        // Real ticks — a pathfinder slice that blocks the server thread would stall the suite,
        // so these stay the generous-but-bounded values the journey rig uses, not MAX_VALUE/2.
        Harness.set("pathfinder.sliceMs", 20);
        Harness.set("pathfinder.maxMs", 2000);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.getInventory().clearContent();
        fp.getInventory().items.set(0, new ItemStack(Items.DIRT, 16));   // what holdPlaceable will grab
        fp.getInventory().items.set(20, new ItemStack(Items.STONE_PICKAXE));  // bag, not hotbar
        fp.getInventory().selected = 0;
        // Ask for FOUR and put THREE there. Every way a mine can bank nothing is on this path:
        // the quota comes up short (so the give-up branch must still sweep what was broken),
        // the first drop is at the bot's feet with its pickup delay still running (so COLLECT
        // must not read "no goal" as "nothing left"), and the other two are at the bottom of
        // holes the bot dug, several blocks apart (so the sweep has to path back to them). A
        // quota that matched the rig would exercise none of it, and the journey's iron rung asks
        // for four ores out of a vein that does not hold four — the short quota IS the normal case.
        bot.mine(List.of("minecraft:iron_ore"), 4, 12);

        ctx.await(() -> !bot.busy()).within(1_200).then(() -> {
            int banked = fp.getInventory().countItem(Items.RAW_IRON);
            int drops = 0;
            StringBuilder where = new StringBuilder();
            for (ItemEntity it : level.getEntitiesOfClass(ItemEntity.class, entityBox(cx, floorY, cz)))
                if (it.getItem().is(Items.RAW_IRON)) {
                    drops += it.getItem().getCount();
                    BlockPos cell = it.blockPosition();
                    // From `cell` — which this loop already computed one line up — and not from
                    // (int) casts of the raw doubles: those truncate toward zero, so a drop at
                    // x=-0.4 read as offset 0 while it sat in the cell at -1. The offset is the
                    // whole point of the row, and the arena's own origin can be negative.
                    where.append(" drop@").append(cell.getX() - cx).append(",")
                         .append(cell.getY() - floorY).append(",").append(cell.getZ() - cz)
                         .append("(d=").append(String.format("%.1f", Math.sqrt(it.distanceToSqr(fp))))
                    // Is there a way IN? level.destroyBlock has no reach gate, so this avatar can
                    // break a block it could never have touched — and an ore mined through solid
                    // rock leaves its drop in a SEALED pocket. "Sealed" and "reachable but the
                    // navigator stopped short" both end as an uncollected item four blocks away, and
                    // no amount of pathfinder work fixes the first one.
                         .append(",above=").append(level.getBlockState(cell.above()).getBlock())
                         .append(",openSides=").append(openSides(level, cell)).append(")");
                }
            ControlContracts.BotStatus status = bot.status();
            ControlContracts.Slot mine = status.slots().mine();
            String diag = " [diag oresLeft=" + standingLabel(level, List.of(ore, oreB, oreC))
                    + " banked=" + banked + " drops=" + drops
                    + " held=" + fp.getMainHandItem().getItem()
                    + " idle=" + !status.busy()
                    + " lastError=" + mine.lastError().orElse(null)
                    + " endReason=" + mine.endReason().orElse(null)
                    + " bot@" + (fp.blockPosition().getX() - cx) + "," + (fp.blockPosition().getY() - floorY)
                    + "," + (fp.blockPosition().getZ() - cz)
                    + " end=" + status.lastProcessEnd()
                    + " collectPath=" + mine.pathStep() + "/" + mine.pathLen()
                    + where + "]";
            int standing = 0;
            for (BlockPos at : List.of(ore, oreB, oreC)) if (level.getBlockState(at).is(Blocks.IRON_ORE)) standing++;
            if (standing > 0)
                ctx.fail("mineHarvest rig: " + standing + " of 3 ores were never broken" + diag);
            if (status.busy())
                ctx.fail("mineHarvest: the mine did not finish and go idle" + diag);
            if (banked < 3)
                ctx.fail((buried ? "mineHarvestBuried" : "mineHarvest")
                        + ": 3 ores broke but only " + banked + " raw iron reached the inventory — "
                        + (drops > 0 ? "the drop exists and was not collected" : "no drop was ever created")
                        + diag);
            // A finished command must say how it ended. `active:false` with no error reads as
            // success, and this verb can end clean having banked nothing — which is what made the
            // two collection bugs above take three playthrough runs to tell apart from "the ore
            // was never reached". Same contract the bunker and the goto already keep.
            if (mine.endReason().isEmpty())
                ctx.fail("mineHarvest: the mine finished without stating a terminal verdict" + diag);
            // Whether the bot EARNED anything for this is a separate question with a separate
            // answer per loader — see wd.serverAvatarEarnsAdvancement.
        });
    }

    // ==================================================================================
    // wd.serverMineNoTool — gap#2 tool-capability gate: toolless bot must NOT grind
    // harvest-requiring blocks; must abort cleanly with a signal naming the missing tool.
    // ==================================================================================

    private static void serverMineNoToolScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 9; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // DIRT floor (NOT a target, mines fine bare-handed) so the scan only weighs the stone.
        for (int dx = -1; dx <= 9; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.DIRT.defaultBlockState());
        BlockPos[] targets = {
                new BlockPos(cx + 2, floorY + 1, cz),
                new BlockPos(cx + 4, floorY + 1, cz),
                new BlockPos(cx + 6, floorY + 1, cz),
        };
        for (BlockPos t : targets) level.setBlockAndUpdate(t, Blocks.STONE.defaultBlockState());

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        // NO pickaxe — empty-handed, matching the campaign soft-lock (broken pickaxe, no craft path).
        bot.mine(List.of("minecraft:stone"), 3, 8);
        bot.tickUntilIdle(400);

        int remaining = 0;
        for (BlockPos t : targets) if (!level.getBlockState(t).isAir()) remaining++;
        String err = bot.status().slots().mine().lastError().orElse(null);
        TestMod.LOG.info("[wd.serverMineNoTool] busy={} remaining={}/3 lastError={}",
                Harness.busy(), remaining, err);
        // Must have mined NONE — a harvest-requiring block with no tool yields nothing.
        if (remaining != 3)
            ctx.fail("toolless mine broke " + (3 - remaining)
                    + "/3 stone for zero drops (should mine none): remaining=" + remaining);
        // Must have aborted cleanly (finished and idle), not spun or ground the quota.
        if (bot.busy() || Harness.busy() != 0)
            ctx.fail("toolless mine did not abort and go idle: busy=" + Harness.busy());
        // Signal must name the missing tool so the planner can act (craft/relocate).
        if (err == null || !err.contains("pickaxe"))
            ctx.fail("expected a tool-block signal naming a pickaxe, got: " + err);
    }

    // ==================================================================================
    // wd.serverNavigatorDeepslateNoTool — gap#2 verdict/regression guard: the Navigator EXECUTOR clears a
    // bare-hand deepslate plug it lacks the tool for (does NOT soft-lock). faithfulBreak ON.
    // ==================================================================================

    private static void serverNavigatorDeepslateNoToolScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 3; dy++)
                    for (int dz = -1; dz <= 5; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // BEDROCK shell (unbreakable → infinite breakCost) so the ONLY finite route is
        // straight through the deepslate plug. Interior corridor: x=cx, z=cz..cz+4, 2-tall.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 5; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.BEDROCK.defaultBlockState());       // floor
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), Blocks.BEDROCK.defaultBlockState());   // ceiling
            }
        for (int dz = -1; dz <= 5; dz++)
            for (int dy = 1; dy <= 2; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx - 1, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());   // west wall
                level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());   // east wall
            }
        for (int dy = 1; dy <= 2; dy++) {
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz - 1), Blocks.BEDROCK.defaultBlockState());           // back cap
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz + 5), Blocks.BEDROCK.defaultBlockState());           // front cap
        }
        // Clear the interior to air, then plug z=cz+2 with deepslate (foot + head cells).
        for (int dz = 0; dz <= 4; dz++)
            for (int dy = 1; dy <= 2; dy++)
                level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        BlockPos plugFoot = new BlockPos(cx, floorY + 1, cz + 2);
        BlockPos plugHead = new BlockPos(cx, floorY + 2, cz + 2);
        level.setBlockAndUpdate(plugFoot, Blocks.DEEPSLATE.defaultBlockState());
        level.setBlockAndUpdate(plugHead, Blocks.DEEPSLATE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + 4);

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);
        Harness.set("faithfulBreak", true);   // REAL destroy-progress: bare-hand deepslate ~650t (else instant destroyBlock masks the timing)

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        // NO pickaxe — empty-handed, matching the campaign soft-lock at y-14 deepslate.
        bot.goTo(Targets.block(goal));               // the navigator's own digging, no mine task

        final int BUDGET = 3000;                     // >> navigatorTotalTickBudget(1200); an infinite grind stays active past this
        int endTick = -1;
        for (int t = 0; t < BUDGET; t++) {
            if (!bot.busy()) { endTick = t; break; }
            bot.tick();
        }
        if (endTick < 0 && !bot.busy()) endTick = BUDGET;

        ServerPlayer fp = bot.player();
        int plugRemaining = (level.getBlockState(plugFoot).isAir() ? 0 : 1)
                          + (level.getBlockState(plugHead).isAir() ? 0 : 1);
        boolean reached = Math.abs(fp.getZ() - (cz + 4 + 0.5)) < 1.5 && fp.getY() >= floorY + 1 - 0.4;
        TestMod.LOG.info("[wd.serverNavigatorDeepslateNoTool] endTick={} end={} pos=({},{},{}) busy={} reached={} plugRemaining={}/2",
                endTick, bot.status().lastProcessEnd(), fp.getX(), fp.getY(), fp.getZ(),
                Harness.busy(), reached, plugRemaining);
        // Real guard: the bot must BREAK THROUGH the bare-hand deepslate plug and REACH
        // the goal. A reset-stall regression (dig never completes) would leave the plug
        // solid and the bot short of the goal — reached/plugRemaining catch it, where a
        // bare idle check would not (it stays green even on a FAIL-at-budget).
        if (!reached || plugRemaining != 0)
            ctx.fail("Navigator did not clear bare-hand deepslate to the goal: reached="
                    + reached + " plugRemaining=" + plugRemaining + "/2 end=" + bot.status().lastProcessEnd()
                    + " endTick=" + endTick + " z=" + fp.getZ());
    }

    // ==================================================================================
    // wd.serverForbidDigWall ⚡ — per-goto forbidDig (NoBreak) against a solid wall = clean planner
    // give-up (Phase A), and precision guard without forbidDig digs through + reaches (Phase B).
    // ==================================================================================

    private static void serverForbidDigWallScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // Ground-anchor to THIS scene's slot (the leash's nearest-entity scan needs the armor
        // stand in Level.getEntities).
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();
        final int plugDz = 6;                              // plug sits 6 CLEAR cells from start: best-effort partial is multi-node (>1) so the Navigator WALKS it to the wall face, where the carrot presses the plug → wallDig can engage. A 1-cell approach ARRIVES at start before the executor ever runs.
        final int standDz = 9;                             // stand sits 3 cells BEYOND the plug
        final double leashRadius = 16.0;                   // stand ~9 from start → inside → PULL forward, not hold back

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 3; dy++)
                    for (int dz = -1; dz <= standDz + 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Build the BEDROCK shell corridor (only route = straight through the plug).
        Runnable buildShell = () -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= standDz + 2; dz++) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.BEDROCK.defaultBlockState());       // floor
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + 3, cz + dz), Blocks.BEDROCK.defaultBlockState());   // ceiling
                }
            for (int dz = -1; dz <= standDz + 2; dz++)
                for (int dy = 1; dy <= 2; dy++) {
                    level.setBlockAndUpdate(new BlockPos(cx - 1, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());   // west wall
                    level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + dy, cz + dz), Blocks.BEDROCK.defaultBlockState());   // east wall
                }
            for (int dy = 1; dy <= 2; dy++) {
                level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz - 1), Blocks.BEDROCK.defaultBlockState());           // back cap
                level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz + standDz + 2), Blocks.BEDROCK.defaultBlockState()); // front cap
            }
        };
        BlockPos plugFoot = new BlockPos(cx, floorY + 1, cz + plugDz);
        BlockPos plugHead = new BlockPos(cx, floorY + 2, cz + plugDz);
        Runnable setPlug = () -> {
            for (int dz = 0; dz <= standDz + 1; dz++)
                for (int dy = 1; dy <= 2; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(plugFoot, Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(plugHead, Blocks.DIRT.defaultBlockState());
        };
        BlockPos standCell = new BlockPos(cx, floorY + 1, cz + standDz);

        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);
        Harness.set("navigatorDebug", false);
        Harness.set("navigatorWallDigFallback", true);          // match the live default that produced day6
        Harness.untimed(ctx);
        final int BUDGET = 1600;                          // > navigatorTotalTickBudget(1200); wallDig fires within ~40t of engaging
        buildShell.run();
        var stand = new ArmorStand(level, cx + 0.5, floorY + 1, cz + standDz + 0.5);   // BEYOND the plug
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        ctx.cleanup(() -> stand.discard());
        // A hard leash on the stand: inside its radius from the start, so it pulls forward rather
        // than holding back.
        Routes leashed = Routes.plain().leashEntity("minecraft:armor_stand", leashRadius, true, 0);

        // Poll (bounded) until the fresh armor stand is queryable, then run Phase A + Phase B.
        ctx.await(() -> !level.getEntitiesOfClass(ArmorStand.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            // ---- Phase A: forbidDig (NoBreak) — the plug MUST survive, bot must NOT get past it. ----
            setPlug.run();
            SceneBot botA = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            ServerPlayer fpA = botA.player();
            fpA.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));  // NOT tool-gated: only forbidDig can stop the dig
            fpA.getInventory().selected = 0;
            botA.goTo(Targets.near(standCell, 1), leashed.noBreak().build());
            int endA = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (!botA.busy()) { endA = t; break; }
                botA.tick();
            }
            int plugA = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean gotPastA = fpA.getZ() > cz + plugDz + 1.0;   // past the plug = tunnelled through
            Optional<ControlContracts.ProcessEnd> endedA = botA.status().lastProcessEnd();
            TestMod.LOG.info("[wd.serverForbidDigWall] A(forbidDig) endTick={} end={} pos=({},{},{}) gotPast={} plugRemaining={}/2",
                    endA, endedA, fpA.getX(), fpA.getY(), fpA.getZ(), gotPastA, plugA);
            double zA = fpA.getZ();
            botA.remove();                                  // so the first bot can't linger into Phase B
            if (plugA != 2 || gotPastA) {
                ctx.fail("forbidDig LEAK: executor dig fallback punched the wall despite NoBreak — "
                        + "plugRemaining=" + plugA + "/2 (want 2) gotPast=" + gotPastA + " (want false) end=" + endedA
                        + " z=" + zA);
                return;
            }

            // ---- Phase B: precision — SAME rig, NO forbidDig — must dig through and REACH the stand. ----
            setPlug.run();
            SceneBot botB = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            ServerPlayer fpB = botB.player();
            fpB.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
            fpB.getInventory().selected = 0;
            botB.goTo(Targets.near(standCell, 1), leashed.build());   // no NoBreak = digging allowed
            int endB = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (!botB.busy()) { endB = t; break; }
                botB.tick();
            }
            int plugB = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean reachedB = Math.abs(fpB.getX() - (cx + 0.5)) < 1.5 && Math.abs(fpB.getZ() - (cz + standDz + 0.5)) < 2.0;
            Optional<ControlContracts.ProcessEnd> endedB = botB.status().lastProcessEnd();
            TestMod.LOG.info("[wd.serverForbidDigWall] B(precision) endTick={} end={} pos=({},{},{}) reached={} plugRemaining={}/2",
                    endB, endedB, fpB.getX(), fpB.getY(), fpB.getZ(), reachedB, plugB);
            if (!reachedB || plugB != 0)
                ctx.fail("precision guard: without forbidDig the bot must dig through and reach the stand — "
                        + "reached=" + reachedB + " (want true) plugRemaining=" + plugB + "/2 (want 0) end=" + endedB
                        + " z=" + fpB.getZ());
        });
    }

    // ==================================================================================
    // wd.serverBuild — the server bot places a two-block schematic through mc.bot.build.
    // ==================================================================================

    private static void serverBuildScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 6; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -1; dx <= 6; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.DIRT.defaultBlockState());
        BlockPos origin = new BlockPos(cx, floorY, cz);
        BlockPos t1 = new BlockPos(cx + 2, floorY + 1, cz);
        BlockPos t2 = new BlockPos(cx + 3, floorY + 1, cz);
        // 4×2×1 with cobblestone at (2,1,0) and (3,1,0): [dx, dy, dz, palette index].
        BuildContracts.Blocks schem = new BuildContracts.Blocks(4, 2, 1, List.of("minecraft:cobblestone"),
                List.of(List.of(2, 1, 0, 0), List.of(3, 1, 0, 0)));

        Harness.set("allowBreak", false);
        Harness.set("allowPlace", true);
        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        bot.player().getInventory().items.set(0, new ItemStack(Blocks.COBBLESTONE, 64));
        bot.player().getInventory().selected = 0;
        Calls.started("build", Calls.now(SceneBot.tasks().build(
                new BuildContracts.BuildParams(bot.ref(), Targets.pos(origin), schem, 0))));
        bot.tickUntilIdle(400);

        boolean p1 = level.getBlockState(t1).is(Blocks.COBBLESTONE);
        boolean p2 = level.getBlockState(t2).is(Blocks.COBBLESTONE);
        ServerPlayer fp = bot.player();
        TestMod.LOG.info("[wd.serverBuild] end={} pos=({},{},{}) busy={} placed1={} placed2={}",
                bot.status().lastProcessEnd(), fp.getX(), fp.getY(), fp.getZ(), Harness.busy(), p1, p2);
        if (!p1 || !p2)
            ctx.fail("server build failed to place both cobble: t1="
                    + level.getBlockState(t1) + " t2=" + level.getBlockState(t2));
        if (bot.busy() || Harness.busy() != 0)
            ctx.fail("build did not finish and go idle: busy=" + Harness.busy());
    }

    // ==================================================================================
    // wd.serverLookRaycast — the body's eye→view clip raycast (mc.debug.body hands.lookingAt).
    // ==================================================================================

    private static void serverLookRaycastScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 7; dx++)
                for (int dy = -1; dy <= 7; dy++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // Clear the arena volume to air first (defensive against shared-world residue in the ray's
        // path), then lay the floor + the single target stone.
        for (int dx = -3; dx <= 7; dx++)
            for (int dy = -1; dy <= 7; dy++)
                for (int dz = -3; dz <= 3; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        for (int dx = -1; dx <= 4; dx++)
            level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz), Blocks.DIRT.defaultBlockState());
        BlockPos target = new BlockPos(cx + 2, floorY + 1, cz);   // a stone 2 cells east at foot height
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        Harness.set("smoothLook", false);            // turn at once, so the read below sees the new aim
        bot.lookAt(target);                          // sets yaw/pitch toward the cell's centre
        BlockPos look = bot.body().hands().lookingAt().map(Targets::cell).orElse(null);   // eye→view clip raycast
        TestMod.LOG.info("[wd.serverLookRaycast] aim={} look={} match={}",
                target.toShortString(), look == null ? "null" : look.toShortString(),
                target.equals(look));
        if (!target.equals(look))
            ctx.fail("server lookingAtBlock did not resolve the aimed cell: aim="
                    + target.toShortString() + " look=" + (look == null ? "null" : look.toShortString()));
    }

    // ==================================================================================
    // wd.serverFollow — the server bot runs mc.bot.follow (entity-sensing) onto an armor stand.
    // ==================================================================================

    /** {@code mc.bot.follow} the nearest armor stand to within {@code radius}, never idling out, giving up after
     *  {@code giveUpTicks} without progress; plain route. */
    private static void followStand(SceneBot bot, int radius, int giveUpTicks) {
        Calls.started("follow", Calls.now(SceneBot.tasks().follow(new MoveContracts.FollowParams(bot.ref(),
                new MoveContracts.QuarryType("minecraft:armor_stand"), radius, 0, giveUpTicks, Optional.empty(), 0))));
    }

    private static void serverFollowScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // Ground-anchored slot (a fresh entity must promote into getEntities).
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -2; dx <= 12; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -2; dx <= 12; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        var stand = new ArmorStand(level, cx + 8 + 0.5, floorY + 1, cz + 0.5);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        ctx.cleanup(() -> stand.discard());

        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        // A freshly-added entity isn't in the queryable section index until the level indexes it;
        // poll (bounded) until the stand appears, then drive.
        ctx.await(() -> !level.getEntitiesOfClass(ArmorStand.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            followStand(bot, 2, 600);
            bot.tickUntilIdle(200);

            ServerPlayer fp = bot.player();
            double dx = fp.getX() - (cx + 8 + 0.5), dz = fp.getZ() - (cz + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            boolean closed = dist <= 3.0;   // follow radius 2 + slack
            TestMod.LOG.info("[wd.serverFollow] pos=({},{},{}) standDist={} closed={}",
                    fp.getX(), fp.getY(), fp.getZ(), dist, closed);
            if (!closed)
                ctx.fail("server follow did not close on the armor stand: dist=" + dist);
        });
    }

    // ==================================================================================
    // wd.serverFollowGivesUpOnAPennedQuarry — a quarry pacing inside a sealed bedrock pen. The bot
    // can walk round the pen but never reach the standoff, and every lap reads as movement to the
    // navigator's futile guard, so only the follow's own give-up window can end it.
    // ==================================================================================

    private static final int PEN_GIVE_UP_TICKS = 200;

    // ==================================================================================
    // wd.serverCombat — the server bot runs mc.bot.combat (kill) on a NoAI zombie; the drive loop
    // ticks the zombie DIRECTLY (not level.tick) for its hurt-cooldown.
    // ==================================================================================

    /** {@code mc.bot.combat}: kill the nearest zombie. By TYPE (scans Level.getEntities) rather than by id — the
     *  by-id lookup is not populated without a full level.tick(); type-mode exercises the same melee loop. */
    private static void killZombie(SceneBot bot) {
        Calls.started("combat", Calls.now(SceneBot.tasks().combat(new CombatContracts.Params(bot.ref(),
                CombatContracts.Mode.KILL, Optional.of(new CombatContracts.FoeType("minecraft:zombie")), false, 0))));
    }

    // ==================================================================================
    // wd.serverCombatCollectDrops — after the kill, the combat loop must SWEEP the drops
    // near the kill spot before terminating (Harness.flag("combatCollectDrops"); live 01:32
    // 2026-07-21: six hunt kills banked one porkchop — melee kites away from the corpse
    // and the old loop ended wherever it stood).
    // ==================================================================================

    /** Same rig as {@code wd.serverCombat}, plus a deterministic "drop": an ItemEntity
     *  pre-placed 3 blocks BEYOND the zombie (cx+9; within the sweep's 8-block scan of the
     *  kill spot cx+6, but a spot the pre-fix loop had no reason to ever visit — it fought
     *  at reach range cx+4..5 and terminated in place). Pre-placed instead of relying on
     *  the zombie's own RNG loot (0–2 rotten flesh — a zero-roll would flake the scene).
     *  PASS = the process finishes AND the drop was swept: item picked up (dead / in
     *  inventory) or the bot finished standing at it (pickup fidelity on a FakePlayer is
     *  not this scene's contract — the walk-to-the-drop is). */
    private static void serverCombatCollectDropsScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -2; dx <= 10; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -2; dz <= 2; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -2; dx <= 10; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        var zombie = new Zombie(level);
        zombie.setPos(cx + 6 + 0.5, floorY + 1, cz + 0.5);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        var kbr = zombie.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kbr != null) kbr.setBaseValue(1.0);
        level.addFreshEntity(zombie);
        ctx.cleanup(() -> zombie.discard());
        var drop = new ItemEntity(level, cx + 9 + 0.5, floorY + 1, cz + 0.5,
                new ItemStack(Items.PORKCHOP, 3));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setNoGravity(true);              // stays put without needing item ticks
        level.addFreshEntity(drop);
        ctx.cleanup(() -> drop.discard());
        // Night comes from StageWright's suite-wide world pin, not from here.

        Harness.set("navigatorDebug", false);
        Harness.untimed(ctx);

        ctx.await(() -> !level.getEntitiesOfClass(Zombie.class, entityBox(cx, floorY, cz)).isEmpty()
                        && !level.getEntitiesOfClass(ItemEntity.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            bot.player().getInventory().clearContent();
            bot.player().getInventory().add(new ItemStack(Items.IRON_SWORD));
            killZombie(bot);

            // Diagnostics live in the FAIL MESSAGE, not LOG.info — late-suite async
            // log lines are dropped wholesale on shutdown.
            int killedAt = -1, endedAt = -1;
            double killX = Double.NaN;
            for (int t = 0; t < 1500 && bot.busy(); t++) {
                bot.tick();
                if (killedAt < 0 && !zombie.isAlive()) {
                    killedAt = t;
                    killX = bot.player().getX();
                }
                endedAt = t;
                if (zombie.isAlive()) {
                    zombie.tick();
                    zombie.setPos(cx + 6 + 0.5, floorY + 1, cz + 0.5);
                    zombie.setDeltaMovement(Vec3.ZERO);
                }
            }

            ServerPlayer fp = bot.player();
            boolean dead = !zombie.isAlive();
            boolean pickedUp = !drop.isAlive()
                    || fp.getInventory().countItem(Items.PORKCHOP) > 0;
            double distToDrop = Math.sqrt(fp.distanceToSqr(cx + 9 + 0.5, floorY + 1, cz + 0.5));
            ControlContracts.BotStatus status = bot.status();
            String diag = " [diag killedAt=" + killedAt + " endedAt=" + endedAt
                    + " sweepTicks=" + (killedAt >= 0 ? endedAt - killedAt : -1)
                    + " killX=" + killX + " endPos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                    + ") dropAlive=" + drop.isAlive()
                    + " end=" + status.lastProcessEnd()
                    + " goal=" + status.slots().combat().goal().orElse(null) + "]";
            if (!dead)
                ctx.fail("collectDrops rig: zombie not killed: hp=" + zombie.getHealth() + diag);
            if (status.busy() || Harness.busy() != 0)
                ctx.fail("collectDrops: combat did not finish and go idle: busy=" + Harness.busy() + diag);
            if (!pickedUp && distToDrop > 2.0)
                ctx.fail("collectDrops: drop NOT swept — bot ended " + distToDrop
                        + " blocks from the drop (pre-fix behaviour: terminate at the kill spot)"
                        + diag);
        });
    }

    // ==================================================================================
    // wd.serverLook — the server bot runs a smooth mc.bot.lookAt (pure yaw/pitch) as a task.
    // ==================================================================================

    private static void serverLookScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 5; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos track = new BlockPos(cx + 5, floorY + 1, cz);

        Harness.set("navigatorDebug", false);
        // A look is only a task when it turns smoothly; an instant one answers before it returns.
        Harness.set("smoothLook", true);

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        bot.lookAt(track);
        bot.tickUntilIdle(300);

        ServerPlayer fp = bot.player();
        var eye = fp.getEyePosition();
        double dx = track.getX() + 0.5 - eye.x, dy = track.getY() + 0.5 - eye.y, dz = track.getZ() + 0.5 - eye.z;
        float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float yawErr = Math.abs(Mth.degreesDifference(fp.getYRot(), ty));
        float pitchErr = Math.abs(tp - fp.getXRot());
        TestMod.LOG.info("[wd.serverLook] yaw={} (tgt {}) pitch={} (tgt {}) busy={}",
                fp.getYRot(), ty, fp.getXRot(), tp, Harness.busy());
        if (bot.busy() || Harness.busy() != 0)
            ctx.fail("server look did not align+finish: busy=" + Harness.busy()
                    + " lastError=" + bot.status().slots().look().lastError().orElse(null));
        if (yawErr > 2f || pitchErr > 2f)
            ctx.fail("server look off target: yawErr=" + yawErr + " pitchErr=" + pitchErr);
    }

    // ==================================================================================
    // wd.serverMineCanopyRadius — gap#67-⑤ scan budget: MineProcess.scanForTarget must find a
    // dy=+4 canopy log at radius 16 AND radius 32 (the wide radius used to truncate the top dy band).
    // ==================================================================================

    // ==================================================================================
    // wd.serverBridgePillarStart — gap#75-a (live death #24): a bridge from a 1×1 pillar top;
    // arm A = sneak-overhang start (yaw 90° off), arm B = centered baseline.
    // ==================================================================================

    private static void serverBridgePillarStartScene(SceneContext ctx) {
        Harness.pin(ctx);
        Harness.clearBots(ctx);
        Harness.set("navigatorDebug", false);

        // Arm A: live death-#24 shape — sneak-overhang start (center 1.05 east of the pillar cell
        // origin => floor(center) is already the void column) + yaw 90° off. Arm B: centered start.
        bridgePillarLeg(ctx, 0, 0, 0.55, 90f, "A(overhang)");
        bridgePillarLeg(ctx, 0, 16, 0.0, 270f, "B(centered)");
    }

    /** One bridge-from-pillar-top run: 1×1 cobblestone pillar (6 high) over a catch floor,
     *  bot on top at {@code (cx+0.5+xOff, cz+0.5)}, {@code mc.bot.construct} a bridge east ×4.
     *  Asserts: feet never drop below pillar-top−1, 4 bridge blocks laid, process ends {@code done}.
     *  {@code dxRel/dzRel} are offsets from the scene origin (arm A at origin, arm B +16 z). */
    private static void bridgePillarLeg(SceneContext ctx, int dxRel, int dzRel, double xOff,
                                        float startYaw, String leg) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX() + dxRel, cz = ctx.origin().getZ() + dzRel;
        final int floorY = ctx.origin().getY() + 20, pillarH = 6;
        final int topY = floorY + pillarH;        // pillar top block y
        final double feetY = topY + 1;            // bot feet
        final int distance = 4;
        // Catch floor + air box (defensive clear against shared-level residue).
        for (int dx = -3; dx <= 9; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
                for (int y = floorY + 1; y <= topY + 5; y++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
            }
        for (int y = floorY + 1; y <= topY; y++)
            level.setBlockAndUpdate(new BlockPos(cx, y, cz), Blocks.COBBLESTONE.defaultBlockState());
        // Air-scrub this arm's footprint (floor + pillar + bridge cells) at scene resolution.
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 9; dx++)
                for (int y = floorY; y <= topY + 5; y++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        });

        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5 + xOff, feetY, cz + 0.5);
        ServerPlayer fp = bot.player();
        fp.setYRot(startYaw); fp.yHeadRot = startYaw; fp.yBodyRot = startYaw;
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        Calls.started("bridge", Calls.now(SceneBot.tasks().construct(new BuildContracts.ConstructParams(bot.ref(),
                new BuildContracts.Bridge(BuildContracts.BridgeDirection.EAST, distance, Optional.of("minecraft:cobblestone")),
                0))));

        double minY = fp.getY();
        int t = 0;
        for (; t < 400 && bot.busy(); t++) {
            bot.tick();
            minY = Math.min(minY, fp.getY());
            if (t < 40 || t % 20 == 0) {
                ControlContracts.Slot builder = bot.status().slots().builder();
                TestMod.LOG.info("[bridgePillar {} t={}] pos=({},{},{}) onGround={} dm={} step={} lastErr={}",
                        leg, t, String.format("%.3f", fp.getX()), String.format("%.3f", fp.getY()),
                        String.format("%.3f", fp.getZ()), fp.onGround(), fp.getDeltaMovement(),
                        builder.pathStep(), builder.lastError().orElse(null));
            }
        }
        boolean finished = !bot.busy();
        String lastErr = bot.status().slots().builder().lastError().orElse(null);
        StringBuilder laid = new StringBuilder();
        int laidCount = 0;
        for (int i = 1; i <= distance; i++) {
            boolean solid = level.getBlockState(new BlockPos(cx + i, topY, cz)).blocksMotion();
            if (solid) laidCount++;
            laid.append(solid ? '#' : '.');
        }
        TestMod.LOG.info("[bridgePillar {}] END t={} pos=({},{},{}) minY={} laid={} finished={} lastErr={}",
                leg, t, fp.getX(), fp.getY(), fp.getZ(), minY, laid, finished, lastErr);
        if (minY < feetY - 1.0)
            ctx.fail("arm " + leg + ": bot dropped below pillar-top-1 (minY="
                    + minY + ", start feetY=" + feetY + ") — bridge start fell off the pillar (death #24 shape)");
        if (laidCount < distance)
            ctx.fail("arm " + leg + ": bridge only laid " + laidCount + "/"
                    + distance + " (" + laid + ") — lastErr=" + lastErr);
        if (!finished || lastErr == null || !lastErr.startsWith("done"))
            ctx.fail("arm " + leg + ": process did not reach the done terminal: "
                    + "finished=" + finished + " lastErr=" + lastErr);
        bot.remove();
    }

    /** How many of an item the bot holds — the only witness a right-click leaves behind. */
    static int countItem(net.minecraft.server.level.ServerPlayer fp,
                         net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) if (st.is(item)) n += st.getCount();
        return n;
    }

    // ==================================================================================
    // The trunk-tax waiver's STATE MACHINE — three scenes, and deliberately not one of them
    // asks about a price.
    //
    // `pathfinderLogBreakTax` is waived while a MineProcess is working a log, so the walk that
    // goes to fetch the fifth log of a trunk may plan through the four under it. The waiver is
    // DERIVED from the current target rather than latched beside it, and the two things most
    // likely to be wrong are the derivation (does it lift when the goal stops being a log?) and
    // the release (does a bot that dies up a tree leave it set forever?).
    //
    // Both are testable here. The MULTIPLICATION is not, and no scene below claims it is: the
    // tax lives in ClientWorldView, and the server-side player plans through LevelWorldView, which
    // applies no break taxes at all — not this one, not the wrong-tool ×3, not the dig
    // multiplier. On a dedicated server every one of those is inert, so a scene asserting
    // "this cell costs 3×" would be asserting something about a cost table this topology never
    // consults. That half belongs to the live ladder, which runs on a real client. Recorded as
    // J33; the two-view divergence is a finding in its own right, not this fix's business.
    //
    // Each scene OPENS by asserting the waiver is off. A leaked owner from anything earlier
    // then fails here, by name, instead of quietly answering the next scene's question.
    // ==================================================================================

    /** Stage one reachable log, plus the floor to walk it. Shared by all three scenes so the only
     *  difference between them is what the process is asked to do. */
    private static BlockPos stageOneLog(SceneContext ctx, ServerLevel level, int cx, int cz, int floorY) {
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 7; dx++)
                for (int dy = 0; dy <= 3; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz),
                                Blocks.AIR.defaultBlockState());
        });
        for (int x = cx - 1; x <= cx + 7; x++)
            level.setBlockAndUpdate(new BlockPos(x, floorY, cz), Blocks.DIRT.defaultBlockState());
        BlockPos logPos = new BlockPos(cx + 5, floorY + 1, cz);
        level.setBlockAndUpdate(logPos, Blocks.OAK_LOG.defaultBlockState());
        return logPos;
    }

    /** Whether the mine's log-cost waiver is held, as {@code mc.debug.body} reads it. */
    private static boolean waiverOn(SceneBot bot) { return bot.body().logWaiver(); }

    /** The guard every scene here opens with, read before the bot is given anything to do. Returns true when the
     *  run must stop. */
    private static boolean waiverLeakedIn(SceneContext ctx, SceneBot bot, String scene) {
        if (!waiverOn(bot)) return false;
        ctx.fail(scene + ": the waiver was already on when the scene started; another scene leaked its owner. "
                + "Any 'waiver on' reading in this run would not be caused by this scene and cannot count as evidence.");
        return true;
    }

    // wd.mineLogWaiverEngages — the waiver is ON while a log goal is in flight, and OFF once the
    // order is over. Both halves matter: an always-on waiver would pass the first half alone.
    private static void mineLogWaiverEngagesScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        Harness.pin(ctx);
        Harness.clearBots(ctx);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        if (waiverLeakedIn(ctx, bot, "mineLogWaiverEngages")) return;

        BlockPos logPos = stageOneLog(ctx, level, cx, cz, floorY);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);

        bot.player().getInventory().items.set(0, new ItemStack(Items.WOODEN_AXE));
        bot.player().getInventory().selected = 0;
        bot.mine(List.of("#minecraft:logs"), 1, 16);

        int onTicks = 0;
        for (int t = 0; t < 600 && bot.busy(); t++) {
            bot.tick();
            if (waiverOn(bot)) onTicks++;
        }
        boolean mined = !level.getBlockState(logPos).is(Blocks.OAK_LOG);
        boolean stillOn = waiverOn(bot);
        ctx.record("gate.ticks with the waiver on", onTicks);
        ctx.record("gate.log mined", mined);
        ctx.record("gate.waiver still on after the order ended", stillOn);

        // Order matters. "The waiver never came on" and "the bot never reached a log" are the same
        // reading, and only one of them is a defect — so ask about the log first.
        if (!mined) {
            ctx.fail("mineLogWaiverEngages: this run never mined the log, so 'the waiver was off' proves nothing "
                    + "(not triggered, not a counterexample). lastError=" + bot.status().slots().mine().lastError().orElse(null));
            return;
        }
        if (onTicks == 0)
            ctx.fail("mineLogWaiverEngages: the log was mined, but no tick in the run read the waiver as on; "
                    + "the derivation is not wired, so the approach to the log did not get the pricing it should have.");
        if (stillOn)
            ctx.fail("mineLogWaiverEngages: the waiver is still on after the order ended; it would carry over into "
                    + "the bot's next route, which is exactly what the tax exists to prevent.");
    }

    // wd.mineStoneLeavesTheWaiverOff — the control arm. Same rig, same log standing in the world,
    // only the order differs: mine STONE. The waiver must never come on, and the stone must
    // actually get mined, or "never on" is just "never ran".
    private static void mineStoneLeavesTheWaiverOffScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        Harness.pin(ctx);
        Harness.clearBots(ctx);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        if (waiverLeakedIn(ctx, bot, "mineStoneLeavesTheWaiverOff")) return;

        // The log is STAGED and left standing: the point is that a log in the world is not what
        // arms the waiver — a log GOAL is.
        BlockPos logPos = stageOneLog(ctx, level, cx, cz, floorY);
        BlockPos stonePos = new BlockPos(cx + 3, floorY + 1, cz);
        level.setBlockAndUpdate(stonePos, Blocks.STONE.defaultBlockState());
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);

        bot.player().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        bot.player().getInventory().selected = 0;
        bot.mine(List.of("minecraft:stone"), 1, 16);

        int onTicks = 0;
        for (int t = 0; t < 600 && bot.busy(); t++) {
            bot.tick();
            if (waiverOn(bot)) onTicks++;
        }
        boolean stoneGone = !level.getBlockState(stonePos).is(Blocks.STONE);
        ctx.record("gate.ticks with the waiver on", onTicks);
        ctx.record("gate.stone mined", stoneGone);
        ctx.record("gate.log still standing", level.getBlockState(logPos).is(Blocks.OAK_LOG));

        if (!stoneGone) {
            ctx.fail("mineStoneLeavesTheWaiverOff: the stone was not mined, so 'the waiver stayed off throughout' only means "
                    + "'this run did nothing'; it is not a control. lastError=" + bot.status().slots().mine().lastError().orElse(null));
            return;
        }
        if (onTicks > 0)
            ctx.fail("mineStoneLeavesTheWaiverOff: the stone-mining order turned the log-tax waiver on for " + onTicks + " ticks; "
                    + "a log standing in the world must not arm it, only a log goal may.");
    }

    // wd.mineLogWaiverReleasedOnSupersede — the leak scene. It supersedes the order through the
    // SAME door production takes (a new mc.bot.* order), not by calling onCancelled
    // directly: a scene that pokes the method under test would go green against a funnel nothing
    // actually uses. It also proves the waiver was ON first, because 0 -> 0 is not a release.
    private static void mineLogWaiverReleasedOnSupersedeScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;
        Harness.pin(ctx);
        Harness.clearBots(ctx);
        SceneBot bot = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        if (waiverLeakedIn(ctx, bot, "mineLogWaiverReleasedOnSupersede")) return;

        stageOneLog(ctx, level, cx, cz, floorY);
        Harness.set("allowBreak", true);
        Harness.set("allowPlace", false);

        bot.player().getInventory().items.set(0, new ItemStack(Items.WOODEN_AXE));
        bot.player().getInventory().selected = 0;
        bot.mine(List.of("#minecraft:logs"), 1, 16);

        // Tick only until the waiver is observed on — superseding after the log is already down
        // would test nothing (the waiver would have lapsed on its own).
        boolean armed = false;
        int armedAt = -1;
        for (int t = 0; t < 400 && bot.busy() && !armed; t++) {
            bot.tick();
            if (waiverOn(bot)) { armed = true; armedAt = t; }
        }
        ctx.record("gate.tick the waiver armed at", armedAt);
        if (!armed) {
            ctx.fail("mineLogWaiverReleasedOnSupersede: the waiver never came on within 400 ticks, "
                    + "so 'it is off after cancellation' would be 0 -> 0 and cannot prove a release (not triggered).");
            return;
        }

        bot.goTo(Targets.block(new BlockPos(cx, floorY + 1, cz)));   // the production door: hand it a new order
        boolean afterSupersede = waiverOn(bot);
        ctx.record("gate.waiver still on after supersede", afterSupersede);
        if (afterSupersede)
            ctx.fail("mineLogWaiverReleasedOnSupersede: the order was replaced but the waiver is still on; a mining order "
                    + "superseded midway would leave the 1.0 pricing on every later route this bot plans.");
    }
}
