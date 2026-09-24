# The playthrough ladder

`wd.journey*` is one bot climbing from an empty inventory at world spawn to a dead ender
dragon. It is not one of the gate tasks: a climb takes far longer than a gate, so it arms
behind its own property, filters to its own scene family, and is never part of `build`. Because
a filtered run skips manifest reconciliation, no rung appears in the expected-scenes manifests;
only `wd.journeyArmed`, which registers in every run, is listed.

## Running it

It is Fabric-only, in three topologies. Each has its own run directory, because the ladder plays
its world — it fells trees, digs shafts and pours lava — so two ladders sharing a directory
would each measure the other's leftovers:

```bash
./gradlew :fabric:runJourneyServer                    # headless; no client exists at all
./gradlew :fabric:runJourneyIntegratedServer          # a real client hosts the world, one JVM
./gradlew :fabric:runJourneyDedicatedServerWithClient # a real client joins over a socket, two JVMs
```

They write to `fabric/run-journey`, `fabric/run-journey-integrated`,
`fabric/run-journey-with-client` and `fabric/run-journey-joining-client`.

The third starts its companion client for you from the same build service the gate task uses,
so Gradle kills it on every exit path. Read `fabric/run-journey-with-client/companion-client.log`
first when a run seems to hang: the server holds its suite back until a player joins and nothing
times that out, so a companion that died during class transformation leaves the server waiting
forever with a healthy-looking log of its own. Its port is deliberately not the gate companion's,
so a ladder is never joined by, and never refuses to start beside, somebody else's gate run.

## Which bot climbs

**The integrated topology climbs on the client's real player**, because verifying on an
integrated server needs a real player to be the subject — a rung that spawned an invulnerable
fake player beside a real player would be testing the wrong one. `JourneyRig.spawnBody()` therefore
adopts the player already present, forcing survival mode, an empty inventory and world spawn,
irreversibly; never point it at a save you care about. That player takes fall damage, starves,
drowns, dies, respawns and earns advancements, because it is a player who joined. The other two
topologies keep the fake player, and the joining one does so by construction rather than by
omission: its client bot lives in the other process, and the object that seam passes cannot
cross a socket.

## How one rung drives either player

A process has one tick method taking a `Body`; the client tick chain hands it a client player and
the server tick a server-side player, so one process object drives a `LocalPlayer` on the client
tick and a joined `ServerPlayer` on the server tick. A rung builds the process and hands it to
`rig.drive`; only the helm changes. Every path that starts a process run goes through
`JourneyRig.startLeg`, and that is load-bearing: registering an adopted driver with the
server-side avatar manager would have the server tick a player its own client is moving, which
the client then contradicts with a movement packet every tick. The server-side player wrapper
refuses such a player, so that mistake throws in the server tick instead of drifting.

**The helm has two halves and both must be routed.** The paragraph above is about the per-tick
process runs. Single-shot actions — hold an item, aim, right-click, place — are a second population of
call sites, and routing them is a separate decision. They go through `JourneyRig.avatar()`, which
picks the client avatar under the real-player helm, mirroring `startLeg`. **A new rung must use
`rig.avatar()`, never `rig.body().avatar()`**; the second writes the server's copy of quantities
vanilla lets only the client own, which produces two unrelated values rather than a race.

A small number of sites deliberately stay on the server side, and reading them as oversights
would break things. The client's break hold only presses a keybind, so a helper whose contract is
that a specific cell opened within the call has to stay server-side or become a silent no-op. The
client's "can break" predicate is unconditionally true, so routing it produces an always-true
check, worse than deleting it. The place tally is not on the `Body` interface at all.

**The ladder cannot judge this seam.** The headless topology has no client, so the whole
question is unreachable there and every rung passes regardless. Judge changes to the seam with
the scenes written for it instead, and note what they do not prove: they run entirely on the
server thread, so passing means the mechanism is right, not that the threading is safe.
Originating single-shot actions from the client tick chain is the coherent fix and is not built;
the client-avatar accessor carries the open-defect note.

**What the server thread must never do here is wait.** `DriverApi`'s `awaitMs` and the
client-hop helper both block the caller until the client answers, and the caller is the server
thread the client is ticking against. Starts are fire-and-forget; completion is polled from the
scene's own predicate.

## Reading a rung's result

Because a topology varies more than one thing, every rung records three keys on every exit path,
including a skip, and those are what make two result rows comparable:

| Key | What it says |
|---|---|
| `journey.topology` | Which of the three, read off the running game rather than echoed from a system property, so a launch that did not do what it promised cannot make the row lie |
| `journey.body` | Which bot is climbing: real, joined or fake; its class, whether it is in the player list, whether it is invulnerable, its game mode |
| `journey.steer` | Which helm advanced the process runs. It does not cover the single-shot actuations, which follow `JourneyRig.avatar()`, so do not read it as a statement about the whole bot. It is separate from `journey.body` on purpose: the integrated run swaps both at once, and two arms are only readable when they differ in one variable |

`journey.helm.endings` is written only under the real-player helm and only as process runs end. Read it
before blaming a rung: the chain clears its process for several different reasons and the busy
flag goes false for all of them alike.

**The minted player being in the player list is load-bearing, on the client topologies too.**
Every server-side player joins through `PlayerList.placeNewPlayer`. The player list is per level and a
human client never leaves the overworld, while the later rungs ask the nether's list for spawner
activation and the end's for the dragon fight. A client standing at world spawn contributes to
neither.

## One rung at a time

One rung at a time, with its preconditions staged by hand, is `wd.rehearse*` — a separate family,
deliberately unable to be read as a climb. It runs under `:fabric:runRehearsalServer` and
`:fabric:runRehearsalIntegratedServer`, writes to `fabric/run-rehearsal` and
`fabric/run-rehearsal-integrated`, and takes Gradle properties to select the rung and its
conditions.
