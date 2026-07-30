(ns resolver-sim.evidence.attestation-completeness-profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.attestation-completeness-profile :as acp]))

;; ── validate-profile ───────────────────────────────────────────────────────

(deftest validate-profile-accepts-valid-sets
  (let [p (acp/make-profile :review {})]
    (is (= p (acp/validate-profile p))
        "valid profile passes validate-profile and returns the profile")))

(deftest validate-profile-accepts-development-mode
  (let [p (acp/make-profile :development {})]
    (is (= p (acp/validate-profile p)))))

(deftest validate-profile-rejects-nil-required
  (is (thrown? clojure.lang.ExceptionInfo
               (acp/validate-profile
                {:profile/schema-version acp/profile-schema-version
                 :profile/mode :review
                 :profile/rules {:evidence/required nil
                                 :evidence/optional #{:evidence-nodes}
                                 :evidence/sensitivity-controlled #{}
                                 :sensitivity/missing-decision :fail
                                 :sensitivity/empty-evidence-set :warn}}))
      "nil in a required set is rejected"))

(deftest validate-profile-rejects-vector-instead-of-set
  (is (thrown? clojure.lang.ExceptionInfo
               (acp/validate-profile
                {:profile/schema-version acp/profile-schema-version
                 :profile/mode :review
                 :profile/rules {:evidence/required [:attestation-records]
                                 :evidence/optional #{}
                                 :evidence/sensitivity-controlled #{}
                                 :sensitivity/missing-decision :fail
                                 :sensitivity/empty-evidence-set :warn}}))
      "vector where a set is expected is rejected"))

(deftest validate-profile-rejects-non-keyword-elements
  (is (thrown? clojure.lang.ExceptionInfo
               (acp/validate-profile
                {:profile/schema-version acp/profile-schema-version
                 :profile/mode :review
                 :profile/rules {:evidence/required #{"not-a-keyword"}
                                 :evidence/optional #{}
                                 :evidence/sensitivity-controlled #{}
                                 :sensitivity/missing-decision :fail
                                 :sensitivity/empty-evidence-set :warn}}))
      "non-keyword elements in a set are rejected"))

(deftest validate-profile-accepts-empty-sets
  (let [p (acp/validate-profile
           {:profile/schema-version acp/profile-schema-version
            :profile/mode :review
            :profile/rules {:evidence/required #{}
                            :evidence/optional #{}
                            :evidence/sensitivity-controlled #{}
                            :signature/required true
                            :sensitivity/missing-decision :fail
                            :sensitivity/empty-evidence-set :warn}})]
    (is (some? p) "empty sets pass validation — vacuous truth for keyword check")))

(deftest validate-profile-accepts-unknown-keywords-in-sets
  (let [p (acp/validate-profile
           {:profile/schema-version acp/profile-schema-version
            :profile/mode :review
            :profile/rules {:evidence/required #{:evidence-nodes :registry-snapshots}
                            :evidence/optional #{:attestation-records}
                            :evidence/sensitivity-controlled #{}
                            :signature/required true
                            :sensitivity/missing-decision :fail
                            :sensitivity/empty-evidence-set :warn}})]
    (is (some? p) "unknown keywords are valid keywords — validator only checks shape, not vocabulary")))

(deftest validate-profile-rejects-unsupported-mode
  (is (thrown? clojure.lang.ExceptionInfo
               (acp/validate-profile
                {:profile/schema-version acp/profile-schema-version
                 :profile/mode :invalid-mode
                 :profile/rules {:evidence/required #{}
                                 :evidence/optional #{}
                                 :evidence/sensitivity-controlled #{}
                                 :sensitivity/missing-decision :fail
                                 :sensitivity/empty-evidence-set :warn}}))
      "unsupported mode is rejected"))

(deftest validate-profile-rejects-unsupported-schema-version
  (is (thrown? clojure.lang.ExceptionInfo
               (acp/validate-profile
                {:profile/schema-version "unknown"
                 :profile/mode :review
                 :profile/rules {:evidence/required #{}
                                 :evidence/optional #{}
                                 :evidence/sensitivity-controlled #{}
                                 :sensitivity/missing-decision :fail
                                 :sensitivity/empty-evidence-set :warn}}))
      "unsupported schema version is rejected"))

(deftest validate-profile-rejects-wrong-decision-values
  (is (thrown? clojure.lang.ExceptionInfo
               (acp/validate-profile
                {:profile/schema-version acp/profile-schema-version
                 :profile/mode :review
                 :profile/rules {:evidence/required #{}
                                 :evidence/optional #{}
                                 :evidence/sensitivity-controlled #{}
                                 :sensitivity/missing-decision :invalid
                                 :sensitivity/empty-evidence-set :fail}}))
      "invalid decision value is rejected"))

