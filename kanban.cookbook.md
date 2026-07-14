# Skein Kanban Spool — Cookbook

Composition recipes for `ct.spools.kanban`: how to run real user↔agent work through the board, and *why* each stamp, edge, and note is where it is.

This is the **how/why** half of the kanban docs. The other two halves are:

- [`kanban.md`](./kanban.md) — the **contract**: the board model, the lanes and
  priority ladder, the `kanban/*` attribute vocabulary, and the CLI op surface.
  Read it for what the board guarantees.
- [`src/ct/spools/kanban.clj`](./src/ct/spools/kanban.clj) — every public
  fn's signature, arity, and docstring.

Division of truth: signatures live in the source docstrings and the attribute table lives in the contract; narrative and composition live here. This cookbook never restates a signature or the lane/attribute table — it links to them.

The kanban CLI is JSON-only, so every recipe below is a `strand kanban …` shell flow. The REPL fns behind each verb (and the ASCII `print-board!` human view) are documented in the source docstrings; run `strand kanban prime` for the live, spool-authored working discipline these recipes distil.

## How to read a recipe

Every recipe has the same four parts, so you can skim to the one that matches your situation and lift the snippet:

1. **Situation** — the shape of problem you're staring at.
2. **Composition** — which verbs combine, and how.
3. **Snippet** — a complete, runnable flow.
4. **Why this shape** — the reasoning: why each stamp is there, what it buys a
   later agent, and what skipping it would cost.

Each recipe cites the honest source it was distilled from — the spool source, this repo's own conventions, or `test/ct/spools/kanban_test.clj`, which drives every documented behaviour against a real weaver runtime and doubles as the executable proof for these flows.

---

## Recipe: Pick up the next card and carry it end to end

**Situation.** You're an agent sitting down to work with a user. There's a queue of pending features; you need to take the right one, make the work discoverable to everyone else, and leave it resumable if you're interrupted.

**Composition.** The usual loop is `next`, `claim`, `task add` to slice the work, and `note` the doing-task as you go. When the work is ready for review, run `review`. If review asks for changes, run `rework`; when the work has landed, `finish` closes it out.

```sh
# 1. Take the highest-priority (p1 first) oldest pending feature.
card=$(strand kanban next | jq -r '.next.id')

# 2. Claim it — owner and branch are mandatory; worktree is optional.
strand kanban claim "$card" --owner claude --branch kanban-spool --worktree /path/to/wt

# 3. Decompose into tasks; the depends-on edges are the concurrency DAG.
impl=$(strand kanban task add "$card" "Implement the ops" | jq -r '.task.id')
docs=$(strand kanban task add "$card" "Write the docs" --depends-on "$impl" | jq -r '.task.id')
strand update "$impl" --attr owner=claude   # owned + deps met = the doing-task

# 4. Note the doing-task as you reach decisions and progress, not at the end.
strand kanban note "$impl" "Chose lane names over statuses because X" --by claude --kind decision
strand --stdin kanban note "$impl" :stdin --by claude --kind activity <<'NOTE'
Done: impl + tests.
Next: docs.
Validation: clojure -M:test green.
Gotcha: reload the weaver after merge.
NOTE

# 5. Move it through review. Rework returns it to claimed when needed.
strand kanban review "$card"
strand kanban rework "$card"
strand kanban review "$card"

# 6. Close it once the work has landed, with a lean handover note on the card.
strand kanban note "$card" "Handover: landed as <sha>; docs follow-up card filed" --by claude --kind summary
strand kanban finish "$card" --outcome done
```

**Why this shape.**

