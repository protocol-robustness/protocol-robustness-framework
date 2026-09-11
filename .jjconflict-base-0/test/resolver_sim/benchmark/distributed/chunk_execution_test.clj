(ns resolver-sim.benchmark.distributed.chunk-execution-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.distributed.chunk-execution :as sut]
            [resolver-sim.benchmark.distributed.executable-distribution :as distribution]
            [resolver-sim.benchmark.distributed.fixed-chunks :as fixed]))

(defn- root [digit] (str "sha256:" (apply str (repeat 64 digit))))
(def entries [{:execution/ordinal 1 :execution/id "execution-a" :execution/descriptor {:id "a"} :scenario/input-root (root "a")}
              {:execution/ordinal 2 :execution/id "execution-b" :execution/descriptor {:id "b"} :scenario/input-root (root "b")}])
(def fixed-set (fixed/derive-fixed-chunk-set entries {:chunk-size 2 :sensitivity-root (root "c") :executable-distribution-root (root "d")}))
(def claim (merge (select-keys fixed-set [:run-plan/root :execution-plan/root]) (first (:chunks fixed-set))))
(defn- worker [entry] {:execution/id (:execution/id entry) :staged-artifact-manifest/root (root (if (= "execution-a" (:execution/id entry)) "d" "e"))})
(defn- execute [claim entries & [sensitivity]]
  (sut/execute-chunk! {:claimed-chunk claim :resolved-executions entries
                       :sensitivity-root (or sensitivity (root "c")) :execute-entry! worker}))

(deftest executes-exact-claimed-membership-and-derives-manifest
  (let [manifest (execute claim entries)]
    (is (= ["execution-a" "execution-b"] (:chunk/execution-ids manifest)))
    (is (= [(root "d") (root "e")] (:chunk/staged-artifact-manifest-roots manifest)))
    (is (= (:chunk/result-root manifest)
           (:chunk/result-root
            (sut/finalize-chunk!
             (sut/authorize-chunk! {:claimed-chunk claim
                                    :resolved-executions entries
                                    :sensitivity-root (root "c") :executable-distribution-root (root "d")})
             (vec (reverse (mapv worker entries)))))))))

(deftest rejects-wrong-plan-work-sensitivity-and-execution-membership
  (testing "observed work and sensitivity are closed over by authorization"
    (doseq [bad [(assoc claim :chunk/expected-work-root (root "f"))
                 (assoc claim :chunk/expected-sensitivity-root (root "f"))]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/authorize-chunk! {:claimed-chunk bad
                                          :resolved-executions entries
                                          :sensitivity-root (root "c") :executable-distribution-root (root "d")})))))
  (testing "execution membership cannot be substituted"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/authorize-chunk! {:claimed-chunk claim
                                        :resolved-executions [(first entries)]
                                        :sensitivity-root (root "c") :executable-distribution-root (root "d")})))))

(deftest rejects-membership-and-observed-material-substitution
  (doseq [bad [(conj entries {:execution/ordinal 3 :execution/id "execution-c" :execution/descriptor {:id "c"} :scenario/input-root (root "d")})
               [(first entries)]
               (vec (reverse entries))
               (assoc-in entries [0 :scenario/input-root] (root "f"))
               (assoc-in entries [0 :execution/descriptor :id] "substituted")]]
    (is (thrown? clojure.lang.ExceptionInfo (execute claim bad))))
  (is (thrown? clojure.lang.ExceptionInfo (execute claim entries (root "f")))))

(deftest rejects-worker-substitution-and-caller-result-root-influence
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/execute-chunk! {:claimed-chunk claim :resolved-executions entries :sensitivity-root (root "c") :executable-distribution-root (root "d")
                                    :execute-entry! (fn [entry] {:execution/id "other" :staged-artifact-manifest/root (root "d")})})))
  (let [manifest (execute claim entries)
        malicious (sut/execute-chunk!
                   {:claimed-chunk claim
                    :resolved-executions entries
                    :sensitivity-root (root "c") :executable-distribution-root (root "d")
                    :chunk/result-root (root "f")
                    :execute-entry! worker})]
    (is (= (:chunk/result-root manifest) (:chunk/result-root malicious)))))

(deftest worker-refuses-before-execution-when-distribution-is-unresolved-or-mismatched
  (let [distribution (distribution/build-distribution
                      {:execution-plan/root (:execution-plan/root claim)
                       :executable-artifact/root (root "e")
                       :local-keys [{:local-key/id "worker-a"
                                     :local-key/capacity 1
                                     :local-key/budget 1}]})
        request {:claimed-chunk claim
                 :resolved-executions entries
                 :sensitivity-root (root "c") :executable-distribution-root (root "d")
                 :expected-executable-distribution (distribution/expected-shape distribution)}]
    (doseq [observed [nil (assoc distribution :executable-distribution/root (root "f"))]]
      (let [executed (atom false)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (sut/execute-chunk! (assoc request
                                                :observed-executable-distribution observed
                                                :execute-entry! (fn [_]
                                                                  (reset! executed true))))))
        (is (false? @executed))))))

(deftest authorization-closes-membership-before-finalization
  (let [handle (sut/authorize-chunk! {:claimed-chunk claim
                                      :resolved-executions entries
                                      :sensitivity-root (root "c") :executable-distribution-root (root "d")})
        rows (mapv worker entries)
        manifest (sut/finalize-chunk! handle rows)]
    (is (= (:chunk/execution-ids claim)
           (mapv :execution/id (:resolved-executions handle))))
    (is (= (mapv :execution/id rows) (:chunk/execution-ids manifest)))
    (is (= (:chunk/result-root manifest)
           (:chunk/result-root (sut/finalize-chunk! handle (vec (reverse rows))))))))