(deftest validate-profile-hash-mismatch-rejected
  (is (thrown? clojure.lang.ExceptionInfo
               (acp/validate-profile
                (assoc (acp/make-profile :review {})
                       :profile/hash "sha256:tampered")))))

;; ── make-profile ───────────────────────────────────────────────────────────

(deftest make-profile-review-has-required-fields
  (let [p (acp/make-profile :review {})]
    (is (= "attestation-completeness-profile.v1" (:profile/schema-version p)))
    (is (= :review (:profile/mode p)))
    (is (some? (:profile/hash p)))
    (is (= #{:attestation-records :claim-results} (get-in p [:profile/rules :evidence/required])))
    (is (= #{:evidence-nodes :registry-snapshots} (get-in p [:profile/rules :evidence/optional])))
    (is (= #{:attestation-records :evidence-nodes} (get-in p [:profile/rules :evidence/sensitivity-controlled])))
    (is (= :fail (get-in p [:profile/rules :sensitivity/missing-decision])))
    (is (= :fail (get-in p [:profile/rules :sensitivity/empty-evidence-set])))))

(deftest make-profile-development-has-all-optional
  (let [p (acp/make-profile :development {})]
    (is (= :development (:profile/mode p)))
    (is (= #{} (get-in p [:profile/rules :evidence/required])))
    (is (seq (get-in p [:profile/rules :evidence/optional])))
    (is (= :warn (get-in p [:profile/rules :sensitivity/missing-decision])))
    (is (= :warn (get-in p [:profile/rules :sensitivity/empty-evidence-set])))))

(deftest make-profile-supports-overrides
  (let [p (acp/make-profile :review {:required-evidence-categories #{:custom-category}
                                     :missing-sensitivity-decision :warn})]
    (is (= #{:custom-category} (get-in p [:profile/rules :evidence/required])))
    (is (= :warn (get-in p [:profile/rules :sensitivity/missing-decision])))))

(deftest make-profile-hash-is-deterministic
  (is (= (:profile/hash (acp/make-profile :review {}))
         (:profile/hash (acp/make-profile :review {})))))

(deftest make-profile-different-modes-different-hash
  (is (not= (:profile/hash (acp/make-profile :review {}))
            (:profile/hash (acp/make-profile :development {})))))

;; ── resolve-profile ────────────────────────────────────────────────────────

(deftest resolve-profile-review
  (let [p (acp/resolve-profile :review)]
    (is (= :review (:profile/mode p)))
    (is (some? (:profile/hash p)))))

(deftest resolve-profile-development
  (let [p (acp/resolve-profile :development)]
    (is (= :development (:profile/mode p)))))

(deftest resolve-profile-unknown-throws
  (is (thrown? clojure.lang.ExceptionInfo (acp/resolve-profile :nonexistent))))

;; ── evaluate-evidence-status ───────────────────────────────────────────────

(deftest evaluate-evidence-all-present
  (let [p (acp/make-profile :review {})
        objects [{:object/kind :attestation-record}
                 {:object/kind :claim-result}]]
    (is (some? (acp/evaluate-evidence-status p {:bundle/objects objects})))))

(deftest evaluate-evidence-missing-required-invalid
  (let [p (acp/make-profile :review {})]
    (is (= :invalid
           (acp/evaluate-evidence-status p {:bundle/objects []})))))

(deftest evaluate-evidence-empty-set-invalid
  (let [p (acp/make-profile :review {})]
    (is (= :invalid
           (acp/evaluate-evidence-status p {:bundle/objects []})))))

(deftest evaluate-evidence-blocked-by-sensitivity
  (let [p (acp/make-profile :review {})
        objects [{:object/kind :attestation-record}
                 {:object/kind :claim-result}]]
    (is (= :blocked-by-sensitivity-policy
           (acp/evaluate-evidence-status p {:bundle/objects objects
                                            :sensitivity/decision :blocked})))))

(deftest evaluate-evidence-allowed-sensitivity
  (let [p (acp/make-profile :review {})
        objects [{:object/kind :attestation-record}
                 {:object/kind :claim-result}
                 {:object/kind :evidence-node}]]
    (is (= :hash-linked
           (acp/evaluate-evidence-status p {:bundle/objects objects
                                            :sensitivity/decision :allowed})))))
