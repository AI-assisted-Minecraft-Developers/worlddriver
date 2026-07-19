package net.magicterra.agent.script;

import dev.latvian.mods.rhino.util.ClassVisibilityContext;

/**
 * Optional JS class-access filter (process spawn, reflection, raw file/socket
 * IO, jdk internals). Used by {@link AgentContextFactory} which installs it on
 * every Rhino Context.
 *
 * <p><b>OFF BY DEFAULT</b> (user directive, 2026-07-17): Rhino scripts are a
 * first-party automation surface — restricting what they can call restricts the
 * driver's own capability, and the scripts' security is the CALLER's
 * responsibility (same trust model as the RPC socket itself: whoever can reach
 * the endpoint already owns the process). Set {@code -Dagent.sandbox=on} to
 * opt back into filtering for hardened deployments.
 *
 * Approach is a denylist with explicit prefix matching. We could allowlist
 * instead, but our scripts intentionally lean on java.util.* / java.lang.*
 * shapes through wrapped AgentApi return values, so listing the dangerous
 * pieces is far less error-prone.
 */
public final class AgentClassFilter {
    private static final boolean DISABLED = !"on".equalsIgnoreCase(System.getProperty("agent.sandbox", "off"));

    /**
     * Exact-name denies. Hit before prefix denies.
     */
    private static final String[] DENY_EXACT = {
        "java.lang.Runtime",
        "java.lang.ProcessBuilder",
        "java.lang.Process",
        "java.lang.ProcessHandle",
        "java.lang.ProcessHandle$Info",
        "java.lang.Thread",
        "java.lang.ThreadGroup",
        "java.lang.Module",
        "java.lang.ModuleLayer",
        "java.lang.SecurityManager",
        "System$LoggerFinder",
        "java.io.File",
        "java.io.FileInputStream",
        "java.io.FileOutputStream",
        "java.io.FileReader",
        "java.io.FileWriter",
        "java.io.RandomAccessFile",
        "java.io.FileDescriptor",
        "java.io.ObjectInputStream",
        "java.io.ObjectOutputStream",
    };

    private static final String[] DENY_PREFIX = {
        "java.lang.reflect.",
        "java.lang.invoke.",
        "java.nio.file.",
        "java.nio.channels.",
        "java.net.",
        "java.security.",
        "javax.script.",
        "javax.tools.",
        "sun.misc.",
        "sun.security.",
        "sun.net.",
        "sun.nio.fs.",
        "com.sun.tools.",
        "com.sun.security.",
        "jdk.internal.",
    };
    // Things deliberately NOT blocked here, with their reasons:
    //   jdk.proxy*.*  — JDK dynamic proxy classes Rhino itself generates when
    //                   wrapping interfaces. Blocking this breaks all script
    //                   execution.
    //   sun.*         — Many Rhino-touched reflective paths legitimately
    //                   resolve to sun.* implementation types. We block only
    //                   the actually-sensitive sub-packages above.
    //   com.sun.*     — Similar reasoning; we block the dangerous sub-packages.
    //   org.objectweb.asm.* — ASM is the bytecode lib Rhino uses internally.

    // Note: we don't deny our own net.magicterra.agent.* classes. visibleToScripts
    // is called during *wrap-time* with MEMBER context too — denying internal
    // classes there breaks the very __api/__rpc/__mcp wrappers JS scripts rely on.
    // This Rhino fork strips the Packages global, so JS cannot resolve our
    // classes by name anyway; nothing of value is being exposed by allowing them.

    private AgentClassFilter() {}

    public static boolean isAllowed(String fullClassName, ClassVisibilityContext type) {
        if (DISABLED) return true;
        if (fullClassName == null || fullClassName.isEmpty()) return true;
        for (String d : DENY_EXACT) {
            if (fullClassName.equals(d)) return false;
        }
        for (String d : DENY_PREFIX) {
            if (fullClassName.startsWith(d)) return false;
        }
        return true;
    }
}
