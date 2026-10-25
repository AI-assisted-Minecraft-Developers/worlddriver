package net.magicterra.worlddriver.bot.stagewright;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.api.methods.bot.BotTaskApi;
import net.magicterra.worlddriver.api.methods.bot.BotTaskStubApi;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * A scene's handle on the client's REAL player: the player the integrated topology exists to test.
 *
 * <p>{@link SceneBot} creates headless bot players and refuses to on an integrated server; {@code JourneyRig}
 * adopts the real player but only for the ladder. This is the third shape: an arena scene that stages
 * terrain, puts the real player in it, and drives it through the {@code mc.bot.*} verbs naming
 * {@link #SELF} — so the navigator under test is the client navigator, on the client thread,
 * with vanilla client physics and every reflex chain armed, which is the thing the headless scenes
 * cannot reach.
 *
 * <p>Everything here runs on the server thread. On an integrated server the client is this JVM; on a dedicated
 * one it is the joined client, and self's calls go to its driver over RPC ({@link ClientRegistry}), with the
 * settings pinned on both sides. Either way self's calls answer later, so a walk is started and waited out
 * through {@link Legs}; readings come back through the world (the {@link ServerPlayer} the client keeps sending
 * movement packets for) and through {@code mc.bot.status}, asked again each time the last answer has landed.
 * Nothing here waits on the client thread.
 *
 * <p>A dedicated server that names no joined client's driver {@linkplain SceneContext#skip skips} the scene.
 */
public final class ClientHelm {

    private final SceneContext ctx;
    private final ServerPlayer player;

    private ClientHelm(SceneContext ctx, ServerPlayer player) {
        this.ctx = ctx;
        this.player = player;
    }

    /**
     * Put the real player at {@code foot} (feet in the centre of that cell), in survival with an
     * empty inventory, with the settings pinned to the test baseline for the scene's duration.
     *
     * <p>Skips unless there is a client to drive — this JVM hosting its own world, or a dedicated server naming its
     * joined client's driver — and a person in the player list. The player is teleported back where they were
     * when the scene resolves.
     */
    public static ClientHelm adopt(SceneContext ctx, BlockPos foot, float yaw) {
        MinecraftServer server = ctx.server();
        boolean integrated = server != null && !server.isDedicatedServer();
        ctx.record("helm.topology", integrated ? "integratedServer" : "dedicatedServer");
        if (!integrated && !ClientRegistry.remote()) {
            ctx.skip("This scene drives the client's real player (bot self): on a dedicated server that needs a "
                    + "joined client whose driver it can reach (" + ClientRegistry.PORT_FILE + "), and this run names none.");
        }
        List<ServerPlayer> humans = SceneBot.humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("The server has no real player: the client has not joined the world yet, or has disconnected");
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
        Harness.pinSelf(ctx);

        ServerLevel from = body.serverLevel();
        final double hx = body.getX(), hy = body.getY(), hz = body.getZ();
        final float hYaw = body.getYRot(), hPitch = body.getXRot();
        final GameType hadMode = body.gameMode.getGameModeForPlayer();
        ctx.cleanup(() -> {
            // Not waited for: the answer comes from the client thread, which ticks against this one.
            Legs.cancel(SELF);
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
        return new ClientHelm(ctx, body);
    }

    /** The server's view of the adopted player. Position lags the client by a packet, never more. */
    public ServerPlayer player() { return player; }

    /** What every call on the real player names. */
    public static final String SELF = "self";

    /** {@link #SELF}. */
    public String ref() { return SELF; }

    /** The bot verbs, through the registry that serves self: this JVM's, or the joined client's. */
    public static BotTaskStubApi tasks() { return BotTaskApi.of(ClientRegistry.self()); }

    /**
     * Start {@code order} on the real player and continue when it lets go of the task or {@code ticks} run out,
     * whichever is first. One evidence row per walk: where the player stopped, how far from {@code goal} (when
     * the walk has a point goal), and how the task ended.
     */
    public <R> void leg(String tag, CompletionStage<Responses.ActionResponse<R>> order, BlockPos goal, int ticks,
                        Legs.Watcher watcher, Consumer<Legs.End> then) {
        Legs.run(ctx, tag, SELF, order, ticks, watcher, end -> {
            ctx.record(tag + ".chain", end.ended() ? "idle at tick " + end.ticks()
                    : "the task was still running when the " + ticks + "-tick budget ran out; cancelled");
            ControlContracts.BotStatus s = end.status();
            ctx.record(tag + ".leg", where() + ", " + gap(goal) + "; "
                    + (s == null ? "no status landed" : "last ending " + s.lastProcessEnd().map(String::valueOf).orElse("none")));
            then.accept(end);
        });
    }

    /**
     * {@link #leg} walking to {@code target}, whose foot cell is {@code goalFoot}, with one more row: what the goto
     * slot said about the ending — never a bare null.
     */
    public void goTo(String tag, GotoContracts.Target target, BlockPos goalFoot, int ticks, Legs.Watcher watcher,
                     Consumer<Legs.End> then) {
        leg(tag, tasks().goTo(new GotoContracts.Params(SELF, Optional.of(target), false, Optional.empty(),
                Optional.empty(), false, Optional.empty(), 0)), goalFoot, ticks, watcher, end -> {
            ctx.record(tag + ".slot", end.slot(ControlContracts.Slots::goTo).map(ClientHelm::slotEnding)
                    .orElse("unavailable/no status landed"));
            then.accept(end);
        });
    }

    private static String slotEnding(ControlContracts.Slot s) {
        return "end=" + s.endReason().orElse("unavailable/the walk was still moving when it was stopped "
                        + "(endReason is written only on the terminal step)")
                + " goalReached=" + s.goalReached().map(String::valueOf).orElse("unavailable")
                + " err=" + s.lastError().orElse("none");
    }

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
    public interface TickWatcher extends Legs.Watcher {}

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

}
