# Archive

Everything below this directory is a historical record, kept for provenance. Each file
describes the state of the project at the time it was written, and none of it has been
updated since. Nothing here should be read as documentation of current behaviour: where a
document in this directory disagrees with the source, with `docs/design/`, or with
`docs/dev/`, the archive is the one that is out of date.

Two of these things are kept verbatim on purpose, because they are evidence: the external bug
reports, in the words of the people who filed them, and the audit trail of a test-suite
migration. Tidying either into better prose would destroy what makes it worth keeping.

Keeping a record verbatim is not the same as keeping it unreadable. The documents this project
wrote about its own work were written in Chinese and have been translated into English, because
their value is the reasoning and the readings they record rather than the sentences they were
first written in; every measurement, coordinate, threshold and identifier is unchanged. Each has
an English preamble saying what it records, what came of it, and where the current description
lives.

The reports under `feedback/` are the exception and are never edited, because they are someone
else's words. One of them quotes a line of Chinese chat as evidence; it stays exactly as filed.

| Path | What it is |
|---|---|
| `feedback/` | Reports from people outside the project who used the mod and hit problems, as filed. |
| `test-framework-migration.md` | The audit trail of retiring the legacy `@GameTest` suite in favour of StageWright scenes. |
| `test-framework-origin-spec.md` | The specification that argued for building that framework, and the incident record behind it. |
| `centre-snap-teleport-audit.md` | A one-off audit of the ten places where a process teleports the body to its own cell centre. |
| `execution-model-proposal.md` | The priority-chain scheduler, as originally proposed. |
| `perception-decision-boundary-proposal.md` | The decision-layering rule as originally written, with a misalignment list that is now stale. |
| `crafting-knowledge-proposal.md` | Recipe lookup and the acquisition planner, as originally proposed. |
| `combat-and-defense-proposal.md` | The combat and defensive-reflex layer, as originally proposed. |
| `boss-playbooks-proposal.md` | Scripting boss fights instead of hard-coding them, as originally proposed. |
| `intent-supervisor-proposal.md` | An escalation channel from the engine to the caller. Never built. |
| `surface-first-water-navigation-proposal.md` | A water-navigation model that was abandoned, plus the field readings taken while evaluating it. |

For why the live code is shaped the way it is, read `docs/design/` instead.
