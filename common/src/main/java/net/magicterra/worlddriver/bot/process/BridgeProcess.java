package net.magicterra.worlddriver.bot.process;

import net.magicterra.worlddriver.bot.BotState;
import net.magicterra.worlddriver.bot.BodyReady;
import net.magicterra.worlddriver.bot.body.Body;
import net.magicterra.worlddriver.bot.body.Hands;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;

public final class BridgeProcess implements BotProcess {
    /** This tick's hands, bound at the top of {@link #tick}, which is the one place they can be absent. */
    private Hands hands;
    private static final int STUCK_TICKS = 80;
    /** Max |player yaw − bridge yaw| before the forward key is allowed: the raw
     *  forward impulse walks along the CAMERA yaw, and on the client the LookController
     *  re-clamps the camera to ~30°/tick AFTER this process writes it — so the first
     *  WALKING ticks used to drive up to 180° off the bridge axis, over a 1×1 pillar top
     *  that is a walk into the void. Gate on the PRE-write yaw (the rendered truth). */
    private static final float YAW_ALIGN_DEG = 20f;

    private final Direction face;
    private final int distance;
    private final String preferredBlockId;
    private BlockPos startFoot;
    private int placed;
    private String failure;

    @Override public String failure() { return failure; }
    /** Cell our last placeOn click targeted, pending world confirmation — `placed` counts
     *  only cells OBSERVED solid afterwards (gap #75-a family audit: a blind placed++ on a
     *  silently no-op'd click decouples the counter from the world). */
    private BlockPos pendingPlaceCell;
    private int stuckTicks;
    private int lastProgress;
    private Phase phase = Phase.WALKING;
    private enum Phase { WALKING, PLACING, DONE }

    public BridgeProcess(Direction face, int distance, String preferredBlockId) {
        this.face = face;
        this.distance = distance;
        this.preferredBlockId = preferredBlockId;
    }

    public String kind() { return "builder"; }

    public void attach(BotState st) {
        st.builder.active = true;
        st.builder.goal = "bridge " + face.getName() + " ×" + distance
                + (preferredBlockId != null ? " (" + preferredBlockId + ")" : "");
        st.builder.startedAtMs = System.currentTimeMillis();
        st.builder.lastError = null;
    }

