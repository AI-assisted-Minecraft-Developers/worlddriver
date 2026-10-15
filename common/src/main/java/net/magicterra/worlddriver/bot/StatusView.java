package net.magicterra.worlddriver.bot;

import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.minecraft.core.BlockPos;
import net.magicterra.worlddriver.bot.behavior.BotState;

/** {@code mc.bot.status}'s view of a {@link BotState}, the same for this client's player and any other bot. */
public final class StatusView {
    private StatusView() {}

    public static ControlContracts.Slots slots(BotState s) {
        return new ControlContracts.Slots(slot(s.mc_goto), slot(s.mine), slot(s.builder), slot(s.follow), slot(s.explore),
                slot(s.runAway), slot(s.retreat), slot(s.look), slot(s.elytra), slot(s.craft), slot(s.smelt), slot(s.escape),
                slot(s.bunker), slot(s.combat), slot(s.plan));
    }

    static ControlContracts.Slot slot(BotState.ProcessSlot p) {
        BlockPos target = p.target;
        long started = p.startedAtMs;
        double dist = p.finalDist;
        return new ControlContracts.Slot(p.active, Optional.ofNullable(p.goal),
                target == null ? Optional.empty() : Optional.of(Positions.position(target)),
                p.pathLen, p.pathStep, started > 0 ? Optional.of(started) : Optional.empty(), Optional.ofNullable(p.lastError),
                Optional.ofNullable(p.goalReached), Optional.ofNullable(p.endReason),
                dist >= 0 ? Optional.of(dist) : Optional.empty());
    }

    /** A {@code {kind, error}} ending as the executors record it, or empty when nothing has ended yet. */
    public static Optional<ControlContracts.ProcessEnd> end(Map<String, Object> lastEnd) {
        if (lastEnd == null || !(lastEnd.get("kind") instanceof String kind)) return Optional.empty();
        return Optional.of(new ControlContracts.ProcessEnd(kind,
                lastEnd.get("error") instanceof String e ? Optional.of(e) : Optional.empty()));
    }
}
