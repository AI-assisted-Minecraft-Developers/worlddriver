# Bot parity

WorldDriver drives a bot through a live world. That bot is sometimes the player of a
running game client, sometimes a player the server joined to itself with no client behind
it, and sometimes a mob that is not a player at all. This document records, behaviour by
behaviour, where a driven bot matches a player connected over a network and where it does
not.

It exists because assuming parity that is not there has produced bugs repeatedly, and
because a scene that passes on one kind of bot proves nothing about another. A reading
taken on a headless bot is a reading about that bot.

**This document is maintained alongside the code.** A change that alters what a bot does
is expected to update the corresponding row in the same change. A row with no code behind
it is worse than a missing row, because it is believed.

## The bots

Everything the walker and the per-verb processes actuate goes through
[`Body`](../../common/src/main/java/net/magicterra/worlddriver/bot/body/Body.java), which
exposes locomotion and the look. What only a bot with an inventory has is behind two
optionals, `Body.hands()` and `Body.containers()`; a process that needs either asks at the
top of its tick and refuses the order with `no_hands` when it is absent.

There are three implementations, and one of them is used in two arrangements that differ
enough to count separately. Class paths below are relative to
`common/src/main/java/net/magicterra/worlddriver/`, or to
`common/src/testmod/java/net/magicterra/worlddriver/` where a row says test sources.

| Bot | Class | Underlying entity | In `level.players()` |
|---|---|---|---|
| Client player | `bot/body/ClientPlayerBody.java` | the client's own `LocalPlayer` | yes, as the person's player |
| Joined player | `bot/sim/ServerPlayerBody.java` over `bot/sim/JoinedPlayerBodies.JoinedBody` | a `ServerPlayer` the server joined to itself | yes |
| Adopted player | `bot/sim/ServerPlayerBody.java` over a client's own `ServerPlayer` | the server-side half of a connected player | yes |
| Mob | `bot/stagewright/LivingBody.java` (test sources) | a `DrivenPiglin`, a mob | no — it is not a player |

**The client player** writes the player's own `AvatarInput` rather than the shared
keybinds, so a process cannot clobber keys a person is holding. Everything else it does is
vanilla's client code: `mc.gameMode.attack`, `continueDestroyBlock`, `handlePlaceRecipe`,
`mc.hitResult`. Its parity with a real player is not a property to be maintained; it *is*
a real player, driven by something other than a hand on a keyboard.

**The joined player** is the one this document is mostly about. `ServerAvatarBodies` hands
out nothing else. It is a `ServerPlayer` put through `PlayerList.placeNewPlayer` with a
connection that goes nowhere, so the server treats it as a player that has arrived: it is
in the player list, registered with the `ChunkMap`, carries the inventory-menu listener
that awards advancements, and fired the loader's login event on the way in. A bot a
driver does not step stands still — `JoinedBody.tick()` only records that the level's
entity loop came, and `JoinedBody.pump` does the work.

**The adopted bot** is the same `ServerPlayerBody` wrapped around a `ServerPlayer` that a
person's client is already driving, which the integrated-server topology does through
`JourneyRig`. It is the only arrangement whose connection reaches a live client, so every
assumption of the form "this bot's connection discards whatever is sent through it" is
false on it.
`ServerPlayerBody.step()` refuses to pump such a bot with an `IllegalStateException`: its
own connection already ticks it, and pumping would tick it twice.

**How a bot is addressed.** Every `mc.bot.*` verb takes an optional `body` parameter.
Absent, blank or `self` routes the call to the client's own `BotApi`, exactly as before the
parameter existed; any other value names an entry in `BodyRegistry` and routes to
`BodyRoutes`, which never touches the client bot. On a dedicated server that is the only
`mc.bot.*` code that runs, and `mc.bot.status` — bound straight to the bot route — answers
there with no client attached. Bots are registered by whoever creates them:
`/worlddriver server spawn <name>` registers `player:<name>`, the test sources register
`npc:<name>`, and the registry is emptied when the server stops. So the surface is not
client-only, and a statement about what "the API" can reach has to say which of the two
routes it means.

**The mob** answers a different question. It is not a player and is not trying to be
one; what it tests is whether the same walker and the same pathfinder can carry a
non-player entity across terrain a player crosses. It has no hands and no menus, and
the planner is kept from pricing digs and placements by the world view rather than by a
flag: over a bot that is not a player, every break is priced as impossible and no block
counts as placeable.

