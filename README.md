# kanban.spool

`ct.spools.kanban` provides the user-facing kanban work board for [Millstrand](https://github.com/codethread/millstrand) as a git-distributed spool: feature/epic cards, refinement/pending/claimed/in_review lanes, a derived-status task tier, notes, free-form labels, and the `strand kanban` CLI op.

It is trusted Clojure code for a live Millstrand weaver. The spool has no `spool.edn` manifest; consumption is the manifest-free contract: approve source in `spools.edn` or `spools.local.edn`, then declare the module explicitly from trusted startup or REPL code.

The board contract lives in [kanban.md](./kanban.md); worked composition recipes live in [kanban.cookbook.md](./kanban.cookbook.md). At runtime, `strand kanban prime` is the agent-facing working discipline and `strand kanban about` the terse command manual — both authored in the spool, so they cannot drift from the installed surface.

`kanban-export` plus the Bun renderer in [scripts/kanban-export](./scripts/kanban-export) render a card's subtree to a standalone HTML file offline (see kanban.md's [Offline export](./kanban.md#offline-export) section).

The `kanban-dash` bin provides an interactive terminal board with epic and feature views, label filters, saved per-workspace views, and keyboard-driven navigation.

## Prerequisites

- A Millstrand checkout/runtime at commit `60e80c5d0d3c3b80f8e60ec9a510fc660669b07d` or a descendant. That commit adds the `defbin` form and `mill bin` commands used by `kanban-dash`. No Millstrand release marker contains that floor yet, so this requirement cannot yet be expressed as `:millstrand/min`.
- A live weaver configured from a workspace you control.
- A 40-hex git SHA pin for this repository, or a local checkout approved through
  `spools.local.edn` for development.

Kanban core has no spool prerequisites or Maven dependencies of its own.

## Dependency information

Approve every source spool explicitly; no prerequisite is fetched transitively. Kanban itself needs only its own coordinate.

Shared workspace example:

```clojure
{:spools {codethread/kanban {:git/url "git@github.com:codethread/kanban.spool.git"
                             :git/sha "<40-hex-sha-for-the-approved-commit>"}}}
```

Local development overlay example (`spools.local.edn`, usually gitignored):

```clojure
{:spools {codethread/kanban {:local/root "/Users/you/dev/kanban.spool"}}}
```

Do not copy a `spool.edn`; this repository intentionally does not ship one. Metadata, prerequisites, and activation order are documented here rather than encoded in a manifest.

## Activation

The consumer owns the runtime and declares kanban explicitly from trusted `init.clj` or REPL code. The declaration names a source target and world policy only. Static authoring forms in [`src/ct/spools/kanban.clj`](./src/ct/spools/kanban.clj) publish the complete operation, pattern, and query partitions; a named lifecycle resource owns vocabulary and runtime-state setup. Kanban has no prerequisite module:

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/module! runtime
  :kanban
  {:spools ['codethread/kanban]
   :ns 'ct.spools.kanban
   :required? true})
```

Source activation collects the `kanban` and `kanban-export` ops, the `kanban-dash` bin, the `kanban-batch` weave pattern, and the `kanban-cards`, `kanban-pending`, and `kanban-epic-pending` queries. The lifecycle resource declares the `kanban/*` and open `kanban.label/*` attribute namespaces and materializes runtime state. Its `:open` and `:close` symbols are implementation callables required by the lifecycle form, not public consumer APIs or new spec surfaces. Image activation replays the same normalized declaration record. Removing the module retracts both ops, the bin, the pattern, and all three queries without clearing stored cards.

Runtime-dependent Clojure functions take the target runtime first. For example,
use `(kanban/add! runtime title flags)` and `(kanban/board runtime)`. The
registered CLI operations select the invoking weaver and pass its runtime into
the same functions.

## Development

Tests run standalone against a sibling Millstrand checkout (see the `:test` alias in [deps.edn](./deps.edn) for the exact root):

```sh
clojure -M:test
```

## Release validation

`bin/identity-check` is the CI gate for active legacy product names. `bin/verify-release` validates the SHA-only core release input, loads Kanban in a clean consumer, and proves that `.millstrand` and `.ms` select the same database. Run candidate proof before landing and published proof only after the annotated Kanban marker exists; the exact commands and required inputs are in [release-exception.md](./release-exception.md).
