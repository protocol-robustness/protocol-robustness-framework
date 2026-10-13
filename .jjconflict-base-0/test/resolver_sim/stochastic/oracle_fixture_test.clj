(ns resolver-sim.stochastic.oracle-fixture-test
  "Oracle fixture / :fixed-or validation, control EDN loads, and resolve-dispute traces.

   See also resolver-sim.stochastic.reproducibility-test (roll consumption, static modes).
   Param authoring: data/params/PHASES.md (Oracle Fixtures section)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.io.params :as io-params]
            [resolver-sim.stochastic.detection :as det]
            [resolver-sim.stochastic.dispute :as dispute]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.stochastic.types :as types]))

(def ^:private control-oracle-param-files
  {"data/params/control-oracle-fixed-roll-sequence.edn" :fixed-roll-sequence
   "data/params/control-oracle-full-trial.edn"           :fixed-roll-sequence
   "data/params/control-oracle-always-detect.edn"        :static-always-detect
   "data/params/control-oracle-static-no-slash.edn"      :static-no-slash})

(def ^:private resolve-dispute-optional-keys
  [:fraud-detection-probability :timeout-detection-probability
   :reversal-detection-probability :reversal-slash-bps :fraud-slash-bps
   :timeout-slash-bps :l2-detection-prob :p-l1-reversal :p-l2-escalation
   :p-l2-reversal :has-kleros? :oracle-fixture :oracle-mode
   :oracle-roll-sequence :oracle-roll-on-exhaustion :fixed-or
   :oracle-roll-trace-enabled?])

(defn- optional-kw-args
  "Keyword args for resolve-dispute — only keys present on p (false booleans kept)."
  [p]
  (mapcat (fn [k]
            (when (contains? p k) [k (get p k)]))
          resolve-dispute-optional-keys))

(defn- resolve-dispute-from-merged-params
  [p]
  (let [rng (rng/make-rng (or (:rng-seed p) 42))]
    (apply dispute/resolve-dispute
           rng
           (long (or (:escrow-size p) (get-in p [:escrow-distribution :mean] 10000)))
           (:resolver-fee-bps p 150)
           (:appeal-bond-bps p 700)
           (:slash-multiplier p 2.5)
           (or (:force-strategy p) :honest)
           (:appeal-probability-if-correct p 0.05)
           (:appeal-probability-if-wrong p 0.40)
           (:slashing-detection-probability p 0.1)
           (optional-kw-args p))))

