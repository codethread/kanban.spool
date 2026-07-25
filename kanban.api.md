
-----
# <a name="ct.spools.kanban">ct.spools.kanban</a>


User-facing kanban board over Skein strands.

  Cards are the user<->agent tracking surface: everything a user asks for is a
  `feature` card (occasionally grouped under an `epic`), and every agent
  working directly with a user works under a claimed card. All card state
  lives under `kanban/*` attributes; `kanban/lane` is the active board lane
  (`refinement` -> `pending` -> `claimed` -> `in_review`) and `kanban/outcome`
  records a finished card's outcome. The
  `kanban/priority` (p1 immediate blocker .. p4 someday, default p3) orders
  lanes and `kanban next`.

  Cards are work roots: claiming stamps `owner`/`branch`/`worktree`, and
  execution strands hang beneath the card with `parent-of` edges — the kanban
  spool complements the engines that produce them, it does not replace them.
  Notes are closed note strands on cards and tasks; progress notes belong on
  the doing-task, so a cold agent self-discovers in-flight work with
  `kanban board` -> `kanban card <id>` -> the doing-task and its
  `latest-note`.




## <a name="ct.spools.kanban/about">`about`</a>
``` clojure
(about)
```
Function.

Return the kanban convention and installed helper surface.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1188-L1256">Source</a></sub></p>

## <a name="ct.spools.kanban/add!">`add!`</a>
``` clojure
(add! title flags)
```
Function.

Create a kanban card in the pending (or refinement) lane.

  `--type epic` creates a grouping epic; `--epic <id>` hangs a new feature
  under an existing epic with a parent-of edge.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L166-L184">Source</a></sub></p>

## <a name="ct.spools.kanban/board">`board`</a>
``` clojure
(board)
```
Function.

Return the grouped board snapshot: epics, feature lanes, closed count.

  Claimed and in-review cards carry their doing-task so a cold agent can see in
  one call who is working where and how to pick up interrupted work.
  `:needs-review` aggregates the human-review frontier across claimed and
  in-review cards.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1062-L1108">Source</a></sub></p>

## <a name="ct.spools.kanban/board-str">`board-str`</a>
``` clojure
(board-str {:keys [epics refinement pending claimed in_review needs-review closed unknown-lane]})
```
Function.

Render a `board` result map as a stacked-lane ASCII board string.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1162-L1181">Source</a></sub></p>

## <a name="ct.spools.kanban/card-view">`card-view`</a>
``` clojure
(card-view id)
```
Function.

Return one card joined to its notes, tasks, work, and frontier.

  This is the resume entry point: everything an agent needs to continue a
  card lives here. `:tasks` projects the feature card's child tasks with the
  four derived statuses (empty for cards that carry no task tier), and
  `:tracker` joins the bound tracker's run status and ready steps for cards
  stamped with `kanban/run-id` (see `tracker-join`).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L964-L987">Source</a></sub></p>

## <a name="ct.spools.kanban/claim!">`claim!`</a>
``` clojure
(claim! id flags)
```
Function.

Claim a pending feature card, stamping the work-root attributes.

  `--owner` and `--branch` are mandatory so every claimed card answers who is
  driving it and on which branch; `--worktree` is optional (direct work in the
  main checkout has no separate worktree). `--run-id` optionally names the card's
  tracker run so `kanban card` can join the bound tracker's status and ready
  steps. Epics group work and are never claimed themselves.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L304-L327">Source</a></sub></p>

## <a name="ct.spools.kanban/contribute">`contribute`</a>
``` clojure
(contribute _ctx)
```
Function.

Return kanban's complete owner contribution for module publication.

  The keys deliberately cover every replaceable board declaration.  A later
  refresh that omits one therefore removes it instead of retaining a stale
  command, query, or pattern.  Vocabulary is runtime state rather than a core
  owner registry, so reconcile owns its idempotent declaration.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1544-L1563">Source</a></sub></p>

## <a name="ct.spools.kanban/finish!">`finish!`</a>
``` clojure
(finish! id flags)
```
Function.

