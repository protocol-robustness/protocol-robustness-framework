(ns resolver-sim.io.scenario-fixture-parity-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.scenario-export :as export]
            [resolver-sim.io.scenario-fixture-sync :as sync]
            [resolver-sim.protocols.sew.invariant-scenarios.doc-summaries :as doc]))

(defn- baseline-s01-s23-ids
  "Derive S01-S23 scenario IDs from the canonical list (entries 0-22 in all-scenarios).
   S12 is a pair producing 2 scenario IDs, so 23 entries yield 24 IDs."
  []
  (set (map :scenario-id (take 24 (sync/all-invariant-scenario-maps)))))

(defn- scenario-by-id [sid]
  (some #(when (= sid (:scenario-id %)) %) (sync/all-invariant-scenario-maps)))

(deftest baseline-doc-summaries-complete
  (testing "every baseline S01–S23 scenario-id has a doc summary"
    (let [missing (remove doc/summary (baseline-s01-s23-ids))]
      (is (empty? missing)
          (str "Add summaries in doc-summaries.clj for: " (vec missing))))))

(deftest trace-contract-aligned-with-clojure-source
  (testing "trace.json contract fields match invariant scenario maps"
    (let [drifts (sync/collect-trace-contract-drifts)]
      (is (empty? drifts)
          (str "Re-run scripts/scenarios/sync_invariant_trace_fixtures.clj — drifts: "
               (pr-str drifts))))))

(defn- scenarios-with-strict-expected-errors []
  (filter #(true? (:strict-expected-errors? %))
          (sync/all-invariant-scenario-maps)))

(deftest public-json-strict-expected-errors-match-source
  (testing "exported scenarios/edn/*.edn carries strict expected-errors contract"
    (doseq [s (scenarios-with-strict-expected-errors)
            :let [sid   (:scenario-id s)
                  fname (export/scenario-id->public-json-filename sid)
                  path  (when fname (str "scenarios/" fname))
                  doc   (when (and path (.exists (io/file path)))
                          (json/read-str (slurp path) :key-fn keyword))]
            :when doc]
      (is (true? (:strict-expected-errors? doc))
          (str sid " public JSON missing :strict-expected-errors?"))
      (is (= (count (:expected-errors s))
             (count (:expected-errors doc)))
          (str sid " public JSON expected-errors count mismatch")))))

(deftest exported-trace-contract-matches-clojure-for-strict-scenarios
  (testing "trace export from Clojure matches contract fields (strict scenarios)"
    (doseq [s (scenarios-with-strict-expected-errors)
            :let [exported (export/scenario->trace-document
                            s
                            (get export/default-export-metadata (:scenario-id s)))
                  drift  (sync/trace-contract-drift s exported)]]
      (is (nil? drift)
          (str "Export drift for " (:scenario-id s) ": " (pr-str drift))))))
