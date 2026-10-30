package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.magicterra.worlddriver.api.schema.annotation.As;
import net.magicterra.worlddriver.api.schema.annotation.AsString;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.OnlyObject;
import net.magicterra.worlddriver.api.schema.annotation.OnlyTrue;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.TsAlias;
import net.magicterra.worlddriver.api.schema.annotation.Variant;
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * Reads wire types off Java declarations: a record's components with their annotations, an enum, a sealed interface
 * marked {@link Tag}. Each record, enum and union is built once per class and each annotated scalar once per spelling,
 * so a named type is one object wherever it is used (TypeScript declares a name once, for one owner). A mistake in the
 * annotations is an {@link IllegalArgumentException} naming the component, thrown when the type is first built.
 */
final class Reflected {

    /** The annotations a type use may carry; any other annotation is looked through for these. */
    private static final Set<Class<? extends Annotation>> CONSTRAINTS = Set.of(
            Range.class,
            Length.class,
            Pattern.class,
            TsAlias.class,
            OneOf.class,
            OnlyTrue.class,
            OnlyObject.class,
            AsString.class,
            As.class);

    private static final Map<Object, ApiType<?>> CACHE = new ConcurrentHashMap<>();

    private static final ThreadLocal<Set<Class<?>>> BUILDING = ThreadLocal.withInitial(HashSet::new);

    private Reflected() {}

    @SuppressWarnings("unchecked")
    static <T> ApiType<T> of(Class<T> type) {
        return (ApiType<T>) resolve(type, Constraints.NONE, type.getSimpleName());
    }

    /**
     * {@code type} under the constraint annotation {@code constraint}, as a component written {@code @Constraint T}
     * reads it.
     */
    @SuppressWarnings("unchecked")
    static <T> ApiType<T> of(Class<T> type, Class<? extends Annotation> constraint) {
        String where = "@" + constraint.getSimpleName() + " " + type.getSimpleName();
        return (ApiType<T>) resolve(type, Constraints.ofDeclaration(constraint, where), where);
    }

    @SuppressWarnings("unchecked")
    static <R> RecordType<R> record(Class<R> type) {
        return (RecordType<R>) cached(type, () -> buildRecord(type, Map.of()));
    }

    static <R> RecordType<R> record(Class<R> type, Map<String, ApiType<?>> componentTypes) {
        for (String name : componentTypes.keySet()) {
            if (!type.isRecord()
                    || Arrays.stream(type.getRecordComponents())
                            .noneMatch(c -> c.getName().equals(name))) {
                throw new IllegalArgumentException(type.getSimpleName() + " has no component " + name);
            }
        }
        return buildRecord(type, componentTypes);
    }

    @SuppressWarnings("unchecked")
    static <T> UnionType<T> union(Class<T> type) {
        return (UnionType<T>) cached(type, () -> buildUnion(type));
    }

