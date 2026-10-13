(ns resolver-sim.research.proof-generation
  "Canonical provenance receipt for future research proof generation.

   A receipt states that a proof artifact was generated for exact rooted
   inputs. It does not verify proof bytes, authorize a verifier, establish
   primitive validity, or grant application authority."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.runner-participation :as participation]
            [resolver-sim.verification.basis :as verification-basis]))

(def ^:const schema "research-proof-generation-receipt.v1")

(defn receipt-root [receipt]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-proof-generation-receipt-v1
                   (dissoc receipt :research-proof-generation-receipt/root))))

(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn valid-receipt? [receipt]
  (and (map? receipt)
       (= schema (:artifact/schema receipt))
       (= :generated-unverified (:proof-generation/status receipt))
       (= :not-verified (:proof-verification/status receipt))
       (= :not-established (:primitive-validation/status receipt))
       (= :not-established (:application-authority/status receipt))
       (every? #(root? (get receipt %))
               [:research-execution-job-assurance/root
                :resolved-research-dependency-set/root
                :research-run-assurance-evidence/root
                :semantic-result/root :proof-system/identity-root
                :proof-program/identity-root :public-values/input-root
                :proof-artifact/root :prover-participation/root
                :research-execution-environment/root :verifier-registry/root])
       (= (:research-proof-generation-receipt/root receipt)
          (try (receipt-root receipt) (catch Exception _ nil)))))

(defn build-receipt
  [{:keys [assured-job-root resolved-dependency-set-root
           run-assurance-evidence-root semantic-result-root
           proof-system-identity-root proof-program-identity-root
           public-values-input-root proof-artifact-root prover-participation
           execution-environment-root verifier-registry-root]}]
  (when-not (and (:valid? (participation/validate-participation prover-participation))
                 (every? root? [assured-job-root resolved-dependency-set-root
                                run-assurance-evidence-root semantic-result-root
                                proof-system-identity-root proof-program-identity-root
                                public-values-input-root proof-artifact-root
                                execution-environment-root verifier-registry-root]))
    (throw (ex-info "Invalid research proof-generation inputs" {})))
  (let [body {:artifact/schema schema
              :research-execution-job-assurance/root assured-job-root
              :resolved-research-dependency-set/root resolved-dependency-set-root
              :research-run-assurance-evidence/root run-assurance-evidence-root
              :semantic-result/root semantic-result-root
              :proof-system/identity-root proof-system-identity-root
              :proof-program/identity-root proof-program-identity-root
              :public-values/input-root public-values-input-root
              :proof-artifact/root proof-artifact-root
              :prover-participation/root
              (:runner-participation/root prover-participation)
              :research-execution-environment/root execution-environment-root
              :verifier-registry/root verifier-registry-root
              :proof-generation/status :generated-unverified
              :proof-verification/status :not-verified
              :primitive-validation/status :not-established
              :application-authority/status :not-established}
        rooted (assoc body :research-proof-generation-receipt/root
                      (receipt-root body))]
    rooted))

(defn receipt-matches-context?
  "Reject cross-job or cross-dependency proof substitution before verification."
  [receipt assured-job-root resolved-dependency-set-root run-assurance-evidence-root]
  (and (valid-receipt? receipt)
       (= assured-job-root (:research-execution-job-assurance/root receipt))
       (= resolved-dependency-set-root
          (:resolved-research-dependency-set/root receipt))
       (= run-assurance-evidence-root
          (:research-run-assurance-evidence/root receipt))))

(defn verifier-registry-matches?
  "Use the existing generic verifier-registry projection. Registry selection
   authorizes proof verification identity; it never selects the computation."
  [receipt extension-map]
  (= (:verifier-registry/root receipt)
     (verification-basis/verifier-registry-root extension-map)))

(defn unique-proof-count
  "Content-identical receipts count once toward any downstream threshold."
  [receipts]
  (count (set (map :research-proof-generation-receipt/root receipts))))
