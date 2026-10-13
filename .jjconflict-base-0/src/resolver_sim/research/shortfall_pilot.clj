(ns resolver-sim.research.shortfall-pilot
  "Method-specific arithmetic replay gate for the unpublished shortfall pilot.
   Existing analysis verification, acceptance and rewards remain authoritative."
  (:require [prf.pro-rata.request :as request]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invariants :as invariants]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]))

(def proposition :claim/allocation-policy-invariance)
(def metric :metric/maximum-row-allocation-difference)

;; This is the canonical case root of the two published teaching inputs. Keep
;; the exclusion in the rooted pilot profile so the known calibration cannot
;; become an incentive-qualifying submission merely through reviewer approval.
(def ^:private calibration-case-roots
  ["sha256:8fe15fe2dc2ff1ad552c08e65808333eb314db2890c2a835ec031fa5137fbced"])

(def challenge-001-candidate-schema "researcher-challenge-scope-candidate.v1")
(def ^:private challenge-001-policy-roots
  #{"sha256:14c1ae6efdb928b134d91bb6aa61ff2e4c7f7b71ad5dddb3e8b7bf84de588143"
    "sha256:2e4c81dc89c3a0b5b15f8a110ceeb66fe1a802fb1f0322ae3e58fed4cef9aeb9"})

(defn- ensure! [condition reason]
  (when-not condition (throw (ex-info (name reason) {:reason reason}))))

(defn- root [schema body]
  (reference/sha256-ref (canonical/domain-hash schema body)))

(defn- rooted [schema fields]
  (let [body (assoc fields :artifact/schema schema)]
    (assoc body :artifact/root (root schema body))))

(def method-root
  (root "research-shortfall-method.v1"
        {:engine :engine/shortfall-policy-comparison :version "1"
         :comparison :maximum-absolute-row-allocation-difference
         :domain :same-canonical-request-except-rounding-and-redistribution
         :classification :positive-difference-is-finite-counterexample
         :validator :fresh-authoritative-allocation-and-result-invariants}))

(defn- canonical-request [input]
  (ensure! (and (map? input)
                (= #{:schema-version :mechanism/version :allocation/id :available :rows
                     :rounding-policy :tie-break-policy :redistribution-policy}
                   (set (keys input)))
                (vector? (:rows input))
                (every? #(and (map? %)
                              (every? #{:row/id :obligation/id :requested :weight :cap} (keys %)))
                        (:rows input)))
           :noncanonical-replay-input)
  (:canonical-request (request/normalize input)))

