# Loader glue: what stays in `fabric/` and `neoforge/`, and what lives in `common`

This document describes how the driver's code in `common` is hooked up to each loader,
written against the source; each claim names the file it can be checked in.

**In one sentence:** what the driver does on an event is written once, in `common`, as plain
static methods that name no loader; each loader module subscribes its own callbacks to those
methods, with a mixin where the loader has no event at the right point.

## The server hooks: `WorldDriverEvents`

`WorldDriverEvents` has one public static method per hook. The driver only listens: no hook
cancels anything or returns a value, so a listener of another mod that cancels the action
earlier in the chain still suppresses the report.

| Hook | Fabric (`fabric/.../WorldDriverFabric.java`, `fabric/.../mixin/`) | NeoForge (`neoforge/.../NeoForgeServerEvents.java`, game bus) |
|---|---|---|
| `serverStarting` | `ServerLifecycleEvents.SERVER_STARTING` | `ServerStartingEvent` |
| `serverStarted` | `ServerLifecycleEvents.SERVER_STARTED` | `ServerStartedEvent` |
| `serverStopping` | `ServerLifecycleEvents.SERVER_STOPPING` | `ServerStoppingEvent` |
| `serverStopped` | `ServerLifecycleEvents.SERVER_STOPPED` | `ServerStoppedEvent` |
| `serverTickEnd` (script tick, then the server-side bot players) | `ServerTickEvents.END_SERVER_TICK` | `ServerTickEvent.Post` |
| `registerCommands` | `CommandRegistrationCallback.EVENT` | `RegisterCommandsEvent` |
| `blockBreaking` (`block.break`) | `PlayerBlockBreakEvents.BEFORE` | `BlockEvent.BreakEvent` |
| `blockPlaced` (`block.place`) | `BlockItemMixin`, around `BlockItem.placeBlock` in `BlockItem.place` | `BlockEvent.EntityPlaceEvent` |
| `livingDying` (`entity.death`) | `LivingEntityDeathMixin`, head of `die` on `LivingEntity`, `Player`, `ServerPlayer` | `LivingDeathEvent` |
| `playerJoined` (`player.join`) | `PlayerListMixin`, end of `PlayerList.placeNewPlayer` | `PlayerEvent.PlayerLoggedInEvent` |
| `playerLeft` (`player.leave`) | `PlayerListMixin`, start of `PlayerList.remove` | `PlayerEvent.PlayerLoggedOutEvent` |
| `chatReceived` (`chat.message`) | `ServerMessageEvents.ALLOW_CHAT_MESSAGE`, returning `true` | `ServerChatEvent` at `LOWEST` |

The NeoForge listeners are all at `HIGH` except chat. The Fabric mixins are in
`worlddriver-fabric.mixins.json`, which only Fabric loads. They must not go in
`worlddriver-common.mixins.json`: both loaders apply that one, and NeoForge would then report
each of those events twice.

`ArchitectureTest.everyEventHookIsWiredOnBothLoaders` fails when a public method of
`WorldDriverEvents` or `WorldDriverClientEvents` is not called from both loaders' sources. It
cannot tell whether the callback is the right one; the in-game `ExternalEventsTest` and
`CoreClientTest.serverReportsTheChatTheClientSent` check that.

## The client points in `common`

| Method | Called from | What it subscribes |
|---|---|---|
| `WorldDriverClientEvents.subscribe()` | Both client entry points | `ClientTickEvent.CLIENT_POST`, which drives the bot's client tick; `ClientLifecycleEvent.CLIENT_STOPPING`, which releases the input focus policy; `ClientGuiEvent.RENDER_HUD`, which draws the mouse-yield overlay |
| `WorldDriverClientEvents.install()` | Both client entry points, **on the render thread** | Not a subscription: it constructs the client API and the bot implementation and registers them with `ClientHooks` and `BotHooks` |

## The timing difference between the two loaders

`install()` and `subscribe()` are separate because the two loaders hand control to a mod
at different moments.

Fabric's client entry point already runs on the render thread, so it calls both back to
back. NeoForge's `FMLClientSetupEvent` runs on a mod-loading worker thread, so
`subscribe()` is called directly and `install()` is handed to `event.enqueueWork` to reach
the render thread. Architectury's own `ClientLifecycleEvent.CLIENT_SETUP` is invoked
directly from that NeoForge handler, without going through `enqueueWork`, which is why it
is not used as the installation hook.

## Where the two loaders' event semantics still differ

The hooks are shared, but what each loader calls them from is not identical.

- **`serverStarting` runs earlier on Fabric.** Fabric's `SERVER_STARTING` fires before the
  server loads its levels; NeoForge's `ServerStartingEvent` fires after. The hook needs no
  level (the client runs the same start-up at the title screen), and on both loaders it comes
  before every server-started handler, which is where StageWright publishes its methods onto
  the registry this hook creates.
- **`block.break` fires before the block is removed on both loaders**, and neither reports a
  break the game refuses. Fabric's `BEFORE` fires after vanilla's adventure-mode and
  command-block permission checks; NeoForge's break event comes pre-cancelled for those.
- **`block.place` covers a narrower set of placements on Fabric.** The mixin reports from
  `BlockItem.place`, after the block is set and only when it was, so it only covers a block
  item placed from an entity's hand. NeoForge's entity-place event is broader. The driver
  drops the event without a placer and on a client level, so the two agree on that case.
- **`entity.death` fires at the moment death is decided, before the drops**, at the head of
  `die` on both loaders. A `Player` that is not a `ServerPlayer` is reported twice on both,
  since `Player.die` calls `super.die`; on a server every player is a `ServerPlayer`.

