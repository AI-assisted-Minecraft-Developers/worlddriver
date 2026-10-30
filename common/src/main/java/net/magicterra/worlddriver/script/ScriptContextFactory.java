package net.magicterra.worlddriver.script;

import java.util.Map;

import dev.latvian.mods.rhino.CachedClassStorage;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;
import dev.latvian.mods.rhino.util.CustomJavaToJsWrapper;

/**
 * Rhino ContextFactory for every script WorldDriver runs: hosted scripts, the validation suite, and
 * {@code mc.script.eval} with skills and playbooks, which subclass it for a {@link ScriptContext} that also
 * watches a deadline. Class filtering is OFF by default — see ScriptClassFilter; -Dworlddriver.sandbox=on
 * opts in.
 */
public class ScriptContextFactory extends ContextFactory {
    @Override
    protected Context createContext() {
        return new ScriptContext(this);
    }

    /** A Java class's methods without the overloads that only make a call ambiguous; see {@link ScriptClassStorage}. */
    @Override
    public CachedClassStorage getCachedClassStorage() {
        return ScriptClassStorage.INSTANCE;
    }

    /** Sends class lookups through {@link ScriptClassFilter} and wraps a Java map as a {@link KeyTypedJavaMap}. */
    static class ScriptContext extends Context {
        ScriptContext(ContextFactory factory) {
            super(factory);
        }

        @Override
        public boolean visibleToScripts(String fullClassName, ClassVisibilityContext type) {
            return ScriptClassFilter.isAllowed(fullClassName, type);
        }

        /** A map keeps its methods whatever its key type; see {@link KeyTypedJavaMap}. */
        @Override
        public Scriptable wrapAsJavaObject(Scriptable scope, Object javaObject, TypeInfo target) {
            if (javaObject instanceof Map<?, ?> map && !(javaObject instanceof CustomJavaToJsWrapper)) {
                return new KeyTypedJavaMap(this, scope, map, target);
            }
            return super.wrapAsJavaObject(scope, javaObject, target);
        }
    }
}
