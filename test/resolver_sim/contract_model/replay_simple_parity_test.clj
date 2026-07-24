(ns resolver-sim.contract-model.replay-simple-parity-test
  "Transition-level parity tests between simple-replay and replay-events.

   The purpose is to prove that 'simple' changes instrumentation and evaluation
   profile, not transition semantics.

   Excluded from comparison:
   - evidence records (:evidence-mode differs)
   - :execution descriptor (profile vs mode differs)
   - :context/version, :context/source (normalized separately)
   - :replay-profile, :protocol-id (simple-only additions)
   - :scenario-normalizations (simple-only addition)"
  (:require [clojure.test :refer :all]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.contract-model.replay.flags :as flags]
            [resolver-sim.contract-model.replay.profile-adapter :as adapter]
            [resolver-sim.protocols.dummy :as dummy]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.io.scenarios :as io-scenarios]))

;; ===========================================================================
;; Helpers
;; ===========================================================================

(defn- load-scenario
  "Load a scenario from a resource path."
  [path]
  (io-scenarios/load-scenario-file path))

(defn- terminal-world-hash
  "Canonical semantic hash of the terminal valid world, when replay produced one."
  [result]
  (when-let [world (:last-valid-world result)]
    (hc/hash-with-intent {:hash/intent :world-structure} world)))

(defn- compare-results
  "Compare simple-replay and replay-events for semantic equivalence.
   Returns nil when equivalent, or a description of the first difference."
  [simple-result events-result]
  (cond
    (not= (:outcome simple-result) (:outcome events-result))
    (str "outcome differs: simple=" (:outcome simple-result) " events=" (:outcome events-result))

    (not= (:halt-reason simple-result) (:halt-reason events-result))
    (str "halt-reason differs: simple=" (:halt-reason simple-result) " events=" (:halt-reason events-result))

    (not= (:events-processed simple-result) (:events-processed events-result))
    (str "events-processed differs: simple=" (:events-processed simple-result) " events=" (:events-processed events-result))

    (not= (mapv (juxt :seq :result :error) (:trace simple-result))
          (mapv (juxt :seq :result :error) (:trace events-result)))
    (str "trace (seq/result/error) differs")

    (not= (mapv :projection-hash (:trace simple-result))
          (mapv :projection-hash (:trace events-result)))
    (str "per-step projection hashes differ")

    (not= (:metrics simple-result) (:metrics events-result))
    (str "metrics differ")

    (not= (terminal-world-hash simple-result)
          (terminal-world-hash events-result))
    (str "terminal world hash differs")

    :else nil))

(defn- test-parity
  "Assert that simple-replay and replay-events produce equivalent results."
  [protocol scenario & [replay-opts]]
  (let [simple-result (replay/simple-replay protocol scenario replay-opts)
        events-result (replay/replay-events protocol scenario
                                            (merge {:minimal true} replay-opts))
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL:" diff)
          (is false diff))
      (is true (str "Parity OK: " (:scenario-id simple-result))))))

;; ===========================================================================
;; 1. Successful scenario
;; ===========================================================================

(def success-scenario
  {:scenario-id "parity-success"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]})

(deftest parity-successful-scenario
  (test-parity dummy/protocol success-scenario))

;; ===========================================================================
;; 2. Expected-error scenario
;; ===========================================================================

(def expected-error-scenario
  {:scenario-id "parity-expected-error"
   :schema-version "1.0"
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :expected-errors [{:seq 0 :action "noop" :error "some-error"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}
            {:seq 1 :time 1100 :agent "a" :action "noop" :params {}}]})

(deftest parity-expected-error-scenario
  (test-parity dummy/protocol expected-error-scenario))

;; ===========================================================================
;; 3. Invalid scenario
;; ===========================================================================

(def invalid-scenario
  {:scenario-id "parity-invalid"
   ;; no :schema-version — simple-replay will default it
   :initial-block-time 1000
   :agents [{:id "a" :address "0xA"}]
   :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]})

(deftest parity-invalid-scenario
  (let [simple-result (replay/simple-replay dummy/protocol invalid-scenario)
        ;; replay-events doesn't auto-default schema-version, so pre-normalize
        events-result (replay/replay-events dummy/protocol
                                            (assoc invalid-scenario :schema-version "1.0")
                                            {:minimal true})
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL:" diff) (is false diff))
      (is true "Parity OK: parity-invalid"))))

;; ===========================================================================
;; 4. Scenario with explicit flag overrides
;; ===========================================================================

(deftest parity-flag-overrides
  (let [simple-result (replay/simple-replay dummy/protocol success-scenario
                                            {:flags {:evaluate-expectations? false}})
        events-result (replay/replay-events dummy/protocol success-scenario
                                            {:minimal true
                                             :flags {:evaluate-expectations? false}})
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL:" diff) (is false diff))
      (is true "Parity OK: parity-flag-overrides"))))

;; ===========================================================================
;; 5. Smoke-level comparison with replay-with-protocol
;; ===========================================================================

(deftest parity-smoke-vs-replay-with-protocol
  (let [scenario {:scenario-id "parity-smoke-vs-full"
                  :schema-version "1.0"
                  :initial-block-time 1000
                  :agents [{:id "a" :address "0xA"}]
                  :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]}
        simple-result (replay/simple-replay dummy/protocol scenario)
        full-result (replay/replay-with-protocol dummy/protocol scenario
                                                 {:flags flags/minimal-replay-flags
                                                  :skip-finalize true})]
    (is (= (:outcome simple-result) (:outcome full-result))
        "outcome matches")
    (is (= (:events-processed simple-result) (:events-processed full-result))
        "events-processed matches")
    (is (= (mapv (juxt :seq :result :error) (:trace simple-result))
           (mapv (juxt :seq :result :error) (:trace full-result)))
        "trace shape matches")))

