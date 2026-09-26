# Human-built scenes and human-chosen outcomes

> This design has been implemented. The marker block, the scene file format, the verbs and
> the scene provider all exist in the `testmod` source set of the `common` module, and one
> human-built scene ships in the suite. `docs/guide/human-verification.md` is the operating
> manual; this document explains why the pieces are shaped the way they are.

## The problem

Writing an integration scene in Java means describing terrain cell by cell with
`ctx.setBlock`. That is fine for a geometric hypothesis and hopeless for a situation a person
noticed while watching the bot: a particular river bank, a particular overhang, the exact
shape of ground on which the walker did something silly. Reproducing it by hand in code is
slow and usually gets it subtly wrong, and "subtly wrong terrain" is the failure mode that
looks exactly like a bot defect.

A tester who can already see the situation should be able to build it in a running world,
mark the parts that matter, watch the bot attempt it, and decide the outcome — and that work
should turn into an ordinary regression scene that runs afterwards with nobody present.

## What was decided

### The human step is upstream of the automated runs, not a fourth one

A tester builds terrain in a world held open by one of the `stagewright…Hold` tasks, marks it,
runs it in place, and records a verdict. The output is a scene file. From then on the file is
registered by a provider in the testmod and runs as an ordinary `human.*` scene alongside the
`wd.*` family.

Nothing about the existing runs changed, and the human verdict is **mechanically incapable** of
changing an automated one. The verdict is written into the `data` field of a results record, and
`data` is only ever rendered for the console — the verdict logic never reads it. That is stronger
than a convention: there is no code path by which a person's opinion becomes a check result.

### Markers are blocks, and they exist only in development

Marking is done with a single block, `worlddriver:marker`, whose role is a blockstate property:
origin, start, goal, forbid, stand, watch, via, pass, corner. An optional block entity carries a
label and arguments for the roles that need one. The block has no collision box and does not block
motion, so a misplaced marker cannot get in the bot's way — though the runner removes every marker
before running regardless, rather than relying on that.

The testmod is not in either published jar, so none of this reaches a player's game, and no new
registry entry exists between a server running the released mod and a vanilla client.

The scene file contains no markers at all. Saving extracts each marker's position and role into the
JSON and restores the terrain underneath it, so the terrain file holds only vanilla and mod blocks
and can be placed on a server that has never heard of the testmod.

Two of the roles are easy to confuse and the distinction is the reason both exist. `via` is an
instruction — the bot is required to go there, and it is passed through as part of the route. `pass`
is a check — the bot picks its own way and the tester is asserting that a correct route goes through
this cell. To assert "it should go under the water" you put a `pass` below the surface and a `forbid`
on it; to assert "it should dig through this wall" you put a `pass` inside the wall, or a `watch` on
a block that must become air.

### Every operation is a driver verb; the command is a thin caller

`worlddriver.mark` and the `worlddriver.scene.*` family are registered through
`ToolCatalog.registerVerb`, and the slash commands do nothing but translate arguments into one
`DriverApi.route` call. That keeps game-affecting behaviour inside `DriverApi`, as the architecture
requires, and it means the whole workflow is available over RPC and from scripts — a nightly job can
run every local scene and leave the results for somebody to read in the morning, without anyone being
in the game.

The verbs use hidden tool schemas, so they do not appear in the Model Context Protocol tool list.
Every advertised tool's schema is sent in every prompt to every language-model client, and these verbs
are for a human tester, not for the model.

The namespace is `worlddriver.` rather than `mc.`, because `mc.*` is reserved for the core surface and
the extension point admits any other dotted name. A mod's own id is the natural prefix.

### Architectury API became a mandatory dependency

This was the first time the repository registered a block of its own, and there was no registration
machinery at all: no `DeferredRegister`, no `RegisterEvent`, and only Architectury's *build* plugins in
the build scripts. The choice was between writing native registration twice, once per loader, behind a
hand-rolled cross-loader interface, or making the Architectury runtime library a hard dependency and
using `DeferredRegister` directly. The second was chosen and the cost was measured first: three
`modImplementation` lines, two dependency declarations in the mod metadata, and one extra download for
players.

