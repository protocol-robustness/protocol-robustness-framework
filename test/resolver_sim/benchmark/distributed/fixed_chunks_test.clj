(ns resolver-sim.benchmark.distributed.fixed-chunks-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.distributed.fixed-chunks :as sut]))

(defn- root [digit] (str "sha256:" (apply str (repeat 64 digit))))

(def plan
  [{:execution/ordinal 1 :execution/id "execution-a" :execution/descriptor {:id "a"}
    :scenario/input-root (root "a")}
   {:execution/ordinal 2 :execution/id "execution-b" :execution/descriptor {:id "b"}
    :scenario/input-root (root "b")}
   {:execution/ordinal 3 :execution/id "execution-c" :execution/descriptor {:id "c"}
    :scenario/input-root (root "c")}])

(deftest derives-a-complete-fixed-ordered-chunk-set
  (let [derived (sut/derive-fixed-chunk-set plan {:chunk-size 2 :sensitivity-root (root "d") :executable-distribution-root (root "e")})]
    (is (= sut/schema (:chunk-set/schema derived)))
    (is (= 2 (:chunk-size derived)))
    (is (= (root "e") (:executable-distribution/root derived)))
    (is (= (:chunk-set/root derived) (sut/chunk-set-root derived)))
    (is (= ["chunk-0001" "chunk-0002"] (mapv :chunk/id (:chunks derived))))
    (is (= [["execution-a" "execution-b"] ["execution-c"]]
           (mapv :chunk/execution-ids (:chunks derived))))
    (is (= derived (sut/derive-fixed-chunk-set plan {:chunk-size 2 :sensitivity-root (root "d") :executable-distribution-root (root "e")})))
    (is (not= (:chunk-set/root derived)
              (:chunk-set/root (sut/derive-fixed-chunk-set plan {:chunk-size 1 :sensitivity-root (root "d") :executable-distribution-root (root "e")}))))
    (is (not= (:chunk-set/root derived)
              (:chunk-set/root (sut/derive-fixed-chunk-set plan {:chunk-size 2 :sensitivity-root (root "e") :executable-distribution-root (root "e")}))))))

(deftest chunk-size-reaches-a-deterministic-one-chunk-fixed-point
  (let [at-plan-size (sut/derive-fixed-chunk-set plan {:chunk-size (count plan)
                                                       :sensitivity-root (root "d") :executable-distribution-root (root "e")})
        above-plan-size (sut/derive-fixed-chunk-set plan {:chunk-size (inc (count plan))
                                                          :sensitivity-root (root "d") :executable-distribution-root (root "e")})]
    (is (not= (:chunk-set/root at-plan-size) (:chunk-set/root above-plan-size)))
    (is (= [plan] (mapv :chunk/work-items (:chunks at-plan-size))))
    (is (= 1 (count (:chunks at-plan-size))))))

(deftest rejects-unfrozen-or-ambiguous-plan-membership
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/derive-fixed-chunk-set (assoc-in plan [0 :scenario/input-root] "not-a-root")
                                           {:chunk-size 2 :sensitivity-root (root "d") :executable-distribution-root (root "e")})))
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/derive-fixed-chunk-set (assoc-in plan [1 :execution/id] "execution-a")
                                           {:chunk-size 2 :sensitivity-root (root "d") :executable-distribution-root (root "e")}))))
