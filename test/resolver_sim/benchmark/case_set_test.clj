(ns resolver-sim.benchmark.case-set-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.case-set :as cs]))

(def sample-plan
  [{:execution/ordinal 1 :execution/id "sha256:abc"}
   {:execution/ordinal 2 :execution/id "sha256:def"}
   {:execution/ordinal 3 :execution/id "sha256:ghi"}])

(deftest case-key-derived-from-ordinal
  (is (= 0 (cs/case-key-for-execution 1)))
  (is (= 4 (cs/case-key-for-execution 5)))
  (is (= 99 (cs/case-key-for-execution 100))))

(deftest ordinal-derived-from-case-key
  (is (= 1 (cs/execution-ordinal-for-case-key 0)))
  (is (= 5 (cs/execution-ordinal-for-case-key 4)))
  (is (= 100 (cs/execution-ordinal-for-case-key 99)))
  (is (= (cs/execution-ordinal-for-case-key (cs/case-key-for-execution 7)) 7)
      "round-trip: ordinal → key → ordinal"))

(deftest build-case-set-from-plan
  (let [case-set (cs/build-case-set sample-plan)]
    (is (= 3 (count case-set)))
    (is (= 0 (:case/key (first case-set))))
    (is (= 2 (:case/key (last case-set))))
    (is (= "sha256:abc" (:execution/id (first case-set))))
    (is (= "sha256:ghi" (:execution/id (last case-set))))
    (is (= [0 1 2] (mapv :case/key case-set)))
    (is (= [1 2 3] (mapv :execution/ordinal case-set)))))

(deftest build-case-set-sorts-by-ordinal
  (let [unsorted [{:execution/ordinal 3 :execution/id "sha256:c"}
                  {:execution/ordinal 1 :execution/id "sha256:a"}
                  {:execution/ordinal 2 :execution/id "sha256:b"}]
        case-set (cs/build-case-set unsorted)]
    (is (= ["sha256:a" "sha256:b" "sha256:c"]
           (mapv :execution/id case-set)))
    (is (= [0 1 2] (mapv :case/key case-set)))))

(deftest compute-case-set-root-deterministic
  (let [case-set (cs/build-case-set sample-plan)
        root-a (cs/compute-case-set-root case-set)
        root-b (cs/compute-case-set-root case-set)]
    (is (= root-a root-b) "same input → same hash")))

(deftest compute-case-set-root-changes-when-execution-ids-change
  (let [case-set-a (cs/build-case-set sample-plan)
        root-a (cs/compute-case-set-root case-set-a)
        case-set-b (cs/build-case-set
                     [{:execution/ordinal 1 :execution/id "sha256:xyz"}
                      {:execution/ordinal 2 :execution/id "sha256:def"}
                      {:execution/ordinal 3 :execution/id "sha256:ghi"}])
        root-b (cs/compute-case-set-root case-set-b)]
    (is (not= root-a root-b) "different execution IDs → different hash")))

(deftest compute-case-set-root-changes-when-ordering-changes
  (let [reversed-plan [{:execution/ordinal 1 :execution/id "sha256:ghi"}
                       {:execution/ordinal 2 :execution/id "sha256:def"}
                       {:execution/ordinal 3 :execution/id "sha256:abc"}]
        case-set-a (cs/build-case-set sample-plan)
        root-a (cs/compute-case-set-root case-set-a)
        case-set-b (cs/build-case-set reversed-plan)
        root-b (cs/compute-case-set-root case-set-b)]
    (is (not= root-a root-b) "different case ordering → different hash")))

(deftest compute-case-set-root-includes-case-count
  (let [plan-2 [{:execution/ordinal 1 :execution/id "sha256:a"}
                {:execution/ordinal 2 :execution/id "sha256:b"}]
        plan-3 (conj plan-2 {:execution/ordinal 3 :execution/id "sha256:c"})
        root-2 (cs/compute-case-set-root (cs/build-case-set plan-2))
        root-3 (cs/compute-case-set-root (cs/build-case-set plan-3))]
    (is (not= root-2 root-3) "different case count → different hash")))

(deftest compute-case-set-root-is-sha256-prefixed
  (let [root (cs/compute-case-set-root (cs/build-case-set sample-plan))]
    (is (re-matches #"sha256:[0-9a-f]{64}" root))))
