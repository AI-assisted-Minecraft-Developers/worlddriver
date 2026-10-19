package net.magicterra.worlddriver.api.methods.screen;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;

/**
 * This client's screen and the input sent to it: {@code mc.client.screen.*}, {@code mc.client.input.*},
 * and {@code mc.observe.container}. Refused as capability_unavailable in a JVM with no client.
 */
public interface ScreenApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static ScreenStubApi of(ApiRegistry registry) { return ScreenApiBinding.calls(registry); }

    @Spec(value = ScreenContracts.class, spec = "INFO")
    CompletionStage<ScreenContracts.ScreenInfo> screenInfo(NoParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "TREE")
    CompletionStage<ScreenContracts.ScreenTree> screenTree(NoParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "CLOSE")
    CompletionStage<ScreenContracts.Closed> closeScreen(ScreenContracts.CloseParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "CLICK")
    CompletionStage<ClientReply> click(ScreenContracts.ClickParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "MOUSE_MOVE")
    CompletionStage<ScreenContracts.Moved> mouseMove(ScreenContracts.MoveParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "SLOT_CLICK")
    CompletionStage<ClientReply> slotClick(ScreenContracts.SlotClickParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "SET_HOTBAR_SLOT")
    CompletionStage<ClientReply> setHotbarSlot(ScreenContracts.HotbarParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "TYPE_TEXT")
    CompletionStage<ClientReply> typeText(ScreenContracts.TypeParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "REPLACE_TEXT")
    CompletionStage<ClientReply> replaceText(ScreenContracts.ReplaceParams p, CallContext ctx);

    @Spec(value = ScreenContracts.class, spec = "SLIDER")
    CompletionStage<ClientReply> slider(ScreenContracts.SliderParams p, CallContext ctx);

    /** A click waits for the next client tick to release, so it is refused on a game thread; a press or release alone is not. */
    @Spec(value = ScreenContracts.class, spec = "KEY")
    CompletionStage<ClientReply> key(ScreenContracts.KeyParams p, CallContext ctx);

    /** As {@link #key}: a named click waits for its release. */
    @Spec(value = ScreenContracts.class, spec = "KEYBIND")
    CompletionStage<ClientReply> keybind(ScreenContracts.KeybindParams p, CallContext ctx);

    /** With a pos, the block entity there, read on the server; without, the menu open on this client. */
    @Spec(value = ScreenContracts.class, spec = "CONTAINER")
    CompletionStage<ScreenContracts.ContainerView> container(ScreenContracts.ContainerParams p, CallContext ctx);
}
