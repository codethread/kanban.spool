(ns ct.spools.kanban
  "User-facing kanban board over Skein strands.

  Cards are the user<->agent tracking surface: everything a user asks for is a
  `feature` card (occasionally grouped under an `epic`), and every agent
  working directly with a user works under a claimed card. All card state
  lives under `kanban/*` attributes; `kanban/lane` is the active board lane
  (`refinement` -> `pending` -> `claimed` -> `in_review`) and `kanban/outcome`
  records a finished card's outcome. The
  `kanban/priority` (p1 immediate blocker .. p4 someday, default p3) orders
  lanes and `kanban next`.

  Cards are work roots: claiming stamps `owner`/`branch`/`worktree`, and
  execution strands hang beneath the card with `parent-of` edges — the kanban
  spool complements the engines that produce them, it does not replace them.
  Notes are closed note strands on cards and tasks; progress notes belong on
  the doing-task, so a cold agent self-discovers in-flight work with
  `kanban board` -> `kanban card <id>` -> the doing-task and its
  `latest-note`."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.notes.alpha :as notes]
            [skein.api.patterns.alpha :as patterns]
            [skein.api.graph.alpha :as graph]
            [skein.api.vocab.alpha :as vocab]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.weaver.internal.op-entry :as op-entry]
            [skein.api.format.alpha :as fmt]
            [skein.api.runtime.alpha :as runtime]
            [skein.api.spool.alpha :refer [attr-get entity-projection fail! reject-unknown-keys!]]))

(def ^:private card-attr :kanban/card)
(def ^:private lane-attr :kanban/lane)
(def ^:private outcome-attr :kanban/outcome)
(def ^:private type-attr :kanban/type)
(def ^:private priority-attr :kanban/priority)
(def ^:private note-kind-attr :note/kind)
(def ^:private task-attr :kanban/task)
(def ^:private run-id-attr :kanban/run-id)
(def ^:private restore-lane-attr :kanban/abandon-restore-lane)

