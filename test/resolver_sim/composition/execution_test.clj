(ns resolver-sim.composition.execution-test
  "Runtime boundary: execution consumes only compiled plans, binds plan and
   compiler provenance, and rejects unchecked inputs."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.composition.compiler :as comp]
            [resolver-sim.composition.execution :as exec]
            [resolver-sim.composition.fixtures :as fx]))

(deftest executes-valid-plan-with-value-flow
  (let [emap (fx/ext-map-with (fx/cap :fixture/a) (fx/cap :fixture/b))
        combo (fx/seq-combination
               (fx/node :n1 [:economics/award-amount :fixture/a] :spec {:rate 500})
               (fx/node :n2 [:economics/award-amount :fixture/b] :spec {:rate 500}))
        {:keys [plan]} (comp/compile-combination emap combo)
        result (exec/execute-compiled-plan plan emap {} 1000)]
    (is (= :completed (:execution/status result)))
    (is (= (:plan/root plan) (:execution/plan-root result)))
    (is (= comp/compiler-id (:execution/compiler-id result)))
    (is (= comp/compiler-version (:execution/compiler-version result)))
    ;; n1: floor(1000*500/10000)=50 → value 950; n2: floor(950*500/10000)=47 → value 903
    (is (= 903 (:execution/value result)))
    (is (= [50 47] (mapv :amount (:execution/nodes result))))
    (is (= [950 903] (mapv :output (:execution/nodes result))))))

(deftest effects-accumulate
  (let [emap (fx/ext-map-with
              (fx/cap :fixture/e :entrypoint 'resolver-sim.composition.fixtures/effect-award))
        combo (fx/seq-combination
               (fx/node :n1 [:economics/award-amount :fixture/e]
                        :spec {:amount 10 :effects [{:effect/contract :prf.effect/x.v1}]})
               (fx/node :n2 [:economics/award-amount :fixture/e]
                        :spec {:amount 5 :effects [{:effect/contract :prf.effect/y.v1}]}))
        {:keys [plan]} (comp/compile-combination emap combo)
        result (exec/execute-compiled-plan plan emap {} 1000)]
    (is (= :completed (:execution/status result)))
    (is (= 2 (count (:execution/effects result))))
    (is (= 985 (:execution/value result)))))

(deftest short-circuit-stops-pipeline
  (let [emap (fx/ext-map-with
              (fx/cap :fixture/s :entrypoint 'resolver-sim.composition.fixtures/short-circuit-award))
        combo (fx/seq-combination
               (fx/node :n1 [:economics/award-amount :fixture/s] :spec {:amount 10})
               (fx/node :n2 [:economics/award-amount :fixture/s] :spec {:amount 10}))
        {:keys [plan]} (comp/compile-combination emap combo)
        result (exec/execute-compiled-plan plan emap {} 1000)]
    (is (= :short-circuited (:execution/status result)))
    (is (= 1 (count (:execution/nodes result)))
        "later nodes do not execute after short-circuit")))

(deftest raw-combination-rejected
  (let [result (exec/execute-compiled-plan {:combination/nodes []} {} {} 0)]
    (is (= :rejected (:execution/status result)))
    (is (some #(= :violation/uncompiled-combination (:violation/id %))
              (:execution/violations result)))))

(deftest descriptor-root-mismatch-rejected
  (let [emap (fx/ext-map-with (fx/cap :fixture/a))
        combo (fx/seq-combination
               (fx/node :n1 [:economics/award-amount :fixture/a] :spec {:rate 500})
               (fx/node :n1b [:economics/award-amount :fixture/a] :spec {:rate 500}))
        {:keys [plan]} (comp/compile-combination emap combo)
        ;; a different registry whose descriptor roots no longer match
        other-map (fx/ext-map-with (fx/cap :fixture/a :output-semantic :gross))
        result (exec/execute-compiled-plan plan other-map {} 1000)]
    (is (= :rejected (:execution/status result)))
    (is (some #(= :violation/descriptor-root-mismatch (:violation/id %))
              (:execution/violations result)))))

(deftest execute-combination-compiles-first
  (let [emap (fx/ext-map-with (fx/cap :fixture/a))
        combo (fx/seq-combination
               (fx/node :n1 [:economics/award-amount :fixture/a] :spec {:rate 500})
               (fx/node :n2 [:economics/award-amount :fixture/a] :spec {:rate 500}))
        result (exec/execute-combination emap combo {} 1000)]
    (is (= :completed (:execution/status result)))
    (is (some? (:execution/compiled-plan result))
        "execution proves an equivalent plan was compiled"))
  (testing "an uncompilable combination is rejected before execution"
    (let [emap (fx/ext-map-with)
          combo (fx/seq-combination
                 (fx/node :n1 [:economics/award-amount :fixture/nope])
                 (fx/node :n2 [:economics/award-amount :fixture/nope]))
          result (exec/execute-combination emap combo {} 0)]
      (is (= :rejected (:execution/status result)))
      (is (nil? (:execution/compiled-plan result))))))
