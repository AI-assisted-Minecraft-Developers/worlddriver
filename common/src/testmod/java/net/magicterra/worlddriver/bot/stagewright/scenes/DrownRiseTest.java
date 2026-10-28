package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.Locale;

import net.magicterra.stagewright.test.Body;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.ClientRig;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Did the player actually RISE — asked of a real body, because nothing else can ask it.
 *
 * <h2>The hole these fill</h2>
 *
 * The drowning family had four members before these and every one of them stopped short of the
 * thing that kills players: two pure gate matrices over "should it float", a unit test of the
 * scheduler's <b>bookkeeping</b> ({@code DrownEscapeChainTest}), and {@code wd.drownEscapeSurface}, which
 * asserts a rise of the <b>Navigator's</b> escape on a server body with the air supply simulated by hand
 * and never constructs {@code DrownEscapeChain}.
 *
 * <p>{@code DrownEscapeChain.tick} opened with {@code if (mc == null) return;} — so the entire
 * execution layer of the reflex that outranks every user process was unreachable from a dedicated
 * server. The chain now steers any body, and the server arms here run it on an idle server bot.
 *
 * <p><b>What that cost.</b> Integrated ladder, 2026-08-22, BED rung: the reflex preempted at
 * {@code air=100}, held the movement channel for 261 ticks, and the bot drowned at
 * {@code -28,61,79} without moving one block.
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
 * the NEXT column, which no scan here ever asks about. The pinned arms stage that geometry deliberately
 * and with a large margin, so a failure means the blindness is real and a pass means the neighbour
 * column is not the mechanism. An open-water arm alone could not tell those apart: it would go green
 * today and go on being green through the exact death it was written for. The open-water arms
 * ({@code ...RisesInOpenWater}) and the client's disarmed arm were dropped: the pinned arms also assert
 * surfacing, and the server's disarmed arm makes the same settings claim.
 *
 * <p>The server arms run in both scene files; the client arms ({@code @Requires(CLIENT)}) drive the joined
 * client's own player, whose brain reads the client JVM's settings ({@link ClientRig} pins both).
 */
@Scene({"wd.scenes", "wd.clientScenes"})
class DrownRiseTest {

    /** Ticks the bot is given to reach air. Ten times the ~20 ticks a free rise over this column
     *  takes, so a failure cannot be read as "the budget was too tight" — and far below the ~240 ticks that separate the
     *  staged {@code air=40} from a drowning death, so a red never costs the client its life. */
    private static final int BUDGET = 200;

    /** Ticks between staging the blocks and using them. The blocks are written on the SERVER and the
     *  player that must be submerged in them may be the CLIENT's; the chunk packets take a round trip,
     *  and a player judged before they land is judged against water it cannot see. */
    private static final int SYNC_TICKS = 20;

    /** Air to stage. Below {@code drownEscapeAirThreshold} (100) so the latch engages on the first
     *  evaluation — waiting for 200 real ticks of drowning to reach it would spend the budget on the
     *  precondition instead of on the subject. */
    private static final int STAGED_AIR = 40;

    /** Ticks the lid arm gets. Dirt is ~15 ticks bare-handed, ×5 for a submerged head and ×5 again
     *  for a player with no ground under it — call it ~375, and a budget that cannot outlast the
     *  thing it measures would report the measurement as a failure. */
    private static final int LID_BUDGET = 600;

    /** Air held under the {@code drownEscapeAirThreshold} setting for the whole lid arm, so the latch
     *  stays engaged without the bot ever drowning. The other arms can let air fall because they
     *  finish in tens of ticks; this one runs for hundreds, and killing the human's client to
     *  measure a dig time is not a trade this suite makes. It also means the tick count this arm
     *  records is a DIG time, never a survival result — see the failure text. */
    private static final int PINNED_AIR = 90;

    // ------------------------------------------------------------------ the arms

    /** The regression on the client's player: an open column by every scan the chain performs, but a
     *  solid block in the neighbouring column the player's own bounding box straddles. */
    @Test
    @Requires(Requires.Capability.CLIENT)
    void drownEscapeClientPinnedByNeighbourColumn(Game game, Client client, Player player,
                                                  @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot,
                                                  TestReporter reporter) {
        BlockPos o = plot.at(0, 0, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, o, 0f);
        run(game, plot, reporter, rig.settings(), player, true, true);
    }

