# Kanban spool

> This is the **contract** doc: the board model, the lanes and priority ladder,
> the `kanban/*` attribute vocabulary, and the CLI op surface. Its companion is
> [`kanban.cookbook.md`](./kanban.cookbook.md) — worked composition recipes
> (how/why you run work through the board). Exact signatures live in the source
> docstrings ([`src/ct/spools/kanban.clj`](./src/ct/spools/kanban.clj)).
> Reach for the cookbook when you want a runnable flow, and this doc for what
> the board guarantees.

The kanban spool is the user-facing work board held entirely in Millstrand strands. It tracks **user↔agent** work: everything a user asks for becomes a `feature` card (occasionally grouped under an `epic`), and every agent working directly with a user works under a claimed card. It complements — never replaces — the execution strands that hang beneath cards.

## Model

Each card is one strand whose `kanban/lane` places it in a board lane:

- **refinement** — an idea or undecided direction; never actionable until a human explicitly runs `kanban promote`.
- **pending** — the actionable queue; `kanban next` serves the highest-priority (p1 first) oldest pending feature.
- **claimed** — work has started; the claim stamps who is driving it and where.
- **in_review** — work is under review. Rework moves it back to `claimed`; finishing moves it to `closed`.
- **closed** — the strand is closed, has no active lane, and `kanban/outcome` records the explicit result (`done`, `abandoned`, ...).

Every card also carries a `kanban/priority` that orders lanes and `kanban next` (oldest first within a priority):

- **p1** — immediate blocker; must be done first (e.g. anything requiring a mill/weaver restart or a breaking change).
- **p2** — high value bug fixes or high-impact features.
- **p3** — the default: most things.
- **p4** — maybe one day; the never-ending someday list.

Card state lives under the `kanban/*` attribute topic, and labels under the sibling `kanban.label/*` topic:

| Attribute | Meaning |
| --- | --- |
| `kanban/card` | String `"true"` for card strands. |
| `kanban/type` | `feature` (default: cards without the attribute read as features) or `epic` (grouping card; `parent-of` its features). Any other value is drift and fails loudly. |
| `kanban/lane` | Active lane: `refinement`, `pending`, `claimed`, or `in_review`. Removed on finish. |
| `kanban/outcome` | Explicit finish result on a closed card. Features record any outcome (default `done`); epics record `done` (completed) or `abandoned`. |
| `kanban/abandon-restore-lane` | Reversibility marker written only by an epic **abandon** cascade: the lane a card was closed *from*. `kanban reopen` restores each marked card to it and clears the marker, so reopen reverses exactly what the abandon closed. |
| `kanban/priority` | `p1`, `p2`, `p3` (default), or `p4`; cards without the attribute read as `p3`. |
| `kanban/source` | Optional path or URL for design context (RFC, feature folder). |
| `kanban/task` | `"true"` on task strands: `parent-of` children of a feature card whose status is derived, never stored. |
| `kanban/run-id` | Optional opaque run pointer; agents query its workflow directly. |
| `kanban.label/<slug>` | String `"true"`, one key per label the card carries. Free-form: no vocabulary is registered up front. |
| `owner` | Who is driving the work; required at claim. |
| `branch` | The work branch; required at claim. |
| `worktree` | Optional worktree path. |

**Labels.** Lanes are a card's position and epics are its grouping; labels are the free-form
axis that cuts across both. A label is a slug matching `[a-z0-9][a-z0-9-]*` — inputs are trimmed
and lowercased, so `Perf` and `perf` are one label, and anything outside the grammar fails loudly
rather than being coerced. Each label is its own attribute key rather than an entry in one list
value, so adding or removing a label is a single-key delta: two agents labelling the same card
concurrently cannot overwrite each other. There is no label registry to populate first — a label
exists the moment a card carries it, and `kanban label list` reports the labels in use across
active cards so an agent reuses an existing one instead of coining a near-duplicate. `board` and
`next` take repeated `--label` flags that intersect (a card must carry every one).

