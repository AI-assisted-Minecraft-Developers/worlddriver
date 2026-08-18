package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.movement.BlastFooting;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
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
 * Rung 20 of the journey ladder (2026-08-18) recorded a pair of rows that only make sense together:
 *
 * <pre>{@code
 * crystal.4.leg   ... → 终点=-33,86,23  脚下=Block{minecraft:iron_bars}  end=arrived
 * crystal.4.result 碎了（站到 y=86，最近 3.3 格，挥 1 刀）
 * crystal.5.leg   起点=-33,86,23  脚下=Block{minecraft:air}  → 最低y=-5220
 * }</pre>
 *
 * Same coordinate, {@code iron_bars} under the body before the swing and {@code air} under it after,
 * and the only thing that happened in between was one hit on an end crystal. Vanilla's
 * {@code EndCrystal.hurt} answers every hit with
 * {@code level.explode(this, …, 6.0F, false, ExplosionInteraction.BLOCK)}; {@code Level.explode}
 * maps {@code BLOCK} onto {@code getDestroyType(RULE_BLOCK_EXPLOSION_DROP_DECAY)}, which is
 * {@code DESTROY} or {@code DESTROY_WITH_DECAY} and <b>never</b> {@code KEEP} — so unlike a creeper
 * this blast is not gated on {@code mobGriefing}. Iron bars have explosion resistance 6.0 and
 * obsidian has 1200. The blast therefore eats the cage and leaves the pillar, and a body that
 * climbed onto the cage to get in range is standing on the half that goes.
 *
 * <p>Rungs 5–9 of that run are all free fall ({@code 脚下=void_air}, {@code 放了 0 块}), i.e. every
 * later crystal order was given to a body that was no longer standing anywhere. One hit, nine rungs.
 *
 * <h2>What these two scenes are</h2>
 *
 * A matched pair whose <b>only</b> variable is the block under the body's feet. Same pillar, same
 * cage, same crystal, same inventory, same tick loop; one arm stands on the cage lid and one stands
 * on the pillar-top obsidian one block from the crystal. Neither touches the product — they are
 * sensors, shipped {@link Scene#withRequired(boolean) withRequired(false)} under this repo's
 * promote-on-first-green rule.
 *
 * <ul>
 *   <li>{@code wd.crystalBlastOnTheCage} — it falsifies「砍水晶时可以站在笼上」. <b>The way it goes
 *       green is a REFUSAL</b>, not a better stand: {@code BlastFooting} declines the swing and
 *       names the footing, so the crystal survives and the body keeps its lid. Read 判据 and the
 *       X1/X2/X3 note below before assuming this arm proves the bot can relocate — it cannot, and
 *       nothing here has ever asked it to.</li>
 *   <li>{@code wd.crystalBlastOnThePillar} — <b>expected GREEN, and it is the anti-overfit arm.</b>
 *       Without it,「一律不许靠近水晶，远远地放弃」is a full-marks answer to the arm above. This one
 *       asserts positively that the crystal still gets broken from a blast-proof stand.</li>
 * </ul>
 *
 * <h2>判据 — three clauses, and none of them may be dropped</h2>
 *
 * <ol>
 *   <li><b>落脚不许被抽走.</b> Both arms assert {@code 最低y(挥刀之后) > 挥刀时站立y − 2}. Anchoring
 *       instead on the pillar top would make the cage arm <b>green while measuring nothing</b>: with
 *       no knockback in play (see below) a body whose lid was destroyed simply drops the four blocks
 *       onto the obsidian, which is still「在柱子上」. This is the real invariant and it is
 *       unchanged.</li>
 *   <li><b>要么砸碎，要么带理由地拒绝.</b> {@code 水晶=碎了}, <i>or</i> the driver's
 *       {@code Avatar.lastAttackRefusal} is non-empty AND carries
 *       {@code BlastFooting.footingTag(块id, 抗性)} for the block the scene itself read under the
 *       swing stand before the hit. The pair is checked as ONE token on purpose: iron bars are 6.0
 *       and the blast is 6.0, so asking separately for the id and for「6.0」would be satisfied by
 *       the {@code power=6.0} every refusal prints. A message that merely says「不行」, or names
 *       another block, or the right block with a wrong number, does not satisfy it — that is what
 *       keeps this clause out of「只要报个错就算过」.</li>
 *   <li><b>⛔ 既没砸碎、也没有理由 ⇒ 红.</b> The clause that plugs the {@code 0==0} hole: a run that
 *       never swings, or one where the driver silently declined, fails here. Without it a body that
 *       stood still for 200 ticks would pass clause 1 perfectly.</li>
 * </ol>
 *
 * <p><b>今天这条臂转绿的方式是第 2 条的后半句 —— 带理由地拒绝，不是站到黑曜石上砍.</b> Do not read a
 * green row here as「bot 会自己换落脚了」. It does not, and this rig could not observe it if it did:
 *
 * <ul>
 *   <li><b>X1 — a process that owns「接近 + 挥刀」两步.</b> {@code Avatar.attackEntity} is one-shot
 *       and single-tick; it can swing or decline, and it must never teleport. Choosing a stand is a
 *       multi-tick job and belongs to whatever walks the body in ({@code SwingAt} on rung 20,
 *       {@code CombatProcess} in production).</li>
 *   <li><b>X2 — the scene must hand control over BEFORE it latches the stand it judges.</b>
 *       {@link #swingAndWatch} reads {@code swingStand} on the line above the driver call, so a
 *       relocation performed inside that call is invisible to the anchor and reads as a fall. A rig
 *       that wants to grade stand-choosing has to drive the X1 process tick by tick instead of
 *       calling the verb itself.</li>
 *   <li><b>X3 — the staging must contain a blast-proof stand the body can REACH.</b> In vanilla's
 *       caged spike there is exactly one — the 3x3 obsidian floor inside the cage — and it is sealed
 *       under a solid 5x5 iron lid, four blocks below a body standing on that lid. Every blast-proof
 *       sole row in this arena is at {@code y = 柱顶}, i.e. {@code 挥刀站立y − 4}, so clause 1's
 *       {@code −2} tolerance can never be met by relocating. That is a fact about the geometry
 *       vanilla builds, not about this file: fixing it means a different staging (or a bot that
 *       breaks in), never a looser number here.</li>
 * </ul>
 *
 * <p>{@code 最低y} <b>excludes the swing tick's own y</b>, for the reason this ladder has already
 * paid for once: a minimum that includes its own starting sample can never contradict the start, and
 * a healthy arm was judged red over exactly that.
 *
 * <h2>Two limits stated on the row rather than left to be discovered</h2>
 *
 * <ul>
 *   <li><b>This body cannot be knocked by the blast, and the ladder's can.</b>
 *       {@code Explosion.explode} collects victims with {@code level.getEntities(source, aabb)},
 *       which reads the level's entity index. A gate-run body is an {@code AvatarFakePlayer} /
 *       NeoForge {@code FakePlayer} that was never added to the level, so it is absent from that
 *       index; the journey run arms {@code -Dworlddriver.realPlayerBodies=true} and its body JOINED,
 *       so it is present and does take the launch. That difference is recorded as
 *       {@code body.inLevelEntityIndex} on every run. It makes these scenes a <b>clean</b> reading of
 *       the footing — the one mechanism they claim — and it means they cannot see the ballistics.
 *       Do not read a green pillar arm as「爆炸不会把身体掀走」.</li>
 *   <li><b>No Walker, no goal, no {@code LevelWorldView}.</b> The sibling void scenes drive a Walker
 *       because their subject is a leap; here the subject is which block is under the feet, and
 *       steering would put a second variable between the two arms. The body is created and stepped
 *       exactly the way {@code wd.parkourVoidShortRunway} creates and steps its own —
 *       {@link ServerPlayerAvatar#createUnique} plus a synchronous {@code av.step()} loop — with
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
 *       the evidence map into the log only on failure, and「绿的那一趟砍完脚下还是黑曜石」is the
 *       reading that separates these two arms.</li>
 * </ul>
 */
public final class WorldDriverCrystalBlastScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                // PROMOTE TO REQUIRED once a gate run confirms it green — the repo's
                // promote-on-first-green rule, and the green it is waiting for is「带理由地拒绝」
                // (BlastFooting), not「换了个落脚」. See 判据 / X1-X3 above.
                Scene.of("wd.crystalBlastOnTheCage", 600,
                        WorldDriverCrystalBlastScenes::crystalBlastOnTheCage).withRequired(false),
                // Expected GREEN. Optional only until one gate run confirms it, per the same rule;
                // it is the arm that refuses "never go near a crystal" as a fix.
                Scene.of("wd.crystalBlastOnThePillar", 600,
                        WorldDriverCrystalBlastScenes::crystalBlastOnThePillar).withRequired(false));
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
     * <b>The reproduction.</b> The body stands on the cage lid, one cell off centre — the offset the
     * ladder's {@code 最近 3.3 格} implies for a body at {@code y=86} over a crystal at {@code y=83}.
     *
     * <p>Expected RED. What it forbids is not「靠近水晶」but「站在会被自己这一炸拆掉的方块上砍它」.
     */
    private static void crystalBlastOnTheCage(SceneContext ctx) {
        smashFrom(ctx, "crystalBlastOnTheCage", CAGE_LID_DY + 1);
    }

    /**
     * <b>The anti-overfit arm.</b> The same pillar, the same cage, the same crystal — the body simply
     * stands on the pillar-top obsidian inside the cage instead of on its lid.
     *
     * <p>Expected GREEN, and its greenness is the whole reason the other arm is allowed to be red:
     * the answer to「爆炸会抽走落脚」is a different stand, not a refusal to approach. A fix that
     * forbids every crystal within reach reddens this arm and is caught.
     *
     * <p>If this arm is RED, the premise「站黑曜石就没事」is itself false, and that is a finding about
     * the world rather than about the rig — read {@code 落点} and {@code body.inLevelEntityIndex}
     * before touching the staging.
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
     * between「掉回柱顶」and「掉进虚空」for a body standing on the wrong lid cell.
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
     * explosion. {@code av.step()} runs {@code fp.travel(...)}, which is the same
     * gravity-then-{@code move()} pipeline the client's {@code aiStep} runs — so a body whose footing
     * has been deleted falls, and one whose footing survived does not.
     */
    private static void swingAndWatch(SceneContext ctx, String name, ServerLevel level,
                                      EndCrystal crystal, int cx, int cz, int topY, int standY,
                                      int standAt) {
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, cx + 1.5, standAt, cz + 0.5);
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
                    // A refused call is NOT a swing. Counting it as one would print「挥了 10 刀」
                    // over a crystal nothing ever touched, and 挥刀 is the row that says whether
                    // this arm measured anything at all.
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
            // Sampled only after the swing tick's own step, so 最低y never contains the y the body
            // had when it swung — a minimum that includes its own start cannot contradict the start.
            if (firstSwingTick >= 0 && t >= firstSwingTick)
                minYAfterSwing = Math.min(minYAfterSwing, fp.getY());
        }

        // "tried", not "swung": the driver may decline (BlastFooting), and the tick the loop
        // COMMITTED to a swing is still the right anchor for clause 1 — that is the tick from
        // which the footing had to survive, whoever ended up deciding whether the sword moved.
        boolean tried = firstSwingTick >= 0;
        boolean broke = !crystal.isAlive();
        int swingStandY = tried ? swingStand.getY() : Integer.MIN_VALUE;
        boolean heldItsGround = tried && minYAfterSwing > swingStandY - 2;
        String resAtSwingText = tried ? String.format(Locale.ROOT, "%.1f", resAtSwing) : null;
        // Clause 2's second half. The block id and the resistance are the scene's OWN readings of
        // the world, taken before the hit, and they are checked as ONE token: iron bars are 6.0 and
        // the blast is 6.0, so two separate contains() calls would be satisfied by the 「power=6.0」
        // any refusal carries — the same coincidence that would have made the threshold itself a
        // silent no-op. A refusal that does not quote this exact pair is not「带理由地拒绝」.
        String footingTag = tried ? BlastFooting.footingTag(underAtSwing, resAtSwing) : null;
        boolean namedTheFooting = refusal != null && footingTag != null
                && refusal.contains(footingTag);
        BlockPos endAt = fp.blockPosition();

        // EVERY row below is written on PASS as well as on FAIL — the harness only prints the
        // evidence map into the log when a scene fails, and the pillar arm's green run carries the
        // control reading (砍后脚下 still obsidian) that makes the cage arm's red mean anything.
        ctx.record("rig", String.format(Locale.ROOT,
                "柱顶实心 y=%d（中心基岩）站立面 y=%d | 笼: |dx|=2或|dz|=2或dy=3, y=%d..%d, 笼盖 y=%d "
                + "| 水晶 (%d.5,%d,%d.5) | 身体起始站位 y=%d（站立面 +%d）| 柱周围 %d 格空到接住地板 y=%d",
                topY, standY, standY, standY + CAGE_LID_DY, standY + CAGE_LID_DY,
                cx, standY + 1, cz, standAt, standAt - standY, VOID_DEPTH, standY - VOID_DEPTH));
        ctx.record("站位", tried
                ? swingStand.toShortString() + " 脚下=" + underAtSwing
                : "从没挥过刀（水晶始终不在 " + MELEE_REACH + " 格内，或身体先掉了）");
        ctx.record("砍后脚下", underAfterSwing == null ? "未采样（没挥过刀）" : underAfterSwing);
        ctx.record("水晶", broke ? "碎了" : "还在");
        ctx.record("最低y", tried
                ? String.format(Locale.ROOT, "%.3f（不含挥刀那一刻的 y=%d；判据 > %d）",
                        minYAfterSwing, swingStandY, swingStandY - 2)
                : "未采样");
        ctx.record("挥刀", swings + " 刀（最近 "
                + (closest == Double.MAX_VALUE ? "未测" : String.format(Locale.ROOT, "%.2f", closest))
                + " 格，门限 " + MELEE_REACH + "）");
        // The control reading for clause 2, written on BOTH arms: the pillar arm's「无」is what
        // says the guard did not simply forbid every crystal, and the cage arm's text is the
        // evidence its green rests on. A green cage arm with an empty 拒绝 row would mean the
        // clause-3 hole reopened — that combination must never be read as a pass.
        ctx.record("拒绝", refusal == null
                ? "无 —— 驱动放行（脚下抗性合格；本趟真正挥出 " + swings + " 刀）"
                : "被拒 " + refused + " 次 / 真正挥出 " + swings + " 刀；首次理由：" + refusal);
        ctx.record("抗爆门槛", String.format(Locale.ROOT,
                "power=%.1f ⇒ 落脚抗性需 ≥ %.1f（13*power/3−0.3，推导见 BlastFooting）；挥刀那一格脚下 %s 抗性 %s",
                BlastFooting.CRYSTAL_BLAST_POWER,
                BlastFooting.blastProofResistance(BlastFooting.CRYSTAL_BLAST_POWER),
                tried ? underAtSwing : "——", tried ? resAtSwingText : "未采样"));
        ctx.record("落点", String.format(Locale.ROOT, "%s 脚下=%s（起始 y=%.1f，净掉 %.1f 格）",
                endAt.toShortString(), blockIdAt(level, endAt.below()), startY, startY - fp.getY()));
        ctx.record("笼子残存", barsLeft(level, cx, cz, standY) + "/" + cageCells()
                + " 块铁栏杆（爆炸抗性 6.0，power 6 的爆炸吃得掉；黑曜石 1200 吃不掉）");
        // The reading that says what this scene CANNOT see. A gate-run body is not in the level's
        // entity index, so Explosion.explode never finds it and never launches it; the journey's
        // joined body IS, and does. A green pillar arm is not a claim about knockback.
        ctx.record("body.inLevelEntityIndex", (level.getEntity(fp.getId()) != null)
                + "（false = 这一趟身体收不到爆炸击退，只测落脚，不测弹道）");
        ctx.record("gamerule.blockExplosionDropDecay", String.valueOf(
                level.getGameRules().getBoolean(GameRules.RULE_BLOCK_EXPLOSION_DROP_DECAY)));
        ctx.record("gamerule.mobGriefing", level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                + "（记录用：ExplosionInteraction.BLOCK 不看这条，看它的是 MOB）");
        WorldDriverCommon.LOG.info("[wd.{}] standAt={} swung={}@{} refused={} under={}->{} broke={} "
                + "minY={} end={} inIndex={}", name, standAt, swings, firstSwingTick, refused,
                underAtSwing, underAfterSwing, broke, minYAfterSwing, endAt,
                level.getEntity(fp.getId()) != null);

        // Soft checks, so both verdicts are always reported: "the crystal survived unexplained" and
        // "the body was dropped" are different failures and one merged line prints them the same.
        ctx.check(broke || namedTheFooting).as("A 要么砸碎，要么带理由地拒绝：水晶"
                + (broke ? "碎了" : "还在")
                + "，驱动" + (refusal == null ? "没有给出拒绝理由" : "拒绝了 " + refused + " 次")
                + (namedTheFooting ? "并点名了落脚（" + footingTag + "）"
                        : refusal == null ? "" : "但理由里找不到成对的【" + footingTag
                                + "】，那不算带理由（分开匹配会被 power=6.0 蒙混过去）")
                + "。⛔ 既没砸碎、也没有带落脚读数的理由 = 红：这一条堵的是「从没挥过刀」以 0==0 白过"
                + "（本趟真正挥出 " + swings + " 刀，最近 "
                + (closest == Double.MAX_VALUE ? "未测"
                        : String.format(Locale.ROOT, "%.2f", closest)) + " 格）").isTrue();
        ctx.check(heldItsGround).as("B 爆炸不许抽走落脚：挥刀（含被拒的那一次）时站在 "
                + (tried ? swingStand.toShortString() + "（脚下 " + underAtSwing + "）" : "——")
                + "，其后最低 y=" + (tried ? String.format(Locale.ROOT, "%.3f", minYAfterSwing) : "未采样")
                + "，判据 > " + (tried ? String.valueOf(swingStandY - 2) : "无锚点")
                + "。锚点是【决定挥刀那一刻】的站立 y，不是布景放下的位置。⚠️ 这条臂今天绿在【带理由地"
                + "拒绝】上，不是绿在【换了落脚】上——换落脚要 X1/X2/X3，见类注释").isTrue();
    }

    // ------------------------------------------------------------- readings ----

    private static String blockIdAt(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    /** Iron bars still standing anywhere in the cage's shell — the direct reading of「炸没炸掉笼子」,
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

    /** How many cells {@link #buildSpike} filled with bars, so {@code 笼子残存} is a fraction rather
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
