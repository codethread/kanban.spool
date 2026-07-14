# Kanban spool

> This is the **contract** doc: the board model, the lanes and priority ladder,
> the `kanban/*` attribute vocabulary, and the CLI op surface. Its companion is
> [`kanban.cookbook.md`](./kanban.cookbook.md) — worked composition recipes
> (how/why you run work through the board). Exact signatures live in the source
> docstrings ([`src/skein/spools/kanban.clj`](./src/skein/spools/kanban.clj)).
> Reach for the cookbook when you want a runnable flow, and this doc for what
> the board guarantees.

The kanban spool is the user-facing work board held entirely in Skein strands. It tracks **user↔agent** work: everything a user asks for becomes a `feature` card (occasionally grouped under an `epic`), and every agent working directly with a user works under a claimed card. It complements — never replaces — the execution strands that hang beneath cards.

## Model

Each card is one strand whose `kanban/status` places it in a board lane:

- **refinement** — an idea or undecided direction; never actionable until a human explicitly runs `kanban promote`.
- **pending** — the actionable queue; `kanban next` serves the highest-priority (p1 first) oldest pending feature.
- **claimed** — work has started; the claim stamps who is driving it and where.
- **in_review** — work is under review. Rework moves it back to `claimed`; finishing moves it to `closed`.
- **closed** — the strand is closed and `kanban/status` records the explicit outcome (`done`, `abandoned`, ...).

Every card also carries a `kanban/priority` that orders lanes and `kanban next` (oldest first within a priority):

- **p1** — immediate blocker; must be done first (e.g. anything requiring a mill/weaver restart or a breaking change).
- **p2** — high value bug fixes or high-impact features.
- **p3** — the default: most things.
- **p4** — maybe one day; the never-ending someday list.

Card state lives under the `kanban/*` attribute topic:

| Attribute | Meaning |
| --- | --- |
| `kanban/card` | String `"true"` for card strands. |
| `kanban/type` | `feature` (default) or `epic` (grouping card; `parent-of` its features). |
| `kanban/status` | `refinement`, `pending`, `claimed`, `in_review`, or an explicit closed outcome. |
| `kanban/priority` | `p1`, `p2`, `p3` (default), or `p4`; cards without the attribute read as `p3`. |
| `kanban/source` | Optional path or URL for design context (RFC, feature folder). |
| `kanban/note` | `"true"` decoration on strands linked to a card or task by the blessed `notes` relation. |
| `kanban/task` | `"true"` on task strands: `parent-of` children of a feature card whose status is derived, never stored. |
| `kanban/devflow` | Optional devflow run-id (the devflow feature name); `kanban card` joins the run's stage and ready steps. |
| `owner` | Who is driving the work; required at claim. |
| `branch` | The work branch; required at claim. |
| `worktree` | Optional worktree path. |

