package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Map;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.MobBot;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.config.BehaviorConfig;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.testcontent.CastingMob;
import net.magicterra.worlddriver.testcontent.NpcContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vanilla mobs driven by the server bot stack, addressed through {@code ApiRegistry.route} the way
 * every transport calls it:
 *
 * <ul>
 *   <li>a zombie named {@code entity:<uuid>} is taken over by its first order, walks down a stone
 *       course, refuses the verbs that need hands, and gets its goals back from
 *       {@code /worlddriver mob release};</li>
 *   <li>a wolf taken over by {@code /worlddriver mob} walks the same course as {@code mob:<name>};</li>
 *   <li>a driven zombie removed mid-walk ends its action as {@code bot_removed} and gives up its ref.</li>
 * </ul>
 *
 * <p>Legal on every topology: no player entity is created. The mobs are invulnerable, so the sun
 * and a fall measure nothing here.
 */
public final class WorldDriverMobScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int COURSE = 10;
    /** Ticks for a fresh mob to be ticked at all and land: an arena chunk is not entity-ticking at once. */
    private static final int SETTLE_TICKS = 100;

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.mobZombieWalksByEntityRefAndIsReleased", 600, WorldDriverMobScenes::zombieByEntityRef),
                Scene.of("wd.mobWolfAdoptedByCommandWalks", 600, WorldDriverMobScenes::wolfByCommand),
                Scene.of("wd.mobRemovedEndsItsAction", 400, WorldDriverMobScenes::removedEndsItsAction),
                Scene.of("wd.mobStepsOffMagmaUnordered", 300, WorldDriverMobScenes::stepsOffMagmaUnordered),
                Scene.of("wd.mobSeesOnlyWhatHuntsIt", 300, WorldDriverMobScenes::seesOnlyWhatHuntsIt),
                Scene.of("wd.mobThatCastsItsOwnControlWalks", 600, WorldDriverMobScenes::castsItsOwnControlWalks),
                Scene.of("wd.mobThatIgnoresInputFailsUnresponsive", 400, WorldDriverMobScenes::ignoresInputFailsUnresponsive),
                Scene.of("wd.mobUndrivableIsRefused", 300, WorldDriverMobScenes::undrivableIsRefused),
                Scene.of("wd.mobWithRegisteredDriverWalks", 600, WorldDriverMobScenes::registeredDriverWalks),
                Scene.of("wd.mobWithRegisteredMoverTravels", 400, WorldDriverMobScenes::registeredMoverTravels));
    }

    /**
     * A stand-in whose own travel reads no input, with an entity driver registered for its type as another
     * mod would: the driver turns the bot's input into its motion, so the goto that fails as unresponsive
     * on the unregistered stand-in walks here.
     */
    private static void registeredDriverWalks(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos goal = o.offset(COURSE, 0, 0);
        Mob mob = stage(ctx, o, NpcContent.VELOCITY_MOB.get());
        String ref = BotRegistry.ENTITY_PREFIX + mob.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> settled(mob)).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the stand-in by entity ref did not start: " + go); return; }
            ctx.await(() -> !"running".equals(read(api, actionId(go)).get("state"))).within(400).then(() -> {
                Map<?, ?> action = read(api, actionId(go));
                BlockPos stoppedAt = mob.blockPosition();
                ctx.record("ended", stoppedAt.toShortString() + " " + action);
                ctx.check("succeeded".equals(action.get("state")) && stoppedAt.closerThan(goal, 1.5))
                        .as("the stand-in walks to the goal through its registered driver: stopped at "
                                + stoppedAt.toShortString() + ", " + action)
                        .isTrue();
            });
        });
    }

    /**
     * A stand-in whose registered driver brings its own mover: the goto is handed to it whole, reports
     * travel {@code mover}, and ends where the mover put the mob, through the wall across the course that
     * the driver's own route would have to go round.
     */
    private static void registeredMoverTravels(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos goal = o.offset(COURSE, 0, 0);
        Mob mob = stage(ctx, o, NpcContent.MOVER_MOB.get());
        for (int z = -3; z <= 3; z++) {
            for (int y = 0; y < 3; y++) ctx.level().setBlockAndUpdate(o.offset(COURSE / 2, y, z), STONE);
        }
        String ref = BotRegistry.ENTITY_PREFIX + mob.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> settled(mob)).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the stand-in by entity ref did not start: " + go); return; }
            ctx.await(() -> !"running".equals(read(api, actionId(go)).get("state"))).within(200).then(() -> {
                Map<?, ?> action = read(api, actionId(go));
                BlockPos stoppedAt = mob.blockPosition();
                ctx.record("ended", stoppedAt.toShortString() + " " + action);
                ctx.check("succeeded".equals(action.get("state")) && String.valueOf(action).contains("travel=mover")
                                && stoppedAt.equals(goal))
                        .as("the mover takes the trip whole and ends on the goal: stopped at "
                                + stoppedAt.toShortString() + ", " + action)
                        .isTrue();
            });
        });
    }

    /**
     * A stand-in for a modded mob whose own tick casts its move control to its own class walks to a
     * goal: the driver writes into the mob's AI step and leaves that control in place, so the cast holds
     * and the mob's own tick keeps running.
     */
    private static void castsItsOwnControlWalks(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos goal = o.offset(COURSE, 0, 0);
        CastingMob mob = (CastingMob) stage(ctx, o, NpcContent.CASTING_MOB.get());
        String ref = BotRegistry.ENTITY_PREFIX + mob.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> settled(mob)).within(SETTLE_TICKS).then(() -> {
            int pulsesBefore = ((CastingMob.OwnMoveControl) mob.getMoveControl()).pulses();
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the stand-in by entity ref did not start: " + go); return; }
            Bot bot = BotRegistry.get(ref);
            ctx.await(() -> !bot.busy()).within(400).then(() -> {
                BlockPos stoppedAt = mob.blockPosition();
                Map<?, ?> action = read(api, actionId(go));
                int pulses = ((CastingMob.OwnMoveControl) mob.getMoveControl()).pulses() - pulsesBefore;
                ctx.record("arrived", stoppedAt.toShortString() + " pulses=" + pulses + " " + action);
                ctx.check(stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && pulses > 0)
                        .as("the stand-in walks to the goal with its own control still in place and its own tick still "
                                + "casting it: stopped at " + stoppedAt.toShortString() + ", pulses=" + pulses + ", " + action)
                        .isTrue();
            });
        });
    }

    /** A stand-in for a modded mob whose own travel reads no input: its goto fails as unresponsive. */
    private static void ignoresInputFailsUnresponsive(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        Mob mob = stage(ctx, o, NpcContent.SELF_MOVING_MOB.get());
        String ref = BotRegistry.ENTITY_PREFIX + mob.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> settled(mob)).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(o.offset(COURSE, 0, 0))));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the stand-in by entity ref did not start: " + go); return; }
            ctx.await(() -> !"running".equals(read(api, actionId(go)).get("state"))).within(200).then(() -> {
                Map<?, ?> action = read(api, actionId(go));
                ctx.record("ended", mob.blockPosition().toShortString() + " " + action);
                ctx.check("failed".equals(action.get("state")) && String.valueOf(action).contains("unresponsive")
                                && mob.blockPosition().closerThan(o, 1.5))
                        .as("the stand-in never moves, and its goto fails as unresponsive instead of running to its deadline: "
                                + mob.blockPosition().toShortString() + ", " + action)
                        .isTrue();
            });
        });
    }

    /**
     * A type listed in {@code undrivableMobs}, and a mob with no AI, are refused by an order naming
     * them and by {@code /worlddriver mob}, and neither is taken over.
     */
    private static void undrivableIsRefused(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        Mob listed = stage(ctx, o, EntityType.WOLF);
        Mob noAi = spawnStill(ctx, EntityType.ZOMBIE, o.offset(0, 0, 2));
        var before = BotConfig.undrivableMobs;
        ctx.cleanup(() -> BotConfig.undrivableMobs = before);
        BotConfig.undrivableMobs = java.util.Set.of("minecraft:wolf");

        ctx.await(() -> settled(listed) && noAi.tickCount > 5).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> listedGo = call(api, "mc.bot.goto", Map.of("bot", BotRegistry.ENTITY_PREFIX + listed.getUUID(),
                    "target", at(o.offset(COURSE, 0, 0))));
            Map<String, Object> noAiGo = call(api, "mc.bot.goto", Map.of("bot", BotRegistry.ENTITY_PREFIX + noAi.getUUID(),
                    "target", at(o.offset(COURSE, 0, 2))));
            int cmd = command(ctx.server(), "worlddriver mob " + listed.getUUID() + " scene-listed");
            ctx.record("refusals", listedGo + " / " + noAiGo + " / command=" + cmd);
            ctx.check(refusedAs(listedGo, "bot_unavailable") && String.valueOf(listedGo).contains("undrivableMobs")
                            && refusedAs(noAiGo, "bot_unavailable") && String.valueOf(noAiGo).contains("NoAI")
                            && cmd == 0 && BotRegistry.get(MobBot.KIND + ":scene-listed") == null
                            && !listed.goalSelector.getAvailableGoals().isEmpty())
                    .as("the listed wolf and the zombie with no AI are refused, say why, and keep their own goals: "
                            + listedGo + " / " + noAiGo + " / command=" + cmd)
                    .isTrue();
        });
    }

    /**
     * An adopted wolf with no order, standing on a magma block: the contact-damage reflex walks it off.
     * Not invulnerable here, since the reflex fires on the damage.
     */
    private static void stepsOffMagmaUnordered(SceneContext ctx) {
        BlockPos o = ctx.origin().above(20);
        Mob wolf = stage(ctx, o, EntityType.WOLF);
        BehaviorConfig.contactDamageEscape = true;
        String ref = MobBot.KIND + ":scene-magma";
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> settled(wolf)).within(SETTLE_TICKS).then(() -> {
            int ok = command(ctx.server(), "worlddriver mob " + wolf.getUUID() + " scene-magma");
            if (ok != 1) { ctx.fail("/worlddriver mob did not adopt the wolf: result=" + ok); return; }
            // Laid after the takeover, so it is the bot and not the wolf's own AI that leaves it.
            wolf.setInvulnerable(false);
            ctx.level().setBlockAndUpdate(o.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
            ctx.await(() -> !wolf.blockPosition().equals(o)).within(100).then(() -> {
                ctx.record("left", wolf.blockPosition().toShortString() + " hp=" + wolf.getHealth());
                ctx.check(wolf.isAlive() && !wolf.getBlockStateOn().is(Blocks.MAGMA_BLOCK))
                        .as("the idle wolf steps off the magma alive: at " + wolf.blockPosition().toShortString() + ", hp=" + wolf.getHealth())
                        .isTrue();
            });
        });
    }

    /**
     * An adopted wolf beside two zombies with their AI off: one has the wolf as its target, the other
     * has none. The wolf's threat scan lists the first and not the second.
     */
    private static void seesOnlyWhatHuntsIt(SceneContext ctx) {
        BlockPos o = ctx.origin().above(20);
        Mob wolf = stage(ctx, o, EntityType.WOLF);
        Mob hunter = spawnStill(ctx, EntityType.ZOMBIE, o.offset(4, 0, -2));
        Mob idle = spawnStill(ctx, EntityType.ZOMBIE, o.offset(4, 0, 2));
        String ref = MobBot.KIND + ":scene-prey";
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        // The zombies have no AI, so they never update onGround; being ticked is enough for them.
        ctx.await(() -> settled(wolf) && hunter.tickCount > 5 && idle.tickCount > 5).within(SETTLE_TICKS).then(() -> {
            hunter.setTarget(wolf);
            int ok = command(ctx.server(), "worlddriver mob " + wolf.getUUID() + " scene-prey");
            if (ok != 1 || !(BotRegistry.get(ref) instanceof MobBot bot)) { ctx.fail("/worlddriver mob did not adopt the wolf: result=" + ok); return; }
            int at = ctx.ticks();
            ctx.await(() -> ctx.ticks() - at >= 3).within(20).then(() -> {
                List<Integer> seen = bot.botState().threats.current().threats().stream().map(ThreatScanner.Threat::id).toList();
                ctx.record("threats", seen + " hunter=" + hunter.getId() + " idle=" + idle.getId());
                ctx.check(seen.contains(hunter.getId()) && !seen.contains(idle.getId()))
                        .as("the wolf's threats are the zombie hunting it and not the idle one: " + seen
                                + ", hunter=" + hunter.getId() + ", idle=" + idle.getId())
                        .isTrue();
            });
        });
    }

    /** An invulnerable mob of {@code type} with its AI off, standing at {@code foot}. */
    private static Mob spawnStill(SceneContext ctx, EntityType<? extends Mob> type, BlockPos foot) {
        Mob mob = type.create(ctx.level());
        if (mob == null) throw new IllegalStateException(type + " created no entity");
        mob.moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5, 90f, 0f);
        mob.setInvulnerable(true);
        mob.setPersistenceRequired();
        mob.setNoAi(true);
        ctx.level().addFreshEntity(mob);
        ctx.cleanup(mob::discard);
        return mob;
    }

    private static void zombieByEntityRef(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos goal = o.offset(COURSE, 0, 0);
        Mob zombie = stage(ctx, o, EntityType.ZOMBIE);
        String ref = BotRegistry.ENTITY_PREFIX + zombie.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));
        int goals = zombie.goalSelector.getAvailableGoals().size(), targets = zombie.targetSelector.getAvailableGoals().size();

        ctx.await(() -> settled(zombie)).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            Bot bot = BotRegistry.get(ref);
            if (actionId(go) == null || !(bot instanceof MobBot)) {
                ctx.fail("a goto naming the zombie by entity ref did not adopt it and start: " + go + ", registered=" + bot);
                return;
            }
            ctx.check(zombie.goalSelector.getAvailableGoals().isEmpty() && zombie.targetSelector.getAvailableGoals().isEmpty()
                            && zombie.isPersistenceRequired())
                    .as("the adopted zombie has its goals taken off and stops despawning: goals=" + zombie.goalSelector.getAvailableGoals().size()
                            + " targets=" + zombie.targetSelector.getAvailableGoals().size())
                    .isTrue();
            // Named, because a status without one also reads the client's own player where there is a
            // client, and that read cannot be made from the server thread.
            Map<?, ?> listed = botEntry(call(api, "mc.bot.status", Map.of("bot", ref)), ref);
            ctx.record("status", String.valueOf(listed));
            ctx.check("mob".equals(listed.get("kind"))).as("status lists the zombie under its ref as a mob: " + listed).isTrue();

            ctx.await(() -> !bot.busy()).within(400).then(() -> {
                BlockPos stoppedAt = zombie.blockPosition();
                Map<?, ?> action = read(api, actionId(go));
                ctx.record("arrived", stoppedAt.toShortString() + " " + action);
                ctx.check(stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")))
                        .as("the zombie walks to the goal and the order succeeds: stopped at " + stoppedAt.toShortString() + ", " + action)
                        .isTrue();

                Map<String, Object> mine = call(api, "mc.bot.mine", Map.of("bot", ref, "blocks", List.of("minecraft:stone"), "radius", 4));
                Map<String, Object> hold = call(api, "mc.bot.holdItem", Map.of("bot", ref, "item", "minecraft:stone"));
                ctx.record("hands", mine + " / " + hold);
                ctx.check(refusedAs(mine, "unsupported_capability") && refusedAs(hold, "unsupported_capability") && !bot.busy())
                        .as("a zombie has no hands, so mine and holdItem are refused before anything starts: " + mine + " / " + hold)
                        .isTrue();

                int ok = command(ctx.server(), "worlddriver mob release " + ref);
                ctx.record("release", "result=" + ok + " goals=" + zombie.goalSelector.getAvailableGoals().size()
                        + " targets=" + zombie.targetSelector.getAvailableGoals().size());
                ctx.check(ok == 1 && BotRegistry.get(ref) == null
                                && zombie.goalSelector.getAvailableGoals().size() == goals
                                && zombie.targetSelector.getAvailableGoals().size() == targets)
                        .as("/worlddriver mob release gives the zombie its " + goals + " goals and " + targets + " target goals back and frees the ref: result="
                                + ok + ", goals=" + zombie.goalSelector.getAvailableGoals().size() + ", targets="
                                + zombie.targetSelector.getAvailableGoals().size() + ", registered=" + BotRegistry.get(ref))
                        .isTrue();
            });
        });
    }

    private static void wolfByCommand(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos goal = o.offset(COURSE, 0, 0);
        Mob wolf = stage(ctx, o, EntityType.WOLF);
        String ref = MobBot.KIND + ":scene-wolf";
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> settled(wolf)).within(SETTLE_TICKS).then(() -> {
            int ok = command(ctx.server(), "worlddriver mob " + wolf.getUUID() + " scene-wolf");
            Bot bot = BotRegistry.get(ref);
            ctx.record("adopt", "result=" + ok + " registered=" + bot);
            if (ok != 1 || !(bot instanceof MobBot) || bot.entity() != wolf) {
                ctx.fail("/worlddriver mob did not adopt the wolf as " + ref + ": result=" + ok + ", registered=" + bot);
                return;
            }
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto to the adopted wolf did not start: " + go); return; }

            ctx.await(() -> !bot.busy()).within(400).then(() -> {
                BlockPos stoppedAt = wolf.blockPosition();
                Map<?, ?> action = read(api, actionId(go));
                ctx.record("arrived", stoppedAt.toShortString() + " " + action);
                ctx.check(stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && ref.equals(action.get("bot")))
                        .as("the wolf, addressed as " + ref + ", walks to the goal and the order succeeds: stopped at "
                                + stoppedAt.toShortString() + ", " + action)
                        .isTrue();
            });
        });
    }

    private static void removedEndsItsAction(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        Mob zombie = stage(ctx, o, EntityType.ZOMBIE);
        String ref = BotRegistry.ENTITY_PREFIX + zombie.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> settled(zombie)).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(o.offset(COURSE, 0, 0))));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the zombie by entity ref did not start: " + go); return; }
            int at = ctx.ticks();
            ctx.await(() -> ctx.ticks() - at >= 10).within(40).then(() -> {
                zombie.discard();
                ctx.await(() -> !"running".equals(read(api, actionId(go)).get("state"))).within(40).then(() -> {
                    Map<?, ?> action = read(api, actionId(go));
                    ctx.record("removed", action + " registered=" + BotRegistry.get(ref));
                    ctx.check("cancelled".equals(action.get("state")) && "bot_removed".equals(action.get("reason"))
                                    && BotRegistry.get(ref) == null)
                            .as("removing the driven zombie ends its walk as bot_removed and frees its ref: " + action
                                    + ", registered=" + BotRegistry.get(ref))
                            .isTrue();
                });
            });
        });
    }

    /** A flat stone course along +x under a pinned baseline, and an invulnerable mob of {@code type} at its start. */
    private static Mob stage(SceneContext ctx, BlockPos o, EntityType<? extends Mob> type) {
        ServerLevel level = ctx.level();
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        WorldConfig.allowBreak = false;
        WorldConfig.allowPlace = false;
        // The shipped arrival rule, which the pinned baseline turns off.
        ControlConfig.navigatorHoldLastNodeUntilStanding = true;
        BlockPos lo = o.offset(-2, -1, -3), hi = o.offset(COURSE + 2, 3, 3);
        fill(level, lo, hi, AIR);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, lo, o.offset(COURSE + 2, -1, 3), STONE);

        Mob mob = type.create(level);
        if (mob == null) throw new IllegalStateException(type + " created no entity");
        mob.moveTo(o.getX() + 0.5, o.getY(), o.getZ() + 0.5, -90f, 0f);
        mob.setInvulnerable(true);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        ctx.cleanup(mob::discard);
        return mob;
    }

    /** The mob has been ticked and stands on the course. */
    static boolean settled(Mob mob) { return mob.tickCount > 5 && mob.onGround(); }

    /** The command's result, or -1 when it does not parse. */
    private static int command(MinecraftServer server, String line) {
        try {
            return server.getCommands().getDispatcher().execute(line, server.createCommandSourceStack().withSuppressedOutput());
        } catch (CommandSyntaxException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> call(ApiRegistry api, String method, Map<String, Object> params) {
        return (Map<String, Object>) api.route(method, params);
    }

    static Map<String, Object> at(BlockPos p) {
        return Map.of("kind", "position", "pos", Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()));
    }

    /** The id of the action an accepted reply started, or null when it was refused. */
    static String actionId(Map<String, Object> reply) {
        return "accepted".equals(reply.get("outcome")) && reply.get("action") instanceof Map<?, ?> a
                ? String.valueOf(a.get("actionId")) : null;
    }

    static boolean refusedAs(Map<String, Object> reply, String reason) {
        return "refused".equals(reply.get("outcome")) && reason.equals(reply.get("reason"));
    }

    /** The action as {@code mc.wait.result} reads it now. */
    static Map<?, ?> read(ApiRegistry api, String actionId) {
        if (actionId == null) return Map.of();
        return call(api, "mc.wait.result", Map.of("kind", "action", "actionId", actionId)).get("action") instanceof Map<?, ?> a ? a : Map.of();
    }

    /** The {@code bots} entry for {@code id} in a {@code mc.bot.status} reply, or an empty map. */
    private static Map<?, ?> botEntry(Map<String, Object> status, String id) {
        if (status.get("bots") instanceof List<?> bots) {
            for (Object o : bots) if (o instanceof Map<?, ?> m && id.equals(m.get("id"))) return m;
        }
        return Map.of();
    }

    static void fill(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(a, b)) level.setBlockAndUpdate(p, state);
    }
}
