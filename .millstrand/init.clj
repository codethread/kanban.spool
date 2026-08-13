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

(runtime/module! runtime :millhouse/spools-workflow
                 {:ns 'millhouse.spools.workflow
                  :spools ['millhouse.spools/workflow]
                  :required? true})

(runtime/module! runtime :millhouse/spools-workflow-cli
                 {:ns 'millhouse.spools.workflow.cli
                  :spools ['millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow]
                  :required? true})

(runtime/module! runtime :millhouse/spools-millstrand-workflows
                 {:ns 'millhouse.spools.millstrand-workflows
                  :spools ['millhouse.spools/millstrand-workflows
                           'millhouse.spools/workflow]
                  :after [:millhouse/spools-workflow
                          :millhouse/spools-workflow-cli]
                  :required? true})

(runtime/module! runtime :kanban
                 {:ns 'ct.spools.kanban
                  :spools ['codethread/kanban]
                  :required? true})
