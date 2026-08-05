(ns resolver-sim.conformance.identity-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.identity :as identity]))

(defn- ident [& {:keys [canonical kind domain-roots profile included excluded]
                 :or {canonical "sha256:c" kind :trace domain-roots {:solidity "keccak:x"}
                      profile "sha256:p"}}]
  (-> (identity/subject-identity
       {:subject/id "s1" :subject/kind kind :subject/canonical-root canonical
        :subject/domain-roots domain-roots :subject/profile-root profile})
      (cond-> included (assoc :included? true)
              excluded (assoc :excluded? true))))

(deftest subject-identity-record
  (let [i (identity/subject-identity
           {:subject/id "b1" :subject/kind :benchmark
            :subject/canonical-root "sha256:c"
            :subject/domain-roots {:solidity "keccak:x"}
            :subject/identity-policy :research-scenario-identity.v1
            :subject/profile-root "sha256:p"})]
    (is (= "conformance.subject-identity/v1" (:schema-version i)))
    (is (= "b1" (:subject/id i)))
    (is (string? (identity/identity-root i)))
    (is (= #{"keccak:x" "sha256:c"} (identity/identity-roots i))))
  (testing "missing id/root throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/subject-identity {:subject/canonical-root "sha256:c"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (identity/subject-identity {:subject/id "x"})))))

(deftest valid-identities-accepted
  (let [i (ident)
        receipt {:subject/id "s1" :subject/root "keccak:x" :profile-root "sha256:p"}]
    (is (:valid? (identity/validate-identities [i] [receipt])))
    (is (identity/receipt-binds-identity? i receipt))))

(deftest unlinked-root-rejected
  (let [i (ident)
        receipt {:subject/id "s1" :subject/root "sha256:foreign" :profile-root "sha256:p"}]
    (is (not (:valid? (identity/validate-identities [i] [receipt]))))
    (is (some #(= :violation/unlinked-subject-root (:violation/id %))
              (:violations (identity/validate-identities [i] [receipt]))))))

(deftest profile-root-mismatch-rejected
  (let [i (ident)
        receipt {:subject/id "s1" :subject/root "keccak:x" :profile-root "sha256:q"}]
    (is (some #(= :violation/profile-root-mismatch (:violation/id %))
              (:violations (identity/validate-identities [i] [receipt]))))))

(deftest inconsistent-canonical-root-rejected
  (is (some #(= :violation/inconsistent-canonical-root (:violation/id %))
            (:violations (identity/validate-identities
                          [(ident :canonical "sha256:c") (ident :canonical "sha256:OTHER")]
                          [])))))

(deftest multiple-subject-kinds-rejected
  (is (some #(= :violation/multiple-subject-kinds (:violation/id %))
            (:violations (identity/validate-identities
                          [(ident :kind :trace) (ident :kind :benchmark)]
                          [])))))

(deftest inclusion-exclusion-root-conflict-rejected
  (is (some #(= :violation/inclusion-exclusion-root-conflict (:violation/id %))
            (:violations (identity/validate-identities
                          [(ident :canonical "sha256:c" :included true)
                           (ident :canonical "sha256:x" :excluded true)]
                          [])))))

(deftest unknown-subject-id-rejected
  (is (some #(= :violation/unknown-subject-id (:violation/id %))
            (:violations (identity/validate-identities
                          [] [{:subject/id "nope" :subject/root "sha256:x"}])))))
