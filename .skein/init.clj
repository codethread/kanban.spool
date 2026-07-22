(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; batteries ships on the classpath (:paths), not a synced spool root; require
;; it so the module source load classifies it as classpath-owned.
(require 'skein.spools.batteries)
(runtime/module! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :contribute 'skein.spools.batteries/contribute
   :reconcile 'skein.spools.batteries/reconcile})

;; Board peering (kanban.md "Peering"): guild first, kanban second, peering
;; last — install-peering! fails loudly unless both predecessors reconciled.
(runtime/module! runtime :guild
  {:ns 'skein.spools.guild
   :spools ['skein.spools/guild]
   :contribute 'skein.spools.guild/contribute
   :reconcile 'skein.spools.guild/reconcile
   :required? true})

(runtime/module! runtime :kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :after [:guild]
   :contribute 'ct.spools.kanban/contribute
   :reconcile 'ct.spools.kanban/reconcile
   :required? true})

(runtime/module! runtime :kanban/peering
  {:file "peering_adapter.clj"
   :spools ['codethread/kanban 'skein.spools/guild]
   :after [:guild :kanban]
   :reconcile 'peering-adapter/reconcile
   :required? true})
