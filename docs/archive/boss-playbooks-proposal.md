# Boss playbooks, as originally proposed

> **Archived proposal, written 2026-06-04, in Chinese. Not a description of current behaviour.**
>
> This is the original argument for writing boss fights as scripts rather than as Java, so that
> tuning a fight does not require rebuilding the mod. That argument was accepted: the verb is
> `mc.bot.playbook` and the scripts are under
> `common/src/main/resources/data/worlddriver/scripts/playbooks/`.
>
> The stage-by-stage fight plans and the boss-perception API sketched here should not be read as a
> description of the shipped playbooks, which have been tuned against real fights since. The
> surviving decision is `docs/design/boss-playbooks.md`; the scripts themselves carry their own
> current strategy notes.

> This was the last of the autonomy milestones, and it assumed all the earlier ones were in place:
> the scheduler, the defensive reflexes and the combat loop, crafting, and equipment management. The
> comparisons are to AltoClef, a third-party Minecraft bot whose source is not part of this
> repository; the classes named from it are its dragon-killing tasks, its bed-based dragon variant,
> its dragon-breath tracker, its pearl-timing task, and its projectile-protection wall task.

## 1. The key decision: boss playbooks are scripts, not hard-coded Java

A boss fight is fundamentally a **multi-stage state machine**, and each stage is nothing more than
orchestrating primitives that already exist — `combat`, `goto`, `elytraFly`, `equip`, `useItem` —
while reading boss-specific state and toggling reflex switches. Putting that high-level logic into
the **existing Rhino sandbox** under `config/worlddriver/scripts/` has three advantages:

1. **Iteration without a rebuild.** Changing tactics does not require recompiling the mod;
   `/worlddriver reload` picks it up. Boss fights depend heavily on tuning — distance thresholds,
   when to swap bow for sword, evasion radii — and a compile per change is too slow.
2. **Contributions.** The community can write and share playbooks, much like data packs.
3. **A clean division of responsibility.** Java exposes primitives plus boss-specific perception;
   *how to fight* stays in the script.

The Java side therefore only needs to add a boss-specific perception API — the list of end crystals,
the dragon's phase, the boss's health stages — and to expose the high-level helpers in `prelude.js`.
`bot.combat`, `bot.goto` and `bot.setting` are already there. The playbooks are
`playbooks/dragon.js` and `playbooks/wither.js`.

> The counter-example is AltoClef, which writes dragon-killing as a Java task, so that changing
> tactics means recompiling. Scripting trades a little performance for iteration speed.

## 2. The perception the Java side has to add

New or extended, read-only, following the existing observation pattern:

```
mc.observe.boss              → { type, present, health, maxHealth, phase?, pos, bossBar% }
mc.query{q:'entities', filter:{type:'end_crystal'}}   → query already does this; mark whether
                                                        a crystal is caged (beam target)
mc.observe.threats           → already specified in the combat proposal, including
                                incomingProjectiles (dragon breath, wither skulls, ghast fireballs)
```

- **The dragon's phase** comes from reading `EnderDragonEntity`'s `phaseManager` — perch, strafing,
  charging_player, dragon breath and so on — and the playbook switches between melee, ranged and
  evasion accordingly.
- **Caged end crystals.** A crystal on top of a pillar surrounded by iron bars has to have the cage
  broken or be reached by climbing; an exposed one can simply be shot. The query marks which is which.

## 3. The Ender Dragon playbook (`playbooks/dragon.js`)

The stage machine, in pseudocode:

```js
// prerequisites: equipment from the equipment work (full armour, sword, bow and arrows),
// building material (~64 end stone), a water bucket, and optionally beds
bot.setting({ autoTotem:true, autoHeal:true, autoDodge:true });  // every reflex on

while (boss().present) {
  const crystals = query('entities', {type:'end_crystal'});
  if (crystals.length) {
    // stage 1: the crystals must go first, or the dragon heals back to full (a hard gate)
    const c = nearest(crystals);
    if (c.caged) { /* pillar up, goto{c.pos+up}, break it in melee; or shoot through the gap */ }
    else         { bot.combat({mode:'kill', target:{id:c.id}}); }  // exposed ones: just attack
    continue;
  }
  // stage 2: crystals cleared, fight the dragon itself
  const ph = boss().phase;
  if (ph === 'perch') {
    // the dragon has landed on the portal base -> close in and hit the head (the damageable part)
    bot.goto({pos: exitPortalTop}); bot.combat({mode:'kill', target:{type:'ender_dragon'}});
  } else {
    // in flight -> the head can only be shot; keep dodging charges and breath
    aimAndShootBow(dragonHead());
  }
  // dragon breath clouds: the dodge reflex and the breath tracker already handle leaving them
}
```

The important points:

- **The crystals are a hard gate.** Until every one is destroyed the dragon heals back to full, so the
  playbook must clear all of them before touching the dragon. AltoClef's dragon task has the same
  structure: gather end stone for a tower to reach the caged crystals, and only then engage.
