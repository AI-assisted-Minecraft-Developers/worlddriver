package net.magicterra.worlddriver.client.internal;

import net.magicterra.worlddriver.api.methods.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Boss;
import net.magicterra.worlddriver.api.methods.ObserveContracts.BossReading;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Crystal;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Dragon;
import net.magicterra.worlddriver.api.methods.ObserveContracts.DragonHead;
import net.magicterra.worlddriver.api.methods.ObserveContracts.DragonPhase;
import net.magicterra.worlddriver.api.methods.ObserveContracts.Wither;
import net.magicterra.worlddriver.api.methods.ObserveContracts.WitherPhase;
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
import java.util.List;
import java.util.Optional;

import static net.magicterra.worlddriver.client.internal.ClientThread.runOnClient;

/**
 * Phase G — boss-specific sensing behind {@code mc.observe.boss}. Reads the
 * {@link ClientLevel} entity set (client-only, like {@link ClientObserve}), so a
 * dedicated server sees no boss. Picks the nearest {@link EnderDragon} or
 * {@link WitherBoss} within {@code radius}: for a dragon its coarse phase, whether
 * it is perched (the melee window, {@code DragonPhaseInstance.isSitting()}) and its
 * head part (the full-damage aim point); for a wither its spawn invulnerability
 * and whether it is powered (≤50% hp, vanilla phase 2).
 *
 * <p>The End crystals in range are listed whatever the boss — the dragon playbook
 * clears them as a hard gate, and it's harmless otherwise.
 */
public final class ClientBoss {
    private ClientBoss() {}

    public static BossReading observeBoss(int radius) {
        final int r = Math.max(1, Math.min(256, radius));
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer pl = mc.player;
            if (level == null || pl == null) return new BossReading(List.of(), Optional.empty());
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

            List<Crystal> cl = new ArrayList<>();
            for (EndCrystal ec : crystals) {
                cl.add(new Crystal(ec.getId(), new Point(ec.getX(), ec.getY(), ec.getZ()), Math.sqrt(ec.distanceToSqr(px, py, pz)),
                        isCaged(level, ec.blockPosition())));
            }
            if (boss == null) return new BossReading(cl, Optional.empty());

            double health = boss.getHealth(), max = boss.getMaxHealth();
            double pct = max > 0 ? Math.max(0, Math.min(1, health / max)) : 0.0;
            Point pos = new Point(boss.getX(), boss.getY(), boss.getZ());
            double dist = Math.sqrt(bossD);
            Boss b;
            if (boss instanceof EnderDragon dragon) {
                DragonPhaseInstance inst = dragon.getPhaseManager().getCurrentPhase();
                boolean perched = inst.isSitting();
                EnderDragonPart head = dragon.head;
                b = new Dragon(boss.getId(), health, max, pct, pos, dist, dragonPhase(inst.getPhase(), perched), perched,
                        new DragonHead(head.getId(), new Point(head.getX(), head.getY(), head.getZ())), crystals.size());
            } else {
                WitherBoss wither = (WitherBoss) boss;
                int invul = wither.getInvulnerableTicks();
                boolean powered = wither.isPowered();
                b = new Wither(boss.getId(), health, max, pct, pos, dist,
                        invul > 0 ? WitherPhase.SPAWNING : powered ? WitherPhase.SECOND : WitherPhase.FIRST, invul, powered);
            }
            return new BossReading(cl, Optional.of(b));
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
    private static DragonPhase dragonPhase(EnderDragonPhase<?> ph, boolean perched) {
        if (ph == EnderDragonPhase.HOLDING_PATTERN)  return DragonPhase.CIRCLING;
        if (ph == EnderDragonPhase.STRAFE_PLAYER)    return DragonPhase.STRAFING;
        if (ph == EnderDragonPhase.LANDING_APPROACH) return DragonPhase.APPROACH;
        if (ph == EnderDragonPhase.LANDING)          return DragonPhase.LANDING;
        if (ph == EnderDragonPhase.TAKEOFF)          return DragonPhase.TAKEOFF;
        if (ph == EnderDragonPhase.CHARGING_PLAYER)  return DragonPhase.CHARGING;
        if (ph == EnderDragonPhase.DYING)            return DragonPhase.DYING;
        if (ph == EnderDragonPhase.HOVERING)         return DragonPhase.HOVERING;
        return perched ? DragonPhase.PERCH : DragonPhase.UNKNOWN;
    }
}
