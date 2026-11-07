package net.magicterra.worlddriver.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.ScriptRuntime;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;
import org.junit.jupiter.api.Test;

/**
 * The {@code Java} global's three answers: a class, a name the filter hides, a name nothing loads. The filter's refusal
 * is checked through a context that hides one class, so it needs no {@code -Dworlddriver.sandbox=on}.
 */
class ScriptJavaGlobalTest {

    /** A class whose static setup would fail, to show Java.type answers it without running that. */
    static final class FailsToInitialize {
        static final int VALUE = Integer.parseInt("not a number");
    }

    private static String run(String source) {
        ContextFactory factory = new ScriptContextFactory() {
            @Override
            protected Context createContext() {
                return new ScriptContext(this) {
                    @Override
                    public boolean visibleToScripts(String fullClassName, ClassVisibilityContext type) {
                        return !"java.lang.Runtime".equals(fullClassName);
                    }
                };
            }
        };
        Context cx = factory.enter();
        ScriptableObject scope = cx.initStandardObjects();
        ScriptJavaGlobal.install(cx, scope);
        return ScriptRuntime.toString(cx, cx.evaluateString(scope, source, "test.js", 1, null));
    }

    private static String caught(String name) {
        return run("try { Java.type('" + name + "'); 'loaded' } catch (e) { String(e.message) }");
    }

    @Test
    void aHiddenClassIsRefusedByNameAndAnotherLoads() {
        assertEquals("the Java class java.lang.Runtime is not visible to scripts", caught("java.lang.Runtime"));
        assertEquals("3", run("String(Java.loadClass('java.lang.Math').max(2, 3))"));
    }

    @Test
    void aNameNothingLoadsSaysSo() {
        assertEquals("no Java class named no.such.Clazz", caught("no.such.Clazz"));
    }

    @Test
    void aClassIsAnsweredWithoutRunningItsStaticSetup() {
        assertEquals("loaded", caught(FailsToInitialize.class.getName()));
        assertFalse(caught(FailsToInitialize.class.getName()).startsWith("no Java class"));
    }
}
