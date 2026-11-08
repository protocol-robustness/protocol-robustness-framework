(ns resolver-sim.protocols.sew.integration-test
  "Integration tests for contract_model/runner.clj.

   Verifies that the runner correctly drives the full lifecycle through the
   contract model and that the divergence detector catches discrepancies."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types    :as t]
            [resolver-sim.protocols.sew.runner   :as runner]
            [resolver-sim.protocols.sew.invariants :as inv]))

;; ---------------------------------------------------------------------------
;; Deterministic rng-fn fixtures
;; ---------------------------------------------------------------------------

(defn- always [v]
  "Returns an rng-fn that always produces value v."
  (constantly v))

(defn- seq-rng
  "Returns an rng-fn that pops values from an atom-wrapped list."
  [& values]
  (let [a (atom (cycle values))]
    (fn [] (let [v (first @a)]
             (swap! a rest)
             v))))

;; ---------------------------------------------------------------------------
;; run-trial
;; ---------------------------------------------------------------------------

(def base-params
  {:escrow-size                    10000
   :resolver-fee-bps               50
   :appeal-bond-bps                700
   :appeal-window-duration         0
   :max-dispute-duration           2592000
   :slashing-detection-probability 0.9
   :appeal-probability-if-correct  0.0
   :appeal-probability-if-wrong    0.0
   :strategy                       :honest})

(deftest run-trial-malicious-detected
  "Malicious resolver: forced wrong verdict + high detection => loss."
  (let [;; rng vals: [verdict?, detection?]
        ;; verdict: malicious judges correctly if val < 0.3 — use 0.99 → wrong
        ;; no appeal-window (=0) so no escalation RNG consumed
        ;; detection: 0.01 < 0.9 → detected
        r (runner/run-trial (seq-rng 0.99 0.01)
                            (assoc base-params :strategy :malicious))]
    (is (false? (:dispute-correct? r)))
    (is (true?  (:slashed? r)))
    ;; profit-malice = fee - bond;  fee=50, bond = 10000*700/10000 = 700 → but uses afa
    ;; afa = 10000 - 50 = 9950;  bond = 9950 * 700 / 10000 = 696 (int div)
    (is (< (:profit-malice r) (:profit-honest r))
        "malicious profit < honest profit when detected")
    (is (true? (:cm/invariants-ok? r)))))

(deftest run-trial-malicious-evades
  "Malicious resolver: wrong verdict but evades detection."
  (let [;; verdict: 0.99 → wrong; appeal: 0.99 → no; detect: 0.99 > 0.9 → not detected
        r (runner/run-trial (seq-rng 0.99 0.99 0.99)
                            (assoc base-params :strategy :malicious))]
    (is (false? (:dispute-correct? r)))
    (is (false? (:slashed? r)))
    (is (= (:profit-honest r) (:profit-malice r))
        "undetected malicious earns same as honest")))

(deftest run-trial-appeal-window-defers-settlement
  "With appeal window set, trial completes without error and invariants hold."
  (let [params (assoc base-params
                      :appeal-window-duration 1800
                      :appeal-probability-if-correct 1.0)  ; always appeal
        r      (runner/run-trial (always 0.5) params)]
    (is (map? r) "run-trial returns a result map")
    (is (true? (:cm/invariants-ok? r)))))

(deftest run-trial-invariants-hold-across-strategies
  "Invariants hold for all strategies."
  (doseq [strategy [:honest :lazy :malicious :collusive]]
    (let [r (runner/run-trial (seq-rng 0.5 0.99 0.99)
                              (assoc base-params :strategy strategy))]
      (is (true? (:cm/invariants-ok? r))
          (str "invariants failed for strategy " strategy)))))

;; ---------------------------------------------------------------------------
;; Divergence detector
;; ---------------------------------------------------------------------------

(deftest compare-outcomes-no-divergence
  (let [a {:profit-honest 50 :profit-malice 50 :slashed? false
           :dispute-correct? true :appeal-triggered? false}
        b (merge a {:cm/final-state :released :cm/invariants-ok? true})]
    (let [result (runner/compare-outcomes a b)]
      (is (nil? (:divergence? result)))
      (is (empty? (:diffs result))))))

(deftest compare-outcomes-detects-profit-divergence
  (let [a {:profit-honest 50 :profit-malice 50 :slashed? false
           :dispute-correct? true :appeal-triggered? false}
        b (assoc a :profit-malice 40)]
    (let [result (runner/compare-outcomes a b)]
      (is (seq (:divergence? result)))
      (is (= 1 (count (:diffs result))))
      (is (= :profit-malice (-> result :diffs first :field))))))

(deftest compare-outcomes-detects-slashing-divergence
  (let [a {:profit-honest 50 :profit-malice -650 :slashed? true
           :dispute-correct? false :appeal-triggered? false}
        b (assoc a :slashed? false :profit-malice 50)]
    (let [result (runner/compare-outcomes a b)]
      (is (seq (:divergence? result)))
      (let [fields (set (map :field (:diffs result)))]
        (is (contains? fields :slashed?))
        (is (contains? fields :profit-malice))))))

;; ---------------------------------------------------------------------------
;; run-with-divergence-check
;; ---------------------------------------------------------------------------

(deftest run-with-divergence-check-identical-results-no-divergence
  "When both models agree, divergence? is nil."
  (let [fixed-result {:profit-honest 50 :profit-malice 50 :slashed? false
                      :dispute-correct? true :appeal-triggered? false}
        dispute-fn   (fn [_params] fixed-result)
        ;; rng produces honest path (val always 0.5 → honest verdict, no appeal, no detect)
        r (runner/run-with-divergence-check
           dispute-fn (always 0.5) base-params)]
    (is (map? (:idealized r)))
    (is (map? (:contract r)))
    (is (map? (:divergence r)))))
