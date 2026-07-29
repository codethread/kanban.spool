
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
(about runtime)
```
Function.

Return the kanban convention and installed helper surface.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1334-L1404">Source</a></sub></p>

## <a name="ct.spools.kanban/add!">`add!`</a>
``` clojure
(add! runtime title flags)
```
Function.

Create a kanban card in the pending (or refinement) lane.

  `--type epic` creates a grouping epic; `--epic <id>` hangs a new feature
  under an existing epic with a parent-of edge.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L221-L238">Source</a></sub></p>

## <a name="ct.spools.kanban/board">`board`</a>
``` clojure
(board runtime)
(board runtime labels)
(board runtime labels all?)
```
Function.

Return the grouped board snapshot: epics, feature lanes, closed count.

  Claimed and in-review cards carry their doing-task so a cold agent can see in
  one call who is working where and how to pick up interrupted work.
  `:needs-review` aggregates the human-review frontier across claimed and
  in-review cards.

  `labels` scopes the whole snapshot — lanes, epics, review frontier, and the
  closed count alike — to cards carrying every listed label, so a filtered board
  reads as a board rather than a lane list with a mismatched tally. A feature
  whose epic is filtered out keeps its lane entry and loses only the `:epic`
  annotation.

  `all?` adds `:cards`, a compact all-state card collection with direct epic
  membership. The ordinary grouped active snapshot remains unchanged.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1190-L1253">Source</a></sub></p>

## <a name="ct.spools.kanban/board-str">`board-str`</a>
``` clojure
(board-str {:keys [epics refinement pending claimed in_review needs-review closed unknown-lane]})
```
Function.

Render a `board` result map as a stacked-lane ASCII board string.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1308-L1327">Source</a></sub></p>

## <a name="ct.spools.kanban/card-view">`card-view`</a>
``` clojure
(card-view runtime id)
```
Function.

Return one card joined to its notes, tasks, work, and frontier.

  This is the resume entry point: everything an agent needs to continue a
  card lives here. `:tasks` projects the feature card's child tasks with the
  four derived statuses (empty for cards that carry no task tier), and
  `:tracker` joins the bound tracker's run status and ready steps for cards
  stamped with `kanban/run-id` (see `tracker-join`).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1043-L1065">Source</a></sub></p>

## <a name="ct.spools.kanban/claim!">`claim!`</a>
``` clojure
(claim! runtime id flags)
```
Function.

Claim a pending feature card, stamping the work-root attributes.

  `--owner` and `--branch` are mandatory so every claimed card answers who is
  driving it and on which branch; `--worktree` is optional (direct work in the
  main checkout has no separate worktree). `--run-id` optionally names the card's
  tracker run so `kanban card` can join the bound tracker's status and ready
  steps. Epics group work and are never claimed themselves.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L387-L410">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1726-L1754">Source</a></sub></p>

## <a name="ct.spools.kanban/finish!">`finish!`</a>
``` clojure
(finish! runtime id flags)
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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L514-L532">Source</a></sub></p>

## <a name="ct.spools.kanban/install-peering!">`install-peering!`</a>
``` clojure
(install-peering! runtime)
```
Function.

Register the opt-in `kanban.send.v1` board-peering receive op.

  A separate opt-in entry point wired into trusted config after the guild
  module and the kanban module are active. Delegates to
  `ct.spools.kanban.peering/install-peering!` via `requiring-resolve` so the base
  kanban spool never load-depends on the guild spool; peering (and its guild
  dependency) load only when a repo opts in.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1777-L1786">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L276-L303">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-export-op">`kanban-export-op`</a>
