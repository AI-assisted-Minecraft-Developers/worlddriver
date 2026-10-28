# Calling the API from Java

Java code in the same JVM as the mod can call methods by name, as every transport does, or through
a typed interface whose implementation is generated at compile time. This page covers the typed
route: how to call a method, how to implement one, and what the compiler checks for you.

## Calling a method

Each group of methods has an interface whose methods have real Java names, with the contract's
records as parameters and results:

```java
SettingsStubApi settings = SettingsApi.of(WorldDriver.registry());
CompletionStage<SettingsReply> reply = settings.setting(SettingsContracts.Read.of("autoEat"));

switch (ApiRegistry.await(reply, 5_000)) {
    case SettingsContracts.Values v  -> …
    case SettingsContracts.Patched p -> …
    case SettingsContracts.Applied a -> …
}
```

Every reply union is sealed, so a `switch` like the one above is checked for every case the method
can answer.

`WorldDriver` (`api/game/`) is where the registry is found. It is null until the driver is up: at
client init on a client, at server start on a dedicated server, in both cases after every mod's own
setup. Code that runs earlier and wants to publish methods of its own passes them to
`WorldDriver.whenReady(registry -> registry.registerBatch(…))`, which runs at once if the driver is
already up.

| Interface | Methods |
|---|---|
| `bot.BotTaskApi` | the body tasks: `goTo`, `mine`, `craft`, `build`, `combat`, … |
| `interact.InteractApi` | `lookAt`, `holdItem`, `useItem`, `attackEntity`, `equip` |
| `control.ControlApi` | `cancel`, `status`, `waypoint`, `roster` |
| `SettingsApi` (in `bot`, outside `api`) | `setting` |
| `wait.WaitApi` | `event`, `worldReady`, `condition`, `result` |
| `observe.ObserveApi` | `player`, `threats`, `boss`, `scene`, `map` |
| `event.EventApi` | `cursor`, `eventsSince`, `events` |
| `query.QueryApi` | `query` |
| `world.WorldApi` / `action.ActionApi` | `mc.world.*` / `mc.action.*` |
| `screen.ScreenApi` / `client.ClientApi` | `mc.client.screen.*` and `mc.client.input.*` / the rest of `mc.client.*` |
| `recipe.RecipeApi`, `script.ScriptApi`, `system.SystemApi`, `debug.DebugApi` | their namespaces |

The packages are under `api.methods` unless noted, one per family, each holding the family's
interface and its `*Contracts`; what several families share is in `api.methods.shared`. Parameter
records with many optional fields have static factories for the common requests
(`GotoContracts.Params.to(pos)`, `CancelParams.action(id)`, `QueryContracts.Blocks.around(center, r)`,
…); `ParamFactoriesTest` holds each one to the contract's defaults.

The object `of` returns is a generated caller, not the implementation. Every call goes through
the method registry exactly as a request from a transport does:

