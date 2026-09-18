(ns resolver-sim.benchmark.research-analysis
  "Rooted boundaries for reproducible research-analysis execution, structural
   verification, and researcher-comparable claims.

   This namespace deliberately distinguishes an engine's reported result from
   what an independent verifier establishes. Its generic verifier establishes
   exact artifact binding and never upgrades an engine result to a theorem."
  (:require [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const output-schema-version "research-analysis-output.v1")
(def ^:const verification-schema-version "research-analysis-verification.v1")
(def ^:const claim-schema-version "research-analytical-claim.v2")
(def ^:const result-schema-version "research-analysis-result.v2")

(def ^:const result-classifications
  #{:supported :counterexample-found :inconclusive})

(def ^:const claim-statuses
  "The generic verifier can establish binding and counterexample presence only.
   :verified-on-declared-domain and :verified-general are reserved for a
   method-specific verifier with a separately specified proof contract."
  #{:not-established :counterexample-found
    :verified-on-declared-domain :verified-general})

(defn- root? [value]
  (hash-ref/valid-sha256-ref? value))

(defn result-root [result]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-analysis-result
                   (dissoc result :result/root))))

(defn result-descriptor-root [descriptor]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-analysis-result-descriptor
                   (dissoc descriptor :result-descriptor/root))))

