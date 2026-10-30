package net.magicterra.worlddriver.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ScriptableObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * prelude.js, the generated method wrappers, and the globals they read, installed the same way in every scope that runs
 * them: the {@code mc.script.eval} scope and the validation harness scope. A scope that set up one of these globals
 * itself and missed another would fail only when a script reached the helper that reads it.
 */
final class ScriptPrelude {
    private static final String RESOURCE = "/data/worlddriver/scripts/prelude.js";
    /** Built from the method descriptions by {@code generateApiArtifacts}; see docs/dev/api-artifacts.md. */
    private static final String GENERATED = "/data/worlddriver/scripts/generated/driver-api.js";

    private static final String SOURCE = load(RESOURCE);
    private static final String WRAPPERS = load(GENERATED);

    private ScriptPrelude() {}

    static void install(Context cx, ScriptableObject scope, ScriptCalls calls) {
        ScriptableObject.putProperty(scope, "__api", cx.javaToJS(calls, scope), cx);
        ScriptableObject.putProperty(scope, "__java", cx.javaToJS(ScriptJava.INSTANCE, scope), cx);
        cx.evaluateString(scope, SOURCE, "prelude.js", 1, null);
        cx.evaluateString(scope, WRAPPERS, "driver-api.js", 1, null);
    }

    private static String load(String resource) {
        try (InputStream in = ScriptPrelude.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + resource, e);
        }
    }
}
