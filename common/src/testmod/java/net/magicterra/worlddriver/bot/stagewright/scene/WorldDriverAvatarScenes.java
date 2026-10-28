package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Optional;

import dev.architectury.event.events.common.PlayerEvent;
import net.magicterra.stagewright.contract.SceneFailure;
import net.magicterra.worlddriver.api.methods.bot.MoveContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractApi;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveApi;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts;
import net.magicterra.worlddriver.api.methods.observe.ObserveContracts.Attack;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.stagewright.Calls;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneArena;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
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
 * <p><b>These are the permanent regression guards for attack cooldown /
 * equipment attributes / {@code Player.tick} fidelity / distinct players, plus the
 * central ServerPlayer-avatar break/place capability claim.</b> Their assertion thresholds are
 * LOAD-BEARING GOLDEN VALUES — the iron-sword recharge {@code ceil(20/1.6)=13} ticks, cooked-beef
 * {@code beefLeft==1}, the empty-then-full recharge bar, etc. — and are translated one-for-one with
 * no number moved. Each bot is a roster player ({@link SceneBot#spawn}: one joined player per bot, the
 * shell these tests were promoted to a REQUIRED regression guard on; it changes identity only, not the
 * player physics the golden numbers measure), posed through its vanilla {@link ServerPlayer}, driven by
 * held keys ({@code mc.test.input.press}) and moved by body steps only ({@link SceneBot#step}); settings are
 * pinned through {@link Harness#pin}. Each world-touching scene air-scrubs its footprint in a
 * {@code ctx.cleanup} (#40). ⛔ No Avatar scene calls {@code level.tick()}.
 *
 * <p>{@code equipMainHand} is reproduced here as a private static, because it is this family's
 * alone. {@code buildFloor} and {@code clearBox} are NOT: the wave-2/3 "each provider
 * self-contains its needed helpers" precedent produced six authors of the first and three of the
 * second, so both now live in {@link SceneArena} and this file calls them.
 */
public final class WorldDriverAvatarScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.serverAgentDistinctBodies", 200, WorldDriverAvatarScenes::serverAgentDistinctBodiesScene),
                Scene.of("wd.serverAvatarTickFidelity", 200, WorldDriverAvatarScenes::serverAvatarTickFidelityScene),
                Scene.of("wd.serverAttackCooldown", 200, WorldDriverAvatarScenes::serverAttackCooldownScene),
                Scene.of("wd.serverElytra", 200, WorldDriverAvatarScenes::serverElytraScene),
                Scene.of("wd.serverBotJoinsPastModdedPayload", 200, WorldDriverAvatarScenes::serverBotJoinsPastModdedPayloadScene));
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
    // wd.serverAgentDistinctBodies — two agents must be two players (createIsolated).
    // ==================================================================================

    private static void serverAgentDistinctBodiesScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int ax = ctx.origin().getX(), az = ctx.origin().getZ();
        final int bx = ax + 20, bz = az + 20, floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            SceneArena.clearBox(level, ax, floorY, az, 6, 8);
            SceneArena.clearBox(level, bx, floorY, bz, 6, 8);
        });
        Harness.set("navigatorDebug", false);

        SceneArena.clearBox(level, ax, floorY + 1, az, 6, 6);
        SceneArena.clearBox(level, bx, floorY + 1, bz, 6, 6);
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                level.setBlockAndUpdate(new BlockPos(ax + dx, floorY, az + dz), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(bx + dx, floorY, bz + dz), Blocks.STONE.defaultBlockState());
            }
        SceneBot a = SceneBot.spawn(ctx, level, ax + 0.5, floorY + 1, az + 0.5);
        SceneBot b = SceneBot.spawn(ctx, level, bx + 0.5, floorY + 1, bz + 0.5);
        ServerPlayer fpA = a.player(), fpB = b.player();

        if (fpA == fpB)
            ctx.fail("both server agents are literally the same entity ("
                    + System.identityHashCode(fpA) + "): the roster handed two bots one shared"
                    + " player, so agents (and concurrent arenas) fight over one player");

        // B is parked. A walks. Vanilla-obvious, and the whole point of having two agents.
        Vec3 bStart = fpB.position();
        a.press(a.keys().move(0, 1));
        a.step(20);
        Vec3 bEnd = fpB.position();
        double bDrift = bStart.distanceTo(bEnd);
        double aMoved = fpA.position().distanceTo(new Vec3(ax + 0.5, floorY + 1, az + 0.5));
        TestMod.LOG.warn("[wd.serverAgentDistinctBodies] fpA={} fpB={} | A walked {} blocks, B (idle) drifted {}",
                System.identityHashCode(fpA), System.identityHashCode(fpB), aMoved, bDrift);
        if (aMoved < 0.5)
            ctx.fail("rig broken: agent A did not walk at all (" + aMoved + ")");
        if (bDrift > 0.01)
            ctx.fail("driving agent A dragged idle agent B " + bDrift + " blocks: the two agents are sharing one player");
    }

    // ==================================================================================
    // wd.serverAvatarTickFidelity — gap#47: the server avatar mirrors Player.tick() pieces
    // (eating, cooldowns, recharge-bar reset). GOLDEN outcomes.
    // ==================================================================================

    private static void serverAvatarTickFidelityScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY() + 20;

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> SceneArena.clearBox(level, cx, floorY, cz, 3, 5));
        Harness.set("navigatorDebug", false);

        SceneArena.clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        SceneBot driver = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.player();

        // --- (A) A HELD USE COMPLETES: eating. ---
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF, 2));
        fp.getInventory().selected = 0;
        fp.getFoodData().setFoodLevel(6);              // hungry, so the meal has somewhere to go
        driver.press(driver.keys().use(true));
        driver.step(40);                               // cooked beef = 32 ticks to eat
        driver.press(driver.keys().use(false));
        int beefLeft = fp.getInventory().getItem(0).getCount();
        int food = fp.getFoodData().getFoodLevel();
        TestMod.LOG.warn("[wd.serverAvatarTickFidelity] after holding use 40t on cooked beef: beefLeft={} foodLevel={}"
                + " (vanilla: 1 left, food 6 -> 14)", beefLeft, food);
        if (beefLeft != 1 || food <= 6)
            ctx.fail("holding USE on food never finished the bite (beefLeft=" + beefLeft
                    + " foodLevel=" + food + "): startUsingItem arms a countdown that nothing advances, so"
                    + " eat/drink/bow-draw are all silent no-ops on the server avatar");

        // --- (B) AN ITEM COOLDOWN EXPIRES. ---
        fp.getCooldowns().addCooldown(Items.ENDER_PEARL, 10);
        if (!fp.getCooldowns().isOnCooldown(Items.ENDER_PEARL))
            ctx.fail("rig broken: the cooldown did not even register");
        driver.step(15);
        boolean stillCooling = fp.getCooldowns().isOnCooldown(Items.ENDER_PEARL);
        TestMod.LOG.warn("[wd.serverAvatarTickFidelity] 10t cooldown after 15 ticks: stillOnCooldown={}", stillCooling);
        if (stillCooling)
            ctx.fail("a 10-tick item cooldown had not expired after 15 avatar ticks:"
                    + " nothing calls cooldowns.tick(), so any item that goes on cooldown stays there forever");

        // --- (C) SWAPPING WEAPONS EMPTIES THE RECHARGE BAR. ---
        fp.getInventory().clearContent();
        fp.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
        fp.getInventory().selected = 0;
        driver.step(30);                               // bar fills on the sword
        float charged = fp.getAttackStrengthScale(0.0f);
        if (charged < 1.0f)
            ctx.fail("rig broken: bar not full after 30 ticks (" + charged + ")");
        fp.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));   // swap: different item
        driver.step();
        float afterSwap = fp.getAttackStrengthScale(0.0f);
        TestMod.LOG.warn("[wd.serverAvatarTickFidelity] recharge scale: onSword={} oneTickAfterSwapToAxe={}"
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

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> SceneArena.clearBox(level, cx, floorY, cz, 3, 5));
        Harness.set("navigatorDebug", false);

        SceneArena.clearBox(level, cx, floorY + 1, cz, 3, 3);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());
        SceneBot driver = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 1, cz + 0.5);
        ServerPlayer fp = driver.player();
        fp.getInventory().clearContent();
        equipMainHand(fp, new ItemStack(Items.IRON_SWORD));
        // Let the weapon SWAP land before timing anything (gap #47 empties the bar on swap).
        driver.step();

        fp.resetAttackStrengthTicker();               // just swung: bar empty
        Attack a0 = attackOf(fp);
        TestMod.LOG.info("[wd.serverAttackCooldown] swordFresh={}", a0);

        // (A) Right after a swing the bar is empty: a hit sent NOW would land for ~0 damage.
        float s0 = (float) a0.strengthScale();
        int cd0 = a0.cooldownTicks();
        int period = a0.fullCooldownTicks();
        if (s0 > 0.01f || a0.ready())
            ctx.fail("bar must read empty right after a swing: " + a0);
        // Iron sword = 1.6 attacks/s = 12.5 ticks; must be the real recharge, not a guess.
        if (period != 13 || cd0 != 13)
            ctx.fail("iron sword recharge should be ceil(20/1.6)=13 ticks, got period=" + period + " cooldownTicks=" + cd0);

        // (B) The bar fills monotonically as ticks pass, and `ready` flips exactly when the
        // scale reaches 1.0 — i.e. `ready` is the same gate CombatProcess swings on.
        int readyAt = -1;
        float prev = s0;
        for (int t = 1; t <= period + 2; t++) {
            driver.step();                            // the one tick-clock a parked bot gets
            Attack a = attackOf(fp);
            float s = (float) a.strengthScale();
            int cd = a.cooldownTicks();
            boolean ready = a.ready();
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
        Attack axe = attackOf(fp);
        int axePeriod = axe.fullCooldownTicks();
        TestMod.LOG.info("[wd.serverAttackCooldown] axe={}", axe);
        if (axePeriod <= period)
            ctx.fail("an axe swings slower than a sword; got axe=" + axePeriod
                    + " sword=" + period + " — the field is not reading the held weapon");
    }

    /** {@code mc.observe.player}'s attack readiness for the bot's player, which joined the player list. */
    private static Attack attackOf(ServerPlayer fp) {
        ObserveContracts.PlayerReading r = Calls.now(ObserveApi.of(Calls.api())
                .player(new ObserveContracts.PlayerParams(Optional.of(fp.getGameProfile().getName()))));
        return r.player().orElseThrow(() -> new SceneFailure("mc.observe.player does not find "
                + fp.getGameProfile().getName() + " in the player list")).attack();
    }

    // ==================================================================================
    // wd.serverCapability — a server-side ServerPlayer avatar BREAKS and PLACES blocks headless.
    // ==================================================================================

    // ==================================================================================
    // wd.serverElytra — the migrated ElytraProcess enters fall-flying server-side without crashing.
    // ==================================================================================

    private static void serverElytraScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ(), floorY = ctx.origin().getY();

        Harness.pin(ctx);
        Harness.clearBots(ctx);
        ctx.cleanup(() -> {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.AIR.defaultBlockState());
        });

        // A small pad far BELOW so the bot is airborne (onGround=false → can fall-fly).
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                level.setBlockAndUpdate(new BlockPos(cx + dx, floorY, cz + dz), Blocks.STONE.defaultBlockState());

        Harness.set("navigatorDebug", false);
        Harness.set("elytraDebug", false);
        Harness.untimed(ctx);

        SceneBot driver = SceneBot.spawn(ctx, level, cx + 0.5, floorY + 40, cz + 0.5);
        ServerPlayer fp = driver.player();
        fp.getInventory().clearContent();
        fp.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));   // fresh wing (full durability)
        // No fireworks: pure glide takeoff (boost needs a ticked firework entity). Reactive flight toward a cell
        // 400 east, 2000 ticks at most, done within 3 blocks: the ElytraProcess this scene always started.
        Calls.started("elytraFly", Calls.now(SceneBot.tasks().elytraFly(new MoveContracts.ElytraParams(driver.ref(),
                Optional.of(Targets.pos(new BlockPos(cx + 400, floorY + 40, cz))), Optional.empty(), Optional.empty(),
                Optional.of(true), Optional.of(false), 40, Optional.of(2000), 3.0, false, 0, 0))));

        // Ticked one bot at a time, which hands a tick that throws back to the caller instead of dropping the bot
        // the way the server's own tick does: a crash fails the tick call, so it cannot pass as a bot that stopped.
        boolean flewAtSomePoint = false;
        String crashed = null;
        for (int t = 0; t < 60 && driver.busy(); t++) {
            try {
                driver.tick();
            } catch (SceneFailure e) {
                crashed = e.getMessage();
                break;
            }
            if (fp.isFallFlying()) flewAtSomePoint = true;
        }
        var status = driver.status();
        TestMod.LOG.info("[wd.serverElytra] flew={} pos=({},{},{}) busy={} lastEnd={} crashed={}",
                flewAtSomePoint, fp.getX(), fp.getY(), fp.getZ(),
                status.busy(), status.lastProcessEnd(), crashed);
        if (crashed != null)
            ctx.fail("server ElytraProcess crashed the tick: " + crashed);
        if (!flewAtSomePoint)
            ctx.fail("server ElytraProcess never entered fall-flying (startFallFlying failed)");
    }

    // ==================================================================================
    // wd.serverBotJoinsPastModdedPayload — a mod's own payload, sent to a bot at login and after,
    // neither stops the join nor throws. NeoForge refuses a payload on a channel the client did not
    // negotiate, and a bot negotiates none; L2Core's login sync made every bot creation fail.
    // ==================================================================================

    /** A payload on a channel no client negotiated. Never encoded: the bot drops it first. */
    private record ProbePayload() implements CustomPacketPayload {
        static final Type<ProbePayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(TestMod.MOD_ID, "bot_payload_probe"));

        @Override public Type<ProbePayload> type() { return TYPE; }
    }

    private static void serverBotJoinsPastModdedPayloadScene(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos at = ctx.origin().above(20);

        // Sent from the login event, as L2Core sends its sync: inside placeNewPlayer. Armed only while the roster
        // spawns the bot, so the player that joins in that window is the bot.
        boolean[] armed = {false};
        int[] sentAtJoin = {0};
        PlayerEvent.PlayerJoin onJoin = player -> {
            if (!armed[0]) return;
            player.connection.send(new ClientboundCustomPayloadPacket(new ProbePayload()));
            sentAtJoin[0]++;
        };
        PlayerEvent.PLAYER_JOIN.register(onJoin);
        ctx.cleanup(() -> PlayerEvent.PLAYER_JOIN.unregister(onJoin));

        ServerPlayer bot;
        armed[0] = true;
        try {
            bot = SceneBot.spawn(ctx, level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5).player();
        } catch (SceneFailure e) {
            ctx.fail("the bot did not join past a modded payload sent at login: " + e.getMessage());
            return;
        } finally {
            armed[0] = false;
        }
        ctx.record("sentAtJoin", sentAtJoin[0]);
        // Without this the scene would pass on a run where the payload was never sent at all.
        if (sentAtJoin[0] != 1)
            ctx.fail("the login event sent the probe " + sentAtJoin[0] + " times, expected once");

        // And after the join, the path a mod's PacketDistributor.sendToPlayer takes.
        try {
            bot.connection.send(new ClientboundCustomPayloadPacket(new ProbePayload()));
        } catch (RuntimeException e) {
            ctx.fail("a modded payload sent to a joined bot threw: " + e);
        }
    }
}
