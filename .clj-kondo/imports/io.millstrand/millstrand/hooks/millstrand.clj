(ns hooks.millstrand
  "clj-kondo analysis hooks for Millstrand's public authoring forms."
  (:require [clj-kondo.hooks-api :as api]))

(defn- sexpr [node]
  (try
    (api/sexpr node)
    (catch Exception error
      (if (= "java.lang.UnsupportedOperationException"
             (.getName (class error)))
        ::unreadable
        (throw (ex-info "Unable to read a clj-kondo hook node"
                        {:node (select-keys (meta node) [:filename :row :col :end-row :end-col])}
                        error))))))

(defn- defn-hook
  "Analyze a function-shaped authoring form as a `defn`."
  [{:keys [node]}]
  (let [[form-node name-node docstring-node opts-node argv-node & body] (:children node)
        defn-node (api/list-node
                   (list* (api/token-node 'defn)
                          name-node docstring-node argv-node body))
        used (api/list-node
              (list (api/token-node 'do)
                    (api/list-node
                     (list (api/token-node 'identity) form-node))
                    (api/list-node
                     (list (api/token-node 'identity) opts-node))
                    defn-node))]
    {:node (with-meta used (meta node))}))

(defn- defop-hook
  "Analyze `defop` as a definition of its `<name>-op` handler Var."
  [{:keys [node]}]
  (let [[form-node name-node docstring-node opts-node argv-node & body] (:children node)
        name (sexpr name-node)]
    (if (= ::unreadable name)
      {:node (api/list-node [])}
      (let [handler-node (with-meta
                           (api/token-node (symbol (str name "-op")))
                           (meta name-node))
            defn-node (api/list-node
                       (list* (api/token-node 'defn)
                              handler-node docstring-node argv-node body))
            used (api/list-node
                  (list (api/token-node 'do)
                        (api/list-node
                         (list (api/token-node 'identity) form-node))
                        (api/list-node
                         (list (api/token-node 'identity) opts-node))
                        defn-node))]
        {:node (with-meta used (meta node))}))))

(defn- defquery-hook
  "Analyze `defquery` as a definition of its query Var."
  [{:keys [node]}]
  (let [[form-node name-node docstring-node opts-node query-node] (:children node)
        used (api/list-node
              (list (api/token-node 'do)
                    (api/list-node
                     (list (api/token-node 'identity) form-node))
                    (api/list-node
                     (list (api/token-node 'identity) opts-node))
                    query-node))
        def-node (api/list-node
                  (list (api/token-node 'def)
                        name-node docstring-node used))]
    {:node (with-meta def-node (meta node))}))

(defn defop
  "Analyze a Millstrand `defop` call."
  [context]
  (defop-hook context))

(defn defquery
  "Analyze a Millstrand `defquery` call."
  [context]
  (defquery-hook context))

(defn defpattern
  "Analyze a Millstrand `defpattern` call."
  [context]
  (defn-hook context))

(defn defhook
  "Analyze a Millstrand `defhook` call."
  [context]
  (defn-hook context))

(defn defhandler
  "Analyze a Millstrand `defhandler` call."
  [context]
  (defn-hook context))

(defn defbin
  "Analyze a Millstrand `defbin` call as a Var definition."
  [{:keys [node]}]
  (let [[form-node name-node docstring-node opts-node] (:children node)
        value-node (api/list-node
                    (list (api/token-node 'do)
                          (api/list-node
                           (list (api/token-node 'identity) form-node))
                          (api/list-node
                           (list (api/token-node 'identity) opts-node))))
        def-node (api/list-node
                  (list (api/token-node 'def)
                        name-node docstring-node value-node))]
    {:node (with-meta def-node (meta node))}))
