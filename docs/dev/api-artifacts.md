# Generated API artifacts

Every typed method is described once, by a `MethodSpec` in `api/methods/`: its name, its parameter
and result types, and how it behaves beside other calls. The build turns those descriptions into
the files scripts and clients use, so none of them is written by hand and none can drift from the
code.

## What is generated

`./gradlew :common:generateApiArtifacts` writes into `common/build/generated/worlddriver-api/`:

| File | What it is | Where it ends up |
|---|---|---|
| `worlddriver.d.ts` | The types, the `WorldDriver.Methods` table and the `Driver` namespaces | `worlddriver-api/` in the mod jar, and the api's `artifacts` zip |
| `driver-function.d.ts` | StageWright's `driver(method, params)` over the same table | the same |
| `driver-api.js` | One named wrapper per method, `Driver.bot.clearArea(params)` | `data/worlddriver/scripts/generated/`, loaded right after `prelude.js` |
| `methods.json` | Each method's input and output schema and its execution profile | `worlddriver-api/` in the mod jar, and the api's `artifacts` zip |
| `events.json` | Each built-in event type's payload schema, severity and trust, from `EventCatalog` | the same |
| `api-reference.md` | The same two, for a person | the same |
| `skill-methods.md` | The agent skill's method manual: every method, every setting key with its range and default, every event type, each type declared once | committed as `.agents/skills/worlddriver-rpc/references/methods.md` |

`processResources` depends on the task, so a normal build always ships current files. The
generated files are not committed: a change to the API shows up in review as a change to the
`MethodSpec` that caused it.

The skill manual is the exception, because an agent reads it without building. After changing a
contract, run `./gradlew :common:updateSkillManual` and commit the result; `check` runs
`checkSkillManual`, which fails while the committed copy differs from the generated one. What the
contracts cannot say (the envelope, availability, pitfalls, the test-only methods) is written by
hand beside it in `references/tips.md`.

## The API jar

Another mod that calls the driver, registers a driver for a mob, or adds a body task compiles
against `net.magicterra:worlddriver-api`, not against the whole mod. It is the `:api` module: the
classes under `api/`, the generated bindings included. `:common` depends on it and
each loader compiles its sources into the loader jar, so the module boundary is what keeps the api
from using the rest of the driver. It is published like the other modules, under the mod's version,
in two mappings. `:api` itself compiles in Mojang names, and that jar is the `dev` one; `:fabric`'s
`remapApiJar` task remaps it to intermediary for the plain one ([build.md](build.md)):

| Artifact | Minecraft names | For |
|---|---|---|
| `worlddriver-api` | intermediary | Fabric with Loom: `modCompileOnly`, which remaps it to whatever the mod uses, Mojang or Yarn |
| `worlddriver-api:dev` | Mojang | NeoForge (ModDevGradle, NeoGradle), which has no intermediary, and any other plain `compileOnly` against Mojang names |
| `worlddriver-api:sources` | — | an IDE |
| `worlddriver-api:artifacts@zip` | — | the generated files above, for a client that is not Java |

Only `api/game` names Minecraft types, so the two jars differ only there; a consumer still needs
Minecraft (and DataFixerUpper, Gson) on its compile classpath, which a mod build has anyway, and the
POM declares no dependencies. At run time the classes come from the installed WorldDriver mod, so a
consumer depends on the api for compiling only and must not bundle it.

## Why it runs without Minecraft

The generator is `:api`'s `gen` source set, which the jars leave out. It runs on the compiled classes of
`:api` plus DataFixerUpper and its four libraries, and nothing else. It loads `CoreMethods`, which lists
only the descriptions. A description that reached into the game (named a client class, needed a registry)
fails the build here, which is the check that keeps the contract layer separate from the game. The
settings' defaults come from the settings records in `api/methods/setting/`, which name no game class
either. `:common` takes the output from there: it checks the declarations and copies them into the mod jar.

## Writing a contract

A contract is a Java record. Its wire type is read off the declaration the first time it is used, by
`ApiTypes.record(X.class)` or `MethodSpec.of(name, Params.class, Result.class, description)`.

- Each component is a field. An `Optional` one may be absent; one with `@Default("…")` takes that value
  when absent (the text itself for a string, JSON for anything else); any other is required.
  `@Wire("…")` gives a wire name that is not a Java name.
- `@Doc` on a component describes the field; on a record, an enum or an annotation it describes the type.
- Rules about a value sit on its type: `@Range`, `@Length`, `@Pattern`, `@OneOf`, `@OnlyTrue`,
  `@OnlyObject`, `@AsString` (a 64-bit integer written as a decimal string) and `@TsAlias`. A rule used in
  several places is an annotation of its own that carries them, such as `CommonTypes.ResourceId` or
  `Responses.BotRef`.
- `@Named` gives a record or an enum its name in the TypeScript declarations. An enum implements
  `WireName`, which spells its constants.
- A sealed interface marked `@Tag("…")` is a union of its records, each marked `@Variant("…")`. A union
  of only some records is `ApiTypes.union(name, tag, records…)`.
- A rule across fields is the record's `check`, from `SelfChecked`.
- A shape no annotation can state, such as an enum whose values are computed when the class loads, is
  a constant built with the factories at the end of `ApiTypes`, which a component names with `@As`.

A mistake in the annotations fails when the type is first built; `RecordTypeTest` builds every
contract class, so it fails the build.

## Adding a method

1. Write its parameter and result records in its family's `*Contracts` class under
   `api/methods/<family>/`, ending in a `MethodSpec` constant.
2. Add the constant to `CoreMethods.all()` in `api/methods/`.
3. Bind a handler in the application and add it to the core batch.
4. Delete whatever hand-written wrapper `prelude.js` had for it; the generated one replaces it.

A body-task method answers with `Responses.response(...)` and contributes its result union to
`ActionResults`, which is what `mc.wait.result` reads an action of any kind with.

## Checking the declarations

`./gradlew :common:checkApiTypes` compiles `common/src/apiTypes/usage.ts` against the generated
declarations with a pinned TypeScript. The file holds calls that must compile and calls marked
`@ts-expect-error` that must not. It runs as part of `check`; Gradle downloads a pinned Node for it,
so none is needed on the PATH. TypeScript cannot check everything the server does (an integer range, the full body-ref
syntax); those stay runtime checks.

## Methods registered at run time

StageWright's `mc.test.*` and any other mod's methods are registered when the game runs, so the
build-time wrappers cannot include them. A script scope adds a named wrapper for each of them from
the registry when it is created, without replacing anything that already exists; an extension ships
its own declarations that extend `WorldDriver.Methods` and the `Driver` namespace interfaces.
