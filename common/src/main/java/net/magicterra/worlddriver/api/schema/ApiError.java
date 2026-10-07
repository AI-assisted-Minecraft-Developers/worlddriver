package net.magicterra.worlddriver.api.schema;

import java.util.List;
import java.util.Objects;

/**
 * The one error shape every entry point carries: RPC as {@code error.data}, MCP as the tool error,
 * JS as the fields of the thrown {@code DriverApiError}. {@link #execution} says whether anything
 * may have happened, which is the only safe basis for a retry.
 */
public record ApiError(Code code, String message, List<ApiIssue> issues, Execution execution) {

    public ApiError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        issues = List.copyOf(issues);
        Objects.requireNonNull(execution, "execution");
    }

    public static ApiError of(Code code, String message) {
        return new ApiError(code, message, List.of(), code.defaultExecution);
    }

    public enum Code implements WireName {
        INVALID_PARAMS("invalid_params", -32602, Execution.NOT_STARTED),
        METHOD_NOT_FOUND("method_not_found", -32601, Execution.NOT_STARTED),
        CAPABILITY_UNAVAILABLE("capability_unavailable", -32004, Execution.NOT_STARTED),
        ACTION_NOT_FOUND("action_not_found", -32006, Execution.NOT_STARTED),
        CONTEXT_CHANGED("context_changed", -32007, Execution.NOT_STARTED),
        SERVER_BUSY("server_busy", -32005, Execution.NOT_STARTED),
        NOT_EXECUTED("not_executed", -32001, Execution.NOT_EXECUTED),
        OUTCOME_UNKNOWN("outcome_unknown", -32002, Execution.UNKNOWN),
        INTERNAL_ERROR("internal_error", -32603, Execution.UNKNOWN);

        private final String wire;
        /** The JSON-RPC {@code error.code} this kind is reported under. */
        public final int rpcCode;
        final Execution defaultExecution;

        Code(String wire, int rpcCode, Execution defaultExecution) {
            this.wire = wire;
            this.rpcCode = rpcCode;
            this.defaultExecution = defaultExecution;
        }

        @Override public String wireName() { return wire; }
    }

    /** Whether the requested operation may have taken effect. */
    public enum Execution implements WireName {
        /** No business operation started. */
        NOT_STARTED("not_started"),
        /** Scheduled work was withdrawn before it ran. */
        NOT_EXECUTED("not_executed"),
        /** It may have run; never retry on this alone. */
        UNKNOWN("unknown");

        private final String wire;

        Execution(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }
}
