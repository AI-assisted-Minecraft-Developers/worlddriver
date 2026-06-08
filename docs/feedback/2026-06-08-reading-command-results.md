# Feedback — no first-class way to read a command result or a blockstate

> Date: 2026-06-08 · Consumer: `magic-server-modpack` (pakku modpack, MC 1.21.1 / NeoForge)
> Goal: build a structure live over the RPC, then **verify** it — including reading a
> block's *state* (e.g. is this `ars_nouveau:source_lamp` actually `lit=true`?) and the
> output of diagnostic commands (`data get`, `execute if`, `seed`).
> Author: Claude (agent), driving + self-verifying world edits.

## Summary

An agent that edits the world needs to **read back** what it did. Right now every
read path drops the thing you most want:

- `mc.action.runCommand` returns only `{ok, via}` — **no command feedback text and no
  Brigadier result/success count.** So `data get`, `execute if`, `locate`, `seed`, etc.
  run but their output is invisible.
- `mc.query q:"blocks"` returns `{pos, type}` — **block *type* only, never the
  blockstate properties** (`lit`, `facing`, `half`, `waterlogged`, …). You can't tell a
  lit lamp from an unlit one, or a stair's facing.
- `mc.world.snapshot` returns **counts** (`blocks`, `nonAir`, `blockEntities`) — not
  per-cell state.
- `mc.script.eval`'s sandbox exposes only `Agent.invoke/system/observe/action/query/
  client` (the same RPC surface) — **no raw `Level`/`BlockState`/light access**, so it
  can't read a state or a light level either.

Net: there is no first-class way to answer "what blockstate is at this position?" or
"what did that command print?" This breaks the verify half of a build→verify loop.

## Concrete pain (what triggered this)

Placing `ars_nouveau:source_lamp` with the default state leaves it **unlit** (the block
defaults to `lit=false`; light is `lit ? light_level : 0`). The fix is to place
`source_lamp[lit=true]`. But `source_lamp extends CopperBulbBlock`, whose `lit` is
normally toggled by redstone — so there's a real question whether `setblock …[lit=true]`
*persists*. I had no way to read the `lit` state back to confirm:

- `mc.query` → type only (`ars_nouveau:source_lamp`), no `lit`.
- `mc.world.snapshot` → `nonAir:1`, no state.
- chat readback (below) → didn't surface the result.

(Compounded by the stairs blindspot from the 2026-06-07 note — `mc.query` also omits
stairs entirely, so even *placement* of stair decorations couldn't be confirmed.)

## chat.send executes, but the reply is not the command's output

`mc.client.chat.send` with a leading `/` does run the command (`{ok, kind:"command"}`),
but the result you get back is **not** the command feedback:

```
mc.client.chat.send {"text":"/seed","awaitReplyMs":2500}
# -> reply.text = "你今天还没有签到，按 J 打开菜单领取每日奖励"   (an unrelated mod's
#    repeating daily-login broadcast), ageTicks ~851919 (stale), NOT "Seed: [...]"
```

`awaitReplyMs` appears to return the next chat packet that arrives, which on a busy
server is some other mod's spam, not the command's feedback. And:

```
mc.client.chat.send {"text":"/say PROBE_ALPHA"}     # ok, kind:"command"
mc.client.chat.history {"limit":12}                  # -> messages: []  (empty)
```

So `/say` / command feedback are not landing in the buffer `chat.history` reads
(`sendCommandFeedback` routing? the driven client not receiving these chat packets?).
Either way, chat is not a usable readback channel here.

## Workaround that DOES work (reify the result into the world)

Turn an unreadable command predicate into a queryable block change, then read it with
`mc.query`:

```
# clear a scratch cell first
mc.action.runCommand {"cmd":"setblock -2950 90 -3530 minecraft:air"}
# if the lamp is lit, drop a redstone_block at the scratch cell
mc.action.runCommand {"cmd":"execute if block -2886 78 -3530 ars_nouveau:source_lamp[lit=true] run setblock -2950 90 -3530 minecraft:redstone_block"}
# read the scratch cell
mc.query {"q":"blocks","center":{"x":-2950,"y":90,"z":-3530},"filter":{"in_radius":1,"type":"minecraft:redstone_block"},"select":["pos"]}
# -> non-empty => predicate true.  (confirmed source_lamp[lit=true] persisted)
```

This reads back **any** block predicate (state match, block-entity NBT via `if data`,
score comparisons, etc.), but it's clunky: needs a scratch cell + cleanup, and one
round-trip per predicate.

## Impact / ranking

- **Medium-high.** Doesn't corrupt anything, but an agent can't verify its own edits or
  use any diagnostic command. Every verification becomes a scratch-cell hack.

## Suggested fixes (ranked)

1. **`mc.action.runCommand`: return the command's output** — the Brigadier result int
   (success/count) and the collected feedback text. This single change unlocks
   `data get`, `execute if/store`, `locate`, `seed`, scoreboard reads, etc.
2. **`mc.query`: include blockstate** — add the state map to each block (or a
   `select:["state"]` projection). Needed to read `lit`/`facing`/`half`/etc.
3. **`mc.world.snapshot`: include per-cell blockstate** in the `blocks` payload (it
   already walks every cell).
4. **Fix chat readback**: make `awaitReplyMs` correlate to the command's *own* feedback
   (not the next arbitrary broadcast), and/or ensure command feedback + `/say` reach the
   buffer `chat.history` returns.
5. **`mc.script.eval`: expose a read-only level accessor** (`getBlockState`,
   `getBlockEntityNbt`, `getLightLevel`) so verification can happen server-side in one
   round-trip.

## Workaround (for consumers, today)

Use the `execute … run setblock <scratch> <marker>` + `mc.query` pattern above to read
any block predicate. For light specifically: there is no light-level read at all —
confirm visually (and note the camera may be human-driven; see the 2026-06-07 notes).
