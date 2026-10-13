(ns resolver-sim.pro-rata.evaluation
  "Canonical pro-rata evaluation and result/root projection.

   Owns the canonical evaluation package, projection artifact, and allocation
   result artifact produced from pro-rata allocation semantics. This is the
   producer boundary for canonical pro-rata roots: it composes the single-pass
   allocator, the redistribution allocator, and the independent exact verifier
   into content-addressed result packages.

   Part of the pro-rata semantic closure: must never depend on protocol,
   runner, research, application, or generic-economics allocator namespaces."
  (:require [resolver-sim.definitions.passive-registries :as registries]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.engine :as engine]
            [resolver-sim.pro-rata.exact-verifier :as exact-verifier]
            [resolver-sim.pro-rata.redistribution :as redistribution]))

(def ^:const default-pro-rata-allocation-result-kind :pro-rata-allocation)
(def ^:const default-pro-rata-allocation-result-version 1)
(def ^:const default-pro-rata-allocation-result-artifact-kind :pro-rata/allocation-result)

(def default-pro-rata-intent-id :pro-rata/slash-obligation-allocation)

(def default-pro-rata-projection-definition-id :projection/pro-rata-slash-obligation)

(defn- registry-entry
  [entries id]
  (some #(when (= id (:id %)) %) entries))

(defn registered-intent
  "Return a passive intent registry entry by id, or nil."
  [intent-id]
  (registry-entry (:intents registries/intent-registry) intent-id))

(defn registered-projection-definition
  "Return a passive projection definition registry entry by id, or nil."
  [projection-definition-id]
  (registry-entry (:projection-definitions registries/projection-definition-registry)
                  projection-definition-id))

(defn- registered-claim
  [claim-id]
  (registry-entry (:claim-definitions registries/claim-definition-registry) claim-id))

(defn- require-registered
  [kind id value]
  (or value
      (throw (ex-info (str "Unregistered " (name kind))
                      {kind id}))))

(defn- short-hash
  [s]
  (subs s 0 (min 16 (count s))))

(defn- prepared-allocation-frame
  [{:keys [amount items id-fn weight-fn cap-fn rounding remainder-policy ordering-policy unit algorithm cap-treatment]
    :or {id-fn :id
         weight-fn :weight
         cap-fn (constantly nil)
         rounding :floor-with-largest-remainder
         remainder-policy :unallocated
         ordering-policy :input-order}}]
  (let [amount (engine/non-negative-integer amount)
        prepared (mapv (fn [idx item]
                         (let [id (id-fn item)
                               weight (engine/non-negative-integer (weight-fn item))
                               cap-raw (cap-fn item)
                               cap (when (some? cap-raw)
                                     (engine/non-negative-integer cap-raw))]
                           {:idx idx
                            :id id
                            :weight weight
                            :cap cap}))
                       (range) (or items []))
        participants (mapv :id prepared)
        eligible (mapv :id (filter #(pos? (:weight %)) prepared))
        weights (into {} (map (juxt :id :weight) prepared))
        caps (into {} (keep (fn [{:keys [id cap]}]
                              (when (some? cap)
                                [id cap]))
                            prepared))
        total-weight (reduce +' 0 (map :weight prepared))]
    {:participants participants
     :eligible-participants eligible
     :weights weights
     :caps caps
     :total-obligation amount
     :constraints {:unit unit
                   :algorithm (or algorithm :weighted-pro-rata)
                   :cap-treatment (or cap-treatment :unallocated)
                   :rounding rounding
                   :remainder-policy remainder-policy
                   :ordering-policy ordering-policy}
     :items (mapv (fn [{:keys [id weight cap]}]
                    {:id id
                     :weight weight
                     :cap cap})
                  prepared)
     :summary {:participant-count (count participants)
               :eligible-count (count eligible)
               :total-weight total-weight
               :total-obligation amount}}))

(defn build-projection-artifact
  "Build a passive projection artifact for the current generic pro-rata input.
   This does not call allocate-pro-rata and does not change allocation behavior."
  ([allocation-input]
   (build-projection-artifact allocation-input {}))
  ([allocation-input {:keys [intent-id projection-definition-id source metadata]
                      :or {intent-id default-pro-rata-intent-id
                           projection-definition-id default-pro-rata-projection-definition-id
                           source {}}}]
   (let [intent (require-registered :intent-id intent-id (registered-intent intent-id))
         projection-definition (require-registered :projection-definition-id
                                                   projection-definition-id
                                                   (registered-projection-definition projection-definition-id))
         claim-ids (mapv :claim-id (:claims projection-definition))
         claims (mapv (fn [claim-id]
                        (let [claim (require-registered :claim-id claim-id (registered-claim claim-id))]
                          {:claim-id claim-id
                           :claim-definition-hash (:canonical-hash claim)
                           :claim-definition-concept-hash (:concept-hash claim)}))
                      claim-ids)
         projection-frame (prepared-allocation-frame allocation-input)
         projection (:summary projection-frame)
         projection-body (dissoc projection-frame :summary)
         projection-definition-hash (:canonical-hash projection-definition)
         projection-concept-hash (:concept-hash projection-definition)
         intent-hash (:canonical-hash intent)
         source-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                          {:intent-id intent-id
                                           :projection-definition-id projection-definition-id
                                           :source source
                                           :projection projection-body})
         artifact-base {:schema-version default-pro-rata-allocation-result-version
                        :projection-id (str "projection-pro-rata-" (short-hash source-hash))
                        :projection-type (:projection-type projection-definition)
                        :projection-version (:version projection-definition)
                        :intent {:id (:id intent)
                                 :version (:version intent)
                                 :intent-hash intent-hash}
                        :projection-definition-id (:id projection-definition)
                        :projection-definition-hash projection-definition-hash
                        :projection-concept-hash projection-concept-hash
                        :source (assoc source :source-hash source-hash)
                        :projection projection-body
                        :summary projection
                        :claims claims
                        :metadata (or metadata {})}
         projection-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                              artifact-base)
         artifact (assoc artifact-base :projection-hash projection-hash)]
     (hc/validate-canonical-value! artifact)
     artifact)))

(defn- validate-projection-artifact!
  [artifact]
  (hc/validate-canonical-value! artifact)
  (let [expected-source-hash
        (hc/hash-with-intent {:hash/intent :projection-artifact}
                             {:intent-id (get-in artifact [:intent :id])
                              :projection-definition-id (:projection-definition-id artifact)
                              :source (dissoc (:source artifact) :source-hash)
                              :projection (:projection artifact)})
        expected-hash (hc/hash-with-intent {:hash/intent :projection-artifact}
                                           (dissoc artifact :projection-hash))]
    (when-not (= expected-source-hash (get-in artifact [:source :source-hash]))
      (throw (ex-info "Projection artifact source hash mismatch"
                      {:expected expected-source-hash
                       :actual (get-in artifact [:source :source-hash])})))
    (when-not (= expected-hash (:projection-hash artifact))
      (throw (ex-info "Projection artifact hash mismatch"
                      {:expected expected-hash
                       :actual (:projection-hash artifact)})))
    (let [intent (require-registered :intent-id
                                     (get-in artifact [:intent :id])
                                     (registered-intent (get-in artifact [:intent :id])))
          projection-definition (require-registered :projection-definition-id
                                                    (:projection-definition-id artifact)
                                                    (registered-projection-definition (:projection-definition-id artifact)))]
      (when-not (= (:canonical-hash intent) (get-in artifact [:intent :intent-hash]))
        (throw (ex-info "Projection artifact intent hash mismatch" {})))
      (when-not (= (:canonical-hash projection-definition) (:projection-definition-hash artifact))
        (throw (ex-info "Projection artifact definition hash mismatch" {})))
      (when-not (= (:concept-hash projection-definition) (:projection-concept-hash artifact))
        (throw (ex-info "Projection artifact definition concept hash mismatch" {})))
      (doseq [claim-ref (:claims artifact)]
        (let [claim (require-registered :claim-id (:claim-id claim-ref)
                                        (registered-claim (:claim-id claim-ref)))]
          (when-not (= (:canonical-hash claim) (:claim-definition-hash claim-ref))
            (throw (ex-info "Projection artifact claim hash mismatch" {:claim-id (:claim-id claim-ref)})))
          (when-not (= (:concept-hash claim) (:claim-definition-concept-hash claim-ref))
            (throw (ex-info "Projection artifact claim concept hash mismatch" {:claim-id (:claim-id claim-ref)})))))
      artifact)))

(defn allocate-from-projection
  "Allocate only from a validated projection artifact and its committed policy.
   Optional runtime observer options do not affect projection-derived semantics."
  ([artifact] (allocate-from-projection artifact {}))
  ([artifact {:keys [on-progress progress-atom]}]
   (validate-projection-artifact! artifact)
   (let [{:keys [total-obligation items constraints]} (:projection artifact)
         {:keys [rounding remainder-policy ordering-policy cap-treatment]} constraints
         request {:amount total-obligation :items items :id-fn :id :weight-fn :weight
                  :cap-fn :cap :rounding rounding :remainder-policy remainder-policy
                  :ordering-policy ordering-policy
                  :on-progress on-progress :progress-atom progress-atom}]
     (case cap-treatment
       :unallocated (allocation/allocate-pro-rata request)
       :redistribute (redistribution/allocate-pro-rata-with-redistribution request)
       (throw (ex-info "Unsupported projected cap treatment" {:cap-treatment cap-treatment}))))))

(defn calculate-prorata-from-projection
  "Compatibility alias for allocate-from-projection."
  [artifact]
  (allocate-from-projection artifact))

(defn- allocation-validation-checks
  [items allocation]
  (let [allocations (:allocations allocation)
        conservation? (= (:total-requested allocation)
                         (+ (:total-allocated allocation)
                            (:total-unmet allocation)
                            (:remainder allocation)))
        bounds? (every? (fn [{:keys [allocated cap]}]
                          (and (not (neg? allocated))
                               (or (nil? cap) (<= allocated cap))))
                        allocations)
        complete? (= (count items) (count allocations))]
    [{:check :allocation-conservation :status (if conservation? :passed :failed)}
     {:check :allocation-bounds :status (if bounds? :passed :failed)}
     {:check :allocation-completeness :status (if complete? :passed :failed)}]))

(defn- contains-function?
  [value]
  (cond
    (fn? value) true
    (map? value) (boolean (some contains-function? (concat (keys value) (vals value))))
    (coll? value) (boolean (some contains-function? value))
    :else false))

(defn- canonical-pro-rata-request
  "Normalize the public data-only evaluation request exactly once.
   Low-level allocation primitives may accept accessors; this public boundary
   intentionally accepts only normalized participant data."
  [{:keys [schema-version amount participants policy use-case unit source metadata]
    :as request}]
  (when (contains-function? request)
    (throw (ex-info "Canonical pro-rata evaluation requests cannot contain functions" {})))
  (when-not (= default-pro-rata-allocation-result-version (or schema-version default-pro-rata-allocation-result-version))
    (throw (ex-info "Unsupported canonical pro-rata request schema" {:schema-version schema-version})))
  (when-not (vector? participants)
    (throw (ex-info "Canonical pro-rata evaluation requires :participants vector" {})))
  (when-not (some? unit)
    (throw (ex-info "Canonical pro-rata evaluation requires an explicit :unit" {})))
  (when-not (and (integer? amount) (not (neg? amount)))
    (throw (ex-info "Canonical pro-rata amount must be a non-negative integer" {:amount amount})))
  (when-not (every? #(and (map? %) (contains? % :id) (contains? % :weight)) participants)
    (throw (ex-info "Each canonical pro-rata participant requires :id and :weight" {})))
  (let [participants (mapv #(select-keys % [:id :weight :cap]) participants)
        ids (mapv :id participants)
        duplicate-ids (->> ids frequencies (keep (fn [[id n]] (when (> n 1) id))) vec)]
    (when (some nil? ids)
      (throw (ex-info "Canonical pro-rata participant IDs cannot be nil" {})))
    (when-not (every? #(or (keyword? %) (string? %) (symbol? %) (integer? %)) ids)
      (throw (ex-info "Canonical pro-rata participant IDs must be canonical scalar values" {:ids ids})))
    (when (seq duplicate-ids)
      (throw (ex-info "Canonical pro-rata participant IDs must be unique" {:duplicate-ids duplicate-ids})))
    (when-not (every? #(and (integer? (:weight %)) (not (neg? (:weight %)))) participants)
      (throw (ex-info "Canonical pro-rata participant weights must be non-negative integers" {})))
    (when-not (some #(pos? (:weight %)) participants)
      (throw (ex-info "Canonical pro-rata evaluation requires at least one positive weight" {})))
    (when-not (every? #(or (nil? (:cap %))
                           (and (integer? (:cap %)) (not (neg? (:cap %)))))
                      participants)
      (throw (ex-info "Canonical pro-rata participant caps must be non-negative integers" {})))
    (let [unknown-policy-fields (seq (remove #{:algorithm :rounding :cap-treatment :tie-break}
                                             (keys (or policy {}))))
          policy (merge {:algorithm :weighted-pro-rata
                         :rounding :floor-with-largest-remainder
                         :cap-treatment :unallocated
                         :tie-break :input-order}
                        policy)]
      (when unknown-policy-fields
        (throw (ex-info "Canonical pro-rata policy contains unsupported fields"
                        {:fields (vec unknown-policy-fields)})))
      (when-not (= :weighted-pro-rata (:algorithm policy))
        (throw (ex-info "Unsupported canonical pro-rata algorithm" {:algorithm (:algorithm policy)})))
      (when-not (#{:unallocated :redistribute} (:cap-treatment policy))
        (throw (ex-info "Unsupported canonical cap treatment" {:cap-treatment (:cap-treatment policy)})))
      (when-not (#{:floor :floor-with-largest-remainder} (:rounding policy))
        (throw (ex-info "Unsupported canonical rounding policy" {:rounding (:rounding policy)})))
      (when-not (= :input-order (:tie-break policy))
        (throw (ex-info "Unsupported canonical tie-break policy" {:tie-break (:tie-break policy)})))
      {:allocation/id (:allocation/id request)
       :use-case use-case
       :unit unit
       :amount amount
       :participants participants
       :policy policy
       :source (or source {})
       :metadata (or metadata {})})))

(defn evaluate-pro-rata-allocation
  "Evaluate a normalized, data-only pro-rata request without persistence.

   The request uses :participants and :policy, never caller accessors. It is
   normalized once, content-addressed as a projection, then allocated and
   replayed from that same data. :on-progress and :progress-atom are runtime
   observers only and are excluded from the canonical request."
  [{:keys [on-progress progress-atom] :as request}]
  (let [{:keys [allocation/id use-case unit amount participants policy source metadata] :as normalized}
        (canonical-pro-rata-request (dissoc request :on-progress :progress-atom))
        allocation-request {:amount amount
                            :items participants
                            :id-fn :id
                            :weight-fn :weight
                            :cap-fn :cap
                            :rounding (:rounding policy)
                            :remainder-policy :unallocated
                            :ordering-policy (:tie-break policy)
                            :algorithm (:algorithm policy)
                            :cap-treatment (:cap-treatment policy)
                            :unit unit}
        projection-source (merge source {:allocation/id id :use-case use-case :unit unit :policy policy})
        projection (build-projection-artifact allocation-request
                                              {:source projection-source :metadata metadata})
        allocation (allocate-from-projection projection
                                             {:progress-atom progress-atom
                                              :on-progress on-progress})
        replay (allocate-from-projection projection)
        weight-verification (exact-verifier/verify-weighted-proportionality
                             {:amount amount
                              :items participants
                              :rounding (:rounding policy)
                              :cap-treatment (:cap-treatment policy)
                              :ordering-policy (:tie-break policy)}
                             allocation)
        checks (conj (allocation-validation-checks participants allocation)
                     {:check :deterministic-replay
                      :status (if (= allocation replay) :passed :failed)
                      :details {:method :same-process-repeat-execution}}
                     {:check :weight-proportionality
                      :status (:status weight-verification)
                      :details (:details weight-verification)})
        evaluated-checks (filter #(not= :not-evaluated (:status %)) checks)
        not-evaluated-checks (filter #(= :not-evaluated (:status %)) checks)
        validation {:status (if (every? #(not= :failed (:status %)) checks) :passed :failed)
                    :coverage-status (if (seq not-evaluated-checks) :partial :complete)
                    :evaluated-check-count (count evaluated-checks)
                    :not-evaluated-check-count (count not-evaluated-checks)
                    :checks checks}
        result-value {:schema-version default-pro-rata-allocation-result-version
                      :allocation/id id
                      :canonical-request normalized
                      :projection-hash (:projection-hash projection)
                      :allocation allocation
                      :validation validation}
        result-hash (hc/domain-hash :pro-rata-evaluation-v1 result-value)]
    {:allocation/id id
     :projection {:artifact/type :pro-rata/projection
                  :artifact/hash (:projection-hash projection)
                  :artifact/value projection}
     :allocation allocation
     :validation validation
     :result {:artifact/type :pro-rata/allocation-result
              :artifact/hash result-hash
              :artifact/value result-value}}))

(defn validate-pro-rata-evaluation-package!
  "Verify the content-addressed result package produced by evaluate-pro-rata-allocation."
  [evaluation]
  (let [value (get-in evaluation [:result :artifact/value])
        recorded (get-in evaluation [:result :artifact/hash])
        computed (hc/domain-hash :pro-rata-evaluation-v1 value)]
    (when-not (and value (= recorded computed))
      (throw (ex-info "Invalid pro-rata evaluation package hash"
                      {:recorded recorded :computed computed})))
    evaluation))

(defn build-pro-rata-allocation-result-artifact
  "Build a pro-rata allocation result artifact from an allocation result
   and its provenance context.

   This is the ex-post outcome artifact that captures what was actually
   allocated, complementing the ex-ante projection frame.

   Required inputs:
     :projection-artifact        — the ex-ante projection frame
     :allocation-result          — output from allocate-pro-rata
     :world-before-hash          — hash of the world before execution
     :world-after-hash           — hash of the world after execution
     :action-hash                — hash of the executed action
     :action-hash-at             — hash of the action at execution time

   Optional inputs:
       :shortfall-outcome          — shortfall breakdown from evidence layer
       :claims                     — claim result links
       :invariant-links            — invariant result links
       :evidence-record-hash       — evidence envelope hash (stored in :external-refs, excluded from canonical hash)
       :evidence-group-id          — group-id for cross-layer linking to surrounding evidence
        :attribution                — researcher attribution context (scenario-id, run-id, event-index, event-type)
         :allocation-input           — raw input map (obligation, parties, basis, cap-field, etc.)
        :metadata                   — additional metadata"
  [{:keys [projection-artifact
           evaluation
           allocation-result
           world-before-hash
           world-after-hash
           action-hash
           action-hash-at
           shortfall-outcome
           allocation-input
           claims
           invariant-links
           evidence-record-hash
           evidence-group-id
           attribution
           metadata]}]
  (let [evaluation (when evaluation (validate-pro-rata-evaluation-package! evaluation))
        allocation-result (or allocation-result (:allocation evaluation))
        evaluation-result-hash (get-in evaluation [:result :artifact/hash])
        projection-artifact-hash (:projection-hash projection-artifact)
        _ (when (and evaluation
                     (not= projection-artifact-hash
                           (get-in evaluation [:projection :artifact/hash])))
            (throw (ex-info "Execution artifact projection does not match evaluation" {})))
        projection-definition-id (:projection-definition-id projection-artifact)
        projection-definition-hash (:projection-definition-hash projection-artifact)
        projection-concept-hash (:projection-concept-hash projection-artifact)
        provenance (merge
                    {:world-before-hash world-before-hash
                     :world-after-hash world-after-hash
                     :action-hash action-hash
                     :action-hash-at action-hash-at}
                    (when attribution
                      (into {} (remove (comp nil? val)
                                       {:scenario-id (:ctx/scenario-id attribution)
                                        :run-id (:ctx/run-id attribution)
                                        :event-index (:ctx/event-index attribution)
                                        :event-type (:ctx/event-type attribution)}))))
        source (get projection-artifact :source {})
        source-hash (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result}
                                         {:projection-artifact-hash projection-artifact-hash
                                          :allocation-result (dissoc allocation-result :policy)
                                          :provenance provenance
                                          :shortfall-outcome shortfall-outcome
                                          :allocation-input allocation-input})
        external-refs (merge (when evidence-record-hash
                               {:evidence-record-hash evidence-record-hash})
                             (when evidence-group-id
                               {:evidence-group-id evidence-group-id}))
        artifact-base {:schema-version default-pro-rata-allocation-result-version
                       :artifact-kind default-pro-rata-allocation-result-artifact-kind
                       :allocation-result-id (str "allocation-pro-rata-"
                                                  (short-hash source-hash))
                       :allocation-result-type default-pro-rata-allocation-result-kind
                       :allocation-result-version default-pro-rata-allocation-result-version
                       :projection-artifact-hash projection-artifact-hash
                       :projection-definition-id projection-definition-id
                       :projection-definition-hash projection-definition-hash
                       :projection-concept-hash projection-concept-hash
                       :evaluation-result-hash evaluation-result-hash
                       :source source
                       :provenance provenance
                       :allocation-input allocation-input
                       :allocation-result allocation-result
                       :shortfall-outcome shortfall-outcome
                       :claims (or claims [])
                       :invariant-links (or invariant-links [])
                       :metadata (or metadata {})
                       :external-refs (when (seq external-refs) external-refs)}
        allocation-result-hash (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result}
                                                    artifact-base)
        artifact (assoc artifact-base :allocation-result-hash allocation-result-hash)]
    (hc/validate-canonical-value!
     (:artifact (hc/project-pro-rata-allocation-result
                 artifact {:hash/intent :pro-rata-allocation-result})))
    artifact))

(defn validate-pro-rata-allocation-result-artifact!
  "Validate a pro-rata allocation result artifact by:
   - verifying canonical safety
   - recomputing and verifying allocation-result-hash
   - requiring projection-artifact-hash
   - requiring allocation-result
   - validating allocation totals
   - checking provenance fields
   - rejecting mismatched hash"
  [artifact]
  (hc/validate-canonical-value! artifact)
  (let [expected-hash (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result}
                                           (dissoc artifact :allocation-result-hash))]
    (when-not (= expected-hash (:allocation-result-hash artifact))
      (throw (ex-info "Pro-rata allocation result hash mismatch"
                      {:expected expected-hash
                       :actual (:allocation-result-hash artifact)}))))
  (let [proj-hash (:projection-artifact-hash artifact)]
    (when (nil? proj-hash)
      (throw (ex-info "Missing projection-artifact-hash" {:artifact artifact}))))
  (let [allocation (:allocation-result artifact)]
    (when (nil? allocation)
      (throw (ex-info "Missing allocation-result" {:artifact artifact})))
    (let [total-requested (:total-requested allocation 0)
          total-allocated (:total-allocated allocation 0)
          total-unmet (:total-unmet allocation 0)
          remainder (:remainder allocation 0)]
      (when (not= total-requested (+ total-allocated total-unmet remainder))
        (throw (ex-info "Allocation totals do not sum to total-requested"
                        {:total-requested total-requested
                         :sum (+ total-allocated total-unmet remainder)
                         :total-allocated total-allocated
                         :total-unmet total-unmet
                         :remainder remainder})))))
  (doseq [k [:world-before-hash :world-after-hash :action-hash :action-hash-at]]
    (when (nil? (get-in artifact [:provenance k]))
      (throw (ex-info (str "Missing provenance field: " k)
                      {:field k :artifact artifact}))))
  artifact)

;; ──────────────────────────────────────────────────────────────────────────────
;; Demo Rendering: Pro-Rata Result Tables and Proof Panels
;; ──────────────────────────────────────────────────────────────────────────────

(defn- basis-amount-shaped?
  [allocations]
  (some :basis-amount (take 1 allocations)))

(defn format-pro-rata-result-table
  "Render a pro-rata allocation result as a formatted text table.

   Handles both basis-amount-shaped allocations (with :basis-amount :share :owed :paid)
   and generic allocations (with :weight :cap :allocated :unmet :remainder).

   Input can be:
   - A pro-rata allocation result artifact (with :allocation-result)
   - An allocation-result map directly (with :allocations list)"
  [artifact-or-result]
  (let [allocations (if (:allocation-result artifact-or-result)
                      (:allocations (:allocation-result artifact-or-result))
                      (:allocations artifact-or-result))
        result (if (:allocation-result artifact-or-result)
                 (:allocation-result artifact-or-result)
                 artifact-or-result)
        basis-amount? (basis-amount-shaped? allocations)
        sb (StringBuilder.)]
    (if basis-amount?
      (do
        (.append sb (format "%-20s %8s %8s %8s %8s%n"
                            "Participant" "Basis" "Owed" "Paid" "Unmet"))
        (.append sb (apply str (repeat 52 "-")))
        (doseq [a allocations]
          (.append sb (format "%n%-20s %8s %8s %8s %8s"
                              (str (:id a))
                              (str (:basis-amount a))
                              (str (:owed a))
                              (str (:paid a))
                              (str (:unmet a)))))
        (let [total-owed (apply + (map :owed allocations))
              total-paid (apply + (map :paid allocations))
              total-unmet (apply + (map :unmet allocations))]
          (.append sb (format "%n%n%-20s %8s %8s %8s %8s"
                              "Total" "" (str total-owed) (str total-paid) (str total-unmet)))))
      (do
        (.append sb (format "%-20s %8s %8s %8s %8s %8s%n"
                            "Participant" "Weight" "Cap" "Allocated" "Unmet" "Remainder"))
        (.append sb (apply str (repeat 60 "-")))
        (doseq [a allocations]
          (.append sb (format "%n%-20s %8s %8s %8s %8s %8s"
                              (str (:id a))
                              (str (or (:weight a) "—"))
                              (str (if (some? (:cap a)) (:cap a) "—"))
                              (str (:allocated a))
                              (str (:unmet a))
                              (str (or (:remainder a) "—")))))
        (let [total-allocated (get result :total-allocated)
              total-unmet (get result :total-unmet)
              remainder (get result :remainder)]
          (.append sb (format "%n%n%-20s %8s %8s %8s %8s"
                              "Total" "" "" (str total-allocated) (str total-unmet)))
          (when (and remainder (pos? (or remainder 0)))
            (.append sb (format " %8s" (str remainder)))))))
    (str sb)))

(defn format-proof-panel
  "Render a forensic proof/link panel for a pro-rata allocation result artifact.
   Shows projection frame hash, allocation result hash, world hashes,
   action-hash-at, evidence hash, and claim/invariant links."
  [artifact]
  (let [sb (StringBuilder.)
        result-hash (:allocation-result-hash artifact)
        proj-hash (:projection-artifact-hash artifact)
        prov (:provenance artifact)
        claims (:claims artifact [])
        invariants (:invariant-links artifact [])]
    (.append sb "=== Proof Panel: Pro-Rata Allocation Result ===\n")
    (.append sb (format "  Allocation Result Hash:  %s%n" (or result-hash "—")))
    (.append sb (format "  Projection Frame Hash:   %s%n" (or proj-hash "—")))
    (.append sb (format "  Projection Def ID:       %s%n" (str (:projection-definition-id artifact "—"))))
    (.append sb (format "  Projection Def Hash:     %s%n" (str (:projection-definition-hash artifact "—"))))
    (.append sb (format "  World Before Hash:       %s%n" (or (:world-before-hash prov) "—")))
    (.append sb (format "  World After Hash:        %s%n" (or (:world-after-hash prov) "—")))
    (.append sb (format "  Action Hash:             %s%n" (or (:action-hash prov) "—")))
    (.append sb (format "  Action Hash-At:          %s%n" (or (:action-hash-at prov) "—")))
    (.append sb (format "  Evidence Record Hash:    %s%n" (or (get-in artifact [:external-refs :evidence-record-hash]) "—")))
    (.append sb (format "  Claim Links:             %d%n" (count claims)))
    (doseq [c claims]
      (.append sb (format "    - %s%n" (str (:claim-id c) " → " (:claim-result-hash c "—")))))
    (.append sb (format "  Invariant Links:         %d%n" (count invariants)))
    (doseq [iv invariants]
      (.append sb (format "    - %s%n" (str iv))))
    (str sb)))