- **`next` encodes the queue policy so you don't.** It serves the p1-first,
  then-oldest pending *feature* — epics are never served, refinement cards stay
  inert until a human promotes them. You take what the board says is next
  instead of re-deriving urgency by hand (contract [Model](./kanban.md#model);
  `kanban-priority-orders-lanes-and-next` in `kanban_test.clj`).
- **`claim`'s `owner`/`branch` is what makes the work discoverable.** The claim
  refuses to proceed without both, because that stamp is exactly what
  `strand branches` and the roster read to answer "who is working where". A
  claimed card is the branch's active work root; skip the claim and the work is
  invisible to every other agent (`claim!` docstring;
  `kanban-add-next-claim-and-finish-round-trip`).
- **The card is the audit root, not a status field.** Claiming moves the card to
  the `claimed` lane and `next` stops serving it, so two agents can't both pull
  the same feature.
- **Tasks make the resume point exist at all.** The claim says who owns the
  feature; the task tier says where inside it the work stands. Skip the
  decomposition and the board's doing-task signal, the derived-status DAG, and
  the note target all vanish — which is exactly how review dumps end up on the
  card (contract [Task tier](./kanban.md#task-tier)).
- **Note the doing-task as you go — that is the interruption contract.** A
  crash, a context limit, or a handoff to another agent all resolve the same
  way: whoever picks up reads the doing-task and its `latest-note`. Writing
  progress *as* you work, not after, is the whole point — the resume point
  exists before you stop. Card notes stay lean handover summaries; bulk content
  (review findings, pasted output) goes on a task note, and views clip note
  bodies past a cap so no single dump drowns the card
  (contract [Notes and resume](./kanban.md#notes-and-resume)).
- **`review` and `rework` keep review visible.** `review` moves the card to
  `in_review`, so board readers can see work waiting on review. `rework` moves it
  back to `claimed` when the branch needs changes.
- **`finish` records an explicit outcome.** `done` and `abandoned` both close the
  card, but the `kanban/status` outcome stays on the strand, so the closed lane
  keeps an honest history rather than a wall of indistinguishable "closed".

Honest source: the pick-up flow authored in the spool's own `prime` payload (`:pick-up-next-card`, `:working-agreement`) and this repo's `CLAUDE.md` kanban convention, proven end to end by `kanban-add-next-claim-and-finish-round-trip`.

---

## Recipe: Stock the backlog in one weave

**Situation.** A user hands you a list of features at once — often with dependencies between them ("docs after the design lands") — and you want them all as pending cards in a single atomic step, with the blockers already wired.

**Composition.** One `kanban-batch` weave. Each item is a card; `deps` values that match a sibling `key` become batch-local `depends-on` edges, and any other `deps` value is treated as an existing strand id.

```sh
strand weave --pattern kanban-batch --input '{
  "items": [
    {"key": "design", "title": "Design the board model", "body": "Lanes + priority ladder", "priority": "p2"},
    {"key": "impl",   "title": "Implement the ops",       "deps": ["design"]},
    {"key": "docs",   "title": "Write the cookbook",      "deps": ["impl", "gfg6x"]}
  ]
}'
```

**Why this shape.**

- **Atomic beats a loop of `add`.** One weave creates every card and every edge
  in a single transaction, so a mid-list failure never leaves you with half a
  backlog and dangling references.
- **Sibling keys and durable ids share one `deps` list.** A dep that matches a
  sibling `key` (`design`) resolves to the card being created alongside it; any
  other value (`gfg6x`) is a durable strand id. That lets a new backlog depend on
  both its own siblings and existing work without two different syntaxes
  (`kanban-batch` docstring;
  `kanban-batch-weave-creates-cards-and-dependencies`).
- **It fails loudly, so a typo can't rot silently.** Duplicate keys, an unknown
  priority, an unexpected item key, or a `deps` id that doesn't exist all abort
  the whole weave with a specific error rather than creating a subtly wrong
  graph (`kanban-batch-weave-fails-loudly`).
- **Cards land pending at p3 by default.** Batch cards are actionable
  immediately; set `priority` per item to jump the queue, and reach for a
  refinement card (via plain `kanban add --status refinement`) only when an idea
  isn't ready to be worked.

Honest source: the `kanban-batch` pattern in the spool source and its two test cases, plus the bulk-authoring section of the contract doc.

---

## Recipe: Group a multi-card initiative under an epic

**Situation.** A single theme spans several features — a subsystem rewrite, a release. You want them grouped so the board shows them together, but each feature still claimed and worked on its own.

**Composition.** Create one `epic` card, then add each feature with `--epic <id>`. The epic is `parent-of` its features; it is a grouping card, never work.

```sh
epic=$(strand kanban add "Board rewrite" --type epic | jq -r '.card.id')
strand kanban add "Design the lanes"  --epic "$epic"
strand kanban add "Port the old cards" --epic "$epic" --priority p2
```

**Why this shape.**

- **An epic groups; it never gets served or claimed.** `next` skips epics and
  `claim` refuses them, because there's nothing to *do* on an epic — the work
  lives in its features. The epic is a lens over the board, not a task
  (`kanban-epics-group-features`).
- **Features stay independently claimable.** Each feature under the epic keeps
  its own lane, priority, owner, and branch, so two agents can claim two features
  of the same epic and work them in parallel. The board tags each feature with
  its `epic:` so the grouping stays visible without collapsing the features into
  one card.
- **The nesting rules fail loudly.** An epic can't nest under another epic, and
  `--epic` must point at an actual epic — both are rejected at `add` time, so the
  grouping can't quietly go two levels deep or hang a feature off a non-epic.

Honest source: `add!`'s `--type`/`--epic` handling and `kanban-epics-group-features`.

---

## Recipe: Hang execution strands under a card

**Situation.** The user's request is approved and it's real work — a task DAG of execution strands. You don't want two competing trackers fighting over the same feature.

**Composition.** Claim the card, build the execution strands as usual, then connect their root to the card with a `parent-of` edge. The card becomes the audit root; the execution strands are the work beneath it.

```sh
strand kanban claim "$card" --owner claude --branch board-rewrite

# Build the execution strands however you normally would; capture their shared root.
root=$(strand add "Feature: board rewrite" | jq -r '.id')
impl=$(strand add "Implement" | jq -r '.id')
review=$(strand add "Review" --attr kind=review | jq -r '.id')
strand update "$root" --edge parent-of:"$impl" --edge parent-of:"$review"
strand update "$review" --edge depends-on:"$impl"

# Adopt the execution root under the card.
strand update "$card" --edge parent-of:"$root"
```

**Why this shape.**

- **One work root, not two.** Kanban complements the engines that build the
  execution strands — it doesn't replace them. Hanging the execution root beneath
  the card means the card stays the single place a human looks, while the engine
  still owns the execution graph (spool `prime` `:working-agreement`; contract
  [Model](./kanban.md#model)).
- **`card <id>` reads straight through the subtree.** Because the execution strands
  hang off the card by `parent-of`, the card view joins the card to its active work and
  the *ready frontier* of that subtree — an agent resuming the card sees exactly
  which tasks are unblocked without a separate query (`card-view` /
  `card-subtree`; the card-view test in `kanban_test.clj`).
- **The claim stamp cascades by reachability.** Only the card carries `branch`
  and `owner`; every descendant is discoverable *through* the card, so you never
  re-stamp each task. That's the same active-work-root convention `strand
  branches` relies on (spool `prime` `:branch-visibility`).

Honest source: the spool `prime` working-agreement and branch-visibility blocks, this repo's `CLAUDE.md` (plans and runs hang beneath cards via `parent-of`), and the card-view test that hangs a task and a review under a claimed card.

---

## Recipe: Resume interrupted work from a cold start

**Situation.** You wake up with no context. A previous agent hit a context limit, crashed, or handed off mid-card, and you need to continue without them.

**Composition.** Two reads, top-down. `board` shows the claimed lane with each card's doing-task; `card <id>` is the full resume view — tasks with their derived statuses, notes newest-first, active work, the ready frontier, and related cards.

```sh
strand kanban board                 # claimed cards show owner, branch, and doing-task
strand kanban card "$card"          # tasks, notes, active work, ready frontier, related
```

**Why this shape.**

- **The task tier is the resume point.** The doing-task carries its body, deps,
  and lane, and its `latest-note` — surfaced inline on every task projection —
  records what's done, what's next, the validation state, and gotchas, so the
  resume path needs no prior conversation and no extra note query. The
  cold-start read is `board` → `card` → doing-task and its `latest-note`; even
  with no notes the doing-task alone tells you where the work stands
  (`card-view`; contract [Notes and resume](./kanban.md#notes-and-resume)).
- **Notes are closed child strands, not an attribute.** Each note keeps its own
  timestamp and author, and concurrent agents appending notes never race a
  read-merge-write cycle on one attribute. That's why two agents can both leave
  notes on a hot card without clobbering each other (`note!` docstring).
- **The card view is the single resume entry point.** It filters the subtree to
  *active* work and intersects it with the engine ready frontier, so you see the
  tasks you can actually start, not the whole history. `related` surfaces
  `depends-on` edges in both directions, so a blocker on another card shows up
  before you start down a dead end (`card-view`;
  `kanban-card-related-both-directions`).

Honest source: the `note!`/`card-view` source, the spool `prime` notes block, and the card-view and board assertions in `kanban_test.clj`.

---

## Recipe: Watch the cross-card review queue

**Situation.** Several cards are claimed or in review, each spawning review work. A coordinator (or a human) needs one queue of what's ready to review right now, without opening every card.

**Composition.** Read `board` and use its `needs-review` list. It aggregates, across claimed and in-review feature cards, the descendants that are active, in the ready frontier, and marked for human review — each tagged with its card and branch.

```sh
strand kanban board | jq '.needs-review'
# => [{"card": "...", "branch": "board-rewrite", "item": {"id": "...", "title": "Review the ops"}}]
```

To feed that queue, mark review work as such when you build the subtree — a `kind: review` strand, or one flagged `hitl` / `workflow/hitl`:

```sh
review=$(strand add "Review the ops" --attr kind=review | jq -r '.id')
strand update "$card" --edge parent-of:"$review"
strand update "$review" --edge depends-on:"$impl"   # stays out of the queue until impl closes
```

**Why this shape.**

- **The queue is always present and always current.** `needs-review` is computed
  on every `board` call, so there's no separate index to maintain and it can't
  drift from the graph. An empty queue is a real answer, not a missing one
  (`kanban-board-needs-review-frontier`).
- **Only *ready* review work surfaces.** A review that still depends on unfinished
  work is deliberately excluded, so the queue is things a reviewer can act on now,
  not a wish list. The `depends-on` edge above is exactly what holds a review out
  of the frontier until its implementation closes (`needs-review-entries`;
  same test).
- **Each entry carries its branch, so review lands in the right tree.** The card's
  claim-time `branch` rides along on every entry, so a reviewer knows which
  worktree to check out before reading the diff.
- **What counts as review is a small, open vocabulary.** `kind: review`, `hitl`,
  or `workflow/hitl` all qualify, which is why engine review gates and ad-hoc
  review strands both show up in the same queue without kanban knowing about
  either (`review-item?`; spool `prime` `:staying-aware`).

Honest source: `needs-review-entries` / `board` in the spool source, the `:staying-aware` prime block, and `kanban-board-needs-review-frontier`.

---

## See also

- [`kanban.md`](./kanban.md) — the contract: the board model, the lane and
  priority ladder, the `kanban/*` attribute table, and the CLI op surface.
- [`src/ct/spools/kanban.clj`](./src/ct/spools/kanban.clj) — signatures
  and docstrings for every verb and helper referenced above.
- `strand kanban prime` — the live, spool-authored working discipline (working
  agreement, pick-up flow, note-as-you-go/resume-from-task contract, adjacent-work awareness,
  branch visibility). The single source these recipes distil.
- `strand kanban about` — the terse command manual.
- `strand pattern explain kanban-batch` — the batch pattern's input contract.
