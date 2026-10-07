package net.magicterra.worlddriver.api.methods;

import java.util.concurrent.CompletionStage;

import net.magicterra.worlddriver.api.Api;
import net.magicterra.worlddriver.api.CallContext;
import net.magicterra.worlddriver.api.DriverApi;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.CommonTypes.NoParams;

/**
 * What this client sees and says: {@code mc.client.*} beyond its screen and input ({@link ScreenCalls}).
 * Refused as capability_unavailable in a JVM with no client.
 */
public interface ClientCalls {

    /** A caller that goes through {@code api}'s registry, checked as a request is. */
    static ClientCalls of(DriverApi api) { return ClientCallsBinding.calls(api); }

    @Api(value = ClientContracts.class, spec = "PLAYER")
    CompletionStage<ClientContracts.ClientPlayer> player(NoParams p, CallContext ctx);

    @Api(value = ClientContracts.class, spec = "SCENE")
    CompletionStage<ClientContracts.ClientScene> scene(NoParams p, CallContext ctx);

    @Api(value = ClientContracts.class, spec = "BLOCKS")
    CompletionStage<ClientContracts.BlockScan> blocks(ClientContracts.BlocksParams p, CallContext ctx);

    /** With awaitReplyMs, waits for the reply, so it is refused on a game thread. */
    @Api(value = ClientContracts.class, spec = "CHAT_SEND")
    CompletionStage<ClientReply> chatSend(ClientContracts.ChatSendParams p, CallContext ctx);

    @Api(value = ClientContracts.class, spec = "CHAT_HISTORY")
    CompletionStage<ClientContracts.ChatHistory> chatHistory(ClientContracts.ChatHistoryParams p, CallContext ctx);

    @Api(value = ClientContracts.class, spec = "OVERLAYS")
    CompletionStage<ClientContracts.OverlaysCleared> overlays(ClientContracts.OverlaysParams p, CallContext ctx);

    /** Waits for a rendered frame, so it is refused on a game thread. */
    @Api(value = ClientContracts.class, spec = "SCREENSHOT")
    CompletionStage<ClientContracts.Screenshot> screenshot(ClientContracts.ScreenshotParams p, CallContext ctx);
}
