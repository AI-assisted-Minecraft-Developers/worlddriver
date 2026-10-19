package net.magicterra.worlddriver.api.game;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A directory of scripts run through a fresh script engine bound to this game's RPC and MCP servers, as a user's
 * scripts are, and the checks they made through the scripts' {@code Test} helper. For a suite of scripts that tests
 * the three transports themselves, from code in the same game; reached through {@link WorldDriver#scripts}.
 */
public interface ScriptChecks {

    /** One check: its name, whether it passed, how long it took, and why it failed. */
    record Check(String name, boolean passed, long ms, Optional<String> error) {}

    /**
     * Runs every script in {@code dir} once and answers each check in the order they ran, the checks of an
     * earlier run forgotten. Off the server thread: a script's RPC call waits on the server thread.
     */
    List<Check> run(Path dir) throws IOException;
}
