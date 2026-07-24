(ns resolver-sim.pro-rata.claims
  "Mechanism-level claim evaluators for deterministic pro-rata allocation.

   This namespace owns only allocation mathematics and allocation evidence;
   it does not select accounts, classify shortfalls, or mutate domain state.

   Claim evaluators for pro-rata allocation correctness properties.

   Evaluators consume evidence-node content, not raw allocation input.
   Each receives the engine context map:
     {:claim-definition <def-map>
      :evidence-nodes [<node> ...]
      :evidence-node-map {<hash> <node>}
      :evidence-references [<hash> ...]
      :dependency-results {<claim-id> <result>}}

   The first evidence node's :result must contain:
     :claims/input-context  — liable parties, total-basis, slash-obligation
      :claims/direct-result  — allocation result (e.g. from calculate-sew-slash-allocation)
         :claims/direct-result-permuted — independently calculated result for a
                                          canonical permutation of the input
         :claims/projection-artifact     — projection artifact (e.g. from build-sew-slash-projection-artifact)
         :claims/projection-artifact-again — second build for determinism check
         :claims/projection-result       — projection-derived allocation result
         :claims/projection-result-permuted — projection result for the same
                                               input permutation

   Returns {:holds? bool :violations [...]}."
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invariants :as invariants]))

;; ── Evidence-node content extractors ─────────────────────────────────────

(defn- evidence-content
  [evidence-nodes]
  (when-let [node (first evidence-nodes)]
    (:result node)))

(defn- input-ctx
  [content]
  (:claims/input-context content))

(defn- direct-result
  [content]
  (:claims/direct-result content))

(defn- direct-result-permuted
  [content]
  (:claims/direct-result-permuted content))

(defn- projection-artifact
  [content]
  (:claims/projection-artifact content))

(defn- projection-artifact-again
  [content]
  (:claims/projection-artifact-again content))

(defn- projection-result
  [content]
  (:claims/projection-result content))

(defn- projection-result-permuted
  [content]
  (:claims/projection-result-permuted content))

(defn- result->allocations
  [result]
  (or (:allocations result) []))

