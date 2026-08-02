# Feedback — first external consumer (winefoxs_spellbooks)

> Date: 2026-06-04 · Consumer: `winefoxs_spellbooks` (NeoForge ModDevGradle mod, MC 1.21.1)
> Goal: use WorldDriver as an automated-test harness to drive + assert another mod's
> gameplay (a custom Iron's Spellbooks school) from an AI agent.
> Author: Claude (agent), first time wiring WorldDriver into a *separate* mod project.

This is net-positive — the integration worked end to end on the first real attempt:
publish → consume → boot a 46-mod server → MCP up → drive + observe winefoxs content.
Below are the rough edges worth fixing, ranked by impact, plus a few MC-behavior notes
that the test-authoring docs should call out.

## What worked (no friction)

- `./gradlew :neoforge:publishToMavenLocal` produced a valid, self-contained NeoForge
  mod jar: `META-INF/neoforge.mods.toml` present, Rhino + netty-http + shaded snakeyaml
  all bundled. Coordinates `net.magicterra:worlddriver-neoforge:0.1.0+1.21.1`.
- Consuming from a **ModDevGradle** project (not Loom/Architectury) Just Worked: one
  `runtimeOnly("net.magicterra:worlddriver-neoforge:…")` line, FML discovered it
  ("Found valid mod file … with {worlddriver} mods"), constructed the entry, and it
  coexisted with Iron's Spellbooks + Touhou Little Maid + ~30 addons (46 mods total),
  no mixin/registry conflicts.
- On a **dedicated server** (`runServer`), `onServerStarting` brought up both RPC and
  MCP. `mc.system.version`, `mc.system.testOrigin`, `mc.action.runCommand`, `mc.query`,
  `mc.script.eval` all reachable via plain `curl` to `/mcp` (no separate `initialize`
  handshake needed). `runCommand` ran arbitrary commands including the consumer mod's
  own registry objects (`/effect give … winefoxs_spellbooks:mana_disruption`,
  `/damage … winefoxs_spellbooks:winefox_hex_magic`).

## Bugs / rough edges (actionable)

### 1. Prod jar bundles stray absolute paths (shadow misconfig) — low risk, looks wrong
`unzip -l worlddriver-neoforge-0.1.0+1.21.1.jar` shows top-level entries that should
not be there:
```
home/coder/
repository/net/
driver/gametests/
driver/scripts/
driver/structure/
http/cookie/        http/cors/   http/multipart/   http/websocketx/   handler/codec/
gdata/util/    extensions/compression/   pathfinder/moves/
```
`home/coder/` and `repository/net/` look like an absolute path (`/home/coder/.m2/
repository/net/...`) got swept into the shadow jar. The `http/*`, `handler/codec/`,
`extensions/compression/` look like a relocation that stripped the `io/netty/` prefix
from *some* classes (while `io/netty/` is also present un-stripped). Harmless at runtime
here, but it's a shadow `relocate`/`from` scoping bug worth cleaning up so the prod jar
contains only `net/magicterra/...` + intentionally-bundled libs.

### 2. POM declares `dev.latvian.mods:rhino` as a `runtime` dependency, but Rhino is
already shaded into the jar (`dev/latvian/mods/rhino/*.class` present). A consumer that
adds the dep naively gets **two** copies of Rhino on the classpath. I had to add:
```gradle
runtimeOnly("net.magicterra:worlddriver-neoforge:…") {
    exclude group: "dev.latvian.mods", module: "rhino"
}
```
Fix: either don't bundle Rhino and keep it a real dependency, or bundle it and drop it
from the published POM (`from components.java` is picking it up). Pick one.

### 3. `mc.query` `select:[…]` silently ignores unknown field names. I asked for
`select:["type","health","effects","uuid","id"]` and got back only `type` + `health` —
no error, no indication `effects`/`uuid`/`id` aren't supported. Either support them or
reject unknown projection keys with an `isError` so callers aren't misled.

### 4. No way to read an entity's **active MobEffects**. This is the single biggest gap
for testing effect-based mechanics (my use case applies/removes a custom `MobEffect`
marker and needs to assert it). Neither `mc.query` (no `effects` projection) nor
`mc.script.eval` (sandbox exposes only `Agent.invoke` routes, no MC classes) can read
`entity.getActiveEffects()`. **Requested enhancement:** an `effects` projection on
`mc.query q:"entities"` returning `[{id, amplifier, duration}]`. Would unlock a whole
class of buff/debuff regression tests.

### 5. `mc.action.runCommand` returns only `{ok:true, via:"brigadier"}` — it discards the
command's **result value / output**. `/data get entity … ActiveEffects`, `/execute
store result …`, and any `/execute if` count are therefore unreadable. Surfacing the
brigadier result int (and/or captured command feedback text) would give a second path
to assert state without new query projections.

### 6. `mc.query q:"entities"` rows can be **healthless** (`{}` or `{"health":0}`) for
non-living entities (items, XP orbs, dying mobs) caught in the radius. Minor, but a
`is_living` filter (mirroring the existing `is_hostile`) would make health-delta
assertions robust without client-side filtering.

## MC-behavior notes for the test-authoring docs (not WorldDriver bugs)

These bit me while driving and will bite every test author; worth a "writing reliable
gameplay assertions" doc section:

- **Invulnerability frames**: an entity hit by `/damage` ignores further damage for ~10
  ticks. Back-to-back `/damage` to the same entity silently no-ops the second hit. Use a
  fresh target per hit, or `mc.system.waitTicks` between hits. (This also has a real
  design consequence for the mod under test: any "deal damage then immediately AoE/chain
  more damage" mechanic must bypass victim i-frames.)
- **Difficulty scaling**: damage types with `scaling: when_caused_by_living_non_player`
  (and vanilla difficulty) make absolute damage non-deterministic (`/damage 6` dealt 5).
  Tests should `/difficulty normal` up front and assert **ratios/deltas**, not absolute
  hp.
- **Entity soup**: repeated summons in one arena cell accumulate; `kill @e[type=…]` +
  unique per-assertion positions keeps reads clean. A built-in "despawn all test
  entities" helper (or documenting the snapshot/restore region pattern for entities)
  would help.

## Port-conflict UX (minor)
A `:fabric:runClient` of WorldDriver itself was already holding the default 39800/39801,
so my consumer server's bind failed with `BindException: Address already in use` and the
only signal was an ERROR mid-log. Pinning the consumer to `-Dworlddriver.mcpPort=39810
-Dworlddriver.rpcPort=39811` fixed it. Consider: on bind failure, fall back to a random port
(you already write the chosen port to `run/agent-{mcp,rpc}.port`) instead of erroring, so
two instances coexist by default.

## Addendum — headless CLIENT e2e (Xvfb) also works

Drove a `runClient` of the consumer mod headlessly under `Xvfb :98` (software GL,
`LIBGL_ALWAYS_SOFTWARE=1`), no matchbox (only needed for screenshot framing; state
assertions read `mc.query`, so it's optional). Findings:

- Boot to MCP ~36–40s; full menu nav worked entirely through `mc.client.screen.tree`
  + `mc.client.input.click`: AccessibilityOnboarding → TitleScreen → SelectWorld/
  CreateWorld → in-world. `mc.observe.player` returning `present:true` is a clean
  in-world signal and hands back the player **UUID** (great — unlocks summoning an
  owned mob via `{OwnerId:[I;…]}` for owner-dependent tests).
- **`mc.observe.player` omits active effects** (same gap as #4 for entities). It
  returns pos/health/food/gameMode/hotbar/armor but no `effects[]`. This is the one
  thing blocking a *direct* assertion of a self-applied buff's stacks; I had to fall
  back to an indirect observable (an AoE radius that only widens at 3 stacks). An
  `effects` field on `mc.observe.player` would close this.
- The `worlddriver-rpc` skill's `rpc.py` worked verbatim in the consumer repo and
  **auto-resolved the port** from `run/worlddriver-rpc.port` with no `--port` needed. The
  "relaunch by PORT OWNER not `pkill -f`" gotcha in the skill is real and saved me
  (I hit the exact exit-144 it warns about before reading it).

> Not an WorldDriver bug, but surfaced via it: the consumer's modpack has a
> pre-existing crash (`ess_requiem.AdrenalineRushRemoved` NPEs on a null
> `MobEffectEvent.Remove.getEffectInstance()` when a wild `mowziesmobs` Elokosa
> ticks). It crashed the integrated server ~40s after world-load before I could act.
> Mitigation for e2e: create a controlled world (mob spawning off / `kill @e` at
> spawn). Mentioning because a `mc.world.*` "freeze entities" or a documented
> "controlled test world" recipe would make consumer e2e more robust.

## Addendum 2 — headless-server gameplay-timing gotchas (HIGH impact, cost hours)

These surfaced while testing a *time-dependent* mechanic (a MobEffect that self-explodes
on natural expiry). They are not WorldDriver bugs, but they are the single most important
thing the test-authoring docs could warn about, because on a **dedicated server with no
player connected** the world looks alive (commands run, entities summon, queries return)
yet is silently **not simulating**. Every symptom mimics a mod bug.

### A. No player ⇒ no chunk is *entity-ticking* ⇒ summoned mobs are frozen
On `runServer` with nobody logged in, no chunk reaches the entity-ticking ticket level.
Consequences, all silent:
- MobEffect durations **do not count down** → effects never expire, `MobEffectEvent.Expired`
  never fires, `applyEffectTick`/`shouldApplyEffectTickThisTick` never run.
- No AI, **no gravity** (a `{NoGravity:0}` mob at y=200 just hovers), no mob burn-in-sun,
  no despawn timers, no `/effect`-driven damage (poison/wither never tick).
- BUT: `/summon`, `/effect give`, `/damage`, and event-driven handlers
  (`LivingDamageEvent` from a `/damage` command) **all work**, because they're driven
  synchronously by the command, not by the tick loop. So detonation-on-hit tests pass
  while expiry/timer tests mysteriously fail.

**Fix in test scripts:** `/forceload add <cx> <cz>` the chunk(s) your entities live in
*before* summoning, and place entities inside that chunk (chunk `0 0` covers blocks 0–15).
Verified: with forceload a poison effect ticked HP down and a custom effect's expiry tick
fired; without it, identical setup is inert. `/forceload remove` to clean up.
**Doc request:** a one-liner in the "writing reliable gameplay assertions" section, or a
`mc.world.tickChunk`/`forceLoad` convenience that also bumps the ticket to entity-ticking.

### B. No player ⇒ no `OnDatapackSyncEvent` ⇒ Iron's Spellbooks spell config never builds
Iron's `SpellConfigManager` builds each spell's resolved config (school, rarity, cooldown,
…) lazily inside an `OnDatapackSyncEvent` handler, which fires on **player login** or
`/reload` — *not* at headless server start. Until it runs, `getSpellConfigValue(spell, …)`
returns the **parameter default**, so every spell's school resolves to the default
(`evocation`) instead of its configured school. For a custom-school mod this means the
spell's `SpellDamageSource` carries the **wrong damage type** (`evocation_magic` instead of
`winefoxs_spellbooks:winefox_hex_magic`) until a sync happens. Cost me a long detour
chasing a "school not wired" bug that was actually "config never built".

**Fix in test scripts:** run `/reload` once after boot (it posts `OnDatapackSyncEvent`,
which builds the config) before asserting anything that depends on a spell's resolved
school/damage-type. Confirmed: pre-`/reload` damage type = `evocation_magic`; post-`/reload`
= the real custom type. (A connected Xvfb client also fixes it via the login sync.)
**Doc request:** call this out for any Iron's-Spellbooks-family consumer; more generally,
"config-on-datapack-sync systems need a `/reload` on a headless server."

### C. `mc.query` `in_radius` missed a present entity
Center `{8,200,8}`, `in_radius:3`, an entity standing at `{8,200,9}` (distance 1) was
**not** returned — the row set listed only the center entity — even though that same entity
was concurrently taking AoE damage from the mechanic under test (so it was unquestionably
loaded and at that position). I couldn't pin the exact cause (possible: radius is computed
against a stale/last-tick position, or excludes entities sharing the query's own cell, or a
spherical-vs-AABB mismatch). Repro is setup-sensitive, but worth a look — silent omission
from a radius query makes "count entities near X" assertions unreliable. A position-explicit
`select:["x","y","z"]` helped me notice it; an `is_living`/exact-AABB mode would help.

## Net
Harness is good and the value is real: I could summon entities, apply the consumer mod's
effects, deal its custom school damage, and assert health deltas — i.e. write real
gameplay regression tests for a third-party mod from an agent. The MobEffect-read gap
(#4) and command-output gap (#5) are the two enhancements that would most expand what's
assertable.