- the parameters get the same checks (ranges, mutually exclusive fields, the contract's `check`);
- the call reaches whichever handler is registered for the method now, including one a mod put in
  its place.

Its type is the family's generated `<Family>StubApi` (`SettingsStubApi`, `QueryStubApi`, …): the
interface plus an overload of each method without the `CallContext`. Leaving it out makes the call
with `CallContext.standalone()`, created fresh for that call: the server's own time limit, nothing
that cancels it, the shared wait quota. Pass a context only when the call belongs to another one, so
that it inherits that call's deadline and cancellation (`ctx.child(ms)`).

A call answers a `CompletionStage`. A failure completes it exceptionally with an `ApiException`;
`ApiRegistry.await(stage, ms)` unwraps it. A call never holds the thread that makes it, so the server
or client thread may make one, including one the other game thread must serve; it is queued there.
On those threads, never wait on the stage: continue from it on a later tick instead (the test scenes
do this through `SceneCalls`).

Call by name (`ApiRegistry.route`, `ApiRegistry.dispatch`) only when the method name is itself data
(replay, wait conditions, watchers, the transports), or when the request's JSON is what is under
test: a scene that sends a field the contract refuses, or checks a reply's wire shape, uses
`SceneCalls.askByName`.

## Implementing methods

Write the interface next to the contract it serves. Each abstract method carries `@Spec`, naming
the class that declares its `MethodSpec` and, when that is not `SPEC`, the field:

```java
public interface SettingsApi {
    static SettingsStubApi of(ApiRegistry registry) { return SettingsApiBinding.calls(registry); }

    @Spec(SettingsContracts.class)
    CompletionStage<SettingsReply> setting(SettingsOp op, CallContext ctx);
}
```

The implementation, named for the family with `Service`, implements the interface (not the stub,
whose overloads are for callers) and publishes its registry entries from the generated binding:

```java
final class SettingsService implements SettingsApi {
    List<ApiMethod<?, ?>> methods() { return SettingsApiBinding.methods(this); }

    @Override
    public CompletionStage<SettingsReply> setting(SettingsOp op, CallContext ctx) { … }
}
```

Keep the implementing class out of callers' reach. A caller holding it directly skips the
registry, and with it every check above.

## What the compiler checks

`:api-processor` runs while `:api` compiles. For each interface with `@Spec` methods it refuses
the build when:

- an abstract method has no `@Spec`;
- the named field does not exist, is not a `MethodSpec<P, R>`, or is not `public static final`;
- the method is not `CompletionStage<R> name(P params, CallContext ctx)` for exactly that `P` and `R`;
- one spec is bound twice in the same interface.

It then writes `<Interface>Binding` and `<Family>StubApi` into
`api/build/generated/sources/annotationProcessor/`. The binding holds `calls(registry)`,
`methods(impl)` and `specs()`; the last is the interface's spec list with no implementation, which is
what the artifact generator publishes for a batch it cannot construct (the path-debug methods). Neither file is committed: it is rebuilt on every compile and ships in the sources jar.

Left for run time is only what run time knows: whether the parameter values are valid, which
thread is calling, and who currently answers the method.

The processor and its dependencies (JavaPoet, AutoService) are on `:api`'s annotation processor
path only. They are not in any jar the build ships and not in any published POM.

## Driving a mob of your own

The bot drives any mob through its own mob body: it takes the mob's goals off and writes its input in
the mob's AI step. A modded creature that moves by code of its own ignores that input, and its goto
fails as `unresponsive`. Register an `EntityDriver` for its type (`api/game/`) during your mod's setup,
before a mob of the type can be adopted:

```java
EntityDrivers.register(MY_CREATURE.get(), creature -> new EntityDriver() {
    @Override public void input(Input in) { creature.steer(in.yaw(), in.left(), in.forward(), in.jump()); }
    @Override public void face(float yaw, float pitch) { creature.setYRot(yaw); creature.setXRot(pitch); }
});
```

From then on a mob of that type, adopted by an order to `entity:<uuid>` or by `mc.bot.roster`, is
driven through it. The bot still searches the route and picks each step; every server tick it hands
`input` what a player would press for that step, relative to `in.yaw()`. Taking the creature's own AI
off while it is driven, and giving it back in `release`, is the driver's job. `movements()` declares
how it moves when that cannot be read off the entity (its navigation), and the route is searched for
those ways only. `bag()` lets the processes that count items see what it carries. It gets no hands:
the route never digs or places for a mob, and an operation of its own belongs in a method instead.

A creature whose movement no route can follow cell by cell (it teleports, burrows, or flies a course
of its own) also returns a `Mover` from `mover()`. A goto to a cell, or through `route.via` cells, is
then handed to it whole: each tick the bot asks `drive(target, within)` and the mover answers
`MOVING`, `ARRIVED` or `FAILED`. The trip reports `travel: mover`. A goto to a target with no single
cell (a Y level, a direction) still uses the bot's own route.

`StandInDrivers` in the testmod registers one of each for its stand-in mobs; the tests
`MobTest.mobWithRegisteredDriverWalks` and `MobTest.mobWithRegisteredMoverTravels` drive them.

## Adding a body task

A mod can add a task of its own that runs on a bot the way `mc.bot.*` tasks do: it starts as an
action, replaces the bot's current task, can be cancelled, and its result answers `mc.wait.result`.
Register it with `BodyTasks` (`api/game/`) during your mod's setup:

```java
@Named("DanceParams")
public record DanceParams(@Default("self") @BotRef String bot, @Range(min = 1, max = 64) int beats,
                          @Default("0") @AwaitMs int awaitMs) implements BodyTasks.Params {}
@Variant("dance")
public record Danced(int beats) {}

static final ApiType<DanceParams> DANCE_PARAMS = ApiTypes.record(DanceParams.class);
static final UnionType<Danced> DANCE_RESULT = ApiTypes.union("DanceResult", "kind", ApiTypes.record(Danced.class));

BodyTasks.register("mymod.dance", DANCE_PARAMS, DANCE_RESULT, "Dance on the spot.",
        (p, body) -> new TaskStep<Danced>() {
            int beat;
            @Override public Progress tick(LivingEntity self) {
                self.setYRot(self.getYRot() + 90);
                return ++beat >= p.beats() ? Progress.SUCCEEDED : Progress.RUNNING;
            }
            @Override public Danced result() { return new Danced(beat); }
        });
```

The name is `<modid>.<verb>`, never under `mc.`. The parameter record has `bot` (`@BotRef`,
defaulting to `self`) and `awaitMs` (`@AwaitMs`, defaulting to 0); the call answers `accepted` or
`refused` like every task. `DANCE_RESULT` is a union on `kind`, each record tagged by its `@Variant`
(how a contract is written: [api-artifacts.md](api-artifacts.md#writing-a-contract)). Its tags join
every other action result's kinds, and each variant needs a record class no other action result uses,
so a tag or class already taken fails the registration.

The factory runs on the bot's game thread when the call arrives. To start nothing, it throws
`BodyTasks.Refusal` with a reason and a message. `tick` runs once a game tick until it answers
`SUCCEEDED` or `FAILED` (with `failure()` as the message), and `stop()` is called when the task is
cancelled or replaced. `mc.bot.status` reports the running task by its method name, and
`mc.bot.cancel` stops it by its `actionId` or with `process: all`.

`StandInTasks` in the testmod registers `testmod.tally`; the test `MobTest.mobRunsARegisteredTask`
runs it on a mob.
