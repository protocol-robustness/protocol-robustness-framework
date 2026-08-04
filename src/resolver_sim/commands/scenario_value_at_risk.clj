(ns resolver-sim.commands.scenario-value-at-risk
  "Strict, declaration-driven timestamped value-at-risk observation v1."
  (:import [java.time Instant DateTimeException]))

(def ^:private schema-version "scenario-value-at-risk.v1")
(defn- mget [m k] (when (map? m) (or (get m k) (get m (if (keyword? k) (name k) (str k))) (get m (keyword (if (keyword? k) (name k) (str k)))))))
(defn- nonblank? [x] (and (string? x) (not (clojure.string/blank? x))))
(defn- kind [x] (cond (keyword? x) (name x) (string? x) x))
(defn- timestamp
  "Render a replay event time as an ISO-8601 string at the world clock's second
   precision. Accepts integer epoch seconds (the scenario-native form) and
   java.time.Instant. A sub-second Instant is floored to its epoch second, matching
   how the replay clock (replay.temporal/epoch-second) and :context/time store time,
   so the observation never reports a precision the simulation did not carry. This
   also keeps the \"clock\" unix-epoch-seconds label truthful."
  [seconds]
  (try
    (cond
      (integer? seconds) (str (Instant/ofEpochSecond seconds))
      (instance? java.time.Instant seconds)
      (str (Instant/ofEpochSecond (.getEpochSecond ^java.time.Instant seconds)))
      :else nil)
    (catch DateTimeException _ nil)))

(defn valid-amount?
  "Validate the declared value encoding. v1 intentionally supports only
   non-negative scenario-native integer amounts."
  [amount encoding]
  (case encoding
    "scenario-native-integer" (and (integer? amount) (not (neg? amount)))
    false))

