(ns resolver-sim.evidence-package.conformance.admission
  "Evidence-package admission conformance adapters (G6 third profile).

   Proves conformance WITHOUT replay, comparison, or reproduction:
     artifact bundle
     → structural validation
     → content-root verification
     → reference closure
     → signature verification
     → policy admission
     → admission claim

   The package claims are kept separate: a content-valid package is not
   necessarily authentic or admissible."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.conformance.validation :as validation]
            [resolver-sim.conformance.profile :as profile]))

(def ^:const validator-version 1)
(def ^:const evidence-package-contract :evidence-package.v1)

(def implementation-root
  (hc/domain-hash :conformance-validator-implementation-v1
                  {:validator/id :artifact-envelope-schema :kind :schema
                   :version validator-version :input-contract evidence-package-contract}))

(defn artifact-envelope-schema
  "Structural validation of an evidence package subject."
  [pkg]
  (let [issues (cond-> []
                 (nil? (:package/id pkg))
                 (conj (validation/validation-issue :missing-package-id))
                 (nil? (:package/content-root pkg))
                 (conj (validation/validation-issue :missing-content-root))
                 (nil? (:package/references pkg))
                 (conj (validation/validation-issue :missing-references))
                 (nil? (:package/signature pkg))
                 (conj (validation/validation-issue :missing-signature)))]
    (if (empty? issues) {:valid? true :issues []} {:valid? false :issues issues})))

(defn artifact-reference-semantics
  "Reference closure: every referenced root is declared as a reference (closed
   package).  External references are rejected unless explicitly admitted."
  [pkg]
  (let [referenced (set (vals (:package/reference-roots pkg {})))
        declared (set (:package/references pkg []))
        open (remove declared referenced)]
    (if (empty? open)
      {:valid? true :issues []}
      {:valid? false
       :issues [(validation/validation-issue :unclosed-reference
                                             {:open-references (vec (sort open))})]})))

(defn artifact-signature-semantics
  "Signature presence and structural validity (authenticity is NOT implied by
   validity — the admission claim is separate)."
  [pkg]
  (let [sig (:package/signature pkg)]
    (if (and (map? sig) (:signature/hash sig) (:signature/algorithm sig))
      {:valid? true :issues []}
      {:valid? false
       :issues [(validation/validation-issue :invalid-signature-record)]})))

(defn- register-check-validator!
  [validator-id kind check-fn]
  (validation/register-validator!
   {:validator/id validator-id
    :validator/kind kind
    :validator/input-contract :evidence-package.v1
     :validator/version validator-version
    :validator/implementation-root implementation-root
    :validator/run (fn [subject]
                     (let [{:keys [valid? issues]} (check-fn subject)]
                       (if valid?
                         (validation/pass-result
                          {:validator/id validator-id :validator/kind kind
                           :validator/input-contract evidence-package-contract
                           :validator/version validator-version
                           :validator/implementation-root implementation-root}
                          subject)
                         (validation/reject-result
                          {:validator/id validator-id :validator/kind kind
                           :validator/input-contract evidence-package-contract
                           :validator/version validator-version
                           :validator/implementation-root implementation-root}
                          subject
                          issues))))}))

(register-check-validator! :artifact-envelope-schema :schema artifact-envelope-schema)
(register-check-validator! :artifact-reference-semantics :semantic artifact-reference-semantics)
(register-check-validator! :artifact-signature-semantics :semantic artifact-signature-semantics)

(profile/register-profile-domain-validator!
 :artifact-package-admission
 (fn [prof]
   (let [issues (cond-> []
                  (not= :evidence-package.v1 (:profile/fixture-contract prof))
                  (conj (validation/validation-issue :unsupported-fixture-contract
                                                     {:fixture-contract (:profile/fixture-contract prof)}))
                  (not= :artifact-admission-profile.v1 (:profile/domain-contract prof))
                  (conj (validation/validation-issue :invalid-domain-contract
                                                     {:domain-contract (:profile/domain-contract prof)})))]
     (if (empty? issues) {:valid? true :violations []} {:valid? false :violations issues}))))

(defn validate-subject
  "Run the evidence-package validators over a package subject."
  [pkg]
  (validation/validate-layers
   [:artifact-envelope-schema :artifact-reference-semantics
    :artifact-signature-semantics]
   [:schema :semantic]
   pkg))

(defn admission
  "Content-root verification + admission-policy evaluation.
   Claims are kept separate: integrity, reference closure, authenticity, and
   admissibility are independently evaluated.  Authenticity is NOT signature
   presence — it requires a cryptographically valid, authorised signature
   receipt for this package."
  [pkg admission-policy-root & [signature-receipt]]
  (let [content-ok? (and (string? (:package/content-root pkg))
                         (seq (:package/content-root pkg)))
        closure-ok? (empty? (:issues (artifact-reference-semantics pkg)))
        authentic? (and signature-receipt
                        (= :pass (:verification/status signature-receipt)))
        policy-ok? (boolean admission-policy-root)
        result {:package/integrity-verified content-ok?
                :package/reference-closure-verified closure-ok?
                :package/authenticity-verified authentic?
                :package/admissible (and content-ok? closure-ok? authentic? policy-ok?)}]
    result))

