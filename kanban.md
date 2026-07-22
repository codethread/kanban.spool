# Kanban spool

> This is the **contract** doc: the board model, the lanes and priority ladder,
> the `kanban/*` attribute vocabulary, and the CLI op surface. Its companion is
> [`kanban.cookbook.md`](./kanban.cookbook.md) — worked composition recipes
> (how/why you run work through the board). Exact signatures live in the source
> docstrings ([`src/ct/spools/kanban.clj`](./src/ct/spools/kanban.clj)).
> Reach for the cookbook when you want a runnable flow, and this doc for what
> the board guarantees.

The kanban spool is the user-facing work board held entirely in Skein strands. It tracks **user↔agent** work: everything a user asks for becomes a `feature` card (occasionally grouped under an `epic`), and every agent working directly with a user works under a claimed card. It complements — never replaces — the execution strands that hang beneath cards.

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

Card state lives under the `kanban/*` attribute topic:

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
| `kanban/run-id` | Optional tracker run-id; `kanban card` joins the bound tracker's status and ready steps (see [Tracker seam](#tracker-seam)). |
| `kanban/from` | Peering provenance serialized as `<board>:<card>` from the wire `:from` map. |
| `owner` | Who is driving the work; required at claim. |
| `branch` | The work branch; required at claim. |
| `worktree` | Optional worktree path. |

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

Install registers one declared-subcommand operation. `strand help kanban` shows the machine-readable verb/flag surface, and `strand kanban help`, `strand kanban -h`, and `strand kanban --help` return that same detail projection. Bare `strand kanban` and unknown verbs fail loudly with the available subcommand names.

```sh
strand kanban prime
strand kanban about
strand kanban add "Feature idea" [--body "Longer context"] [--source docs/rfcs/...] [--lane pending|refinement] [--type feature|epic] [--epic <epic-id>] [--priority p1|p2|p3|p4]
strand kanban board
strand kanban card <id>
strand kanban next
strand kanban priority <id> <p1|p2|p3|p4>
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

`board` returns the grouped snapshot (epics, refinement/pending/claimed/in_review lanes sorted p1-first then oldest, closed count); active cards with a lane outside the known set surface in `unknown-lane` rather than being hidden. It also returns `needs-review`: a vector aggregated across claimed and in-review feature cards of `{:card :item}` entries (plus `:branch` from the claim stamp), one per card descendant that is active, in the engine ready frontier, and marks human review (`hitl` true, `workflow/checkpoint-kind` `human`, or `kind` `review`), sorted by card id then item id — the always-present cross-card review queue. `next` returns the highest-priority (p1 first) oldest active pending feature (epics are never served). `priority` restamps an active card's `kanban/priority` and fails loudly on unknown values or closed cards. `promote` is the explicit human command that moves a refinement card into the pending lane. `claim` fails loudly without `--owner` and `--branch` and refuses epics; `--worktree` is optional for direct work in the main checkout. `review` moves a claimed card to `in_review`; `rework` moves it back to `claimed`; `finish` is polymorphic on `kanban/type` — it closes a claimed or in-review *feature* with an explicit `kanban/outcome`, and closes an *epic* from `refinement`/`pending` (`--outcome done` guards its feature children are closed, `--outcome abandoned` cascades a reversible close, recording `kanban/abandon-restore-lane`). `reopen` is the inverse of an epic abandon only — it restores an abandoned epic and the children that abandon closed to their stored lanes and refuses a done or non-abandoned card (see [Finishing an epic](#finishing-an-epic)).

`card` returns the resume view (card, tasks with derived statuses and `latest-note`, compact card
notes, active work, ready frontier) plus `related`: a vector of `{:relation :strand}` entries for
every `depends-on` edge touching the card. The relation is `depends-on` when the card is the
dependent and `depended-on-by` when it is the dependency; entries sort by the other endpoint's id.
Cards stamped with `kanban/run-id` (via `claim --run-id`) also carry `tracker`: the bound tracker's status
for that run and its ready steps (see [Tracker seam](#tracker-seam)). `task add` hangs a task under a
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
printf "(do (require 'ct.spools.kanban) (ct.spools.kanban/print-board!))\n" | mill weaver repl --stdin
```

`print-board!` prints a stacked-lane ASCII board (epics, refinement, pending, claimed and in_review with owner/branch and doing-task, needs-review); `board-str` is the pure renderer over the `board` result for reuse.

## Offline export

Install also registers `kanban-export`, a read-only op that bundles one card's full `parent-of` subtree plus its internal `depends-on` edges in a single call:

```sh
strand kanban-export <card-id>
```

It returns the root, every strand beneath it via `parent-of` (all lifecycle states, so closed work still counts), the `parent-of` hierarchy edges, and the `depends-on` edges internal to that subtree. It is a pure graph projection — presentation and the progress rollup live in the consumer.

