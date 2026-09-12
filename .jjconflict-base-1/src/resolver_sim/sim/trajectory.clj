(ns resolver-sim.sim.trajectory
  "Shared trajectory types, schemas, and helpers for multi-agent audit phases.

   This namespace is a vocabulary boundary. It defines the canonical trajectory
   type keywords and provides pure helpers for constructing and querying
   trajectory data structures. It does not run simulations.

   Trajectory types:
     :trajectory/equity          — cumulative profit per resolver over epochs
     :trajectory/strategy-spread — honest vs. strategic equity gap per epoch
     :trajectory/displacement    — honest-resolver share eroded by attacker ring
     :trajectory/invariant-margin — invariant headroom over epochs (future)

   Layering: may be imported by sim/* only.
   model/*, contract_model/* must NOT import this namespace.")

;; ---------------------------------------------------------------------------
;; Canonical type vocabulary
;; ---------------------------------------------------------------------------

(def trajectory-types
  "Canonical set of trajectory type keywords used across all audit phases."
  #{:trajectory/equity
    :trajectory/strategy-spread
    :trajectory/displacement
    :trajectory/invariant-margin})

;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- strategic-strategy? [strategy]
  (#{:malicious :lazy :collusive :ring} strategy))

(defn- mean-of [coll]
  (if (seq coll)
    (double (/ (reduce + coll) (count coll)))
    0.0))

;; ---------------------------------------------------------------------------
;; Equity trajectories
;; ---------------------------------------------------------------------------

(defn equity-trajectory
  "Extract per-epoch cumulative profit for one resolver from epoch-snapshots.

   epoch-snapshots — vector of maps {resolver-id → profit}, one per epoch in order.
   resolver-id     — the resolver to extract.

   Returns a vector of profit values, length = (count epoch-snapshots)."
  [epoch-snapshots resolver-id]
  (mapv #(get % resolver-id 0.0) epoch-snapshots))

(defn build-equity-trajectories
  "Build the full :equity-trajectories map from epoch-snapshots.

   epoch-snapshots — [{resolver-id → profit} ...].
   resolver-ids    — seq of all resolver ids to include.

   Returns {resolver-id → [profit@e1 profit@e2 ...]}."
  [epoch-snapshots resolver-ids]
  (reduce (fn [acc id]
            (assoc acc id (equity-trajectory epoch-snapshots id)))
          {}
          resolver-ids))

;; ---------------------------------------------------------------------------
;; Strategy-spread trajectory
;; ---------------------------------------------------------------------------

(defn strategy-spread-at-epoch
  "Compute honest vs. strategic equity spread at one epoch.

   resolver-histories — {resolver-id → resolver-state} (with :strategy key).
   epoch-snapshots    — [{resolver-id → profit} ...] indexed from 0.
   epoch-idx          — 0-based index into epoch-snapshots.

   Returns {:epoch :honest-count :strategic-count
            :honest-mean-equity :strategic-mean-equity :spread}."
  [resolver-histories epoch-snapshots epoch-idx]
  (let [snapshot     (nth epoch-snapshots epoch-idx nil)
        honest-ids   (keep (fn [[id r]] (when (= :honest (:strategy r)) id))
                           resolver-histories)
        strat-ids    (keep (fn [[id r]] (when (strategic-strategy? (:strategy r)) id))
                           resolver-histories)
        mean-equity  (fn [ids]
                       (mean-of (map #(get snapshot % 0.0) ids)))
        h-mean       (mean-equity honest-ids)
        s-mean       (mean-equity strat-ids)]
    {:epoch                 (inc epoch-idx)
     :honest-count          (count honest-ids)
     :strategic-count       (count strat-ids)
     :honest-mean-equity    h-mean
     :strategic-mean-equity s-mean
     :spread                (- s-mean h-mean)}))

(defn strategy-spread-trajectory
  "Compute strategy-spread at every epoch.

   Returns vector of strategy-spread maps (one per epoch)."
  [resolver-histories epoch-snapshots]
  (mapv #(strategy-spread-at-epoch resolver-histories epoch-snapshots %)
        (range (count epoch-snapshots))))

;; ---------------------------------------------------------------------------
;; Displacement trajectory
;; ---------------------------------------------------------------------------

(defn displacement-trajectory
  "Build a displacement trajectory from per-epoch resolver counts.

   epoch-resolver-counts — seq of {:epoch :honest-active :ring-active}.

   Returns [{:epoch :honest-active :ring-active :honest-share} ...]
   showing whether honest-resolver displacement is gradual, sudden, or reversible."
  [epoch-resolver-counts]
  (mapv (fn [{:keys [epoch honest-active ring-active]}]
          (let [total (+ (or honest-active 0) (or ring-active 0))]
            {:epoch         epoch
             :honest-active (or honest-active 0)
             :ring-active   (or ring-active 0)
             :honest-share  (if (pos? total)
                              (double (/ honest-active total))
                              0.0)}))
        epoch-resolver-counts))

;; ---------------------------------------------------------------------------
;; Full multi-dimensional trajectories (Step 3)
;; ---------------------------------------------------------------------------

(defn- extract-field
  "Extract one numeric field from a rich epoch-snapshot for a single resolver.
   Returns a vector of values, one per epoch (0.0 if resolver absent or field missing)."
  [epoch-snapshots resolver-id field]
  (mapv (fn [snap]
          (double (get-in snap [resolver-id field] 0.0)))
        epoch-snapshots))

(defn- loss-events-from-history
  "Extract slash loss events from a resolver's epoch-history.
   Returns [{:epoch N :profit P :slashed? true}] for epochs with slash events."
  [resolver-history]
  (->> (for [[ek ed] (:epoch-history resolver-history)
             :when (:slashed? ed false)]
         {:epoch   (:epoch ed)
          :profit  (:profit ed 0.0)
          :slashed true})
       (sort-by :epoch)
       vec))

(defn resolver-full-trajectory
  "Build the full multi-dimensional trajectory for one resolver.

   epoch-snapshots — rich [{resolver-id → {:profit :reputation :trials ...}} ...].
   resolver-id     — resolver to extract.
   resolver-history — the resolver's final state (for loss-events).

   Returns:
   {:equity          [cumulative-profit@e1 ...]
    :reputation      [win-rate@e1 ...]
    :trial-count     [trials-assigned@e1 ...]
    :verdict-count   [verdicts@e1 ...]
    :slash-count     [slashes@e1 ...]
    :appeal-count    [appeals@e1 ...]
    :escalated-count [escalations@e1 ...]
    :loss-events     [{:epoch :profit :slashed true} ...]}"
  [epoch-snapshots resolver-id resolver-history]
  {:equity          (extract-field epoch-snapshots resolver-id :profit)
   :reputation      (extract-field epoch-snapshots resolver-id :reputation)
   :trial-count     (extract-field epoch-snapshots resolver-id :trials)
   :verdict-count   (extract-field epoch-snapshots resolver-id :verdicts)
   :slash-count     (extract-field epoch-snapshots resolver-id :slashed)
   :appeal-count    (extract-field epoch-snapshots resolver-id :appealed)
   :escalated-count (extract-field epoch-snapshots resolver-id :escalated)
   :loss-events     (loss-events-from-history resolver-history)})

(defn build-full-trajectories
  "Build multi-dimensional per-resolver trajectories from rich epoch-snapshots.

   epoch-snapshots — vector of {resolver-id → rich-snapshot-map}, one per epoch.
   resolver-ids    — seq of resolver IDs to include.
   resolver-histories — {resolver-id → resolver-state} for loss-event extraction.
                        If nil, loss-events will be empty for all resolvers.

   Returns {resolver-id → full-trajectory-map}.

   The :equity field is identical to build-equity-trajectories output, so this
   is a strict superset. Callers migrating from build-equity-trajectories can
   pull (:equity (get full-trajectories id)) for backward compatibility."
  ([epoch-snapshots resolver-ids]
   (build-full-trajectories epoch-snapshots resolver-ids nil))
  ([epoch-snapshots resolver-ids resolver-histories]
   (reduce (fn [acc id]
             (let [hist (when resolver-histories (get resolver-histories id {}))]
               (assoc acc id (resolver-full-trajectory epoch-snapshots id hist))))
           {}
           resolver-ids)))

;; ---------------------------------------------------------------------------
;; Statistical helpers
;; ---------------------------------------------------------------------------

(defn p95
  "Compute the 95th percentile of a numeric collection. Returns 0.0 if empty."
  [coll]
  (if (empty? coll)
    0.0
    (let [sorted (sort coll)
          idx    (int (Math/floor (* 0.95 (dec (count sorted)))))]
      (double (nth sorted idx)))))

(defn divergence-ratio
  "Compute strategic-to-honest equity ratio. Returns nil when honest-mean is zero."
  [honest-mean strategic-mean]
  (when (pos? honest-mean)
    (double (/ strategic-mean honest-mean))))