    @Override public boolean tick(Body a, WorldView w, BotState st) {
        LivingEntity p = a.entity();
        if (p == null) { failure = st.builder.lastError = "player vanished"; st.builder.reset(); return true; }
        hands = a.hands().orElse(null);
        if (hands == null) { failure = st.builder.lastError = BodyReady.Reason.NO_HANDS; st.builder.reset(); return true; }
        Level lvl = p.level();
        // Player yaw BEFORE this tick's snap-write below: on the client the LookController
        // re-clamps the camera after every actuator, so the value we WRITE is not the yaw
        // the player travels by — the pre-write value is the rendered truth.
        float yawNow = p.getYRot();
        BlockPos foot = anchoredFoot(p, lvl);
        if (startFoot == null) startFoot = foot;
        int progress = Math.abs(foot.getX() - startFoot.getX()) + Math.abs(foot.getZ() - startFoot.getZ());
        st.builder.target = foot;
        st.builder.pathStep = progress;
        st.builder.pathLen = distance;

        if (progress >= distance) {
            st.builder.lastError = "done (placed=" + placed + ", progress=" + progress + ")";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }
        if (progress > lastProgress) { lastProgress = progress; stuckTicks = 0; }
        else if (++stuckTicks > STUCK_TICKS) {
            failure = st.builder.lastError = "stuck (no XZ progress in " + STUCK_TICKS + "t — out of blocks or blocked)";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }

        if (!TowerProcess.ensureHoldingPlaceable(hands, preferredBlockId)) {
            failure = st.builder.lastError = "no placeable block in hotbar";
            st.builder.reset();
            a.releaseInputs();
            return true;
        }

        // Sneak always — prevents falling off the bridge edge.
        a.commandSneak(true);
        p.setShiftKeyDown(true);

        // Always face the travel direction.
        float yaw = face == Direction.SOUTH ? 0f
                  : face == Direction.WEST  ? 90f
                  : face == Direction.NORTH ? 180f
                  : /* EAST */                270f;
        p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw;

        // The cell directly ahead of our feet, one step in `face`.
        BlockPos aheadFoot = foot.offset(face.getStepX(), 0, face.getStepZ());
        BlockPos aheadSupport = aheadFoot.offset(0, -1, 0);
        boolean aheadSupportSolid = lvl.getBlockState(aheadSupport).blocksMotion();

        // Credit the last click once its cell is observed solid (see pendingPlaceCell).
        if (pendingPlaceCell != null && lvl.getBlockState(pendingPlaceCell).blocksMotion()) {
            placed++;
            pendingPlaceCell = null;
        }

        switch (phase) {
            case WALKING -> {
                if (!aheadSupportSolid) {
                    // Reached an edge — switch to PLACING. Release walk
                    // input so we don't drift off while we turn around.
                    a.commandForward(0f);
                    phase = Phase.PLACING;
                    return false;
                }
                // Gate the forward key on the PLAYER actually facing down the bridge axis:
                // the raw impulse walks along the camera yaw, which pans toward `yaw` at
                // the LookController's slew rate — walking before it arrives drives off
                // the bridge line (over a pillar start, into the void). Gap #75-a.
                if (Math.abs(wrapDeg(yawNow - yaw)) > YAW_ALIGN_DEG) {
                    a.commandForward(0f);
                    return false;
                }
                a.commandForward(1f);
            }
            case PLACING -> {
                a.commandForward(0f);
                if (aheadSupportSolid) {
                    // Either the previous place succeeded, or the world
                    // moved under us — resume walking.
                    phase = Phase.WALKING;
                    return false;
                }
                // Place by clicking the current support's forward face.
                // Support sits under the ANCHORED foot; clicking its +face
                // puts the new block at aheadSupport, which becomes the
                // support for the next step.
                BlockPos currentSupport = foot.offset(0, -1, 0);
                if (!lvl.getBlockState(currentSupport).blocksMotion()) {
                    // The anchored foot has no support either — the bot is genuinely
                    // airborne (anchoredFoot already re-anchored any sneak overhang).
                    failure = st.builder.lastError = "no support under feet (fell off?)";
                    st.builder.reset();
                    a.releaseInputs();
                    return true;
                }
                // Look slightly down and toward the forward face.
                p.setXRot(45f);
                hands.placeOn(currentSupport, face);
                pendingPlaceCell = aheadSupport;   // credited once OBSERVED solid
                // Stay in PLACING; next tick checks aheadSupportSolid again
                // and either switches back to WALKING (success) or retries.
            }
            case DONE -> { return true; }
        }
        return false;
    }

    /**
     * The support-bearing feet cell — {@code floor(center)} re-anchored to the AABB.
     *
     * <p>{@code floor(center)} alone killed live death #24 (gap #75-a): vanilla's sneak
     * edge-clamp ({@code Player.maybeBackOffFromEdge}) deliberately lets a sneaking player
     * OVERHANG a ledge until only a sliver of the 0.6-wide AABB still touches support, so
     * the center legally crosses into the unsupported neighbour column (a tower finish on a
     * 1×1 pillar top routinely parks the bot there). Anchoring on that column read
     * "support under feet = air" while the bot stood perfectly safe; the terminal then
     * released the sneak that was pinning it to the edge — the actual fall. If the center
     * cell has support it wins unchanged; otherwise the supported cell under the AABB with
     * the largest footprint overlap is the anchor. Returns the center cell when nothing
     * under the AABB has support (genuinely airborne — the caller's fell-off terminal).
     */
    private static BlockPos anchoredFoot(LivingEntity p, Level lvl) {
        BlockPos foot = blockPosOf(p);
        if (lvl.getBlockState(foot.offset(0, -1, 0)).blocksMotion()) return foot;
        AABB box = p.getBoundingBox();
        BlockPos best = null;
        double bestOverlap = 0;
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ); z++) {
                BlockPos cell = new BlockPos(x, foot.getY(), z);
                if ((x == foot.getX() && z == foot.getZ())
                        || !lvl.getBlockState(cell.offset(0, -1, 0)).blocksMotion()) continue;
                double ov = (Math.min(box.maxX, x + 1) - Math.max(box.minX, x))
                          * (Math.min(box.maxZ, z + 1) - Math.max(box.minZ, z));
                if (ov > bestOverlap) { bestOverlap = ov; best = cell; }
            }
        }
        return best != null ? best : foot;
    }

    /** Signed shortest-arc degrees in (-180, 180]. */
    private static float wrapDeg(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }
}
