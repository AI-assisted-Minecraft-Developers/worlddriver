package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.CachedClassStorage;
import java.lang.invoke.MethodType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The methods a script sees on a Java class: Rhino's, less two kinds that only make an overloaded call ambiguous.
 *
 * <p>Rhino picks among overloads by how easily each argument converts, and calls the choice ambiguous when no overload
 * wins on every argument. fastutil's specialised collections offer three {@code put}s for one call: {@code put(int,
 * V)}, a deprecated boxed {@code put(Integer, V)}, and the compiler's erasure bridge {@code put(Object, Object)}. Rhino
 * reads the {@code V} of a generic method and the {@code Object} of its bridge as different types that a string
 * converts to equally well, and the {@code V} of the class and the {@code V} of the interface likewise, so
 * {@code map.put(3, 'x')} matched all three and threw. Hidden here:
 *
 * <ul>
 *   <li>A bridge, when the class also has the method it bridges to, public on a public type. A bridge that a public
 *       class gets for a public method of a non-public superclass is the only way to reach that method, and stays.
 *   <li>A deprecated overload whose parameters differ from a non-deprecated one's only by boxing a primitive:
 *       fastutil's {@code put(Integer, V)} beside {@code put(int, V)}. The primitive one takes the same calls.
 * </ul>
 */
final class ScriptClassStorage extends CachedClassStorage {

    static final ScriptClassStorage INSTANCE = new ScriptClassStorage();

    private ScriptClassStorage() {
        super(false);
    }

    @Override
    public boolean include(Class<?> type, Member member) {
        if (!super.include(type, member)) {
            return false;
        }
        if (!(member instanceof Method method)) {
            return true;
        }
        if (method.isBridge()) {
            return !hasReachableTarget(type, method);
        }
        if (method.isAnnotationPresent(Deprecated.class)) {
            return !hasPrimitiveTwin(type, method);
        }
        return true;
    }

    /** {@code type} answers {@code bridge}'s name and arity with a method that is not a bridge, on a public type. */
    private static boolean hasReachableTarget(Class<?> type, Method bridge) {
        for (Method m : type.getMethods()) {
            if (m.isBridge() || !sameNameAndArity(m, bridge)) {
                continue;
            }
            if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
                return true;
            }
        }
        return false;
    }

    /** {@code type} has a method that is not deprecated and takes what {@code boxed} takes, primitives unboxed. */
    private static boolean hasPrimitiveTwin(Class<?> type, Method boxed) {
        Class<?>[] wanted = boxed.getParameterTypes();
        candidates:
        for (Method m : type.getMethods()) {
            if (m.isBridge() || !sameNameAndArity(m, boxed) || m.isAnnotationPresent(Deprecated.class)) {
                continue;
            }
            Class<?>[] params = m.getParameterTypes();
            boolean unboxes = false;
            for (int i = 0; i < params.length; i++) {
                if (params[i] == wanted[i]) {
                    continue;
                }
                if (!params[i].isPrimitive() || box(params[i]) != wanted[i]) {
                    continue candidates;
                }
                unboxes = true;
            }
            if (unboxes) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameNameAndArity(Method a, Method b) {
        return a.getName().equals(b.getName()) && a.getParameterCount() == b.getParameterCount();
    }

    private static Class<?> box(Class<?> primitive) {
        return MethodType.methodType(primitive).wrap().returnType();
    }
}
