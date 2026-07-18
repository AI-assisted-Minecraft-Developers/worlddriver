package net.magicterra.agent.bot.testkit.scene;

import net.magicterra.agent.bot.sim.ServerAgentDriver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Common-side sim probe helpers for the dogfood scenes (P1.6 Task 2). The method
 * bodies are moved VERBATIM from the neoforge legacy GameTest files (single source
 * — the scene must drive the identical measurement); only the body type narrows
 * from the neoforge {@code FakePlayer} to vanilla {@link ServerPlayer} (a FakePlayer
 * IS a ServerPlayer) and the driver parameter narrows to the common
 * {@link ServerAgentDriver}.
 *
 * <p>The legacy statics stay in place as one-line delegates onto these helpers so
 * every existing GameTest caller compiles untouched:
 * <ul>
 *   <li>{@code AgentGameTestServer.probeSwing} → {@link #probeSwing};</li>
 *   <li>{@code AgentGameTestServer.probeHurt} → {@link #probeHurt};</li>
 *   <li>{@code AgentGameTestSupport.grantWaterEffects} → {@link #grantWaterEffects}.</li>
 * </ul>
 */
public final class SimProbes {

    private SimProbes() {}

    /** One full-strength swing at a fresh NoAI zombie; returns the health it lost.
     *  <p>Moved verbatim from {@code AgentGameTestServer#probeSwing} (P1.6 Task 2);
     *  {@code FakePlayer fp} → {@link ServerPlayer fp}, driver param → common
     *  {@link ServerAgentDriver}. */
    public static float probeSwing(ServerLevel level, ServerAgentDriver driver, ServerPlayer fp,
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
        level.addFreshEntity(z);
        for (int i = 0; i < 3; i++) level.tick(() -> true);
        // Full recharge: step() is the only thing that advances the FakePlayer's ticker.
        fp.resetAttackStrengthTicker();
        for (int i = 0; i < 30; i++) driver.avatar().step();
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
