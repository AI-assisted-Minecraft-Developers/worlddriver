package net.magicterra.worlddriver.bot.stagewright;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.sim.BodyDriver;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * A {@link LivingBody} addressable as {@code npc:<name>}, and its own driver on the server tick: the
 * NPC counterpart of {@code ServerBodyHost} over a {@code ServerWorldDriver}.
 *
 * <p>Shaped like that driver on purpose: one process, dropped from {@link ServerAvatarManager} when it
 * finishes, registered again by {@link #start}. A difference between the two bodies under the same
 * route is then the body's and not the host's. Like that driver, a finished host stops stepping its
 * body, and a driven mob nobody pumps stands where it stopped.
 */
public final class NpcBodyHost implements BodyHost, BodyDriver {

    public static final String KIND = "npc";

    private final String id;
    private final LivingBody body;
    private final BotState botState = new BotState();
    private volatile BotProcess process;
    private volatile boolean finished = true;
    private LevelWorldView view;

    public NpcBodyHost(String name, LivingBody body) {
        this.id = KIND + ":" + name;
        this.body = body;
    }

    @Override public String id() { return id; }
    @Override public String kind() { return KIND; }
    @Override public LivingEntity entity() { return body.entity(); }
    @Override public BotState botState() { return botState; }

    @Override public boolean busy() {
        return !finished && process != null && ServerAvatarManager.isRegistered(this);
    }

    @Override public void start(BotProcess p) {
        release("superseded");
        p.attach(botState);
        process = p;
        finished = false;
        ServerAvatarManager.register(this);
    }

    /** The way {@code ServerWorldDriver.cancel} ends a task: the process hears it, its slot keeps the
     *  reason and goes inactive. */
    @Override public String cancel(String which) {
        BotProcess p = busy() ? process : null;
        if (p == null || !("all".equals(which) || p.kind().equals(which))) return null;
        release("user-cancel");
        BotState.ProcessSlot slot = botState.slotFor(p.kind());
        if (slot != null) { slot.lastError = "user-cancel"; slot.reset(); }
        finished = true;
        return p.kind();
    }

    @Override public Walker.Step tick() {
        BotProcess p = process;
        if (finished || p == null) return Walker.Step.ARRIVED;
        boolean done = p.tick(body, view(), botState);
        body.step();
        if (done) finished = true;
        return done ? Walker.Step.ARRIVED : Walker.Step.WALKING;
    }

    @Override public boolean finished() { return finished; }

    /** Rebuilt when the mob changes level, for the reason {@code ServerWorldDriver.world()} is. */
    private LevelWorldView view() {
        Level now = body.entity().level();
        if (view == null || view.level() != now) view = LevelWorldView.forBody(now, body.entity());
        return view;
    }

    private void release(String reason) {
        BotProcess prev = process;
        process = null;
        if (prev != null) prev.onCancelled(reason);
    }
}
