package net.magicterra.worlddriver.api.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.magicterra.worlddriver.protocol.ServerBusyException;

/** One translation from whatever a call threw to the {@link ApiError} every entry point reports. */
public final class ApiErrors {
    private ApiErrors() {}

    public static ApiError from(Throwable t) {
        Throwable c = unwrap(t);
        ApiException api = ApiException.find(c);
        if (api != null) return api.error();
        ServerThreadHop.HopTimeoutException hop = ServerThreadHop.find(c);
        if (hop instanceof ServerThreadHop.NotExecutedException) {
            return ApiError.of(ApiError.Code.NOT_EXECUTED, hop.getMessage());
        }
        if (hop != null) return ApiError.of(ApiError.Code.OUTCOME_UNKNOWN, hop.getMessage());
        ServerBusyException busy = ServerBusyException.find(c);
        if (busy != null) return ApiError.of(ApiError.Code.SERVER_BUSY, busy.getMessage());
        String msg = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
        // A service rejecting a value the type could not judge (an unknown block id) before it acted.
        if (c instanceof IllegalArgumentException) {
            return new ApiError(ApiError.Code.INVALID_PARAMS, msg, List.of(new ApiIssue("", ApiIssue.Code.INVALID_VALUE, msg)),
                    ApiError.Execution.NOT_STARTED);
        }
        // "No server attached", "no client registered": the entry cannot serve this call here and now.
        if (c instanceof IllegalStateException) return ApiError.of(ApiError.Code.CAPABILITY_UNAVAILABLE, msg);
        return ApiError.of(ApiError.Code.INTERNAL_ERROR, c.getClass().getSimpleName() + ": " + msg);
    }

    /** The cause a future or reflection wrapper hides. */
    public static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    /** {@code {code, message, issues, execution}}, the wire form every entry point sends. */
    public static Map<String, Object> toTree(ApiError e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.code().wireName());
        m.put("message", e.message());
        List<Object> issues = new java.util.ArrayList<>();
        for (ApiIssue i : e.issues()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("path", i.path());
            im.put("code", i.code().wireName());
            im.put("message", i.message());
            issues.add(im);
        }
        m.put("issues", issues);
        m.put("execution", e.execution().wireName());
        return m;
    }

    /** The inverse of {@link #toTree}, for a bridge that received one; null when {@code tree} is not one. */
    public static ApiError fromTree(Object tree) {
        if (!(tree instanceof Map<?, ?> m)) return null;
        if (!(m.get("code") instanceof String code) || !(m.get("message") instanceof String message)) return null;
        ApiError.Code c = null;
        for (ApiError.Code k : ApiError.Code.values()) if (k.wireName().equals(code)) c = k;
        if (c == null) return null;
        List<ApiIssue> issues = new java.util.ArrayList<>();
        if (m.get("issues") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> im && im.get("path") instanceof String p && im.get("code") instanceof String ic
                        && im.get("message") instanceof String msg) {
                    for (ApiIssue.Code k : ApiIssue.Code.values()) {
                        if (k.wireName().equals(ic)) issues.add(new ApiIssue(p, k, msg));
                    }
                }
            }
        }
        ApiError.Execution ex = ApiError.Execution.UNKNOWN;
        if (m.get("execution") instanceof String es) {
            for (ApiError.Execution k : ApiError.Execution.values()) if (k.wireName().equals(es)) ex = k;
        }
        return new ApiError(c, message, issues, ex);
    }
}
