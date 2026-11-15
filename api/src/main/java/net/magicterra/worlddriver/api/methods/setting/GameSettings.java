package net.magicterra.worlddriver.api.methods.setting;

import com.google.gson.JsonObject;
import java.util.List;
import net.magicterra.worlddriver.api.methods.setting.SettingsContracts.BlockId;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * The game access layer's keys of {@code mc.bot.setting}, one component each. A component is named as the field that
 * holds the key, and {@code @Wire} gives the key where the two differ. {@link #DEFAULTS} holds every default, and each
 * field starts from it; a key with no field of its own is optional and has none.
 */
public record GameSettings(
        @Doc("Log per-tick Navigator movement/break decisions to the client log (movement-bug instrumentation)")
        @Default("false")
        boolean navigatorDebug,

        @Doc("A server bot digs at the real speed for its tool instead of in one tick.") @Default("false")
        boolean faithfulBreak,

        @Doc("A server bot's place click uses the block item in its hand, seeds or a torch as much as a building "
                + "block, as a client's does; off, it swaps a building block up and refuses without one.")
        @Default("true")
        boolean serverPlaceClicksHeldBlock,

        @Doc("Pan camera over ticks (pathfinding + lookAt) for stream/demo instead of snapping.") @Default("false")
        boolean smoothLook,

        @Doc("Turn rate when smoothLook on (20°/tick ≈ 400°/s)") @Default("20.0") @Range(min = 1, max = 180)
        double smoothLookDegPerTick,

        @Doc("Extra hazards pathfinder treats as impassable.") @Wire("blocksToAvoid") @Default("[]") @Length(max = 256)
        List<@BlockId String> extraHazardBlocks,

        @Doc("Block ids the bot may PLACE as build/support footing (pillar/bridge/parkour). EMPTY (default) = any "
                + "non-falling, non-GUI block with a sturdy top face (so mud/soul sand count, bottom slabs/fences "
                + "don't); non-empty PINS placement to exactly these ids (use to stop the bot grabbing bamboo/thin "
                + "blocks it can't stand on). Whole-list replace; [] clears.")
        @Default("[]")
        @Length(max = 256)
        List<@BlockId String> buildBlockWhitelist,

        @Default("true") boolean cameraSlew,
        @Default("true") boolean cameraFrameLerp,
        @Default("true") boolean aimOverheadKeepsYaw,

        @Doc("A client bot breaks only the block under its crosshair, on the face the crosshair is on, and aims at a "
                + "visible point of a block whose centre is hidden.")
        @Default("true")
        boolean digOnlyUnderCrosshair,

        @Doc("A client bot places a block only through the face under its crosshair: it aims at a visible point of "
                + "the face it clicks, and the click carries the game's own crosshair pick.")
        @Default("true")
        boolean placeOnlyUnderCrosshair,

        @Doc("A client bot's placement waits out vanilla's four-tick right-click delay, as a held use button does, "
                + "and starts it again.")
        @Default("true")
        boolean placeWaitsForUseDelay,

        @Default("30.0") double cameraSlewDegPerTick,
        @Default("20.0") double cameraPitchSlewDegPerTick,
        @Default("true") boolean mouseYield,
        @Default("true") boolean mouseYieldHud,
        @Default("true") boolean keepTickingUnfocused) {

    public static final GameSettings DEFAULTS =
            ApiTypes.record(GameSettings.class).decode(new JsonObject());
}
