package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * <b>Breaking an end crystal takes your own footing away — if you are standing on the cage.</b>
 *
 * <h2>The mechanism, and the run that produced it</h2>
 *
 * Rung 20 of the journey ladder (2026-08-18) recorded a pair of rows that only make sense together (field labels
 * translated):
 *
 * <pre>{@code
 * crystal.4.leg   ... → end=-33,86,23  underFeet=Block{minecraft:iron_bars}  end=arrived
 * crystal.4.result broken (stood at y=86, closest 3.3 blocks, 1 swing)
 * crystal.5.leg   start=-33,86,23  underFeet=Block{minecraft:air}  → lowestY=-5220
 * }</pre>
 *
 * Same coordinate, {@code iron_bars} under the bot before the swing and {@code air} under it after, and the only thing
 * that happened in between was one hit on an end crystal. Vanilla's {@code EndCrystal.hurt} answers every hit with
 * {@code level.explode(this, …, 6.0F, false, ExplosionInteraction.BLOCK)}; {@code Level.explode} maps {@code BLOCK}
 * onto {@code getDestroyType(RULE_BLOCK_EXPLOSION_DROP_DECAY)}, which is {@code DESTROY} or {@code DESTROY_WITH_DECAY}
 * and <b>never</b> {@code KEEP} — so unlike a creeper this blast is not gated on {@code mobGriefing}. Iron bars have
 * explosion resistance 6.0 and obsidian has 1200. The blast therefore destroys the cage and leaves the pillar, and a
 * bot that climbed onto the cage to get in range is standing on the part that is destroyed.
 *
 * <p>Rungs 5–9 of that run are all free fall (block under the feet {@code void_air}, zero blocks placed), i.e. every
 * later crystal order was given to a bot that was no longer standing anywhere. One hit cost nine rungs.
 *
 * <h2>What these two tests are</h2>
 *
 * A matched pair whose <b>only</b> variable is the block under the bot's feet. Same pillar, same cage, same crystal,
 * same inventory, same tick loop; one arm stands on the cage lid and one stands on the pillar-top obsidian one block
 * from the crystal. Neither touches the product — they are sensors, and both went green on both loaders, the cage arm
 * by way of a refusal.
 *
 * <ul>
 *   <li>{@code crystalBlastOnTheCage} — it falsifies the claim "the bot may stand on the cage while breaking the
 *       crystal". <b>The way it passes is a REFUSAL</b>, not a better stand: {@code BlastFooting} declines the swing
 *       and names the footing, so the crystal survives and the bot keeps its lid. Read the criteria and the X1/X2/X3
 *       note below before assuming this arm proves the bot can relocate — it cannot, and nothing here has ever asked it
 *       to.
 *   <li>{@code crystalBlastOnThePillar} — <b>expected to pass, and it is the anti-overfit arm.</b> Without it, "never
 *       approach a crystal; give up from a distance" would fully satisfy the arm above. This one asserts positively
 *       that the crystal still gets broken from a blast-proof stand.
 * </ul>
 *
 * <h2>Criteria — three clauses, and none of them may be dropped</h2>
 *
 * <ol>
 *   <li><b>The footing must not be removed.</b> Both arms assert {@code lowest y after the swing > standing y at the
 *       swing − 2}. Anchoring instead on the pillar top would make the cage arm <b>pass while measuring nothing</b>:
 *       with no knockback in play (see below) a bot whose lid was destroyed simply drops the four blocks onto the
 *       obsidian, which is still "on the pillar". This is the real invariant and it is unchanged.
 *   <li><b>Either break the crystal or refuse with a reason.</b> The crystal is broken, <i>or</i>
 *       {@code mc.bot.attackEntity} answers a refusal whose reason carries {@link #footingTag footingTag(blockId,
 *       resistance)} for the block the test itself read under the swing stand before the hit. The pair is checked as
 *       ONE token on purpose: iron bars are 6.0 and the blast is 6.0, so asking separately for the id and for "6.0"
 *       would be satisfied by the {@code power=6.0} every refusal prints. A message that merely says "not allowed", or
 *       names another block, or the right block with a wrong number, does not satisfy it — that is what keeps this
 *       clause from passing on any error at all.
 *   <li><b>Neither broken nor refused with a reason means FAIL.</b> The clause that plugs the {@code 0==0} hole: a run
 *       that never swings, or one where the driver silently declined, fails here. Without it a bot that stood still for
 *       200 ticks would pass clause 1 perfectly.
 * </ol>
 *
 * <p><b>The cage arm currently passes through the second half of clause 2 — a refusal with a reason — not by standing
 * on the obsidian to swing.</b> Do not read a passing row here as "the bot now relocates its footing on its own". It
 * does not, and this rig could not observe it if it did:
 *
 * <ul>
 *   <li><b>X1 — a process that owns both steps, approaching and swinging.</b> {@code Body.attackEntity} is one-shot and
 *       single-tick; it can swing or decline, and it must never teleport. Choosing a stand is a multi-tick job and
 *       belongs to whatever walks the bot in ({@code SwingAt} on rung 20, {@code CombatProcess} in production).
 *   <li><b>X2 — the test must hand control over BEFORE it latches the stand it judges.</b> {@link #swingAndWatch} reads
 *       {@code swingStand} on the line above the driver call, so a relocation performed inside that call is invisible
 *       to the anchor and reads as a fall. A rig that wants to grade stand-choosing has to drive the X1 process tick by
 *       tick instead of calling the verb itself.
 *   <li><b>X3 — the staging must contain a blast-proof stand the bot can REACH.</b> In vanilla's caged spike there is
 *       exactly one — the 3x3 obsidian floor inside the cage — and it is sealed under a solid 5x5 iron lid, four blocks
 *       below a bot standing on that lid. Every blast-proof sole row in this arena is at {@code y = pillar top}, i.e.
 *       {@code standing y at the swing − 4}, so clause 1's {@code −2} tolerance can never be met by relocating. That is
 *       a fact about the geometry vanilla builds, not about this file: fixing it means a different staging (or a bot
 *       that breaks in), never a looser number here.
 * </ul>
 *
 * <p>{@code minYAfterSwing} <b>excludes the swing tick's own y</b>, for a reason this ladder has already paid for once:
 * a minimum that includes its own starting sample can never contradict the start, and a healthy arm was judged failing
 * for exactly that reason.
 *
 * <h2>Two limits stated on the row rather than left to be discovered</h2>
 *
 * <ul>
 *   <li><b>Whether the blast can knock this player back depends on whether the player joined the level — and every
 *       server-side player joins now.</b> {@code Explosion.explode} collects victims with
 *       {@code level.getEntities(source, aabb)}, which reads the level's entity index. A player that never joined is
 *       absent from that index and takes no launch; a {@code JoinedBody} is present and is thrown. The first run on
 *       joined players threw the bot {@code dx=+5} and {@code dy=−31} off a sole that was still obsidian.
 *       <p>Clause B therefore exempts a fall whose sole stayed blast-proof AND in which the player moved horizontally —
 *       that combination is knockback, and knockback is not what B grades. The exemption is narrow on purpose: dissolve
 *       the footing and {@code soleSurvived} goes false, so no amount of launch can buy a green. <b>The ballistics are
 *       real, and this test still does not measure them</b> — there is no staging for it and no criterion on it, so a
 *       green pillar arm remains no evidence about knockback in either direction. The {@code trajectory} row says on
 *       every run which of the three outcomes this run was.
 *   <li><b>No task, no goal, no planning.</b> The sibling void scenes drive a goto because their subject is a leap;
 *       here the subject is which block is under the feet, and steering would put a second variable between the two
 *       arms. The bot is spawned parked and only its body is stepped ({@code mc.test.bots} body ticks, no perceiving,
 *       reflexes or task), every key let go, and each swing is one {@code mc.bot.attackEntity}.
 * </ul>
 *
 * <h2>Rig rules</h2>
 *
 * <ul>
 *   <li><b>The staging copies vanilla's caged spike, not a convenient approximation.</b> {@code SpikeFeature} guards
 *       exactly the two spikes with {@code radius 2}, at heights 79 and 82 — which is why the ladder's crystal sat at
 *       {@code y=83} with its cage lid at {@code 85} and the bot at {@code 86}. The obsidian is placed where
 *       {@code dx²+dz² <= radius²+1}, the centre block under the crystal is bedrock, the cage is {@code |dx|==2 ||
 *       |dz|==2 || dy==3} over {@code [-2,2]²×[0,3]}, and the crystal sits one block above the standing surface. Get
 *       this wrong in the generous direction — a wider top, a shorter cage — and the arms stop being about the pillar
 *       the bot actually meets.
 *   <li><b>The drop has to be lethal or the fall is not a fall.</b> {@value #VOID_DEPTH} blocks of clear air ring the
 *       pillar down to a catch floor, comfortably past {@code SurvivalMath.survivableFall} at full health (22).
 *   <li><b>Every row is published on PASS as well as FAIL.</b> "On the passing run the block under the feet is still
 *       obsidian after the swing" is the reading that separates these two arms.
 * </ul>
 */
@Scene("wd.scenes")
class CrystalBlastTest {

    /**
     * Vanilla's radius for the two GUARDED spikes ({@code SpikeFeature}: {@code 2 + i/3} with the cage on {@code i == 1
     * || i == 2}). Obsidian fills {@code dx²+dz² <= r²+1}, so the top is a 5x5 with no corners — and the cage lid above
     * it is a full 5x5 that overhangs them.
     */
    private static final int PILLAR_RADIUS = 2;

    /** Half-width of the cage in x/z. Vanilla's cage is {@code [-2,2]²} regardless of the pillar. */
    private static final int CAGE_HALF = 2;

    /**
     * Height of the cage: walls at {@code dy 0..2}, lid at {@code dy == 3}, measured from the standing surface. The lid
     * is what the ladder's bot was standing on.
     */
    private static final int CAGE_LID_DY = 3;

    /**
     * Clear cells between the pillar's ring and the catch floor. Past {@code SurvivalMath.survivableFall(20) == 22}, so
     * stepping off the pillar is unambiguously a fall and not a hop.
     */
    private static final int VOID_DEPTH = 32;

    /** Half-width of the working box, the catch floor and the entity box. */
    private static final int CLEAR_HALF = 6;

    /**
     * {@code LivingEntity.invulnerableTime}. Swinging faster than this does less damage, not more — the same cadence
     * {@code JourneyEndRungs.SwingAt} uses, so the two are comparable.
     */
    private static final int SWING_EVERY = 20;

    /**
     * The reach {@code JourneyEndRungs} gates its swings on. Copied rather than widened: an arm that can hit from
     * anywhere is not measuring a stand.
     */
    private static final double MELEE_REACH = 4.5;

    /**
     * Ticks the loop runs. The first swing lands at {@code t == SWING_EVERY - 1}; the rest is enough for a player that
     * lost its footing to finish falling {@value #VOID_DEPTH} blocks.
     */
    private static final int TICKS = 200;

    /** The power vanilla's {@code EndCrystal.hurt} explodes with. */
    private static final float CRYSTAL_BLAST_POWER = 6.0F;

    /** The plot's standing surface (relative y): the catch floor {@value #VOID_DEPTH} below and the cage above fit. */
    private static final int STAND_Y = 15;

    /**
     * The resistance a block needs to survive any roll of a {@code power} blast at point-blank range, {@code 13 * power
     * / 3 - 0.3}: the driver's own derivation, restated here so the test judges the world by a number it states rather
     * than one it asks the driver for.
     */
    private static double blastProofResistance(float power) {
        return 13.0 * power / 3.0 - 0.3;
    }

    /**
     * The pair a footing refusal must quote: the block under the sole and its resistance, in the driver's refusal
     * wording. Restated, so a change of that wording fails clause A loudly.
     */
    private static String footingTag(String blockId, float resistance) {
        return String.format(Locale.ROOT, "footing=%s resistance=%.1f", blockId, resistance);
    }

    /**
     * <b>The reproduction.</b> The bot stands on the cage lid, one cell off centre — the offset the ladder's "closest
     * 3.3 blocks" implies for a bot at {@code y=86} over a crystal at {@code y=83}.
     *
     * <p>What it forbids is not approaching the crystal but hitting it while standing on a block that the resulting
     * blast will destroy.
     */
    @Test
    void crystalBlastOnTheCage(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 48, z = 16) Plot plot, TestReporter reporter) {
        smashFrom(game, plot, reporter, CAGE_LID_DY + 1);
    }

    /**
     * <b>The anti-overfit arm.</b> The same pillar, the same cage, the same crystal — the bot simply stands on the
     * pillar-top obsidian inside the cage instead of on its lid.
     *
     * <p>Its passing is the whole reason the other arm may pass by refusing: the answer to "the blast removes the
     * footing" is a different stand, not a refusal to approach. A fix that forbids every crystal within reach makes
     * this arm fail and is caught.
     *
     * <p>If this arm is RED, the premise "standing on obsidian is safe" is itself false, and that is a finding about
     * the world rather than about the rig — read {@code landing} and {@code body.inLevelEntityIndex} before touching
     * the staging.
     */
    @Test
    void crystalBlastOnThePillar(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 48, z = 16) Plot plot, TestReporter reporter) {
        smashFrom(game, plot, reporter, 0);
    }

    /**
     * The shared implementation of both arms. {@code standDy} — how far above the pillar's standing surface the player
     * is placed — is the ONLY input, so any difference between the two rows can only be the block under the feet.
     *
     * @param standDy 0 puts the player on the pillar-top obsidian; {@code CAGE_LID_DY + 1} puts it on the cage lid
     */
    private static void smashFrom(Game game, Plot plot, TestReporter reporter, int standDy) {
        BlockPos stand = plot.at(0, STAND_Y, 0);
        final int cx = stand.getX(), cz = stand.getZ();
        final int standY = stand.getY(), topY = standY - 1;
        final int standAt = standY + standDy;

        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            catchFloor(level, cx, cz, standY);
            buildSpike(level, cx, cz, topY, standY);
        });

        Settings.pin(game);

        EndCrystal crystal = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            EndCrystal c = new EndCrystal(level, cx + 0.5, standY + 1, cz + 0.5);
            c.setShowBottom(true);
            level.addFreshEntity(c);
            return c;
        });
        game.afterTest(s -> {
            if (crystal.isAlive()) {
                crystal.discard();
            }
        });

        // The crystal entering the level's index is what attackEntity looks it up by.
        game.await()
                .within(200)
                .as("the crystal is in the level's index")
                .until(() -> !crystal.level()
                        .getEntitiesOfClass(EndCrystal.class, arenaBox(cx, standY, cz))
                        .isEmpty());

        SceneBot bot = SceneBot.spawn(game, plot.dimension(), cx + 1.5, standAt, cz + 0.5);
        Map<String, String> rows = new LinkedHashMap<>();
        boolean[] verdict = new boolean[2];
        String[] why = new String[2];
        game.runOnServer(s -> swingAndWatch(bot, crystal, cx, cz, topY, standY, standAt, rows, verdict, why));
        rows.forEach(reporter::publishEntry);
        game.check(verdict[0], why[0]);
        game.check(verdict[1], why[1]);
    }

    /** The catch floor under the working box; the plot hands everything else over as air. */
    private static void catchFloor(ServerLevel level, int cx, int cz, int standY) {
        for (int dx = -CLEAR_HALF; dx <= CLEAR_HALF; dx++) {
            for (int dz = -CLEAR_HALF; dz <= CLEAR_HALF; dz++) {
                level.setBlockAndUpdate(
                        new BlockPos(cx + dx, standY - VOID_DEPTH, cz + dz), Blocks.STONE.defaultBlockState());
            }
        }
    }

    /**
     * Vanilla's caged spike ({@code SpikeFeature}, {@code guarded = true}): obsidian where {@code dx²+dz² <= r²+1} from
     * the catch floor up to {@code topY}, bedrock in the centre of the top, and the iron-bar cage over
     * {@code [-2,2]²×[0,3]} on top of it.
     */
    private static void buildSpike(ServerLevel level, int cx, int cz, int topY, int standY) {
        for (int dx = -PILLAR_RADIUS; dx <= PILLAR_RADIUS; dx++) {
            for (int dz = -PILLAR_RADIUS; dz <= PILLAR_RADIUS; dz++) {
                if (dx * dx + dz * dz > PILLAR_RADIUS * PILLAR_RADIUS + 1) {
                    continue;
                }
                for (int y = topY; y > standY - VOID_DEPTH; y--) {
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz), Blocks.OBSIDIAN.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(new BlockPos(cx, topY, cz), Blocks.BEDROCK.defaultBlockState());
        for (int dx = -CAGE_HALF; dx <= CAGE_HALF; dx++) {
            for (int dz = -CAGE_HALF; dz <= CAGE_HALF; dz++) {
                for (int dy = 0; dy <= CAGE_LID_DY; dy++) {
                    boolean bar = Math.abs(dx) == CAGE_HALF || Math.abs(dz) == CAGE_HALF || dy == CAGE_LID_DY;
                    if (bar) {
                        level.setBlockAndUpdate(
                                new BlockPos(cx + dx, standY + dy, cz + dz), Blocks.IRON_BARS.defaultBlockState());
                    }
                }
            }
        }
    }

    /**
     * Put a player on {@code standAt}, let it settle, hit the crystal on vanilla's own cadence, and watch what the
     * blast did to the block it was standing on. Server thread: the whole loop is one server task, so no world tick
     * falls between the body's steps.
     *
     * <p>The loop is deliberately the simplest thing that can produce the measurement: every input is released on every
     * tick, so the only forces acting on the player are gravity, collision and the explosion. {@code bot.step()} runs
     * the player's own vanilla {@code aiStep}, the same gravity-then-{@code move()} pipeline the client runs — so a
     * player whose footing has been deleted falls, and one whose footing survived does not.
     */
    private static void swingAndWatch(
            SceneBot bot,
            EndCrystal crystal,
            int cx,
            int cz,
            int topY,
            int standY,
            int standAt,
            Map<String, String> rows,
            boolean[] verdict,
            String[] why) {
        ServerPlayer fp = bot.player();
        ServerLevel level = fp.serverLevel();
        SimProbes.grantWaterEffects(fp); // inert here — the player is already invulnerable
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_SWORD));
        fp.getInventory().selected = 0;
        // Every key let go once; a press holds until the next one changes it.
        bot.release();

        final double startY = fp.getY();
        int swings = 0, sinceSwing = 0, firstSwingTick = -1, refused = 0;
        double minYAfterSwing = Double.MAX_VALUE, closest = Double.MAX_VALUE;
        BlockPos swingStand = null;
        String underAtSwing = null, underAfterSwing = null, refusal = null;
        float resAtSwing = Float.NaN;

        for (int t = 0; t < TICKS; t++) {
            sinceSwing++;
            boolean swungNow = false;
            if (crystal.isAlive()) {
                double d = fp.distanceTo(crystal);
                closest = Math.min(closest, d);
                if (d <= MELEE_REACH && sinceSwing >= SWING_EVERY) {
                    if (firstSwingTick < 0) {
                        // Read the stand BEFORE the hit. Asked afterwards it would describe the
                        // world the explosion left, while claiming to describe the one it found.
                        // The resistance is taken here for the same reason, and it is the test's
                        // OWN derivation — clause 2 feeds it back as a substring test, so a driver
                        // message that names some other block cannot satisfy it.
                        swingStand = fp.blockPosition();
                        underAtSwing = blockIdAt(level, swingStand.below());
                        resAtSwing = level.getBlockState(swingStand.below())
                                .getBlock()
                                .getExplosionResistance();
                        firstSwingTick = t;
                    }
                    // EndCrystal.hurt explodes INSIDE this call.
                    Responses.ImmediateResponse hit = bot.attack(crystal.getId());
                    sinceSwing = 0;
                    // A refused call is NOT a swing. Counting it as one would print "10 swings"
                    // over a crystal nothing ever touched, and the swings row is the one that says
                    // whether this arm measured anything at all.
                    String no = hit instanceof Responses.Refused<?> r ? r.message() : null;
                    if (no != null) {
                        refused++;
                        if (refusal == null) {
                            refusal = no;
                        }
                    } else {
                        swings++;
                        swungNow = true;
                    }
                }
            }
            if (swungNow && underAfterSwing == null) {
                underAfterSwing = blockIdAt(level, swingStand.below());
            }
            bot.step();
            // Sampled only after the swing tick's own step, so the minimum never contains the y the
            // bot had when it swung — a minimum that includes its own start cannot contradict the start.
            if (firstSwingTick >= 0 && t >= firstSwingTick) {
                minYAfterSwing = Math.min(minYAfterSwing, fp.getY());
            }
        }

        // "tried", not "swung": the driver may decline (BlastFooting), and the tick the loop
        // COMMITTED to a swing is still the right anchor for clause 1 — that is the tick from
        // which the footing had to survive, whoever ended up deciding whether the sword moved.
        boolean tried = firstSwingTick >= 0;
        boolean broke = !crystal.isAlive();
        int swingStandY = tried ? swingStand.getY() : Integer.MIN_VALUE;
        // DID THE SOLE SURVIVE? This is the mechanism clause B actually names — the blast must not
        // remove the footing — and it is a question about the WORLD, not about where the bot ended up.
        // Obsidian is 1200 against a power-6 blast, so a cell that is still solid was never taken.
        boolean soleSurvived = tried
                && level.getBlockState(swingStand.below()).getBlock().getExplosionResistance()
                        >= blastProofResistance(CRYSTAL_BLAST_POWER);
        // AND WAS THE PLAYER LAUNCHED OFF IT? Horizontal displacement is the signature of knockback:
        // Explosion.explode applies an impulse along the vector from the blast, and a player whose
        // footing was removed falls STRAIGHT down. Measured on the flipped gates, 2026-08-22:
        // dx=+5 dz=0 dy=−31 with obsidian still under the feet after the swing — the sole was there
        // the whole time.
        boolean launchedSideways = tried
                && (Math.abs(fp.getX() - (swingStand.getX() + 0.5)) > 1.5
                        || Math.abs(fp.getZ() - (swingStand.getZ() + 0.5)) > 1.5);
        // Clause B grades FOOTING. A player that lost height while its sole stood firm and its
        // trajectory carried it sideways was thrown, not dropped, and ballistics is a mechanism this
        // test does not stage for or measure — see the class note. Exempting it keeps the clause
        // falsifiable by the thing it names: remove the obsidian's resistance and `soleSurvived`
        // goes false, and no amount of knockback can rescue it.
        boolean knockedClear = soleSurvived && launchedSideways;
        boolean heldItsGround = tried && (minYAfterSwing > swingStandY - 2 || knockedClear);
        String resAtSwingText = tried ? String.format(Locale.ROOT, "%.1f", resAtSwing) : null;
        // Clause 2's second half. The block id and the resistance are the test's OWN readings of
        // the world, taken before the hit, and they are checked as ONE token: iron bars are 6.0 and
        // the blast is 6.0, so two separate contains() calls would be satisfied by the "power=6.0"
        // any refusal carries. A refusal that does not quote this exact pair is not a refusal with a reason.
        String footingTag = tried ? footingTag(underAtSwing, resAtSwing) : null;
        boolean namedTheFooting = refusal != null && footingTag != null && refusal.contains(footingTag);
        BlockPos endAt = fp.blockPosition();

        rows.put(
                "rig",
                String.format(
                        Locale.ROOT,
                        "solid pillar top y=%d (bedrock centre), standing surface y=%d | cage: |dx|=2 or |dz|=2 or dy=3, y=%d..%d, cage lid y=%d "
                                + "| crystal (%d.5,%d,%d.5) | bot start y=%d (standing surface +%d) | %d blocks of air around the pillar down to the catch floor y=%d",
                        topY,
                        standY,
                        standY,
                        standY + CAGE_LID_DY,
                        standY + CAGE_LID_DY,
                        cx,
                        standY + 1,
                        cz,
                        standAt,
                        standAt - standY,
                        VOID_DEPTH,
                        standY - VOID_DEPTH));
        rows.put(
                "stand",
                tried
                        ? swingStand.toShortString() + " underFeet=" + underAtSwing
                        : "never swung (the crystal was never within " + MELEE_REACH
                                + " blocks, or the bot fell first)");
        rows.put("underAfterSwing", underAfterSwing == null ? "not sampled (never swung)" : underAfterSwing);
        rows.put("crystal", broke ? "broken" : "intact");
        rows.put(
                "minYAfterSwing",
                tried
                        ? String.format(
                                Locale.ROOT,
                                "%.3f (excludes the y=%d at the moment of the swing; criterion > %d)",
                                minYAfterSwing,
                                swingStandY,
                                swingStandY - 2)
                        : "not sampled");
        rows.put(
                "swings",
                swings + " swings (closest "
                        + (closest == Double.MAX_VALUE ? "not measured" : String.format(Locale.ROOT, "%.2f", closest))
                        + " blocks, reach limit " + MELEE_REACH + ")");
        rows.put(
                "refusal",
                refusal == null
                        ? "none: the driver allowed the swing (resistance under the feet is sufficient; " + swings
                                + " real swings this run)"
                        : "refused " + refused + " times / " + swings + " real swings; first reason: " + refusal);
        rows.put(
                "blastProofThreshold",
                String.format(
                        Locale.ROOT,
                        "power=%.1f ⇒ footing resistance must be ≥ %.1f (13*power/3−0.3, derived in BlastFooting); block under the feet at the swing: %s, resistance %s",
                        CRYSTAL_BLAST_POWER,
                        blastProofResistance(CRYSTAL_BLAST_POWER),
                        tried ? underAtSwing : "-",
                        tried ? resAtSwingText : "not sampled"));
        rows.put(
                "landing",
                String.format(
                        Locale.ROOT,
                        "%s underFeet=%s (start y=%.1f, net drop %.1f blocks)",
                        endAt.toShortString(),
                        blockIdAt(level, endAt.below()),
                        startY,
                        startY - fp.getY()));
        rows.put(
                "cageBarsLeft",
                barsLeft(level, cx, cz, standY) + "/" + cageCells()
                        + " iron bars (blast resistance 6.0, destroyed by a power-6 blast; obsidian at 1200 is not)");
        rows.put(
                "body.inLevelEntityIndex",
                (level.getEntity(fp.getId()) != null)
                        + " (false = the bot cannot receive blast knockback in this run; true = it can and the trajectory is real, but this test still grades only the footing)");
        rows.put(
                "trajectory",
                !tried
                        ? "not sampled"
                        : !launchedSideways
                                ? "the bot did not move horizontally (footing "
                                        + (soleSurvived ? "survived" : "was removed")
                                        + "); no knockback to exempt in this run"
                                : soleSurvived
                                        ? "the bot was thrown: horizontal move " + swingStand.toShortString() + " → "
                                                + endAt.toShortString()
                                                + ", while the block under the feet at the swing, "
                                                + blockIdAt(level, swingStand.below())
                                                + ", was sufficiently resistant and never removed. "
                                                + "Classified as knockback; clause B is exempted. WARNING: the knockback is real and this "
                                                + "test does not measure it (no test setup and no criterion for it); do not read this row "
                                                + "as \"the blast cannot move the bot\""
                                        : "the bot moved horizontally and the footing was also removed; not exempted, clause B fails as usual");
        rows.put(
                "gamerule.blockExplosionDropDecay",
                String.valueOf(level.getGameRules().getBoolean(GameRules.RULE_BLOCK_EXPLOSION_DROP_DECAY)));
        rows.put(
                "gamerule.mobGriefing",
                level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                        + " (recorded for reference: ExplosionInteraction.BLOCK ignores this rule; only MOB reads it)");

        verdict[0] = broke || namedTheFooting;
        why[0] = "A: either break the crystal or refuse with a reason: the crystal is "
                + (broke ? "broken" : "intact")
                + ", the driver " + (refusal == null ? "gave no refusal reason" : "refused " + refused + " times")
                + (namedTheFooting
                        ? " and named the footing (" + footingTag + ")"
                        : refusal == null
                                ? ""
                                : " but the reason does not contain the paired token [" + footingTag
                                        + "], so it does not count as a reason (matching the parts separately would be fooled by power=6.0)")
                + ". Neither broken nor refused with a footing reading means FAIL: this clause stops a run that never "
                + "swung from passing as 0==0 (" + swings + " real swings this run, closest "
                + (closest == Double.MAX_VALUE ? "not measured" : String.format(Locale.ROOT, "%.2f", closest))
                + " blocks)";
        verdict[1] = heldItsGround;
        why[1] = "B: the blast must not remove the footing: at the swing (including a refused one) the bot stood at "
                + (tried ? swingStand.toShortString() + " (under the feet: " + underAtSwing + ")" : "-")
                + ", lowest y afterwards="
                + (tried ? String.format(Locale.ROOT, "%.3f", minYAfterSwing) : "not sampled")
                + ", criterion > " + (tried ? String.valueOf(swingStandY - 2) : "no anchor")
                + (knockedClear
                        ? " (exempted this run: the footing stayed sufficient and the bot was thrown sideways "
                                + "by the blast, see the trajectory row; the height loss is a consequence of knockback, "
                                + "not of the footing being removed)"
                        : "")
                + ". The anchor is the standing y at the moment the swing was decided, not where the test setup "
                + "placed the bot. WARNING: this arm currently passes through a refusal with a reason, not through "
                + "a change of footing; changing footing requires X1/X2/X3, see the class comment";
    }

    private static String blockIdAt(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock())
                .toString();
    }

    /**
     * Iron bars still standing anywhere in the cage's shell — the direct reading of whether the blast destroyed the
     * cage.
     */
    private static int barsLeft(ServerLevel level, int cx, int cz, int standY) {
        int n = 0;
        for (int dx = -CAGE_HALF; dx <= CAGE_HALF; dx++) {
            for (int dz = -CAGE_HALF; dz <= CAGE_HALF; dz++) {
                for (int dy = 0; dy <= CAGE_LID_DY; dy++) {
                    boolean bar = Math.abs(dx) == CAGE_HALF || Math.abs(dz) == CAGE_HALF || dy == CAGE_LID_DY;
                    if (bar
                            && level.getBlockState(new BlockPos(cx + dx, standY + dy, cz + dz))
                                    .is(Blocks.IRON_BARS)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * How many cells {@link #buildSpike} filled with bars, so {@code cageBarsLeft} is a fraction rather than a count.
     */
    private static int cageCells() {
        int n = 0;
        for (int dx = -CAGE_HALF; dx <= CAGE_HALF; dx++) {
            for (int dz = -CAGE_HALF; dz <= CAGE_HALF; dz++) {
                for (int dy = 0; dy <= CAGE_LID_DY; dy++) {
                    if (Math.abs(dx) == CAGE_HALF || Math.abs(dz) == CAGE_HALF || dy == CAGE_LID_DY) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static AABB arenaBox(int cx, int standY, int cz) {
        return new AABB(
                cx - CLEAR_HALF,
                standY - VOID_DEPTH,
                cz - CLEAR_HALF,
                cx + CLEAR_HALF + 1,
                standY + 8,
                cz + CLEAR_HALF + 1);
    }
}