[`scripts/kanban-export/kanban-export.ts`](./scripts/kanban-export/kanban-export.ts) is that consumer: a dependency-free Bun renderer that turns the export payload into a single self-contained HTML file with an overall progress rollup and a per-child breakdown. `make kanban-export ID=<card-id> [ARGS='--open']` runs it directly; `make kanban-serve ID=<card-id> [PORT=8000]` exports to `/tmp/kanban-export` and serves it over the LAN.

## Peering

Board peering lets sibling weavers on one machine hand cards to each other's boards. It is **opt-in and off by default**: `install!` never touches it. A repo turns it on by registering a second entry point, `install-peering!`, from trusted config, which registers three ops — the `kanban.send.v1` guild receive op, and the local `kanban-peers` and `kanban-send` ops.

### What travels, and what never does

Peering moves the **board tier only** — the shape of the work, not its execution or history.

| Travels | Stays home |
| --- | --- |
| A feature card's title, body, priority, source, and lane (`pending`/`refinement`). | Tasks and any other `parent-of` execution strands. |
| An epic card and its pending/refinement feature children as one bundle. | Notes, and everything under them. |
| `:from` provenance: the sending board's name and the local card id. | Claims (`owner`/`branch`/`worktree`) and the local card id as an identity. |

Only queued work travels. A `claimed`, `in_review`, or closed card is in-flight or finished work that is world-local; `kanban-send` refuses it loudly with the blocking lane. An epic refuses to send while any feature child is `claimed` or `in_review` (the blocking children are named); closed children are finished and simply stay home. A bundle carries every direct child that claims card-ness (`kanban/card`), so a nested epic or a drifted marker or type is named in a loud failure rather than dropped from a bundle that then reports success; unmarked children — tasks, notes, and the execution strands an engine hangs under a card — are not cards and never join the bundle. Received cards are **new local cards** on the target board — they travel the same `add!` path as any local card, take the target's own ids and defaults, and carry no back-reference beyond the `kanban/from` stamp. Nothing on either board's lane changes as a side effect: closing the source card after a send stays the caller's choice.

### Loading

Peering depends on the `skein.spools.guild` spool for its receive op and on `skein.api.peers.alpha` for discovery. `skein.spools.guild` loads like any other spool — through the consuming workspace's `spools.edn` approval plus a runtime sync — so a peering repo approves **both** guild and kanban:

```clojure
;; spools.edn (or spools.local.edn overlay)
{:spools {skein.spools/guild {:local/root "/path/to/your/skein/spools/guild"}
          codethread/kanban {:git/url "git@github.com:codethread/kanban.spool.git"
                             :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

Peering stamps every card it sends with the local weaver's **published name**, so set one in `.skein/config.json` (a machine with two clones can override it per-checkout in the gitignored `.skein/config.local.json`):

```json
{"configFormat": "alpha", "name": "backend"}
```

Then activate, in order: guild first, kanban second, `install-peering!` last. `install-peering!` fails loudly if guild or the kanban board op is not already registered, so the `:after` ordering is a hard prerequisite, not a preference:

```clojure
(runtime/sync! runtime)

(runtime/use! runtime
  :guild
  {:ns 'skein.spools.guild
   :spools ['skein.spools/guild]
   :required? true})

(require '[skein.spools.guild :as guild])
(guild/install! runtime)

(runtime/use! runtime
  :kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :call 'ct.spools.kanban/install!
   :required? true})

(runtime/use! runtime
  :kanban/peering
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban 'skein.spools/guild]
   :after [:guild :kanban]
   :call 'ct.spools.kanban/install-peering!
   :required? true})
```

In a live module graph, use `ct.spools.kanban.peering/contribute` and
`ct.spools.kanban.peering/reconcile` for this entry after `:guild` and `:kanban`.
The owner contribution replaces `kanban-peers` and `kanban-send` as one set;
Guild continues to own the `kanban.send.v1` dispatch facade.

### Discovering and sending

`kanban-peers` lists sibling weavers from mill metadata. Every peer is listed; each **running non-self** peer is probed via `guild list`, and `kanban-send?` is `true` when it advertises `kanban.send.v1`. A running peer that rejects `guild list` as an unknown op is an expected non-peering sibling (`kanban-send? false`); any other transport or protocol failure — including a malformed `guild list` envelope — propagates loudly. Stale peers (`running? false`) are listed but never probed. The local weaver — when it appears in the roster — is marked `self? true` and classified from the local op registry, not a socket call to itself.

```sh
strand kanban-peers
# => {"operation": "kanban-peers",
#     "peers": [{"name": "frontend", "workspace": "…", "weaver-id": "…",
#                "running?": true, "kanban-send?": true},
#               {"name": "backend", "weaver-id": "…", "running?": true,
#                "self?": true, "kanban-send?": true}]}
```

`kanban-send <peer> <card-id>` resolves the local feature or epic card, preflights the target's `guild list` for `kanban.send.v1` (a clear error names the fix when the peer runs no peering or an older kanban), sends the payload, records a note on the local card with the created remote ids, and returns them:

```sh
strand kanban-send frontend abc12
# => {"operation": "kanban-send", "peer": "frontend", "sent": {"card": {"id": "9xk2p"}}}
```

### The `kanban.send.v1` wire contract

The receive op takes **one JSON object** as its single argument (guild parses it to keyword keys and validates it before the handler runs). It is exactly one of two shapes, plus optional provenance:

```clojure
;; a single feature
{:card {:title "…"                      ; required
        :body "…" :source "…"           ; optional
        :priority "p1|p2|p3|p4"         ; optional; receiver defaults p3
        :lane "pending|refinement"}   ; optional; receiver defaults pending
 :from {:board "backend" :card "abc12"}} ; optional provenance

