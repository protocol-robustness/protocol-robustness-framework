(ns resolver-sim.benchmark.research-analysis
  "Rooted boundaries for reproducible research-analysis execution, structural
   verification, and researcher-comparable claims.

   This namespace deliberately distinguishes an engine's reported result from
   what an independent verifier establishes. Its generic verifier establishes
   exact artifact binding and never upgrades an engine result to a theorem."
  (:require [resolver-sim.benchmark.incentive-deviation-domain :as domain]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const output-schema-version "research-analysis-output.v1")
(def ^:const verification-schema-version "research-analysis-verification.v1")
(def ^:const claim-schema-version "research-analytical-claim.v1")

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
   (hc/domain-hash :research-analytical-claim
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
           analysis-output/proposition-id analysis-output/metric-id
           analysis-output/result analysis-output/evidence-root]}]
  (let [base {:schema-version output-schema-version
              :analysis-output/command-root (:command/hash research-command)
              :analysis-output/model-root (:incentive-model/root incentive-model)
              :analysis-output/domain-root (:deviation-domain/root deviation-domain)
              :analysis-output/assignment-root (:research-assignment/hash research-assignment)
              :analysis-output/outcome-root (:benchmark-outcome/hash outcome-manifest)
              :analysis-engine/id id
              :analysis-engine/version version
              :analysis-output/proposition-id proposition-id
              :analysis-output/metric-id metric-id
              :analysis-output/result result
              :analysis-output/evidence-root evidence-root}
        validation (validate-output base)]
    (when-not (:valid? validation)
      (throw (ex-info "Research analysis output is invalid" validation)))
    (assoc base :research-analysis-output/root (output-root base))))

(defn validate-output
  "Validate only output shape and self-root integrity. It does not execute an
   engine, inspect evidence material, or establish the asserted proposition."
  [analysis-output]
  (let [result (:analysis-output/result analysis-output)
        errors (cond-> []
                 (not= output-schema-version (:schema-version analysis-output))
                 (conj :unsupported-output-schema)
                 (not (every? root? (map analysis-output [:analysis-output/command-root
                                                          :analysis-output/model-root
                                                          :analysis-output/domain-root
                                                          :analysis-output/assignment-root
                                                          :analysis-output/outcome-root
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

   This is a structural/provenance verifier, not an analysis-engine evaluator.
   It makes no general-IC or exhaustive-domain claim."
  [{:keys [research-command incentive-model deviation-domain research-assignment
           outcome-manifest analysis-output]}]
  (let [output-check (validate-output analysis-output)
        command-check (command/validate-command research-command)
        model-check (model/validate-model incentive-model)
        domain-check (domain/validate-domain deviation-domain)
        assignment-check (assignment/validate-assignment research-assignment)
        outcome-check (outcome/outcome-complete-for-command? research-command outcome-manifest)
        errors (cond-> []
                 (not (:valid? output-check)) (into (:errors output-check))
                 (not (:valid? command-check)) (conj :invalid-research-command)
                 (not (:valid? model-check)) (conj :invalid-incentive-model)
                 (not (:valid? domain-check)) (conj :invalid-deviation-domain)
                 (not (:valid? assignment-check)) (conj :invalid-research-assignment)
                 (not (:complete? outcome-check)) (conj :incomplete-outcome-manifest)
                 (not= (:incentive-model/root incentive-model)
                       (:deviation-domain/incentive-model-root deviation-domain))
                 (conj :domain-model-mismatch)
                 (not= (:command/hash research-command)
                       (:research-assignment/command-root research-assignment))
                 (conj :assignment-command-mismatch)
                 (not= (:command/hash research-command)
                       (:analysis-output/command-root analysis-output))
                 (conj :output-command-mismatch)
                 (not= (:incentive-model/root incentive-model)
                       (:analysis-output/model-root analysis-output))
                 (conj :output-model-mismatch)
                 (not= (:deviation-domain/root deviation-domain)
                       (:analysis-output/domain-root analysis-output))
                 (conj :output-domain-mismatch)
                 (not= (:research-assignment/hash research-assignment)
                       (:analysis-output/assignment-root analysis-output))
                 (conj :output-assignment-mismatch)
                 (not= (:benchmark-outcome/hash outcome-manifest)
                       (:analysis-output/outcome-root analysis-output))
                 (conj :output-outcome-mismatch))
        base {:schema-version verification-schema-version
              :research-analysis-verification/output-root (:research-analysis-output/root analysis-output)
              :research-analysis-verification/command-root (:command/hash research-command)
              :research-analysis-verification/model-root (:incentive-model/root incentive-model)
              :research-analysis-verification/domain-root (:deviation-domain/root deviation-domain)
              :research-analysis-verification/assignment-root (:research-assignment/hash research-assignment)
              :research-analysis-verification/outcome-root (:benchmark-outcome/hash outcome-manifest)
              :research-analysis-verification/status (if (empty? errors) :verified :invalid)
              :research-analysis-verification/claim-status
              (if (empty? errors) (verified-claim-status analysis-output) :not-established)}]
    (assoc base :research-analysis-verification/root (verification-root base)
           :errors (vec errors))))

(defn verification-valid? [verification]
  (and (= :verified (:research-analysis-verification/status verification))
       (= (:research-analysis-verification/root verification)
          (verification-root verification))))

(defn derive-claim
  "Derive a researcher-comparable claim from a verified output report.

   This rejects reports not produced by `verify-output` and deliberately cannot
   derive either positive verification status from a generic engine result."
  [analysis-output verification]
  (when-not (verification-valid? verification)
    (throw (ex-info "Research analysis verification is not valid" {:verification verification})))
  (let [status (:research-analysis-verification/claim-status verification)
        base {:schema-version claim-schema-version
              :research-claim/subject-root (:analysis-output/model-root analysis-output)
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
    (assoc base :research-claim/root (claim-root base))))

(defn validate-claim
  "Validate claim shape and self-root. Claim status is checked against the
   generic assurance boundary; positive proof statuses are rejected here."
  [claim]
  (let [status (:research-claim/status claim)
        errors (cond-> []
                 (not= claim-schema-version (:schema-version claim))
                 (conj :unsupported-claim-schema)
                 (not (every? root? (map claim [:research-claim/subject-root
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
