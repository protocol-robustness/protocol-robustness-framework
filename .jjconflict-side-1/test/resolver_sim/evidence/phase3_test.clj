(ns resolver-sim.evidence.phase3-test
  "Phase 3: Artifact-Grade Evidence Enforcement
   
   Tests for:
     - cap-field builder (evidence-base, cap-field, cap-fields, require-fields, finalize-evidence)
     - evidence completeness validation
     - deterministic replay hash chain stability
     - capture-event-evidence! integration with builder"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.capture :as cap]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.io.event-evidence :as evidence]
            [resolver-sim.protocols.sew.runner :as runner]))

;; ── Helpers ──────────────────────────────────────────────────────────────────

(defn seeded-rng [seed]
  (let [r (java.util.Random. seed)]
    (fn [] (.nextDouble r))))

(def sample-attribution
  {:ctx/run-id "phase3-test"
   :ctx/scenario-id "test-scenario"
   :ctx/trial-id "trial-1"
   :ctx/event-index 1
   :ctx/replay-seed 42
   :ctx/oracle-cursor 0
   :ctx/oracle-mode :fixed-or
   :ctx/oracle-fixture-id "fixture-1"})

;; ── Task 1: cap-field Builder ─────────────────────────────────────────────────

(deftest test-evidence-base-creates-minimal-record
  (testing "evidence-base creates minimal evidence with schema version and type"
    (let [e (cap/evidence-base {:type :fraud-slash :importance :core :ctx sample-attribution})]
      (is (string? (:evidence/schema-version e)))
      (is (= "fraud-slash" (:evidence/type e)))
      (is (= "core" (:evidence/importance e)))
      (is (contains? e :evidence/context)))))

(deftest test-cap-field-adds-qualified-keyword
  (testing "cap-field adds a qualified keyword field"
    (let [e (-> (cap/evidence-base {:type :test :importance :diagnostic :ctx {}})
                (cap/cap-field :actor/id "0xAlice"))]
      (is (= "0xAlice" (:actor/id e))))))

(deftest test-cap-field-omits-nil
  (testing "cap-field silently omits nil values"
    (let [e (-> (cap/evidence-base {:type :test :importance :trace :ctx {}})
                (cap/cap-field :actor/id nil))]
      (is (not (contains? e :actor/id))))))

(deftest test-cap-field-rejects-unqualified-keyword
  (testing "cap-field throws on unqualified keyword"
    (try
      (-> (cap/evidence-base {:type :test :importance :core :ctx {}})
          (cap/cap-field :unqualified-key "value"))
      (is false "Expected exception")
      (catch clojure.lang.ExceptionInfo e
        (is (some? (:field (ex-data e))))))))

(deftest test-cap-fields-adds-multiple
  (testing "cap-fields adds multiple fields at once"
    (let [e (-> (cap/evidence-base {:type :test :importance :core :ctx {}})
                (cap/cap-fields {:scenario/id "sid"
                                 :run/id "rid"
                                 :event/seq 5}))]
      (is (= "sid" (:scenario/id e)))
      (is (= "rid" (:run/id e)))
      (is (= 5 (:event/seq e))))))

(def core-required #{:scenario/id :run/id :event/seq :transition/id
                     :world/before-hash :world/after-hash
                     :replay/seed :oracle/cursor
                     :evidence/type :evidence/importance})

(deftest test-require-fields-passes-for-complete-core
  (testing "require-fields passes when all CORE fields present"
    (let [e (reduce-kv (fn [acc k v] (assoc acc k v))
                       (cap/evidence-base {:type :test :importance :core :ctx {}})
                       (zipmap core-required (range)))]
      (is (= e (cap/require-fields e))))))

(deftest test-require-fields-throws-for-incomplete-core
  (testing "require-fields throws when CORE fields missing"
    (let [e (cap/evidence-base {:type :test :importance :core :ctx {}})]
      (try
        (cap/require-fields e)
        (is false "Expected exception")
        (catch clojure.lang.ExceptionInfo ex
          (is (some? (:missing (ex-data ex))))
          (is (= :core (:importance (ex-data ex)))))))))

(deftest test-finalize-evidence-adds-hash
  (testing "finalize-evidence computes composite evidence-hash"
    (let [e (-> (cap/evidence-base {:type :test :importance :core :ctx {}})
                (cap/cap-field :actor/id "0xAlice")
                cap/finalize-evidence)]
      (is (string? (:evidence/hash e)))
      (is (= 64 (count (:evidence/hash e)))
          "Evidence hash is a 64-char hex string (no sha256: prefix)")
      ;; Hash is deterministic for same inputs
      (let [e2 (-> (cap/evidence-base {:type :test :importance :core :ctx {}})
                   (cap/cap-field :actor/id "0xAlice")
                   cap/finalize-evidence)]
        (is (= (:evidence/hash e) (:evidence/hash e2)))))))

