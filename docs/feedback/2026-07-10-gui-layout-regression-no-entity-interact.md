# Feedback — GUI layout regression needs an entity-interact tool

> Date: 2026-07-10 · Consumer: `touhou_little_citizen` (NeoForge ModDevGradle mod, MC 1.21.1)
> Goal: visually verify a modded button's layout inside another mod's container GUI
> (Touhou Little Maid's 256×256 maid screen) on a headless Xvfb dev client — open the
> GUI, read widget geometry, screenshot before/after a layout fix, click the button.
> Author: Claude (agent), driving via the MCP transport (`mcp.sh` from the
> touhou_little_citizen regression manual).

Net-positive session: the whole screenshot-regression loop (enter world → open GUI →
`screen.tree` → screenshot → fix code → restart → re-verify → click-through) worked.
But the *first* step — opening an entity-bound GUI at all — has no driver support and
cost the most wall-clock time. Ranked below.

## 1. No way to right-click an entity (feature gap, high impact)

The maid GUI opens on entity interact (`EntityMaid.mobInteract`). The driver has
`mc.bot.attackEntity` (left-click) but **no interact/use counterpart**, so there is no
way to open any entity-bound GUI (villager trades, TLM maid screen, boats/minecarts,
leashing, shearing…). Everything else about the session was blocked behind this.

Dead ends tried, to save the next agent the time:

- `mc.client.input.click` is screen-coordinate only and explicitly `ok:false` when no
  screen is open — it cannot produce an in-game mouse click.
- `mc.client.input.key` has no mouse-button names (only keyboard keys), so the `use`
  keybind can't be triggered through it.
- Real X-level clicks on the Xvfb display (`xdotool click 3`, both XTEST and
  `--window`/XSendEvent variants, with `windowfocus` + pointer inside the window)
  **never reach GLFW** — verified by right-clicking grass while holding a hoe: no
  farmland appeared. Also note Xvfb has no WM, so `xdotool windowactivate` aborts with
  `_NET_ACTIVE_WINDOW` unsupported.

Workaround that finally worked: adding a debug command **in the consumer mod**
(`/touhou_little_citizen opengui <maid-uuid>` → server-side
`maid.openMaidGui(serverPlayer)`, invoked as `/execute as Dev run …`). That is fine for
a mod you own, but useless against third-party mods.

**Ask**: `mc.bot.interactEntity {entityId|uuid, hand?}` mirroring `attackEntity`
(snap look, then `MultiPlayerGameMode.interact` on the render thread so the normal
client→server interact packet flow runs). A `mc.bot.useKey {ticks?}` fallback that
presses the `use` keybind against whatever is under the crosshair would also cover
blocks-with-menus and item use in one tool.

## 2. `mc.client.overlays` throws inside its tutorial path (bug, cosmetic)

Call with `{}` returned:

```json
{"ok":true,"tutorialError":"ClassNotFoundException: TutorialSteps","toasts":"cleared"}
```

Toast clearing itself worked (and was exactly what I needed — the movement-tutorial
toast was covering the top-right corner of the GUI in every screenshot, and walking
around with `mc.bot.goto` / WASD keybinds never dismissed it). But the reflection
lookup for `TutorialSteps` misses in a mojmap dev runtime, so the "suppress tutorial at
the source" half of the tool silently no-ops. Worth fixing the class name resolution or
dropping the tutorial half; `toasts:"cleared"` alone is already the useful part.

## 3. `mc.observe.player().look` lags client camera changes (doc gotcha)

Inside a single `mc.script.eval`, calling `mc.bot.lookAt {pos}` and then immediately
`Agent.observe.player().look` returned the **pre-lookAt** rotation (`yaw:0`) even
though a screenshot confirmed the camera had moved. Presumably observe reads the
server-side entity and the client rotation packet hadn't ticked over yet. One line in
the `lookAt`/`observe.player` docs ("rotation is visible to observe.player only on the
next tick — waitTicks(1) before asserting") would prevent false "lookAt is broken"
diagnoses; I lost a debugging round to this.

## 4. Wrong-argument error is unhelpful for `runCommand` (rough edge)

`mc.action.runCommand {"command":"…"}` (wrong field name; schema wants `cmd`) fails
with `"empty command"` instead of an unknown-field/missing-required validation error.
Schema-validating the argument object (or echoing the expected field in the error)
would have saved a tools/list round-trip.

## What worked well (keep these)

- `mc.client.screen.tree` returning live geometry for **modded** widgets
  (`MaidTabButton`, our event-added `Button`) made the layout fix precise: the tabs'
  real span (x 94–193 GUI-relative) came straight from the tree, no pixel-measuring.
  This is the killer feature for GUI layout regression.
- `mc.client.screenshot {maxWidth, format}` + `mc.client.input.mouseMove` (tooltip
  hover) + `mc.client.input.click` (screen open) covered the entire visual + functional
  verification loop.
- Title-screen navigation by `screen.tree` coordinates, quit-to-title world save, and
  the documented restore-from-zip baseline flow all replayed exactly as written in the
  consumer repo's regression manual.
