# Defence, combat and equipment, as originally proposed

> **Archived proposal, written 2026-06-04, in Chinese. Not a description of current behaviour.**
>
> This is the original design for the defensive reflexes, the combat loop and the automatic
> equipment behaviours. Its first principle — that the loop keeping the bot alive never goes
> through a language model, because a creeper's fuse and a skeleton's firing interval are measured
> in ticks — is still the rule, and is stated in `docs/design/perception-and-decision-layers.md`.
>
> The shipped combat layer is `bot/process/CombatProcess.java` and `bot/scheduler/CombatChain.java`,
> with the ambient equipment behaviours in `bot/auto/`. It does not match the structure proposed
> here, and several of the thresholds and gates described were revised by live evidence; the
> revisions are in `docs/design/scheduler-semantics.md`.

> This covers three roadmap milestones at once: the defensive reflexes, the active combat loop, and
> equipment management. All three depend on the priority-chain scheduler proposed in
> `execution-model-proposal.md`. The comparisons throughout are to AltoClef, a third-party Minecraft
> bot whose source is not part of this repository; the classes named from it are its mob-defence,
> food and fall-catch chains, its aim controller, and its kill-entity, projectile-dodge,
> creeper-evasion and armour-equipping tasks.

## 1. First principle: the loop that keeps the bot alive never goes through a language model

A creeper's fuse is about 1.5 seconds; a skeleton's firing interval and the attack-cooldown recovery
are measured in ticks. Any "notice danger, then react" path that makes one round trip through a
language model — hundreds of milliseconds to several seconds — is fatal by construction.

> **The division of labour.** The language model sets *intent* only: fight that zombie, withdraw to
> survive, fight the Wither. Tick-level tactics stay entirely local to the mod. The defensive
> reflexes do not even need an intent — they bid continuously and take over the moment a threat
> appears.

## 2. Shared foundation: threat perception

Combat and defence share one perception pass, so that there are not two scanners.

`mc.observe.threats{radius?}`, or an extension of `mc.query`'s entity mode:

```
out: [{
     id, type, pos, distance,
     hostile: true,
     canSeeMe: bool,                 // unobstructed line of sight (raycast)
     facingMe: bool,                 // facing me (predicts an incoming projectile)
     charging: "bow"|"none",         // drawing a bow or winding up
     threat: 0.0–1.0                 // combined score
   }],
   incomingProjectiles: [{ id, type:"arrow", pos, vel, willHit: bool, ticksToImpact }]
```

The score combines distance, how dangerous the type is — creepers and ghasts weighted heavily —
`canSeeMe` and `charging`. `willHit` is a one-step prediction of the projectile's velocity vector
against the player's bounding box.

The implementation goes in `bot/combat/ThreatScanner.java`, computed and cached once per tick and
read by both the defensive and the combat layers.

## 3. The defensive reflexes

### 3.1 Two landing places, by channel

Reflexes are split by whether they contend for the **movement channel** or for the **hand and
equipment channel**, which lines up with the existing `bot/auto/` plus `CLUTCH` structure. Sections 2
and 6 of the execution-model proposal set this out.

| Reflex | Contends for | Lands in | Priority |
|---|---|---|---|
| Evading a creeper's detonation | movement | `PanicChain` (scheduler) | `PRIORITY_PANIC` 1000 |
| Water-bucket fall catch | movement | the existing `CLUTCH` (already an early-return preemption) | 1000 |
| Evading an incoming projectile or a dragon-breath cloud | movement | `DodgeChain` (scheduler) | 900 |
| `autoRetreat`, disengaging at low health | movement | `RetreatChain` (scheduler) | `PRIORITY_SURVIVAL` 100 |
| `autoShield`, raising a shield | use key | `bot/auto/AutoShield` | — |
| `autoTotem`, replacing an off-hand totem | off-hand slot | `bot/auto/AutoTotem` | — |
| `autoHeal`, drinking or eating to heal | use key | an extension of `bot/auto/AutoEat` | — |

The hand and equipment reflexes **do not bid in the chain competition**. They are siblings in
`bot/auto/`, right beside the existing `AutoEat`, `AutoTool`, `AutoRespawn` and `AutoSwim`, gated by
a `BotConfig` switch and by channel ownership, and they run concurrently with movement. That is what
makes "walk while holding a shield up" or "walk while replacing a totem" possible.

