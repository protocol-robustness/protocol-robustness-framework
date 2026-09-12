(ns resolver-sim.benchmark.distributed.executable-invocation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.distributed.executable-invocation :as sut]))

(defn root [digit]
  (str "sha256:" (apply str (repeat 64 digit))))

(def invocation
  (sut/build-invocation
   {:execution-plan/root (root "a")
    :executable-distribution/root (root "b")
    :chunk/id "chunk-1"
    :execution-ids ["execution-1" "execution-2"]
    :input/root (root "c")
    :work/root (root "d")
    :sensitivity/root (root "e")}))

(deftest descriptor-is-rooted-and-closed
  (is (sut/verify-invocation invocation))
  (is (= sut/required-fields (set (keys invocation))))
  (is (not (contains? invocation :execute-entry!)))
  (is (not (contains? invocation :executor))))

(deftest semantic-fields-change-root
  (doseq [[field value] [[:execution-plan/root (root "f")]
                         [:executable-distribution/root (root "1")]
                         [:input/root (root "2")]
                         [:work/root (root "3")]
                         [:sensitivity/root (root "4")]
                         [:chunk/id "chunk-2"]]]
    (testing (str field)
      (is (not= (:executable-invocation/root invocation)
                (:executable-invocation/root
                 (sut/build-invocation (assoc invocation field value))))))))

(deftest operational-fields-are-not-admitted
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/build-invocation (assoc invocation :worker/id "worker-a"))))
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/build-invocation (assoc invocation :lease/token "lease")))))

(deftest membership-order-is-rooted
  (is (not= (:executable-invocation/root invocation)
            (:executable-invocation/root
             (sut/build-invocation
              (assoc invocation :execution-ids ["execution-2" "execution-1"]))))))
