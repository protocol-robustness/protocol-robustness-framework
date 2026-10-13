(ns test-invariant-catalog
  (:require [clojure.set]
            [clojure.test :refer [deftest is]]
            [resolver-sim.yield.invariant-catalog :as cat]
            [resolver-sim.yield.invariants :as inv]))

(deftest invariant-parity
  (let [registered-ids (set (inv/registered-ids))
        catalog-ids    (set (keys cat/catalog))]
    (is (= registered-ids catalog-ids)
        (str "Catalog and implementation must match. Missing from catalog: "
             (clojure.set/difference registered-ids catalog-ids)
             ", Extra in catalog: "
             (clojure.set/difference catalog-ids registered-ids)))))
