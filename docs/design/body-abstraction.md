# One bot layer, three kinds of controlled entity

> This design has been implemented. `Body` is in `game/body/` and its registry in
> `bot/`, the two shipped implementations are `ClientPlayerBody` and
> `ServerPlayerBody`, the testmod adds `LivingBody` and a driveable piglin, and the
> `mc.bot.*` verbs that drive a bot accept a `bot` parameter (called `body` until 2026-10-16).
> Since 2026-10-16 a third shipped implementation, `MobBody`, drives any unmodified vanilla mob.

## The problem

Everything above the pathfinder — the walker, the processes, the reflex chains — was written
against `Player`, and in practice against the client's `LocalPlayer`. That had two costs.

It limited the whole layer to a single entity. There was no way to drive a second entity, and no way to
run the bot's own logic against anything that is not a player.

It also made the server-side player a reimplementation rather than a reuse. The old server-side player
integrated movement by hand: it wrote the inputs, then ran its own transcription of vanilla's
physics. That transcription was over a thousand lines and it mirrored the real thing one defect
at a time — hard-coded jump velocity, no pose updates, the underwater jump and the jump cooldown
missing — and each of those showed up as a row in the fake-player parity table.

## What was decided

### The bot layer sees one interface, and its core is a `LivingEntity`

`Body` replaces the old `Avatar` interface. Its one mandatory member is a `LivingEntity`;
`asPlayer()` returns null for anything that is not a player and callers must check. Locomotion —
movement and jump and sneak and sprint commands, aiming, releasing inputs — is mandatory, because
every controlled entity can move. Everything else is optional — `Equipment`, `Attack`,
`BlockInteraction`, `Bag` and `Menus`, one interface each — because most of the vanilla operations
behind them are `Player`-only: crafting, container menus, `useItemOn` placement, the recipe book,
eating and elytra all route through the player's own menu and game-mode machinery and have no
`LivingEntity` equivalent. That is not a simplification, it is the shape of vanilla, and expressing
it as optional capabilities is what lets a process refuse work cleanly instead of failing at a cast.
They were two groups, `Hands` and `Containers`, until the layering rework split them so that a mob
which can hold or swing but not dig can say so. How the entity moves — size, step and jump height,
speeds, and which of walking, swimming and flying it has — is `Locomotion`, read off its attributes;
the pathfinder merges the move catalog of each mode it has.

A process that needs hands checks for them when it attaches and refuses with a single named reason
if they are absent. Processes that only need to move — following, exploring, running away, and the
intent process behind `goto` — need nothing else.

Dimensions and abilities come from the real entity rather than from an enum. The walker already
branched on step height and jump height; it had simply always been handed the player's values.

### All three bots drive vanilla's own physics

Every bot writes the input fields that live on `LivingEntity` — the movement impulses, the jump
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
  channel. So a bot that has joined the player list but whose connection is not in the listener
  gets `tick()` forever and `doTick()` never.
- `Mob.serverAiStep()` ends by writing the forward impulse, because `Mob.setSpeed` also sets it.
  `Player` does not override `setSpeed`, which is why a move controller attached to a player would
  have to write the impulse itself.

### The server-side player pumps vanilla rather than mirroring it

`ServerPlayerBody` steps a genuinely joined `ServerPlayer` — one placed through the normal join
path, so it is in the level's player list and holds chunk tickets — and each step runs vanilla's
own tick chain: write the inputs, then the player tick, then `doTick()`, then the fall-damage check
and the chunk-source move that a real connection's move handler would have run.

The pump is **not** placed inside the entity's own `tick()`, which is what the obvious precedent
does. That precedent gives a bot exactly one step per server tick, and this repository's tests are
written the other way round: they step a bot many times inside a single server tick, commonly a few
hundred and in one case several thousand. So the pump is a method the driver calls, the level's
entity loop leaves the bot alone, and the pump compensates for the bookkeeping the entity loop would
otherwise have done.

Three consequences were accepted deliberately rather than worked around:

Inputs have to be written the way the client writes them, because vanilla only applies those rules
inside `LocalPlayer.aiStep` — the sneaking and item-use speed multipliers, sinking when sneaking in
water, and the conditions under which a sprint stops. A sprint on a player is carried entirely by the
sprint flag, because `Player.getSpeed` reads an attribute and `setSpeed` does nothing.

Per-step rather than per-tick advancement of hunger, effects, fire, air, cooldowns and item use is
the price of driving the real chain, and it is not hidden. A bot that is not being stepped does not
advance at all: it does not fall, does not get hungry and does not heal. A caller that wants it alive
while waiting steps it while waiting.

One rule could not be copied from the client. The "minor horizontal collision" flag that lets a client
keep sprinting while brushing a wall is only overridden on `LocalPlayer` and is always false on a
server player, so copying the client's stop-sprinting rule verbatim would have dropped the sprint on
every step and lost the sprint-jump distance bonus. The override was ported onto the server-side player
instead.

### The third bot is a mob, and it is not a second pathfinder

