(ns resolver-sim.accounting.held-ledger-index-test
  "Tests for the held-ledger index schema contract and reconciliation.
   The index is a five-dimensional cumulative map shared between the live
   Sew path and the replay path.  Integer-keyed accessors provide compact
   dimension references matching the review-member pattern."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.accounting.held-ledger-index :as hli]))

;; ── Empty index ────────────────────────────────────────────────────────────

(deftest empty-index-is-valid
  (let [idx (hli/empty-held-ledger-index)]
    (is (hli/valid-held-ledger-index? idx))
    (is (nil? (hli/explain-held-ledger-index idx)))))

(deftest empty-has-all-five-dimensions
  (let [idx (hli/empty-held-ledger-index)]
    (is (= #{} (set (keys (:by-token idx)))))
    (is (= #{} (set (keys (:by-position idx)))))
    (is (= #{} (set (keys (:by-account idx)))))
    (is (= #{} (set (keys (:by-owner idx)))))
    (is (= #{} (set (keys (:by-workflow idx)))))
    (is (= #{:by-token :by-position :by-account :by-owner :by-workflow}
           (set (keys idx))))))

(deftest empty-has-correct-dimension-order
  (is (= [:by-token :by-position :by-account :by-owner :by-workflow]
         (vec hli/index-dimensions))))

;; ── Valid index ───────────────────────────────────────────────────────────

(def sample-index
  {:by-token    {:USDC 4000, :ETH 50}
   :by-position {[:held/position :USDC :escrow-principal 0] 4000
                 [:held/position :ETH   :escrow-principal 1] 50}
   :by-account  {:escrow-principal 4050}
   :by-owner    {"0xAlice" 4000, "0xBob" 50}
   :by-workflow {0 4000, 1 50}})

(deftest sample-index-valid
  (is (hli/valid-held-ledger-index? sample-index)))

(deftest sample-index-dimension-keys
  (is (= #{:USDC :ETH} (set (keys (:by-token sample-index)))))
  (is (= 4000 (get-in sample-index [:by-token :USDC])))
  (is (= 50 (get-in sample-index [:by-workflow 1]))))

;; ── Invalid index rejection ───────────────────────────────────────────────

(deftest rejects-missing-dimension
  (let [bad (dissoc sample-index :by-workflow)]
    (is (not (hli/valid-held-ledger-index? bad)))
    (is (some? (hli/explain-held-ledger-index bad))))
  (let [bad (dissoc sample-index :by-token)]
    (is (not (hli/valid-held-ledger-index? bad)))))

(deftest rejects-non-integer-amount
  (let [bad (assoc-in sample-index [:by-token :USDC] 40.5)]
    (is (not (hli/valid-held-ledger-index? bad)))))

(deftest rejects-string-token-key
  (let [bad (assoc-in sample-index [:by-token "USDC"] 1000)]
    (is (not (hli/valid-held-ledger-index? bad)))))

;; ── Custody state ─────────────────────────────────────────────────────────

(def sample-custody-state
  {:held-ledger/index sample-index
   :total-held        {:USDC 4000, :ETH 50}
   :held/positions    {[:held/position :USDC :escrow-principal 0] 4000
                       [:held/position :ETH   :escrow-principal 1] 50}})

(deftest custody-state-valid
  (is (hli/valid-held-custody-state? sample-custody-state))
  (is (nil? (hli/explain-held-custody-state sample-custody-state))))

(deftest custody-state-rejects-missing-index
  (let [bad (dissoc sample-custody-state :held-ledger/index)]
    (is (not (hli/valid-held-custody-state? bad)))))

;; ── Reconciliation ────────────────────────────────────────────────────────

(deftest reconcile-passes-when-aliases-match
  (is (hli/reconcile? sample-custody-state)))

(deftest reconcile-fails-when-total-held-mismatch
  (let [bad (assoc sample-custody-state :total-held {:USDC 9999})]
    (is (not (hli/reconcile? bad)))))

(deftest reconcile-fails-when-held-positions-mismatch
  (let [bad (assoc sample-custody-state :held/positions {})]
    (is (not (hli/reconcile? bad)))))

(deftest reconcile-fails-on-missing-total-held
  (let [bad (dissoc sample-custody-state :total-held)]
    (is (not (hli/reconcile? bad)))))

;; ── Integer-keyed dimension accessors ─────────────────────────────────────
;; These provide compact integer references to held-ledger dimensions,
;; matching the pattern established in review-member-canonical-indices.

(deftest index-dimensions-has-canonical-order
  (is (= [:by-token :by-position :by-account :by-owner :by-workflow]
         hli/index-dimensions))
  (is (= 5 (count hli/index-dimensions))))

(deftest dimension-count-stable
  ;; The five dimensions are foundational — changing them is a schema version
  ;; bump.  This test catches accidental additions or removals.
  (is (= 5 (count hli/index-dimensions))))

;; ── Edge cases ────────────────────────────────────────────────────────────

(deftest empty-index-reconciles-with-empty-custody-state
  (let [empty-idx (hli/empty-held-ledger-index)
        state {:held-ledger/index empty-idx
               :total-held {}
               :held/positions {}}]
    (is (hli/reconcile? state))))

(deftest by-owner-allow-negative
  (let [idx (assoc-in sample-index [:by-owner "0xAlice"] -500)]
    (is (hli/valid-held-ledger-index? idx))))
