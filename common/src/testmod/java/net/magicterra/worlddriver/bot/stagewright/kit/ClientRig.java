package net.magicterra.worlddriver.bot.stagewright.kit;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.api.methods.bot.BotTaskBlockingApi;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.control.ControlApi;
import net.magicterra.worlddriver.api.methods.control.ControlBlockingApi;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.debug.DebugBlockingApi;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.test.TestHarnessBlockingApi;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * The real client's player as the {@code wd.client*} families drive it: staged in the test's plot, driven through the
 * <b>client's</b> registry as {@link #SELF} — the client navigator, on the client thread, with vanilla client physics —
 * and read back through the server's copy of the player. Replaces the old {@code ClientHelm}, {@code ClientRegistry},
 * {@code SceneCalls} and {@code Legs}: {@link Client#registry()} is the joined client's registry, and the waits are
 * plain blocking loops on the test thread.
 *
 * <pre>{@code
 * @Test void climbsOut(Game game, Client client, Player player, @Plot.Centered @Plot.Size(32) Plot plot) {
 *     ClientRig rig = ClientRig.adopt(game, client, player, plot.at(0, 1, 0), 0f);   // pins both JVMs' settings
 *     rig.settings().set("navigatorDebug", false);
 *     rig.sync(20);
 *     ClientRig.End end = rig.goTo(Targets.block(plot.at(6, 1, 0)), 400, tick -> minY[0] = Math.min(minY[0], rig.y()));
 *     game.check(end.ended(), "the walk ended within 400 ticks: " + rig.where());
 * }
 * }</pre>
 *
 * <p>The class carries {@code @Requires(CLIENT)} and runs in the client scene file. {@link Player} is shared by every
 * test of the run and keeps whatever a test leaves on it; {@link #adopt} saves its game mode and position and puts them
 * back when the test ends (respawning it first if the test left it dead), and its {@link Settings} pin makes client
 * tests run one at a time.
 *
 * <p>Methods named for the client ({@link #tasks}, {@link #control}, {@link #debug}) answer from the client: never call
 * them inside {@code game.runOnServer}, where the server thread would wait on the network. Readings of the server's
 * copy ({@link #onServer}, {@link #where}) work from either thread.
 */
public final class ClientRig {

    /** What every call on the real player names in the client's registry. */
    public static final String SELF = "self";

    private final Game game;
    private final Client client;
    private final Player player;
    private final Settings settings;

    private ClientRig(Game game, Client client, Player player, Settings settings) {
        this.game = game;
        this.client = client;
        this.player = player;
        this.settings = settings;
    }

    /**
     * Put the real player standing in the centre of {@code feet} (overworld), facing {@code yaw}, in survival with an
     * empty inventory and full health and hunger, with the settings pinned to the test baseline in both JVMs. Fails
     * when the player is dead or the server paused: the run set out to drive this player and it is not there to drive.
     * Only from the test's own thread.
     */
    public static ClientRig adopt(Game game, Client client, Player player, BlockPos feet, float yaw) {
        return adopt(game, client, player, Level.OVERWORLD, feet, yaw);
    }

    /** {@link #adopt(Game, Client, Player, BlockPos, float)} in {@code dimension}. */
    public static ClientRig adopt(
            Game game, Client client, Player player, ResourceKey<Level> dimension, BlockPos feet, float yaw) {
        Boolean dead = player.onServer(p -> p.isDeadOrDying() || p.isRemoved());
        if (dead) fail("the client's player is dead (on the death screen); respawn it before driving it");
        if (game.<Boolean>onServer(s -> s.isPaused())) {
            fail("the game is paused (the pause menu is open, or the window lost focus)");
        }
        Settings settings = Settings.pin(game).mirrorTo(client);
        ClientRig rig = new ClientRig(game, client, player, settings);

        record Was(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch, GameType mode) {}
        Was was = player.onServer(p -> new Was(
                p.level().dimension(),
                p.getX(),
                p.getY(),
                p.getZ(),
                p.getYRot(),
                p.getXRot(),
                p.gameMode.getGameModeForPlayer()));
        game.afterTest(server -> {
            // Not waited for: the answer comes from the client, which this server thread must not wait on.
            ControlApi.of(client.registry())
                    .cancel(new ControlContracts.CancelParams(Optional.empty(), Optional.of(SELF), Optional.empty()));
            // A respawn replaces the ServerPlayer; the handle finds the player by name, so this is the one there now.
            player.runOnServer(p -> {
                if (p.isDeadOrDying()) {
                    p.connection.handleClientCommand(
                            new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                }
            });
            player.runOnServer(p -> {
                p.setGameMode(was.mode());
                ServerLevel level = server.getLevel(was.dimension());
                if (level != null) p.teleportTo(level, was.x(), was.y(), was.z(), Set.of(), was.yaw(), was.pitch());
            });
        });
        player.runOnServer(p -> {
            p.setGameMode(GameType.SURVIVAL);
            p.getInventory().clearContent();
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            ServerLevel level = p.server.getLevel(dimension);
            p.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, Set.of(), yaw, 0f);
        });
        return rig;
    }

    // ---------------------------------------------------------------- handles

    /** The settings pin {@link #adopt} took, mirrored to the client: write through it, both JVMs follow. */
    public Settings settings() {
        return settings;
    }

    public Game game() {
        return game;
    }

    public Client client() {
        return client;
    }

    public Player player() {
        return player;
    }

    /** The task verbs on the client's registry; name {@link #SELF}. */
    public BotTaskBlockingApi tasks() {
        return client.api(BotTaskBlockingApi::of);
    }

    /** Status and cancel on the client's registry. */
    public ControlBlockingApi control() {
        return client.api(ControlBlockingApi::of);
    }

    /** The client navigator's readings ({@code mc.debug.*} on the client's registry). */
    public DebugBlockingApi debug() {
        return client.api(DebugBlockingApi::of);
    }

    /** Run {@code work} on the server thread with the server's copy of the player. */
    public <T> T onServer(java.util.function.Function<ServerPlayer, T> work) {
        return player.onServer(work);
    }

    // ---------------------------------------------------------------- driving

    /** Start a walk to {@code target} on the client's player; a refusal fails the test. */
    public Responses.ActionSnapshot<GotoContracts.Result> startGoTo(GotoContracts.Target target) {
        return Calls.started(
                "goto " + target,
                tasks().goTo(new GotoContracts.Params(
                        SELF,
                        Optional.of(target),
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        false,
                        Optional.empty(),
                        0)));
    }

    /** {@link #startGoTo}, then {@link #awaitIdle} with {@code watcher}. */
    public End goTo(GotoContracts.Target target, int ticks, Watcher watcher) {
        startGoTo(target);
        return awaitIdle(ticks, watcher);
    }

    /**
     * Wait until the client's player lets go of its task or {@code ticks} server ticks ran out, whichever is first,
     * calling {@code watcher} (may be null) once per tick on the test thread. A task still running at the end is
     * cancelled. The status is asked of the client each tick, so it trails the client by a tick or so; the server's
     * copy of the player trails it by a packet — read it through {@link #onServer} in the watcher.
     */
    public End awaitIdle(int ticks, Watcher watcher) {
        ControlContracts.BotStatus last = null;
        int waited = 0;
        while (true) {
            if (watcher != null) watcher.tick(waited);
            last = status();
            if (!last.busy()) return new End(true, waited, last);
            if (waited >= ticks) {
                cancel();
                return new End(false, waited, last);
            }
            game.ticks(1);
            waited++;
        }
    }

    /** Let {@code ticks} server ticks pass, so the client catches up with a teleport before anything is judged. */
    public void sync(int ticks) {
        sync(ticks, 0, null);
    }

    /** {@link #sync(int)} calling {@code watcher} each tick, its tick numbers continuing from {@code from}. */
    public void sync(int ticks, int from, Watcher watcher) {
        for (int i = 0; i < ticks; i++) {
            if (watcher != null) watcher.tick(from + i);
            game.ticks(1);
        }
    }

    /** The client player's own status, asked of the client. */
    public ControlContracts.BotStatus status() {
        Responses.ImmediateResponse r = control().status(ControlContracts.StatusParams.self());
        if (!(r instanceof Responses.Status s) || s.status().isEmpty()) return fail("mc.bot.status self answered " + r);
        return s.status().get();
    }

    public boolean busy() {
        return status().busy();
    }

    /** Stop whatever the client's player is doing, and wait for the client to say so. */
    public void cancel() {
        control().cancel(new ControlContracts.CancelParams(Optional.empty(), Optional.of(SELF), Optional.empty()));
    }

    /** Hold or let go of the client player's keys, pressed on the client thread; start from {@code Press.of(SELF)}. */
    public TestHarnessContracts.Pressed press(TestHarnessContracts.Press press) {
        return client.api(TestHarnessBlockingApi::of).press(press);
    }

    /** Hotbar slots 0.. in order, slot 0 selected, on the server's copy (the client follows). */
    public void hold(ItemStack... stacks) {
        player.runOnServer(p -> {
            for (int i = 0; i < stacks.length; i++) p.getInventory().items.set(i, stacks[i]);
            p.getInventory().selected = 0;
            p.containerMenu.broadcastChanges();
        });
    }

    // ---------------------------------------------------------------- readings

    /** The server copy's feet height. */
    public double y() {
        return player.onServer(ServerPlayer::getY);
    }

    /** Where the player is, with the block under its feet, as one string for a failure message. */
    public String where() {
        return player.onServer(p -> {
            BlockPos at = p.blockPosition();
            return String.format(
                    Locale.ROOT,
                    "at %.2f,%.2f,%.2f (block %s, below feet=%s, onGround=%s, inWater=%s)",
                    p.getX(),
                    p.getY(),
                    p.getZ(),
                    at.toShortString(),
                    p.level().getBlockState(at.below()).getBlock(),
                    p.onGround(),
                    p.isInWater());
        });
    }

    /** Horizontal distance from the player's centre to the centre of {@code cell}. */
    public double flatDistance(BlockPos cell) {
        return player.onServer(p -> Math.hypot(p.getX() - (cell.getX() + 0.5), p.getZ() - (cell.getZ() + 0.5)));
    }

    /** Something that reads the player once per tick of a wait, on the test thread. */
    @FunctionalInterface
    public interface Watcher {
        void tick(int tick);
    }

    /**
     * How a wait ended. {@code ended}: the player let go of the task within the budget; {@code ticks}: how many it
     * waited; {@code status}: the last status the client gave.
     */
    public record End(boolean ended, int ticks, ControlContracts.BotStatus status) {
        /** The goto slot of the last status. */
        public ControlContracts.Slot gotoSlot() {
            return status.slots().goTo();
        }
    }
}
