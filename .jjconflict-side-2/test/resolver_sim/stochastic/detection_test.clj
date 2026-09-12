(ns resolver-sim.stochastic.detection-test
  "Tests for probabilistic detection helpers, including the new-evidence-probability
   gate that controls Track 2 pending-evidence reversal slashing and the
   L2 (Kleros) backstop detection."
  (:require [clojure.test :refer :all]
            [resolver-sim.stochastic.detection :as detection]
            [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.io.params :as io-params]
            [malli.core :as m]))

(deftest reversal-pending-live?-zero-threshold-disabled
  (is (false? (detection/reversal-pending-live?
               {:rng (rng/make-rng 42)
                :new-evidence-probability 0.0
                :reversal-slash-bps 2500}
               {:reversal-slashed? true}))
      "reversal-pending-live? should return false when threshold is 0.0"))

(deftest reversal-pending-live?-not-slashed-disabled
  (is (false? (detection/reversal-pending-live?
               {:rng (rng/make-rng 42)
                :new-evidence-probability 0.05
                :reversal-slash-bps 2500}
               {:reversal-slashed? false}))
      "reversal-pending-live? should return false when not reversal-slashed"))

(deftest reversal-pending-live?-positive-threshold-active
  (let [params  {:rng (rng/make-rng 42)
                 :new-evidence-probability 1.0
                 :reversal-slash-bps 2500}
        result  (detection/reversal-pending-live? params {:reversal-slashed? true})]
    (is (true? result)
        "reversal-pending-live? should return true when threshold=1.0 (always fires)")))

(deftest reversal-pending-live?-probabilistic-roll
  (let [n-trials 1000
        params-fn (fn [seed]
                    {:rng (rng/make-rng seed)
                     :new-evidence-probability 0.5
                     :reversal-slash-bps 2500})
        results (for [s (range n-trials)]
                  (detection/reversal-pending-live? (params-fn s) {:reversal-slashed? true}))
        rate (/ (count (filter true? results)) (double n-trials))]
    (is (< 0.40 rate 0.60)
        (str "reversal-pending-live? with 0.5 threshold should fire ~50% over " n-trials " trials; got " rate))))

(deftest reversal-pending-live?-trace-decision-marker
  (let [params {:rng (rng/make-rng 42)
                :oracle-roll-trace-enabled? true
                :new-evidence-probability 1.0
                :reversal-slash-bps 2500
                :scenario-id "detection-test"}
        result (detection/reversal-pending-live? params {:reversal-slashed? true})]
    (is (true? result)
        "oracle-roll-trace-enabled? should not affect reversal-pending-live? outcome")))

(deftest reversal-pending-live?-zero-slash-bps-no-roll
  (let [cursor (atom 0)
        params {:rng (rng/make-rng 42)
                :oracle-fixture {:mode :fixed-roll-sequence
                                 :rolls [0.01]
                                 :scope #{:detection}
                                 :on-exhaustion :throw}
                :oracle-roll-cursor cursor
                :new-evidence-probability 0.5
                :reversal-slash-bps 0}
        result (detection/reversal-pending-live? params {:reversal-slashed? true})]
    (is (false? result)
        "reversal-pending-live? must return false when reversal-slash-bps is 0")
    (is (zero? @cursor)
        "oracle roll cursor must NOT advance when reversal-slash-bps is 0")))

(deftest reversal-pending-live?-shared-stream-skip-no-cursor-advance
  (testing "shared-stream cursor does not advance when reversal-pending-live? is gated out (not reversal-slashed)"
    (let [cursor (atom 0)
          params {:rng (rng/make-rng 42)
                  :oracle-fixture {:mode :fixed-roll-sequence
                                   :rolls [0.01 0.99]
                                   :scope #{:detection}
                                   :on-exhaustion :throw}
                  :oracle-roll-cursor cursor
                  :new-evidence-probability 0.5
                  :reversal-slash-bps 2500}
          ;; When reversal-slashed? is false, reversal-pending-live? returns false
          ;; WITHOUT calling oracle-roll-event. The shared cursor must NOT advance.
          result (detection/reversal-pending-live? params {:reversal-slashed? false})]
      (is (false? result)
          "reversal-pending-live? must return false when not reversal-slashed")
      (is (zero? @cursor)
          "shared-stream cursor must NOT advance when reversal-pending-live? skips the roll"))))

(deftest new-evidence-probability-integration
  (testing "schema validator rejects out-of-range values"
    (let [entry-schema (m/validator [:and number? [:>= 0] [:<= 1]])]
      (is (entry-schema 0.0) "0.0 is valid")
      (is (entry-schema 0.05) "0.05 is valid")
      (is (entry-schema 1.0) "1.0 is valid")
      (is (not (entry-schema -0.1)) "-0.1 is invalid")
      (is (not (entry-schema 1.5)) "1.5 is invalid")
      (is (not (entry-schema "0.5")) "string is invalid")))

  (testing "params EDN files have differentiated values"
    (let [baseline   (io-params/load-edn "data/params/phase-n-baseline.edn")
          high-fraud (io-params/load-edn "data/params/phase-n-high-fraud.edn")]
      (is (< (get baseline :new-evidence-probability 0)
             (get high-fraud :new-evidence-probability 0))
          "high-fraud new-evidence-probability should exceed baseline"))))