(defn- normalize-integer-values
  "Normalize integer representations before comparing semantically identical
   allocation evidence produced by separate calculation paths."
  [value]
  (walk/postwalk #(if (integer? %) (bigint %) %) value))

(defn- canonical-allocation-vector
  "Canonical, duplicate-preserving comparison view. Allocation rows may repeat
   economically meaningful values; only presentation-only ordering metadata is removed."
  [allocations]
  (->> allocations
       (map #(-> %
                 (dissoc :idx :order)
                 normalize-integer-values))
       ;; Claim rows are keyed by the same typed identity contract as the
       ;; allocator; map iteration and printed representations are irrelevant.
       (sort-by (comp allocation/canonical-id-key :id))
       vec))

(defn- evidence-shape-violations
  "Validate evidence required by all allocation claims except the independent
   permutation witness, which is required only by that property."
  [content]
  (cond-> []
    (nil? (direct-result content))
    (conj {:type :missing-direct-result})

    (nil? (projection-result content))
    (conj {:type :missing-projection-result})

    (nil? (projection-artifact content))
    (conj {:type :missing-projection-artifact})
    (nil? (projection-artifact-again content))
    (conj {:type :missing-projection-artifact-again})
    (nil? (:projection-hash (projection-artifact content)))
    (conj {:type :missing-projection-hash :artifact :first})
    (nil? (:projection-hash (projection-artifact-again content)))
    (conj {:type :missing-projection-hash :artifact :second})
    (not (vector? (:allocations (direct-result content))))
    (conj {:type :missing-allocation-rows :path :direct})

    (not (vector? (:allocations (projection-result content))))
    (conj {:type :missing-allocation-rows :path :projection})

    (and (vector? (:allocations (direct-result content)))
         (not= (count (:allocations (direct-result content)))
               (count (distinct (map :id (:allocations (direct-result content)))))))
    (conj {:type :duplicate-allocation-identity :path :direct})

    (and (vector? (:allocations (projection-result content)))
         (not= (count (:allocations (projection-result content)))
               (count (distinct (map :id (:allocations (projection-result content)))))))
    (conj {:type :duplicate-allocation-identity :path :projection})))

;; ── Violation helpers ────────────────────────────────────────────────────

(defn- shadow-equivalence-violations
  [content]
  (let [artifact (projection-artifact content)
        artifact-again (projection-artifact-again content)
        direct (direct-result content)
        projection (projection-result content)]
    (into (evidence-shape-violations content)
          (cond-> []
            (not= (:projection-hash artifact)
                  (:projection-hash artifact-again))
            (conj {:type :non-deterministic-projection-hash
                   :first (:projection-hash artifact)
                   :second (:projection-hash artifact-again)})

            (not= (canonical-allocation-vector (:allocations direct))
                  (canonical-allocation-vector (:allocations projection)))
            (conj {:type :allocation-mismatch
                   :direct (:allocations direct)
                   :projection (:allocations projection)})))))

(defn- non-negative-violations
  [result]
  (vec (mapcat (fn [[i alloc]]
                 (keep (fn [k]
                         (when (and (some? (k alloc))
                                    (neg? (long (k alloc))))
                           {:idx i :key k :value (k alloc)}))
                       [:paid :unmet :owed :basis-amount :share :allocated :weight :cap]))
               (map-indexed vector (result->allocations result)))))

(defn- allocation-complete-violations
  [content]
  (let [ctx (input-ctx content)
        result (direct-result content)
        liable-parties (set (map :id (:liable-parties ctx [])))
        allocated-ids (set (map :id (result->allocations result)))
        missing (set/difference liable-parties allocated-ids)
        extra (set/difference allocated-ids liable-parties)]
    (cond-> []
      (seq missing) (conj {:type :missing-participants :ids (vec missing)})
      (seq extra) (conj {:type :extra-participants :ids (vec extra)}))))

(defn- conservation-violations
  [result]
  (let [violations (vec
                    (keep (fn [[idx a]]
                            (let [owed (long (or (:owed a) 0))
                                  paid (long (or (:paid a) 0))
                                  unmet (long (or (:unmet a) 0))]
                              (when (not= owed (+ paid unmet))
                                {:type :per-allocation-owed-mismatch
                                 :idx idx
                                 :id (:id a)
                                 :expected owed
                                 :observed (+ paid unmet)})))
                          (map-indexed vector (:allocations result))))

        total-requested (long (or (:total-requested result)
                                  (:slash-obligation result)
                                  0))
        total-allocated (long (or (:total-allocated result)
                                  (:recovered-total result)
                                  0))
        total-unmet (long (or (:total-unmet result)
                              (:unmet-total result)
                              0))
        remainder (long (or (:remainder result) 0))
        sum-checked (+ total-allocated total-unmet remainder)]
    (cond-> violations
      (not= total-requested sum-checked)
      (conj {:type :aggregate-conservation
             :total-requested total-requested
             :total-allocated total-allocated
             :total-unmet total-unmet
             :remainder remainder
             :difference (- total-requested sum-checked)}))))

(defn- rounding-bounded-violations
  [content]
  (let [ctx (input-ctx content)
        result (direct-result content)
        total-basis (long (or (:total-basis ctx) 0))
        amount (long (or (:slash-obligation ctx)
                         (:total-requested result)
                         0))]
    (if (zero? total-basis)
      []
      (vec (keep (fn [alloc]
                   (let [basis (long (or (:basis-amount alloc)
                                         (:weight alloc)
                                         0))
                         share (/ basis total-basis)
                         ideal (* share amount)
                         paid (long (or (:paid alloc)
                                        (:allocated alloc)
                                        0))
                         diff (- paid ideal)]
                     (when (< 1 (Math/abs (double diff)))
                       {:type :rounding-exceeded
                        :id (:id alloc)
                        :basis basis
                        :share share
                        :ideal ideal
                        :paid paid
                        :deviation diff})))
                 (result->allocations result))))))

(defn- ordering-independent-violations
  "Compare independently recomputed results from an input permutation.
   Canonical vectors retain duplicates, so duplicate-row evidence cannot be
   collapsed into a passing set comparison."
  [content]
  (let [direct (direct-result content)
        direct-permuted (direct-result-permuted content)
        projection (projection-result content)
        projection-permuted (projection-result-permuted content)]
    (cond-> []
      (nil? direct-permuted)
      (conj {:type :missing-direct-result-permuted})

      (nil? projection-permuted)
      (conj {:type :missing-projection-result-permuted})

      (not (vector? (:allocations direct-permuted)))
      (conj {:type :missing-allocation-rows :path :direct-permuted})

      (not (vector? (:allocations projection-permuted)))
      (conj {:type :missing-allocation-rows :path :projection-permuted})

      (and (vector? (:allocations direct-permuted))
           (not= (canonical-allocation-vector (:allocations direct))
                 (canonical-allocation-vector (:allocations direct-permuted))))
      (conj {:type :ordering-dependent
             :path :direct
             :original (canonical-allocation-vector (:allocations direct))
             :permuted (canonical-allocation-vector (:allocations direct-permuted))})

      (and (vector? (:allocations projection-permuted))
           (not= (canonical-allocation-vector (:allocations projection))
                 (canonical-allocation-vector (:allocations projection-permuted))))
      (conj {:type :ordering-dependent
             :path :projection
             :original (canonical-allocation-vector (:allocations projection))
             :permuted (canonical-allocation-vector (:allocations projection-permuted))}))))

(defn- pro-rata-fairness-violations
  [content]
  (let [result (direct-result content)
        allocations (result->allocations result)]
    (if (< (count allocations) 2)
      []
      (let [entries (mapv (fn [alloc]
                            {:id (:id alloc)
                             :received (long (or (:paid alloc) (:allocated alloc) 0))
                             :owed (long (or (:owed alloc) (:weight alloc) (:basis-amount alloc) 0))})
                          allocations)
            active (filterv #(and (pos? (:received %)) (pos? (:owed %))) entries)]
        (if (< (count active) 2)
          []
          (vec (for [i (range (count active))
                     j (range (inc i) (count active))
                     :let [a (nth active i)
                           b (nth active j)
                           cross-i (* (:received a) (:owed b))
                           cross-j (* (:received b) (:owed a))]
                     :when (not= cross-i cross-j)]
                 {:type :pro-rata-fairness-violation
                  :left-id (:id a)
                  :right-id (:id b)
                  :left-received (:received a)
                  :left-owed (:owed a)
                  :right-received (:received b)
                  :right-owed (:owed b)
                  :expected-cross-product cross-i
                  :actual-cross-product cross-j})))))))

(defn- mechanism-result
  [content]
  (:claims/mechanism-result content))

(defn- mechanism-result-check
  [evidence-nodes checker]
  (if-let [result (some-> evidence-nodes evidence-content mechanism-result)]
    (let [violations (checker result)]
      {:holds? (empty? violations)
       :violations (vec violations)})
    {:holds? false
     :violations [{:type :missing-mechanism-allocation-result}]}))

(defn check-cap-respecting
  [{:keys [evidence-nodes]}]
  (mechanism-result-check evidence-nodes invariants/cap-respecting-violations))

(defn check-quota-bounded
  [{:keys [evidence-nodes]}]
  (mechanism-result-check evidence-nodes invariants/quota-bounded-violations))

(defn check-canonical-remainder-assignment
  [{:keys [evidence-nodes]}]
  (if-let [result (some-> evidence-nodes evidence-content mechanism-result)]
    (let [{:keys [result reason violations]} (invariants/remainder-assignment-status result)]
      {:holds? (empty? violations)
       :result result
       :reason reason
       :violations (vec violations)})
    {:holds? false
     :violations [{:type :missing-mechanism-allocation-result}]}))

;; ── Claim Evaluators ─────────────────────────────────────────────────────

(defn check-projection-deterministic
  "Projection artifact is deterministically produced and shadow paths agree."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (try
        (let [violations (shadow-equivalence-violations content)]
          (if (empty? violations)
            {:holds? true}
            {:holds? false :violations violations}))
        (catch Exception e
          {:holds? false
           :violations [{:type :exception
                         :message (.getMessage e)
                         :class (.getName (class e))}]})))))

(defn check-projection-canonical-safe
  "Projection artifact contains only canonical hash-safe values."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (try
        (let [shadow-violations (shadow-equivalence-violations content)
              canonical-violations (:claims/canonical-safe-violations content [])]
          (if (and (empty? shadow-violations) (empty? canonical-violations))
            {:holds? true}
            {:holds? false
             :violations (into shadow-violations canonical-violations)}))
        (catch Exception e
          {:holds? false
           :violations [{:type :non-canonical-value
                         :message (.getMessage e)
                         :class (.getName (class e))}]})))))

