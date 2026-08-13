
-----
# <a name="ct.spools.kanban">ct.spools.kanban</a>


User-facing kanban board over Millstrand strands.

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
(about _runtime)
```
Function.

Return the kanban convention and installed helper surface.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1183-L1248">Source</a></sub></p>

## <a name="ct.spools.kanban/add!">`add!`</a>
``` clojure
(add! runtime title flags)
```
Function.

Create a kanban card in the pending (or refinement) lane.

  `--type epic` creates a grouping epic; `--epic <id>` hangs a new feature
  under an existing epic with a parent-of edge.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L222-L239">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1039-L1102">Source</a></sub></p>

## <a name="ct.spools.kanban/board-str">`board-str`</a>
``` clojure
(board-str {:keys [epics refinement pending claimed in_review needs-review closed unknown-lane]})
```
Function.

Render a `board` result map as a stacked-lane ASCII board string.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1157-L1176">Source</a></sub></p>

## <a name="ct.spools.kanban/card-view">`card-view`</a>
``` clojure
(card-view runtime id)
```
Function.

Return one card joined to its notes, tasks, work, and frontier.

  This is the resume entry point: everything an agent needs to continue a
  card lives here. `:tasks` projects the feature card's child tasks with the
  four derived statuses (empty for cards that carry no task tier).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L897-L914">Source</a></sub></p>

## <a name="ct.spools.kanban/claim!">`claim!`</a>
``` clojure
(claim! runtime id flags)
```
Function.

Claim a pending feature card, stamping the work-root attributes.

  `--owner` and `--branch` are mandatory so every claimed card answers who is
  driving it and on which branch; `--worktree` is optional (direct work in the
  main checkout has no separate worktree). `--run-id` optionally stamps an
  opaque run pointer for agents to query through their workflow directly. Epics
  group work and are never claimed themselves.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L389-L412">Source</a></sub></p>

## <a name="ct.spools.kanban/close-kanban!">`close-kanban!`</a>
``` clojure
(close-kanban! _context)
```
Function.

Close Kanban's module resource without retracting process-lifetime state.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1616-L1619">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L516-L534">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban">`kanban`</a>
``` clojure
(kanban ctx)
```
Function.

Manage the user-facing kanban work board.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1570-L1574">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L277-L305">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-cards">`kanban-cards`</a>




Select every Kanban card strand.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1587-L1590">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-dash">`kanban-dash`</a>




Open the interactive Kanban board in the caller's terminal.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1582-L1585">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-epic-pending">`kanban-epic-pending`</a>




Select active pending cards hanging directly under one epic.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1599-L1606">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-export">`kanban-export`</a>
``` clojure
(kanban-export ctx)
```
Function.

Return a card's full parent-of subtree with its internal depends-on edges.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1576-L1580">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-pending">`kanban-pending`</a>




Select active Kanban cards in the pending lane.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1592-L1597">Source</a></sub></p>

## <a name="ct.spools.kanban/kanban-runtime">`kanban-runtime`</a>




Own Kanban vocabulary and runtime-state setup for the module lifetime.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1621-L1624">Source</a></sub></p>

## <a name="ct.spools.kanban/label-add!">`label-add!`</a>
``` clojure
(label-add! runtime id labels)
```
Function.

Add labels to a card, one `kanban.label/<slug>` attribute key per label.

  Adding a label a card already carries is idempotent, and labels are free-form:
  no vocabulary is registered up front, so a new label exists the moment it is
  first used.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L366-L373">Source</a></sub></p>

## <a name="ct.spools.kanban/label-list">`label-list`</a>
``` clojure
(label-list runtime)
```
Function.

Return every label in use on active cards with the count of cards carrying it.

  Labels have no registry of their own, so the board's own cards are the
  vocabulary: this is how an agent discovers which labels exist before reusing
  one instead of coining a near-duplicate.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L939-L952">Source</a></sub></p>

## <a name="ct.spools.kanban/label-rm!">`label-rm!`</a>
``` clojure
(label-rm! runtime id labels)
```
Function.

Remove labels from a card by deleting their attribute keys.

  Removing a label a card does not carry is a no-op, so an unlabel is safe to
  repeat without first reading the card.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L375-L381">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L977-L998">Source</a></sub></p>

## <a name="ct.spools.kanban/note!">`note!`</a>
``` clojure
(note! runtime id text flags)
```
Function.

Append a note to a card or task via the blessed notes relation.

  The note rides the shared `notes` edge (`millstrand.api.notes.alpha/note!`) with
  optional inherited `note/by` attribution and the kanban-owned `note/kind` view
  hint, so concurrent agents never race a read-merge-write cycle. Every note
  keeps its own timestamp and attribution. Note the doing-task as you go — that
  is what `kanban card <id>` surfaces as each task's `:latest-note` — and keep
  card notes to lean handover summaries. `--kind` stamps the open `note/kind`
  view hint (blessed values: activity, decision, review-dump, summary). A
  task note reports its owning card alongside the task when one parents it.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L769-L796">Source</a></sub></p>

## <a name="ct.spools.kanban/open-kanban!">`open-kanban!`</a>
``` clojure
(open-kanban! {:keys [runtime]})
```
Function.

Declare Kanban vocabulary and materialize its process-lifetime runtime state.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1608-L1614">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1250-L1330">Source</a></sub></p>

## <a name="ct.spools.kanban/print-board!">`print-board!`</a>
``` clojure
(print-board! runtime)
```
Function.

Print the live board as ASCII; the human view for `mill weaver repl`.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L1178-L1181">Source</a></sub></p>

## <a name="ct.spools.kanban/promote!">`promote!`</a>
``` clojure
(promote! runtime id)
```
Function.

Move a refinement card into the pending lane (an explicit human act).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L334-L340">Source</a></sub></p>

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
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L550-L587">Source</a></sub></p>

## <a name="ct.spools.kanban/review!">`review!`</a>
``` clojure
(review! runtime id)
```
Function.

Move a claimed kanban card into the in_review lane.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L414-L420">Source</a></sub></p>

## <a name="ct.spools.kanban/rework!">`rework!`</a>
``` clojure
(rework! runtime id)
```
Function.

Move an in_review kanban card back to claimed for rework.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L422-L428">Source</a></sub></p>

## <a name="ct.spools.kanban/set-priority!">`set-priority!`</a>
``` clojure
(set-priority! runtime id priority)
```
Function.

Set an active card's priority (p1 highest urgency .. p4 someday).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L342-L352">Source</a></sub></p>

## <a name="ct.spools.kanban/task-add!">`task-add!`</a>
``` clojure
(task-add! runtime feature-id title flags)
```
Function.

Create a task strand under a feature card via a `parent-of` edge.

  `--depends-on <id>` is repeatable and lays the same `depends-on` edges that
  are the concurrency DAG and drive the derived `blocked`/`ready` split; task
  status is never stored.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L703-L722">Source</a></sub></p>

## <a name="ct.spools.kanban/task-list">`task-list`</a>
``` clojure
(task-list runtime feature-id)
```
Function.

Project a feature card's tasks with their derived statuses.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L724-L730">Source</a></sub></p>

## <a name="ct.spools.kanban/task-op">`task-op`</a>
``` clojure
(task-op runtime {:keys [feature title subcommand]} flags)
```
Function.

Dispatch a parsed `kanban task ...` action, failing loudly on an unknown one.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban.clj#L732-L739">Source</a></sub></p>
