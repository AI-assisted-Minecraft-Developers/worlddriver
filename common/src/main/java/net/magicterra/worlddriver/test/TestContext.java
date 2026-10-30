package net.magicterra.worlddriver.test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The assertions a {@link ScriptTest} body calls. A failed assertion throws an {@link AssertionError} whose message
 * starts with the test's name in brackets.
 */
public final class TestContext {
    public final String name;

    /** A context for the test {@code name}. */
    public TestContext(String name) {
        this.name = name;
    }

    /** Fails with "expected true" unless {@code cond} holds. */
    public void assertTrue(boolean cond) {
        assertTrue(cond, "expected true");
    }

    /** Fails with {@code msg} unless {@code cond} holds. */
    public void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(tag() + msg);
        }
    }

    /** Fails with "expected false" if {@code cond} holds. */
    public void assertFalse(boolean cond) {
        assertFalse(cond, "expected false");
    }

    /** Fails with {@code msg} if {@code cond} holds. */
    public void assertFalse(boolean cond, String msg) {
        if (cond) {
            throw new AssertionError(tag() + msg);
        }
    }

    /** Fails unless {@code a} equals {@code b}; see {@link #assertEqual(Object, Object, String)}. */
    public void assertEqual(Object a, Object b) {
        assertEqual(a, b, "");
    }

    /**
     * Fails unless {@code a} equals {@code b}, comparing two numbers by their double value whatever their boxed types;
     * the message shows both values.
     */
    public void assertEqual(Object a, Object b, String msg) {
        boolean eq;
        if (a instanceof Number na && b instanceof Number nb) {
            eq = na.doubleValue() == nb.doubleValue();
        } else {
            eq = a == null ? b == null : a.equals(b);
        }
        if (!eq) {
            throw new AssertionError(tag() + "not equal" + (msg.isEmpty() ? "" : ": " + msg) + "\n  expected: " + a
                    + "\n  actual:   " + b);
        }
    }

    /** Fails unless {@code obj} is a map containing {@code key}. */
    public void assertHasKey(Object obj, String key) {
        if (obj instanceof Map<?, ?> m) {
            if (!m.containsKey(key)) {
                throw new AssertionError(tag() + "missing key '" + key + "' in " + m.keySet());
            }
            return;
        }
        throw new AssertionError(tag() + "not a Map: " + obj);
    }

    /**
     * Fails unless {@code a} and {@code b} are structurally equal: maps by keys and values, lists and other collections
     * element by element in order, numbers by double value.
     */
    public void assertDeepEqual(Object a, Object b, String msg) {
        if (!deepEq(a, b)) {
            throw new AssertionError(
                    tag() + "deep not equal" + (msg.isEmpty() ? "" : ": " + msg) + "\n  A: " + a + "\n  B: " + b);
        }
    }

    /** The prefix of every failure message: the test's name in brackets and a space. */
    private String tag() {
        return "[" + name + "] ";
    }

    private static boolean deepEq(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (var e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) {
                    return false;
                }
                if (!deepEq(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!deepEq(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Collection<?> ca && b instanceof Collection<?> cb) {
            return deepEq(List.copyOf(ca), List.copyOf(cb));
        }
        return a.equals(b);
    }
}
