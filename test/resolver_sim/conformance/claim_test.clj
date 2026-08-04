(ns resolver-sim.conformance.claim-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.claim :as claim]))

(deftest claim-classes-are-stable
  (is (= #{:attested :reproduced :candidate-compatible
           :accepted-divergence :not-evaluated}
         claim/claim-classes)))

(deftest mode-permissions
  (is (= #{:attested} (claim/permitted-claims-for-mode :attested)))
  (is (= #{:reproduced} (claim/permitted-claims-for-mode :reproduce)))
  (is (= #{:candidate-compatible :accepted-divergence :not-evaluated}
         (claim/permitted-claims-for-mode :candidate)))
  (is (= (claim/permitted-claims-for-mode :compare)
         (claim/permitted-claims-for-mode :candidate)))
  (is (nil? (claim/permitted-claims-for-mode :unknown-mode))))

(deftest claim-result-valid
  (let [r (claim/claim-result :attested :attested :pass
                              {:subject/root "sha256:x"
                               :profile/root "sha256:y"})]
    (is (= :attested (:evaluation/mode r)))
    (is (= :attested (:claim/class r)))
    (is (= :pass (:claim/status r)))
    (is (= "sha256:x" (:subject/root r)))
    (is (claim/claim-consistent? r))))

(deftest claim-result-rejects-stronger-claim
  (testing "a mode cannot emit a claim class it is not permitted to claim"
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim/claim-result :candidate :attested)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim/claim-result :attested :candidate-compatible)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (claim/claim-result :reproduce :attested)))))

(deftest claim-result-rejects-unknown-mode-and-status
  (is (thrown? clojure.lang.ExceptionInfo
               (claim/claim-result :unknown-mode :not-evaluated)))
  (is (thrown? clojure.lang.ExceptionInfo
               (claim/claim-result :attested :attested :maybe))))

(deftest derive-claim-mapping
  (testing "the machine class is derived, never authored by hand"
    (is (= :attested (claim/derive-claim :attested true false)))
    (is (= :not-evaluated (claim/derive-claim :attested false false)))
    (is (= :reproduced (claim/derive-claim :reproduce true false)))
    (is (= :candidate-compatible (claim/derive-claim :candidate true false)))
    (is (= :accepted-divergence (claim/derive-claim :candidate true true)))
    (is (= :not-evaluated (claim/derive-claim :candidate false false)))
    (is (= :not-evaluated (claim/derive-claim :bogus true false)))))

(deftest claim-label-derived-from-class
  (is (= "attested" (claim/claim-label :attested)))
  (is (= "candidate-compatible" (claim/claim-label :candidate-compatible)))
  (is (= "not-evaluated" (claim/claim-label :not-evaluated))))

(deftest evaluate-via-claim-result-with-derive
  (testing "derive -> claim-result keeps the two steps explicit and validated"
    (let [mode :candidate
          class (claim/derive-claim mode true false)
          result (claim/claim-result mode class :pass
                                    {:subject/root "sha256:s"
                                     :profile/root "sha256:p"})]
      (is (= :candidate-compatible (:claim/class result)))
      (is (claim/claim-consistent? result)))))

(deftest claim-with-coverage-gates-emission
  (let [candidate (claim/claim-result :candidate :candidate-compatible :pass {})
        complete {:coverage/complete? true}
        incomplete {:coverage/complete? false}]
    (is (= candidate (claim/claim-with-coverage complete candidate)))
    (is (nil? (claim/claim-with-coverage incomplete candidate)))))

(deftest claim-with-evidence-binds-reconciliation-root
  (let [candidate (claim/claim-result :candidate :candidate-compatible :pass {})
        complete {:coverage/complete? true}
        passed {:reconciliation/status :pass :reconciliation/root "sha256:rec"}
        failed {:reconciliation/status :fail :reconciliation/root "sha256:rec"}]
    (is (= "sha256:rec"
           (:reconciliation/root (claim/claim-with-evidence complete passed candidate))))
    (testing "a failed reconciliation blocks the claim"
      (is (nil? (claim/claim-with-evidence complete failed candidate))))
    (testing "incomplete coverage blocks the claim"
      (is (nil? (claim/claim-with-evidence {:coverage/complete? false} passed candidate))))))
