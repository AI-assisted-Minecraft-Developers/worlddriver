package net.magicterra.worlddriver.bot.stagewright.journey;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Rung 11's second half: carry the filled bucket back to daylight, pick a cell of standing water,
 * and pour — once, because there is exactly one bucket of lava and no second try.
 *
 * <p>Mechanical extraction out of {@link WorldDriverJourneyScenes}, which reached its 3000-line
 * budget; the same seam {@code JourneyStation} and {@code JourneyPortalEntry} were cut on. Every
 * evidence key is unchanged — {@code cast.*} rows from before and after this move are directly
 * comparable, which is the whole point of moving code rather than rewriting it.
 *
 * <p>The FIRST half — tunnelling to the lava and filling — stays in {@link JourneyFill} and in
 * {@code WorldDriverJourneyScenes.reachLava}. The two halves ask opposite questions about the same
 * 4.5-block ray, and both of them get it from {@link JourneyFill#BUCKET_REACH}: the fill needs a
 * source it can REACH (seeing it at the digging range is not enough), and the pour needs a bed it
 * can SEE (standing near it is not enough).
 */
final class JourneyCast {
    private JourneyCast() {}

    /** Climb back to daylight carrying the lava, get out of the water it surfaced in, then cast. */
    static void leaveWithTheLava(SceneContext ctx, JourneyRig rig, int surfaceY) {
        rig.attempting("背着岩浆爬回地面");
        JourneyShaft.climbOut(rig, surfaceY, "lava.exit", () -> {
            rig.evidence("lava_bucket.atSurface", rig.carrying("minecraft:lava_bucket"));
            standOnDryGround(rig, () -> castBesideWater(ctx, rig));
        });
    }

    /** How far to look for somewhere to stand. Sixteen, because the climb that made this necessary
     *  surfaced four columns off its own pillar — the drift is a few blocks, not a few dozen, and a
     *  wider search only buys a longer swim to reach it. */
    private static final int DRY_LAND_SEARCH = 16;

    /**
     * Get out of the water before doing anything else, because a body that surfaced into it is on a
     * clock.
     *
     * <p><b>The run this is written from.</b> j34 reached this rung holding the lava —
     * {@code lava_bucket.atSurface = 1}, one pour from the obsidian — and then drowned:
     * {@code death.blow = drown −2.0→14.0@6 … drown −2.0→0.0@146}, {@code death.driving = goto},
     * {@code death.standingIn = 脚格/脚下/头格 全是 water[level=8]}. The exit had already said so
     * one row earlier: {@code lava.exit#6.endedOn = 脚格=water，脚下=water —— 浮在水里，脚下没有
     * 地板；上面每一级都会从一个正在下沉的身体开始}, over {@code endedIn = -3,56（起塔柱是
     * -7,53 —— 不是同一柱）} and {@code gained = 32/37}. The cast then walked a suffocating body
     * for 146 ticks and the rung's verdict was a death.
     *
     * <p><b>This is a missing hand-off, not a new mechanism.</b> {@link JourneyShaft#recordExit}'s
     * javadoc worked this out on 2026-08-22 and deliberately left the repair here: 「Getting out of
     * water is a horizontal problem and it belongs to the caller, which is why the iron rung now
     * ends with a walk home.」 The iron rung took that hand-off; this one never did. So the fix is
     * a caller-side step, and specifically NOT 「refuse to finish the climb while wet」— that same
     * javadoc already priced that one: it falls through to a {@code Goal.YLevel(surfaceY)} fallback
     * which is satisfied at that very moment, i.e. the same question asked twice.
     *
     * <p>Both branches record, and the afloat test is literally the one {@code recordExit} prints:
     * this leg used to spell out the same two-cell predicate a second time, and it now calls
     * {@link JourneyShaft#afloat} so the hand-off and the row that motivates it can never drift
     * apart — a caller answering「wet」where the exit answered「afloat」would walk ashore for a body
     * that was standing on rock, or leave one floating.
     */
    private static void standOnDryGround(JourneyRig rig, Runnable then) {
        ServerLevel lvl = rig.ctx().level();
        BlockPos at = rig.player().blockPosition();
        boolean afloat = JourneyShaft.afloat(lvl, at);
        rig.evidence("lava.exit.afloat", afloat
                ? "是 —— 脚格与脚下都是流体，" + at.toShortString() + "，先上岸再浇"
                : "否 —— " + at.toShortString() + "，脚下=" + lvl.getBlockState(at.below()).getBlock());
        if (!afloat) { then.run(); return; }
        BlockPos dry = nearestDryColumn(lvl, at, DRY_LAND_SEARCH);
        rig.evidence("lava.exit.dryLand", dry == null
                ? DRY_LAND_SEARCH + " 格内没有一柱是干的" : dry.toShortString());
        // Nothing to walk to is not a reason to stop: the pour may still find shallow water from
        // here, and failing the rung on the recovery would replace a pour diagnosis with a walking
        // one. The row above is what says which happened.
        if (dry == null) { then.run(); return; }
        rig.attempting("背着岩浆先上岸：走不到 " + dry.getX() + "," + dry.getZ() + "（正在窒息）");
        // Tolerance 1 and a short budget on purpose. Drowning costs 2 HP every 20 ticks, so a full
        // health bar is 200 ticks of swimming — a leg allowed to spend thousands here would watch
        // the body die exactly as the cast's own goto did.
        WorldDriverJourneyScenes.walkToColumn(rig, "lava.ashore", dry.getX(), dry.getZ(), 1, 600,
                () -> { ashore(rig); then.run(); },
                () -> { ashore(rig); then.run(); });
    }

    private static void ashore(JourneyRig rig) {
        BlockPos at = rig.player().blockPosition();
        ServerLevel lvl = rig.ctx().level();
        rig.evidence("lava.exit.ashore", at.toShortString() + "，脚下="
                + lvl.getBlockState(at.below()).getBlock() + "，血 " + rig.player().getHealth()
                + "，空气 " + rig.player().getAirSupply());
    }

    /** The nearest column with standing room at its own surface, by {@link JourneyTerrain#dryUnderfoot}
     *  — the same predicate the descent uses to refuse a wet column, asked here for the opposite
     *  reason. Ranked by horizontal distance only: the y is whatever that column's daylight is. */
    private static BlockPos nearestDryColumn(ServerLevel lvl, BlockPos from, int r) {
        BlockPos best = null;
        long bestD2 = Long.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long d2 = (long) dx * dx + (long) dz * dz;
                if (d2 >= bestD2) continue;
                int x = from.getX() + dx, z = from.getZ() + dz;
                if (!JourneyTerrain.dryUnderfoot(lvl, x, z)) continue;
                bestD2 = d2;
                best = new BlockPos(x, JourneyTerrain.daylightAt(lvl, new BlockPos(x, 0, z)), z);
            }
        }
        return best;
    }

    /**
     * Pour the lava into standing water, at a cell chosen before the pour.
     *
     * <p>"Chosen before" is the whole assertion. Obsidian appearing SOMEWHERE after a bucket is
     * emptied proves the fluids met; obsidian appearing in the cell the rung named proves the body
     * put it there, and only the second is a capability a portal can be built on.
     */
    private static void castBesideWater(SceneContext ctx, JourneyRig rig) {
        rig.attempting("找一处底下是实心的浅水，把岩浆倒进去");
        BlockPos shallow = JourneyTerrain.shallowWaterNear(rig, 24);
        if (shallow != null) { approachAndPour(ctx, rig, shallow); return; }
        // Nothing underfoot: fall back on the surveyed water. A swamp normally makes this branch
        // dead code, which is exactly why it is worth recording when it is not.
        BlockPos w = JourneyRoute.firstWater;
        rig.evidence("cast.walkedToSurveyedWater", w.toShortString());
        WorldDriverJourneyScenes.walkToColumn(rig, "water", w.getX(), w.getZ(), 0, 16_000,
                () -> approachAndPour(ctx, rig, JourneyTerrain.shallowWaterNear(rig, 12)),
                () -> ctx.fail("走不到 firstWater " + w.toShortString()
                        + "：停在 " + rig.player().blockPosition()));
    }

    private static void approachAndPour(SceneContext ctx, JourneyRig rig, BlockPos water) {
        approachAndPour(ctx, rig, water, CAST_APPROACHES);
    }

    /** How many times the cast may walk at its target before saying it cannot get there. A pour is
     *  a four-and-a-half-block ray and {@code Goal.Near} can end short, so arriving is a thing to
     *  CHECK rather than a thing to assume — measured, a run reached the pour at
     *  {@code cast.range=12.12} with {@code cast.picks=MISS}, twelve blocks from water it had
     *  walked at once. */
    private static final int CAST_APPROACHES = 3;

    private static void approachAndPour(SceneContext ctx, JourneyRig rig, BlockPos water, int tries) {
        if (water == null) {
            ctx.fail("附近没有底下实心的水面：身体在 " + rig.player().blockPosition()
                    + "（深水没有落点，浇下去的岩浆会沉，铸出的黑曜石也拿不回来）");
            return;
        }
        rig.evidence("cast.target", water.toShortString());
        // Adjacent, not merely near. A pour is a ray, and a ray aimed at a cell below the bank's lip
        // hits the lip: measured in the arena, where a mould two cells away swallowed the bucket and
        // left the target empty while the use still reported CONSUME.
        rig.settle(new IntentProcess(new Intent(new Goal.Near(water, 2))), 2_000, () -> {
            BlockPos at = rig.player().blockPosition();
            // HOW THIS LEG ENDED, on both branches. `rig.settle` runs its callback when the budget
            // is spent just as it does when the process finishes, and nothing here told them apart:
            // ladder-14 spent all 2100 ticks pinned at -7,58,45 with the water eight blocks away,
            // then re-picked a nearer pool and failed the pour — and the rung's verdict named the
            // AIMING LINE. The walk that never happened left no row at all, so the only account of
            // it was three heartbeat lines in the log. Same key shape the lava leg already uses.
            rig.evidence("cast.walk", JourneyLeg.walkerEnd(rig) + "；停在 " + at.toShortString()
                    + "，距计划的水面 " + water.toShortString() + " "
                    + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(at.distSqr(water)))
                    + " 格");
            BlockPos again = JourneyTerrain.shallowWaterNear(rig, 8);
            BlockPos aim = again == null ? water : again;
            // A NEARER WATER IS NOT A VISIBLE WATER. The re-pick exists because a walk can end a
            // cell short of the surveyed pool, and it is measured by DISTANCE — which says nothing
            // about the line. ladder-14: the walk got nowhere, the re-pick found a pool 3.4 away
            // and FOUR BLOCKS UP, and the ray to its bed went through the column holding it up
            // (`-7,59,46 stone`, `-7,60,46 dirt`, `-7,60,47 dirt`). The clearing loop then spent
            // itself on terrain that was never in the way of anything but this bad choice.
            //
            // Ask the engine, from here, with the FULL bucket's own ray. Blocked → go back to the
            // surveyed pool and walk again rather than dig upward into the pool's own floor: that
            // pool is above the body, so clearing the line means removing what holds the water,
            // and the reward for succeeding is water on the body's head.
            if (again != null && !again.equals(water) && tries > 0
                    && !JourneyFill.bucketLineLandsOn(rig, again.below(), false)) {
                rig.evidence("cast.rePickBlocked", again.toShortString()
                        + " 更近，但以满桶自己的射线打不到它的床格 " + again.below().toShortString()
                        + " —— 退回勘测到的 " + water.toShortString() + " 再走一趟");
                approachAndPour(ctx, rig, water, tries - 1);
                return;
            }
            // Did the walk actually ARRIVE? `Goal.Near` reporting done is not the same as being in
            // reach, and the pour has no way to tell the difference afterwards: out of range the ray
            // returns MISS, which the blocked-line guard then reports as an obstruction it cannot
            // clear. One rung's evidence read `cast.range=12.12, cast.picks=MISS` — nothing was in
            // the way at all, the target was simply eight blocks past the end of the ray.
            //
            // Derived from the BUCKET's reach, not the digging one. Same number as before by
            // arithmetic; the point is that the threshold now moves with the constant it is about.
            double limit = JourneyFill.BUCKET_REACH - 0.5;
            double range = rig.player().getEyePosition()
                    .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(aim));
            if (range > limit && tries > 0) {
                rig.evidence("cast.tooFar", String.format(java.util.Locale.ROOT,
                        "%.1fm > %.1fm，再走一次", range, limit));
                approachAndPour(ctx, rig, aim, tries - 1);
                return;
            }
            pourInto(ctx, rig, aim);
        });
    }

    private static void pourInto(SceneContext ctx, JourneyRig rig, BlockPos target) {
        pourInto(ctx, rig, target, CAST_CLEARINGS);
    }

    /**
     * How many things the pour may clear out of its own line before giving up.
     *
     * <p>Two. The pour aims at the bed UNDER the water and the fluid lands in the cell in front of
     * whatever face the ray hits — so anything standing in that line silently moves the target one
     * cell. Measured: the rung chose `-15,62,42`, aimed at `-15,61,42`, and the pick came back
     * <b>`-15,62,42`</b> — the water cell itself, because it held <b>seagrass</b>. Seagrass has no
     * collision but it does have a {@code Block.OUTLINE} shape, which is what a bucket's clip uses.
     * The pour then reported `CONSUME`, the bucket emptied, and obsidian appeared at `-15,62,41`:
     * one cell short, cast against the near face of the plant. Swamp water is full of seagrass, so
     * this is terrain the ladder will meet every run, not an oddity.
     */
    private static final int CAST_CLEARINGS = 2;

    private static void pourInto(SceneContext ctx, JourneyRig rig, BlockPos target, int clearings) {
        ServerLevel level = ctx.level();
        var fp = rig.player();
        rig.evidence("cast.cell", target.toShortString());
        rig.evidence("cast.floor", String.valueOf(level.getBlockState(target.below()).getBlock()));
        rig.evidence("cast.range", String.format(java.util.Locale.ROOT, "%.2f",
                fp.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(target))));
        // Aimed at the FLOOR under the water, not at the water: a filled bucket ray-traces with
        // fluids ignored, so the block it lands on is the bed, and the fluid goes into the cell in
        // front of the face it hit — which is the water cell above.
        JourneyHands.holdForUse(rig, Items.LAVA_BUCKET, "cast");
        JourneyHands.aimThenAct(rig, target.below(), () -> {
            // hitFluids=false, matching what a FILLED bucket's own clip does. A pick that ignores
            // fluids is the only pick that predicts this pour.
            //
            // AND AT THE BUCKET'S OWN RANGE. This traced TUNNEL_REACH (5.0, the digging figure)
            // while the use it is predicting traces `blockInteractionRange()` = 4.5 — so between
            // 4.5 and 5.0 the guard approved a line the pour could not make, spending the run's one
            // bucket on a use that returns PASS and changes nothing. Third bucket site to carry the
            // wrong constant; the right one has been a javadoc in JourneyFill the whole time.
            var hit = JourneyHands.aimedAt(fp, JourneyFill.BUCKET_REACH, false);
            rig.evidence("cast.picks", hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    ? hit.getBlockPos().toShortString() + " " + level.getBlockState(hit.getBlockPos()).getBlock()
                      + " face=" + hit.getDirection()
                    : String.valueOf(hit.getType()));
            // Check the line BEFORE spending the bucket. The tunnel already works this way — mine
            // whatever the ray hits first, because that IS the obstruction — and the cast, which has
            // exactly one bucket of lava and no second try, did not. A pour down a blocked line is
            // not a failed pour: it is a SUCCESSFUL pour into the wrong cell, and by the time the
            // assertion reads the chosen cell the lava is gone.
            BlockPos bed = target.below();
            boolean onLine = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(bed);
            if (!onLine) {
                BlockPos inTheWay = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos() : null;
                // NEVER THE CELL UNDER THE BED. It is what holds the pool up, and a body clearing
                // its way up to a pool above itself is digging out the floor of the water it wants
                // to pour into — the reward for succeeding is the pool on its own head. The re-pick
                // guard above should mean this line is never reached; it stays because a guard that
                // is only correct while its neighbour is correct is not a guard.
                boolean holdsThePool = inTheWay != null && inTheWay.equals(bed.below());
                if (holdsThePool)
                    rig.evidence("cast.wontClear", inTheWay.toShortString()
                            + " 撑着要浇的那格水，清掉它等于把水放到自己头上 —— 不清");
                if (clearings > 0 && inTheWay != null && !holdsThePool) {
                    // A plant stops the RAY but not the BODY. short_grass and seagrass have no
                    // collider — the body walks through them — yet `getPlayerPOVHitResult` clips on
                    // Block.OUTLINE, which a plant has, so they land square on the aiming line. And
                    // MineProcess will not remove them: measured, two clearings in a row left the
                    // same seagrass standing, and short_grass cost run 9 this rung.
                    //
                    // So swing at it directly, which is what a player does — aim, hold, destroy.
                    // This is the body's own verb, not staging: `staging.calls` stays 0 and the rung
                    // keeps its claim. Solid blockers still go through mine, where the drop matters.
                    boolean noCollider = level.getBlockState(inTheWay)
                            .getCollisionShape(level, inTheWay).isEmpty();
                    rig.evidence("cast.blockedBy", inTheWay.toShortString() + " "
                            + level.getBlockState(inTheWay).getBlock()
                            + (noCollider ? "（无碰撞箱的植物：直接挥手清掉，mine 清不动）"
                                          : "（挡在瞄准线上，先清掉）"));
                    if (noCollider) {
                        // Aim BOTH bodies, then swing — see JourneyHands.swingOffPlant, which owns
                        // the whole shape and the two measurements that shaped it. Routing the break
                        // to the server avatar without moving the aim there (the previous fix here)
                        // left `aimTarget` null and destroyed nothing: this exact cell, `-4, 63, 55`,
                        // read back as `short_grass` on ladder-8 AND on the rehearsal that was run to
                        // verify that fix.
                        JourneyHands.swingOffPlant(rig, inTheWay);
                        rig.settle(new HoldStill(3), 12, () -> {
                            rig.evidence("cast.cleared." + inTheWay.toShortString(),
                                    String.valueOf(level.getBlockState(inTheWay).getBlock()));
                            pourInto(ctx, rig, target, clearings - 1);
                        });
                        return;
                    }
                    rig.mineBlock(inTheWay, 600, () -> rig.settle(new HoldStill(5), 20,
                            () -> pourInto(ctx, rig, target, clearings - 1)));
                    return;
                }
                // Out of clearings and the line is still not the bed: STOP. Pouring anyway spends
                // the run's only bucket of lava into whatever the ray does hit, and the rung then
                // reports "casts obsidian in the chosen cell (false)" — which reads as the cast
                // being broken, while `obsidian.anywhere` sits one metre away proving it is not.
                // A failure that names the obstruction is worth more than one that names the goal.
                // (Measured: two clearings in a row failed to remove the same seagrass, so the
                // third attempt poured blind. Why mineBlock cannot break it is a separate finding;
                // not choosing that cell in the first place is the fix, and this is the backstop.)
                ctx.fail("浇筑瞄准线被挡住，且清不掉：想浇 " + target.toShortString()
                        + "（瞄 " + bed.toShortString() + "），射线停在 "
                        + (inTheWay == null ? String.valueOf(hit.getType())
                            : inTheWay.toShortString() + " " + level.getBlockState(inTheWay).getBlock())
                        + "。没有倒 —— 一桶岩浆只有一次机会，倒下去只会浇歪并且把失败写成"
                        + "\"浇不出黑曜石\"");
                return;
            }
            // THE HAND, RE-ASSERTED — `aimThenAct` settles ten ticks between the hold above and this
            // use, and ten ticks is enough for a hold that went down `ensureHolding`'s bag branch to
            // come undone on both bodies at once. See JourneyHands.regripBeforeUse; rung 12 lost a
            // cast to it and reported the failure six legs later.
            // ⚠️ This site's earlier note said it「has never been bitten because nothing between the
            // two lines holds anything else」. ladder-12 (2026-08-23) falsified that from here:
            // `cast.handSlipped` fired with COBBLESTONE in slot 0 on both bodies — the shaft climb-out
            // pillars up to 36 blocks of cobblestone, and the pillar's own hold is what displaces the
            // bucket. The neighbour that bites is not the next line, it is the last leg.
            // SILENCE THE OTHER AUTHOR FOR THE LENGTH OF THE POUR.
            //
            // Re-gripping is as close to the use as a caller can get and ladder j46 proved it is
            // still not close enough: `cast.handSlipped` fired, `cast.again.hand` re-took the
            // bucket, `cast.atUse` recorded lava_bucket on BOTH ends — and then
            // `handTrace.t0.server = gameTime=28476 槽4 = lava_bucket` was followed by
            // `t1.server = gameTime=28477 槽4 = cobblestone ×29`. The slot INDEX never moved (the
            // row prints `inv.selected`), so what changed is the slot's CONTENTS, which is
            // `BotInteract.ensureHoldingPillarBlock`'s tail: a real SWAP click that pulls a pillar
            // block out of the main inventory into the selected slot and pushes the bucket back
            // into the bag — hence `lava_bucket.after = 1` beside a cell that is still water.
            //
            // Its three call sites (WalkerTickDrive:210, WalkerTickStallDetect:269/282) and the
            // two `ensureHoldingPlaceableAny` ones (Walker:1771/2379) ALL short-circuit on
            // `BotConfig.allowPlace` before they touch the hand, so turning it off for these twelve
            // ticks makes the swap unreachable rather than merely unlikely. Nothing here needs to
            // place: the body is standing still, aimed, about to empty a bucket.
            //
            // Restored on every exit below — the fail branch and the settle's completion — because
            // the walk that follows the pour DOES need to pillar.
            boolean placeWas = BotConfig.allowPlace;
            BotConfig.allowPlace = false;
            // AND A NET UNDER IT. The two restores below cover every path this method can take, but
            // not the one it cannot: `cast.handTrace.samples` documents that a body which leaves the
            // world skips the settle outright, and then the flag would stay false for whatever runs
            // after — a global flipped by a scene that never came back. Cleanups drain on every exit,
            // including a timeout, so the flag cannot outlive the scene that turned it off.
            ctx.cleanup(() -> BotConfig.allowPlace = placeWas);
            rig.evidence("cast.placeHeldOff", "浇的这一段关掉放置权（原值 " + placeWas
                    + "）—— 垒塔的 hold 是 SWAP 走槽内容的那个作者，三个调用点都先短路 allowPlace");
            boolean gripped = JourneyHands.regripBeforeUse(rig, Items.LAVA_BUCKET, "cast");
            // BOTH BODIES AT THE INSTANT OF THE USE, unconditionally — the row ladder-12 needed and
            // did not have. `useItemInHand` is `MultiPlayerGameMode.useItem`, i.e. a CLIENT-side
            // prediction over the CLIENT's stack, so its SUCCESS says only「the client held a
            // bucket」. That run returned SUCCESS and read `lava_bucket.after = 1` off the server —
            // a full bucket after a successful pour — and nothing on disk could say whether the
            // server had a bucket in that slot at all. regripBeforeUse checks `actingHolds`, which
            // is also the client; so「re-gripped」and「the server agrees」are two claims and only one
            // of them was ever recorded.
            JourneyHands.handsAtUse(rig, "cast");
            if (!gripped) {
                BotConfig.allowPlace = placeWas;
                ctx.fail("开浇的那只手不是 minecraft:lava_bucket，重新拿过一次也没拿到："
                        + JourneyHands.heldOnBoth(rig)
                        + " —— 没有倒。空手 use 只会返回 PASS，"
                        + "然后这一级会把失败写成「浇不出黑曜石」");
                return;
            }
            // IS THE BODY STILL MOVING? `cast.atUse` shows both ends agreeing on the eye — but it
            // samples HERE, and the server runs the ray when it processes the packet, one or more
            // movement packets later. The angle cannot drift (ServerboundUseItemPacket carries
            // yRot/xRot and handleUseItem absRotateTo's before useItem); the POSITION can, and
            // `getPlayerPOVHitResult` starts the line at the eye. Over 2.98 blocks a few tenths
            // changes which face is hit, and a MISS returns PASS — which spends no bucket, changes
            // no block, and still lets the client predict SUCCESS. Exactly ladder-16's rung 11.
            //
            // The reason to suspect motion here rather than assume stillness: that rung's own
            // `cast.walk` row read `end=unavailable`, i.e. the walk was STILL DRIVING when its
            // budget ended. Releasing the controls is not braking.
            var vel = rig.player().getDeltaMovement();
            rig.evidence("cast.motionAtUse", String.format(java.util.Locale.ROOT,
                    "速度=(%.4f,%.4f,%.4f) |水平|=%.4f onGround=%s；goto 槽 end=%s",
                    vel.x, vel.y, vel.z, Math.hypot(vel.x, vel.z), rig.player().onGround(),
                    String.valueOf(rig.slotEnd("goto"))));
            // THE CALIBRATION ROW, in the watcher's own format and taken BEFORE the use. It reads
            // the same instant `cast.atUse` does — nothing between the two lines ticks the server —
            // so if the two disagree the INSTRUMENT is broken and nothing below it may be read as a
            // fact about the world. Comparing the watcher's t0 against `cast.atUse` would not do
            // this job: t0 is taken after `useItemInHand` has already run.
            JourneyHands.handTrace(rig, "cast", -1);
            rig.evidence("cast.result", String.valueOf(rig.avatar().useItemInHand()));
            // THE HAND ON CONSECUTIVE SERVER TICKS. `cast.result` is the CLIENT's prediction and
            // `cast.stillFull` is the SERVER ten ticks later; between them sits the moment that
            // decides this rung — the tick on which the server processes the use packet and reads
            // its OWN `inventory.selected`. Nothing here has ever sampled that, so a hand that was
            // right at the send and wrong at the handling is indistinguishable from a refusal.
            //
            // The watcher is the sampling site rather than the scene body because the watcher runs
            // on the SERVER thread (JourneyRig.settle → await → SceneContext.advance →
            // StageWrightHarness, which is driven from the server tick event). Sampling from a
            // per-tick step of this scene's own chain would read the server's inventory across the
            // same thread boundary the defect is about, i.e. copy the bug into the instrument.
            //
            // A counter rather than the watcher's own tick number: TickWatcher takes no argument,
            // and the window has to stop — 6 ticks × 2 bodies is 12 rows, and the rest of the
            // settle would add 8 more that answer nothing.
            int[] traced = {0};
            rig.settle(new HoldStill(10), 20, () -> {
                if (traced[0] < JourneyHands.TRACE_TICKS) {
                    JourneyHands.handTrace(rig, "cast", traced[0]++);
                }
            }, () -> {
                // FIRST LINE OF THE CALLBACK, before anything can fail or return: the pour is over,
                // and everything after it — the walk home, the next fetch — is allowed to pillar.
                BotConfig.allowPlace = placeWas;
                // HOW MANY TICKS THE INSTRUMENT ACTUALLY SAW, so that silence can be read. Three
                // outcomes have to look different on disk and this row is what separates them:
                // this row missing entirely ⇒ the run never reached the pour (未触发, evidence for
                // neither side); this row present with 0 ⇒ the settle was skipped (the body left
                // the world) and the instrument never fired, so its silence is also not evidence;
                // this row present with 6 ⇒ the trace rows above are the answer.
                rig.evidence("cast.handTrace.samples", "采到 " + traced[0] + "/"
                        + JourneyHands.TRACE_TICKS + " 个服务端 tick。t0 与 useItemInHand 落在同一个"
                        + "服务端 tick（settle 的等待在加入它的那一次 advance() 里就被求值一次）——"
                        + "以每行的 gameTime 为准，别以 tick 序号为准。"
                        + "采到 0 ⇒ settle 被跳过（身体掉出世界），仪器没响，这一趟的沉默不算证据；"
                        + "整组 cast.handTrace.* 都不存在 ⇒ 这一趟根本没走到这一浇，同样不算证据。");
                var got = level.getBlockState(target).getBlock();
                rig.evidence("cast.cellAfter", String.valueOf(got));
                rig.evidence("lava_bucket.after", rig.carrying("minecraft:lava_bucket"));
                rig.evidence("bucket.after", rig.carrying("minecraft:bucket"));
                // Where it went when it did not go here. An empty bucket with no obsidian in the
                // chosen cell is a misplacement; a full bucket is a refusal; and the two want
                // opposite fixes.
                BlockPos anywhere = rig.nearestBlock("minecraft:obsidian", 8, 4);
                rig.evidence("obsidian.anywhere", anywhere == null ? "无" : anywhere.toShortString());
                // A THIRD outcome the pair above cannot name: the client predicted the pour and the
                // server never made it. `cast.result` comes from the client and this count comes
                // from the server, so「SUCCESS ＋ 桶还是满的」is not a contradiction to explain away
                // — it is the two ends disagreeing, and it reads exactly like a refusal until the
                // two sources are named. Written whenever it happens, since the whole rung turns on
                // this one bucket.
                if (rig.carrying("minecraft:lava_bucket") > 0)
                    rig.evidence("cast.stillFull",
                            "服务端读到岩浆桶还有 " + rig.carrying("minecraft:lava_bucket")
                            + " 个，而 cast.result 是客户端 MultiPlayerGameMode.useItem 的预测 —— "
                            + "两个数来自两端。要判是「服务端那只手不对」还是「两端都拿着桶但这一浇被拒」，"
                            // cast.atUse 只答得了「发包那一刻」；服务端读的是「处理包那一刻」的手，
                            // 这两刻之间隔着几个 tick，而 cast.handTrace.* 是唯一采到了那几个 tick 的行。
                            + "去读 cast.handTrace.t*.server 那一组（use 之后连采的服务端 tick，"
                            + "先看 cast.handTrace.samples 确认仪器响了）；"
                            + "cast.atUse 只是发包那一刻，答不了处理包那一刻。"
                            // ⚠️ 这一段是 settle 之后 重新读的一次活状态，不是 cast.atUse 那一行。
                            // 原文写的是「读 cast.atUse 那一行的服务端半边：」后面直接接这个值,
                            // 于是一行自称在引用另一行、实际又量了一次，而且隔了 10 tick 的 settle
                            // ——两者不同的那一趟，正是这行字最会骗人的那一趟。
                            + "（下面这个是 settle 之后重新读的，隔了 10 tick，不是 use 那一刻）"
                            + JourneyHands.heldOnBoth(rig));
                ctx.expect(got == Blocks.OBSIDIAN)
                        .as("lava poured into standing water casts obsidian in the chosen cell").isTrue();
                // The claim, and what the claim is NOT. A rung that fetched its lava, climbed one
                // block of thirty-six, and then found water in the cave it was already standing in
                // has cast obsidian — the stage's whole assertion — while the exit it was supposed
                // to prove never happened. Saying so on the green row is the difference between a
                // ladder and a scoreboard.
                String how = rig.player().blockPosition().getY() < 50
                        ? "（在地下 y=" + rig.player().blockPosition().getY() + " 浇的，没能爬回地面）"
                        : "";
                rig.reach("在 " + target.toShortString() + " 浇出黑曜石，桶已回到手上 ×"
                        + rig.carrying("minecraft:bucket") + how);
            });
        });
    }
}