``` clojure
(kanban-export-op #:op{:keys [args runtime]})
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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1650-L1672">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-op">`kanban-op`</a>
``` clojure
(kanban-op #:op{:keys [args runtime]})
```
Function.

Dispatch parsed `strand kanban ...` subcommands.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1597-L1623">Source</a></sub></p>

## <a name="ct.spools.kanban/label-add!">`label-add!`</a>
``` clojure
(label-add! runtime id labels)
```
Function.

Add labels to a card, one `kanban.label/<slug>` attribute key per label.

  Adding a label a card already carries is idempotent, and labels are free-form:
  no vocabulary is registered up front, so a new label exists the moment it is
  first used.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L364-L371">Source</a></sub></p>

## <a name="ct.spools.kanban/label-list">`label-list`</a>
``` clojure
(label-list runtime)
```
Function.

Return every label in use on active cards with the count of cards carrying it.

  Labels have no registry of their own, so the board's own cards are the
  vocabulary: this is how an agent discovers which labels exist before reusing
  one instead of coining a near-duplicate.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1090-L1103">Source</a></sub></p>

## <a name="ct.spools.kanban/label-rm!">`label-rm!`</a>
``` clojure
(label-rm! runtime id labels)
```
Function.

Remove labels from a card by deleting their attribute keys.

  Removing a label a card does not carry is a no-op, so an unlabel is safe to
  repeat without first reading the card.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L373-L379">Source</a></sub></p>

## <a name="ct.spools.kanban/next-card">`next-card`</a>
``` clojure
(next-card runtime)
(next-card runtime labels)
(next-card runtime labels epic-id)
```
Function.

Return the highest-priority (p1 first) oldest active pending feature card, or nil.

  `labels` narrows the queue to cards carrying every listed label, so an agent
  working one axis pulls the next card on that axis rather than the next card
  overall. `epic-id` narrows to one epic's direct features — the pick-up read
  for a loop working a single epic — and fails loudly when the id does not
  name an epic card.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1128-L1149">Source</a></sub></p>

## <a name="ct.spools.kanban/note!">`note!`</a>
``` clojure
(note! runtime id text flags)
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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L767-L794">Source</a></sub></p>

## <a name="ct.spools.kanban/prime">`prime`</a>
``` clojure
(prime runtime)
```
Function.

Return the full agent-priming payload for working the kanban board.

  The single source of truth for kanban usage discipline: repo agent docs
  point here (`strand kanban prime`) rather than duplicating conventions that
  then drift from the spool. A superset of `about` — it reuses the same lane,
  attribute, command, and pattern surface and adds the working agreement,
  pick-up flow, note discipline, adjacent-work awareness, and branch
  visibility that an agent needs before touching the board.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1406-L1486">Source</a></sub></p>

## <a name="ct.spools.kanban/print-board!">`print-board!`</a>
``` clojure
(print-board! runtime)
```
Function.

Print the live board as ASCII; the human view for `mill weaver repl`.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1329-L1332">Source</a></sub></p>

## <a name="ct.spools.kanban/promote!">`promote!`</a>
``` clojure
(promote! runtime id)
```
Function.

Move a refinement card into the pending lane (an explicit human act).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L332-L338">Source</a></sub></p>

## <a name="ct.spools.kanban/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime], :as ctx})
```
Function.

Reconcile kanban's non-registry runtime state around module publication.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1756-L1764">Source</a></sub></p>

## <a name="ct.spools.kanban/reopen!">`reopen!`</a>
``` clojure
(reopen! runtime id)
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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L548-L585">Source</a></sub></p>

## <a name="ct.spools.kanban/review!">`review!`</a>
``` clojure
(review! runtime id)
```
Function.

Move a claimed kanban card into the in_review lane.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L412-L418">Source</a></sub></p>

## <a name="ct.spools.kanban/rework!">`rework!`</a>
``` clojure
(rework! runtime id)
```
Function.

Move an in_review kanban card back to claimed for rework.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L420-L426">Source</a></sub></p>

## <a name="ct.spools.kanban/set-priority!">`set-priority!`</a>
``` clojure
(set-priority! runtime id priority)
```
Function.

Set an active card's priority (p1 highest urgency .. p4 someday).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L340-L350">Source</a></sub></p>

## <a name="ct.spools.kanban/set-tracker!">`set-tracker!`</a>
``` clojure
(set-tracker! runtime tracker)
```
Function.

Bind the run-tracker strategy for this weaver lifetime.

  The binding is `{:name <non-blank-string> :project <fq-symbol-or-fn>}`. `:name`
  surfaces in `about` and the card view so a cold agent knows which convention
  the projected steps follow; `:project` is `(fn [run-id] -> {:status <string|nil>
  :ready [step ...]})`, called as `(project runtime run-id)` and resolved with
  `requiring-resolve` at call time when a
  symbol so a config reload rebinds cleanly. Rebinding replaces the prior value;
  pass a valid binding after every weaver startup or config reload. Module
  activation never binds a default. The binding is validated against
  `::tracker-binding`.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L946-L960">Source</a></sub></p>

## <a name="ct.spools.kanban/spool">`spool`</a>




Entry-point declaration for the kanban spool (ADR-004 `def spool` convention).

  The refresh coordinator resolves `:contribute`/`:reconcile` from this public
  var at every module evaluation, so a consumer declares only a source target
  and world policy (`{:ns 'ct.spools.kanban :spools ['codethread/kanban]}`) and
  never mirrors the pair. Unqualified symbols resolve against this namespace;
  fn values are rejected (ADR-002.O1).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1766-L1775">Source</a></sub></p>

## <a name="ct.spools.kanban/task-add!">`task-add!`</a>
``` clojure
(task-add! runtime feature-id title flags)
```
Function.

Create a task strand under a feature card via a `parent-of` edge.

  `--depends-on <id>` is repeatable and lays the same `depends-on` edges that
  are the concurrency DAG and drive the derived `blocked`/`ready` split; task
  status is never stored.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L701-L720">Source</a></sub></p>

## <a name="ct.spools.kanban/task-list">`task-list`</a>
``` clojure
(task-list runtime feature-id)
```
Function.

Project a feature card's tasks with their derived statuses.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L722-L728">Source</a></sub></p>

## <a name="ct.spools.kanban/task-op">`task-op`</a>
``` clojure
(task-op runtime {:keys [feature title subcommand]} flags)
```
Function.

Dispatch a parsed `kanban task ...` action, failing loudly on an unknown one.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L730-L737">Source</a></sub></p>
