(ns resolver-sim.benchmark.authority-semantics-policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.authority-semantics-policy :as policy]
            [resolver-sim.benchmark.governed-authority-semantics :as semantics]))

(def valid-policy
  (policy/build-policy
   {:authority-semantics/root (:governed-authority-semantics/root semantics/default-semantics)}))

(deftest policy-selects-exact-closed-semantics
  (is (:valid? (policy/validate-policy valid-policy)))
  (is (= valid-policy
         (policy/build-policy
          {:authority-semantics/root (:governed-authority-semantics/root semantics/default-semantics)})))
  (is (:valid? (policy/verify-policy-selection valid-policy semantics/default-semantics))))

(deftest policy-rejects-transplanted-or-malformed-semantics
  (testing "P cannot be paired with a different rooted descriptor"
    (let [other (assoc semantics/default-semantics
                       :governed-authority-semantics/root
                       "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
      (is (false? (:valid? (policy/verify-policy-selection valid-policy other))))))
  (testing "the policy has a closed, self-rooted projection"
    (is (false? (:valid? (policy/validate-policy (assoc valid-policy :extra true)))))
    (is (false? (:valid? (policy/validate-policy
                          (assoc valid-policy :authority-semantics-policy/root
                                 "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")))))))
