(ns ct.spools.kanban.peering
  "Opt-in board peering: the RECEIVE guild op plus the SEND-side local ops.

  A trusted-config module wires `ct.spools.kanban/install-peering!` in after the
  guild spool is installed (`skein.spools.guild/install! runtime`) and the kanban board
  op is registered (`ct.spools.kanban/install!`). That entry point registers
  three ops:

  - `kanban.send.v1` — the guild receive op. A sibling weaver drops a card, or
    an epic bundle, onto this board. Received cards travel the same
    `ct.spools.kanban/add!` code path as local cards, so defaults, lanes, and
    epic `parent-of` wiring are identical; `:from` provenance is stamped as one
    `kanban/from` attribute. Guild parses the op's single JSON argument to a
    keyword-keyed map at `:guild/input`, so `::send-input` specs keyword keys
    throughout.
  - `kanban-peers` — list sibling weavers and, for each running one, whether it
    advertises `kanban.send.v1` (so a caller knows where a card can be sent).
  - `kanban-send` — resolve a local card and mirror the board tier onto a peer's
    board over `kanban.send.v1`.

  The two peering seams onto sibling weavers — enumerate/probe and invoke — go
  through `skein.api.peers.alpha` behind `*list-peers*`, `*list-peer-guild*`, and
  `*send-card*` so classification and payload building are testable without a
  live socket peer."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [skein.api.current.alpha :as current]
            [skein.api.graph.alpha :as graph]
            [skein.api.peers.alpha :as peers]
            [skein.api.weaver.alpha :as weaver]
            [skein.api.weaver.internal.op-entry :as op-entry]
            [skein.api.spool.alpha :refer [attr-get fail!]]
            [ct.spools.kanban :as kanban]))

;; ---------------------------------------------------------------------------
;; ::send-input: closed-key spec for the kanban.send.v1 op input
;; ---------------------------------------------------------------------------

(defn- non-blank-string?
  "Return true when v is a non-blank string."
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- known-keys?
  "Return true when map m contains only allowed keys."
  [allowed m]
  (empty? (remove allowed (keys m))))