The card is the **work root**: execution strands hang under it with `parent-of` edges, and the claim-time `branch`/`owner`/`worktree` stamp makes the whole subtree discoverable by branch (see the repo's `strand branches` convention). Kanban never tracks execution runs directly, but because they hang under card descendants, `strand subgraph <card-id>` (and future queries) can project every strand working under a feature.

**Relating work.** Relate cards or tasks to each other with `depends-on` edges (`strand update <a> --edge depends-on:<b>`); agents check the `:related` list in `kanban card <id>` when claiming or resuming so blockers and dependents surface without extra queries.

**Viewing work in a branch.** `strand branches "$(git branch --show-current)"` shows the feature cards and their substrands stamped on the current branch.

## Task tier

A feature card decomposes into **tasks**, the `epic > feature > task` tier. A task is an ordinary strand hung under the card by a `parent-of` edge and stamped `kanban/task=true`; other children (plans, reviews, notes) never read as tasks. `strand kanban task add <feature> "<title>"` stamps the marker and lays the edge, with `--body` for longer context and repeatable `--depends-on <id>` for the concurrency DAG; bare `strand add` under the card still works. `strand kanban task list <feature>` projects the card's tasks with their derived status and each task's newest note (`latest-note`).

Task status is **derived, never stored**: a pure function of the core strand graph plus the core `owner` attr, reading no execution-engine vocabulary.

| Status | Derives from |
| --- | --- |
| `done` | the task strand is closed. |
| `blocked` | active, with a `depends-on` target that is not yet closed. |
| `doing` | active, every dependency closed, and an `owner` stamped. |
| `ready` | active, every dependency closed, and no `owner`. |

Because nothing writes the status it cannot drift the way a stored field would: delete every execution-engine record and the derivation still computes. The `depends-on` edges that split `blocked` from `ready` are the same DAG that orders concurrent work, so no second structure tracks it. The first `doing` task under a card is its **doing-task** — the board's live resume signal.

## Notes and resume

Notes are strands linked to their target by the blessed `notes` relation, not card attributes or `parent-of` children. Kanban marks its notes with
`kanban/note`; that attribute is decoration for board views. Concurrent agents do not race a
read-merge-write cycle, and every note keeps its own timestamp and author.

A note targets a **card or a task** — anything else fails loudly. Progress notes belong on the doing-task; the card's own trail stays a lean handover summary. Bulk content (review findings, pasted output) always goes on a task note:

```sh
strand kanban note <task-id> "Decided X because Y" --author claude --kind decision
strand kanban note <card-id> "Handover: impl landed, docs next" --author claude
```

Note as you go, not at the end: record what is done, what is next, validation state, and gotchas on the doing-task as you reach them. Each task projection (`card <id>`, `task list`, the board's doing-task) carries the task's newest note as `latest-note`, so a cold agent resumes from the doing-task and its `latest-note` with no prior context. Even with no notes the doing-task still carries its body, deps, and lane:

1. `strand kanban board` — claimed cards show owner, branch, worktree, and their doing-task (with its `latest-note`).
2. `strand kanban card <id>` — the full card: tasks with their derived statuses and `latest-note`, card notes (newest first), active work subtree, and the ready frontier.

Views compact what they show: note bodies past a cap (600 characters) are clipped and marked `truncated: true`; the full text stays on the note strand (`strand show <note-id>`). This keeps one long note from drowning the resume read — the card view is a projection, never the storage.

A note may carry a `note/kind` decorating attr (`--kind <value>`) that views can fold or filter by; it is a core `note` attribute, not kanban-specific. The set is open and guidance-only, never an enforced enum, with four blessed values: `activity` (a progress log), `decision` (a durable choice and why), `review-dump` (bulk findings), and `summary` (a run or session wrap-up). An absent `note/kind` reads as `activity`; any other value stays a valid userland annotation. Compact note projections surface it as `kind`.

## CLI op

Install registers one declared-subcommand operation. `strand help kanban` shows the machine-readable verb/flag surface, and `strand kanban help`, `strand kanban -h`, and `strand kanban --help` return that same detail projection. Bare `strand kanban` and unknown verbs fail loudly with the available subcommand names.

```sh
strand kanban prime
strand kanban about
strand kanban add "Feature idea" [--body "Longer context"] [--source docs/rfcs/...] [--status pending|refinement] [--type feature|epic] [--epic <epic-id>] [--priority p1|p2|p3|p4]
strand kanban board
strand kanban card <id>
strand kanban next
strand kanban priority <id> <p1|p2|p3|p4>
strand kanban promote <id>
strand kanban claim <id> --owner <name> --branch <branch> [--worktree /path] [--devflow <run-id>]
strand kanban note <card-or-task-id> <text> [--author <name>] [--kind activity|decision|review-dump|summary]
strand kanban task add <feature> <title> [--body "Longer context"] [--depends-on <id> ...]
strand kanban task list <feature>
strand kanban review <id>
strand kanban rework <id>
strand kanban finish <id> [--outcome done|abandoned]
```

`prime` is the agent onboarding surface: a superset of `about` that adds the working discipline (work under a claimed card, the pick-up-next flow, the note-as-you-go/resume-from-task contract, adjacent-work awareness, and branch visibility) so repo agent docs point at it instead of duplicating conventions that then drift from the spool. `about` stays the terse command manual.

`board` returns the grouped snapshot (epics, refinement/pending/claimed/in_review lanes sorted p1-first then oldest, closed count); active cards with a status outside the known lanes surface in `unknown-status` rather than being hidden. It also returns `needs-review`: a vector aggregated across claimed and in-review feature cards of `{:card :item}` entries (plus `:branch` from the claim stamp), one per card descendant that is active, in the engine ready frontier, and marks human review (`hitl`/`workflow/hitl` true, or `kind` `review`), sorted by card id then item id — the always-present cross-card review queue. `next` returns the highest-priority (p1 first) oldest active pending feature (epics are never served). `priority` restamps an active card's `kanban/priority` and fails loudly on unknown values or closed cards. `promote` is the explicit human command that moves a refinement card into the pending lane. `claim` fails loudly without `--owner` and `--branch` and refuses epics; `--worktree` is optional for direct work in the main checkout. `review` moves a claimed card to `in_review`; `rework` moves it back to `claimed`; `finish` closes a claimed or in-review card with the outcome status.

`card` returns the resume view (card, tasks with derived statuses and `latest-note`, compact card notes, active work, ready frontier) plus `related`: a vector of `{:relation :strand}` entries for every `depends-on` edge touching the card — `depends-on` when the card is the dependent, `depended-on-by` when it is the dependency — sorted by other-endpoint id. Cards stamped with `kanban/devflow` (via `claim --devflow`) also carry `devflow`: the named run's current stage and its ready steps (see [Devflow dependency](#devflow-dependency)). `task add` hangs a task under a feature card (marker attr plus `parent-of`, optional `--depends-on` edges) and `task list` projects that card's tasks with their derived statuses (see the Task tier section); both fail loudly on a missing, non-card, or non-feature target — epics group features and never own tasks directly.

For bulk authoring, the `kanban-batch` weave pattern creates pending feature cards with bodies and `depends-on` edges atomically:

```sh
strand weave --pattern kanban-batch --input '{"items":[{"key":"design","title":"Design feature","body":"...","priority":"p2"},{"key":"docs","title":"Write docs","deps":["design","existing-strand-id"]}]}'
```

## Human view

The CLI stays JSON-only (TEN-006); the human rendering lives on the REPL surface:

```sh
printf "(do (require 'skein.spools.kanban) (skein.spools.kanban/print-board!))\n" | mill weaver repl --stdin
```

`print-board!` prints a stacked-lane ASCII board (epics, refinement, pending, claimed and in_review with owner/branch and doing-task, needs-review); `board-str` is the pure renderer over the `board` result for reuse.

## Offline export

Install also registers `kanban-export`, a read-only op that bundles one card's full `parent-of` subtree plus its internal `depends-on` edges in a single call:

```sh
strand kanban-export <card-id>
```

It returns the root, every strand beneath it via `parent-of` (all lifecycle states, so closed work still counts), the `parent-of` hierarchy edges, and the `depends-on` edges internal to that subtree. It is a pure graph projection — presentation and the progress rollup live in the consumer.

[`scripts/kanban-export/kanban-export.ts`](./scripts/kanban-export/kanban-export.ts) is that consumer: a dependency-free Bun renderer that turns the export payload into a single self-contained HTML file with an overall progress rollup and a per-child breakdown. `make kanban-export ID=<card-id> [ARGS='--open']` runs it directly; `make kanban-serve ID=<card-id> [PORT=8000]` exports to `/tmp/kanban-export` and serves it over the LAN.

## Devflow dependency

Kanban requires [`skein.spools.devflow`](https://github.com/codethread/devflow.spool) in exactly
one seam: the `kanban card` view. A card optionally names its devflow run through the
`kanban/devflow` attribute (`claim --devflow <run-id>` stamps it; the devflow feature name is the
workflow run-id), and `card` then joins:

```json
"devflow": {"feature": "widgets-run",
            "stage": "intake",
            "next-steps": [{"id": "...", "title": "...", "kind": "step", "stage": "intake"}]}
```

A stamped card whose run has no active devflow root — not started, finished, or archived —
projects a `null` stage with no steps rather than hiding the key. Kanban only reads devflow
state; it never writes it, and devflow never depends on kanban.

The dependency is deliberately one-way and one-seam. Consumers approve the devflow spool as its
own `spools.edn` coordinate and activate it before kanban; the [README](./README.md) carries the
full recipe.

## Queries

Install also registers:

- `kanban-cards` — all kanban card strands.
- `kanban-unstarted` — active cards with `kanban/status=pending`.
