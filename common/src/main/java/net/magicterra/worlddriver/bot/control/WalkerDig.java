package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.game.body.BotUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * The walker's one dig door — every walker dig routes through {@link #avatarDig}; aim at the cell
 * it returns. Held outside {@link Walker} for that file's line budget.
 */
final class WalkerDig {
    private WalkerDig() {}

    /**
     * Break {@code cell}: hold the key AND drive the destroy directly, never one alone. On a client
     * avatar {@link BlockInteraction#breakHold} only rides vanilla's continueAttack pipeline, which a driven
     * client never reaches because the mouse is never grabbed — so the key by itself breaks
     * nothing. Server avatars break on the key and take the destroy as an inherited no-op, which is
     * why every wd.server* dig scene passed for as long as the walker drove the key alone.
     *
     * <p>THE ONE DOOR. Every walker dig routes through here, and here is where the cell is decided:
     * the caller's cell is a <i>request</i>, the returned cell is what was actually driven. Vanilla's
     * {@code MultiPlayerGameMode} tracks exactly ONE destroy target, so a phase that drives a second
     * cell does not merely wait its turn — it runs {@code startDestroyBlock} and throws the other
     * phase's accumulated {@code destroyProgress} away. Seven call sites each held their own opinion
     * about whether to claim the slot, whether to claim before or after digging, and which cell to
     * hand the avatar; three of them dug a cell nobody had claimed. Aim at the RETURNED cell.
     *
     * @param selectTool pick the best tool first — only for the sites that did so before the door
     *                   existed; a site that never swapped tools must not start now.
     */
    static BlockPos avatarDig(Walker wk, Body a, BlockPos cell, boolean selectTool) {
        BlockPos target = cell;
        if (wk != null && cell != null && (ControlConfig.walkerStickyDig || ControlConfig.walkerDigAimPriority)) {
            wk.stickyDig.engage(cell);
            if (wk.stickyDig.pos != null) target = wk.stickyDig.pos;
        }
        // Tool and crosshair go on the cell actually being driven, never on the cell that was merely
        // requested: vanilla's sameDestroyTarget compares the HELD ITEM as well as the position, so
        // swapping the tool mid-dig throws the progress away exactly the way switching cells does.
        if (selectTool) wk.equipment.selectTool(target);
        a.aimAtBlock(target);
        wk.blocks.breakHold(true);
        wk.blocks.continueDestroy(target);
        if (wk != null) {
            wk.digDrivenThisTick = true;
            wk.digKeyOwned = true;
            wk.tallies.dig(target);
        }
        return target;
    }

    /** Yaw off the body's heading past which a pre-dig would turn the jump rather than tilt the head.
     *  Diagonal stair cells sit 27-45° off (live 1388,92,-527 and 1388,100,-534), and air control is
     *  too weak for that turn to move the landing off its cell. */
    private static final float PRE_DIG_MAX_YAW = 50f;

    /**
     * Start the next stairUpBreak's dig while still airborne on the step before it. Every finished
     * break leaves vanilla's 5-tick {@code destroyDelay} behind and only a {@code continueDestroyBlock}
     * call counts it down, so a stair cut seconds after the previous one stood still for it before any
     * progress — a player holding the attack key up a cliff burns it on the way. Live R1 cliff: 9 ticks
     * per cut of a 3-tick grass block, five cuts a run. Unclaimed on purpose: the landing tick's
     * actuator claims the same cell, and if the hold lapses first the abort zeroes the progress but
     * not the burned delay.
     */
    static void preDigNextBreak(Walker wk, Body a, WorldView world) {
        if (!ControlConfig.walkerPreDigNextBreak || wk.path == null || wk.stickyDig.pos != null) return;
        LivingEntity p = a.entity();
        if (p == null || p.onGround() || p.isInWater() || wk.blocks.destroyProgress() < 0) return;
        if (wk.step + 1 >= wk.path.size()) return;
        Move.Edge cur = wk.edgeAt(wk.step), next = wk.edgeAt(wk.step + 1);
        if (next == null || !"stairUpBreak".equals(next.move) || !next.toPlace.isEmpty()) return;
        if (cur != null && PathSmoothing.hasPendingEdge(world, cur)) return;
        BlockPos b = null;
        for (BlockPos c : next.toBreak) if (world.isSolid(c)) { b = c; break; }
        // Only the stair cell above the landing: one level with it sat beside and behind the body,
        // and turning down to it mid-jump (live 1388,86,-521, pitch 50) missed the landing, 17 ticks.
        if (b == null || b.getY() <= wk.path.get(wk.step).getY()
                || !BotUtil.eyeWithin(p, b, BotUtil.blockReachToCentre(p))) return;
        double yaw = Math.toDegrees(Math.atan2(-(b.getX() + 0.5 - p.getX()), b.getZ() + 0.5 - p.getZ()));
        if (Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) > PRE_DIG_MAX_YAW) return;
        wk.equipment.selectTool(b);
        a.aimAtBlock(b);
        wk.blocks.breakHold(true);
        wk.blocks.continueDestroy(b);
        wk.digDrivenThisTick = true;
        wk.digKeyOwned = true;
    }

    /** {@link #avatarDig} for a dig that must not queue: suffocation. Takes the slot, then digs. */
    static BlockPos avatarDigPreempt(Walker wk, Body a, BlockPos cell, boolean selectTool) {
        if (wk != null) wk.stickyDig.revoke();
        return avatarDig(wk, a, cell, selectTool);
    }
}
