# Centre-snap teleport audit

> **Archived audit, carried out 2026-08-22. Kept for its evidence, not as current
> documentation.** Line numbers, and in one case the code itself, have moved since.

## What was audited, and what was found

Three of the bot's digging processes — `BunkerProcess`, `DescendProcess` and
`EscapeProcess` — contain ten calls of the same shape: `p.setPos(foot + 0.5, y,
foot + 0.5)`, which places the body at the horizontal centre of the cell its feet are
already in. `Entity.setPos` writes the position and rebuilds the bounding box and does
nothing else: no collision solve, no `checkInsideBlocks`, no distance accumulation. A
real player's horizontal position is always the output of `move()`, so a real player can
never take this step. The question was whether any of the ten let the body end up inside
a block that should have stopped it.

Nine of the ten are harmless, and the reason is a single piece of geometry rather than
nine separate arguments. A player's box is 0.6 wide, so a body snapped to its own cell
centre occupies the horizontal span from 0.2 to 0.8 of that cell and nothing outside it.
The set of cells the body overlaps after the snap is therefore a subset of the set it
overlapped before. If the body was standing legally beforehand, it is still standing
legally afterwards: the snap can only reduce overlap, never create it. That argument is
exact for blocks whose collision shape fills the whole cell, which is what these
processes dig through.

The audit found one exception in each direction. The geometry does not hold for shapes
that occupy the centre band while still leaving a standable corner — the upper step of a
staircase is the clear case, and a body snapped to centre would overlap it by 0.3 of a
block. No instance of that was ever observed, because these are survival digging verbs
and they run in natural stone and earth, but `mc.bot.bunker` and the descent verbs can be
called anywhere, including inside a structure.

The one call the audit recommended changing was the vertical-rise snap in
`EscapeProcess`. Unlike its nine siblings it took its reference cell from a destination
computed earlier, guarded neither the X nor the Z coordinate, and ran during a jump, when
nothing re-pins the body each tick. A body that drifted into a neighbouring column during
those airborne ticks could be snapped a whole cell or more sideways, and across two
columns the cell in between may be solid. **That guard has since been added**: the snap
now requires the foot cell to match the destination in X and Z, to be at or above it in
Y, and the body to be on the ground. The comment on it names this file.

The rest of this document is the audit as written, including the bytecode
listings, the cell-set proof, the per-shape table, and a final section recording the
suspicions that were checked and ruled out so that the next reader does not repeat them.

---

## Centre-snap audit: ten calls of `p.setPos(foot+0.5, y, foot+0.5)`

**This document answers one question.** There are ten places in production code with the same shape —
snapping the body to the centre of the cell it is already in. Does any of them let the body do
something a real player could not?

**It does not answer "how to fix it".** Only the last section offers the cheapest equivalent way to
write it. **No code was changed in this pass.** A full journey run was in progress on an
integrated-server client (`runJourneyIntegratedServer`), and any compilation would have turned that
run into a mixture of two versions.

Investigated on 2026-08-22. Line numbers are anchored to a working tree identical to `HEAD` —
`git diff --stat HEAD` was empty for all three files — so every line number below is also the line
number in `HEAD`.

---

## 0. Summary, one line per call site

