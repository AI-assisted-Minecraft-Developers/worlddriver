package net.magicterra.worlddriver.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.registry.Scripting;
import net.magicterra.worlddriver.api.registry.Scripting.ScriptFailure;
import org.junit.jupiter.api.Test;

/** What crosses between Java and a hosted script, and how failures leave it. */
class HostedScriptsTest {
    private final HostedScripts scripts = new HostedScripts(new ApiRegistry());

    @Test
    void valuesCrossAsPlainJava() {
        Object out = scripts.evaluate(
                "values.js",
                "({n: given.n + 1, s: given.s + '!', list: [1, 'a', null], b: true})",
                Map.of("given", Map.of("n", 41, "s", "hi")));
        assertEquals(Map.of("n", 42.0, "s", "hi!", "list", java.util.Arrays.asList(1.0, "a", null), "b", true), out);
    }

    @Test
    void aScriptsFunctionIsCalledLaterFromAnotherThread() throws Exception {
        List<Scripting.Function> registered = new ArrayList<>();
        Scripting.Function register = args -> {
            registered.add((Scripting.Function) args[0]);
            return null;
        };
        scripts.evaluate(
                "later.js",
                "var base = 10; register(function (x) { return base + x; });",
                Map.of("register", register));
        Object out =
                CompletableFuture.supplyAsync(() -> registered.get(0).call(5)).get(10, TimeUnit.SECONDS);
        assertEquals(15.0, out);
    }

    @Test
    void aJavaExceptionReachesTheCallerAsItself() {
        IllegalStateException thrown = new IllegalStateException("from java");
        Scripting.Function boom = args -> {
            throw thrown;
        };
        assertSame(
                thrown,
                assertThrows(
                        IllegalStateException.class,
                        () -> scripts.evaluate("boom.js", "boom()", Map.of("boom", boom))));
        assertSame(
                thrown,
                assertThrows(
                        IllegalStateException.class,
                        () -> scripts.evaluate(
                                "rethrow.js", "try { boom() } catch (e) { throw e }", Map.of("boom", boom))));
        AssertionError failed = new AssertionError("expected");
        Scripting.Function check = args -> {
            throw failed;
        };
        assertSame(
                failed,
                assertThrows(
                        AssertionError.class, () -> scripts.evaluate("check.js", "check()", Map.of("check", check))));
    }

    @Test
    void aScriptCanCatchAJavaExceptionButNotAJavaError() {
        Scripting.Function boom = args -> {
            throw new IllegalStateException("from java");
        };
        Object caught = scripts.evaluate(
                "catch.js",
                "var seen = 'none'; try { boom() } catch (e) { seen = 'caught: ' + e.message } finally { seen += ' /finally' } seen",
                Map.of("boom", boom));
        assertTrue(
                String.valueOf(caught).startsWith("caught: ")
                        && String.valueOf(caught).contains("from java")
                        && String.valueOf(caught).endsWith(" /finally"),
                caught + "");
        AssertionError failed = new AssertionError("expected");
        Scripting.Function check = args -> {
            throw failed;
        };
        assertSame(
                failed,
                assertThrows(
                        AssertionError.class,
                        () -> scripts.evaluate(
                                "uncaught.js", "try { check() } catch (e) { 'swallowed' }", Map.of("check", check))));
    }

    @Test
    void aScriptErrorSaysWhere() {
        ScriptFailure failure = assertThrows(
                ScriptFailure.class,
                () -> scripts.evaluate("where.js", "var a = 1;\nthrow new Error('nope');", Map.of()));
        assertEquals("where.js", failure.script());
        assertEquals(2, failure.line());
        assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
    }

    @Test
    void aJavaObjectRoundTripsThroughAScript() {
        Thread thread = Thread.currentThread();
        assertSame(thread, scripts.evaluate("same.js", "thread", Map.of("thread", thread)));
        Object made = new Object();
        Scripting.Function make = args -> made;
        List<Object> seen = new ArrayList<>();
        Scripting.Function take = args -> {
            seen.add(args[0]);
            return null;
        };
        scripts.evaluate(
                "pass.js",
                "var pass = function (o) { take(o); return o; }; pass(make())",
                Map.of("make", make, "take", take));
        assertSame(made, seen.get(0));
        assertEquals(thread.getName(), scripts.evaluate("call.js", "thread.getName()", Map.of("thread", thread)));
    }

