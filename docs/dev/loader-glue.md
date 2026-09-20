# Loader glue: what stays in `fabric/` and `neoforge/`, and what lives in `common`

Architectury API is a required dependency on both loaders. The version is
`architectury_api_version` in `gradle.properties`, and both loaders' mod metadata declare
it. This document describes the division of labour that follows, written against the
source; each claim names the file it can be checked in.

**In one sentence:** event subscriptions and registrations are written once, in `common`,
and each loader entry point keeps only what has no cross-loader form.

## The two subscription points in `common`

| Method | Called from | What it subscribes |
|---|---|---|
| `WorldDriverEvents.register()` | Both loader entry points, once each during mod construction | `LifecycleEvent` SERVER_STARTING, SERVER_STARTED and SERVER_STOPPING; `TickEvent.SERVER_POST`, which fires the script tick and then drives the server-side bodies; `CommandRegistrationEvent`; `BlockEvent.BREAK` and `BlockEvent.PLACE`; `EntityEvent.LIVING_DEATH`; `PlayerEvent.PLAYER_JOIN` and `PLAYER_QUIT`; `ChatEvent.RECEIVED` |
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

The subscriptions are shared, but what Architectury fires them from is not identical, and
two of the three differences are visible in what the driver reports.

- **`block.break` fires before the block is removed on both loaders.** On Fabric,
  Architectury injects into `ServerPlayerGameMode.destroyBlock` before the block state is
  consumed; on NeoForge it maps onto the platform's pre-break event. Both are cancellable,
  so a listener earlier in the chain that cancels the break also suppresses the report.
- **`block.place` covers a narrower set of placements on Fabric.** Architectury fires it
  from `BlockItem.place`, so on Fabric the event only covers a block item being placed
  from an entity's hand. On NeoForge it maps onto the platform's entity-place event, which
  is broader. The driver drops the event when no placer is present, so the two agree on
  that case.
- **`entity.death` fires at the moment death is decided, not after it.** On Fabric,
  Architectury injects at the head of `LivingEntity.die`; on NeoForge it hooks the
  platform's living-death event. Same position, same payload, cancellable on both.

The body of `chat.message` is the message component's string on both loaders. Architectury
takes the decorated content on Fabric and the server chat event's message on NeoForge;
vanilla decoration is the identity transform, so the two produce the same text.

## What stays on the loader side, and why

| Entry point | What it keeps | Why |
|---|---|---|
| `WorldDriverFabricClient` | The GAME and CHAT message events, including both `_CANCELED` variants | `mc.client.chat.history` must record lines other mods cancelled, and Architectury's client chat event has no cancelled variant. The two variants register the same method so they cannot drift. |
| `WorldDriverNeoForgeClient` | The client chat received event with `receiveCanceled = true` | The same reason, expressed in the platform's own form. |

Neither server entry point keeps anything. The server-side body factory and the
`/worlddriver server` command subtree live in `common`: a body joins through vanilla's own
`PlayerList.placeNewPlayer`, which needs no loader API, and the command registers along
with the rest of `WorldDriverEvents`.

## Test content has a construction-time entry point

`WorldDriverCommon.installTestContent()` is called once from each loader's construction
phase. It uses `ServiceLoader` to find implementations of
`net.magicterra.worlddriver.TestContent` and calls `register()` on each. The test content
is folded into the WorldDriver mod itself on both loaders, so the service file is visible
to the same class loader; a published jar contains no implementation, and the loop does
nothing. This exists because `DeferredRegister.register()` is only effective during
construction, which the scene provider — discovered after the server starts — cannot
satisfy.

The interface sits in the root package rather than in the package its implementations live
in, and that is NeoForge's module system's doing. In a development environment the main
and test-mod outputs are two separate JPMS modules, and a package present in both is a
split package that the module layer refuses outright, so the server does not start. **Every
package in the test-mod source set must therefore be a package the main source set does
not have.** The existing ones satisfy that; new ones must too.

## One command root, registered twice

The `worlddriver` literal is registered separately by `WorldDriverCommon.registerCommands`
and by `ServerAvatarCommand.register`. Brigadier's `CommandNode.addChild` merges a second
literal of the same name into the existing node, so each registration owns its own
subtree, and the permission requirement sits on the subtree that needs it rather than on
the shared root.

## The boundary StageWright compiles against

StageWright's common module compiles against six WorldDriver classes: `ToolCatalog`,
`ToolSchema`, `BotHooks`, `BotApi`, `DriverEvent` and `WorldDriverCommon`. None of those
six names an Architectury type anywhere — all the subscriptions are inside method bodies
of other classes. StageWright's own runs do not load the WorldDriver mod, so this
dependency is invisible to it.