(deftest validate-oracle-params-rejects-invalid-scope
  (testing ":scope must be a subset of #{:detection :appeal}"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"subset of"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :stochastic :scope #{:verdict}}})))))

(deftest validate-oracle-params-accepts-appeal-scope
  (testing ":scope #{:appeal} is valid for appeal-only fixtures"
    (let [params (det/validate-oracle-params!
                  {:oracle-fixture {:mode :stochastic :scope #{:appeal}}})]
      (is (= #{:appeal} (:scope (:oracle-effective params)))))))

(deftest validate-oracle-params-rejects-unknown-roll-kind
  (testing "per-kind :rolls keys must be known detection kinds or :default"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unknown oracle-fixture"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :fixed-roll-sequence
                            :rolls {:fraud-detect [0.1]}
                            :scope #{:detection}}})))))

(deftest validate-oracle-params-rejects-orphan-oracle-roll-sequence
  (testing ":oracle-roll-sequence is orphan when effective mode is not :fixed-roll-sequence"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Orphan oracle legacy"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :stochastic}
           :oracle-roll-sequence [0.1 0.2]})))))

(deftest validate-oracle-params-rejects-conflicting-oracle-mode
  (testing ":oracle-mode conflicts with :oracle-fixture :mode"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Orphan oracle legacy"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :stochastic}
           :oracle-mode :fixed-roll-sequence})))))

(deftest fixed-or-rejects-mixed-oracle-fixture-rolls
  (testing "validate-oracle-params! rejects mixed :oracle-fixture :rolls and :fixed-or"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"both :fixed-or and :oracle-fixture :rolls"
         (det/validate-oracle-params!
          {:oracle-fixture {:mode :stochastic :rolls [0.5]}
           :fixed-or [0.99 0.01]
           :oracle-roll-sequence [0.3]})))
    ;; Legacy :oracle-roll-sequence alongside :fixed-or is fine (no :rolls key conflict)
    (let [params (det/validate-oracle-params!
                  {:fixed-or [0.99 0.01]
                   :oracle-roll-sequence [0.3]})
          effective (:oracle-effective params)]
      (is (= :fixed-roll-sequence (:mode effective)))
      (is (= [0.99 0.01] (:rolls effective)))
      (is (= :shared-stream (:fixture-mode effective))))))

(deftest appeal-fixture-scripts-l1-reversal
  (testing ":scope #{:appeal} uses fixed roll for L1 reversal threshold"
    (let [params (det/prepare-oracle-params
                  {:rng (rng/make-rng 1)
                   :oracle-fixture {:mode :fixed-roll-sequence
                                    :rolls {:l1-reversal [0.01]}
                                    :scope #{:appeal}
                                    :on-exhaustion :throw}
                   :p-l1-reversal 0.5
                   :has-kleros? false})
          outcome (det/appeal-reversal-outcome (rng/make-rng 99) params
                                               {:verdict-correct? false
                                                :appealed? true})]
      (is (true? (:l1-reversed? outcome)))
      (is (true? (:decision-reversed? outcome))))))

(deftest prepare-oracle-params-attaches-effective-and-cursors
  (testing "prepare-oracle-params supplies cursors and :oracle-effective"
    (let [params (det/prepare-oracle-params
                  {:oracle-fixture {:mode :static-no-slash}
                   :oracle-roll-trace-enabled? true})]
      (is (instance? clojure.lang.Atom (:oracle-roll-cursor params)))
      (is (= :static-no-slash (:mode (:oracle-effective params))))
      (is (instance? clojure.lang.Atom (:oracle-roll-trace params))))))

(deftest all-control-oracle-param-files-validate
  (testing "every data/params/control-oracle-*.edn loads with expected :oracle-effective :mode"
    (doseq [[path expected-mode] control-oracle-param-files]
      (let [merged (io-params/validate-and-merge path)]
        (is (= expected-mode (:mode (:oracle-effective merged)))
            (str path " effective mode"))
        (is (map? (:oracle-effective merged))
            (str path " has :oracle-effective")))))
  (testing "control-oracle-full-trial.edn includes appeal + detection scope"
    (let [merged (io-params/validate-and-merge
                  "data/params/control-oracle-full-trial.edn")]
      (is (= #{:detection :appeal} (:scope (:oracle-effective merged)))))))

(deftest control-oracle-full-trial-resolve-dispute-trace
  (testing "control-oracle-full-trial.edn drives appeal + reversal rolls in :oracle-roll-trace"
    (let [p (io-params/validate-and-merge "data/params/control-oracle-full-trial.edn")
          result (resolve-dispute-from-merged-params p)
          kinds (set (map :roll/kind (:oracle-roll-trace result)))]
      (is (true? (:oracle-roll-trace-enabled? p)))
      (is (seq (:oracle-roll-trace result)))
      (is (true? (:appeal-triggered? result)))
      (is (contains? kinds :l1-reversal)
          "appeal scope scripts L1 reversal roll")
      (is (contains? kinds :reversal-detection)
          "detection scope scripts reversal slash roll")
      (is (= :reversal (:slashing-reason result))))))

(deftest resolve-dispute-fixed-or-per-kind-reversal-trace
  (testing "scripted reversal detection appears in :oracle-roll-trace"
    (let [rng   (rng/make-rng 101)
          result
          (dispute/resolve-dispute
           rng 10000 150 700 0 :collusive 0 1.0 0
           :p-l1-reversal 1.0
           :fraud-detection-probability 0
           :timeout-detection-probability 0
           :slashing-detection-probability 0
           :reversal-slash-bps 2500
           :reversal-detection-probability 0.25
           :oracle-fixture {:mode :fixed-roll-sequence
                            :rolls {:reversal-detection [0.20]
                                    :fraud-detection [0.99]
                                    :timeout-detection [0.99]
                                    :l1-detection [0.99]}
                            :scope #{:detection}
                            :on-exhaustion :throw}
           :oracle-roll-trace-enabled? true)]
      (is (seq (:oracle-roll-trace result)))
      (is (some #(= :reversal-detection (:roll/kind %)) (:oracle-roll-trace result)))
      (when (= :reversal (:slashing-reason result))
        (is (true? (:slashed? result)))))))

(deftest types-validate-scenario-runs-oracle-check
  (testing "validate-scenario invokes oracle validation"
    (is (types/validate-scenario
         (merge types/default-params
                {:description "oracle validation smoke"
                 :scenario-id "oracle-validation-smoke"
                 :rng-seed 1
                 :escrow-distribution {:type :constant :value 10000}
                 :strategy-mix {:honest 1.0 :lazy 0.0 :malicious 0.0 :collusive 0.0}
                 :oracle-fixture {:mode :static-no-slash}})))))

;; ── Per-kind detection activation tests ─────────────────────────────────

(deftest fixed-or-fraud-detection-active
  (testing ":fixed-or :fraud-detection rolls are consumed when fraud-detection-probability > 0"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 0.05 0.40 0.1
                  :fraud-detection-probability 0.5
                  :fixed-or {:rolls {:fraud-detection [0.01]}
                             :scope #{:detection}
                             :on-exhaustion :throw
                             :on-unknown-roll-kind :stochastic}
                  :oracle-roll-trace-enabled? true)]
      (is (some #(= :fraud-detection (:roll/kind %))
                (:oracle-roll-trace result))
          ":fraud-detection roll consumed when threshold > 0"))))

(deftest fixed-or-timeout-detection-active
  (testing ":fixed-or :timeout-detection rolls are consumed when timeout-detection-probability > 0"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 43) 10000 150 700 2.5 :malicious 0.05 0.40 0.1
                  :timeout-detection-probability 0.5
                  :fixed-or {:rolls {:timeout-detection [0.01]}
                             :scope #{:detection}
                             :on-exhaustion :throw
                             :on-unknown-roll-kind :stochastic}
                  :oracle-roll-trace-enabled? true)]
      (is (some #(= :timeout-detection (:roll/kind %))
                (:oracle-roll-trace result))
          ":timeout-detection roll consumed when threshold > 0"))))

(deftest fixed-or-l2-detection-active
  (testing ":fixed-or :l2-detection rolls are consumed when l2-detection-prob > 0"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 44) 10000 150 700 2.5 :malicious 0.05 1.0 0.1
                  :l2-detection-prob 0.5
                  :p-l1-reversal 1.0
                  :fixed-or {:rolls {:l2-detection [0.01]}
                             :scope #{:detection}
                             :on-exhaustion :throw
                             :on-unknown-roll-kind :stochastic}
                  :oracle-roll-trace-enabled? true)]
      (is (:appeal-triggered? result) "appeal must fire for the L2 path to be reached")
      (is (some #(= :l2-detection (:roll/kind %))
                (:oracle-roll-trace result))
          ":l2-detection roll consumed when appeal fires and threshold > 0"))))

