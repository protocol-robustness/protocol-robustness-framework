(ns resolver-sim.forensic.execution-dag-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.forensic.execution-dag :as dag]))

(defn- single-dag []
  (let [node (dag/make-plan-node {:id "execution-1"})]
    (dag/build-dag [node] [])))

(deftest persisted-dag-validity-and-boolean-wrapper
  (let [value (single-dag)]
    (is (:valid? (dag/validate-persisted-dag value)))
    (is (true? (dag/valid-persisted-dag? value)))
    (is (boolean? (dag/valid-persisted-dag? value)))))

(deftest finalized-single-scenario-dag-requires-explicit-identities
  (let [legacy (single-dag)
        canonical (dag/build-dag [(dag/make-plan-node {:id "execution-1"})] []
                                 {:run-id "run-1" :scenario-id "scenario-1" :execution-id "execution:run-1"})]
    (is (some #(= :execution-dag/missing-identity (:code %))
              (:reasons (dag/validate-persisted-dag legacy {:require-identities? true}))))
    (is (:valid? (dag/validate-persisted-dag canonical {:require-identities? true})))
    (is (= "scenario-1" (:scenario-id (dag/validate-persisted-dag canonical {:require-identities? true}))))))

(deftest persisted-dag-defines-empty-and-disconnected-behavior
  (let [empty (dag/build-dag [] [])
        a (dag/make-plan-node {:id "a"})
        b (dag/make-plan-node {:id "b"})
        disconnected (dag/build-dag [a b] [])]
    (is (some #(= :execution-dag/empty-graph (:code %)) (:reasons (dag/validate-persisted-dag empty))))
    (is (some #(= :execution-dag/disconnected-node (:code %)) (:reasons (dag/validate-persisted-dag disconnected))))))

(deftest persisted-dag-rejects-node-and-edge-corruption
  (let [a (dag/make-plan-node {:id "a"})
        bad-node (dag/build-dag [(assoc a :node/hash "wrong")] [])
        missing-edge (dag/build-dag [a] [(dag/make-plan-edge {:from "a" :to "missing"})])]
    (is (some #(= :execution-dag/node-hash-mismatch (:code %)) (:reasons (dag/validate-persisted-dag bad-node))))
    (is (some #(= :execution-dag/missing-edge-node (:code %)) (:reasons (dag/validate-persisted-dag missing-edge))))))

(deftest persisted-dag-rejects-semantic-invalidity
  (let [a (dag/make-plan-node {:id "a"})
        b (dag/make-plan-node {:id "b"})
        cycle (dag/build-dag [a b] [(dag/make-plan-edge {:from "a" :to "b"})
                                    (dag/make-plan-edge {:from "b" :to "a"})])
        duplicate (dag/build-dag [a b] [(dag/make-plan-edge {:from "a" :to "b"})
                                        (dag/make-plan-edge {:from "a" :to "b"})])]
    (is (some #(= :execution-dag/cycle-detected (:code %)) (:reasons (dag/validate-persisted-dag cycle))))
    (is (some #(= :execution-dag/duplicate-edge (:code %)) (:reasons (dag/validate-persisted-dag duplicate))))))
