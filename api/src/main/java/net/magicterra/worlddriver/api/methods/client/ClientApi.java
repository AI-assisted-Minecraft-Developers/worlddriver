package net.magicterra.worlddriver.api.methods.client;

import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.screen.ScreenApi;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Spec;

/**
 * What this client sees and says: {@code mc.client.*} beyond its screen and input ({@link ScreenApi}). Refused as
 * capability_unavailable in a JVM with no client.
 */
public interface ClientApi {

    /** A caller that goes through {@code registry}, checked as a request is. */
    static ClientStubApi of(ApiRegistry registry) {
        return ClientApiBinding.calls(registry);
    }

    /** {@code mc.client.player}: this client's own player as the client predicts it. */
    @Spec(value = ClientContracts.class, spec = "PLAYER")
    CompletionStage<ClientContracts.ClientPlayer> player(NoParams p, CallContext ctx);

    /** {@code mc.client.scene}: the facts the client bot derives every tick from its hazard grid. */
    @Spec(value = ClientContracts.class, spec = "SCENE")
    CompletionStage<ClientContracts.ClientScene> scene(NoParams p, CallContext ctx);

    /** {@code mc.client.blocks}: the blocks the client has loaded around a center, to diff against the server. */
    @Spec(value = ClientContracts.class, spec = "BLOCKS")
    CompletionStage<ClientContracts.BlockScan> blocks(ClientContracts.BlocksParams p, CallContext ctx);

    /** With awaitReplyMs, waits for the reply, so it is refused on a game thread. */
    @Spec(value = ClientContracts.class, spec = "CHAT_SEND")
    CompletionStage<ClientReply> chatSend(ClientContracts.ChatSendParams p, CallContext ctx);

    /** {@code mc.client.chat.history}: the chat and system lines this client received, newest first. */
    @Spec(value = ClientContracts.class, spec = "CHAT_HISTORY")
    CompletionStage<ClientContracts.ChatHistory> chatHistory(ClientContracts.ChatHistoryParams p, CallContext ctx);

    /** {@code mc.client.overlays}: dismisses the tutorial hints and toasts. */
    @Spec(value = ClientContracts.class, spec = "OVERLAYS")
    CompletionStage<ClientContracts.OverlaysCleared> overlays(ClientContracts.OverlaysParams p, CallContext ctx);

    /** Waits for a rendered frame, so it is refused on a game thread. */
    @Spec(value = ClientContracts.class, spec = "SCREENSHOT")
    CompletionStage<ClientContracts.Screenshot> screenshot(ClientContracts.ScreenshotParams p, CallContext ctx);
}
