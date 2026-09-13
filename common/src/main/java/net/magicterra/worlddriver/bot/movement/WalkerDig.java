package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.minecraft.core.BlockPos;

/**
 * The walker's one dig door — every walker dig routes through {@link #avatarDig}. Held outside
 * {@link Walker} for that file's line budget; the two static delegations there keep the seven
 * call sites unchanged.
 */
final class WalkerDig {
    private WalkerDig() {}

    /**
     * Break {@code cell}: hold the key AND drive the destroy directly, never one alone. On a client
     * avatar {@link Hands#breakHold} only rides vanilla's continueAttack pipeline, which a driven
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
    static BlockPos avatarDig(Walker wk, Avatar a, BlockPos cell, boolean selectTool) {
        BlockPos target = cell;
        if (wk != null && cell != null && (BotConfig.walkerStickyDig || BotConfig.walkerDigAimPriority)) {
            wk.stickyDig.engage(cell);
            if (wk.stickyDig.pos != null) target = wk.stickyDig.pos;
        }
        // Tool and crosshair go on the cell actually being driven, never on the cell that was merely
        // requested: vanilla's sameDestroyTarget compares the HELD ITEM as well as the position, so
        // swapping the tool mid-dig throws the progress away exactly the way switching cells does.
        if (selectTool) wk.hands.selectTool(target);
        a.aimAtBlock(target);
        wk.hands.breakHold(true);
        wk.hands.continueDestroy(target);
        if (wk != null) {
            wk.digDrivenThisTick = true;
            wk.digKeyOwned = true;
            wk.tallies.dig(target);
        }
        return target;
    }

    /** {@link #avatarDig} for a dig that must not queue: suffocation. Takes the slot, then digs. */
    static BlockPos avatarDigPreempt(Walker wk, Avatar a, BlockPos cell, boolean selectTool) {
        if (wk != null) wk.stickyDig.revoke();
        return avatarDig(wk, a, cell, selectTool);
    }
}