;; ── Pending-evidence / effective-bond-loss integration tests ────────────

(deftest pending-evidence-defers-loss-when-not-frozen
  (testing "when slashing-pending? and not frozen, profit-malice reflects deferred loss"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :fraud-detection-probability 0
                  :reversal-detection-probability 1.0
                  :new-evidence-probability 1.0
                  :reversal-slash-bps 2500
                  :p-l1-reversal 1.0
                  :freeze-on-detection? false
                  :has-kleros? true
                  :fixed-or {:rolls {:reversal-detection [0.01]
                                     :pending-evidence [0.01]}
                             :scope #{:detection}
                             :on-unknown-roll-kind :stochastic})
          fee 150  ; = 10000 * 150 / 10000
          expected-profit fee]
      (is (:appeal-triggered? result) "appeal must trigger for reversal path")
      (is (:slashing-pending? result)
          "slashing-pending? must be true: new-evidence-probability=1.0 and reversal-slashed?")
      (is (not (:frozen? result)) "frozen? must be false: freeze-on-detection?=false")
      (is (= expected-profit (:profit-malice result))
          (str "profit-malice should equal fee (" fee ") when loss is deferred by pending-evidence"))
      (is (pos? (:bond-loss result))
          "raw bond-loss is positive (the reporting key, not effective)"))))

