(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Requires Skein commit 24f900464d9b26e8e3d81550eef3a06230de5395 or a
;; descendant. Each declaration names a source target and world policy only;
;; static contribution and lifecycle forms in the target namespace provide its
;; complete owner partition.

;; Batteries is approved as a shipped source-root spool; the :spools guard
;; keeps source loading behind that visible spools.edn approval.
(runtime/module! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :spools ['skein.spools/batteries]})

;; Board peering (kanban.md "Peering"): guild first, kanban second, peering
;; last — its lifecycle resource fails loudly unless both predecessors are
;; active.
(runtime/module! runtime :guild
  {:ns 'skein.spools.guild
   :spools ['skein.spools/guild]
   :required? true})

(runtime/module! runtime :kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :after [:guild]
   :required? true})

(runtime/module! runtime :kanban/peering
  {:ns 'ct.spools.kanban.peering
   :spools ['codethread/kanban 'skein.spools/guild]
   :after [:guild :kanban]
   :required? true})
