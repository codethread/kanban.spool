(ns ct.spools.kanban-test
  "Tests for the kanban board spool against a disposable weaver runtime."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [skein.api.graph.alpha :as graph]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.format.alpha :as fmt]
            [skein.api.spool.alpha :as spool]
            [skein.core.weaver.runtime :as weaver-runtime]
            [ct.spools.kanban :as kanban]
            [ct.spools.kanban-peering-test]
            [skein.test.alpha :as t]))

(deftest exact-entity-projections-discard-extra-fields-and-fail-loudly
  (let [strand {:id "s1" :title "Work" :state "active"
                :attributes {:kind "task"} :created_at "discarded"}]
    (is (= (dissoc strand :created_at) (spool/entity-projection strand)))
    (doseq [field [:id :title :state :attributes]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"missing canonical entity fields"
                            (spool/entity-projection (dissoc strand field)))))))

(defn stub-projection
  "Stand-in tracker strategy for the card-view join tests.

  Returns a canned projection for `widgets-run`, an empty projection for any
  other run id (the tracker's honest \"no active run\" report), and carries an
  extra step key so the tests can prove kanban trims steps to its own key set."
  [run-id]
  (if (= "widgets-run" run-id)
    {:status "spec"
     :ready [{:id "s1" :title "Draft spec" :role "step" :stage "spec"
              :checkpoint false :extra "trimmed by kanban"}]}
    {:status nil :ready []}))

(defn- with-kanban
  "Run f with a fresh weaver runtime that has the kanban spool installed.

  The runtime lifecycle and isolation come from the public author test helper
  (`skein.test.alpha/with-weaver-world`), with the runtime thread-bound so the
  spool's `current/runtime` resolution sees this world. kanban ships on this
  repo's src classpath, so install! runs directly against the bound runtime."
  [f]
  (t/run-with-weaver-world
   {:storage :sqlite-memory}
   (fn [ctx]
     (weaver-runtime/with-runtime-binding (:runtime ctx)
       #(do (kanban/install!)
            (f (:runtime ctx)))))))

