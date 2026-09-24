package net.magicterra.worlddriver.bot.stagewright.scene;

import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Common-side sim probe helpers for the dogfood scenes (P1.6 Task 2). The bots came
 * over from the neoforge legacy GameTest files ({@code AgentGameTestServer.probeSwing}
 * / {@code probeHurt}, {@code AgentGameTestSupport.grantWaterEffects}), which no longer
 * exist — the GameTest path was retired in P4-final and these are now the only copies.
 * The type narrowing at the move was the neoforge {@code FakePlayer} to vanilla
 * {@link ServerPlayer} (a FakePlayer IS a ServerPlayer) and the driver parameter to the
 * common {@link ServerWorldDriver}.
 *
 * <p>Every probe here holds the environment constant so it measures only what it names.
 * {@link #grantWaterEffects} does that for the avatar — drowning, burning and starving
 * cannot enter a movement scene's physics; {@link #probeHurt} zeroes i-frames so the
 * previous probe cannot eat the hit; {@link #probeSwing} does BOTH for its target, which
 * it did not have to before arenas ticked entities. When a probe reads zero, suspect this
 * list first: it is far likelier that the environment ate the measurement than that the
 * driver stopped working.
 */
public final class SimProbes {

    private SimProbes() {}

    /** One full-strength swing at a fresh NoAI zombie; returns the health it lost.
     *  <p>Moved from {@code AgentGameTestServer#probeSwing} (P1.6 Task 2);
     *  {@code FakePlayer fp} → {@link ServerPlayer fp}, driver param → common
     *  {@link ServerWorldDriver}.
     *  <p>No longer verbatim: the target is made fire-proof and its i-frames are cleared at
     *  the instant of the swing. Both lines exist to keep the environment OUT of the
     *  measurement — see the comments at each. The legacy body could omit them only because
     *  its arena could not tick an entity, so its zombie could never burn. */
    public static float probeSwing(ServerLevel level, ServerWorldDriver driver, ServerPlayer fp,
                                    ItemStack weapon, int cx, int floorY, int cz) {
        fp.getInventory().clearContent();
        if (!weapon.isEmpty()) { fp.getInventory().setItem(0, weapon); }
        fp.getInventory().selected = 0;
        var z = new net.minecraft.world.entity.monster.Zombie(level);
        z.setPos(cx + 2 + 0.5, floorY + 1, cz + 0.5);
        z.setNoAi(true);
        z.setPersistenceRequired();
        var kbr = z.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        if (kbr != null) kbr.setBaseValue(1.0);
        z.setInvulnerable(false);
        // The target burned without this before StageWright pinned the clock: arenas started ticking
        // entities on 2026-08-05, and a sun-sensitive mob under open sky ignites on a per-tick dice
        // roll (Zombie#aiStep -> isSunBurnTick) — which is exactly why the resulting failure was
        // intermittent. Kept even though the run is now pinned to night, because this is a
        // measurement helper: a caller that legitimately declares Clock.NOON must not silently get a
        // different number out of it. One fire tick refuses the whole measurement: inside
        // i-frames vanilla only lets a hit through when it EXCEEDS lastHurt, and a bare fist's 1.0
        // does not exceed a fire tick's 1.0, so probeSwing returned a flat 0. Fire resistance keeps
        // the burn out of the damage math without touching melee (it is read only by
        // isInvulnerableTo, never by actuallyHurt).
        z.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
        level.addFreshEntity(z);
        for (int i = 0; i < 3; i++) level.tick(() -> true);
        // Full recharge: step() is the only thing that advances the FakePlayer's ticker.
        fp.resetAttackStrengthTicker();
        for (int i = 0; i < 30; i++) driver.avatar().step();
        // Belt to the effect's braces: whatever hurt the target, i-frame residue must not be able
        // to refuse the swing. Zeroing this forces vanilla's else-branch, which overwrites lastHurt
        // instead of comparing against it. Nothing ticks between here and attackEntity, so neither
        // the fire nor the i-frames can come back.
        if (z.invulnerableTime > 0 || !z.isAlive())
            net.magicterra.worlddriver.WorldDriverCommon.LOG.warn(
                    "[probeSwing] target compromised before the swing: weapon={} hp={} fire={} invT={} alive={}",
                    weapon.isEmpty() ? "bare" : weapon.getItem(), z.getHealth(),
                    z.getRemainingFireTicks(), z.invulnerableTime, z.isAlive());
        z.setRemainingFireTicks(0);
        z.invulnerableTime = 0;
        float before = z.getHealth();
        driver.avatar().attackEntity(z);
        float lost = before - z.getHealth();
        z.discard();
        return lost;
    }

    /** A fixed 10-point generic hit, with and without a full set of diamond armor; returns health lost.
     *  <p>Moved verbatim from {@code AgentGameTestServer#probeHurt} (P1.6 Task 2);
     *  {@code FakePlayer fp} → {@link ServerPlayer fp}. */
    public static float probeHurt(ServerPlayer fp, boolean armored) {
        fp.getInventory().clearContent();
        if (armored) {
            fp.getInventory().armor.set(3, new ItemStack(Items.DIAMOND_HELMET));
            fp.getInventory().armor.set(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            fp.getInventory().armor.set(1, new ItemStack(Items.DIAMOND_LEGGINGS));
            fp.getInventory().armor.set(0, new ItemStack(Items.DIAMOND_BOOTS));
        }
        fp.setHealth(20.0f);
        fp.invulnerableTime = 0;                       // no i-frames from a previous probe
        fp.hurt(fp.damageSources().generic(), 10.0f);
        return 20.0f - fp.getHealth();
    }

    /** Infinite non-locomotion protective effects (matches the harness eval player):
     *  water-breathing/resistance/regen/fire-resistance keep baseTick survival
     *  mechanics from skewing the physics — none of these alter movement.
     *  <p>Moved verbatim from {@code AgentGameTestSupport#grantWaterEffects} (P1.6
     *  Task 2); its parameter was already vanilla {@link Player}, so no type change. */
    public static void grantWaterEffects(Player p) {
        p.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, -1, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, -1, 4, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, -1, 4, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
    }
}
