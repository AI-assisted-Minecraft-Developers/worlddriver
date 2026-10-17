package net.magicterra.worlddriver.bot.stagewright;

import java.util.Map;

import net.magicterra.worlddriver.api.action.ActionHandle;
import net.magicterra.worlddriver.bot.ActionTickets;
import net.magicterra.worlddriver.bot.behavior.BotState;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.control.Locomotor;
import net.magicterra.worlddriver.bot.behavior.BotProcess;
import net.magicterra.worlddriver.bot.arbitration.HeldProcess;
import net.magicterra.worlddriver.bot.BotTicker;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * A {@link LivingBody} addressable as {@code npc:<name>}, and its own driver on the server tick: the
 * NPC counterpart of {@link net.magicterra.worlddriver.bot.PlayerBot}.
 *
 * <p>Shaped like that driver's task contract on purpose: one process, registered again by
 * {@link #start}. A difference between the two driven entities on the same route then comes from the
 * entity and not from the bot. Unlike that driver it has no reflexes: a finished bot stays on
 * {@link BotTicker}'s list but stops stepping its entity, and a driven mob nobody pumps stands where
 * it stopped.
 *
 * <p>An NPC holds no chunk ticket, so a walk can carry it out of the loaded area. The tick after its
 * chunk unloads, or after it dies, ends the task with the reason {@link #refusal} would have refused
 * the order with, left in the slot.
 */
public final class NpcBot implements Bot {

    public static final String KIND = "npc";

    private final String id;
    private final LivingBody body;
    private final BotState botState = new BotState();
    private final HeldProcess held = new HeldProcess(botState);
    private volatile boolean finished = true;
    private LevelWorldView view;

    public NpcBot(String name, LivingBody body) {
        this.id = KIND + ":" + name;
        this.body = body;
    }

    @Override public String id() { return id; }
    @Override public String kind() { return KIND; }
    @Override public net.magicterra.worlddriver.game.body.Body body() { return body; }
    @Override public LivingEntity entity() { return body.entity(); }
    @Override public BotState botState() { return botState; }

    @Override public boolean busy() {
        return !finished && held.process() != null && BotTicker.isRegistered(this);
    }

    @Override public Map<String, Object> lastProcessEnd() { return held.lastEnd(); }

    @Override public void start(BotProcess p, ActionHandle action) {
        held.start(p, ActionTickets.of(action));
        finished = false;
        BotTicker.register(this);
    }

    @Override public boolean cancelAction(ActionHandle action, String reason) {
        if (!held.cancelAction(ActionTickets.of(action), reason)) return false;
        finished = true;
        return true;
    }

    @Override public void crashed(Throwable t) {
        held.crashed(t);
        finished = true;
    }

    /** The way {@code PlayerBot.cancel} ends a task: the process hears it, and the slots it
     *  switched on keep the reason and go inactive. */
    @Override public String cancel(String which) {
        BotProcess p = busy() ? held.process() : null;
        if (p == null || !("all".equals(which) || p.kind().equals(which))) return null;
        end("user-cancel");
        return p.kind();
    }

    @Override public Locomotor.Status tick() {
        BotProcess p = held.process();
        if (finished || p == null) return Locomotor.Status.REACHED;
        Map<String, Object> gone = refusal();
        if (gone != null) {
            // Dead or unloaded is the task failing, not anyone cancelling it.
            String why = String.valueOf(gone.get("reason"));
            held.cancel(why, h -> h.fail(String.valueOf(gone.get("error"))));
            finished = true;
            return Locomotor.Status.FAILED;
        }
        boolean done = p.tick(body, view(), botState);
        body.step();
        if (done) { held.finished(); finished = true; }
        return done ? Locomotor.Status.REACHED : Locomotor.Status.MOVING;
    }

    @Override public boolean finished() { return finished; }

    /** Rebuilt when the mob changes level, for the reason {@code PlayerBot.world()} is. */
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
