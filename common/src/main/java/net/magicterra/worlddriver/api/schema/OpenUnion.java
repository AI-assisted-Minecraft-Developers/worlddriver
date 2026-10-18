package net.magicterra.worlddriver.api.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A tagged union that takes more variants after it is built: the result of an action, which another mod's
 * registered task adds to while the game runs. Each addition builds a whole new {@link UnionType}, so a reader
 * sees it entirely or not at all, and a repeated tag or an indistinguishable class is refused as it is there.
 */
public final class OpenUnion<T> extends ApiType<T> {
    private volatile UnionType<T> current;

    OpenUnion(UnionType<T> base) { this.current = base; }

    /** Add {@code more}'s variants. Throws, adding nothing, when a tag or class is already taken. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public synchronized void add(UnionType<? extends T> more) {
        UnionType<T> cur = current;
        if (!more.tagName().equals(cur.tagName())) {
            throw new IllegalArgumentException(more.name() + " is tagged by " + more.tagName() + ", not " + cur.tagName());
        }
        List<UnionType.Variant<T, ?>> all = new ArrayList<>(cur.variants());
        for (UnionType.Variant<?, ?> v : more.variants()) all.add(new UnionType.Variant(v.tag(), v.javaType(), v.type()));
        current = new UnionType<>(cur.name(), cur.tagName(), all);
    }

    /** The variants now, in the order they were added. */
    public List<UnionType.Variant<T, ?>> variants() { return current.variants(); }

    @Override protected T read(Object value, DecodeContext at) { return current.read(value, at); }
    @Override protected Object write(T value) { return current.write(value); }
    @Override protected Map<String, Object> schemaBody() { return current.schemaBody(); }
    @Override public String ts(TsContext ts) { return current.ts(ts); }
    @Override public String expected() { return current.expected(); }
}
