package net.magicterra.worlddriver.application;

import java.io.IOException;
import net.magicterra.worlddriver.mcp.McpServer;
import net.magicterra.worlddriver.rpc.RpcServer;

/** A transport over {@code app}'s registry and event feed, on an ephemeral loopback port. */
public final class TestTransports {
    private TestTransports() {}

    public static McpServer mcp(DriverApplication app) throws IOException {
        return new McpServer(app.registry(), app, 0);
    }

    public static RpcServer rpc(DriverApplication app) {
        return new RpcServer(app.registry(), app, 0);
    }
}