    @Test
    void javaTypeReachesAStaticMember() {
        assertEquals(
                2.0,
                ((Number) scripts.evaluate("max.js", "Java.type('java.lang.Math').max(1, 2)", Map.of())).doubleValue());
        assertEquals(true, scripts.evaluate("number.js", "Java.type('java.lang.Math').max(1, 2) === 2", Map.of()));
        assertEquals(
                7.0,
                ((Number) scripts.evaluate("load.js", "Java.loadClass('java.lang.Integer').parseInt('7')", Map.of()))
                        .doubleValue());
    }

    @Test
    void javaTypeConstructs() {
        Object built = scripts.evaluate(
                "new.js",
                "var Sb = Java.type('java.lang.StringBuilder'); var sb = new Sb('ab'); sb.append('c'); sb",
                Map.of());
        assertTrue(built instanceof StringBuilder sb && "abc".equals(sb.toString()), String.valueOf(built));
    }

    /** A map whose keys are not strings still has methods: looking a member name up as a key must not throw. */
    @Test
    void aMethodOnAnIntKeyedMapIsCallable() {
        String make = "var M = Java.type('it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap'); var m = new M();"
                + " m[3] = 'three';";
        Object size = scripts.evaluate("int-map.js", make + " m.int2ObjectEntrySet().size() + m.size()", Map.of());
        assertEquals(2.0, ((Number) size).doubleValue());
        assertEquals("three", String.valueOf(scripts.evaluate("int-map-key.js", make + " String(m[3])", Map.of())));
    }

    @Test
    void aPrimitiveMapTakesPut() {
        // put has a primitive, a deprecated boxed and a bridge overload, which Rhino used to call ambiguous.
        Object out = scripts.evaluate(
                "primitive-put.js",
                "var i = new (Java.type('it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap'))(); i.put(3, 'three');"
                        + " var l = new (Java.type('it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap'))(); l.put(5, 'five');"
                        + " var o = new (Java.type('it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap'))();"
                        + " o.put('a', 1); o.addTo('a', 10);"
                        + " [i.get(3), i.containsKey(3), l.get(5), l.remove(5), l.size(), o.getInt('a')].join(',')",
                Map.of());
        assertEquals("three,true,five,five,0,11", out);
    }

    @Test
    void aPrimitiveMapIsIndexedInItsOwnTypes() {
        // An index reached the map as Integer and a number as Double, which a long key or an int value refuses.
        Object out = scripts.evaluate(
                "primitive-index.js",
                "var i = new (Java.type('it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap'))(); i[4] = 'four';"
                        + " var l = new (Java.type('it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap'))();"
                        + " l[6] = 'six'; l[7] = 'seven'; delete l[7];"
                        + " var o = new (Java.type('it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap'))(); o.b = 2;"
                        + " [i[4], l[6], 6 in l, 7 in l, l.size(), o.getInt('b'), o.b].join(',')",
                Map.of());
        assertEquals("four,six,true,false,1,2,2", out);
    }

    @Test
    void ordinaryOverloadsAndABridgeToAHiddenSuperclassStayCallable() throws Exception {
        assertTrue(ShownSub.class.getDeclaredMethod("greet").isBridge(), "the fixture must carry a visibility bridge");
        Object out = scripts.evaluate(
                "overloads.js",
                "var a = new (Java.type('java.util.ArrayList'))(); a.add('x'); a.add('y'); a.add(0, 'w'); a.remove(0);"
                        + " var max = Java.type('java.lang.Math').max(2, 7);"
                        + " var s = new (Java.type('" + ShownSub.class.getName() + "'))();"
                        + " [a.get(0), a.size(), max, s.greet()].join(',')",
                Map.of());
        assertEquals("x,2,7,hi", out);
    }

    /** Not public: a script reaches {@link #greet} only through the bridge javac gives {@link ShownSub}. */
    static class HiddenBase {
        public String greet() {
            return "hi";
        }
    }

    public static class ShownSub extends HiddenBase {}

    @Test
    void anUnknownClassIsCatchable() {
        Object caught = scripts.evaluate(
                "unknown.js", "try { Java.type('no.such.Clazz'); 'loaded' } catch (e) { e.message }", Map.of());
        assertTrue(String.valueOf(caught).contains("no.such.Clazz"), String.valueOf(caught));
        ScriptFailure uncaught = assertThrows(
                ScriptFailure.class, () -> scripts.evaluate("uncaught.js", "Java.type('no.such.Clazz')", Map.of()));
        assertTrue(uncaught.getMessage().contains("no.such.Clazz"), uncaught.getMessage());
    }
}
