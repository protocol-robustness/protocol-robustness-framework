(ns resolver-sim.research.interaction-reproduction-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [resolver-sim.research.coordinate-interaction :as engine]
            [resolver-sim.research.interaction-reproduction :as replay]
            [resolver-sim.research.reproduction-fixture :as qualification]
            [resolver-sim.research.semantic-accounting-test :as existing]))

;; Replay the pre-existing real engine fixture, not the affine qualification.
(defn fixture []
  (let [args {:x-axis (existing/axis (:axis-x existing/roots) :parameter/x :integer
                                   {:ordered? true :integer? true :additive? true})
              :y-axis (existing/axis (:axis-y existing/roots) :parameter/y :integer
                                   {:ordered? true :integer? true})
              :levels {:x1 0 :x2 1 :y1 0 :y2 1}
              :observations (zipmap [:x1-y1 :x1-y2 :x2-y1 :x2-y2]
                                   (map existing/observation
                                        (map existing/fake-root [\1 \2 \3 \4])
                                        [10 13 14 25]))}]
    {:input (replay/build-input args)
     :implementation (replay/implementation (:dependency existing/roots) "1.0.0")
     :original (engine/difference-of-differences
                (:x-axis args) (:y-axis args) (:levels args) (:observations args))}))

(deftest recorded-engine-fixture
  (let [{:keys [input implementation authoritative-result]}
        (edn/read-string (slurp (io/resource "research/interaction-reproduction.edn")))
        execution (replay/execute input implementation)
        evidence (replay/compare-execution execution authoritative-result)]
    (is (= 8 (:interaction/value authoritative-result)))
    (is (= :reproduced (:reproduction/status evidence)))
    (is (= (:input (fixture)) input))
    (is (= authoritative-result (:execution/fresh-result execution)))))

(deftest exact-real-replay
  (let [{:keys [input implementation original]} (fixture)
        a (replay/execute input implementation)
        b (replay/execute input implementation)
        evidence (replay/compare-execution a original)]
    (is (= 8 (:interaction/value (:execution/fresh-result a))))
    (is (= original (:execution/fresh-result a)))
    (is (= a b))
    (is (replay/valid-execution? a))
    (is (= :reproduced (:reproduction/status evidence)))
    (is (qualification/valid-evidence? evidence))
    (is (map? (replay/evidence-node a original evidence)))
    (is (thrown? clojure.lang.ArityException (apply replay/execute [input implementation original])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (replay/build-input (assoc (:computation/input input) :expected original))))
    (is (false? (replay/valid-input? (assoc input :runner :a :host "host" :timing 10))))))

(deftest substitutions-and-counterexamples
  (let [{:keys [input implementation original]} (fixture)
        a (replay/execute input implementation)
        changed (replay/build-input
                 (assoc-in (:computation/input input) [:observations :x2-y2 :observation/value] 26))
        b (replay/execute changed implementation)
        alternate (replay/implementation (:dependency existing/roots) "1.0.1")
        c (replay/execute input alternate)
        wrong (assoc original :interaction/value 999)
        wrong (assoc wrong :research-coordinate-interaction/root (engine/interaction-root wrong))
        conflict (replay/compare-execution a wrong)]
    (is (not= (:reproduction-input/root input) (:reproduction-input/root changed)))
    (is (not= (:reproduction-execution/root a) (:reproduction-execution/root b)))
    (is (not= (:reproduction-execution/root a) (:reproduction-execution/root c)))
    (is (= (:execution/fresh-result a) (:execution/fresh-result c)))
    (is (= :reproduced (:reproduction/status (replay/compare-execution c original))))
    (is (= a (replay/execute input implementation)))
    (is (= :counterexample-found (:reproduction/status conflict)))
    (is (= (:execution/fresh-result a) (:reproduction/counterexample-result conflict)))
    (is (some? (:reproduction/counterexample-witness-root conflict)))
    (is (= :not-comparable (:reproduction/status (replay/compare-execution a {:artifact/schema "other"}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (replay/compare-execution {:evidence/mode :source-projection} original)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (replay/evidence-node a original conflict)))))

(deftest genuine-engine-failure-and-incomparability
  (let [{:keys [input implementation original]} (fixture)
        bad (replay/build-input (assoc-in (:computation/input input) [:x-axis :axis/kind] :researcher))
        failed (replay/execute bad implementation)
        missing (replay/build-input
                 (update-in (:computation/input input) [:observations :x2-y2]
                            assoc :observation/status :missing :observation/value nil
                            :observation/reason :measurement-absent))
        execution (replay/execute missing implementation)]
    (is (= :failed (:execution/outcome failed)))
    (is (= :failed (:reproduction/status (replay/compare-execution failed original))))
    (is (= :completed (:execution/outcome execution)))
    (is (= :missing (:interaction/status (:execution/fresh-result execution))))
    (is (= :not-comparable (:reproduction/status (replay/compare-execution execution original))))))
