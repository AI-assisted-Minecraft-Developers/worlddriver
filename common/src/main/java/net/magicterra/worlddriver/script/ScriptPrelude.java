package net.magicterra.worlddriver.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ScriptableObject;
import net.magicterra.worlddriver.api.DriverApi;

/**
 * prelude.js and the globals it reads, installed the same way in every scope that runs it: the
 * {@code mc.script.eval} scope and the validation harness scope. A scope that set up one of these
 * globals itself and missed another would fail only when a script reached the helper that reads it.
 */
final class ScriptPrelude {
    private static final String RESOURCE = "/data/worlddriver/scripts/prelude.js";
    private static final String SOURCE = load();

    private ScriptPrelude() {}

    static void install(Context cx, ScriptableObject scope, DriverApi api) {
        ScriptableObject.putProperty(scope, "__api", cx.javaToJS(api, scope), cx);
        ScriptableObject.putProperty(scope, "__java", cx.javaToJS(ScriptJava.INSTANCE, scope), cx);
        cx.evaluateString(scope, SOURCE, "prelude.js", 1, null);
    }

    private static String load() {
        try (InputStream in = ScriptPrelude.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load " + RESOURCE, e);
        }
    }
}