(defn check-allocation-complete
  "Every eligible participant has a corresponding allocation row."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (let [shadow-violations (shadow-equivalence-violations content)
            completeness-violations (allocation-complete-violations content)]
        (if (and (empty? shadow-violations) (empty? completeness-violations))
          {:holds? true}
          {:holds? false
           :violations (into shadow-violations completeness-violations)})))))

(defn check-non-negative
  "Allocation, unmet, weight, and cap values are never negative."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (let [shadow-violations (shadow-equivalence-violations content)
            result (direct-result content)
            non-neg-violations (non-negative-violations result)]
        (if (and (empty? shadow-violations) (empty? non-neg-violations))
          {:holds? true}
          {:holds? false
           :violations (into shadow-violations non-neg-violations)})))))

(defn check-conservation
  "Requested amount equals allocated plus unmet plus remainder."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (let [shadow-violations (shadow-equivalence-violations content)
            result (direct-result content)
            conserv-violations (conservation-violations result)]
        (if (and (empty? shadow-violations) (empty? conserv-violations))
          {:holds? true}
          {:holds? false
           :violations (into shadow-violations conserv-violations)})))))

(defn check-rounding-bounded
  "No allocation deviates from its ideal share by more than 1 unit."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (let [shadow-violations (shadow-equivalence-violations content)
            rounding-violations (rounding-bounded-violations content)]
        (if (and (empty? shadow-violations) (empty? rounding-violations))
          {:holds? true}
          {:holds? false
           :violations (into shadow-violations rounding-violations)})))))

