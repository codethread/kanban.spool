(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Requires Millstrand commit 24f900464d9b26e8e3d81550eef3a06230de5395 or a
;; descendant. Each declaration names a source target and world policy only;
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
(runtime/module! runtime :guild
  {:ns 'millstrand.spools.guild
   :spools ['millstrand.spools/guild]
   :required? true})

(runtime/module! runtime :kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :after [:guild]
   :required? true})

(runtime/module! runtime :kanban/peering
  {:ns 'ct.spools.kanban.peering
   :spools ['codethread/kanban 'millstrand.spools/guild]
   :after [:guild :kanban]
   :required? true})