(defn validate-result-descriptor [descriptor]
  (let [errors (cond-> []
                 (not= "research-result-descriptor.v1" (:schema-version descriptor))
                 (conj :unsupported-result-descriptor-schema)
                 (not (keyword? (:measure/id descriptor)))
                 (conj :invalid-measure-id)
                 (not= :integer (:measure/value-domain descriptor))
                 (conj :unsupported-measure-value-domain)
                 (not (keyword? (:measure/unit descriptor)))
                 (conj :invalid-measure-unit)
                 (not (contains? #{:higher-is-better :lower-is-better :unordered}
                                 (:measure/direction descriptor)))
                 (conj :invalid-measure-direction)
                 (not (keyword? (:measure/comparison-semantics descriptor)))
                 (conj :invalid-comparison-semantics)
                 (and (:measure/witness-schema-root descriptor)
                      (not (root? (:measure/witness-schema-root descriptor))))
                 (conj :invalid-witness-schema-root)
                 (and (:result-descriptor/root descriptor)
                      (not= (:result-descriptor/root descriptor)
                            (result-descriptor-root descriptor)))
                 (conj :result-descriptor-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-result-descriptor [fields]
  (let [descriptor (assoc fields :schema-version "research-result-descriptor.v1")
        validation (validate-result-descriptor descriptor)]
    (when-not (:valid? validation)
      (throw (ex-info "Result descriptor is invalid" validation)))
    (assoc descriptor :result-descriptor/root (result-descriptor-root descriptor))))

(defn validate-result [result]
  (let [errors (cond-> []
                 (not= result-schema-version (:schema-version result))
                 (conj :unsupported-result-schema)
                 (not (root? (:result/descriptor-root result)))
                 (conj :invalid-result-metric-root)
                 (not (root? (:result/case-root result)))
                 (conj :invalid-result-case-root)
                 (not (integer? (:result/value result)))
                 (conj :invalid-result-value)
                 (not (contains? result-classifications (:result/classification result)))
                 (conj :invalid-result-classification)
                 (and (= :counterexample-found (:result/classification result))
                      (not (root? (:result/witness-root result))))
                 (conj :counterexample-requires-witness-root)
                 (and (:result/root result)
                      (not= (:result/root result) (result-root result)))
                 (conj :result-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn output-root [analysis-output]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-analysis-output
                   (dissoc analysis-output :research-analysis-output/root))))

(defn verification-root [verification]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-analysis-verification
                   (dissoc verification :research-analysis-verification/root
                           :errors))))

(defn claim-root [claim]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-analytical-claim-v2
                   (dissoc claim :research-claim/root))))

(declare validate-output validate-claim)

(defn build-output
  "Record an analysis engine's rooted output without treating it as verified.

   The result is intentionally a small deterministic slot suitable for later
   researcher-position comparison. `:analysis-output/evidence-root` identifies
   the engine output or certificate material; its meaning is established only
   by a method-specific verifier."
  [{:keys [research-command incentive-model deviation-domain research-assignment
           outcome-manifest analysis-engine/id analysis-engine/version
           analysis-output/proposition-id analysis-output/_metric-id
           analysis-output/result analysis-output/result-descriptor
           analysis-output/closure-root analysis-output/evidence-root]}]
  (let [descriptor-validation (validate-result-descriptor result-descriptor)]
    (when-not (:valid? descriptor-validation)
      (throw (ex-info "Result descriptor is invalid" descriptor-validation)))
    (let [base {:schema-version output-schema-version
                :analysis-output/command-root (:command/hash research-command)
                :analysis-output/model-root (:incentive-model/root incentive-model)
                :analysis-output/domain-root (:deviation-domain/root deviation-domain)
                :analysis-output/assignment-root (:research-assignment/hash research-assignment)
                :analysis-output/outcome-root (:benchmark-outcome/hash outcome-manifest)
                :analysis-output/closure-root closure-root
                :analysis-engine/id id
                :analysis-engine/version version
                :analysis-output/proposition-id proposition-id
                :analysis-output/metric-id (:measure/id result-descriptor)
                :analysis-output/result (assoc result :result/descriptor-root (:result-descriptor/root result-descriptor))
                :analysis-output/evidence-root evidence-root}
          validation (validate-output base)]
      (when-not (:valid? validation)
        (throw (ex-info "Research analysis output is invalid" validation)))
      (assoc base :research-analysis-output/root (output-root base)))))

(defn validate-output
  "Validate only output shape and self-root integrity. It does not execute an
   engine, inspect evidence material, or establish the asserted proposition."
  [analysis-output]
  (let [result (:analysis-output/result analysis-output)
        result-validation (when (= result-schema-version (:schema-version result))
                            (validate-result result))
        errors (cond-> []
                 (not= output-schema-version (:schema-version analysis-output))
                 (conj :unsupported-output-schema)
                 (not (every? root? (map analysis-output [:analysis-output/command-root
                                                          :analysis-output/model-root
                                                          :analysis-output/domain-root
                                                          :analysis-output/assignment-root
                                                          :analysis-output/outcome-root
                                                          :analysis-output/closure-root
                                                          :analysis-output/evidence-root])))
                 (conj :invalid-output-root-reference)
                 (not (keyword? (:analysis-engine/id analysis-output)))
                 (conj :invalid-engine-id)
                 (not (string? (:analysis-engine/version analysis-output)))
                 (conj :invalid-engine-version)
                 (not (keyword? (:analysis-output/proposition-id analysis-output)))
                 (conj :invalid-proposition-id)
                 (not (keyword? (:analysis-output/metric-id analysis-output)))
                 (conj :invalid-metric-id)
                 (not (map? result))
                 (conj :invalid-result)
                 (not (contains? result :result/value))
                 (conj :missing-result-value)
                 (not (integer? (:result/value result)))
                 (conj :invalid-result-value)
                 (not (contains? result-classifications (:result/classification result)))
                 (conj :invalid-result-classification)
                 (and result-validation (not (:valid? result-validation)))
                 (into (:errors result-validation))
                 (and (:research-analysis-output/root analysis-output)
                      (not= (:research-analysis-output/root analysis-output)
                            (output-root analysis-output)))
                 (conj :output-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn verified-claim-status
  "Derive the strongest status the generic verifier may establish.

   A counterexample is a concrete negative result. Every non-counterexample
   remains :not-established until a method-specific verifier establishes a
   stronger claim under an explicit proof contract."
  [analysis-output]
  (if (= :counterexample-found
         (get-in analysis-output [:analysis-output/result :result/classification]))
    :counterexample-found
    :not-established))

(defn verify-output
  "Independently check an output's declared research context and self-root.

  Outcome manifests and the analysis closure are canonically reconstructed
  before their roots can authenticate the output context."
  [{:keys [research-command incentive-model deviation-domain research-assignment
           outcome-manifest analysis-output analysis-closure]}]
  (let [output-check (validate-output analysis-output)
        command-check (command/validate-command research-command)
        model-check (model/validate-model incentive-model)
        domain-check (domain/validate-domain deviation-domain)
        assignment-check (assignment/validate-assignment research-assignment)
        outcome-validation (outcome/validate-manifest outcome-manifest)
        outcome-check (outcome/outcome-complete-for-command? research-command outcome-manifest)
        derived-closure (closure/verify-closure {:research-command research-command
                                                 :incentive-model incentive-model
                                                 :deviation-domain deviation-domain
                                                 :research-assignment research-assignment
                                                 :outcome-manifest outcome-manifest})
        errors (cond-> []
                 (not (:valid? output-check)) (into (:errors output-check))
                 (not (:valid? command-check)) (conj :invalid-research-command)
                 (not (:valid? model-check)) (conj :invalid-incentive-model)
                 (not (:valid? domain-check)) (conj :invalid-deviation-domain)
                 (not (:valid? assignment-check)) (conj :invalid-research-assignment)
                 (not (:valid? outcome-validation)) (conj :invalid-outcome-manifest)
                 (not (:complete? outcome-check)) (conj :incomplete-outcome-manifest)
                 (not (closure/closure-valid? derived-closure)) (conj :invalid-analysis-closure)
                 (not= (:incentive-model/root incentive-model) (:deviation-domain/incentive-model-root deviation-domain)) (conj :domain-model-mismatch)
                 (not= (:command/hash research-command) (:research-assignment/command-root research-assignment)) (conj :assignment-command-mismatch)
                 (not= (:command/hash research-command) (:analysis-output/command-root analysis-output)) (conj :output-command-mismatch)
                 (not= (:incentive-model/root incentive-model) (:analysis-output/model-root analysis-output)) (conj :output-model-mismatch)
                 (not= (:deviation-domain/root deviation-domain) (:analysis-output/domain-root analysis-output)) (conj :output-domain-mismatch)
                 (not= (:research-assignment/hash research-assignment) (:analysis-output/assignment-root analysis-output)) (conj :output-assignment-mismatch)
                 (not= (:benchmark-outcome/hash outcome-manifest) (:analysis-output/outcome-root analysis-output)) (conj :output-outcome-mismatch)
                 (not= (:research-analysis/root derived-closure) (:analysis-output/closure-root analysis-output)) (conj :output-closure-mismatch)
                 (and analysis-closure
                      (not= (:research-analysis/root analysis-closure)
                            (:research-analysis/root derived-closure))) (conj :supplied-closure-mismatch))
        base {:schema-version verification-schema-version
              :research-analysis-verification/output-root (:research-analysis-output/root analysis-output)
              :research-analysis-verification/command-root (:command/hash research-command)
              :research-analysis-verification/model-root (:incentive-model/root incentive-model)
              :research-analysis-verification/domain-root (:deviation-domain/root deviation-domain)
              :research-analysis-verification/assignment-root (:research-assignment/hash research-assignment)
              :research-analysis-verification/outcome-root (:benchmark-outcome/hash outcome-manifest)
              :research-analysis-verification/status (if (empty? errors) :verified :invalid)
              :research-analysis-verification/claim-status (if (empty? errors) (verified-claim-status analysis-output) :not-established)}]
    (assoc base :research-analysis-verification/root (verification-root base)
           :errors (vec errors))))

(defn verification-valid?
  ([verification]
   (and (= :verified (:research-analysis-verification/status verification))
        (= (:research-analysis-verification/root verification)
           (verification-root verification))
        (empty? (:errors verification))))
  ([verification analysis-output]
   (and (verification-valid? verification)
        (= (:research-analysis-output/root analysis-output)
           (:research-analysis-verification/output-root verification))
        (= (:analysis-output/command-root analysis-output)
           (:research-analysis-verification/command-root verification))
        (= (:analysis-output/model-root analysis-output)
           (:research-analysis-verification/model-root verification))
        (= (:analysis-output/domain-root analysis-output)
           (:research-analysis-verification/domain-root verification))
        (= (:analysis-output/assignment-root analysis-output)
           (:research-analysis-verification/assignment-root verification))
        (= (:analysis-output/outcome-root analysis-output)
           (:research-analysis-verification/outcome-root verification))
        (= (verified-claim-status analysis-output)
           (:research-analysis-verification/claim-status verification)))))

(defn derive-claim
  "Derive a researcher-comparable claim only from the exact verified output.
  A verification object for another output is not transferable."
  [analysis-output verification incentive-model]
  (let [output-validation (validate-output analysis-output)]
    (when-not (:valid? output-validation)
      (throw (ex-info "Research analysis output is not valid" output-validation)))
    (when-not (verification-valid? verification analysis-output)
      (throw (ex-info "Research analysis verification is not valid or does not bind this output"
                      {:verification verification})))
    (let [model-validation (model/validate-model incentive-model)]
      (when-not (:valid? model-validation)
        (throw (ex-info "Incentive model is not valid" model-validation)))
      (when-not (= (:incentive-model/root incentive-model)
                   (:analysis-output/model-root analysis-output))
        (throw (ex-info "Research analysis output does not bind this model"
                        {:reason :output-model-mismatch})))
      (let [status (:research-analysis-verification/claim-status verification)
            base {:schema-version claim-schema-version
                  :research-claim/analysis-output-root (:research-analysis-output/root analysis-output)
                  :research-claim/subject-root (:incentive-model/subject-root incentive-model)
                  :research-claim/model-root (:incentive-model/root incentive-model)
                  :research-claim/proposition-id (:analysis-output/proposition-id analysis-output)
                  :research-claim/domain-root (:analysis-output/domain-root analysis-output)
                  :research-claim/metric-id (:analysis-output/metric-id analysis-output)
                  :research-claim/result (:analysis-output/result analysis-output)
                  :research-claim/evidence-root (:analysis-output/evidence-root analysis-output)
                  :research-claim/verification-root (:research-analysis-verification/root verification)
                  :research-claim/status status}
            validation (validate-claim base)]
        (when-not (:valid? validation)
          (throw (ex-info "Research analytical claim is invalid" validation)))
        (assoc base :research-claim/root (claim-root base))))))

(defn validate-claim
  "Validate a V2 claim shape and self-root.

  V2 requires distinct subject and model roots. Legacy V1 claims are rejected
  because their subject-root meant the model root and cannot be interpreted
  under this contract."
  [claim]
  (let [status (:research-claim/status claim)
        errors (cond-> []
                 (not= claim-schema-version (:schema-version claim))
                 (conj :unsupported-claim-schema)
                 (not (every? root? (map claim [:research-claim/analysis-output-root
                                                :research-claim/subject-root
                                                :research-claim/model-root
                                                :research-claim/domain-root
                                                :research-claim/evidence-root
                                                :research-claim/verification-root])))
                 (conj :invalid-claim-root-reference)
                 (not (keyword? (:research-claim/proposition-id claim)))
                 (conj :invalid-claim-proposition-id)
                 (not (keyword? (:research-claim/metric-id claim)))
                 (conj :invalid-claim-metric-id)
                 (not (contains? claim-statuses status))
                 (conj :invalid-claim-status)
                 (contains? #{:verified-on-declared-domain :verified-general} status)
                 (conj :positive-status-requires-method-specific-verifier)
                 (not (map? (:research-claim/result claim)))
                 (conj :invalid-claim-result)
                 (and (:research-claim/root claim)
                      (not= (:research-claim/root claim) (claim-root claim)))
                 (conj :claim-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))
