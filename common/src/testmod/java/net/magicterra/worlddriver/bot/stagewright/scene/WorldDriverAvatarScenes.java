package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.process.ElytraProcess;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Dogfooded worlddriver scenes — <b>P4c wave 8, the Avatar family</b>: the 5 legacy
 * {@code AgentGameTestServer} avatar-fidelity tests migrated verbatim to testkit {@code wd.*}
 * scenes, with their legacy twins deleted from the Server suite in the same commit (count chain
 * legacy 20 → 15, closing the wave-8 cut).
 *
 * <p><b>These are the permanent regression guards for medical records #45 (attack cooldown) /
 * #46 (equipment attributes) / #47 ({@code Player.tick} fidelity) / #48 (distinct bodies), plus the
 * central ServerPlayer-avatar break/place capability claim.</b> Their assertion thresholds are
 * LOAD-BEARING GOLDEN VALUES — the iron-sword recharge {@code ceil(20/1.6)=13} ticks, cooked-beef
 * {@code beefLeft==1}, the empty-then-full recharge bar, etc. — and are translated one-for-one with
 * no number moved. The canonical wave-6 substitutions apply: {@code helper.getLevel()} →
 * {@link SceneContext#level()}; absolute {@code cx/cz} → origin X/Z; absolute {@code floorY=220} →
 * {@code origin.y + 20} / {@code floorY=200} → {@code origin.y}; {@code ServerWorldDriver.create} →
 * {@link ServerWorldDriver#createIsolated} and {@code ServerPlayerAvatar.create} →
 * {@link ServerPlayerAvatar#createUnique} (the #48 per-scene isolated body — the shell these tests
 * were promoted to a REQUIRED regression guard on; it changes identity only, not the body physics
 * the golden numbers measure); legacy NeoForge {@code FakePlayer} → common {@link ServerPlayer};
 * {@code try/finally} config save/restore → {@link BotConfig#pinnedBaseline()}; {@code throw} →
 * {@link SceneContext#fail}. Each world-touching scene registers {@code ctx.cleanup} to discard its
 * avatar(s) and air-scrub its footprint (#40). ⛔ No Avatar scene calls {@code level.tick()}.
 *
 * <p>The GameTest-only helpers {@code clearBox} / {@code equipMainHand} / {@code buildFloor} are
 * reproduced here as private statics (the wave-2/3 "each provider self-contains its needed helpers"
 * precedent; {@code buildFloor} stays in the Server suite too — still used by the surviving Process
 * family — while {@code clearBox} / {@code equipMainHand} are orphaned by this wave's deletions and
 * removed from the Server suite).
 */
public final class WorldDriverAvatarScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.serverAgentDistinctBodies", 200, WorldDriverAvatarScenes::serverAgentDistinctBodiesScene),
                Scene.of("wd.serverAvatarTickFidelity", 200, WorldDriverAvatarScenes::serverAvatarTickFidelityScene),
                Scene.of("wd.serverAttackCooldown", 200, WorldDriverAvatarScenes::serverAttackCooldownScene),
                Scene.of("wd.serverCapability", 200, WorldDriverAvatarScenes::serverCapabilityScene),
                Scene.of("wd.serverElytra", 200, WorldDriverAvatarScenes::serverElytraScene));
    }

    /** Inlined from {@code AgentGameTestServer#clearBox}: a {@code (2r+1)×h×(2r+1)} box of air. */
    private static void clearBox(ServerLevel level, int cx, int baseY, int cz, int r, int h) {
        for (int dx = -r; dx <= r; dx++)
            for (int dy = 0; dy < h; dy++)
                for (int dz = -r; dz <= r; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, baseY + dy, cz + dz), Blocks.AIR.defaultBlockState());
    }

    /** Inlined from {@code AgentGameTestServer#buildFloor}: 11×11 stone floor, air +1..+18 above. */
    private static void buildFloor(ServerLevel level, int cx, int cz, int floorY) {
        for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = 1; dy <= 18; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
            }
    }

    /**
     * Inlined from {@code AgentGameTestServer#equipMainHand}: put a weapon in the main hand AND
     * apply its attribute modifiers (attack speed/damage) WITHOUT stepping the avatar, keeping the
     * #45 observation independent of #46's sync fix.
     */
    private static void equipMainHand(ServerPlayer fp, ItemStack weapon) {
        ItemStack prev = fp.getMainHandItem();
        if (!prev.isEmpty()) {
            prev.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
                var inst = fp.getAttributes().getInstance(attr);
                if (inst != null) inst.removeModifier(mod.id());
            });
        }
        fp.getInventory().setItem(0, weapon);
        fp.getInventory().selected = 0;
        weapon.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
            var inst = fp.getAttributes().getInstance(attr);
            if (inst != null) { inst.removeModifier(mod.id()); inst.addTransientModifier(mod); }
        });
    }

    // ==================================================================================
    // wd.serverAgentDistinctBodies — gap#48: two agents must be two bodies (createIsolated).
    // ==================================================================================

    private static void serverAgentDistinctBodiesScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int ax = ctx.origin().getX(), az = ctx.origin().getZ();
        final int bx = ax + 20, bz = az + 20, floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(() -> {
            clearBox(level, ax, floorY, az, 6, 8);
            clearBox(level, bx, floorY, bz, 6, 8);
        });
        BotConfig.walkerDebug = false;

        clearBox(level, ax, floorY + 1, az, 6, 6);
        clearBox(level, bx, floorY + 1, bz, 6, 6);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(ax + dx, floorY, az + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(bx + dx, floorY, bz + dz), Blocks.STONE.defaultBlockState());
            }
        ServerWorldDriver a = ServerWorldDriver.createIsolated(level, ax + 0.5, floorY + 1, az + 0.5);
        ServerWorldDriver b = ServerWorldDriver.createIsolated(level, bx + 0.5, floorY + 1, bz + 0.5);
        ctx.cleanup(() -> { a.fakePlayer().discard(); b.fakePlayer().discard(); });
        ServerPlayer fpA = a.fakePlayer(), fpB = b.fakePlayer();

        if (fpA == fpB)
            ctx.fail("both server agents are literally the same entity ("
                    + System.identityHashCode(fpA) + "): FakePlayerFactory.getMinecraft(level) is a"
                    + " per-level singleton, so agents (and concurrent arenas) fight over one body");

        // B is parked. A walks. Vanilla-obvious, and the whole point of having two agents.
        Vec3 bStart = fpB.position();
        a.avatar().commandMove(0f, 1f);
        for (int i = 0; i < 20; i++) { a.avatar().step(); }
        Vec3 bEnd = fpB.position();
        double bDrift = bStart.distanceTo(bEnd);
        double aMoved = fpA.position().distanceTo(new Vec3(ax + 0.5, floorY + 1, az + 0.5));
        WorldDriverCommon.LOG.warn("[wd.serverAgentDistinctBodies] fpA={} fpB={} | A walked {} blocks, B (idle) drifted {}",
                System.identityHashCode(fpA), System.identityHashCode(fpB), aMoved, bDrift);
        if (aMoved < 0.5)
            ctx.fail("rig broken: agent A did not walk at all (" + aMoved + ")");
        if (bDrift > 0.01)
            ctx.fail("driving agent A dragged idle agent B " + bDrift + " blocks: the two agents are sharing one body");
    }

    // ==================================================================================
    // wd.serverAvatarTickFidelity — gap#47: the server avatar mirrors Player.tick() pieces
    // (eating, cooldowns, recharge-bar reset). GOLDEN outcomes.
    // ==================================================================================

    private static void serverAvatarTickFidelityScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(() -> clearBox(level, cx, floorY, cz, 3, 5));
        BotConfig.walkerDebug = false;

        clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        ServerWorldDriver driver = ServerWorldDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();

        // --- (A) A HELD USE COMPLETES: eating. ---
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF, 2));
        fp.getInventory().selected = 0;
        fp.getFoodData().setFoodLevel(6);              // hungry, so the meal has somewhere to go
        driver.avatar().commandUseItem(true);
        for (int i = 0; i < 40; i++) driver.avatar().step();   // cooked beef = 32 ticks to eat
        driver.avatar().commandUseItem(false);
        int beefLeft = fp.getInventory().getItem(0).getCount();
        int food = fp.getFoodData().getFoodLevel();
        WorldDriverCommon.LOG.warn("[wd.serverAvatarTickFidelity] after holding use 40t on cooked beef: beefLeft={} foodLevel={}"
                + " (vanilla: 1 left, food 6 -> 14)", beefLeft, food);
        if (beefLeft != 1 || food <= 6)
            ctx.fail("holding USE on food never finished the bite (beefLeft=" + beefLeft
                    + " foodLevel=" + food + "): startUsingItem arms a countdown that nothing advances, so"
                    + " eat/drink/bow-draw are all silent no-ops on the server avatar");

        // --- (B) AN ITEM COOLDOWN EXPIRES. ---
        fp.getCooldowns().addCooldown(Items.ENDER_PEARL, 10);
        if (!fp.getCooldowns().isOnCooldown(Items.ENDER_PEARL))
            ctx.fail("rig broken: the cooldown did not even register");
        for (int i = 0; i < 15; i++) driver.avatar().step();
        boolean stillCooling = fp.getCooldowns().isOnCooldown(Items.ENDER_PEARL);
        WorldDriverCommon.LOG.warn("[wd.serverAvatarTickFidelity] 10t cooldown after 15 ticks: stillOnCooldown={}", stillCooling);
        if (stillCooling)
            ctx.fail("a 10-tick item cooldown had not expired after 15 avatar ticks:"
                    + " nothing calls cooldowns.tick(), so any item that goes on cooldown stays there forever");

        // --- (C) SWAPPING WEAPONS EMPTIES THE RECHARGE BAR. ---
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        fp.getInventory().selected = 0;
        for (int i = 0; i < 30; i++) driver.avatar().step();   // bar fills on the sword
        float charged = fp.getAttackStrengthScale(0.0f);
        if (charged < 1.0f)
            ctx.fail("rig broken: bar not full after 30 ticks (" + charged + ")");
        fp.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));   // swap: different item
        driver.avatar().step();
        float afterSwap = fp.getAttackStrengthScale(0.0f);
        WorldDriverCommon.LOG.warn("[wd.serverAvatarTickFidelity] recharge scale: onSword={} oneTickAfterSwapToAxe={}"
                + " (vanilla: the swap empties the bar)", charged, afterSwap);
        if (afterSwap > 0.5f)
            ctx.fail("swapping to a different weapon did not reset the recharge bar"
                    + " (scale still " + afterSwap + "): the avatar would swing the new weapon at full strength"
                    + " immediately, and observe.player.attack would advertise a bar vanilla says is empty");
    }

    // ==================================================================================
    // wd.serverAttackCooldown — gap#45: the melee attack cooldown is visible on observe.player.
    // GOLDEN recharge = ceil(20/1.6) = 13 ticks.
    // ==================================================================================

    private static void serverAttackCooldownScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(() -> clearBox(level, cx, floorY, cz, 3, 5));
        BotConfig.walkerDebug = false;

        clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        ServerWorldDriver driver = ServerWorldDriver.createIsolated(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        equipMainHand(fp, new ItemStack(Items.IRON_SWORD));
        // Let the weapon SWAP land before timing anything (gap #47 empties the bar on swap).
        driver.avatar().step();

        DriverApi api = new DriverApi();
        fp.resetAttackStrengthTicker();               // just swung: bar empty
        @SuppressWarnings("unchecked")
        Map<String, Object> a0 = (Map<String, Object>) api.observe.playerSnapshot(fp).get("attack");
        WorldDriverCommon.LOG.info("[wd.serverAttackCooldown] swordFresh={}", a0);
        if (a0 == null)
            ctx.fail("observe.player carries no `attack` field at all — "
                    + "the agent cannot see the cooldown its own damage is scaled by (this is the gap)");

        // (A) Right after a swing the bar is empty: a hit sent NOW would land for ~0 damage.
        float s0 = ((Number) a0.get("strengthScale")).floatValue();
        int cd0 = ((Number) a0.get("cooldownTicks")).intValue();
        int period = ((Number) a0.get("fullCooldownTicks")).intValue();
        if (s0 > 0.01f || Boolean.TRUE.equals(a0.get("ready")))
            ctx.fail("bar must read empty right after a swing: " + a0);
        // Iron sword = 1.6 attacks/s = 12.5 ticks; must be the real recharge, not a guess.
        if (period != 13 || cd0 != 13)
            ctx.fail("iron sword recharge should be ceil(20/1.6)=13 ticks, got period=" + period + " cooldownTicks=" + cd0);

        // (B) The bar fills monotonically as ticks pass, and `ready` flips exactly when the
        // scale reaches 1.0 — i.e. `ready` is the same gate CombatProcess swings on.
        int readyAt = -1;
        float prev = s0;
        for (int t = 1; t <= period + 2; t++) {
            driver.avatar().step();                   // the one tick-clock a FakePlayer gets
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) api.observe.playerSnapshot(fp).get("attack");
            float s = ((Number) a.get("strengthScale")).floatValue();
            int cd = ((Number) a.get("cooldownTicks")).intValue();
            boolean ready = Boolean.TRUE.equals(a.get("ready"));
            if (s < prev)
                ctx.fail("scale went backwards at t=" + t + ": " + a);
            if (ready != (s >= 1.0f))
                ctx.fail("`ready` disagrees with scale>=1.0 at t=" + t + ": " + a);
            if (ready && cd != 0)
                ctx.fail("ready but cooldownTicks!=0 at t=" + t + ": " + a);
            if (!ready && cd <= 0)
                ctx.fail("not ready but nothing left to wait at t=" + t + ": " + a);
            if (ready && readyAt < 0) readyAt = t;
            prev = s;
        }
        if (readyAt != period)
            ctx.fail("full strength should arrive after exactly the reported " + period
                    + " ticks (that IS the contract the agent waits on), arrived at " + readyAt);

        // (C) The number tracks the HELD weapon, not the player: an axe recharges slower.
        equipMainHand(fp, new ItemStack(Items.IRON_AXE));
        @SuppressWarnings("unchecked")
        Map<String, Object> axe = (Map<String, Object>) api.observe.playerSnapshot(fp).get("attack");
        int axePeriod = ((Number) axe.get("fullCooldownTicks")).intValue();
        WorldDriverCommon.LOG.info("[wd.serverAttackCooldown] axe={}", axe);
        if (axePeriod <= period)
            ctx.fail("an axe swings slower than a sword; got axe=" + axePeriod
                    + " sword=" + period + " — the field is not reading the held weapon");
    }

    // ==================================================================================
    // wd.serverCapability — a server-side ServerPlayer avatar BREAKS and PLACES blocks headless.
    // ==================================================================================

    private static void serverCapabilityScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ctx.cleanup(() -> {
            for (int dx = -5; dx <= 5; dx++)
                for (int dy = 0; dy <= 18; dy++)
                    for (int dz = -5; dz <= 5; dz++)
                        level.setBlockAndUpdate(new BlockPos(cx + dx, floorY + dy, cz + dz), Blocks.AIR.defaultBlockState());
        });

        buildFloor(level, cx, cz, floorY);
        BlockPos breakTarget = new BlockPos(cx + 2, floorY, cz);     // a floor block to mine
        BlockPos placeCell = new BlockPos(cx - 2, floorY + 1, cz);   // empty cell (its floor neighbour is solid)

        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;

        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 0.5, floorY + 1, cz + 0.5);
        ctx.cleanup(() -> av.fakePlayer().discard());
        ServerPlayer fp = av.fakePlayer();
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        fp.getInventory().add(new ItemStack(Items.DIRT, 64));
        LevelWorldView w = new LevelWorldView(level, fp);

        // BREAK: aim + break the floor block → becomes air.
        av.selectTool(breakTarget);
        av.aimAtBlock(breakTarget);
        av.breakHold(true);
        boolean broke = level.getBlockState(breakTarget).isAir();

        // PLACE: hold a placeable + fill the empty cell against the floor face.
        av.holdPlaceable();
        av.place(w, placeCell);
        boolean placed = !level.getBlockState(placeCell).isAir();

        WorldDriverCommon.LOG.info("[wd.serverCapability] broke={} placed={}", broke, placed);
        if (!broke) ctx.fail("server agent failed to BREAK the block (still " + level.getBlockState(breakTarget) + ")");
        if (!placed) ctx.fail("server agent failed to PLACE a block at " + placeCell);
    }

    // ==================================================================================
    // wd.serverElytra — the migrated ElytraProcess enters fall-flying server-side without crashing.
    // ==================================================================================

    private static void serverElytraScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // A small pad far BELOW so the bot is airborne (onGround=false → can fall-fly).
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        BotConfig.walkerDebug = false;
        BotConfig.elytraDebug = false;
        BotConfig.pathfinderSliceMs = Long.MAX_VALUE / 2;
        BotConfig.pathfinderMaxMs = Long.MAX_VALUE / 2;

        ServerWorldDriver driver = ServerWorldDriver.createIsolated(level, cx + 0.5, floorY + 40, cz + 0.5);
        ctx.cleanup(() -> driver.fakePlayer().discard());
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().clearContent();
        fp.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));   // fresh wing (full durability)
        // No fireworks: pure glide takeoff (boost needs a ticked firework entity).
        driver.runProcess(new ElytraProcess(
                new BlockPos(cx + 400, floorY + 40, cz), null, 0f, false, 0, 2000, 3.0, true));
        ServerAvatarManager.register(driver);

        boolean flewAtSomePoint = false;
        for (int t = 0; t < 60 && ServerAvatarManager.activeCount() > 0; t++) {
            ServerAvatarManager.tickAll();
            if (fp.isFallFlying()) flewAtSomePoint = true;
        }
        boolean crashed = !driver.finished() && ServerAvatarManager.activeCount() == 0;
        WorldDriverCommon.LOG.info("[wd.serverElytra] flew={} pos=({},{},{}) finished={} active={} crashed={}",
                flewAtSomePoint, fp.getX(), fp.getY(), fp.getZ(),
                driver.finished(), ServerAvatarManager.activeCount(), crashed);
        if (crashed)
            ctx.fail("server ElytraProcess crashed the tick (driver removed unfinished)");
        if (!flewAtSomePoint)
            ctx.fail("server ElytraProcess never entered fall-flying (startFallFlying failed)");
    }
}
