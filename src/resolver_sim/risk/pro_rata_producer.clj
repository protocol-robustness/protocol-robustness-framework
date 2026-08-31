(ns resolver-sim.risk.pro-rata-producer
  "First producer for risk-projection.v1: general pro-rata.

   Derives generic loss-bearing exposure rows from canonical pro-rata
   quantity state and canonical delta effects (pro-rata.canonical-effects),
   keeping ALL pro-rata-specific meaning outside the generic risk kernel.

   Economic reading: a quantity held as an outstanding pro-rata claim is
   value presently exposed to loss — if the counterparty/mechanism fails
   before settlement, that value is what can be lost. Exposure therefore
   begins and ends where the economic state really changes:

     :exposure/current — the quantity in state-before (the exact source state);
     :exposure/after   — the quantity in state-after (final stage);
     :exposure/peak    — the EXACT maximum the quantity reaches at ANY stage of
                         the staged operation (>= max(current, after)).

   The producer accepts a vector of raw-effect stages and replays them with
   the canonical effect kernel (underflow still rejects the whole operation),
   so exposure is traced through actual effects/state-after. Aggregate peak in
   the projection summary is the conservative sum of these per-row exact peaks
   (see risk.projection).

   Live-consumption state binding: when :source-root is not supplied, the
   producer defaults it to the canonical state root of state-before
   (pro-rata.canonical-effects/state-root), so an exact-state-bound projection
   commits the exact candidate/source state it derives from — the invariant a
   future live-admission consumer checks via
   risk-limit-evaluation/evaluate-bound.

   The producer never invents domain identities: domain attribution is
   supplied by the CALLER (:attribution map or :attribution-fn) from
   quantity root to a vector of {:risk/domain s :risk/member s?} entries.
   Attribution entries carry no amounts: a row's exposure is attributed to
   each listed domain in full (overlapping domains are never additive — see
   resolver-sim.risk.projection)."
  (:require [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.risk.projection :as rp]))

(def ^:private sha256-root-re #"(?:sha256:)?[0-9a-f]{64}")

(defn- subject-root-ref
  "Quantity roots are bare sha256 hex or sha256 refs; projection rows carry
   canonical sha256 refs."
  [qroot]
  (let [r (str qroot)]
    (when-not (re-matches sha256-root-re r)
      (throw (ex-info "quantity root is not a sha256 root" {:quantity/root qroot})))
    (if (re-matches #"sha256:.*" r) r (str "sha256:" r))))

(defn exposure-projection
  "Build a risk-projection.v1 from a staged pro-rata operation.

   args:
     :source-root          — optional sha256 ref committing the exact source
                             state; defaults to (effects/state-root
                             state-before) for exact-state binding
     :valuation-basis-root — sha256 ref committing the valuation basis
     :unit-root            — sha256 ref of the exposure unit
     :time-basis           — as in risk.projection/projection; defaults to
                             {:basis :state-derived} (exact-state-bound)
     :state-before         — canonical quantity state {root integer}
     :stages               — vector of raw canonical-delta-effect vectors;
                             empty means an at-rest projection
     :attribution          — {quantity-root [{:risk/domain s :risk/member s?}]}
     :attribution-fn       — optional fn quantity-root -> entries (overrides
                             :attribution lookup)
     :subject-labels       — optional {root label} for stable :exposure/id;
                             ids default to exp-<root-prefix>.

   Rows are emitted for every quantity root that is positive at ANY point
   (before, any stage, after), ordered by quantity root."
  [{:keys [time-basis state-before stages attribution attribution-fn
           subject-labels]
    :or {stages [] time-basis {:basis :state-derived}}}
   {:keys [source-root valuation-basis-root unit-root]}]
  (when-not (map? state-before)
    (throw (ex-info "producer requires canonical :state-before map" {})))
  (when-not (vector? stages)
    (throw (ex-info "producer :stages must be a vector" {})))
  (let [source-root (or source-root
                        (hash-ref/sha256-ref (effects/state-root state-before)))]
    (letfn [(attributions [qroot]
              (cond
                attribution-fn (or (attribution-fn qroot) [])
                :else (get attribution qroot [])))
            (label [qroot]
              (or (get subject-labels qroot)
                  (str "exp-" (subs (subject-root-ref qroot) 7 23))))]
      (let [;; Replay each stage with the canonical effect kernel. This both
            ;; validates the operation (fail-closed underflow) and yields the
            ;; real per-stage states.
            path (reduce (fn [acc stage]
                           (let [t (effects/transition (peek acc) stage)]
                             (conj acc (:state-after t))))
                         [state-before]
                         stages)
            qroots (->> (mapcat keys path) distinct sort)
            rows (->> qroots
                      (map (fn [qroot]
                             (let [values (map #(get % qroot 0) path)
                                   peak (apply max values)]
                               (when (pos? peak)
                                 (rp/exposure-row
                                  {:exposure/id (label qroot)
                                   :exposure/subject-root (subject-root-ref qroot)
                                   :exposure/current (first values)
                                   :exposure/after (last values)
                                   :exposure/peak peak
                                   :exposure/domains (attributions qroot)})))))
                      (keep identity)
                      vec)]
        (rp/projection
         {:source/root source-root
          :valuation-basis/root valuation-basis-root
          :unit/root unit-root
          :time-basis time-basis
          :rows rows})))))