There are no fake players left. NeoForge's `FakePlayer`, a Fabric copy of it, and the
system property that chose between them and joined players were deleted together once every
gate and every ladder topology already ran on joined players. The name of this document is
older than that decision; it survives because the question it answers did not change, only
the bot it asks about. A comment or a scene that still speaks of a factory bot or a fake
player is describing what was replaced.

## Which bot a scene runs on

The rule divides by *topology*, not by what a scene is testing, and it is enforced in one
place: `SceneBody` in the test sources, which every scene goes through to obtain a bot.

- **Dedicated server.** Headless joined players. There is no client, so there is nothing
  else to drive.
- **Integrated server with the client half of the driver in the same process.** The
  client's own player, or the scene skips and records where its coverage lives. Minting a
  headless player beside a person's player is neither, and `SceneBody` refuses it.
- **Dedicated server with a separate client process.** Headless joined players again. The
  client is in the other process and a running process object cannot cross a socket.
- **Mob bots are legal on every topology.** The rule is about headless *player* bots on
  a server a client hosts; a client in that world simply sees a mob.

The predicate behind the refusal asks only about the shape of the JVM — is this a client
hosting its own world, and is the client half of the driver in this process — and never
whether a person has actually arrived. Both halves of that question are settled before the
first scene runs, so the set of skips is the same on every run. Asking "is a human in the
player list right now" would make it depend on how fast the machine booted.

A skip is not coverage. The reconciliation that has to hold after any change here is that
every scene skipped for this reason on the integrated gate is executed on a dedicated one.
See [testing.md](testing.md) for how that is checked.

## What produces parity where it exists

Four mechanisms account for nearly every row marked `Parity` below.

**Joining rather than copying.** The earlier server-side player was a `ServerPlayer` that had
never been *placed*, and everything missing from it was repaired by hand-copying one more
piece of vanilla into a mirror of `Player.tick()`. That list only ever grew, because it was
maintained by discovering what was absent. `PlayerList.placeNewPlayer` installs all of it
at once: the inventory-menu listener that awards advancements, membership of
`ServerLevel.players()` so the level keeps ticking and mob AI can see the bot, `ChunkMap`
registration so it loads the chunks it walks into, and the loader's login event.

**Pumping vanilla's own tick chain.** `JoinedBody.pump` runs, in a connected player's
order: this step's input, `ServerPlayer.tick()`, `doTick()` — which enters `Player.tick`
and so runs `baseTick`, item use, equipment attributes, `aiStep` with its jump gate and
jump cooldown, `travel`, food and pose — and then the tail of the server's move-packet
handler, which is where a server learns that a player moved: the fall-distance check, the
known movement, the impulse-context reset, and `ChunkMap.move`. The handler's movement
statistics are deliberately not repeated there, because `ServerPlayer.travel` has already
counted the same displacement and a second call doubles both the statistics and the food
that swimming and sprinting cost.

**Porting the client's half of `aiStep`.** A server never turns input into movement for a
player; the client does that and sends the result. A bot that is its own client has to do
it, so `JoinedBody.aiStep` carries the crouch decision and its speed scale, the scale while
an item is in use, the push out of a block a corner of the bot is inside, the rules that
stop a sprint, the sink while sneaking in water, and the client's own
`isHorizontalCollisionMinor` test — the angle between where input pushed and where the move
went, under eight degrees. A port is a copy, and copies drift; this is the largest single
maintenance liability in the parity story.

**A connection that is up and goes nowhere.** `SilentConnection` reports
`isConnected() == true` and discards every packet. Reporting itself disconnected would not
drop packets, it would queue them in `pendingActions` forever. It carries a real netty
`EmbeddedChannel` because NeoForge's join path stores the connection type as a channel
attribute and dereferences it: without a channel, the armed NeoForge suite fell to 76
executed scenes, every bot-minting scene reporting the same null dereference.

## The boundary table

In the tables, *a driven bot* means the server-side player: the joined player, or the adopted
one where a row says so. The client player is named explicitly wherever it behaves
differently, and the mob has a group of its own at the end.

A status established from a scene result was established under the scene baseline, which is
not the configuration a live client runs. `BotConfig.allowBreak` and `BotConfig.allowPlace`
are both on by default, so a `goto` on a live client will mine and bridge its way through;
`BotConfig.applyGameTestBaseline()` pins both off, along with several movement settings, for
the duration of a scene run, and a live client never calls it. A scene that wants one of
them sets it back explicitly. So a row whose evidence is a scene says what the bot does
when the pathfinder may not change the world, and the same bot under default settings has
more ways to get where it is going.

