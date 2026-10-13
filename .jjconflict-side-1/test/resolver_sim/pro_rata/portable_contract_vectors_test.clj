(ns resolver-sim.pro-rata.portable-contract-vectors-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as canonical-hash]
            [resolver-sim.pro-rata.allocation :as production]
            [resolver-sim.pro-rata-contract.contract :as portable]))

(def vector-files ["cap-clamping-001.edn"
                   "redistribute-cap-excess-001.edn"
                   "zero-weight-001.edn"
                   "floor-residual-001.edn"])

(defn- vector-data [name]
  (edn/read-string (slurp (io/file "portable-pro-rata-contract" "vectors" name))))

(deftest production-allocation-matches-portable-vectors
  (doseq [name vector-files]
    (let [{:keys [request expected]} (vector-data name)
          portable-result (portable/allocate request)
          production-result (production/allocate request)]
      (is (= portable-result production-result) name)
      (is (= (:allocation/hash expected) (:allocation/hash production-result)) name)
      (is (= (:request/hash production-result)
             (canonical-hash/hash-with-intent
              {:hash/intent :projection-artifact}
              (:canonical-request production-result)))
          (str name " request hash remains compatible with legacy intent projection")))))
