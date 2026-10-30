package net.magicterra.worlddriver.bot.control.act;

import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.game.body.Attack;
import net.magicterra.worlddriver.game.body.Bag;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.game.body.Locomotion;
import net.magicterra.worlddriver.game.body.Menus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * Where a walking tick's movement commands reach the body: once, after the tick is decided. The head has already been
 * turned by then, so a push is converted against the final heading (see {@link InputFrame}), then jump and sneak
 * follow.
 */
public final class Actuate {
    private Actuate() {}

    /** Hand {@code frame} to {@code body}, in the order the tick gave the two move commands. */
    public static void apply(InputFrame frame, Body body) {
        LivingEntity e = body.entity();
        if (frame.hasPush() && frame.hasForward()) {
            if (frame.pushLast()) {
                forward(frame, body);
                push(frame, body, e);
            } else {
                push(frame, body, e);
                forward(frame, body);
            }
        } else if (frame.hasPush()) push(frame, body, e);
        else if (frame.hasForward()) forward(frame, body);
        if (frame.jump() != null) body.commandJump(frame.jump());
        if (frame.sneak() != null) body.commandSneak(frame.sneak());
        frame.clear();
    }

    private static void push(InputFrame frame, Body body, LivingEntity e) {
        float[] lf = frame.pushFor(e == null ? 0f : e.getYRot());
        body.commandMove(lf[0], lf[1]);
    }

    private static void forward(InputFrame frame, Body body) {
        body.commandForward(frame.rawForward());
    }

    /**
     * The body the tick phases drive: movement commands go into the frame, everything else — the entity, the hands, the
     * look — straight through to the real body.
     */
    public static final class FrameBody implements Body {
        public final InputFrame frame = new InputFrame();
        private Body real;

        /** Drive {@code body} this tick, from an empty frame. */
        public FrameBody begin(Body body) {
            real = body;
            frame.clear();
            return this;
        }

        Body real() {
            return real;
        }

        @Override
        public LivingEntity entity() {
            return real.entity();
        }

        @Override
        public Optional<Equipment> equipment() {
            return real.equipment();
        }

        @Override
        public Optional<Attack> attack() {
            return real.attack();
        }

        @Override
        public Optional<BlockInteraction> blockInteraction() {
            return real.blockInteraction();
        }

        @Override
        public Optional<Bag> bag() {
            return real.bag();
        }

        @Override
        public Optional<Menus> menus() {
            return real.menus();
        }

        @Override
        public Locomotion locomotion() {
            return real.locomotion();
        }

        @Override
        public void commandMove(float left, float forward) {
            LivingEntity e = real.entity();
            frame.push(e == null ? 0f : e.getYRot(), left, forward);
        }

        @Override
        public void commandForward(float forward) {
            frame.forward(forward);
        }

        @Override
        public void commandJump(boolean v) {
            frame.jump(v);
        }

        @Override
        public void commandSneak(boolean v) {
            frame.sneak(v);
        }

        @Override
        public void commandSprint(boolean v) {
            real.commandSprint(v);
        }

        @Override
        public void requestLookSnap() {
            real.requestLookSnap();
        }

        @Override
        public List<BlockPos> takeOwnBreaks() {
            return real.takeOwnBreaks();
        }

        @Override
        public void aimAtBlock(BlockPos cell) {
            real.aimAtBlock(cell);
        }

        @Override
        public BlockPos lookingAtBlock() {
            return real.lookingAtBlock();
        }

        // The navigator's own trace reads what it has commanded so far this tick.
        @Override
        public boolean dbgForwardImpulse() {
            return frame.hasPush() || frame.hasForward() ? frame.moving() : real.dbgForwardImpulse();
        }

        @Override
        public boolean dbgJumping() {
            return frame.jump() != null ? frame.jump() : real.dbgJumping();
        }

        @Override
        public boolean dbgSneak() {
            return frame.sneak() != null ? frame.sneak() : real.dbgSneak();
        }

        @Override
        public long dbgLastJumpTick() {
            return real.dbgLastJumpTick();
        }
    }
}
