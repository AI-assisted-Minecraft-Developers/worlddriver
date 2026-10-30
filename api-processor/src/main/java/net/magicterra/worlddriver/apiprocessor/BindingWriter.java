package net.magicterra.worlddriver.apiprocessor;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import java.io.IOException;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;

/**
 * Writes three files for a checked interface. {@code <Interface>Binding} holds {@code calls(…)}, a caller that sends
 * each method through the registry, {@code methods(impl)}, an implementation's registry entries, and {@code specs()},
 * the same specs without an implementation. The caller's type is {@code <Family>StubApi}, the interface plus a default
 * overload of each method that leaves out the {@code CallContext}; an implementation still implements the interface
 * alone. The third, {@code <Family>BlockingApi}, answers each method's result by waiting for the stage on the calling
 * thread.
 */
final class BindingWriter {
    private BindingWriter() {}

    private static final ClassName REGISTRY = ClassName.get(ApiProcessor.REGISTRY_PACKAGE, "ApiRegistry");
    private static final ClassName API_METHOD = ClassName.get(ApiProcessor.REGISTRY_PACKAGE, "ApiMethod");
    private static final ClassName CALL_CONTEXT = ClassName.bestGuess(ApiProcessor.CALL_CONTEXT);
    private static final ClassName METHOD_SPEC = ClassName.bestGuess(ApiProcessor.METHOD_SPEC);
    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

    /** {@code QueryApi} → {@code QueryStubApi}; an interface not named {@code …Api} gets {@code Stub} appended. */
    static String stubName(ClassName type) {
        String base = String.join("_", type.simpleNames());
        return base.endsWith("Api") ? base.substring(0, base.length() - 3) + "StubApi" : base + "Stub";
    }

    /**
     * {@code QueryApi} → {@code QueryBlockingApi}; an interface not named {@code …Api} gets {@code Blocking} appended.
     */
    static String blockingName(ClassName type) {
        String base = String.join("_", type.simpleNames());
        return base.endsWith("Api") ? base.substring(0, base.length() - 3) + "BlockingApi" : base + "Blocking";
    }

    static void write(ProcessingEnvironment env, TypeElement iface, List<ApiProcessor.Bound> bound) {
        ClassName type = ClassName.get(iface);
        String name = String.join("_", type.simpleNames()) + "Binding";
        ClassName stubType = ClassName.get(type.packageName(), stubName(type));
        boolean open = iface.getModifiers().contains(Modifier.PUBLIC);
        TypeName entry = ParameterizedTypeName.get(
                API_METHOD, WildcardTypeName.subtypeOf(Object.class), WildcardTypeName.subtypeOf(Object.class));
        TypeName entries = ParameterizedTypeName.get(ClassName.get(List.class), entry);
        TypeName spec = ParameterizedTypeName.get(
                METHOD_SPEC, WildcardTypeName.subtypeOf(Object.class), WildcardTypeName.subtypeOf(Object.class));

        TypeSpec.Builder stub = TypeSpec.interfaceBuilder(stubType)
                .addOriginatingElement(iface)
                .addSuperinterface(type)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", ApiProcessor.class.getName())
                        .build())
                .addJavadoc(
                        "Generated from {@link $T}: what {@code of(registry)} answers, each method also callable\n"
                                + "without a {@link $T}.\n",
                        type,
                        CALL_CONTEXT);
        if (open) stub.addModifiers(Modifier.PUBLIC);

        TypeSpec.Builder caller = TypeSpec.classBuilder("Caller")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(stubType)
                .addField(FieldSpec.builder(REGISTRY, "registry", Modifier.PRIVATE, Modifier.FINAL)
                        .build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addParameter(REGISTRY, "registry")
                        .addStatement(
                                "this.registry = $T.requireNonNull(registry, $S)", java.util.Objects.class, "registry")
                        .build());
        CodeBlock.Builder list =
                CodeBlock.builder().add("return $T.<$T>of(", List.class, entry).indent();
        CodeBlock.Builder specList =
                CodeBlock.builder().add("return $T.<$T>of(", List.class, spec).indent();
        for (int i = 0; i < bound.size(); i++) {
            ApiProcessor.Bound b = bound.get(i);
            String params = b.method().getParameters().get(0).getSimpleName().toString();
            String ctx = b.method().getParameters().get(1).getSimpleName().toString();
            caller.addMethod(MethodSpec.overriding(b.method(), (DeclaredType) iface.asType(), env.getTypeUtils())
                    .addStatement(
                            "return registry.invoke($T.$N, $N, $N)", ClassName.get(b.holder()), b.field(), params, ctx)
                    .build());
            String method = b.method().getSimpleName().toString();
            // standalone() reads the clock, so each call makes its own rather than sharing one made earlier.
            stub.addMethod(MethodSpec.methodBuilder(method)
                    .addJavadoc(
                            "{@link #$N} as a call of its own: {@code CallContext.standalone()}, made for this call.\n",
                            method)
                    .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                    .returns(TypeName.get(b.method().getReturnType()))
                    .addParameter(TypeName.get(b.method().getParameters().get(0).asType()), params)
                    .addStatement("return $N($N, $T.standalone())", method, params, CALL_CONTEXT)
                    .build());
            list.add(i == 0 ? "\n" : ",\n")
                    .add(
                            "new $T<>($T.$N, impl::$N)",
                            API_METHOD,
                            ClassName.get(b.holder()),
                            b.field(),
                            b.method().getSimpleName().toString());
            specList.add(i == 0 ? "\n" : ",\n").add("$T.$N", ClassName.get(b.holder()), b.field());
        }
        list.unindent().add(");\n");
        specList.unindent().add(");\n");

