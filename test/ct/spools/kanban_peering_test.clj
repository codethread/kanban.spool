(ns ct.spools.kanban-peering-test
  "Tests for the opt-in kanban board peering receive op (kanban.send.v1).

  Exercises the op through the real guild dispatch path — register, then invoke
  with a JSON string — so the JSON->keyword-key parsing assumptions the spec
  relies on are covered end to end."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.spools.guild :as guild]
            [skein.api.graph.alpha :as graph]
            [skein.api.weaver.alpha :as weaver]
            [skein.core.weaver.runtime :as weaver-runtime]
            [ct.spools.kanban :as kanban]
            [ct.spools.kanban.peering :as peering]
            [skein.test.alpha :as t]))

(defn- with-world
  "Run (f rt) inside a fresh bound weaver runtime after running (setup rt)."
  [setup f]
  (t/run-with-weaver-world
   {:storage :sqlite-memory}
   (fn [ctx]
     (weaver-runtime/with-runtime-binding (:runtime ctx)
       #(do (setup (:runtime ctx))
            (f (:runtime ctx)))))))

(defn- with-peering
  "Run (f rt) with guild, kanban, and kanban peering installed in order."
  [f]
  (with-world
    (fn [rt] (guild/install! rt) (kanban/install!) (kanban/install-peering!))
    f))