Status values:

| Status | Meaning |
|---|---|
| `Parity` | The behaviour is vanilla's own code on the driven bot, not a copy of it. |
| `Approximate` | Structurally close but not identical. The note says which tests it can make lie. |
| `Accepted` | Deliberately different. The note says what was traded for what. |
| `Open` | A known divergence with no decision behind it. |

Most of these rows were first derived by reading code, and reading is not how a row gets
confirmed. `wd.bodyParityCensus` is the instrument that confirms them: one scene, no
assertions at all, which drives a bot through each quantity the table makes a claim about
and records what the bot actually reports. It cannot go red, deliberately — an assertion
would have to come from the same reading that produced the prediction, which is a test that
is green forever and measures nothing. It records the run's premises first, because each of
them can invalidate every row under it, and it writes a value it could not take as
`unavailable` with a reason rather than as zero.

### Movement and collision

Divergence here comes from one root: the bot produces its own movement instead of
receiving it in a packet, so anything the server does *because a move packet arrived* has
to be arranged by hand, and anything the client does *before sending one* has to be ported.

| Behaviour | A connected player | A driven bot | Status |
|---|---|---|---|
| Per-tick physics | `travel` runs from the movement the client computed | `pump` runs `ServerPlayer.tick()` and `doTick()`, so `travel`, `aiStep`, item use, food and pose are vanilla's | `Parity` |
| The client half of `aiStep` | `LocalPlayer.aiStep` | ported into `JoinedBody.aiStep` | `Approximate` — a port, not a call |
| Minor horizontal collision | the client's eight-degree test decides whether a glancing wall stops a sprint | reimplemented on the joined player, because `Entity`'s answer on a server is always false | `Approximate` |
| Starting a sprint | a key press, with vanilla's own start rules | the driver sets the sprint flag directly, on both player bots | `Approximate` |
| Creative flight, starting an elytra glide from the jump key, riding jumps | part of `LocalPlayer.aiStep` | not ported; the elytra is started through `startFallFlying` instead | `Accepted` |
| Position authority | the client proposes a position and the server accepts or corrects it | the bot's position is produced on the server, so there is no proposal to check and nothing to correct | `Accepted` — a driven bot is never rubber-banded |
| Movement statistics, and the food they cost | counted once per move | counted once, inside `travel`; the move-handler tail's second count is deliberately omitted, because repeating it doubles both the distance and the food that swimming and sprinting cost | `Parity` |
| Fall distance and landing | reaches the landing rules through the move handler | `pump` calls the same fall check itself, from the displacement it just produced | `Parity` |
| Tick cadence | one tick per server tick, driven by the connection | one tick per *step*, driven by whatever advances the bot | `Accepted` — see below |

The cadence row is a contract, not a defect. A bot no driver steps does not move, and the
level's entity loop must not move one on a schedule of its own; a scene that steps a bot
hundreds of times inside a single server tick gets a bot that advances hundreds of ticks.
`pump` supplies `setOldPosAndRot()` and the tick counter itself whenever the entity loop
has not been past since the last step, so the per-tick rules that key on a tick count fire
once per step rather than all at once or never.

### Input and control

The driven bot has no camera and no keyboard. What a person's input passes through on its
way to the game is here.

| Behaviour | A connected player | A driven bot | Status |
|---|---|---|---|
| Impulse | keyboard, via the player's `Input` | `commandMove` / `commandForward`, written into the player's own input rather than the shared keybinds | `Parity` |
| Jump | a held key; vanilla's gate decides which presses become impulses | `commandJump` is an ask; the same vanilla gate decides. The server-side player releases the ask after one step unless it is in water, where a held ask keeps a swim rising | `Parity` for the gate, `Accepted` for the release policy |
| Look | a mouse, with the camera following | the client player slews the camera and can be asked to snap; the server-side player assigns yaw and pitch outright | `Accepted` — there is no camera to move server-side |
| What the bot is looking at | the client's crosshair hit result | the server-side player raycasts from the eye along its yaw and pitch, out to 4.5 blocks, against block outlines and ignoring fluids | `Approximate` |
| Client options, including view distance | sent at login and changeable at any time | a real default is supplied at join, and there is no verb to change it afterwards | `Approximate` — everything keyed to a player's loading radius reads the default |

