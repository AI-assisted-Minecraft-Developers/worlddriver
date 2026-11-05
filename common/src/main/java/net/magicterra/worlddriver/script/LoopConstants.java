package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Node;
import dev.latvian.mods.rhino.Parser;
import dev.latvian.mods.rhino.ast.AstNode;
import dev.latvian.mods.rhino.ast.AstRoot;
import dev.latvian.mods.rhino.ast.FunctionNode;
import dev.latvian.mods.rhino.ast.Loop;
import dev.latvian.mods.rhino.ast.VariableDeclaration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A script's {@code const} inside a loop, made a {@code let} before the engine compiles it.
 *
 * <p>The engine's {@code const} is the old Rhino one: scoped to the function, not the block, and assigned once. A
 * {@code const} the body of a loop declares keeps its first value on every later pass, and the assignments after are
 * dropped without an error, so {@code for (const id in ids) { const v = version(id); ... }} reads the first answer each
 * time while each call was made with the right id: the head's own {@code const} is fresh on each pass, the body's is
 * not. A third party's checks read a mod's version from the wrong answer this way. The head's is rewritten too, which
 * changes nothing for it. Its {@code let} is block scoped and fresh on each pass, and a program that never assigns its
 * constants, which is every valid one, runs the same with either; so the keyword is replaced in place, with {@code "let
 * "} of the same length, and every line and column a failure names stays where the author wrote it.
 *
 * <p>Only inside a loop of the same function: a {@code const} that runs once per call already behaves.
 */
final class LoopConstants {

    private static final String CONST = "const";

    private static final String LET = "let  ";

    /** Getters that lead up or across the tree rather than down into it. */
    private static final Set<String> NOT_DOWN = Set.of(
            "getParent", "getEnclosingFunction", "getEnclosingScope", "getParentScope", "getTop", "getChildScopes");

    /** Each AST class's getters that lead to its parts. */
    private static final Map<Class<?>, List<Method>> PARTS = new ConcurrentHashMap<>();

    private LoopConstants() {}

    /** {@code source}, its constants inside loops declared with {@code let}; as it is when it does not parse. */
    static String blockScoped(Context cx, String source, String name) {
        if (source == null || !source.contains(CONST)) {
            return source;
        }
        AstRoot root;
        try {
            root = new Parser(cx).parse(source, name, 1);
        } catch (RuntimeException e) {
            return source; // the compile that follows reports it
        }
        StringBuilder out = null;
        for (VariableDeclaration declaration : constants(root)) {
            int position = declaration.getAbsolutePosition();
            if (inLoop(declaration) && source.startsWith(CONST, position)) {
                out = out != null ? out : new StringBuilder(source);
                out.replace(position, position + CONST.length(), LET);
            }
        }
        return out != null ? out.toString() : source;
    }

    /**
     * Every {@code const} declaration in the tree. This engine's AST has no visitor, and its script nodes list their
     * functions only once compiled, so the walk goes through each node's public getters for its parts, as the visitor
     * it removed did, and through the children a block lists its statements as.
     */
    private static List<VariableDeclaration> constants(AstRoot root) {
        List<VariableDeclaration> found = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> next = new ArrayDeque<>();
        next.push(root);
        while (!next.isEmpty()) {
            Object item = next.pop();
            if (item instanceof Iterable<?> many && !(item instanceof Node)) {
                many.forEach(each -> push(next, each));
                continue;
            }
            if (!(item instanceof AstNode node) || !seen.add(node)) {
                continue;
            }
            if (node instanceof VariableDeclaration declaration && declaration.isConst()) {
                found.add(declaration);
            }
            for (Node child : node) {
                push(next, child);
            }
            for (Method part : parts(node.getClass())) {
                push(next, part(part, node));
            }
        }
        return found;
    }

    private static void push(Deque<Object> next, Object item) {
        if (item != null) {
            next.push(item);
        }
    }

    private static Object part(Method getter, AstNode node) {
        try {
            return getter.invoke(node);
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null; // a part this walk cannot read; the declarations under it stay as written
        }
    }

    private static List<Method> parts(Class<?> type) {
        return PARTS.computeIfAbsent(type, t -> {
            List<Method> out = new ArrayList<>();
            for (Method m : t.getMethods()) {
                if (m.getParameterCount() == 0
                        && !Modifier.isStatic(m.getModifiers())
                        && m.getName().startsWith("get")
                        && !NOT_DOWN.contains(m.getName())
                        && (AstNode.class.isAssignableFrom(m.getReturnType())
                                || Iterable.class.isAssignableFrom(m.getReturnType()))
                        && !Node.class.equals(m.getDeclaringClass())) {
                    out.add(m);
                }
            }
            return List.copyOf(out);
        });
    }

    /** Whether a loop of the declaration's own function repeats it: its parents, up to the function. */
    private static boolean inLoop(AstNode node) {
        for (AstNode up = node.getParent(); up != null && !(up instanceof FunctionNode); up = up.getParent()) {
            if (up instanceof Loop) {
                return true;
            }
        }
        return false;
    }
}
