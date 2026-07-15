(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

(runtime/sync! runtime)
(runtime/use! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :call 'skein.spools.batteries/activate!})

;; Board peering (kanban.md "Peering"): guild first, kanban second, peering
;; last — install-peering! fails loudly unless both predecessors registered.
(runtime/use! runtime :guild
  {:ns 'skein.spools.guild
   :spools ['skein.spools/guild]
   :call 'skein.spools.guild/install!
   :required? true})

(runtime/use! runtime :kanban
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban]
   :call 'ct.spools.kanban/install!
   :required? true})

(runtime/use! runtime :kanban/peering
  {:ns 'ct.spools.kanban
   :spools ['codethread/kanban 'skein.spools/guild]
   :after [:guild :kanban]
   :call 'ct.spools.kanban/install-peering!
   :required? true})
