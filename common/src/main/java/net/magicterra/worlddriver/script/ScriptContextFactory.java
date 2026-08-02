package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.util.ClassVisibilityContext;

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
    }
}
