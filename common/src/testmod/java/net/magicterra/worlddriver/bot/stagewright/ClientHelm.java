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
 * A scene's handle on the client's REAL player: the player the integrated topology exists to test.
 *
 * <p>{@link SceneBody} creates headless bot players and refuses to on an integrated server; {@code JourneyRig}
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
                + ", mc.bot.* available in this JVM=" + BotHooks.isAvailable());
        if (!integrated || !BotHooks.isAvailable()) {
            ctx.skip("This scene drives the client's real player (BotApi.runProcess) and runs only on the "
                    + "integrated topology; on a dedicated server its coverage is zero, not merely weak.");
        }
        List<ServerPlayer> humans = SceneBody.humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("The integrated server has no real player: the client has not joined the world yet, "
                    + "or has disconnected");
        }
        ServerPlayer body = humans.get(0);
        // A dead player is still in the player list until it respawns, and setHealth below does not
        // revive it: the client stays on its death screen and every walk runs against a player that
        // cannot move. Judged from the server's own view (no client-thread wait), and a failure
        // rather than a skip — the run set out to drive this player and it is not there to drive.
        if (body.isDeadOrDying() || body.isRemoved()) {
            ctx.fail("the client's player is dead (on the death screen) — respawn before running a scene on it");
        }
        // A paused integrated server still drains its task queue (this route ran), but ticks
        // nothing: the scene would wait out its whole budget. Vanilla pauses the tick the window
        // loses focus; see -Dworlddriver.pauseOnLostFocus=false for an unattended client.
        if (server.isPaused()) {
            ctx.fail("the game is paused (the pause menu is open, or the window lost focus) — close it, or run the client with -Dworlddriver.pauseOnLostFocus=false");
        }
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
        ctx.record("helm.body", body.getGameProfile().getName() + " (real player), placed at " + foot.toShortString());
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
     * {@link #sync(int, Runnable)} with a per-tick reader. The server's copy of the player trails the
     * client by however many move packets the server thread has not consumed yet — measured at six
     * ticks on this machine while a scene was staging — so a walk can report itself over before the
     * server has seen the player land. A watcher that keeps reading through the settle window sees
     * the landing the walk's own watcher missed. {@code tick} continues from {@code from}.
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

    /** Something that reads the player on every tick of a walk. */
    public interface TickWatcher { void tick(int tick); }

    /**
     * Run {@code process} on the client's user-task chain and continue when the chain lets go of it
     * or {@code ticks} run out, whichever is first. One evidence row per walk: where the player
     * stopped, how far from {@code goal} (when the walk has a point goal), and what the process said about
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
                        + (now.get("error") == null ? "none" : now.get("error")) + ", at tick " + waited[0]);
                return true;
            }
            return ++waited[0] >= ticks;
        }).within(ticks + 100).then(() -> {
            if (!ended[0]) {
                ctx.record(tag + ".chain", "the process was still running when the " + ticks + "-tick budget ran out (busy=true)");
                try { bot.runProcess(new HoldStill(1)); } catch (RuntimeException ignored) { }
            }
            ctx.record(tag + ".leg", where() + ", " + gap(goal) + "; " + slotEnding(process.kind()));
            then.run();
        });
    }

    /** {@link #leg} with an {@link IntentProcess} aimed at {@code goal}. */
    public void goTo(String tag, Goal goal, BlockPos goalFoot, int ticks, TickWatcher watcher, Runnable then) {
        leg(tag, new IntentProcess(new Intent(goal)), goalFoot, ticks, watcher, then);
    }

    /** Where the player is, with the block under its feet, as one string. */
    public String where() {
        BlockPos at = player.blockPosition();
        return String.format(Locale.ROOT, "stopped at %.2f,%.2f,%.2f (block %s, below feet=%s, onGround=%s, inWater=%s)",
                player.getX(), player.getY(), player.getZ(), at.toShortString(),
                ctx.level().getBlockState(at.below()).getBlock(), player.onGround(), player.isInWater());
    }

    private String gap(BlockPos goal) {
        if (goal == null) return "distance to goal unavailable/this process's goal is not a single block";
        return String.format(Locale.ROOT, "to %s: horizontal %.2f blocks, height difference %+.2f",
                goal.toShortString(), flatDistance(goal), player.getY() - goal.getY());
    }

    /** Horizontal distance from the player's centre to the centre of {@code cell}. */
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
        return "end=" + (end == null
                        ? "unavailable/the process was still moving when it was stopped "
                                + "(endReason is written only on the terminal step)"
                        : end)
                + " goalReached=" + (reached == null ? "unavailable" : reached)
                + " err=" + (err == null ? "none" : err);
    }
}
