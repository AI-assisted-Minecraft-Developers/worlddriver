package net.magicterra.worlddriver.apiprocessor;

import java.io.IOException;
import java.util.List;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.Diagnostic;

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

/**
 * Writes {@code <Interface>Binding} for a checked interface: {@code calls(…)}, a caller that sends
 * each method through the registry, and {@code methods(impl)}, an implementation's registry entries.
 */
final class BindingWriter {
    private BindingWriter() {}

    private static final ClassName REGISTRY = ClassName.get(ApiProcessor.API_PACKAGE, "ApiRegistry");
    private static final ClassName DRIVER_API = ClassName.get(ApiProcessor.API_PACKAGE, "DriverApi");
    private static final ClassName API_METHOD = ClassName.get(ApiProcessor.API_PACKAGE, "ApiMethod");
    private static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

    static void write(ProcessingEnvironment env, TypeElement iface, List<ApiProcessor.Bound> bound) {
        ClassName type = ClassName.get(iface);
        String name = String.join("_", type.simpleNames()) + "Binding";
        boolean open = iface.getModifiers().contains(Modifier.PUBLIC);
        TypeName entry = ParameterizedTypeName.get(API_METHOD,
                WildcardTypeName.subtypeOf(Object.class), WildcardTypeName.subtypeOf(Object.class));
        TypeName entries = ParameterizedTypeName.get(ClassName.get(List.class), entry);

        TypeSpec.Builder caller = TypeSpec.classBuilder("Caller")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(type)
                .addField(FieldSpec.builder(REGISTRY, "registry", Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addParameter(REGISTRY, "registry")
                        .addStatement("this.registry = $T.requireNonNull(registry, $S)", java.util.Objects.class, "registry")
                        .build());
        CodeBlock.Builder list = CodeBlock.builder().add("return $T.<$T>of(", List.class, entry).indent();
        for (int i = 0; i < bound.size(); i++) {
            ApiProcessor.Bound b = bound.get(i);
            String params = b.method().getParameters().get(0).getSimpleName().toString();
            String ctx = b.method().getParameters().get(1).getSimpleName().toString();
            caller.addMethod(MethodSpec.overriding(b.method(), (DeclaredType) iface.asType(), env.getTypeUtils())
                    .addStatement("return registry.invoke($T.$N, $N, $N)", ClassName.get(b.holder()), b.field(), params, ctx)
                    .build());
            list.add(i == 0 ? "\n" : ",\n").add("new $T<>($T.$N, impl::$N)", API_METHOD, ClassName.get(b.holder()),
                    b.field(), b.method().getSimpleName().toString());
        }
        list.unindent().add(");\n");

        TypeSpec.Builder binding = TypeSpec.classBuilder(name)
                .addModifiers(Modifier.FINAL)
                .addOriginatingElement(iface)
                .addAnnotation(AnnotationSpec.builder(GENERATED)
                        .addMember("value", "$S", ApiProcessor.class.getName()).build())
                .addJavadoc("Generated from {@link $T}: its caller and its registry entries.\n", type)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(MethodSpec.methodBuilder("calls")
                        .addJavadoc("Calls through {@code api}'s registry: checked and refused as a request is.\n")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(type)
                        .addParameter(DRIVER_API, "api")
                        .addStatement("return new Caller(api.registry())")
                        .build())
                .addMethod(MethodSpec.methodBuilder("calls")
                        .addJavadoc("Calls through {@code registry}: checked and refused as a request is.\n")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(type)
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
                .addType(caller.build());
        if (open) binding.addModifiers(Modifier.PUBLIC);

        try {
            JavaFile.builder(type.packageName(), binding.build()).skipJavaLangImports(true).build()
                    .writeTo(env.getFiler());
        } catch (IOException e) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR, "cannot write " + name + ": " + e.getMessage(), iface);
        }
    }
}