| # | Location | Reference cell comes from | Same-column guarantee | Upper bound on a single horizontal displacement | Can it pass through a block that should have stopped it | Verdict |
|---|---|---|---|---|---|---|
| 1 | `BunkerProcess.java:132` | `p.blockPosition()`, taken fresh this tick | **holds statically** | < 0.7071 | full cubes: **impossible**; off-centre collision shapes such as a staircase's upper step: **possible, never observed** | harmless |
| 2 | `DescendProcess.java:97` | as above | **holds statically** | < 0.7071 | as above | harmless |
| 3 | `DescendProcess.java:139` | as above | **holds statically** | < 0.7071 | as above | harmless |
| 4 | `DescendProcess.java:219` | `destFeet`, **with an X/Z guard** (`:216-217`) | **holds statically** | < 0.7071 | as above | harmless |
| 5 | `DescendProcess.java:255` | `base`, no guard, **relies on being re-pinned every tick** | argued, not static | < 0.7071 under normal conditions | as above | harmless, subject to the premise in section 4.5 |
| 6 | `EscapeProcess.java:119` | `p.blockPosition()`, taken fresh this tick | **holds statically** | < 0.7071 | as above | harmless |
| 7 | `EscapeProcess.java:155` | as above | **holds statically** | < 0.7071 | as above | harmless |
| 8 | `EscapeProcess.java:261` | `nf`, **with an X/Z guard** (`:256-257`) | **holds statically** | < 0.7071 | as above | harmless |
| 9 | `EscapeProcess.java:289` | `base`, no guard, **relies on being re-pinned every tick** | argued, not static | < 0.7071 under normal conditions | as above | harmless, subject to the premise in section 4.9 |
| 10 | **`EscapeProcess.java:327`** | `dest = base.above()`, **no X/Z guard, and nothing re-pins the body during the jump** | **does not hold** | **not bounded by the cell boundary**; bounded by the drift during the jump | **can cross columns; across two columns the cell between them may be solid, which really is passing through a wall** | **the one change recommended** |

**Nine of the ten are harmless; one, `EscapeProcess.java:327`, should gain a guard.** None of them
reaches "must be changed" — the harmful path for the tenth is something the code permits but that
does not appear anywhere in the existing logs.

---

## 1. Method, and where the evidence comes from

Three kinds of evidence, labelled separately and never mixed:

- **Code.** Reading the source and the call graph at `HEAD`.
- **Bytecode.** `javap -p -c` against the named-mapping vanilla jar
  (`minecraft-merged-1.21.1-loom.mappings.1_21_1.layered+hash.652182843-v2.jar`, the same canonical
  jar that `docs/dev/fake-player-parity.md` names in its section on how to read it). **No decompiler
  JVM was started**; only the JDK's own `javap`.
- **Measurement.** Readings taken from the log of the journey client that was **running at the
  time**, `fabric/run-journey-integrated/logs/latest.log`.

**No game JVM was started, and nothing was compiled.**

---

## 2. What `setPos` actually does (bytecode)

```
public void setPos(double, double, double);
  Code:
     0: aload_0
     1: dload_1
     2: dload_3
     3: dload  5
     5: invokevirtual #737   // Method setPosRaw:(DDD)V
     8: aload_0
     9: aload_0
    10: invokevirtual #740   // Method makeBoundingBox:()Lnet/minecraft/world/phys/AABB;
    13: invokevirtual #744   // Method setBoundingBox:(Lnet/minecraft/world/phys/AABB;)V
    16: return
```

The whole method body is those two things. There is **no** `move()`, **no** `collide()`, **no**
`checkInsideBlocks()`, **no** accumulation of `walkDist` or `moveDist`, and **no** update to
`xo`/`yo`/`zo`.

So the premise behind the question is correct: **this is a discrete displacement with no collision
solve, and a real player's horizontal position is always solved by `move()`, so a real player can
never jump this way.** That leaves exactly one question: can this unsolved displacement land the body
inside a block that should have stopped it?

---

## 3. The skeleton of the second question: one geometric theorem covering nine of the ten

**This is not an extrapolation from one site to ten.** It is one geometric fact that covers every
same-column call site at once.

Let the body be at `P = (x, y, z)`, with foot cell `F = (⌊x⌋, ⌊y⌋, ⌊z⌋)`, and let the snap produce
`P' = (F.x+0.5, y, F.z+0.5)`. The player's bounding box is 0.6 wide — half-width 0.3, which is the
same ±0.3 that `WalkerGeometry.java:98-99` uses — and Y is unchanged.

- Horizontally, `P'`'s bounding box is `[F.x+0.2, F.x+0.8] × [F.z+0.2, F.z+0.8]`, which lies
  **entirely inside its own column**, with 0.2 of clearance on each side. So the set of cells `P'`
  overlaps is `{F.x} × {F.z} × {the same y layers it already overlapped}`.
