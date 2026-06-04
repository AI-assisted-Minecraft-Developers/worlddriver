package net.magicterra.agent.bot.auto;

import net.magicterra.agent.bot.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import static net.magicterra.agent.AgentDriverCommon.LOG;
import static net.magicterra.agent.bot.util.BotInteract.aimAtBlockSnap;
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
 */
public final class AntiSuffocate {
    private AntiSuffocate() {}

    /** True while we are the one driving the attack key, so we release our own hold. */
    private static boolean held;

    /** @return true if it took over to break a suffocating head block this tick. */
    public static boolean tick(Minecraft mc, LocalPlayer p) {
        boolean suffocating = BotConfig.antiSuffocate && BotConfig.allowBreak
                && mc.level != null && p.isInWall();
        if (!suffocating) { releaseIfHeld(mc); return false; }

        // The block intersecting the eyes is what's choking us. The eye box can
        // straddle two cells; if the eye cell reads air, fall back to the cell
        // just above the foot (the standard head block).
        BlockPos head = BlockPos.containing(p.getEyePosition());
        BlockState st = mc.level.getBlockState(head);
        if (st.isAir()) {
            head = p.blockPosition().above();
            st = mc.level.getBlockState(head);
            if (st.isAir()) { releaseIfHeld(mc); return false; }
        }
        // Don't flail at an unbreakable block (bedrock = negative destroy speed).
        if (st.getDestroySpeed(mc.level, head) < 0f) { releaseIfHeld(mc); return false; }

        selectBestToolFor(mc, head);
        aimAtBlockSnap(p, head);
        mc.options.keyAttack.setDown(true);
        held = true;
        if (BotConfig.walkerDebug)
            LOG.info("[antiSuffocate] head suffocating → breaking {} ({})", head, st.getBlock());
        return true;
    }

    private static void releaseIfHeld(Minecraft mc) {
        if (held) { mc.options.keyAttack.setDown(false); held = false; }
    }
}
