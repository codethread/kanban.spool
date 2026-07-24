(ns peering-adapter
  "Board-peering activation: kanban keeps install-peering! as the imperative
  opt-in (it never load-depends on guild), so this world reconciles it as its
  own module after guild and kanban."
  (:require [skein.api.current.alpha :as current]))

(defn reconcile
  "Install kanban board peering; requires guild and kanban already reconciled."
  [{:keys [runtime]}]
  (current/with-runtime runtime
    ((requiring-resolve 'ct.spools.kanban/install-peering!)))
  {:reconciled :kanban/peering})

(def spool
  "Entry-point declaration for this file module.

  The file declares one namespace, so the coordinator resolves the reconciler
  from this var and the `:file` declaration in init.clj names only the source
  target and world policy."
  {:reconcile 'reconcile})