(defn- op! [rt & argv]
  (weaver/op! rt 'kanban argv))

(defn- export! [rt & argv]
  (weaver/op! rt 'kanban-export argv))

(defn- return-case-leaves [operation context return-case]
  (if (and (map? return-case) (contains? return-case :stream))
    (set (map (fn [channel] [operation (assoc context :channel channel)]) [:emits :result]))
    #{[operation context]}))

(defn- op-return-leaves [{:keys [name returns]}]
  (if (and (map? returns) (contains? returns :subcommands))
    (into #{} (mapcat (fn [[subcommand return-case]]
                        (return-case-leaves name {:subcommand subcommand} return-case)))
          (:subcommands returns))
    (return-case-leaves name {} returns)))

(deftest production-return-coverage-is-derived-from-kanban-provenance
  (with-kanban
    (fn [rt]
      (let [entries (filterv #(= 'ct.spools.kanban (:provenance %)) (weaver/ops rt))
            missing (filterv #(not (contains? % :returns)) entries)
            required (into #{} (mapcat op-return-leaves) (remove #(not (contains? % :returns)) entries))
            checked (atom #{})]
        (is (seq entries))
        (is (empty? missing) (str "production ops missing :returns: " (mapv :name missing)))
        (doseq [[operation context :as leaf] required]
          (t/check-op-return!
           rt (symbol operation) context
           (if (= "kanban-export" operation)
             {:operation operation :root-id "card" :strands [] :parent-of-edges [] :depends-on-edges []}
             {:operation operation}))
          (swap! checked conj leaf))
        (is (= required @checked))
        (is (empty? (set/difference required @checked)))))))

(deftest install-declares-kanban-attr-namespace
  (with-kanban
    (fn [rt]
      (let [decl (vocab/declaration rt :attr-namespace "kanban")]
        (is (some? decl) "install! declares the kanban/* attribute namespace")
        (is (= :skein/spools-kanban (:owner decl))
            "kanban/* is owned by the single verified use-key :skein/spools-kanban")
        (is (every? #(str/starts-with? % "kanban/") (:keys decl))
            "advisory :keys all live under the kanban/ prefix")
        (is (contains? (set (:keys decl)) "kanban/task")
            "the task-tier marker attr is declared in the vocab registry")))))

(deftest kanban-about-commands-match-declared-subcommands
  (with-kanban
    (fn [rt]
      (let [detail (weaver/resolve-op rt 'kanban)
            manual-entries (-> (kanban/about) :commands)
            manual-commands (set (keep :verb manual-entries))
            declared-commands (set (keys (get-in detail [:arg-spec :subcommands])))]
        (is (every? #(or (:verb %) (:repl %)) manual-entries)
            "every kanban about command entry must carry :verb or documented :repl")
        (is (empty? (set/difference manual-commands declared-commands))
            (str "kanban about commands missing from arg-spec: " (sort (set/difference manual-commands declared-commands))))
        (is (empty? (set/difference declared-commands manual-commands))
            (str "kanban arg-spec subcommands missing from about: " (sort (set/difference declared-commands manual-commands))))))))

(deftest kanban-add-next-claim-and-finish-round-trip
  (with-kanban
    (fn [rt]
      (is (some #(= "kanban" (:name %)) (weaver/ops rt)))
      (testing "add creates a pending feature card"
        (let [added (op! rt "add" "Build active work convention" "--source" "devflow/rfcs/2026-07-02-feature-tracking-registry.md")
              id (get-in added [:card :id])
              stored (weaver/show rt id)]
          (is (= "Build active work convention" (:title stored)))
          (is (= "true" (get-in stored [:attributes :kanban/card])))
          (is (= "pending" (get-in stored [:attributes :kanban/lane])))
          (is (= "feature" (get-in stored [:attributes :kanban/type])))
          (is (= "devflow/rfcs/2026-07-02-feature-tracking-registry.md"
                 (get-in stored [:attributes :kanban/source])))
          (testing "next serves the oldest pending feature"
            (is (= id (get-in (op! rt "next") [:next :id]))))
          (testing "claim requires owner and branch"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --owner"
                                  (op! rt "claim" id "--branch" "feature-branch")))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --branch"
                                  (op! rt "claim" id "--owner" "agent"))))
          (testing "claim stamps status and work-root attributes"
            (let [claimed (op! rt "claim" id "--owner" "agent" "--branch" "kanban-spool"
                               "--worktree" "/tmp/wt")]
              (is (= "claimed" (get-in claimed [:card :attributes :kanban/lane])))
              (is (= "agent" (get-in claimed [:card :attributes :owner])))
              (is (= "kanban-spool" (get-in claimed [:card :attributes :branch])))
              ;; regression: the claimed status must survive the round trip to
              ;; storage (string/keyword attr-key collisions once dropped it)
              (is (= "claimed" (get-in (weaver/show rt id) [:attributes :kanban/lane])))
              (is (nil? (:next (op! rt "next"))))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be pending"
                                    (op! rt "claim" id "--owner" "other" "--branch" "b")))))
          (testing "review, rework, and finish enforce the review lane"
            (let [reviewing (op! rt "review" id)]
              (is (= "in_review" (get-in reviewing [:card :attributes :kanban/lane])))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be claimed"
                                    (op! rt "review" id)))
              (is (= "claimed" (get-in (op! rt "rework" id) [:card :attributes :kanban/lane])))
              (is (= "in_review" (get-in (op! rt "review" id) [:card :attributes :kanban/lane]))))
            (let [finished (op! rt "finish" id)]
              (is (= "closed" (get-in finished [:card :state])))
              (is (nil? (get-in finished [:card :attributes :kanban/lane])))
              (is (= "done" (get-in finished [:card :attributes :kanban/outcome]))))))))))

(deftest kanban-declared-subcommands-help-and-parser-errors
  (with-kanban
    (fn [rt]
      (testing "help projections list the declared verb surface"
        (let [detail (weaver/op! rt 'help ["kanban"])
              alias (op! rt "help")
              verbs (mapv :name (get-in detail [:arg-spec :subcommands]))]
          (is (= detail alias))
          (is (= ["about" "add" "board" "card" "claim" "finish" "next" "note" "prime" "priority" "promote" "review" "rework" "task"] verbs))
          (is (some #(= "about" (:name %)) (get-in alias [:arg-spec :subcommands])))))
      (testing "missing and unknown verbs fail during parser routing with available names"
        (let [missing (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing subcommand"
                                            (op! rt)))
              unknown (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown subcommand"
                                            (op! rt "bogus")))]
          (is (= :missing-subcommand (:reason (ex-data missing))))
          (is (= :unknown-subcommand (:reason (ex-data unknown))))
          (is (= ["about" "add" "board" "card" "claim" "finish" "next" "note" "prime" "priority" "promote" "review" "rework" "task"]
                 (:available-subcommands (ex-data missing))))
          (is (= (:available-subcommands (ex-data missing))
                 (:available-subcommands (ex-data unknown)))))))))

(deftest kanban-prime-supersets-about-with-working-discipline
  (with-kanban
    (fn [rt]
      (let [prime (op! rt "prime")
            about (op! rt "about")]
        (is (not (contains? (kanban/prime) :operation)))
        (testing "prime reuses about's command/lane/attribute surface"
          (is (= (:commands about) (:commands prime)))
          (is (= (:lanes about) (:lanes prime)))
          (is (= (:attributes about) (:attributes prime))))
        (testing "prime carries the working discipline about does not"
          (is (seq (:working-agreement prime)))
          (is (seq (:pick-up-next-card prime)))
          (is (seq (:note-discipline prime)))
          (is (seq (:staying-aware prime)))
          (is (string? (:branch-visibility prime)))
          (is (nil? (:working-agreement about))))
        (testing "prime's fill-authored blocks wrap into single-line prose items"
          (is (= 4 (count (:working-agreement prime))))
          (is (= 3 (count (:staying-aware prime))))
          (is (every? #(nil? (re-find #"\n" %)) (:staying-aware prime))))
        (testing "prime advertises itself in the command surface without duplicating usage"
          (is (some #(= "prime" (:verb %)) (:commands prime)))
          (is (= "strand help kanban" (get-in prime [:discovery :help]))))))))

(deftest fill-wraps-prose-and-preserves-indented-blocks
  (testing "flush-left lines soft-wrap; a bare bar starts a new item; an indented line keeps the item verbatim"
    (is (= ["Prose that is long enough to wrap across two source lines."
            "Before running:\n    strand kanban prime\n    strand kanban board"]
           (fmt/fill "
                     |Prose that is long enough to
                     |wrap across two source lines.
                     |
                     |Before running:
                     |    strand kanban prime
                     |    strand kanban board"))))
  (testing "reflow soft-wraps a single-paragraph block into one string"
    (is (= "One sentence spread over two source lines."
           (fmt/reflow "
                       |One sentence spread over
                       |two source lines."))))
  (testing "a bar-less block is an authoring error, not empty output"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no barred lines"
                          (fmt/fill "prose that lost its bars")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no barred lines"
                          (fmt/reflow "prose that lost its bars")))))

(deftest kanban-refinement-lane-and-promote
  (with-kanban
    (fn [rt]
      (let [idea (op! rt "add" "Vague idea" "--lane" "refinement")
            idea-id (get-in idea [:card :id])]
        (is (= "refinement" (get-in idea [:card :attributes :kanban/lane])))
        (testing "refinement cards are not actionable"
          (is (nil? (:next (op! rt "next"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be pending"
                                (op! rt "claim" idea-id "--owner" "a" "--branch" "b"))))
        (testing "promote moves the card into the pending lane"
          (is (= "pending" (get-in (op! rt "promote" idea-id)
                                   [:card :attributes :kanban/lane])))
          (is (= idea-id (get-in (op! rt "next") [:next :id])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be refinement"
                                (op! rt "promote" idea-id))))
        (testing "add rejects unknown statuses and types"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pending or refinement"
                                (op! rt "add" "Bad lane" "--lane" "someday")))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"feature or epic"
                                (op! rt "add" "Bad type" "--type" "story"))))))))

(deftest kanban-priority-orders-lanes-and-next
  (with-kanban
    (fn [rt]
      (let [old-default (get-in (op! rt "add" "Default work") [:card :id])
            someday (get-in (op! rt "add" "Someday idea" "--priority" "p4") [:card :id])
            blocker (get-in (op! rt "add" "Breaking change blocker" "--priority" "p1") [:card :id])]
        (testing "add stamps p3 unless told otherwise and validates the flag"
          (is (= "p3" (get-in (weaver/show rt old-default) [:attributes :kanban/priority])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"p1, p2, p3, p4"
                                (op! rt "add" "Bad priority" "--priority" "urgent"))))
        (testing "next serves the highest priority first despite creation order"
          (is (= blocker (get-in (op! rt "next") [:next :id]))))
        (testing "board lanes sort p1 first and expose :priority on compact cards"
          (let [pending (:pending (op! rt "board"))]
            (is (= [blocker old-default someday] (mapv :id pending)))
            (is (= ["p1" "p3" "p4"] (mapv :priority pending)))))
        (testing "cards that predate priorities read as p3"
          (let [legacy (weaver/add rt {:title "Legacy card"
                                       :attributes {:kanban/card "true"
                                                    :kanban/lane "pending"
                                                    :kanban/type "feature"}})
                on-board (some #(when (= (:id legacy) (:id %)) %)
                               (:pending (op! rt "board")))]
            (is (= "p3" (:priority on-board)))))
        (testing "priority reprioritises an active card and fails loudly otherwise"
          (is (= "p2" (get-in (op! rt "priority" someday "p2")
                              [:card :attributes :kanban/priority])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"p1, p2, p3, p4"
                                (op! rt "priority" someday "p9")))
          (op! rt "claim" blocker "--owner" "agent" "--branch" "priority-x")
          (op! rt "finish" blocker)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be active"
                                (op! rt "priority" blocker "p1"))))
        (testing "about documents the priority ladder"
          (is (= #{:p1 :p2 :p3 :p4} (set (keys (:priorities (op! rt "about")))))))))))

(deftest kanban-epics-group-features
  (with-kanban
    (fn [rt]
      (let [epic-id (get-in (op! rt "add" "Big theme" "--type" "epic") [:card :id])
            feat-id (get-in (op! rt "add" "First slice" "--epic" epic-id) [:card :id])]
        (testing "epic features are linked with parent-of and shown on the board"
          (let [edges (:edges (graph/subgraph rt [epic-id] {:type "parent-of"}))]
            (is (some #(and (= epic-id (:from_strand_id %))
                            (= feat-id (:to_strand_id %))) edges)))
          (let [board (op! rt "board")]
            (is (= [epic-id] (mapv :id (:epics board))))
            (is (= epic-id (:epic (first (:pending board)))))))
        (testing "epics are never served or claimed as work"
          (is (= feat-id (get-in (op! rt "next") [:next :id])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot be claimed"
                                (op! rt "claim" epic-id "--owner" "a" "--branch" "b"))))
        (testing "epics cannot nest and epic targets must be epics"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot nest"
                                (op! rt "add" "Nested" "--type" "epic" "--epic" epic-id)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an epic"
                                (op! rt "add" "Bad parent" "--epic" feat-id))))))))

(deftest kanban-notes-and-card-view
  (with-kanban
    (fn [rt]
      (let [card-id (get-in (op! rt "add" "Crashable feature") [:card :id])]
        (op! rt "claim" card-id "--owner" "agent-a" "--branch" "crashable")
        (let [task (weaver/add rt {:title "Implement it" :attributes {:kind "task"}})
              review (weaver/add rt {:title "Review it" :attributes {:kind "review"}})]
          (weaver/update rt card-id {:edges [{:type "parent-of" :to (:id task)}
                                             {:type "parent-of" :to (:id review)}]})
          (weaver/update rt (:id review) {:edges [{:type "depends-on" :to (:id task)}]})
          (op! rt "note" card-id "Decided to keep lane names" "--by" "agent-a")
          (op! rt "note" card-id
               "Done: impl. Next: review. Validation: tests green."
               "--by" "agent-a")
          (testing "card view joins notes newest-first, work, and frontier"
            (let [view (op! rt "card" card-id)]
              (is (= card-id (get-in view [:card :id])))
              (is (= 2 (count (:notes view))))
              (is (= "Done: impl. Next: review. Validation: tests green."
                     (:note (first (:notes view)))))
              (is (= #{(:id task) (:id review)}
                     (set (map :id (:active-work view)))))
              ;; review depends on the task, so only the task is ready
              (is (= [(:id task)] (mapv :id (:ready view))))))
          (testing "notes reject targets outside the card/task tier and missing text"
            ;; the child here carries kind=task but not the kanban/task marker,
            ;; so it is generic work, not a note target
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a kanban card or task"
                                  (op! rt "note" (:id task) "text")))
            (let [missing-text (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Missing required argument text"
                                                     (op! rt "note" card-id)))]
              (is (= :missing-required (:reason (ex-data missing-text)))))
            (let [removed-author (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown flag --author"
                                                       (op! rt "note" card-id "text" "--author" "agent-a")))]
              (is (= :unknown-flag (:reason (ex-data removed-author)))))))))))

(deftest kanban-note-targets-tasks-and-stamps-kind
  (with-kanban
    (fn [rt]
      (let [feature-id (get-in (op! rt "add" "Task-noted feature") [:card :id])
            task-id (get-in (op! rt "task" "add" feature-id "Wire the thing") [:task :id])]
        (testing "a task note reports the task and its owning card"
          (let [noted (op! rt "note" task-id "Done: wiring. Next: tests."
                           "--by" "agent-a" "--kind" "activity")]
            (is (= task-id (:task noted)))
            (is (= feature-id (:card noted)))
            (is (= "agent-a" (get-in noted [:strand :attributes :note/by])))
            (is (= "activity" (get-in noted [:strand :attributes :note/kind])))
            (is (nil? (get-in noted [:strand :attributes :kanban/note])))))
        (testing "the newest task note surfaces as :latest-note in every task projection"
          (op! rt "note" task-id "Chose sqlite over flat files" "--kind" "decision")
          (let [listed (first (:tasks (op! rt "task" "list" feature-id)))
                viewed (first (:tasks (op! rt "card" feature-id)))]
            (is (= "Chose sqlite over flat files" (get-in listed [:latest-note :note])))
            (is (= "decision" (get-in listed [:latest-note :kind])))
            (is (= (dissoc (:latest-note listed) :at)
                   (dissoc (:latest-note viewed) :at)))))
        (testing "task notes stay out of the card's own note trail"
          (op! rt "note" feature-id "Handover: task tier carries the detail")
          (let [notes (mapv :note (:notes (op! rt "card" feature-id)))]
            (is (= ["Handover: task tier carries the detail"] notes))))
        (testing "a card note keeps the card-only response shape"
          (let [noted (op! rt "note" feature-id "Lean card note")]
            (is (= feature-id (:card noted)))
            (is (not (contains? noted :task)))))
        (testing "a blank --kind fails loudly"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"kind must be a non-blank string"
                                (op! rt "note" task-id "text" "--kind" ""))))
        (testing "a task with no notes omits :latest-note"
          (let [bare-id (get-in (op! rt "task" "add" feature-id "Untouched task") [:task :id])
                bare (some #(when (= bare-id (:id %)) %)
                           (:tasks (op! rt "task" "list" feature-id)))]
            (is (not (contains? bare :latest-note)))))))))

(deftest kanban-views-clip-long-note-bodies
  (with-kanban
    (fn [rt]
      (let [feature-id (get-in (op! rt "add" "Dump-resistant feature") [:card :id])
            task-id (get-in (op! rt "task" "add" feature-id "Reviewed task") [:task :id])
            dump (str/join (repeat 700 "x"))]
        (op! rt "note" feature-id "Short and intact")
        (op! rt "note" feature-id dump "--kind" "review-dump")
        (op! rt "note" task-id dump)
        (testing "card notes past the cap are clipped and marked truncated"
          (let [[long-note short-note] (:notes (op! rt "card" feature-id))]
            (is (true? (:truncated long-note)))
            (is (< (count (:note long-note)) (count dump)))
            (is (str/ends-with? (:note long-note) "…"))
            (is (= "review-dump" (:kind long-note)))
            (is (= "Short and intact" (:note short-note)))
            (is (not (contains? short-note :truncated)))))
        (testing "the full text stays on the note strand"
          (let [note-id (:id (first (:notes (op! rt "card" feature-id))))]
            (is (= dump (get-in (weaver/show rt note-id) [:attributes :note/text])))))
        (testing "task latest-note bodies clip the same way"
          (let [latest (:latest-note (first (:tasks (op! rt "card" feature-id))))]
            (is (true? (:truncated latest)))
            (is (str/ends-with? (:note latest) "…"))))))))

(deftest kanban-board-groups-lanes
  (with-kanban
    (fn [rt]
      (let [idea-id (get-in (op! rt "add" "Idea" "--lane" "refinement") [:card :id])
            queued-id (get-in (op! rt "add" "Queued") [:card :id])
            working-id (get-in (op! rt "add" "Working") [:card :id])
            review-id (get-in (op! rt "add" "Reviewing") [:card :id])
            done-id (get-in (op! rt "add" "Done already") [:card :id])]
        (op! rt "claim" working-id "--owner" "agent" "--branch" "feature-x")
        (op! rt "claim" review-id "--owner" "reviewer" "--branch" "feature-y")
        (op! rt "review" review-id)
        (op! rt "claim" done-id "--owner" "agent" "--branch" "done-x")
        (op! rt "finish" done-id "--outcome" "abandoned")
        (let [board (op! rt "board")]
          (is (= [idea-id] (mapv :id (:refinement board))))
          (is (= [queued-id] (mapv :id (:pending board))))
          (is (= [working-id] (mapv :id (:claimed board))))
          (is (= [review-id] (mapv :id (:in_review board))))
          (is (= "feature-x" (:branch (first (:claimed board)))))
          (is (= 1 (get-in board [:closed :count])))
          (is (not (contains? board :unknown-lane))))
        (is (= "abandoned" (get-in (weaver/show rt done-id) [:attributes :kanban/outcome])))))))

(deftest kanban-board-needs-review-frontier
  (with-kanban
    (fn [rt]
      (let [card-id (get-in (op! rt "add" "Reviewable feature") [:card :id])]
        (op! rt "claim" card-id "--owner" "agent" "--branch" "review-branch")
        (testing "needs-review is always present and empty before any review work"
          (is (= [] (:needs-review (op! rt "board")))))
        (let [ready-review (weaver/add rt {:title "Review ready"
                                           :attributes {:workflow/checkpoint-kind "human"}})
              impl (weaver/add rt {:title "Implement" :attributes {:kind "task"}})
              blocked-review (weaver/add rt {:title "Review blocked" :attributes {:kind "review"}})]
          (weaver/update rt card-id {:edges [{:type "parent-of" :to (:id ready-review)}
                                             {:type "parent-of" :to (:id impl)}
                                             {:type "parent-of" :to (:id blocked-review)}]})
          ;; blocked-review depends on impl, so it stays out of the ready frontier
          (weaver/update rt (:id blocked-review) {:edges [{:type "depends-on" :to (:id impl)}]})
          (testing "needs-review surfaces only ready review children with the card branch"
            (let [entries (:needs-review (op! rt "board"))]
              (is (vector? entries))
              (is (= [(:id ready-review)] (mapv #(get-in % [:item :id]) entries)))
              (is (= card-id (:card (first entries))))
              (is (= "review-branch" (:branch (first entries)))))))))))

(deftest kanban-card-related-both-directions
  (with-kanban
    (fn [rt]
      (let [a-id (get-in (op! rt "add" "Card A") [:card :id])
            b-id (get-in (op! rt "add" "Card B") [:card :id])
            edge (fn [related] (mapv (fn [e] [(:relation e) (get-in e [:strand :id])]) related))]
        ;; A depends-on B: A is the dependent, B is the dependency
        (weaver/update rt a-id {:edges [{:type "depends-on" :to b-id}]})
        (testing "the dependent card sees the depends-on direction"
          (is (= [["depends-on" b-id]] (edge (:related (op! rt "card" a-id))))))
        (testing "the dependency card sees the depended-on-by direction"
          (is (= [["depended-on-by" a-id]] (edge (:related (op! rt "card" b-id))))))
        (testing "incoming edges from non-card strands surface too"
          ;; regression: depends-on subgraph expansion walks outgoing edges only,
          ;; so a card-rooted scan never saw task -> card blockers
          (let [task (weaver/add rt {:title "Cross-feature task" :attributes {:kind "task"}})]
            (weaver/update rt (:id task) {:edges [{:type "depends-on" :to b-id}]})
            (is (= #{["depended-on-by" a-id] ["depended-on-by" (:id task)]}
                   (set (edge (:related (op! rt "card" b-id))))))))
        (testing "related is always present and empty for an unlinked card"
          (let [c-id (get-in (op! rt "add" "Card C") [:card :id])]
            (is (= [] (:related (op! rt "card" c-id))))))))))

(deftest kanban-board-str-renders-ascii-lanes
  (with-kanban
    (fn [rt]
      (let [long-title (apply str "Very long title " (repeat 40 "padding "))
            _idea (op! rt "add" long-title "--lane" "refinement")
            working-id (get-in (op! rt "add" "Working card") [:card :id])]
        (op! rt "claim" working-id "--owner" "agent-a" "--branch" "feature-x")
        (let [rendered ((requiring-resolve 'ct.spools.kanban/board-str) (op! rt "board"))
              lines (str/split-lines rendered)]
          (is (str/includes? rendered "REFINEMENT (1)"))
          (is (str/includes? rendered "PENDING (0)"))
          (is (str/includes? rendered "CLAIMED / WIP (1)"))
          (is (str/includes? rendered "IN REVIEW (0)"))
          (is (str/includes? rendered "[p3 @feature-x agent-a] Working card"))
          (is (str/includes? rendered "NEEDS REVIEW (0)"))
          (testing "rows are clipped to the board width"
            (is (every? #(<= (count %) 100) lines))))))))

(deftest kanban-task-add-and-list-project-tasks-under-feature
  (with-kanban
    (fn [rt]
      (let [feature-id (get-in (op! rt "add" "Task-bearing feature") [:card :id])
            added (op! rt "task" "add" feature-id "Implement" "the" "core" "--body" "context")
            task-id (get-in added [:task :id])]
        (testing "task add stamps the marker + kind and parents under the feature"
          (is (= "kanban task add" (:operation added)))
          (is (= feature-id (:feature added)))
          (let [stored (weaver/show rt task-id)]
            (is (= "Implement the core" (:title stored)))
            (is (= "true" (get-in stored [:attributes :kanban/task])))
            (is (= "task" (get-in stored [:attributes :kind])))
            (is (= "context" (get-in stored [:attributes :body]))))
          (let [edges (:edges (graph/subgraph rt [feature-id] {:type "parent-of"}))]
            (is (some #(and (= feature-id (:from_strand_id %))
                            (= task-id (:to_strand_id %))) edges))))
        (testing "task list projects only marked tasks, not other parent-of children"
          ;; a bare strand parented under the feature is not a task (marker-selected)
          (let [plain (weaver/add rt {:title "Not a task"})]
            (weaver/update rt feature-id {:edges [{:type "parent-of" :to (:id plain)}]})
            (let [listed (op! rt "task" "list" feature-id)]
              (is (= "kanban task list" (:operation listed)))
              (is (= [task-id] (mapv :id (:tasks listed))))
              (is (= "ready" (:status (first (:tasks listed))))))))
        (testing "task add fails loudly on a missing title, non-card feature, and unknown action"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"title must be a non-blank"
                                (op! rt "task" "add" feature-id)))
          (let [orphan (weaver/add rt {:title "Loose strand"})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a kanban card"
                                  (op! rt "task" "add" (:id orphan) "x"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"action must be add or list"
                                (op! rt "task" "bogus" feature-id))))
        (testing "task add/list reject an epic parent — only features bear tasks"
          (let [epic-id (get-in (op! rt "add" "Epic theme" "--type" "epic") [:card :id])]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a feature card"
                                  (op! rt "task" "add" epic-id "x")))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a feature card"
                                  (op! rt "task" "list" epic-id)))))))))

(deftest kanban-task-status-derives-from-graph-and-owner
  ;; Self-contained DAG (DELTA-Nwt-001.J2): the four statuses derive from
  ;; state=closed, the depends-on frontier, and the owner attr only — never a
  ;; delegation or agent-run attribute is set, so the litmus (delete delegation,
  ;; the derivation still computes) holds.
  (with-kanban
    (fn [rt]
      (let [feature-id (get-in (op! rt "add" "DAG feature") [:card :id])
            ready-id (get-in (op! rt "task" "add" feature-id "Ready task") [:task :id])
            doing-id (get-in (op! rt "task" "add" feature-id "Doing task") [:task :id])
            done-id (get-in (op! rt "task" "add" feature-id "Done task") [:task :id])
            blocked-id (get-in (op! rt "task" "add" feature-id "Blocked task"
                                    "--depends-on" ready-id) [:task :id])
            status-of (fn [] (into {} (map (juxt :id :status))
                                   (:tasks (op! rt "task" "list" feature-id))))]
        (weaver/update rt doing-id {:attributes {:owner "agent-a"}})
        (weaver/update rt done-id {:state "closed"})
        (testing "the four statuses derive purely from graph + core attrs"
          (let [status (status-of)]
            (is (= "ready" (status ready-id)) "active, dependencies met, no owner")
            (is (= "doing" (status doing-id)) "active, dependencies met, owner present")
            (is (= "closed" (status done-id)) "closed strand")
            (is (= "blocked" (status blocked-id)) "active with an unmet depends-on target")))
        (testing "closing the dependency unblocks its dependent"
          (weaver/update rt ready-id {:state "closed"})
          (let [status (status-of)]
            (is (= "closed" (status ready-id)) "the closed dependency reads as closed")
            (is (= "ready" (status blocked-id)) "dependency closed, no owner -> ready")))))))

(deftest kanban-card-view-projects-tasks-lane
  (with-kanban
    (fn [rt]
      (let [feature-id (get-in (op! rt "add" "Card-view task feature") [:card :id])
            ready-id (get-in (op! rt "task" "add" feature-id "Ready task") [:task :id])
            doing-id (get-in (op! rt "task" "add" feature-id "Doing task") [:task :id])]
        (weaver/update rt doing-id {:attributes {:owner "agent-a"}})
        (testing "card view lists child tasks with their derived statuses"
          (let [tasks (:tasks (op! rt "card" feature-id))]
            (is (= #{ready-id doing-id} (set (map :id tasks))))
            (is (= {ready-id "ready" doing-id "doing"}
                   (into {} (map (juxt :id :status)) tasks)))))
        (testing "tasks stay out of the generic work projections — status has one source of truth"
          ;; the derived-doing task must not leak into :active-work/:ready, where
          ;; a caller hunting unclaimed work would misread an already-owned task
          (let [view (op! rt "card" feature-id)
                task-ids #{ready-id doing-id}]
            (is (empty? (filter task-ids (map :id (:active-work view)))))
            (is (empty? (filter task-ids (map :id (:ready view)))))))
        (testing "a card with no task tier projects an empty tasks lane"
          (let [plain-id (get-in (op! rt "add" "No tasks here") [:card :id])]
            (is (= [] (:tasks (op! rt "card" plain-id))))))))))

(deftest kanban-board-surfaces-doing-task-on-wip-lanes
  (with-kanban
    (fn [rt]
      (let [feature-id (get-in (op! rt "add" "Doing-task feature") [:card :id])]
        (op! rt "claim" feature-id "--owner" "agent-a" "--branch" "doing-branch")
        (let [doing-id (get-in (op! rt "task" "add" feature-id "Wire the thing") [:task :id])]
          (weaver/update rt doing-id {:attributes {:owner "agent-a"}})
          (testing "the claimed lane carries the derived doing-task title"
            (let [claimed (some #(when (= feature-id (:id %)) %) (:claimed (op! rt "board")))]
              (is (= "Wire the thing" (get-in claimed [:doing-task :title])))
              (is (= "doing" (get-in claimed [:doing-task :status])))))
          (testing "the in_review lane carries the doing-task title too"
            (op! rt "review" feature-id)
            (let [reviewing (some #(when (= feature-id (:id %)) %) (:in_review (op! rt "board")))]
              (is (= "Wire the thing" (get-in reviewing [:doing-task :title])))))
          (testing "board-str renders the doing-task line"
            (let [rendered ((requiring-resolve 'ct.spools.kanban/board-str) (op! rt "board"))]
              (is (str/includes? rendered "doing: Wire the thing")))))))))

(deftest kanban-card-view-joins-the-bound-tracker
  ;; The spool's one tracker seam: a card stamped with kanban/run-id (claim --run-id)
  ;; projects the bound tracker's status and ready steps in card view.
  (with-kanban
    (fn [rt]
      (let [card-id (get-in (op! rt "add" "Tracked feature") [:card :id])
            plain-id (get-in (op! rt "add" "Untracked feature") [:card :id])]
        (testing "claim --run-id stamps the run-id on the card as kanban/run-id"
          (let [claimed (op! rt "claim" card-id "--owner" "agent" "--branch" "widgets"
                             "--run-id" "widgets-run")]
            (is (= "widgets-run" (get-in claimed [:card :attributes :kanban/run-id])))))
        (kanban/set-tracker! {:name "stub" :project stub-projection})
        (testing "a bound tracker joins the run's status and trimmed ready steps"
          (let [{:keys [name run-id status ready]} (:tracker (op! rt "card" card-id))]
            (is (= "stub" name))
            (is (= "widgets-run" run-id))
            (is (= "spec" status))
            ;; kanban trims each step to its own closed key set — the tracker's
            ;; :extra key never leaks into the kanban-owned card-view shape
            (is (= [{:id "s1" :title "Draft spec" :role "step" :stage "spec" :checkpoint false}]
                   ready))))
        (testing "a tracker reporting no active run projects an honest nil status"
          (let [idle-id (get-in (op! rt "add" "Idle-run feature") [:card :id])]
            (op! rt "claim" idle-id "--owner" "agent" "--branch" "idle" "--run-id" "idle-run")
            (is (= {:name "stub" :run-id "idle-run" :status nil :ready []}
                   (:tracker (op! rt "card" idle-id))))))
        (testing "an unstamped card carries no :tracker key"
          (is (not (contains? (op! rt "card" plain-id) :tracker))))))))

(deftest kanban-card-view-projects-a-stamped-run-as-unbound-with-no-tracker
  ;; RFC-022.G5: a stamped card in a world with no binding projects honestly as
  ;; unbound — the stamp visible, the missing strategy visible — never hidden.
  (with-kanban
    (fn [rt]
      (let [card-id (get-in (op! rt "add" "Unbound-world feature") [:card :id])]
        (op! rt "claim" card-id "--owner" "agent" "--branch" "widgets" "--run-id" "widgets-run")
        (is (= {:name nil :run-id "widgets-run" :status nil :ready []}
               (:tracker (op! rt "card" card-id))))))))

(deftest kanban-set-tracker-resolves-a-symbol-valued-project
  ;; :project may be a fully-qualified symbol, resolved with requiring-resolve at
  ;; call time so a config reload rebinds cleanly.
  (with-kanban
    (fn [rt]
      (let [card-id (get-in (op! rt "add" "Symbol-tracked feature") [:card :id])]
        (op! rt "claim" card-id "--owner" "agent" "--branch" "widgets" "--run-id" "widgets-run")
        (kanban/set-tracker! {:name "stub" :project 'ct.spools.kanban-test/stub-projection})
        (let [{:keys [name status ready]} (:tracker (op! rt "card" card-id))]
          (is (= "stub" name))
          (is (= "spec" status))
          (is (= ["s1"] (mapv :id ready))))))))

(deftest kanban-set-tracker-rejects-a-malformed-binding
  (with-kanban
    (fn [_rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a map"
                            (kanban/set-tracker! "not-a-map")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                            (kanban/set-tracker! {:name "x" :project stub-projection :surprise true})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":name must be a non-blank string"
                            (kanban/set-tracker! {:name "" :project stub-projection})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":project must be a fully-qualified symbol or a function"
                            (kanban/set-tracker! {:name "x" :project "nope"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":project must be a fully-qualified symbol or a function"
                            (kanban/set-tracker! {:name "x" :project 'bare-symbol}))))))

(deftest kanban-card-view-validates-and-propagates-tracker-failures
  (with-kanban
    (fn [rt]
      (let [bad-id (get-in (op! rt "add" "Bad-projection feature") [:card :id])
            boom-id (get-in (op! rt "add" "Throwing-tracker feature") [:card :id])]
        (op! rt "claim" bad-id "--owner" "a" "--branch" "b" "--run-id" "widgets-run")
        (op! rt "claim" boom-id "--owner" "a" "--branch" "c" "--run-id" "widgets-run")
        (testing "a non-map projection result fails loudly"
          (kanban/set-tracker! {:name "bad" :project (fn [_] "not-a-map")})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"projection does not match its owning spec"
                                (op! rt "card" bad-id))))
        (testing "missing or malformed projection fields fail loudly"
          (doseq [projection [{:status nil}
                              {:status 42 :ready []}
                              {:status nil :ready nil}
                              {:status nil :ready ["not-a-map"]}
                              {:status nil :ready [{}]}
                              {:status nil :ready [{:id "s1" :title "" :role "step"}]}
                              {:status nil :ready [] :surprise true}]]
            (kanban/set-tracker! {:name "malformed" :project (fn [_] projection)})
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"projection does not match its owning spec"
                                  (op! rt "card" bad-id)))))
        (testing "a throwing strategy propagates rather than being masked"
          (kanban/set-tracker! {:name "boom" :project (fn [_] (throw (ex-info "tracker exploded" {})))})
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tracker exploded"
                                (op! rt "card" boom-id))))))))

(deftest kanban-card-view-rejects-a-malformed-stored-run
  (with-kanban
    (fn [rt]
      (let [card (weaver/add rt {:title "Malformed run card"
                                 :attributes {:kanban/card "true"
                                              :kanban/lane "claimed"
                                              :kanban/type "feature"
                                              :kanban/run-id ""}})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Tracker view does not match its owning spec"
                              (op! rt "card" (:id card))))))))

(deftest kanban-about-names-the-bound-tracker
  (with-kanban
    (fn [rt]
      (testing "with no binding, about states none is bound"
        (is (re-find #"No tracker bound" (:tracker (op! rt "about")))))
      (testing "after set-tracker!, about names the bound tracker"
        (kanban/set-tracker! {:name "devflow" :project stub-projection})
        (is (re-find #"devflow" (:tracker (op! rt "about"))))))))

(deftest state-shape-matches-declared-version
  ;; Drift alarm for kanban's versioned spool-state: update this key set and
  ;; state-version together whenever new-state's shape changes.
  (is (= #{:tracker-binding}
         (set (keys (#'kanban/new-state))))))

(deftest kanban-batch-weave-creates-cards-and-dependencies
  (with-kanban
    (fn [rt]
      (let [existing (weaver/add rt {:title "Existing blocker"})
            result (patterns/weave! rt :kanban-batch
                                    {:items [{:key "design"
                                              :title "Design batch"
                                              :body "Design body"
                                              :priority "p2"}
                                             {:key "docs"
                                              :title "Write docs"
                                              :depends-on ["design" (:id existing)]}]})
            design-id (get-in result [:refs "design"])
            docs-id (get-in result [:refs "docs"])
            design (weaver/show rt design-id)
            docs (weaver/show rt docs-id)
            edge-set (set (map (juxt :from_strand_id :to_strand_id :edge_type)
                               (:edges (graph/subgraph rt [docs-id] {:type "depends-on"}))))]
        (is (= "Design batch" (:title design)))
        (is (= "Design body" (get-in design [:attributes :body])))
        (is (= "p2" (get-in design [:attributes :kanban/priority])))
        (is (= "true" (get-in docs [:attributes :kanban/card])))
        (is (= "pending" (get-in docs [:attributes :kanban/lane])))
        (is (= "p3" (get-in docs [:attributes :kanban/priority])))
        (is (contains? edge-set [docs-id design-id "depends-on"]))
        (is (contains? edge-set [docs-id (:id existing) "depends-on"]))))))

(deftest kanban-batch-weave-fails-loudly
  (with-kanban
    (fn [rt]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern input failed spec validation"
                            (patterns/weave! rt :kanban-batch
                                             {:items [{:key "x" :title "X" :surprise true}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Pattern input failed spec validation"
                            (patterns/weave! rt :kanban-batch
                                             {:items [{:key "x" :title "X" :priority "urgent"}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"item keys must be unique"
                            (patterns/weave! rt :kanban-batch
                                             {:items [{:key "x" :title "X"}
                                                      {:key "x" :title "Again"}]})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target strand not found"
                            (patterns/weave! rt :kanban-batch
                                             {:items [{:key "x" :title "X" :depends-on ["missing-strand"]}]}))))))

(deftest install-registers-kanban-export-op
  (with-kanban
    (fn [rt]
      (is (some #(= "kanban-export" (:name %)) (weaver/ops rt))))))

(deftest kanban-export-returns-subtree-with-internal-edges
  (with-kanban
    (fn [rt]
      (let [root-id (get-in (op! rt "add" "Export me") [:card :id])
            child (weaver/add rt {:title "Child work" :attributes {:kind "task"}})
            dep (weaver/add rt {:title "Dependency" :attributes {:kind "task"}})]
        (weaver/update rt root-id {:edges [{:type "parent-of" :to (:id child)}
                                           {:type "parent-of" :to (:id dep)}]})
        (weaver/update rt (:id child) {:edges [{:type "depends-on" :to (:id dep)}]})
        (weaver/update rt (:id dep) {:state "closed"})
        (let [result (export! rt root-id)
              strand-ids (set (map :id (:strands result)))]
          (is (= "kanban-export" (:operation result)))
          (is (= root-id (:root-id result)))
          (is (= #{root-id (:id child) (:id dep)} strand-ids)
              "closed strands stay in the export payload")
          (is (some #(= "closed" (:state %)) (:strands result)))
          (is (= #{{:from_strand_id root-id :to_strand_id (:id child) :edge_type "parent-of"}
                   {:from_strand_id root-id :to_strand_id (:id dep) :edge_type "parent-of"}}
                 (set (:parent-of-edges result))))
          (is (= [{:from_strand_id (:id child) :to_strand_id (:id dep) :edge_type "depends-on"}]
                 (:depends-on-edges result))))))))

(deftest kanban-export-enforces-the-card-contract
  (with-kanban
    (fn [rt]
      (testing "an unknown id fails loudly"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strand not found"
                              (export! rt "missing-id"))))
      (testing "a known strand that is not a kanban card fails loudly"
        ;; regression: the op once exported any existing strand's subtree
        ;; instead of enforcing its documented feature-or-epic-card contract
        (let [plain (weaver/add rt {:title "Not a card"})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a kanban card"
                                (export! rt (:id plain)))))))))

(deftest kanban-claim-guards-the-run-flags
  (with-kanban
    (fn [rt]
      (let [id (get-in (op! rt "add" "Blank run guard") [:card :id])]
        ;; regression: a blank run-id once stamped an empty attr that later
        ;; rendered as the same honest unbound shape as a real unstarted run
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"run-id must be a non-blank string"
                              (op! rt "claim" id "--owner" "agent" "--branch" "b" "--run-id" "")))
        (is (= "pending" (get-in (weaver/show rt id) [:attributes :kanban/lane]))
            "no failed claim moved the card")))))

(defn -main
  "Run the standalone kanban.spool test suite."
  [& _args]
  (let [summary (clojure.test/run-tests 'ct.spools.kanban-test
                                        'ct.spools.kanban-peering-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
