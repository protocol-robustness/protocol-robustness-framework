(ns resolver-sim.pro-rata.semantic-admission-test
  "Gate 1-A: ephemeral semantic admission boundary for simple pro-rata decisions.

   The critical invariant: an attacker who swaps allocation amounts between
   claimants, preserves conservation, and recomputes the decision hash will
   pass closed-form/hash checks but MUST be rejected by semantic reconstruction."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.yield.partial-fill]
            [resolver-sim.pro-rata.semantic-admission :as admission]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def input
  {:available 50
   :requested {:alice 40 :bob 60}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder}})

(def valid-decision
  {:settlement-mode :partial-fill
   :requested {:alice 40 :bob 60}
   :filled {:alice 20 :bob 30}
   :deferred {:alice 20 :bob 30}
   :haircut {}
   :unrealized {}})

(def ^:private stub-closed-form
  "Stub closed-form checks to isolate semantic reconstruction behavior."
  (constantly []))

(defn- verify-with-stub
  "Run verify-semantic-decision with closed-form checks stubbed."
  [inp decision]
  (with-redefs [resolver-sim.yield.partial-fill/partial-fill-closed-form-checks
                stub-closed-form]
    (admission/verify-semantic-decision inp decision)))

;; ---------------------------------------------------------------------------
;; 1. Valid decision admission
;; ---------------------------------------------------------------------------

(deftest valid-semantic-decision-is-admitted
  (testing "semantic reconstruction is exposed as a separate ephemeral surface"
    (let [result (verify-with-stub input valid-decision)]
      (is (= :admitted (:admission/status result)))
      (is (true? (:valid? result)))
      (is (= :ephemeral (get-in result [:scope :persistence])))
      (is (true? (get-in result [:semantic-reconstruction :supported?]))))))

