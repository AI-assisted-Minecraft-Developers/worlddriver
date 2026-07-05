package net.magicterra.agent.bot.process;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Generalized entity lookup shared by {@link FollowProcess} and the dynamic
 *  leash re-solve in {@link IntentProcess}: nearest entity matching a name or
 *  entity-type id, by exact display-name match (no colon) or entity-type id
 *  match (contains ':', e.g. "minecraft:armor_stand"). Uses {@code Level}
 *  (EntityGetter), which works on BOTH ClientLevel and ServerLevel — unlike
 *  the client-only {@code entitiesForRendering()}. */
public final class EntityFind {
    private EntityFind() {}

    /** Nearest entity to {@code self} within a 96-block AABB matching
     *  {@code nameOrType}, or {@code null} if none found. */
    public static Entity nearest(Level lvl, Player self, String nameOrType) {
        double bestDist = Double.POSITIVE_INFINITY;
        Entity best = null;
        boolean isType = nameOrType.indexOf(':') >= 0;
        AABB box = self.getBoundingBox().inflate(96.0);
        for (Entity e : lvl.getEntities(self, box, x -> true)) {
            if (e == self) continue;
            if (isType) {
                String t = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                if (!nameOrType.equals(t)) continue;
            } else {
                String n = e.getName().getString();
                if (!nameOrType.equals(n)) continue;
            }
            double d = e.distanceToSqr(self);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }
}