(defn check-ordering-independent
  "Allocation result is invariant under permutation of input items."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (try
        (let [shadow-violations (shadow-equivalence-violations content)
              ordering-violations (ordering-independent-violations content)]
          (if (and (empty? shadow-violations) (empty? ordering-violations))
            {:holds? true}
            {:holds? false
             :violations (into shadow-violations ordering-violations)}))
        (catch Exception e
          {:holds? false
           :violations [{:type :exception
                         :message (.getMessage e)
                         :class (.getName (class e))}]})))))

(defn check-pro-rata-fairness
  "No pair of claimants has a different fill ratio (cross-product equality).
   Pro-rata fairness: received[i] / owed[i] = received[j] / owed[j] for all i, j.
   Verified via cross-multiplication: received[i] * owed[j] = received[j] * owed[i].
   
   Reads from :claims/direct-result → :allocations (Sew slash allocation format)."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (let [shadow-violations (shadow-equivalence-violations content)
            fairness-violations (pro-rata-fairness-violations content)]
        (if (and (empty? shadow-violations) (empty? fairness-violations))
          {:holds? true}
          {:holds? false
           :violations (into shadow-violations fairness-violations)})))))

;; ── Partial-fill bridge evaluator ───────────────────────────────────────

(defn- partial-fill-decision->content
  "Adapt a partial-fill decision artifact into claims-compatible content
   so the existing pro-rata-fairness-violations check can be reused."
  [decision]
  (let [requested (:requested decision {})
        filled (:filled decision {})
        allocations (mapv (fn [k]
                            {:id k
                             :paid (long (get filled k 0))
                             :owed (long (get requested k 0))})
                          (keys requested))]
    {:claims/direct-result {:allocations allocations}
     :claims/projection-result {:allocations allocations}
     :claims/projection-artifact {:projection-hash "partial-fill-direct"}
     :claims/projection-artifact-again {:projection-hash "partial-fill-direct"}}))