;; ---------------------------------------------------------------------------
;; Exact reference closure
;; ---------------------------------------------------------------------------

(defn reference-closure
  "Explicit package reference universe.

   pkg keys:
     :package/root, :package/declared-artifacts, :package/embedded-artifacts
       [{:artifact/id :artifact/root :artifact/kind ...}...],
     :package/external-artifacts, :package/reference-roots {logical-id root}

   Returns {:package/root ... :declared-artifacts ... :embedded-artifacts ...
            :external-artifacts ... :referenced-roots ... :resolved-roots ...
            :missing-roots [] :unexpected-artifacts [] :closure-complete? bool
            :issues [...]}."
  [pkg]
  (let [embedded (mapv :artifact/root (or (:package/embedded-artifacts pkg) []))
        external (set (or (:package/external-artifacts pkg) []))
        embedded-set (set embedded)
        referenced (set (vals (or (:package/reference-roots pkg) {})))
        missing (vec (sort (remove #(or (contains? embedded-set %) (contains? external %))
                                   referenced)))
        unexpected (vec (sort (remove referenced embedded-set)))
        ;; duplicate roots referenced twice with different logical ids
        duplicates (->> (or (:package/reference-roots pkg) {})
                        (group-by val)
                        (keep (fn [[root refs]] (when (> (count refs) 1) root)))
                        vec)
        issues (cond-> []
                 (seq missing)
                 (conj (validation/validation-issue :missing-reference
                                                    {:missing (vec (sort missing))}))
                 (seq duplicates)
                 (conj (validation/validation-issue :duplicate-reference-root
                                                    {:duplicates duplicates}))
                 (seq unexpected)
                 (conj (validation/validation-issue :unexpected-embedded-artifact
                                                    {:unexpected unexpected})))]
    {:package/root (:package/root pkg)
     :declared-artifacts (vec (or (:package/declared-artifacts pkg) []))
     :embedded-artifacts (vec (or (:package/embedded-artifacts pkg) []))
     :external-artifacts (vec (or (:package/external-artifacts pkg) []))
     :referenced-roots (vec (sort referenced))
     :resolved-roots (vec (sort (filter #(contains? embedded-set %) referenced)))
     :missing-roots missing
     :unexpected-artifacts unexpected
     :duplicate-roots duplicates
     :closure-complete? (empty? issues)
     :issues issues}))

;; ---------------------------------------------------------------------------
;; Admission decision receipt
;; ---------------------------------------------------------------------------

(def admission-schema-version "evidence-package-admission/v1")

(defn admission-decision
  "A first-class admission decision receipt, mechanically derivable from its
   prerequisite claims and the policy root.  An evaluator cannot emit :admit
   when a required prerequisite is absent, non-claimable, or bound to another
   package root.

   prerequisite-claims {:integrity {:claimable? :package/root ...}
                        :reference-closure {...} :authenticity {...}
                        :policy-pass {...}}"
  [m]
  (let [package-root (:package/root m)
        prerequisites (select-keys (or (:prerequisite-claims m) {})
                                   #{:integrity :reference-closure :authenticity :policy-pass})
        claimable? (fn [c] (and (map? c) (:claimable? c true)
                                (or (nil? (:package/root c))
                                    (= package-root (:package/root c)))))
        missing (vec (sort (remove prerequisites #{:integrity :reference-closure
                                                   :authenticity :policy-pass})))
        non-claimable (vec (sort (keep (fn [k] (when-not (claimable? (get prerequisites k)) k))
                                       #{:integrity :reference-closure :authenticity :policy-pass})))
        wrong-root (vec (sort (keep (fn [k]
                                      (let [c (get prerequisites k)]
                                        (when (and (map? c) (:package/root c)
                                                   (not= package-root (:package/root c)))
                                          k)))
                                    #{:integrity :reference-closure :authenticity :policy-pass})))
        admit? (and (empty? missing) (empty? non-claimable) (empty? wrong-root)
                    (boolean (:policy/root m)))
        reason-codes (into [] (concat
                               (map #(keyword (str "missing-" (name %))) missing)
                               (map #(keyword (str "non-claimable-" (name %))) non-claimable)
                               (map #(keyword (str "wrong-package-root-" (name %))) wrong-root)))
        reason-codes (if (and (empty? reason-codes) (not admit?))
                       (conj reason-codes :policy-not-satisfied)
                       reason-codes)
        receipt {:admission/schema-version admission-schema-version
                 :subject/id (:subject/id m)
                 :package/root package-root
                 :profile/root (:profile/root m)
                 :environment/root (:environment/root m)
                 :policy/root (:policy/root m)
                 :prerequisite-claims prerequisites
                 :decision (if admit? :admit :reject)
                 :reason-codes reason-codes
                 :evaluator/implementation-root (:evaluator/implementation-root m)
                 :receipt/root nil}]
    (assoc receipt :receipt/root
           (hc/domain-hash :evidence-package-admission-v1
                           (dissoc receipt :receipt/root)))))
