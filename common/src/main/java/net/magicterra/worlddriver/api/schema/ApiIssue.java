package net.magicterra.worlddriver.api.schema;

import java.util.Objects;

/**
 * One field-level problem with a request: where it is (a JSON Pointer, {@code ""} for the root),
 * which of the five kinds it is, and a sentence for a person.
 */
public record ApiIssue(String path, Code code, String message) {

    public ApiIssue {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    /** The closed set of issue kinds; anything more specific goes into the message. */
    public enum Code implements WireName {
        REQUIRED("required"),
        UNKNOWN_FIELD("unknown_field"),
        TYPE_MISMATCH("type_mismatch"),
        OUT_OF_RANGE("out_of_range"),
        INVALID_VALUE("invalid_value");

        private final String wire;

        Code(String wire) { this.wire = wire; }

        @Override public String wireName() { return wire; }
    }
}
