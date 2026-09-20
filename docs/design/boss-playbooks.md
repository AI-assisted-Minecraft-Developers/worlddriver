# Boss fights are scripts, not Java

## The problem

A boss fight is a multi-stage state machine, and each stage is mostly an arrangement of
primitives the driver already has: move there, fight that, fly, equip, use an item, read the
boss's current state, turn a reflex on or off. Almost none of it is new capability.

But it is extremely sensitive to tuning — which distance, when to switch weapons, how wide to
dodge — and tuning something that lives in Java means recompiling the mod between attempts.

## What was decided

**The fight logic lives in the in-process script engine, and Java supplies only the primitives
and the boss-specific perception.** A playbook is a JavaScript file in the run directory's script
folder, and reloading it takes a command rather than a build.

Three things follow, and all three were the point:

Iteration is fast, which for a behaviour that is tuned by trial is the difference between
converging and not.

A playbook is contributable. Someone can write one, share it, and drop it in, the same way a data
pack works — without touching the mod.

The responsibilities separate cleanly. Java owns the primitives and the perception a fight needs
that nothing else does: the crystal list, the dragon's current phase, boss health tiers, incoming
projectiles. The script owns *how to fight*, which is exactly the part that is opinion rather than
mechanism.

The cost of writing it in Java instead is concrete and was the deciding argument: the comparable
project this was measured against implements its dragon fight as a Java task, so changing the
strategy means rebuilding.

## What this rules out

No boss fight is implemented as a hard-coded process. If a fight needs something the script cannot
express, the correct response is to add the missing **primitive** to the driver, not the fight to
Java — otherwise the boundary erodes one special case at a time and the reload loop is lost.

Playbooks run in the same script engine as everything else, with the same access and the same
constraints; there is no separate privileged mode for them.

## Where to look

- `api/DriverApi.java` — the `mc.bot.playbook` route and the binding seam its runner is installed
  through.
- `common/src/main/resources/data/worlddriver/scripts/playbooks/` — the shipped playbooks, currently
  one for the ender dragon and one for the wither, each carrying its own strategy notes at the top.
- `script/` — the engine, the shared scope and the reload path.
- `docs/guide/transports.md` — how to run a script and what a script can reach.
