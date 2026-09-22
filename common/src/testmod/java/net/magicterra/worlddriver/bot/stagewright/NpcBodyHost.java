package net.magicterra.worlddriver.bot.stagewright;

import java.util.Map;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.body.BodyHost;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.process.BotProcess;
import net.magicterra.worlddriver.bot.scheduler.HeldProcess;
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
 *
 * <p>An NPC holds no chunk ticket, so a walk can carry it out of the loaded area. The tick after its
 * chunk unloads, or after it dies, ends the task with the reason {@link #refusal} would have refused
 * the order with, left in the slot.
 */
public final class NpcBodyHost implements BodyHost, BodyDriver {

    public static final String KIND = "npc";

    private final String id;
    private final LivingBody body;
    private final BotState botState = new BotState();
    private final HeldProcess held = new HeldProcess(botState);
    private volatile boolean finished = true;
    private LevelWorldView view;

    public NpcBodyHost(String name, LivingBody body) {
        this.id = KIND + ":" + name;
        this.body = body;
    }

    @Override public String id() { return id; }
    @Override public String kind() { return KIND; }
    @Override public net.magicterra.worlddriver.bot.body.Body body() { return body; }
    @Override public LivingEntity entity() { return body.entity(); }
    @Override public BotState botState() { return botState; }

    @Override public boolean busy() {
        return !finished && held.process() != null && ServerAvatarManager.isRegistered(this);
    }

    @Override public void start(BotProcess p) {
        held.start(p);
        finished = false;
        ServerAvatarManager.register(this);
    }

    /** The way {@code ServerWorldDriver.cancel} ends a task: the process hears it, and the slots it
     *  switched on keep the reason and go inactive. */
    @Override public String cancel(String which) {
        BotProcess p = busy() ? held.process() : null;
        if (p == null || !("all".equals(which) || p.kind().equals(which))) return null;
        end("user-cancel");
        return p.kind();
    }

    @Override public Walker.Step tick() {
        BotProcess p = held.process();
        if (finished || p == null) return Walker.Step.ARRIVED;
        Map<String, Object> gone = refusal();
        if (gone != null) {
            end(String.valueOf(gone.get("reason")));
            return Walker.Step.FAILED;
        }
        boolean done = p.tick(body, view(), botState);
        body.step();
        if (done) { held.finished(); finished = true; }
        return done ? Walker.Step.ARRIVED : Walker.Step.WALKING;
    }

    @Override public boolean finished() { return finished; }

    /** Rebuilt when the mob changes level, for the reason {@code ServerWorldDriver.world()} is. */
    private LevelWorldView view() {
        Level now = body.entity().level();
        if (view == null || view.level() != now) view = LevelWorldView.forBody(now, body.entity());
        return view;
    }

    private void end(String reason) {
        held.cancel(reason);
        finished = true;
    }
}
