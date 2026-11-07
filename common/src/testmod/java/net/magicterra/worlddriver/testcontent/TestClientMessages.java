package net.magicterra.worlddriver.testcontent;

/**
 * Where the loaders' hooks hand each system message the client receives: how a test on the server asks the client to do
 * something only it can. Client thread.
 */
public final class TestClientMessages {

    private TestClientMessages() {}

    /** Hands a system chat line to the test log and the test panel. */
    public static void received(String text) {
        TestClientLog.received(text);
        TestPanel.received(text);
    }
}
