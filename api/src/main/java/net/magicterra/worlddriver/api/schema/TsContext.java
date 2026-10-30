package net.magicterra.worlddriver.api.schema;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Collects the named TypeScript declarations a set of types needs, each once, in first-use order. Two different types
 * asking for one name is a definition error, not a silent merge.
 */
public final class TsContext {
    private final Map<String, String> declarations = new LinkedHashMap<>();

    private final Map<String, Object> owners = new LinkedHashMap<>();

    private final Map<Object, String> inProgress = new IdentityHashMap<>();

    /**
     * Declare {@code name} for {@code owner} with the body {@code declaration} produces (the whole {@code interface …}
     * or {@code type … = …;} text) and return the name to reference.
     */
    public String declare(String name, Object owner, Supplier<String> declaration) {
        Object existing = owners.get(name);
        if (existing != null) {
            if (existing != owner) {
                throw new IllegalStateException("two different types declare the TypeScript name " + name);
            }
            return name;
        }
        if (inProgress.containsKey(owner)) {
            return name; // a self-reference while the body is written
        }
        inProgress.put(owner, name);
        owners.put(name, owner);
        try {
            declarations.put(name, declaration.get());
        } finally {
            inProgress.remove(owner);
        }
        return name;
    }

    /** Every declaration, in the order they were first needed. */
    public String render(String indent) {
        StringBuilder out = new StringBuilder();
        for (String d : declarations.values()) {
            for (String line : d.split("\n", -1)) {
                out.append(indent).append(line).append('\n');
            }
        }
        return out.toString();
    }

    /** A TypeScript string literal: a line terminator or control character inside one would end it early. */
    public static String literal(String s) {
        StringBuilder b = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\'', '\\' -> b.append('\\').append(c);
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('\'').toString();
    }

    /** A property key: bare when it is an identifier, quoted otherwise. */
    public static String key(String name) {
        return name.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? name : literal(name);
    }
}
