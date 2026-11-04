package net.magicterra.worlddriver.testcontent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Makes a client log an error when the server tells it to, for a test of what a client's log holds: a system message
 * starting with {@value #PREFIX} is logged on the client as an error, the rest of it the message, with an exception.
 * Fired by each loader's testmod from its client's system-message event.
 */
public final class TestClientLog {

    /** The logger the error is logged under, and the start of a message that asks for one. */
    public static final String PREFIX = "wd.logsTest";

    private static final Logger LOG = LogManager.getLogger(PREFIX);

    private TestClientLog() {}

    /** Called on the client with each system message it receives. */
    public static void received(String text) {
        if (text.startsWith(PREFIX + ":")) {
            LOG.error(text.substring(PREFIX.length() + 1), new IllegalStateException("on purpose"));
        }
    }
}
