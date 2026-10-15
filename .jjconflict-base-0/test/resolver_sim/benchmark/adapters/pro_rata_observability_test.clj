(ns resolver-sim.benchmark.adapters.pro-rata-observability-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.benchmark.adapters.pro-rata-observability :as observability]))

(def request
  {:schema-version "pro-rata-allocation-request.v1"
   :mechanism/version 1
   :allocation/id :observability-test
   :available 10N
   :rows [{:row/id :row/a :obligation/id :obligation/a :requested 8N :weight 8N :cap 8N}
          {:row/id :row/b :obligation/id :obligation/b :requested 7N :weight 7N :cap 7N}]
   :rounding-policy :largest-remainder
   :tie-break-policy :canonical-row-id
   :redistribution-policy :redistribute-cap-excess})

(deftest allocation-observations-are-exact-and-bound-to-provenance
  (let [result (allocation/allocate request)
        projection (observability/derive-observations result)
        binding (observability/bind-observations result projection)]
    (is (= "pro-rata-allocation-result.v1" (:schema-version result)))
    (is (= "515bd3a91ddeed6d4c29cb4a11f956d2c996a0143d6677d9ede3175e3e86220b"
           (:allocation/hash result)))
    (is (= "sha256:8b2ee99ca25dba912c7cd1189556ae9b4998412efdb8fd9cf24d9f2a29acb17c"
           (:research-observation-projection/root projection)))
    (is (= "sha256:9995e76568308a169be087753871fa409762b9c7a0c46f1210281a2e29c363f4"
           (:binding/root binding)))
    (is (= 10N (get-in projection [:observations :pro-rata/allocated-total :observation/value])))
    (is (= 0N (get-in projection [:observations :pro-rata/unallocated-residual :observation/value])))
    (is (= (:allocation/hash result) (:allocation/hash binding)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not match allocation"
                          (observability/bind-observations
                           result
                           (assoc projection :observations
                                  (assoc (:observations projection)
                                         :pro-rata/unmet-total
                                         {:observation/value 999N
                                          :observation/domain {:kind :integer :unit :pro-rata-units}})))))))

(deftest adapter-rejects-invalid-or-unsupported-allocation-results
  (let [result (allocation/allocate request)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (observability/derive-observations
                  (assoc result :schema-version "pro-rata-allocation-result.v2"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (observability/derive-observations
                  (assoc result :allocated-total -1N))))))