> **Contention for the use key is a real constraint.** `AutoShield` holding the use key to raise a
> shield, `AutoEat` and `AutoHeal` all want to hold `mc.options.keyUse`, and the three are mutually
> exclusive. `bot/auto/` needs an arbiter for that key: a projectile incoming or a melee mob facing
> the bot gives the shield priority; otherwise low health gives healing priority; otherwise ordinary
> eating wins. This extends the existing `processOwnsUseKey` gating, with the claimant going from
> "one process" to "a process plus these few ambient users".

### 3.2 What each reflex has to get right

- **`autoTotem`.** If the off-hand holds something other than a totem and the inventory has one, swap
  it in with `slotClick`. Detect that a totem has just fired — the `TotemOfUndying` animation, or a
  sudden health restoration — and replace it immediately. AltoClef's mob-defence chain has the same
  logic.
- **`autoShield`.** When `incomingProjectiles.willHit` holds, or a melee mob is at close range and
  facing the bot, switch to the shield (or right-click to raise it) and turn to face the source, since
  a shield only blocks from the direction the damage comes from. Note that a shield does not block the
  bulk of a creeper's explosion damage, so creepers are handled by panic evasion rather than by
  raising a shield.
- **`autoHeal`.** An extension of `autoEat`: when health is low and a splash healing potion or a
  golden apple is available, use that first; once health is full, fall back to ordinary `autoEat` to
  maintain saturation.
- **`autoRetreat`.** When health drops below `retreatHpThreshold`, reuse the existing
  `RunAwayProcess` to disengage away from the threats' centre of mass, with `autoHeal` continuing to
  restore health throughout.
- **Creeper evasion.** When a creeper comes within `CREEPER_KEEP_DISTANCE` — around 3 blocks, the
  point at which it starts to swell — sprint away from it. AltoClef's creeper-evasion task does the
  same.
- **Projectile and dragon-breath evasion.** The approach of AltoClef's projectile-dodge task: step
  sideways out of the line of fire. Dragon breath lands as an `AreaEffectCloud` and forms a lingering
  cloud, so leave the cloud's horizontal extent immediately; the boss fights reuse this, as does
  AltoClef's dragon-breath tracker.

### 3.3 Configuration

New volatile switches on `BotConfig`, which are exposed through `mc.bot.setting` automatically and so
do not require a new verb:

```
autoTotem, autoShield, autoHeal, autoRetreat, autoDodge   (bool)
retreatHpThreshold (float, default 6)
creeperKeepDistance, projectileDodgeRadius (double)
```

### 3.4 Validation (`41_defense.js`)

Spawn a creeper and assert that the bot backs off and is not blown up. Spawn a skeleton firing arrows
and assert that the bot raises its shield or steps aside, and that the damage taken stays bounded.
Drive health low and assert that retreat and healing trigger and that health recovers.

## 4. The active combat loop

### 4.1 `CombatChain` plus `CombatProcess`

`CombatChain.priority` returns `PRIORITY_COMBAT` 60 — above the user task at 50, so it preempts
mining and the like automatically — when there is a hostile entity whose `threat` exceeds a threshold
and the bot either has an engagement intent or has `autoFight` switched on. With no threat present it
returns 0.

`CombatProcess` holds the foreground combat logic:

```
mc.bot.combat{ mode: "engage"|"defend"|"kill", target?: {type|id|nearest} }
```

- `kill` takes a specified target and fights until it is dead.
- `engage` clears every hostile within range until the area is clear.
- `defend` only strikes back at whatever attacks the bot and never initiates, which pairs with the
  reflex layer.

Each tick, the combat loop:

1. Selects a target — the highest scorer from `ThreatScanner`, or a specified id — and locks on, so
   that it does not jitter between targets every tick. AltoClef holds a locked entity for the same
   reason.
2. Determines the weapon class. Melee (a sword or an axe) means closing to within 3 blocks; ranged
   (a bow or a crossbow) means holding `kiteDistance` while facing the target.
