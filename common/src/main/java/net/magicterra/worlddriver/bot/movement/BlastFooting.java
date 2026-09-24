package net.magicterra.worlddriver.bot.movement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.magicterra.worlddriver.bot.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>Do not swing at something that explodes while standing on a block its blast will take.</b>
 *
 * <h2>The run this exists for</h2>
 *
 * Rung 20 of the journey ladder (2026-08-18) walked onto a caged end spike's LID to get in reach
 * (end position {@code -33,86,23}, block underfoot {@code minecraft:iron_bars}, {@code end=arrived}),
 * hit the crystal once, and the next rung started with {@code minecraft:air} underfoot and ended at a
 * lowest y of {@code -5220}.
 * Rungs 5–9 of that run are all free fall. One hit, nine rungs.
 *
 * <p>The mechanism is vanilla and has no gamerule in it: {@code EndCrystal.hurt} answers every hit
 * with {@code level.explode(this, …, 6.0F, false, ExplosionInteraction.BLOCK)}, and {@code BLOCK}
 * maps onto {@code getDestroyType(RULE_BLOCK_EXPLOSION_DROP_DECAY)} — {@code DESTROY} or
 * {@code DESTROY_WITH_DECAY}, <b>never</b> {@code KEEP}. Unlike a creeper it is not gated on
 * {@code mobGriefing}. Iron bars have explosion resistance 6.0 and obsidian 1200, so the blast eats
 * the cage and leaves the pillar — and a bot that climbed onto the cage is standing on the half
 * that goes.
 *
 * <h2>The threshold, and why it is NOT {@code resistance < power}</h2>
 *
 * Writing the criterion the obvious way — {@code getExplosionResistance() < power} — makes this
 * whole guard a <b>silent no-op</b> for the exact case it was written for: iron bars are 6.0, the
 * crystal's blast is 6.0, and {@code 6.0 < 6.0} is false, so the bars pass. Nothing would refuse,
 * nothing would log, and the bot would still be dropped into the void.
 *
 * <p>The real rule is in {@code Explosion.explode()}. Each of vanilla's rays starts at
 * {@code f = power * (0.7F + random.nextFloat() * 0.6F)}, i.e. up to {@code 1.3 * power}. At every
 * 0.3-block step it subtracts {@code (resistance + 0.3F) * 0.3F} for the cell it is in and then
 * destroys that cell if {@code f} is <i>still</i> above zero (and only afterwards subtracts the
 * {@code 0.22500001F} of distance decay). So a block survives <b>every roll</b> of the strongest
 * possible ray exactly when
 *
 * <pre>{@code (R + 0.3) * 0.3 >= 1.3 * power   <=>   R >= 13 * power / 3 - 0.3 }</pre>
 *
 * <p>which is what {@link #blastProofResistance} returns. For an end crystal ({@code power 6.0})
 * the bar is <b>25.7</b>:
 *
 * <table border="1">
 *   <caption>Blocks a bot actually stands on near a crystal</caption>
 *   <tr><th>block</th><th>resistance</th><th>≥ 25.7?</th></tr>
 *   <tr><td>obsidian / crying obsidian</td><td>1200</td><td>✓</td></tr>
 *   <tr><td>bedrock</td><td>3 600 000</td><td>✓</td></tr>
 *   <tr><td>iron bars (the cage)</td><td>6</td><td>✗</td></tr>
 *   <tr><td>stone / deepslate / iron block</td><td>6</td><td>✗</td></tr>
 *   <tr><td>end stone</td><td>9</td><td>✗</td></tr>
 * </table>
 *
 * <p>Distance decay is deliberately left OUT of the threshold. Folding it in would license standing
 * on something that survives a blast <i>at this range</i> — and the next crystal, or the next step,
 * is one block closer. The number above is the worst case (adjacent), which is the only case a
 * bot about to melee something can assume it is in.
 *
 * <h2>What is on the trigger surface, and what is deliberately not</h2>
 *
 * <ul>
 *   <li><b>Only entities that explode BECAUSE THEY WERE HIT.</b> {@link #blastPowerOnHurt} is that
 *       list and it currently has one member: vanilla 1.21.1 has no interface for "explodes on
 *       hurt", and {@code EndCrystal} is the only entity whose {@code hurt} calls
 *       {@code level.explode} unconditionally. A creeper explodes from its own AI and TNT from its
 *       fuse, so neither is on this path; ordinary combat never reaches this guard, which is the
 *       point — every {@code wd.*combat*} scene must read exactly as it did before.</li>
 *   <li><b>{@code MinecartTNT} is knowingly omitted.</b> Its {@code destroy(DamageSource)} can
 *       explode from a hit too, but the power depends on its own momentum and this repo has no
 *       scene for it. Adding it blind would widen a live invariant with no reading behind it.</li>
 *   <li><b>{@code wd.serverBreaksAnEndCrystal} is NOT covered, on purpose.</b> That scene calls
 *       {@code fp.attack(crystal)} straight on the {@code ServerPlayer} and never touches
 *       {@link Body}, so this guard cannot fire there — and must not. Its question is "can the
 *       crystal be broken", not "where to stand to hit it": it stages the crystal on the bot's own
 *       level with a plain floor,
 *       and a guard that refused there would delete the coverage of the verb itself. If that ever
 *       needs the footing rule too, it should get its OWN arm rather than have this one reach into
 *       a raw vanilla call.</li>
 * </ul>
 *
 * <h2>This guard refuses; it does not relocate</h2>
 *
 * {@code attackEntity} is a one-shot, single-tick verb: it can swing or decline, and "first stand
 * on footing the blast cannot destroy, then swing" is two steps. Making the bot actually take a
 * better stand needs a process that owns the approach — see the X1/X2/X3 note on
 * {@code wd.crystalBlastOnTheCage}. Until that exists the honest answer is to decline loudly,
 * because "this spike was not destroyed" is cheap and "fell out of the world" is not.
 */
public final class BlastFooting {

    private BlastFooting() {}

    /** {@code EndCrystal.hurt} → {@code level.explode(…, 6.0F, false, ExplosionInteraction.BLOCK)}. */
    public static final float CRYSTAL_BLAST_POWER = 6.0F;

    /** How far {@link #refuseSwing}'s message looks for a stand that WOULD qualify. Small and
     *  bounded on purpose (9³ cells, read only on the cold refusal path). It is 4 rather than 3 so
     *  that the one stand vanilla's caged spike actually has — the 3x3 obsidian floor INSIDE the
     *  cage, four blocks under a bot on the lid — appears in the message instead of being reported
     *  as "nothing nearby", which would send the reader looking for the wrong fix. */
    public static final int STAND_SURVEY_RADIUS = 4;

    /** The blast a hit on {@code target} sets off, or 0 for everything that does not explode when
     *  hurt. See the trigger-surface note on the class. */
    public static float blastPowerOnHurt(Entity target) {
        return target instanceof EndCrystal ? CRYSTAL_BLAST_POWER : 0f;
    }

    /**
     * The explosion resistance a block needs to survive <b>any</b> roll of a {@code power} blast at
     * point-blank range: {@code 13 * power / 3 - 0.3}. Derivation and the block table are on the
     * class javadoc — read it before changing this number.
     */
    public static double blastProofResistance(float power) {
        return 13.0 * power / 3.0 - 0.3;
    }

    /**
     * The one fragment of a footing refusal that is meant to be checked rather than read:
     * the block under the sole and its resistance, <b>as a pair</b>.
     *
     * <p>Exists because the obvious assertion — "the message mentions the block, and the message
     * mentions the resistance" — is satisfiable by accident here. Iron bars are 6.0 and the crystal
     * blast is 6.0, so {@code contains("6.0")} matches the {@code power=6.0} clause of any refusal
     * whatsoever; the same numeric coincidence that would have made the whole threshold a silent
     * no-op would have made the test that guards it vacuous. Pairing them in one token cannot be
     * matched by a message that names some other block, or the right block with the wrong number.
     */
    public static String footingTag(String blockId, float resistance) {
        return String.format(Locale.ROOT, "footing=%s resistance=%.1f", blockId, resistance);
    }

    /**
     * Why this bot must not swing at {@code target} from where it is standing, or {@code null} to
     * go ahead.
     *
     * <p>The footing is read with {@link WalkerGeometry#eachSoleCell} — the same row and the same
     * cells {@link WalkerGeometry#soleOnSolid} sums and {@link WalkerGeometry#soleRow} prints, so
     * this guard and every edge guard in the walker agree by construction about which block the
     * bot is standing on. The only predicate added on top is {@code isAir}, to skip cells that
     * hold nothing; the weakest remaining cell decides, because losing any one of them is losing
     * that part of the support.
     */
    public static String refuseSwing(LivingEntity p, Entity target) {
        float power = blastPowerOnHurt(target);
        if (power <= 0f) return null;
        Level level = p.level();
        double need = blastProofResistance(power);
        String targetId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        // Names the entity from the registry rather than hardcoding "end crystal": the day
        // blastPowerOnHurt grows a second member, a message that still said EndCrystal would be
        // wrong exactly when it was being read most carefully.
        String head = String.format(Locale.ROOT,
                "Swing refused: target %s explodes when hit (power=%.1f; its hurt() calls level.explode(…, "
                + "ExplosionInteraction.BLOCK) directly, regardless of mobGriefing; trigger surface: "
                + "BlastFooting.blastPowerOnHurt). ", targetId, power);

        // The weakest cell of the sole row, and whether the row holds anything at all.
        BlockPos[] weakest = new BlockPos[1];
        float[] weakestRes = { Float.MAX_VALUE };
        int[] solidCells = { 0 };
        WalkerGeometry.eachSoleCell(p, (cell, area) -> {
            BlockState st = level.getBlockState(cell);
            if (st.isAir()) return;
            solidCells[0]++;
            float res = st.getBlock().getExplosionResistance();
            if (res < weakestRes[0]) { weakestRes[0] = res; weakest[0] = cell.immutable(); }
        });

        if (solidCells[0] == 0) {
            // A DIFFERENT failure from "your footing is too weak", and it must never read like it:
            // this bot has no stand to protect, so the blast decides where it lands. Airborne is
            // the state a bot is in one tick after jumping next to a crystal on good obsidian —
            // the caller simply gets its swing on the next grounded tick.
            return head + String.format(Locale.ROOT,
                    "The row under the bot's feet, y=%d, is all air (the bot is not on the ground), so "
                    + "the explosion decides where it lands. Swing again once the bot is standing on "
                    + "the ground.", WalkerGeometry.soleRowY(p));
        }
        if (weakestRes[0] >= need) return null;

        String footId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(weakest[0]).getBlock()).toString();
        return head + footingTag(footId, weakestRes[0]) + " — " + String.format(Locale.ROOT,
                "the weakest support in the row under the bot's feet, y=%d, is %s @%s (blast resistance "
                + "%.1f), below the blast-proof threshold %.1f (=13*power/3-0.3, derived in BlastFooting: "
                + "ray strength is at most 1.3*power, each block hit first costs (R+0.3)*0.3, and a block "
                + "is destroyed if anything remains). The blast would destroy it and the bot would lose "
                + "its footing. %s"
                + "This verb does not move the bot: changing footing needs a process that owns both the "
                + "approach and the swing (see X1/X2/X3 of wd.crystalBlastOnTheCage).",
                weakest[0].getY(), footId, weakest[0].toShortString(), weakestRes[0], need,
                surveyStands(level, p.blockPosition(), need));
    }

    /**
     * The qualifying stands themselves, nearest first — {@code surveyStands}'s survey as a value
     * instead of a sentence.
     *
     * <p>The survey's javadoc says no caller may branch on it because reachability is unverified,
     * and that stays true of this list: <b>a caller must treat each entry as a candidate to WALK
     * to, and let the walk be the reachability test.</b> That is a different contract from
     * "can stand here". A caller that teleports to one, or that reports success because the list is
     * non-empty, is making exactly the false-yes the wording was written to prevent. Rung 20 uses
     * it the intended way: it walks, and if the walk does not arrive it is no worse off than the
     * refusal it started from.
     */
    public static List<BlockPos> qualifyingStands(Level level, BlockPos from, double need) {
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -STAND_SURVEY_RADIUS; dx <= STAND_SURVEY_RADIUS; dx++)
            for (int dy = -STAND_SURVEY_RADIUS; dy <= STAND_SURVEY_RADIUS; dy++)
                for (int dz = -STAND_SURVEY_RADIUS; dz <= STAND_SURVEY_RADIUS; dz++) {
                    BlockPos stand = from.offset(dx, dy, dz);
                    BlockState floor = level.getBlockState(stand.below());
                    if (floor.isAir() || floor.getBlock().getExplosionResistance() < need) continue;
                    if (level.getBlockState(stand).blocksMotion()
                            || level.getBlockState(stand.above()).blocksMotion()) continue;
                    out.add(stand.immutable());
                }
        out.sort((a, b) -> Double.compare(a.distSqr(from), b.distSqr(from)));
        return out;
    }

    /** The threshold a stand must clear to survive a hit on {@code target}. */
    public static double needFor(Entity target) {
        return blastProofResistance(blastPowerOnHurt(target));
    }

    /**
     * One sentence naming the qualifying stands within {@link #STAND_SURVEY_RADIUS}, so the refusal
     * says "is there anywhere else to stand" rather than only "not here".
     *
     * <p><b>Diagnostic, and it says so.</b> A candidate here is a cell with head-room whose floor
     * is blast-proof; nothing checks that the bot could actually WALK there, and in the geometry
     * this guard was written for it provably cannot (vanilla's cage lid is a solid 5x5 of iron bars
     * over the only qualifying floor). Reporting these as "can stand here" would be exactly the kind
     * of false yes this repo has paid for before, so the wording is {@code reachability unverified}
     * and no caller
     * may branch on it. The coarser {@code blocksMotion} head-room test lives ONLY here, inside a
     * message — the criterion above is the sole row and nothing else. That test is deliberately the
     * same shape {@code LavaProximityEscape} already uses to look for a cell to stand in (floor
     * blocks motion, the two bot cells do not), so this survey and the escape's agree about what a
     * candidate looks like even though neither decides anything with it.
     */
    private static String surveyStands(Level level, BlockPos from, double need) {
        List<BlockPos> stands = qualifyingStands(level, from, need);
        int found = stands.size();
        BlockPos nearest = found == 0 ? null : stands.get(0);
        if (found == 0)
            return String.format(Locale.ROOT,
                    "No qualifying stand is visible within radius %d (each cell either has a floor that "
                    + "is too weak or air, or has no room for the bot). ",
                    STAND_SURVEY_RADIUS);
        return String.format(Locale.ROOT,
                "%2$d qualifying stands are visible within radius %1$d (nearest %3$s, floor %4$s with "
                + "resistance %5$.1f, reachability unverified). ",
                STAND_SURVEY_RADIUS, found, nearest.toShortString(),
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(nearest.below()).getBlock()),
                level.getBlockState(nearest.below()).getBlock().getExplosionResistance());
    }
}
