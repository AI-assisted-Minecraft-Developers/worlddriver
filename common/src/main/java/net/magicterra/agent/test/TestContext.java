package net.magicterra.agent.test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class TestContext {
    public final String name;
    public TestContext(String name) { this.name = name; }

    public void assertTrue(boolean cond) { assertTrue(cond, "expected true"); }
    public void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("[" + name + "] " + msg);
    }
    public void assertFalse(boolean cond) { assertFalse(cond, "expected false"); }
    public void assertFalse(boolean cond, String msg) {
        if (cond) throw new AssertionError("[" + name + "] " + msg);
    }

    public void assertEqual(Object a, Object b) { assertEqual(a, b, ""); }
    public void assertEqual(Object a, Object b, String msg) {
        boolean eq;
        if (a instanceof Number na && b instanceof Number nb) {
            eq = na.doubleValue() == nb.doubleValue();
        } else {
            eq = (a == null) ? b == null : a.equals(b);
        }
        if (!eq) throw new AssertionError(
            "[" + name + "] not equal" + (msg.isEmpty() ? "" : ": " + msg)
            + "\n  expected: " + a + "\n  actual:   " + b
        );
    }

    public void assertHasKey(Object obj, String key) {
        if (obj instanceof Map<?, ?> m) {
            if (!m.containsKey(key)) {
                throw new AssertionError("[" + name + "] missing key '" + key + "' in " + m.keySet());
            }
            return;
        }
        throw new AssertionError("[" + name + "] not a Map: " + obj);
    }

    public void assertDeepEqual(Object a, Object b, String msg) {
        if (!deepEq(a, b)) {
            throw new AssertionError(
                "[" + name + "] deep not equal" + (msg.isEmpty() ? "" : ": " + msg)
                + "\n  A: " + a + "\n  B: " + b
            );
        }
    }

    private static boolean deepEq(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) return false;
            for (var e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) return false;
                if (!deepEq(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) if (!deepEq(la.get(i), lb.get(i))) return false;
            return true;
        }
        if (a instanceof Collection<?> ca && b instanceof Collection<?> cb) {
            return deepEq(List.copyOf(ca), List.copyOf(cb));
        }
        return a.equals(b);
    }
}
