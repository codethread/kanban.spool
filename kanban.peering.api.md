
-----
# <a name="ct.spools.kanban.peering">ct.spools.kanban.peering</a>


Opt-in board peering: the RECEIVE guild op plus the SEND-side local ops.

  A trusted-config module activates this namespace after the guild and kanban
  modules. Static forms publish the two local operations, and a named lifecycle
  resource registers the Guild receiver; the prerequisites fail loudly when
  missing:

  - `kanban.send.v1` — the guild receive op. A sibling weaver drops a card, or
    an epic bundle, onto this board. Received cards travel the same
    `ct.spools.kanban/add!` code path as local cards, so defaults, lanes, and
    epic `parent-of` wiring are identical; `:from` provenance is stamped as one
    `kanban/from` attribute. Guild parses the op's single JSON argument to a
    keyword-keyed map at `:guild/input`, so `::send-input` specs keyword keys
    throughout.
  - `kanban-peers` — list sibling weavers and, for each running one, whether it
    advertises `kanban.send.v1` (so a caller knows where a card can be sent).
  - `kanban-send` — resolve a local card and mirror the board tier onto a peer's
    board over `kanban.send.v1`.

  The two peering seams onto sibling weavers — enumerate/probe and invoke — go
  through `millstrand.api.peers.alpha` behind `*list-peers*`, `*list-peer-guild*`, and
  `*send-card*` so classification and payload building are testable without a
  live socket peer.




## <a name="ct.spools.kanban.peering/*list-peer-guild*">`*list-peer-guild*`</a>



<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L229-L229">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/*list-peers*">`*list-peers*`</a>



<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L228-L228">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/*send-card*">`*send-card*`</a>



<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L230-L230">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/close-peering!">`close-peering!`</a>
``` clojure
(close-peering! _context)
```
Function.

Close peering's module resource without claiming Guild's dispatch-table teardown.

  Guild owns receiver registration and has no per-op removal seam in this
  baseline. Its own lifecycle reset removes receivers; this close therefore
  preserves the established process-lifetime receiver behavior while static
  peering operations retract with their module owner.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L716-L724">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/kanban-peering-receiver">`kanban-peering-receiver`</a>




Own guarded Guild receiver registration for the peering module lifetime.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L726-L729">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/kanban-peers-op">`kanban-peers-op`</a>
``` clojure
(kanban-peers-op ctx)
```
Function.

List sibling weavers and whether each accepts peered kanban cards.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L687-L692">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/kanban-send-op">`kanban-send-op`</a>
``` clojure
(kanban-send-op ctx)
```
Function.

Send a pending or refinement card (or epic bundle) to a sibling weaver's board.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L694-L699">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/open-peering!">`open-peering!`</a>
``` clojure
(open-peering! {:keys [runtime]})
```
Function.

Register the guarded `kanban.send.v1` receiver through Guild's supported seam.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L701-L714">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/send-op">`send-op`</a>
``` clojure
(send-op {:guild/keys [input], :op/keys [runtime]})
```
Function.

Receive a peered card or epic bundle onto this board.

  Handles the guild op `kanban.send.v1`: `:guild/input` is the spec-validated,
  keyword-keyed JSON body. A `:card` creates a single feature; an `:epic` +
  `:features` bundle creates the epic and hangs each feature under it with a
  `parent-of` edge (same path as `kanban add --epic`), preserving input order.
  Returns JSON-safe ids only.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L185-L203">Source</a></sub></p>