;; ===========================================================================
;; 6. prepare-simple-scenario purity
;; ===========================================================================

(deftest prepare-scenario-purity
  (let [scenario {:scenario-id "purity-test" :initial-block-time 1000
                  :agents [{:id "a" :address "0xA"}]
                  :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]}
        original (assoc scenario :schema-version "1.0")
        prep-result (replay/prepare-simple-scenario scenario)]
    (is (= "1.0" (get-in prep-result [:scenario :schema-version])))
    (is (not (contains? scenario :schema-version))
        "input not mutated")
    (is (= (:normalizations prep-result)
           [{:field :schema-version :value "1.0" :reason :simple-replay-default}]))))

(deftest prepare-scenario-explicit-preserved
  (let [scenario {:scenario-id "explicit-test" :schema-version "1.0"
                  :initial-block-time 1000
                  :agents [{:id "a" :address "0xA"}]
                  :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]}
        prep-result (replay/prepare-simple-scenario scenario)]
    (is (= "1.0" (get-in prep-result [:scenario :schema-version])))
    (is (empty? (:normalizations prep-result)))))

;; ===========================================================================
;; 7. normalize-simple-result structure
;; ===========================================================================

(deftest normalize-simple-result-keys
  (let [raw {:outcome :pass :scenario-id "test" :events-processed 0 :trace [] :metrics {}}
        result (replay/normalize-simple-result raw dummy/protocol
                                               [{:field :schema-version :value "1.0" :reason :simple-replay-default}]
                                               {:profile :simple :engine :canonical-loop}
                                               "my-run")]
    (is (= :simple (:replay-profile result)))
    (is (= "dummy" (:protocol-id result)))
    (is (= {:profile :simple :engine :canonical-loop} (:execution result)))
    (is (= "1.0" (:context/version result)))
    (is (= {:scenario-id "test" :run-id "my-run"} (:context/source result)))
    (is (seq (:scenario-normalizations result)))))

;; ===========================================================================
;; 8. Evidence-invariant tests
;; ===========================================================================

(deftest simple-replay-leaves-no-evidence-artifacts
  (let [scenario {:scenario-id "evidence-free-test" :schema-version "1.0"
                  :initial-block-time 1000
                  :agents [{:id "a" :address "0xA"}]
                  :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]}
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))
    (is (zero? (get-in (chain/registry-snapshot) [:artifact-count] 0))
        "Evidence registry artifact count is zero after simple-replay")))

(deftest simple-replay-prohibited-flags-are-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"cannot override enforced profile flags"
                        (adapter/extract-simple-opts {:flags {:evidence-mode :all
                                                              :strict-validation? true}}
                                                     :test))))

(deftest simple-replay-rejects-unknown-top-level-options
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unsupported options"
                        (adapter/extract-simple-opts {:minimal false} :test))))

(deftest simple-replay-rejects-top-level-evidence-mode
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Simple replay does not support"
                        (adapter/extract-simple-opts {:evidence-mode :all} :test))))

(deftest simple-replay-no-evidence-with-evidence-module
  (let [scenario {:scenario-id "dummy-evidence-test" :schema-version "1.0"
                  :initial-block-time 1000
                  :agents [{:id "a" :address "0xA"}]
                  :events [{:seq 0 :time 1000 :agent "a" :action "noop" :params {}}]}
        result (replay/simple-replay dummy/protocol scenario)]
    (is (= :pass (:outcome result)))
    (is (zero? (get-in (chain/registry-snapshot) [:artifact-count] 0))
        "Evidence registry is empty after simple-replay")))

;; ===========================================================================
;; 9. Real Sew scenario parity
;; ===========================================================================

(deftest parity-sew-basic-escrow-release
  (let [scenario (io-scenarios/load-scenario-file "scenarios/edn/S-DR-001-basic-release-ruling.edn")
        protocol (preg/get-protocol "sew-v1")
        simple-result (replay/simple-replay protocol scenario)
        events-result (replay/replay-events protocol scenario {:minimal true})
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL (sew-basic):" diff) (is false diff))
      (is (= :pass (:outcome simple-result))
          "S-DR-001 basic release passes under simple-replay"))))

(deftest parity-sew-expected-error-scenario
  (let [scenario (io-scenarios/load-scenario-file "scenarios/edn/S-DR-003-duplicate-dispute-rejected.edn")
        protocol (preg/get-protocol "sew-v1")
        simple-result (replay/simple-replay protocol scenario)
        events-result (replay/replay-events protocol scenario {:minimal true})
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL (sew-expected-error):" diff) (is false diff))
      (is (= :pass (:outcome simple-result))
          "S-DR-003 passes (expected errors matched)"))))

(deftest parity-sew-rich-scenario
  (let [scenario (io-scenarios/load-scenario-file "scenarios/edn/S-DR-030-biased-resolver-appealed.edn")
        protocol (preg/get-protocol "sew-v1")
        simple-result (replay/simple-replay protocol scenario)
        events-result (replay/replay-events protocol scenario {:minimal true})
        diff (compare-results simple-result events-result)]
    (if diff
      (do (println "PARITY FAIL (sew-rich):" diff) (is false diff))
      (is (some? (:outcome simple-result))
          "S-DR-030 runs under simple-replay"))))
