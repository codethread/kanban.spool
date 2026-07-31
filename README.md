# kanban.spool

`ct.spools.kanban` provides the user-facing kanban work board for
[Skein](https://github.com/codethread/skein) as a git-distributed spool:
feature/epic cards, refinement/pending/claimed/in_review lanes, a derived-status
task tier, notes, free-form labels, and the `strand kanban` CLI op.

It is trusted Clojure code for a live Skein weaver. The spool has no
`spool.edn` manifest; consumption is the manifest-free contract: approve source
in `spools.edn` or `spools.local.edn`, then declare the module explicitly from
trusted startup or REPL code.

The board contract lives in [kanban.md](./kanban.md); worked composition
recipes live in [kanban.cookbook.md](./kanban.cookbook.md). At runtime,
`strand kanban prime` is the agent-facing working discipline and
`strand kanban about` the terse command manual — both authored in the spool, so
they cannot drift from the installed surface.

`kanban-export` plus the Bun renderer in [scripts/kanban-export](./scripts/kanban-export)
render a card's subtree to a standalone HTML file offline (see kanban.md's
[Offline export](./kanban.md#offline-export) section).

The `kanban-dash` bin provides an interactive terminal board with epic and feature views, label filters, saved per-workspace views, and keyboard-driven navigation.

## Prerequisites

- A Skein checkout/runtime at commit `60e80c5d0d3c3b80f8e60ec9a510fc660669b07d` or a descendant. That commit adds the `defbin` form and `mill bin` commands used by `kanban-dash`. No Skein release marker contains that floor yet, so this requirement cannot yet be expressed as `:skein/min`.
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

The consumer owns the runtime and declares kanban explicitly from trusted `init.clj` or REPL code. The declaration names a source target and world policy only. Static authoring forms in [`src/ct/spools/kanban.clj`](./src/ct/spools/kanban.clj) publish the complete operation, pattern, and query partitions; a named lifecycle resource owns vocabulary and runtime-state setup. Kanban has no prerequisite module:

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime
  :kanban
  {:spools ['codethread/kanban]
   :ns 'ct.spools.kanban
   :required? true})
```

Source activation collects the `kanban` and `kanban-export` ops, the `kanban-dash` bin, the `kanban-batch` weave pattern, and the `kanban-cards`, `kanban-pending`, and `kanban-epic-pending` queries. The lifecycle resource declares the `kanban/*` and open `kanban.label/*` attribute namespaces and materializes runtime state; it never binds a tracker. Its `:open` and `:close` symbols are implementation callables required by the lifecycle form, not public consumer APIs or new spec surfaces. Image activation replays the same normalized declaration record. Removing the module retracts both ops, the bin, the pattern, and all three queries without clearing stored cards or the process-lifetime tracker binding.

Runtime-dependent Clojure functions take the target runtime first. For example, use `(kanban/add! runtime title flags)`, `(kanban/board runtime)`, and `(kanban/set-tracker! runtime binding)`. The registered CLI operations select the invoking weaver and pass its runtime into the same functions.

### Binding a tracker (optional)

To have `kanban card` project a run's status and ready steps, bind a tracker
strategy after kanban activates (see the [Tracker seam](./kanban.md#tracker-seam)
for the contract). A repo that stages work through devflow activates devflow and
its workflow prerequisite, then binds a small trusted-config adapter. The
convention-only pair below requires coordinated devflow `v6` and the same Skein
ancestry floor named above:

```clojure
;; workflow is an approved spool root, not base-classpath code: guard the
;; module on its coordinate so a missing approval fails loudly.
(runtime/module! runtime
  :workflow
  {:ns 'skein.spools.workflow
   :spools ['skein.spools/workflow]
   :required? true})

(runtime/module! runtime
  :devflow
  {:spools ['codethread/devflow]
   :ns 'ct.spools.devflow
   :after [:workflow]
   :required? true})

;; kanban_tracker.clj composes devflow's read fns into kanban's projection shape
;; and calls kanban/set-tracker!; it is the one place that knows both vocabularies.
(runtime/module! runtime
  :kanban/tracker
  {:file "kanban_tracker.clj"
   :spools ['codethread/kanban 'codethread/devflow]
   :after [:kanban :devflow]})
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
