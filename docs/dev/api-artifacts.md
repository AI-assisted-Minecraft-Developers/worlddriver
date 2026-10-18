# Generated API artifacts

Every typed method is described once, by a `MethodSpec` in `api/methods/`: its name, its parameter
and result types, and how it behaves beside other calls. The build turns those descriptions into
the files scripts and clients use, so none of them is written by hand and none can drift from the
code.

## What is generated

`./gradlew :common:generateApiArtifacts` writes into `common/build/generated/worlddriver-api/`:

| File | What it is | Where it ends up |
|---|---|---|
| `worlddriver.d.ts` | The types, the `WorldDriver.Methods` table and the `Driver` namespaces | `worlddriver-api/` in the jar |
| `driver-function.d.ts` | StageWright's `driver(method, params)` over the same table | `worlddriver-api/` in the jar |
| `driver-api.js` | One named wrapper per method, `Driver.bot.clearArea(params)` | `data/worlddriver/scripts/generated/`, loaded right after `prelude.js` |
| `methods.json` | Each method's input and output schema and its execution profile | `worlddriver-api/` in the jar |
| `api-reference.md` | The same, for a person | `worlddriver-api/` in the jar |

`processResources` depends on the task, so a normal build always ships current files. The
generated files are not committed: a change to the API shows up in review as a change to the
`MethodSpec` that caused it.

## Why it runs without Minecraft

The generator's classpath is the compiled classes plus DataFixerUpper and its four libraries, and
nothing else. It loads `CoreMethods`, which lists only the descriptions. A description that reached
into the game (read `BotConfig`, named a client class, needed a registry) fails the build here, which
is the check that keeps the contract layer separate from the game.

## Adding a method

1. Write its records and `ApiType`s in its family's `*Contracts` class under `api/methods/<family>/`,
   ending in a `MethodSpec` constant.
2. Add the constant to `CoreMethods.all()` (in `application/`, because `mc.bot.setting`'s contract,
   `bot/SettingsContracts.java`, is built from the bot's settings table, which `api/` may not import).
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
