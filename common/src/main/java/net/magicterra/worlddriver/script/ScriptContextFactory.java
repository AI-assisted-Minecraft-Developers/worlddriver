package net.magicterra.worlddriver.script;

import java.util.Map;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.type.TypeInfo;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;
import dev.latvian.mods.rhino.util.CustomJavaToJsWrapper;

/**
 * Rhino ContextFactory for agent script Contexts (class filtering is OFF by
 * default — see ScriptClassFilter; -Dworlddriver.sandbox=on opts in). The Context
 * delegates {@link Context#visibleToScripts} to {@link ScriptClassFilter}
 * so any LiveConnect class lookup goes through our denylist.
 */
public final class ScriptContextFactory extends ContextFactory {
    @Override
    protected Context createContext() {
        return new SandboxedContext(this);
    }

    private static final class SandboxedContext extends Context {
        SandboxedContext(ContextFactory factory) {
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