Having taken that dependency, the two loader entry points' paired event handlers — lifecycle, tick,
command, break, place, death, join, leave, chat, and the client-side tick, disconnect, overlay and chat
— merged into a single set of Architectury subscriptions in `common`. That was done as a separate change
immediately afterwards, on the grounds that the moment to collapse a duplication is the moment the thing
that removes it arrives; see `docs/dev/loader-glue.md` for what the merge changed.

`DeferredRegister.register()` has to run while the mod is being constructed, and the testmod has no
construction entry point of its own. So the main source set carries a one-method interface,
`TestContent`, and `WorldDriverCommon.installTestContent()` runs a `ServiceLoader` over it from both
loader constructors. The published jar contains no implementation, the loop is empty, and nothing
happens — the same shape as the scene provider.

That interface lives in the driver's **root package**, not beside its implementations. On NeoForge the
main and testmod outputs become two JPMS modules, and a package present in both is a split package the
module layer refuses to build. Every package in the testmod has to be one the main output does not have.

### The scene file is two files

Terrain is a vanilla `StructureTemplate` NBT, so any server can read it. Everything else — markers,
inventory, configuration overrides, the segments of the journey, the accepted numeric expectations, and the
chunk radius — is JSON beside it.

The chunk radius is computed from the bounding box at save time rather than left at the default. A scene
gets a forced-loading window sized by `Scene.withChunkRadius`, and a write outside that window still
succeeds, because the chunk loads on demand — it just was not waited for during setup. The result is a
scene that passes most of the time and occasionally fails in a way that reads like a bot defect. The
source-level guard against this reads `ctx.setBlock` offsets out of Java source and therefore cannot see
terrain that arrives from an NBT file at run time, so it gained a second check that reconciles the declared
radius against the saved size and origin.

### Three layers of check, in order of how much they need a person

**Hard conditions** come from the markers and are on by default: reach each `via` and each `goal` in turn,
pass through every `pass`, never enter a `forbid`, finish standing on `stand`, and leave every `watch` cell
in the required state. These need nobody: building the scene produces them.

**Numeric ranges** come from the `expect` block, which `accept` writes from an observed run — ticks taken,
replans, recovery hops, digs — with a margin. This is what "the tester chose this outcome" looks like once
it has been recorded: the run a person judged good became the standard.

**The human verdict** is recorded in a local journal and surfaced wherever results are displayed. It is not
committed; what gets committed is the `expect` block that `accept` derived from it.

### A local scene is not a suite scene until it is declared

Scenes in the run directory are registered only when the world is being held open, or when an explicit
property asks for it. On an ordinary run they are not registered at all.

The reason is the undeclared-scene check. Any scene registered under a namespace the manifest already uses,
but absent from the manifest, turns the run red. That check is exactly what should happen to a scene somebody
forgot to declare — and it would also happen to every half-finished scene sitting in a tester's run directory,
for no good reason. Gating the directory on the hold flag keeps the check sharp.

Becoming a suite scene therefore means three things landing together: the file in the testmod's resources, an
entry in the index beside it, and an entry in both loaders' manifests. They have to be in one commit, because
the manifest is part of the judge.

The provider reads an index file rather than scanning the directory, because Java cannot portably enumerate a
directory on the classpath. The precedent for loading scenes from files — the JavaScript scene loader — reads
a run-directory path on the filesystem, which is a different thing.

## What this rules out

There is no recording and no replay in this workflow; the tester watches the bot as it runs, and replay stays
with the existing corpus mechanism. There is no fourth automated pipeline and no change to the verdict logic.
There is no graphical editor: labels are typed as command arguments. And markers are blocks only — a scene
cannot be authored in a world without the testmod, for instance by substituting vanilla marker entities. Each
of these can be added later; none was needed to make the loop work.

## Where to look

- `common/src/testmod/java/net/magicterra/worlddriver/testcontent/` — the marker block and role, the scene
  file model, the reader and writer, the runner, and the verdict journal.
- `common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/scene/HumanScenes.java` — the provider,
  and the local-directory gate.
- `common/src/main/java/net/magicterra/worlddriver/TestContent.java` — the construction-time hook and the
  split-package note.
- `common/src/testmod/resources/scenes/` — the index and the shipped scene.
- `docs/guide/human-verification.md` — how to actually use it.
