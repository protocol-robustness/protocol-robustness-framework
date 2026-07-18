(ns resolver-sim.sensitivity.report-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.sensitivity.report :as report]))

(def ^:private ^:const test-run-id "test-run-uuid")

(defn- context
  []
  {:run-id test-run-id
   :profile :internal
   :sentinel-version "1.0.0"
   :scenario-ids ["s01" "s99"]})

(defn- sample-safety-result
  []
  {:profile :internal :decision :allowed :findings []})

(defn- sample-run-sensitivity
  []
  {:level :sensitivity/private
   :risk-meta {:value-at-risk "15,000,000" :risk-severity :risk-severity/critical}})

(defn- sample-scenarios
  []
  [{:scenario-id "s01" :scenario-metadata {} :sensitivity/level :sensitivity/public
    :scenario-path "scenarios/edn/s01.edn" :scenario-hash "hash-s01"}
   {:scenario-id "s99"
    :scenario-metadata {:scenario/sensitivity {:level :sensitivity/private
                                                :risk-meta {:value-at-risk "15M"}}}
    :scenario-input-hash "abc123"
    :scenario-path "scenarios/edn/s99.edn"
    :scenario-hash "hash-s99"}])

(deftest build-report-minimal
  (let [report (report/build-sensitivity-report (sample-safety-result) nil [] (context))]
    (is (= "sensitivity-report.v2" (:schema-version report)))
    (is (= test-run-id (:run-id report)))
    (is (= "internal" (:profile report)))
    (is (= "allowed" (:decision report)))
    (is (= 0 (:scenario-count report)))
    (is (= 0 (:sensitive-scenario-count report)))
    (is (nil? (:run-level report)))
    (is (nil? (:provenance report)))
    (is (string? (:report-hash report)))))

(deftest build-report-with-sensitivity
  (let [report (report/build-sensitivity-report
                (sample-safety-result)
                (sample-run-sensitivity)
                (sample-scenarios)
                (context))]
    (is (= "sensitivity-report.v2" (:schema-version report)))
    (is (= "private" (:run-level report)))
    (is (= "15,000,000" (get-in report [:risk-meta :value-at-risk])))
    (is (= 2 (:scenario-count report)))
    (is (= 1 (:sensitive-scenario-count report)))
    ;; Provenance is built internally by the report builder
    (is (some? (:provenance report)))
    (is (= "private" (get-in report [:provenance :sentinel/effective-level])))))

(deftest build-report-per-scenario-entries
  (let [report (report/build-sensitivity-report
                (sample-safety-result)
                (sample-run-sensitivity)
                (sample-scenarios)
                (context))
        scenarios (:scenarios report)]
    (is (= 2 (count scenarios)))
    (is (= "s01" (:id (first scenarios))))
    (is (= "no-declaration-structural-only" (:sensitivity/status (first scenarios))))
    (is (nil? (:declared-level (first scenarios))))
    (is (nil? (:declaration-provenance (first scenarios))))
    (is (= "s99" (:id (second scenarios))))
    (is (= "evaluated" (:sensitivity/status (second scenarios))))
    (is (= "private" (:declared-level (second scenarios))))
    (is (= "abc123" (:input-hash (second scenarios))))
    ;; Declaration provenance
    (let [dp (:declaration-provenance (second scenarios))]
      (is (some? dp))
      (is (= "scenario-sensitivity.v1" (:schema dp)))
      (is (= "private" (:value dp)))
      (is (= "s99" (:declaration/source-artifact-id dp)))
      (is (= "scenarios/edn/s99.edn" (:declaration/source-path dp)))
      (is (= "abc123" (:declaration/source-bytes-hash dp)))
      (is (= "hash-s99" (:declaration/source-content-hash dp))))))

(deftest build-report-hash-stable
  (let [ctx (context)
        r1 (report/build-sensitivity-report
            (sample-safety-result)
            (sample-run-sensitivity)
            (sample-scenarios)
            ctx)
        r2 (report/build-sensitivity-report
            (sample-safety-result)
            (sample-run-sensitivity)
            (sample-scenarios)
            ctx)]
    ;; Same data should produce same hash (even though evaluated-at differs)
    (is (= (:report-hash r1) (:report-hash r2)))
    ;; Hash should be 64 hex chars
    (is (re-matches #"[0-9a-f]{64}" (:report-hash r1)))))

(deftest build-report-hash-stable-different-time
  (let [ctx (context)
        r1 (report/build-sensitivity-report
            (sample-safety-result)
            (sample-run-sensitivity)
            (sample-scenarios)
            (assoc ctx :report-time "2025-01-01T00:00:00Z"))
        r2 (report/build-sensitivity-report
            (sample-safety-result)
            (sample-run-sensitivity)
            (sample-scenarios)
            (assoc ctx :report-time "2025-06-15T12:30:00Z"))]
    ;; Different report-time should not affect the hash
    (is (= (:report-hash r1) (:report-hash r2)) "Hash must not depend on evaluated-at or report-time")))

(deftest write-report-roundtrip
  (let [tmp-dir (io/file (str (java.io.File/createTempFile "sensitivity-test" "") ".d"))
        manifest-path (.getPath tmp-dir)]
    (.mkdirs tmp-dir)
    (let [report (report/build-sensitivity-report
                  (sample-safety-result)
                  (sample-run-sensitivity)
                  (sample-scenarios)
                  (context))
          written (report/write-sensitivity-report! manifest-path report)
          read-back (json/read-str
                     (slurp (io/file manifest-path "sensitivity-report.json"))
                     :key-fn keyword)]
      (is (= (:schema-version written) (:schema-version read-back)))
      (is (= (:run-level written) (:run-level read-back)))
      (is (= (:scenario-count written) (:scenario-count read-back)))
      (is (= (:report-hash written) (:report-hash read-back)))
      (io/delete-file tmp-dir true))))
