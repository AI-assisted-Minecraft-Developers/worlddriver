package net.magicterra.worlddriver.api.contract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where a decode is (a JSON Pointer) and the issues found so far, shared by every level of one
 * decode so a single request reports all of its independent problems.
 */
public final class DecodeContext {
    private final String path;
    private final List<ApiIssue> issues;

    private DecodeContext(String path, List<ApiIssue> issues) {
        this.path = path;
        this.issues = issues;
    }

    public static DecodeContext root() { return new DecodeContext("", new ArrayList<>()); }

    public String path() { return path; }

    public List<ApiIssue> issues() { return issues; }

    /** The member {@code name} of the object here. */
    public DecodeContext field(String name) {
        return new DecodeContext(path + "/" + name.replace("~", "~0").replace("/", "~1"), issues);
    }

    /** Element {@code index} of the array here. */
    public DecodeContext index(int index) { return new DecodeContext(path + "/" + index, issues); }

    /** Record a problem here; returns null so a reader can {@code return at.fail(...)}. */
    public <T> T fail(ApiIssue.Code code, String message) {
        issues.add(new ApiIssue(path, code, message));
        return null;
    }

    /** A type_mismatch naming what was expected and what arrived. */
    public <T> T mismatch(String expected, Object got) {
        return fail(ApiIssue.Code.TYPE_MISMATCH, "expected " + expected + ", got " + describe(got));
    }

    /** How a value reads in a message, without echoing an unbounded string back. */
    public static String describe(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "string \"" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "\"";
        if (v instanceof Boolean b) return "boolean " + b;
        if (v instanceof BigDecimal || v instanceof BigInteger || v instanceof Number) return "number " + v;
        if (v instanceof Map<?, ?>) return "object";
        if (v instanceof List<?>) return "array";
        if (v instanceof net.magicterra.worlddriver.protocol.JsonCodec.RejectedNumber r) {
            return "number " + r.token() + " (" + r.reason() + ")";
        }
        return v.getClass().getSimpleName();
    }
}
