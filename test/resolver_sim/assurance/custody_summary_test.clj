(ns resolver-sim.assurance.custody-summary-test
  "held-custody-summary file-artifact tests: roundtrip, custody dimensions,
   token balances, attribution posture, completeness, closed-form failure
   counts, and tamper detection."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.custody :as c]))

(def adj-1
  {:held-adjustment/id "held-adjustment-1"
   :held/direction :in :token :USDC :amount 1000
   :held/before 0 :held/after 1000
   :held/account :escrow-principal :owner/address "0xalice"
   :held/workflow-id 1 :held/reason :create-escrow
   :held/position-id [:held/position :USDC :escrow-principal 1]})

(def adj-2
  {:held-adjustment/id "held-adjustment-2"
   :held/direction :out :token :USDC :amount 400
   :held/before 1000 :held/after 600
   :held/account :escrow-principal :owner/address "0xalice"
   :held/workflow-id 1 :held/reason :release
   :held/position-id [:held/position :USDC :escrow-principal 1]})

(defn- build-summary []
  (let [adjustments [adj-1 adj-2]
        artifacts (mapv c/build-held-custody-artifact adjustments)
        idx (c/replay-held-adjustment-state adjustments)]
    (c/build-held-custody-summary
     {:adjustments adjustments
      :artifacts artifacts
      :index (:held-ledger/index idx)
      :total-held (:total-held idx)
      :completeness {:held-adjustments/complete? true}})))

(deftest held-custody-summary-roundtrip
  (let [s (build-summary)]
    (is (= "held-custody-summary.v1" (:schema-version s)))
    (is (= :held-custody-summary (:artifact/kind s)))
    (is (= 2 (:adjustment-count s)))
    (is (true? (c/valid-held-custody-summary? s)))))

(deftest held-custody-summary-custody-dimensions
  (let [s (build-summary)]
    (is (= {:USDC 2} (:by-token s)))
    (is (= {:in 1 :out 1} (:by-direction s)))
    (is (= {:escrow-principal 2} (:by-account s)))
    (is (= {"0xalice" 2} (:by-owner s)))
    (is (= {1 2} (:by-workflow s)))
    (is (= {:create-escrow 1 :release 1} (:by-reason s)))
    (is (= {:USDC 1400} (:amount-by-token s)))
    (is (= {:in 1000 :out 400} (:amount-by-direction s)))))

(deftest held-custody-summary-token-balances
  (let [s (build-summary)]
    (is (= {:USDC {:opening 0 :in 1000 :out 400 :closing 600}}
           (:token-balances s)))))

(deftest held-custody-summary-completeness-and-reconciliation
  (let [s (build-summary)]
    (is (true? (get-in s [:completeness :held-adjustments/complete?])))
    (is (= {:USDC 600} (:replayed-closing (:completeness s))))
    (is (= {:USDC 600} (:observed-closing (:completeness s))))
    (is (true? (get-in s [:completeness :reconciliation-valid?])))))

(deftest held-custody-summary-attribution-posture
  (testing "synthetic v3 artifacts without parameter provenance classify unattributed"
    (let [s (build-summary)]
      (is (map? (:attribution-counts s)))
      (is (= 2 (:unattributed-v3 (:attribution-counts s))))
      (is (zero? (get (:attribution-counts s) :attributed-v3 0))))))

(deftest held-custody-summary-closed-form-failure-counts
  (let [s (build-summary)
        counts (:closed-form-failure-counts s)]
    (is (= 0 (get counts :held-custody/hash-integrity)))
    (is (= 0 (get counts :held-custody/local-delta)))
    (is (= 0 (get counts :held-custody/non-negative-after)))))

(deftest held-custody-summary-chain-head
  (let [s (build-summary)]
    (is (string? (:artifact-chain-head s)))
    (is (re-find #"^sha256:" (:artifact-chain-head s)))))

(deftest held-custody-summary-tamper-detected
  (let [s (build-summary)]
    (is (false? (c/valid-held-custody-summary?
                 (assoc s :adjustment-count 99))))
    (is (false? (c/valid-held-custody-summary?
                 (assoc s :artifact/hash "sha256:forged"))))
    (is (false? (c/valid-held-custody-summary?
                 (assoc s :schema-version "wrong.v9"))))
    (is (false? (c/valid-held-custody-summary?
                 (dissoc s :artifact/preimage))))))

(deftest held-custody-summary-reconciliation-mismatch
  (testing "an observed closing that disagrees with replay is flagged"
    (let [adjustments [adj-1 adj-2]
          artifacts (mapv c/build-held-custody-artifact adjustments)
          s (c/build-held-custody-summary
             {:adjustments adjustments
              :artifacts artifacts
              :index {}
              :total-held {:USDC 999}
              :completeness {:held-adjustments/complete? false}})]
      (is (false? (get-in s [:completeness :reconciliation-valid?])))
      (is (= {:USDC 999} (:observed-closing (:completeness s))))
      (is (true? (c/valid-held-custody-summary? s))))))
