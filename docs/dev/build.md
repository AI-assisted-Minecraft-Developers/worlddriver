# The build

How the Gradle build is put together: which plugin builds which module, where each loader's classes
come from, and what each published jar holds. For the jars other mods compile against, see
[api-artifacts.md](api-artifacts.md); for the in-game test suites, [testing.md](testing.md).

## Modules and plugins

| Module | Plugin | Minecraft it compiles against | Ships |
|---|---|---|---|
| `:api` | ModDevGradle, vanilla only | vanilla in Mojang names | `worlddriver-api` (two jars, below) |
| `:common` | ModDevGradle, vanilla only | vanilla in Mojang names, with the access transformer | `worlddriver-common` |
| `:fabric` | Fabric Loom (`fabric-loom`) | Mojang names, remapped to intermediary for the shipped jar | the Fabric mod |
| `:neoforge` | ModDevGradle | NeoForge in Mojang names | the NeoForge mod |
| `:api-processor` | plain `java` | — | nothing; it is an annotation processor |

The root `build.gradle` applies the loader plugin to each module and holds what they share: the
version, Java 21 through a toolchain, the licence files, publishing, and the JVM-flag properties
(`-PworlddriverJdwp`, `-PworlddriverVmArgs`, `-PtestkitCoverageAgent`), which it hands to Loom's runs
and to ModDevGradle's runs alike.

`:api` and `:common` use the vanilla build (NeoForm) that NeoForge `neoforge_version` is made from,
`neoform_version` in `gradle.properties`, so their Minecraft is the one NeoForge patches.

### Convention plugins

Build logic that more than one project uses, other than the root's own cross-project settings above,
lives in `build-logic/`, a build the root `settings.gradle` includes for its plugins. Each
`build-logic/src/main/groovy/<id>.gradle` is a plugin a project applies with `id '<id>'` in its
`plugins` block:

| Plugin | Applied by | What it does |
|---|---|---|
| `worlddriver.loader` | `:fabric`, `:neoforge` | compiles `:api`'s and `:common`'s sources into the loader (below) |
| `worlddriver.lint` | the root | Spotless, Checkstyle and PMD ([code-style.md](code-style.md)) |

### Why Loom 1.13

Fabric Loom 1.14 and later need Gradle 9, and StageWright's Gradle plugin cannot run the test suites
there yet: its launch-spec task reads each loader project's run classpath from the root project,
which Gradle 9 refuses, and under Loom 1.19 it also misreads the run tasks' dependency on
`configureLaunch`. Loom 1.13.6 is the newest release that runs on Gradle 8.14. Moving on means a
newer Loom here, Gradle 9 in the wrapper and a Gradle daemon on JDK 25, once StageWright can follow.

## Each loader compiles the shared sources

No jar is merged into another. The `worlddriver.loader` plugin, applied by both loaders, adds `:api`'s and
`:common`'s Java sources to the loader's own compile (running `:api-processor` there), adds
`:common`'s resources directory to the loader's resources, and copies in the generated API files. The
loader's own jar therefore holds every class it ships. The testmod works the same way: each loader's
`testmod` source set compiles `common/src/testmod` beside its own registration of the test content,
and one `META-INF/services` file lists the providers from both.

`:common` still compiles on its own, against vanilla, for its unit tests and for its publication; the
loaders do not use its jar.

`:common`'s resources are a source directory of each loader rather than a copied task output because
Loom treats only the mixin configs it finds in a source directory as the mod's own.

## Access widener and access transformer

Fabric Loom applies only access wideners and ModDevGradle only access transformers, so the same
members are opened twice:

- `common/src/main/resources/worlddriver.accesswidener`, named by `fabric.mod.json` and set as Loom's
  `accessWidenerPath`;
- `common/src/main/resources/META-INF/accesstransformer.cfg`, applied to `:common`'s Minecraft from
  its default place, named explicitly for `:neoforge`, and read by NeoForge from the jar.

`scripts/check_remap_safety.py` fails when the two lists differ.

ModDevGradle normally applies access transformers to the decompiled game and compiles it again. Ours
opens two methods of `AbstractSliderButton` that vanilla overrides as `protected`, which javac then
refuses, so `:common` and `:neoforge` set `disableRecompilation`: the transformer is applied to the
compiled game instead.

## Mixins

The mixin configs list classes from `:common` and `:fabric`. Loom 1.13 does not run the Mixin
annotation processor: it rewrites the names in the mixin annotations when it remaps the jar, so the
Fabric jar has no refmap. NeoForge runs in Mojang names and needs none either.

## Libraries in the shipped jars

- Rhino is shaded and relocated to `net.magicterra.worlddriver.shadow.rhino` by each loader's
  `shadowJar`, from a copy of its classes without its mod metadata.
- netty-codec-http, which Minecraft does not ship, is nested: `include` on Fabric
  (`META-INF/jars/`), `jarJar` on NeoForge (`META-INF/jarjar/`, with `metadata.json`). ModDevGradle
  adds the `jarJar` output only to `jar`, so the NeoForge `shadowJar` takes it explicitly (Shadow 8
  would unpack it; the build uses Shadow 9). In NeoForge development runs the library reaches the game
  through `additionalRuntimeClasspath`.

## Published jars

| Module | Plain artifact | `dev` classifier |
|---|---|---|
| `worlddriver-api` | `:fabric:remapApiJar`: `:api`'s jar remapped to intermediary | `:api`'s jar, Mojang names |
| `worlddriver-common` | `:common`'s jar, Mojang names | the same file |
| `worlddriver-fabric` | `remapJar`, intermediary | Loom's named jar (`build/devlibs`) |
| `worlddriver-neoforge` | `shadowJar`, Mojang names | the same file |

`dev` always means Mojang names; it is what StageWright compiles against. The POMs declare no
dependencies, and no Gradle module metadata is published. `scripts/check_packaging.py` checks the
licence files, POMs, mod metadata and sources jars of all four.

## Run tasks

`:fabric` declares `runClient`, `runServer` and `runLabClient` (the lab, see
[running-the-client.md](running-the-client.md)); `:neoforge` declares `runClient` and `runServer`.
StageWright's plugin adds the suites' runs to both. On NeoForge it gives them a copy of the
`worlddriver` mod with the testmod in it, so no other run sees the test content.
