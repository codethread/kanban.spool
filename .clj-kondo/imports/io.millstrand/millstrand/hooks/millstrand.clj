(ns hooks.millstrand
  "clj-kondo analysis hooks for Millstrand's public authoring forms."
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]))

(def ^:private source-meta-keys
  [:filename :row :col :end-row :end-col])

(defn- source-meta [node]
  (select-keys (meta node) source-meta-keys))

(defn- invalid-hook-context!
  [hook message node offending-node offending-value]
  (throw (ex-info (str (name hook) " hook " message)
                  {:node (source-meta node)
                   :offending-node offending-node
                   :offending-value offending-value})))

(defn- hook-children
  [hook context child-count exact?]
  (let [node (when (map? context) (:node context))
        children (when (api/node? node) (:children node))
        invalid-child (when (sequential? children)
                        (some #(when-not (api/node? %) [::invalid %]) children))]
    (cond
      (not (map? context))
      (invalid-hook-context! hook "context must be a map" nil nil context)

      (not (api/list-node? node))
      (invalid-hook-context! hook "context must contain a list node"
                             node node node)

      (not (sequential? children))
      (invalid-hook-context! hook "context node must contain child nodes"
                             node node children)

      invalid-child
      (invalid-hook-context! hook "context node contains a non-node child"
                             node node (second invalid-child))

      (and exact? (not= child-count (count children)))
      (invalid-hook-context! hook
                             (str "context node must contain exactly " child-count
                                  " children")
                             node node children)

      (< (count children) child-count)
      (invalid-hook-context! hook
                             (str "context node must contain at least " child-count
                                  " children")
                             node node children)

      :else
      [node children])))

(defn- sexpr [node]
  (try
    (api/sexpr node)
    (catch Exception error
      (throw (ex-info "Unable to read a clj-kondo hook node"
                      {:node (source-meta node)
                       :offending-node node}
                      error)))))

(defn- symbol-name
  [hook name-node]
  (let [name (sexpr name-node)]
    (if (symbol? name)
      name
      (invalid-hook-context! hook "name must be a symbol"
                             name-node name-node name))))

(defn- identity-node [node]
  (api/list-node (list (api/token-node 'identity) node)))

(defn- used-node [form-node value-nodes]
  (api/list-node
   (list* (api/token-node 'do)
          (identity-node form-node)
          (map identity-node value-nodes))))

(defn- definition-name-node [hook name-node]
  (symbol-name hook name-node)
  name-node)

(defn- barred-lines [block]
  (let [lines (keep (fn [line]
                      (when-let [index (str/index-of line "|")]
                        (subs line (inc index))))
                    (str/split-lines block))]
    (when (empty? lines)
      (throw (ex-info "|-margin block has no barred lines" {:block block})))
    lines))

(defn- reflow-margin-block [block]
  (->> (barred-lines block)
       (remove str/blank?)
       (map str/trim)
       (str/join " ")))

(defn- registry-macro-docstrings [noun]
  (let [article (if (= noun 'op) "an" "a")]
    [(format (reflow-margin-block
              "|Define an inert %s declaration; return its Var.")
             noun)
     (format (reflow-margin-block
              "|Select one or more %s declaration Vars; return them as a vector.")
             noun)
     (format (reflow-margin-block
              "|Define and select %s %s declaration; return its Var.")
             article noun)]))

(defn defvalue
  "Analyze a value-backed authoring form as a `def` at its authored name."
  [context]
  (let [[node children] (hook-children :defvalue context 4 false)
        [form-node name-node docstring-node & values] children]
    (definition-name-node :defvalue name-node)
    {:node (with-meta
             (api/list-node
              (list (api/token-node 'def)
                    name-node docstring-node
                    (used-node form-node values)))
             (meta node))}))

(defn deffn
  "Analyze a function-backed authoring form as a `defn` at its authored name."
  [context]
  (let [[node children] (hook-children :deffn context 5 false)
        [form-node name-node docstring-node & after-doc] children
        argv-index (first (keep-indexed
                           (fn [index child]
                             (when (api/vector-node? child) index))
                           after-doc))]
    (definition-name-node :deffn name-node)
    (when-not argv-index
      (invalid-hook-context! :deffn
                             "context must contain a function argument vector"
                             node node after-doc))
    (let [option-nodes (take argv-index after-doc)
          argv-node (nth after-doc argv-index)
          body (drop (inc argv-index) after-doc)
          defn-node (api/list-node
                     (list* (api/token-node 'defn)
                            name-node docstring-node argv-node body))]
      {:node (with-meta
               (api/list-node
                (into [(api/token-node 'do)
                       (identity-node form-node)]
                      (concat (map identity-node option-nodes)
                              [defn-node])))
               (meta node))})))

(defn use-vars
  "Analyze a typed use form as Var references without defining Vars."
  [context]
  (let [[node children] (hook-children :use-vars context 2 false)
        [form-node & args] children
        [options var-nodes] (if (api/map-node? (first args))
                              [(first args) (next args)]
                              [nil args])]
    (when (empty? var-nodes)
      (invalid-hook-context! :use-vars
                             "context must contain one or more Var symbols"
                             node node args))
    (doseq [var-node var-nodes]
      (when-not (symbol? (sexpr var-node))
        (invalid-hook-context! :use-vars "Var references must be symbols"
                               node var-node (sexpr var-node))))
    (let [option-use (if options [(identity-node options)] [])
          var-uses (map (fn [var-node]
                          (api/list-node
                           (list (api/token-node 'var) var-node)))
                        var-nodes)]
      {:node (with-meta
               (api/list-node
                (into [(api/token-node 'do)
                       (identity-node form-node)]
                      (concat option-use var-uses)))
               (meta node))})))

(defn defauthoring
  "Analyze a family generator as definitions of its three generated macros."
  [context]
  (let [[node [_form-node noun-node bindings-node & _]]
        (hook-children :defauthoring context 3 false)
        noun (symbol-name :defauthoring noun-node)
        bindings (when (api/vector-node? bindings-node)
                   (:children bindings-node))]
    (when-not (api/vector-node? bindings-node)
      (invalid-hook-context! :defauthoring
                             "builder bindings must be a vector"
                             node bindings-node (sexpr bindings-node)))
    (when-not (and (seq bindings)
                   (simple-symbol? (sexpr (first bindings))))
      (invalid-hook-context! :defauthoring
                             "builder bindings must start with a symbol"
                             node bindings-node (sexpr bindings-node)))
    (let [[inert-doc use-doc bang-doc] (registry-macro-docstrings noun)
          macros [[(symbol (str "def" noun)) inert-doc]
                  [(symbol (str "use-" noun "!")) use-doc]
                  [(symbol (str "def" noun "!")) bang-doc]]
          macro-def (fn [[name docstring]]
                      (api/list-node
                       (list (api/token-node 'defmacro)
                             (api/token-node name)
                             (api/string-node docstring)
                             (api/vector-node [(api/token-node '&)
                                               (api/token-node 'args)])
                             (api/token-node 'args))))]
      {:node (with-meta
               (api/list-node
                (into [(api/token-node 'do)] (map macro-def macros)))
               (meta node))})))
