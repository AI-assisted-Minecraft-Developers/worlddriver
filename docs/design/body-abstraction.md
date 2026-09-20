# One bot layer, three kinds of body

> This design has been implemented. `Body` and its registry are in
> `bot/body/`, the two shipped implementations are `ClientPlayerBody` and
> `ServerPlayerBody`, the testmod adds `LivingBody` and a driveable piglin, and the
> `mc.bot.*` verbs that drive a body accept a `body` parameter.

## The problem

Everything above the pathfinder — the walker, the processes, the reflex chains — was written
against `Player`, and in practice against the client's `LocalPlayer`. That had two costs.

It made the whole layer single-body. There was no way to drive a second entity, and no way to
run the bot's own logic against anything that is not a player.

It also made the server-side body a reimplementation rather than a reuse. The old server body
integrated movement by hand: it wrote the inputs, then ran its own transcription of vanilla's
physics. That transcription was over a thousand lines and it mirrored the real thing one defect
at a time — hard-coded jump velocity, no pose updates, the underwater jump and the jump cooldown
missing — and each of those showed up as a row in the fake-player parity table.

## What was decided

### The bot layer sees one interface, and its core is a `LivingEntity`

`Body` replaces the old `Avatar` interface. Its one mandatory member is a `LivingEntity`;
`asPlayer()` returns null for anything that is not a player and callers must check. Locomotion —
movement and jump and sneak and sprint commands, aiming, releasing inputs — is mandatory, because
every body has legs. Hands and containers are optional, because the vanilla operations behind them
are `Player`-only: crafting, container menus, `useItemOn` placement, the recipe book, eating and
elytra all route through the player's own menu and game-mode machinery and have no `LivingEntity`
equivalent. That is not a simplification, it is the shape of vanilla, and expressing it as two
optional capability groups is what lets a process refuse work cleanly instead of failing at a cast.

A process that needs hands checks for them when it attaches and refuses with a single named reason
if they are absent. Processes that only need to move — following, exploring, running away, and the
intent process behind `goto` — need nothing else.

Body dimensions and abilities come from the real entity rather than from an enum. The walker already
branched on step height and jump height; it had simply always been handed the player's values.

### All three bodies drive vanilla's own physics

Every body writes the input fields that live on `LivingEntity` — the movement impulses, the jump
flag, the sneak flag — and lets vanilla's `travel()` compute the result. Nothing sets velocity
directly, and nothing teleports except as a stuck-recovery last resort.

This is what every comparable project does, and reviewing them was how the old hand-written physics
was recognised as the outlier rather than as the norm.

The mandatory vanilla facts that shape the implementation, checked against a decompiled 1.21.1:

- `navigation`, `moveControl`, `jumpControl` and `lookControl` are all on `Mob`, not on
  `LivingEntity`, and the navigation constructor is typed to `Mob`. Neither a player nor an armour
  stand is a `Mob`.
- The input fields, `setJumping`, `jumpFromGround()`, `travel()` and `aiStep()` are all on
  `LivingEntity`. The input setters are only on `Mob`, so a player's fields are written directly.
- `ServerPlayer.tick()` does not call `super.tick()`; the physics is in `doTick()`, and the only
  caller of `doTick()` in the whole tree is the connection's own tick, driven by a real network
  channel. So a body that has joined the player list but whose connection is not in the listener
  gets `tick()` forever and `doTick()` never.
- `Mob.serverAiStep()` ends by writing the forward impulse, because `Mob.setSpeed` also sets it.
  `Player` does not override `setSpeed`, which is why a move controller attached to a player would
  have to write the impulse itself.

### The server body pumps vanilla rather than mirroring it

`ServerPlayerBody` steps a genuinely joined `ServerPlayer` — one placed through the normal join
path, so it is in the level's player list and holds chunk tickets — and each step runs vanilla's
own tick chain: write the inputs, then the player tick, then `doTick()`, then the fall-damage check
and the chunk-source move that a real connection's move handler would have run.

The pump is **not** placed inside the entity's own `tick()`, which is what the obvious precedent
does. That precedent gives a body exactly one step per server tick, and this repository's tests are
written the other way round: they step a body many times inside a single server tick, commonly a few
hundred and in one case several thousand. So the pump is a method the driver calls, the level's
entity loop leaves the body alone, and the pump compensates for the bookkeeping the entity loop would
otherwise have done.

Three consequences were accepted deliberately rather than worked around:

Inputs have to be written the way the client writes them, because vanilla only applies those rules
inside `LocalPlayer.aiStep` — the sneaking and item-use speed multipliers, sinking when sneaking in
water, and the conditions under which a sprint stops. A sprint on a player is carried entirely by the
sprint flag, because `Player.getSpeed` reads an attribute and `setSpeed` does nothing.