    /** The same regression on an idle server bot: its own brain's {@code DrownEscapeChain} and drowning float. */
    @Test
    void drownEscapeServerPinnedByNeighbourColumn(Game game, @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot,
                                                  TestReporter reporter) {
        Settings settings = Settings.pin(game);
        run(game, plot, reporter, settings, serverBot(game, plot), true, true);
    }

    /** The counter-arm: with both reflexes off the same idle server bot stays down, so the armed greens come from the reflex. */
    @Test
    void drownEscapeServerStaysDownDisarmed(Game game, @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot,
                                            TestReporter reporter) {
        Settings settings = Settings.pin(game);
        run(game, plot, reporter, settings, serverBot(game, plot), false, false);
    }

    /**
     * The arm the ladder of 2026-08-26 needed and nobody had: the arms above cover the vertical arm and the
     * disarmed counter-arm, and NONE of them ever enters the lateral one ({@code cappedColumn} is false in
     * every arena above). See {@link #breaksTheLid}.
     */
    @Test
    @Requires(Requires.Capability.CLIENT)
    void drownEscapeClientBreaksTheLidWhenOpenWaterIsWalledOff(Game game, Client client, Player player,
                                                               @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot,
                                                               TestReporter reporter) {
        breaksTheLid(game, client, player, plot, reporter, 0);
    }

    /**
     * THE SAME ARM OVER WATER, and it is the control the arm above cannot be read without. Standing up to
     * dig is guarded on "the cell under the feet can be stood on", and a guard is only worth what its
     * NEGATIVE arm is worth: over water the jump must stay held, because a bot that sinks in a deep pocket
     * drifts out of RISE_PROBE's half-block reach, loses the lid, and starts a jump/sink oscillation that
     * resets the break progress every cycle. Three water cells under the foot is the shallowest staging
     * that is unambiguously "deeper than two".
     *
     * <p>Read the two {@code lid.broken} numbers side by side: the shallow arm should fall to about a fifth
     * once the bot grounds, and THIS one should not move at all.
     */
    @Test
    @Requires(Requires.Capability.CLIENT)
    void drownEscapeClientKeepsFloatingWhenThePocketIsDeep(Game game, Client client, Player player,
                                                           @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot,
                                                           TestReporter reporter) {
        breaksTheLid(game, client, player, plot, reporter, 3);
    }

    /** A parked, empty-handed server bot in the column's cell; {@link #run} puts it in place and unparks it. */
    private static SceneBot serverBot(Game game, Plot plot) {
        return SceneBot.spawn(game, plot, 0, 0, 0);
    }

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
     * water in range but walled off, does the bot end up attacking the lid?</b>
     *
     * <p>The air supply is pinned under the threshold rather than allowed to drain, so this measures
     * a dig, not a race. Whether the dig fits inside a real breath is arithmetic on the recorded
     * number against ~290 ticks (100 of air, then 19 HP at 2 per 20), and it is recorded as such —
     * this test deliberately asserts nothing about it, because nobody has measured it.
     *
     * @param underFoot how many water cells sit BELOW the pocket's foot cell. Zero puts the bot on
     *        rock and lets the dig ground itself; three puts it over water, where the stand-up
     *        guard must decline and the arm must behave exactly as it did before the guard existed.
     */
    private static void breaksTheLid(Game game, Client client, Player player, Plot plot, TestReporter reporter,
                                     int underFoot) {
        BlockPos o = plot.at(0, 0, 0);
        final int ox = o.getX(), oz = o.getZ();
        final int y0 = o.getY();                     // the pocket's floor
        final BlockPos foot = new BlockPos(ox, y0, oz);
        final BlockPos head = new BlockPos(ox, y0 + 1, oz);
        // The lid: DIRT, as it was in the death. Dirt and not stone on purpose — stone cannot be
        // chewed inside any breath at all, so a stone lid would make every reading a timeout and
        // tell us nothing about which arm ran.
        final BlockPos lid = new BlockPos(ox, y0 + 2, oz);
        final BlockPos gap = new BlockPos(ox, y0, oz - 1);        // stays stone: the wall
        final BlockPos bait = new BlockPos(ox, y0, oz - 2);       // air, and open all the way out

        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Shell first, then carve — an unwalled pocket drains and the bot is judged in air it made
            // itself. Deep enough to hold the variant's own water column.
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++)
                    for (int y = y0 - 1 - underFoot; y <= y0 + 7; y++)
                        level.setBlockAndUpdate(new BlockPos(ox + dx, y, oz + dz), Blocks.STONE.defaultBlockState());
            // The pocket: 1×1, two cells of water, so the eye is submerged with the feet on the floor.
            // `underFoot` extends it DOWNWARD only: the lid, the bait and the bot's entry cell are the
            // same in both variants, so the one thing that differs between them is what the stand-up
            // guard reads under the feet.
            level.setBlockAndUpdate(foot, Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(head, Blocks.WATER.defaultBlockState());
            for (int d = 1; d <= underFoot; d++)
                level.setBlockAndUpdate(new BlockPos(ox, y0 - d, oz), Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(lid, Blocks.DIRT.defaultBlockState());
            // Air above the lid, so breaking it actually reaches somewhere breathable.
            for (int y = y0 + 3; y <= y0 + 6; y++)
                level.setBlockAndUpdate(new BlockPos(ox, y, oz), Blocks.AIR.defaultBlockState());
            // The bait: a column two cells away that CAN surface, with solid rock in between. This is
            // the false positive — `breathableColumn` says yes about the far cell, and the near cell is
            // the one the old scan never asked about. Carved to the TOP of the shell, so the column
            // really surfaces and the evidence row stays honest.
            for (int y = y0; y <= y0 + 7; y++)
                level.setBlockAndUpdate(new BlockPos(ox, y, oz - 2), Blocks.AIR.defaultBlockState());
        });
        reporter.publishEntry("setup.underFoot", underFoot == 0
                ? "under the feet " + new BlockPos(ox, y0 - 1, oz).toShortString() + " is stone: the bot can stand, "
                  + "so it should release jump and ground itself while breaking the lid"
                : "under the feet " + underFoot + " blocks are water (down to " + new BlockPos(ox, y0 - underFoot, oz).toShortString()
                  + "): the bot cannot stand, so it should keep holding jump while breaking the lid");
        reporter.publishEntry("setup.cellInTheWay", gap.toShortString() + " stone, bait column " + bait.toShortString());