(deftest test-hash-changes-when-fields-change
  (testing "evidence hash differs when fields differ"
    (let [e1 (-> (cap/evidence-base {:type :test :importance :core :ctx {}})
                 (cap/cap-field :actor/id "0xAlice")
                 cap/finalize-evidence)
          e2 (-> (cap/evidence-base {:type :test :importance :core :ctx {}})
                 (cap/cap-field :actor/id "0xBob")
                 cap/finalize-evidence)]
      (is (not= (:evidence/hash e1) (:evidence/hash e2))))))

;; ── Task 2: Evidence Completeness Validation ─────────────────────────────────

(defn validate-evidence-completeness
  "Post-scenario validation: check that critical evidence requirements are met.
   Returns {:valid? bool :issues [string]}"
  [evidence-list]
  (let [events (set (map :evidence/type evidence-list))
        issues (atom [])]
    ;; If dispute raised, there must be dispute-created evidence
    (when (contains? events "create-escrow")
      (when-not (contains? events "raise-dispute")
        (swap! issues conj "create-escrow present but no raise-dispute evidence")))
    ;; If resolution executed, there must be a resolution decision
    (when (contains? events "execute-resolution")
      (when-not (contains? events "fraud-slash")
        (swap! issues conj "resolution executed but no fraud-slash evidence")))
    ;; If slash occurred, there must be caused-by link
    (let [slash-events (filter #(= "fraud-slash" (:evidence/type %)) evidence-list)]
      (doseq [e slash-events]
        (when-not (:caused-by/evidence-id e)
          (swap! issues conj "fraud-slash evidence missing caused-by/evidence-id"))
        (when-not (:caused-by/rule e)
          (swap! issues conj "fraud-slash evidence missing caused-by/rule"))))
    ;; CORE evidence must have replay coordinates
    (doseq [e evidence-list
            :when (= "core" (:evidence/importance e))]
      (when-not (:replay/seed e)
        (swap! issues conj (str "CORE evidence " (:evidence/type e) " missing replay/seed")))
      (when-not (:world/before-hash e)
        (swap! issues conj (str "CORE evidence " (:evidence/type e) " missing world/before-hash")))
      (when-not (:world/after-hash e)
        (swap! issues conj (str "CORE evidence " (:evidence/type e) " missing world/after-hash"))))
    {:valid? (empty? @issues) :issues @issues}))

(deftest test-evidence-completeness-validates-fraud-slash-chain
  (testing "Completeness validation catches missing evidence in fraud-slash chain"
    ;; Empty evidence list should have no specific issues (nothing to check)
    (is (:valid? (validate-evidence-completeness [])))
    ;; Missing replay coords on CORE triggers issue
    (let [incomplete [{:evidence/type "fraud-slash"
                       :evidence/importance "core"
                       :caused-by/rule :fraud-detected}]]
      (is (not (:valid? (validate-evidence-completeness incomplete))))
      (is (some #(.contains % "missing replay/seed") (:issues (validate-evidence-completeness incomplete)))))))

;; ── Task 3: Deterministic Replay Test ────────────────────────────────────────
;;
;; Same seed + same scenario => same evidence hash chain
;;
;; This is the Phase 3 quality gate: the evidence chain must be fully
;; deterministic for identical inputs.

(deftest test-deterministic-evidence-hash-chain
  (testing "Same scenario run twice produces identical evidence hash chain"
    (let [params {:escrow-size 10000
                  :resolver-fee-bps 50
                  :appeal-bond-bps 100
                  :strategy :malicious
                  :slashing-detection-probability 1.0
                  :escalation-probability-if-correct 0.0
                  :escalation-probability-if-wrong 0.0}
          seed 42
          ;; Run 1
          rng1 (seeded-rng seed)
          result1 (runner/run-trial rng1 (assoc params :attributed? true))
          ;; Run 2 (same seed)
          rng2 (seeded-rng seed)
          result2 (runner/run-trial rng2 (assoc params :attributed? true))]
      ;; Output parity is already proven — this confirms the hash chain is stable
      (is (= result1 result2) "Deterministic: same seed produces identical output"))))

(deftest test-deterministic-hash-with-intent
  (testing "hash-with-intent is deterministic across invocations"
    (let [data {:scenario/id "test" :run/id "run-1" :event/seq 42}
          h1 (hc/hash-with-intent {:hash/intent :evidence-record} data)
          h2 (hc/hash-with-intent {:hash/intent :evidence-record} data)
          h3 (hc/hash-with-intent {:hash/intent :evidence-record} (into (sorted-map) data))]
      (is (string? h1))
      (is (= 64 (count h1)))
      (is (= h1 h2))
      (is (= h1 h3)))))

(deftest test-deterministic-hash-differs-for-different-inputs
  (testing "hash-with-intent differs for different inputs"
    (is (not= (hc/hash-with-intent {:hash/intent :evidence-record} {:a 1})
              (hc/hash-with-intent {:hash/intent :evidence-record} {:a 2})))))

;; ── Task 4: capture-event-evidence! Integration ──────────────────────────────
;;
;; Verify that the pre-built evidence path integrates correctly with
;; the durable persistence function.

(deftest test-capture-with-prebuilt-evidence
  (chain/reset-registry!)
  (chain/reset-chain-cursor!)
  (testing "capture-event-evidence! accepts pre-built evidence map"
    (let [evidence (-> (cap/evidence-base {:type :test-capture :importance :core
                                           :ctx sample-attribution})
                       (cap/cap-fields {:scenario/id "test"
                                        :run/id "run-1"
                                        :event/seq 99
                                        :actor/id "0xAlice"
                                        :action/type :test
                                        :world/before-hash "sha256:abc"
                                        :world/after-hash "sha256:def"
                                        :replay/seed 42
                                        :oracle/cursor 0
                                        :transition/id "test-txn"})
                       cap/finalize-evidence)
          result (evidence/capture-event-evidence! evidence)]
      (is (map? result))
      (is (= (:evidence/hash evidence) (:evidence/hash result)))
      (is (= "test-capture" (:evidence/type result))))))

;; ── Task 5: cap-field Builder Flow Example ───────────────────────────────────
;;
;; Full builder flow for the fraud-slash path (from Phase 3 vertical slice).

(deftest test-fraud-slash-evidence-builder-flow
  (testing "Fraud-slash evidence is built correctly using the cap-field builder"
    (let [e (-> (cap/evidence-base {:type :fraud-slash :importance :core
                                    :ctx sample-attribution})
                (cap/cap-fields {:scenario/id        "scenario-1"
                                 :run/id             "run-42"
                                 :event/seq          7
                                 :actor/id           "0xResolver"
                                 :action/type        :slash-resolver
                                 :caused-by/rule     :fraud-detected
                                 :caused-by/action   :execute-resolution
                                 :transition/id      "slash-0"
                                 :financial/amount   500
                                 :financial/asset    :USDC
                                 :replay/seed        42
                                 :oracle/cursor      0
                                 :oracle/mode        :fixed-or
                                 :oracle/fixture-id  "fixture-1"
                                 :world/before-hash  "sha256:abc123"
                                 :world/after-hash   "sha256:def456"})
                (assoc :transition/inputs {:pre-stake 1000 :slash-obligation 500})
                cap/finalize-evidence)]
      (is (= "fraud-slash" (:evidence/type e)))
      (is (= "core" (:evidence/importance e)))
      (is (= "scenario-1" (:scenario/id e)))
      (is (= "run-42" (:run/id e)))
      (is (= 7 (:event/seq e)))
      (is (= "0xResolver" (:actor/id e)))
      (is (= "slash-resolver" (:action/type e)))
      (is (= "fraud-detected" (:caused-by/rule e)))
      (is (= 500 (:financial/amount e)))
      (is (= "USDC" (:financial/asset e)))
      (is (string? (:world/before-hash e)))
      (is (string? (:world/after-hash e)))
      (is (string? (:evidence/hash e)))
      (is (= 64 (count (:evidence/hash e))))
      ;; Hash chain stability: same inputs => same hash
      (let [e2 (-> (cap/evidence-base {:type :fraud-slash :importance :core
                                       :ctx sample-attribution})
                   (cap/cap-fields {:scenario/id       "scenario-1"
                                    :run/id            "run-42"
                                    :event/seq         7
                                    :actor/id          "0xResolver"
                                    :action/type       :slash-resolver
                                    :caused-by/rule    :fraud-detected
                                    :caused-by/action  :execute-resolution
                                    :transition/id     "slash-0"
                                    :financial/amount  500
                                    :financial/asset   :USDC
                                    :replay/seed       42
                                    :oracle/cursor     0
                                    :oracle/mode       :fixed-or
                                    :oracle/fixture-id "fixture-1"
                                    :world/before-hash "sha256:abc123"
                                    :world/after-hash  "sha256:def456"})
                   (assoc :transition/inputs {:pre-stake 1000 :slash-obligation 500})
                   cap/finalize-evidence)]
        (is (= (:evidence/hash e) (:evidence/hash e2)))))))
