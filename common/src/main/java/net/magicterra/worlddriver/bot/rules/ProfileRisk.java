package net.magicterra.worlddriver.bot.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.bot.rules.constraints.ForbidRegion;
import net.magicterra.worlddriver.bot.rules.constraints.MobCluster;
import net.magicterra.worlddriver.bot.rules.constraints.SightExposure;
import net.magicterra.worlddriver.bot.rules.modifiers.AvoidRegion;
import net.minecraft.core.BlockPos;

/**
 * {@link RiskAt} over a profile's components: every {@link SightExposure} and {@link MobCluster} in it, and the regions
 * in declaration order.
 */
public final class ProfileRisk implements RiskAt {
    private final List<SightExposure> sight = new ArrayList<>();

    private final List<MobCluster> mobs = new ArrayList<>();

    private final List<Map.Entry<String, Region>> regions = new ArrayList<>();

    public ProfileRisk(SearchProfile profile) {
        int avoid = 0;
        int forbid = 0;
        for (CostModifier m : profile.bias()) {
            if (m instanceof SightExposure s) {
                sight.add(s);
            } else if (m instanceof MobCluster c) {
                mobs.add(c);
            } else if (m instanceof AvoidRegion a) {
                regions.add(Map.entry("avoid#" + (++avoid), a.region()));
            }
        }
        for (Constraint c : profile.constraints()) {
            if (c instanceof SightExposure s && !sight.contains(s)) {
                sight.add(s);
            } else if (c instanceof ForbidRegion f) {
                regions.add(Map.entry("forbid#" + (++forbid), f.region()));
            }
        }
    }

    @Override
    public List<ThreatSnapshot.Threat> seeing(BlockPos cell) {
        List<ThreatSnapshot.Threat> out = new ArrayList<>();
        for (SightExposure s : sight) {
            for (ThreatSnapshot.Threat t : s.seeing(cell)) {
                if (!out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    @Override
    public double nearestMob(BlockPos cell) {
        double best = Double.POSITIVE_INFINITY;
        for (MobCluster m : mobs) {
            best = Math.min(best, m.nearest(cell));
        }
        return best;
    }

    @Override
    public List<String> regions(BlockPos cell) {
        List<String> out = new ArrayList<>();
        for (var e : regions) {
            if (e.getValue().contains(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5)) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
