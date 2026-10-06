package net.magicterra.worlddriver.bot.scheduler;

/**
 * The accepted action a process carries out, as the scheduler sees it: what it may end and how. The
 * scheduler is core behaviour and knows nothing of the API that accepted the action; whoever installs a
 * process wraps the API's handle in one of these, and two tickets for the same action are equal.
 */
public interface Ticket {
    String id();

    boolean running();

    boolean succeed();

    boolean succeed(Object result);

    boolean fail(String message);

    boolean cancelled(Stop why);

    /** Ends this as replaced by the action {@code replacementId}. */
    boolean supersede(String replacementId);

    /** Why a running action was cancelled. */
    enum Stop { REQUESTED, WORLD_CLOSED, BODY_REMOVED, TEST_RESET, CONNECTION_CLOSED }
}