        ClientRig rig = ClientRig.adopt(game, client, player, foot, 0f);
        rig.settings().set("autoDrownEscape", true).set("autoFloatWhenDrowning", true)
                .set("navigatorDebug", true)
                .set("allowBreak", true);                     // without this the lid arm is a no-op
        game.afterTest(s -> player.runOnServer(p -> p.setAirSupply(p.getMaxAirSupply())));
        player.runOnServer(p -> {
            p.teleportTo(ox + 0.5, y0, oz + 0.5);
            p.setAirSupply(p.getMaxAirSupply());       // full for the sync wait — see run()'s note
        });

        game.ticks(SYNC_TICKS);
        boolean submerged = player.onServer(p -> p.isEyeInFluid(FluidTags.WATER));
        if (!submerged) {
            fail("test setup failed: after waiting " + SYNC_TICKS + " ticks the bot's eyes are still not in water ("
                    + player.onServer(p -> String.format(Locale.ROOT, "y=%.3f, foot cell=%s", p.getY(),
                            p.level().getBlockState(p.blockPosition())))
                    + "). Fix the test setup first; this is unrelated to this arm.");
        }
        final double startY = player.onServer(p -> p.getY());
        final int[] t = {0};
        final boolean[] broke = {false};
        final double[] maxY = {startY};
        // WHY THE DIG COSTS WHAT IT COSTS — the stance, sampled, because the tick count alone
        // cannot say. Vanilla's Player#getDestroySpeed divides by 5 when the bot is off the
        // ground and again by 5 when its eyes are in water; this arm measured 379 ticks for ONE dirt
        // block, and 15 × 25 = 375. Values, not predicates: a rate and a block id, so a later reader can
        // redo the judgement. `lid.underFoot` distinguishes "had a floor and never stood on it" (a fix
        // at the jump) from "the pocket is deeper than 2" (a fix somewhere else entirely).
        final int[] grounded = {0};
        final int[] samples = {0};
        final double[] riseSum = {0.0};
        ServerLevel level = game.onServer(s -> s.getLevel(plot.dimension()));
        ServerPlayer p = player.onServer(x -> x);
        game.await().within(LID_BUDGET + 100).as("the lid breaks or the budget runs out").until(() -> {
            p.setAirSupply(PINNED_AIR);           // latched, never drowning — see PINNED_AIR
            maxY[0] = Math.max(maxY[0], p.getY());
            // Counted against its own denominator, not against `t`: `t` is skipped on the tick that
            // breaks out, so a rate over it could exceed 1.
            samples[0]++;
            if (p.onGround()) grounded[0]++;
            riseSum[0] += p.getDeltaMovement().y;
            if (!level.getBlockState(lid).is(Blocks.DIRT)) broke[0] = true;
            return broke[0] || ++t[0] >= LID_BUDGET;
        });
        String underRow = game.onServer(s -> {
            BlockPos under = p.blockPosition().below();
            return under.toShortString() + " = " + level.getBlockState(under).getBlock();
        });
        String rows = game.onServer(s -> String.format(Locale.ROOT, "y=%.3f eyeInWater=%s", p.getY(),
                p.isEyeInFluid(FluidTags.WATER)));
        reporter.publishEntry("lid.underFoot", underRow);
        reporter.publishEntry("lid.groundedRate", grounded[0] + "/" + samples[0] + " samples grounded"
                + " (dig speed penalties: not grounded ÷5, eyes in water another ÷5)");
        reporter.publishEntry("lid.meanVerticalSpeed", String.format(Locale.ROOT, "%.5f blocks/tick (sum over %d samples %.3f)",
                samples[0] == 0 ? 0.0 : riseSum[0] / samples[0], samples[0], riseSum[0]));
        reporter.publishEntry("lid.broken", broke[0] + " (used " + t[0] + "/" + LID_BUDGET + " ticks)");
        reporter.publishEntry("rise.netRise", String.format(Locale.ROOT, "%.3f blocks", maxY[0] - startY));
        reporter.publishEntry("rise.finalState", rows);
        // WARNING: NOT a survival verdict. Air was pinned at PINNED_AIR the whole way, so this is a dig time.
        reporter.publishEntry("race.comparison", "one breath lasts about 290 ticks (100 air + 19 health ÷ 2 health per 20 ticks); "
                + "this run broke the lid in " + t[0] + " ticks ⇒ " + (t[0] <= 290 ? "enough" : "not enough")
                + ". WARNING: this arm pins air at " + PINNED_AIR + " so it never drops; this is only a dig time, not a survival result");
        if (!broke[0]) {
            fail("after holding for " + LID_BUDGET + " ticks, the lid " + lid.toShortString() + " is still there (net rise "
                    + String.format(Locale.ROOT, "%.3f", maxY[0] - startY) + " blocks). "
                    + "This is exactly the signature of rung 9 of the real ladder on 2026-08-26: the bait column " + bait.toShortString()
                    + " really can breathe, while the cell in the way, " + gap.toShortString() + ", is stone. "
                    + "If the lateral arm picked that column again, the bot keeps pushing against the stone and the lid-break "
                    + "fallback never gets a turn. Count the two kinds of [drownEscape] CAPPED lid lines in the log: "
                    + "'firstStep=' means it is swimming laterally again; "
                    + "'cannot be reached by swimming' means the route choice was right and the lid-break itself did nothing.");
        }
    }

    // ------------------------------------------------------------------ the rig

    /**
     * One rise arm on {@code who}: the client's player, or an idle server bot on the tick list with no task, so
     * what lifts it is its own brain's {@code DrownEscapeChain} and drowning float. The bot stands hard against a
     * cell boundary with a stone block in the neighbouring column its bounding box straddles.
     */
    private static void run(Game game, Plot plot, TestReporter reporter, Settings settings, Body who,
                            boolean pinNeighbour, boolean armed) {
        BlockPos o = plot.at(0, 0, 0);
        final int ox = o.getX(), oz = o.getZ();
        final int y0 = o.getY();            // the water's floor
        // Where the bot stands. Centred for an open arm; for the pinned arm, deliberately hard
        // against a cell boundary so the bounding box (width 0.6) straddles TWO columns: at
        // x = ox-0.98 the box runs [ox-1.28, ox-0.68], i.e. into column ox-2, while
        // blockPosition() still answers ox-1. Every scan in DrownEscapeChain asks about ox-1.
        final double bodyX = pinNeighbour ? ox - 0.98 : ox + 0.5;
        final double bodyZ = oz + 0.5;
        final BlockPos scanned = new BlockPos(pinNeighbour ? ox - 1 : ox, y0 + 2, oz);
        final BlockPos straddled = new BlockPos(ox - 2, y0 + 2, oz);
        game.runOnServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            // Shell first, then carve: an unwalled pool drains sideways and the bot would be judged in
            // air it made itself.
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
            // The lid the player actually hits, in the column nobody reads. Its height is footY+2:
            // a player on the floor has its top at y0+1.8, so it is pinned from the first tick rather
            // than after a climb — the accident's own pose.
            if (pinNeighbour) level.setBlockAndUpdate(straddled, Blocks.STONE.defaultBlockState());
        });
        reporter.publishEntry("column.bot", String.format(Locale.ROOT, "x=%.2f z=%.2f, blockPosition column=%s",
                bodyX, bodyZ, scanned.toShortString()));
        reporter.publishEntry("column.blocker", pinNeighbour ? straddled.toShortString() + "=stone (no scan ever reads this column)" : "none");

        settings.set("autoDrownEscape", armed).set("autoFloatWhenDrowning", armed)
                .set("navigatorDebug", true)            // the arm's per-tick row is gated on it
                .set("allowBreak", true);               // so a lid the scan DOES see would be broken

        if (who instanceof SceneBot bot) {
            bot.runOnServer(p -> p.setPos(bodyX, y0, bodyZ));
            // Idle, but ticked by the server: its reflexes run, no task does.
            bot.unpark();
        } else {
            game.afterTest(s -> who.runOnServer(p -> p.setAirSupply(p.getMaxAirSupply())));
            who.runOnServer(p -> p.teleportTo(bodyX, y0, bodyZ));
        }
        // FULL air for the sync wait, and the staged value only once measuring starts: staging the low
        // value here let the reflex spend the sync ticks freeing the bot, and moved the baseline of the
        // very rise being measured (2026-08-23).
        who.runOnServer(p -> p.setAirSupply(p.getMaxAirSupply()));

        game.ticks(SYNC_TICKS);
        // The staging is asserted BEFORE the subject is. A bot that never got submerged would
        // produce a perfectly readable "did not rise" that describes the arena, not the reflex.
        if (!who.onServer(p -> p.isEyeInFluid(FluidTags.WATER))) {
            fail("test setup failed: after waiting " + SYNC_TICKS + " ticks the bot's eyes are still not in water ("
                    + who.onServer(p -> String.format(Locale.ROOT, "y=%.3f, foot cell=%s", p.getY(),
                            p.level().getBlockState(p.blockPosition())))
                    + "). This reading is unrelated to DrownEscapeChain; fix the test setup first.");
        }
        final double startY = who.onServer(p -> {
            p.setAirSupply(STAGED_AIR);       // the window opens HERE
            return p.getY();
        });
        reporter.publishEntry("setup.measureStartRelativeToSetup", String.format(Locale.ROOT,
                "%.3f (drifted %.3f during the sync wait)", startY, startY - y0));
        final double[] maxY = {startY};
        final boolean[] surfaced = {false};
        final int[] t = {0};
        // Terminates on its own: the disarmed arm's success condition is by design never reached, so the
        // loop counts to BUDGET and the verdict is taken after it.
        ServerPlayer p = who.onServer(x -> x);
        game.await().within(BUDGET + 100).as("the bot surfaces or the budget runs out").until(() -> {
            maxY[0] = Math.max(maxY[0], p.getY());
            if (!p.isEyeInFluid(FluidTags.WATER)) surfaced[0] = true;
            return surfaced[0] || ++t[0] >= BUDGET;
        });
        double gained = maxY[0] - startY;
        reporter.publishEntry("rise.netRise", String.format(Locale.ROOT, "%.3f blocks (start %.3f, peak %.3f)",
                gained, startY, maxY[0]));
        reporter.publishEntry("rise.surfaced", surfaced[0] + " (used " + t[0] + "/" + BUDGET + " ticks)");
        reporter.publishEntry("rise.finalState", game.onServer(s -> String.format(Locale.ROOT,
                "y=%.3f eyeInWater=%s air=%d", p.getY(), p.isEyeInFluid(FluidTags.WATER), p.getAirSupply())));
        if (armed && !surfaced[0]) {
            fail("jump was held for " + BUDGET + " ticks and the head never left the water: net rise "
                    + String.format(Locale.ROOT, "%.3f", gained) + " blocks, water surface at y=" + (y0 + 4)
                    + ".0, and the feet only need to reach " + String.format(Locale.ROOT, "%.2f", y0 + 2.38)
                    + " to breathe. This arm's blocker is at " + straddled.toShortString()
                    + ", while cappedColumn, lid and nearestBreathable all read only the "
                    + scanned.toShortString() + " column: the single-column scan blind spot is confirmed.");
        }
        if (!armed && surfaced[0]) {
            fail("counter-arm failed: autoDrownEscape and autoFloatWhenDrowning are both off, "
                    + "yet the bot still surfaced (net rise " + String.format(Locale.ROOT, "%.3f", gained)
                    + " blocks). Some other channel that was not disabled is lifting it; until it is found, "
                    + "the other two arms passing does not prove that this reflex is what lifted the bot.");
        }
    }
}
