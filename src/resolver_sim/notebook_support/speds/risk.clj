(ns resolver-sim.notebook-support.speds.risk
  "P1: Scenario Risk Projection (risk-projection.v1).

   A canonical, deterministic, evidence-backed projection of ONE observed risk
   quantity — the escrow module's aggregate `total-held` — across the scenario
   universe of an event-evidence bundle.

   Boundaries (do not cross):

   1. CORPUS ≠ DISTRIBUTION. This projection produces rows and corpus-safe
      statistics. It never emits VaR claims. :distribution/status stays
      :not-measured until a separate probability / weighting artifact exists.

   2. EVIDENCE PROVENANCE ≠ EVIDENCE INTEGRITY. Every row names the evidence
      object (:evidence/hash) and the exact observed field (:evidence/path)
      from which its amount was derived. Whether those objects belong to a
      valid hash chain / world transition is NOT verified in v1 and renders
      :not-measured.

   3. OBSERVED EXPOSURE ≠ LOSS. A negative delta is an observed decrease of
      the held quantity (a release or refund moving value out of escrow). It
      is reported as a decrease magnitude, never as a protocol loss claim.

   4. SCENARIO-SEPARATED AGGREGATION. Rows from different scenarios are never
      added. Exposure values are only compared within a scenario; the only
      cross-scenario value is worst-observed-scenario, a corpus statistic.

   5. TIMESTAMP ≠ ORDERING. :chain/seq (evidence chain position) is the
      authoritative ordering coordinate and is always present. :event/at is a
      clock coordinate joined from a scenario trace and may be :not-measured.

   The namespace is pure and deterministic: identical inputs yield identical
   output (no wall-clock, no randomness)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "risk-projection.v1")

(def root-domain-tag
  "Domain-separated canonical hash tag for the risk projection commitment.
   Deliberately distinct from :projection-evidence; a string tag is used so
   this namespace does not mutate the shared domain-tags authority."
  "RISK_PROJECTION_V1")

(def quantity
  "The single observed risk quantity in v1: total value held by the escrow
   module, read from the post-state of each evidence node."
  :escrow/total-held)

(def quantity-label
  "Canonical machine name of the quantity."
  "escrow/total-held")

(def phase-by-evidence-type
  "Explicit mapping from evidence type to a protocol phase. Phases are a
   semantic state-machine label carried per row — never inferred by reordering
   :chain/seq. Unknown evidence types map to :other, not to a guessed phase."
  {:escrow-created     :escrow-pending
   :dispute-raised     :disputed
   :dispute-escalated  :disputed
   :resolution-challenged :disputed
   :escrow-released    :released
   :escrow-refunded    :refunded
   :escrow-withdrawn   :withdrawn
   :slashing           :slashed
   :stake-registered   :stake-registered
   :stake-withdrawn    :stake-withdrawn
   :guard-rejected     :guard-rejected
   :resolver-frozen    :resolver-frozen
   :default            :other})

;; ──────────────────────────────────────────────────────────────────────────
;; Ingestion
;; ──────────────────────────────────────────────────────────────────────────

(defn load-event-evidence
  "Load every event-evidence JSON in a directory into maps. Each map keeps its
   source filename under :evidence/file. Unparseable files are dropped — a
   dropped node contributes no rows (never a fabricated amount)."
  [dir]
  (let [d (io/file dir)]
    (if (and (.exists d) (.isDirectory d))
      (->> (.listFiles d)
           (filter #(.isFile %))
           (keep (fn [f]
                   (try
                     (let [m (json/read-str (slurp f) {:key-fn keyword})]
                       (assoc m :evidence/file (.getName f)))
                     (catch Exception _ nil))))
           vec)
      [])))

(defn load-traces
  "Load scenario trace files into {scenario-id {event-seq time}}. Only the
   event ordering → clock mapping is retained; nothing else is used."
  [dir]
  (let [d (io/file dir)]
    (if (and (.exists d) (.isDirectory d))
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName %) ".trace.json"))
           (keep (fn [f]
                   (try
                     (let [m (json/read-str (slurp f) {:key-fn keyword})]
                       [(:scenario-id m)
                        (into {} (map (fn [e] [(:seq e) (:time e)])
                                      (:events m)))])
                     (catch Exception _ nil))))
           (into {}))
      {})))