(defn check-projection-diff
  "Verify :projection/flattened-fields provenance metadata on projection artifacts.
   Every recorded flattening event must carry :path, :type, :value, and :contract.
   This claim does not validate the absolute correctness of the projection;
   that is enforced by check-projection-canonical-safe. It only guarantees
   that transformations are explicitly documented, enabling reproducible
   cross-language verification without silent coercions."
  [{:keys [evidence-nodes]}]
  (let [content (evidence-content evidence-nodes)]
    (if-not content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (let [flat (get content :projection/flattened-fields [])
            required-keys #{:path :type :value :contract}]
        (if (empty? flat)
          {:holds? true}
          (let [violations (vec (keep-indexed
                                 (fn [i entry]
                                   (let [missing (clojure.set/difference required-keys (set (keys entry)))]
                                     (when (seq missing)
                                       {:type :flattening-metadata-incomplete
                                        :index i
                                        :entry entry
                                        :missing (vec missing)})))
                                 flat))]
            {:holds? (empty? violations)
             :violations violations}))))))

(defn check-partial-fill-fairness
  "Pro-rata fairness check over partial-fill decision artifacts.
   Reads a partial-fill decision from evidence-node content and verifies
   cross-product equality across all claimed buckets.
   
   Evidence node should contain a partial-fill decision artifact
   with :requested and :filled maps (as produced by decision-artifact)."
  [{:keys [evidence-nodes]}]
  (let [raw-content (evidence-content evidence-nodes)]
    (if-not raw-content
      {:holds? false :violations [{:type :missing-evidence-content}]}
      (let [content (partial-fill-decision->content raw-content)
            fairness-violations (pro-rata-fairness-violations content)]
        (if (empty? fairness-violations)
          {:holds? true}
          {:holds? false :violations fairness-violations})))))

;; ── Evaluator resolver for claims engine ────────────────────────────────

(def evaluator-registry
  "Maps claim-id to evaluator fn for claims.engine/evaluate-claims."
  {:projection-deterministic          check-projection-deterministic
   :projection-canonical-safe         check-projection-canonical-safe
   :pro-rata/allocation-complete      check-allocation-complete
   :pro-rata/non-negative             check-non-negative
   :pro-rata/conservation             check-conservation
   :pro-rata/quota-bounded            check-rounding-bounded
   :pro-rata/permutation-invariant    check-ordering-independent
   :pro-rata/cap-respecting           check-cap-respecting
   :pro-rata/canonical-remainder-assignment check-canonical-remainder-assignment
   :pro-rata/projection-diff          check-projection-diff
   ;; Exact fill-ratio equality is not valid after integer largest-remainder
   ;; allocation. The registered claim uses the passive registry's bounded
   ;; partial-fill fairness definition.
   :pro-rata/partial-fill-fairness check-partial-fill-fairness})

(defn evaluator-resolver
  "Resolve a claim-id to its evaluator function.
   Intended as the :evaluator-resolver for claims.engine/evaluate-claims."
  [claim-definition]
  (get evaluator-registry (:id claim-definition)))

;; ── Legacy support ──────────────────────────────────────────────────────

(defn registered-claim-ids
  "Claim IDs applicable to the slash-allocation evidence node. The partial-fill
   evaluator remains available for callers that provide partial-fill decision
   evidence, but must not be requested for a slash allocation projection."
  []
  (->> (keys evaluator-registry)
       (remove #{:pro-rata/partial-fill-fairness})
       vec))

(defn evaluate-claim
  "Run a single claim evaluator from evidence-node content.
   Input should contain :evidence-nodes.
   Returns {:holds? bool :violations [...]}."
  [claim-id {:keys [evidence-nodes]}]
  (if-let [evaluator (get evaluator-registry claim-id)]
    (evaluator {:evidence-nodes evidence-nodes})
    (throw (ex-info "Unknown pro-rata claim" {:claim-id claim-id
                                              :known (registered-claim-ids)}))))