(def ^:private addable-lanes #{"pending" "refinement"})
(def ^:private active-lanes #{"refinement" "pending" "claimed" "in_review"})
(def ^:private epic-finish-lanes #{"refinement" "pending"})
(def ^:private card-types #{"feature" "epic"})
(def ^:private card-priorities #{"p1" "p2" "p3" "p4"})
(def ^:private default-priority "p3")

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- require-non-blank!
  "Return v when it is a non-blank string, otherwise throw with arg context."
  [arg v]
  (when-not (non-blank-string? v)
    (throw (ex-info (str (name arg) " must be a non-blank string")
                    {:argument arg :value v})))
  v)

(defn- require-flag!
  "Return the value of flag, failing loudly when it is absent."
  [op flags flag]
  (or (get flags flag)
      (throw (ex-info (str op " requires " flag)
                      {:flag flag :provided (sort (keys flags))}))))

(defn- attr-value
  "Return a strand attribute by keyword or string key, via the shared spool-tier
  tolerant reader (`skein.api.spool.alpha/attr-get`)."
  [strand k]
  (attr-get strand k))

(defn- card-type
  "Return a card's kanban type.

  An absent `kanban/type` reads as `feature`, the board's documented default. A
  value outside the known types is drift: it fails loudly rather than passing as
  a feature and quietly taking the feature path."
  [strand]
  (let [type (attr-value strand type-attr)]
    (cond
      (nil? type) "feature"
      (contains? card-types type) type
      :else (throw (ex-info "kanban/type must be feature or epic"
                            {:id (:id strand)
                             :type type
                             :allowed (sort card-types)})))))

(defn- card-priority
  "Return a card's priority, defaulting to p3 for cards that predate priorities."
  [strand]
  (or (attr-value strand priority-attr) default-priority))

(defn- require-priority!
  "Return priority when it is one of p1-p4, failing loudly otherwise."
  [priority]
  (when-not (contains? card-priorities priority)
    (throw (ex-info "kanban priority must be one of p1, p2, p3, p4"
                    {:priority priority :allowed (sort card-priorities)})))
  priority)

(defn- card-attributes
  "Return the attributes for a newly added kanban card strand."
  [flags]
  (let [lane (or (get flags "--lane") "pending")
        type (or (get flags "--type") "feature")
        priority (require-priority! (or (get flags "--priority") default-priority))]
    (when-not (contains? addable-lanes lane)
      (throw (ex-info "kanban add --lane must be pending or refinement"
                      {:lane lane :allowed (sort addable-lanes)})))
    (when-not (contains? card-types type)
      (throw (ex-info "kanban add --type must be feature or epic"
                      {:type type :allowed (sort card-types)})))
    (cond-> {card-attr "true"
             lane-attr lane
             type-attr type
             priority-attr priority}
      (get flags "--body") (assoc :body (get flags "--body"))
      (get flags "--source") (assoc :kanban/source (get flags "--source")))))

(defn- compact-card
  "Return the compact card shape used in board/next output."
  [strand]
  (cond-> {:id (:id strand)
           :title (:title strand)
           :state (:state strand)
           :lane (attr-value strand lane-attr)
           :type (card-type strand)
           :priority (card-priority strand)
           :created_at (:created_at strand)}
    (attr-value strand :owner) (assoc :owner (attr-value strand :owner))
    (attr-value strand :branch) (assoc :branch (attr-value strand :branch))
    (attr-value strand :worktree) (assoc :worktree (attr-value strand :worktree))
    (attr-value strand :kanban/source) (assoc :source (attr-value strand :kanban/source))))

(defn- card-strand
  "Return id's kanban card strand, failing loudly if it is absent or not a card."
  [id]
  (let [strand (or (weaver/show (current/runtime) id)
                   (throw (ex-info "Kanban strand not found" {:id id})))]
    (when-not (= "true" (attr-value strand card-attr))
      (throw (ex-info "Strand is not a kanban card" {:id id :attributes (:attributes strand)})))
    strand))

(defn- epic-strand
  "Return id's epic card strand, failing loudly for non-epic cards."
  [id]
  (let [strand (card-strand id)]
    (when-not (= "epic" (card-type strand))
      (throw (ex-info "Strand is not an epic card" {:id id :type (card-type strand)})))
    strand))

(defn- feature-strand
  "Return id's feature card strand, failing loudly for non-feature cards.

  Only features bear tasks; an epic parent fails here rather than silently
  parenting a task under the wrong tier."
  [id]
  (let [strand (card-strand id)]
    (when-not (= "feature" (card-type strand))
      (throw (ex-info "Strand is not a feature card" {:id id :type (card-type strand)})))
    strand))

(defn add!
  "Create a kanban card in the pending (or refinement) lane.

  `--type epic` creates a grouping epic; `--epic <id>` hangs a new feature
  under an existing epic with a parent-of edge."
  [title flags]
  (let [title (require-non-blank! :title title)
        rt (current/runtime)
        epic-id (get flags "--epic")]
    (when (and epic-id (= "epic" (get flags "--type")))
      (throw (ex-info "kanban epics cannot nest under other epics" {:epic epic-id})))
    (let [epic (some-> epic-id epic-strand)
          strand (weaver/add! rt {:title title
                                  :attributes (card-attributes flags)})]
      (when epic
        (weaver/update! rt (:id epic) {:edges [{:type "parent-of" :to (:id strand)}]}))
      (cond-> {:operation "kanban add"
               :card (entity-projection strand)}
        epic (assoc :epic (:id epic))))))

;; kanban-batch weave pattern
(s/def ::non-blank-string non-blank-string?)
(s/def ::key ::non-blank-string)
(s/def ::title ::non-blank-string)
(s/def ::body ::non-blank-string)
(s/def ::depends-on (s/coll-of ::non-blank-string :kind vector?))
(s/def ::priority card-priorities)
(def ^:private batch-item-keys #{:key :title :body :depends-on :priority})
(def ^:private batch-input-keys #{:items})

(defn- known-keys?
  "Return true when map m contains only allowed keys."
  [allowed m]
  (empty? (remove allowed (keys m))))

(s/def ::batch-item
  (s/and map?
         #(known-keys? batch-item-keys %)
         (s/keys :req-un [::key ::title]
                 :opt-un [::body ::depends-on ::priority])))
(s/def ::items (s/coll-of ::batch-item :kind vector? :min-count 1))
(s/def ::kanban-batch-input
  (s/and map?
         #(known-keys? batch-input-keys %)
         (s/keys :req-un [::items])))

(defn- duplicate-item
  "Return the first duplicate value in xs, or nil."
  [xs]
  (some (fn [[v n]] (when (> n 1) v)) (frequencies xs)))

(defn- item-ref
  "Return the batch-local symbol for item key."
  [key]
  (symbol key))

(defn kanban-batch
  "Create pending feature cards with bodies and depends-on edges.

  Input shape: {:items [{:key \"slug\" :title \"Title\" :body \"optional\"
  :priority \"p1|p2|p3|p4 (optional, default p3)\"
  :depends-on [\"sibling-key-or-existing-strand-id\"]}]}. `depends-on` values matching sibling
  keys become batch-local edges; all other values are treated as durable strand
  ids and fail loudly if absent."
  [{:keys [input]}]
  (let [{:keys [items]} input
        keys (mapv :key items)]
    (when-let [duplicate-key (duplicate-item keys)]
      (throw (ex-info "kanban-batch item keys must be unique" {:key duplicate-key})))
    (let [sibling-keys (set keys)]
      (mapv (fn [{:keys [key title body depends-on priority]}]
              (cond-> {:ref (item-ref key)
                       :title title
                       :attributes (card-attributes (cond-> {}
                                                      body (assoc "--body" body)
                                                      priority (assoc "--priority" priority)))}
                (seq depends-on)
                (assoc :edges (mapv (fn [dep]
                                      {:type "depends-on"
                                       :to (if (contains? sibling-keys dep)
                                             (item-ref dep)
                                             dep)})
                                    depends-on))))
            items))))

(defn- require-lane!
  "Return strand when it is active in the expected kanban lane."
  [op strand expected]
  (when-not (= "active" (:state strand))
    (throw (ex-info (str "Kanban card must be active to " op)
                    {:id (:id strand) :state (:state strand)})))
  (when-not (= expected (attr-value strand lane-attr))
    (throw (ex-info (str "Kanban card must be " expected " to " op)
                    {:id (:id strand) :lane (attr-value strand lane-attr)})))
  strand)

(defn- update-card!
  "Write only the changed `attrs` (and optional `state`) onto a kanban card.

  `attrs` is a delta: just the keyword-keyed attributes this op changes, handed
  straight to `weaver/update` so `db/update-strand!`'s `json_patch` merge folds them
  into the stored map. Writing a delta rather than a read-merged full map removes
  a lost-update race — two concurrent `update-card!` calls (e.g. `set-priority!`
  and `claim!`) each patch only their own keys instead of overwriting the whole
  attribute map from a possibly-stale read. `weaver/update` returns the full merged
  strand, so callers still see every attribute in the result."
  [strand attrs state]
  (weaver/update! (current/runtime)
                  (:id strand)
                  (cond-> {:attributes attrs}
                    state (assoc :state state))))

(defn promote!
  "Move a refinement card into the pending lane (an explicit human act)."
  [id]
  (let [strand (require-lane! "promote" (card-strand (require-non-blank! :id id)) "refinement")
        updated (update-card! strand {lane-attr "pending"} nil)]
    {:operation "kanban promote"
     :card (entity-projection updated)}))

(defn set-priority!
  "Set an active card's priority (p1 highest urgency .. p4 someday)."
  [id priority]
  (let [strand (card-strand (require-non-blank! :id id))
        priority (require-priority! priority)]
    (when-not (= "active" (:state strand))
      (throw (ex-info "Kanban card must be active to reprioritise"
                      {:id (:id strand) :state (:state strand)})))
    (let [updated (update-card! strand {priority-attr priority} nil)]
      {:operation "kanban priority"
       :card (entity-projection updated)})))

(defn- claim-run-id
  "Return the run id to stamp at claim, or nil when `--run-id` is absent."
  [flags]
  (when-let [run (get flags "--run-id")]
    (require-non-blank! :run-id run)))

(defn claim!
  "Claim a pending feature card, stamping the work-root attributes.

  `--owner` and `--branch` are mandatory so every claimed card answers who is
  driving it and on which branch; `--worktree` is optional (direct work in the
  main checkout has no separate worktree). `--run-id` optionally names the card's
  tracker run so `kanban card` can join the bound tracker's status and ready
  steps. Epics group work and are never claimed themselves."
  [id flags]
  (let [strand (require-lane! "claim" (card-strand (require-non-blank! :id id)) "pending")]
    (when (= "epic" (card-type strand))
      (throw (ex-info "Kanban epics cannot be claimed; claim a feature under the epic"
                      {:id (:id strand)})))
    (let [owner (require-flag! "kanban claim" flags "--owner")
          branch (require-flag! "kanban claim" flags "--branch")
          run (claim-run-id flags)
          attrs (cond-> {lane-attr "claimed"
                         :owner owner
                         :branch branch}
                  (get flags "--worktree") (assoc :worktree (get flags "--worktree"))
                  run (assoc run-id-attr run))
          updated (update-card! strand attrs nil)]
      {:operation "kanban claim"
       :card (entity-projection updated)})))

(defn review!
  "Move a claimed kanban card into the in_review lane."
  [id]
  (let [strand (require-lane! "mark in_review" (card-strand (require-non-blank! :id id)) "claimed")
        updated (update-card! strand {lane-attr "in_review"} nil)]
    {:operation "kanban review"
     :card (entity-projection updated)}))

(defn rework!
  "Move an in_review kanban card back to claimed for rework."
  [id]
  (let [strand (require-lane! "rework" (card-strand (require-non-blank! :id id)) "in_review")
        updated (update-card! strand {lane-attr "claimed"} nil)]
    {:operation "kanban rework"
     :card (entity-projection updated)}))

(defn- direct-feature-children
  "Return an epic's direct `parent-of` children that are feature cards, sorted by id.

  One batched edge lookup and one batched strand read, mirroring `feature-tasks`.
  Non-card children (tasks, notes, execution strands) and epic children are
  filtered out; a child card whose `kanban/type` is drift fails loudly via
  `card-type` rather than being silently skipped."
  [rt epic]
  (let [child-ids (mapv :to_strand_id (graph/outgoing-edges rt [(:id epic)] "parent-of"))]
    (->> (graph/strands-by-ids rt child-ids)
         (filter #(and (= "true" (attr-value % card-attr))
                       (= "feature" (card-type %))))
         (sort-by :id)
         vec)))

(defn- finish-feature!
  "Close a claimed or in_review feature card with an explicit outcome."
  [id strand outcome]
  (when-not (contains? #{"claimed" "in_review"} (attr-value strand lane-attr))
    (throw (ex-info "Kanban card must be claimed or in_review to finish"
                    {:id id :lane (attr-value strand lane-attr)})))
  (let [updated (update-card! strand {lane-attr nil outcome-attr outcome} "closed")]
    {:operation "kanban finish"
     :card (entity-projection updated)}))

(defn- complete-epic!
  "Close an epic as done, guarding that every direct feature child is closed.

  An open feature child fails loudly, naming every offending child and its lane —
  a done epic asserts its features are finished, so it never silently closes over
  live work."
  [rt id strand]
  (let [open (filterv #(not= "closed" (:state %)) (direct-feature-children rt strand))]
    (when (seq open)
      (throw (ex-info "Kanban epic cannot be completed while feature children are open"
                      {:id id
                       :open-children (mapv (fn [child]
                                              {:id (:id child)
                                               :lane (attr-value child lane-attr)})
                                            open)})))
    (let [updated (update-card! strand {lane-attr nil outcome-attr "done"} "closed")]
      {:operation "kanban finish"
       :card (entity-projection updated)})))

(defn- abandon-epic!
  "Abandon an epic and cascade-close its still-open feature children.

  Each feature child not already closed records its current lane in
  `kanban/abandon-restore-lane` before closing (outcome `abandoned`, lane
  cleared), so `reopen` can restore exactly what this abandon closed. Children
  already closed before the cascade are finished work: they are left untouched
  and carry no marker. The epic records its own pre-abandon lane the same way."
  [rt strand]
  (let [cascaded (filterv #(not= "closed" (:state %)) (direct-feature-children rt strand))]
    (doseq [child cascaded]
      (update-card! child
                    {restore-lane-attr (attr-value child lane-attr)
                     lane-attr nil
                     outcome-attr "abandoned"}
                    "closed"))
    (let [updated (update-card! strand
                                {restore-lane-attr (attr-value strand lane-attr)
                                 lane-attr nil
                                 outcome-attr "abandoned"}
                                "closed")]
      {:operation "kanban finish"
       :card (entity-projection updated)
       :cascaded (mapv :id cascaded)})))

(defn- finish-epic!
  "Close a grouping epic from the refinement or pending lane.

  Epics are never claimed, so they finish from a queue lane, not the work lanes.
  `--outcome done` completes (every feature child must be closed); `--outcome
  abandoned` cascades a reversible close over the still-open children. Any other
  outcome fails loudly."
  [rt id strand outcome]
  (when-not (contains? epic-finish-lanes (attr-value strand lane-attr))
    (throw (ex-info "Kanban epic must be in the refinement or pending lane to finish"
                    {:id id :lane (attr-value strand lane-attr) :allowed (sort epic-finish-lanes)})))
  (case outcome
    "done" (complete-epic! rt id strand)
    "abandoned" (abandon-epic! rt strand)
    (throw (ex-info "Kanban epic finish --outcome must be done or abandoned"
                    {:id id :outcome outcome :allowed ["abandoned" "done"]}))))

(defn finish!
  "Close a kanban card with an explicit outcome, polymorphic on `kanban/type`.

  A feature card closes from the claimed or in_review lane (`--outcome` defaults
  to done). A grouping epic is never claimed, so it closes from the refinement or
  pending lane: `--outcome done` completes it (guarding every direct feature
  child is closed) and `--outcome abandoned` cascade-closes each still-open
  feature child, recording each transitioned card's lane in
  `kanban/abandon-restore-lane` so `kanban reopen` can reverse exactly what the
  abandon closed."
  [id flags]
  (let [id (require-non-blank! :id id)
        rt (current/runtime)
        strand (card-strand id)
        outcome (or (get flags "--outcome") "done")]
    (when-not (= "active" (:state strand))
      (throw (ex-info "Kanban card must be active to finish" {:id id :state (:state strand)})))
    (if (= "epic" (card-type strand))
      (finish-epic! rt id strand outcome)
      (finish-feature! id strand outcome))))

(defn- validated-restore-lane
  "Return card's stored `kanban/abandon-restore-lane`, failing loudly on a bad marker.

  reopen consumes markers a prior abandon wrote. A missing or drifted marker
  would otherwise restore a card into an unknown lane; validating every marker
  up front — before any mutation — keeps a bad marker from leaving a
  half-reopened cascade behind."
  [card]
  (let [lane (attr-value card restore-lane-attr)]
    (when-not (contains? active-lanes lane)
      (throw (ex-info "Kanban reopen found an invalid abandon-restore-lane marker"
                      {:id (:id card) :restore-lane lane :allowed (sort active-lanes)})))
    lane))

(defn reopen!
  "Reopen an abandoned epic, reversing exactly the cascade a matching abandon closed.

  The inverse of abandon only: the epic must be a closed epic with
  `kanban/outcome=abandoned`; a done epic (or any non-abandoned card) is refused,
  because reopen pairs with abandon, not complete. The epic returns to its stored
  `kanban/abandon-restore-lane` (state active, outcome and marker cleared). Each
  direct feature child that is closed *and* carries the marker is reopened to its
  own stored restore lane; a child closed before the abandon (no marker) was
  legitimately done and stays closed. Reopen is a true inverse, never a blanket
  reopen."
  [id]
  (let [id (require-non-blank! :id id)
        rt (current/runtime)
        strand (epic-strand id)]
    (when-not (= "closed" (:state strand))
      (throw (ex-info "Kanban epic must be closed to reopen" {:id id :state (:state strand)})))
    (when-not (= "abandoned" (attr-value strand outcome-attr))
      (throw (ex-info "Kanban reopen reverses an abandoned epic only"
                      {:id id :outcome (attr-value strand outcome-attr)})))
    (let [cascaded (filterv #(and (= "closed" (:state %))
                                  (some? (attr-value % restore-lane-attr)))
                            (direct-feature-children rt strand))
          epic-restore-lane (validated-restore-lane strand)
          child-restore-lanes (mapv validated-restore-lane cascaded)]
      (doseq [[child lane] (map vector cascaded child-restore-lanes)]
        (update-card! child
                      {lane-attr lane
                       outcome-attr nil
                       restore-lane-attr nil}
                      "active"))
      (let [updated (update-card! strand
                                  {lane-attr epic-restore-lane
                                   outcome-attr nil
                                   restore-lane-attr nil}
                                  "active")]
        {:operation "kanban reopen"
         :card (entity-projection updated)
         :cascaded (mapv :id cascaded)}))))

;; ---------------------------------------------------------------------------
;; note compaction: shared by the notes, task, and card projections
;; ---------------------------------------------------------------------------

(def ^:private note-text-cap
  "Length past which card/task views clip note text.

  Sized to keep whole activity and decision notes intact while folding bulk
  content (review dumps, pasted output) that would otherwise drown the resume
  read; `strand show <note-id>` always returns the full text."
  600)

(defn- compact-note
  "Return the compact note shape used in card and task output.

  Note text past `note-text-cap` is clipped and marked `:truncated true`; the
  full note stays on the note strand (`strand show <note-id>`). Carries the
  primitive's open `note/kind` view hint as `:kind` when stamped."
  [strand]
  (let [note (attr-value strand :note/text)
        clipped? (and (string? note) (> (count note) note-text-cap))]
    (cond-> {:id (:id strand)
             :title (:title strand)
             :note (if clipped? (str (subs note 0 note-text-cap) " …") note)
             :at (or (attr-value strand :note/at) (:created_at strand))}
      clipped? (assoc :truncated true)
      (attr-value strand :note/by) (assoc :by (attr-value strand :note/by))
      (attr-value strand note-kind-attr) (assoc :kind (attr-value strand note-kind-attr)))))

(defn- latest-notes-by-target
  "Return {target-strand-id compact-newest-note} for the given strand ids.

  One batched incoming-`notes` read across every id; the newest note per
  target wins, ordered by note/at, then created_at, then id."
  [rt ids]
  (if (seq ids)
    (let [edges (graph/incoming-edges rt ids "notes")
          target-by-note (into {} (map (juxt :from_strand_id :to_strand_id)) edges)]
      (->> (graph/strands-by-ids rt (vec (keys target-by-note)))
           (sort-by (juxt #(attr-value % :note/at) :created_at :id))
           (reduce (fn [m note]
                     (assoc m (target-by-note (:id note)) (compact-note note)))
                   {})))
    {}))

;; ---------------------------------------------------------------------------
;; task tier: execution strands under a feature card
;; ---------------------------------------------------------------------------

(defn- task-strand?
  "Return true when strand is a kanban task."
  [strand]
  (= "true" (attr-value strand task-attr)))

(defn- feature-tasks
  "Return a feature card's direct `parent-of` task strands, sorted by id.

  Closed tasks are kept (they read as `closed`); only the marker attr selects a
  task, so non-task children (plans, reviews, notes) never leak in."
  [rt feature-id]
  (let [task-ids (mapv :to_strand_id (graph/outgoing-edges rt [feature-id] "parent-of"))]
    (->> (graph/strands-by-ids rt task-ids)
         (filter task-strand?)
         (sort-by :id)
         vec)))

(defn- derive-task-status
  "Derive a task's status from core graph state and the core `owner` attr only.

  `dep-states` is the seq of `:state` values of the task's `depends-on` targets.
  Reads no execution-engine vocabulary: `closed` on a closed strand,
  `blocked` while any dependency is unclosed, then `doing`/`ready` split on
  whether an `owner` is stamped."
  [task dep-states]
  (cond
    (= "closed" (:state task)) "closed"
    (some #(not= "closed" %) dep-states) "blocked"
    (some? (attr-value task :owner)) "doing"
    :else "ready"))

(defn- compact-task
  "Return the compact task shape used in `task list` output."
  [strand]
  (cond-> {:id (:id strand)
           :title (:title strand)
           :state (:state strand)}
    (attr-value strand :owner) (assoc :owner (attr-value strand :owner))
    (attr-value strand :body) (assoc :body (attr-value strand :body))))

(defn- tasks-with-status
  "Return compact tasks decorated with their derived status and newest note.

  Batches the `depends-on` frontier and the incoming `notes` reads: one edge
  lookup across every task, one state lookup across every dependency, one
  note sweep — so the projection derives without a per-task round trip.
  `:latest-note` (compact, text-clipped) is the doing-task resume read; tasks
  with no notes simply omit it."
  [rt tasks]
  (let [dep-edges (graph/outgoing-edges rt (mapv :id tasks) "depends-on")
        target-state (into {}
                           (map (juxt :id :state))
                           (graph/strands-by-ids rt (into [] (map :to_strand_id) dep-edges)))
        deps-by-task (reduce (fn [m {:keys [from_strand_id to_strand_id]}]
                               (update m from_strand_id (fnil conj []) to_strand_id))
                             {} dep-edges)
        latest-note (latest-notes-by-target rt (mapv :id tasks))]
    (mapv (fn [task]
            (cond-> (assoc (compact-task task)
                           :status (derive-task-status
                                    task
                                    (map target-state (get deps-by-task (:id task)))))
              (latest-note (:id task)) (assoc :latest-note (latest-note (:id task)))))
          tasks)))

(defn task-add!
  "Create a task strand under a feature card via a `parent-of` edge.

  `--depends-on <id>` is repeatable and lays the same `depends-on` edges that
  are the concurrency DAG and drive the derived `blocked`/`ready` split; task
  status is never stored."
  [feature-id title flags]
  (let [feature (feature-strand (require-non-blank! :feature feature-id))
        title (require-non-blank! :title title)
        rt (current/runtime)
        deps (get flags "--depends-on")
        task (weaver/add! rt {:title title
                              :attributes (cond-> {task-attr "true"
                                                   :kind "task"}
                                            (get flags "--body") (assoc :body (get flags "--body")))})]
    (weaver/update! rt (:id feature) {:edges [{:type "parent-of" :to (:id task)}]})
    (when (seq deps)
      (weaver/update! rt (:id task) {:edges (mapv (fn [dep] {:type "depends-on" :to dep}) deps)}))
    {:operation "kanban task add"
     :feature (:id feature)
     :task (entity-projection (weaver/show rt (:id task)))}))

(defn task-list
  "Project a feature card's tasks with their derived statuses."
  [feature-id]
  (let [rt (current/runtime)
        feature (feature-strand (require-non-blank! :feature feature-id))]
    {:operation "kanban task list"
     :feature (:id feature)
     :tasks (tasks-with-status rt (feature-tasks rt (:id feature)))}))

(defn task-op
  "Dispatch a parsed `kanban task ...` action, failing loudly on an unknown one."
  [{:keys [action feature title]} flags]
  (case action
    "add" (task-add! feature (str/join " " title) flags)
    "list" (task-list feature)
    (throw (ex-info "kanban task action must be add or list"
                    {:action action :allowed ["add" "list"]}))))

;; ---------------------------------------------------------------------------
;; notes
;; ---------------------------------------------------------------------------

(defn- note-target
  "Return id's kanban card or task strand, failing loudly for anything else.

  Notes target the work tier only: progress notes belong on the doing-task
  (the resume read) and card notes stay a lean handover trail. Any other
  strand is a wrong target."
  [id]
  (let [strand (or (weaver/show (current/runtime) id)
                   (throw (ex-info "Kanban strand not found" {:id id})))]
    (when-not (or (= "true" (attr-value strand card-attr))
                  (= "true" (attr-value strand task-attr)))
      (throw (ex-info "kanban note target must be a kanban card or task"
                      {:id id :attributes (:attributes strand)})))
    strand))

(defn- owning-card
  "Return the kanban card that parents task-strand, or nil when unparented."
  [rt task-strand]
  (let [parent-ids (mapv :from_strand_id
                         (graph/incoming-edges rt [(:id task-strand)] "parent-of"))]
    (->> (graph/strands-by-ids rt parent-ids)
         (filter #(= "true" (attr-value % card-attr)))
         first)))

(defn note!
  "Append a note to a card or task via the blessed notes relation.

  The note rides the shared `notes` edge (`skein.api.notes.alpha/note!`) with
  optional inherited `note/by` attribution and the kanban-owned
  `note/kind` view hint, so concurrent agents never race a
  read-merge-write cycle and every note keeps its own timestamp and attribution. Note the doing-task as you go — that is
  what `kanban card <id>` surfaces as each task's `:latest-note` — and keep
  card notes to lean handover summaries. `--kind` stamps the open `note/kind`
  view hint (blessed values: activity, decision, review-dump, summary). A
  task note reports its owning card alongside the task when one parents it."
  [id text flags]
  (let [target (note-target (require-non-blank! :id id))
        text (require-non-blank! :text text)
        rt (current/runtime)
        decorating (cond-> {}
                     (get flags "--by") (assoc :by (get flags "--by"))
                     (get flags "--kind") (assoc note-kind-attr
                                                 (require-non-blank! :kind
                                                                     (get flags "--kind"))))
        {note-id :id} (notes/note! rt (:id target) text decorating)
        note (weaver/show rt note-id)
        result {:operation "kanban note"
                :strand (entity-projection note)}]
    (if (= "true" (attr-value target card-attr))
      (assoc result :card (:id target))
      (let [card (owning-card rt target)]
        (cond-> (assoc result :task (:id target))
          card (assoc :card (:id card)))))))

(defn- summarize-strand
  "Return the compact strand shape used in card subtree output."
  [strand]
  (entity-projection strand))

(defn- note-strand?
  "Return true when strand carries the blessed note primitive's text."
  [strand]
  (some? (attr-value strand :note/text)))

(defn- truthy-attr?
  "Return true for a JSON-decoded boolean true or its string form."
  [v]
  (or (true? v) (= "true" v)))

(defn- review-item?
  "Return true when strand marks itself for human review.

  Any of bare hitl (boolean true or \"true\"), workflow/checkpoint-kind
  \"human\", or kind \"review\"."
  [strand]
  (or (truthy-attr? (attr-value strand :hitl))
      (= "human" (attr-value strand :workflow/checkpoint-kind))
      (= "review" (attr-value strand :kind))))

(defn- card-relations
  "Return depends-on relations touching card-id, sorted by other-endpoint id.

  Roots the subgraph at every strand id because depends-on expansion only
  walks outgoing edges: rooting at the card (or even all cards) never yields
  edges whose dependent is an unrelated strand, so incoming edges from
  non-card work would be dropped. The full root set keeps every edge incident
  to the card visible, both directions, any strand, any state."
  [rt card-id]
  (let [all-ids (mapv :id (weaver/list rt))
        {:keys [strands edges]} (graph/subgraph rt all-ids {:type "depends-on"})
        by-id (into {} (map (juxt :id identity)) strands)]
    (->> edges
         (keep (fn [{:keys [from_strand_id to_strand_id]}]
                 (cond
                   (= card-id from_strand_id) [to_strand_id "depends-on"]
                   (= card-id to_strand_id) [from_strand_id "depended-on-by"]
                   :else nil)))
         (sort-by first)
         (mapv (fn [[other relation]]
                 {:relation relation :strand (summarize-strand (by-id other))})))))

(defn- card-subtree
  "Return the card's notes and its parent-of work strands.

  Notes source from the card's incoming `notes` edges (the blessed note
  relation), newest first; work is the card's `parent-of` subgraph. Notes and
  tasks both ride `parent-of` but own their own projections (`:notes` and the
  derived-status `:tasks` lane), so both are split out of the generic work set
  — task status has one source of truth in `:tasks`."
  [rt card]
  (let [note-ids (mapv :from_strand_id (graph/incoming-edges rt [(:id card)] "notes"))
        notes (->> (graph/strands-by-ids rt note-ids)
                   (sort-by (juxt #(attr-value % :note/at) :created_at :id))
                   reverse
                   vec)
        {:keys [strands]} (graph/subgraph rt [(:id card)] {:type "parent-of"})
        work (->> strands
                  (remove #(= (:id card) (:id %)))
                  (remove note-strand?)
                  (remove task-strand?)
                  (sort-by :id)
                  vec)]
    {:notes notes :work work}))

;; ---------------------------------------------------------------------------
;; tracker seam: the run projection joined into card view
;;
;; Kanban core carries no tracker dependency. Trusted config binds a run-tracker
;; strategy for the weaver lifetime with `set-tracker!` (mirroring chime's
;; `set-notifier!`), and `card-view` joins the bound strategy's projection for
;; cards stamped with a run id. See RFC-022.D1.
;; ---------------------------------------------------------------------------

(s/def ::tracker-name non-blank-string?)
(s/def ::tracker-project
  #(or (fn? %) (and (symbol? %) (namespace %))))
(s/def ::tracker-binding
  (s/and map?
         #(known-keys? #{:name :project} %)
         #(s/valid? ::tracker-name (:name %))
         #(s/valid? ::tracker-project (:project %))))
(s/def ::tracker-status (s/nilable string?))
(s/def ::tracker-step
  (s/and map?
         #(s/valid? ::non-blank-string (:id %))
         #(s/valid? ::non-blank-string (:title %))
         #(s/valid? ::non-blank-string (:role %))))
(s/def ::tracker-steps (s/coll-of ::tracker-step :kind vector?))
(s/def ::tracker-projection
  (s/and map?
         #(known-keys? #{:status :ready} %)
         #(contains? % :status)
         #(contains? % :ready)
         #(s/valid? ::tracker-status (:status %))
         #(s/valid? ::tracker-steps (:ready %))))
(s/def ::tracker-view
  (s/and map?
         #(known-keys? #{:name :run-id :status :ready} %)
         #(contains? % :name)
         #(contains? % :run-id)
         #(contains? % :status)
         #(contains? % :ready)
         #(or (nil? (:name %)) (s/valid? ::tracker-name (:name %)))
         #(s/valid? ::non-blank-string (:run-id %))
         #(s/valid? ::tracker-status (:status %))
         #(s/valid? ::tracker-steps (:ready %))))

(def ^:private state-version
  "Shape version for kanban's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives `reload!`, so a post-upgrade reload would
  otherwise reuse a preserved map missing the new key (docs/spools/writing-shared-spools.md
  'Versioned spool state', SPEC-004.C95)."
  1)

(defn- new-state []
  {:tracker-binding (atom nil)})

(defn- state []
  (runtime/spool-state (current/runtime) ::state {:version state-version} new-state))

(defn- tracker-binding [] (:tracker-binding (state)))

(defn- validate-tracker!
  "Return tracker when it satisfies `::tracker-binding`, failing loudly otherwise.

  Mirrors chime's `validate-notifier!`; the manual checks retain concise
  boundary errors while the owning spec remains the complete shape contract."
  [tracker]
  (when-not (map? tracker)
    (fail! "Tracker binding must be a map" {:binding tracker}))
  (reject-unknown-keys! "kanban set-tracker!" #{:name :project} tracker)
  (when-not (non-blank-string? (:name tracker))
    (fail! "Tracker :name must be a non-blank string" {:name (:name tracker)}))
  (let [project (:project tracker)]
    (when-not (or (fn? project) (and (symbol? project) (namespace project)))
      (fail! "Tracker :project must be a fully-qualified symbol or a function"
             {:project project})))
  (when-not (s/valid? ::tracker-binding tracker)
    (fail! "Tracker binding does not match its owning spec"
           {:binding tracker
            :spec ::tracker-binding
            :explain (s/explain-data ::tracker-binding tracker)}))
  tracker)

(defn set-tracker!
  "Bind the run-tracker strategy for this weaver lifetime.

  The binding is `{:name <non-blank-string> :project <fq-symbol-or-fn>}`. `:name`
  surfaces in `about` and the card view so a cold agent knows which convention
  the projected steps follow; `:project` is `(fn [run-id] -> {:status <string|nil>
  :ready [step ...]})`, resolved with `requiring-resolve` at call time when a
  symbol so a config reload rebinds cleanly. Rebinding replaces the prior value;
  pass a valid binding after every weaver startup or config reload. `install!`
  never binds a default. The binding is validated against `::tracker-binding`."
  [tracker]
  (reset! (tracker-binding) (validate-tracker! tracker))
  {:tracker @(tracker-binding)})

(def ^:private tracker-step-keys
  "Closed key set kanban projects from each tracker step, keeping the card-view
  shape kanban-owned regardless of the bound strategy's richer step maps."
  [:id :title :role :stage :checkpoint])

(defn- resolve-project
  "Resolve a tracker binding's :project to a callable fn at call time.

  A fn is used as-is; a fully-qualified symbol is resolved with
  `requiring-resolve` so a config reload rebinds cleanly."
  [project]
  (if (fn? project)
    project
    (or (requiring-resolve project)
        (fail! "Tracker :project symbol cannot be resolved" {:project project}))))

(defn- card-run-id
  "Return the `kanban/run-id` stamped on a card, or nil for unstamped cards."
  [card]
  (attr-value card run-id-attr))

(defn- validate-projection!
  "Return projection when it satisfies `::tracker-projection`.

  The tracker name and run id travel with failures so a repo owner can identify
  the bad trusted-config strategy without reconstructing the card view call."
  [binding run projection]
  (when-not (s/valid? ::tracker-projection projection)
    (fail! "Tracker projection does not match its owning spec"
           {:tracker (:name binding)
            :run-id run
            :projection projection
            :spec ::tracker-projection
            :allowed {:keys [:status :ready]
                      :status "string or nil"
                      :ready "vector of maps"}
            :explain (s/explain-data ::tracker-projection projection)}))
  projection)

(defn- validate-tracker-view!
  "Return view when it satisfies the public `::tracker-view` contract."
  [card view]
  (when-not (s/valid? ::tracker-view view)
    (fail! "Tracker view does not match its owning spec"
           {:card (:id card)
            :view view
            :spec ::tracker-view
            :allowed {:keys [:name :run-id :status :ready]
                      :name "non-blank string or nil"
                      :run-id "non-blank string"
                      :status "string or nil"
                      :ready "vector of maps with non-blank :id, :title, and :role"}
            :explain (s/explain-data ::tracker-view view)}))
  view)

(defn- tracker-join
  "Return the card's tracker run projection, or nil for unstamped cards.

  The card names its run through `kanban/run-id`. With a tracker bound
  (`set-tracker!`), the bound strategy projects the run's status and ready
  steps, and kanban trims each step to its own closed
  key set so the card-view shape stays kanban-owned (RFC-022.G3); a tracker that
  reports no active run projects an honest nil status with no steps. A stamped
  card in a world with no binding projects as `{:name nil ...}` — the stamp
  visible, the missing strategy visible — rather than hiding the key. A throwing
  strategy propagates: the binding is repo-owner config, and masking its failure
  would violate TEN-003. The strategy result is validated against
  `::tracker-projection`; the returned public shape is `::tracker-view`."
  [card]
  (when-let [run (card-run-id card)]
    (validate-tracker-view!
     card
     (if-let [binding @(tracker-binding)]
       (let [projection (validate-projection!
                         binding run ((resolve-project (:project binding)) run))]
         {:name (:name binding)
          :run-id run
          :status (:status projection)
          :ready (mapv #(select-keys % tracker-step-keys) (:ready projection))})
       {:name nil :run-id run :status nil :ready []}))))

(defn card-view
  "Return one card joined to its notes, tasks, work, and frontier.

  This is the resume entry point: everything an agent needs to continue a
  card lives here. `:tasks` projects the feature card's child tasks with the
  four derived statuses (empty for cards that carry no task tier), and
  `:tracker` joins the bound tracker's run status and ready steps for cards
  stamped with `kanban/run-id` (see `tracker-join`)."
  [id]
  (let [rt (current/runtime)
        card (card-strand (require-non-blank! :id id))
        {:keys [notes work]} (card-subtree rt card)
        active-work (filterv #(= "active" (:state %)) work)
        work-ids (set (map :id active-work))
        ready (filterv #(contains? work-ids (:id %)) (weaver/ready rt))
        tracker (tracker-join card)]
    (cond-> {:operation "kanban card"
             :card (select-keys card [:id :title :state :attributes :created_at :updated_at])
             :tasks (tasks-with-status rt (feature-tasks rt (:id card)))
             :notes (mapv compact-note notes)
             :active-work (mapv summarize-strand active-work)
             :ready (mapv summarize-strand ready)
             :related (card-relations rt (:id card))}
      tracker (assoc :tracker tracker))))

;; ---------------------------------------------------------------------------
;; board
;; ---------------------------------------------------------------------------

(defn- cards
  "Return all kanban card strands."
  []
  (weaver/list (current/runtime) [:= [:attr "kanban/card"] "true"] {}))

(defn- by-created
  "Return strands sorted oldest first."
  [strands]
  (sort-by (juxt :created_at :id) strands))

(defn- by-priority
  "Return strands sorted p1 first, oldest first within a priority."
  [strands]
  (sort-by (juxt card-priority :created_at :id) strands))

(defn next-card
  "Return the highest-priority (p1 first) oldest active pending feature card, or nil."
  []
  (some->> (cards)
           (filter #(and (= "active" (:state %))
                         (= "pending" (attr-value % lane-attr))
                         (= "feature" (card-type %))))
           by-priority
           first
           compact-card))

(defn- epic-membership
  "Return {feature-card-id epic-id} for direct features under active epics."
  [rt epics]
  (into {}
        (mapcat (fn [epic]
                  (let [{:keys [edges]} (graph/subgraph rt [(:id epic)] {:type "parent-of"})]
                    (->> edges
                         (filter #(= (:id epic) (:from_strand_id %)))
                         (map (fn [edge] [(:to_strand_id edge) (:id epic)]))))))
        epics))

(defn- doing-task-for
  "Return the compact derived-`doing` task for a card, or nil.

  The doing task is the board's live resume signal: the first active,
  deps-met, owned task under the feature card."
  [rt card]
  (some->> (tasks-with-status rt (feature-tasks rt (:id card)))
           (filter #(= "doing" (:status %)))
           first))

(defn- needs-review-entries
  "Return review-frontier entries across review-relevant feature cards.

  An entry qualifies when a claimed or in-review card descendant is active, in
  the engine ready frontier, and marks human review. Sorted by card id then item
  id."
  [rt review-relevant-features]
  (let [ready-ids (set (map :id (weaver/ready rt)))]
    (->> review-relevant-features
         (mapcat (fn [card]
                   (let [{:keys [work]} (card-subtree rt card)
                         branch (attr-value card :branch)]
                     (->> work
                          (filter #(and (= "active" (:state %))
                                        (contains? ready-ids (:id %))
                                        (review-item? %)))
                          (map (fn [item]
                                 (cond-> {:card (:id card) :item (summarize-strand item)}
                                   branch (assoc :branch branch))))))))
         (sort-by (juxt :card #(get-in % [:item :id])))
         vec)))

(defn board
  "Return the grouped board snapshot: epics, feature lanes, closed count.

  Claimed and in-review cards carry their doing-task so a cold agent can see in
  one call who is working where and how to pick up interrupted work.
  `:needs-review` aggregates the human-review frontier across claimed and
  in-review cards."
  []
  (let [rt (current/runtime)
        all (cards)
        active (filter #(= "active" (:state %)) all)
        epics (filterv #(= "epic" (card-type %)) active)
        features (remove #(= "epic" (card-type %)) active)
        claimed-features (filter #(= "claimed" (attr-value % lane-attr)) features)
        review-features (filter #(= "in_review" (attr-value % lane-attr)) features)
        membership (epic-membership rt epics)
        with-epic (fn [card]
                    (cond-> (compact-card card)
                      (membership (:id card)) (assoc :epic (membership (:id card)))))
        lane (fn [lane-name]
               (->> features
                    (filter #(= lane-name (attr-value % lane-attr)))
                    by-priority
                    (mapv with-epic)))
        known-lanes active-lanes
        unknown (->> features
                     (remove #(contains? known-lanes (attr-value % lane-attr)))
                     by-created
                     (mapv with-epic))]
    (cond-> {:operation "kanban board"
             :epics (mapv compact-card (by-created epics))
             :refinement (lane "refinement")
             :pending (lane "pending")
             :claimed (mapv (fn [card]
                              (cond-> (with-epic card)
                                (doing-task-for rt card)
                                (assoc :doing-task (doing-task-for rt card))))
                            (by-priority claimed-features))
             :in_review (mapv (fn [card]
                                (cond-> (with-epic card)
                                  (doing-task-for rt card)
                                  (assoc :doing-task (doing-task-for rt card))))
                              (by-priority review-features))
             :needs-review (needs-review-entries rt (concat claimed-features review-features))
             :closed {:count (count (filter #(= "closed" (:state %)) all))}}
      ;; active cards outside the known lanes are drift; surface them loudly
      (seq unknown) (assoc :unknown-lane unknown))))

;; ---------------------------------------------------------------------------
;; ASCII board: REPL human view (the CLI stays JSON-only per TEN-006)
;; ---------------------------------------------------------------------------

(def ^:private board-width 100)

(defn- clip
  "Return s truncated with an ellipsis to fit within n characters."
  [n s]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 (- n 3)) "...") s)))

(defn- card-line
  "Return one ASCII board row for a compact card map."
  [{:keys [id title owner branch epic priority]}]
  (let [tags (cond-> []
               priority (conj priority)
               branch (conj (str "@" branch))
               owner (conj owner)
               epic (conj (str "epic:" epic)))
        prefix (str "  " id "  " (when (seq tags) (str "[" (str/join " " tags) "] ")))]
    (str prefix (clip (- board-width (count prefix)) title))))

(defn- lane-lines
  "Return the ASCII section for one board lane."
  [label entries row-fn]
  (into [(str label " (" (count entries) ")")]
        (if (seq entries)
          (mapv row-fn entries)
          ["  (none)"])))

(defn- doing-task-line
  "Return the indented doing-task row for a claimed/in-review card, or nil."
  [{:keys [doing-task]}]
  (when doing-task
    (str "         " (clip (- board-width 9)
                           (str "doing: " (:title doing-task))))))

(defn- wip-row
  "Return the ASCII rows for a claimed/in-review card: the card line plus its
  doing-task signal line when present."
  [card]
  (->> [(card-line card) (doing-task-line card)]
       (remove nil?)
       (str/join "\n")))

(defn- review-line
  "Return one ASCII row for a needs-review entry."
  [{:keys [card branch item]}]
  (let [prefix (str "  " (:id item) "  [card " card (when branch (str " @" branch)) "] ")]
    (str prefix (clip (- board-width (count prefix)) (:title item)))))

(defn board-str
  "Render a `board` result map as a stacked-lane ASCII board string."
  [{:keys [epics refinement pending claimed in_review needs-review closed unknown-lane]}]
  (let [rule (str/join (repeat board-width \=))]
    (->> (concat
          [(str "KANBAN BOARD  (closed: " (:count closed) ")") rule]
          (lane-lines "EPICS" epics card-line)
          [""]
          (lane-lines "REFINEMENT" refinement card-line)
          [""]
          (lane-lines "PENDING" pending card-line)
          [""]
          (lane-lines "CLAIMED / WIP" claimed wip-row)
          [""]
          (lane-lines "IN REVIEW" in_review wip-row)
          [""]
          (lane-lines "NEEDS REVIEW" needs-review review-line)
          (when (seq unknown-lane)
            (into [""] (lane-lines "UNKNOWN LANE (drift!)" unknown-lane card-line))))
         (str/join "\n"))))

(defn print-board!
  "Print the live board as ASCII; the human view for `mill weaver repl`."
  []
  (println (board-str (board))))

(defn about
  "Return the kanban convention and installed helper surface."
  []
  {:summary "Kanban cards are the user<->agent work board; agents working directly with a user work under a claimed card."
   :lanes {:refinement "not actionable until an explicit human `kanban promote`"
           :pending "actionable queue; `kanban next` serves the highest-priority (p1 first) oldest feature"
           :claimed "work started; owner/branch (and worktree) stamped at claim"
           :in_review "work is under review; rework returns it to claimed, finish closes it"
           :closed "finished with kanban/outcome recording the result (done, abandoned, ...)"}
   :priorities {:p1 "immediate blocker; must be done first — e.g. anything requiring a mill/weaver restart or a breaking change"
                :p2 "high value bug fixes or high leverage features"
                :p3 "the default: most things"
                :p4 "maybe one day — the never-ending someday list"}
   :attributes {card-attr "true"
                type-attr "feature (default) | epic (grouping; parent-of its features)"
                lane-attr "refinement|pending|claimed|in_review (active cards only)"
                outcome-attr "explicit finish outcome on closed cards (done|abandoned)"
                restore-lane-attr "reversibility marker: lane an abandon cascade closed a card from, so `kanban reopen` restores exactly what it closed"
                priority-attr "p1|p2|p3|p4 (default p3); orders lanes and `kanban next`"
                task-attr "true on task strands (parent-of children of a feature card; status derived)"
                run-id-attr "optional tracker run-id; `kanban card` joins the bound tracker's status and ready steps"
                :kanban/source "optional path or URL for design context"
                :owner "claimant, required at claim"
                :branch "work branch, required at claim"
                :worktree "optional worktree path"}
   :tracker (if-let [bound @(tracker-binding)]
              (str "Bound tracker: " (:name bound)
                   ". `kanban card` joins the run's status and ready steps for cards stamped kanban/run-id.")
              (str "No tracker bound. Cards stamped kanban/run-id project honestly as unbound (name nil) "
                   "until trusted config binds one with set-tracker!."))
   :convention (fmt/reflow "
                 |The card is the work root: claim stamps owner/branch, and execution strands hang
                 |under it with parent-of. Kanban complements the engines that produce them; it never
                 |tracks their runs directly.")
   :note-discipline (fmt/reflow "
                      |Note as you go, on the doing-TASK: `kanban note <task-id> \"...\" --by
                      |<name> [--kind activity|decision|review-dump|summary]` records each
                      |significant decision, step, and gotcha while the work is fresh, and
                      |surfaces as that task's `latest-note`. Card notes stay lean handover
                      |summaries; bulk content (review findings, pasted output) belongs on a task
                      |note via `strand --stdin kanban note <task-id> :stdin --by <name> --kind
                      |review-dump` — views clip note text past a cap. A cold agent resumes from
                      |the doing-task and its `latest-note` via `kanban board` -> `kanban card
                      |<id>`.")
   :discovery {:help "strand help kanban"
               :prime "strand kanban prime"
               :batch-pattern "strand pattern explain kanban-batch"}
   :commands [{:verb "about" :purpose "Return the kanban convention and installed helper surface."}
              {:verb "prime" :purpose "Full agent priming: working discipline plus command surface."}
              {:verb "add" :purpose "Create a feature or epic card."}
              {:verb "board" :purpose "Return the grouped board snapshot."}
              {:verb "card" :purpose "Show one card with notes, active work, related cards, and ready frontier."}
              {:verb "next" :purpose "Return the next pending feature card by priority and age."}
              {:verb "priority" :purpose "Change a card's p1..p4 ordering priority."}
              {:verb "promote" :purpose "Move a refinement card into the pending lane."}
              {:verb "claim" :purpose "Move a card into claimed and stamp owner/branch/worktree."}
              {:verb "note" :purpose "Append an immutable note to a card or task (the doing-task is the resume read)."}
              {:verb "task" :purpose "Add or list a feature card's tasks with their derived statuses."}
              {:verb "review" :purpose "Move a claimed card into in_review."}
              {:verb "rework" :purpose "Move an in_review card back to claimed."}
              {:verb "finish" :purpose "Close a card with an explicit outcome (feature from claimed/in_review; epic from refinement/pending — done requires closed children, abandoned cascades reversibly)."}
              {:verb "reopen" :purpose "Reopen an abandoned epic, reversing exactly the cascade the matching abandon closed."}
              {:repl "ct.spools.kanban/print-board!" :purpose "ASCII board from mill weaver repl; CLI output stays JSON-only."}]
   :patterns [{:name "kanban-batch"
               :input {:items [{:key "slug"
                                :title "Feature title"
                                :body "optional body"
                                :priority "optional p1|p2|p3|p4 (default p3)"
                                :depends-on ["sibling-key-or-existing-strand-id"]}]}}]})

(defn prime
  "Return the full agent-priming payload for working the kanban board.

  The single source of truth for kanban usage discipline: repo agent docs
  point here (`strand kanban prime`) rather than duplicating conventions that
  then drift from the spool. A superset of `about` — it reuses the same lane,
  attribute, command, and pattern surface and adds the working agreement,
  pick-up flow, note discipline, adjacent-work awareness, and branch
  visibility that an agent needs before touching the board."
  []
  (assoc (about)
         :working-agreement
         (fmt/fill "
               |Every user request is a feature card; occasionally group related cards under an
               |`epic` (`--type epic`, link features with `--epic <id>`).
               |
               |Every agent working directly with the user works under a claimed card — claim
               |before starting user work.
               |
               |Execution strands hang beneath a card via `parent-of` — kanban complements the
               |engines that produce them, it never tracks their runs directly.
               |
               |Half-formed ideas go to the refinement lane (`kanban add \"...\" --lane
               |refinement`); they stay inert until a human `kanban promote`s them.")
         :pick-up-next-card
         (fmt/fill "
               |`kanban next` serves the highest-priority pending feature card (p1 first, oldest
               |first within a priority). p1 is an immediate blocker and must be done before
               |anything else; reprioritise with `kanban priority <id> <p1|p2|p3|p4>`.
               |
               |Claim it: `kanban claim <id> --owner <name> --branch <branch> [--worktree
               |<path>]` — owner and branch are mandatory; the claim is what makes branch work
               |discoverable.
               |
               |Decompose the feature into tasks before working: `kanban task add <feature>
               |\"<title>\" [--depends-on <id>]` slices the work into the derived-status DAG,
               |and the task you are driving is the board's doing-task signal. Other
               |execution strands still hang under the card via `parent-of` — the card is the
               |parent/audit root, the tasks are the driveable slices.
               |
               |`kanban review <id>` when work enters review, `kanban rework <id>` when it
               |needs changes, and `kanban finish <id> [--outcome done|abandoned]` after
               |merge, archive, or explicit abandonment.")
         :note-discipline
         (fmt/fill "
               |Note as you go, on the doing-TASK: `kanban note <task-id> \"...\" --by
               |<name>` records each significant decision, step, and gotcha while the work is
               |fresh — do not save it for a stopping point. Task notes surface as that task's
               |`latest-note`; the card's own note trail stays a lean handover summary.
               |
               |Bulk content never goes on the card: review findings, pasted command output,
               |and long dumps belong on a task note via `strand --stdin kanban note <task-id>
               |:stdin --by <name> --kind review-dump`. Other view hints are
               |activity|decision|summary. Views clip note text past a cap; `strand show
               |<note-id>` returns the full text.
               |
               |Tasks are the resume point. A cold agent resumes from the doing-task and its
               |`latest-note`: `kanban board` shows claimed and in-review cards with their
               |doing-task; `kanban card <id>` returns the card, its tasks (each carrying its
               |`latest-note`), notes, active work, and ready frontier. Even with no notes
               |yet, the doing-task's body, dependencies, and lane name the next move.")
         :staying-aware
         (fmt/fill "
               |`kanban board` returns `needs-review`: the human-review frontier aggregated
               |across claimed and in-review cards (ready hitl/review work grouped by card and
               |branch).
               |
               |Inside a feature branch, `strand branches \"$(git branch --show-current)\"`
               |shows the feature cards worked on there and their substrands.
               |
               |Relate adjacent work with `depends-on` edges (`strand update <a> --edge
               |depends-on:<b>`) and check `related` in `kanban card <id>` when claiming or
               |resuming, so blockers and dependents surface.")
         :branch-visibility
         (fmt/reflow "
               |Every piece of work on a branch has exactly one active work root strand stamped
               |`branch` (plus `owner`, and `worktree` when one exists), with execution strands
               |beneath it via `parent-of`. `kanban claim` stamps card roots; for non-card roots
               |(ad hoc execution roots, coordination strands) stamp them yourself: `strand
               |update <root-id> --attr branch=<branch> --attr owner=<name>`. Children are
               |reachable from the root and need no `branch` attr of their own.")))

(def ^:private kanban-arg-spec
  "Declared command surface for the `kanban` op."
  {:op "kanban"
   :doc "Manage the user-facing kanban work board. Run `strand kanban about` for the convention manual."
   :subcommands
   {"about" {:doc "Return the kanban convention and installed helper surface."}
    "prime" {:doc "Return full agent priming for working the kanban board."}
    "add" {:doc "Create a feature or epic card."
           :flags {:body {:doc "Longer card context."}
                   :source {:doc "Path or URL for design context."}
                   :lane {:doc "Initial lane: pending or refinement."}
                   :type {:doc "Card type: feature or epic."}
                   :epic {:doc "Existing epic card id to parent this feature under."}
                   :priority {:doc "Priority p1|p2|p3|p4; defaults to p3."}}
           :positionals [{:name :title
                          :required? true
                          :variadic? true
                          :doc "Card title words."}]}
    "board" {:doc "Return the grouped board snapshot."}
    "card" {:doc "Return one card's resume view."
            :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "next" {:doc "Return the highest-priority (p1 first) oldest active pending feature card."}
    "priority" {:doc "Set an active card's priority (p1 immediate blocker .. p4 someday)."
                :positionals [{:name :id :required? true :doc "Kanban card id."}
                              {:name :priority :required? true :doc "Priority: p1, p2, p3, or p4."}]}
    "promote" {:doc "Move a refinement card into the pending lane."
               :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "claim" {:doc "Claim a pending feature card."
             :flags {:owner {:doc "Claimant name (required by handler)."}
                     :branch {:doc "Work branch (required by handler)."}
                     :worktree {:doc "Optional worktree path."}
                     :run-id {:doc "Optional tracker run-id joined by `kanban card` (stamps kanban/run-id)."}}
             :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "note" {:doc "Append a note to a card or task; note the doing-task as you go."
            :flags {:by {:doc "Note attribution."}
                    :kind {:doc "Open note/kind view hint: activity, decision, review-dump, summary."}}
            :positionals [{:name :id :required? true :doc "Kanban card or task id."}
                          {:name :text
                           :required? true
                           :variadic? true
                           :doc "Note text words."}]}
    "task" {:doc "Manage a feature card's tasks: `add <feature> <title...>` or `list <feature>`."
            :flags {:body {:doc "Longer task context (add only)."}
                    :depends-on {:repeat? true
                                 :doc "Task/strand id this task depends on (repeatable; add only)."}}
            :positionals [{:name :action :required? true :doc "Task action: add or list."}
                          {:name :feature :required? true :doc "Feature card id the tasks hang under."}
                          {:name :title
                           :variadic? true
                           :doc "Task title words (add only)."}]}
    "review" {:doc "Move a claimed card into the in_review lane."
              :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "rework" {:doc "Move an in_review card back to claimed for rework."
              :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "finish" {:doc "Close a card with an explicit outcome. Features close from claimed/in_review; epics close from refinement/pending (done requires closed feature children, abandoned cascades reversibly)."
              :flags {:outcome {:doc "Closed outcome; defaults to done. For an epic: done|abandoned."}}
              :positionals [{:name :id :required? true :doc "Kanban card id."}]}
    "reopen" {:doc "Reopen an abandoned epic, reversing exactly the cascade the matching abandon closed."
              :positionals [{:name :id :required? true :doc "Abandoned epic card id."}]}}})

(defn- legacy-flags
  "Return parsed keyword flags in the string-keyed shape expected by handlers."
  [args]
  (into {}
        (keep (fn [[k v]]
                (when (and (not= k :subcommand)
                           (some? v)
                           (not (contains? #{:id :title :text :action :feature} k)))
                  [(str "--" (name k)) v])))
        args))

(defn kanban-op
  "Dispatch parsed `strand kanban ...` subcommands."
  [{:op/keys [args]}]
  (let [flags (legacy-flags args)]
    (case (:subcommand args)
      "about" (about)
      "prime" (prime)
      "add" (add! (str/join " " (:title args)) flags)
      "board" (board)
      "card" (card-view (:id args))
      "next" {:operation "kanban next" :next (next-card)}
      "priority" (set-priority! (:id args) (:priority args))
      "promote" (promote! (:id args))
      "claim" (claim! (:id args) flags)
      "task" (task-op args flags)
      "review" (review! (:id args))
      "rework" (rework! (:id args))
      "note" (note! (:id args) (str/join " " (:text args)) flags)
      "finish" (finish! (:id args) flags)
      "reopen" (reopen! (:id args)))))

;; ---------------------------------------------------------------------------
;; kanban-export: a card's full parent-of subtree for offline rendering
;; ---------------------------------------------------------------------------

(defn- export-strand
  "Compact strand shape for the export payload, timestamps included.

  Unlike loom's active-only `summarize`, this keeps closed strands and their
  created/updated stamps so a consumer can show completed work and age."
  [strand]
  (select-keys strand [:id :title :state :attributes :created_at :updated_at]))

(defn- internal-edges
  "Return edges whose endpoints both sit in id-set, projected and sorted.

  Subgraph expansion walks outward to strands beyond the subtree, so edges are
  filtered against the subtree's own id set to keep the projection
  self-contained (mirrors loom's internal-edge discipline)."
  [id-set edges]
  (->> edges
       (filter #(and (contains? id-set (:from_strand_id %))
                     (contains? id-set (:to_strand_id %))))
       (sort-by (juxt :from_strand_id :to_strand_id :edge_type))
       (mapv #(select-keys % [:from_strand_id :to_strand_id :edge_type]))))

(defn kanban-export-op
  "Handle `strand kanban-export <card-id>`: a card's full parent-of subtree
  with its internal depends-on edges.

  Given a feature or epic card id, returns the root, every strand beneath it via
  parent-of (all lifecycle states, so completed work still counts toward
  progress), the parent-of hierarchy edges, and the depends-on edges internal to
  the subtree. It is a read-only graph projection: presentation and the progress
  rollup live in the consumer (this spool's scripts/kanban-export). The existing
  `subgraph` op walks one relation at a time, so this op exists to bundle the
  hierarchy and its dependencies in a single call. Fails loudly when the id is
  unknown or names a strand that is not a kanban card."
  [ctx]
  (let [{:keys [card-id]} (:op/args ctx)
        rt (current/runtime)
        card (card-strand card-id)
        {:keys [strands edges]} (graph/subgraph rt [(:id card)] {:type "parent-of"})
        id-set (set (map :id strands))
        depends (:edges (graph/subgraph rt (vec id-set) {:type "depends-on"}))]
    {:operation "kanban-export"
     :root-id card-id
     :strands (mapv export-strand strands)
     :parent-of-edges (internal-edges id-set edges)
     :depends-on-edges (internal-edges id-set depends)}))

(def ^:private kanban-export-arg-spec
  "Declared command surface for the `kanban-export` op."
  {:op "kanban-export"
   :doc "Show a card's parent-of subtree with internal depends-on edges."
   :positionals [{:name :card-id
                  :type :string
                  :required? true
                  :doc "Feature or epic card strand id."}]})

(def ^:private kanban-returns
  {:subcommands
   (into {}
         (map (fn [subcommand]
                [subcommand {:type :map
                             :required {:operation :string}
                             :extra :json}]))
         (keys (:subcommands kanban-arg-spec)))})

(def ^:private kanban-export-returns
  {:type :map
   :required {:operation :string
              :root-id :string
              :strands {:type :collection :items :json}
              :parent-of-edges {:type :collection :items :json}
              :depends-on-edges {:type :collection :items :json}}})

(def ^:private kanban-vocab
  {:kind :attr-namespace
   :name "kanban"
   :owner :skein/spools-kanban
   :keys ["kanban/card" "kanban/lane" "kanban/outcome" "kanban/type"
          "kanban/priority" "kanban/source" "kanban/task"
          "kanban/run-id" "kanban/from" "kanban/abandon-restore-lane"]
   :doc "Kanban card state attributes written by ct.spools.kanban/add!."})

(def ^:private kanban-op-options
  {:doc "Manage the user-facing kanban work board. Run `strand kanban about` for the convention manual."
   :arg-spec kanban-arg-spec :returns kanban-returns :hook-class :mutating})

(def ^:private kanban-export-op-options
  {:doc "Return a card's full parent-of subtree with its internal depends-on edges."
   :arg-spec kanban-export-arg-spec :returns kanban-export-returns :hook-class :read})

(defn contribute
  "Return kanban's complete owner contribution for module publication.

  The keys deliberately cover every replaceable board declaration.  A later
  refresh that omits one therefore removes it instead of retaining a stale
  command, query, or pattern.  Vocabulary is runtime state rather than a core
  owner registry, so reconcile owns its idempotent declaration."
  [_ctx]
  {:ops {"kanban" (op-entry/assemble 'kanban kanban-op-options
                                     'ct.spools.kanban/kanban-op)
         "kanban-export" (op-entry/assemble 'kanban-export kanban-export-op-options
                                            'ct.spools.kanban/kanban-export-op)}
   :patterns {"kanban-batch" {:name "kanban-batch"
                              :doc "Create pending feature cards with bodies and depends-on edges."
                              :fn 'ct.spools.kanban/kanban-batch
                              :input-spec ::kanban-batch-input}}
   :queries {"kanban-cards" [:= [:attr "kanban/card"] "true"]
             "kanban-pending" [:and [:= :state "active"]
                               [:= [:attr "kanban/card"] "true"]
                               [:= [:attr "kanban/lane"] "pending"]]}})

(defn reconcile
  "Reconcile kanban's non-registry runtime state around module publication."
  [{:keys [runtime] :as ctx}]
  (if (= :removed (get-in ctx [:module/contribution :status]))
    {:reconciled :removed}
    (do (vocab/declare! runtime kanban-vocab)
        (runtime/spool-state runtime ::state {:version state-version} new-state)
        {:reconciled :applied})))

(defn install!
  "Install kanban directly for legacy trusted config.

  Module config should use `contribute`/`reconcile`; this entry point retains
  the pre-module API for existing workspaces."
  []
  (let [rt (current/runtime)]
    {:installed true :namespace 'ct.spools.kanban
     :vocab (vocab/declare! rt kanban-vocab)
     :ops [(weaver/register-op! rt 'kanban kanban-op-options 'ct.spools.kanban/kanban-op)
           (weaver/register-op! rt 'kanban-export kanban-export-op-options
                                'ct.spools.kanban/kanban-export-op)]
     :pattern (patterns/register-pattern! rt 'kanban-batch
                                          "Create pending feature cards with bodies and depends-on edges."
                                          'ct.spools.kanban/kanban-batch ::kanban-batch-input)
     :queries [(graph/register-query! rt 'kanban-cards [:= [:attr "kanban/card"] "true"])
               (graph/register-query! rt 'kanban-pending
                                      [:and [:= :state "active"]
                                       [:= [:attr "kanban/card"] "true"]
                                       [:= [:attr "kanban/lane"] "pending"]])]}))

(defn install-peering!
  "Register the opt-in `kanban.send.v1` board-peering receive op.

  A separate opt-in entry point wired into trusted config after
  `(skein.spools.guild/install! runtime)` and `(install!)`. Delegates to
  `ct.spools.kanban.peering/install-peering!` via `requiring-resolve` so the base
  kanban spool never load-depends on the guild spool; peering (and its guild
  dependency) load only when a repo opts in."
  []
  ((requiring-resolve 'ct.spools.kanban.peering/install-peering!)))
