package net.magicterra.worlddriver.bot;

import java.util.Map;

import net.magicterra.worlddriver.bot.behavior.RouteEvents;
import net.magicterra.worlddriver.bot.world.ThreatScanner;
import net.magicterra.worlddriver.bot.world.WorldModel;
import net.minecraft.core.BlockPos;

/**
 * One-shot events on the rising edges of what a bot perceives: {@code duskExposed} and
 * {@code cornered} from the world model, {@code threat.appeared} from the threat scan. Each fires
 * once per transition, so the agent learns of them without polling {@code mc.client.scene}.
 */
final class PerceptionEvents {

    private final RouteEvents.Emitter events;
    private boolean prevExposedAtNight;
    private boolean prevCornered;
    private int lastThreatId = -1;

    PerceptionEvents(RouteEvents.Emitter events) {
        this.events = events;
    }

    /** After the world model's update for this tick. */
    void scene(WorldModel.Snapshot snap) {
        if (!snap.present()) {
            prevExposedAtNight = false;
            prevCornered = false;
            return;
        }
        BlockPos at = snap.pos();
        Map<String, Object> pos = Map.of("pos", Map.of("x", at.getX(), "y", at.getY(), "z", at.getZ()));
        if (snap.exposedAtNight() && !prevExposedAtNight) events.emit("duskExposed", at, pos);
        if (snap.cornered() && !prevCornered) events.emit("cornered", at, pos);
        prevExposedAtNight = snap.exposedAtNight();
        prevCornered = snap.cornered();
    }

    /** After the threat scan's refresh for this tick. */
    void threats(ThreatScanner.Scan scan) {
        ThreatScanner.Threat top = scan.top();
        int topId = top != null ? top.id() : -1;
        if (topId != -1 && topId != lastThreatId) {
            events.emit("threat.appeared", top.entity().blockPosition(), Map.of(
                    "type", top.type(), "id", topId,
                    "distance", top.distance(), "score", top.score()));
        }
        lastThreatId = topId;
    }
}