    /** {@code variants} under one discriminator, each tagged by its record's {@link Variant}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T> UnionType<T> union(String name, String tagName, List<RecordType<? extends T>> variants) {
        List<UnionType.Variant<T, ?>> list = new ArrayList<>();
        for (RecordType<? extends T> r : variants) {
            Class<?> type = r.javaType();
            Variant v = type == null ? null : type.getAnnotation(Variant.class);
            if (v == null) {
                throw new IllegalArgumentException("union " + name + ": " + r.expected() + " has no @Variant");
            }
            list.add(new UnionType.Variant(v.value(), type, r));
        }
        return new UnionType<>(name, tagName, list);
    }

    // ------------------------------------------------------------------ cache

    private static ApiType<?> cached(Object key, java.util.function.Supplier<ApiType<?>> build) {
        ApiType<?> hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        ApiType<?> made = build.get();
        // Built outside any lock: building one type builds the types it refers to, through this same cache.
        ApiType<?> raced = CACHE.putIfAbsent(key, made);
        return raced != null ? raced : made;
    }

    // ------------------------------------------------------------------ records

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <R> RecordType<R> buildRecord(Class<R> type, Map<String, ApiType<?>> overrides) {
        String where = type.getSimpleName();
        if (!type.isRecord()) {
            throw new IllegalArgumentException(where + " is not a record");
        }
        Set<Class<?>> building = BUILDING.get();
        if (!building.add(type)) {
            throw new IllegalArgumentException(where + " contains itself; a contract record may not");
        }
        try {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] signature = new Class<?>[components.length];
            List<ApiField<R, ?>> fields = new ArrayList<>();
            for (int i = 0; i < components.length; i++) {
                signature[i] = components[i].getType();
                fields.add(field(type, components[i], overrides.get(components[i].getName())));
            }
            Constructor<R> constructor;
            try {
                constructor = type.getDeclaredConstructor(signature);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(where + " has no canonical constructor", e);
            }
            constructor.setAccessible(true);
            Named named = type.getAnnotation(Named.class);
            RecordType<R> record = new RecordType<>(
                    type, named == null ? null : named.value(), fields, args -> construct(constructor, args));
            Doc doc = type.getAnnotation(Doc.class);
            if (doc != null) {
                record.describe(doc.value());
            }
            if (SelfChecked.class.isAssignableFrom(type)) {
                record = record.checked((value, at) -> ((SelfChecked) value).check(at));
                if (doc != null) {
                    record.describe(doc.value());
                }
            }
            return record;
        } finally {
            building.remove(type);
        }
    }

    private static <R> R construct(Constructor<R> constructor, Object[] args) {
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            // A compact constructor's own refusal reaches RecordType as itself, to be reported as invalid_value.
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "cannot construct " + constructor.getDeclaringClass().getSimpleName(), e);
        }
    }

    private static <R> ApiField<R, ?> field(Class<R> owner, RecordComponent c, ApiType<?> override) {
        String where = owner.getSimpleName() + "." + c.getName();
        Wire wire = c.getAnnotation(Wire.class);
        Doc doc = c.getAnnotation(Doc.class);
        Default def = c.getAnnotation(Default.class);
        AnnotatedType at = c.getAnnotatedType();
        boolean optional = c.getType() == Optional.class;
        ApiType<?> type;
        if (optional) {
            if (def != null) {
                throw new IllegalArgumentException(where + ": an Optional has no default; it is absent");
            }
            AnnotatedType inner = ((AnnotatedParameterizedType) at).getAnnotatedActualTypeArguments()[0];
            type = override != null
                    ? override
                    : resolve(inner, Constraints.of(concat(at.getAnnotations(), inner.getAnnotations()), where), where);
        } else {
            type = override != null ? override : resolve(at, Constraints.of(at.getAnnotations(), where), where);
        }
        Method accessor = c.getAccessor();
        accessor.setAccessible(true);
        Function<R, Object> getter = record -> {
            try {
                return accessor.invoke(record);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read " + where, e);
            }
        };
        String name = wire == null ? c.getName() : wire.value();
        String description = doc == null ? null : doc.value();
        if (optional) {
            return ApiField.of(name, type, ApiField.Presence.OPTIONAL, null, getter, description);
        }
        if (def != null) {
            return ApiField.of(
                    name,
                    type,
                    ApiField.Presence.DEFAULTED,
                    parseDefault(type, def.value(), where),
                    getter,
                    description);
        }
        return ApiField.of(name, type, ApiField.Presence.REQUIRED, null, getter, description);
    }

    /** {@code text} as a value of {@code type}: the string itself where the wire value is a string, JSON otherwise. */
    private static Object parseDefault(ApiType<?> type, String text, String where) {
        JsonElement typeName = type.schema().get("type");
        JsonElement json;
        if (typeName != null && typeName.isJsonPrimitive() && "string".equals(typeName.getAsString())) {
            json = new JsonPrimitive(text);
        } else {
            try {
                json = JsonParser.parseString(text);
            } catch (JsonParseException e) {
                throw new IllegalArgumentException(where + ": default " + text + " is not JSON", e);
            }
        }
        try {
            return type.decode(json);
        } catch (ApiException e) {
            throw new IllegalArgumentException(
                    where + ": default " + text + " does not fit " + type.expected() + ": "
                            + e.error().issues(),
                    e);
        }
    }

