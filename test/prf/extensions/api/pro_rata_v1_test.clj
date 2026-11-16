(ns prf.extensions.api.pro-rata-v1-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.extensions.api.pro-rata.v1 :as pro-rata]))

(deftest portable-pro-rata-api-supports-allocation-and-evidence
  (let [allocation (pro-rata/allocate
                    {:schema-version "pro-rata-allocation-request.v1"
                     :mechanism/version 1
                     :allocation/id :public-api-contract
                     :available 10
                     :rows [{:row/id :row-a :obligation/id :a
                             :requested 10 :weight 3}
                            {:row/id :row-b :obligation/id :b
                             :requested 10 :weight 1}]
                     :rounding-policy :largest-remainder
                     :tie-break-policy :canonical-row-id
                     :redistribution-policy :unallocated})
        evidence (pro-rata/mechanism-evidence-artifact allocation)]
    (is (= [8 2] (mapv :allocated (:rows allocation))))
    (is (true? (pro-rata/evidence-valid? evidence)))
    (is (= (:evidence/hash evidence)
           (:evidence/hash (pro-rata/evidence-reference evidence))))))

(deftest portable-pro-rata-api-supports-projection-evaluation
  (let [artifact (pro-rata/build-projection-artifact
                  {:amount 10
                   :items [{:id :a :weight 3} {:id :b :weight 1}]
                   :id-fn :id
                   :weight-fn :weight})
        result (pro-rata/calculate-from-projection artifact)]
    (is (= 10 (:total-requested result)))
    (is (= 10 (:total-allocated result)))))
