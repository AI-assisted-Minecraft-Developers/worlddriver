# Three world views, one set of rules

## The problem

The pathfinder never touches the level directly. It asks a `WorldView` three kinds of
question about a cell: can the player pass through it (`isPassable`), can the player stand on
it (`canStandOn`), and what does breaking it cost (`breakCost`). Three implementations of
that interface read a real level:

| View | Used by | Player |
|---|---|---|
| `bot/ClientWorldView` | `BotApiImpl` on a shipping client, and `PlanProbeTool` | the real player, a `LocalPlayer` |
| `bot/world/LevelWorldView` | `ServerWorldDriver`, the `wd.*` scenes, the journey harness | a server-side player |
| `bot/world/ServerWorldView` | `mc.observe.scene` and `WalkerGeometry`'s read-only probes | none |

Each of them used to answer those questions with its own method body, and the three implementations
disagreed. Running the parity scene `wd.clientWorldViewParity`, which stands in one world
and puts the same question to two views side by side, produced 67 disagreements out of
1650 questions over 50 terrain types, in three families:

**Break pricing.** The client priced blocks that need no tool at all — dirt, logs, leaves,
gravel, fences, chests — as if the held item were the wrong tool, ten times dearer than
vanilla's own destroy progress. The server priced only by whatever item happened to be
selected. Both executors switch to the best tool in the whole inventory before digging, so
both were wrong, in opposite directions.

**Standing.** The client demanded that the collision shape fill the top face of the whole
cell, which rejects slabs, soul sand, mud, farmland, dirt paths and honey blocks. The server
asked only whether the block stops motion, which accepts fences, walls and stairs as floor.

**Passage.** The client let a player through a pressure plate or a closed trapdoor as a thin
floor decoration; the server treated both as walls.

The consequence was not that scenes failed. It was worse: they passed, against a different
set of rules. A route proven by a `wd.*` scene was not necessarily a route a client would
have planned.

## What was decided

All three views delegate to one class, `bot/world/CellRules`, so each rule is written once.

**`isPassable`** accepts air and water. With `BotConfig.collisionAwarePathing` on it also
accepts two more cases: a collision shape that misses the 0.6-wide column of the player's hitbox entirely
(cocoa pods, single-axis glass panes, the bulge on a wall), and a shape that hugs the floor
and is no taller than `BotConfig.pathfinderThinObstacleHeight` (pressure plates, carpets,
lily pads, a closed bottom trapdoor).

**`canStandOn`** requires the block to stop motion with a non-empty collision shape, to have
a top face no higher than its own cell — a fence or wall tops out at 1.5, so the feet land
in the cell above rather than in this one — not to be a thin decoration, since the player
stands *in* that cell rather than on top of it, and to intersect the player's hitbox column. The
fourteen-sixteenths and fifteen-sixteenths family (soul sand, mud, farmland, dirt path,
honey block), bottom slabs, chests and crafting tables are all floor, because a player really
does stand on them.

**`isBreakableObstruction`** is a non-air, non-fluid block with a collision shape that a bare
hand removes instantly: lily pads, thin snow, pressure plates.

**`breakCost`** follows vanilla's `getDestroyProgress` semantics. A block that needs no tool
counts the bare hand as the correct tool; a block that needs one and does not have it takes
the hundredfold progress divisor and then a threefold wrong-tool multiplier. The best tool in
all thirty-six inventory slots is used for the calculation, because both executors switch
before digging. Logs are multiplied by `pathfinderLogBreakTax`, exempted when a log is the
current target, and everything is multiplied by `pathfinderBreakCostMultiplier`. Potion
effects and the efficiency enchantment are snapshotted once per search in
`CellRules.DigSnapshot`.

Pose-dependent slowdowns — eyes underwater, feet off the ground — are deliberately **not**
in `breakCost`. They describe where the bot is now, not what a future dig will cost, and
folding them in would make a search result depend on the bot's posture at the moment the
search happened to start.

## What each view still keeps for itself

Sharing the rules does not mean erasing the differences that are real.

`ClientWorldView` keeps its `BreakFeasibility` poisoned-cell set, the exception that only
leaves may be cut while escaping, and the two-argument `breakCost(p, from)` that applies the
fivefold and twenty-fivefold penalties for digging while afloat.

`LevelWorldView` keeps the `allowBreak` gate.

The placement-count asymmetry — the client counts the nine hotbar slots, the server counts
all thirty-six — is deliberate. Each planner counts what its own executor can reach.

## What this rules out

A rule cannot be changed for one view alone. Adding a fourth view, or changing what counts as
floor, means changing `CellRules` and re-running the parity scene, which is the point: the
alternative was three implementations drifting apart silently for months.

## How it is verified

```
./gradlew stagewrightIntegratedServerFabric -Pstagewright.scenes='wd.clientWorldViewParity'
```

The scene lays out fifty terrain types on a real client and asks eleven questions about the
cell below, at, and above each one, recording only the rows where two views disagree. The
criterion is zero rows. On a dedicated server it skips, because there is no client view to
compare against. Run it before and after touching `CellRules` or adding a view.

One trap in the staging: the palette has to be laid out in a grid beside the player, not in a
line running away from it. The client only holds the chunks near the player, and cells in
chunks it has not received read as air on its side — which the scene faithfully records as a
long list of disagreements that do not exist.

## Where to look

`bot/world/CellRules.java` holds every shared rule. The three views are
`bot/ClientWorldView.java`, `bot/world/LevelWorldView.java` and
`bot/world/ServerWorldView.java`; the interface they implement is
`bot/pathfinder/WorldView.java`.
