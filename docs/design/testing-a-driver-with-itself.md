# Testing a driver with itself

## The problem

The thing under test is a driver: its whole job is to move a body, read a world and press
buttons. A test framework for it needs to do all of those, and the only implementation of all
of those is the driver.

That circularity is not avoidable by extracting the input injection and the screen reading into
the test framework, because those *are* the product. So the framework depends on the driver and
never the other way round — and the risk that creates has to be managed rather than designed
away.

The risk is specific: if a scene sets itself up using the same code path it is about to test,
a defect in that path can make the scene pass. The framework cannot catch this, because the
framework is downstream of the same code.

## What was decided

### The driver's surface is split in two, by role rather than by module

An **instrument face** is the part a scene may use to build a situation and to read the result:
teleporting a body, filling blocks, giving items, reading positions and inventories, injecting a
key or a click, reading the open screen.

A **behaviour face** is the part that is the thing under test: walking, mining, crafting,
fighting, escaping — everything the driver does autonomously.

**Setup may never use the behaviour face.** A scene that walks the body to its starting position
is testing its own setup, and if walking is broken that scene fails for a reason that has nothing
to do with what it claims to check. A scene puts the body where it wants it and then asks the
behaviour face for exactly one thing.

The split is a discipline over one API, not two APIs. Making it two would mean two dispatch paths
and two sets of thread rules for the same operations, which is a worse failure than the one it
prevents.

### The instrument face is guarded from outside itself

The operations a scene relies on for setup are checked by a separate suite that speaks the raw
WebSocket protocol, out of process, and does not use the scene harness's assertion machinery at
all.

That placement is the whole value. If the instrument checks lived inside the harness, a defect in
the harness could pass both the scenes and the checks that were supposed to validate the scenes.
Outside it, three independent things have to break at once to produce a false pass: the driver, the
harness, and a bare protocol client that shares no code with either.

### The framework owns orchestration, and the results file is the verdict

The driver's scenes run on an ordinary dedicated server rather than inside the game's built-in
test-server shell, which is the decision that removed a whole family of scheduling problems and let
both mod loaders share one entry point. The verdict comes from the machine-readable results file and
from nothing else — not from an exit code, not from a count computed independently of the engine.

Registered and executed are reconciled against a checked-in manifest, so a scene that registers and
never runs cannot count as a pass. That reconciliation is why the manifest is part of the judge: a
scene and its manifest entry belong in the same change, and adding one without the other turns the
run red on purpose.

## Why the framework exists at all

Nothing available could have been used. Fabric's client-game-test module requires a Minecraft version
newer than the one this mod targets, is loader-specific, and is absent from the Fabric API build in
use here; NeoForge has no client-side test facility at all. Building was not a preference.

The framework was subsequently split into its own repository, **StageWright**, and this mod now
consumes it as published Maven artifacts. The dependency direction is unchanged and is fixed:
StageWright compiles against this repository's common module, and this repository never compiles
against StageWright's.

## What this rules out

Test code does not ship. Neither published jar contains any scene or any part of the framework; the
scenes live in a separate source set that is packaged only for development runs.

A scene may not reach for the behaviour face to arrange its own preconditions, and a reviewer should
treat a scene that does as broken regardless of its colour.

A capability the harness needs is added to the driver as an instrument operation, used by the framework,
and guarded by the out-of-process contract suite — not added to the framework as a private back door
into the game.

## Where to look

- `common/src/testmod/java/net/magicterra/worlddriver/bot/stagewright/` — this repository's scenes and
  the harness glue they use.
- `scripts/stagewright/expected-scenes-fabric.txt` and `expected-scenes-neoforge.txt` — the manifest
  that the reconciliation judges against.
- `common/src/main/resources/data/worlddriver/scripts/validation/` — the cross-transport checks.
- `docs/dev/testing.md` — how to run any of it.
- StageWright's own documentation — the topologies, the contracts and the Gradle plugin.
