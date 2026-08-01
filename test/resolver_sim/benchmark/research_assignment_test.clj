(ns resolver-sim.benchmark.research-assignment-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.research-assignment :as assignment]))

(defn- root [label]
  (str "sha256:" (hc/domain-hash :evidence-record {:label label})))

(defn- fixture []
  {:research-assignment/id :assignment/held-custody
   :research-assignment/environment-hash (root :environment)
   :research-assignment/policy-hash (root :policy)
   :research-assignment/review-round-hash (root :round)
   :research-assignment/request-root (root :request)
   :research-assignment/target {:target/kind :governance-mandated
                                :target/public-force-authorisation-scope-hash (root :scope)
                                :target/workflow-id 0
                                :target/reason :resolver-overcapacity}
   :research-assignment/command-root (root :command)
   :research-assignment/plan-root (root :plan)})

(deftest assignment-is-hash-bound-and-tamper-evident
  (let [built (assignment/build-assignment (fixture))]
    (is (assignment/assignment-valid? built))
    (is (= (:research-assignment/hash built) (assignment/assignment-hash built)))
    (is (false? (assignment/assignment-valid?
                 (assoc-in built [:research-assignment/target :target/reason] :other-reason))))))

(deftest assignment-requires-canonical-roots-and-exact-authorisation-binding
  (let [built (assignment/build-assignment (fixture))
        auth {:authorisation/request-root (:research-assignment/request-root built)
              :authorisation/target
              {:target/public-force-authorisation-scope-hash
               (get-in built [:research-assignment/target
                              :target/public-force-authorisation-scope-hash])}}]
    (is (assignment/assignment-matches-authorisation? built auth))
    (is (false? (assignment/assignment-matches-authorisation?
                 built
                 (assoc-in auth [:authorisation/target
                                 :target/public-force-authorisation-scope-hash]
                           (root :other-scope)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (assignment/build-assignment
                  (assoc (fixture) :research-assignment/environment-hash "not-a-root"))))))
