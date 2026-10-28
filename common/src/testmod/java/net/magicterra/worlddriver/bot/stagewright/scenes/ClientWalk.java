package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.Locale;
import java.util.function.Consumer;

import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.bot.stagewright.kit.ClientRig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * One walk of the client's real player, read once per tick through the server's copy of the player: what the
 * {@code wd.client*} walk families judge (where it landed, when it first stood on dry ground, how low it fell).
 *
 * <p>Ticks are the server's own tick count from the moment the walk was ordered, not the number of polls: a poll is a
 * hop to the server and a status asked of the client, and a slow one must not stretch a budget the old scenes counted
 * in server ticks.
 */
final class ClientWalk {

    /**
     * The {@code @ResourceLock} every class driving the client's player holds, so they run one test at a time as the
     * old with-client gate did: they share one player, and their plots, leased all at once, kept the server thread
     * generating chunks past StageWright's 8-second task deadline.
     */
    static final String PLAYER = "worlddriver.clientPlayer";

    /** The server copy of the player at one tick. */
    record Sample(long tick, double x, double y, double z, boolean onGround, boolean inWater, float health) {
        /** Horizontal distance from the player's centre to the centre of {@code cell}. */
        double flat(BlockPos cell) {
            return Math.hypot(x - (cell.getX() + 0.5), z - (cell.getZ() + 0.5));
        }
    }

    private final ClientRig rig;
    private long start;

    ClientWalk(ClientRig rig) {
        this.rig = rig;
    }

    /** The server copy of the player now. */
    static Sample sample(ClientRig rig) {
        return rig.onServer(p -> new Sample(p.server.getTickCount(), p.getX(), p.getY(), p.getZ(), p.onGround(),
                p.isInWater(), p.getHealth()));
    }

    /** How many server ticks after the walk was ordered {@code s} was taken. */
    int tick(Sample s) {
        return (int) (s.tick() - start);
    }

    /**
     * Order a walk to {@code target}, wait until the player lets go of it or {@code legTicks} pass (cancelled then),
     * and keep sampling for {@code settleTicks} more: a walk that ends on the last hop of a step-up is still in the
     * air for a few ticks, and the server's copy trails the client by several more. {@code perTick} sees every sample.
     */
    ClientRig.End go(GotoContracts.Target target, int legTicks, int settleTicks, Consumer<Sample> perTick) {
        start = sample(rig).tick();
        ClientRig.End end = rig.goTo(target, legTicks, t -> perTick.accept(sample(rig)));
        rig.sync(settleTicks, 0, t -> perTick.accept(sample(rig)));
        return end;
    }

    /** How many of {@code item} the server copy of the player carries. */
    static int count(ClientRig rig, Item item) {
        return rig.onServer(p -> p.getInventory().countItem(item));
    }

    /** {@code tick} as a failure message says it: a number, or "never". */
    static String at(int tick) {
        return tick < 0 ? "never" : String.valueOf(tick);
    }

    static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
