# Calling the API from Java

Java code in the same JVM as the mod can call methods by name, as every transport does, or through
a typed interface whose implementation is generated at compile time. This page covers the typed
route: how to call a method, how to implement one, and what the compiler checks for you.

## Calling a method

Each group of methods has an interface whose methods have real Java names, with the contract's
records as parameters and results:

```java
SettingsCalls settings = SettingsCalls.of(WorldDriverCommon.api());
CompletionStage<SettingsReply> reply =
        settings.setting(new SettingsContract.Read(Optional.of(List.of("autoEat"))), CallContext.standalone());

switch (DriverApi.await(reply, 5_000)) {
    case SettingsContract.Values v  -> …
    case SettingsContract.Patched p -> …
    case SettingsContract.Applied a -> …
}
```

The object `of` returns is a generated caller, not the implementation. Every call goes through
the method registry exactly as a request from a transport does:

- the parameters get the same checks (ranges, mutually exclusive fields, the contract's `check`);
- on a game thread, a call that would wait is refused before it starts;
- the call reaches whichever handler is registered for the method now, including one a mod put in
  its place.

A call answers a `CompletionStage`. A failure completes it exceptionally with an `ApiException`;
`DriverApi.await(stage, ms)` unwraps it. On the server or client thread, never wait on the stage:
continue from it on a later tick instead (the test scenes do this through `SceneCalls`).

Call by name (`DriverApi.route`, `DriverApi.dispatch`) only when the method name is itself data:
replay, wait conditions, watchers, and the transports.

## Implementing methods

Write the interface next to the contract it serves. Each abstract method carries `@Api`, naming
the class that declares its `MethodSpec` and, when that is not `SPEC`, the field:

```java
public interface SettingsCalls {
    static SettingsCalls of(DriverApi api) { return SettingsCallsBinding.calls(api); }

    @Api(SettingsContract.class)
    CompletionStage<SettingsReply> setting(SettingsOp op, CallContext ctx);
}
```

The implementation implements the same interface and publishes its registry entries from the
generated binding:

```java
final class SettingsMethods implements SettingsCalls {
    List<ApiMethod<?, ?>> methods() { return SettingsCallsBinding.methods(this); }

    @Override
    public CompletionStage<SettingsReply> setting(SettingsOp op, CallContext ctx) { … }
}
```

Keep the implementing class out of callers' reach. A caller holding it directly skips the
registry, and with it every check above.

## What the compiler checks

`:api-processor` runs while `:common` compiles. For each interface with `@Api` methods it refuses
the build when:

- an abstract method has no `@Api`;
- the named field does not exist, is not a `MethodSpec<P, R>`, or is not `public static final`;
- the method is not `CompletionStage<R> name(P params, CallContext ctx)` for exactly that `P` and `R`;
- one spec is bound twice in the same interface.

It then writes `<Interface>Binding` into `common/build/generated/sources/annotationProcessor/`.
That file is not committed: it is rebuilt on every compile and ships in the sources jar.

Left for run time is only what run time knows: whether the parameter values are valid, which
thread is calling, and who currently answers the method.

The processor and its dependencies (JavaPoet, AutoService) are on `:common`'s annotation processor
path only. They are not in any jar the build ships and not in any published POM.
