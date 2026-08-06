(ns resolver-sim.economics.with-bounty.public-result-test
  "Pre-C2 review R5: verifier comparison uses a frozen canonical public result
   with canonical-byte equality; diagnostics and replay inputs are excluded;
   missing and extra public fields are failures."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.economics.with-bounty.proof :as proof]
            [resolver-sim.economics.with-bounty.public-result :as pr]))

(defn- applied-result
  []
  (proof/evaluate-bounty {:event/context {:review/finalised? true
                                          :event/actor :researcher/alice}
                          :base/result {:resolved-amount 10000}}))

(deftest public-result-projects-committed-roots
  (let [result (applied-result)
        projection (pr/public-result-projection result)]
    (is (= :applied (:status projection)))
    (is (= (get-in result [:receipt :composition/policy-root])
           (:composition/policy-root projection)))
    (is (= (get-in result [:receipt :bounty/application-plan-root])
           (:bounty/application-plan-root projection)))
    (is (not (contains? projection :replay/inputs)))
    (is (not (contains? projection :bounty/eligibility)))
    (is (:valid? (pr/validate-public-result projection)))))

(deftest public-result-root-deterministic-and-sensitive
  (let [r1 (applied-result)
        r2 (applied-result)
        different (proof/evaluate-bounty {:event/context {:review/finalised? true}
                                          :base/result {:resolved-amount 20000}})]
    (is (= (pr/public-result-root r1) (pr/public-result-root r2)))
    (is (not= (pr/public-result-root r1) (pr/public-result-root different)))))

(deftest missing-and-extra-public-fields-fail
  (let [projection (pr/public-result-projection (applied-result))]
    (is (not (:valid? (pr/validate-public-result (dissoc projection :status)))))
    (is (not (:valid? (pr/validate-public-result (assoc projection :diagnostic/extra "x")))))))

(deftest skipped-result-projects-stable-classification
  (let [skipped (proof/evaluate-bounty {:event/context {:review/finalised? false}
                                        :base/result {:resolved-amount 10000}})
        projection (pr/public-result-projection skipped)]
    (is (= :skipped (:status projection)))
    (is (nil? (:bounty/effect-root projection)))
    (is (:valid? (pr/validate-public-result projection)))))