The card is the **work root**: execution strands hang under it with `parent-of` edges, and the claim-time `branch`/`owner`/`worktree` stamp makes the whole subtree discoverable by branch (see the repo's `strand branches` convention). Kanban never tracks execution runs directly, but because they hang under card descendants, `strand subgraph <card-id>` (and future queries) can project every strand working under a feature.

**Relating work.** Relate cards or tasks to each other with `depends-on` edges (`strand update <a> --edge depends-on:<b>`); agents check the `:related` list in `kanban card <id>` when claiming or resuming so blockers and dependents surface without extra queries.

**Viewing work in a branch.** `strand branches "$(git branch --show-current)"` shows the feature cards and their substrands stamped on the current branch.

### Finishing an epic

`finish` is polymorphic on `kanban/type`. A **feature** closes from the `claimed` or `in_review` lane, as always. An **epic** is grouping-only — it is never claimed — so it closes from the `refinement` or `pending` lane instead; any other epic lane or state fails loudly. An epic takes exactly two outcomes:

- **`finish <epic> --outcome done`** (complete) — guards that every direct feature child is `closed`; otherwise it fails loudly, naming each open child and its lane. A completed epic is `closed` with `kanban/outcome=done` and no restore marker.
- **`finish <epic> --outcome abandoned`** (abandon) — allowed regardless of child states, and **cascades**: every direct feature child not already `closed` records its current lane in `kanban/abandon-restore-lane`, then closes (`kanban/outcome=abandoned`, lane cleared). A child that was *already* `closed` is finished work — it is left untouched and gets no marker. The epic records its own pre-abandon lane the same way before closing abandoned.

**`reopen <epic>`** is the inverse of **abandon only**. It requires a `closed` epic with `kanban/outcome=abandoned` — a completed (`done`) epic, or any non-abandoned card, is refused. Reopen returns the epic to its stored `kanban/abandon-restore-lane` (state `active`, outcome and marker cleared), then reverses the cascade: every direct feature child that is `closed` **and** carries the marker is reopened to its own stored restore lane and cleared. A child that was legitimately `done` before the abandon carries no marker and stays closed. Reopen is a true inverse — it reopens exactly what a matching abandon closed, never a blanket reopen.

Every attribute clear above is the trusted nil patch (the attribute goes absent) — a cleared lane, outcome, or marker is never a blank string.

## Task tier

A feature card decomposes into **tasks**, the `epic > feature > task` tier. A task is an ordinary strand hung under the card by a `parent-of` edge and stamped `kanban/task=true`; other children (plans, reviews, notes) never read as tasks. `strand kanban task add <feature> "<title>"` stamps the marker and lays the edge, with `--body` for longer context and repeatable `--depends-on <id>` for the concurrency DAG; bare `strand add` under the card still works. `strand kanban task list <feature>` projects the card's tasks with their derived status and each task's newest note (`latest-note`).

Task status is **derived, never stored**: a pure function of the core strand graph plus the core `owner` attr, reading no execution-engine vocabulary.

| Status | Derives from |
| --- | --- |
| `closed` | the task strand is closed. |
| `blocked` | active, with a `depends-on` target that is not yet closed. |
| `doing` | active, every dependency closed, and an `owner` stamped. |
| `ready` | active, every dependency closed, and no `owner`. |

Because nothing writes the status it cannot drift the way a stored field would: delete every execution-engine record and the derivation still computes. The `depends-on` edges that split `blocked` from `ready` are the same DAG that orders concurrent work, so no second structure tracks it. The first `doing` task under a card is its **doing-task** — the board's live resume signal.

## Notes and resume

Notes are strands linked to their target by the blessed `notes` relation, not card attributes or
`parent-of` children. They carry the primitive's `note/text`, `note/at`, and optional `note/by`;
kanban inherits the primitive's optional `note/kind` view hint. Concurrent agents do not race a
read-merge-write cycle, and every note keeps its own timestamp and attribution.

A note targets a **card or a task** — anything else fails loudly. Progress notes belong on the doing-task; the card's own trail stays a lean handover summary. Bulk content (review findings, pasted output) always goes on a task note:

```sh
strand kanban note <task-id> "Decided X because Y" --by claude --kind decision
strand kanban note <card-id> "Handover: impl landed, docs next" --by claude

# Read long or code-bearing text from stdin.
strand --stdin kanban note <task-id> :stdin --by claude --kind review-dump <<'NOTE'
Review findings:
- The parser rejects the removed flag.
- Stored attribution remains under `note/by`.
NOTE

# Or resolve a named payload from a file.
strand --payload review=review-findings.md kanban note <task-id> :payload/review --by claude --kind review-dump
```

Note as you go, not at the end: record what is done, what is next, validation state, and gotchas on the doing-task as you reach them. Each task projection (`card <id>`, `task list`, the board's doing-task) carries the task's newest note as `latest-note`, so a cold agent resumes from the doing-task and its `latest-note` with no prior context. Even with no notes the doing-task still carries its body, dependencies, and lane:

1. `strand kanban board` — claimed cards show owner, branch, worktree, and their doing-task (with its `latest-note`).
2. `strand kanban card <id>` — the full card: tasks with their derived statuses and `latest-note`, card notes (newest first), active work subtree, and the ready frontier.

Views compact what they show: note text past a cap (600 characters) is clipped and marked `truncated: true`; the full text stays on the note strand (`strand show <note-id>`). This keeps one long note from drowning the resume read — the card view is a projection, never the storage.

A note may carry the primitive's `note/kind` decorating attr (`--kind <value>`) that views can fold or filter by. The set is open and guidance-only, never an enforced enum, with four suggested values: `activity` (a progress log), `decision` (a durable choice and why), `review-dump` (bulk findings), and `summary` (a run or session wrap-up). An absent value remains absent; any other value stays a valid userland annotation. Compact note projections surface it as `kind`.

## CLI op

Module activation registers one declared-subcommand operation. `strand help kanban` shows the machine-readable verb/flag surface; `strand help kanban task add` resolves the nested task-add leaf, including its `mutating` and `standard` classes. Bare `strand kanban` and unknown verbs fail loudly with the available subcommand names. The old `strand kanban help` form is retired; use the root `help` operation instead.

```sh
strand kanban prime
strand kanban about
strand kanban add "Feature idea" [--body "Longer context"] [--source docs/rfcs/...] [--lane pending|refinement] [--type feature|epic] [--epic <epic-id>] [--priority p1|p2|p3|p4] [--label <slug> ...]
strand kanban board [--label <slug> ...] [--all true]
strand kanban card <id>
strand kanban next [--label <slug> ...] [--epic <epic-id>]
strand kanban priority <id> <p1|p2|p3|p4>
strand kanban label add <id> <slug> [<slug> ...]
strand kanban label rm <id> <slug> [<slug> ...]
strand kanban label list
strand kanban promote <id>
strand kanban claim <id> --owner <name> --branch <branch> [--worktree /path] [--run-id <run-id>]
strand kanban note <card-or-task-id> <text> [--by <name>] [--kind activity|decision|review-dump|summary]
strand kanban task add <feature> <title> [--body "Longer context"] [--depends-on <id> ...]
strand kanban task list <feature>
strand kanban review <id>
strand kanban rework <id>
strand kanban finish <id> [--outcome done|abandoned]
strand kanban reopen <epic-id>
```

`prime` is the agent onboarding surface: a superset of `about` that adds the working discipline (work under a claimed card, the pick-up-next flow, the note-as-you-go/resume-from-task contract, adjacent-work awareness, and branch visibility) so repo agent docs point at it instead of duplicating conventions that then drift from the spool. `about` stays the terse command manual.

`board` returns the grouped snapshot (epics, refinement/pending/claimed/in_review lanes sorted p1-first then oldest, closed count); active cards with a lane outside the known set surface in `unknown-lane` rather than being hidden. `--all true` also returns `cards`, a compact all-state collection whose feature rows carry their direct `epic` id and whose closed rows carry their outcome. It also returns `needs-review`: a vector aggregated across claimed and in-review feature cards of `{:card :item}` entries (plus `:branch` from the claim stamp), one per card descendant that is active, in the engine ready frontier, and marks human review (`hitl` true, `workflow/checkpoint-kind` `human`, or `kind` `review`), sorted by card id then item id — the always-present cross-card review queue. `next` returns the highest-priority (p1 first) oldest active pending feature (epics are never served); `--epic <epic-id>` narrows the queue to that epic's direct features — the pick-up read for a loop working one epic — and fails loudly when the id does not name an epic card. Repeated `--label` flags on `board` and `next` narrow to cards carrying *every* listed label; on `board` the filter scopes the whole snapshot — lanes, epics, review frontier, closed count, and all-state cards alike — so a filtered board still reads as a board, and a feature whose epic was filtered out keeps its lane entry and loses only its `epic` annotation. `priority` restamps an active card's `kanban/priority` and fails loudly on unknown values or closed cards. `promote` is the explicit human command that moves a refinement card into the pending lane. `claim` fails loudly without `--owner` and `--branch` and refuses epics; `--worktree` is optional for direct work in the main checkout. `review` moves a claimed card to `in_review`; `rework` moves it back to `claimed`; `finish` is polymorphic on `kanban/type` — it closes a claimed or in-review *feature* with an explicit `kanban/outcome`, and closes an *epic* from `refinement`/`pending` (`--outcome done` guards its feature children are closed, `--outcome abandoned` cascades a reversible close, recording `kanban/abandon-restore-lane`). `reopen` is the inverse of an epic abandon only — it restores an abandoned epic and the children that abandon closed to their stored lanes and refuses a done or non-abandoned card (see [Finishing an epic](#finishing-an-epic)).

`label add` and `label rm` stamp and clear labels on one card and return the card's full label set;
both are idempotent, so adding a label a card already carries and removing one it never had are
no-ops rather than failures. `label list` returns the labels in use on active cards with the count
of cards carrying each — the board's cards *are* the vocabulary, so this is how an agent discovers
which labels exist. Compact cards on `board` and `next` carry a sorted `labels` vector, omitted
entirely when a card has none.

`card` returns the resume view (card, tasks with derived statuses and `latest-note`, compact card
notes, active work, ready frontier) plus `related`: a vector of `{:relation :strand}` entries for
every `depends-on` edge touching the card. The relation is `depends-on` when the card is the
dependent and `depended-on-by` when it is the dependency; entries sort by the other endpoint's id.
`claim --run-id` stamps `kanban/run-id` as an opaque pointer for agents to query through their workflow.
`task add` hangs a task under a
feature card (marker attr plus `parent-of`, optional `--depends-on` edges), and `task list` projects
that card's tasks with their derived statuses (see the Task tier section). Both fail loudly on a
missing, non-card, or non-feature target. Epics group features and never own tasks directly.

For bulk authoring, the `kanban-batch` weave pattern creates pending feature cards with bodies and `depends-on` edges atomically:

```sh
strand weave --pattern kanban-batch --input '{"items":[{"key":"design","title":"Design feature","body":"...","priority":"p2"},{"key":"docs","title":"Write docs","depends-on":["design","existing-strand-id"]}]}'
```

## Human view

The CLI stays JSON-only (TEN-006); the human rendering lives on the REPL surface:

```sh
printf "(do (require '[ct.spools.kanban :as kanban] '[millstrand.api.current.alpha :as current]) (kanban/print-board! (current/runtime)))\n" | mill weaver repl --stdin
```

`print-board!` prints a stacked-lane ASCII board (epics, refinement, pending, claimed and in_review with owner/branch and doing-task, needs-review); `board-str` is the pure renderer over the `board` result for reuse.

## Interactive dashboard

The spool publishes `kanban-dash`, a Go interactive terminal board. Build its binary after installing or upgrading the spool, then run it against the workspace selected by `mill`:

```sh
mill bin build kanban-dash
mill bin run kanban-dash
```

The dashboard retains the board's epic and feature views, keyboard navigation, label filters, and saved views. Saved filters are keyed by workspace, so one repository's views do not leak into another. The runner selects the workspace; `--interval <seconds>` changes the two-second poll interval, `--all` starts with closed cards visible, and `--once` prints one snapshot without entering the interactive view. Press `?` in the dashboard for the current key map.

## Offline export

Module activation also registers `kanban-export`, a read-only op that bundles one card's full `parent-of` subtree plus its internal `depends-on` edges in a single call:

```sh
strand kanban-export <card-id>
```

It returns the root, every strand beneath it via `parent-of` (all lifecycle states, so closed work still counts), the `parent-of` hierarchy edges, and the `depends-on` edges internal to that subtree. It is a pure graph projection — presentation and the progress rollup live in the consumer.

[`scripts/kanban-export/kanban-export.ts`](./scripts/kanban-export/kanban-export.ts) is that consumer: a dependency-free Bun renderer that turns the export payload into a single self-contained HTML file with an overall progress rollup and a per-child breakdown. `make kanban-export ID=<card-id> [ARGS='--open']` runs it directly; `make kanban-serve ID=<card-id> [PORT=8000]` exports to `/tmp/kanban-export` and serves it over the LAN.

## Queries

Module activation also registers:

- `kanban-cards` — all kanban card strands.
- `kanban-pending` — active cards with `kanban/lane=pending`.
- `kanban-epic-pending` — active pending cards hanging directly under one epic;
  parameterised, so the epic is named at invocation time.

`kanban-epic-pending` is the epic-loop frontier read: composed with the engine's ready overlay it
answers "what is ready next inside epic X" in one command, honouring the `depends-on` edges between
the epic's cards:

```sh
strand ready --query kanban-epic-pending --param epic=<epic-id>
```

`strand list --query kanban-epic-pending --param epic=<epic-id>` is the same selection without
readiness — every pending card in the epic, blocked or not. A missing `--param` fails loudly;
a non-epic id matches nothing, since no cards hang under it. `kanban next --epic` serves the same
queue one card at a time, ordered by priority.
