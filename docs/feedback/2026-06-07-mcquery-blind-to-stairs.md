# Feedback — `mc.query q:"blocks"` silently omits stair blocks

> Date: 2026-06-07 · Consumer: `magic-server-modpack` (pakku modpack, MC 1.21.1 / NeoForge)
> Goal: drive AgentDriver over the RPC websocket to **build a structure live** (a round
> conical wizard tower) via `mc.action.fill` / `placeMany` / `runCommand`, then **verify**
> the result with `mc.query`.
> Author: Claude (agent), building + verifying world edits programmatically.

## Summary

`mc.query` with `q:"blocks"` **does not return stair blocks** (`minecraft:*_stairs`
and modded stairs like `ars_nouveau:archwood_stairs` alike). The blocks are physically
placed and present in the world — `mc.query` just omits them from its result array.

This is a **verification-only** bug: placement works fine. But because the query is the
natural way an agent confirms its own edits, the blindspot caused a false "the stairs
didn't place" conclusion and a wasted debugging detour. An agent that trusts `mc.query`
for verification will report correct builds as broken (or worse, "fix" them by
re-placing, doubling work).

## Repro

Build area was cleared to air, ground pad at y107.

```
# place a vanilla stair and a control block one cell over
mc.action.runCommand {"cmd":"setblock 990 113 -2345 minecraft:oak_stairs"}
mc.action.runCommand {"cmd":"setblock 991 113 -2345 minecraft:stone"}

# query the 2-cell neighborhood
mc.query {"q":"blocks","center":{"x":990,"y":113,"z":-2345},"filter":{"in_radius":2},"select":["pos","type"]}
# -> returns the stone at 991, but NOTHING at 990 (the stair is missing from the array)
```

Confirmed the stair is actually placed via an independent read (`mc.world.snapshot`,
which counts non-air by block state, not via the query serializer):

```
mc.action.runCommand {"cmd":"setblock 990 113 -2345 minecraft:air"}
mc.world.snapshot {"from":{"x":990,"y":113,"z":-2345},"to":{"x":990,"y":113,"z":-2345}}
# -> "nonAir":0
mc.action.runCommand {"cmd":"setblock 990 113 -2345 minecraft:oak_stairs"}
mc.world.snapshot {"from":{"x":990,"y":113,"z":-2345},"to":{"x":990,"y":113,"z":-2345}}
# -> "nonAir":1   (stair IS there; mc.query just won't report it)
```

Reproduced with both `minecraft:oak_stairs` and `ars_nouveau:archwood_stairs`, placed via
`runCommand setblock`, `runCommand fill`, and `mc.action.placeMany` — all three place the
stair successfully, and in all three `mc.query` omits it. Affected at scale: a decoration
pass placed ~13 stairs (portico steps, door hood/awning, buttress shoulders); `mc.query`
reported `0` stairs across the whole tower while `mc.world.snapshot` confirmed them
present.

## Scope

- **Affected:** stair blocks (vanilla + modded). `mc.query` returns them as if the cell
  were air.
- **NOT affected (return normally):** full blocks, `*_slab`, `*_fence`, logs, `glass_pane`,
  lanterns (incl. `[hanging=true]`), `bookshelf`, `chest`, jigsaw, structure_block, etc.
  Only stairs were observed missing.

## Likely cause

Smells like the `mc.query` block→JSON serializer special-cases or throws on stair block
states and the entry is dropped (rather than the whole scan failing). Worth checking
whether the query builder filters by a block/shape predicate, or whether `select:["type"]`
projection chokes on the stair `BlockState` (facing/half/shape/waterlogged props) and the
element is skipped. `mc.client.blocks` (client-authoritative scan) should be checked for
the same blindspot.

## Impact / ranking

- **Medium.** Doesn't corrupt the world, but silently breaks the read-back loop that
  agents rely on for self-verification. Stairs are extremely common in building, so this
  hits any "build then verify" workflow.

## Suggested fixes

1. Fix the serializer so stairs are returned like any other block.
2. Until then: **document** that `mc.query` is blind to stairs, and point to
   `mc.world.snapshot` (`nonAir` / per-cell block data) as the reliable verification path
   for stair placement.
3. Consider a quick gametest: place each stair variant, assert `mc.query` returns it.

## Workaround (for consumers, today)

Verify stair placement with `mc.world.snapshot {from,to}` and check `nonAir`, or trust the
placement call's `ok:true` (placement itself is reliable). Do **not** infer "stairs
missing" from a `mc.query` result.
