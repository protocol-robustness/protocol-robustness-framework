(ns resolver-sim.evidence.attestation-policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation :as att]
            [resolver-sim.evidence.attestation-registry :as ar]
            [resolver-sim.evidence.attestation-policy :as apol]))

(defn- attestor [] {:type :ci-runner :id :ci-validation})
(defn- subject [] {:type :evidence-node :hash "sha256:abc"})

(defn- build-a
  [& {:keys [signed-at claim claim-id signing-key-id attestor-id subject-kind subject-hash]
      :or {signed-at "2025-01-01T00:00:00Z" claim :verified}}]
  (let [a (or attestor-id :ci-validation)
        sk (or subject-kind :evidence-node)
        sh (or subject-hash "sha256:abc")]
    (att/build-attestation
     (or (when attestor-id {:type :ci-runner :id attestor-id}) (attestor))
     (if (= :claim sk) {:type :claim :claim-id (or claim-id :consistency)} {:type sk :hash sh})
     claim
     (cond-> {:signed-at signed-at}
       claim-id (assoc :claim-id claim-id)
       signing-key-id (assoc :signing-key-id signing-key-id)
       signing-key-id (assoc :signing-fn (fn [_]
                                           {:algorithm :ed25519
                                            :public-key-id signing-key-id
                                            :signature-bytes "deadbeef"}))))))

