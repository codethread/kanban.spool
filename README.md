# kanban.spool

`skein.spools.kanban` provides the user-facing kanban work board for
[Skein](https://github.com/codethread/skein) as a git-distributed spool:
feature/epic cards, refinement/pending/claimed/in_review lanes, a derived-status
task tier, notes, and the `strand kanban` CLI op.

It is trusted Clojure code for a live Skein weaver. The spool has no
`spool.edn` manifest; consumption is the manifest-free contract: approve source
in `spools.edn` or `spools.local.edn`, run `sync!`, then activate explicitly
with `use!`.

The board contract lives in [kanban.md](./kanban.md); worked composition
recipes live in [kanban.cookbook.md](./kanban.cookbook.md). At runtime,
`strand kanban prime` is the agent-facing working discipline and
`strand kanban about` the terse command manual — both authored in the spool, so
they cannot drift from the installed surface.

## Prerequisites

- A Skein checkout/runtime providing the blessed `skein.api.*.alpha` surface.
- A live weaver configured from a workspace you control.
- The devflow spool ([`codethread/devflow.spool`](https://github.com/codethread/devflow.spool)),
  approved as its own coordinate: `kanban card` joins a card's named devflow
  run (stage + ready steps), so `skein.spools.kanban` requires
  `skein.spools.devflow` at load. The dependency is one-way — devflow never
  depends on kanban.
- A 40-hex git SHA pin for this repository, or a local checkout approved through
  `spools.local.edn` for development.

This spool declares no Maven dependencies of its own.

## Dependency information

Approve every source spool explicitly; no prerequisite is fetched
transitively. Kanban's one spool prerequisite is devflow, which in turn builds
on the `skein.spools.workflow` namespace shipped by Skein.

Shared workspace example:

```clojure
{:spools {codethread/devflow {:git/url "git@github.com:codethread/devflow.spool.git"
                              :git/sha "<40-hex-sha-for-the-approved-commit>"}
          codethread/kanban {:git/url "git@github.com:codethread/kanban.spool.git"
                             :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

Local development overlay example (`spools.local.edn`, usually gitignored):

```clojure
{:spools {codethread/kanban {:local/root "/Users/you/dev/kanban.spool"}}}
```

Do not copy a `spool.edn`; this repository intentionally does not ship one.
Metadata, prerequisites, and activation order are documented here rather than
encoded in a manifest.

## Activation

Activate prerequisites before dependents: workflow (shipped with Skein), then
devflow, then kanban. The consumer owns the runtime, calls `sync!`, and
activates each module explicitly from trusted `init.clj` or REPL code.

```clojure
(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/use! runtime
  :workflow
  {:ns 'skein.spools.workflow
   :required? true})

(runtime/sync! runtime)

(runtime/use! runtime
  :devflow
  {:spools [codethread/devflow]
   :ns 'skein.spools.devflow
   :call 'skein.spools.devflow/install!
   :after [:workflow]
   :required? true})

(runtime/use! runtime
  :kanban
  {:spools [codethread/kanban codethread/devflow]
   :ns 'skein.spools.kanban
   :call 'skein.spools.kanban/install!
   :after [:devflow]
   :required? true})
```

Keep kanban's `:after [:devflow]` and both coordinates in its `:spools` guard:
the namespace requires `skein.spools.devflow`, so devflow's approved source
must be synced before kanban loads.

`install!` registers the `kanban` op, the `kanban-batch` weave pattern, the
`kanban-cards`/`kanban-unstarted` queries, and declares the `kanban/*`
attribute namespace.

## Development

Tests run standalone against a sibling Skein checkout (see the `:test` alias
in [deps.edn](./deps.edn) for the exact roots):

```sh
clojure -M:test
```
