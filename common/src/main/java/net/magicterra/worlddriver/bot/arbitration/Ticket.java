package net.magicterra.worlddriver.bot.arbitration;

/**
 * The accepted action a process carries out, as the scheduler sees it: what it may end and how. The scheduler is core
 * behaviour and knows nothing of the API that accepted the action; whoever installs a process wraps the API's handle in
 * one of these, and two tickets for the same action are equal.
 */
public interface Ticket {
    /** The action's id, as the API reports it. */
    String id();

    /** Whether the action is still running. */
    boolean running();

    /** Ends the action as succeeded; true when this call did it. */
    boolean succeed();

    /** Ends the action as succeeded with {@code result}; true when this call did it. */
    boolean succeed(Object result);

    /** Ends the action as failed with {@code message}; true when this call did it. */
    boolean fail(String message);

    /** Ends the action as cancelled for {@code why}; true when this call did it. */
    boolean cancelled(Stop why);

    /** Ends this as replaced by the action {@code replacementId}. */
    boolean supersede(String replacementId);

    /** Why a running action was cancelled. */
    enum Stop {
        REQUESTED,
        WORLD_CLOSED,
        BOT_REMOVED,
        TEST_RESET,
        CONNECTION_CLOSED
    }
}
