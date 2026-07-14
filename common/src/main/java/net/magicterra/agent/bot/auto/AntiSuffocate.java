package net.magicterra.agent.bot.auto;

import net.magicterra.agent.bot.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.util.BotInteract.aimAtBlockSnap;
import static net.magicterra.agent.bot.util.BotInteract.pickFaceTowardsPlayer;
import static net.magicterra.agent.bot.util.BotInteract.selectBestToolFor;

/**
 * Suffocation backstop (sibling of {@link AutoSwim}): when a solid block collapses
 * into the bot's HEAD/eye cell — classically falling SAND while digging up or down
 * through a disturbed pit — break that block so the bot stops taking sustained
 * suffocation damage and keeps a clear upward channel. An unconditional survival
 * reflex run every tick after the scheduler, NOT a movement chain.
 *
 * <p>Why it exists: a naked bot dug into an old, disturbed sand pit and the sand
 * kept caving into its head faster than bunker/goto/escape cleared it → HP 17→11
 * in seconds, dig-out oscillating y56↔y59 with no progress. None of those
 * processes guards the head cell; this does, generally, for all of them.
 *
 * <p>Mechanics: gated on {@link BotConfig#antiSuffocate} + {@link BotConfig#allowBreak}
 * (it mines the head block). Fires only while {@link LocalPlayer#isInWall()} (a
 * suffocating block overlaps the eyes). In a falling-sand column it chews UPWARD —
 * each break lets the column above drop in and the top becomes air — until the
 * head clears, which both stops the damage and opens the escape channel. Tracks
 * its own key hold so it releases attack exactly once when suffocation ends,
 * without clobbering a process that wasn't digging.
 *
 * <p>Scope (learned the hard way, then verified live via the client introspection
 * that became {@code mc.client.player}): vanilla aggressively self-rescues a
 * player from a static head block — the moment there's a 1-tall air gap it drops
 * to CROUCH/SWIM pose (eye height ~0.4), sliding the eye <em>below</em> the block,
 * so {@code isInWall()} goes false and there is nothing to break. That is correct:
 * this reflex must NOT fight vanilla's crawl-evade (no false positives, confirmed
 * — it stays idle whenever the bot can crawl out). It fires only when the eye is
 * genuinely encased with no crawl gap (STANDING, fully surrounded) — the dynamic
 * caving-sand death it was built for.
 *
 * <p>Live A/B cert (2026-06-04): tp the bot into the centre of a 3³ solid cube,
 * giving the CLIENT a few ticks to receive the blocks <em>before</em> the bot
 * enters (so client and server agree — placing a block into the bot's
 * already-occupied cell instead desyncs: the client crawl-evades into air while
 * only the server suffocates). With the cube synced, the client reports
 * {@code pose=STANDING, inWall=true, eyeBlock=dirt}. antiSuffocate OFF → the eye
 * block persists and HP keeps dropping; antiSuffocate ON → the reflex chews the
 * eye block to air within ~20 ticks ("head suffocating → breaking" ×23),
 * {@code inWall} flips false and the bleed stops. Same mechanic covers real
 * client-synced falling-sand cave-ins.
 *
 * <p><b>gap#69 (live death #16):</b> the cube A/B above deliberately synced the
 * client BEFORE entry. A raw {@code tp} into the CENTER of already-solid terrain
 * is the opposite case — client/server DESYNC on entry — and {@code isInWall()}
 * is a pure client geometry read that can disagree with the server's own
 * suffocation bookkeeping on exactly that tick: the bot took full HP→0 damage
 * with this reflex reading {@code isInWall()==false} every tick, zero action.
 * The server's damage attribution is authoritative and trusted ABOVE the client
 * geometry: {@link LocalPlayer#getLastDamageSource()} mirrors the server's last
 * hurt cause via {@code ClientboundDamageEventPacket} (vanilla's own ~40-tick
 * last-damager window, same one {@link net.magicterra.agent.bot.combat.ThreatScanner}
 * and {@link net.magicterra.agent.bot.ClientEventDetector} already lean on for
 * gap#55 attribution) — its {@code msgId} is {@code "inWall"} while suffocation
 * damage keeps landing, geometry read or not. {@link AntiSuffocateGate#shouldTrigger}
 * folds both signals into one static, matrix-tested gate (split into its own
 * dependency-free file so a server-side gametest can call it with no client).
 *
 * <p><b>final-review M1:</b> {@code shouldTrigger}'s ~40-tick damage-window can
 * outlive the actual suffocation by up to 40 ticks after the bot is freed. That is
 * harmless for the eye/above legs in {@link #resolveHead} (they just re-check an
 * already-air cell), but the foot/horizontal fallback legs are a last-resort guess
 * that degenerates into digging the bot's own foot or a shaft/bunker wall during
 * that stale tail. Those two legs are additionally gated on
 * {@link AntiSuffocateGate#allowProximityFallback} — see its javadoc and
 * {@link #resolveHead}.
 */
public final class AntiSuffocate {
    private AntiSuffocate() {}

    /** gap#69: once the raycast-driven path has failed to land on the target for
     *  this many consecutive ticks, stop trusting {@code keyAttack} and drive the
     *  destroy pipeline directly (see {@link #tick}). */
    private static final int RAYCAST_BYPASS_TICKS = 10;

    /** True while we are the one driving the attack key, so we release our own hold. */
    private static boolean held;
    /** True while we're bypassing keyAttack and driving gameMode.continueDestroyBlock
     *  directly for the current head block (gap#69, requirement 3). */
    private static boolean directDrive;
    /** The head cell the raycast-miss counter below is tracking; reset whenever the
     *  resolved target changes (new suffocation episode or the fallback chain moved). */
    private static BlockPos trackedHead;
    /** Consecutive ticks the camera raycast ({@code mc.hitResult}) failed to land on
     *  {@link #trackedHead} while we were actively aiming at it. */
    private static int rayMissTicks;