;; an epic bundle: an :epic card plus one or more :features (both card maps)
{:epic {:title "…"} :features [{:title "…"} …] :from {…}}
```

Unknown keys, a missing title, a bad priority or lane, a lone `:card`+`:epic`, an epic without features, and a malformed `:from` all fail spec validation loudly. The op returns JSON-safe ids only — `{:operation "kanban.send.v1" :card {:id …}}` for a single card, or `{:operation … :epic {:id …} :features [{:id …} …]}` for a bundle, features in input order. Every created card carries its provenance as one `kanban/from` attribute, `"<board>:<card>"` (e.g. `"backend:abc12"`), so the receiving board can trace a card to its origin without importing the source id as an identity.

## Tracker seam

Kanban core has no compile- or load-time dependency on any tracker. Instead, trusted config
binds a **run-tracker strategy** for the weaver lifetime, and the `kanban card` view joins it in
exactly one seam. This mirrors chime's notifier binding: a vocabulary-agnostic engine ships
unbound, config supplies the implementation, and unbound use degrades honestly.

**Binding.** Bind the strategy once per weaver lifetime (and again after every startup or config
reload — `install!` never binds a default):

```clojure
(kanban/set-tracker!
  {:name "devflow"
   :project 'kanban-tracker/devflow-projection})
```

- `:name` — a non-blank string naming the convention; it surfaces in `kanban about` and in the
  card view's `tracker` so a cold agent knows which tracker the steps come from.
- `:project` — a fully-qualified symbol (resolved with `requiring-resolve` at call time, so a
  reload rebinds cleanly) or a function. Contract: `(project run-id)` returns
  `{:status <string|nil> :ready [step ...]}`. Kanban selects each step down to the closed key
  set `#{:id :title :role :stage :checkpoint}`, so the card-view shape stays kanban-owned whatever
  the tracker returns.

A malformed binding is rejected loudly (unknown keys, a blank name, or a `:project` that is
neither a fully-qualified symbol nor a function). The owning Clojure specs are
`:ct.spools.kanban/tracker-binding` for the binding,
`:ct.spools.kanban/tracker-projection` for the strategy result, and
`:ct.spools.kanban/tracker-view` for the public card-view shape. A projection must contain
exactly `:status` and `:ready`; every step must be a map with non-blank `:id`, `:title`, and
`:role`. Malformed status values, missing keys, non-vector steps, and invalid step entries fail with
the tracker name and run id in the error data. Kanban also validates the constructed public view,
so malformed legacy run attributes fail instead of leaking through as an ambiguous projection.

**The join.** A card names its run through the `kanban/run-id` attribute (`claim --run-id <run-id>`
stamps it). For a stamped card, `card` carries a `tracker` key:

```json
"tracker": {"name": "devflow",
            "run-id": "widgets-run",
            "status": "spec",
            "ready": [{"id": "...", "title": "...", "role": "step", "stage": "spec"}]}
```

- **binding present** — the bound strategy's status and ready steps; a tracker that reports no
  active run projects a `null` status with no steps rather than hiding the key.
- **binding absent** — `{"name": null, "run-id": "widgets-run", "status": null, "ready": []}`:
  the stamp is visible and the missing strategy is visible.
- **binding throws** — the card view fails with the strategy's error. The binding is repo-owner
  config; masking its failures would violate fail-loud (TEN-003).

An unstamped card carries no `tracker` key at all. Kanban only reads tracker state; it never
writes it.

**Worked example: the devflow adapter.** A repo that stages work through
[`ct.spools.devflow`](https://github.com/codethread/devflow.spool) binds a small trusted-config
module (roughly fifteen lines) that composes devflow's read fns into the projection shape:

```clojure
(defn devflow-projection [run-id]
  (let [stage (some-> (devflow/feature-roots run-id) first
                      (attr-value :devflow/stage))]
    {:status stage
     :ready (if stage (devflow/ready run-id) [])}))

(defn install! []
  (kanban/set-tracker! {:name "devflow"
                        :project 'kanban-tracker/devflow-projection}))
```

The devflow spool stays kanban-ignorant, and the adapter lives in the one place that knows both
vocabularies — consumer config, not either spool. A repo with a different tracker writes its own
module against the same two-key contract; a repo with no tracker skips the block entirely and
stamped cards project as unbound. The consuming [README](./README.md) carries the full recipe.

## Queries

Install also registers:

- `kanban-cards` — all kanban card strands.
- `kanban-pending` — active cards with `kanban/lane=pending`.