(deftest pending-evidence-applies-loss-when-frozen
  (testing "when slashing-pending? and frozen (not escaped), profit-malice reflects full bond loss"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :fraud-detection-probability 0
                  :reversal-detection-probability 1.0
                  :new-evidence-probability 1.0
                  :reversal-slash-bps 2500
                  :p-l1-reversal 1.0
                  :freeze-on-detection? true
                  ;; 14 >= freeze(3) + appeal(7): withdrawal cannot complete before
                  ;; the slash executes, so can-escape? is false → escaped?=false
                  :unstaking-delay-days 14
                  :has-kleros? true
                  :fixed-or {:rolls {:reversal-detection [0.01]
                                     :pending-evidence [0.01]}
                             :scope #{:detection}
                             :on-unknown-roll-kind :stochastic})
          fee 150]
      (is (:slashing-pending? result) "slashing-pending? must be true")
      (is (:frozen? result) "frozen? must be true: freeze-on-detection?=true")
      (is (not (:escaped? result))
          "escaped? must be false: unstaking delay exceeds freeze + appeal window")
      (is (pos? (:bond-loss result))
          "bond-loss must be positive when frozen despite slashing-pending?")
      (is (< (:profit-malice result) fee)
          "profit-malice must be less than fee when bond loss is applied"))))

(deftest pending-evidence-defers-loss-when-frozen-but-escaped
  (testing "when slashing-pending?, frozen, and escape possible, the resolver escapes with no bond loss"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :fraud-detection-probability 0
                  :reversal-detection-probability 1.0
                  :new-evidence-probability 1.0
                  :reversal-slash-bps 2500
                  :p-l1-reversal 1.0
                  :freeze-on-detection? true
                  ;; 5 < freeze(3) + appeal(7): withdrawal completes before the
                  ;; slash executes, so can-escape? is true → escaped?=true
                  :unstaking-delay-days 5
                  :has-kleros? true
                  :fixed-or {:rolls {:reversal-detection [0.01]
                                     :pending-evidence [0.01]}
                             :scope #{:detection}
                             :on-unknown-roll-kind :stochastic})
          fee 150]
      (is (:slashing-pending? result) "slashing-pending? must be true")
      (is (:frozen? result) "frozen? must be true: freeze-on-detection?=true")
      (is (:escaped? result)
          "escaped? must be true: short unstaking delay beats freeze + appeal window")
      (is (= fee (:profit-malice result))
          (str "profit-malice should equal fee (" fee ") when the resolver escapes")))))

(deftest pending-evidence-zero-slash-bps-skips-pending
  (testing "slashing-pending? is false when reversal-slash-bps=0 despite new-evidence-probability>0"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :fraud-detection-probability 0
                  :reversal-detection-probability 1.0
                  :new-evidence-probability 0.5
                  :reversal-slash-bps 0                     ; zero → reversal-slashed-live? returns false
                  :p-l1-reversal 1.0
                  :has-kleros? true
                  :fixed-or {:rolls {:reversal-detection [0.01]
                                     :pending-evidence [0.99]}
                             :scope #{:detection}
                             :on-unknown-roll-kind :stochastic})]
      ;; When reversal-slash-bps=0, reversal-slashed-live? returns false (gated by pos? check),
      ;; so slashed-detected? depends on other detection.  L1 may or may not fire.
      ;; The point is that slashing-pending? is always false because reversal-pending-live?
      ;; is also gated by reversal-slash-bps, meaning the pending-evidence oracle roll
      ;; is NOT consumed regardless of new-evidence-probability.
      (is (not (:slashing-pending? result))
          "slashing-pending? must be false: reversal-slash-bps=0 gates both reversal and pending-evidence paths")
      (is (nil? (:slashing-reason result))
          "slashing-reason should be nil: reversal-slash-bps=0 prevents reversal, l1 detection depends on rng"))))

;; ── reversal-pending? output key tests (Gap 3) ─────────────────────────

