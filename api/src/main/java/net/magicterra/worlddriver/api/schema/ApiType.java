package net.magicterra.worlddriver.api.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JavaOps;

/**
 * One wire type: how a JSON value tree becomes a {@code T}, how a {@code T} goes back, and the
 * JSON Schema and TypeScript that describe both. Every rule lives here once; decoding, encoding,
 * {@link #validate} and the generated artifacts all read the same object.
 *
 * <p>Value trees are what {@code JsonCodec} produces and consumes: {@code Map<String,Object>},
 * {@code List<Object>}, String, Boolean, null, and numbers as Long, BigInteger or BigDecimal (Java
 * callers may also hand over Integer or Double).
 */
public abstract class ApiType<T> {
    private String description;

    /**
     * Read {@code value}, or record why not at {@code at} and return null. Never throws for bad
     * input; collecting every independent problem is the point.
     */
    protected abstract T read(Object value, DecodeContext at);

    /** The value tree for a {@code T}, without checking it; {@link #encode} checks. */
    protected abstract Object write(T value);

    /** This type's JSON Schema without the description; a fresh mutable map on every call. */
    protected abstract Map<String, Object> schemaBody();

    /** The TypeScript expression for this type, declaring any named parts in {@code ts}. */
    public abstract String ts(TsContext ts);

    /** A short phrase naming what is expected, for a type_mismatch message ("an integer 1–64"). */
    public abstract String expected();

    /** Attach a description; returns {@code this} so a constant can be declared in one expression. */
    public ApiType<T> describe(String text) {
        this.description = text;
        return this;
    }

    public final String description() { return description; }

    /** The JSON Schema for this type, with its description. */
    public final Map<String, Object> schema() {
        Map<String, Object> s = schemaBody();
        if (description != null && !s.containsKey("description")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("description", description);
            out.putAll(s);
            return out;
        }
        return s;
    }

    /** Decode a whole tree, or throw {@code invalid_params} carrying every issue found. */
    public final T decode(Object tree) {
        DecodeContext root = DecodeContext.root();
        T v = read(tree, root);
        if (!root.issues().isEmpty()) throw ApiException.invalidParams(root.issues());
        return v;
    }

    /** Decode at {@code at}, adding issues there instead of throwing; null when it failed. */
    public final T decode(Object tree, DecodeContext at) {
        int before = at.issues().size();
        T v = read(tree, at);
        return at.issues().size() == before ? v : null;
    }

    /**
     * The value tree for {@code value}, checked against this type. A response that breaks its own
     * contract is a bug in the server, so it is an {@link IllegalStateException}, never a request error.
     */
    public final Object encode(T value) {
        Object tree = write(value);
        DecodeContext root = DecodeContext.root();
        read(tree, root);
        if (!root.issues().isEmpty()) {
            throw new IllegalStateException("value violates its own contract " + expected() + ": " + root.issues());
        }
        return tree;
    }

    /** What {@link #decode} would make of {@link #encode}: a Java-built value gets the same checks a request does. */
    public final T validate(T value) {
        Object tree = write(value);
        return decode(tree);
    }

    /** This type plus a check over the whole decoded value, for rules that span fields. */
    public final ApiType<T> withCheck(BiConsumer<T, DecodeContext> check) {
        return new Checked<>(this, check);
    }

    /**
     * The same rules as a DataFixerUpper {@link Codec}, so a contract value can round-trip through
     * any {@link DynamicOps}. Conversion goes through {@link JavaOps}, the tree this class reads.
     */
    public final Codec<T> codec() {
        ApiType<T> self = this;
        return new Codec<>() {
            @Override
            public <U> DataResult<Pair<T, U>> decode(DynamicOps<U> ops, U input) {
                Object tree = ops.convertTo(JavaOps.INSTANCE, input);
                DecodeContext root = DecodeContext.root();
                T v = self.read(tree, root);
                if (!root.issues().isEmpty()) {
                    List<ApiIssue> issues = new ArrayList<>(root.issues());
                    return DataResult.error(issues::toString);
                }
                return DataResult.success(Pair.of(v, ops.empty()));
            }

            @Override
            public <U> DataResult<U> encode(T input, DynamicOps<U> ops, U prefix) {
                try {
                    Object tree = self.encode(input);
                    return DataResult.success(JavaOps.INSTANCE.convertTo(ops, tree));
                } catch (IllegalStateException e) {
                    return DataResult.error(e::getMessage);
                }
            }
        };
    }

    /** {@link #withCheck}'s wrapper: the inner type's rules, then the extra check when they passed. */
    private static final class Checked<T> extends ApiType<T> {
        private final ApiType<T> inner;
        private final BiConsumer<T, DecodeContext> check;

        Checked(ApiType<T> inner, BiConsumer<T, DecodeContext> check) {
            this.inner = Objects.requireNonNull(inner);
            this.check = Objects.requireNonNull(check);
            describe(inner.description());
        }

        @Override protected T read(Object value, DecodeContext at) {
            int before = at.issues().size();
            T v = inner.read(value, at);
            if (at.issues().size() == before && v != null) check.accept(v, at);
            return at.issues().size() == before ? v : null;
        }

        @Override protected Object write(T value) { return inner.write(value); }
        @Override protected Map<String, Object> schemaBody() { return inner.schemaBody(); }
        @Override public String ts(TsContext ts) { return inner.ts(ts); }
        @Override public String expected() { return inner.expected(); }

        ApiType<T> inner() { return inner; }
    }

    /** The type a {@link #withCheck} wrapper decorates, or this type itself. */
    final ApiType<T> unchecked() {
        ApiType<T> t = this;
        while (t instanceof Checked<T> c) t = c.inner();
        return t;
    }
}
