package net.magicterra.worlddriver.api.schema;

import java.util.List;
import java.util.Objects;

/** An {@link ApiError} thrown through Java code; every entry point unwraps it rather than its message. */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ApiError error;

    /** An exception that carries {@code error} to the entry point unchanged. */
    public ApiException(ApiError error) {
        super(error.message());
        this.error = Objects.requireNonNull(error, "error");
    }

    /** As {@link #ApiException(ApiError)}, with {@code cause} kept for logs and the cause chain. */
    public ApiException(ApiError error, Throwable cause) {
        super(error.message(), cause);
        this.error = Objects.requireNonNull(error, "error");
    }

    /** The error this exception carries. */
    public ApiError error() {
        return error;
    }

    /** An exception for {@code code} with {@code message} and no issues. */
    public static ApiException of(ApiError.Code code, String message) {
        return new ApiException(ApiError.of(code, message));
    }

    /** An {@code invalid_params} error that lists {@code issues}; its message names the first one. */
    public static ApiException invalidParams(List<ApiIssue> issues) {
        String first = issues.isEmpty()
                ? ""
                : ": " + describe(issues.get(0)) + (issues.size() > 1 ? " (and " + (issues.size() - 1) + " more)" : "");
        return new ApiException(new ApiError(
                ApiError.Code.INVALID_PARAMS, "invalid params" + first, issues, ApiError.Execution.NOT_STARTED));
    }

    /** One issue the way a single-field rejection reads in a message. */
    public static ApiException invalidParam(String path, ApiIssue.Code code, String message) {
        return invalidParams(List.of(new ApiIssue(path, code, message)));
    }

    private static String describe(ApiIssue issue) {
        return (issue.path().isEmpty() ? "" : issue.path() + " ") + issue.message();
    }

    /** The first {@link ApiException} on the cause chain, or null. */
    public static ApiException find(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ApiException a) {
                return a;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }
}