- `P`'s bounding box necessarily overlaps cell `F`, because it contains the point `x` and
  `⌊x⌋ = F.x`, and its y layers are identical.

Therefore **the set of cells `P'` overlaps is a subset of the set `P` overlaps.**

**Corollary, exact for full-cube collision shapes.** Stone, dirt, sand, sandstone, logs, obsidian,
netherrack and ores — for any block whose collision shape equals the whole cell, "the bounding box
overlaps that cell" is the same statement as "the bounding box intersects its collision shape". If
the body was standing legally before the snap, meaning it was not inside any block, then none of the
cells it overlapped was a solid full cube; the destination's cell set is a subset of that, so the
destination contains no solid full cube either. **The snap cannot create a new intersection; it can
only reduce an existing overlap.**

The swept path is the same story: the horizontal band between start and finish lies within
`[x-0.3, F.x+0.8]`, still inside cells the bounding box already overlapped, so the fact that a
discrete teleport does not sweep introduces no additional risk here.

**The theorem applies directly to sites 1, 2, 3, 4, 6, 7 and 8 — seven statically same-column cases —
and applies to sites 5 and 9 once sections 4.5 and 4.9 have argued their premises. It does not apply
to site 10.**

### 3.1 The theorem's exception: off-centre collision shapes (possible, never observed)

The theorem is exact only for full cubes. What breaks it is a shape that **occupies the centre band
`[0.2,0.8]` while still leaving a standable corner.** Checked family by family:

| Shape | Breaks the theorem | Arithmetic |
|---|---|---|
| **A staircase's upper step** | **breaks it** | The upper step occupies half the cell's footprint, for instance x ∈ [0.5,1], y ∈ [0.5,1]. A body standing on the lower tread has its feet at `C.y+0.5`, so `⌊y⌋ = C.y` and **its foot cell is the staircase cell**; avoiding the upper step requires `x ≤ C+0.2`. Snapping to `C+0.5` gives a bounding box of `[C+0.2, C+0.8]`, which **overlaps the upper step's `[C+0.5, C+1.0]` by 0.3 of a block** |
| Outer-corner staircase variants | **breaks it** | The upper step occupies a quarter of the footprint; same arithmetic |
| Doors and trapdoors | does not break it | 3/16 thick, so 0.1875, occupying `[C+0.8125, C+1.0]`; the snapped bounding box reaches `C+0.8`, **clearing it by exactly 0.0125**. This is no coincidence — vanilla already relies on a centred player fitting through a doorway |
| Fences, walls, fence gates, bamboo | does not break it | The post occupies the centre band, and a player cannot enter that cell in the first place |
| Composters and cauldrons | does not break it | Hollow, with 0.125-thick walls around the outside; the cell centre is the **safe** region, and the snap moves towards it |
| Slabs, snow layers, carpets, beds, chests, hoppers, anvils | does not break it | The footprint is the full cell, or at least includes the centre band, and the body stands on top of it; with Y unchanged there is no new overlap |
| Ladders, vines, cobwebs, torches, flowers | does not break it | No collision volume |
| **Custom shapes from mods** | **unknown** | WorldDriver is a tool for testing mod packs, and this family cannot be enumerated |

**There is no observed instance of the staircase family.** These three processes are survival digging
verbs — digging a pit, digging a staircase, escaping a shaft — and the terrain they run in is natural
stone, earth and sand. Staircases appear in villages, strongholds, bastions and mod-pack buildings,
and `mc.bot.ascend` and `mc.bot.bunker` are verbs a caller can invoke **anywhere**. So the finding is
**"possible", not "there is a defect".**

### 3.2 This family's asymmetric consequences, which deserve a line in the parity document

If the body did end up inside a block, the two bodies end up in **opposite** states:

- **A client `LocalPlayer` heals itself.** `Player.aiStep()` runs `moveTowardsClosestSpace` at the
  four horizontal corners — `Entity`'s `protected void moveTowardsClosestSpace(double, double,
  double)`, confirmed present in the bytecode — and pushes the body out of the block. On a joined
  body there is also the server's `handleMovePlayer` with its
  `isPlayerCollidingWithAnythingNew` check and its "moved wrongly" correction.
- **The server-side body had neither.** `ServerPlayerAvatar.step()` (`:993`) runs `fp.baseTick()`,
  then `mirrorPlayerTick()`, then `fp.travel(...)`, and **never enters `aiStep()`** — that is the
  channel the parity document records as not running — and `AvatarNetHandler` has no
  `handleMovePlayer` either. **Once it is inside a block, it stays inside.**
- **From 2026-09-14 the server half of this no longer holds.** `ServerPlayerBody.step()` now goes
  through `JoinedBody.pump`, so the body runs vanilla's `aiStep()`, and `JoinedBody.aiStep` ports
  `LocalPlayer.aiStep`'s `moveTowardsClosestSpace` at the four horizontal corners. The two bodies are
  symmetric on this point now.

This has the same shape as the judgement the parity document draws overall: **the issue is not that
the client body lacks a capability, but that the server-side body lacks a correction.**

---

## 4. Site by site, because the context differs at each of the ten

### 4.1 `BunkerProcess.java:132` — fixing the cell before starting work

```java
BlockPos foot = p.blockPosition();          // :128
if (startY == Integer.MIN_VALUE) {
    startY = foot.getY();                   // :130
    p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);   // :132
```

`foot` was taken on line 128 of this same tick, and nothing between `:128` and `:132` moves `p`. **Same
cell, holds statically.** It happens once, guarded by the `startY` sentinel, so a whole bunker snaps
only this once. The theorem applies.

The objection was raised that `BunkerProcess` is the path taken when the body is stuck, and being
stuck means there is something nearby. **Geometrically that makes it safer, not more dangerous.** The
more crowded the surroundings, the closer the body's legal position already is to its own column's
centre, and the smaller the snap. The theorem proves that moving towards the cell centre is moving
*away from every neighbouring column*, so a tight space is the favourable case.

### 4.2 `DescendProcess.java:97` — fixing the cell at the destination

`foot` comes from `:88`, and nothing moves between `:88` and `:97`. **Same cell, holds statically.** It
runs once per `DescendProcess`, immediately before `done(...)`. The theorem applies.

### 4.3 `DescendProcess.java:139` — fixing the cell before the pick

`foot` is passed in from `tick()` at `:88`. **Same cell, holds statically.** Every entry into `pick()`
necessarily leaves it again, to `CARVE`, `DIG_OWN` or `done`, so each stride snaps only once. The
theorem applies.

### 4.4 `DescendProcess.java:219` — fixing the cell on arrival in `STEP_DOWN`

The reference cell is `destFeet` rather than `foot`, **but `:216-217` carries an X/Z guard**:

```java
boolean atDest = foot.getX() == destFeet.getX() && foot.getZ() == destFeet.getZ()
        && foot.getY() <= destFeet.getY();