        ClassName blockingType = ClassName.get(type.packageName(), blockingName(type));
        TypeSpec.Builder blocking = TypeSpec.interfaceBuilder(blockingType)
                .addOriginatingElement(iface)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", ApiProcessor.class.getName())
                        .build())
                .addJavadoc(
                        "Generated from {@link $T}: each method called through {@code registry} and waited for on this thread.\n",
                        type);
        if (open) blocking.addModifiers(Modifier.PUBLIC);
        TypeSpec.Builder waiting = TypeSpec.anonymousClassBuilder("").addSuperinterface(blockingType);
        for (ApiProcessor.Bound b : bound) {
            String params = b.method().getParameters().get(0).getSimpleName().toString();
            String method = b.method().getSimpleName().toString();
            TypeName result = TypeName.get(((DeclaredType) b.method().getReturnType())
                    .getTypeArguments()
                    .get(0));
            TypeName param = TypeName.get(b.method().getParameters().get(0).asType());
            blocking.addMethod(MethodSpec.methodBuilder(method)
                    .addJavadoc(
                            "{@link $T#$N} waited for at most {@link $T#SERVER_CAP_MS}; throws its {@code ApiException}.\n",
                            type,
                            method,
                            CALL_CONTEXT)
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .returns(result)
                    .addParameter(param, params)
                    .build());
            waiting.addMethod(MethodSpec.methodBuilder(method)
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(result)
                    .addParameter(param, params)
                    .addStatement(
                            "return $T.await(stub.$N($N), $T.SERVER_CAP_MS)", REGISTRY, method, params, CALL_CONTEXT)
                    .build());
        }
        blocking.addMethod(MethodSpec.methodBuilder("of")
                .addJavadoc(
                        "A caller that goes through {@code registry}, checked as a request is; each call blocks this thread.\n")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(blockingType)
                .addParameter(REGISTRY, "registry")
                .addStatement("$T stub = $T.calls(registry)", stubType, ClassName.get(type.packageName(), name))
                .addStatement("return $L", waiting.build())
                .build());

        TypeSpec.Builder binding = TypeSpec.classBuilder(name)
                .addModifiers(Modifier.FINAL)
                .addOriginatingElement(iface)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", ApiProcessor.class.getName())
                        .build())
                .addJavadoc("Generated from {@link $T}: its caller and its registry entries.\n", type)
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PRIVATE)
                        .build())
                .addMethod(MethodSpec.methodBuilder("calls")
                        .addJavadoc("Calls through {@code registry}: checked and refused as a request is.\n")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(stubType)
                        .addParameter(REGISTRY, "registry")
                        .addStatement("return new Caller(registry)")
                        .build())
                .addMethod(MethodSpec.methodBuilder("methods")
                        .addJavadoc("{@code impl}'s methods as registry entries, one per spec.\n")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(entries)
                        .addParameter(type, "impl")
                        .addCode(list.build())
                        .build())
                .addMethod(MethodSpec.methodBuilder("specs")
                        .addJavadoc(
                                "The specs the interface binds, in its order: what it serves, with no implementation.\n")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(ParameterizedTypeName.get(ClassName.get(List.class), spec))
                        .addCode(specList.build())
                        .build())
                .addType(caller.build());
        if (open) binding.addModifiers(Modifier.PUBLIC);

        writeFile(env, iface, type.packageName(), stub.build());
        writeFile(env, iface, type.packageName(), binding.build());
        writeFile(env, iface, type.packageName(), blocking.build());
    }

    private static void writeFile(ProcessingEnvironment env, TypeElement iface, String pkg, TypeSpec spec) {
        try {
            JavaFile.builder(pkg, spec).skipJavaLangImports(true).build().writeTo(env.getFiler());
        } catch (IOException e) {
            env.getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "cannot write " + spec.name() + ": " + e.getMessage(), iface);
        }
    }
}
