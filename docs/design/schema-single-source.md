# One schema, advertised and enforced

## The problem

Parameter validation existed on exactly one side of one transport. A call that arrived over the
WebSocket endpoint, through the in-process script engine, or from an internal caller reached its
route handler completely unchecked, and a wrong key or a wrong type then failed in whatever way
that particular handler happened to fail. A caller who sent `command` instead of `cmd` to the
run-a-command verb got an error about the command being empty — a message about the wrong thing,
three layers away from the mistake.

The cause was structural rather than a forgotten check. The schema builder rendered its typed tree
into a plain map at definition time and then threw the tree away. At run time there was nothing
left to validate against, so validation could only ever be a second, hand-written set of rules —
which is a new source of drift, not a fix.

## What was decided

**The tool record keeps the typed schema and renders only when the catalogue is advertised.** The
advertisement and the enforcement are then two walks over the same object. They cannot drift,
because there is nothing to drift between; the advertised shape and the enforced shape are the same
value.

**Validation is installed at the single point where every caller converges.** `DriverApi.route` is
the one dispatch point, so the validator runs there, before any handler, and therefore applies
identically to the Model Context Protocol endpoint, the WebSocket endpoint, the script engine, and
any internal caller.

**The validator is injected as a plain function rather than imported.** The core must not depend on
the transport layer; the tool catalogue supplies a lookup from verb name to schema at start-up, and
the core knows only that it has a validating function. A start-up invariant asserts that every route
has a schema, so a verb registered without one is a boot failure rather than an unvalidated surface.

**Strictness is absolute from the first version.** No warn-then-enforce phase, because warning logs
are not read, and no exemption for internal callers, because an internal caller is bound by the same
contract. An undeclared key is an error; a declared type is the only accepted type. The one accommodation
is that a whole-numbered floating-point value is accepted where an integer is declared, because JSON
decoders routinely produce them. An explicit null is treated as absence: skipped where the field is
optional, reported as missing where it is required.

**The error names both problems at once.** A call with a missing required key and an unexpected one gets
both in a single message, so the caller can correct it in one round trip instead of discovering the second
mistake after fixing the first.

## What this rules out

There is no second validation rule set anywhere, and adding one would recreate exactly the drift this
removed.

There are no soft coercions: a string is not accepted where a number is declared, and an out-of-range
enumeration value is not silently clamped. A caller that sends the wrong thing is told so.

Because an undeclared key is an error, **removing a field from a schema is a hard cut**. That property is
relied on deliberately elsewhere: when every route condition moved into one object, the old top-level
fields were simply deleted, and a call using one now fails in validation with the key named. The
migration guidance lives in the tool description rather than in the error, because the error cannot know
where a field went.

Validation also has to be the reason a parameter is refused where a verb does not support it. A body
name passed to a verb that does not drive a body is rejected by the schema rather than accepted and
explained by the handler — which is both more honest and cheaper, since the alternative is shipping
unusable parameter documentation to every model client.

## Where to look

- `mcp/schema/ToolSchema.java` — the record that keeps the typed tree and renders on demand.
- `mcp/schema/SchemaValidator.java` — the walk, the accepted shapes per node type, and the aggregated
  message.
- `api/ParamsValidator.java` — the injected function, declared in the core.
- `api/DriverApi.java` — the single dispatch point where it runs.
- `WorldDriverCommon.java` — where the catalogue's lookup is installed, and the start-up invariant that
  every route has a schema.
- `mcp/catalog/ToolCatalog.java` — schema lookup by verb name, and the namespace policy for
  extension-registered verbs.

The behaviour is asserted by the schema-validation scripts in
`common/src/main/resources/data/worlddriver/scripts/validation/`, which run on every transport and
compare the results.
