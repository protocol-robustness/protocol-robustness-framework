(ns resolver-sim.research.accounting-proof-admission
  "Proof-backed admission for checked-i64 native accounting partitions.

   This is a separate consequence from native accounting V1 validation and
   never changes that schema's same-implementation-recomputation meaning."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accounting-arithmetic-sp1-host :as sp1]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.proof-generation :as proof]
            [resolver-sim.research.execution-control :as control]))

(def schema "accounting-proof-backed-admission.v1")

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn admission-root [admission]
  (root :accounting-proof-backed-admission-v1
        (dissoc admission :accounting-proof-backed-admission/root)))

(defn valid-admission-binding?
  "Check admission envelope identity and exact constituent binding. This does
   not itself cryptographically verify proof bytes; use `verify-and-build!` to
   create an admission, or re-run that verifier when importing one."
  [work execution admission]
  (let [partial (:accounting-partial execution)
        assurance (:run-assurance-evidence execution)
        generation (:research-proof-generation-receipt admission)
        verification (:research-proof-verification-evidence admission)]
    (and (= schema (:artifact/schema admission))
         (native/validate-execution-lineage work execution)
         (= (:research-native-accounting-work/root work)
            (:research-native-accounting-work/root admission))
         (= (native/execution-root execution)
            (:research-native-accounting-run/root admission))
         (= (:accounting-request/root (:accounting/request work))
            (:accounting-request/root admission))
         (= (:accounting-partition-plan/root (:accounting/partition-plan work))
            (:accounting-partition-plan/root admission))
         (= (:accounting-work-unit/root (:accounting/work-unit work))
            (:accounting-work-unit/root admission))
         (= (:accounting-partial-result/root partial)
            (:accounting-partial-result/root admission))
         (= (accounting/partial-semantic-root partial)
            (:canonical-accounting-partial/root admission))
         (proof/receipt-matches-context?
          generation (get-in work [:work/assured-job :research-execution-job-assurance/root])
          (get-in work [:work/resolved-dependencies :resolved-research-dependency-set/root])
          (:research-run-assurance-evidence/root assurance))
         (= (:proof/artifact-root admission) (:proof-artifact/root generation))
         (= (:proof/public-values-root admission) (:public-values/input-root generation))
         (control/valid-proof-verification? verification)
         (= (:research-proof-generation-receipt/root generation)
            (:research-proof-generation-receipt/root verification))
         (= (:semantic-result/root assurance) (:semantic-result/root verification))
         (= (:research-proof-generation-receipt/root generation)
            (:research-proof-generation-receipt/root admission))
         (= (:research-proof-verification-evidence/root verification)
            (:research-proof-verification-evidence/root admission))
         (= :proof-verified (:accounting/arithmetic-assurance admission))
         (= :not-established (:primitive-validation/status admission))
         (= :not-established (:application-authority/status admission))
         (= (:accounting-proof-backed-admission/root admission)
            (try (admission-root admission) (catch Exception _ nil))))))

(defn verify-and-build!
  "Cryptographically verify a real SP1 proof against the exact coordinator-side
   work/execution lineage, then build a separately rooted admission artifact.
   Proof paths are operational references and are not included in its root."
  [{:keys [work execution] :as input}]
  (let [{:keys [statement generation-receipt verification-evidence proof-identities]}
        (sp1/verify-existing-proof! input)
        partial (:accounting-partial execution)
        body {:artifact/schema schema
              :research-native-accounting-work/root
              (:research-native-accounting-work/root work)
              :research-native-accounting-run/root (native/execution-root execution)
              :accounting-request/root (get-in work [:accounting/request :accounting-request/root])
              :accounting-partition-plan/root
              (get-in work [:accounting/partition-plan :accounting-partition-plan/root])
              :accounting-work-unit/root
              (get-in work [:accounting/work-unit :accounting-work-unit/root])
              :accounting-partial-result/root (:accounting-partial-result/root partial)
              :canonical-accounting-partial/root
              (:canonical-accounting-partial/root statement)
              :research-proof-generation-receipt/root
              (:research-proof-generation-receipt/root generation-receipt)
              :research-proof-verification-evidence/root
              (:research-proof-verification-evidence/root verification-evidence)
              :research-proof-generation-receipt generation-receipt
              :research-proof-verification-evidence verification-evidence
              :proof/artifact-root (:proof-root proof-identities)
              :proof/public-values-root (:public-values-root proof-identities)
              :accounting/arithmetic-assurance :proof-verified
              :primitive-validation/status :not-established
              :application-authority/status :not-established}
        admission (assoc body :accounting-proof-backed-admission/root
                         (admission-root body))]
    (when-not (valid-admission-binding? work execution admission)
      (throw (ex-info "Constructed accounting proof admission is inconsistent" {})))
    admission))
