(ns resolver-sim.allocation.roots-test
  "Tests for root and Merkle construction."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.kernel :as kernel]
            [resolver-sim.allocation.roots :as roots]
            [resolver-sim.allocation.test-fixtures :as fixtures]
            [resolver-sim.hash.canonical :as hc]))

(defn- ctx []
  (context/build-context (fixtures/happy-input)))

(deftest roots-are-64-hex-domain-separated
  (let [c (ctx)]
    (doseq [[label root] [[:claimant (roots/claimant-set-root c)]
                          [:outcome (roots/outcome-set-root c)]
                          [:rates (roots/proposed-rates-root c)]]]
      (is (= 64 (count root)) (str label))
      (is (re-matches #"[0-9a-f]{64}" root) (str label)))))

(deftest roots-are-stable-across-identical-contexts
  (let [a (ctx) b (ctx)]
    (is (= (roots/claimant-set-root a) (roots/claimant-set-root b)))
    (is (= (roots/outcome-set-root a) (roots/outcome-set-root b)))
    (is (= (roots/proposed-rates-root a) (roots/proposed-rates-root b)))))

(deftest roots-are-domain-separated
  (let [c (ctx)
        claim-root (roots/claimant-set-root c)
        outcome-root (roots/outcome-set-root c)]
    (is (not= claim-root outcome-root))
    (is (not= claim-root (hc/domain-hash :outcome-set (:claimants c))))))

(deftest merkle-root-single-leaf
  (let [leaf (roots/result-leaf {:context-hash "0x00"
                                 :claim/id "A" :beneficiary "owner-A"
                                 :input-amount 50 :input-weight 50
                                 :final-allocation 50
                                 :selected-outcome-id "O1"
                                 :result-status "allocated"})
        root (roots/result-merkle-root [leaf])]
    (is (= 64 (count root)))
    (is (re-matches #"[0-9a-f]{64}" root))))

(deftest merkle-root-two-leaves
  (let [leaf-a (roots/result-leaf {:context-hash "0x00" :claim/id "A"
                                   :beneficiary "owner-A" :input-amount 50
                                   :input-weight 50 :final-allocation 50
                                   :selected-outcome-id "O1" :result-status "allocated"})
        leaf-b (roots/result-leaf {:context-hash "0x00" :claim/id "B"
                                   :beneficiary "owner-B" :input-amount 30
                                   :input-weight 30 :final-allocation 0
                                   :selected-outcome-id "O1" :result-status "not-allocated"})
        root (roots/result-merkle-root [leaf-a leaf-b])]
    (is (= 64 (count root)))))

(deftest merkle-root-odd-leaf-duplicates-final-node
  ;; three distinct leaves must differ from two leaves; odd level duplicates
  (let [leaf-a (roots/result-leaf {:context-hash "0x00" :claim/id "A"
                                   :beneficiary "owner-A" :input-amount 50
                                   :input-weight 50 :final-allocation 50
                                   :selected-outcome-id "O1" :result-status "allocated"})
        leaf-b (roots/result-leaf {:context-hash "0x00" :claim/id "B"
                                   :beneficiary "owner-B" :input-amount 30
                                   :input-weight 30 :final-allocation 0
                                   :selected-outcome-id "O1" :result-status "not-allocated"})
        leaf-c (roots/result-leaf {:context-hash "0x00" :claim/id "C"
                                   :beneficiary "owner-C" :input-amount 20
                                   :input-weight 20 :final-allocation 0
                                   :selected-outcome-id "O1" :result-status "not-allocated"})]
    (is (= (roots/result-merkle-root [leaf-a leaf-b leaf-c])
           (roots/result-merkle-root [leaf-a leaf-b leaf-c])))
    (is (not= (roots/result-merkle-root [leaf-a leaf-b])
              (roots/result-merkle-root [leaf-a leaf-b leaf-c])))))

(deftest merkle-root-order-matters
  (let [leaf-a (roots/result-leaf {:context-hash "0x00" :claim/id "A"
                                   :beneficiary "owner-A" :input-amount 50
                                   :input-weight 50 :final-allocation 50
                                   :selected-outcome-id "O1" :result-status "allocated"})
        leaf-b (roots/result-leaf {:context-hash "0x00" :claim/id "B"
                                   :beneficiary "owner-B" :input-amount 30
                                   :input-weight 30 :final-allocation 0
                                   :selected-outcome-id "O1" :result-status "not-allocated"})]
    (is (not= (roots/result-merkle-root [leaf-a leaf-b])
              (roots/result-merkle-root [leaf-b leaf-a])))))

(deftest result-leaf-commits-contract-fields
  (let [leaf (roots/result-leaf {:context-hash "0xab" :claim/id "A"
                                 :beneficiary "owner-A" :input-amount 50
                                 :input-weight 50 :final-allocation 50
                                 :selected-outcome-id "O1" :result-status "allocated"})]
    (doseq [k [:round/context-hash :claim/id :beneficiary :input-amount
               :input-weight :final-allocation :selected-outcome-id :result-status]]
      (is (contains? leaf k) (str k)))))

(deftest certificate-assertions-digest-commitment
  (let [digest (roots/certificate-assertions-digest
                {:allocation-context-hash "0x00"
                 :assertions [{:assertion/id :allocation.assertion/x :assertion/result true}]
                 :selected-outcome-id "O1" :selected-outcome-index 0
                 :result-root "0x00" :total-allocated 50 :residual-capacity 0
                 :allocation-kernel-version "allocation-kernel.v1"})]
    (is (= 64 (count digest)))
    (let [other (roots/certificate-assertions-digest
                 {:allocation-context-hash "0x00"
                  :assertions [{:assertion/id :allocation.assertion/x :assertion/result true}]
                  :selected-outcome-id "O2" :selected-outcome-index 1
                  :result-root "0x00" :total-allocated 50 :residual-capacity 0
                  :allocation-kernel-version "allocation-kernel.v1"})]
      (is (not= digest other)))))

(deftest certificate-assertions-digest-v2-commits-lifecycle
  (let [base {:allocation-context-hash "0x00"
              :assertions [{:assertion/id :allocation.assertion/x :assertion/result true}]
              :selected-outcome-id "O1" :selected-outcome-index 0
              :result-root "0x00" :total-allocated 50 :residual-capacity 0
              :allocation-kernel-version "allocation-kernel.v1"}
        lifecycle {:round-state "allocation-committed"
                   :derived-state "allocation-committed"
                   :lifecycle-profile-id "prf.lifecycle-window/probabilistic-allocation"
                   :lifecycle-profile-version 1
                   :cancellation-window-schema "cancellation-window.v1"
                   :cancellation-window "open"
                   :cancellation-possible true
                   :cancellation-blocking-reasons []
                   :lifecycle-assertion-status "passing"
                   :lifecycle-assurance "independent-replay"}
        digest (roots/certificate-assertions-digest-v2 (merge base lifecycle))]
    (is (= 64 (count digest)))
    (testing "a change in the committed round-state changes the digest"
      (is (not= digest (roots/certificate-assertions-digest-v2
                        (merge base lifecycle
                               {:round-state "randomness-requested"
                                :derived-state "randomness-requested"
                                :cancellation-window "closed"
                                :cancellation-possible false
                                :cancellation-blocking-reasons
                                ["authoritative-randomness-requested"]})))))
    (testing "a change in blocking reasons changes the digest"
      (is (not= digest (roots/certificate-assertions-digest-v2
                        (merge base lifecycle
                               {:cancellation-blocking-reasons ["unknown-target-state"]})))))
    (testing "v2 is distinct from v1 under the same values"
      (is (not= digest (roots/certificate-assertions-digest
                        (merge base lifecycle)))))))

(deftest result-leaves-order-is-canonical-claimant-order
  (let [c (ctx)
        selected (first (:outcomes c))
        leaves (kernel/result-leaves c selected "0x00")
        ids (mapv :claim/id leaves)]
    (is (= ["A" "B" "C"] ids))))
