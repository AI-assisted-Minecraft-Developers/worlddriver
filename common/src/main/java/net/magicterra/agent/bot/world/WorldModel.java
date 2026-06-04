package net.magicterra.agent.bot.world;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tick derived-facts blackboard. Mutated ONLY on the client tick thread; each
 * {@link #update} publishes an immutable {@link Snapshot} to a volatile field for
 * off-thread readers (mc.client.scene). Makes NO decisions — pure derived facts.
 * See docs/design/04-perception-and-decision-boundary.md.
 */
public final class WorldModel {
    private volatile Snapshot snapshot = Snapshot.absent();
    private HazardField hazard;          // raw grid kept in-process for reflexes
    private int tick;

    public Snapshot snapshot() { return snapshot; }
    public HazardField hazard() { return hazard; }

    public void update(Minecraft mc, WorldView w, Object state) {
        var p = mc.player;
        if (p == null || mc.level == null) { snapshot = Snapshot.absent(); hazard = null; return; }
        tick++;
        BlockPos foot = p.blockPosition();
        int survivable = SurvivalMath.survivableFall(p.getHealth());
        int radius = BotConfig.hazardGridRadius;
        int decimate = Math.max(1, BotConfig.hazardGridDecimateTicks);
        if (hazard == null || tick % decimate == 0 || !foot.equals(hazard.center)) {
            hazard = HazardField.compute(w, foot, radius, survivable, BotConfig.deepWaterMax);
        }
        boolean cornered = SurvivalFacts.cornered(hazard);
        int lethal = SurvivalFacts.lethalCount(hazard);
        long timeOfDay = mc.level.getDayTime() % 24000L;
        String phase = dayPhase(timeOfDay);
        boolean skyExposed = mc.level.canSeeSky(foot.above());
        boolean exposedAtNight = ("NIGHT".equals(phase) || "DUSK".equals(phase)) && skyExposed;
        this.snapshot = new Snapshot(true, foot, p.getHealth(), p.getFoodData().getFoodLevel(),
                phase, skyExposed, exposedAtNight, cornered, lethal, AsciiMapRenderer.rows(hazard));
    }

    static String dayPhase(long t) {
        if (t < 12000) return "DAY";
        if (t < 13800) return "DUSK";
        if (t < 22200) return "NIGHT";
        return "DAWN";
    }

    /** Immutable, off-thread-safe view. */
    public record Snapshot(boolean present, BlockPos pos, float health, int food,
                           String dayPhase, boolean skyExposed, boolean exposedAtNight,
                           boolean cornered, int lethalCount, List<String> rows) {
        public static Snapshot absent() {
            return new Snapshot(false, BlockPos.ZERO, 0, 0, "DAY", false, false, false, 0, List.of());
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("present", present);
            if (!present) return m;
            m.put("pos", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
            m.put("health", health);
            m.put("food", food);
            m.put("dayPhase", dayPhase);
            m.put("skyExposed", skyExposed);
            m.put("exposedAtNight", exposedAtNight);
            m.put("cornered", cornered);
            m.put("lethalCount", lethalCount);
            m.put("rows", rows);
            return m;
        }
    }
}