(defn- fail [& codes] {"schema_version" schema-version "status" "fail" "reason_codes" (vec codes)})
(defn- selector-wire [xs] (mapv #(if (keyword? %) (name %) %) xs))
(defn- select* [value selector]
  (reduce (fn [v step] (cond (map? v) (mget v step) (and (vector? v) (integer? step) (<= 0 step) (< step (count v))) (nth v step) :else nil)) value selector))
(defn- coordinate [replay index] (filter #(= index (mget % :seq)) (or (mget replay :trace) [])))
(defn- declared [scenario] (mget scenario :value-at-risk))
(defn- action-name [event]
  (let [a (:action event)] (if (keyword? a) (name a) (str a))))
(defn- event-identifier
  "Stable identifier for the trace event. Prefers the event's own :event-id param
   (used by chain-ingestion / external-log replay); standard protocol events do not
   carry one, so their action name is used instead. The declared :event-id must match
   whichever form the event actually carries."
  [event]
  (or (mget (mget event :params) :event-id)
      (action-name event)))
(defn- derive-observation [scenario replay provenance source-ref]
  (let [d (declared scenario)]
    (if (nil? d)
      {"schema_version" schema-version "status" "not-declared"}
      (let [at (mget d :at) risk (mget d :risk) value (mget d :value) scope (mget d :scope) calculation (mget d :calculation)
            declared-ts (mget at :timestamp) index (mget at :event-index) event-id (mget at :event-id) phase (mget at :phase)
            scope-kind (kind (mget scope :kind)) scope-id (mget scope :id) selector (mget calculation :selector) encoding (mget value :amount-encoding)]
        (cond
          (not (and (nonblank? declared-ts) (try (= declared-ts (str (Instant/parse declared-ts))) (catch Exception _ false)))) (fail "invalid-declared-timestamp")
          (not (integer? index)) (fail "invalid-event-index")
          (not (nonblank? event-id)) (fail "invalid-event-id")
          (not= "post-event" (kind phase)) (fail "unsupported-phase")
          (not= "workflow" scope-kind) (fail "unsupported-scope-kind")
          (nil? scope-id) (fail "invalid-scope-id")
          (not (vector? selector)) (fail "invalid-calculation-selector")
          ;; Selector root is data-driven: the first segment names the world root
          ;; that holds the scope (e.g. :escrow-transfers for Sew), the second must
          ;; equal the declared scope-id. Previously the root was hardcoded to
          ;; :workflows, which no protocol world actually contains.
          (not (and (<= 2 (count selector))
                    (nonblank? (kind (nth selector 0)))
                    (= scope-id (nth selector 1)))) (fail "selector-scope-mismatch")
          (not (nonblank? (mget risk :id))) (fail "invalid-risk-id")
          (not (nonblank? (mget value :asset))) (fail "invalid-value-asset")
          (not= "scenario-native-integer" encoding) (fail "unsupported-amount-encoding")
          :else (let [matches (vec (coordinate replay index))]
                  (cond
                    (empty? matches) (fail "event-not-found")
                    (not= 1 (count matches)) (fail "event-coordinate-not-unique")
                    :else (let [event (first matches) actual-ts (timestamp (mget event :time)) world (mget event :world) amount (select* world selector)]
                            (cond
                              (not= event-id (event-identifier event)) (fail "event-id-mismatch")
                              (nil? actual-ts) (fail "invalid-event-time")
                              (not= declared-ts actual-ts) (fail "declared-timestamp-mismatch")
                              (nil? (select* world (subvec selector 0 2))) (fail "scope-not-found")
                              (not (valid-amount? amount encoding)) (fail "invalid-amount")
                              :else {"schema_version" schema-version "status" "pass" "timestamp" actual-ts
                                     "timestamp_source" {"clock" "unix-epoch-seconds" "field" "trace.event.time"}
                                     "event" {"index" index "id" event-id "phase" "post-event"}
                                     "risk" {"id" (mget risk :id)} "value" {"asset" (mget value :asset) "amount" amount "amount_encoding" encoding}
                                     "scope" {"kind" scope-kind "id" scope-id}
                                     "calculation" {"method" "field-read" "source_ref" source-ref "selector" (selector-wire selector)}
                                     "validation" {"status" "pass"
                                                   "checks" ["timestamp-resolves" "event-coordinate-matches"
                                                             "selector-scope-matches" "valid-amount"]}
                                     "derived_from" provenance})))))))))
(defn value-at-risk-timeline
  "Derived reviewer table for an opted-in observation. It is intentionally not a
   second authoritative observation contract: each row is a post-event reading
   using the declared workflow selector."
  [scenario replay source-ref]
  (let [d (declared scenario)
        selector (some-> d (mget :calculation) (mget :selector))
        asset (some-> d (mget :value) (mget :asset))
        scope (some-> d (mget :scope))]
    (if-not d
      {"schema_version" "scenario-value-at-risk-timeline.v1" "status" "not-declared" "rows" []}
      (let [rows (->> (or (mget replay :trace) [])
                      (keep (fn [event]
                              (let [amount (select* (mget event :world) selector)
                                    ts (timestamp (mget event :time))]
                                (when (and ts (valid-amount? amount (mget (mget d :value) :amount-encoding)))
                                  {"event_index" (mget event :seq)
                                   "timestamp" ts
                                   "phase" "post-event"
                                   "asset" asset
                                   "amount" amount
                                   "scope" {"kind" (kind (mget scope :kind)) "id" (mget scope :id)}
                                   "source_ref" source-ref}))))
                      vec)
            rows (mapv (fn [previous row]
                         (assoc row "change" (when previous (- (get row "amount") (get previous "amount")))))
                       (cons nil rows) rows)]
        {"schema_version" "scenario-value-at-risk-timeline.v1"
         "status" "derived"
         "authoritative" false
         "rows" rows}))))

(defn- observation-matches?
  "True when every declared field of `expected` is present in `observation` with an
   equal value. Additive (extra) fields on a persisted observation are tolerated so
   a later enrichment does not invalidate an otherwise correct record; declared
   fields must still match exactly."
  [expected observation]
  (every? (fn [[k v]] (= (get observation k ::missing) v)) expected))

(defn build-observation [scenario replay provenance source-ref] (derive-observation scenario replay provenance source-ref))
(defn validate-persisted [observation scenario replay expected-provenance expected-source-ref]
  (let [expected (derive-observation scenario replay expected-provenance expected-source-ref)]
    (cond (not (map? observation)) (fail "missing-observation")
          (not (observation-matches? expected observation)) (fail "observation-mismatch")
          (= "pass" (get expected "status")) {"schema_version" schema-version "status" "pass" "reason_codes" []}
          :else expected)))