    // ------------------------------------------------------------------ unions and enums

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> UnionType<T> buildUnion(Class<T> type) {
        String where = type.getSimpleName();
        Tag tag = type.getAnnotation(Tag.class);
        if (tag == null || !type.isSealed()) {
            throw new IllegalArgumentException(where + " is no sealed interface marked @Tag");
        }
        List<RecordType<? extends T>> variants = new ArrayList<>();
        for (Class<?> sub : type.getPermittedSubclasses()) {
            variants.add((RecordType) record(sub));
        }
        Named named = type.getAnnotation(Named.class);
        UnionType<T> union = union(named == null ? null : named.value(), tag.value(), variants);
        Doc doc = type.getAnnotation(Doc.class);
        if (doc != null) {
            union.describe(doc.value());
        }
        return union;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ApiType<?> buildEnum(Class<?> type) {
        if (!WireName.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                    type.getSimpleName() + " does not implement WireName, which spells its constants");
        }
        Named named = type.getAnnotation(Named.class);
        EnumType<?> e = new EnumType(
                named == null ? null : named.value(),
                Arrays.asList(type.getEnumConstants()),
                v -> ((WireName) v).wireName());
        Doc doc = type.getAnnotation(Doc.class);
        if (doc != null) {
            e.describe(doc.value());
        }
        return e;
    }

    // ------------------------------------------------------------------ type uses

    private static ApiType<?> resolve(AnnotatedType at, Constraints c, String where) {
        // @As names the whole type, so it may stand on a generic one no annotation could describe.
        As as = c.take(As.class);
        if (as != null) {
            c.done(where);
            return constant(as, where);
        }
        Type t = at.getType();
        if (t instanceof ParameterizedType p) {
            return container(at, (Class<?>) p.getRawType(), c, where);
        }
        if (t instanceof Class<?> cls) {
            return resolve(cls, c, where);
        }
        throw new IllegalArgumentException(where + ": no wire type for " + t
                + "; a type variable is bound with ApiTypes.record(type, componentTypes)");
    }

    private static ApiType<?> resolve(Class<?> cls, Constraints c, String where) {
        if (cls.isRecord() || cls.isEnum() || cls.isInterface()) {
            c.done(where);
            if (c.doc != null) {
                throw new IllegalArgumentException(
                        where + ": describe " + cls.getSimpleName() + " with @Doc on its declaration");
            }
            if (cls.isRecord()) {
                return record(cls);
            }
            if (cls.isEnum()) {
                return cached(cls, () -> buildEnum(cls));
            }
            return union(cls);
        }
        // int and Integer are one wire type: a constant built from the boxed class is the field's own object.
        Class<?> boxed = cls == int.class
                ? Integer.class
                : cls == long.class
                        ? Long.class
                        : cls == double.class ? Double.class : cls == boolean.class ? Boolean.class : cls;
        return cached(List.of(boxed, c.list, String.valueOf(c.doc)), () -> {
            ApiType<?> scalar = scalar(cls, c, where);
            c.done(where);
            if (c.doc != null) {
                scalar.describe(c.doc);
            }
            return scalar;
        });
    }