```

Equal X and Z implies the same column. Y may be lower, but `setPos` does not change Y, so that is
irrelevant. **Same column, holds statically.** The theorem applies.

### 4.5 `DescendProcess.java:255` — `DIG_OWN`, re-pinned every tick

The reference cell is `base`, with **no guard**. The same-column property is argued rather than
enforced:

1. `base = foot.immutable()` (`:163`), and the `pick()` that entered `DIG_OWN` had just snapped the
   body to `foot`'s centre at `:139` — so on entry `base` is the column the body is in.
2. `digOwn` begins each tick with `setPos(base` centre`)` and then `setDeltaMovement(0, y, 0)`
   (`:255-256`), so the horizontal velocity is permanently zero and this path issues no forward input
   at all.
3. The moment the floor is dug through (`!w.isSolid(below)`), `:241` returns early and **never reaches
   `:255`** — so nothing snaps during the fall.

Therefore, under normal conditions `base` is always the body's column and the theorem applies.

**The premise, stated plainly:** this same-column property is argued, not guarded. **If an external
force — a piston, an explosion, a water current's impulse — pushed the body a full cell out within a
single tick, the next tick's `:255` would discretely drag it back.** But `DIG_OWN` is entered only
when all four horizontal directions are unsafe, meaning the body is in a column enclosed by solid
blocks, and terrain in which an external force could push it a full cell is exactly the terrain in
which `DIG_OWN` is not chosen. **The risk is self-limiting.**

### 4.6 `EscapeProcess.java:119` — fixing the cell at the destination

`foot` comes from `:99`, and nothing moves between `:99` and `:119`. **Same cell, holds statically.**
The theorem applies.

The comment at `:117-118` says the purpose is to avoid sliding into an undug wall on residual
momentum and suffocating. **That comment matches the code**: it is the adjacent `:120`,
`setDeltaMovement(0, min(0,y), 0)`, that stops the slide, while the `setPos` merely squares up the
starting position.

### 4.7 `EscapeProcess.java:155` — fixing the cell before the pick

`foot` is passed in from `:99`. **Same cell, holds statically.** Every entry into `pick()` necessarily
leaves it again. The theorem applies.

### 4.8 `EscapeProcess.java:261` — fixing the cell on arrival in `STEP_UP`

The reference cell is `nf`, and `:256-257` carries an X/Z guard:

```java
boolean atDest = foot.getX() == nf.getX() && foot.getZ() == nf.getZ()
        && foot.getY() >= nf.getY();