(defn execute-comparison
  "Freshly run two policies on the same scenario. No expected result argument.
   Inputs are closed data, never callbacks or precomputed output artifacts."
  [inputs]
  (ensure! (and (vector? inputs) (= 2 (count inputs))) :expected-two-requests)
  (let [requests (mapv canonical-request inputs)
        base #(dissoc % :rounding-policy :redistribution-policy)]
    (ensure! (apply = (map base requests)) :incompatible-scenarios)
    (let [results (mapv allocation/allocate requests)
          _ (doseq [result results]
              (ensure! (and (allocation/allocation-hash-valid? result)
                            (empty? (invariants/result-violations result))) :invalid-allocation))
          differences (mapv (fn [a b] (abs (- (:allocated a) (:allocated b))))
                            (:rows (first results)) (:rows (second results)))]
      (rooted "research-shortfall-policy-comparison.v1"
              {:method/root method-root :requests requests :results results
               :case/root (root "research-shortfall-case.v1" requests)
               :value (reduce max 0 differences)}))))

(defn validate-comparison!
  "Recompute from canonical inputs, including all witnesses, not just a scalar."
  [comparison]
  (ensure! (= comparison (execute-comparison (:requests comparison))) :comparison-replay-mismatch)
  comparison)

(defn- candidate-domain-valid? [request]
  (let [rows (:rows request)
        total (reduce +' 0N (map :requested rows))]
    (and (<= 2 (count rows) 12)
         (every? (fn [{:keys [requested weight cap]}]
                   (and (integer? requested) (<= 1 requested 1000000)
                        (= requested weight)
                        (or (nil? cap)
                            (and (integer? cap) (<= 0 cap requested)))))
                 rows)
         (<= total 10000000)
         (integer? (:available request))
         (<= 0 (:available request) total))))

(defn counterexample-witness-projection
  "Derive the compact maximum-difference row view from the fully retained,
   freshly recomputed pair. This projection is not a minimized input."
  [comparison]
  (validate-comparison! comparison)
  (let [[left right] (:results comparison)
        pairs (mapv (fn [a b]
                      {:row/id (:row/id a)
                       :requested (:requested a)
                       :effective-cap (:effective-cap a)
                       :allocated/left (:allocated a)
                       :allocated/right (:allocated b)
                       :absolute-difference (abs (- (:allocated a) (:allocated b)))})
                    (:rows left) (:rows right))
        maximum (reduce max 0N (map :absolute-difference pairs))]
    {:comparison/root (:artifact/root comparison)
     :case/root (:case/root comparison)
     :maximum-difference maximum
     :rows (if (pos? maximum)
             (->> pairs (filter #(= maximum (:absolute-difference %)))
                  (mapv #(dissoc % :absolute-difference)))
             [])}))

(defn- policy-roots [comparison]
  (vec (sort (distinct (map #(reference/sha256-ref (allocation/allocation-policy-root %))
                            (:results comparison))))))

(defn validate-challenge-001-scope-candidate!
  "Validate the unpublished Challenge 001 candidate without admitting a
   submission or creating an authoritative challenge/reward artifact."
  [candidate comparison]
  (let [candidate-root (reference/sha256-ref
                        (canonical/domain-hash challenge-001-candidate-schema
                                               (dissoc candidate :candidate/root)))]
    (ensure! (and (= challenge-001-candidate-schema (:artifact/schema candidate))
                  (= :proposed-not-authorized (:candidate/status candidate))
                  (= candidate-root (:candidate/root candidate)))
             :invalid-challenge-001-candidate-root)
    (ensure! (= method-root (get-in candidate [:subject :method-root]))
             :challenge-001-method-substitution)
    (ensure! (= {:allocation-row-count {:minimum 2 :maximum 12}
                 :requested-per-row {:minimum 1 :maximum 1000000}
                 :total-requested {:maximum 10000000}
                 :available {:minimum 0 :maximum :total-requested}
                 :weight-rule :weight-equals-requested
                 :cap-rule :optional-integer-from-zero-through-requested
                 :row-identities :unique-canonical-allocation-row-ids
                 :domain-boundary-is-inclusive? true
                 :shortfall-required-for-falsification? true}
                (:input-domain candidate))
             :challenge-001-domain-substitution)
    (ensure! (= [{:rounding :largest-remainder
                  :tie-break :canonical-row-id
                  :redistribution :unallocated
                  :observed-policy-root "sha256:14c1ae6efdb928b134d91bb6aa61ff2e4c7f7b71ad5dddb3e8b7bf84de588143"}
                 {:rounding :largest-remainder
                  :tie-break :canonical-row-id
                  :redistribution :redistribute-cap-excess
                  :observed-policy-root "sha256:2e4c81dc89c3a0b5b15f8a110ceeb66fe1a802fb1f0322ae3e58fed4cef9aeb9"}]
                (:policy-pair candidate))
             :challenge-001-declared-policy-pair-substitution)
    (ensure! (re-matches #"sha256:[0-9a-f]{64}"
                         (:source-content-manifest/root (get candidate :source/revision-observation)))
             :challenge-001-source-manifest-root-invalid)
    (let [comparison (validate-comparison! comparison)
          [left right] (:requests comparison)
          policy-signatures (mapv (juxt :rounding-policy :tie-break-policy :redistribution-policy)
                                  [left right])
          roots (set (policy-roots comparison))
          total (reduce +' 0N (map :requested (:rows left)))
          value (:value comparison)]
      (ensure! (and (candidate-domain-valid? left) (candidate-domain-valid? right))
               :challenge-001-input-outside-bounds)
      (ensure! (= (dissoc left :redistribution-policy)
                  (dissoc right :redistribution-policy))
               :challenge-001-policy-pair-mismatch)
      (ensure! (= [[:largest-remainder :canonical-row-id :unallocated]
                   [:largest-remainder :canonical-row-id :redistribute-cap-excess]]
                  policy-signatures)
               :challenge-001-unsupported-policy-pair)
      (ensure! (= challenge-001-policy-roots roots) :challenge-001-policy-root-mismatch)
      (ensure! (not (some #{(:case/root comparison)} calibration-case-roots))
               :calibration-case-not-qualifying)
      (when (pos? value)
        (ensure! (< (:available left) total) :challenge-001-counterexample-requires-shortfall))
      {:candidate/root candidate-root
       :comparison/root (:artifact/root comparison)
       :case/root (:case/root comparison)
       :scope/status :valid-candidate-input
       :result/classification (if (pos? value) :counterexample-found :supported)
       :falsification-condition-met? (pos? value)
       :novelty/review-required? true
       :witness (counterexample-witness-projection comparison)})))

(defn- economic-model [subject policies]
  (model/build-model
   {:incentive-model/id :model/shortfall-policy-comparison
    :incentive-model/subject-root subject
    :incentive-model/participant-roles [:actor/claimant]
    :incentive-model/payoff-interpretation :terminal-payoff
    :incentive-model/rewards {:allocation :capital-units}
    :incentive-model/penalties {} :incentive-model/costs {}
    :incentive-model/evaluator-semantics-root method-root
    :incentive-model/policy-roots policies}))

(def descriptor
  (analysis/build-result-descriptor
   {:measure/id metric :measure/value-domain :integer :measure/unit :unit/capital
    :measure/direction :unordered :measure/comparison-semantics :exact-integer}))

(defn build-context
  "Construct the existing six-artifact analysis context from actual arithmetic.
   Metadata supplies only issued assignment/environment/runner references."
  [metadata comparison]
  (validate-comparison! comparison)
  (ensure! (= #{:subject-root :environment-root :runner-root :assignment-fields}
              (set (keys metadata))) :invalid-context-metadata)
  (ensure! (= #{:research-assignment/id :research-assignment/policy-hash
                :research-assignment/review-round-hash :research-assignment/target
                :research-assignment/plan-root}
              (set (keys (:assignment-fields metadata)))) :invalid-assignment-metadata)
  (let [{:keys [subject-root environment-root runner-root assignment-fields]} metadata
        incentive-model (economic-model subject-root (policy-roots comparison))
        deviation-domain (domain/build-domain
                          {:deviation-domain/id :domain/shortfall-policy-pair
                           :deviation-domain/subject-root subject-root
                           :deviation-domain/incentive-model-root (:incentive-model/root incentive-model)
                           :deviation-domain/baseline-strategy :strategy/first-policy
                           :deviation-domain/participants [:actor/claimant]
                           :deviation-domain/deviations [:strategy/second-policy]
                           :deviation-domain/coalition-scope :none
                           :deviation-domain/constraints {:case/root (:case/root comparison)}
                           :deviation-domain/evaluation-method :observed-single-trace})
        research-command (command/build-command
                          {:schema-version command/schema-version-v2
                           :command/id :command/shortfall-policy-comparison
                           :command/type :invariant-check :command/argv ["prf" "shortfall" "compare"]
                           :command/includes [] :command/environment-root environment-root
                           :command/runner-root runner-root :command/input-root (:case/root comparison)
                           :command/output-root (:artifact/root comparison)})
        research-assignment (assignment/build-assignment
                             (assoc assignment-fields
                                    :research-assignment/environment-hash environment-root
                                    :research-assignment/request-root (:case/root comparison)
                                    :research-assignment/command-root (:command/hash research-command)))
        outcome-manifest (outcome/build-manifest
                          {:benchmark/content-root subject-root
                           :benchmark/model-root (:incentive-model/root incentive-model)
                           :execution/status :completed :execution/command-root (:command/hash research-command)
                           :outcomes/operational-root (:artifact/root comparison)})
        context {:research-command research-command :incentive-model incentive-model
                 :deviation-domain deviation-domain :research-assignment research-assignment
                 :outcome-manifest outcome-manifest}
        result (cond-> {:schema-version analysis/result-schema-version
                        :result/case-root (:case/root comparison) :result/value (:value comparison)
                        :result/classification (if (pos? (:value comparison)) :counterexample-found :supported)}
                 (pos? (:value comparison)) (assoc :result/witness-root (:artifact/root comparison)))
        output (analysis/build-output
                (assoc context :analysis-engine/id :engine/shortfall-policy-comparison
                       :analysis-engine/version "1" :analysis-output/proposition-id proposition
                       :analysis-output/result-descriptor descriptor :analysis-output/result result
                       :analysis-output/closure-root (:research-analysis/root (closure/verify-closure context))
                       :analysis-output/evidence-root (:artifact/root comparison)))]
    (assoc context :analysis-output output)))

(defn build-profile
  "Rooted opt-in method requirement around, not a revision of, V1 configuration.
   Policy roots are fixed; scenario inputs may vary between findings."
  [config comparison]
  (validate-comparison! comparison)
  (doseq [challenge (:challenges config)]
    (ensure! (= {:proposition-id proposition :metric-id metric
                 :result-classifications #{:counterexample-found}}
                (:acceptance/criteria challenge)) :unsupported-pilot-criteria))
  (rooted "researcher-shortfall-pilot.v1"
          {:configuration/root (:artifact/root config) :method/root method-root
           :allocation-policy/roots (policy-roots comparison)
           :excluded-calibration-case/roots calibration-case-roots}))

(defn validate-profile! [config profile]
  (ensure! (and (= "researcher-shortfall-pilot.v1" (:artifact/schema profile))
                (= (:artifact/root config) (:configuration/root profile))
                (= method-root (:method/root profile))
                (= profile (rooted "researcher-shortfall-pilot.v1"
                                   (dissoc profile :artifact/schema :artifact/root))))
           :invalid-pilot-profile)
  (let [roots (:allocation-policy/roots profile)]
    (ensure! (and (vector? roots) (<= 1 (count roots) 2)
                  (= roots (vec (sort (distinct roots))))
                  (every? reference/valid-sha256-ref? roots)) :invalid-pilot-policy-roots))
  (ensure! (= calibration-case-roots (:excluded-calibration-case/roots profile))
           :invalid-calibration-exclusions)
  profile)

(defn validate-context!
  "Independent method validation. Rebuild all semantic context from fresh replay;
   only issued assignment and execution attribution are copied from the source."
  [config profile challenge-root context evidence]
  (let [comparison (get evidence (get-in context [:analysis-output :analysis-output/evidence-root]))
        challenge (some #(when (= challenge-root (:artifact/root %)) %) (:challenges config))
        a (:research-assignment context) c (:research-command context)]
    (ensure! comparison :missing-replay-evidence)
    (ensure! challenge :unknown-challenge)
    (ensure! (not (some #{(:case/root comparison)} calibration-case-roots))
             :calibration-case-not-qualifying)
    (ensure! (= profile (build-profile config comparison)) :pilot-policy-substitution)
    (ensure! (= context
                (build-context
                 {:subject-root (:subject/root challenge)
                  :environment-root (:command/environment-root c) :runner-root (:command/runner-root c)
                  :assignment-fields (select-keys a [:research-assignment/id :research-assignment/policy-hash
                                                     :research-assignment/review-round-hash
                                                     :research-assignment/target :research-assignment/plan-root])}
                 comparison)) :economic-evidence-mismatch)
    (let [verification (analysis/verify-output context)]
      (ensure! (analysis/verification-valid? verification (:analysis-output context)) :unverified-analysis))
    (forensic/build-forensic-evidence-node
     {:subject {:subject/kind :research/shortfall-replay :subject/root (:research-analysis-output/root (:analysis-output context))}
      :role :validated :visibility :public
      :causal-parent-roots (vec (sort (distinct [(:artifact/root comparison) (:artifact/root profile)
                                                 (:artifact/root config) (:case/root comparison) method-root])))})))
