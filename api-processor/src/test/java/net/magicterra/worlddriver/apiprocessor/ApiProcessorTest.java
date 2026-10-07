package net.magicterra.worlddriver.apiprocessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each mistake a binding can carry is a compile error at the method that carries it. The driver's
 * types are stood in for by minimal stubs with the same names, so this needs nothing but javac.
 */
class ApiProcessorTest {

    @TempDir Path out;

    /** The driver types the processor and the generated code refer to, reduced to their signatures. */
    private static final Map<String, String> STUBS = Map.of(
            "net.magicterra.worlddriver.api.Api", """
                    package net.magicterra.worlddriver.api;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
                    public @interface Api { Class<?> value(); String spec() default "SPEC"; }
                    """,
            "net.magicterra.worlddriver.api.CallContext", """
                    package net.magicterra.worlddriver.api;
                    public final class CallContext {}
                    """,
            "net.magicterra.worlddriver.api.contract.MethodSpec", """
                    package net.magicterra.worlddriver.api.contract;
                    public final class MethodSpec<P, R> {}
                    """,
            "net.magicterra.worlddriver.api.ApiHandler", """
                    package net.magicterra.worlddriver.api;
                    public interface ApiHandler<P, R> { java.util.concurrent.CompletionStage<R> execute(P p, CallContext c); }
                    """,
            "net.magicterra.worlddriver.api.ApiMethod", """
                    package net.magicterra.worlddriver.api;
                    public record ApiMethod<P, R>(net.magicterra.worlddriver.api.contract.MethodSpec<P, R> spec,
                                                  ApiHandler<P, R> handler) {}
                    """,
            "net.magicterra.worlddriver.api.ApiRegistry", """
                    package net.magicterra.worlddriver.api;
                    public final class ApiRegistry {
                        public <P, R> java.util.concurrent.CompletionStage<R> invoke(
                                net.magicterra.worlddriver.api.contract.MethodSpec<P, R> spec, P p, CallContext c) {
                            return null;
                        }
                    }
                    """,
            "net.magicterra.worlddriver.api.DriverApi", """
                    package net.magicterra.worlddriver.api;
                    public abstract class DriverApi { public final ApiRegistry registry() { return null; } }
                    """,
            "demo.Specs", """
                    package demo;
                    import net.magicterra.worlddriver.api.contract.MethodSpec;
                    public final class Specs {
                        public record In(int n) {}
                        public record Out(String s) {}
                        public static final MethodSpec<In, Out> SPEC = new MethodSpec<>();
                        public static final MethodSpec<In, Out> OTHER = new MethodSpec<>();
                        public static MethodSpec<In, Out> LOOSE = new MethodSpec<>();
                    }
                    """);

    private static String calls(String body) {
        return """
                package demo;
                import java.util.concurrent.CompletionStage;
                import net.magicterra.worlddriver.api.Api;
                import net.magicterra.worlddriver.api.CallContext;
                import demo.Specs.In;
                import demo.Specs.Out;
                public interface DemoCalls {
                """ + body + "\n}\n";
    }

    /** Compiles the stubs plus {@code DemoCalls}; answers the errors, empty on success. */
    private List<String> compile(String demoCalls) throws Exception {
        List<JavaFileObject> sources = new ArrayList<>();
        STUBS.forEach((name, text) -> sources.add(source(name, text)));
        sources.add(source("demo.DemoCalls", demoCalls));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        Path classes = Files.createDirectories(out.resolve("classes"));
        Path generated = Files.createDirectories(out.resolve("generated"));
        JavaCompiler.CompilationTask task = javac.getTask(null, null, diags,
                List.of("-d", classes.toString(), "-s", generated.toString(), "-proc:full"), null, sources);
        task.setProcessors(List.of(new ApiProcessor()));
        boolean ok = task.call();
        List<String> errors = diags.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null)).collect(Collectors.toList());
        assertEquals(ok, errors.isEmpty(), "javac's verdict and its errors disagree: " + errors);
        return errors;
    }

    private static JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return text; }
        };
    }

    private void assertRefused(String demoCalls, String expected) throws Exception {
        List<String> errors = compile(demoCalls);
        assertTrue(errors.stream().anyMatch(e -> e.contains(expected)), "wanted '" + expected + "', got " + errors);
    }

    @Test
    void aMatchingInterfaceCompilesWithItsBinding() throws Exception {
        assertEquals(List.of(), compile(calls("""
                @Api(Specs.class) CompletionStage<Out> run(In in, CallContext ctx);
                @Api(value = Specs.class, spec = "OTHER") CompletionStage<Out> other(In in, CallContext ctx);
                """)));
        assertTrue(Files.exists(out.resolve("generated/demo/DemoCallsBinding.java")));
        assertTrue(Files.exists(out.resolve("classes/demo/DemoCallsBinding.class")));
    }

    @Test
    void anUnboundAbstractMethodIsRefused() throws Exception {
        assertRefused(calls("""
                @Api(Specs.class) CompletionStage<Out> run(In in, CallContext ctx);
                CompletionStage<Out> stray(In in, CallContext ctx);
                """), "needs @Api");
    }

    @Test
    void parametersTheSpecDoesNotDecodeAreRefused() throws Exception {
        assertRefused(calls("@Api(Specs.class) CompletionStage<Out> run(Out wrong, CallContext ctx);"), "takes demo.Specs.Out");
    }

    @Test
    void aResultTheSpecDoesNotProduceIsRefused() throws Exception {
        assertRefused(calls("@Api(Specs.class) CompletionStage<In> run(In in, CallContext ctx);"), "answers");
    }

    @Test
    void aMissingContextIsRefused() throws Exception {
        assertRefused(calls("@Api(Specs.class) CompletionStage<Out> run(In in);"), "an @Api method is");
    }

    @Test
    void aSpecThatDoesNotExistIsRefused() throws Exception {
        assertRefused(calls("@Api(value = Specs.class, spec = \"NOPE\") CompletionStage<Out> run(In in, CallContext ctx);"),
                "has no field NOPE");
    }

    @Test
    void aSpecThatCouldBeReplacedIsRefused() throws Exception {
        assertRefused(calls("@Api(value = Specs.class, spec = \"LOOSE\") CompletionStage<Out> run(In in, CallContext ctx);"),
                "must be public static final");
    }

    @Test
    void oneSpecBoundTwiceIsRefused() throws Exception {
        assertRefused(calls("""
                @Api(Specs.class) CompletionStage<Out> run(In in, CallContext ctx);
                @Api(Specs.class) CompletionStage<Out> again(In in, CallContext ctx);
                """), "bound twice");
    }
}
