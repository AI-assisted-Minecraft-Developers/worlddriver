package net.magicterra.agent.bot.testkit.scene;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.agent.AgentDriverCommon;
import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.pathfinder.CapabilityProfile;
import net.magicterra.agent.bot.pathfinder.constraints.NoBreak;
import net.magicterra.agent.bot.process.BridgeProcess;
import net.magicterra.agent.bot.process.BuildProcess;
import net.magicterra.agent.bot.process.CombatProcess;
import net.magicterra.agent.bot.process.EntityLeash;
import net.magicterra.agent.bot.process.FollowProcess;
import net.magicterra.agent.bot.process.Intent;
import net.magicterra.agent.bot.process.IntentProcess;
import net.magicterra.agent.bot.process.LookProcess;
import net.magicterra.agent.bot.process.MineProcess;
import net.magicterra.agent.bot.process.RunAwayProcess;
import net.magicterra.agent.bot.process.Schematic;
import net.magicterra.agent.bot.sim.ServerAgentDriver;
import net.magicterra.agent.bot.sim.ServerAgentManager;
import net.magicterra.agent.bot.sim.ServerPlayerAvatar;
import net.magicterra.testkit.scene.Scene;
import net.magicterra.testkit.scene.SceneContext;
import net.magicterra.testkit.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Dogfooded agent-driver scenes — <b>P4c wave 9, the Process-core family (the FINAL wave)</b>: the
 * last 15 legacy {@code AgentGameTestServer} tests — the headless server-side driver / process /
 * avatar arenas (goto-driver / mine / process / flee / mine-process / mine-no-tool / walker-deepslate
 * / forbid-dig-wall / build / look-raycast / follow / combat / look / mine-canopy-radius /
 * bridge-pillar) — migrated verbatim to testkit {@code ad.*} scenes. Their legacy twins, and the three
 * legacy class files ({@code AgentGameTestServer} / {@code AgentGameTestRegistrar} /
 * {@code AgentGameTestSupport}), are DELETED in the same commit: after this wave the legacy
 * {@code @GameTest} suite is EMPTY (count chain legacy 15 → 0, closing the P4c campaign).
 *
 * <p><b>Canonical wave-6/7/8 substitutions apply.</b> {@code helper.getLevel()} →
 * {@link SceneContext#level()}; absolute {@code cx/cz} → origin X/Z; absolute {@code floorY=220} →
 * {@code origin.y + 20}, ground-anchored {@code floorY=anchor.getY()} → {@code origin.y}; the
 * hardcoded far anchors ({@code 380/460/…/2600/1300}, picked only to dodge the shared persistent
 * world's coordinate collisions) → {@link SceneContext#origin()} AUTO slots (the harness allocates a
 * fresh non-colliding slot per scene, so the collision-avoidance the legacy did by hand is now
 * structural); ground-anchored {@code helper.absolutePos(ZERO)} → {@code ctx.origin()};
 * {@code ServerAgentDriver.create} → {@link ServerAgentDriver#createIsolated} (#48 per-scene body);
 * legacy NeoForge {@code FakePlayer} → common {@link ServerPlayer}; {@code try/finally} config
 * save/restore → {@link BotConfig#pinnedBaseline()} + {@code ctx.cleanup(pin::close)} (snapshots EVERY
 * mutable field, so {@code fleeActive}/{@code walkerWallDigFallback}/… are restored too); {@code throw
 * new GameTestAssertException} → {@link SceneContext#fail}; {@code helper.succeed()} → return;
 * {@code gtOnlySkips(...)} → deleted. The real {@code IntentProcess}/{@code MineProcess}/
 * {@code RunAwayProcess}/{@code BuildProcess}/{@code FollowProcess}/{@code CombatProcess}/
 * {@code LookProcess}/{@code BridgeProcess} legs and the bespoke driver/gotoGoal legs run over the
 * bounded {@code ServerAgentManager.register}+{@code tickAll()} loop (finish auto-unregisters).
 *
 * <p><b>⚡ The three re-entrant {@code level.tick()} mines.</b> The legacy
 * {@code serverForbidDigWallArena} / {@code serverFollowArena} / {@code serverCombatArena} each ran a
 * {@code for (int i=0;i<3;i++) level.tick(()->true)} "index the fresh entity" loop — the documented
 * ChunkMap-livelock trigger that ⛔MUST NOT be copied into a scene (persistent dogfood world). Each is
 * translated to the established wave-5 <b>bounded entity-visibility await</b>: build + summon in the
 * body, then {@code ctx.await(() -> !level.getEntitiesOfClass(...).isEmpty()).within(100).then(...)}
 * (loud STEP_TIMEOUT on non-appearance) — the harness ticks the server between polls, promoting the
 * force-loaded arena chunk to ENTITY_TICKING so the fresh entity enters the queryable section index
 * (the exact state the legacy 3-tick loop hand-forced). Everything downstream of the await —
 * {@code ServerAgentManager.register}, the {@code tickAll()} drive loop, and every assertion — is kept
 * <b>VERBATIM</b>. {@code ad.serverCombat} additionally ticks the {@code Zombie} DIRECTLY
 * ({@code zombie.tick()}, NOT {@code level.tick()}) each drive iteration to clear its hurt-cooldown —
 * the exact legacy actuation, and safe (direct entity tick, no ChunkMap re-entry).
 *
 * <p><b>{@code ad.serverCombat} controlled-combat rig — daytime auto-burn protection copied verbatim.</b>
 * The zombie is {@code setNoAi(true)} + {@code setPersistenceRequired()} + max {@code KNOCKBACK_RESISTANCE}
 * and the level is pinned to {@code setDayTime(18000)} (night) so it cannot sun-burn to a false
 * fire-kill; it is re-pinned to its cell each drive iteration. This protection is the legacy rig's
 * verbatim — nothing tuned. (No {@code level.tick()} elapses for the zombie in daylight; the direct
 * {@code zombie.tick()} at night cannot ignite.)
 *
 * <p><b>{@code ad.serverWalkerDeepslateNoTool}</b> flips {@link ServerPlayerAvatar#faithfulBreak} (a
 * static NOT covered by {@code pinnedBaseline}) — saved/restored via its own {@code ctx.cleanup}.
 *
 * <p><b>{@code buildFloor}</b> (the sole {@code AgentGameTestSupport} static the surviving Process
 * tests still referenced — from {@code serverMineArena}) is reproduced here as a private static (the
 * wave-2/3/8 "each provider self-contains its needed helpers" precedent), so the three legacy classes
 * can be deleted with zero dangling references. ⛔ No Process scene calls {@code level.tick()}.
 */
public final class AgentDriverProcessScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("ad.serverDriver", 400, AgentDriverProcessScenes::serverDriverScene),
                Scene.of("ad.serverMine", 400, AgentDriverProcessScenes::serverMineScene),
                Scene.of("ad.serverProcess", 400, AgentDriverProcessScenes::serverProcessScene),
                Scene.of("ad.serverFlee", 400, AgentDriverProcessScenes::serverFleeScene),
                Scene.of("ad.serverMineProcess", 600, AgentDriverProcessScenes::serverMineProcessScene),
                Scene.of("ad.serverMineNoTool", 600, AgentDriverProcessScenes::serverMineNoToolScene),
                Scene.of("ad.serverWalkerDeepslateNoTool", 600, AgentDriverProcessScenes::serverWalkerDeepslateNoToolScene),
                Scene.of("ad.serverForbidDigWall", 400, AgentDriverProcessScenes::serverForbidDigWallScene),
                Scene.of("ad.serverBuild", 600, AgentDriverProcessScenes::serverBuildScene),
                Scene.of("ad.serverLookRaycast", 200, AgentDriverProcessScenes::serverLookRaycastScene),
                Scene.of("ad.serverFollow", 400, AgentDriverProcessScenes::serverFollowScene),
                Scene.of("ad.serverCombat", 400, AgentDriverProcessScenes::serverCombatScene),
                Scene.of("ad.serverCombatCollectDrops", 400, AgentDriverProcessScenes::serverCombatCollectDropsScene),
                Scene.of("ad.serverLook", 400, AgentDriverProcessScenes::serverLookScene),
                Scene.of("ad.serverMineCanopyRadius", 600, AgentDriverProcessScenes::serverMineCanopyRadiusScene),
                Scene.of("ad.serverBridgePillarStart", 400, AgentDriverProcessScenes::serverBridgePillarStartScene));
    }

    /** Inlined from {@code AgentGameTestSupport#buildFloor}: 11×11 stone floor at {@code floorY},
     *  cleared air +1..+18 above — a clean test slab. */
    private static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 1; dy <= 18; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
    }

    /** A ±24-block cube around the arena centre — the entity-visibility poll box. Used only to detect
     *  when a freshly-added entity has entered the level's queryable section index (wave-5 precedent). */
    private static AABB entityBox(int cx, int floorY, int cz) {
        return new AABB(cx - 24, floorY - 24, cz - 24, cx + 24, floorY + 24, cz + 24);
    }

    // ==================================================================================
    // ad.serverDriver — Phase 2 headless driver: FakePlayer walks + steps up a +1 ledge
    // to a Block goal, driven through ServerAgentManager (the live server-tick entry).
    // ==================================================================================

    private static void serverDriverScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.gotoGoal(new Goal.Block(goal));
        ServerAgentManager.register(driver);
        if (ServerAgentManager.activeCount() != 1) { ctx.fail("driver failed to register"); return; }

        // Drive via the SAME entry point the server tick uses.
        for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 9 + 0.5)) < 1.5
                && fp.getY() >= floorY + 2 - 0.4;
        AgentDriverCommon.LOG.info("[ad.serverDriver] step={} pos=({},{},{}) finished={} active={} reached={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAgentManager.activeCount(), reached);
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("server driver did not finish + auto-unregister: finished="
                    + driver.finished() + " active=" + ServerAgentManager.activeCount());
        if (!reached)
            ctx.fail("server-driven agent did not reach the goal: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + driver.lastStep());
    }

    // ==================================================================================
    // ad.serverMine — Phase 2 task: navigate + MINE a target block (no client).
    // ==================================================================================

    private static void serverMineScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
        ctx.cleanup(() -> {
            for (int dx = -5; dx <= 5; dx++)
                for (int dy = 0; dy <= 18; dy++)
                    for (int dz = -5; dz <= 5; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        buildFloor(level, cx, cz, floorY);
        BlockPos target = new BlockPos(cx + 3, floorY + 1, cz);   // a block on the floor, away from the bot
        level.setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx - 3 + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.mine(target);
        ServerAgentManager.register(driver);
        for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        boolean mined = level.getBlockState(target).isAir();
        ServerPlayer fp = driver.fakePlayer();
        AgentDriverCommon.LOG.info("[ad.serverMine] step={} pos=({},{},{}) finished={} active={} mined={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAgentManager.activeCount(), mined);
        if (!mined)
            ctx.fail("server agent did not mine the target (still " + level.getBlockState(target) + ")");
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("mine task did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAgentManager.activeCount());
    }

    // ==================================================================================
    // ad.serverProcess — Phase 2b: the SERVER runs a REAL IntentProcess over a FakePlayer.
    // ==================================================================================

    private static void serverProcessScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -1; dz <= 10; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 10; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        BlockPos goal = new BlockPos(cx, floorY + 1, cz + 9);

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.runProcess(new IntentProcess(new Intent(new Goal.Block(goal))));   // the REAL client process, server-side
        ServerAgentManager.register(driver);

        for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 9 + 0.5)) < 1.5;
        AgentDriverCommon.LOG.info("[ad.serverProcess] step={} pos=({},{},{}) finished={} active={} reached={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAgentManager.activeCount(), reached);
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("server IntentProcess did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAgentManager.activeCount());
        if (!reached)
            ctx.fail("server-run IntentProcess did not reach the goal: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
    }

    // ==================================================================================
    // ad.serverFlee — Phase 2b: the SERVER runs a REAL RunAwayProcess (stateful) over a FakePlayer.
    // ==================================================================================

    private static void serverFleeScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20, R = 10;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.runProcess(new RunAwayProcess(from, minDist));
        ServerAgentManager.register(driver);

        for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        double dx = fp.getX() - (cx + 0.5), dz = fp.getZ() - (cz + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean fled = dist >= minDist - 0.5;
        AgentDriverCommon.LOG.info("[ad.serverFlee] step={} pos=({},{},{}) dist={} finished={} active={} fled={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(), dist,
                driver.finished(), ServerAgentManager.activeCount(), fled);
        if (!fled)
            ctx.fail("server RunAwayProcess did not reach min flee distance: dist="
                    + dist + " (need " + minDist + ")");
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("flee process did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAgentManager.activeCount());
    }

    // ==================================================================================
    // ad.serverMineProcess — Phase 2b headline: the SERVER runs the REAL MineProcess over a
    // FakePlayer; mines a whole 3-stone quota holding a pickaxe.
    // ==================================================================================

    private static void serverMineProcessScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 9; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // DIRT floor (NOT a target) so the scan only finds the placed stone.
        for (int dx = -1; dx <= 9; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.DIRT.defaultBlockState());
        BlockPos[] targets = {
                new BlockPos(cx + 2, floorY + 1, cz),
                new BlockPos(cx + 4, floorY + 1, cz),
                new BlockPos(cx + 6, floorY + 1, cz),
        };
        for (BlockPos t : targets) level.setBlockAndUpdate(t, Blocks.STONE.defaultBlockState());

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        // Hold a pickaxe: stone requiresCorrectToolForDrops, and the tool gate (gap#2)
        // now keeps a toolless bot from futilely "mining" harvest-requiring blocks for
        // zero drops — so the mine happy-path must actually carry the harvesting tool.
        driver.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        driver.fakePlayer().getInventory().selected = 0;
        driver.runProcess(new MineProcess(List.of("minecraft:stone"), 3, 8));
        ServerAgentManager.register(driver);

        for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        int remaining = 0;
        for (BlockPos t : targets) if (!level.getBlockState(t).isAir()) remaining++;
        ServerPlayer fp = driver.fakePlayer();
        AgentDriverCommon.LOG.info("[ad.serverMineProcess] step={} pos=({},{},{}) finished={} active={} remaining={}/3",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAgentManager.activeCount(), remaining);
        if (remaining != 0)
            ctx.fail("server MineProcess left " + remaining + "/3 target stone unmined");
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("server MineProcess did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAgentManager.activeCount());
    }

    // ==================================================================================
    // ad.serverMineNoTool — gap#2 tool-capability gate: toolless bot must NOT grind
    // harvest-requiring blocks; must abort cleanly with a signal naming the missing tool.
    // ==================================================================================

    private static void serverMineNoToolScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        // NO pickaxe — empty-handed, matching the campaign soft-lock (broken pickaxe, no craft path).
        driver.runProcess(new MineProcess(List.of("minecraft:stone"), 3, 8));
        ServerAgentManager.register(driver);

        for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        int remaining = 0;
        for (BlockPos t : targets) if (!level.getBlockState(t).isAir()) remaining++;
        String err = driver.botState().mine.lastError;
        AgentDriverCommon.LOG.info("[ad.serverMineNoTool] finished={} active={} remaining={}/3 lastError={}",
                driver.finished(), ServerAgentManager.activeCount(), remaining, err);
        // Must have mined NONE — a harvest-requiring block with no tool yields nothing.
        if (remaining != 3)
            ctx.fail("toolless MineProcess broke " + (3 - remaining)
                    + "/3 stone for zero drops (should mine none): remaining=" + remaining);
        // Must have aborted cleanly (finished + unregistered), not spun or ground the quota.
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("toolless MineProcess did not abort+unregister: finished="
                    + driver.finished() + " active=" + ServerAgentManager.activeCount());
        // Signal must name the missing tool so the planner can act (craft/relocate).
        if (err == null || !err.contains("pickaxe"))
            ctx.fail("expected a tool-block signal naming a pickaxe, got: " + err);
    }

    // ==================================================================================
    // ad.serverWalkerDeepslateNoTool — gap#2 verdict/regression guard: the Walker EXECUTOR clears a
    // bare-hand deepslate plug it lacks the tool for (does NOT soft-lock). faithfulBreak ON.
    // ==================================================================================

    private static void serverWalkerDeepslateNoToolScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        boolean ofb = ServerPlayerAvatar.faithfulBreak;
        ctx.cleanup(() -> ServerPlayerAvatar.faithfulBreak = ofb);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        ServerPlayerAvatar.faithfulBreak = true;         // REAL destroy-progress: bare-hand deepslate ~650t (else instant destroyBlock masks the timing)

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        // NO pickaxe — empty-handed, matching the campaign soft-lock at y-14 deepslate.
        driver.gotoGoal(new Goal.Block(goal));       // real Walker executor, no MineProcess
        ServerAgentManager.register(driver);

        final int BUDGET = 3000;                     // >> walkerTotalTickBudget(1200); an infinite grind stays active past this
        int endTick = -1;
        for (int t = 0; t < BUDGET; t++) {
            if (ServerAgentManager.activeCount() == 0) { endTick = t; break; }
            ServerAgentManager.tickAll();
        }
        if (endTick < 0 && ServerAgentManager.activeCount() == 0) endTick = BUDGET;

        ServerPlayer fp = driver.fakePlayer();
        int plugRemaining = (level.getBlockState(plugFoot).isAir() ? 0 : 1)
                          + (level.getBlockState(plugHead).isAir() ? 0 : 1);
        boolean reached = Math.abs(fp.getZ() - (cz + 4 + 0.5)) < 1.5 && fp.getY() >= floorY + 1 - 0.4;
        AgentDriverCommon.LOG.info("[ad.serverWalkerDeepslateNoTool] endTick={} step={} pos=({},{},{}) finished={} active={} reached={} plugRemaining={}/2",
                endTick, driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAgentManager.activeCount(), reached, plugRemaining);
        // Real guard: the bot must BREAK THROUGH the bare-hand deepslate plug and REACH
        // the goal. A reset-stall regression (dig never completes) would leave the plug
        // solid and the bot short of the goal — reached/plugRemaining catch it, where a
        // bare active==0 check would not (it stays green even on a FAIL-at-budget).
        if (!reached || plugRemaining != 0)
            ctx.fail("Walker did not clear bare-hand deepslate to the goal: reached="
                    + reached + " plugRemaining=" + plugRemaining + "/2 step=" + driver.lastStep()
                    + " endTick=" + endTick + " z=" + fp.getZ());
    }

    // ==================================================================================
    // ad.serverForbidDigWall ⚡ — per-goto forbidDig (NoBreak) against a solid wall = clean planner
    // give-up (Phase A), and precision guard without forbidDig digs through + reaches (Phase B).
    // The legacy `for(3) level.tick()` entity-index loop → bounded entity-visibility await.
    // ==================================================================================

    private static void serverForbidDigWallScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // Ground-anchor to THIS scene's slot (the leash's EntityFind.nearest scan needs the armor
        // stand in Level.getEntities); the legacy used helper.absolutePos(ZERO) for the same reason.
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();
        final int plugDz = 6;                              // plug sits 6 CLEAR cells from start: best-effort partial is multi-node (>1) so the Walker WALKS it to the wall face, where the carrot presses the plug → wallDig can engage. A 1-cell approach ARRIVES at start before the executor ever runs.
        final int standDz = 9;                             // stand sits 3 cells BEYOND the plug
        final double leashRadius = 16.0;                   // stand ~9 from start → inside → PULL forward, not hold back

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.walkerWallDigFallback = true;          // match the live default that produced day6
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;
        final int BUDGET = 1600;                          // > walkerTotalTickBudget(1200); wallDig fires within ~40t of engaging
        buildShell.run();
        var stand = new ArmorStand(level, cx + 0.5, floorY + 1, cz + standDz + 0.5);   // BEYOND the plug
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        ctx.cleanup(() -> stand.discard());
        EntityLeash leash = new EntityLeash("minecraft:armor_stand", leashRadius, 0, true);

        // ENTITY-VISIBILITY WAIT (wave-5) replacing the legacy `for(3) level.tick(()->true)` index
        // loop: poll (bounded) until the fresh armor stand enters the queryable section index, then
        // run Phase A + Phase B VERBATIM. No manual level.tick() — the harness ticks the server.
        ctx.await(() -> !level.getEntitiesOfClass(ArmorStand.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            // ---- Phase A: forbidDig (NoBreak) — the plug MUST survive, bot must NOT get past it. ----
            setPlug.run();
            ServerAgentDriver driverA = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
            driverA.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));  // NOT tool-gated: only forbidDig can stop the dig
            driverA.fakePlayer().getInventory().selected = 0;
            Intent intentA = new Intent(new Goal.Near(standCell, 1), List.of(), CapabilityProfile.ALL, List.of(new NoBreak()), leash);
            driverA.runProcess(new IntentProcess(intentA));
            ServerAgentManager.register(driverA);
            int endA = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (ServerAgentManager.activeCount() == 0) { endA = t; break; }
                ServerAgentManager.tickAll();
            }
            ServerPlayer fpA = driverA.fakePlayer();
            int plugA = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean gotPastA = fpA.getZ() > cz + plugDz + 1.0;   // past the plug = tunnelled through
            AgentDriverCommon.LOG.info("[ad.serverForbidDigWall] A(forbidDig) endTick={} step={} pos=({},{},{}) gotPast={} plugRemaining={}/2",
                    endA, driverA.lastStep(), fpA.getX(), fpA.getY(), fpA.getZ(), gotPastA, plugA);
            ServerAgentManager.clear();
            fpA.discard();                                  // so the pinned FakePlayer can't linger into Phase B
            if (plugA != 2 || gotPastA) {
                ctx.fail("forbidDig LEAK: executor dig fallback punched the wall despite NoBreak — "
                        + "plugRemaining=" + plugA + "/2 (want 2) gotPast=" + gotPastA + " (want false) step=" + driverA.lastStep()
                        + " z=" + fpA.getZ());
                return;
            }

            // ---- Phase B: precision — SAME rig, NO forbidDig — must dig through and REACH the stand. ----
            setPlug.run();
            ServerAgentDriver driverB = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
            ctx.cleanup(() -> driverB.fakePlayer().discard());
            driverB.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
            driverB.fakePlayer().getInventory().selected = 0;
            Intent intentB = new Intent(new Goal.Near(standCell, 1), List.of(), CapabilityProfile.ALL, List.of(), leash);   // no NoBreak = digging allowed
            driverB.runProcess(new IntentProcess(intentB));
            ServerAgentManager.register(driverB);
            int endB = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (ServerAgentManager.activeCount() == 0) { endB = t; break; }
                ServerAgentManager.tickAll();
            }
            ServerPlayer fpB = driverB.fakePlayer();
            int plugB = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean reachedB = Math.abs(fpB.getX() - (cx + 0.5)) < 1.5 && Math.abs(fpB.getZ() - (cz + standDz + 0.5)) < 2.0;
            AgentDriverCommon.LOG.info("[ad.serverForbidDigWall] B(precision) endTick={} step={} pos=({},{},{}) reached={} plugRemaining={}/2",
                    endB, driverB.lastStep(), fpB.getX(), fpB.getY(), fpB.getZ(), reachedB, plugB);
            if (!reachedB || plugB != 0)
                ctx.fail("precision guard: without forbidDig the bot must dig through and reach the stand — "
                        + "reached=" + reachedB + " (want true) plugRemaining=" + plugB + "/2 (want 0) step=" + driverB.lastStep()
                        + " z=" + fpB.getZ());
        });
    }

    // ==================================================================================
    // ad.serverBuild — Phase 2b: the SERVER runs the REAL BuildProcess over a FakePlayer.
    // ==================================================================================

    private static void serverBuildScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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
        List<Schematic.Entry> es = new ArrayList<>();
        es.add(new Schematic.Entry(2, 1, 0, "minecraft:cobblestone"));
        es.add(new Schematic.Entry(3, 1, 0, "minecraft:cobblestone"));
        Schematic schem = new Schematic(4, 2, 1, es);

        BotConfig.allowBreak = false;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.fakePlayer().getInventory().items.set(0, new ItemStack(Blocks.COBBLESTONE, 64));
        driver.fakePlayer().getInventory().selected = 0;
        driver.runProcess(new BuildProcess(origin, schem));
        ServerAgentManager.register(driver);

        for (int t = 0; t < 400 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        boolean p1 = level.getBlockState(t1).is(Blocks.COBBLESTONE);
        boolean p2 = level.getBlockState(t2).is(Blocks.COBBLESTONE);
        ServerPlayer fp = driver.fakePlayer();
        AgentDriverCommon.LOG.info("[ad.serverBuild] step={} pos=({},{},{}) finished={} active={} placed1={} placed2={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAgentManager.activeCount(), p1, p2);
        if (!p1 || !p2)
            ctx.fail("server BuildProcess failed to place both cobble: t1="
                    + level.getBlockState(t1) + " t2=" + level.getBlockState(t2));
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("build process did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAgentManager.activeCount());
    }

    // ==================================================================================
    // ad.serverLookRaycast — Avatar.lookingAtBlock() eye→view clip raycast primitive.
    // ==================================================================================

    private static void serverLookRaycastScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayerAvatar av = driver.avatar();
        av.aimAtBlock(target);                       // sets yaw/pitch toward the cell
        BlockPos look = av.lookingAtBlock();         // eye→view clip raycast
        AgentDriverCommon.LOG.info("[ad.serverLookRaycast] aim={} look={} match={}",
                target.toShortString(), look == null ? "null" : look.toShortString(),
                target.equals(look));
        if (!target.equals(look))
            ctx.fail("server lookingAtBlock did not resolve the aimed cell: aim="
                    + target.toShortString() + " look=" + (look == null ? "null" : look.toShortString()));
    }

    // ==================================================================================
    // ad.serverFollow ⚡ — the SERVER runs the REAL FollowProcess (entity-sensing) over a FakePlayer.
    // The legacy `for(3) level.tick()` entity-index loop → bounded entity-visibility await.
    // ==================================================================================

    private static void serverFollowScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // Ground-anchored slot (a fresh entity must promote into getEntities); floorY=220 → origin.y+20.
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        // ENTITY-VISIBILITY WAIT (wave-5) replacing the legacy `for(3) level.tick(()->true)` index
        // loop: a freshly-added entity isn't in the queryable section index until the level indexes
        // it; poll (bounded) until the stand appears, then run the drive loop + assertion VERBATIM.
        ctx.await(() -> !level.getEntitiesOfClass(ArmorStand.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
            ctx.cleanup(() -> driver.fakePlayer().discard());
            driver.runProcess(new FollowProcess("minecraft:armor_stand", null, 2, 0));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 200 && ServerAgentManager.activeCount() > 0; t++)
                ServerAgentManager.tickAll();

            ServerPlayer fp = driver.fakePlayer();
            double dx = fp.getX() - (cx + 8 + 0.5), dz = fp.getZ() - (cz + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            boolean closed = dist <= 3.0;   // follow radius 2 + slack
            AgentDriverCommon.LOG.info("[ad.serverFollow] pos=({},{},{}) standDist={} closed={}",
                    fp.getX(), fp.getY(), fp.getZ(), dist, closed);
            if (!closed)
                ctx.fail("server FollowProcess did not close on the armor stand: dist=" + dist);
        });
    }

    // ==================================================================================
    // ad.serverCombat ⚡ — the SERVER runs the REAL CombatProcess over a FakePlayer; kills a NoAI
    // night-pinned zombie. The legacy `for(3) level.tick()` entity-index loop → bounded await;
    // the drive loop ticks the zombie DIRECTLY (not level.tick) for its hurt-cooldown, VERBATIM.
    // ==================================================================================

    private static void serverCombatScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // Ground-anchored slot (a fresh entity must promote into getEntities); floorY=220 → origin.y+20.
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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
        zombie.setNoAi(true);                 // no wander/retaliation; stays a fixed target
        zombie.setPersistenceRequired();
        // Immovable target: max knockback resistance so a landed hit can't shove it out of reach.
        var kbr = zombie.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (kbr != null) kbr.setBaseValue(1.0);
        level.addFreshEntity(zombie);
        ctx.cleanup(() -> zombie.discard());
        // Post-review hygiene (P4c wave-9): restore the pre-scene dayTime on exit so this scene
        // leaves the shared persistent world's clock untouched (the scene's own run still pins
        // 18000 below — behaviour unchanged; this is cleanup-hygiene only, future-proofing against
        // a later day-sensitive neighbour).
        long savedDayTime = level.getDayTime();
        ctx.cleanup(() -> level.setDayTime(savedDayTime));
        level.setDayTime(18000);              // night → the zombie won't sun-burn (no false fire-kill)

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        // ENTITY-VISIBILITY WAIT (wave-5) replacing the legacy `for(3) level.tick(()->true)` index
        // loop: poll (bounded) until the fresh zombie is queryable, then run the fight VERBATIM.
        ctx.await(() -> !level.getEntitiesOfClass(Zombie.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
            ctx.cleanup(() -> driver.fakePlayer().discard());
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.IRON_SWORD));
            // KILL by TYPE (scans Level.getEntities) rather than by id — the by-id lookup is not
            // populated without a full level.tick(); type-mode exercises the same melee loop.
            driver.runProcess(new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:zombie"));
            ServerAgentManager.register(driver);

            for (int t = 0; t < 1500 && ServerAgentManager.activeCount() > 0; t++) {
                ServerAgentManager.tickAll();
                // Tick the zombie DIRECTLY each iteration so it processes its hurt-cooldown
                // (invulnerableTime) — a non-ticked target stays permanently invulnerable after
                // the first hit. NOT level.tick() (that is the ChunkMap-livelock trap): a direct
                // entity tick is safe and matches a live server entity-ticking every loaded chunk.
                if (zombie.isAlive()) {
                    zombie.tick();
                    // Re-pin the (NoAI) zombie so nothing — residual knockback, fall — drifts it off.
                    zombie.setPos(cx + 6 + 0.5, floorY + 1, cz + 0.5);
                    zombie.setDeltaMovement(Vec3.ZERO);
                }
            }

            boolean dead = !zombie.isAlive();
            ServerPlayer fp = driver.fakePlayer();
            AgentDriverCommon.LOG.info("[ad.serverCombat] step={} pos=({},{},{}) zHp={} dead={} finished={} active={}",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    zombie.getHealth(), dead, driver.finished(), ServerAgentManager.activeCount());
            if (!dead)
                ctx.fail("server CombatProcess did not kill the zombie: hp=" + zombie.getHealth());
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                ctx.fail("server CombatProcess did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount());
        });
    }

    // ==================================================================================
    // ad.serverCombatCollectDrops — after the kill, the combat loop must SWEEP the drops
    // near the kill spot before terminating (BotConfig.combatCollectDrops; live 01:32
    // 2026-07-21: six hunt kills banked one porkchop — melee kites away from the corpse
    // and the old loop ended wherever it stood).
    // ==================================================================================

    /** Same rig as {@code ad.serverCombat}, plus a deterministic "drop": an ItemEntity
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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
        long savedDayTime = level.getDayTime();
        ctx.cleanup(() -> level.setDayTime(savedDayTime));
        level.setDayTime(18000);              // night → no zombie sun-burn false kill

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ctx.await(() -> !level.getEntitiesOfClass(Zombie.class, entityBox(cx, floorY, cz)).isEmpty()
                        && !level.getEntitiesOfClass(ItemEntity.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
            ctx.cleanup(() -> driver.fakePlayer().discard());
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.IRON_SWORD));
            driver.runProcess(new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:zombie"));
            ServerAgentManager.register(driver);

            // Diagnostics live in the FAIL MESSAGE, not LOG.info — late-suite async
            // log lines are dropped wholesale on shutdown (task#95 lesson).
            int killedAt = -1, endedAt = -1;
            double killX = Double.NaN;
            for (int t = 0; t < 1500 && ServerAgentManager.activeCount() > 0; t++) {
                ServerAgentManager.tickAll();
                if (killedAt < 0 && !zombie.isAlive()) {
                    killedAt = t;
                    killX = driver.fakePlayer().getX();
                }
                endedAt = t;
                if (zombie.isAlive()) {
                    zombie.tick();
                    zombie.setPos(cx + 6 + 0.5, floorY + 1, cz + 0.5);
                    zombie.setDeltaMovement(Vec3.ZERO);
                }
            }

            ServerPlayer fp = driver.fakePlayer();
            boolean dead = !zombie.isAlive();
            boolean pickedUp = !drop.isAlive()
                    || fp.getInventory().countItem(Items.PORKCHOP) > 0;
            double distToDrop = Math.sqrt(fp.distanceToSqr(cx + 9 + 0.5, floorY + 1, cz + 0.5));
            String diag = " [diag killedAt=" + killedAt + " endedAt=" + endedAt
                    + " sweepTicks=" + (killedAt >= 0 ? endedAt - killedAt : -1)
                    + " killX=" + killX + " endPos=(" + fp.getX() + "," + fp.getY() + "," + fp.getZ()
                    + ") dropAlive=" + drop.isAlive()
                    + " lastStep=" + driver.lastStep()
                    + " goal=" + driver.botState().combat.goal + "]";
            if (!dead)
                ctx.fail("collectDrops rig: zombie not killed: hp=" + zombie.getHealth() + diag);
            if (!driver.finished() || ServerAgentManager.activeCount() != 0)
                ctx.fail("collectDrops: combat did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAgentManager.activeCount() + diag);
            if (!pickedUp && distToDrop > 2.0)
                ctx.fail("collectDrops: drop NOT swept — bot ended " + distToDrop
                        + " blocks from the drop (pre-fix behaviour: terminate at the kill spot)"
                        + diag);
        });
    }

    // ==================================================================================
    // ad.serverLook — the SERVER runs the REAL LookProcess (pure yaw/pitch) over a FakePlayer.
    // ==================================================================================

    private static void serverLookScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
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

        BotConfig.walkerDebug = false;

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        driver.runProcess(new LookProcess(track, 0f, 0f));
        ServerAgentManager.register(driver);
        for (int t = 0; t < 300 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        var eye = fp.getEyePosition();
        double dx = track.getX() + 0.5 - eye.x, dy = track.getY() + 0.5 - eye.y, dz = track.getZ() + 0.5 - eye.z;
        float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float yawErr = Math.abs(((ty - fp.getYRot()) % 360f + 540f) % 360f - 180f);
        float pitchErr = Math.abs(tp - fp.getXRot());
        AgentDriverCommon.LOG.info("[ad.serverLook] yaw={} (tgt {}) pitch={} (tgt {}) finished={} active={}",
                fp.getYRot(), ty, fp.getXRot(), tp, driver.finished(), ServerAgentManager.activeCount());
        if (!driver.finished() || ServerAgentManager.activeCount() != 0)
            ctx.fail("server LookProcess did not align+finish: active=" + ServerAgentManager.activeCount());
        if (yawErr > 2f || pitchErr > 2f)
            ctx.fail("server LookProcess off target: yawErr=" + yawErr + " pitchErr=" + pitchErr);
    }

    // ==================================================================================
    // ad.serverMineCanopyRadius — gap#67-⑤ scan budget: MineProcess.scanForTarget must find a
    // dy=+4 canopy log at radius 16 AND radius 32 (the wide radius used to truncate the top dy band).
    // ==================================================================================

    private static void serverMineCanopyRadiusScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 11; dx++)
                for (int dy = 0; dy <= 8; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        BlockPos logPos = new BlockPos(cx + 10, floorY + 5, cz);
        level.setBlockAndUpdate(new BlockPos(cx, floorY, cz), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(cx - 1, floorY, cz), Blocks.DIRT.defaultBlockState());
        // Ascending 1-block-per-step staircase (ordinary step-up) up to platform height, then a flat
        // platform run — a real, walkable path so the ONLY variable under test is the scan.
        for (int s = 1; s <= 4; s++)
            level.setBlockAndUpdate(new BlockPos(cx + s, floorY + s, cz), Blocks.STONE.defaultBlockState());
        for (int x = cx + 5; x <= cx + 9; x++)
            level.setBlockAndUpdate(new BlockPos(x, floorY + 4, cz), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(logPos, Blocks.OAK_LOG.defaultBlockState());

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        // Phase 1 — radius=16 (regression: already worked before the fix, must still work after it).
        ServerAgentDriver driver16 = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver16.fakePlayer().discard());
        driver16.fakePlayer().getInventory().items.set(0, new ItemStack(Items.WOODEN_AXE));
        driver16.fakePlayer().getInventory().selected = 0;
        driver16.runProcess(new MineProcess(List.of("#minecraft:logs"), 1, 16));
        ServerAgentManager.register(driver16);
        for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();
        boolean minedAt16 = !level.getBlockState(logPos).is(Blocks.OAK_LOG);
        String err16 = driver16.botState().mine.lastError;
        AgentDriverCommon.LOG.info("[ad.serverMineCanopyRadius] radius=16 minedAt16={} finished={} lastError={}",
                minedAt16, driver16.finished(), err16);
        if (!minedAt16) { ctx.fail("gap#67(regression): radius=16 must still find the dy=+4 canopy log: lastError=" + err16); return; }
        ServerAgentManager.clear();

        // Phase 2 — radius=32, the exact live repro: respawn the log and re-run with the wider radius.
        level.setBlockAndUpdate(logPos, Blocks.OAK_LOG.defaultBlockState());
        ServerAgentDriver driver32 = ServerAgentDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver32.fakePlayer().discard());
        driver32.fakePlayer().getInventory().items.set(0, new ItemStack(Items.WOODEN_AXE));
        driver32.fakePlayer().getInventory().selected = 0;
        driver32.runProcess(new MineProcess(List.of("#minecraft:logs"), 1, 32));
        ServerAgentManager.register(driver32);
        for (int t = 0; t < 800 && ServerAgentManager.activeCount() > 0; t++)
            ServerAgentManager.tickAll();
        boolean minedAt32 = !level.getBlockState(logPos).is(Blocks.OAK_LOG);
        String err32 = driver32.botState().mine.lastError;
        AgentDriverCommon.LOG.info("[ad.serverMineCanopyRadius] radius=32 minedAt32={} finished={} lastError={}",
                minedAt32, driver32.finished(), err32);
        if (!minedAt32)
            ctx.fail("gap#67(⑤): radius=32 must find the dy=+4 canopy log (was: scan budget truncated the top dy layers, not the farthest cells): lastError=" + err32);
    }

    // ==================================================================================
    // ad.serverBridgePillarStart — gap#75-a (live death #24): BridgeProcess from a 1×1 pillar top;
    // leg A = sneak-overhang start (yaw 90° off), leg B = centered baseline.
    // ==================================================================================

    private static void serverBridgePillarStartScene(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAgentManager.clear();
        ctx.cleanup(ServerAgentManager::clear);
        BotConfig.walkerDebug = false;

        // Leg A: live death-#24 shape — sneak-overhang start (center 1.05 east of the pillar cell
        // origin => floor(center) is already the void column) + yaw 90° off. Leg B: centered start.
        bridgePillarLeg(ctx, 0, 0, 0.55, 90f, "A(overhang)");
        bridgePillarLeg(ctx, 0, 16, 0.0, 270f, "B(centered)");
    }

    /** One bridge-from-pillar-top run: 1×1 cobblestone pillar (6 high) over a catch floor,
     *  FakePlayer on top at {@code (cx+0.5+xOff, cz+0.5)}, REAL {@link BridgeProcess} east ×4.
     *  Asserts: feet never drop below pillar-top−1, 4 bridge blocks laid, process ends {@code done}.
     *  {@code dxRel/dzRel} are offsets from the scene origin (leg A at origin, leg B +16 z). */
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
        // Air-scrub this leg's footprint (floor + pillar + bridge cells) at scene resolution.
        ctx.cleanup(() -> {
            for (int dx = -3; dx <= 9; dx++)
                for (int y = floorY; y <= topY + 5; y++)
                    for (int dz = -3; dz <= 3; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.AIR.defaultBlockState());
        });

        ServerAgentDriver driver = ServerAgentDriver.createIsolated(level, cx + 0.5 + xOff, feetY, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.setYRot(startYaw); fp.yHeadRot = startYaw; fp.yBodyRot = startYaw;
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        driver.runProcess(new BridgeProcess(Direction.EAST, distance, "minecraft:cobblestone"));
        ServerAgentManager.register(driver);

        double minY = fp.getY();
        int t = 0;
        for (; t < 400 && ServerAgentManager.activeCount() > 0; t++) {
            ServerAgentManager.tickAll();
            minY = Math.min(minY, fp.getY());
            if (t < 40 || t % 20 == 0)
                AgentDriverCommon.LOG.info("[bridgePillar {} t={}] pos=({},{},{}) onGround={} dm={} step={} lastErr={}",
                        leg, t, String.format("%.3f", fp.getX()), String.format("%.3f", fp.getY()),
                        String.format("%.3f", fp.getZ()), fp.onGround(), fp.getDeltaMovement(),
                        driver.botState().builder.pathStep, driver.botState().builder.lastError);
        }
        String lastErr = driver.botState().builder.lastError;
        StringBuilder laid = new StringBuilder();
        int laidCount = 0;
        for (int i = 1; i <= distance; i++) {
            boolean solid = level.getBlockState(new BlockPos(cx + i, topY, cz)).blocksMotion();
            if (solid) laidCount++;
            laid.append(solid ? '#' : '.');
        }
        AgentDriverCommon.LOG.info("[bridgePillar {}] END t={} pos=({},{},{}) minY={} laid={} finished={} lastErr={}",
                leg, t, fp.getX(), fp.getY(), fp.getZ(), minY, laid, driver.finished(), lastErr);
        if (minY < feetY - 1.0)
            ctx.fail("gap#75-a leg " + leg + ": bot dropped below pillar-top-1 (minY="
                    + minY + ", start feetY=" + feetY + ") — bridge start fell off the pillar (death #24 shape)");
        if (laidCount < distance)
            ctx.fail("gap#75-a leg " + leg + ": bridge only laid " + laidCount + "/"
                    + distance + " (" + laid + ") — lastErr=" + lastErr);
        if (!driver.finished() || lastErr == null || !lastErr.startsWith("done"))
            ctx.fail("gap#75-a leg " + leg + ": process did not reach the done terminal: "
                    + "finished=" + driver.finished() + " lastErr=" + lastErr);
        ServerAgentManager.clear();
    }
}