3. Closes the distance with the existing `goto{entity}` or `follow`.
4. **Gets the timing right, which is the crux of the whole loop.**
   - Melee only swings when `mc.player.getAttackStrengthScale(0.5f) >= 1.0`, because full damage
     requires a full cooldown. Under Mojang mappings that method takes a `partialTick` argument.
     **When this document was written, `attackEntity` was missing that step entirely: it called
     `gameMode.attack(p,target)` and `swing()` without consulting the cooldown, so calling it
     repeatedly just produced a stream of weak attacks. This has since landed: `CombatProcess`
     decides when to strike from `getAttackStrengthScale`, and `bot/util/AttackSnap.java` reads it
     every tick.**
   - **Critical hits.** Attacking while falling — not on the ground, not ascending, not in water or
     on a ladder — triggers a 1.5× critical. The combat loop can hop deliberately and strike at the
     moment it starts to fall.
   - It goes through the existing `attackEntity`, which uses `gameMode.attack()`, so that vanilla
     applies damage, sweeping and criticals itself.
5. **Strafes.** Melee circles the target to stay off its front; ranged kites, backing away while
   firing and holding `kiteDistance`.
6. **Terminates itself.** When the target is dead and no other threat remains, the process completes,
   `CombatChain`'s priority falls to zero, and the preempted user task resumes. A health emergency is
   deliberately not handled here: `autoRetreat` sits at priority 100, above combat at 60, so it
   preempts and withdraws on its own.

### 4.2 Why the timing can be judged locally, and reliably

Attack cooldown and critical eligibility are both judged from client-side `mc.player` state —
`getAttackStrengthScale`, `onGround`, `fallDistance` — and never depend on a packet coming back from
the server, so they stay correct under high latency. This answers the corresponding entry in the
roadmap's risk table.

### 4.3 The external surface

One verb, `mc.bot.combat`, covers all three intents through its `mode` parameter, in keeping with the
convention against multiplying verbs. `autoFight` is a `BotConfig` switch; with it on, `CombatChain`
bids automatically whenever a threat appears, so the caller does not have to issue an order each time.

### 4.4 Validation (`42_combat.js`)

Spawn a group of zombies and assert that all of them are cleared. Instrument every swing to record
`getAttackStrengthScale` at that moment, and assert that the overwhelming majority are at or above
0.9, meaning the swings land inside the cooldown window. In ranged mode, spawn a skeleton and assert
that the bot keeps its distance and kills it with the bow.

For reference, AltoClef's aim controller covers aiming, cooldown and multiple targets, and its
kill-entity tasks cover the rest.

## 5. Equipment management

This is a prerequisite for hard fights and boss fights.

### 5.1 `EquipProcess`

```
mc.bot.equip{ profile: "best"|"combat"|{slots...} }
```

- Scan the inventory, choose the best item for each armour slot — material first, then enchantment
  weighting — and put it on with `slotClick`.
- Choose the best weapon for the main hand: swords first, ranked by material plus Sharpness or Power.
- Check durability. A piece below `durabilityThreshold` raises an alert and hands the decision back to
  the caller, which can repair or replace it. Missing a critical piece, such as lacking full armour
  before fighting the Wither, is reported as `missing`.

### 5.2 The `autoEquip` reflex

At the moment `CombatChain` activates, `bot/auto/AutoEquip` makes sure the best equipment is already
on. It belongs to the same family and the same package as the existing `AutoTool`: `AutoTool` manages
the held tool, `AutoEquip` manages armour and weapons.

### 5.3 Validation (`45_equip.js`)

Fill the inventory with a mixture of armour — some iron, some diamond — and assert that the best full
set goes on. Insert a sword with very low durability and assert that the alert fires.

For reference, AltoClef's armour-equipping task and its pre-equip chain do the same job.

## 6. The data flow

```
ThreatScanner (every tick, shared)
   ├──→ defensive reflexes: scheduler chains (Panic/Retreat/Dodge)
   │      + bot/auto/ (AutoShield/AutoTotem/AutoEat arbitrating for the use key)
   └──→ combat: CombatChain.priority + CombatProcess choosing target, timing and footwork
                                          │
   equipment: bot/auto/AutoEquip ─────────┘ ensures gear is on before the fight
                                          │
scheduler (movement channel only): PANIC > SURVIVAL > COMBAT > USER  → preempt and resume
hand/equipment ambient behaviour runs alongside movement and never enters the scheduler
```
