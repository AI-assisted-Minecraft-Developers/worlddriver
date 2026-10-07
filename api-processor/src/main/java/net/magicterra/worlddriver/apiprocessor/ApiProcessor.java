package net.magicterra.worlddriver.apiprocessor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.google.auto.service.AutoService;

/**
 * Checks every {@code @Api} interface against the specs it names and generates its binding. Every
 * check that can be made here is made here, so a game never starts with a binding that disagrees
 * with its contract: what is left for run time is only what run time knows (the parameter values, the
 * calling thread, who currently answers a method).
 */
@AutoService(Processor.class)
@SupportedAnnotationTypes(ApiProcessor.API)
public final class ApiProcessor extends AbstractProcessor {
    static final String API_PACKAGE = "net.magicterra.worlddriver.api";
    static final String API = API_PACKAGE + ".Api";
    static final String CALL_CONTEXT = API_PACKAGE + ".CallContext";
    static final String METHOD_SPEC = API_PACKAGE + ".contract.MethodSpec";
    static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";

    /** One checked method of an interface: the spec it calls, by holder and field. */
    record Bound(ExecutableElement method, TypeElement holder, String field) {}

    @Override
    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement api = processingEnv.getElementUtils().getTypeElement(API);
        if (api == null || annotations.isEmpty()) return false;
        Set<TypeElement> interfaces = new LinkedHashSet<>();
        for (Element e : round.getElementsAnnotatedWith(api)) {
            Element owner = e.getEnclosingElement();
            if (owner.getKind() != ElementKind.INTERFACE) {
                error(e, "@Api binds a method of an interface; " + owner.getSimpleName() + " is not one");
                continue;
            }
            interfaces.add((TypeElement) owner);
        }
        for (TypeElement iface : interfaces) {
            List<Bound> bound = check(iface, api);
            if (bound != null) BindingWriter.write(processingEnv, iface, bound);
        }
        return true;
    }

    /** The interface's bindings, or null when any check failed (each failure already reported). */
    private List<Bound> check(TypeElement iface, TypeElement api) {
        boolean ok = true;
        if (!iface.getTypeParameters().isEmpty()) {
            error(iface, "an @Api interface cannot be generic: each method calls one fixed spec");
            ok = false;
        }
        List<Bound> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Elements elements = processingEnv.getElementUtils();
        for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(iface))) {
            if (!m.getModifiers().contains(Modifier.ABSTRACT)) continue;
            AnnotationMirror mirror = mirrorOf(m, api);
            if (mirror == null) {
                error(m, "every abstract method of " + iface.getSimpleName() + " needs @Api: the binding has to "
                        + "send it somewhere");
                ok = false;
                continue;
            }
            Bound b = checkMethod(m, mirror);
            if (b == null) {
                ok = false;
            } else if (!seen.add(b.holder().getQualifiedName() + "." + b.field())) {
                error(m, b.holder().getSimpleName() + "." + b.field() + " is bound twice in " + iface.getSimpleName());
                ok = false;
            } else {
                out.add(b);
            }
        }
        return ok ? out : null;
    }

    private Bound checkMethod(ExecutableElement m, AnnotationMirror mirror) {
        Types types = processingEnv.getTypeUtils();
        Elements elements = processingEnv.getElementUtils();
        TypeElement holder = null;
        String field = "SPEC";
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                : elements.getElementValuesWithDefaults(mirror).entrySet()) {
            String key = e.getKey().getSimpleName().toString();
            Object v = e.getValue().getValue();
            if (key.equals("value") && v instanceof DeclaredType t) holder = (TypeElement) t.asElement();
            if (key.equals("spec")) field = (String) v;
        }
        if (holder == null) {
            error(m, mirror, "@Api names no class declaring the spec");
            return null;
        }
        if (!m.getTypeParameters().isEmpty()) {
            error(m, "an @Api method cannot be generic: its spec fixes its types");
            return null;
        }

        VariableElement spec = null;
        for (VariableElement f : ElementFilter.fieldsIn(holder.getEnclosedElements())) {
            if (f.getSimpleName().contentEquals(field)) spec = f;
        }
        if (spec == null) {
            error(m, mirror, holder.getSimpleName() + " has no field " + field);
            return null;
        }
        if (!spec.getModifiers().containsAll(Set.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL))) {
            error(m, mirror, holder.getSimpleName() + "." + field + " must be public static final: the binding "
                    + "and the registry both need that one instance");
            return null;
        }
        List<? extends TypeMirror> specArgs = argsOf(spec.asType(), METHOD_SPEC);
        if (specArgs == null) {
            error(m, mirror, holder.getSimpleName() + "." + field + " is not a MethodSpec<P, R>");
            return null;
        }
        TypeMirror p = specArgs.get(0);
        TypeMirror r = specArgs.get(1);
        String shape = "an @Api method is CompletionStage<" + r + "> " + m.getSimpleName() + "(" + p
                + " params, CallContext ctx), as " + holder.getSimpleName() + "." + field + " fixes";

        List<? extends VariableElement> params = m.getParameters();
        TypeElement ctx = elements.getTypeElement(CALL_CONTEXT);
        if (params.size() != 2 || ctx == null || !types.isSameType(params.get(1).asType(), ctx.asType())) {
            error(m, shape);
            return null;
        }
        if (!types.isSameType(params.get(0).asType(), p)) {
            error(params.get(0), "takes " + params.get(0).asType() + " but " + holder.getSimpleName() + "." + field
                    + " decodes " + p + "; " + shape);
            return null;
        }
        List<? extends TypeMirror> result = argsOf(m.getReturnType(), COMPLETION_STAGE);
        if (result == null || !types.isSameType(result.get(0), r)) {
            error(m, "answers " + m.getReturnType() + " but " + holder.getSimpleName() + "." + field
                    + " produces " + r + "; " + shape);
            return null;
        }
        return new Bound(m, holder, field);
    }

    /** {@code type}'s type arguments when it is {@code erasure<…>} with all of them given, else null. */
    private List<? extends TypeMirror> argsOf(TypeMirror type, String erasure) {
        if (type.getKind() != TypeKind.DECLARED) return null;
        DeclaredType d = (DeclaredType) type;
        TypeElement e = (TypeElement) d.asElement();
        if (!e.getQualifiedName().contentEquals(erasure)) return null;
        List<? extends TypeMirror> args = d.getTypeArguments();
        return args.isEmpty() || args.size() != e.getTypeParameters().size() ? null : args;
    }

    private static AnnotationMirror mirrorOf(Element e, TypeElement api) {
        for (AnnotationMirror a : e.getAnnotationMirrors()) {
            if (a.getAnnotationType().asElement().equals(api)) return a;
        }
        return null;
    }

    private void error(Element at, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, at);
    }

    private void error(Element at, AnnotationMirror on, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, at, on);
    }
}