Close a kanban card with an explicit outcome, polymorphic on `kanban/type`.

  A feature card closes from the claimed or in_review lane (`--outcome` defaults
  to done). A grouping epic is never claimed, so it closes from the refinement or
  pending lane: `--outcome done` completes it (guarding every direct feature
  child is closed) and `--outcome abandoned` cascade-closes each still-open
  feature child, recording each transitioned card's lane in
  `kanban/abandon-restore-lane` so `kanban reopen` can reverse exactly what the
  abandon closed.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L431-L450">Source</a></sub></p>

## <a name="ct.spools.kanban/install-peering!">`install-peering!`</a>
``` clojure
(install-peering!)
```
Function.

Register the opt-in `kanban.send.v1` board-peering receive op.

  A separate opt-in entry point wired into trusted config after the guild
  module and the kanban module are active. Delegates to
  `ct.spools.kanban.peering/install-peering!` via `requiring-resolve` so the base
  kanban spool never load-depends on the guild spool; peering (and its guild
  dependency) load only when a repo opts in.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1585-L1594">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-batch">`kanban-batch`</a>
``` clojure
(kanban-batch {:keys [input]})
```
Function.

Create pending feature cards with bodies and depends-on edges.

  Input shape: {:items [{:key "slug" :title "Title" :body "optional"
  :priority "p1|p2|p3|p4 (optional, default p3)"
  :depends-on ["sibling-key-or-existing-strand-id"]}]}. `depends-on` values matching sibling
  keys become batch-local edges; all other values are treated as durable strand
  ids and fail loudly if absent.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L222-L249">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-export-op">`kanban-export-op`</a>
``` clojure
(kanban-export-op ctx)
```
Function.

