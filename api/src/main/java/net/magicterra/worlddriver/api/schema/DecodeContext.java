package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a decode is (a JSON Pointer) and the issues found so far, shared by every level of one decode so a single
 * request reports all of its independent problems.
 */
public final class DecodeContext {
    private final String path;

    private final List<ApiIssue> issues;

    private DecodeContext(String path, List<ApiIssue> issues) {
        this.path = path;
        this.issues = issues;
    }

    public static DecodeContext root() {
        return new DecodeContext("", new ArrayList<>());
    }

    public String path() {
        return path;
    }

    public List<ApiIssue> issues() {
        return issues;
    }

    /** The member {@code name} of the object here. */
    public DecodeContext field(String name) {
        return new DecodeContext(path + "/" + name.replace("~", "~0").replace("/", "~1"), issues);
    }

    /** Element {@code index} of the array here. */
    public DecodeContext index(int index) {
        return new DecodeContext(path + "/" + index, issues);
    }

    /** Record a problem here; returns null so a reader can {@code return at.fail(...)}. */
    public <T> T fail(ApiIssue.Code code, String message) {
        issues.add(new ApiIssue(path, code, message));
        return null;
    }

    /** A type_mismatch naming what was expected and what arrived. */
    public <T> T mismatch(String expected, JsonElement got) {
        return fail(ApiIssue.Code.TYPE_MISMATCH, "expected " + expected + ", got " + describe(got));
    }

    /** How a value reads in a message, without echoing an unbounded string back. */
    public static String describe(JsonElement v) {
        if (v == null || v.isJsonNull()) {
            return "null";
        }
        if (v.isJsonObject()) {
            return "object";
        }
        if (v.isJsonArray()) {
            return "array";
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return "boolean " + p.getAsBoolean();
        }
        if (p.isNumber()) {
            return "number " + clip(p.getAsNumber().toString());
        }
        return describe(p.getAsString());
    }

    /** A string as {@link #describe(JsonElement)} shows one. */
    public static String describe(String s) {
        return "string \"" + clip(s) + "\"";
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
