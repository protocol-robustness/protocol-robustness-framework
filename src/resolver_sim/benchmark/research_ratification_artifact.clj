(ns resolver-sim.benchmark.research-ratification-artifact
  "Content-addressed retained inputs for independently reconstructed research
   ratification. A valid research result does not imply rewards or authority."
  (:require [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.research.ratification :as ratification]))

(def schema-version "prf/research-ratification-artifact.v1")
(def incentive-root-fields
  [:incentive/policy-root :incentive/eligibility-root :incentive/obligation-root])

(defn artifact-root [artifact]
  (ref/sha256-ref
   (canonical/domain-hash :research-ratification-artifact-v1
                          (dissoc artifact :artifact/root))))

(defn- incentive-links-valid? [links]
  (or (nil? links)
      (and (map? links)
           (every? #(ref/valid-sha256-ref? (get links %)) incentive-root-fields))))

(defn verify-artifact
  "Recompute claim, signature/policy evaluation, and the complete artifact
   root from retained inputs and caller-supplied trusted key bindings."
  [artifact key-bindings]
  (let [claim (:artifact/claim artifact)
        target (:artifact/target artifact)
        policy (:artifact/policy artifact)
        contributions (:artifact/contributions artifact)
        trust-roots (:artifact/trust-roots artifact)
        key-registry-root (:artifact/key-registry-root artifact)
        analysis-context (:artifact/analysis-context artifact)
        analysis-verification (when (map? analysis-context)
                                (analysis/verify-output analysis-context))
        derived-claim (when (and analysis-verification
                                 (= :verified (:research-analysis-verification/status
                                               analysis-verification)))
                        (analysis/derive-claim (:analysis-output analysis-context)
                                               analysis-verification
                                               (:incentive-model analysis-context)))
        reconstructed (try
                        (ratification/reconstruct-ratification
                         {:target target :policy policy :contributions contributions
                          :key-bindings key-bindings})
                        (catch clojure.lang.ExceptionInfo _ nil))
        errors (cond-> []
                 (not= schema-version (:schema/version artifact)) (conj :unsupported-schema)
                 (not (:valid? (analysis/validate-claim claim))) (conj :invalid-claim)
                 (nil? derived-claim) (conj :analysis-inputs-not-independently-verified)
                 (and derived-claim (not= claim derived-claim)) (conj :claim-reconstruction-mismatch)
                 (not= (:research-claim/root claim) (:target/subject-root target))
                 (conj :target-claim-mismatch)
                 (not= (:research-claim/evidence-root claim) (:target/evidence-root target))
                 (conj :target-evidence-mismatch)
                 (not (incentive-links-valid? (:artifact/incentive-links artifact)))
                 (conj :invalid-incentive-links)
                 (not= false (:artifact/authority-issued? artifact))
                 (conj :research-ratification-cannot-issue-authority)
                 (not= false (:artifact/rewards-created? artifact))
                 (conj :research-ratification-cannot-create-rewards)
                 (not (seq trust-roots)) (conj :missing-trust-roots)
                 (not (ref/valid-sha256-ref? key-registry-root))
                 (conj :invalid-key-registry-root)
                 (some #(not (ref/valid-sha256-ref? %)) trust-roots)
                 (conj :invalid-trust-root)
                 (not (some #{(:policy/authority-root policy)} trust-roots))
                 (conj :policy-authority-not-in-trust-roots)
                 (not (some #{key-registry-root} trust-roots))
                 (conj :key-registry-not-in-trust-roots)
                 (nil? reconstructed) (conj :ratification-reconstruction-failed)
                 (and reconstructed (not= reconstructed (:artifact/ratification artifact)))
                 (conj :ratification-reconstruction-mismatch)
                 (not= (:artifact/root artifact) (artifact-root artifact))
                 (conj :artifact-root-mismatch))]
    {:valid? (empty? errors)
     :errors errors
     :verification/claim (if (and derived-claim (= claim derived-claim)) :verified :unverified)
     :verification/ratification (if (and reconstructed
                                         (= reconstructed (:artifact/ratification artifact)))
                                  :verified :unverified)
     :verification/trust-roots (if (and (seq trust-roots)
                                        (every? ref/valid-sha256-ref? trust-roots)
                                        (some #{(:policy/authority-root policy)} trust-roots)
                                        (some #{key-registry-root} trust-roots))
                                 :externally-anchored :unverified)
     :ratification/recomputed reconstructed}))

(defn build-artifact
  [{:keys [claim analysis-context target policy contributions key-bindings trust-roots
           key-registry-root
           incentive-links]}]
  (let [ratification-result
        (ratification/reconstruct-ratification
         {:target target :policy policy :contributions contributions
          :key-bindings key-bindings})
        artifact {:schema/version schema-version
                  :artifact/claim claim
                  :artifact/analysis-context analysis-context
                  :artifact/target target
                  :artifact/policy policy
                  :artifact/contributions (vec contributions)
                  :artifact/ratification ratification-result
                  :artifact/trust-roots (vec (sort trust-roots))
                  :artifact/key-registry-root key-registry-root
                  :artifact/incentive-links incentive-links
                  :artifact/authority-issued? false
                  :artifact/rewards-created? false}
        artifact (assoc artifact :artifact/root (artifact-root artifact))
        verification (verify-artifact artifact key-bindings)]
    (when-not (:valid? verification)
      (throw (ex-info "Research ratification artifact failed reconstruction"
                      {:errors (:errors verification)})))
    artifact))

(defn persist-artifact!
  "Persist a verified artifact under its semantic root in PRF's immutable CAS."
  [store artifact key-bindings]
  (let [verification (verify-artifact artifact key-bindings)]
    (when-not (:valid? verification)
      (throw (ex-info "Refusing to persist an unverified research ratification"
                      {:errors (:errors verification)})))
    (cas/put-if-absent!
     store {:hash-reference (:artifact/root artifact)
            :artifact artifact
            :verify #(-> (verify-artifact % key-bindings) :valid?)})))

(defn resolve-artifact
  "Resolve and independently verify a retained artifact by its semantic root."
  [store artifact-root key-bindings]
  (cas/verify-stored-artifact
   store artifact-root #(-> (verify-artifact % key-bindings) :valid?)))
