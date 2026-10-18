package net.magicterra.worlddriver.script;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What the opt-in script sandbox ({@code -Dworlddriver.sandbox=on}) keeps from a script. One class per
 * denylist entry: dropping or narrowing an entry turns this red, so widening what a sandboxed script can
 * reach is a visible change to this list rather than a quiet edit to the filter.
 */
class ScriptClassFilterTest {

    private static final List<String> DENIED = List.of(
            "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process", "java.lang.ProcessHandle",
            "java.lang.ProcessHandle$Info", "java.lang.Thread", "java.lang.ThreadGroup", "java.lang.Module",
            "java.lang.ModuleLayer", "java.lang.SecurityManager", "System$LoggerFinder",
            "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader",
            "java.io.FileWriter", "java.io.RandomAccessFile", "java.io.FileDescriptor",
            "java.io.ObjectInputStream", "java.io.ObjectOutputStream",
            "java.lang.reflect.Method", "java.lang.invoke.MethodHandles", "java.nio.file.Files",
            "java.nio.channels.FileChannel", "java.net.Socket", "java.security.AccessController",
            "javax.script.ScriptEngineManager", "javax.tools.ToolProvider", "sun.misc.Unsafe",
            "sun.security.provider.SecureRandom", "sun.net.www.protocol.http.HttpURLConnection",
            "sun.nio.fs.UnixPath", "com.sun.tools.attach.VirtualMachine", "com.sun.security.auth.UserPrincipal",
            "jdk.internal.misc.Unsafe");

    /** What scripts lean on, and what Rhino itself needs to wrap anything at all. */
    private static final List<String> ALLOWED = List.of(
            "java.lang.String", "java.lang.Math", "java.util.ArrayList", "java.util.HashMap",
            "jdk.proxy1.$Proxy12", "sun.invoke.util.Wrapper", "org.objectweb.asm.ClassWriter",
            "net.magicterra.worlddriver.script.ScriptEvaluator", "");

    @Test
    void everyDenylistEntryIsDenied() {
        List<String> leaked = new ArrayList<>();
        for (String name : DENIED) if (!ScriptClassFilter.denied(name)) leaked.add(name);
        assertTrue(leaked.isEmpty(), "a sandboxed script could reach " + leaked);
    }

    @Test
    void whatScriptsAndRhinoNeedStaysReachable() {
        List<String> blocked = new ArrayList<>();
        for (String name : ALLOWED) if (ScriptClassFilter.denied(name)) blocked.add(name);
        assertTrue(blocked.isEmpty(), "the sandbox would block " + blocked);
    }
}
