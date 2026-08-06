(ns resolver-sim.allocation.reconciliation-test
  "Direct and kernel-level tests for the strengthened capacity reconciliation.
   The public :result-capacity-reconciles claim must fail when per-award
   obligations are violated even if the global total still equals capacity."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.allocation.reconciliation :as reconciliation]
            [resolver-sim.allocation.test-fixtures :as fixtures]))

(defn- happy-parts
  "Build a passing kernel result plus the components reconciliation needs."
  []
  (let [input (fixtures/happy-with-committed)
        ctx (context/build-context input)
        result (kernel/run-kernel input)
        selected-outcome (first (filter #(= (:selected-outcome-id result) (:outcome/id %))
                                        (:outcomes ctx)))
        leaves (kernel/result-leaves ctx selected-outcome (:allocation-context-hash result))]
    {:context ctx
     :selected-outcome selected-outcome
     :leaves leaves
     :result result}))

(defn- reconcile-opts [parts & {:keys [selected-outcome leaves total residual committed-root rounding-policy]
                                :or {rounding-policy "floor-to-asset-decimals.v1"}}]
  (let [result (:result parts)]
    {:context (:context parts)
     :selected-outcome (or selected-outcome (:selected-outcome parts))
     :leaves (or leaves (:leaves parts))
     :total-allocated (or total (:total-allocated result))
     :residual-capacity (or residual (:residual-capacity result))
     :committed-result-root (or committed-root (get-in (fixtures/happy-committed) [:result-root]))
     :rounding-policy rounding-policy}))

(deftest happy-path-reconciles
  (let [parts (happy-parts)
        r (reconciliation/reconcile (reconcile-opts parts))]
    (is (true? (:ok? r)) (pr-str r))
    (is (= :ok (:reason r)))))

(deftest swapped-distorted-allocations-fail-public-claim
  (testing "individual allocations distorted while total still equals capacity"
    (let [parts (happy-parts)
          ;; A=30, B=20, C=0: total 50 (== capacity) but A was committed 50.
          distorted (->> (:leaves parts)
                         (map (fn [leaf]
                                (case (:claim/id leaf)
                                  "A" (assoc leaf :final-allocation 30)
                                  "B" (assoc leaf :final-allocation 20)
                                  leaf)))
                         (mapv identity))
          r (reconciliation/reconcile (reconcile-opts parts :leaves distorted))]
      (is (false? (:ok? r)))
      (is (= :result-award-mismatch (:reason r)) (pr-str r))
      (is (= "A" (get-in r [:detail :claim/id])))))
  (testing "swapped leaves (A<->B) fail the per-award correspondence"
    (let [parts (happy-parts)
          by-id (into {} (map (juxt :claim/id identity)) (:leaves parts))
          swapped [(assoc (get by-id "A") :claim/id "B")
                   (assoc (get by-id "B") :claim/id "A")
                   (get by-id "C")]
          r (reconciliation/reconcile (reconcile-opts parts :leaves swapped))]
      (is (false? (:ok? r)))
      (is (= :result-award-mismatch (:reason r)) (pr-str r)))))

(deftest entitlement-and-total-reasons
  (testing "fractional allocation (not 0 or full amount) is an entitlement violation"
    (let [parts (happy-parts)
          selected (assoc (:selected-outcome parts)
                          :allocations [{:claim/id "A" :allocated 40}
                                        {:claim/id "B" :allocated 10}
                                        {:claim/id "C" :allocated 0}])
          leaves (kernel/result-leaves (:context parts) selected
                                       (:allocation-context-hash (:result parts)))
          r (reconciliation/reconcile (reconcile-opts parts :selected-outcome selected :leaves leaves))]
      (is (false? (:ok? r)))
      (is (= :result-entitlement-mismatch (:reason r)) (pr-str r))))
  (testing "total-allocated != capacity is :result-total-capacity-mismatch"
    (let [parts (happy-parts)
          r (reconciliation/reconcile (reconcile-opts parts :total 49))]
      (is (= :result-total-capacity-mismatch (:reason r)))))
  (testing "nonzero residual is :result-nonzero-residual"
    (let [parts (happy-parts)
          r (reconciliation/reconcile (reconcile-opts parts :total 50 :residual 1))]
      (is (= :result-nonzero-residual (:reason r))))))

(deftest leaf-set-and-root-reasons
  (testing "missing claimant in leaves is :result-leaf-set-incomplete"
    (let [parts (happy-parts)
          incomplete (vec (remove #(= "C" (:claim/id %)) (:leaves parts)))
          r (reconciliation/reconcile (reconcile-opts parts :leaves incomplete))]
      (is (= :result-leaf-set-incomplete (:reason r)))))
  (testing "duplicate leaf for one claimant is :result-leaf-set-incomplete"
    (let [parts (happy-parts)
          by-id (into {} (map (juxt :claim/id identity)) (:leaves parts))
          dup [(get by-id "A") (get by-id "A") (get by-id "B") (get by-id "C")]
          r (reconciliation/reconcile (reconcile-opts parts :leaves dup))]
      (is (= :result-leaf-set-incomplete (:reason r)))))
  (testing "committed result-root that does not recompute is :result-root-mismatch"
    (let [parts (happy-parts)
          r (reconciliation/reconcile (reconcile-opts parts :committed-root "0x0000000000000000000000000000000000000000000000000000000000000000"))]
      (is (= :result-root-mismatch (:reason r)))))
  (testing "unsupported rounding policy is :result-rounding-rule-mismatch"
    (let [parts (happy-parts)
          r (reconciliation/reconcile (reconcile-opts parts :rounding-policy "bogus.v9"))]
      (is (= :result-rounding-rule-mismatch (:reason r))))))

(deftest kernel-surface-fails-specific-reason
  (testing "an unsupported rounding policy makes the public claim fail end-to-end"
    (let [input (assoc (fixtures/happy-with-committed) "rounding-policy" "bogus.v9")
          result (kernel/run-kernel input)]
      (is (= :rejected (:result/status result)))
      (is (= :result-rounding-rule-mismatch (:rejection/classification result)))
      (let [assertion14 (first (filter #(= :allocation.assertion/result-capacity-reconciles
                                           (:assertion/id %))
                                       (:assertions result)))]
        (is (false? (:assertion/result assertion14)))
        (is (= :result-rounding-rule-mismatch (:assertion/reason assertion14))))))
  (testing "the happy path still passes assertion 14 with the strengthened checks"
    (let [result (fixtures/kernel-result)]
      (is (= :passing (:result/status result)))
      (let [assertion14 (first (filter #(= :allocation.assertion/result-capacity-reconciles
                                           (:assertion/id %))
                                       (:assertions result)))]
        (is (true? (:assertion/result assertion14)))))))