Handle `strand kanban-export <card-id>`: a card's full parent-of subtree
  with its internal depends-on edges.

  Given a feature or epic card id, returns the root, every strand beneath it via
  parent-of (all lifecycle states, so completed work still counts toward
  progress), the parent-of hierarchy edges, and the depends-on edges internal to
  the subtree. It is a read-only graph projection: presentation and the progress
  rollup live in the consumer (this spool's scripts/kanban-export). The existing
  `subgraph` op walks one relation at a time, so this op exists to bundle the
  hierarchy and its dependencies in a single call. Fails loudly when the id is
  unknown or names a strand that is not a kanban card.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1475-L1498">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-op">`kanban-op`</a>
``` clojure
(kanban-op #:op{:keys [args]})
```
Function.

Dispatch parsed `strand kanban ...` subcommands.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1428-L1448">Source</a></sub></p>

## <a name="ct.spools.kanban/next-card">`next-card`</a>
``` clojure
(next-card)
```
Function.

Return the highest-priority (p1 first) oldest active pending feature card, or nil.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1008-L1017">Source</a></sub></p>

## <a name="ct.spools.kanban/note!">`note!`</a>
``` clojure
(note! id text flags)
```
Function.

Append a note to a card or task via the blessed notes relation.

  The note rides the shared `notes` edge (`skein.api.notes.alpha/note!`) with
  optional inherited `note/by` attribution and the kanban-owned
  `note/kind` view hint, so concurrent agents never race a
  read-merge-write cycle and every note keeps its own timestamp and attribution. Note the doing-task as you go — that is
  what `kanban card <id>` surfaces as each task's `:latest-note` — and keep
  card notes to lean handover summaries. `--kind` stamps the open `note/kind`
  view hint (blessed values: activity, decision, review-dump, summary). A
  task note reports its owning card alongside the task when one parents it.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L688-L716">Source</a></sub></p>

## <a name="ct.spools.kanban/prime">`prime`</a>
``` clojure
(prime)
```
Function.

Return the full agent-priming payload for working the kanban board.

  The single source of truth for kanban usage discipline: repo agent docs
  point here (`strand kanban prime`) rather than duplicating conventions that
  then drift from the spool. A superset of `about` — it reuses the same lane,
  attribute, command, and pattern surface and adds the working agreement,
  pick-up flow, note discipline, adjacent-work awareness, and branch
  visibility that an agent needs before touching the board.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1258-L1338">Source</a></sub></p>

## <a name="ct.spools.kanban/print-board!">`print-board!`</a>
``` clojure
(print-board!)
```
Function.

Print the live board as ASCII; the human view for `mill weaver repl`.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1183-L1186">Source</a></sub></p>

## <a name="ct.spools.kanban/promote!">`promote!`</a>
``` clojure
(promote! id)
```
Function.

Move a refinement card into the pending lane (an explicit human act).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L278-L284">Source</a></sub></p>

## <a name="ct.spools.kanban/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime], :as ctx})
```
Function.

Reconcile kanban's non-registry runtime state around module publication.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1565-L1572">Source</a></sub></p>

## <a name="ct.spools.kanban/reopen!">`reopen!`</a>
``` clojure
(reopen! id)
```
Function.

Reopen an abandoned epic, reversing exactly the cascade a matching abandon closed.

  The inverse of abandon only: the epic must be a closed epic with
  `kanban/outcome=abandoned`; a done epic (or any non-abandoned card) is refused,
  because reopen pairs with abandon, not complete. The epic returns to its stored
  `kanban/abandon-restore-lane` (state active, outcome and marker cleared). Each
  direct feature child that is closed *and* carries the marker is reopened to its
  own stored restore lane; a child closed before the abandon (no marker) was
  legitimately done and stays closed. Reopen is a true inverse, never a blanket
  reopen.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L466-L504">Source</a></sub></p>

## <a name="ct.spools.kanban/review!">`review!`</a>
``` clojure
(review! id)
```
Function.

Move a claimed kanban card into the in_review lane.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L329-L335">Source</a></sub></p>

## <a name="ct.spools.kanban/rework!">`rework!`</a>
``` clojure
(rework! id)
```
Function.

Move an in_review kanban card back to claimed for rework.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L337-L343">Source</a></sub></p>

## <a name="ct.spools.kanban/set-priority!">`set-priority!`</a>
``` clojure
(set-priority! id priority)
```
Function.

Set an active card's priority (p1 highest urgency .. p4 someday).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L286-L296">Source</a></sub></p>

## <a name="ct.spools.kanban/set-tracker!">`set-tracker!`</a>
``` clojure
(set-tracker! tracker)
```
Function.

Bind the run-tracker strategy for this weaver lifetime.

  The binding is `{:name <non-blank-string> :project <fq-symbol-or-fn>}`. `:name`
  surfaces in `about` and the card view so a cold agent knows which convention
  the projected steps follow; `:project` is `(fn [run-id] -> {:status <string|nil>
  :ready [step ...]})`, resolved with `requiring-resolve` at call time when a
  symbol so a config reload rebinds cleanly. Rebinding replaces the prior value;
  pass a valid binding after every weaver startup or config reload. Module
  activation never binds a default. The binding is validated against
  `::tracker-binding`.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L868-L881">Source</a></sub></p>

## <a name="ct.spools.kanban/spool">`spool`</a>




Entry-point declaration for the kanban spool (ADR-004 `def spool` convention).

  The refresh coordinator resolves `:contribute`/`:reconcile` from this public
  var at every module evaluation, so a consumer declares only a source target
  and world policy (`{:ns 'ct.spools.kanban :spools ['codethread/kanban]}`) and
  never mirrors the pair. Unqualified symbols resolve against this namespace;
  fn values are rejected (ADR-002.O1).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1574-L1583">Source</a></sub></p>

## <a name="ct.spools.kanban/task-add!">`task-add!`</a>
``` clojure
(task-add! feature-id title flags)
```
Function.

Create a task strand under a feature card via a `parent-of` edge.

  `--depends-on <id>` is repeatable and lays the same `depends-on` edges that
  are the concurrency DAG and drive the derived `blocked`/`ready` split; task
  status is never stored.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L620-L640">Source</a></sub></p>

## <a name="ct.spools.kanban/task-list">`task-list`</a>
``` clojure
(task-list feature-id)
```
Function.

Project a feature card's tasks with their derived statuses.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L642-L649">Source</a></sub></p>

## <a name="ct.spools.kanban/task-op">`task-op`</a>
``` clojure
(task-op {:keys [feature title subcommand]} flags)
```
Function.

Dispatch a parsed `kanban task ...` action, failing loudly on an unknown one.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L651-L658">Source</a></sub></p>
