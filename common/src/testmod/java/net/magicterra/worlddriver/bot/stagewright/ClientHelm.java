package net.magicterra.worlddriver.bot.stagewright;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotApi;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.magicterra.worlddriver.bot.stagewright.journey.HoldStill;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * A scene's handle on the client's REAL player: the body the integrated topology exists to test.
 *
 * <p>{@link SceneBody} mints headless bodies and refuses to on an integrated server; {@code JourneyRig}
 * adopts the real player but only for the ladder. This is the third shape: an arena scene that stages
 * terrain, puts the real player in it, and drives it through the client's own user-task chain
 * ({@link BotApi#runProcess}) — so the walker under test is the client walker, on the client thread,
 * with vanilla client physics and every reflex chain armed, which is the thing the headless scenes
 * cannot reach.
 *
 * <p>Everything here runs on the SERVER thread of an integrated server. Driving goes through
 * {@code runProcess}, which marshals and never blocks; readings come back through the world (the
 * {@link ServerPlayer} the client keeps sending movement packets for) and through
 * {@link BotApi#status()}, which reads volatiles. Nothing here waits on the client thread.
 *
 * <p>On any other topology the scene {@linkplain SceneContext#skip skips} and says so.
 */
public final class ClientHelm {

    private final SceneContext ctx;
    private final ServerPlayer player;
    private final BotApi bot;

    private ClientHelm(SceneContext ctx, ServerPlayer player, BotApi bot) {
        this.ctx = ctx;
        this.player = player;
        this.bot = bot;
    }

    /**
     * Put the real player at {@code foot} (feet in the centre of that cell), in survival with an
     * empty inventory, with {@link BotConfig} pinned to the baseline for the scene's duration.
     *
     * <p>Skips unless this JVM is a client hosting its own world with the driver's client half
     * constructed and a person in the player list — the same three facts the ladder reads.
     * The player is teleported back where they were when the scene resolves.
     */
    public static ClientHelm adopt(SceneContext ctx, BlockPos foot, float yaw) {
        MinecraftServer server = ctx.server();
        boolean integrated = server != null && !server.isDedicatedServer();
        ctx.record("helm.topology", (integrated ? "integratedServer" : "dedicatedServer")
                + "，mc.bot.* 在本 JVM=" + BotHooks.isAvailable());
        if (!integrated || !BotHooks.isAvailable()) {
            ctx.skip("这条场景驱动的是客户端的真玩家（BotApi.runProcess），只在集成拓扑上跑；"
                    + "专用服上它的覆盖率是零，不是弱。");
        }
        List<ServerPlayer> humans = SceneBody.humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("集成服上没有真玩家 —— 客户端还没进世界，或已经掉线");
        }
        ServerPlayer body = humans.get(0);
        // A dead player is still in the player list until it respawns, and setHealth below does not
        // revive it: the client stays on its death screen and every leg runs against a body that
        // cannot move. Judged from the server's own view (no client-thread wait), and a failure
        // rather than a skip — the run set out to drive this body and it is not there to drive.
        if (body.isDeadOrDying() || body.isRemoved()) {
            ctx.fail("the client's player is dead (on the death screen) — respawn before running a scene on it");
        }
        // A paused integrated server still drains its task queue (this route ran), but ticks
        BotApi bot = BotHooks.impl();

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);

        ServerLevel from = body.serverLevel();
        final double hx = body.getX(), hy = body.getY(), hz = body.getZ();
        final float hYaw = body.getYRot(), hPitch = body.getXRot();
        final GameType hadMode = body.gameMode.getGameModeForPlayer();
        ctx.cleanup(() -> {
            // NOT bot.cancel(): that marshals onto the client thread and WAITS, and this is the
            // thread the client ticks against. runProcess supersedes without waiting.
            try { bot.runProcess(new HoldStill(1)); } catch (RuntimeException ignored) { }
            body.setGameMode(hadMode);
            body.teleportTo(from, hx, hy, hz, java.util.Set.of(), hYaw, hPitch);
        });
        body.setGameMode(GameType.SURVIVAL);
        body.getInventory().clearContent();
        body.setHealth(body.getMaxHealth());
        body.getFoodData().setFoodLevel(20);
        body.teleportTo(ctx.level(), foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5,
                java.util.Set.of(), yaw, 0f);
        ctx.record("helm.body", body.getGameProfile().getName() + " 真玩家，落到 " + foot.toShortString());
        return new ClientHelm(ctx, body, bot);
    }

    /** The server's view of the adopted player. Position lags the client by a packet, never more. */
    public ServerPlayer player() { return player; }

    /** The client half of the driver. */
    public BotApi bot() { return bot; }

    /** Hotbar slots 0.. in order, slot 0 selected. */
    public void hold(ItemStack... stacks) {
        for (int i = 0; i < stacks.length; i++) player.getInventory().items.set(i, stacks[i]);
        player.getInventory().selected = 0;
    }

    /** Let the client catch up with a teleport before anything is judged. */
    public void sync(int ticks, Runnable then) {
        sync(ticks, null, then);
    }

    /**
     * {@link #sync(int, Runnable)} with a per-tick reader. The server's copy of the body trails the
     * client by however many move packets the server thread has not consumed yet — measured at six
     * ticks on this box while a scene was staging — so a leg can report itself over before the
     * server has seen the body land. A watcher that keeps reading through the settle window sees
     * the landing the leg's own watcher missed. {@code tick} continues from {@code from}.
     */
    public void sync(int ticks, int from, TickWatcher watcher, Runnable then) {
        final int[] waited = { 0 };
        ctx.await(() -> {
            if (watcher != null) watcher.tick(from + waited[0]);
            return ++waited[0] >= ticks;
        }).within(ticks + 100).then(then);
    }

    private void sync(int ticks, TickWatcher watcher, Runnable then) {
        sync(ticks, 0, watcher, then);
    }

    /** Something that reads the body on every tick of a leg. */
    public interface TickWatcher { void tick(int tick); }

    /**
     * Run {@code process} on the client's user-task chain and continue when the chain lets go of it
     * or {@code ticks} run out, whichever is first. One evidence row per leg: where the body stopped,
     * how far from {@code goal} (when the leg has a point goal), and what the process said about
     * its ending — never a bare null.
     */
    public void leg(String tag, BotProcess process, BlockPos goal, int ticks, TickWatcher watcher, Runnable then) {
        Map<String, Object> started = bot.runProcess(process);
        ctx.record(tag + ".started", String.valueOf(started));
        final int[] waited = { 0 };
        final boolean[] ended = { false };
        ctx.await(() -> {
            if (watcher != null) watcher.tick(waited[0]);
            Map<String, Object> now = bot.userTaskLeg();
            if (!Boolean.TRUE.equals(now.get("busy"))) {
                ended[0] = true;
                ctx.record(tag + ".chain", "busy=false kind=" + now.get("kind") + " error="
                        + (now.get("error") == null ? "无" : now.get("error")) + "，第 " + waited[0] + " tick");
                return true;
            }
            return ++waited[0] >= ticks;
        }).within(ticks + 100).then(() -> {
            if (!ended[0]) {
                ctx.record(tag + ".chain", "预算 " + ticks + " tick 用完时进程还在跑（busy=true）");
                try { bot.runProcess(new HoldStill(1)); } catch (RuntimeException ignored) { }
            }
            ctx.record(tag + ".leg", where() + "，" + gap(goal) + "；" + slotEnding(process.kind()));
            then.run();
        });
    }

    /** {@link #leg} with an {@link IntentProcess} aimed at {@code goal}. */
    public void goTo(String tag, Goal goal, BlockPos goalFoot, int ticks, TickWatcher watcher, Runnable then) {
        leg(tag, new IntentProcess(new Intent(goal)), goalFoot, ticks, watcher, then);
    }

    /** Where the body is, with the block under its feet, as one string. */
    public String where() {
        BlockPos at = player.blockPosition();
        return String.format(Locale.ROOT, "停在 %.2f,%.2f,%.2f（格 %s，脚下=%s，onGround=%s，inWater=%s）",
                player.getX(), player.getY(), player.getZ(), at.toShortString(),
                ctx.level().getBlockState(at.below()).getBlock(), player.onGround(), player.isInWater());
    }

    private String gap(BlockPos goal) {
        if (goal == null) return "距目标 unavailable/这一腿的目标不是一个点";
        return String.format(Locale.ROOT, "距 %s 水平 %.2f 格、高差 %+.2f",
                goal.toShortString(), flatDistance(goal), player.getY() - goal.getY());
    }

    /** Horizontal distance from the body's centre to the centre of {@code cell}. */
    public double flatDistance(BlockPos cell) {
        return Math.hypot(player.getX() - (cell.getX() + 0.5), player.getZ() - (cell.getZ() + 0.5));
    }

    /** One slot of the client's {@link BotApi#status()}; empty when the slot does not exist. */
    public Map<?, ?> slot(String name) {
        Object s = bot.status().get(name);
        return s instanceof Map<?, ?> m ? m : Map.of();
    }

    private String slotEnding(String slotName) {
        Map<?, ?> s = slot(slotName);
        Object end = s.get("endReason"), err = s.get("lastError"), reached = s.get("goalReached");
        return "end=" + (end == null ? "unavailable/进程被叫停时还在走（endReason 只在终止步写）" : end)
                + " goalReached=" + (reached == null ? "unavailable" : reached)
                + " err=" + (err == null ? "无" : err);
    }
}