### Breaking, placing and reach

This group holds the largest remaining divergences, and all of them err the same way: the
headless server-side player is *more* permitted than a connected player, not less. The
correct repair is therefore to make the server-side player honest, not to give the client
player more power.

| Behaviour | A connected player | A driven bot | Status |
|---|---|---|---|
| Breaking a block | the server's game mode applies the tool requirement, Silk Touch, Fortune, `Block#playerDestroy` and tool wear | the server-side player calls `Level#destroyBlock` directly, so none of those rules apply and the block yields its plain unenchanted harvest whatever is held | `Open` |
| Break duration | progress accumulates per tick and the not-on-ground and underwater penalties apply | the server-side player destroys in one tick unless `ServerPlayerBody.faithfulBreak` is set, which two scenes do and nothing in production does | `Open` |
| Break reach and visibility | the crosshair raycast, plus the server's distance check | the server-side player requires the target to have at least one face that is not a full solid render, and the eye-to-centre distance to be within the bot's own interaction range plus half a block. It does not raycast | `Approximate`, deliberately |
| Break reach on the client player | the crosshair raycast and the server's distance check | the client player answers "yes" to every cell and lets vanilla's own game mode decide, because second-guessing it there could only disagree with the game | `Accepted` |
| Placing | the server checks reach and the hit vector before `useItemOn`, then swings | the process-facing actuator calls `gameMode.useItemOn` with no reach check and no swing | `Open` |
| Attacking | the server checks reach before `Player.attack`, then swings | the process-facing actuator calls `Player.attack` with no reach check and no swing | `Open` |
| Placing into a cell with no solid neighbour | impossible — there is nothing to click | also impossible, and the actuator counts the refusal separately from "held nothing placeable" | `Parity` |
| Swinging | every consumed use, place and attack swings | the swing lives at the call sites, not on the bot: the client player swings on each dig tick, and the combat, interaction and elytra processes swing on a consumed action. The server-side player's place, use and attack do not | `Open` |

The two routes described above do not agree here, and the difference is deliberate. A
`mc.bot.*` verb addressed at a named bot lands in `api/BodyInteractions`, which does what
the server's packet handlers do with a packet, reach check included: it refuses a use or an
attack out of range, where a client's equivalent packet would simply be ignored, and swings
on a consumed action. The `Hands` actuator the walker and the processes drive does neither.
That asymmetry is the first thing to check when a scene and an API call disagree about
whether an action was possible.

The instant break is the divergence that most distorts planning: a route that costs the
headless server-side player one tick per obstructing block costs the client player a real
dig with vanilla's divide-by-five penalties for being airborne and for having its eyes
underwater. The two scenes that switch `faithfulBreak` on do so precisely to model that:
with it on, a bare-handed deepslate block takes roughly 650 ticks and a stone block mined
while afloat roughly 750. With it off, both take one.

`holdPlaceable` and `selectTool` both search the whole inventory, not just the hotbar, and
swap a winner up into the hand — a player does that by hand, and a bot that could only use
what happened to be on its hotbar would fail for a reason no agent could see through the
API. Two arms of the same footing scene differ by nothing but that: a stack of blocks in
slot 0 lets the footing remedy spend one and lifts the sole from 0.168 to 0.360, while the
same stack in slot 20 spends nothing, moves the sole from 0.168 to 0.184, and puts the bot
off the ledge.

### Inventory and the held item

The inventory itself is a player's own, so most of this group is parity. The exceptions are
the writes that skip the packet handler a real click would have gone through.

| Behaviour | A connected player | A driven bot | Status |
|---|---|---|---|
| The selected hotbar slot | the client sends its choice and the server applies it | one method writes the field and publishes a carried-item packet, guarded on the value actually changing | `Parity` |
| Changing the selected slot while an item is in use | the handler also stops the use, so a drawn bow is released from the draw | the driven bot's write does not, so a bow drawn to full stays "in use" across a slot change | `Open` |
| Moving a stack between the bag and the hand | a container click | the slot contents are swapped directly; that much rides `containerMenu.broadcastChanges()` in the player's own tick, which is the same channel a click would have used | `Parity` for the contents |
| Opening a station | right-click, `openMenu` | `useBlock` calls `gameMode.useItemOn` and the block's own use opens the menu through vanilla's `openMenu` | `Parity` |
| Container clicks | the click arrives as a packet and the server's container handler applies it | the driven bot calls `containerMenu.clicked` directly, after checking that the container id it was given is the one that is open | `Approximate` |
| Placing a recipe into a grid | the player has unlocked the recipe; the handler refuses otherwise | a freshly joined player's recipe book is empty, so the driver unlocks the recipe first and then places it | `Accepted` — the bot is given something a player would have had to earn |
| Container buttons — enchanting, stonecutter, loom | a button packet | no verb | `Open` |
| Dropping an item | a key press drops the held stack | no verb, so dropping and everything downstream of it cannot be exercised | `Open` |

