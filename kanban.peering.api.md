
-----
# <a name="ct.spools.kanban.peering">ct.spools.kanban.peering</a>


Opt-in board peering: the RECEIVE guild op plus the SEND-side local ops.

  A trusted-config module wires `ct.spools.kanban/install-peering!` in after
  the guild and kanban modules are active (both are module-lifecycle
  activations; the prerequisites fail loudly when missing). That entry point
  registers three ops:

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
  through `skein.api.peers.alpha` behind `*list-peers*`, `*list-peer-guild*`, and
  `*send-card*` so classification and payload building are testable without a
  live socket peer.




## <a name="ct.spools.kanban.peering/*list-peer-guild*">`*list-peer-guild*`</a>



<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L230-L230">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/*list-peers*">`*list-peers*`</a>



<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L229-L229">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/*send-card*">`*send-card*`</a>



<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L231-L231">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/contribute">`contribute`</a>
``` clojure
(contribute _ctx)
```
Function.

Return the complete owner set for peering's local CLI declarations.

  Guild owns its dispatch facade and receive-op table; `reconcile` below keeps
  the `kanban.send.v1` handler in that table.  The two board-local operations
  are ordinary core registry entries, so publishing them here gives refresh its
  deletion semantics without ad-hoc register-or-replace probing.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L671-L688">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/install-peering!">`install-peering!`</a>
``` clojure
(install-peering!)
```
Function.

Register the receive and send-side board-peering ops after guild and kanban.

  Opt-in: trusted config wires this in after the guild and kanban modules are
  active. It never activates guild itself — guild's lifecycle has exactly one
  owner, the repo config. Both preconditions fail loudly with the failing state
  and the remedy. Registers three ops: the `kanban.send.v1` guild receive op,
  and the local `kanban-peers` and `kanban-send` ops. Every registration
  upserts (`guild/register-op!` and `register-or-replace-op!`), so re-running
  is reload-safe.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L735-L768">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/peers-op">`peers-op`</a>
``` clojure
(peers-op _ctx)
```
Function.

List sibling weavers and whether each accepts peered kanban cards.

  Every metadata row from `peers/peers` is listed, including stale ones
  (`:running? false`), which are never probed. Each running non-self peer is
  probed via `guild list`; `:kanban-send? true` when `kanban.send.v1` is
  active. The local weaver is marked `:self? true` when it appears in the roster
  and answers from the local op registry rather than calling its own socket. The
  return conforms to `::peers-result` (rows to `::peer-row`).
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L301-L327">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/reconcile">`reconcile`</a>
``` clojure
(reconcile {:keys [runtime], :as ctx})
```
Function.

Reconcile Guild's receive table after local owner publication.

  Guild has the receive-dispatch state in this frozen baseline; its supported
  registrar is idempotent, preserving the established wire contract and seams.
  Local board operations themselves are entirely owner-published by
  `contribute`.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L708-L725">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/send-card-op">`send-card-op`</a>
``` clojure
(send-card-op #:op{:keys [args runtime-metadata]})
```
Function.

Send a local card or epic bundle to a sibling weaver's board.

  Resolves the local card, refuses in-flight or finished work (with the lane in
  the error), and mirrors the board tier — titles, bodies, priority, source, and
  lane — as a `kanban.send.v1` payload stamped with `:from` provenance. Preflights
  the target's `guild list` for the op, sends the payload as one JSON `:argv`
  string, and validates the peer's reply (`validate-send-result!`) before
  recording the created remote ids as a note on the local card. Returns the
  remote ids, conforming to `::send-result`. The local card's lane is never
  touched — closing it stays the caller's choice.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L602-L627">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/send-op">`send-op`</a>
``` clojure
(send-op #:guild{:keys [input]})
```
Function.

Receive a peered card or epic bundle onto this board.

  Handles the guild op `kanban.send.v1`: `:guild/input` is the spec-validated,
  keyword-keyed JSON body. A `:card` creates a single feature; an `:epic` +
  `:features` bundle creates the epic and hangs each feature under it with a
  `parent-of` edge (same path as `kanban add --epic`), preserving input order.
  Returns JSON-safe ids only.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L185-L204">Source</a></sub></p>

## <a name="ct.spools.kanban.peering/spool">`spool`</a>




Entry-point declaration for the kanban peering spool.

  Consumers declare only its source target and world policy. Unqualified
  symbols resolve against this namespace.
<p><sub><a href="https://github.com/codethread/kanban.spool/blob/main/src/ct/spools/kanban/peering.clj#L727-L733">Source</a></sub></p>