Per-step rather than per-tick advancement of hunger, effects, fire, air, cooldowns and item use is
the price of driving the real chain, and it is not hidden. A body that is not being stepped does not
advance at all: it does not fall, does not get hungry and does not heal. A caller that wants it alive
while waiting steps it while waiting.

One rule could not be copied from the client. The "minor horizontal collision" flag that lets a client
keep sprinting while brushing a wall is only overridden on `LocalPlayer` and is always false on a
server player, so copying the client's stop-sprinting rule verbatim would have dropped the sprint on
every step and lost the sprint-jump distance bonus. The override was ported onto the server body
instead.

### The third body is a mob, and it is not a second pathfinder

`LivingBody` drives any `LivingEntity`, and the testmod supplies the first one: a piglin subclass.
The claim that players use input fields while mobs use navigation is a false dichotomy —
`MoveControl` ultimately writes the same forward impulse — so a mob body's downstream is identical to
a player's. The only extra question is whose legs they are this tick, and that is an explicit mode
set on attach and cleared on release, never half and half.

Taking the legs did not need a mixin, which matters because a mixin ships in the released jar.
`Mob.serverAiStep` is final, but everything inside it that fights a driver is replaceable from a
subclass: the brain runs in an overridable method, and the move, jump and look controllers are
protected fields that the driveable piglin swaps in its constructor for versions that do nothing while
driven. A mixin is still the answer for driving an unmodified vanilla mob, and is deliberately left
until there is one.

A mob body has no chunk ticket. Scenes keep it loaded by the arena's own forced-loading window; a mob
driven over the network is refused if its chunk is not loaded, and gives up with a named reason if the
chunk unloads or it dies mid-journey.

### Bodies are addressed by name through one optional parameter

`BodyRegistry` maps a name to a host. The client body is `self` and is not in the registry at all,
which keeps the existing client path exactly as it was. A server player body registers as
`player:<name>`, and the testmod registers mobs as `npc:<name>`.

The `mc.bot.*` verbs that drive a body take an optional `body` parameter rather than gaining a parallel
set of verbs, because every verb's schema is sent in every prompt to every model client and a second
verb family would double that cost for nothing. Status always lists the bodies.

Four verbs deliberately do **not** take it — equipping, settings, waypoints and playbooks — because
equipping drives client inventory clicks and the other three are client-side or process-wide state, not
a body's. They reject the parameter in schema validation rather than accepting it and explaining later.

Refusals use one shape and one vocabulary: an unknown name, a removed entity, an unloaded chunk, or a
body with no hands all come back as a named reason on a refused call.

Parameter parsing was moved out of the client-facing implementation into a class that names no client
types, so a client call and a server-body call construct the same process from the same code.

## What this rules out

The scheduler's reflex layer — automatic eating, automatic swimming, suffocation escape — does **not**
run on non-client bodies. Those exist because a human and a driver share one client, and the first
version deliberately gives a server body and a mob body a single user-task chain instead. The chain
interface takes a `Body` so that the reflex layer *can* move there later; that is not the same as it
having moved.

The server body is a public extension point, not an internal detail. It stays in the released jar
together with its command surface, so a third-party mod can drive one, and renaming or splitting it is
an API change.

Several things were considered and explicitly not done. There is no forked copy of vanilla's navigation
for non-mobs: this project already has a pathfinder and it does not care what kind of entity it is
driving, so forking three vanilla classes would only add a second set of physics to maintain. There is
no god-object parameter bundle: route conditions already have one object and body differences already
have a capability record, and merging them would produce the sprawling configuration surface that other
projects in this space are known for. No exception is swallowed; a missing piece of the silent-connection
shim is a failed scene, not a caught null.

## Where to look

- `bot/body/Body.java` — the interface, and what is mandatory versus optional.
- `bot/body/BodyRegistry.java` and `BodyHost.java` — naming and the per-body process slot.
- `bot/body/ClientPlayerBody.java` — the client implementation.
- `bot/sim/ServerPlayerBody.java` and the joined-body pump beside it — the server implementation.
- `api/BodyRoutes.java` and `api/BodyInteractions.java` — how the `body` parameter reaches a process and
  how hand operations are performed for a non-client body.
- `bot/VerbOrders.java` — the shared parameter parsing.
- `common/src/testmod/java/.../stagewright/LivingBody.java` and
  `.../testcontent/DrivenPiglin.java` — the mob body.
- `docs/dev/fake-player-parity.md` — the measured differences between bodies.
- `docs/design/world-view-parity.md` — the matching story for what each body's world view believes.