(defn- send!
  "Invoke kanban.send.v1 through the guild dispatch path with a JSON body."
  [rt input]
  (weaver/op! rt 'kanban.send.v1 [(json/write-str input)]))

(deftest install-peering-requires-guild-first
  ;; precondition (a): guild must already be registered
  (with-world
    (fn [_rt] (kanban/install!))
    (fn [_rt]
      (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                     #"requires the guild spool"
                                     (kanban/install-peering!)))]
        (is (= "guild" (:missing (ex-data ex))))
        (is (re-find #"skein\.spools\.guild/install!" (:remedy (ex-data ex))))))))

(deftest peering-owner-contribution-covers-both-local-ops
  ;; The receive operation remains Guild's dispatch-table declaration; these
  ;; are the two core-registry entries this module owns and replaces together.
  (is (= #{"kanban-peers" "kanban-send"}
         (set (keys (:ops (peering/contribute {})))))))

(deftest install-peering-requires-kanban-first
  ;; precondition (b): the kanban board op must already be installed
  (with-world
    guild/install!
    (fn [_rt]
      (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                     #"requires the kanban board"
                                     (kanban/install-peering!)))]
        (is (= "kanban" (:missing (ex-data ex))))
        (is (re-find #"ct\.spools\.kanban/install!" (:remedy (ex-data ex))))))))

(deftest install-peering-registers-op-and-returns-data
  (with-world
    (fn [rt] (guild/install! rt) (kanban/install!))
    (fn [rt]
      (let [result (kanban/install-peering!)]
        (is (true? (:installed result)))
        (is (= 'ct.spools.kanban.peering (:namespace result)))
        (is (some #(= "kanban.send.v1" (:name %)) (weaver/ops rt)))
        (testing "guild list advertises the receive op"
          (let [listed (weaver/op! rt 'guild ["list"])]
            (is (some #(= "kanban.send.v1" (:name %)) (:active listed)))))))))

(deftest install-peering-is-reload-safe
  (with-peering
    (fn [rt]
     ;; with-peering installed peering once already; guild/register-op! is upsert, so a
     ;; second install-peering! must not duplicate the op or break dispatch
      (let [again (kanban/install-peering!)]
        (is (true? (:installed again)))
        (is (= 'ct.spools.kanban.peering (:namespace again)))
        (is (= 1 (count (filter #(= "kanban.send.v1" (:name %)) (weaver/ops rt))))
            "re-running install-peering! upserts a single op, never duplicates"))
      (let [id (get-in (send! rt {:card {:title "After reload"}}) [:card :id])]
        (is (= "After reload" (:title (weaver/show rt id))))))))

(deftest send-single-card-uses-local-add-defaults
  (with-peering
    (fn [rt]
      (let [result (send! rt {:card {:title "Peered feature"}})
            id (get-in result [:card :id])
            stored (weaver/show rt id)]
        (is (= "kanban.send.v1" (:operation result)))
        (is (= #{:operation :card} (set (keys result))))
        (testing "a peered card shares the local add! defaults, lane, and type"
          (is (= "Peered feature" (:title stored)))
          (is (= "true" (get-in stored [:attributes :kanban/card])))
          (is (= "pending" (get-in stored [:attributes :kanban/lane])))
          (is (= "feature" (get-in stored [:attributes :kanban/type])))
          (is (= "p3" (get-in stored [:attributes :kanban/priority]))))
        (testing "no :from means no kanban/from stamp"
          (is (nil? (get-in stored [:attributes :kanban/from]))))))))

(deftest send-single-card-passes-optional-fields
  (with-peering
    (fn [rt]
      (let [id (get-in (send! rt {:card {:title "Rich card"
                                         :body "longer context"
                                         :source "docs/rfc.md"
                                         :priority "p1"
                                         :lane "refinement"}})
                       [:card :id])
            stored (weaver/show rt id)]
        (is (= "longer context" (get-in stored [:attributes :body])))
        (is (= "docs/rfc.md" (get-in stored [:attributes :kanban/source])))
        (is (= "p1" (get-in stored [:attributes :kanban/priority])))
        (is (= "refinement" (get-in stored [:attributes :kanban/lane])))))))

(deftest send-epic-bundle-parents-features-in-order
  (with-peering
    (fn [rt]
      (let [result (send! rt {:epic {:title "Theme epic"}
                              :features [{:title "First"} {:title "Second"} {:title "Third"}]})
            epic-id (get-in result [:epic :id])
            feature-ids (mapv :id (:features result))]
        (is (= "kanban.send.v1" (:operation result)))
        (is (= #{:operation :epic :features} (set (keys result))))
        (testing "the epic is an epic card and features keep their input order"
          (is (= "epic" (get-in (weaver/show rt epic-id) [:attributes :kanban/type])))
          (is (= ["First" "Second" "Third"]
                 (mapv #(:title (weaver/show rt %)) feature-ids))))
        (testing "each feature hangs under the epic via parent-of (same as add --epic)"
          (let [edges (:edges (graph/subgraph rt [epic-id] {:type "parent-of"}))]
            (is (= (set feature-ids)
                   (set (keep #(when (= epic-id (:from_strand_id %)) (:to_strand_id %)) edges))))))
        (testing "features carry the local feature type and pending lane"
          (doseq [fid feature-ids]
            (let [feat (weaver/show rt fid)]
              (is (= "feature" (get-in feat [:attributes :kanban/type])))
              (is (= "pending" (get-in feat [:attributes :kanban/lane]))))))))))

(deftest send-stamps-from-provenance-on-every-created-card
  (with-peering
    (fn [rt]
      (testing "a single card stamps kanban/from as <board>:<card>"
        (let [id (get-in (send! rt {:card {:title "From a peer"}
                                    :from {:board "backend" :card "abc12"}})
                         [:card :id])]
          (is (= "backend:abc12" (get-in (weaver/show rt id) [:attributes :kanban/from])))))
      (testing "an epic bundle stamps kanban/from on the epic and every feature"
        (let [result (send! rt {:epic {:title "Bundle epic"}
                                :features [{:title "F1"} {:title "F2"}]
                                :from {:board "frontend" :card "xy9"}})
              ids (cons (get-in result [:epic :id]) (map :id (:features result)))]
          (doseq [id ids]
            (is (= "frontend:xy9"
                   (get-in (weaver/show rt id) [:attributes :kanban/from])))))))))

(deftest send-rejects-malformed-input-through-guild-dispatch
  (with-peering
    (fn [rt]
      (testing "unknown top-level key"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:card {:title "X"} :surprise true}))))
      (testing "unknown card key"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:card {:title "X" :bogus "y"}}))))
      (testing "missing title"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:card {:body "no title"}}))))
      (testing "bad status"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:card {:title "X" :lane "someday"}}))))
      (testing "bad priority"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:card {:title "X" :priority "urgent"}}))))
      (testing "features without an epic"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:features [{:title "orphan"}]}))))
      (testing "an epic without features"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:epic {:title "lonely epic"}}))))
      (testing "an empty features vector"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:epic {:title "E"} :features []}))))
      (testing "both a single card and an epic bundle"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:card {:title "X"}
                                         :epic {:title "Y"}
                                         :features [{:title "Z"}]}))))
      (testing "malformed :from provenance"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed spec validation"
                              (send! rt {:card {:title "X"} :from {:board "b"}})))))))

;; ---------------------------------------------------------------------------
;; send side: kanban-peers and kanban-send
;; ---------------------------------------------------------------------------

(defn- card-strand
  "Resolve a stored kanban card strand by id."
  [rt id]
  (weaver/show rt id))

(defn- add-card!
  "Create a local kanban card through the board op and return its id."
  ([title] (add-card! title {}))
  ([title flags] (get-in (kanban/add! title flags) [:card :id])))

(defn- guild-list-with
  "A `guild list` result advertising the given active op names."
  [& op-names]
  {"guild" "peer" "active" (mapv (fn [n] {"name" n}) op-names)})

(deftest send-builds-a-feature-payload-mapping-the-board-tier
  (with-peering
    (fn [rt]
      (let [id (add-card! "Feature title" {"--body" "longer context"
                                           "--source" "docs/rfc.md"
                                           "--priority" "p1"
                                           "--lane" "refinement"})
            payload (#'peering/build-payload rt {:board "backend" :card id} (card-strand rt id))]
        (is (= {:card {:title "Feature title"
                       :lane "refinement"
                       :priority "p1"
                       :body "longer context"
                       :source "docs/rfc.md"}
                :from {:board "backend" :card id}}
               payload))))))

(deftest send-omits-absent-optional-card-fields
  (with-peering
    (fn [rt]
      ;; a bare pending card still carries add!'s defaults (pending, p3) but no
      ;; body/source, so only the present keys travel
      (let [id (add-card! "Bare card")
            payload (#'peering/build-payload rt {:board "b" :card id} (card-strand rt id))]
        (is (= {:card {:title "Bare card" :lane "pending" :priority "p3"}
                :from {:board "b" :card id}}
               payload))))))

(deftest send-builds-an-epic-bundle
  (with-peering
    (fn [rt]
      (let [epic (add-card! "Theme epic" {"--type" "epic"})]
        (add-card! "First" {"--epic" epic})
        (add-card! "Second" {"--epic" epic "--priority" "p2"})
        (let [payload (#'peering/build-payload rt {:board "backend" :card epic} (card-strand rt epic))]
          (is (= "Theme epic" (get-in payload [:epic :title])))
          (is (= {:board "backend" :card epic} (:from payload)))
          ;; created_at is second-granular, so two same-second children tie-break
          ;; by random slug id: the bundle's members are contractual, its order
          ;; is only deterministic, not creation-ordered.
          (testing "each feature child travels, mapping its tier"
            (is (= #{{:title "First" :lane "pending" :priority "p3"}
                     {:title "Second" :lane "pending" :priority "p2"}}
                   (set (:features payload))))))))))

(deftest send-refuses-in-flight-and-finished-cards
  (with-peering
    (fn [rt]
      (testing "a claimed card fails loudly with its lane"
        (let [id (add-card! "Claimed work")]
          (kanban/claim! id {"--owner" "a" "--branch" "b"})
          (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in-flight"
                                         (#'peering/build-payload rt {:board "b" :card id} (card-strand rt id))))]
            (is (= "claimed" (:lane (ex-data ex)))))))
      (testing "an in_review card fails loudly with its lane"
        (let [id (add-card! "Review work")]
          (kanban/claim! id {"--owner" "a" "--branch" "b"})
          (kanban/review! id)
          (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in-flight"
                                         (#'peering/build-payload rt {:board "b" :card id} (card-strand rt id))))]
            (is (= "in_review" (:lane (ex-data ex)))))))
      (testing "a closed card fails loudly as finished"
        (let [id (add-card! "Finished work")]
          (kanban/claim! id {"--owner" "a" "--branch" "b"})
          (kanban/finish! id {"--outcome" "done"})
          (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed"
                                         (#'peering/build-payload rt {:board "b" :card id} (card-strand rt id))))]
            (is (= "closed" (:state (ex-data ex))))))))))

(deftest send-refuses-an-epic-with-unexpected-card-children
  (with-peering
    (fn [rt]
      (let [epic (add-card! "Theme epic" {"--type" "epic"})
            feature (add-card! "Real slice" {"--epic" epic})
            nested (add-card! "Nested theme" {"--type" "epic"})
            drifted (add-card! "Drifted slice" {"--epic" epic})]
        (weaver/update! rt epic {:edges [{:type "parent-of" :to nested}]})
        (weaver/update! rt drifted {:attributes {:kanban/type "story"}})
        (testing "a nested epic and a drifted type are named, never dropped from the bundle"
          (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                         #"not feature cards"
                                         (#'peering/build-payload rt {:board "b" :card epic}
                                                                  (card-strand rt epic))))]
            (is (= epic (:epic (ex-data ex))))
            (is (= #{{:id nested :card "true" :type "epic"}
                     {:id drifted :card "true" :type "story"}}
                   (set (:unexpected (ex-data ex)))))
            (is (some? feature) "the valid sibling exists but the bundle still refuses")))))))

(deftest send-bundles-an-epic-past-its-non-card-children
  (with-peering
    (fn [rt]
      ;; tasks, notes, and engine execution strands hang under cards unmarked:
      ;; they are not board cards, so they are not bundle members either
      (let [epic (add-card! "Theme epic" {"--type" "epic"})
            work (weaver/add! rt {:title "Coordination strand" :attributes {:kind "task"}})]
        (add-card! "Real slice" {"--epic" epic})
        (weaver/update! rt epic {:edges [{:type "parent-of" :to (:id work)}]})
        (kanban/note! epic "Handover" {"--by" "agent"})
        (let [payload (#'peering/build-payload rt {:board "b" :card epic} (card-strand rt epic))]
          (is (= [{:title "Real slice" :lane "pending" :priority "p3"}]
                 (:features payload))))))))

(deftest send-refuses-an-epic-with-in-flight-children
  (with-peering
    (fn [rt]
      (let [epic (add-card! "Blocked epic" {"--type" "epic"})
            open (add-card! "Open feature" {"--epic" epic})
            claimed (add-card! "Claimed feature" {"--epic" epic})]
        (kanban/claim! claimed {"--owner" "a" "--branch" "b"})
        (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in-flight feature children"
                                       (#'peering/build-payload rt {:board "b" :card epic} (card-strand rt epic))))]
          (is (= [{:id claimed :title "Claimed feature" :lane "claimed"}]
                 (:blocking (ex-data ex)))
              "only the in-flight child is named as blocking")
          (is (= epic (:epic (ex-data ex))))
          (is (some? open) "the pending sibling exists but the send still refuses"))))))

(deftest send-refuses-an-epic-with-no-sendable-children
  (with-peering
    (fn [rt]
      ;; an epic whose only child is finished has nothing left to peer
      (let [epic (add-card! "Spent epic" {"--type" "epic"})
            done (add-card! "Done feature" {"--epic" epic})]
        (kanban/claim! done {"--owner" "a" "--branch" "b"})
        (kanban/finish! done {"--outcome" "done"})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no pending or refinement feature"
                              (#'peering/build-payload rt {:board "b" :card epic} (card-strand rt epic))))))))

(deftest send-requires-a-named-runtime
  ;; provenance travels with every card, so a nameless board cannot send
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"publishes no name"
                        (#'peering/local-board-name {:name nil})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"publishes no name"
                        (#'peering/local-board-name {})))
  (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"publishes no name"
                                 (#'peering/local-board-name {:name "  "})))]
    (is (re-find #"config\.json" (:remedy (ex-data ex)))))
  (is (= "backend" (#'peering/local-board-name {:name "backend"}))))

(deftest send-preflights-the-target-for-the-receive-op
  (with-peering
    (fn [rt]
      (let [id (add-card! "To send")]
        (testing "a peer whose guild lacks kanban.send.v1 fails loudly"
          (binding [peering/*list-peer-guild* (fn [_] (guild-list-with "gate.status.v1"))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not advertise kanban.send.v1"
                                  (weaver/op! rt 'kanban-send ["frontend" id])))))
        (testing "a peer with no guild API is reframed as running no peering"
          (binding [peering/*list-peer-guild*
                    (fn [_] (throw (ex-info "unknown op" {:code :peer/domain-error})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runs no guild API"
                                  (weaver/op! rt 'kanban-send ["frontend" id])))))
        (testing "a transport failure during preflight propagates loudly"
          (binding [peering/*list-peer-guild*
                    (fn [_] (throw (ex-info "socket down" {:code :peer/transport-failed})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"socket down"
                                  (weaver/op! rt 'kanban-send ["frontend" id])))))))))

(deftest send-invokes-the-peer-and-notes-the-local-card
  (with-peering
    (fn [rt]
      (let [id (add-card! "Ship it" {"--body" "context"})
            sent-args (atom nil)]
        (binding [peering/*list-peer-guild* (fn [_] (guild-list-with "kanban.send.v1"))
                  peering/*send-card* (fn [peer json-arg]
                                        (reset! sent-args {:peer peer :json json-arg})
                                        {"operation" "kanban.send.v1" "card" {"id" "remote-1"}})]
          (let [result (weaver/op! rt 'kanban-send ["frontend" id])]
            (testing "the result reports the peer and the created remote ids"
              (is (= "kanban-send" (:operation result)))
              (is (= "frontend" (:peer result)))
              (is (= {:card {:id "remote-1"}} (:sent result))))
            (testing "the payload rides one JSON argv string carrying the board tier"
              (is (= "frontend" (:peer @sent-args)))
              (let [payload (json/read-str (:json @sent-args) :key-fn keyword)]
                (is (= "Ship it" (get-in payload [:card :title])))
                (is (= "context" (get-in payload [:card :body])))
                (is (= id (get-in payload [:from :card])))
                (is (not (str/blank? (get-in payload [:from :board]))))))
            (testing "a note recording the send lands on the local card"
              (let [card (weaver/op! rt 'kanban ["card" id])]
                (is (some #(re-find #"Sent to peer frontend as card remote-1" (:note %))
                          (:notes card)))))
            (testing "the local card's lane is untouched — closing stays the caller's choice"
              (is (= "pending" (get-in (weaver/show rt id) [:attributes :kanban/lane]))))))))))

(deftest send-invokes-the-peer-with-an-epic-bundle
  (with-peering
    (fn [rt]
      (let [epic (add-card! "Bundle epic" {"--type" "epic"})]
        (add-card! "F1" {"--epic" epic})
        (add-card! "F2" {"--epic" epic})
        (binding [peering/*list-peer-guild* (fn [_] (guild-list-with "kanban.send.v1"))
                  peering/*send-card* (fn [_ _]
                                        {"operation" "kanban.send.v1"
                                         "epic" {"id" "remote-epic"}
                                         "features" [{"id" "remote-f1"} {"id" "remote-f2"}]})]
          (let [result (weaver/op! rt 'kanban-send ["frontend" epic])]
            (is (= {:epic {:id "remote-epic"}
                    :features [{:id "remote-f1"} {:id "remote-f2"}]}
                   (:sent result)))
            (let [card (weaver/op! rt 'kanban ["card" epic])]
              (is (some #(re-find #"as epic remote-epic with features remote-f1, remote-f2" (:note %))
                        (:notes card))))))))))

(deftest peers-classifies-siblings-through-the-injectable-probe
  (with-peering
    (fn [rt]
      (let [self-id (:nonce (:metadata rt))
            rows [{:name "advertiser" :workspace "/ws/adv" :weaver-id "w-adv" :running? true}
                  {:name "plain" :workspace "/ws/plain" :weaver-id "w-plain" :running? true}
                  {:name "asleep" :workspace "/ws/asleep" :weaver-id "w-stale" :running? false}
                  {:name "me" :workspace "/ws/me" :weaver-id self-id :running? true}]]
        (binding [peering/*list-peers* (fn [] rows)
                  peering/*list-peer-guild*
                  (fn [row]
                    (case (:name row)
                      "advertiser" (guild-list-with "kanban.send.v1" "gate.status.v1")
                      "plain" (throw (ex-info "unknown op" {:code :peer/domain-error}))
                      (throw (ex-info "unexpected probe" {:row row}))))]
          (let [result (peering/peers-op {})
                by-name (into {} (map (juxt :name identity)) (:peers result))]
            (is (= "kanban-peers" (:operation result)))
            (testing "an advertising running peer is a send target"
              (is (true? (:kanban-send? (by-name "advertiser")))))
            (testing "a running peer that rejects guild list is a non-peering sibling"
              (is (false? (:kanban-send? (by-name "plain")))))
            (testing "a stale peer is listed but never probed"
              (is (false? (:running? (by-name "asleep"))))
              (is (not (contains? (by-name "asleep") :kanban-send?))))
            (testing "the local weaver is marked self and answered from the local registry"
              (is (true? (:self? (by-name "me"))))
              (is (true? (:kanban-send? (by-name "me")))))))))))

(deftest peers-propagates-a-non-domain-probe-failure
  (with-peering
    (fn [_rt]
      (binding [peering/*list-peers* (fn [] [{:name "broken" :workspace "/ws/b"
                                              :weaver-id "w-b" :running? true}])
                peering/*list-peer-guild*
                (fn [_] (throw (ex-info "socket down" {:code :peer/transport-failed})))]
        ;; TEN-003: only an unknown-op domain error classifies as non-peering; a
        ;; transport failure must never be swallowed into :kanban-send? false
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"socket down"
                              (peering/peers-op {})))))))

(deftest install-peering-registers-both-send-side-ops
  (with-world
    (fn [rt] (guild/install! rt) (kanban/install!))
    (fn [rt]
      (kanban/install-peering!)
      (let [ops (into {} (map (juxt :name identity)) (weaver/ops rt))]
        (testing "kanban-peers is a read op with its arg-spec and returns"
          (let [entry (get ops "kanban-peers")]
            (is (some? entry))
            (is (= :read (:hook-class entry)))
            (is (= "kanban-peers" (get-in entry [:arg-spec :op])))
            (is (some? (:returns entry)))))
        (testing "kanban-send is a mutating op with positionals and returns"
          (let [entry (get ops "kanban-send")]
            (is (some? entry))
            (is (= :mutating (:hook-class entry)))
            (is (= [:peer :card-id] (mapv :name (get-in entry [:arg-spec :positionals]))))
            (is (some? (:returns entry)))))))))

(deftest install-peering-upserts-both-send-side-ops
  (with-peering
    (fn [rt]
      ;; with-peering installed once; a second install-peering! (config reload)
      ;; must upsert rather than collide on the already-registered names
      (is (map? (kanban/install-peering!)))
      (doseq [op-name ["kanban-peers" "kanban-send"]]
        (is (= 1 (count (filter #(= op-name (:name %)) (weaver/ops rt))))
            (str "re-running install-peering! keeps a single " op-name))))))

;; ---------------------------------------------------------------------------
;; send side: protocol- and result-shape validation (fail loud, no silent drops)
;; ---------------------------------------------------------------------------

(deftest peers-fails-loud-on-a-malformed-guild-list-envelope
  ;; TEN-003: a malformed guild list reply is protocol corruption, never a
  ;; peer that is silently classified as non-advertising
  (with-peering
    (fn [_rt]
      (let [row [{:name "broken" :workspace "/ws/b" :weaver-id "w-b" :running? true}]]
        (testing "a reply with no active list is rejected"
          (binding [peering/*list-peers* (fn [] row)
                    peering/*list-peer-guild* (fn [_] {"guild" "peer"})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed envelope"
                                  (peering/peers-op {})))))
        (testing "an active entry without a string name is rejected"
          (binding [peering/*list-peers* (fn [] row)
                    peering/*list-peer-guild* (fn [_] {"active" [{"nope" 1}]})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed envelope"
                                  (peering/peers-op {})))))))))

(deftest send-preflight-fails-loud-on-a-malformed-guild-list-envelope
  (with-peering
    (fn [rt]
      (let [id (add-card! "To send")]
        (binding [peering/*list-peer-guild* (fn [_] {"active" [{"nope" 1}]})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed envelope"
                                (weaver/op! rt 'kanban-send ["frontend" id]))))))))

(deftest send-fails-loud-on-a-malformed-peer-result
  (with-peering
    (fn [rt]
      (let [id (add-card! "Ship it")
            listing (fn [_] (guild-list-with "kanban.send.v1"))]
        (testing "a missing card id is not silently reported as success"
          (binding [peering/*list-peer-guild* listing
                    peering/*send-card* (fn [_ _] {"operation" "kanban.send.v1"})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed card result"
                                  (weaver/op! rt 'kanban-send ["frontend" id])))))
        (testing "a blank id fails before any misleading local note is written"
          (binding [peering/*list-peer-guild* listing
                    peering/*send-card* (fn [_ _] {"card" {"id" "  "}})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed card result"
                                  (weaver/op! rt 'kanban-send ["frontend" id])))
            (let [card (weaver/op! rt 'kanban ["card" id])]
              (is (not-any? #(re-find #"Sent to peer" (:note %)) (:notes card))
                  "no note claims success when the remote reply is unverified"))))))))

(deftest send-fails-loud-on-an-epic-feature-count-mismatch
  (with-peering
    (fn [rt]
      (let [epic (add-card! "Bundle epic" {"--type" "epic"})]
        (add-card! "F1" {"--epic" epic})
        (add-card! "F2" {"--epic" epic})
        (binding [peering/*list-peer-guild* (fn [_] (guild-list-with "kanban.send.v1"))
                  peering/*send-card* (fn [_ _] {"epic" {"id" "remote-epic"}
                                                 "features" [{"id" "only-one"}]})]
          (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different number of features"
                                         (weaver/op! rt 'kanban-send ["frontend" epic])))]
            (is (= 2 (:sent (ex-data ex))))
            (is (= 1 (:created (ex-data ex))))))))))

(deftest send-refuses-an-epic-with-an-unknown-child-lane
  ;; a corrupt or nil child lane must fail loudly, not be filtered into a partial
  ;; bundle alongside a sendable sibling
  (with-peering
    (fn [rt]
      (let [epic (add-card! "Corrupt epic" {"--type" "epic"})
            good (add-card! "Good feature" {"--epic" epic})
            bad (add-card! "Corrupt feature" {"--epic" epic})]
        (weaver/update! rt bad {:attributes {:kanban/lane "bogus"}})
        (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown or missing board lane"
                                       (#'peering/build-payload rt {:board "b" :card epic} (card-strand rt epic))))]
          (is (= [{:id bad :lane "bogus"}] (:invalid (ex-data ex)))
              "only the corrupt child is named")
          (is (some? good) "the sendable sibling exists but the send still refuses"))))))

(deftest public-seam-specs-constrain-op-return-shapes
  (testing "well-formed kanban-peers results and rows conform"
    (is (s/valid? ::peering/peers-result
                  {:operation "kanban-peers"
                   :peers [{:name "frontend" :workspace "/ws/f" :weaver-id "w-f"
                            :running? true :kanban-send? true}
                           {:name nil :workspace "/ws/s" :weaver-id "w-s" :running? false}]}))
    (is (s/valid? ::peering/peer-row
                  {:name "x" :workspace "/w" :weaver-id "w" :running? true
                   :self? true :kanban-send? false})))
  (testing "malformed peer rows and results are rejected"
    (is (not (s/valid? ::peering/peer-row {:name "x" :workspace "/w"}))
        "missing required keys")
    (is (not (s/valid? ::peering/peer-row {:name "x" :workspace "/w" :weaver-id "w" :running? "yes"}))
        "running? must be boolean")
    (is (not (s/valid? ::peering/peers-result {:operation "nope" :peers []}))
        "operation label is fixed"))
  (testing "kanban-send results conform for card and epic sends"
    (is (s/valid? ::peering/send-result
                  {:operation "kanban-send" :peer "frontend" :sent {:card {:id "9xk2p"}}}))
    (is (s/valid? ::peering/send-result
                  {:operation "kanban-send" :peer "frontend"
                   :sent {:epic {:id "e"} :features [{:id "f1"} {:id "f2"}]}})))
  (testing "blank ids, empty feature bundles, and wrong labels are rejected"
    (is (not (s/valid? ::peering/send-result
                       {:operation "kanban-send" :peer "frontend" :sent {:card {:id "  "}}})))
    (is (not (s/valid? ::peering/send-result
                       {:operation "kanban-send" :peer "frontend"
                        :sent {:epic {:id "e"} :features []}})))
    (is (not (s/valid? ::peering/send-result
                       {:operation "wrong" :peer "frontend" :sent {:card {:id "x"}}})))))
