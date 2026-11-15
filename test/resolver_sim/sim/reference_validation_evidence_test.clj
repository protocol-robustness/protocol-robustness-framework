(ns resolver-sim.sim.reference-validation-evidence-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.invariants :as inv]
            [resolver-sim.yield.invariant-catalog :as yield-invariants]
            [resolver-sim.sim.reference-validation-evidence :as evidence]))

(deftest all-manifest-evidence-ids-are-mapped
  (is (= evidence/all-evidence-ids
         (set (keys evidence/evidence-invariant->canonical)))))

(deftest mapped-canonical-ids-exist-in-registry
  (let [mapped (evidence/canonical-ids-for-evidence (seq evidence/all-evidence-ids))
        ;; claimable-classification is a legacy evidence predicate rather than
        ;; an entry in the invariant result registry.
        available (into inv/canonical-ids
                        (concat (keys yield-invariants/catalog)
                                [:claimable-classification]))]
    (is (set/subset? mapped available)
        (str "unknown canonical: " (sort (set/difference mapped available))))))

(deftest evidence-checking-requires-an-explicit-protocol-adapter
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Protocol invariant checker is required"
       (evidence/verify-evidence-invariants!
        {:outcome :pass :world {}} []))))