;; ──────────────────────────────────────────────────────────────────────────
;; Field extraction
;; ──────────────────────────────────────────────────────────────────────────

(defn- find-total-held-path
  "Return the exact path to the unique `total-held` integer leaf in a map, or
   nil when absent or ambiguous. Deterministic: children are visited in key
   order. One field path per row keeps the derived value attributable to a
   single observed field."
  [m]
  (letfn [(walk [x p]
            (if (map? x)
              (mapcat (fn [[k v]]
                        (if (and (= k :total-held) (integer? v))
                          [(conj p :total-held)]
                          (walk v (conj p k))))
                      (sort-by key (seq x)))
              []))]
    (let [found (vec (walk m []))]
      (when (= 1 (count found)) (first found)))))

(defn extract-row
  "Derive one canonical row from an event-evidence node, joining :event/at
   from the trace when the (scenario-id, event-seq) coordinate resolves.
   Returns nil when the node carries no total-held post-state field."
  [node trace-times]
  (let [post (:post-state node)
        path (find-total-held-path post)]
    (when (and path (seq (:evidence/hash node)))
      (let [scen-id (:scenario/id node)
            ts      (get-in trace-times [scen-id (:event/seq node)])
            token   (get-in node [:inputs :escrow/token])]
        {:event/at     (if (integer? ts) ts :not-measured)
         :chain/seq    (:evidence/chain-seq node)
         :scenario/id  scen-id
         :scope        quantity
         :asset        (if (seq token) token :not-measured)
         :amount       (get-in post path)
         :protocol/phase (get phase-by-evidence-type
                              (keyword (str (:evidence/type node)))
                              (phase-by-evidence-type :default))
         :claim/basis  :derived
         :source       {:evidence/hash (try
                                         (hash-ref/sha256-ref (:evidence/hash node))
                                         (catch Exception _
                                           ;; Malformed hash field: name the object
                                           ;; verbatim rather than crash; integrity
                                           ;; verification is :not-measured in v1.
                                           (str (:evidence/hash node))))
                        :evidence/file (:evidence/file node)
                        :evidence/path path
                        :field         :total-held
                        :observation-basis :observed}}))))

;; ──────────────────────────────────────────────────────────────────────────
;; Rows → deltas and metrics
;; ──────────────────────────────────────────────────────────────────────────

(defn- sort-rows
  [rows]
  (sort-by (juxt :scenario/id :chain/seq) rows))

(defn- with-deltas
  "Attach :delta = change vs the previous row within the same scenario (by
   :chain/seq order). The first row has nil :delta. Deltas are derived from
   observed amounts, never invented."
  [rows]
  (mapv (fn [prev row]
          (assoc row :delta (when prev (- (:amount row) (:amount prev)))))
        (cons nil rows) rows))

(defn- scenario-metrics
  "Unquestionably-valid per-scenario statistics over one scenario's rows.
   All are DERIVED from observed amounts and are scenario-local — none is a
   cross-scenario or probabilistic claim."
  [rows]
  (let [amounts (map :amount rows)
        deltas  (keep :delta rows)
        losses  (->> deltas (filter neg?) (map -))
        drawdown (loop [rs rows, running-peak 0, dd 0]
                   (if (empty? rs)
                     dd
                     (let [a    (:amount (first rs))
                           peak (max running-peak a)]
                       (recur (rest rs) peak (max dd (- peak a))))))]
    {:peak-observed-exposure  (apply max 0 amounts)
     :max-observed-event-loss (apply max 0 losses)
     :peak-drawdown           drawdown}))

;; ──────────────────────────────────────────────────────────────────────────
;; P3: Evidence verification
;; ──────────────────────────────────────────────────────────────────────────