(defn- default-policy
  []
  {:policy-id :default
   :description "Default test policy"
   :rules [{:policy-id :evidence-rule
            :subject-kinds #{:evidence-node}
            :attestors #{:ci-validation}
            :claim-results #{:verified :reproduced :certified :approved :rejected}}
           {:policy-id :claim-rule
            :subject-kinds #{:claim}
            :claim-ids #{:conservation :non-negative}
            :attestors #{:ci-validation :auditor}
            :claim-results #{:verified :reproduced}}]})

;; ── register-policy! ────────────────────────────────────────────────────────

(deftest register-policy-stores-by-id
  (apol/with-fresh-registry
    (let [p (apol/register-policy! (default-policy))]
      (is (= p (apol/find-policy :default))))))

(deftest register-policy-overwrites-existing
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (let [p2 (apol/register-policy!
              {:policy-id :default :description "v2" :rules []})]
      (is (= "v2" (:description (apol/find-policy :default)))))))

(deftest find-policy-returns-nil-for-unknown
  (apol/with-fresh-registry
    (is (nil? (apol/find-policy :nonexistent)))))

(deftest all-policies-returns-registered
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (apol/register-policy! {:policy-id :secondary :description "secondary" :rules []})
    (is (= 2 (count (apol/all-policies))))))

;; ── rule-matches? ────────────────────────────────────────────────────────────

(deftest rule-matches-attestor
  (apol/with-fresh-registry
    (let [rule {:attestors #{:ci-validation}}
          a (build-a)]
      (is (:matches? (#'apol/rule-matches? a rule)))
      (let [a2 (build-a :attestor-id :unauthorized)]
        (is (false? (:matches? (#'apol/rule-matches? a2 rule))))))))

(deftest rule-matches-subject-kind
  (apol/with-fresh-registry
    (let [rule {:subject-kinds #{:evidence-node}}
          a (build-a)]
      (is (:matches? (#'apol/rule-matches? a rule)))
      (let [a2 (build-a :subject-kind :claim :claim-id :consistency)]
        (is (false? (:matches? (#'apol/rule-matches? a2 rule))))))))

(deftest rule-matches-claim-id
  (apol/with-fresh-registry
    (let [rule {:claim-ids #{:conservation}}
          a (build-a :claim-id :conservation)]
      (is (:matches? (#'apol/rule-matches? a rule)))
      (let [a2 (build-a :claim-id :consistency)]
        (is (false? (:matches? (#'apol/rule-matches? a2 rule))))))))

(deftest rule-matches-claim-result
  (apol/with-fresh-registry
    (let [rule {:claim-results #{:verified :approved}}
          a (build-a :claim :verified)]
      (is (:matches? (#'apol/rule-matches? a rule)))
      (let [a2 (build-a :claim :rejected)]
        (is (false? (:matches? (#'apol/rule-matches? a2 rule))))))))

(deftest rule-nil-set-allows-all
  (apol/with-fresh-registry
    (let [rule {}  ;; no restrictions
          a (build-a :attestor-id :anything :claim :anything)]
      (is (:matches? (#'apol/rule-matches? a rule))))))

;; ── evaluate-attestation ─────────────────────────────────────────────────────

(deftest evaluate-compliant-evidence-attestation
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (let [a (build-a)
          result (apol/evaluate-attestation a :default)]
      (is (true? (:compliant? result)))
      (is (= :default (:policy-id result)))
      (is (= :evidence-rule (:policy-id (:matched-rule result)))))))

(deftest evaluate-compliant-claim-attestation
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (let [a (build-a :subject-kind :claim :claim-id :conservation)
          result (apol/evaluate-attestation a :default)]
      (is (true? (:compliant? result)))
      (is (= :claim-rule (:policy-id (:matched-rule result)))))))

(deftest evaluate-non-compliant-attestor
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (let [a (build-a :attestor-id :unauthorized)
          result (apol/evaluate-attestation a :default)]
      (is (false? (:compliant? result)))
      (is (seq (:reasons result))))))

(deftest evaluate-non-compliant-claim-id
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (let [a (build-a :subject-kind :claim :claim-id :unregistered-claim)
          result (apol/evaluate-attestation a :default)]
      (is (false? (:compliant? result))))))

(deftest evaluate-non-compliant-subject-kind
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (let [a (build-a :subject-kind :invalid-kind)
          result (apol/evaluate-attestation a :default)]
      (is (false? (:compliant? result))))))

(deftest evaluate-returns-policy-not-found
  (apol/with-fresh-registry
    (let [a (build-a)
          result (apol/evaluate-attestation a :nonexistent)]
      (is (= :policy-not-found (:compliant? result))))))

(deftest evaluate-defaults-to-policy-id-default
  (apol/with-fresh-registry
    (let [a (build-a)
          result (apol/evaluate-attestation a)]
      (is (= :policy-not-found (:compliant? result))))))

(deftest evaluate-multiple-rules-or-semantics
  (apol/with-fresh-registry
    (let [policy {:policy-id :multi
                  :description "At least one rule must match"
                  :rules [{:policy-id :rule-a :attestors #{:attestor-a}}
                          {:policy-id :rule-b :attestors #{:attestor-b}}]}
          a (build-a :attestor-id :attestor-b)]
      (apol/register-policy! policy)
      (let [result (apol/evaluate-attestation a :multi)]
        (is (true? (:compliant? result)))
        (is (= :rule-b (:policy-id (:matched-rule result))))))))

;; ── check-attestation ───────────────────────────────────────────────────────

(deftest check-attestation-inline-rules
  (let [a (build-a)
        rules [{:attestors #{:ci-validation} :subject-kinds #{:evidence-node}}]
        result (apol/check-attestation a rules)]
    (is (true? (:compliant? result)))))

(deftest check-attestation-inline-non-compliant
  (let [a (build-a :attestor-id :unauthorized)
        rules [{:attestors #{:ci-validation}}]
        result (apol/check-attestation a rules)]
    (is (false? (:compliant? result)))
    (is (seq (:reasons result)))))

;; ── check-registry ──────────────────────────────────────────────────────────

(deftest check-registry-all-compliant
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (ar/with-fresh-registry
      (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
      (ar/register-attestation! (build-a :signed-at "2025-01-02T00:00:00Z" :claim :reproduced))
      (let [result (apol/check-registry :default)]
        (is (= 2 (:total-checked result)))
        (is (= 2 (:compliant-count result)))
        (is (= 0 (:non-compliant-count result)))))

    (deftest check-registry-detects-non-compliant
      (apol/with-fresh-registry
        (apol/register-policy! (default-policy))
        (ar/with-fresh-registry
          (ar/register-attestation! (build-a :signed-at "2025-01-01T00:00:00Z"))
          (ar/register-attestation! (build-a :signed-at "2025-01-02T00:00:00Z" :attestor-id :unauthorized))
          (let [result (apol/check-registry :default)]
            (is (= 2 (:total-checked result)))
            (is (= 1 (:compliant-count result)))
            (is (= 1 (:non-compliant-count result))))))))

  (deftest check-registry-empty
    (apol/with-fresh-registry
      (apol/register-policy! (default-policy))
      (ar/with-fresh-registry
        (let [result (apol/check-registry :default)]
          (is (= 0 (:total-checked result)))
          (is (= 0 (:compliant-count result)))
          (is (= 0 (:non-compliant-count result))))))))

;; ── with-fresh-registry ─────────────────────────────────────────────────────

(deftest with-fresh-registry-isolates-policies
  (apol/with-fresh-registry
    (apol/register-policy! (default-policy))
    (is (some? (apol/find-policy :default)))
    (apol/with-fresh-registry
      (is (nil? (apol/find-policy :default)))
      (apol/register-policy! {:policy-id :nested :description "nested" :rules []})
      (is (some? (apol/find-policy :nested))))
    (is (some? (apol/find-policy :default)))
    (is (nil? (apol/find-policy :nested)))))
