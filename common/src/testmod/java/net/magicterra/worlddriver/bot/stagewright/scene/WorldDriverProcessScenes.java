package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.ArrayList;
import java.util.List;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.BridgeProcess;
import net.magicterra.worlddriver.bot.process.BuildProcess;
import net.magicterra.worlddriver.bot.process.CombatProcess;
import net.magicterra.worlddriver.bot.process.EntityLeash;
import net.magicterra.worlddriver.bot.process.FollowProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.process.LookProcess;
import net.magicterra.worlddriver.bot.process.MineProcess;
import net.magicterra.worlddriver.bot.process.RunAwayProcess;
import net.magicterra.worlddriver.bot.process.Schematic;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.stagewright.journey.HoldStill;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.magicterra.worlddriver.bot.stagewright.journey.HoldStill;
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
 * Dogfooded worlddriver scenes — <b>P4c wave 9, the Process-core family (the FINAL wave)</b>: the
 * last 15 legacy {@code AgentGameTestServer} tests — the headless server-side driver / process /
 * avatar arenas (goto-driver / mine / process / flee / mine-process / mine-no-tool / walker-deepslate
 * / forbid-dig-wall / build / look-raycast / follow / combat / look / mine-canopy-radius /
 * bridge-pillar) — migrated verbatim to testkit {@code wd.*} scenes. Their legacy twins, and the three
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
 * {@code ServerWorldDriver.create} → {@link ServerWorldDriver#createIsolated} (#48 per-scene body);
 * legacy NeoForge {@code FakePlayer} → common {@link ServerPlayer}; {@code try/finally} config
 * save/restore → {@link BotConfig#pinnedBaseline()} + {@code ctx.cleanup(pin::close)} (snapshots EVERY
 * mutable field, so {@code fleeActive}/{@code walkerWallDigFallback}/… are restored too); {@code throw
 * new GameTestAssertException} → {@link SceneContext#fail}; {@code helper.succeed()} → return;
 * {@code gtOnlySkips(...)} → deleted. The real {@code IntentProcess}/{@code MineProcess}/
 * {@code RunAwayProcess}/{@code BuildProcess}/{@code FollowProcess}/{@code CombatProcess}/
 * {@code LookProcess}/{@code BridgeProcess} legs and the bespoke driver/gotoGoal legs run over the
 * bounded {@code ServerAvatarManager.register}+{@code tickAll()} loop (finish auto-unregisters).
 *
 * <p><b>⚡ The three re-entrant {@code level.tick()} mines.</b> The legacy
 * {@code serverForbidDigWallArena} / {@code serverFollowArena} / {@code serverCombatArena} each ran a
 * {@code for (int i=0;i<3;i++) level.tick(()->true)} "index the fresh entity" loop — the documented
 * ChunkMap-livelock trigger that ⛔MUST NOT be copied into a scene (persistent dogfood world). Each is
 * translated to the established wave-5 <b>bounded entity-visibility await</b>: build + summon in the
 * body, then {@code ctx.await(() -> !level.getEntitiesOfClass(...).isEmpty()).within(100).then(...)}
 * (loud STEP_TIMEOUT on non-appearance) — the harness ticks the server between polls, and the fresh
 * entity enters the queryable section index (the exact state the legacy 3-tick loop hand-forced).
 * Since 2026-08-05 StageWright's PREP will not start a scene until its arena is entity-ticking, so
 * these awaits resolve on their first poll; they stay as the guard that says so. Everything
 * downstream of the await — {@code ServerAvatarManager.register}, the {@code tickAll()} drive loop,
 * and every assertion — is kept <b>VERBATIM</b>. {@code wd.serverCombat} additionally ticks the
 * {@code Zombie} DIRECTLY ({@code zombie.tick()}, NOT {@code level.tick()}) each drive iteration to
 * clear its hurt-cooldown — the exact legacy actuation, and safe (direct entity tick, no ChunkMap
 * re-entry). A live arena does not make that redundant and never did: the drive loop runs up to 1500
 * avatar ticks inside a single server tick, so the server's own entity tick fires once for the whole
 * fight, and a target ticked once stays invulnerable after the first hit.
 *
 * <p><b>{@code wd.serverCombat} controlled-combat rig.</b> The zombie is {@code setNoAi(true)} +
 * {@code setPersistenceRequired()} + max {@code KNOCKBACK_RESISTANCE}, and it is re-pinned to its
 * cell each drive iteration. It cannot sun-burn to a false fire-kill because StageWright pins the
 * whole run to a frozen midnight — the scene used to do that for itself, and no longer has to.
 *
 * <p><b>{@code wd.serverWalkerDeepslateNoTool}</b> flips {@link ServerPlayerAvatar#faithfulBreak} (a
 * static NOT covered by {@code pinnedBaseline}) — saved/restored via its own {@code ctx.cleanup}.
 *
 * <p><b>{@code buildFloor}</b> (the sole {@code AgentGameTestSupport} static the surviving Process
 * tests still referenced — from {@code serverMineArena}) is reproduced here as a private static (the
 * wave-2/3/8 "each provider self-contains its needed helpers" precedent), so the three legacy classes
 * can be deleted with zero dangling references. ⛔ No Process scene calls {@code level.tick()}.
 *
 * <p><b>Three subject families live in sibling classes.</b> This file grew past the 3000-line source
 * budget, so — the same mechanical seam-split the {@code WalkerTick*} phase classes did for
 * {@code Walker} — {@link WorldDriverPortalScenes} (obsidian cast / portal frame / lighting / nether
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
                Scene.of("wd.serverProcess", 400, WorldDriverProcessScenes::serverProcessScene),
                Scene.of("wd.serverFlee", 400, WorldDriverProcessScenes::serverFleeScene),
                Scene.of("wd.serverMineProcess", 600, WorldDriverProcessScenes::serverMineProcessScene),
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
                // A body that digs the block out from under itself and rides it down. It shipped
                // optional and red as the smallest statement of why the scene above was red, and
                // outlived that explanation — the buried drops turned out to be sealed in rock
                // rather than lying at the bottom of a hole. It has since gone green on both
                // loaders and is required under promote-on-first-green; the descent it measures is
                // the miner's peel-one-block-at-a-time path, so a regression there lands here.
                Scene.of("wd.serverSelfShaftDescends", 1_200, WorldDriverProcessScenes::serverSelfShaftDescends),
                // Pure navigation, no mining: can the walker path to the bottom of a pit? That is
                // the question under wd.serverMineHarvestBuried, and mining it first means a
                // failure could belong to either half. Required, because if this is red the
                // buried-drop story is a Walker story and every fix aimed at MineProcess is aimed
                // at the wrong file.
                Scene.of("wd.serverBreakNeedsReach", 400, WorldDriverProcessScenes::serverBreakNeedsReach),
                Scene.of("wd.serverWalkIntoAPit", 900, WorldDriverProcessScenes::serverWalkIntoAPit),
                // The same pit, with the miner's own permissions. MineProcess sweeps with breaking
                // AND placing on, and a walker allowed to place can treat a hole as terrain to
                // bridge rather than a place to stand — so "the walker can reach a pit" and "the
                // collector can reach a pit" are not the same claim. Measured green on both
                // loaders, so it is now required: the armed walker must keep choosing to descend.
                Scene.of("wd.serverWalkIntoAPitArmed", 900, WorldDriverProcessScenes::serverWalkIntoAPitArmed),
                // Optional because it is GREEN on Fabric and RED on NeoForge — and the NeoForge red
                // is NOT a worlddriver defect. NeoForge patches two vanilla classes to refuse a
                // FakePlayer any progression at all:
                //
                //   PlayerAdvancements.award   → `if (this.player instanceof FakePlayer) return false;`
                //   PlayerList.getPlayerAdvancements → `if (!(arg instanceof FakePlayer)) …setPlayer(arg);`
                //
                // Cite the method, not the line: line numbers move with the decompiler's flags, so
                // the two `instanceof FakePlayer` branches above are the durable part. For a fresh
                // re-derivation, decompile with vineflower 1.10.1 `-dgs=1`:
                //   .gradle/caches/fabric-loom/1.21.1/neoforge/21.1.230/minecraft-merged-mojang-patched.jar
                //     → PlayerAdvancements.java:186 (method at :185), PlayerList.java:821
                // and compare against the vanilla merged jar, which has NEITHER branch —
                // PlayerAdvancements.award:182 and PlayerList:787 are unconditional there.
                // 21.1.230 is what gradle.properties:26 pins; a 21.0.167 tree also sits in that
                // cache and is MC 1.21.0, i.e. NOT what this repo builds. Re-checked 2026-08-21.
                //
                // So on NeoForge this body cannot earn ANY advancement, in ANY scene, ever — no
                // amount of driver-side work moves it. What DOES move it is the body: both patches
                // key on `instanceof FakePlayer`, and JoinedBody extends ServerPlayer without being
                // one. THIS RED THEREFORE DISAPPEARS WITH THE FakePlayer RETIREMENT rather than with
                // a fix, and this scene should be promoted back to required at that point, not
                // before. See docs/fake-player-parity.md §6.5 (甲档) for the full derivation.
                Scene.of("wd.serverAvatarEarnsAdvancement", 300,
                        WorldDriverProcessScenes::serverAvatarEarnsAdvancementScene).withRequired(false),
                Scene.of("wd.serverMineNoTool", 600, WorldDriverProcessScenes::serverMineNoToolScene),
                Scene.of("wd.serverWalkerDeepslateNoTool", 600, WorldDriverProcessScenes::serverWalkerDeepslateNoToolScene),
                Scene.of("wd.serverForbidDigWall", 400, WorldDriverProcessScenes::serverForbidDigWallScene),
                Scene.of("wd.serverBuild", 600, WorldDriverProcessScenes::serverBuildScene),
                Scene.of("wd.serverLookRaycast", 200, WorldDriverProcessScenes::serverLookRaycastScene),
                Scene.of("wd.serverFollow", 400, WorldDriverProcessScenes::serverFollowScene),
                Scene.of("wd.serverCombat", 400, WorldDriverProcessScenes::serverCombatScene),
                Scene.of("wd.serverCombatCollectDrops", 400, WorldDriverProcessScenes::serverCombatCollectDropsScene),
                Scene.of("wd.serverLook", 400, WorldDriverProcessScenes::serverLookScene),
                Scene.of("wd.serverMineCanopyRadius", 600, WorldDriverProcessScenes::serverMineCanopyRadiusScene),
                Scene.of("wd.serverBridgePillarStart", 400, WorldDriverProcessScenes::serverBridgePillarStartScene),
                // Required from the day it was written, because it passed the day it was written.
                // It exists because the journey reported the opposite — exit.fromY=54 ->
                // exit.toY=55, one block in 1200 ticks — and an isolated arena said the body leaves
                // a four-deep shaft in 46 ticks. That gap is now known to be about the journey's
                // budget and terrain, not about a missing capability, which is exactly the sort of
                // thing a stalled rung four scenes downstream cannot tell you.
                Scene.of("wd.serverPillarsOutOfAPit", 1_500, WorldDriverProcessScenes::serverPillarsOutOfAPit),
                // The same question at the depth a real mining rung digs to, and driven by the
                // process the journey now scripts rather than by the walker. It shipped optional —
                // the four-deep arena above proves a capability, not this one, and the journey had
                // measured a NINE-deep shaft moving the body one block in 6 000 ticks — and was
                // promoted in the run that first saw it green (135 ticks), which is what keeps a
                // frontier from sliding back.
                Scene.of("wd.serverTowersOutOfADeepShaft", 1_500,
                        WorldDriverProcessScenes::serverTowersOutOfADeepShaft)));
        WorldDriverPortalScenes.register(out);
        WorldDriverMobFightScenes.register(out);
        WorldDriverSmeltDeliveryScenes.register(out);
        return List.copyOf(out);
    }

    /** Inlined from {@code AgentGameTestSupport#buildFloor}: 11×11 stone floor at {@code floorY},
     *  cleared air +1..+18 above — a clean test slab. */
    static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 1; dy <= 18; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
    }

    /**
     * Can a server-driven body earn an advancement at all?
     *
     * <p>{@code story/upgrade_tools} is "hold a stone pickaxe" — an {@code inventory_changed}
     * trigger, the simplest one there is. It fires from the {@code ContainerListener} vanilla
     * attaches in {@code ServerPlayer.initInventoryMenu}, which a body that was never placed
     * through {@code PlayerList} does not have, and it is only delivered when something calls
     * {@code containerMenu.broadcastChanges()} — which {@code ServerPlayer.doTick} does and this
     * avatar's {@code Player}-shaped tick did not. Both are now done, and the result is
     * <b>green on Fabric and red on NeoForge</b>: same common constructor, same common tick, and
     * NeoForge's own {@code FakePlayer} still earns nothing. That divergence is what this scene
     * exists to keep visible; it is not yet explained, and the loader it fails on is in the
     * failure message so nobody has to re-derive which.
     *
     * <p>Advancements are not on the critical path to a dead dragon, which is why this is a
     * sensor rather than a blocker — but a driver that reports a modpack's progression to an
     * agent cannot silently award nothing.
     */
    private static void serverAvatarEarnsAdvancementScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = 0; dy <= 2; dy++)
                    for (int dz = -1; dz <= 1; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        // A process, because a registered driver with nothing to do is not ticked — and the
        // inventory broadcast that delivers the trigger rides the body's tick. LookProcess is the
        // cheapest one there is (pure yaw/pitch, no world interaction), so what this scene
        // measures stays "can this body earn anything" and not "can it mine".
        driver.runProcess(new LookProcess(new BlockPos(cx + 2, floorY + 1, cz), 0f, 0f));
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 40 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        if (!earned(fp, "minecraft:story/upgrade_tools"))
            ctx.fail("the body holds a stone pickaxe and did not earn story/upgrade_tools — "
                    + "nothing is listening to its inventory, so a server-driven agent's whole "
                    + "progression is invisible [diag ticked=" + driver.finished()
                    + " held=" + fp.getMainHandItem().getItem() + "]");
    }

    /** Whether this body has completed a named advancement. False also when the id is unknown,
     *  which cannot happen for a vanilla story id in a vanilla runtime. */
    private static boolean earned(ServerPlayer fp, String id) {
        var holder = fp.server.getAdvancements().get(
                net.minecraft.resources.ResourceLocation.parse(id));
        return holder != null && fp.getAdvancements().getOrStartProgress(holder).isDone();
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
    // wd.serverDriver — Phase 2 headless driver: FakePlayer walks + steps up a +1 ledge
    // to a Block goal, driven through ServerAvatarManager (the live server-tick entry).
    // ==================================================================================

    private static void serverDriverScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.gotoGoal(new Goal.Block(goal));
        ServerAvatarManager.register(driver);
        if (ServerAvatarManager.activeCount() != 1) { ctx.fail("driver failed to register"); return; }

        // Drive via the SAME entry point the server tick uses.
        for (int t = 0; t < 200 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 9 + 0.5)) < 1.5
                && fp.getY() >= floorY + 2 - 0.4;
        WorldDriverCommon.LOG.info("[wd.serverDriver] step={} pos=({},{},{}) finished={} active={} reached={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAvatarManager.activeCount(), reached);
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("server driver did not finish + auto-unregister: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount());
        if (!reached)
            ctx.fail("server-driven agent did not reach the goal: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ") step=" + driver.lastStep());
    }

    // ==================================================================================
    // wd.serverMine — Phase 2 task: navigate + MINE a target block (no client).
    // ==================================================================================

    private static void serverMineScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx - 3 + 0.5, floorY + 1, cz + 0.5);
        driver.mine(target);
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 200 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        boolean mined = level.getBlockState(target).isAir();
        ServerPlayer fp = driver.fakePlayer();
        WorldDriverCommon.LOG.info("[wd.serverMine] step={} pos=({},{},{}) finished={} active={} mined={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAvatarManager.activeCount(), mined);
        if (!mined)
            ctx.fail("server agent did not mine the target (still " + level.getBlockState(target) + ")");
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("mine task did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount());
    }

    // ==================================================================================
    // wd.serverProcess — Phase 2b: the SERVER runs a REAL IntentProcess over a FakePlayer.
    // ==================================================================================

    private static void serverProcessScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.runProcess(new IntentProcess(new Intent(new Goal.Block(goal))));   // the REAL client process, server-side
        ServerAvatarManager.register(driver);

        for (int t = 0; t < 200 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        boolean reached = Math.abs(fp.getX() - (cx + 0.5)) < 1.5
                && Math.abs(fp.getZ() - (cz + 9 + 0.5)) < 1.5;
        WorldDriverCommon.LOG.info("[wd.serverProcess] step={} pos=({},{},{}) finished={} active={} reached={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAvatarManager.activeCount(), reached);
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("server IntentProcess did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount());
        if (!reached)
            ctx.fail("server-run IntentProcess did not reach the goal: pos=("
                    + fp.getX() + "," + fp.getY() + "," + fp.getZ() + ")");
    }

    // ==================================================================================
    // wd.serverFlee — Phase 2b: the SERVER runs a REAL RunAwayProcess (stateful) over a FakePlayer.
    // ==================================================================================

    private static void serverFleeScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20, R = 10;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.runProcess(new RunAwayProcess(from, minDist));
        ServerAvatarManager.register(driver);

        for (int t = 0; t < 200 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        double dx = fp.getX() - (cx + 0.5), dz = fp.getZ() - (cz + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        boolean fled = dist >= minDist - 0.5;
        WorldDriverCommon.LOG.info("[wd.serverFlee] step={} pos=({},{},{}) dist={} finished={} active={} fled={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(), dist,
                driver.finished(), ServerAvatarManager.activeCount(), fled);
        if (!fled)
            ctx.fail("server RunAwayProcess did not reach min flee distance: dist="
                    + dist + " (need " + minDist + ")");
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("flee process did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount());
    }

    // ==================================================================================
    // wd.serverMineProcess — Phase 2b headline: the SERVER runs the REAL MineProcess over a
    // FakePlayer; mines a whole 3-stone quota holding a pickaxe.
    // ==================================================================================

    private static void serverMineProcessScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        // Hold a pickaxe: stone requiresCorrectToolForDrops, and the tool gate (gap#2)
        // now keeps a toolless bot from futilely "mining" harvest-requiring blocks for
        // zero drops — so the mine happy-path must actually carry the harvesting tool.
        driver.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        driver.fakePlayer().getInventory().selected = 0;
        driver.runProcess(new MineProcess(List.of("minecraft:stone"), 3, 8));
        ServerAvatarManager.register(driver);

        for (int t = 0; t < 400 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        int remaining = 0;
        for (BlockPos t : targets) if (!level.getBlockState(t).isAir()) remaining++;
        ServerPlayer fp = driver.fakePlayer();
        WorldDriverCommon.LOG.info("[wd.serverMineProcess] step={} pos=({},{},{}) finished={} active={} remaining={}/3",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAvatarManager.activeCount(), remaining);
        if (remaining != 0)
            ctx.fail("server MineProcess left " + remaining + "/3 target stone unmined");
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("server MineProcess did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount());
    }

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
     * <p><b>Real ticks, not a {@code tickAll()} spin.</b> The whole family drives its process
     * inside one server tick. That cannot work here: a mined block drops an {@link ItemEntity}
     * with a 10-tick pickup delay, and an entity only counts that delay down when the LEVEL
     * ticks it. Spun in-body, the drop is never pickable and the scene would fail for a
     * reason that has nothing to do with the code under test. So the process is registered
     * and left to the platform's per-server-tick {@code tickAll()}, and the scene awaits its
     * self-unregister — the {@code JourneyRig.drive} shape.
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
     * <p>The most ordinary thing a player does underground, and this body cannot do it. The journey's
     * iron rung found it the expensive way: the ore is four blocks under the surface, so the route
     * scripted a shaft — break the block below, fall in, repeat — and the trace showed twelve legs
     * with the body at a constant {@code y=63}, shuffling sideways one cell at a time.
     *
     * <p>Two separate facts have to hold and the failure message names which one broke.
     * <b>The block must break</b>: {@code ServerWorldDriver.mine} aims a {@code Goal.Near(target,2)}
     * and the target is one block away, so navigation is trivially satisfied and the actuator runs.
     * <b>The body must then descend into the hole</b>: it has no free-running physics — the platform
     * only steps an avatar that a registered driver is ticking, and the single-block mine ends on the
     * tick the block turns to air, which buys one {@code avatar.step()} and about a tenth of a block
     * of gravity. So the descent is driven explicitly, with the emptied cell as the goal.
     *
     * <p>Goal.Block on that cell rather than a height: {@code Goal.YLevel} was tried on the journey
     * and it descends to the wrong place — "be at y=60" is satisfied anywhere, and the walker took
     * the cheapest way down it could find, landing six blocks off the ore column. A shaft is a
     * column, and only a goal naming the column keeps the body over its own hole.
     */
    private static void serverSelfShaftDescends(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;    // placing would let it pillar back up and muddy the reading
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = 20;
        BotConfig.pathfinderMaxMs = 2000;

        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        fp.getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
        fp.getInventory().selected = 0;

        BlockPos under = new BlockPos(cx, floorY, cz);
        BlockPos deeper = under.below();
        final int startY = fp.blockPosition().getY();

        // TWO courses, not one, and the reason is the bug this scene missed the first time.
        // ServerWorldDriver.tick() branches on `process` before `mineTarget`, and mine()/gotoGoal()
        // did not clear it — so on a driver that had ever run a BotProcess, every later mine was
        // silently ignored and the stale process ran instead, finishing against its already-met
        // goal. A one-course version arms mine() on a fresh driver, which is the single ordering
        // where that cannot bite; the journey found it only after four rungs of processes had run
        // on the same body. So the second course is mined AFTER a process has owned this driver.
        driver.mine(under);
        ServerAvatarManager.register(driver);

        ctx.await(() -> ServerAvatarManager.activeCount() == 0).within(400).then(() -> {
            boolean brokeFirst = level.getBlockState(under).isAir();
            driver.runProcess(new IntentProcess(new Intent(new Goal.Block(under))));
            ServerAvatarManager.register(driver);

            ctx.await(() -> ServerAvatarManager.activeCount() == 0).within(600).then(() -> {
                int midY = fp.blockPosition().getY();
                driver.mine(deeper);
                ServerAvatarManager.register(driver);

                ctx.await(() -> ServerAvatarManager.activeCount() == 0).within(400).then(() -> {
                    BlockPos at = fp.blockPosition();
                    String diag = " [diag under=" + level.getBlockState(under).getBlock()
                            + " deeper=" + level.getBlockState(deeper).getBlock()
                            + " startY=" + startY + " midY=" + midY + " endY=" + at.getY()
                            + " body@" + (at.getX() - cx) + "," + (at.getY() - floorY)
                            + "," + (at.getZ() - cz)
                            + " onGround=" + fp.onGround()
                            + " lastStep=" + driver.lastStep() + "]";
                    ctx.expect(brokeFirst)
                            .as("the block under the body broke — if this is false the descent was"
                                    + " never even attempted and the rest of the message is noise" + diag)
                            .isEqualTo(true);
                    ctx.expect(midY < startY)
                            .as("the body followed its own shaft down; a player who digs the block"
                                    + " beneath them falls in, and every drop from that dig is down"
                                    + " there with it" + diag)
                            .isEqualTo(true);
                    ctx.expect(level.getBlockState(deeper).isAir())
                            .as("a mine armed AFTER a process actually mines — the driver ticks its"
                                    + " process branch first, so a stale one turns every later mine"
                                    + " into a no-op that still reports success" + diag)
                            .isEqualTo(true);
                });
            });
        });
    }

    /**
     * Walk to the bottom of a two-deep, one-wide pit six blocks away.
     *
     * <p>The navigation half of {@code wd.serverMineHarvestBuried}, on its own. That scene mines
     * three ores, two of them buried, and then fails to collect the drops that fell into the holes;
     * its verdict reads {@code collect timed out after 240 ticks} with {@code collectPath=0/0} —
     * the collect walker searched for the whole budget and never produced a path, and never said
     * FAILED either. "Cannot path into a pit" and "can path but the collect logic asks for the
     * wrong cell" produce exactly that same line, and they live in different files.
     *
     * <p>So the pit is dug by the harness rather than by the bot, there is no item and no mining,
     * and the only verb under test is {@code IntentProcess} against {@code Goal.Block} on the pit
     * floor. Whatever this scene says is unambiguous.
     */
    /**
     * The avatar must not mine what a player could not have touched.
     *
     * <p>Three targets in one slab, at once, so a fix that trades one for another cannot pass:
     * a block <b>sealed</b> in stone (all six faces solid), a block <b>far</b> away but exposed,
     * and a block <b>adjacent</b> to the body. Only the third may break.
     *
     * <p>This is the contract behind {@code wd.serverMineHarvestBuried}. Without it
     * {@code Level#destroyBlock} breaks anything the avatar aims at, at any distance, through any
     * amount of rock — and the drop from a sealed block lands in a 1×1×1 pocket
     * ({@code openSides=0}) that no pathfinder can ever reach. Two rounds of work went into the
     * walker and the collect sweep before anyone measured the pocket.
     */
    private static void serverBreakNeedsReach(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = false;

        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        fp.getInventory().items.set(0, new ItemStack(Items.DIAMOND_PICKAXE));  // never the excuse
        fp.getInventory().selected = 0;

        // Aim and hold at each, generously — a slow-mine needs ticks, and giving the two that must
        // NOT break more ticks than the one that must is the point.
        ServerPlayerAvatar avatar = driver.avatar();
        for (BlockPos at : List.of(sealed, far, adjacent))
            for (int t = 0; t < 60; t++) {
                avatar.selectTool(at);
                avatar.aimAtBlock(at);
                avatar.breakHold(true);
            }

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
                .as("and the block right next to the body must still break, or the gate has simply"
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
     * allowed. The rung above it inherited a body in a pit and spent its whole budget walking
     * nowhere.
     *
     * <p>Isolated deliberately. A stalled food rung four scenes downstream is a terrible place to
     * learn this, and "the walker never planned a pillar" and "it planned one and the avatar could
     * not execute the jump-and-place" are different bugs — {@code PillarUp} exists as a pathfinder
     * move, so this scene's failure message carries the path length, which separates them.
     */
    private static void serverPillarsOutOfAPit(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;      // pillaring is the point; forbidding it would be circular
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = 20;
        BotConfig.pathfinderMaxMs = 2000;

        final int pitFloorY = floorY - 4;
        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, pitFloorY, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 32));
        fp.getInventory().selected = 0;

        final int startY = fp.blockPosition().getY();
        driver.runProcess(new IntentProcess(new Intent(new Goal.YLevel(floorY + 1))));
        ServerAvatarManager.register(driver);

        ctx.await(() -> ServerAvatarManager.activeCount() == 0).within(1_200).then(() -> {
            BlockPos at = fp.blockPosition();
            String diag = " [diag startY=" + startY + " endY=" + at.getY()
                    + " surfaceY=" + (floorY + 1)
                    + " climbed=" + (at.getY() - startY)
                    + " body@" + (at.getX() - cx) + "," + (at.getY() - floorY) + "," + (at.getZ() - cz)
                    + " cobble=" + fp.getInventory().countItem(Items.COBBLESTONE)
                    + " lastStep=" + driver.lastStep()
                    + " path=" + driver.botState().mc_goto.pathStep
                    + "/" + driver.botState().mc_goto.pathLen
                    + " endReason=" + driver.botState().mc_goto.endReason + "]";
            ctx.expect(at.getY() >= floorY)
                    .as("the body pillared out of the shaft it would have dug — a miner that cannot"
                            + " leave its own hole strands every rung after it" + diag)
                    .isEqualTo(true);
            // NOT "it spent blocks pillaring". That was the first version of this assertion and it
            // was wrong in the instructive way: the body got out in 46 ticks having spent nothing,
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
     * ticks. The journey's stone rung sinks nine courses and then mines sideways, so the body ends
     * under its own roof, and there the walker measured one block of climb in 6 000 ticks. Two
     * things differ at once — depth, and the overhang — so this scene reproduces both and drives
     * the exit the way the journey now scripts it: clear {@code feet+2}, then
     * {@link TowerProcess} one course, repeat.
     *
     * <p>The overhang is the interesting half. {@code TowerProcess} does not break; it jumps and
     * fills the cell it left. Under a roof that is a jump into rock, reported as "stuck (no Y gain)"
     * — a message that reads like a missing capability and is really a missing step in the plan.
     */
    private static void serverTowersOutOfADeepShaft(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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
        // roof over the body's head. Standing in the alcove, the column home is a step away and
        // the way up is through stone.
        for (int dy = -9; dy <= 0; dy++)
            level.setBlockAndUpdate(new BlockPos(cx, floorY + dy, cz), Blocks.AIR.defaultBlockState());
        for (int dy = -9; dy <= -8; dy++)
            level.setBlockAndUpdate(new BlockPos(cx + 1, floorY + dy, cz), Blocks.AIR.defaultBlockState());

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = 20;
        BotConfig.pathfinderMaxMs = 2000;

        final int shaftFloorY = floorY - 9;
        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 1.5, shaftFloorY, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        // Slot 0, because that is the constraint TowerProcess actually has: outside creative it
        // only scans the hotbar, and a run whose cobblestone had settled into the main inventory
        // would report "no placeable block in hotbar" while carrying thirty of them.
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 32));
        fp.getInventory().selected = 0;
        // A stone pickaxe, because the ceiling is stone and the exit is only scriptable if the
        // body can clear it in a sensible number of ticks.
        fp.getInventory().items.set(1, new ItemStack(Items.STONE_PICKAXE));

        final int startY = fp.blockPosition().getY();
        towerOneCourse(ctx, driver, fp, floorY + 1, 40, () -> {
            BlockPos at = fp.blockPosition();
            String diag = " [diag startY=" + startY + " endY=" + at.getY()
                    + " surfaceY=" + (floorY + 1)
                    + " climbed=" + (at.getY() - startY)
                    + " body@" + (at.getX() - cx) + "," + (at.getY() - floorY) + "," + (at.getZ() - cz)
                    + " cobble=" + fp.getInventory().countItem(Items.COBBLESTONE)
                    + " builder=" + driver.botState().builder.lastError + "]";
            ctx.expect(at.getY() > floorY)
                    .as("the body climbed nine courses out of a roofed shaft — the exit a mining"
                            + " rung has to make before the next rung can go anywhere" + diag)
                    .isEqualTo(true);
        });
    }

    /** One course of the scripted exit: clear {@code feet+2} if it is solid, else tower one block.
     *  Recursive rather than looped for the same reason the journey's version is — a course is two
     *  waits, and the body has to move between them. */
    private static void towerOneCourse(SceneContext ctx, ServerWorldDriver driver, ServerPlayer fp,
                                       int surfaceY, int budget, Runnable then) {
        BlockPos at = fp.blockPosition();
        if (at.getY() >= surfaceY || budget <= 0) { then.run(); return; }
        BlockPos ceiling = at.above(2);
        if (fp.level().getBlockState(ceiling).blocksMotion()) {
            ServerAvatarManager.register(driver.mine(ceiling));
            ctx.await(driver::finished).within(400)
                    .then(() -> towerOneCourse(ctx, driver, fp, surfaceY, budget - 1, then));
            return;
        }
        // Land before jumping, exactly as the journey's version does. TowerProcess waits for
        // onGround in READY and counts stuck ticks from zero, so a body still settling out of the
        // mine that preceded it burns its whole patience falling and reports "out of blocks?" while
        // holding thirty-two cobblestone. Same HoldStill the journey uses — an arena that models
        // the routine with a different settle is not modelling the routine.
        if (!fp.onGround()) {
            ServerAvatarManager.register(driver.runProcess(new HoldStill(40)));
            ctx.await(driver::finished).within(60)
                    .then(() -> towerOneCourse(ctx, driver, fp, surfaceY, budget - 1, then));
            return;
        }
        ServerAvatarManager.register(driver.runProcess(new TowerProcess(at.getY() + 1, "minecraft:cobblestone")));
        ctx.await(driver::finished).within(200).then(() -> {
            ServerAvatarManager.unregister(driver);
            if (fp.blockPosition().getY() <= at.getY()) { then.run(); return; }
            towerOneCourse(ctx, driver, fp, surfaceY, budget - 1, then);
        });
    }

    private static void serverWalkIntoAPit(SceneContext ctx) { walkIntoAPit(ctx, false); }

    /** {@link #serverWalkIntoAPit} with the permissions {@code MineProcess} actually sweeps under. */
    private static void serverWalkIntoAPitArmed(SceneContext ctx) { walkIntoAPit(ctx, true); }

    private static void walkIntoAPit(SceneContext ctx, boolean armed) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        // Unarmed: nothing to dig or pave with, so the only answer the walker can give is about
        // the pit. Armed: exactly what MineProcess.COLLECT runs under, dirt in hand included.
        BotConfig.allowBreak = armed;
        BotConfig.allowPlace = armed;
        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = 20;
        BotConfig.pathfinderMaxMs = 2000;

        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
        if (armed) {
            fp.getInventory().items.set(0, new ItemStack(Items.DIRT, 16));   // holdPlaceable's pick
            fp.getInventory().items.set(1, new ItemStack(Items.STONE_PICKAXE));
            fp.getInventory().selected = 0;
        }

        driver.runProcess(new IntentProcess(new Intent(new Goal.Block(pitBottom))));
        ServerAvatarManager.register(driver);

        ctx.await(() -> ServerAvatarManager.activeCount() == 0).within(700).then(() -> {
            BlockPos at = fp.blockPosition();
            double away = Math.sqrt(at.distSqr(pitBottom));
            String diag = " [diag pit=" + (pitBottom.getX() - cx) + "," + (pitBottom.getY() - floorY)
                    + "," + (pitBottom.getZ() - cz)
                    + " body@" + (at.getX() - cx) + "," + (at.getY() - floorY) + "," + (at.getZ() - cz)
                    + " dist=" + String.format("%.1f", away)
                    + " lastStep=" + driver.lastStep()
                    + " pathStep=" + driver.botState().mc_goto.pathStep
                    + "/" + driver.botState().mc_goto.pathLen
                    + " endReason=" + driver.botState().mc_goto.endReason
                    + " lastError=" + driver.botState().mc_goto.lastError + "]";
            ctx.expect(at.getY() <= pitBottom.getY())
                    .as("the body got down into the pit — a drop at the bottom of a two-deep hole"
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;      // the hazard: placing is what makes the hand hold dirt
        BotConfig.walkerDebug = false;
        // Real ticks — a pathfinder slice that blocks the server thread would stall the suite,
        // so these stay the generous-but-bounded values the journey rig uses, not MAX_VALUE/2.
        BotConfig.pathfinderSliceMs = 20;
        BotConfig.pathfinderMaxMs = 2000;

        ServerWorldDriver driver = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
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
        driver.runProcess(new MineProcess(List.of("minecraft:iron_ore"), 4, 12));
        ServerAvatarManager.register(driver);

        ctx.await(() -> ServerAvatarManager.activeCount() == 0).within(1_200).then(() -> {
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
                    // walker stopped short" both end as an uncollected item four blocks away, and
                    // no amount of pathfinder work fixes the first one.
                         .append(",above=").append(level.getBlockState(cell.above()).getBlock())
                         .append(",openSides=").append(openSides(level, cell)).append(")");
                }
            String diag = " [diag oresLeft=" + standingLabel(level, List.of(ore, oreB, oreC))
                    + " banked=" + banked + " drops=" + drops
                    + " held=" + fp.getMainHandItem().getItem()
                    + " finished=" + driver.finished()
                    + " lastError=" + driver.botState().mine.lastError
                    + " endReason=" + driver.botState().mine.endReason
                    + " body@" + (fp.blockPosition().getX() - cx) + "," + (fp.blockPosition().getY() - floorY)
                    + "," + (fp.blockPosition().getZ() - cz)
                    + " lastStep=" + driver.lastStep()
                    + " collectPath=" + driver.botState().mine.pathStep + "/" + driver.botState().mine.pathLen
                    + where + "]";
            int standing = 0;
            for (BlockPos at : List.of(ore, oreB, oreC)) if (level.getBlockState(at).is(Blocks.IRON_ORE)) standing++;
            if (standing > 0)
                ctx.fail("mineHarvest rig: " + standing + " of 3 ores were never broken" + diag);
            if (!driver.finished())
                ctx.fail("mineHarvest: the mine did not finish+unregister" + diag);
            if (banked < 3)
                ctx.fail((buried ? "mineHarvestBuried" : "mineHarvest")
                        + ": 3 ores broke but only " + banked + " raw iron reached the inventory — "
                        + (drops > 0 ? "the drop exists and was not collected" : "no drop was ever created")
                        + diag);
            // A finished command must say how it ended. `active:false` with no error reads as
            // success, and this verb can end clean having banked nothing — which is what made the
            // two collection bugs above take three playthrough runs to tell apart from "the ore
            // was never reached". Same contract BunkerProcess and IntentProcess already keep.
            if (driver.botState().mine.endReason == null)
                ctx.fail("mineHarvest: the mine finished without stating a terminal verdict" + diag);
            // Whether the body EARNED anything for this is a separate question with a separate
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        // NO pickaxe — empty-handed, matching the campaign soft-lock (broken pickaxe, no craft path).
        driver.runProcess(new MineProcess(List.of("minecraft:stone"), 3, 8));
        ServerAvatarManager.register(driver);

        for (int t = 0; t < 400 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        int remaining = 0;
        for (BlockPos t : targets) if (!level.getBlockState(t).isAir()) remaining++;
        String err = driver.botState().mine.lastError;
        WorldDriverCommon.LOG.info("[wd.serverMineNoTool] finished={} active={} remaining={}/3 lastError={}",
                driver.finished(), ServerAvatarManager.activeCount(), remaining, err);
        // Must have mined NONE — a harvest-requiring block with no tool yields nothing.
        if (remaining != 3)
            ctx.fail("toolless MineProcess broke " + (3 - remaining)
                    + "/3 stone for zero drops (should mine none): remaining=" + remaining);
        // Must have aborted cleanly (finished + unregistered), not spun or ground the quota.
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("toolless MineProcess did not abort+unregister: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount());
        // Signal must name the missing tool so the planner can act (craft/relocate).
        if (err == null || !err.contains("pickaxe"))
            ctx.fail("expected a tool-block signal naming a pickaxe, got: " + err);
    }

    // ==================================================================================
    // wd.serverWalkerDeepslateNoTool — gap#2 verdict/regression guard: the Walker EXECUTOR clears a
    // bare-hand deepslate plug it lacks the tool for (does NOT soft-lock). faithfulBreak ON.
    // ==================================================================================

    private static void serverWalkerDeepslateNoToolScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        boolean ofb = ServerPlayerAvatar.faithfulBreak;
        ctx.cleanup(() -> ServerPlayerAvatar.faithfulBreak = ofb);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        // NO pickaxe — empty-handed, matching the campaign soft-lock at y-14 deepslate.
        driver.gotoGoal(new Goal.Block(goal));       // real Walker executor, no MineProcess
        ServerAvatarManager.register(driver);

        final int BUDGET = 3000;                     // >> walkerTotalTickBudget(1200); an infinite grind stays active past this
        int endTick = -1;
        for (int t = 0; t < BUDGET; t++) {
            if (ServerAvatarManager.activeCount() == 0) { endTick = t; break; }
            ServerAvatarManager.tickAll();
        }
        if (endTick < 0 && ServerAvatarManager.activeCount() == 0) endTick = BUDGET;

        ServerPlayer fp = driver.fakePlayer();
        int plugRemaining = (level.getBlockState(plugFoot).isAir() ? 0 : 1)
                          + (level.getBlockState(plugHead).isAir() ? 0 : 1);
        boolean reached = Math.abs(fp.getZ() - (cz + 4 + 0.5)) < 1.5 && fp.getY() >= floorY + 1 - 0.4;
        WorldDriverCommon.LOG.info("[wd.serverWalkerDeepslateNoTool] endTick={} step={} pos=({},{},{}) finished={} active={} reached={} plugRemaining={}/2",
                endTick, driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAvatarManager.activeCount(), reached, plugRemaining);
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
    // wd.serverForbidDigWall ⚡ — per-goto forbidDig (NoBreak) against a solid wall = clean planner
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
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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
            ServerWorldDriver driverA = SceneBody.bare(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            driverA.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));  // NOT tool-gated: only forbidDig can stop the dig
            driverA.fakePlayer().getInventory().selected = 0;
            Intent intentA = new Intent(new Goal.Near(standCell, 1), List.of(), CapabilityProfile.ALL, List.of(new NoBreak()), leash);
            driverA.runProcess(new IntentProcess(intentA));
            ServerAvatarManager.register(driverA);
            int endA = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (ServerAvatarManager.activeCount() == 0) { endA = t; break; }
                ServerAvatarManager.tickAll();
            }
            ServerPlayer fpA = driverA.fakePlayer();
            int plugA = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean gotPastA = fpA.getZ() > cz + plugDz + 1.0;   // past the plug = tunnelled through
            WorldDriverCommon.LOG.info("[wd.serverForbidDigWall] A(forbidDig) endTick={} step={} pos=({},{},{}) gotPast={} plugRemaining={}/2",
                    endA, driverA.lastStep(), fpA.getX(), fpA.getY(), fpA.getZ(), gotPastA, plugA);
            ServerAvatarManager.clear();
            fpA.discard();                                  // so the pinned FakePlayer can't linger into Phase B
            if (plugA != 2 || gotPastA) {
                ctx.fail("forbidDig LEAK: executor dig fallback punched the wall despite NoBreak — "
                        + "plugRemaining=" + plugA + "/2 (want 2) gotPast=" + gotPastA + " (want false) step=" + driverA.lastStep()
                        + " z=" + fpA.getZ());
                return;
            }

            // ---- Phase B: precision — SAME rig, NO forbidDig — must dig through and REACH the stand. ----
            setPlug.run();
            ServerWorldDriver driverB = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            driverB.fakePlayer().getInventory().items.set(0, new ItemStack(Items.STONE_PICKAXE));
            driverB.fakePlayer().getInventory().selected = 0;
            Intent intentB = new Intent(new Goal.Near(standCell, 1), List.of(), CapabilityProfile.ALL, List.of(), leash);   // no NoBreak = digging allowed
            driverB.runProcess(new IntentProcess(intentB));
            ServerAvatarManager.register(driverB);
            int endB = -1;
            for (int t = 0; t < BUDGET; t++) {
                if (ServerAvatarManager.activeCount() == 0) { endB = t; break; }
                ServerAvatarManager.tickAll();
            }
            ServerPlayer fpB = driverB.fakePlayer();
            int plugB = (level.getBlockState(plugFoot).isAir() ? 0 : 1) + (level.getBlockState(plugHead).isAir() ? 0 : 1);
            boolean reachedB = Math.abs(fpB.getX() - (cx + 0.5)) < 1.5 && Math.abs(fpB.getZ() - (cz + standDz + 0.5)) < 2.0;
            WorldDriverCommon.LOG.info("[wd.serverForbidDigWall] B(precision) endTick={} step={} pos=({},{},{}) reached={} plugRemaining={}/2",
                    endB, driverB.lastStep(), fpB.getX(), fpB.getY(), fpB.getZ(), reachedB, plugB);
            if (!reachedB || plugB != 0)
                ctx.fail("precision guard: without forbidDig the bot must dig through and reach the stand — "
                        + "reached=" + reachedB + " (want true) plugRemaining=" + plugB + "/2 (want 0) step=" + driverB.lastStep()
                        + " z=" + fpB.getZ());
        });
    }

    // ==================================================================================
    // wd.serverBuild — Phase 2b: the SERVER runs the REAL BuildProcess over a FakePlayer.
    // ==================================================================================

    private static void serverBuildScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.fakePlayer().getInventory().items.set(0, new ItemStack(Blocks.COBBLESTONE, 64));
        driver.fakePlayer().getInventory().selected = 0;
        driver.runProcess(new BuildProcess(origin, schem));
        ServerAvatarManager.register(driver);

        for (int t = 0; t < 400 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        boolean p1 = level.getBlockState(t1).is(Blocks.COBBLESTONE);
        boolean p2 = level.getBlockState(t2).is(Blocks.COBBLESTONE);
        ServerPlayer fp = driver.fakePlayer();
        WorldDriverCommon.LOG.info("[wd.serverBuild] step={} pos=({},{},{}) finished={} active={} placed1={} placed2={}",
                driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAvatarManager.activeCount(), p1, p2);
        if (!p1 || !p2)
            ctx.fail("server BuildProcess failed to place both cobble: t1="
                    + level.getBlockState(t1) + " t2=" + level.getBlockState(t2));
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("build process did not finish+unregister: finished="
                    + driver.finished() + " active=" + ServerAvatarManager.activeCount());
    }

    // ==================================================================================
    // wd.serverLookRaycast — Avatar.lookingAtBlock() eye→view clip raycast primitive.
    // ==================================================================================

    private static void serverLookRaycastScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayerAvatar av = driver.avatar();
        av.aimAtBlock(target);                       // sets yaw/pitch toward the cell
        BlockPos look = av.lookingAtBlock();         // eye→view clip raycast
        WorldDriverCommon.LOG.info("[wd.serverLookRaycast] aim={} look={} match={}",
                target.toShortString(), look == null ? "null" : look.toShortString(),
                target.equals(look));
        if (!target.equals(look))
            ctx.fail("server lookingAtBlock did not resolve the aimed cell: aim="
                    + target.toShortString() + " look=" + (look == null ? "null" : look.toShortString()));
    }

    // ==================================================================================
    // wd.serverFollow ⚡ — the SERVER runs the REAL FollowProcess (entity-sensing) over a FakePlayer.
    // The legacy `for(3) level.tick()` entity-index loop → bounded entity-visibility await.
    // ==================================================================================

    private static void serverFollowScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // Ground-anchored slot (a fresh entity must promote into getEntities); floorY=220 → origin.y+20.
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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
            ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.runProcess(new FollowProcess("minecraft:armor_stand", null, 2, 0));
            ServerAvatarManager.register(driver);

            for (int t = 0; t < 200 && ServerAvatarManager.activeCount() > 0; t++)
                ServerAvatarManager.tickAll();

            ServerPlayer fp = driver.fakePlayer();
            double dx = fp.getX() - (cx + 8 + 0.5), dz = fp.getZ() - (cz + 0.5);
            double dist = Math.sqrt(dx * dx + dz * dz);
            boolean closed = dist <= 3.0;   // follow radius 2 + slack
            WorldDriverCommon.LOG.info("[wd.serverFollow] pos=({},{},{}) standDist={} closed={}",
                    fp.getX(), fp.getY(), fp.getZ(), dist, closed);
            if (!closed)
                ctx.fail("server FollowProcess did not close on the armor stand: dist=" + dist);
        });
    }

    // ==================================================================================
    // wd.serverCombat ⚡ — the SERVER runs the REAL CombatProcess over a FakePlayer; kills a NoAI
    // night-pinned zombie. The legacy `for(3) level.tick()` entity-index loop → bounded await;
    // the drive loop ticks the zombie DIRECTLY (not level.tick) for its hurt-cooldown, VERBATIM.
    // ==================================================================================

    private static void serverCombatScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        // Ground-anchored slot (a fresh entity must promote into getEntities); floorY=220 → origin.y+20.
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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
        // No night pin here any more: StageWright freezes every scene at midnight and says so in the
        // run's results header, so the zombie cannot sun-burn to a false fire-kill.

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        // ENTITY-VISIBILITY WAIT (wave-5) replacing the legacy `for(3) level.tick(()->true)` index
        // loop: poll (bounded) until the fresh zombie is queryable, then run the fight VERBATIM.
        ctx.await(() -> !level.getEntitiesOfClass(Zombie.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.IRON_SWORD));
            // KILL by TYPE (scans Level.getEntities) rather than by id — the by-id lookup is not
            // populated without a full level.tick(); type-mode exercises the same melee loop.
            driver.runProcess(new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:zombie"));
            ServerAvatarManager.register(driver);

            for (int t = 0; t < 1500 && ServerAvatarManager.activeCount() > 0; t++) {
                ServerAvatarManager.tickAll();
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
            WorldDriverCommon.LOG.info("[wd.serverCombat] step={} pos=({},{},{}) zHp={} dead={} finished={} active={}",
                    driver.lastStep(), fp.getX(), fp.getY(), fp.getZ(),
                    zombie.getHealth(), dead, driver.finished(), ServerAvatarManager.activeCount());
            if (!dead)
                ctx.fail("server CombatProcess did not kill the zombie: hp=" + zombie.getHealth());
            if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
                ctx.fail("server CombatProcess did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAvatarManager.activeCount());
        });
    }

    // ==================================================================================
    // wd.serverCombatCollectDrops — after the kill, the combat loop must SWEEP the drops
    // near the kill spot before terminating (BotConfig.combatCollectDrops; live 01:32
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

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        BotConfig.walkerDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ctx.await(() -> !level.getEntitiesOfClass(Zombie.class, entityBox(cx, floorY, cz)).isEmpty()
                        && !level.getEntitiesOfClass(ItemEntity.class, entityBox(cx, floorY, cz)).isEmpty())
                .within(100).then(() -> {
            ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
            driver.fakePlayer().getInventory().clearContent();
            driver.fakePlayer().getInventory().add(new ItemStack(Items.IRON_SWORD));
            driver.runProcess(new CombatProcess(CombatProcess.Mode.KILL, null, "minecraft:zombie"));
            ServerAvatarManager.register(driver);

            // Diagnostics live in the FAIL MESSAGE, not LOG.info — late-suite async
            // log lines are dropped wholesale on shutdown (task#95 lesson).
            int killedAt = -1, endedAt = -1;
            double killX = Double.NaN;
            for (int t = 0; t < 1500 && ServerAvatarManager.activeCount() > 0; t++) {
                ServerAvatarManager.tickAll();
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
            if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
                ctx.fail("collectDrops: combat did not finish+unregister: finished="
                        + driver.finished() + " active=" + ServerAvatarManager.activeCount() + diag);
            if (!pickedUp && distToDrop > 2.0)
                ctx.fail("collectDrops: drop NOT swept — bot ended " + distToDrop
                        + " blocks from the drop (pre-fix behaviour: terminate at the kill spot)"
                        + diag);
        });
    }

    // ==================================================================================
    // wd.serverLook — the SERVER runs the REAL LookProcess (pure yaw/pitch) over a FakePlayer.
    // ==================================================================================

    private static void serverLookScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver.runProcess(new LookProcess(track, 0f, 0f));
        ServerAvatarManager.register(driver);
        for (int t = 0; t < 300 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();

        ServerPlayer fp = driver.fakePlayer();
        var eye = fp.getEyePosition();
        double dx = track.getX() + 0.5 - eye.x, dy = track.getY() + 0.5 - eye.y, dz = track.getZ() + 0.5 - eye.z;
        float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float tp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float yawErr = Math.abs(((ty - fp.getYRot()) % 360f + 540f) % 360f - 180f);
        float pitchErr = Math.abs(tp - fp.getXRot());
        WorldDriverCommon.LOG.info("[wd.serverLook] yaw={} (tgt {}) pitch={} (tgt {}) finished={} active={}",
                fp.getYRot(), ty, fp.getXRot(), tp, driver.finished(), ServerAvatarManager.activeCount());
        if (!driver.finished() || ServerAvatarManager.activeCount() != 0)
            ctx.fail("server LookProcess did not align+finish: active=" + ServerAvatarManager.activeCount());
        if (yawErr > 2f || pitchErr > 2f)
            ctx.fail("server LookProcess off target: yawErr=" + yawErr + " pitchErr=" + pitchErr);
    }

    // ==================================================================================
    // wd.serverMineCanopyRadius — gap#67-⑤ scan budget: MineProcess.scanForTarget must find a
    // dy=+4 canopy log at radius 16 AND radius 32 (the wide radius used to truncate the top dy band).
    // ==================================================================================

    private static void serverMineCanopyRadiusScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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
        ServerWorldDriver driver16 = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver16.fakePlayer().getInventory().items.set(0, new ItemStack(Items.WOODEN_AXE));
        driver16.fakePlayer().getInventory().selected = 0;
        driver16.runProcess(new MineProcess(List.of("#minecraft:logs"), 1, 16));
        ServerAvatarManager.register(driver16);
        for (int t = 0; t < 800 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();
        boolean minedAt16 = !level.getBlockState(logPos).is(Blocks.OAK_LOG);
        String err16 = driver16.botState().mine.lastError;
        WorldDriverCommon.LOG.info("[wd.serverMineCanopyRadius] radius=16 minedAt16={} finished={} lastError={}",
                minedAt16, driver16.finished(), err16);
        if (!minedAt16) { ctx.fail("gap#67(regression): radius=16 must still find the dy=+4 canopy log: lastError=" + err16); return; }
        ServerAvatarManager.clear();

        // Phase 2 — radius=32, the exact live repro: respawn the log and re-run with the wider radius.
        level.setBlockAndUpdate(logPos, Blocks.OAK_LOG.defaultBlockState());
        ServerWorldDriver driver32 = SceneBody.mint(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        driver32.fakePlayer().getInventory().items.set(0, new ItemStack(Items.WOODEN_AXE));
        driver32.fakePlayer().getInventory().selected = 0;
        driver32.runProcess(new MineProcess(List.of("#minecraft:logs"), 1, 32));
        ServerAvatarManager.register(driver32);
        for (int t = 0; t < 800 && ServerAvatarManager.activeCount() > 0; t++)
            ServerAvatarManager.tickAll();
        boolean minedAt32 = !level.getBlockState(logPos).is(Blocks.OAK_LOG);
        String err32 = driver32.botState().mine.lastError;
        WorldDriverCommon.LOG.info("[wd.serverMineCanopyRadius] radius=32 minedAt32={} finished={} lastError={}",
                minedAt32, driver32.finished(), err32);
        if (!minedAt32)
            ctx.fail("gap#67(⑤): radius=32 must find the dy=+4 canopy log (was: scan budget truncated the top dy layers, not the farthest cells): lastError=" + err32);
    }

    // ==================================================================================
    // wd.serverBridgePillarStart — gap#75-a (live death #24): BridgeProcess from a 1×1 pillar top;
    // leg A = sneak-overhang start (yaw 90° off), leg B = centered baseline.
    // ==================================================================================

    private static void serverBridgePillarStartScene(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
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

        ServerWorldDriver driver = SceneBody.mint(ctx, level, cx + 0.5 + xOff, feetY, cz + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        fp.setYRot(startYaw); fp.yHeadRot = startYaw; fp.yBodyRot = startYaw;
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        driver.runProcess(new BridgeProcess(Direction.EAST, distance, "minecraft:cobblestone"));
        ServerAvatarManager.register(driver);

        double minY = fp.getY();
        int t = 0;
        for (; t < 400 && ServerAvatarManager.activeCount() > 0; t++) {
            ServerAvatarManager.tickAll();
            minY = Math.min(minY, fp.getY());
            if (t < 40 || t % 20 == 0)
                WorldDriverCommon.LOG.info("[bridgePillar {} t={}] pos=({},{},{}) onGround={} dm={} step={} lastErr={}",
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
        WorldDriverCommon.LOG.info("[bridgePillar {}] END t={} pos=({},{},{}) minY={} laid={} finished={} lastErr={}",
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
        ServerAvatarManager.clear();
    }

    /** How many of an item the body holds — the only witness a right-click leaves behind. */
    static int countItem(net.minecraft.server.level.ServerPlayer fp,
                         net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) if (st.is(item)) n += st.getCount();
        return n;
    }
}