    /** @return true if it took over to break a suffocating head block this tick. */
    public static boolean tick(Minecraft mc, LocalPlayer p) {
        if (mc.level == null) { reset(mc); return false; }
        String lastDamageMsgId = null;
        DamageSource src = p.getLastDamageSource();
        if (src != null) lastDamageMsgId = src.getMsgId();

        boolean suffocating = AntiSuffocateGate.shouldTrigger(p.isInWall(), lastDamageMsgId,
                BotConfig.antiSuffocate, BotConfig.allowBreak);
        if (!suffocating) { reset(mc); return false; }

        BlockPos head = resolveHead(mc, p);
        if (head == null) { reset(mc); return false; }
        BlockState st = mc.level.getBlockState(head);
        // Don't flail at an unbreakable block (bedrock = negative destroy speed).
        if (st.getDestroySpeed(mc.level, head) < 0f) { reset(mc); return false; }

        if (!head.equals(trackedHead)) { trackedHead = head; rayMissTicks = 0; directDrive = false; }

        selectBestToolFor(mc, head);
        aimAtBlockSnap(p, head);

        // gap#69 requirement 3: the camera raycast (mc.hitResult) can fail to land on
        // `head` even after aiming dead-center at it — the eye origin sits INSIDE solid
        // geometry when we're genuinely embedded, and a raycast starting inside a solid
        // block can miss entirely or resolve to the wrong face/block. keyAttack rides
        // that same raycast (vanilla's continueAttack → gameMode.continueDestroyBlock),
        // so a persistently-missing raycast means keyAttack silently does nothing while
        // this reflex believes it's breaking. Track consecutive misses and, past the
        // threshold, drive gameMode.continueDestroyBlock directly — it self-starts via
        // startDestroyBlock on the first call for a new target, no separate call needed.
        boolean rayOnTarget = mc.hitResult instanceof BlockHitResult bhr
                && bhr.getType() == HitResult.Type.BLOCK && bhr.getBlockPos().equals(head);
        if (rayOnTarget) rayMissTicks = 0; else rayMissTicks++;
        if (!directDrive && rayMissTicks >= RAYCAST_BYPASS_TICKS) directDrive = true;

        if (directDrive) {
            if (held) { mc.options.keyAttack.setDown(false); held = false; }
            Direction face = pickFaceTowardsPlayer(head, p);
            mc.gameMode.continueDestroyBlock(head, face);
            if (BotConfig.walkerDebug)
                LOG.info("[antiSuffocate] raycast miss x{} → direct-driving destroy on {} ({})",
                        rayMissTicks, head, st.getBlock());
        } else {
            mc.options.keyAttack.setDown(true);
            held = true;
            if (BotConfig.walkerDebug)
                LOG.info("[antiSuffocate] head suffocating → breaking {} ({})", head, st.getBlock());
        }
        return true;
    }

    /** The block intersecting the eyes is what's choking us. The eye box can straddle
     *  two cells; fall back to the cell just above the foot (the standard head block),
     *  then — ONLY while damage is still FRESH, see below — the foot cell itself and a
     *  solid HORIZONTAL neighbour of the eye cell. gap#69 requirement 2: a damage-
     *  signal-driven trigger can fire on a tick where the client's geometry is fully
     *  desynced and reads AIR at eye/above/foot alike — the damage is real (we only
     *  got here because {@link AntiSuffocateGate#shouldTrigger} matched), so as a last
     *  resort hug a solid HORIZONTAL neighbour of the eye cell: sustained suffocation
     *  damage means some solid block is touching us even if vanilla's client-side
     *  render hasn't caught up yet.
     *
     *  <p><b>final-review M1:</b> {@code shouldTrigger} rides vanilla's ~40-tick last-
     *  damager window, which outlives the actual suffocation by up to 40 ticks after
     *  the bot is freed (eye/above legs clear harmlessly during that tail — at worst a
     *  redundant air check). The foot/horizontal legs do NOT degrade harmlessly: once
     *  freed, eye/above read air too, so these legs fall through to the bot's OWN foot
     *  cell or the first solid neighbour of the eye — typically the shaft/bunker wall —
     *  and chew it for the trailing ~2s (a bunker-wall breach on every successful
     *  rescue). Gate them on {@link AntiSuffocateGate#allowProximityFallback}
     *  ({@code p.hurtTime>0}): real suffocation re-damages every ~10 ticks so hurtTime
     *  stays hot for the whole episode, but decays to 0 within ≤10 ticks of freedom —
     *  well inside the stale 40-tick tail.
     *  @return the block to break, or null if nothing nearby reads solid. */
    private static BlockPos resolveHead(Minecraft mc, LocalPlayer p) {
        BlockPos eye = BlockPos.containing(p.getEyePosition());
        if (!mc.level.getBlockState(eye).isAir()) return eye;
        BlockPos above = p.blockPosition().above();
        if (!mc.level.getBlockState(above).isAir()) return above;
        if (!AntiSuffocateGate.allowProximityFallback(p.hurtTime)) return null;
        BlockPos foot = p.blockPosition();
        if (!mc.level.getBlockState(foot).isAir()) return foot;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = eye.relative(d);
            if (!mc.level.getBlockState(n).isAir()) return n;
        }
        return null;
    }

    private static void reset(Minecraft mc) {
        if (held) { mc.options.keyAttack.setDown(false); held = false; }
        if (directDrive) { mc.gameMode.stopDestroyBlock(); directDrive = false; }
        trackedHead = null;
        rayMissTicks = 0;
    }
}
