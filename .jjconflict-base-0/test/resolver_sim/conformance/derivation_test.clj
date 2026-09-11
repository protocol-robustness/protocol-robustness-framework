(ns resolver-sim.conformance.derivation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.conformance.derivation :as der]))

(defn- mk-receipt [boundary input output statuses]
  (der/derivation-receipt
   {:boundary/id boundary
    :input/root input
    :output/root output
    :fixture-contract/id :trace-fixture.v2
    :transformation/id (case boundary
                         :export :cdrs-export
                         :sync :byte-preserving-copy
                         :replay :trace-replay
                         :unknown)
    :validation-results (mapv (fn [s] {:validation/status s
                                       :validation/layer :schema
                                       :validation/code :ok})
                              statuses)}))

(deftest receipt-status-derived-from-validations
  (is (= :pass (:status (mk-receipt :export "a" "b" [:pass :pass]))))
  (is (= :fail (:status (mk-receipt :export "a" "b" [:pass :rejected])))))

(deftest receipt-preserves-layers
  (let [r (mk-receipt :export "a" "b" [:rejected])]
    (is (= :rejected (get-in r [:validation-results 0 :validation/status])))
    (is (= :schema (get-in r [:validation-results 0 :validation/layer])))))

(deftest chain-links-output-to-input
  (let [export (mk-receipt :export "sim-root" "gen-fixture" [:pass])
        sync (mk-receipt :sync "gen-fixture" "solidity-fixture" [:pass])
        replay (mk-receipt :replay "solidity-fixture" "receipt-root" [:pass])
        chain (der/derivation-chain [export sync replay])]
    (is (:links-ok? chain))
    (is (= :pass (:status chain)))
    (is (string? (:root chain)))
    (is (= 3 (count (:chain chain))))
    (is (empty? (:violations chain)))))

(deftest chain-rejects-broken-link
  (let [export (mk-receipt :export "sim-root" "gen-a" [:pass])
        sync (mk-receipt :sync "gen-b" "solidity-fixture" [:pass])
        chain (der/derivation-chain [export sync])]
    (is (not (:links-ok? chain)))
    (is (= :fail (:status chain)))
    (is (some #(= :violation/chain-link-mismatch (:violation/id %))
              (:violations chain)))))

(deftest chain-rejects-failed-boundary
  (let [export (mk-receipt :export "sim-root" "gen-fixture" [:pass])
        sync (mk-receipt :sync "gen-fixture" "solidity-fixture" [:rejected])
        chain (der/derivation-chain [export sync])]
    (is (:links-ok? chain)) ; links still connect
    (is (= :fail (:status chain)))
    (is (some #(= :violation/chain-boundary-failed (:violation/id %))
              (:violations chain)))))

(deftest chain-root-deterministic
  (let [r1 (mk-receipt :export "a" "b" [:pass])
        r2 (mk-receipt :export "a" "b" [:pass])]
    (is (= (:root (der/derivation-chain [r1]))
           (:root (der/derivation-chain [r2]))))))