The equality guard on the carried-item publication is not an optimisation. A client that
receives the packet applies it without updating what it believes it last sent, so its next
tick echoes the value back; an echo already in flight when a second write happens carries
the older value and rolls the hand back to it. The window is bounded by one round trip, and
publishing an unchanged value would open it for nothing.

Publishing at all was added because of the adopted bot. Both halves of the split hold
their own copy of the selected slot and both read their own: the driver's "is it already
held?" fast path and vanilla's client-side "was this slot already sent?" check each consult
the copy they wrote, so a divergence cannot heal itself. The scene that measures it
reported server slot 4 against client slot 0, identical at the same tick and ten ticks
later. Since the server is the hand that acts — a use packet carries a hand, never an item
— a tower whose client hand held cobblestone while the server hand still held a pickaxe
right-clicked the block face with the pickaxe and legally, silently, did nothing.

### Damage, vitals and death

The joined player cannot be hurt. That is one deliberate line of code, and everything else in
this group follows from it.

| Behaviour | A connected player | A driven bot | Status |
|---|---|---|---|
| Taking damage | damage applies, and death follows | `isInvulnerableTo` returns true, `die` is a no-op, and the bot cannot harm another player | `Accepted` |
| Invulnerability frames | the counter decrements in the player's own tick | it does too, since the pump runs that tick | `Parity` |
| Hunger | the food counter ticks, exhaustion accrues from jumping, sprinting, swimming and work | the food counter ticks; jump exhaustion comes from the real jump, and swimming and sprinting from the movement statistics inside `travel` | `Parity` for those sources |
| Exhaustion from mining and attacking | charged along the break and attack paths | follows whichever path each verb takes, and the break verb is the one that bypasses the game mode | `Open` |
| Starving | a player at zero food starves | the bot gets hungry and cannot die of it | `Accepted`, as a consequence of the row above |
| Eating and drinking | hold right-click; the use countdown completes the item | `commandUseItem` starts the use on the rising edge and releases it on the falling edge, and the countdown advances inside the vanilla tick the pump runs | `Parity` |
| Experience pickup | the pickup delay decrements every tick | it does too | `Parity` |
| Death and respawn | death, a respawn screen, a respawn packet | nothing: a bot that cannot die needs no respawn, and there is no verb for one | `Accepted` |

The invulnerability is what scenes and the ladder are built on, and its cost is that no
scene may assert survival. The honest form of such an assertion says the fight was won, not
that the bot lived, and scenes that need the other reading use a vanilla player as the
victim instead. A survival-fidelity run would want the invulnerability gone — and would
need the whole package at once, because a bot that can be hurt but has no respawn is
harder to explain than one that cannot be hurt at all.

### World, chunks and awareness

Everything in this group is a consequence of being in the player list, which is the single
largest reason joined players replaced players that were merely constructed.

| Behaviour | A connected player | A driven bot | Status |
|---|---|---|---|
| Presence in `level.players()` | yes | yes, from `placeNewPlayer` — so mob AI, spawner proximity, natural spawning and the dragon fight all see the bot | `Parity` |
| Chunk loading following the bot | the move handler ends in a chunk-map move | `pump` does the same, guarded on player-list membership because the removal path inside it dereferences an entry a departed bot does not have | `Parity` |
| Advancements | awarded through the inventory-menu listener and the criteria the player's tick fires | the listener is attached by the join path, and the bot's constructor attaches it again for a bot it was handed | `Parity` |
| Chunk batch acknowledgement | the client acknowledges each batch and the server paces the next one | nothing acknowledges, so the send pacing has no feedback | `Approximate` |
| Leaving the world | disconnect, and the chat line that goes with it | leaving means leaving the player list; the bot routes its own removal through `PlayerList.remove` behind a re-entry guard | `Parity`, with the instrumentation caveat below |
| Modded network channels | a custom payload packet reaches the mod | no verb, and nothing to deliver one to | `Open` — this is the ceiling on what modpack behaviour can be exercised |
| Chat as a source | a player can speak | sending chat is a client verb (`mc.client.chat.send`), as is every screen-level input verb; a headless bot has none of them | `Accepted` |
| Commands | run with the player's own permission level, entity and position | `mc.action.runCommand` builds its source from the first player in the list, or from the server when there is none, and always at permission level 4 | `Approximate` — no bot is ever refused for permission, and the anchor may be a different bot than the caller had in mind |