(s/def ::non-blank-string non-blank-string?)
(s/def ::title ::non-blank-string)
(s/def ::body ::non-blank-string)
(s/def ::source ::non-blank-string)
(s/def ::priority #{"p1" "p2" "p3" "p4"})
(s/def ::lane #{"pending" "refinement"})

(def ^:private card-keys #{:title :body :source :priority :lane})

(s/def ::card-map
  (s/and map?
         #(known-keys? card-keys %)
         (s/keys :req-un [::title]
                 :opt-un [::body ::source ::priority ::lane])))

(s/def ::card ::card-map)
(s/def ::epic ::card-map)
(s/def ::features (s/coll-of ::card-map :kind vector? :min-count 1))

(s/def ::from
  (s/and map?
         #(known-keys? #{:board :card} %)
         #(non-blank-string? (:board %))
         #(non-blank-string? (:card %))))

(def ^:private send-input-keys #{:card :epic :features :from})

(defn- one-shape?
  "Return true for exactly one of a single :card or an :epic + :features bundle.

  `:features` is only valid alongside `:epic`; a lone `:card` and a lone `:epic`
  (no features) are both rejected here so a caller must pick one whole shape."
  [m]
  (let [card? (contains? m :card)
        epic? (contains? m :epic)
        features? (contains? m :features)]
    (or (and card? (not epic?) (not features?))
        (and epic? features? (not card?)))))

(s/def ::send-input
  (s/and map?
         #(known-keys? send-input-keys %)
         one-shape?
         (s/keys :opt-un [::card ::epic ::features ::from])))

;; ---------------------------------------------------------------------------
;; public op return contracts: the structured shapes kanban-peers and
;; kanban-send hand back. Registry `:items :json` metadata cannot constrain
;; these, so they are named specs the ops consult before returning.
;; ---------------------------------------------------------------------------

(s/def ::operation ::non-blank-string)
(s/def ::name (s/nilable string?))
(s/def ::workspace (s/nilable string?))
(s/def ::weaver-id (s/nilable string?))
(s/def ::running? boolean?)
(s/def ::self? true?)
(s/def ::kanban-send? boolean?)

(s/def ::peer-row
  (s/keys :req-un [::name ::workspace ::weaver-id ::running?]
          :opt-un [::self? ::kanban-send?]))

(s/def ::peers (s/coll-of ::peer-row :kind vector?))

(s/def ::peers-result
  (s/and (s/keys :req-un [::operation ::peers])
         #(= "kanban-peers" (:operation %))))

(s/def ::sent-id
  (s/and map? #(non-blank-string? (:id %))))

(s/def ::sent-card
  (s/and map? #(known-keys? #{:card} %) #(s/valid? ::sent-id (:card %))))

(s/def ::sent-epic
  (s/and map?
         #(known-keys? #{:epic :features} %)
         #(s/valid? ::sent-id (:epic %))
         #(s/valid? (s/coll-of ::sent-id :kind vector? :min-count 1) (:features %))))

(s/def ::sent (s/or :card ::sent-card :epic ::sent-epic))
(s/def ::peer ::non-blank-string)

(s/def ::send-result
  (s/and (s/keys :req-un [::operation ::peer ::sent])
         #(= "kanban-send" (:operation %))))

(defn- conform-out!
  "Return value when it satisfies spec, failing loudly with an explanation otherwise.

  Guards a public op's own structured return so a shape regression fails at the
  boundary rather than shipping a malformed result to a caller."
  [spec value what]
  (when-not (s/valid? spec value)
    (fail! (str what " produced a result violating its return contract")
           {:value value :explain (s/explain-str spec value)}))
  value)

;; ---------------------------------------------------------------------------
;; receive handler: create cards through the local add! code path
;; ---------------------------------------------------------------------------

(defn- from-stamp
  "Return the `<board>:<card>` provenance string for a :from map, or nil."
  [from]
  (when from
    (str (:board from) ":" (:card from))))

(defn- card->flags
  "Return the string-keyed flag map add! expects for a received card map.

  Only keys the caller supplied are passed through; add!/card-attributes apply
  the same p3/pending defaults local cards get, so peered and local cards share
  one defaulting path."
  [card]
  (cond-> {}
    (:lane card) (assoc "--lane" (:lane card))
    (:priority card) (assoc "--priority" (:priority card))
    (:body card) (assoc "--body" (:body card))
    (:source card) (assoc "--source" (:source card))))

(defn- create-card!
  "Create one card via `kanban/add!`, stamp provenance, and return its id.

  `extra-flags` carries the `--type epic` / `--epic <id>` wiring for bundles;
  `stamp` is the `kanban/from` provenance string (nil to skip)."
  [rt card extra-flags stamp]
  (let [id (get-in (kanban/add! (:title card) (merge (card->flags card) extra-flags))
                   [:card :id])]
    (when stamp
      (weaver/update! rt id {:attributes {:kanban/from stamp}}))
    id))

(defn send-op
  "Receive a peered card or epic bundle onto this board.

  Handles the guild op `kanban.send.v1`: `:guild/input` is the spec-validated,
  keyword-keyed JSON body. A `:card` creates a single feature; an `:epic` +
  `:features` bundle creates the epic and hangs each feature under it with a
  `parent-of` edge (same path as `kanban add --epic`), preserving input order.
  Returns JSON-safe ids only."
  [{:guild/keys [input]}]
  (let [rt (current/runtime)
        stamp (from-stamp (:from input))]
    (if-let [card (:card input)]
      {:operation "kanban.send.v1"
       :card {:id (create-card! rt card {} stamp)}}
      (let [epic-id (create-card! rt (:epic input) {"--type" "epic"} stamp)]
        {:operation "kanban.send.v1"
         :epic {:id epic-id}
         :features (mapv (fn [feature]
                           {:id (create-card! rt feature {"--epic" epic-id} stamp)})
                         (:features input))}))))

;; ---------------------------------------------------------------------------
;; peer transport seams: enumerate, probe, and invoke sibling weavers
;;
;; The two touchpoints onto `skein.api.peers.alpha` sit behind dynamic vars so
;; tests exercise classification and payload building without a live socket
;; peer. Real use never rebinds them.
;; ---------------------------------------------------------------------------

(defn- list-peers*
  "Return `skein.api.peers.alpha/peers` rows (siblings under the mill state root)."
  []
  (peers/peers))

(defn- list-peer-guild*
  "Invoke `guild list` on a peer (row or friendly name) via `peers/call!`."
  [peerish]
  (peers/call! peerish "guild" {:argv ["list"]}))

(defn- send-card*
  "Invoke `kanban.send.v1` on a peer with the payload as one JSON `:argv` string."
  [peerish json-arg]
  (peers/call! peerish "kanban.send.v1" {:argv [json-arg]}))

(def ^:dynamic *list-peers* list-peers*)
(def ^:dynamic *list-peer-guild* list-peer-guild*)
(def ^:dynamic *send-card* send-card*)

;; guild list rides back through peers/call! JSON-decoded, so its envelope
;; and op entries are string-keyed. The shape is a contract: a well-formed reply
;; carries an "active" sequence of `{"name" <string>}` op entries.

(s/def ::guild-active-entry
  (s/and map? #(non-blank-string? (get % "name"))))

(s/def ::guild-active
  (s/coll-of ::guild-active-entry :kind sequential?))

(s/def ::guild-list-envelope
  (s/and map?
         #(contains? % "active")
         #(s/valid? ::guild-active (get % "active"))))

(defn- validate-guild-list!
  "Return listed when it is a well-formed `guild list` envelope, failing loudly otherwise.

  A malformed envelope — missing or non-sequential `active`, or entries lacking a
  string `name` — is protocol corruption, not an ordinary non-advertising peer.
  TEN-003 requires it to surface rather than collapse silently into a false
  capability classification."
  [peerish listed]
  (when-not (s/valid? ::guild-list-envelope listed)
    (fail! "guild list returned a malformed envelope"
           {:peer peerish
            :received listed
            :expected "{\"active\" [{\"name\" <non-blank string>} ...]}"
            :explain (s/explain-str ::guild-list-envelope listed)}))
  listed)

(defn- advertises-send?
  "Return true when a validated `guild list` envelope lists `kanban.send.v1` as active.

  Callers must pass a `validate-guild-list!`-checked envelope, so every entry is a
  string-keyed op map: a false result means a well-formed peer that simply does
  not advertise the receive op, never a malformed reply."
  [listed]
  (boolean (some #(= "kanban.send.v1" (get % "name")) (get listed "active"))))

(defn- domain-error?
  "Return true when ex is a `peers/call!` domain-error (the peer rejected the op)."
  [ex]
  (= :peer/domain-error (:code (ex-data ex))))

(defn- op-registered?
  "Return true when an op named op-name is present in the weaver op registry."
  [rt op-name]
  (boolean (some #(= op-name (:name %)) (weaver/ops rt))))

;; ---------------------------------------------------------------------------
;; kanban-peers: which siblings accept peered cards
;; ---------------------------------------------------------------------------

(defn- probe-kanban-send?
  "Return true when a running peer advertises `kanban.send.v1`.

  A running peer that rejects `guild list` as an unknown op (a domain error)
  is an expected non-peering sibling -> false. Any other transport or protocol
  failure propagates loudly (TEN-003: no swallowed errors)."
  [peer-row]
  (try
    (advertises-send? (validate-guild-list! peer-row (*list-peer-guild* peer-row)))
    (catch clojure.lang.ExceptionInfo ex
      (if (domain-error? ex)
        false
        (throw ex)))))

(defn peers-op
  "List sibling weavers and whether each accepts peered kanban cards.

  Every metadata row from `peers/peers` is listed, including stale ones
  (`:running? false`), which are never probed. Each running non-self peer is
  probed via `guild list`; `:kanban-send? true` when `kanban.send.v1` is
  active. The local weaver is marked `:self? true` when it appears in the roster
  and answers from the local op registry rather than calling its own socket. The
  return conforms to `::peers-result` (rows to `::peer-row`)."
  [_ctx]
  (let [rt (current/runtime)
        self-id (:nonce (:metadata rt))
        self-send? (op-registered? rt "kanban.send.v1")]
    (conform-out!
     ::peers-result
     {:operation "kanban-peers"
      :peers (mapv (fn [{:keys [name workspace weaver-id running?] :as row}]
                     (let [self? (and (some? self-id) (= self-id weaver-id))]
                       (cond-> {:name name
                                :workspace workspace
                                :weaver-id weaver-id
                                :running? running?}
                         self? (assoc :self? true)
                         (and running? self?) (assoc :kanban-send? self-send?)
                         (and running? (not self?)) (assoc :kanban-send? (probe-kanban-send? row)))))
                   (*list-peers*))}
     "kanban-peers")))

;; ---------------------------------------------------------------------------
;; kanban-send: mirror a local card's board tier onto a peer's board
;; ---------------------------------------------------------------------------

(def ^:private card-attr :kanban/card)
(def ^:private lane-attr :kanban/lane)
(def ^:private type-attr :kanban/type)
(def ^:private priority-attr :kanban/priority)
(def ^:private source-attr :kanban/source)

(def ^:private sendable-lanes
  "Lanes whose cards may travel: only queued work. In-flight (claimed,
  in_review) and finished (closed) work is world-local and never peers."
  #{"pending" "refinement"})

(def ^:private active-child-lanes
  "Every board lane an epic's direct, still-open feature child may occupy. Closed
  children are detected by strand state; an open child outside this set is a corrupt or nil lane, not a
  silently-droppable one."
  #{"pending" "refinement" "claimed" "in_review"})

(def ^:private card-types
  "The board's card types. An absent `kanban/type` reads as `feature`; a present
  value outside this set is drift."
  #{"feature" "epic"})

(defn- card-type
  "Return a card's kanban type.

  An absent `kanban/type` reads as `feature`, the board's documented default. A
  value outside the known types fails loudly rather than passing as a feature and
  peering as one."
  [strand]
  (let [type (attr-get strand type-attr)]
    (cond
      (nil? type) "feature"
      (contains? card-types type) type
      :else (fail! "kanban/type must be feature or epic"
                   {:id (:id strand)
                    :type type
                    :allowed (sort card-types)}))))

(defn- card-lane
  "Return a card's board lane."
  [strand]
  (attr-get strand lane-attr))

(defn- card-strand
  "Return id's kanban card strand, failing loudly if absent or not a card."
  [rt id]
  (let [strand (or (weaver/show rt id)
                   (fail! "Kanban strand not found" {:id id}))]
    (when-not (= "true" (attr-get strand card-attr))
      (fail! "Strand is not a kanban card" {:id id :attributes (:attributes strand)}))
    strand))

(defn- require-sendable!
  "Return card when it may travel, failing loudly with its lane otherwise.

  Only active pending/refinement cards peer; the failure names the blocking lane
  so a caller sees why in-flight or finished work stayed home."
  [card]
  (when-not (= "active" (:state card))
    (fail! "Kanban card is closed; finished work is world-local and never peers"
           {:id (:id card) :state (:state card) :lane (card-lane card)}))
  (when-not (contains? sendable-lanes (card-lane card))
    (fail! "Kanban card is in-flight; only pending or refinement cards peer"
           {:id (:id card)
            :lane (card-lane card)
            :sendable (sort sendable-lanes)}))
  card)

(defn- card->send-map
  "Return the keyword-keyed card map the receive op's `::card-map` spec expects.

  Only keys the card carries travel; the receiving board applies its own p3 and
  pending defaults, so the two boards share one defaulting path."
  [card]
  (cond-> {:title (:title card)}
    (card-lane card) (assoc :lane (card-lane card))
    (attr-get card priority-attr) (assoc :priority (attr-get card priority-attr))
    (attr-get card :body) (assoc :body (attr-get card :body))
    (attr-get card source-attr) (assoc :source (attr-get card source-attr))))

(defn- card-claiming?
  "Return true when a strand claims card-ness by carrying the `kanban/card` marker.

  Any value is a claim, because only `\"true\"` marks a real card: a drifted
  marker has to be caught rather than read as one of the ordinary non-card
  children."
  [strand]
  (some? (attr-get strand card-attr)))

(defn- feature-child?
  "Return true when an epic's child is a valid feature card of the board."
  [strand]
  (and (= "true" (attr-get strand card-attr))
       (contains? #{nil "feature"} (attr-get strand type-attr))))

(defn- epic-features
  "Return an epic's direct `parent-of` feature-card children, oldest first
  (created_at is second-granular, so same-second siblings tie-break by id).

  Only children claiming card-ness are bundle members. Notes ride the `notes`
  relation, and tasks and the execution strands an engine hangs under a card are
  unmarked by convention, so none of them claim `kanban/card` and all pass
  through untouched. A child that does claim card-ness must be a valid feature —
  a drifted marker, a nested epic, or an unknown `kanban/type` fails loudly
  rather than dropping out of a bundle that then reports success."
  [rt epic]
  (let [child-ids (mapv :to_strand_id (graph/outgoing-edges rt [(:id epic)] "parent-of"))
        children (filterv card-claiming? (graph/strands-by-ids rt child-ids))
        unexpected (remove feature-child? children)]
    (when (seq unexpected)
      (fail! "Epic has direct card children that are not feature cards"
             {:epic (:id epic)
              :unexpected (mapv (fn [child]
                                  {:id (:id child)
                                   :card (attr-get child card-attr)
                                   :type (attr-get child type-attr)})
                                unexpected)
              :expected "kanban/card \"true\" with kanban/type feature (or absent)"}))
    (vec (sort-by (juxt :created_at :id) children))))

(defn- feature-payload
  "Return the `{:card ... :from ...}` payload for a single feature card."
  [from card]
  {:card (card->send-map (require-sendable! card))
   :from from})

(defn- epic-payload
  "Return the `{:epic ... :features [...] :from ...}` payload for an epic bundle.

  Every direct feature child must occupy a known lane: an unknown or nil lane
  is corruption and fails loudly naming the offending children, never a silent
  drop that could ship a partial bundle. In-flight children (claimed or
  in_review) block the whole send and are named in the failure; closed children
  are finished work and stay home; the bundle travels the remaining
  pending/refinement features in board order. An epic with no travelling feature
  fails loudly rather than sending an empty bundle."
  [from epic children]
  (require-sendable! epic)
  (let [open (remove #(= "closed" (:state %)) children)
        unknown (filterv #(not (contains? active-child-lanes (card-lane %))) open)]
    (when (seq unknown)
      (fail! "Epic has feature children in an unknown or missing board lane"
             {:epic (:id epic)
              :invalid (mapv (fn [c] {:id (:id c) :lane (card-lane c)}) unknown)
              :known (sort active-child-lanes)}))
    (let [blocked (filterv #(contains? #{"claimed" "in_review"} (card-lane %)) open)]
      (when (seq blocked)
        (fail! "Epic has in-flight feature children; claimed or in_review work never peers"
               {:epic (:id epic)
                :blocking (mapv (fn [c] {:id (:id c) :title (:title c) :lane (card-lane c)}) blocked)})))
    (let [features (filterv #(contains? sendable-lanes (card-lane %)) open)]
      (when (empty? features)
        (fail! "Epic has no pending or refinement feature children to send"
               {:epic (:id epic)
                :children (mapv (fn [c] {:id (:id c) :lane (card-lane c)}) children)}))
      {:epic (card->send-map epic)
       :features (mapv card->send-map features)
       :from from})))

(defn- build-payload
  "Return the `kanban.send.v1` payload for a resolved local card.

  A feature card sends itself; an epic card sends its feature children as a
  bundle. `from` is the `{:board :card}` provenance stamped onto every created
  remote card."
  [rt from card]
  (if (= "epic" (card-type card))
    (epic-payload from card (epic-features rt card))
    (feature-payload from card)))

(defn- local-board-name
  "Return the running weaver's published name, failing loudly when unnamed.

  Provenance travels with every peered card, so a nameless board cannot send —
  the remedy names the config key to set."
  [metadata]
  (let [name (:name metadata)]
    (when-not (non-blank-string? name)
      (fail! "This weaver publishes no name; kanban-send cannot stamp provenance"
             {:remedy "set \"name\" in .skein/config.json and restart the weaver"}))
    name))

(defn- preflight-target!
  "Fail loudly unless a peer advertises `kanban.send.v1`; return its guild listing.

  A peer with no guild API rejects `guild list` (a domain error) and is
  reframed as running no kanban peering; a peer whose guild lacks the op fails
  with the same remedy. Any other failure propagates."
  [peerish]
  (let [listed (try
                 (*list-peer-guild* peerish)
                 (catch clojure.lang.ExceptionInfo ex
                   (if (domain-error? ex)
                     (fail! "Target peer runs no guild API and cannot accept kanban.send.v1"
                            {:peer peerish
                             :remedy "install skein.spools.guild and run kanban install-peering! on the target"})
                     (throw ex))))]
    (validate-guild-list! peerish listed)
    (when-not (advertises-send? listed)
      (fail! "Target peer does not advertise kanban.send.v1 (no kanban peering, or an older kanban)"
             {:peer peerish
              :active (mapv #(get % "name") (get listed "active"))
              :remedy "run kanban install-peering! on the target after installing guild"}))
    listed))

;; The remote kanban.send.v1 result rides back JSON-decoded (string keys). Its
;; shape is the receive op's return contract, so a successful call must produce
;; non-blank ids in the shape mirroring the sent payload before any local note
;; claims success.

(s/def ::result-id
  (s/and map? #(non-blank-string? (get % "id"))))

(s/def ::send-result-card
  (s/and map? #(s/valid? ::result-id (get % "card"))))

(s/def ::send-result-features
  (s/coll-of ::result-id :kind sequential? :min-count 1))

(s/def ::send-result-epic
  (s/and map?
         #(s/valid? ::result-id (get % "epic"))
         #(s/valid? ::send-result-features (get % "features"))))

(defn- validate-send-result!
  "Return result when the peer's `kanban.send.v1` reply verifies the send, failing loudly otherwise.

  A successful call must carry non-blank remote ids in the shape mirroring the
  sent payload — a single `card` id, or an epic id plus one feature id per sent
  feature. A missing, blank, or wrong-cardinality reply means the remote creation
  is unverifiable, so it fails here before a misleading local note is written."
  [peer payload result]
  (if (:card payload)
    (when-not (s/valid? ::send-result-card result)
      (fail! "Peer kanban.send.v1 returned a malformed card result; the send is unverified"
             {:peer peer :result result :expected "{\"card\" {\"id\" <non-blank string>}}"}))
    (do
      (when-not (s/valid? ::send-result-epic result)
        (fail! "Peer kanban.send.v1 returned a malformed epic result; the send is unverified"
               {:peer peer
                :result result
                :expected "{\"epic\" {\"id\" <string>} \"features\" [{\"id\" <string>} ...]}"}))
      (let [sent (count (:features payload))
            created (count (get result "features"))]
        (when-not (= sent created)
          (fail! "Peer kanban.send.v1 created a different number of features than were sent"
                 {:peer peer :sent sent :created created :result result})))))
  result)

(defn- created-ids
  "Return the created remote ids from a validated `kanban.send.v1` result.

  Assumes `validate-send-result!` has run, so every id is present and non-blank;
  the shape mirrors the payload — a single `:card` id, or an epic id plus its
  feature ids in order."
  [payload result]
  (if (:card payload)
    {:card {:id (get-in result ["card" "id"])}}
    {:epic {:id (get-in result ["epic" "id"])}
     :features (mapv (fn [f] {:id (get f "id")}) (get result "features"))}))

(defn- sent-note-text
  "Return the local-card note text recording a peer send and its remote ids."
  [peer sent]
  (if-let [card (:card sent)]
    (str "Sent to peer " peer " as card " (:id card) ".")
    (str "Sent to peer " peer " as epic " (get-in sent [:epic :id])
         " with features " (str/join ", " (map :id (:features sent))) ".")))

(defn send-card-op
  "Send a local card or epic bundle to a sibling weaver's board.

  Resolves the local card, refuses in-flight or finished work (with the lane in
  the error), and mirrors the board tier — titles, bodies, priority, source, and
  lane — as a `kanban.send.v1` payload stamped with `:from` provenance. Preflights
  the target's `guild list` for the op, sends the payload as one JSON `:argv`
  string, and validates the peer's reply (`validate-send-result!`) before
  recording the created remote ids as a note on the local card. Returns the
  remote ids, conforming to `::send-result`. The local card's lane is never
  touched — closing it stays the caller's choice."
  [{:op/keys [args runtime-metadata]}]
  (let [{:keys [peer card-id]} args
        rt (current/runtime)
        board (local-board-name runtime-metadata)
        card (card-strand rt card-id)
        payload (build-payload rt {:board board :card card-id} card)]
    (preflight-target! peer)
    (let [result (*send-card* peer (json/write-str payload))
          sent (created-ids payload (validate-send-result! peer payload result))]
      (kanban/note! card-id (sent-note-text peer sent) {"--by" board "--kind" "summary"})
      (conform-out! ::send-result
                    {:operation "kanban-send"
                     :peer peer
                     :sent sent}
                    "kanban-send"))))

;; ---------------------------------------------------------------------------
;; install-peering!: opt-in registration of the receive and send-side ops
;; ---------------------------------------------------------------------------

(def ^:private send-returns
  "Permissive return shape: an :operation label plus JSON-safe id payloads whose
  key set differs between the single-card and epic-bundle responses."
  {:type :map
   :required {:operation :string}
   :extra :json})

(def ^:private kanban-peers-arg-spec
  "Declared command surface for the `kanban-peers` op."
  {:op "kanban-peers"
   :doc "List sibling weavers and whether each accepts peered kanban cards."})

(def ^:private kanban-send-arg-spec
  "Declared command surface for the `kanban-send` op."
  {:op "kanban-send"
   :doc "Send a pending or refinement card (or epic bundle) to a sibling weaver's board."
   :positionals [{:name :peer
                  :type :string
                  :required? true
                  :doc "Target peer weaver name (or workspace path)."}
                 {:name :card-id
                  :type :string
                  :required? true
                  :doc "Local feature or epic card id to send."}]})

(def ^:private kanban-peers-returns
  {:type :map
   :required {:operation :string
              :peers {:type :collection :items :json}}})

(def ^:private kanban-send-returns
  {:type :map
   :required {:operation :string
              :peer :string
              :sent :json}})

(defn contribute
  "Return the complete owner set for peering's local CLI declarations.

  Guild owns its dispatch facade and receive-op table; `reconcile` below keeps
  the `kanban.send.v1` handler in that table.  The two board-local operations
  are ordinary core registry entries, so publishing them here gives refresh its
  deletion semantics without ad-hoc register-or-replace probing."
  [_ctx]
  {:ops {"kanban-peers" (op-entry/assemble 'kanban-peers
                                           {:doc (:doc kanban-peers-arg-spec)
                                            :arg-spec kanban-peers-arg-spec
                                            :returns kanban-peers-returns
                                            :hook-class :read}
                                           'ct.spools.kanban.peering/peers-op)
         "kanban-send" (op-entry/assemble 'kanban-send
                                          {:doc (:doc kanban-send-arg-spec)
                                           :arg-spec kanban-send-arg-spec
                                           :returns kanban-send-returns
                                           :hook-class :mutating}
                                          'ct.spools.kanban.peering/send-card-op)}})

(defn- require-peering-prerequisites! [rt]
  (when-not (op-registered? rt "guild")
    (fail! "kanban install-peering! requires the guild spool to be installed first"
           {:missing "guild" :registered-ops (mapv :name (weaver/ops rt))
            :remedy "run (skein.spools.guild/install! runtime) before install-peering!"}))
  (when-not (op-registered? rt "kanban")
    (fail! "kanban install-peering! requires the kanban board to be installed first"
           {:missing "kanban" :registered-ops (mapv :name (weaver/ops rt))
            :remedy "run (ct.spools.kanban/install!) before install-peering!"})))

(defn reconcile
  "Reconcile Guild's receive table after local owner publication.

  Guild has the receive-dispatch state in this frozen baseline; its supported
  registrar is idempotent, preserving the established wire contract and seams.
  Local board operations themselves are entirely owner-published by
  `contribute`."
  [{:keys [runtime] :as ctx}]
  (if (= :removed (get-in ctx [:module/contribution :status]))
    {:reconciled :removed}
    (let [guild-register-op! (requiring-resolve 'skein.spools.guild/register-op!)]
      (require-peering-prerequisites! runtime)
      {:reconciled :applied
       :op (guild-register-op! runtime 'kanban.send.v1
                               {:doc "Receive a peered kanban card or epic bundle onto this board."
                                :input-spec ::send-input :returns send-returns}
                               'ct.spools.kanban.peering/send-op)})))

(defn install-peering!
  "Register the receive and send-side board-peering ops after guild and kanban.

  Opt-in: trusted config wires this in after `(skein.spools.guild/install! runtime)` and
  `(ct.spools.kanban/install!)`. It never installs guild itself — guild's
  lifecycle has exactly one owner, the repo config. Both preconditions fail loudly
  with the failing state and the remedy. Registers three ops: the `kanban.send.v1`
  guild receive op, and the local `kanban-peers` and `kanban-send` ops. Every
  registration upserts (`guild/register-op!` and `register-or-replace-op!`), so
  re-running is reload-safe."
  []
  (let [rt (current/runtime)
        guild-register-op! (requiring-resolve 'skein.spools.guild/register-op!)]
    (require-peering-prerequisites! rt)
    {:installed true
     :namespace 'ct.spools.kanban.peering
     :op (guild-register-op! rt 'kanban.send.v1
                             {:doc "Receive a peered kanban card or epic bundle onto this board."
                              :input-spec ::send-input
                              :returns send-returns}
                             'ct.spools.kanban.peering/send-op)
     :ops [(if (op-registered? rt "kanban-peers")
             (weaver/resolve-op rt 'kanban-peers)
             (weaver/register-op! rt 'kanban-peers
                                  {:doc (:doc kanban-peers-arg-spec) :arg-spec kanban-peers-arg-spec
                                   :returns kanban-peers-returns :hook-class :read}
                                  'ct.spools.kanban.peering/peers-op))
           (if (op-registered? rt "kanban-send")
             (weaver/resolve-op rt 'kanban-send)
             (weaver/register-op! rt 'kanban-send
                                  {:doc (:doc kanban-send-arg-spec) :arg-spec kanban-send-arg-spec
                                   :returns kanban-send-returns :hook-class :mutating}
                                  'ct.spools.kanban.peering/send-card-op))]}))