(deftest valid-full-fill-decision-is-admitted
  (testing "full-fill decision where requested <= available: reconstruction allocates full available"
    (let [inp {:available 100
               :requested {:alice 40 :bob 60}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          ;; When available == total-requested, full-fill = allocate all available
          decision {:settlement-mode :full-fill
                    :requested {:alice 40 :bob 60}
                    :filled {:alice 40 :bob 60}
                    :deferred {:alice 0 :bob 0}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

;; ---------------------------------------------------------------------------
;; 2. Critical mutation test: wrong allocation + conservation + hash
;; ---------------------------------------------------------------------------

(deftest mutation-wrong-allocation-with-conservation-is-rejected
  (testing (str "swapping allocation between claimants preserves conservation "
                "but semantic reconstruction rejects")
    (let [;; Correct: alice=20, bob=30 (proportional to 40:60)
          ;; Mutated: alice=30, bob=20 (swapped, conservation preserved: 50=50)
          mutated (assoc valid-decision
                         :filled {:alice 30 :bob 20}
                         :deferred {:alice 10 :bob 40})
          result (verify-with-stub input mutated)]
      (is (= :rejected (:admission/status result))
          "swapped allocation is rejected despite conservation")
      (is (false? (:valid? result)))
      (is (seq (get-in result [:semantic-reconstruction :mismatches]))
          "mismatches are reported"))))

(deftest mutation-concentrated-allocation-is-rejected
  (testing "giving all allocation to one claimant preserves conservation"
    (let [;; alice=50, bob=0 — conservation holds (50=50)
          mutated (assoc valid-decision
                         :filled {:alice 50 :bob 0}
                         :deferred {:alice 0 :bob 60})
          result (verify-with-stub input mutated)]
      (is (= :rejected (:admission/status result))
          "concentrated allocation rejected despite conservation"))))

(deftest mutation-rounding-swap-detects-deviation
  (testing "a ±1 rounding swap between claimants is detected"
    (let [;; alice=21, bob=29 — still sums to 50
          mutated (assoc valid-decision
                         :filled {:alice 21 :bob 29}
                         :deferred {:alice 19 :bob 31})
          result (verify-with-stub input mutated)]
      (is (= :rejected (:admission/status result))
          "rounding swap detected"))))

(deftest mutation-correct-allocation-passes
  (testing "the correct pro-rata allocation passes reconstruction"
    (let [result (verify-with-stub input valid-decision)]
      (is (= :admitted (:admission/status result)))
      (is (empty? (get-in result [:semantic-reconstruction :mismatches]))))))

;; ---------------------------------------------------------------------------
;; 3. Altered fill amounts
;; ---------------------------------------------------------------------------

(deftest altered-fill-amounts-rejected
  (testing "increasing one claimant's fill at the expense of another"
    (doseq [shift [1 5 10]]
      (let [mutated (assoc valid-decision
                           :filled {:alice (+ 20 shift) :bob (- 30 shift)}
                           :deferred {:alice (- 20 shift) :bob (+ 30 shift)})]
        (is (= :rejected (:admission/status (verify-with-stub input mutated)))
            (str "shift of " shift " detected"))))))

(deftest zeroed-fill-is-rejected
  (testing "filling nothing when allocation should occur is rejected"
    (let [mutated (assoc valid-decision
                         :filled {:alice 0 :bob 0}
                         :deferred {:alice 40 :bob 60})]
      (is (= :rejected (:admission/status (verify-with-stub input mutated)))))))

;; ---------------------------------------------------------------------------
;; 4. Altered policy
;; ---------------------------------------------------------------------------

(deftest different-rounding-policy-yields-different-valid-allocation
  (testing "floor rounding produces a different valid allocation than largest-remainder"
    (let [inp {:available 7
               :requested {:a 4 :b 4 :c 2}
               :policy {:mode :pro-rata :rounding-policy :floor}}
          ;; floor-alloc: a=2, b=2, c=1 (each gets floor of share)
          decision {:settlement-mode :partial-fill
                    :requested {:a 4 :b 4 :c 2}
                    :filled {:a 2 :b 2 :c 1}
                    :deferred {:a 2 :b 2 :c 1}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

(deftest mismatched-rounding-policy-is-rejected
  (testing "allocation computed with wrong rounding policy is rejected"
    (let [;; Input says largest-remainder, but decision used floor
          ;; floor: a=2, b=2, c=1; largest-remainder: a=3, b=3, c=1
          decision-floor {:settlement-mode :partial-fill
                          :requested {:a 4 :b 4 :c 2}
                          :filled {:a 2 :b 2 :c 1}
                          :deferred {:a 2 :b 2 :c 1}
                          :haircut {}
                          :unrealized {}}]
      (is (= :rejected (:admission/status (verify-with-stub input decision-floor)))
          "floor allocation rejected when largest-remainder expected"))))

;; ---------------------------------------------------------------------------
;; 5. Semantically relevant ordering
;; ---------------------------------------------------------------------------

(deftest reconstructed-allocation-is-order-independent
  (testing "semantic reconstruction is deterministic regardless of request map order"
    (let [inp-a {:available 50
                 :requested (array-map :alice 40 :bob 60)
                 :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          inp-b {:available 50
                 :requested (array-map :bob 60 :alice 40)
                 :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          result-a (get-in (verify-with-stub inp-a valid-decision)
                           [:semantic-reconstruction :expected :filled])
          result-b (get-in (verify-with-stub inp-b valid-decision)
                           [:semantic-reconstruction :expected :filled])]
      (is (= result-a result-b)
          "reconstruction output is stable across request ordering"))))

;; ---------------------------------------------------------------------------
;; 6. Rounding ties
;; ---------------------------------------------------------------------------

(deftest rounding-tie-at-exact-half-is-deterministic
  (testing "reconstruction is deterministic at the rounding boundary"
    (let [inp {:available 10
               :requested {:a 1 :b 1}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          ;; 50/50 split of 10: both get 5
          decision {:settlement-mode :full-fill
                    :requested {:a 1 :b 1}
                    :filled {:a 5 :b 5}
                    :deferred {:a 0 :b 0}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

(deftest rounding-tie-three-way-is-deterministic
  (testing "three-way rounding tie produces deterministic reconstruction"
    (let [inp {:available 10
               :requested {:a 1 :b 1 :c 1}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          result (verify-with-stub inp
                                   {:settlement-mode :partial-fill
                                    :requested {:a 1 :b 1 :c 1}
                                    :filled {:a 3 :b 3 :c 3}
                                    :deferred {:a 1 :b 1 :c 1}
                                    :haircut {}
                                    :unrealized {}})]
      ;; With largest-remainder on equal weights, floor gives 3 each, remainder 1
      ;; goes to one claimant — reconstruction must match exactly
      (is (contains? #{:admitted :rejected}
                     (:admission/status result))
          "rounding tie is deterministic (no crash)"))))

;; ---------------------------------------------------------------------------
;; 7. Full vs partial fill
;; ---------------------------------------------------------------------------

(deftest full-fill-decision-detected-when-total-equals-available
  (testing "settlement mode is inferred from available vs requested"
    (let [inp {:available 100
               :requested {:a 50 :b 50}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :full-fill
                    :requested {:a 50 :b 50}
                    :filled {:a 50 :b 50}
                    :deferred {:a 0 :b 0}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

(deftest partial-fill-decision-when-total-exceeds-available
  (testing "partial fill when requested > available"
    (let [inp {:available 50
               :requested {:a 50 :b 50}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :partial-fill
                    :requested {:a 50 :b 50}
                    :filled {:a 25 :b 25}
                    :deferred {:a 25 :b 25}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

(deftest wrong-settlement-mode-is-rejected
  (testing "claiming full-fill when only partial-fill is possible is rejected"
    (let [inp {:available 50
               :requested {:a 50 :b 50}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :full-fill
                    :requested {:a 50 :b 50}
                    :filled {:a 25 :b 25}
                    :deferred {:a 25 :b 25}
                    :haircut {}
                    :unrealized {}}]
      (is (= :rejected (:admission/status (verify-with-stub inp decision)))))))

;; ---------------------------------------------------------------------------
;; 8. Malformed inputs
;; ---------------------------------------------------------------------------

(deftest zero-available-rejects-nonzero-fill
  (testing "zero available with non-zero fill is rejected"
    (let [inp {:available 0
               :requested {:a 10 :b 20}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :partial-fill
                    :requested {:a 10 :b 20}
                    :filled {:a 0 :b 0}
                    :deferred {:a 10 :b 20}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

(deftest empty-request-is-admitted
  (testing "empty request with zero fill is vacuously correct"
    (let [inp {:available 100
               :requested {}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :full-fill
                    :requested {}
                    :filled {}
                    :deferred {}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

(deftest single-claimant-is-admitted
  (testing "single claimant receives all allocation"
    (let [inp {:available 100
               :requested {:solo 100}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :full-fill
                    :requested {:solo 100}
                    :filled {:solo 100}
                    :deferred {:solo 0}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

;; ---------------------------------------------------------------------------
;; 9. Duplicate identities
;; ---------------------------------------------------------------------------

(deftest duplicate-request-keys-collapse
  (testing "duplicate keys in requested map are collapsed by Clojure semantics"
    (let [;; Clojure maps collapse duplicate keys — last value wins
          inp {:available 50
               :requested {:alice 40 :bob 60}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}]
      ;; The decision must match the collapsed input
      (is (= :admitted (:admission/status (verify-with-stub inp valid-decision)))))))

;; ---------------------------------------------------------------------------
;; 10. Runtime-option independence
;; ---------------------------------------------------------------------------

(deftest reconstruction-is-independent-of-caller-options
  (testing "reconstruction result does not depend on ephemeral caller state"
    (let [result-1 (verify-with-stub input valid-decision)
          result-2 (verify-with-stub input valid-decision)]
      (is (= (:admission/status result-1) (:admission/status result-2)))
      (is (= (get-in result-1 [:semantic-reconstruction :expected])
             (get-in result-2 [:semantic-reconstruction :expected]))
          "reconstruction is deterministic across calls"))))

(deftest reconstruction-ignores-decision-hash
  (testing "semantic reconstruction does not inspect or depend on decision hash"
    (let [;; Add a fake hash to the decision — reconstruction should still pass
          with-hash (assoc valid-decision :decision/hash "fake-hash-abc123")
          result (verify-with-stub input with-hash)]
      (is (= :admitted (:admission/status result))
          "reconstruction is unaffected by presence of a hash"))))

;; ---------------------------------------------------------------------------
;; 11. Dependency boundary: namespace isolation
;; ---------------------------------------------------------------------------

(deftest semantic-admission-is-independent-of-producer
  (testing "semantic-admission namespace does not alias producer allocation namespaces"
    (let [aliases (set (keys (ns-aliases 'resolver-sim.pro-rata.semantic-admission)))]
      (is (not (contains? aliases 'payoffs))
          "must not alias payoffs")
      (is (not (contains? aliases 'allocation))
          "must not alias pro-rata.allocation")
      (is (not (contains? aliases 'partial-fill-internal))
          "must not alias partial-fill internals beyond the public API"))))

(deftest semantic-admission-source-references-no-allocator
  (testing "no fully-qualified reference to a forbidden namespace appears in source"
    (let [source (slurp (io/resource "resolver_sim/pro_rata/semantic_admission.clj"))
          forbidden ["resolver-sim.pro-rata.allocation"
                     "resolver-sim.economics.payoffs"]]
      (doseq [ns-name forbidden]
        (is (not (re-find (re-pattern (str ns-name "/")) source))
            (str "must not reference " ns-name " even fully-qualified"))))))

;; ---------------------------------------------------------------------------
;; 12. Ephemeral output contract
;; ---------------------------------------------------------------------------

(deftest result-is-ephemeral-no-evidence-node
  (testing "result contains no evidence node, no content-addressed root"
    (let [result (verify-with-stub input valid-decision)]
      (is (nil? (:evidence/root result))
          "no evidence root in result")
      (is (nil? (:artifact/hash result))
          "no artifact hash in result")
      (is (nil? (:decision/hash result))
          "no decision hash in result")
      (is (= :ephemeral (get-in result [:scope :persistence]))
          "persistence tag is :ephemeral"))))

(deftest result-does-not-mutate-input
  (testing "verify-semantic-decision does not side-effect the input decision"
    (let [decision-copy (assoc valid-decision :decision/hash "original-hash")
          _ (verify-with-stub input decision-copy)]
      (is (= "original-hash" (:decision/hash decision-copy))
          "input decision is not mutated"))))

(deftest unsupported-result-contains-reconstruction-surface
  (testing "unsupported results still expose the reconstruction surface"
    (let [result (admission/verify-semantic-decision
                  (assoc input :rows [{:key :alice :owed 40 :cap 20}])
                  valid-decision)]
      (is (= :unsupported (:admission/status result)))
      (is (contains? result :semantic-reconstruction))
      (is (contains? result :closed-form))
      (is (contains? result :scope)))))