(deftest reversal-pending-key-true-when-track-2
  (testing ":reversal-pending? is true when Track 2 (new evidence) triggers pending"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :fraud-detection-probability 0
                  :reversal-detection-probability 1.0
                  :new-evidence-probability 1.0
                  :reversal-slash-bps 2500
                  :p-l1-reversal 1.0
                  :has-kleros? true
                  :fixed-or {:rolls {:reversal-detection [0.01]
                                     :pending-evidence [0.01]}
                             :scope #{:detection}
                             :on-unknown-roll-kind :stochastic})]
      (is (:slashing-pending? result) "slashing-pending? must be true")
      (is (:reversal-pending? result)
          "reversal-pending? must be true when pending-evidence fires")
      (is (zero? (:slashing-delay-weeks result))
          "delay-weeks must be 0: only reversal-pending triggered, not slashing-detection-delay"))))

(deftest reversal-pending-key-false-when-delay-only
  (testing ":reversal-pending? is false when pending comes from delay-weeks, not Track 2"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :fraud-detection-probability 0
                  :reversal-detection-probability 1.0
                  :new-evidence-probability 0.0           ; no Track 2
                  :reversal-slash-bps 2500
                  :p-l1-reversal 1.0
                  :slashing-detection-delay-weeks 3        ; Phase G delay
                  :has-kleros? true
                  :fixed-or {:rolls {:reversal-detection [0.01]}
                             :scope #{:detection}
                             :on-unknown-roll-kind :stochastic})]
      (is (:slashing-pending? result) "slashing-pending? must be true (delay weeks)")
      (is (not (:reversal-pending? result))
          "reversal-pending? must be false: new-evidence-probability=0")
      (is (= 3 (:slashing-delay-weeks result))
          "delay-weeks must be 3: slashing-detection-delay-weeks=3"))))

(deftest pending-evidence-gated-shared-stream-cursor
  (testing "shared-vector cursor aligns when reversal-pending-live? gates out (no reversal-slashed)"
      ;; Setup: malicious resolver, but p-l1-reversal=0 so verdict never reversed.
      ;; reversal-pending-live? is called but returns false because reversal-slashed? is false.
      ;; The :pending-evidence roll should NOT be consumed from the shared stream,
      ;; leaving subsequent rolls (for other detection) at the correct position.
    (let [cursor (atom 0)
          result (dispute/resolve-dispute
                  (rng/make-rng 42) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :p-l1-reversal 0.0                     ; never reverses → no reversal-slashed
                  :fraud-detection-probability 0
                  :reversal-detection-probability 1.0
                  :new-evidence-probability 0.5
                  :reversal-slash-bps 2500
                  :has-kleros? false
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls [0.99 0.01]}
                  :oracle-roll-cursor cursor)]
        ;; No reversal slash because p-l1-reversal=0 (verdict not reversed)
      (is (nil? (:slashing-reason result))
          "no slashing: reversal never triggered")
      (is (not (:slashing-pending? result))
          "slashing-pending? must be false: no reversal")
      (is (not (:reversal-pending? result))
          "reversal-pending? must be false: no reversal to pend"))))

;; ── All-9 roll kinds integration test ───────────────────────────────────

