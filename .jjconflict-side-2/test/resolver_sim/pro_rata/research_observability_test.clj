(ns resolver-sim.pro-rata.research-observability-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.research-observability :as observability]))

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
