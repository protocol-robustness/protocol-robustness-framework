(ns resolver-sim.economics.bounty-payable-backing-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.bounty-payable-backing :as bpb]))

(deftest build-bounty-payable-backing-valid
  (let [b (bpb/build-bounty-payable-backing
           {:payable-root "sha256:payable1"
            :payable-id "payable-award-1"
            :distribution-root "sha256:dist1"
            :amount 100
            :source-allocations {:sew.allocation/insurance 50
                                 :sew.allocation/protocol 50}})]
    (is (= "bounty-payable-backing.v1" (:schema-version b)))
    (is (= "backing-payable-award-1" (:backing/id b)))
    (is (= 100 (:backing/amount b)))
    (is (= :committed (:backing/lifecycle b)))
    (is (string? (:backing/hash b)))))

(deftest build-bounty-payable-backing-deterministic
  (let [args {:payable-root "sha256:payable1"
              :payable-id "payable-award-1"
              :distribution-root "sha256:dist1"
              :amount 100
              :source-allocations {:a 50 :b 50}}
        b1 (bpb/build-bounty-payable-backing args)
        b2 (bpb/build-bounty-payable-backing args)]
    (is (= (:backing/hash b1) (:backing/hash b2)))))

(deftest build-bounty-payable-backing-no-payable-root-rejected
  (is (thrown? Exception
               (bpb/build-bounty-payable-backing
                {:payable-id "p1"
                 :distribution-root "sha256:d1"
                 :amount 50}))))

(deftest build-bounty-payable-backing-negative-amount-rejected
  (is (thrown? Exception
               (bpb/build-bounty-payable-backing
                {:payable-root "sha256:p1"
                 :payable-id "p1"
                 :distribution-root "sha256:d1"
                 :amount -1}))))

(deftest validate-bounty-payable-backing-valid
  (let [b (bpb/build-bounty-payable-backing
           {:payable-root "sha256:p1"
            :payable-id "p1"
            :distribution-root "sha256:d1"
            :amount 100})
        v (bpb/validate-bounty-payable-backing b)]
    (is (:valid? v))))

(deftest verify-bounty-payable-backing-untampered
  (let [b (bpb/build-bounty-payable-backing
           {:payable-root "sha256:p1"
            :payable-id "p1"
            :distribution-root "sha256:d1"
            :amount 100})
        v (bpb/verify-bounty-payable-backing b)]
    (is (:valid? v))))

(deftest verify-bounty-payable-backing-tampered
  (let [b (bpb/build-bounty-payable-backing
           {:payable-root "sha256:p1"
            :payable-id "p1"
            :distribution-root "sha256:d1"
            :amount 100})
        tampered (assoc b :backing/amount 999)
        v (bpb/verify-bounty-payable-backing tampered)]
    (is (not (:valid? v)))))

(deftest backing-amount-reconciliation
  (let [b (bpb/build-bounty-payable-backing
           {:payable-root "sha256:p1"
            :payable-id "p1"
            :distribution-root "sha256:d1"
            :amount 100
            :source-allocations {:insurance 50 :protocol 50}})]
    (is (= 100 (bpb/backing-amount-reconciliation b)))))
