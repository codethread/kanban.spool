(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Requires Millstrand commit fb6c9057 or a descendant. Each declaration names
;; a source target and world policy only;
;; static contribution and lifecycle forms in the target namespace provide its
;; complete owner partition.

;; Batteries is approved as a shipped source-root spool; the :spools guard
;; keeps source loading behind that visible spools.edn approval.
(runtime/module! runtime :millstrand/spools-batteries
  {:ns 'millstrand.spools.batteries
   :spools ['millstrand.spools/batteries]})

;; Board peering (kanban.md "Peering"): guild first, kanban second, peering
;; last — its lifecycle resource fails loudly unless both predecessors are
;; active.
(runtime/module! runtime :skein/examples-guild
  {:ns 'skein.examples.guild
   :spools ['skein.examples/guild]
   :required? true})

(runtime/module! runtime :kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :after [:skein/examples-guild]
   :required? true})

(runtime/module! runtime :kanban/peering
  {:ns 'ct.spools.kanban.peering
   :spools ['codethread/kanban 'skein.examples/guild]
   :after [:skein/examples-guild :kanban]
   :required? true})
