package net.magicterra.worlddriver.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Methods published together or not at all. A caller builds its batch once and retries THAT object: the registry
 * recognises the same batch by identity and does nothing, while a different batch under the same owner and id is a
 * conflict, never a silent skip.
 *
 * @param owner who answers for the methods ("worlddriver", "stagewright", a mod id)
 * @param id which of the owner's batches this is
 */
public record ApiBatch(String owner, String id, List<ApiMethod<?, ?>> methods) {
    /** Rejects a missing owner or id; copies the method list, so the caller's later edits cannot reach it. */
    public ApiBatch {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        methods = List.copyOf(methods);
    }

    // Identity, not value: two batches with equal content built at different times are two batches.
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