The `text` of a `chat.message` is the message component's string on both loaders. Fabric
passes the decorated content and NeoForge the server chat event's message; vanilla decoration
is the identity transform, so the two produce the same text.

## What stays on the loader side, and why

| Entry point | What it keeps | Why |
|---|---|---|
| `WorldDriverFabricClient` | The GAME and CHAT message events, including both `_CANCELED` variants | `mc.client.chat.history` must record lines other mods cancelled, and Architectury's client chat event has no cancelled variant. The two variants register the same method so they cannot drift. |
| `WorldDriverNeoForgeClient` | The client chat received event with `receiveCanceled = true` | The same reason, expressed in the platform's own form. |

Beyond subscribing the hooks, neither server entry point keeps anything. The factory for
server-side players and the `/worlddriver server` command subtree live in `common`: such a
player joins through vanilla's own `PlayerList.placeNewPlayer`, which needs no loader API, and
the command registers through `WorldDriverEvents.registerCommands`.

## Test content has a construction-time entry point

`WorldDriverCommon.installTestContent()` is called once from each loader's construction
phase. It uses `ServiceLoader` to find implementations of
`net.magicterra.worlddriver.api.game.TestContent` and calls `register()` on each. The test content
is folded into the WorldDriver mod itself on both loaders, so the service file is visible
to the same class loader; a published jar contains no implementation, and the loop does
nothing. This exists because `DeferredRegister.register()` is only effective during
construction, which an in-game test — started only once the game is running — cannot
satisfy.

The interface sits in the api (`api/game`) rather than in the package its implementations live
in, and that is NeoForge's module system's doing. In a development environment the main
and test-mod outputs are two separate JPMS modules, and a package present in both is a
split package that the module layer refuses outright, so the server does not start. **Every
package in the test-mod source set must therefore be a package the main source set does
not have.** The existing ones satisfy that; new ones must too.

## One command root, registered twice

The `worlddriver` literal is registered separately by `WorldDriverCommon.registerCommands`
and by `BotCommand.register`. Brigadier's `CommandNode.addChild` merges a second
literal of the same name into the existing node, so each registration owns its own
subtree, and the permission requirement sits on the subtree that needs it rather than on
the shared root.

## The boundary StageWright compiles against

StageWright's common module compiles against WorldDriver's API registry and contract types
(`api/registry/*`, `api/schema/*`, and the records in `api/methods/test/TestInputContracts`)
and against `BotHooks`, `BotApi`, `EventContracts` and `WorldDriverCommon`. None of those names an Architectury type anywhere — all the subscriptions are inside method bodies
of other classes. StageWright's own runs do not load the WorldDriver mod, so this
dependency is invisible to it.

## No reflection on a Mojang-mapped member

Checked by `python3 scripts/check_remap_safety.py`, which reads the remapped jar and therefore
needs `./gradlew :fabric:build` first. The build maps to Mojang names, but remapping rewrites the
shipped Fabric artifact into the intermediary namespace and does not rewrite string constants,
so a reflective lookup by field name throws at runtime in the shipped jar. NeoForge is
unaffected, its runtime namespace already being Mojang-mapped, and no test run ever loads a
remapped jar — which is why the existing sites went unnoticed.

To open a member, add it to **both** of these and call it directly:

- `common/src/main/resources/worlddriver.accesswidener` — Fabric and compile
- `neoforge/src/main/resources/META-INF/accesstransformer.cfg` — NeoForge

They are separate because the loom version in use has no access-widener-to-transformer
conversion for NeoForge. The check asserts the two stay in step; nothing in the build does, and a
member opened on one loader only is a runtime `IllegalAccessError` on the other. Remaining
reflection sites are baselined in the script with a per-site reason. Shrink that list, never grow
it; if you must add one, make the degradation loud and say so in the entry.

## No client type handed to a wider parameter from a class a dedicated server loads

The check is `./gradlew :common:test` (`SchedulerClientCallSurfaceTest`, which reads the bytecode)
and then both `stagewrightTestFabric` and `stagewrightTestNeoforge`: the two loaders use different
mechanisms — Fabric's class loader checks the environment type, NeoForge's runtime cleaner checks the
dist — so one loader passing proves nothing about the other. The failure is at class-load time, so
the test dies at zero ticks with a message naming the class that failed to load and never the call site that
asked for it.

Holding a `LocalPlayer` in a local variable and calling its own methods is fine and always was.
What is not fine is passing it to a parameter declared `Player` or `Entity`: that widening makes
the verifier load `LocalPlayer` to prove the subtype relation. "It calls into a client type" is
not the rule.

The shape that survives is to put the widening inside a client-only class and reach it with
`invokestatic`, which resolves its owner and not its owner's dependencies; a chain whose tick
method opens by returning when the client is absent never loads that owner on a server. Verify by
measurement rather than by reading. For `bot/arbitration/**` the measurement is already written:
`SchedulerClientCallSurfaceTest` parses the compiled constant pool and fails if any class in that
package names a client type at all. The chains steer through `Body` and hold no client value, so
there is nothing there to call or to widen. It carries its own positive control, so a green result
means it looked rather than that it found nothing.
`./gradlew :common:test` runs it and needs no game. **Run it before landing any change that alters
the shape of a call** — folding a duplicated expression into a shared helper, extracting a method,
adding a parameter. Those read as tidy-ups, which is the disguise this rule keeps being broken in.

That test guards one package. The architecture check keeps every other `bot/` package from
naming a client type, but it reads source text, not call shapes: outside `bot/arbitration`,
disassemble the class and count the calls taking a `Player` parameter by hand.
