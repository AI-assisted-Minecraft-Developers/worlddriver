package net.magicterra.worlddriver.api.methods.test;

import java.util.List;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Face;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Hand;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Interaction;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;

/**
 * What the client bot answers the test framework's instrument hooks with ({@code BotApi.resetClientEntry},
 * {@code heldKeys}, {@code useOnBlock}). The driver publishes no method for them: StageWright builds its
 * {@code mc.test.*} contracts on these records and types, so the shapes live here, where it can import them.
 */
public final class TestInputContracts {
    private TestInputContracts() {}

    /**
     * The eight inputs {@code BotInteract.releaseKeys()} clears, each true while down. {@code attack} is the
     * bot's dig latch rather than the attack keybind, kept in its slot so the shape is stable.
     */
    public record HeldKeys(boolean up, boolean down, boolean left, boolean right, boolean jump, boolean sprint,
                           boolean attack, boolean shift) {}

    public static final RecordType<HeldKeys> HELD_KEYS = ApiTypes.record("HeldKeys",
            ApiField.field("up", ApiTypes.bool()).required().forGetter(HeldKeys::up),
            ApiField.field("down", ApiTypes.bool()).required().forGetter(HeldKeys::down),
            ApiField.field("left", ApiTypes.bool()).required().forGetter(HeldKeys::left),
            ApiField.field("right", ApiTypes.bool()).required().forGetter(HeldKeys::right),
            ApiField.field("jump", ApiTypes.bool()).required().forGetter(HeldKeys::jump),
            ApiField.field("sprint", ApiTypes.bool()).required().forGetter(HeldKeys::sprint),
            ApiField.field("attack", ApiTypes.bool()).required().forGetter(HeldKeys::attack),
            ApiField.field("shift", ApiTypes.bool()).required().forGetter(HeldKeys::shift),
            HeldKeys::new);

    /** One right-click on a block, with no aiming, moving or sneaking around it. */
    public record UseOnBlock(BlockPosition pos, Hand hand) {}

    /** What the game made of it, which hand clicked, and the face clicked: the one nearest the player's eye. */
    public record UsedOnBlock(Interaction result, boolean consumed, Hand hand, Face face) {}

    public static final RecordType<UseOnBlock> USE_ON_BLOCK = ApiTypes.record("UseOnBlock",
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(UseOnBlock::pos),
            ApiField.field("hand", InteractContracts.HAND).defaulted(Hand.MAIN).forGetter(UseOnBlock::hand),
            UseOnBlock::new);

    public static final RecordType<UsedOnBlock> USED_ON_BLOCK = ApiTypes.record("UsedOnBlock",
            ApiField.field("result", InteractContracts.INTERACTION).required().forGetter(UsedOnBlock::result),
            ApiField.field("consumed", ApiTypes.bool()).required().forGetter(UsedOnBlock::consumed),
            ApiField.field("hand", InteractContracts.HAND).required().forGetter(UsedOnBlock::hand),
            ApiField.field("face", InteractContracts.FACE).required().forGetter(UsedOnBlock::face),
            UsedOnBlock::new);

    /**
     * What a client-entry reset undid, one token per effect: the cancelled task's kind, {@code combat},
     * {@code keys:<names>} (with {@code →still:<names>} for any the release left down), {@code screen:<class>},
     * and always {@code chat:<lines cleared>}.
     */
    public static final ApiType<List<String>> RESET = ApiTypes.list(ApiTypes.string(), 64);
}
