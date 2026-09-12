(ns resolver-sim.sim.reference-validation-evidence-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.sim.reference-validation-evidence :as evidence]))

(deftest all-manifest-evidence-ids-are-mapped
  (is (= evidence/all-evidence-ids
         (set (keys evidence/evidence-invariant->canonical)))))

(deftest mapped-canonical-ids-exist-in-registry
  (let [mapped (evidence/canonical-ids-for-evidence (seq evidence/all-evidence-ids))]
    (is (set/subset? mapped inv/canonical-ids)
        (str "unknown canonical: " (sort (set/difference mapped inv/canonical-ids))))))
