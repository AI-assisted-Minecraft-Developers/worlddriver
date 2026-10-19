package net.magicterra.worlddriver.api.methods.test;

import java.util.List;

import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Face;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Hand;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Interaction;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Named;

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
    @Named("HeldKeys")
    public record HeldKeys(boolean up, boolean down, boolean left, boolean right, boolean jump, boolean sprint,
                           boolean attack, boolean shift) {}

    public static final RecordType<HeldKeys> HELD_KEYS = ApiTypes.record(HeldKeys.class);

    /** One right-click on a block, with no aiming, moving or sneaking around it. */
    @Named("UseOnBlock")
    public record UseOnBlock(BlockPosition pos, @Default("main") Hand hand) {}

    /** What the game made of it, which hand clicked, and the face clicked: the one nearest the player's eye. */
    @Named("UsedOnBlock")
    public record UsedOnBlock(Interaction result, boolean consumed, Hand hand, Face face) {}

    public static final RecordType<UseOnBlock> USE_ON_BLOCK = ApiTypes.record(UseOnBlock.class);

    public static final RecordType<UsedOnBlock> USED_ON_BLOCK = ApiTypes.record(UsedOnBlock.class);

    /**
     * What a client-entry reset undid, one token per effect: the cancelled task's kind, {@code combat},
     * {@code keys:<names>} (with {@code →still:<names>} for any the release left down), {@code screen:<class>},
     * and always {@code chat:<lines cleared>}.
     */
    public static final ApiType<List<String>> RESET = ApiTypes.list(ApiTypes.string(), 64);
}
