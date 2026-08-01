package net.magicterra.worlddriver.client.internal;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.magicterra.worlddriver.client.internal.ClientThread.runOnClient;

/**
 * Phase G — boss-specific sensing behind {@code mc.observe.boss}. Reads the
 * {@link ClientLevel} entity set (client-only, like {@link ClientObserve}), so
 * it returns {@code present:false} on a dedicated server. Picks the nearest
 * {@link EnderDragon} or {@link WitherBoss} within {@code radius} and projects a
 * JSON-friendly snapshot the Rhino playbooks key on:
 *
 * <ul>
 *   <li>common: {@code type, present, id, health, maxHealth, healthPct, pos,
 *       distance}</li>
 *   <li>dragon: {@code phase} (coarse label), {@code perched} (the perch melee
 *       window = {@code DragonPhaseInstance.isSitting()}), {@code head:{x,y,z,id}}
 *       (the head part — full-damage aim point), {@code crystalsAlive}</li>
 *   <li>wither: {@code invulTicks} (>0 = spawn animation, about to explode),
 *       {@code powered} (≤50% hp, vanilla phase 2), {@code phase} (1/2/"spawning")</li>
 * </ul>
 *
 * <p>{@code crystals} is always present (a list of {@code {id, pos, distance,
 * caged}}) regardless of boss type — the dragon playbook clears them as a hard
 * gate, and it's harmless otherwise.
 */
public final class ClientBoss {
    private ClientBoss() {}

    public static Map<String, Object> observeBoss(int radius) {
        final int r = Math.max(1, Math.min(256, radius));
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer pl = mc.player;
            if (level == null || pl == null) {
                return Map.of("present", false, "crystals", List.of());
            }
            double px = pl.getX(), py = pl.getY(), pz = pl.getZ();
            double r2 = (double) r * r;
            LivingEntity boss = null;
            double bossD = Double.MAX_VALUE;
            List<EndCrystal> crystals = new ArrayList<>();
            for (Entity e : level.entitiesForRendering()) {
                double d = e.distanceToSqr(px, py, pz);
                if (d > r2) continue;
                if (e instanceof EndCrystal ec) {
                    crystals.add(ec);
                } else if ((e instanceof EnderDragon || e instanceof WitherBoss) && e instanceof LivingEntity le) {
                    if (d < bossD) { bossD = d; boss = le; }
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            List<Object> cl = new ArrayList<>();
            for (EndCrystal ec : crystals) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", ec.getId());
                cm.put("pos", posMap(ec.getX(), ec.getY(), ec.getZ()));
                cm.put("distance", Math.sqrt(ec.distanceToSqr(px, py, pz)));
                cm.put("caged", isCaged(level, ec.blockPosition()));
                cl.add(cm);
            }
            out.put("crystals", cl);

            if (boss == null) {
                out.put("present", false);
                return out;
            }
            out.put("present", true);
            out.put("id", boss.getId());
            out.put("health", (double) boss.getHealth());
            out.put("maxHealth", (double) boss.getMaxHealth());
            out.put("healthPct", boss.getMaxHealth() > 0 ? (double) boss.getHealth() / boss.getMaxHealth() : 0.0);
            out.put("pos", posMap(boss.getX(), boss.getY(), boss.getZ()));
            out.put("distance", Math.sqrt(bossD));

            if (boss instanceof EnderDragon dragon) {
                out.put("type", "ender_dragon");
                DragonPhaseInstance inst = dragon.getPhaseManager().getCurrentPhase();
                boolean perched = inst.isSitting();
                out.put("phase", dragonPhaseName(inst.getPhase(), perched));
                out.put("perched", perched);
                EnderDragonPart head = dragon.head;
                Map<String, Object> hm = posMap(head.getX(), head.getY(), head.getZ());
                hm.put("id", head.getId());
                out.put("head", hm);
                out.put("crystalsAlive", crystals.size());
            } else if (boss instanceof WitherBoss wither) {
                out.put("type", "wither");
                int invul = wither.getInvulnerableTicks();
                boolean powered = wither.isPowered();
                out.put("invulTicks", invul);
                out.put("powered", powered);
                out.put("phase", invul > 0 ? "spawning" : (powered ? 2 : 1));
            }
            return out;
        });
    }

    /** The taller End pillars cap their crystal in an iron-bar cage; the bot must
     *  climb / break in rather than shoot it from the ground. Cheap heuristic:
     *  any iron bars in the small box around the crystal block. */
    private static boolean isCaged(ClientLevel level, BlockPos c) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = 0; dy <= 3; dy++)
                for (int dz = -2; dz <= 2; dz++)
                    if (level.getBlockState(c.offset(dx, dy, dz)).is(Blocks.IRON_BARS)) return true;
        return false;
    }

    /** Coarse, playbook-friendly phase label. The SITTING_* phases all collapse to
     *  "perch" (the melee window); everything else maps to its flight behaviour. */
    private static String dragonPhaseName(EnderDragonPhase<?> ph, boolean perched) {
        if (ph == EnderDragonPhase.HOLDING_PATTERN)  return "circling";
        if (ph == EnderDragonPhase.STRAFE_PLAYER)    return "strafing";
        if (ph == EnderDragonPhase.LANDING_APPROACH) return "approach";
        if (ph == EnderDragonPhase.LANDING)          return "landing";
        if (ph == EnderDragonPhase.TAKEOFF)          return "takeoff";
        if (ph == EnderDragonPhase.CHARGING_PLAYER)  return "charging";
        if (ph == EnderDragonPhase.DYING)            return "dying";
        if (ph == EnderDragonPhase.HOVERING)         return "hovering";
        return perched ? "perch" : "unknown";
    }

    private static Map<String, Object> posMap(double x, double y, double z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x); m.put("y", y); m.put("z", z);
        return m;
    }
}