    private static ApiType<?> scalar(Class<?> cls, Constraints c, String where) {
        if (cls == int.class || cls == Integer.class) {
            Range r = c.take(Range.class);
            return r == null
                    ? ApiTypes.integer()
                    : ApiTypes.integer((int) whole(r.min(), Integer.MIN_VALUE, Integer.MAX_VALUE, where), (int)
                            whole(r.max(), Integer.MIN_VALUE, Integer.MAX_VALUE, where));
        }
        if (cls == long.class || cls == Long.class) {
            Range r = c.take(Range.class);
            long min = r == null ? Long.MIN_VALUE : whole(r.min(), Long.MIN_VALUE, Long.MAX_VALUE, where);
            long max = r == null ? Long.MAX_VALUE : whole(r.max(), Long.MIN_VALUE, Long.MAX_VALUE, where);
            if (c.take(AsString.class) != null) {
                if (max != Long.MAX_VALUE) {
                    throw new IllegalArgumentException(where + ": a long written as a string has no maximum");
                }
                return ApiTypes.int64String(min);
            }
            return ApiTypes.int64(min, max);
        }
        if (cls == double.class || cls == Double.class) {
            Range r = c.take(Range.class);
            return r == null ? ApiTypes.number() : ApiTypes.number(r.min(), r.max());
        }
        if (cls == boolean.class || cls == Boolean.class) {
            if (c.doc != null) {
                throw new IllegalArgumentException(where + ": a boolean is described on its field");
            }
            return c.take(OnlyTrue.class) != null ? ApiTypes.trueOnly() : ApiTypes.bool();
        }
        if (cls == String.class) {
            return string(c, where);
        }
        if (cls == JsonValue.class) {
            return c.take(OnlyObject.class) != null ? ApiTypes.jsonObject() : ApiTypes.json();
        }
        throw new IllegalArgumentException(
                where + ": no wire type for " + cls.getName() + "; use @As for a hand-built one");
    }

    private static ApiType<?> string(Constraints c, String where) {
        OneOf oneOf = c.take(OneOf.class);
        if (oneOf != null) {
            return ApiTypes.stringEnum(oneOf.name().isEmpty() ? null : oneOf.name(), oneOf.value());
        }
        Pattern p = c.take(Pattern.class);
        Length len = c.take(Length.class);
        StringType s;
        if (p != null) {
            s = ApiTypes.pattern(p.value(), p.expected());
            if (len != null && len.min() > 0) {
                s = s.minLength(len.min());
            }
            if (len != null && len.max() != Integer.MAX_VALUE) {
                s = s.maxLength(len.max());
            }
        } else {
            s = len == null ? ApiTypes.string() : ApiTypes.string(len.min(), len.max());
        }
        TsAlias alias = c.take(TsAlias.class);
        return alias == null ? s : s.tsAlias(alias.name(), alias.type());
    }

    private static ApiType<?> container(AnnotatedType at, Class<?> raw, Constraints c, String where) {
        AnnotatedType[] args = ((AnnotatedParameterizedType) at).getAnnotatedActualTypeArguments();
        ApiType<?> made;
        if (raw == List.class) {
            ApiType<?> item = resolve(args[0], Constraints.of(args[0].getAnnotations(), where), where + "[]");
            Length len = c.take(Length.class);
            made = len == null ? ApiTypes.list(item, Integer.MAX_VALUE) : ApiTypes.list(item, len.min(), len.max());
        } else if (raw == Map.class) {
            if (args[0].getType() != String.class) {
                throw new IllegalArgumentException(where + ": a map's keys are strings");
            }
            ApiType<?> value = resolve(args[1], Constraints.of(args[1].getAnnotations(), where), where + "{}");
            Length len = c.take(Length.class);
            if (len != null && len.min() != 0) {
                throw new IllegalArgumentException(where + ": a map has no minimum size");
            }
            made = ApiTypes.map(value, len == null ? Integer.MAX_VALUE : len.max());
        } else if (raw == Optional.class) {
            throw new IllegalArgumentException(
                    where + ": Optional only as a component's own type, where it means the field may be absent");
        } else {
            throw new IllegalArgumentException(where + ": no wire type for " + at.getType()
                    + "; bind a generic record with ApiTypes.record(type, componentTypes)");
        }
        c.done(where);
        if (c.doc != null) {
            made.describe(c.doc);
        }
        return made;
    }

