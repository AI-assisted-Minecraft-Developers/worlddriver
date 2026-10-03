package net.magicterra.worlddriver.script;

import java.util.List;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.ScriptableObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** javaClassName against the values a script can actually hold, in the real prelude. */
class ScriptJavaTest {

    /** Each return shape reaches a script differently: wrapped, or as a bare JS value. */
    public static final class Probe {
        public String str() { return "s"; }
        public Long boxedLong() { return 5L; }
        public long primLong() { return 5L; }
        public List<Object> list() { return List.of(5L, "x"); }
    }

    private Context cx;
    private ScriptableObject scope;

    @BeforeEach
    void scope() {
        cx = new ContextFactory().enter();
        scope = cx.initStandardObjects();
        ScriptPrelude.install(cx, scope, null);
        ScriptableObject.putProperty(scope, "p", cx.javaToJS(new Probe(), scope), cx);
    }

    private Object eval(String src) {
        return cx.evaluateString(scope, src, "<test>", 1, null);
    }

    @Test
    void namesAWrappedJavaObject() {
        assertEquals("java.lang.String", eval("javaClassName(p.str())"));
        assertEquals("java.lang.Long", eval("javaClassName(p.boxedLong())"));
        assertEquals(new Probe().list().getClass().getName(), eval("javaClassName(p.list())"));
        assertEquals(Probe.class.getName(), eval("javaClassName(p)"));
    }

    @Test
    void aValueTheScriptSeesAsAJsPrimitiveIsNull() {
        // Java produced these, but a script cannot tell them from literals.
        assertEquals("null", eval("String(javaClassName(p.primLong()))"));
        assertEquals("null", eval("String(javaClassName(p.list()[0]))"));
        assertEquals("null", eval("String(javaClassName(p.list()[1]))"));
        assertEquals("null", eval("String(javaClassName('a' + 1))"));
        assertEquals("null", eval("String(javaClassName(1.5))"));
        assertEquals("null", eval("String(javaClassName(true))"));
    }

    @Test
    void aScriptObjectOrNothingIsNull() {
        for (String v : List.of("null", "undefined", "({})", "[1]", "(function () {})")) {
            assertEquals("null", eval("String(javaClassName(" + v + "))"), v);
        }
    }

    @Test
    void theJavaSideCannotTellAJsPrimitiveFromAJavaOne() {
        // Why the typeof filter lives in the prelude and not here.
        assertEquals("java.lang.String", eval("String(__java.className('a'))"));
        assertEquals("java.lang.Double", eval("String(__java.className(1.5))"));
        assertEquals("null", eval("String(__java.className(undefined))"));
    }
}
