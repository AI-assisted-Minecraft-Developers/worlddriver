package net.magicterra.worlddriver.bot.behavior;

import net.magicterra.worlddriver.game.observe.EntitySelector;
import net.magicterra.worlddriver.game.observe.RegistryIds;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Generalized entity lookup shared by {@link FollowProcess} and the dynamic leash re-solve in {@link IntentProcess}:
 * the nearest entity an {@link EntitySelector} names, an id being {@code Entity.getId()} (what
 * {@code route.leash.entity} takes as a number). Uses {@code Level} (EntityGetter), which works on BOTH ClientLevel and
 * ServerLevel — unlike the client-only {@code entitiesForRendering()}.
 */
public final class EntityFind {
    private EntityFind() {}

    /**
     * Nearest entity to {@code self} within a 96-block AABB matching {@code selector}, or {@code null} if none found.
     */
    public static Entity nearest(Level lvl, LivingEntity self, String selector) {
        if (EntitySelector.isId(selector)) {
            Integer id = EntitySelector.id(selector);
            Entity byId = id == null ? null : lvl.getEntity(id);
            return byId == null || byId == self ? null : byId;
        }
        double bestDist = Double.POSITIVE_INFINITY;
        Entity best = null;
        AABB box = self.getBoundingBox().inflate(96.0);
        for (Entity e : lvl.getEntities(self, box, x -> true)) {
            if (e == self
                    || !EntitySelector.matches(selector, e.getId(), e.getName().getString(), RegistryIds.typeId(e))) {
                continue;
            }
            double d = e.distanceToSqr(self);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }
}
