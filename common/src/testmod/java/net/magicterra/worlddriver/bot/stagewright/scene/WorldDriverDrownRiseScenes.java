package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.BotHooks;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;

/**
 * Did the player actually RISE — asked of the real client, because nothing else can ask it.
 *
 * <h2>The hole these fill</h2>
 *
 * The drowning family had four members before this file and every one of them stopped short of the
 * thing that kills players:
 *
 * <ul>
 *   <li>{@code wd.drowningFloatShouldFloatMatrix} — PURE, four boolean rows over
 *       {@code DrowningFloatGate.shouldFloat}. The word {@code should} is literal.</li>
 *   <li>{@code wd.drownEscapeGateMatrix} — PURE, 18 gate rows + 8 lifecycle rows.</li>
 *   <li>{@code wd.drownEscapePreempt} — mints a fake player, feeds {@code sensorForTest}, and
 *       asserts the scheduler's <b>bookkeeping</b>.</li>
 *   <li>{@code wd.drownEscapeSurface} — does assert a rise, but of the <b>Walker's</b> escape
 *       ({@code walkerDrowningEscape}), on a {@code ServerPlayerBody}, with the air supply
 *       simulated by hand. It never constructs {@code DrownEscapeChain}.</li>
 * </ul>
 *
 * <p>{@code DrownEscapeChain.tick} opens with {@code if (mc == null) return;} — so the entire
 * execution layer of the reflex that outranks every user process is unreachable from a dedicated
 * server, which is where all four of those run. Twenty-six assertions on "should it rise", zero on
 * "did it actually rise".
 *
 * <p><b>What that cost.</b> Integrated ladder, 2026-08-22, BED rung: the reflex preempted at
 * {@code air=100}, held the movement channel for 261 ticks, and the bot drowned at
 * {@code -28,61,79} without moving one block. The whole in-window log is two scheduler handovers —
 * that arm printed nothing per tick and its lid-break sub-arm printed nothing ever.
 *
 * <h2>Why the pinned arm is the one that matters</h2>
 *
 * The kinematics of that death already exclude two of the three candidate causes. The player neither
 * rose nor sank for 261 ticks, while the corpse — the same player one tick later, with the reflex
 * released — sank at ~0.02/tick. A player with no lift sinks; a player with lift and a clear path
 * surfaces and its air recovers. Neither happened, so buoyancy was applying and <b>a collision face
 * was in the way</b> — and every scan in {@code DrownEscapeChain} ({@code cappedColumn}, the
 * {@code lid} cell, {@code nearestBreathable}) looks at exactly one column, the one
 * {@code blockPosition()} names.
 *
 * <p>A player at {@code x=-27.716} has its bounding box edge at {@code -28.016}: 0.016 of it is in
 * the NEXT column, which no scan here ever asks about. {@link #pinnedByNeighbourColumn} stages that
 * geometry deliberately and with a large margin, so a failure means the blindness is real and a
 * pass means the neighbour column is not the mechanism and the search moves on. An open-water
 * arm alone could not tell those apart: it would go green today and go on being green through the
 * exact death it was written for.
 */
public final class WorldDriverDrownRiseScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.drownEscapeClientRisesInOpenWater", 400,
                        WorldDriverDrownRiseScenes::risesInOpenWater),
                // REQUIRED. The failure it recorded (net rise 0.000 blocks over 200 ticks, lid
                // blocked=false, hit ceiling=true) settled the hypothesis and the blindness is fixed.
                // A sensor that stays optional after its subject is understood will pass by
                // accident on the day it regresses.
                Scene.of("wd.drownEscapeClientPinnedByNeighbourColumn", 400,
                        WorldDriverDrownRiseScenes::pinnedByNeighbourColumn),
                Scene.of("wd.drownEscapeClientStaysDownDisarmed", 400,
                        WorldDriverDrownRiseScenes::staysDownDisarmed),
                // The arm the ladder of 2026-08-26 needed and nobody had. The three above cover the
                // vertical arm and the disarmed counter-arm; NONE of them ever enters the lateral
                // one — measured on the integrated gate the day this went in: `CAPPED lid` rows = 0
                // across the whole run, because `cappedColumn` is false in every arena above.
                Scene.of("wd.drownEscapeClientBreaksTheLidWhenOpenWaterIsWalledOff", 900,
                        ctx -> breaksTheLid(ctx, 0)),
                // THE SAME ARM OVER WATER, and it is the control the pair above cannot be read
                // without. Standing up to dig is guarded on "the cell under the feet can be stood
                // on", and a guard is only worth what its NEGATIVE arm is worth: over water the
                // jump must stay held, because a bot that sinks in a deep pocket drifts out of
                // RISE_PROBE's half-block reach, loses the lid, and starts a jump/sink oscillation
                // that resets the break progress every cycle. Three water cells under the foot is
                // the shallowest staging that is unambiguously "deeper than two".
                //
                // Read the two `lid.broken` numbers side by side: the shallow arm should fall to about
                // a fifth once the bot grounds, and THIS one should not move at all. A single
                // number cannot tell "the fix worked" from "the dig got cheaper for some other
                // reason"; two numbers whose ratio is predicted in advance can.
                Scene.of("wd.drownEscapeClientKeepsFloatingWhenThePocketIsDeep", 900,
                        ctx -> breaksTheLid(ctx, 3)));
    }

    /** Ticks the bot is given to reach air. Ten times the ~20 ticks a free rise over this column
     *  takes, so a failure cannot be read as "the budget was too tight" — and far below the ~240 ticks that separate the
     *  staged {@code air=40} from a drowning death, so a red never costs the client its life. */
    private static final int BUDGET = 200;

    /** Ticks between staging the blocks and using them. The blocks are written on the SERVER and the
     *  player that must be submerged in them is the CLIENT's; the chunk packets take a round trip,
     *  and a player judged before they land is judged against water it cannot see. */
    private static final int SYNC_TICKS = 20;

    /** Air to stage. Below {@code drownEscapeAirThreshold} (100) so the latch engages on the first
     *  evaluation — waiting for 200 real ticks of drowning to reach it would spend the budget on the
     *  precondition instead of on the subject. */
    private static final int STAGED_AIR = 40;

    // ------------------------------------------------------------------ the three arms

    /** Open column, nothing overhead: the bot must reach air. This is the arm that says the
     *  actuation path works at all — command channel, scheduler, client physics, end to end. */
    private static void risesInOpenWater(SceneContext ctx) {
        run(ctx, false, true);
    }

    /** The regression. Same open column by every scan this class performs, but a solid block in the
     *  neighbouring column the bot's own bounding box straddles. Nothing in
     *  {@code DrownEscapeChain} looks there, so the bot should be pinned one block below air with
     *  the reflex reporting a clear path. */
    private static void pinnedByNeighbourColumn(SceneContext ctx) {
        run(ctx, true, true);
    }

    /**
     * The counter-arm: with the reflex disarmed the bot must NOT reach air.
     *
     * <p>Both flags, not one. {@code autoDrownEscape} switches off the chain; {@code
     * autoFloatWhenDrowning} switches off {@code AutoSwim.drowningSentinel}, which holds jump on an
     * IDLE bot — and a bot parked in a test column with no process is exactly idle. Disarming only
     * the chain would leave the sentinel to surface the bot, and this arm would then fail while
     * describing the wrong subject. Its whole job is to prove the other two arms' green is not free.
     */
    private static void staysDownDisarmed(SceneContext ctx) {
        run(ctx, false, false);
    }

    /** Ticks the lid arm gets. Dirt is ~15 ticks bare-handed, ×5 for a submerged head and ×5 again
     *  for a player with no ground under it — call it ~375, and a budget that cannot outlast the
     *  thing it measures would report the measurement as a failure. */
    private static final int LID_BUDGET = 600;

    /** Air held under {@link BotConfig#drownEscapeAirThreshold} for the whole lid arm, so the latch
     *  stays engaged without the bot ever drowning. The other arms can let air fall because they
     *  finish in tens of ticks; this one runs for hundreds, and killing the human's client to
     *  measure a dig time is not a trade this suite makes. It also means the tick count this arm
     *  records is a DIG time, never a survival result — see the failure text. */
    private static final int PINNED_AIR = 90;

    /**
     * Capped pocket, open water walled off: the bot must turn to the LID.
     *
     * <p>Live death #31, the real ladder of 2026-08-26 rung 9. The bot sat in a 1×1 pocket at
     * 81,59,82 under a dirt lid. {@code nearestBreathable} chose 81,59,80 — a column that genuinely
     * surfaces — and 81,59,81, the one cell between, is stone. The reflex held {@code forward} into
     * that stone for 532 ticks at a horizontal speed of exactly 0.0000 and the bot drowned without
     * moving one block. The lid was never touched, because a non-null lateral target is precisely
     * what skips the lid-break.
     *
     * <p>So the subject here is neither "can it rise" nor "does it survive". It is: <b>with open
     * water in range but walled off, does the bot end up attacking the lid?</b> The old code cannot pass
     * this — it swims sideways into rock until the budget runs out and the lid is still there.
     *
     * <p>The air supply is pinned under the threshold rather than allowed to drain, so this measures
     * a dig, not a race. Whether the dig fits inside a real breath is arithmetic on the recorded
     * number against ~290 ticks (100 of air, then 19 HP at 2 per 20), and it is recorded as such —
     * this scene deliberately asserts nothing about it, because nobody has measured it.
     *
     * @param underFoot how many water cells sit BELOW the pocket's foot cell. Zero puts the bot on
     *        rock and lets the dig ground itself; three puts it over water, where the stand-up
     *        guard must decline and the arm must behave exactly as it did before the guard existed.
     */
    private static void breaksTheLid(SceneContext ctx, int underFoot) {
        MinecraftServer server = ctx.server();
        boolean integrated = server != null && !server.isDedicatedServer();
        ctx.record("topology", (integrated ? "integratedServer" : "dedicatedServer")
                + ", mc.bot.* in this JVM=" + BotHooks.isAvailable());
        if (!integrated || !BotHooks.isAvailable()) {
            ctx.skip("DrownEscapeChain's execution layer requires a real LocalPlayer in the same JVM: "
                    + "the first line of tick() is if (mc == null) return. On a dedicated server this coverage is not weak, it is zero.");
        }
        List<ServerPlayer> humans = SceneBody.humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("no real player on the integrated server: the client has not entered the world yet, or has disconnected");
        }
        ServerPlayer body = humans.get(0);
        ServerLevel level = ctx.level();

        BlockPos o = ctx.origin();
        final int ox = o.getX(), oz = o.getZ();
        final int y0 = o.getY() + 8;                     // the pocket's floor

        // Shell first, then carve — an unwalled pocket drains and the bot is judged in air it made
        // itself. Same order as the other arms.
        // Same footprint as run()'s basin (±4, y0-1..y0+7) on purpose: the arenas sit side by side
        // and a scene that reaches further than its neighbours is a scene that stages theirs.
        // Deep enough to hold the variant's own water column — the shell has to reach BELOW the
        // deepest water cell or the pocket drains out of its own floor and the bot is judged in
        // air it made itself.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int y = y0 - 1 - underFoot; y <= y0 + 7; y++)
                    level.setBlockAndUpdate(new BlockPos(ox + dx, y, oz + dz), Blocks.STONE.defaultBlockState());

        // The pocket: 1×1, two cells of water, so the eye is submerged with the feet on the floor.
        // `underFoot` extends it DOWNWARD only: the lid, the bait and the bot's entry cell are the
        // same in both variants, so the one thing that differs between them is what the stand-up
        // guard reads under the feet.
        final BlockPos foot = new BlockPos(ox, y0, oz);
        final BlockPos head = new BlockPos(ox, y0 + 1, oz);
        level.setBlockAndUpdate(foot, Blocks.WATER.defaultBlockState());
        level.setBlockAndUpdate(head, Blocks.WATER.defaultBlockState());
        for (int d = 1; d <= underFoot; d++)
            level.setBlockAndUpdate(new BlockPos(ox, y0 - d, oz), Blocks.WATER.defaultBlockState());
        ctx.record("setup.underFoot", underFoot == 0
                ? "under the feet " + new BlockPos(ox, y0 - 1, oz).toShortString() + " is stone: the bot can stand, "
                  + "so it should release jump and ground itself while breaking the lid"
                : "under the feet " + underFoot + " blocks are water (down to " + new BlockPos(ox, y0 - underFoot, oz).toShortString()
                  + "): the bot cannot stand, so it should keep holding jump while breaking the lid, and the readings "
                  + "should match those from before the stand-up guard existed");

        // The lid: DIRT, as it was in the death. Dirt and not stone on purpose — stone cannot be
        // chewed inside any breath at all, so a stone lid would make every reading a timeout and
        // tell us nothing about which arm ran.
        final BlockPos lid = new BlockPos(ox, y0 + 2, oz);
        level.setBlockAndUpdate(lid, Blocks.DIRT.defaultBlockState());
        // Air above the lid, so breaking it actually reaches somewhere breathable.
        for (int y = y0 + 3; y <= y0 + 6; y++)
            level.setBlockAndUpdate(new BlockPos(ox, y, oz), Blocks.AIR.defaultBlockState());

        // The bait: a column two cells away that CAN surface, with solid rock in between. This is
        // the false positive — `breathableColumn` says yes about the far cell, and the near cell is
        // the one the old scan never asked about.
        final BlockPos gap = new BlockPos(ox, y0, oz - 1);        // stays stone: the wall
        final BlockPos bait = new BlockPos(ox, y0, oz - 2);       // air, and open all the way out
        // Carved to the TOP of the shell, not to y0+6. Left one short, the shell's own lid at y0+7
        // would cap this column — `breathableColumn` would still say yes (it stops at the first
        // non-water cell, which is air at y0), so the scene would behave identically while its
        // evidence row claimed a surface that is not there. A bait that really does surface keeps
        // the row honest.
        for (int y = y0; y <= y0 + 7; y++)
            level.setBlockAndUpdate(new BlockPos(ox, y, oz - 2), Blocks.AIR.defaultBlockState());

        ctx.record("setup.waterPocket", foot.toShortString() + " / " + head.toShortString() + " = water");
        ctx.record("setup.lid", lid.toShortString() + " = "
                + level.getBlockState(lid).getBlock() + " (air above at y " + (y0 + 3) + ".." + (y0 + 6) + ")");
        ctx.record("setup.baitColumn", bait.toShortString() + " = " + level.getBlockState(bait).getBlock()
                + ", column top " + new BlockPos(ox, y0 + 7, oz - 2).toShortString() + " = "
                + level.getBlockState(new BlockPos(ox, y0 + 7, oz - 2)).getBlock()
                + " (it really opens to the outside, not merely judged breathable by the predicate)");
        ctx.record("setup.cellInTheWay", gap.toShortString() + " = " + level.getBlockState(gap).getBlock()
                + " (blocks the only step toward the bait column)");

        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.autoDrownEscape = true;
        BotConfig.autoFloatWhenDrowning = true;
        BotConfig.walkerDebug = true;
        BotConfig.allowBreak = true;                     // without this the lid arm is a no-op

        final double homeX = body.getX(), homeY = body.getY(), homeZ = body.getZ();
        final int homeAir = body.getAirSupply();
        ctx.cleanup(() -> {
            body.teleportTo(homeX, homeY, homeZ);
            body.setAirSupply(homeAir);
        });
        body.teleportTo(ox + 0.5, y0, oz + 0.5);
        body.setAirSupply(body.getMaxAirSupply());       // full for the sync wait — see run()'s note

        final int[] waited = { 0 };
        ctx.await(() -> ++waited[0] >= SYNC_TICKS).within(SYNC_TICKS + 100).then(() -> {
            boolean submerged = body.isEyeInFluid(FluidTags.WATER);
            ctx.record("setup.eyeInWater", submerged);
            if (!submerged) {
                ctx.fail("test setup failed: after waiting " + SYNC_TICKS + " ticks the bot's eyes are still not in water ("
                        + String.format(Locale.ROOT, "y=%.3f", body.getY()) + ", foot cell="
                        + level.getBlockState(body.blockPosition()) + "). Fix the test setup first; this is unrelated to this arm.");
            }
            final double startY = body.getY();
            final int[] t = { 0 };
            final boolean[] broke = { false };
            final double[] maxY = { startY };
            // WHY THE DIG COSTS WHAT IT COSTS — the stance, sampled, because the tick count alone
            // cannot say. Vanilla's Player#getDestroySpeed divides by 5 when the bot is off the
            // ground and again by 5 when its eyes are in water, and this arm measured 379 ticks for
            // ONE dirt block: bare-hand dirt is ~15 ticks, and 15 × 25 = 375. That arithmetic makes
            // the ×25 look proved, but nothing here recorded `onGround`, so the off-ground half was
            // inferred. The repo has measured both factors before — WalkerTickClimb's hopelessness
            // gate notes "the same stone bank cell is 150t dug grounded ashore yet 750-3750t sampled
            // mid-bob" — and that same gate PRICES digs assuming "the bot can always ground", while
            // DrownEscapeChain#tick holds jump unconditionally and never does. These two rows decide
            // whether that gap is what this arm is paying for.
            //
            // Values, not predicates: a rate and a block id, so a later reader can redo the judgement
            // instead of inheriting mine. `lid.underFoot` distinguishes "had a floor and never stood on
            // it" (a fix at the jump) from "the pocket is deeper than 2" (a fix somewhere else entirely).
            final int[] grounded = { 0 };
            final int[] samples = { 0 };
            final double[] riseSum = { 0.0 };
            ctx.await(() -> {
                body.setAirSupply(PINNED_AIR);           // latched, never drowning — see PINNED_AIR
                maxY[0] = Math.max(maxY[0], body.getY());
                // COUNTED AGAINST ITS OWN DENOMINATOR, not against `t`. The tick counter is
                // incremented in the return expression and skipped on the tick that breaks out, so
                // a rate written over `t` can exceed 1 — the first run of this row printed
                // "80/79 samples grounded", a ratio the world cannot produce. The numbers around it were
                // right, which is exactly why the impossible one had to go: a row that cannot be
                // true invites doubt about the readings that can.
                samples[0]++;
                if (body.onGround()) grounded[0]++;
                riseSum[0] += body.getDeltaMovement().y;
                if (!level.getBlockState(lid).is(Blocks.DIRT)) broke[0] = true;
                return broke[0] || ++t[0] >= LID_BUDGET;
            }).within(LID_BUDGET + 100).then(() -> {
                BlockPos under = body.blockPosition().below();
                ctx.record("lid.groundedRate", grounded[0] + "/" + samples[0] + " samples grounded"
                        + " (dig speed penalties: not grounded ÷5, eyes in water another ÷5)");
                ctx.record("lid.underFoot", under.toShortString() + " = " + level.getBlockState(under).getBlock());
                ctx.record("lid.meanVerticalSpeed", String.format(Locale.ROOT, "%.5f blocks/tick (sum over %d samples %.3f)",
                        samples[0] == 0 ? 0.0 : riseSum[0] / samples[0], samples[0], riseSum[0]));
                ctx.record("lid.broken", broke[0] + " (used " + t[0] + "/" + LID_BUDGET + " ticks)");
                ctx.record("lid.finalState", lid.toShortString() + " = " + level.getBlockState(lid).getBlock());
                ctx.record("rise.netRise", String.format(Locale.ROOT, "%.3f blocks", maxY[0] - startY));
                ctx.record("rise.finalState", String.format(Locale.ROOT, "y=%.3f eyeInWater=%s",
                        body.getY(), body.isEyeInFluid(FluidTags.WATER)));
                // WARNING: NOT a survival verdict. Air was pinned at PINNED_AIR the whole way, so this
                // is a dig time. The comparison is arithmetic on it, printed as a VALUE so a later
                // reader can redo it rather than inherit a predicate.
                ctx.record("race.comparison", "one breath lasts about 290 ticks (100 air + 19 health ÷ 2 health per 20 ticks); "
                        + "this run broke the lid in " + t[0] + " ticks ⇒ " + (t[0] <= 290 ? "enough" : "not enough")
                        + ". WARNING: this arm pins air at " + PINNED_AIR + " so it never drops; this is only a dig time, not a survival result");
                if (!broke[0]) {
                    ctx.fail("after holding for " + LID_BUDGET + " ticks, the lid " + lid.toShortString() + " is still there (net rise "
                            + String.format(Locale.ROOT, "%.3f", maxY[0] - startY) + " blocks). "
                            + "This is exactly the signature of rung 9 of the real ladder on 2026-08-26: the bait column " + bait.toShortString()
                            + " really can breathe, while the cell in the way, " + gap.toShortString() + ", is stone. "
                            + "If the lateral arm picked that column again, the bot keeps pushing against the stone and the lid-break "
                            + "fallback never gets a turn. Count the two kinds of [drownEscape] CAPPED lid lines in the log: "
                            + "'firstStep=' means it is swimming laterally again; "
                            + "'cannot be reached by swimming' means the route choice was right and the lid-break itself did nothing.");
                }
            });
        });
    }

    // ------------------------------------------------------------------ the rig

    private static void run(SceneContext ctx, boolean pinNeighbour, boolean armed) {
        MinecraftServer server = ctx.server();
        boolean integrated = server != null && !server.isDedicatedServer();
        ctx.record("topology", (integrated ? "integratedServer" : "dedicatedServer")
                + ", mc.bot.* in this JVM=" + BotHooks.isAvailable());
        if (!integrated || !BotHooks.isAvailable()) {
            // Not a soft spot in the coverage — a hard one, and named. DrownEscapeChain.tick's first
            // line is `if (mc == null) return;`, so on a dedicated server this subject does not
            // merely go untested, it cannot execute. The dedicated gates' pass says nothing here.
            ctx.skip("DrownEscapeChain's execution layer requires a real LocalPlayer in the same JVM: "
                    + "the first line of tick() is if (mc == null) return. On a dedicated server this coverage is not weak, it is zero.");
        }
        List<ServerPlayer> humans = SceneBody.humanPlayers(ctx);
        if (humans.isEmpty()) {
            ctx.skip("no real player on the integrated server: the client has not entered the world yet, or has disconnected");
        }
        ServerPlayer body = humans.get(0);
        ServerLevel level = ctx.level();

        // ---- the column ----------------------------------------------------------------
        BlockPos o = ctx.origin();
        final int ox = o.getX(), oz = o.getZ();
        final int y0 = o.getY() + 8;            // the water's floor
        // Shell first, then carve: an unwalled pool drains sideways and the bot would be judged in
        // air it made itself. Same order as wd.drownEscapeSurface's basin.
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int y = y0 - 1; y <= y0 + 7; y++)
                    level.setBlockAndUpdate(new BlockPos(ox + dx, y, oz + dz), Blocks.STONE.defaultBlockState());
        // Water y0..y0+3 (surface plane y0+4.0), air above it. A bot standing on the floor has its
        // eye at y0+1.62 and needs to reach y0+2.38 to breathe — 2.38 blocks of rise, big enough
        // that "rose slightly" and "reached the surface" cannot print the same.
        for (int dx = -3; dx <= 3; dx++)
            for (int dz = -3; dz <= 3; dz++) {
                for (int y = y0; y <= y0 + 3; y++)
                    level.setBlockAndUpdate(new BlockPos(ox + dx, y, oz + dz), Blocks.WATER.defaultBlockState());
                for (int y = y0 + 4; y <= y0 + 6; y++)
                    level.setBlockAndUpdate(new BlockPos(ox + dx, y, oz + dz), Blocks.AIR.defaultBlockState());
            }

        // Where the bot stands. Centred for the open arms; for the pinned arm, deliberately hard
        // against a cell boundary so the bounding box (width 0.6) straddles TWO columns: at
        // x = ox-0.98 the box runs [ox-1.28, ox-0.68], i.e. into column ox-2, while
        // blockPosition() still answers ox-1. Every scan in DrownEscapeChain asks about ox-1.
        final double bodyX = pinNeighbour ? ox - 0.98 : ox + 0.5;
        final double bodyZ = oz + 0.5;
        final BlockPos scanned = new BlockPos(pinNeighbour ? ox - 1 : ox, y0 + 2, oz);
        final BlockPos straddled = new BlockPos(ox - 2, y0 + 2, oz);
        if (pinNeighbour) {
            // The lid the player actually hits, in the column nobody reads. Its height is footY+2:
            // a player on the floor has its top at y0+1.8, so it is pinned from the first tick rather
            // than after a climb — the accident's own pose, where the corpse sat at 61.159 with its
            // top face at 62.959 against a face at 63.0.
            level.setBlockAndUpdate(straddled, Blocks.STONE.defaultBlockState());
        }
        ctx.record("column.waterSurface", "y=" + (y0 + 4) + ".0 (water fills " + y0 + ".." + (y0 + 3) + ")");
        ctx.record("column.bot", String.format(Locale.ROOT, "x=%.2f z=%.2f, blockPosition column=%s",
                bodyX, bodyZ, scanned.toShortString()));
        ctx.record("column.blocker", pinNeighbour ? straddled.toShortString() + "=stone (no scan ever reads this column)" : "none");

        // ---- config, restored by the pin ----------------------------------------------
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.autoDrownEscape = armed;
        BotConfig.autoFloatWhenDrowning = armed;
        BotConfig.walkerDebug = true;            // the arm's per-tick row is gated on it
        BotConfig.allowBreak = true;             // so a lid the scan DOES see would be broken

        // ---- the player, restored on the way out --------------------------------------
        final double homeX = body.getX(), homeY = body.getY(), homeZ = body.getZ();
        final int homeAir = body.getAirSupply();
        ctx.cleanup(() -> {
            body.teleportTo(homeX, homeY, homeZ);
            body.setAirSupply(homeAir);
        });
        body.teleportTo(bodyX, y0, bodyZ);
        // FULL air for the sync wait, and the staged value only once measuring starts.
        //
        // Staging the low value here instead cost a criterion on 2026-08-23: the latch engaged
        // immediately, the reflex spent the 20 sync ticks freeing the bot, and rise.startY — taken
        // after the wait — was 209.017 instead of the staged 208.2. rise.netRise then read 1.302 for a
        // bot that had actually risen 2.12, i.e. the baseline had been moved by the very thing the
        // number was measuring. 300 is above every float threshold in the driver
        // (drownEscapeAirThreshold 100, drownFloatAirThreshold 240) with room for the ~20 ticks of
        // drain the wait costs, so nothing acts before the window opens.
        body.setAirSupply(body.getMaxAirSupply());

        // ---- measure -------------------------------------------------------------------
        final int[] waited = { 0 };
        ctx.await(() -> ++waited[0] >= SYNC_TICKS).within(SYNC_TICKS + 100).then(() -> {
            // The staging is asserted BEFORE the subject is. A bot that never got submerged would
            // produce a perfectly readable "did not rise" that describes the arena, not the reflex.
            boolean submerged = body.isEyeInFluid(FluidTags.WATER);
            ctx.record("setup.eyeInWater", submerged);
            ctx.record("setup.footCell", level.getBlockState(body.blockPosition()).getBlock().toString());
            if (!submerged) {
                ctx.fail("test setup failed: after waiting " + SYNC_TICKS + " ticks the bot's eyes are still not in water ("
                        + String.format(Locale.ROOT, "y=%.3f", body.getY()) + ", foot cell="
                        + level.getBlockState(body.blockPosition()) + "). "
                        + "This reading is unrelated to DrownEscapeChain; fix the test setup first.");
            }
            body.setAirSupply(STAGED_AIR);       // the window opens HERE — see the teleport's note
            final double startY = body.getY();
            ctx.record("setup.measureStartRelativeToSetup", String.format(Locale.ROOT,
                    "%.3f (setup y0=%d, drifted %.3f during the sync wait)", startY, y0, startY - y0));
            final double[] maxY = { startY };
            final boolean[] surfaced = { false };
            final int[] t = { 0 };
            // Terminates on its own: `within` expiring is a FAIL in this harness, and the disarmed
            // arm's success condition is by design never reached. So the loop counts to BUDGET and
            // the verdict is taken in then(), not by the await.
            ctx.await(() -> {
                maxY[0] = Math.max(maxY[0], body.getY());
                if (!body.isEyeInFluid(FluidTags.WATER)) surfaced[0] = true;
                return surfaced[0] || ++t[0] >= BUDGET;
            }).within(BUDGET + 100).then(() -> {
                double gained = maxY[0] - startY;
                ctx.record("rise.startY", String.format(Locale.ROOT, "%.3f", startY));
                ctx.record("rise.peakY", String.format(Locale.ROOT, "%.3f", maxY[0]));
                ctx.record("rise.netRise", String.format(Locale.ROOT, "%.3f blocks", gained));
                ctx.record("rise.surfaced", surfaced[0] + " (used " + t[0] + "/" + BUDGET + " ticks)");
                ctx.record("rise.finalState", String.format(Locale.ROOT,
                        "y=%.3f eyeInWater=%s air=%d", body.getY(), body.isEyeInFluid(FluidTags.WATER),
                        body.getAirSupply()));
                if (armed && !surfaced[0]) {
                    ctx.fail("jump was held for " + BUDGET + " ticks and the head never left the water: net rise "
                            + String.format(Locale.ROOT, "%.3f", gained) + " blocks, water surface at y=" + (y0 + 4)
                            + ".0, and the feet only need to reach " + String.format(Locale.ROOT, "%.2f", y0 + 2.38)
                            + " to breathe. " + (pinNeighbour
                                    ? "This arm's blocker is at " + straddled.toShortString()
                                      + ", while cappedColumn, lid and nearestBreathable all read only the "
                                      + scanned.toShortString() + " column: the single-column scan blind spot is confirmed."
                                    : "This arm is fully open overhead, so the problem is not geometry but the execution layer: "
                                      + "check the jumpReadBack and hitCeiling fields of the [drownEscape] vertical arm lines in the log."));
                }
                if (!armed && surfaced[0]) {
                    ctx.fail("counter-arm failed: autoDrownEscape and autoFloatWhenDrowning are both off, "
                            + "yet the bot still surfaced (net rise " + String.format(Locale.ROOT, "%.3f", gained)
                            + " blocks). Some other channel that was not disabled is lifting it; until it is found, "
                            + "the other two arms passing does not prove that this reflex is what lifted the bot.");
                }
            });
        });
    }
}
