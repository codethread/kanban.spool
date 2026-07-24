(require '[skein.api.current.alpha :as current]
         '[skein.api.runtime.alpha :as runtime])

(def runtime (current/runtime))

;; Requires Skein commit 343f886880092bc38ed3e0522eca2d95a7cf04bc or a
;; descendant. Each declaration names a source target and world policy only:
;; namespace targets resolve entry points from that namespace's `spool` var,
;; while the file target resolves them from the single namespace its file
;; declares.

;; Batteries is approved as a shipped source-root spool; the :spools guard
;; keeps source loading behind that visible spools.edn approval.
(runtime/module! runtime :skein/spools-batteries
  {:ns 'skein.spools.batteries
   :spools ['skein.spools/batteries]})

;; Board peering (kanban.md "Peering"): guild first, kanban second, peering
;; last — install-peering! fails loudly unless both predecessors reconciled.
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
  {:file "peering_adapter.clj"
   :spools ['codethread/kanban 'skein.spools/guild]
   :after [:guild :kanban]
   :required? true})
