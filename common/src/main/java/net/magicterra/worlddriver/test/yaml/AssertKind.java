package net.magicterra.worlddriver.test.yaml;

/**
 * The assert verbs a YAML test may use (docs/yaml-gametest.md §5). Dispatch lives
 * in {@link YamlTestInterpreter}; this enum is the single place that names every
 * verb so an unknown one fails loudly at parse-eval rather than silently passing.
 *
 * <p>{@link #NO_EXCEPTION_IN_LOG} and {@link #BLOCK_CHANGED_WITHIN} are recognised
 * but not yet implemented (log appender / microtiming — later phases); using them
 * raises a clear error so a test that depends on them cannot quietly succeed.
 */
public enum AssertKind {
    BLOCK_PRESENT,
    BLOCK_ABSENT,
    ENTITY_PRESENT,
    TPS,
    NO_EXCEPTION_IN_LOG,
    BLOCK_CHANGED_WITHIN;

    public static AssertKind fromKey(String key) {
        return switch (key) {
            case "block_present"        -> BLOCK_PRESENT;
            case "block_absent"         -> BLOCK_ABSENT;
            case "entity_present"       -> ENTITY_PRESENT;
            case "tps"                  -> TPS;
            case "no_exception_in_log"  -> NO_EXCEPTION_IN_LOG;
            case "block_changed_within" -> BLOCK_CHANGED_WITHIN;
            default -> throw new IllegalArgumentException("unknown assert verb: " + key);
        };
    }
}
