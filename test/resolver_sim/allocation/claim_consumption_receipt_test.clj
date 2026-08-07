(ns resolver-sim.allocation.claim-consumption-receipt-test
  "Tests for claim-consumption-receipt.v1 — the terminal, content-addressed
   receipt for claim consumption in the probabilistic-allocation lifecycle
   (:claim-consumption-started is the irreversible cutpoint)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.claim-consumption-receipt :as cc]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]))

(defn- h
  [pattern]
  (str "sha256:" (apply str (take 64 (cycle pattern)))))

(def ^:private result-root (h "a1"))
(def ^:private claimable-hash (h "b2"))
(def ^:private evidence-hash (h "c3"))

(defn- valid-receipt [& {:keys [status amount terminal-evidence]
                         :or {status :consumed amount 500}}]
  (cc/build-claim-consumption-receipt
   (cond-> {:claim-consumption/id :claim-consumption/test-001
            :claim/id :claim/bounty-1
            :allocation/result-root result-root
            :claim/amount amount
            :claim-consumption/consumed-claimable-hash claimable-hash
            :claim-consumption/status status
            :claim-consumption/consumption-key "ck-001"}
     terminal-evidence (assoc :claim-consumption/terminal-evidence-hash
                              terminal-evidence))))

(deftest builds-consumed-receipt
  (let [r (valid-receipt)]
    (is (= "claim-consumption-receipt.v1" (:schema-version r)))
    (is (= :claim/bounty-1 (:claim/id r)))
    (is (= 500 (:claim/amount r)))
    (is (= :consumed (:claim-consumption/status r)))
    (is (some? (:claim-consumption/hash r)))
    (is (cc/claim-consumption-receipt-valid? r))
    (is (:valid? (cc/validate-claim-consumption-receipt r)))))

(deftest hash-is-content-addressed-and-deterministic
  (let [a (valid-receipt)
        b (valid-receipt)]
    (is (= (:claim-consumption/hash a) (:claim-consumption/hash b))))
  (let [a (valid-receipt)
        b (valid-receipt :amount 501)]
    (is (not= (:claim-consumption/hash a) (:claim-consumption/hash b))
        "changing the consumed amount must change the receipt hash")))

(deftest status-rules-enforced
  (testing ":failed-after-consumption requires terminal evidence"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"terminal-evidence"
                          (valid-receipt :status :failed-after-consumption)))
    (is (cc/claim-consumption-receipt-valid?
         (valid-receipt :status :failed-after-consumption
                        :terminal-evidence evidence-hash))))
  (testing ":consumed may carry terminal evidence (status rule only requires it on failure)"
    (is (cc/claim-consumption-receipt-valid?
         (valid-receipt :terminal-evidence evidence-hash)))))

(deftest fails-closed-on-bad-inputs
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"claim/amount"
                        (valid-receipt :amount -1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"claim/amount"
                        (valid-receipt :amount 1.5))
      "fractional amounts are never silently truncated")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"result-root"
                        (cc/build-claim-consumption-receipt
                         (dissoc (valid-receipt) :allocation/result-root))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"consumed-claimable-hash"
                        (cc/build-claim-consumption-receipt
                         (assoc (valid-receipt)
                                :claim-consumption/consumed-claimable-hash "bogus"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"status"
                        (cc/build-claim-consumption-receipt
                         (assoc (valid-receipt)
                                :claim-consumption/status :bogus))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"consumption-key"
                        (cc/build-claim-consumption-receipt
                         (dissoc (valid-receipt) :claim-consumption/consumption-key)))))

(deftest tampering-is-detected
  (let [r (valid-receipt)
        tampered (assoc r :claim/amount 999)]
    (is (not (:valid? (cc/validate-claim-consumption-receipt tampered)))
        "the standalone validator recomputes the hash and rejects tampering")
    (is (cc/claim-consumption-receipt-valid? tampered)
        "the quick structural check does not recompute the hash — it is
         structural only, like the FA receipt-valid? convention")))

(deftest conflict-key-binds-target-claim-and-lifecycle
  (let [k (cc/claim-consumption-conflict-key
           "alloc/9" :claim/bounty-1 cfa/probabilistic-allocation-window)]
    (is (= "alloc/9" (:target/id k)))
    (is (= :claim/bounty-1 (:claim/id k)))
    (is (= :prf.lifecycle-window/probabilistic-allocation
           (:lifecycle/profile-id k)))
    (is (= 1 (:lifecycle/profile-version k)))))