```

**Same column, holds statically.** The theorem applies.

### 4.9 `EscapeProcess.java:289` — `VERT_BREAK`, re-pinned every tick

Structurally identical to section 4.5. `base = foot.immutable()` (`:189`) is taken just after `pick()`
has snapped, and `vertBreak` does `setPos(base` centre`)` plus `setDeltaMovement(0, y, 0)` every tick
(`:289-290`). **Same column holds, and the theorem applies.**

**But the premise here is weaker than in section 4.5.** The condition for entering `VERT_BREAK`
(`:177`, `best == null`) includes `!w.isSolid(tread)`, so **a direction being open air is also a
rejection**. `VERT_BREAK` can therefore be chosen somewhere entirely open — standing on top of a
pillar, or at the bottom of a large cavern where the surrounding floor is one level lower — and there
an external force has room to push the body aside. And the class comment on `EscapeProcess`
(`:20-23`) says in so many words that the situation it serves is **a one-wide shaft with the feet in
water** — so **current push is this process's standing operating condition**:
`ServerPlayerAvatar.step()`'s `fp.baseTick()` (`:1004`) runs
`updateInWaterStateAndDoFluidPushing`, which adds the current into `deltaMovement`.

However, `:290` zeroes the horizontal component every tick, so a current can push the body less than
0.014 of a block per tick and **cannot cross a cell boundary within one tick**. The verdict is still
harmless.

### 4.10 `EscapeProcess.java:327` — `VERT_RISE` arrival: the one change recommended

```java
BlockPos dest = base.above();
if (foot.getY() >= dest.getY() && p.onGround()) {          // :326  ← asks only about Y and onGround
    p.setPos(dest.getX() + 0.5, p.getY(), dest.getZ() + 0.5);   // :327
```

**This is the only one of the ten with neither an X/Z guard nor a per-tick pin.**

**The evidence is a comparison within the same family, not a guess.** There are three sibling "snap on
arrival" guards, in the same two files, with the same shape:

| Arrival guard | X equal | Z equal | Y | onGround |
|---|---|---|---|---|
| `DescendProcess.java:216-218` (`STEP_DOWN`) | yes | yes | `<=` | yes |
| `EscapeProcess.java:256-258` (`STEP_UP`) | yes | yes | `>=` | yes |
| **`EscapeProcess.java:326` (`VERT_RISE`)** | no | no | `>=` | yes |

Two have it and one does not. Either that is deliberate, in which case it owes a comment explaining
why, or it is a divergence.

**The harmful path, which the code permits.** During `VERT_RISE` the body is jumping (`:340`,
`commandJump(true)`). That phase has **no** `setDeltaMovement(0,…)`, **no** `setPos` pin, and **no**
`releaseInputs()` on entry. Across the dozen or so ticks of a jump, any horizontal component at all —
a forward key still held, a water current, a mob shoving through `Entity.push` — can land the body in
a neighbouring column. If the landing spot happens to have a solid surface at `y >= dest.getY()`, the
guard at `:326` is satisfied and `:327` **discretely moves the body a full cell sideways, or more**,
back into `base`'s column. Across two columns, the cell in between can be solid rock — **and that
really is passing through a wall**, because `setPos` does not sweep.

And `VERT_RISE` is entered precisely when none of the four directions can serve as a step, which
includes the case where the step is solid but `nf`/`nh` cannot be mined, such as bedrock. **That
terrain has a solid landing surface at y = base+1 right beside it**, which is exactly what satisfies
the guard.

**Never observed.** `EscapeProcess` produces zero lines across all existing logs; see section 6. So
the finding is a **recommendation**, not a **requirement**.

**The minimal fix**, waiting for a compilation window rather than applied in this pass, is to bring
`:326` up to the same three-part guard as its two siblings:

```java
if (foot.getX() == dest.getX() && foot.getZ() == dest.getZ()
        && foot.getY() >= dest.getY() && p.onGround()) {
```

When X and Z do not match, control falls naturally to the 100-tick stall at `:349` and returns to
`PICK` to decide again, which is a fallback the process already has.

---

## 5. Question 1: how large is the horizontal displacement of one snap

**The bound from the code**, for the nine same-column sites: the body's centre can be anywhere inside
its own cell, so the greatest distance to the cell centre is `sqrt(0.5² + 0.5²) = 0.7071` of a block.
**One tick, no collision solve.**

**A measured proxy distribution**, from `fabric/run-journey-integrated/logs/latest.log`, over the
`p=(x,y,z)` values in 16818 `[walker] t=` lines:

| Horizontal distance to the cell centre | Samples | Share |
|---|---|---|
| < 0.05 | 97 | 0.58% |
| 0.05–0.1 | 348 | 2.07% |
| 0.1–0.2 | 981 | 5.83% |
| 0.2–0.3 | 9362 | 55.67% |
| 0.3–0.4 | 2376 | 14.13% |
| 0.4–0.5 | 2283 | 13.57% |
| **≥ 0.5** | **1371** | **8.15%** |

- The mean is **0.2929** and the maximum **0.7001**, at `41.00, 62.00, 56.01`, where the body is
  sitting squarely on its cell's corner.
- Per axis, the means are `|dx| = 0.2030` and `|dz| = 0.1726`, and the per-axis maximum is **0.5000**,
  the theoretical limit.
- On **88.68% of ticks (14915/16818) the body's bounding box reaches into at least one neighbouring
  column**, meaning a per-axis offset above 0.2. So the snap is **almost never a no-op**: every time,
  it really is pulling the bounding box back out of a neighbouring column.

**This table's label has to be nailed down**: it is the distribution of the body's within-cell offset
while the walker is driving it, **not the measured displacement of any snap**. It stands in for the
population the snap acts on — it measures how far from the cell centre the body usually is, not how
far any particular `setPos` moved it.

**Two disciplines for reading it:**

- All 16818 samples were recorded on `[Render thread]`, with a `Server thread` count of zero, so these
  are readings from an **integrated server with a client `LocalPlayer`** — which is exactly the
  topology in question.
- The log carries only two decimal places. `41.00` may be a rounded `40.9996`, which leaves ±0.005 of
  ambiguity in which cell `⌊x⌋` names. **That affects the single extreme value — 0.7001 may really be
  0.6960 — and does not affect the distribution's conclusion.**

**Compared with a real player**: vanilla sprinting covers about 0.28 of a block per tick and a sprint
jump about 0.34. So in the worst case this snap is **roughly 2.5 sprint-jumps' worth of horizontal
displacement inside a single tick, with no collision solve.** "A real player cannot do this" is
literally true — and section 3 proves that in full-cube terrain, being unable to do it **has no
consequences whatsoever**.

**Site 10 is the exception.** The displacement at `EscapeProcess.java:327` is not bounded by the cell
boundary at all. It is determined by the horizontal drift during the jump and can be one block, two,
or more. **That is a difference in kind from the other nine, not a difference in degree.**

---

## 6. An honest "not found": not one actual snap appears in the existing logs

All three processes use the same `dbg()`, gated on `BotConfig.walkerDebug` (`BunkerProcess.java:103`,
`DescendProcess.java:60`, `EscapeProcess.java:70`).

**Prove the channel is open before reading a zero from it**, which is a hard rule in this repository:

| Log | `[walker]` | `[escape]` / `[descend]` / `[bunker]` |
|---|---|---|
| `fabric/run-journey-integrated/logs/latest.log`, the journey run in progress | **56909** | **0** |
| `fabric/run-dogfood/logs/latest.log` | **15402** | **0** |
| `neoforge/run-dogfood/logs/latest.log` | — | **0** |
| `fabric/run-rehearsal/logs/latest.log` | — | **0** |
| every `fabric/run-*/logs/*.gz` and `neoforge/run-*/logs/*.gz` | — | **0** |

Fifty-six thousand `[walker]` lines prove that `walkerDebug == true`, on the same gate through the
same logger. **So this zero is a real zero: these three processes have never run in any existing
log.**

And even if they had, the displacement could not be measured from them. None of those `dbg` lines
prints the coordinates from before the snap. `BunkerProcess.java:333`'s `STEP_IN walking pos=` is the
only one carrying coordinates at all, and it comes **after** the snap.

**So: this report's bound for the first question is derived from the code plus a measured proxy
distribution, the second question is answered by a geometric theorem plus bytecode, and for "how far
a snap has actually moved a body" there is no answer — and reasoning is not offered in place of
one.**

Getting a real reading needs **a census scene that asserts nothing at all**: run `mc.bot.ascend`,
`mc.bot.descend` and `mc.bot.bunker`, and unconditionally `ctx.record` the position before and after
every `setPos`, the displacement, and whether the destination bounding box intersects any collision
shape — recording `unavailable/<reason>` where a value cannot be taken. **That is the next pass's
work, not this one's.**

---

## 7. Question 3: is there a cheaper equivalent way to write this

**"Let the body walk to the cell centre itself" is not equivalent, for three reasons:**

1. **It takes several ticks and does not converge.** Input-driven positioning overshoots and
   oscillates. Half a block at a walking speed of roughly 0.1 of a block per tick takes five or more
   ticks, and with no deceleration term it will hunt back and forth.
2. **These call sites explicitly need it within this tick.** `BunkerProcess.java:131` says "Center on
   the column so the shaft/niche cells are unambiguous", and `EscapeProcess.java:154` says the same;
   both go straight on to compute cell-relative geometry with `foot.relative(d)`, and that geometry
   has to be settled **inside the same tick**.
3. **At some of these sites the body cannot move at all.** `EscapeProcess` serves situations where the
   body is trapped, and a one-wide shaft leaves no room to position; `DIG_OWN` is entered precisely
   when all four directions are unsafe. **Walking there is too slow, and often impossible.**

**The genuinely cheap equivalent is a different API, not a different path:**

```java
// today
p.setPos(foot.getX() + 0.5, p.getY(), foot.getZ() + 0.5);

// equivalent, effective the same tick, and collision-solved
p.move(MoverType.SELF, new Vec3(foot.getX() + 0.5 - p.getX(), 0, foot.getZ() + 0.5 - p.getZ()));
```

`Entity.move` goes through `collide()`, so **the displacement is the same and it takes effect this
tick, but it cannot enter a block**; and it puts `walkDist`/`moveDist`, `checkInsideBlocks()` and
`setOnGroundWithMovement()` back on their proper paths as well — none of which `setPos` does, as
section 2 shows.

**Two things to watch:**

- `move()` triggers automatic step-up through `maxUpStep`, so a half-block horizontal displacement
  could in principle lift the body onto a half-block step. Processes that judge progress by Y, such as
  `DescendProcess` and `EscapeProcess`, need this confirmed to have no side effect.
- `move()` advances `fallDistance`. On a `ServerPlayer` that is an empty override — see the parity
  table's row on fall damage — so the server-side body is unaffected; on the client body it needs
  confirming.

**Suggested order:** the X/Z guard at `:327` — one line, purely a tightening, no side effects — comes
**before** switching to `move()`, which touches ten sites, has a side-effect surface, and needs a
check run on both loaders.

---

## 8. Suspicions checked and ruled out, recorded so the next pass does not repeat them

| Suspicion | Conclusion | Basis |
|---|---|---|
| `setPos` skips collision, so it pushes the body into walls | **disproved for full-cube shapes** | The cell-set theorem in section 3; the destination bounding box `[0.2,0.8]²` lies entirely inside its own column |
| "Snapping is more dangerous when the body is stuck", the `Escape`/`Bunker` situation | **the opposite is true** | The more crowded the surroundings, the closer the body's legal position is to the cell centre and the smaller the displacement; the snap always moves *away* from every neighbouring column |
| These ten are teleports to somewhere else | **disproved**, and the reference cells were re-checked | Seven are statically in the same cell or column, two are re-pinned every tick, and **one (`:327`) is unguarded** |
| A client-side `setPos` will be rubber-banded by the server's "moved wrongly" check | **it will not**; reasoned, not measured | On an integrated server `isSingleplayerOwner()` is true and both of `handleMovePlayer`'s checks are off; and geometrically the snap creates no new collision, so `isPlayerCollidingWithAnythingNew` does not hold. **The "moved too quickly" threshold is 100 blocks² per tick, and 0.7 of a block is three orders of magnitude below it** |
| It will be judged as moving too fast | **it will not** | As above |
| It breaks landing detection or `fallDistance` | **it does not** | Y is unchanged, and `ServerPlayer.checkFallDamage` is an empty override anyway — see the parity table's row on fall damage |
| It renders on the client as a blink | **it does not**; a perception-level question | `setPos` does not update `xo`/`yo`/`zo` (section 2's bytecode), so render interpolation draws it as a fast slide |
| A server-side `setPos` misses the entity partition update | **it does not** | `setPosRaw` calls `levelCallback.onMove()` at its end |
| After the snap, this tick's position is settled | **not on the server** | In `ServerWorldDriver.tick()`, `process.tick(...)` runs **and then** `avatar.step()`, so `travel()`/`move()` still run once more this tick after the snap. **The client is the reverse**: `ClientTickEvent.CLIENT_POST` (`WorldDriverClientEvents.subscribe`) fires **after** `LocalPlayer.tick()`, so on the client the snap is the tick's last word |

---

## 9. Where this belongs, in the two directions the parity document distinguishes

- These ten are **not** specific to a fake player. They live in `bot/process/` and run on **any** body
  — `LocalPlayer`, `JoinedBody` and `AvatarFakePlayer` alike. **Retiring the fake player would not
  make them go away.**
- In direction, they belong to **"the server-side body lacks a correction"** rather than "the client
  body lacks a capability". The asymmetry in section 3.2 — `Player.aiStep`'s `moveTowardsClosestSpace`
  running only on the client — means the same excursion is smoothed out on the client and persists on
  the server-side body. **That has exactly the same shape as the parity document's own observation
  that most of its table is of the second kind.**
- **Do not add this to the boundary table yet.** Nine harmless sites are not a divergence, and until
  there is one observation, the tenth is only "possible". Decide what status it takes in that table
  once the census scene described in section 6 has produced readings.