(deftest fixed-or-per-kind-map-pipeline
  (testing ":fixed-or with per-kind map propagates through to reversal-pending-live?"
    (let [params  {:fixed-or {:rolls {:pending-evidence [0.9 0.9 0.9 0.9 0.9 0.1]}
                              :scope #{:detection}}
                   :new-evidence-probability 0.5
                   :reversal-slash-bps 2500}
          ;; The first 5 rolls (0.9) are NOT < 0.5 → no detect.
          ;; The 6th roll (0.1) IS < 0.5 → detect.
          prepared (detection/prepare-oracle-params params)
          oracle-effective (:oracle-effective prepared)]
      (is (= :fixed-roll-sequence (:mode oracle-effective))
          ":fixed-or shorthand must normalize to :fixed-roll-sequence")
      (is (contains? oracle-effective :rolls)
          "oracle fixture must have :rolls")
      ;; The fixture has 5 zeros then a 1.0 — with threshold 1.0 and
      ;; reversal-slashed? true, the 6th roll (1.0) should detect.
      (let [_ (dotimes [_ 5]
                (detection/oracle-roll-event prepared :pending-evidence))
            result (detection/reversal-pending-live?
                    prepared {:reversal-slashed? true})]
        (is result "reversal-pending-live? should detect on 6th roll (0.1 < 0.5 threshold)")))))

(deftest fixed-or-simple-vector-fallback
  (testing ":fixed-or simple vector feeds shared roll sequence across kinds"
    (let [params  {:fixed-or [0.9 0.9 0.9 0.9 0.9 0.1]
                   :oracle-mode :fixed-or
                   :new-evidence-probability 0.5
                   :reversal-slash-bps 2500}
          prepared (detection/prepare-oracle-params params)
          oracle-effective (:oracle-effective prepared)]
      (is (= :fixed-roll-sequence (:mode oracle-effective))
          ":fixed-or shorthand must normalize to :fixed-roll-sequence")
      (is (vector? (:rolls oracle-effective))
          "simple vector must remain a vector, not become a per-kind map")
      ;; Consume 5 rolls via :fraud-detection (valid roll-kind, shared cursor)
      (dotimes [_ 5]
        (detection/oracle-roll-event prepared :fraud-detection))
      ;; The 6th roll (0.1) is now at cursor position 5 (0-indexed).
      ;; :pending-evidence falls back to the same shared sequence.
      (let [result (detection/reversal-pending-live?
                    prepared {:reversal-slashed? true})]
        (is result "simple vector: 6th roll 0.1 < 0.5 threshold should detect on :pending-evidence")))))

;; ── L2 (Kleros) backstop detection ─────────────────────────────────────────

(deftest l2-slashed?-zero-threshold
  (is (false? (detection/l2-slashed?
               {:rng (rng/make-rng 7) :l2-detection-prob 0}
               {:verdict-correct? false :appealed? true}))
      "l2-slashed? returns false when threshold is 0"))

(deftest l2-slashed?-not-appealed
  (is (false? (detection/l2-slashed?
               {:rng (rng/make-rng 7) :l2-detection-prob 0.99}
               {:verdict-correct? false :appealed? false}))
      "l2-slashed? returns false when not appealed"))

(deftest l2-slashed?-correct-verdict
  (is (false? (detection/l2-slashed?
               {:rng (rng/make-rng 7) :l2-detection-prob 0.99}
               {:verdict-correct? true :appealed? true}))
      "l2-slashed? returns false when verdict is correct"))

(deftest l2-slashed?-detects-wrong-appealed
  (testing "l2-slashed? detects wrong+appealed verdict when roll under threshold"
    (let [result (detection/l2-slashed?
                  {:rng (rng/make-rng 42) :l2-detection-prob 0.99}
                  {:verdict-correct? false :appealed? true})]
      (is (true? result)
          "l2-slashed? should detect at 0.99 threshold with rng 42"))))

(deftest l2-slashed?-misses-when-roll-over-threshold
  (testing "l2-slashed? misses when roll value > threshold"
    (let [result (detection/l2-slashed?
                  {:rng (rng/make-rng 7) :l2-detection-prob 0.01}
                  {:verdict-correct? false :appealed? true})]
      (is (false? result)
          "l2-slashed? should miss at 0.01 threshold with rng 7"))))

(deftest l2-slashed?-gated-by-has-kleros
  (testing "l2-slashed? returns false when has-kleros? is false regardless of l2-detection-prob"
    (is (false? (detection/l2-slashed?
                 {:rng (rng/make-rng 42)
                  :l2-detection-prob 0.99
                  :has-kleros? false}
                 {:verdict-correct? false :appealed? true}))
        "l2-slashed? must be suppressed when has-kleros? is false")))

(deftest l2-slashed?-default-has-kleros-true
  (testing "l2-slashed? defaults has-kleros? to true when not set"
    (let [result (detection/l2-slashed?
                  {:rng (rng/make-rng 42) :l2-detection-prob 0.99}
                  {:verdict-correct? false :appealed? true})]
      (is (true? result)
          "l2-slashed? should default has-kleros? to true"))))

(deftest l2-slashed?-static-no-slash-suppresses
  (testing ":static-no-slash oracle fixture suppresses L2 detection"
    (is (false? (detection/l2-slashed?
                 {:rng (rng/make-rng 9)
                  :oracle-fixture {:mode :static-no-slash}
                  :l2-detection-prob 0.99}
                 {:verdict-correct? false :appealed? true}))
        "static-no-slash must suppress L2 detection")))

(deftest l2-slashed?-consumes-l2-detection-roll
  (testing "l2-slashed? consumes an :l2-detection oracle roll"
    (let [cursor (atom 0)
          result (detection/l2-slashed?
                  {:rng (rng/make-rng 42)
                   :l2-detection-prob 0.99
                   :oracle-fixture {:mode :fixed-roll-sequence
                                    :rolls [0.01 0.99]
                                    :scope #{:detection}
                                    :on-exhaustion :throw}
                   :oracle-roll-cursor cursor}
                  {:verdict-correct? false :appealed? true})]
      (is (true? result) "should detect")
      (is (= 1 @cursor) "should have consumed 1 roll"))))