(deftest fixed-or-all-roll-kinds-consumed
  (testing "all 9 oracle roll kinds consumed in one trial with :fixed-or"
    (let [result (dispute/resolve-dispute
                  (rng/make-rng 45) 10000 150 700 2.5 :malicious 1.0 1.0 0.1
                  :fraud-detection-probability 0.5
                  :timeout-detection-probability 0.5
                  :reversal-detection-probability 0.5
                  :new-evidence-probability 0.5
                  :l2-detection-prob 0.5
                  :reversal-slash-bps 2500
                  ;; 0.5 with roll 0.99: L1 does NOT reverse, so the L2 branch
                  ;; (escalation + reversal) is also exercised in this trial.
                  :p-l1-reversal 0.5
                  :p-l2-escalation 1.0
                  :p-l2-reversal 1.0
                  :has-kleros? true
                  :fixed-or {:rolls {:fraud-detection [0.99]   ; consumed, NOT detected → :l1-detection fires
                                     :timeout-detection [0.01]
                                     :pending-evidence [0.01]
                                     :l2-detection [0.01]
                                     :reversal-detection [0.01]
                                     :l1-detection [0.01]
                                     :l1-reversal [0.99]       ; not reversed → :l2-escalation reachable
                                     :l2-escalation [0.01]
                                     :l2-reversal [0.01]}
                             :scope #{:detection :appeal}
                             :on-exhaustion :throw}
                  :oracle-roll-trace-enabled? true)
          trace (:oracle-roll-trace result)
          kinds (set (map :roll/kind trace))]
      (is (seq trace) "roll trace should be non-empty")
      (is (contains? kinds :fraud-detection) ":fraud-detection consumed")
      (is (contains? kinds :timeout-detection) ":timeout-detection consumed")
      (is (contains? kinds :l1-detection) ":l1-detection consumed")
      (is (contains? kinds :reversal-detection) ":reversal-detection consumed")
      (is (contains? kinds :pending-evidence) ":pending-evidence consumed")
      (is (contains? kinds :l2-detection) ":l2-detection consumed")
      (is (contains? kinds :l1-reversal) ":l1-reversal consumed")
      (is (contains? kinds :l2-escalation) ":l2-escalation consumed")
      (is (contains? kinds :l2-reversal) ":l2-reversal consumed")))

;; ── Cursor semantics tests ───────────────────────────────────────────────

  (deftest shared-vector-cursor-crosses-kinds
    (testing "shared vector cursor advances across different roll kinds in call order"
      (let [params {:rng (rng/make-rng 40)
                    :oracle-fixture {:mode :fixed-roll-sequence
                                     :rolls [0.90 0.01 0.80 0.02]
                                     :scope #{:detection}
                                     :on-exhaustion :throw}
                    :oracle-roll-cursor (atom 0)
                    :fraud-detection-probability 0.1
                    :timeout-detection-probability 0.1}
            out (det/detect-probabilistic-violations params :malicious false 0.1)]
      ;; call order: fraud (0.90 >= 0.1 → false), timeout (0.01 < 0.1 → true), l1 (0.80 >= 0.1 → false)
        (is (false? (:fraud-detected? out)) ":fraud gets roll 0")
        (is (true? (:timeout-detected? out)) ":timeout gets roll 1")
        (is (false? (:l1-slashed? out)) ":l1 gets roll 2")
      ;; fourth roll (0.02) not consumed because detect-probabilistic-violations does 3 checks
        (let [cursor @(:oracle-roll-cursor params)]
          (is (= 3 cursor) "shared cursor advanced to 3 after 3 detection calls")))))

  (deftest per-kind-map-cursor-advances-independently
    (testing "per-kind cursors advance independently for each roll-kind"
      (let [cursors (atom {})
            params {:rng (rng/make-rng 41)
                    :oracle-fixture {:mode :fixed-roll-sequence
                                     :rolls {:fraud-detection [0.90 0.01 0.30]
                                             :timeout-detection [0.01 0.90]}
                                     :scope #{:detection}
                                     :on-exhaustion :throw
                                     :on-unknown-roll-kind :stochastic}
                    :oracle-roll-cursors cursors
                    :fraud-detection-probability 0.1
                    :timeout-detection-probability 0.1}
            out (det/detect-probabilistic-violations params :malicious false 0.1)]
      ;; fraud gets its own 0.90 → false; timeout gets its own 0.01 → true; l1 unrolled → false
        (is (false? (:fraud-detected? out)) ":fraud uses [0.90] → false")
        (is (true? (:timeout-detected? out)) ":timeout uses [0.01] → true")
      ;; second call: fraud advances to next roll in its independent sequence
        (let [out2 (det/detect-probabilistic-violations params :malicious false 0.1)]
          (is (true? (:fraud-detected? out2)) ":fraud second call uses [0.01] → true")
          (is (false? (:timeout-detected? out2)) ":timeout second call uses [0.90] → false"))
      ;; verify cursor positions
        (let [fraud-cursor (get @cursors :fraud-detection 0)
              timeout-cursor (get @cursors :timeout-detection 0)]
          (is (= 2 fraud-cursor) ":fraud cursor advanced to 2 after 2 calls")
          (is (= 2 timeout-cursor) ":timeout cursor advanced to 2 after 2 calls")))))

  (deftest shared-vector-cycle-exhaustion
    (testing ":cycle on shared vector wraps the single cursor back to start"
      (let [exhausted? (atom false)
            params {:rng (rng/make-rng 42)
                    :oracle-fixture {:mode :fixed-roll-sequence
                                     :rolls [0.01 0.50 1.0]
                                     :scope #{:detection}
                                     :on-exhaustion :cycle}
                    :oracle-roll-cursor (atom 0)
                    :oracle-fixture/exhausted? exhausted?
                    :fraud-detection-probability 0.1
                    :timeout-detection-probability 0.1}
          ;; 3 detection checks per call, 3-roll vector cycles cleanly
            results (repeatedly 3
                                #(det/detect-probabilistic-violations
                                  params :malicious false 0.1))
            fraud-results (map :fraud-detected? results)
            timeout-results (map :timeout-detected? results)]
      ;; Each 3-roll cycle: fraud(0.01)→true, timeout(0.50)→false, l1(1.0)→false
        (is (= [true false true false true false]
               (interleave fraud-results timeout-results))
            "shared cycle repeats [0.01 0.50 1.0] in order, l1(1.0)→false")
        (is (true? @exhausted?)
            "exhausted flag set after cycle begins"))))

  (deftest per-kind-map-cycle-advances-independently
    (testing ":cycle on per-kind map wraps each kind's sequence independently"
      (let [cursors (atom {})
            params {:rng (rng/make-rng 43)
                    :oracle-fixture {:mode :fixed-roll-sequence
                                     :rolls {:fraud-detection [0.01]
                                             :timeout-detection [0.90 0.01]}
                                     :scope #{:detection}
                                     :on-exhaustion :cycle
                                     :on-unknown-roll-kind :stochastic}
                    :oracle-roll-cursors cursors
                    :fraud-detection-probability 0.1
                    :timeout-detection-probability 0.1}
            results (repeatedly 3
                                #(det/detect-probabilistic-violations
                                  params :malicious false 0.1))
            fraud-results (map :fraud-detected? results)
            timeout-results (map :timeout-detected? results)]
      ;; fraud cycles [0.01] → always true; timeout cycles [0.90 0.01] → false, true, false
        (is (= [true true true] fraud-results)
            ":fraud-detection cycles [0.01] → always detected")
        (is (= [false true false] timeout-results)
            ":timeout-detection cycles [0.90 0.01] → false, true, false"))))

  (deftest validate-raises-on-mixed-fixed-or-and-fixture-rolls
    (testing "validate-oracle-params! rejects both :fixed-or and :oracle-fixture :rolls"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"both :fixed-or and :oracle-fixture :rolls"
           (det/validate-oracle-params!
            {:fixed-or [0.1 0.9]
             :oracle-fixture {:rolls {:fraud-detection [0.5]}}
             :fraud-detection-probability 0.1})))
    ;; non-roll keys on :oracle-fixture are still allowed alongside :fixed-or
      (is (det/validate-oracle-params!
           {:fixed-or [0.1 0.9]
            :oracle-fixture {:scope #{:detection :appeal}
                             :on-exhaustion :repeat-last}
            :fraud-detection-probability 0.1})
          "non-roll :oracle-fixture keys coexist with :fixed-or")))

  (deftest normalize-oracle-fixture-emits-fixture-mode
    (testing "normalize-oracle-fixture includes :fixture-mode metadata"
      (let [shared   (det/normalize-oracle-fixture {:fixed-or [0.1 0.9]})
            per-kind (det/normalize-oracle-fixture
                      {:oracle-fixture {:mode :fixed-roll-sequence
                                        :rolls {:fraud-detection [0.1]}
                                        :scope #{:detection}}})
            no-fix   (det/normalize-oracle-fixture {})]
        (is (= :shared-stream (:fixture-mode shared)))
        (is (= :per-kind (:fixture-mode per-kind)))
        (is (= :none (:fixture-mode no-fix)))))))
