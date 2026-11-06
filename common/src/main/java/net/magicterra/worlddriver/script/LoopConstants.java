package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Node;
import dev.latvian.mods.rhino.Parser;
import dev.latvian.mods.rhino.ast.AstNode;
import dev.latvian.mods.rhino.ast.AstRoot;
import dev.latvian.mods.rhino.ast.ForInLoop;
import dev.latvian.mods.rhino.ast.ForLoop;
import dev.latvian.mods.rhino.ast.FunctionNode;
import dev.latvian.mods.rhino.ast.Jump;
import dev.latvian.mods.rhino.ast.LabeledStatement;
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
import java.util.LinkedHashMap;
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

    /** How many sources {@link #blockScoped} keeps its answer for. */
    static final int KEPT = 64;

    /** Each source's answer, the least recently used going first. Guarded by itself. */
    private static final Map<String, String> REWRITTEN = new LinkedHashMap<>(KEPT, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > KEPT;
        }
    };

    private LoopConstants() {}

    /**
     * {@code source}, its constants inside loops declared with {@code let}; as it is when it does not parse. The answer
     * is kept for the last {@value #KEPT} sources, since an agent evaluates the same few again and again and each costs
     * a parse and a walk of its tree before the engine compiles it.
     */
    static String blockScoped(Context cx, String source, String name) {
        if (source == null || !source.contains(CONST)) {
            return source;
        }
        synchronized (REWRITTEN) {
            String kept = REWRITTEN.get(source);
            if (kept != null) {
                return kept;
            }
        }
        String rewritten = rewrite(cx, source, name);
        synchronized (REWRITTEN) {
            REWRITTEN.put(source, rewritten);
        }
        return rewritten;
    }

    private static String rewrite(Context cx, String source, String name) {
        AstRoot root;
        try {
            root = new Parser(cx).parse(source, name, 1);
        } catch (RuntimeException e) {
            return source; // the compile that follows reports it
        }
        StringBuilder out = null;
        for (VariableDeclaration declaration : constants(root)) {
            int position = inLoop(declaration) ? keyword(declaration, source) : -1;
            if (position >= 0) {
                out = out != null ? out : new StringBuilder(source);
                out.replace(position, position + CONST.length(), LET);
            }
        }
        return out != null ? out.toString() : source;
    }

    /**
     * Where the declaration's {@code const} stands in {@code source}, or -1 when it is not there. The engine's parser
     * gets a labelled loop's offsets wrong: entering the loop, it moves the loop back by the label's offset, meant to
     * make it absolute again, after the label had already made it relative, so what it parses from then on reads short
     * by the label's offset in the source. That is the body of a {@code for}, whose head comes first, and the condition
     * and body of a {@code while} or {@code do}. A labelled loop inside another's affected part is short by both, the
     * inner label's own offset being short by the outer's.
     */
    static int keyword(AstNode declaration, String source) {
        Deque<AstNode> path = new ArrayDeque<>();
        for (AstNode up = declaration; up != null; up = up.getParent()) {
            path.push(up);
        }
        int shift = 0;
        AstNode above = null;
        for (AstNode node : path) {
            if (above instanceof Loop loop
                    && loop.getParent() instanceof LabeledStatement label
                    && label.getStatement() == loop
                    && (!(loop instanceof ForLoop || loop instanceof ForInLoop) || node == loop.getBody())) {
                shift += label.getAbsolutePosition() + shift;
            }
            above = node;
        }
        int at = declaration.getAbsolutePosition() + shift;
        return isConst(source, at) ? at : -1;
    }

    private static boolean isConst(String source, int at) {
        int end = at + CONST.length();
        return at >= 0
                && source.startsWith(CONST, at)
                && (at == 0 || !Character.isJavaIdentifierPart(source.charAt(at - 1)))
                && (end == source.length() || !Character.isJavaIdentifierPart(source.charAt(end)));
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
                        && !Node.class.equals(m.getDeclaringClass())
                        // A jump's own getters are links the compiler sets, to the loop or try a break leaves; each
                        // asks for one kind of jump and has the engine print a failed assertion for any other.
                        && !Jump.class.equals(m.getDeclaringClass())) {
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
