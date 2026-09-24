package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.movement.BlastFooting;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * <b>Breaking an end crystal takes your own footing away — if you are standing on the cage.</b>
 *
 * <h2>The mechanism, and the run that produced it</h2>
 *
 * Rung 20 of the journey ladder (2026-08-18) recorded a pair of rows that only make sense together
 * (field labels translated):
 *
 * <pre>{@code
 * crystal.4.leg   ... → end=-33,86,23  underFeet=Block{minecraft:iron_bars}  end=arrived
 * crystal.4.result broken (stood at y=86, closest 3.3 blocks, 1 swing)
 * crystal.5.leg   start=-33,86,23  underFeet=Block{minecraft:air}  → lowestY=-5220
 * }</pre>
 *
 * Same coordinate, {@code iron_bars} under the bot before the swing and {@code air} under it after,
 * and the only thing that happened in between was one hit on an end crystal. Vanilla's
 * {@code EndCrystal.hurt} answers every hit with
 * {@code level.explode(this, …, 6.0F, false, ExplosionInteraction.BLOCK)}; {@code Level.explode}
 * maps {@code BLOCK} onto {@code getDestroyType(RULE_BLOCK_EXPLOSION_DROP_DECAY)}, which is
 * {@code DESTROY} or {@code DESTROY_WITH_DECAY} and <b>never</b> {@code KEEP} — so unlike a creeper
 * this blast is not gated on {@code mobGriefing}. Iron bars have explosion resistance 6.0 and
 * obsidian has 1200. The blast therefore destroys the cage and leaves the pillar, and a bot that
 * climbed onto the cage to get in range is standing on the part that is destroyed.
 *
 * <p>Rungs 5–9 of that run are all free fall (block under the feet {@code void_air}, zero blocks
 * placed), i.e. every later crystal order was given to a bot that was no longer standing anywhere.
 * One hit cost nine rungs.
 *
 * <h2>What these two scenes are</h2>
 *
 * A matched pair whose <b>only</b> variable is the block under the bot's feet. Same pillar, same
 * cage, same crystal, same inventory, same tick loop; one arm stands on the cage lid and one stands
 * on the pillar-top obsidian one block from the crystal. Neither touches the product — they are
 * sensors, shipped {@code withRequired(false)} under this repo's promote-on-first-green rule and
 * required now — both went green on both loaders, the cage arm by way of a refusal.
 *
 * <ul>
 *   <li>{@code wd.crystalBlastOnTheCage} — it falsifies the claim "the bot may stand on the cage
 *       while breaking the crystal". <b>The way it passes is a REFUSAL</b>, not a better stand:
 *       {@code BlastFooting} declines the swing and names the footing, so the crystal survives and
 *       the bot keeps its lid. Read the criteria and the X1/X2/X3 note below before assuming this arm
 *       proves the bot can relocate — it cannot, and nothing here has ever asked it to.</li>
 *   <li>{@code wd.crystalBlastOnThePillar} — <b>expected to pass, and it is the anti-overfit arm.</b>
 *       Without it, "never approach a crystal; give up from a distance" would fully satisfy the arm
 *       above. This one asserts positively that the crystal still gets broken from a blast-proof
 *       stand.</li>
 * </ul>
 *
 * <h2>Criteria — three clauses, and none of them may be dropped</h2>
 *
 * <ol>
 *   <li><b>The footing must not be removed.</b> Both arms assert {@code lowest y after the swing >
 *       standing y at the swing − 2}. Anchoring instead on the pillar top would make the cage arm
 *       <b>pass while measuring nothing</b>: with no knockback in play (see below) a bot whose lid was
 *       destroyed simply drops the four blocks onto the obsidian, which is still "on the pillar".
 *       This is the real invariant and it is unchanged.</li>
 *   <li><b>Either break the crystal or refuse with a reason.</b> The crystal is broken, <i>or</i>
 *       the driver's {@code Body.lastAttackRefusal} is non-empty AND carries
 *       {@code BlastFooting.footingTag(blockId, resistance)} for the block the scene itself read
 *       under the swing stand before the hit. The pair is checked as ONE token on purpose: iron bars
 *       are 6.0 and the blast is 6.0, so asking separately for the id and for "6.0" would be
 *       satisfied by the {@code power=6.0} every refusal prints. A message that merely says "not
 *       allowed", or names another block, or the right block with a wrong number, does not satisfy
 *       it — that is what keeps this clause from passing on any error at all.</li>
 *   <li><b>Neither broken nor refused with a reason means FAIL.</b> The clause that plugs the
 *       {@code 0==0} hole: a run that never swings, or one where the driver silently declined, fails
 *       here. Without it a bot that stood still for 200 ticks would pass clause 1 perfectly.</li>
 * </ol>
 *
 * <p><b>The cage arm currently passes through the second half of clause 2 — a refusal with a
 * reason — not by standing on the obsidian to swing.</b> Do not read a passing row here as "the bot
 * now relocates its footing on its own". It does not, and this rig could not observe it if it did:
 *
 * <ul>
 *   <li><b>X1 — a process that owns both steps, approaching and swinging.</b>
 *       {@code Body.attackEntity} is one-shot and single-tick; it can swing or decline, and it must
 *       never teleport. Choosing a stand is a multi-tick job and belongs to whatever walks the bot in
 *       ({@code SwingAt} on rung 20, {@code CombatProcess} in production).</li>
 *   <li><b>X2 — the scene must hand control over BEFORE it latches the stand it judges.</b>
 *       {@link #swingAndWatch} reads {@code swingStand} on the line above the driver call, so a
 *       relocation performed inside that call is invisible to the anchor and reads as a fall. A rig
 *       that wants to grade stand-choosing has to drive the X1 process tick by tick instead of
 *       calling the verb itself.</li>
 *   <li><b>X3 — the staging must contain a blast-proof stand the body can REACH.</b> In vanilla's
 *       caged spike there is exactly one — the 3x3 obsidian floor inside the cage — and it is sealed
 *       under a solid 5x5 iron lid, four blocks below a bot standing on that lid. Every blast-proof
 *       sole row in this arena is at {@code y = pillar top}, i.e. {@code standing y at the swing − 4},
 *       so clause 1's
 *       {@code −2} tolerance can never be met by relocating. That is a fact about the geometry
 *       vanilla builds, not about this file: fixing it means a different staging (or a bot that
 *       breaks in), never a looser number here.</li>
 * </ul>
 *
 * <p>{@code minYAfterSwing} <b>excludes the swing tick's own y</b>, for a reason this ladder has
 * already paid for once: a minimum that includes its own starting sample can never contradict the
 * start, and a healthy arm was judged failing for exactly that reason.
 *
 * <h2>Two limits stated on the row rather than left to be discovered</h2>
 *
 * <ul>
 *   <li><b>Whether the blast can knock this body depends on whether the body joined — and every
 *       server body joins now.</b> {@code Explosion.explode} collects victims with
 *       {@code level.getEntities(source, aabb)}, which reads the level's entity index. A body that
 *       never joined is absent from that index and takes no launch; a {@code JoinedBody} is present
 *       and is thrown. The ladder and the six gates all use joined bodies, so there is no clean
 *       footing-only reading: the first run on joined bodies threw the bot {@code dx=+5} and
 *       {@code dy=−31} off a sole that was still obsidian.
 *
 *       <p>Clause B therefore exempts a fall whose sole stayed blast-proof AND whose body moved
 *       horizontally — that combination is knockback, and knockback is not what B grades. The
 *       exemption is narrow on purpose: dissolve the footing and {@code soleSurvived} goes false, so
 *       no amount of launch can buy a green. <b>The ballistics are real, and this scene still does
 *       not measure them</b> — there is no staging for it and no criterion on it, so a green pillar
 *       arm remains no evidence about knockback in either direction. The {@code trajectory} row says
 *       on every run which of the three outcomes this run was.</li>
 *   <li><b>No Walker, no goal, no {@code LevelWorldView}.</b> The sibling void scenes drive a Walker
 *       because their subject is a leap; here the subject is which block is under the feet, and
 *       steering would put a second variable between the two arms. The body is created and stepped
 *       exactly the way {@code wd.parkourVoidShortRunway} creates and steps its own —
 *       {@link ServerPlayerBody#createUnique} plus a synchronous {@code av.step()} loop — with
 *       every input released each tick.</li>
 * </ul>
 *
 * <h2>Rig rules</h2>
 *
 * <ul>
 *   <li><b>The staging copies vanilla's caged spike, not a convenient approximation.</b>
 *       {@code SpikeFeature} guards exactly the two spikes with {@code radius 2}, at heights 79 and
 *       82 — which is why the ladder's crystal sat at {@code y=83} with its cage lid at {@code 85}
 *       and the body at {@code 86}. The obsidian is placed where {@code dx²+dz² <= radius²+1}, the
 *       centre block under the crystal is bedrock, the cage is {@code |dx|==2 || |dz|==2 || dy==3}
 *       over {@code [-2,2]²×[0,3]}, and the crystal sits one block above the standing surface. Get
 *       this wrong in the generous direction — a wider top, a shorter cage — and the arms stop being
 *       about the pillar the bot actually meets.</li>
 *   <li><b>The drop has to be lethal or the fall is not a fall.</b> {@value #VOID_DEPTH} blocks of
 *       clear air ring the pillar down to a catch floor, comfortably past
 *       {@code SurvivalMath.survivableFall} at full health (22).</li>
 *   <li><b>Every row is {@link SceneContext#record}ed on PASS as well as FAIL.</b> The harness prints
 *       the evidence map into the log only on failure, and "on the passing run the block under the
 *       feet is still obsidian after the swing" is the reading that separates these two arms.</li>
 * </ul>
 */
public final class WorldDriverCrystalBlastScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                // PROMOTE TO REQUIRED once a gate run confirms it green — the repo's
                // promote-on-first-green rule, and the pass it is waiting for is a refusal with a
                // reason (BlastFooting), not a change of footing. See the criteria and X1-X3 above.
                Scene.of("wd.crystalBlastOnTheCage", 600,
                        WorldDriverCrystalBlastScenes::crystalBlastOnTheCage),
                // Expected GREEN. Optional only until one gate run confirms it, per the same rule;
                // it is the arm that refuses "never go near a crystal" as a fix.
                Scene.of("wd.crystalBlastOnThePillar", 600,
                        WorldDriverCrystalBlastScenes::crystalBlastOnThePillar));
    }

    // ---------------------------------------------------------------- rig ----

    /** Vanilla's radius for the two GUARDED spikes ({@code SpikeFeature}: {@code 2 + i/3} with the
     *  cage on {@code i == 1 || i == 2}). Obsidian fills {@code dx²+dz² <= r²+1}, so the top is a
     *  5x5 with no corners — and the cage lid above it is a full 5x5 that overhangs them. */
    private static final int PILLAR_RADIUS = 2;

    /** Half-width of the cage in x/z. Vanilla's cage is {@code [-2,2]²} regardless of the pillar. */
    private static final int CAGE_HALF = 2;

    /** Height of the cage: walls at {@code dy 0..2}, lid at {@code dy == 3}, measured from the
     *  standing surface. The lid is what the ladder's body was standing on. */
    private static final int CAGE_LID_DY = 3;

    /** Clear cells between the pillar's ring and the catch floor. Past
     *  {@code SurvivalMath.survivableFall(20) == 22}, so stepping off the pillar is unambiguously a
     *  fall and not a hop. */
    private static final int VOID_DEPTH = 32;

    /** Half-width of the box the rig empties before it builds anything, so nothing generated can be
     *  mistaken for staging. Inside {@link SceneContext#chunkRadius()}'s ±1-chunk window. */
    private static final int CLEAR_HALF = 6;

    /** {@code LivingEntity.invulnerableTime}. Swinging faster than this does less damage, not more —
     *  the same cadence {@code JourneyEndRungs.SwingAt} uses, so the two are comparable. */
    private static final int SWING_EVERY = 20;

    /** The reach {@code JourneyEndRungs} gates its swings on. Copied rather than widened: an arm that
     *  can hit from anywhere is not measuring a stand. */
    private static final double MELEE_REACH = 4.5;

    /** Ticks the loop runs. The first swing lands at {@code t == SWING_EVERY - 1}; the rest is enough
     *  for a body that lost its footing to finish falling {@value #VOID_DEPTH} blocks. */
    private static final int TICKS = 200;

    /** Top solid block of the pillar. Standing surface is one above; the catch floor is
     *  {@link #VOID_DEPTH} below that. Same altitude band as {@code wd.parkourAscend}. */
    private static int pillarTopY(SceneContext ctx) {
        return ctx.origin().getY() + 30;
    }

    // ------------------------------------------------------------- the arms ----

    /**
     * <b>The reproduction.</b> The bot stands on the cage lid, one cell off centre — the offset the
     * ladder's "closest 3.3 blocks" implies for a bot at {@code y=86} over a crystal at {@code y=83}.
     *
     * <p>Expected RED. What it forbids is not approaching the crystal but hitting it while standing
     * on a block that the resulting blast will destroy.
     */
    private static void crystalBlastOnTheCage(SceneContext ctx) {
        smashFrom(ctx, "crystalBlastOnTheCage", CAGE_LID_DY + 1);
    }

    /**
     * <b>The anti-overfit arm.</b> The same pillar, the same cage, the same crystal — the bot simply
     * stands on the pillar-top obsidian inside the cage instead of on its lid.
     *
     * <p>Expected GREEN, and its passing is the whole reason the other arm is allowed to fail: the
     * answer to "the blast removes the footing" is a different stand, not a refusal to approach. A
     * fix that forbids every crystal within reach makes this arm fail and is caught.
     *
     * <p>If this arm is RED, the premise "standing on obsidian is safe" is itself false, and that is a
     * finding about the world rather than about the rig — read {@code landing} and
     * {@code body.inLevelEntityIndex} before touching the staging.
     */
    private static void crystalBlastOnThePillar(SceneContext ctx) {
        smashFrom(ctx, "crystalBlastOnThePillar", 0);
    }

    /**
     * The shared body of both arms. {@code standDy} — how far above the pillar's standing surface the
     * body is placed — is the ONLY input, so any difference between the two rows can only be the
     * block under the feet.
     *
     * @param standDy 0 puts the body on the pillar-top obsidian; {@code CAGE_LID_DY + 1} puts it on
     *                the cage lid
     */
    private static void smashFrom(SceneContext ctx, String name, int standDy) {
        ServerLevel level = ctx.level();
        final int cx = ctx.origin().getX(), cz = ctx.origin().getZ();
        final int topY = pillarTopY(ctx), standY = topY + 1;
        final int standAt = standY + standDy;

        clearAndCatch(level, cx, cz, standY);
        buildSpike(level, cx, cz, topY, standY);

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);

        EndCrystal crystal = new EndCrystal(level, cx + 0.5, standY + 1, cz + 0.5);
        crystal.setShowBottom(true);
        level.addFreshEntity(crystal);
        ctx.cleanup(() -> { if (crystal.isAlive()) crystal.discard(); });
        ctx.cleanup(() -> {
            for (ItemEntity d : level.getEntitiesOfClass(ItemEntity.class, arenaBox(cx, standY, cz)))
                d.discard();
        });

        // Same promotion wait wd.serverBreaksAnEndCrystal uses: a freshly added entity is not in the
        // level's queryable index until the arena promotes it, and a scene that assumes otherwise
        // reports an empty world rather than a slow one.
        ctx.await(() -> !level.getEntitiesOfClass(EndCrystal.class, arenaBox(cx, standY, cz)).isEmpty())
                .within(200).then(() -> swingAndWatch(ctx, name, level, crystal,
                        cx, cz, topY, standY, standAt));
    }

    /** Empty the working box, then hang the catch floor under it. Everything asserted about is built
     *  afterwards, so nothing generated can be mistaken for the rig. */
    private static void clearAndCatch(ServerLevel level, int cx, int cz, int standY) {
        for (int dx = -CLEAR_HALF; dx <= CLEAR_HALF; dx++)
            for (int dz = -CLEAR_HALF; dz <= CLEAR_HALF; dz++) {
                for (int dy = -VOID_DEPTH; dy <= 8; dy++)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, standY + dy, cz + dz),
                            Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(cx + dx, standY - VOID_DEPTH, cz + dz),
                        Blocks.STONE.defaultBlockState());
            }
    }

    /**
     * Vanilla's guarded end spike, to the block: obsidian where {@code dx²+dz² <= r²+1} down to the
     * catch floor, bedrock in the centre of the top course, and the iron cage above it.
     *
     * <p>The lid is a FULL 5x5 while the obsidian top has no corners, exactly as
     * {@code SpikeFeature.place} builds it. That overhang is not decoration: it is the difference
     * between falling back onto the pillar top and falling into the void for a bot standing on the
     * wrong lid cell.
     */
    private static void buildSpike(ServerLevel level, int cx, int cz, int topY, int standY) {
        for (int dx = -PILLAR_RADIUS; dx <= PILLAR_RADIUS; dx++)
            for (int dz = -PILLAR_RADIUS; dz <= PILLAR_RADIUS; dz++) {
                if (dx * dx + dz * dz > PILLAR_RADIUS * PILLAR_RADIUS + 1) continue;
                for (int y = topY; y > standY - VOID_DEPTH; y--)
                    level.setBlockAndUpdate(new BlockPos(cx + dx, y, cz + dz),
                            Blocks.OBSIDIAN.defaultBlockState());
            }
        level.setBlockAndUpdate(new BlockPos(cx, topY, cz), Blocks.BEDROCK.defaultBlockState());
        for (int dx = -CAGE_HALF; dx <= CAGE_HALF; dx++)
            for (int dz = -CAGE_HALF; dz <= CAGE_HALF; dz++)
                for (int dy = 0; dy <= CAGE_LID_DY; dy++) {
                    boolean bar = Math.abs(dx) == CAGE_HALF || Math.abs(dz) == CAGE_HALF
                            || dy == CAGE_LID_DY;
                    if (bar) level.setBlockAndUpdate(new BlockPos(cx + dx, standY + dy, cz + dz),
                            Blocks.IRON_BARS.defaultBlockState());
                }
    }

    /**
     * Put a body on {@code standAt}, let it settle, hit the crystal on vanilla's own cadence, and
     * watch what the blast did to the block it was standing on.
     *
     * <p>The loop is deliberately the dumbest thing that can produce the measurement: every input is
     * released on every tick, so the only forces acting on the body are gravity, collision and the
     * explosion. {@code av.step()} runs the body's own vanilla {@code aiStep}, the same
     * gravity-then-{@code move()} pipeline the client runs — so a body whose footing has been
     * deleted falls, and one whose footing survived does not.
     */
    private static void swingAndWatch(SceneContext ctx, String name, ServerLevel level,
                                      EndCrystal crystal, int cx, int cz, int topY, int standY,
                                      int standAt) {
        ServerPlayerBody av = SceneBody.avatar(ctx, level, cx + 1.5, standAt, cz + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        SimProbes.grantWaterEffects(fp);   // inert here — the body is already invulnerable
        fp.getInventory().clearContent();
        fp.getInventory().add(new ItemStack(Items.IRON_SWORD));
        fp.getInventory().selected = 0;

        final double startY = fp.getY();
        int swings = 0, sinceSwing = 0, firstSwingTick = -1, refused = 0;
        double minYAfterSwing = Double.MAX_VALUE, closest = Double.MAX_VALUE;
        BlockPos swingStand = null;
        String underAtSwing = null, underAfterSwing = null, refusal = null;
        float resAtSwing = Float.NaN;

        for (int t = 0; t < TICKS; t++) {
            av.commandMove(0, 0);
            av.commandJump(false);
            av.breakHold(false);
            sinceSwing++;
            boolean swungNow = false;
            if (crystal.isAlive()) {
                double d = fp.distanceTo(crystal);
                closest = Math.min(closest, d);
                if (d <= MELEE_REACH && sinceSwing >= SWING_EVERY) {
                    if (firstSwingTick < 0) {
                        // Read the stand BEFORE the hit. Asked afterwards it would describe the
                        // world the explosion left, while claiming to describe the one it found.
                        // The resistance is taken here for the same reason, and it is the scene's
                        // OWN derivation — clause 2 feeds it back as a substring test, so a driver
                        // message that names some other block cannot satisfy it.
                        swingStand = fp.blockPosition();
                        underAtSwing = blockIdAt(level, swingStand.below());
                        resAtSwing = level.getBlockState(swingStand.below())
                                .getBlock().getExplosionResistance();
                        firstSwingTick = t;
                    }
                    av.attackEntity(crystal);   // EndCrystal.hurt explodes INSIDE this call
                    sinceSwing = 0;
                    // A refused call is NOT a swing. Counting it as one would print "10 swings"
                    // over a crystal nothing ever touched, and the swings row is the one that says
                    // whether this arm measured anything at all.
                    String why = av.lastAttackRefusal();
                    if (why != null) {
                        refused++;
                        if (refusal == null) refusal = why;
                    } else {
                        swings++;
                        swungNow = true;
                    }
                }
            }
            if (swungNow && underAfterSwing == null)
                underAfterSwing = blockIdAt(level, swingStand.below());
            av.step();
            // Sampled only after the swing tick's own step, so the minimum never contains the y the
            // bot had when it swung — a minimum that includes its own start cannot contradict the start.
            if (firstSwingTick >= 0 && t >= firstSwingTick)
                minYAfterSwing = Math.min(minYAfterSwing, fp.getY());
        }

        // "tried", not "swung": the driver may decline (BlastFooting), and the tick the loop
        // COMMITTED to a swing is still the right anchor for clause 1 — that is the tick from
        // which the footing had to survive, whoever ended up deciding whether the sword moved.
        boolean tried = firstSwingTick >= 0;
        boolean broke = !crystal.isAlive();
        int swingStandY = tried ? swingStand.getY() : Integer.MIN_VALUE;
        // DID THE SOLE SURVIVE? This is the mechanism clause B actually names — the blast must not
        // remove the footing — and it is a question about the WORLD, not about where the bot ended up. Obsidian is
        // 1200 against a power-6 blast, so a cell that is still solid was never taken.
        boolean soleSurvived = tried
                && level.getBlockState(swingStand.below()).getBlock().getExplosionResistance()
                        >= BlastFooting.blastProofResistance(BlastFooting.CRYSTAL_BLAST_POWER);
        // AND WAS THE BODY LAUNCHED OFF IT? Horizontal displacement is the signature of knockback:
        // Explosion.explode applies an impulse along the vector from the blast, and a body whose
        // footing was removed falls STRAIGHT down. Measured on the flipped gates, 2026-08-22:
        // dx=+5 dz=0 dy=−31 with obsidian still under the feet after the swing — the sole was there
        // the whole time.
        boolean launchedSideways = tried
                && (Math.abs(fp.getX() - (swingStand.getX() + 0.5)) > 1.5
                 || Math.abs(fp.getZ() - (swingStand.getZ() + 0.5)) > 1.5);
        // Clause B grades FOOTING. A body that lost height while its sole stood firm and its
        // trajectory carried it sideways was thrown, not dropped, and ballistics is a mechanism this
        // scene does not stage for or measure — see the class note. Exempting it keeps the clause
        // falsifiable by the thing it names: remove the obsidian's resistance and `soleSurvived`
        // goes false, and no amount of knockback can rescue it.
        boolean knockedClear = soleSurvived && launchedSideways;
        boolean heldItsGround = tried && (minYAfterSwing > swingStandY - 2 || knockedClear);
        String resAtSwingText = tried ? String.format(Locale.ROOT, "%.1f", resAtSwing) : null;
        // Clause 2's second half. The block id and the resistance are the scene's OWN readings of
        // the world, taken before the hit, and they are checked as ONE token: iron bars are 6.0 and
        // the blast is 6.0, so two separate contains() calls would be satisfied by the "power=6.0"
        // any refusal carries — the same coincidence that would have made the threshold itself a
        // silent no-op. A refusal that does not quote this exact pair is not a refusal with a reason.
        String footingTag = tried ? BlastFooting.footingTag(underAtSwing, resAtSwing) : null;
        boolean namedTheFooting = refusal != null && footingTag != null
                && refusal.contains(footingTag);
        BlockPos endAt = fp.blockPosition();

        // EVERY row below is written on PASS as well as on FAIL — the harness only prints the
        // evidence map into the log when a scene fails, and the pillar arm's green run carries the
        // control reading (underAfterSwing still obsidian) that makes the cage arm's failure meaningful.
        ctx.record("rig", String.format(Locale.ROOT,
                "solid pillar top y=%d (bedrock centre), standing surface y=%d | cage: |dx|=2 or |dz|=2 or dy=3, y=%d..%d, cage lid y=%d "
                + "| crystal (%d.5,%d,%d.5) | bot start y=%d (standing surface +%d) | %d blocks of air around the pillar down to the catch floor y=%d",
                topY, standY, standY, standY + CAGE_LID_DY, standY + CAGE_LID_DY,
                cx, standY + 1, cz, standAt, standAt - standY, VOID_DEPTH, standY - VOID_DEPTH));
        ctx.record("stand", tried
                ? swingStand.toShortString() + " underFeet=" + underAtSwing
                : "never swung (the crystal was never within " + MELEE_REACH + " blocks, or the bot fell first)");
        ctx.record("underAfterSwing", underAfterSwing == null ? "not sampled (never swung)" : underAfterSwing);
        ctx.record("crystal", broke ? "broken" : "intact");
        ctx.record("minYAfterSwing", tried
                ? String.format(Locale.ROOT, "%.3f (excludes the y=%d at the moment of the swing; criterion > %d)",
                        minYAfterSwing, swingStandY, swingStandY - 2)
                : "not sampled");
        ctx.record("swings", swings + " swings (closest "
                + (closest == Double.MAX_VALUE ? "not measured" : String.format(Locale.ROOT, "%.2f", closest))
                + " blocks, reach limit " + MELEE_REACH + ")");
        // The control reading for clause 2, written on BOTH arms: the pillar arm's "none" is what
        // says the guard did not simply forbid every crystal, and the cage arm's text is the
        // evidence its pass rests on. A passing cage arm with an empty refusal row would mean the
        // clause-3 hole reopened — that combination must never be read as a pass.
        ctx.record("refusal", refusal == null
                ? "none: the driver allowed the swing (resistance under the feet is sufficient; " + swings + " real swings this run)"
                : "refused " + refused + " times / " + swings + " real swings; first reason: " + refusal);
        ctx.record("blastProofThreshold", String.format(Locale.ROOT,
                "power=%.1f ⇒ footing resistance must be ≥ %.1f (13*power/3−0.3, derived in BlastFooting); block under the feet at the swing: %s, resistance %s",
                BlastFooting.CRYSTAL_BLAST_POWER,
                BlastFooting.blastProofResistance(BlastFooting.CRYSTAL_BLAST_POWER),
                tried ? underAtSwing : "-", tried ? resAtSwingText : "not sampled"));
        ctx.record("landing", String.format(Locale.ROOT, "%s underFeet=%s (start y=%.1f, net drop %.1f blocks)",
                endAt.toShortString(), blockIdAt(level, endAt.below()), startY, startY - fp.getY()));
        ctx.record("cageBarsLeft", barsLeft(level, cx, cz, standY) + "/" + cageCells()
                + " iron bars (blast resistance 6.0, destroyed by a power-6 blast; obsidian at 1200 is not)");
        // The reading that says what this scene CANNOT see. A bot that never joined the level is not
        // in its entity index, so Explosion.explode never finds it and never launches it; a joined
        // bot IS, and is launched. A passing pillar arm is not a claim about knockback.
        ctx.record("body.inLevelEntityIndex", (level.getEntity(fp.getId()) != null)
                + " (false = the bot cannot receive blast knockback in this run; true = it can and the trajectory is real, but this scene still grades only the footing)");
        // WHAT THE VERDICT IGNORED, and why that is not the same as "there is no trajectory". Written
        // on every run, including the ones where nothing was exempted, so a reader can tell "not
        // thrown" from "thrown, but this clause does not grade it" — those are different outcomes
        // that a bare pass would print identically.
        ctx.record("trajectory", !tried ? "not sampled"
                : !launchedSideways
                    ? "the bot did not move horizontally (footing " + (soleSurvived ? "survived" : "was removed")
                        + "); no knockback to exempt in this run"
                    : soleSurvived
                        ? "the bot was thrown: horizontal move " + swingStand.toShortString() + " → "
                            + endAt.toShortString() + ", while the block under the feet at the swing, "
                            + blockIdAt(level, swingStand.below()) + ", was sufficiently resistant and never removed. "
                            + "Classified as knockback; clause B is exempted. WARNING: the knockback is real and this "
                            + "scene does not measure it (no test setup and no criterion for it); do not read this row "
                            + "as \"the blast cannot move the bot\""
                        : "the bot moved horizontally and the footing was also removed; not exempted, clause B fails as usual");
        ctx.record("gamerule.blockExplosionDropDecay", String.valueOf(
                level.getGameRules().getBoolean(GameRules.RULE_BLOCK_EXPLOSION_DROP_DECAY)));
        ctx.record("gamerule.mobGriefing", level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                + " (recorded for reference: ExplosionInteraction.BLOCK ignores this rule; only MOB reads it)");
        WorldDriverCommon.LOG.info("[wd.{}] standAt={} swung={}@{} refused={} under={}->{} broke={} "
                + "minY={} end={} inIndex={}", name, standAt, swings, firstSwingTick, refused,
                underAtSwing, underAfterSwing, broke, minYAfterSwing, endAt,
                level.getEntity(fp.getId()) != null);

        // Soft checks, so both verdicts are always reported: "the crystal survived unexplained" and
        // "the body was dropped" are different failures and one merged line prints them the same.
        ctx.check(broke || namedTheFooting).as("A: either break the crystal or refuse with a reason: the crystal is "
                + (broke ? "broken" : "intact")
                + ", the driver " + (refusal == null ? "gave no refusal reason" : "refused " + refused + " times")
                + (namedTheFooting ? " and named the footing (" + footingTag + ")"
                        : refusal == null ? "" : " but the reason does not contain the paired token [" + footingTag
                                + "], so it does not count as a reason (matching the parts separately would be fooled by power=6.0)")
                + ". Neither broken nor refused with a footing reading means FAIL: this clause stops a run that never "
                + "swung from passing as 0==0 (" + swings + " real swings this run, closest "
                + (closest == Double.MAX_VALUE ? "not measured"
                        : String.format(Locale.ROOT, "%.2f", closest)) + " blocks)").isTrue();
        ctx.check(heldItsGround).as("B: the blast must not remove the footing: at the swing (including a refused one) the bot stood at "
                + (tried ? swingStand.toShortString() + " (under the feet: " + underAtSwing + ")" : "-")
                + ", lowest y afterwards=" + (tried ? String.format(Locale.ROOT, "%.3f", minYAfterSwing) : "not sampled")
                + ", criterion > " + (tried ? String.valueOf(swingStandY - 2) : "no anchor")
                + (knockedClear ? " (exempted this run: the footing stayed sufficient and the bot was thrown sideways "
                        + "by the blast, see the trajectory row; the height loss is a consequence of knockback, "
                        + "not of the footing being removed)" : "")
                + ". The anchor is the standing y at the moment the swing was decided, not where the test setup "
                + "placed the bot. WARNING: this arm currently passes through a refusal with a reason, not through "
                + "a change of footing; changing footing requires X1/X2/X3, see the class comment").isTrue();
    }

    // ------------------------------------------------------------- readings ----

    private static String blockIdAt(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    /** Iron bars still standing anywhere in the cage's shell — the direct reading of whether the blast destroyed the cage,
     *  which the footing row alone cannot give (one surviving cell under the feet would hide it). */
    private static int barsLeft(ServerLevel level, int cx, int cz, int standY) {
        int n = 0;
        for (int dx = -CAGE_HALF; dx <= CAGE_HALF; dx++)
            for (int dz = -CAGE_HALF; dz <= CAGE_HALF; dz++)
                for (int dy = 0; dy <= CAGE_LID_DY; dy++) {
                    boolean bar = Math.abs(dx) == CAGE_HALF || Math.abs(dz) == CAGE_HALF
                            || dy == CAGE_LID_DY;
                    if (bar && level.getBlockState(new BlockPos(cx + dx, standY + dy, cz + dz))
                            .is(Blocks.IRON_BARS)) n++;
                }
        return n;
    }

    /** How many cells {@link #buildSpike} filled with bars, so {@code cageBarsLeft} is a fraction rather
     *  than a bare count nobody can scale. */
    private static int cageCells() {
        int n = 0;
        for (int dx = -CAGE_HALF; dx <= CAGE_HALF; dx++)
            for (int dz = -CAGE_HALF; dz <= CAGE_HALF; dz++)
                for (int dy = 0; dy <= CAGE_LID_DY; dy++)
                    if (Math.abs(dx) == CAGE_HALF || Math.abs(dz) == CAGE_HALF
                            || dy == CAGE_LID_DY) n++;
        return n;
    }

    private static AABB arenaBox(int cx, int standY, int cz) {
        return new AABB(cx - CLEAR_HALF, standY - VOID_DEPTH, cz - CLEAR_HALF,
                cx + CLEAR_HALF + 1, standY + 8, cz + CLEAR_HALF + 1);
    }
}
