(ns resolver-sim.io.diff-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.io.diff :as diff]
            [resolver-sim.io.diff-runner :as diff-runner]))

(def ^:private base-trace
  [{:seq 0 :action "create_escrow" :world {:block-time 1 :escrow-count 1}}
   {:seq 1 :action "raise_dispute" :world {:block-time 2 :escrow-count 1 :disputes 1}}])

(def ^:private changed-trace
  [{:seq 0 :action "create_escrow" :world {:block-time 1 :escrow-count 1}}
   {:seq 1 :action "raise_dispute" :world {:block-time 2 :escrow-count 1 :disputes 2}}])

(deftest diff-traces-no-divergence
  (is (nil? (diff/diff-traces base-trace base-trace))))

(deftest diff-traces-finds-first-world-divergence
  (let [result (diff/diff-traces base-trace changed-trace)]
    (is (some? result))
    (is (= 1 (:divergence-at result)))
    (is (= "raise_dispute" (:action result)))
    (is (= 1 (get-in result [:only-in-baseline :disputes])))
    (is (= 2 (get-in result [:only-in-candidate :disputes])))))

(deftest diff-traces-length-mismatch
  (let [result (diff/diff-traces base-trace (conj base-trace
                                                  {:seq 2 :action "wait" :world {}}))]
    (is (= :trace-length-mismatch (:reason result)))
    (is (= 2 (:length-a result)))
    (is (= 3 (:length-b result)))))

(deftest diff-traces-ignores-nested-trace-field
  (let [with-trace (assoc-in base-trace [0 :world :trace] [{:nested true}])]
    (is (nil? (diff/diff-traces base-trace with-trace)))))

(deftest print-diff-report-handles-nil-and-divergence
  (testing "nil diff"
    (let [out (with-out-str (diff/print-diff-report nil))]
      (is (re-find #"No structural divergence" out))))
  (testing "divergence"
    (let [out (with-out-str (diff/print-diff-report (diff/diff-traces base-trace changed-trace)))]
      (is (re-find #"STRUCTURAL DIVERGENCE" out))
      (is (re-find #"seq=1" out)))))

(deftest trace-from-replay-json-round-trip
  (let [file (io/file "target/diff-test-replay.json")]
    (.mkdirs (.getParentFile file))
    (try
      (spit file (json/write-str {:outcome "pass" :trace base-trace}))
      (is (= base-trace (diff-runner/trace-from-replay-json (.getPath file))))
      (finally
        (.delete file)))))

(deftest run-diff-traces-exit-code
  (let [dir (doto (io/file "target/diff-test-pair") (.mkdirs))
        base-file (io/file dir "base.json")
        same-file (io/file dir "same.json")
        diff-file (io/file dir "diff.json")]
    (try
      (spit base-file (json/write-str {:trace base-trace}))
      (spit same-file (json/write-str {:trace base-trace}))
      (spit diff-file (json/write-str {:trace changed-trace}))
      (is (zero? (diff-runner/run-diff-traces! (.getPath base-file) (.getPath same-file))))
      (is (= 1 (diff-runner/run-diff-traces! (.getPath base-file) (.getPath diff-file))))
      (finally
        (doseq [f [base-file same-file diff-file]] (.delete f))
        (.delete dir)))))