`LivingBody` drives any `LivingEntity`, and the testmod supplies the first one: a piglin subclass.
The claim that players use input fields while mobs use navigation is a false dichotomy —
`MoveControl` ultimately writes the same forward impulse — so a mob's downstream is identical to
a player's. The only extra question is who controls the mob's movement this tick, and that is an
explicit mode set on attach and cleared on release, never shared between the two.

Taking over the mob's movement did not need a mixin, which matters because a mixin ships in the released jar.
`Mob.serverAiStep` is final, but everything inside it that fights a driver is replaceable from a
subclass: the brain runs in an overridable method, and the move, jump and look controllers are
protected fields that the driveable piglin swaps in its constructor for versions that do nothing while
driven.

Driving an unmodified vanilla mob turned out not to need a mixin either. `MobBody` takes every goal
off the mob's two goal selectors and keeps them with their priorities, which stops the mob's own AI
without stopping its physics (`setNoAi` would stop both); turning the selectors' control flags off
would not hold, because vanilla turns them back on every few ticks. A mob run by a brain instead of
goals has its walk target erased every tick. The bot's input then goes through the mob's own move
and jump controls: the input is turned into a heading, the mob faces it, and `MoveControl.strafe`
moves it straight ahead. Straight ahead, because `strafe` swaps any sideways or backward input for
forward whenever the next cell is not plain ground. So the mob keeps its own speed and step height.
Two costs come with that: a strafe moves at a quarter of the mob's movement speed per unit of
input, which is too slow for a parkour gap, so a mob's searches plan none; and the input is read in
the mob's own AI step on the next tick, one tick later than a player's.

A mob is taken over by the first order that names it as `entity:<uuid>`, or by
`/worlddriver mob <entity> <name>`; it is handed back, goals restored, by
`/worlddriver mob release <bot>` or when the entity is gone. It is marked persistent on takeover and
stays so, since vanilla has no way to unset that.

A mob has no chunk ticket. Scenes keep it loaded by the arena's own forced-loading window; a mob
driven over the network is refused if its chunk is not loaded, and gives up with a named reason if the
chunk unloads or it dies mid-journey.

### Bots are addressed by name through one optional parameter

`BotRegistry` maps a name to a host. The client player is `self` and is not in the registry at all,
which keeps the existing client path exactly as it was. A server player registers as
`player:<name>`, a vanilla mob as `mob:<name>` or `entity:<uuid>`, and the testmod registers its
piglin as `npc:<name>`.

The `mc.bot.*` verbs that drive a bot take an optional `bot` parameter rather than gaining a parallel
set of verbs, because every verb's schema is sent in every prompt to every model client and a second
verb family would double that cost for nothing. Status always lists the bots.

Four verbs deliberately do **not** take it — equipping, settings, waypoints and playbooks — because
equipping drives client inventory clicks and the other three are client-side or process-wide state, not
a bot's. They reject the parameter in schema validation rather than accepting it and explaining later.

Refusals use one shape and one vocabulary: an unknown name, a removed entity, an unloaded chunk, or a
bot with no hands all come back as a named reason on a refused call.

Parameter parsing was moved out of the client-facing implementation into a class that names no client
types, so a client call and a call for a server-side bot construct the same process from the same code.

## What this rules out

The first version ran the scheduler's reflex layer — automatic eating, automatic swimming, suffocation
escape — only on the client's player, and gave a server-side player and a mob a single user-task chain.
That was lifted on 2026-10-16: every server bot now runs the same `BotBrain` as the client, reflexes
included, and keeps running them while idle. The reflexes that need hands or a player (eating, the
contact and lava escapes) still do nothing on a mob.

The server-side player is a public extension point, not an internal detail. It stays in the released jar
together with its command surface, so a third-party mod can drive one, and renaming or splitting it is
an API change.

Several things were considered and explicitly not done. There is no forked copy of vanilla's navigation
for non-mobs: this project already has a pathfinder and it does not care what kind of entity it is
driving, so forking three vanilla classes would only add a second set of physics to maintain. There is
no god-object parameter bundle: route conditions already have one object and differences between bots already
have a capability record, and merging them would produce the sprawling configuration surface that other
projects in this space are known for. No exception is swallowed; a missing piece of the silent-connection
shim is a failed scene, not a caught null.

## Where to look

- `game/body/Body.java` — the interface, and what is mandatory versus optional.
- `bot/BotRegistry.java` and `Bot.java` — naming and the per-bot process slot.
- `game/body/ClientPlayerBody.java` — the client implementation.
- `game/body/ServerPlayerBody.java` and the joined-player pump beside it — the server implementation.
- `application/BodyRoutes.java` and `application/BodyInteractions.java` — how the `bot` parameter reaches a process and
  how hand operations are performed for a non-client player.
- `bot/VerbOrders.java` — the shared parameter parsing.
- `game/body/MobBody.java` and `bot/MobBot.java` — a vanilla mob, taken over and handed back.
- `common/src/testmod/java/.../stagewright/LivingBody.java` and
  `.../testcontent/DrivenPiglin.java` — the test mob.
- `docs/dev/fake-player-parity.md` — the measured differences between the kinds of bot.
- `docs/design/world-view-parity.md` — the matching story for what each bot's world view believes.