(defn- verify-scenario-chains
  "Run the repo's chain verifier over every scenario in the bundle. Each
   scenario's records must form one contiguous, hash-linked `link-v1` chain:
   every :evidence/chain-self-hash equals
   chain-link-hash(:evidence/hash, :evidence/chain-seq, :evidence/chain-prev-hash)
   and every predecessor link matches the prior record's chain-self-hash."
  [nodes]
  (let [by-scenario (->> nodes (group-by :scenario/id) (sort-by key))
        results (mapv (fn [[sid ns]]
                        [sid (chain/verify-scenario-chain ns :scenario-id sid)])
                      by-scenario)
        invalid (keep (fn [[sid r]]
                        (when (not= :verified (:chain/status r))
                          {:scenario/id sid
                           :chain/status (:chain/status r)
                           :errors (take 3 (:chain/errors r))}))
                      results)]
    {:scenario-count (count results)
     :verified-scenario-count (- (count results) (count invalid))
     :invalid-scenario-count (count invalid)
     :chains-verified? (and (seq results) (empty? invalid))
     :invalid-scenarios (vec invalid)}))

(defn- hex64? [s] (and (string? s) (re-matches #"^[0-9a-f]{64}$" s)))

(defn- world-hash-well-formed
  "Structural check: every node carries a well-formed world before/after hash.
   This verifies the hash FIELDS only — it does not recompute whether the
   after-hash equals the world state after the transition (that requires the
   replay engine and is reported separately as :not-measured)."
  [nodes]
  (let [violations (keep (fn [n]
                           (let [b (:world/before-hash n)
                                 a (:world/after-hash n)]
                             (cond
                               (not (hex64? b))
                               {:scenario/id (:scenario/id n)
                                :chain-seq (:evidence/chain-seq n)
                                :reason :before-hash-malformed}
                               (not (hex64? a))
                               {:scenario/id (:scenario/id n)
                                :chain-seq (:evidence/chain-seq n)
                                :reason :after-hash-malformed})))
                         nodes)]
    {:node-count (count nodes)
     :well-formed? (empty? violations)
     :violations (vec violations)}))

(defn verify-evidence
  "P3 evidence verification over a bundle. Returns the :evidence status map.
   Distinguishes what is established from what is not:

     - :chain-verification — established (:verified) when every scenario chain
       verifies end-to-end via chain/verify-scenario-chain.
     - :integrity — NOT established (:not-measured): :evidence/hash recomputation
       is not possible from the persisted bundle, which stores a re-serialized
       projection of each evidence record rather than the exact hashed content.
     - :world-transition-verification — NOT established (:not-measured):
       recomputing world before/after hashes requires the replay engine.
     - :world-hash-fields — structural well-formedness of the world before/after
       hash fields, scoped to the evidence objects the projection actually uses
       (row-nodes); nodes that produce no rows are outside the projection's
       evidence scope.

   nodes — all bundle nodes (chain verification covers whole scenarios).
   row-nodes — nodes that produced projection rows.
   roots — bare-hex evidence hashes used by the projection; the
   :verification-root commits to that verified set."
  [nodes row-nodes roots]
  (let [chains (verify-scenario-chains nodes)
        world  (world-hash-well-formed row-nodes)
        verification-root (hash-ref/sha256-ref
                           (chain/evidence-hash-set-root roots))]
    {:traceability :verified
     :integrity :not-measured
     :integrity-detail
     "evidence/hash recomputation unavailable: the persisted event-evidence view is a re-serialized projection, not the exact hashed content"
     :chain-verification (if (:chains-verified? chains) :verified :failed)
     :chain-verification-detail chains
     :world-transition-verification :not-measured
     :world-transition-detail
     "world before/after transition recomputation requires the replay engine; structural hash-field well-formedness is verified separately"
     :world-hash-fields (if (:well-formed? world) :verified :failed)
     :world-hash-fields-detail world
     :verification-root verification-root}))

;; ──────────────────────────────────────────────────────────────────────────
;; Projection
;; ──────────────────────────────────────────────────────────────────────────

(defn committable-content
  "The canonical semantic body that the :risk-projection/root commits.
   Rendering (cards), verification status, context, and the root itself are
   deliberately OUTSIDE this body so presentation evolution can never change
   the risk claim."
  [{:keys [source projection coverage metrics]}]
  {:schema schema-version
   :source source
   :projection projection
   :coverage coverage
   :aggregation-policy {:mode "scenario-separated"
                        :cross-scenario-addition? false}
   :distribution-policy {:status "not-measured"
                         :model nil
                         :var-claims-absent true}
   :metrics metrics})

(defn commit-content
  "Produce the canonical commitment map for a semantic body."
  [content]
  {:canonical/bytes (canonical/canonical-bytes-hex content)
   :canonical/hash  (hash-ref/sha256-ref
                     (canonical/domain-hash root-domain-tag content))})

(defn project
  "Build a deterministic risk-projection.v1 over an event-evidence bundle.

   opts :: {:bundle-dir <dir of event-evidence JSONs>
            :trace-dir  <dir of .trace.json files, optional>
            :run-id     <string, optional, informational>}"
  [{:keys [bundle-dir trace-dir run-id]}]
  (let [nodes      (load-event-evidence bundle-dir)
        traces     (load-traces trace-dir)
        scenario-ids (vec (sort (distinct (map :scenario/id nodes))))
        rows       (->> nodes
                        (keep #(extract-row % traces))
                        sort-rows
                        vec)
        by-scenario (->> rows (group-by :scenario/id) (sort-by key))
        scenario-rows (mapv (fn [[sid rs]]
                              [sid (with-deltas rs)])
                            by-scenario)
        rows-with  (into [] (mapcat second) scenario-rows)
        per-scenario (mapv (fn [[sid rs]]
                             (merge {:scenario/id sid
                                     :row-count (count rs)}
                                    (scenario-metrics rs)))
                           scenario-rows)
        measured-ids (set (keys (group-by :scenario/id rows)))
        not-measured-ids (vec (remove measured-ids scenario-ids))
        evidence-roots (vec (sort (distinct (map (comp :evidence/hash :source) rows))))
        roots-bare (vec (map #(str/replace % #"^sha256:" "") evidence-roots))
        row-nodes (vec (keep (fn [n]
                               (when (and (:evidence/hash n)
                                          (find-total-held-path (:post-state n)))
                                 n))
                             nodes))
        evidence (verify-evidence nodes row-nodes roots-bare)
        source    {:evidence-roots evidence-roots
                   :scenario-roots scenario-ids}
        projection {:quantity quantity-label
                    :rows rows-with}
        coverage  {:scenario-count (count scenario-ids)
                   :measured-scenario-count (count measured-ids)
                   :not-measured-scenario-count (count not-measured-ids)
                   :row-count (count rows-with)
                   :not-measured-scenarios not-measured-ids}
        metrics   {:per-scenario per-scenario
                   :worst-observed-scenario
                   (when (seq per-scenario)
                     (-> (apply max-key :max-observed-event-loss per-scenario)
                         (select-keys [:scenario/id :max-observed-event-loss])))}
        content   (committable-content {:source source
                                        :projection projection
                                        :coverage coverage
                                        :metrics metrics})
        root      (commit-content content)]
    {:schema schema-version
     :projection-id (subs (canonical/domain-hash root-domain-tag content) 0 16)
     :context {:bundle-dir bundle-dir
               :trace-dir trace-dir
               :run-id (or run-id "UNSET")}
     :source source
     :projection projection
     :coverage coverage
     :aggregation-policy {:mode :scenario-separated
                          :cross-scenario-addition? false}
     :distribution-policy {:status :not-measured
                           :model nil
                           :var-claims-absent true}
     :metrics metrics
     :evidence evidence
     :risk-projection/root root}))

;; ──────────────────────────────────────────────────────────────────────────
;; Verification
;; ──────────────────────────────────────────────────────────────────────────

(defn recompute-root
  "Reconstruct the committed semantic body from an artifact's semantic fields
   and recompute its canonical commitment."
  [artifact]
  (commit-content
   (committable-content
    {:source (:source artifact)
     :projection (:projection artifact)
     :coverage (:coverage artifact)
     :metrics (:metrics artifact)})))

(defn verify-root
  "Re-verify an artifact's :risk-projection/root against its own semantic
   fields. Returns {:status :pass} when the recomputed commitment matches, or
   {:status :fail :reason <key>} otherwise."
  [artifact]
  (let [stored (:risk-projection/root artifact)
        fresh  (recompute-root artifact)]
    (cond
      (nil? stored) {:status :fail :reason :missing-root}
      (not= (:canonical/bytes stored) (:canonical/bytes fresh))
      {:status :fail :reason :canonical-bytes-mismatch}
      (not= (:canonical/hash stored) (:canonical/hash fresh))
      {:status :fail :reason :canonical-hash-mismatch}
      :else {:status :pass})))
