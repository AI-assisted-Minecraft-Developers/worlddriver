# Sharing input with the human at the keyboard

The bot drives the player through the same objects a human does: the key mappings and the mouse
handler are global singletons, not per-actor. Three mechanisms keep them from fighting, and which
one applies depends on the input.

| Input | Mechanism | Rule |
|---|---|---|
| Movement and sprint keys | `InputReleaseGate` and `BotInteract.releaseKeys()` | The bot actuating any of them marks the set dirty; the idle path clears them once per drive burst. A human playing with no agent connected never has their keys touched. The per-tick clear this replaced left a manually held key dead within about fifty milliseconds. |
| Digging | `ClientIntents.holdDig` and the client mixin | The bot never presses the attack key. Every destroy drive asserts a dig and vanilla's next two attack passes stand aside, so the drive is the whole dig, one skipped drive costs nothing, and a human's held button is read by nobody but vanilla. The latch is bookkeeping and is cleared by `releaseKeys()`. |
| Item use | `ClientIntents.holdUse` and the client mixin | The bot never presses the use key. The mixin widens vanilla's own keybind reads to "pressed, or the bot holds use", so starting, holding and releasing remain vanilla's code. Deliberately excluded from `releaseKeys()`, because the idle release runs after the shield, heal and eat reflexes have set it. One holder per tick in that precedence, losers release; a builder-kind process suppresses all three so their use action cannot double up with its own direct interaction. Pinned by `UseKeyOwnershipTest`. |
| Cursor and camera | `MouseYieldGate` and `MouseYield` | While the bot drives, the cursor is released to the operating system so the human's mouse moves a desktop pointer rather than the crosshair. It is sticky, since vanilla re-grabs on any click; a double tap of escape reclaims it for the rest of the burst. |

Two consequences are worth knowing before touching this area. The bot's break-held query reads
its own latch and not a keybind, so a human's click is no longer visible through it — the two
inputs are separate objects now, which is the point. And a new writer of any of these globals is a
design decision, not a refactor: the failure is silent in both directions, either clobbered so the
action never happens or leaked so the bot walks around holding a key.
`SharedKeybindQuarantineTest` refuses any attack-key or use-key write outside the mixin, so a new
one has to be argued there.
