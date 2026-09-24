# Right-clicking an entity is a third mode, not a fourth verb

## The problem

The driver could left-click an entity, and it could right-click a block or the air, but it had no
way to right-click an *entity*. In vanilla that single code path is behind mounting a boat or a
saddled horse, opening villager trade, shearing, milking, feeding, taming, leashing, and equipping
an armour stand — so a large family of ordinary actions was simply unreachable.

There was no workaround. Screen-coordinate clicks only work when a screen is open and cannot produce
an in-world click; the key-injection surface has no mouse buttons; and synthetic clicks at the
window-system level never reach the game's input layer at all.

## What was decided

**It is a third dispatch mode on the existing use-item verb, keyed on an entity id.** The route
already dispatched on parameters: an entity id means use *on* that entity, a position means use on
that block, neither means use in the air. Adding a verb would have been the obvious alternative and
was rejected, because every tool's schema is sent in every prompt to every model client — a new verb
is a permanent tax on every conversation, and extending an existing one is free.

**Behaviour mirrors vanilla's own entity branch exactly**: attempt the positional interaction first,
and if that did not consume the action attempt the plain one, then swing. Parity is then a property
of having copied the sequence rather than of having reimplemented the outcome. A mod that hooks either
step sees what it expects.

**The result reports what actually landed.** A mount and an opened menu are both observable, so the
call reports whether the bot ended up riding and whether a screen opened, instead of forcing a
follow-up observation. That turned out to need care: the server applies the mount and opens the menu
on a *later* tick, so the result compares against the state captured before the call and then polls
off the game thread. Blocking the client thread to wait would deadlock the very packet processing
being waited for.

**Sneaking is an explicit parameter and is restored afterwards.** Several interactions are sneak-gated,
and the interaction packet snapshots the sneak state, so the modifier has to be held across exactly that
call. Leaving a modifier key stuck is its own class of contamination, so it is released in a finally
block.

**Reach is not validated on the caller's side.** The server silently rejects an over-reach interaction,
and the call reports the measured distance so the caller can check it. That matches the attack verb; the
alternative is two reach rules that will eventually disagree with each other.

## What this rules out

There is no separate interact verb, and no entity-handle system beyond the integer id that the entity
query already returns — a second handle system would only create drift between the two.

There is no key-press fallback. The three parameterised modes cover the cases that motivated this, and a
parameterised call beats one that depends on where the crosshair happens to point.

## Where to look

- `bot/InteractionCommands.java` — the vanilla sequence, the sneak handling, and the deferred result poll.
- `api/DriverApi.java` — the three-way dispatch on the use-item route.
- `api/BodyInteractions.java` — the same operation for a bot player that is not the client's, where the reach
  check has to be explicit because there is no server to silently drop it.
- `mcp/catalog/BotTools.java` — the advertised schema and the documented return shape.

This capability exists because someone outside the project needed it and said so; the report is
`docs/archive/feedback/2026-07-10-gui-layout-regression-no-entity-interact.md`.