A departure is *required* to be silent in vanilla's channel. The arrival is announced by the
join path, but the "left the game" line is broadcast from the disconnect path a driven bot
deliberately never enters, so counting joins against that line in a server log compares two
unrelated channels and reports a healthy run as hundreds of joins and no departures. The
bot logs its own leave line, shaped like the join line, so the comparison is between two
things that answer the same question.

Two measurements are worth keeping about departure, because both were expensive:

- Before removal was routed through the player list, a scene disposing of its bot left it
  in the list, where the next scene picked it up as a connected player. Measured on the
  first armed Fabric run: 79 joins, zero departures, and thirteen scenes that should have
  skipped ran against a stranded bot instead.
- The per-profile bot cache sweeps departed bots on every mint rather than from a removal
  callback. Without the sweep, and with scenes minting under unique names so a stale entry
  is never overwritten, the map was the last thing holding each departed player with its
  advancements, statistics and inventory: 255 bots retained after 300 scenes on the
  dedicated Fabric suite, and the 2 GB server heap exhausted around scene 290 in two runs of
  three.

The chunk-map move is the other one. Before it was part of every step, the bot kept the
chunk section it joined in, which is what feeds chunk tickets, entity tracking and the
players-nearby check in front of natural spawning. Measured on the ladder's Nether segments
before the move was added: 107 monsters within 128 blocks beside the portal, 2 at a fortress
360 blocks away, and none at all over 7200 ticks in a warped forest.

### Bots that are not players

A mob is not measured against a connected player. What it is measured against is the
player on the same staging: the scenes walk the mob first, then walk a server-side
player from the same cell along the same route with breaking and placing disabled.

| Behaviour | A player | A mob | Status |
|---|---|---|---|
| Locomotion channel | writes impulse, jump and sneak, then vanilla moves the entity | the same, plus a speed field a mob's move control normally fills, which the bot writes from the movement attribute and scales the impulse by | `Approximate` |
| Hands and menus | present | absent; a process that needs either refuses on its first tick with `no_hands` | `Accepted` |
| Planning | breaks and placements are priced normally | the world view prices every break as impossible and counts no placeable blocks | `Accepted` |
| Climbing out of water | 28 ticks in the scene that measures it | 40 ticks on the same staging | `Approximate`, unattributed |
| Head direction | follows the look | the look control is off while driven, so each step turns the head to the yaw the walker set | `Accepted` |
| Idling | a bot nothing steps stands still | the same | `Accepted` |

The climb-out gap is the only reading in that set that differs. Across staircase, one-block
gap, open-water swim and ladder, the two bots land within a tick or two of each other; on
the flush water bank the mob takes twelve ticks longer, and the difference is not in the
swim — over open water the same impulse differs by one tick — but in the few ticks of
leaving the water. Both loaders' dedicated gates report the same figures. What this can make
lie is any assertion that budgets a mob's tick count from a player's; the scenes that
measure it assert arrival only.

Two things those scenes deliberately do not measure: fall damage, because the driven mob is
made invulnerable, and chunk behaviour, because a mob holds no chunk ticket and the scenes
never leave their staging area.

## Related documents

- [testing.md](testing.md) — the gates, the topologies, and how coverage is reconciled
  across them.
- [movement-tick-phases.md](movement-tick-phases.md) — what the walker does with a bot once
  it has one.
- [bot-layering.md](bot-layering.md) — where the bot seam sits among the other layers.
- [../design/body-abstraction.md](../design/body-abstraction.md) — why there is one bot
  layer over three kinds of controlled entity, what was ruled out, and which vanilla facts forced the
  shape. This document says what the bots do; that one says why they are built that way.
- [../design/world-view-parity.md](../design/world-view-parity.md) — the matching question
  for what each bot's world view believes.