- **The perch phase is the main damage window.** When the dragon lands on the base, melee against the
  head does by far the most damage.
- **Optionally, beds during the perch.** Placing a bed at the dragon's head and detonating it does
  enormous damage in one hit. This would be a `playbooks/dragon-beds.js` variant, matching AltoClef's
  bed-based dragon task.
- **Do not hit endermen**, since attacking one aggravates the rest. The playbook filters endermen out
  of its target selection; AltoClef excludes them from its force field for the same reason.

## 4. The Wither playbook (`playbooks/wither.js`)

The Wither depends far more than the dragon does on preparation and on the arena.

```js
// prerequisites, hard-checked at the top of the playbook; abort back to the caller if unmet:
//   a full set of enchanted armour, N healing potions, strength, resistance,
//   a good sword, and a sealed bedrock space
ensureGear(['enchanted_armor_full','healing_potions>=8','strength_potion','good_sword']) || abort();

const arena = findOrDigSealedSpace();  // sealed in bedrock, so it cannot smash through and escape
summonWither(arena);
bot.setting({ autoTotem:true, autoHeal:true, autoDodge:true, autoRetreat:false }); // no retreat in a sealed arena

while (boss().present) {
  const hp = boss().health / boss().maxHealth;
  if (hp > 0.5) {
    // stage 1: immune to projectiles above half health -> melee only; it explodes the
    // instant it is summoned, so back off and wait that out before closing in
    bot.combat({mode:'kill', target:{type:'wither'}});
  } else {
    // stage 2: below half health it flies erratically -> keep chasing in melee, and clear
    // the mobs it knocks loose as it smashes into the ground
    bot.combat({mode:'kill', target:{type:'wither'}});
  }
  // throughout: dodge wither skulls (incomingProjectiles); on the wither effect, drink milk or disengage
  if (hasEffect('wither')) drinkMilkOrRetreat();
}
```

The important points:

- **It explodes on summon.** The moment the last wither skull is placed it charges an explosion, so
  the playbook has to back away after summoning and wait for that to pass before approaching.
- **Half health is the dividing line.** Above 50% it is immune to projectiles, so only melee works;
  below 50% the immunity is gone but it flies erratically and burrows, so melee has to chase.
- **A sealed arena** in bedrock stops it from smashing through the terrain and escaping in the second
  stage. Digging or finding that arena reuses the placement work from the crafting execution layer and
  `clearArea`.
- **The wither effect.** Being hit by a skull stacks a damage-over-time effect; drink milk to clear it
  or back off.
- There is no AltoClef implementation of the Wither to compare against, so this one is our own. The
  projectile evasion, equipment prerequisites and melee loop it needs are all reused from the defence,
  combat and equipment work.

## 5. How a playbook attaches to the system

- A playbook is JavaScript that `mc.script.eval` can run, or a file at
  `config/worlddriver/scripts/playbooks/*.js` loaded by `/worlddriver reload`.
- The external trigger is `mc.bot.playbook{name:"dragon"}`, or running the playbook file directly
  through `mc.script.eval`. Inside its loop the playbook calls `mc.bot.combat`, `mc.bot.goto` and the
  rest, with the scheduler and the reflexes keeping the bot alive underneath it.
- A playbook runs inside the **existing Rhino sandbox** with no widening of its permissions. It only
  orchestrates `Driver.invoke(...)` calls and never touches a forbidden class.

## 6. Validation

A boss fight is hard to test deterministically, because entity AI is random. Validation is therefore
layered:

- **The perception layer.** In a StageWright scene, `/summon` an end crystal and a wither and assert
  that `mc.observe.boss`, the crystal query and the phase read are all correct.
- **The critical invariants.** For the dragon, assert that once every crystal is destroyed the
  dragon's health falls monotonically, which is what verifies that no crystal was missed. For the
  Wither, assert that the playbook enters its back-away state immediately after summoning, which
  verifies the explosion guard.
- **A smoke run.** Play a whole fight through in a creative world with buffs applied by `/effect`, and
  confirm by hand and by screenshot that it can be completed. This is a manual regression rather than
  a continuous-integration requirement, because of the entity randomness.

## 7. What this depends on

| What the playbook uses | Where it comes from |
|---|---|
| `bot.combat` — target selection, cooldown, criticals, kiting | the combat loop in the combat and defence proposal |
| The `autoTotem`, `autoHeal`, `autoDodge` and `autoRetreat` reflexes | the defensive reflexes in the same proposal |
| `ensureGear` and equipment | the equipment management in the same proposal |
| Placing arena blocks, `clearArea` | the existing build and `clearArea` verbs, plus crafting |
| Preemption and resumption, so a stage continues after an interruption | the scheduler in the execution-model proposal |
| High-level helpers | the existing Rhino prelude |
