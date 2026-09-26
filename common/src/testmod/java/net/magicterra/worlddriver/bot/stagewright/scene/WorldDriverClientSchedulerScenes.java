package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.process.SleepProcess;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.magicterra.worlddriver.bot.stagewright.journey.HoldStill;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * The scheduler's contract on the client's real player, where every chain is registered and the
 * status a caller reads is the one {@code mc.bot.status} ships: a process's slot is switched off when
 * the process ends, whatever its kind.
 *
 * <p>Adopts the player through {@link ClientHelm}, so every scene here skips on a dedicated server.
 */
public final class WorldDriverClientSchedulerScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.clientSleepCancelEndsGoto", 600, WorldDriverClientSchedulerScenes::sleepCancelEndsGoto),
                Scene.of("wd.clientBunkerBailHandsToRetreat", 600,
                        WorldDriverClientSchedulerScenes::bunkerBailHandsToRetreat),
                Scene.of("wd.clientDuskSecureBeachDoesNotRestart", 800,
                        WorldDriverClientSchedulerScenes::duskSecureBeachDoesNotRestart));
    }

    private static final int GROUND = 20;

    /** One reading of {@code mc.bot.status} per server tick of a watch window. */
    private record Sample(String chain, Map<?, ?> chains) {}

    private static Sample sample(ClientHelm helm) {
        Map<String, Object> s = helm.bot().status();
        Object chains = s.get("chains");
        return new Sample(String.valueOf(s.get("activeChain")), chains instanceof Map<?, ?> m ? m : Map.of());
    }

    private static Object bailOf(Sample s, String chain) {
        return s.chains().get(chain) instanceof Map<?, ?> one ? one.get("bail") : null;
    }

    private static Mob stillHostile(SceneContext ctx, EntityType<? extends Mob> type, int dx, int dy, int dz) {
        Mob m = type.create(ctx.level());
        if (m == null) ctx.fail("could not create " + type);
        m.setNoAi(true);
        m.setPersistenceRequired();
        BlockPos at = ctx.rel(dx, dy, dz);
        m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        ctx.level().addFreshEntity(m);
        ctx.cleanup(m::discard);
        return m;
    }

    /**
     * Low health, two hostiles in reach and one block of water at a shoreline: the bunker reflex is
     * cornered and bids 300, but will not dig a shaft it stands in water over. It must bail out of
     * the bid so the retreat reflex (100) gets control of the bot, instead of re-bidding 300 on
     * every tick and holding the bot still in front of the mobs.
     */
    private static void bunkerBailHandsToRetreat(SceneContext ctx) {
        for (int dx = -12; dx <= 12; dx++)
            for (int dz = -6; dz <= 6; dz++) {
                ctx.setBlock(dx, GROUND - 1, dz, Blocks.STONE);
                // The strip stops short of the slab's edge, so none of it runs off.
                ctx.setBlock(dx, GROUND, dz, Math.abs(dx) <= 1 && Math.abs(dz) <= 4 ? Blocks.WATER : Blocks.STONE);
                for (int dy = 1; dy <= 5; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
        // Husks, not zombies: they do not burn in daylight, so the siege lasts the whole window.
        stillHostile(ctx, EntityType.HUSK, 4, GROUND + 1, 2);
        stillHostile(ctx, EntityType.HUSK, 4, GROUND + 1, -2);
        BlockPos start = ctx.rel(0, GROUND, 0);
        ctx.record("test setup", "one-deep strip of water at x in [-1,1], the bot standing in the water at " + start.toShortString() + ", two motionless husks 4 blocks away on the east bank");

        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        BotConfig.autoBunker = true;
        BotConfig.autoRetreat = true;
        ServerPlayer body = helm.player();
        // Below the bunker threshold (10) and the retreat one, and too hungry to regenerate out of it.
        body.setHealth(5f);
        body.getFoodData().setFoodLevel(17);
        body.getFoodData().setSaturation(0f);
        helm.sync(20, () -> {
            final int window = 120;
            final int[] t = { 0 }, bunkerTicks = { 0 }, firstRetreat = { -1 };
            final Object[] bail = { null };
            ctx.await(() -> {
                Sample s = sample(helm);
                if ("bunker".equals(s.chain())) bunkerTicks[0]++;
                if ("retreat".equals(s.chain()) && firstRetreat[0] < 0) firstRetreat[0] = t[0];
                if (bail[0] == null) bail[0] = bailOf(s, "bunker");
                return ++t[0] >= window;
            }).within(window + 100).then(() -> {
                ctx.record("observed", "bunker was the active chain for " + bunkerTicks[0] + " ticks, retreat first took over at tick "
                        + (firstRetreat[0] < 0 ? "never" : String.valueOf(firstRetreat[0])) + ", bail=" + bail[0]
                        + "; " + helm.where());
                ctx.check(firstRetreat[0] >= 0 && firstRetreat[0] <= 40)
                        .as("A: retreat takes over within 40 ticks: at tick " + firstRetreat[0]).isTrue();
                ctx.check(bunkerTicks[0] <= 3).as("B: after bailing, bunker does not reclaim control every tick: active for "
                        + bunkerTicks[0] + " ticks").isTrue();
                ctx.check(bail[0] instanceof Map<?, ?> m && "water".equals(m.get("reason")))
                        .as("C: the bunker bail is visible in status: " + bail[0]).isTrue();
            });
        });
    }

    private static Map<?, ?> call(SceneContext ctx, String tag, String method, Map<String, Object> params) {
        Object r = WorldDriverCommon.api().route(method, params);
        ctx.record(tag, String.valueOf(r));
        return r instanceof Map<?, ?> m ? m : Map.of();
    }

    /**
     * Exposed at night on a beach with {@code autoSecureAtDusk}: the sand beside the bot is wet, so
     * even a one-deep niche floods and the bunker process ends with {@code unsafe-site} on its first
     * tick, changing nothing. Dusk shelter must then sit out its cooldown and let the user task run,
     * instead of bidding 90 above it and restarting the dig every tick for the whole night.
     */
    private static void duskSecureBeachDoesNotRestart(SceneContext ctx) {
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -6; dz <= 6; dz++) {
                ctx.setBlock(dx, GROUND - 1, dz, Blocks.STONE);
                // The pool stops short of the slab's edge on every side, so none of it runs off.
                ctx.setBlock(dx, GROUND, dz, dx >= 1 && dx <= 4 && Math.abs(dz) <= 4 ? Blocks.WATER : Blocks.SAND);
                for (int dy = 1; dy <= 5; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        final long dayTimeWas = ctx.level().getDayTime();
        ctx.cleanup(() -> ctx.level().setDayTime(dayTimeWas));
        ctx.level().setDayTime(15_000);
        ctx.record("test setup", "beach, the bot standing at " + start.toShortString() + ", water directly to the east at x in [1,4]; time 15000 (night)");

        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        BotConfig.autoSecureAtDusk = true;
        BotConfig.duskUrgent = true;
        BotConfig.duskUrgentDryRun = false;
        // Time packets reach the client once a second; wait for the night to be the client's too.
        helm.sync(40, () -> {
            helm.bot().runProcess(new HoldStill(1_000));
            final int window = 300;
            final int[] t = { 0 }, duskTicks = { 0 }, userTicks = { 0 };
            final Object[] bail = { null }, endReason = { null };
            ctx.await(() -> {
                Sample s = sample(helm);
                if ("duskSecure".equals(s.chain())) duskTicks[0]++;
                if ("user".equals(s.chain())) userTicks[0]++;
                if (bail[0] == null) bail[0] = bailOf(s, "duskSecure");
                if (endReason[0] == null) endReason[0] = helm.slot("bunker").get("endReason");
                return ++t[0] >= window;
            }).within(window + 100).then(() -> {
                ctx.record("observed", "duskSecure was the active chain for " + duskTicks[0] + " ticks, user for " + userTicks[0]
                        + " ticks (out of " + window + "), bunker.endReason=" + endReason[0] + ", bail=" + bail[0]);
                ctx.check("unsafe-site".equals(endReason[0]))
                        .as("test setup: the bunker on the beach ends with unsafe-site: " + endReason[0]).isTrue();
                ctx.check(duskTicks[0] <= 3).as("A: after the failure it does not restart every tick: duskSecure active for " + duskTicks[0] + " ticks").isTrue();
                ctx.check(userTicks[0] >= window - 40).as("B: the user task regains control: user active for " + userTicks[0] + " ticks").isTrue();
                ctx.check(bail[0] instanceof Map<?, ?>).as("C: the duskSecure bail is visible in status: " + bail[0]).isTrue();
            });
        });
    }

    /**
     * {@code mc.bot.sleep} reports into the goto slot under the kind {@code sleep}. Cancelled while it
     * walks to the bed, the goto slot must go inactive: a slot left on says the bot is walking, keeps
     * the mouse and focus takeover engaged, and lets the screen watchdog close the player's chests.
     */
    private static void sleepCancelEndsGoto(SceneContext ctx) {
        for (int dx = -4; dx <= 16; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                ctx.setBlock(dx, GROUND, dz, Blocks.STONE);
                for (int dy = 1; dy <= 4; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
        // Head first: placing the foot then updates the head against a real foot, and neither half
        // is dropped as an orphan.
        BlockPos foot = ctx.rel(14, GROUND + 1, 0), head = ctx.rel(15, GROUND + 1, 0);
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        ctx.level().setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
        ctx.level().setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
        ctx.cleanup(() -> {
            ctx.level().setBlock(foot, Blocks.AIR.defaultBlockState(), 3);
            ctx.level().setBlock(head, Blocks.AIR.defaultBlockState(), 3);
        });
        BlockPos start = ctx.rel(0, GROUND + 1, 0);
        ctx.record("test setup", "stone slab at y=" + GROUND + ", bed at " + foot.toShortString() + " (facing east), start " + start.toShortString());

        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.sync(20, () -> {
            helm.bot().runProcess(new SleepProcess(null, 16));
            final int[] waited = { 0 };
            ctx.await(() -> {
                waited[0]++;
                return Boolean.TRUE.equals(helm.slot("goto").get("active")) && waited[0] >= 15;
            }).within(200).then(() -> {
                double gap = helm.flatDistance(foot);
                ctx.record("beforeCancel", helm.where() + String.format(Locale.ROOT, ", %.2f blocks from the bed; goto=%s",
                        gap, helm.slot("goto")));
                ctx.check(gap > 2.0).as("test setup: at cancellation the bot is still on its way to the bed (" + gap + " blocks from the bed)").isTrue();
                call(ctx, "cancel", "mc.bot.cancel", Map.of("process", "sleep"));
                helm.sync(5, () -> {
                    Map<?, ?> status = call(ctx, "afterCancel.status", "mc.bot.status", Map.of());
                    Map<?, ?> go = helm.slot("goto");
                    ctx.check(Boolean.FALSE.equals(go.get("active")))
                            .as("A: after the sleep task is cancelled, the goto slot is no longer active: " + go).isTrue();
                    ctx.check(status.get("activeProcess") == null)
                            .as("B: the user task slot is empty: activeProcess=" + status.get("activeProcess")).isTrue();
                    Object end = status.get("lastProcessEnd");
                    ctx.check(end instanceof Map<?, ?> m && "sleep".equals(m.get("kind")))
                            .as("C: the most recent process end is recorded as sleep: " + end).isTrue();
                });
            });
        });
    }
}
