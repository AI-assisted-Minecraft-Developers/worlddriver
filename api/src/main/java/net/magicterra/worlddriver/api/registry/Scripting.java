package net.magicterra.worlddriver.api.registry;

import java.util.Map;

/**
 * Runs JavaScript in this game's script engine, for code in another mod that wants scripts without linking the
 * engine itself (the shipped jar relocates it). A script sees the {@code mc.*} methods, as {@code mc.script.eval}
 * does, plus the globals it is given; reached through
 * {@link net.magicterra.worlddriver.api.game.WorldDriver#scripting}. It names no game type, so the script
 * transport can implement it.
 *
 * <p>Values cross as plain Java: {@code null}, {@link Boolean}, {@link Number} (a script's numbers arrive as
 * {@link Double}), {@link String}, {@link java.util.List}, {@link Map} with string keys, and {@link Function}. A
 * script's function arrives as a {@link Function}; a {@link Function} given to a script is a function there. Any
 * other Java object is refused rather than wrapped, so nothing of the engine's leaks either way.
 *
 * <p>An exception a Java {@link Function} throws reaches the Java code that started the script as itself, through
 * however much script lies between; an error the script throws arrives as a {@link ScriptFailure}.
 */
public interface Scripting {

    /** A function across the boundary. A script's may be called from any thread, any number of times. */
    @FunctionalInterface
    interface Function {
        Object call(Object... args);
    }

    /** An error a script threw or the engine raised, with where in which script. */
    final class ScriptFailure extends RuntimeException {
        private final String script;
        private final int line;

        public ScriptFailure(String message, String script, int line, String scriptStack) {
            super(message + (scriptStack == null || scriptStack.isBlank() ? "" : "\n" + scriptStack.stripTrailing()));
            this.script = script;
            this.line = line;
        }

        /** The name the script was evaluated under. */
        public String script() { return script; }

        /** The line it failed at, 0 when unknown. */
        public int line() { return line; }
    }

    /**
     * Evaluates {@code source} in a fresh scope holding {@code globals}, on the calling thread and without a time
     * limit, and answers its last expression's value. {@code name} is how errors refer to it. Off the server
     * thread if the script calls a method that waits for a tick.
     */
    Object evaluate(String name, String source, Map<String, ?> globals);
}
