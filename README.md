# kanban.spool

`ct.spools.kanban` provides the user-facing kanban work board for
[Skein](https://github.com/codethread/skein) as a git-distributed spool:
feature/epic cards, refinement/pending/claimed/in_review lanes, a derived-status
task tier, notes, and the `strand kanban` CLI op.

It is trusted Clojure code for a live Skein weaver. The spool has no
`spool.edn` manifest; consumption is the manifest-free contract: approve source
in `spools.edn` or `spools.local.edn`, then declare the module explicitly from
trusted startup or REPL code.

The board contract lives in [kanban.md](./kanban.md); worked composition
recipes live in [kanban.cookbook.md](./kanban.cookbook.md). At runtime,
`strand kanban prime` is the agent-facing working discipline and
`strand kanban about` the terse command manual — both authored in the spool, so
they cannot drift from the installed surface.

`kanban-export` plus the bun renderer in [scripts/kanban-export](./scripts/kanban-export)
render a card's subtree to a standalone HTML file offline (see kanban.md's
[Offline export](./kanban.md#offline-export) section).

## Prerequisites

- A Skein checkout/runtime providing the blessed `skein.api.*.alpha` surface.
- A live weaver configured from a workspace you control.
- A 40-hex git SHA pin for this repository, or a local checkout approved through
  `spools.local.edn` for development.

Kanban core has **no spool prerequisites**: it carries no compile- or load-time
dependency on devflow or any other tracker. The `kanban card` run projection is
a runtime binding supplied by trusted config, so a world can run kanban whether
it binds devflow, binds another tracker, or binds nothing (see the
[Tracker seam](./kanban.md#tracker-seam)). This spool declares no Maven
dependencies of its own.

## Dependency information

Approve every source spool explicitly; no prerequisite is fetched
transitively. Kanban itself needs only its own coordinate.

Shared workspace example:

```clojure
{:spools {codethread/kanban {:git/url "git@github.com:codethread/kanban.spool.git"
                             :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

A repo that wants the devflow tracker binding also approves devflow (and the
workflow spool root it requires), but kanban never loads them itself:

```clojure
{:spools {skein.spools/workflow {:local/root "/path/to/your/skein/spools/workflow"}
          codethread/devflow {:git/url "git@github.com:codethread/devflow.spool.git"
                              :git/sha "<40-hex-sha-for-the-approved-commit>"}
          codethread/kanban {:git/url "git@github.com:codethread/kanban.spool.git"
                             :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

The workflow root can instead be a sha-pinned nested-root git coordinate on the
Skein repo (`:git/url` + `:git/sha` + `:deps/root "spools/workflow"`); both
forms and the version-skew convention are covered in [Skein's nested-spool
prerequisites
guidance](https://github.com/codethread/skein/blob/main/docs/spools/writing-shared-spools.md#nested-spool-prerequisites).

Local development overlay example (`spools.local.edn`, usually gitignored):

```clojure
{:spools {codethread/kanban {:local/root "/Users/you/dev/kanban.spool"}}}
```

Do not copy a `spool.edn`; this repository intentionally does not ship one.
Metadata, prerequisites, and activation order are documented here rather than
encoded in a manifest.

## Activation

The consumer owns the runtime and declares kanban explicitly from trusted
`init.clj` or REPL code. Kanban has no prerequisite module:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime
  :kanban
  {:spools ['codethread/kanban]
   :ns 'ct.spools.kanban
   :contribute 'ct.spools.kanban/contribute
   :reconcile 'ct.spools.kanban/reconcile
   :required? true})
```

Applied reconciliation registers the `kanban` and `kanban-export` ops, the `kanban-batch` weave pattern, the
`kanban-cards`/`kanban-pending` queries, and declares the `kanban/*`
attribute namespace. It never binds a tracker. Replacing the module atomically replaces both
ops, the batch pattern, and both board queries; removing it removes those
declarations without clearing stored cards or the tracker binding.

### Binding a tracker (optional)

To have `kanban card` project a run's status and ready steps, bind a tracker
strategy after kanban activates (see the [Tracker seam](./kanban.md#tracker-seam)
for the contract). A repo that stages work through devflow activates devflow and
its workflow prerequisite, then binds a small trusted-config adapter:

```clojure
;; workflow is an approved spool root, not base-classpath code: guard the
;; module on its coordinate so a missing approval fails loudly.
(runtime/module! runtime
  :workflow
  {:ns 'skein.spools.workflow
   :spools ['skein.spools/workflow]
   :contribute 'skein.spools.workflow/contribute
   :reconcile 'skein.spools.workflow/reconcile
   :required? true})

(runtime/module! runtime
  :devflow
  {:spools ['codethread/devflow]
   :ns 'ct.spools.devflow
   :contribute 'ct.spools.devflow/contribute
   :reconcile 'ct.spools.devflow/reconcile
   :after [:workflow]
   :required? true})

;; kanban_tracker.clj composes devflow's read fns into kanban's projection shape
;; and calls kanban/set-tracker!; it is the one place that knows both vocabularies.
(runtime/module! runtime
  :kanban/tracker
  {:file "kanban_tracker.clj"
   :spools ['codethread/kanban 'codethread/devflow]
   :after [:kanban :devflow]
   :contribute 'kanban-tracker/contribute
   :reconcile 'kanban-tracker/reconcile})
```

A repo with a different tracker writes its own module against the same contract;
a repo with no tracker skips the block entirely and stamped cards project as
unbound.

## Development

Tests run standalone against a sibling Skein checkout (see the `:test` alias
in [deps.edn](./deps.edn) for the exact root):

```sh
clojure -M:test
```
