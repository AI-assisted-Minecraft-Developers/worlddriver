package net.magicterra.worlddriver.api.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

/**
 * One wire type: how a JSON value becomes a {@code T}, how a {@code T} goes back, and the JSON Schema
 * and TypeScript that describe both. Every rule lives here once; decoding, encoding, {@link #validate}
 * and the generated artifacts all read the same object.
 *
 * <p>Values are Gson trees. A JSON {@code null} is {@link JsonNull}; a Java {@code null} handed to
 * {@link #decode} reads as one.
 */
public abstract class ApiType<T> {
    private String description;

    /**
     * Read {@code value}, or record why not at {@code at} and return null. Never throws for bad
     * input; collecting every independent problem is the point. {@code value} is never Java null.
     */
    protected abstract T read(JsonElement value, DecodeContext at);

    /** The JSON for a {@code T}, without checking it; {@link #encode} checks. */
    protected abstract JsonElement write(T value);

    /** This type's JSON Schema without the description; a fresh object on every call. */
    protected abstract JsonObject schemaBody();

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

    /** The JSON Schema for this type, with its description first. */
    public final JsonObject schema() {
        JsonObject s = schemaBody();
        if (description != null && !s.has("description")) {
            JsonObject out = new JsonObject();
            out.addProperty("description", description);
            for (Map.Entry<String, JsonElement> e : s.entrySet()) out.add(e.getKey(), e.getValue());
            return out;
        }
        return s;
    }

    /** Decode a whole tree, or throw {@code invalid_params} carrying every issue found. */
    public final T decode(JsonElement tree) {
        DecodeContext root = DecodeContext.root();
        T v = read(orNull(tree), root);
        if (!root.issues().isEmpty()) throw ApiException.invalidParams(root.issues());
        return v;
    }

    /** Decode at {@code at}, adding issues there instead of throwing; null when it failed. */
    public final T decode(JsonElement tree, DecodeContext at) {
        int before = at.issues().size();
        T v = read(orNull(tree), at);
        return at.issues().size() == before ? v : null;
    }

    private static JsonElement orNull(JsonElement tree) { return tree == null ? JsonNull.INSTANCE : tree; }

    /**
     * The JSON for {@code value}, checked against this type. A response that breaks its own contract
     * is a bug in the server, so it is an {@link IllegalStateException}, never a request error.
     */
    public final JsonElement encode(T value) {
        JsonElement tree = write(value);
        DecodeContext root = DecodeContext.root();
        read(tree, root);
        if (!root.issues().isEmpty()) {
            throw new IllegalStateException("value violates its own contract " + expected() + ": " + root.issues());
        }
        return tree;
    }

    /** What {@link #decode} would make of {@link #encode}: a Java-built value gets the same checks a request does. */
    public final T validate(T value) {
        return decode(write(value));
    }

    /** This type plus a check over the whole decoded value, for rules that span fields. */
    public final ApiType<T> withCheck(BiConsumer<T, DecodeContext> check) {
        return new Checked<>(this, check);
    }

    /** The same rules as a DataFixerUpper {@link Codec}, so a contract value can round-trip through any {@link DynamicOps}. */
    public final Codec<T> codec() {
        ApiType<T> self = this;
        return new Codec<>() {
            @Override
            public <U> DataResult<Pair<T, U>> decode(DynamicOps<U> ops, U input) {
                JsonElement tree = ops.convertTo(JsonOps.INSTANCE, input);
                DecodeContext root = DecodeContext.root();
                T v = self.read(orNull(tree), root);
                if (!root.issues().isEmpty()) {
                    List<ApiIssue> issues = new ArrayList<>(root.issues());
                    return DataResult.error(issues::toString);
                }
                return DataResult.success(Pair.of(v, ops.empty()));
            }

            @Override
            public <U> DataResult<U> encode(T input, DynamicOps<U> ops, U prefix) {
                try {
                    return DataResult.success(JsonOps.INSTANCE.convertTo(ops, self.encode(input)));
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

        @Override protected T read(JsonElement value, DecodeContext at) {
            int before = at.issues().size();
            T v = inner.read(value, at);
            if (at.issues().size() == before && v != null) check.accept(v, at);
            return at.issues().size() == before ? v : null;
        }

        @Override protected JsonElement write(T value) { return inner.write(value); }
        @Override protected JsonObject schemaBody() { return inner.schemaBody(); }
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