    private static ApiType<?> constant(As as, String where) {
        try {
            Field f = as.value().getDeclaredField(as.field());
            if (!Modifier.isStatic(f.getModifiers()) || !ApiType.class.isAssignableFrom(f.getType())) {
                throw new IllegalArgumentException(
                        where + ": " + as.value().getSimpleName() + "." + as.field() + " is no static ApiType");
            }
            f.setAccessible(true);
            ApiType<?> t = (ApiType<?>) f.get(null);
            if (t == null) {
                throw new IllegalArgumentException(where + ": " + as.value().getSimpleName() + "." + as.field()
                        + " is not set yet; declare it before the contract that uses it");
            }
            return t;
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException(
                    where + ": " + as.value().getSimpleName() + " has no field " + as.field(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code bound} as a whole number within {@code [lo,hi]}; an infinite or out-of-reach bound is the limit itself.
     */
    private static long whole(double bound, long lo, long hi, String where) {
        if (bound == Double.NEGATIVE_INFINITY || bound <= lo) {
            return lo;
        }
        if (bound == Double.POSITIVE_INFINITY || bound >= hi) {
            return hi;
        }
        if (bound != Math.rint(bound)) {
            throw new IllegalArgumentException(where + ": an integer's bound " + bound + " is not whole");
        }
        return (long) bound;
    }

    private static Annotation[] concat(Annotation[] a, Annotation[] b) {
        Annotation[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * The constraint annotations on one type use, with those reached through a constraint annotation's own declaration
     * ({@code @Near} carrying {@code @Range} and {@code @Doc}) spelled out. Each is taken once by the type that applies
     * it; one left over does not apply to that type.
     */
    private static final class Constraints {
        static final Constraints NONE = new Constraints(List.of(), null);

        final List<Annotation> list;

        final String doc;

        private final Set<Class<? extends Annotation>> taken = new HashSet<>();

        private Constraints(List<Annotation> list, String doc) {
            this.list = list;
            this.doc = doc;
        }

        static Constraints of(Annotation[] direct, String where) {
            List<Annotation> out = new ArrayList<>();
            String[] doc = {null};
            for (Annotation a : direct) {
                expand(a, out, doc, where, 0);
            }
            return checked(out, doc[0], where);
        }

        /** What a use of the annotation {@code kind} would carry. */
        static Constraints ofDeclaration(Class<? extends Annotation> kind, String where) {
            List<Annotation> out = new ArrayList<>();
            String[] doc = {null};
            expandDeclaration(kind, out, doc, where, 0);
            return checked(out, doc[0], where);
        }

        private static Constraints checked(List<Annotation> out, String doc, String where) {
            Set<Class<? extends Annotation>> kinds = new HashSet<>();
            for (Annotation a : out) {
                if (!kinds.add(a.annotationType())) {
                    throw new IllegalArgumentException(
                            where + ": two @" + a.annotationType().getSimpleName());
                }
            }
            return out.isEmpty() && doc == null ? NONE : new Constraints(List.copyOf(out), doc);
        }

        private static void expand(Annotation a, List<Annotation> out, String[] doc, String where, int depth) {
            if (CONSTRAINTS.contains(a.annotationType())) {
                out.add(a);
                return;
            }
            expandDeclaration(a.annotationType(), out, doc, where, depth);
        }

        private static void expandDeclaration(
                Class<? extends Annotation> kind, List<Annotation> out, String[] doc, String where, int depth) {
            if (depth > 4 || kind.getName().startsWith("java.")) {
                return;
            }
            Doc d = kind.getAnnotation(Doc.class);
            if (d != null) {
                if (doc[0] != null) {
                    throw new IllegalArgumentException(where + ": two descriptions, one from @" + kind.getSimpleName());
                }
                doc[0] = d.value();
            }
            for (Annotation meta : kind.getAnnotations()) {
                expand(meta, out, doc, where, depth + 1);
            }
        }

        @SuppressWarnings("unchecked")
        <A extends Annotation> A take(Class<A> kind) {
            for (Annotation a : list) {
                if (a.annotationType() == kind) {
                    taken.add(kind);
                    return (A) a;
                }
            }
            return null;
        }

        /** Refuse any constraint the type did not take: {@code @Range} on a string means nothing. */
        void done(String where) {
            for (Annotation a : list) {
                if (!taken.contains(a.annotationType())) {
                    throw new IllegalArgumentException(
                            where + ": @" + a.annotationType().getSimpleName() + " does not apply here");
                }
            }
        }
    }
}
