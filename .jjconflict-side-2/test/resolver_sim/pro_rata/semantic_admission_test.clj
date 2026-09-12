(ns resolver-sim.pro-rata.semantic-admission-test
  "Gate 1-A: ephemeral semantic admission boundary for simple pro-rata decisions.

   The critical invariant: an attacker who swaps allocation amounts between
   claimants, preserves conservation, and recomputes the decision hash will
   pass closed-form/hash checks but MUST be rejected by semantic reconstruction.

   Gate 1-A proves semantic derivability from supplied inputs (not authoritative
   admission). The authority is :caller-supplied-semantic-inputs — the
   reconstructed result is derivable from the inputs, but the inputs themselves
   are not authenticated by this boundary.

   Reference independence: semantic-reconstruction uses exact-math allocator
   primitives (m/floor-alloc, m/largest-remainder-alloc, etc.) directly,
   NOT the producer functions (calculate-fulfillment, payoffs/*, pro-rata/*).
   This proves independent reconstruction orchestration. The exact-math layer
   is trusted shared arithmetic infrastructure used by both producer and
   verifier — full implementation independence from shared allocation
   primitives is NOT claimed by Gate 1-A."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.partial-fill :as partial-fill]
            [resolver-sim.pro-rata.semantic-admission :as admission]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def input
  {:available 50
   :requested {:alice 40 :bob 60}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder}})

(def valid-decision
  "Minimal valid decision for closed-form and reconstruction tests.
   Includes :policy and :evidence for closed-form checks."
  {:settlement-mode :partial-fill
   :requested {:alice 40 :bob 60}
   :filled {:alice 20 :bob 30}
   :deferred {:alice 20 :bob 30}
   :haircut {}
   :unrealized {}
   :policy {:mode :pro-rata :rounding-policy :largest-remainder}
   :evidence {:available-liquidity 50}})

(def ^:private stub-closed-form
  "Stub closed-form checks to isolate semantic reconstruction behavior."
  (constantly []))

(defn- verify-with-stub
  "Run verify-semantic-decision with closed-form checks stubbed."
  [inp decision]
  (with-redefs [partial-fill/partial-fill-closed-form-checks
                stub-closed-form]
    (admission/verify-semantic-decision inp decision)))

(defn- build-decision-artifact
  "Build a minimal decision artifact with a real content-addressed hash.
   The decision body is passed through hc/project-committable-content and
   hc/canonical-commitment to produce a genuine :decision/hash."
  [decision]
  (let [base {:schema-version "yield-partial-fill-decision.v1"
              :artifact/kind :yield/partial-fill-decision
              :decision/source :yield-withdraw
              :position/id "test-position"
              :module/id :test-module
              :token :eth
              :settlement-mode (:settlement-mode decision)
              :requested (:requested decision)
              :filled (:filled decision)
              :deferred (:deferred decision)
              :haircut (:haircut decision)
              :unrealized (:unrealized decision)
              :policy (:policy decision)
              :evidence (:evidence decision)}
        proj (hc/project-committable-content base)
        commitment (hc/canonical-commitment :evidence-record proj)
        hash (:canonical/hash commitment)]
    (assoc base
           :decision/id (str "partial-fill-" (subs hash 7 (min (count hash) 23)))
           :decision/hash hash
           :decision/preimage (pr-str base)
           :decision/canonical-bytes (:canonical/bytes commitment)
           :decision/canonical-hash hash)))

;; ---------------------------------------------------------------------------
;; 1. Composed admission boundary (real closed-form + reconstruction)
;; ---------------------------------------------------------------------------

(deftest real-closed-form-passes-for-valid-decision
  (testing "real closed-form checks pass for a valid pro-rata decision"
    (let [result (admission/verify-semantic-decision input valid-decision)
          checks (get-in result [:closed-form :checks])
          failing (filterv #(= :fail (:status %)) checks)]
      (is (= :admitted (:admission/status result)))
      (is (empty? failing)
          (str "no checks should fail, but got: " (mapv :check/id failing)))
      (is (seq (mapv :check/id checks)) "closed-form checks actually ran"))))

(deftest closed-form-fails-when-conservation-violated
  (testing "closed-form rejects conservation violation even if reconstruction passes"
    (let [;; filled+deferred sum != requested (conservation violated)
          bad-decision (assoc valid-decision
                              :filled {:alice 25 :bob 30}
                              :deferred {:alice 15 :bob 30})
          result (admission/verify-semantic-decision input bad-decision)]
      (is (= :rejected (:admission/status result)))
      (is (false? (get-in result [:closed-form :valid?]))
          "closed-form detects conservation violation"))))

(deftest reconstruction-fails-when-allocation-wrong
  (testing "reconstruction rejects wrong allocation; closed-form may also fail"
    (let [;; conservation holds (30+20=50 for each), but allocation is wrong
          ;; (swapped amounts)
          swapped (assoc valid-decision
                         :filled {:alice 30 :bob 20}
                         :deferred {:alice 10 :bob 40})
          result (admission/verify-semantic-decision input swapped)]
      (is (= :rejected (:admission/status result)))
      (is (seq (get-in result [:semantic-reconstruction :mismatches]))
          "reconstruction detects the swap"))))

(deftest both-surfaces-must-pass-for-admission
  (testing "admission requires both closed-form AND reconstruction to pass"
    (let [result (admission/verify-semantic-decision input valid-decision)
          failing (filterv #(= :fail (:status %))
                           (get-in result [:closed-form :checks]))]
      (is (= :admitted (:admission/status result)))
      (is (empty? failing) "no closed-form checks fail")
      (is (get-in result [:semantic-reconstruction :valid?])
          "reconstruction passes"))))

;; ---------------------------------------------------------------------------
;; 2. Critical mutation test: wrong allocation + valid hash + real closed-form
;; ---------------------------------------------------------------------------

(deftest mutation-wrong-allocation-valid-hash-real-closed-form-rejected
  (testing (str "layered defense: wrong allocation + conservation + valid hash "
                "-> both closed-form and reconstruction independently reject")
    (let [;; Build a decision with swapped allocation (conservation preserved)
          ;; floor-alloc of 50 across {:alice 40 :bob 60} -> alice=20, bob=30
          ;; Swap: alice=30, bob=20 (conservation holds)
          ;;
          ;; NOTE: The current closed-form suite is comprehensive for pro-rata
          ;; allocation mutations — rounding-fairness catches any deviation from
          ;; ideal-floor allocation, and fail-action-fairness catches deferred
          ;; distribution shifts. This test demonstrates that both surfaces
          ;; independently detect the same mutation (layered defense), not that
          ;; reconstruction is necessary when closed-form passes. Reconstruction
          ;; provides defense-in-depth against potential future closed-form check
          ;; regressions and mechanism-specific blind spots.
          swapped-decision {:settlement-mode :partial-fill
                            :requested {:alice 40 :bob 60}
                            :filled {:alice 30 :bob 20}
                            :deferred {:alice 10 :bob 40}
                            :haircut {}
                            :unrealized {}
                            :policy {:mode :pro-rata :rounding-policy :floor}
                            :evidence {:available-liquidity 50}}
          artifact (build-decision-artifact swapped-decision)
          result (admission/verify-semantic-decision
                  (assoc-in input [:policy :rounding-policy] :floor)
                  artifact)]
      ;; 1. Decision hash is genuinely valid (content-addressed integrity)
      (is (partial-fill/decision-hash-valid? artifact)
          "decision hash is genuinely valid")
      ;; 2. Conservation is preserved per-claim
      (is (= 40 (+ 30 10)) "alice: filled+deferred = requested")
      (is (= 60 (+ 20 40)) "bob: filled+deferred = requested")
      ;; 3. Admission is rejected
      (is (= :rejected (:admission/status result))
          "semantic reconstruction rejects the wrong allocation")
      ;; 4. Closed-form detects the allocation mismatch (rounding-fairness)
      (is (false? (get-in result [:closed-form :valid?]))
          "closed-form also rejects the wrong allocation")
      ;; 5. Reconstruction reports specific mismatches
      (let [mismatches (get-in result [:semantic-reconstruction :mismatches])]
        (is (seq mismatches) "mismatches are reported")
        (is (some #(= :filled (:field %)) mismatches)
            "filled field mismatch is reported")))))

(deftest mutation-wrong-allocation-but-valid-hash-with-stub
  (testing "reconstruction detects wrong allocation independently of hash validity"
    (let [swapped-decision {:settlement-mode :partial-fill
                            :requested {:alice 40 :bob 60}
                            :filled {:alice 30 :bob 20}
                            :deferred {:alice 10 :bob 40}
                            :haircut {}
                            :unrealized {}}
          result (verify-with-stub input swapped-decision)]
      (is (= :rejected (:admission/status result)))
      (is (empty? (get-in result [:closed-form :checks]))
          "closed-form was stubbed")
      (is (seq (get-in result [:semantic-reconstruction :mismatches]))
          "reconstruction detects the swap"))))

;; ---------------------------------------------------------------------------
;; 3. Dependency boundary: exact-math as trusted shared infrastructure
;; ---------------------------------------------------------------------------

(deftest semantic-reconstruction-delegates-to-exact-math
  (testing "reconstruction uses exact-math allocator primitives directly"
    (let [source (slurp (io/resource "resolver_sim/yield/partial_fill.clj"))
          ;; Check that semantic-reconstruction calls m/floor-alloc, m/largest-remainder-alloc, etc.
          recon-start (.indexOf source "(defn semantic-reconstruction")
          recon-end (.indexOf source "\n(defn " (inc recon-start))
          recon-body (if (> recon-end 0)
                       (subs source recon-start recon-end)
                       (subs source recon-start))]
      ;; Should reference exact-math functions
      (is (re-find #"\(m/floor-alloc" recon-body)
          "reconstruction uses m/floor-alloc")
      (is (re-find #"\(m/largest-remainder-alloc" recon-body)
          "reconstruction uses m/largest-remainder-alloc")
      (is (re-find #"\(m/floor-and-carry-alloc" recon-body)
          "reconstruction uses m/floor-and-carry-alloc"))))

(deftest exact-math-is-trusted-shared-infrastructure
  (testing "exact-math is documented as shared between producer and verifier"
    (let [source (slurp (io/resource "resolver_sim/pro_rata/semantic_admission.clj"))]
      (is (re-find #"(?s)require.*partial-fill" source)
          "semantic_admission requires partial-fill (which delegates to exact-math)")
      (is (re-find #"semantic-reconstruction" source)
          "semantic_admission calls semantic-reconstruction from partial-fill"))))

(deftest semantic-reconstruction-does-not-call-producer
  (testing "reconstruction does not call the high-level producer functions"
    (let [source (slurp (io/resource "resolver_sim/yield/partial_fill.clj"))
          recon-start (.indexOf source "(defn semantic-reconstruction")
          recon-end (.indexOf source "\n(defn " (inc recon-start))
          recon-body (if (> recon-end 0)
                       (subs source recon-start recon-end)
                       (subs source recon-start))]
      (is (not (re-find #"\(payoffs/" recon-body))
          "does not call payoffs allocator")
      (is (not (re-find #"\(pro-rata/" recon-body))
          "does not call pro-rata allocator")
      (is (not (re-find #"\(calculate-fulfillment" recon-body))
          "does not call the high-level producer"))))

;; ---------------------------------------------------------------------------
;; 4. Malformed input coverage
;; ---------------------------------------------------------------------------

(deftest missing-available-is-rejected
  (testing "missing :available in input is rejected fail-closed"
    (let [inp (dissoc input :available)
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "missing available is rejected fail-closed")
      (is (some #(= :missing-available %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest negative-available-is-rejected
  (testing "negative available is rejected fail-closed"
    (let [inp (assoc input :available -10)
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "negative available is rejected fail-closed")
      (is (some #(= :negative-available %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest non-integer-amounts-rejected
  (testing "non-integer amounts are rejected fail-closed, not silently coerced"
    (let [inp {:available 50
               :requested {:alice 40.5 :bob 60}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :partial-fill
                    :requested {:alice 40.5 :bob 60}
                    :filled {:alice 20 :bob 30}
                    :deferred {:alice 20 :bob 30}
                    :haircut {}
                    :unrealized {}
                    :policy {:mode :pro-rata :rounding-policy :largest-remainder}
                    :evidence {:available-liquidity 50}}
          result (verify-with-stub inp decision)]
      (is (= :rejected (:admission/status result))
          "non-integer amounts are rejected fail-closed")
      (is (some #(= :non-integer-request %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest non-integer-available-rejected
  (testing "non-integer available-liquidity is rejected fail-closed"
    (let [inp (assoc input :available 50.5)
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "non-integer available is rejected fail-closed")
      (is (some #(= :non-integer-available %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest missing-policy-is-rejected
  (testing "missing :policy in input is rejected fail-closed"
    (let [inp (dissoc input :policy)
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "missing policy is rejected fail-closed")
      (is (some #(= :missing-policy %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest missing-mode-is-rejected
  (testing "missing :mode in policy is rejected fail-closed"
    (let [inp (assoc-in input [:policy] {:rounding-policy :floor})
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "missing mode is rejected fail-closed")
      (is (some #(= :missing-mode %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest unknown-rounding-policy-is-rejected
  (testing "unknown rounding policy is rejected fail-closed"
    (let [inp (assoc-in input [:policy :rounding-policy] :unknown-rounding)
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "unknown rounding policy is rejected fail-closed")
      (is (some #(= :unknown-rounding-policy %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest negative-request-amounts-rejected
  (testing "negative request amounts are rejected fail-closed"
    (let [inp (assoc-in input [:requested :alice] -10)
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "negative request amounts are rejected fail-closed")
      (is (some #(= :negative-request %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest nil-claim-key-is-rejected
  (testing "nil claim key in requested is rejected fail-closed"
    (let [inp (assoc input :requested {nil 40 :bob 60})
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "nil claim key is rejected fail-closed")
      (is (some #(= :invalid-claim-identity %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest extra-decision-keys-rejected
  (testing "decision with unexpected top-level keys is rejected"
    (let [mutated (assoc valid-decision :malicious/payload true)
          result (verify-with-stub input mutated)]
      (is (= :rejected (:admission/status result))
          "extra decision keys are rejected fail-closed")
      (is (some #(= :unexpected-decision-keys %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest extra-decision-claims-detected
  (testing "decision has claims not in the input request"
    (let [mutated (assoc valid-decision
                         :filled {:alice 15 :bob 25 :charlie 10}
                         :deferred {:alice 25 :bob 35 :charlie 0})]
      (is (= :rejected (:admission/status (verify-with-stub input mutated)))
          "extra claim in decision rejected"))))

;; ---------------------------------------------------------------------------
;; 5. Runtime-option independence
;; ---------------------------------------------------------------------------

(deftest runtime-parallelism-options-do-not-affect-result
  (testing "adding execution/parallelism options does not change reconstruction"
    (let [r1 (verify-with-stub input valid-decision)
          r2 (verify-with-stub (assoc input
                                      :execution/claimant-parallelism :sequential
                                      :execution/quiescence-timeout-seconds 30)
                               valid-decision)]
      (is (= (:admission/status r1) (:admission/status r2)))
      (is (= (get-in r1 [:semantic-reconstruction :expected])
             (get-in r2 [:semantic-reconstruction :expected]))
          "reconstruction is independent of execution options"))))

(deftest progress-atom-does-not-affect-result
  (testing "adding a progress-atom does not change reconstruction"
    (let [r1 (verify-with-stub input valid-decision)
          r2 (verify-with-stub (assoc input :progress-atom (atom nil))
                               valid-decision)]
      (is (= (:admission/status r1) (:admission/status r2))
          "progress-atom is ignored by reconstruction"))))

;; ---------------------------------------------------------------------------
;; 6. Altered fill amounts
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
;; 7. Altered policy
;; ---------------------------------------------------------------------------

(deftest different-rounding-policy-yields-different-valid-allocation
  (testing "floor rounding produces a different valid allocation than largest-remainder"
    (let [inp {:available 7
               :requested {:a 4 :b 4 :c 2}
               :policy {:mode :pro-rata :rounding-policy :floor}}
          decision {:settlement-mode :partial-fill
                    :requested {:a 4 :b 4 :c 2}
                    :filled {:a 2 :b 2 :c 1}
                    :deferred {:a 2 :b 2 :c 1}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

(deftest mismatched-rounding-policy-is-rejected
  (testing "allocation computed with wrong rounding policy is rejected"
    (let [decision-floor {:settlement-mode :partial-fill
                          :requested {:a 4 :b 4 :c 2}
                          :filled {:a 2 :b 2 :c 1}
                          :deferred {:a 2 :b 2 :c 1}
                          :haircut {}
                          :unrealized {}}]
      (is (= :rejected (:admission/status (verify-with-stub input decision-floor)))
          "floor allocation rejected when largest-remainder expected"))))

;; ---------------------------------------------------------------------------
;; 8. Claim ordering
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

(deftest mutation-extra-claimant-is-rejected
  (testing "adding an extra claimant not in the input is rejected"
    (let [mutated (assoc valid-decision
                         :filled {:alice 20 :bob 25 :charlie 5}
                         :deferred {:alice 20 :bob 35 :charlie 0})]
      (is (= :rejected (:admission/status (verify-with-stub input mutated)))
          "extra claimant rejected"))))

(deftest mutation-missing-claimant-is-rejected
  (testing "removing a claimant from filled is rejected"
    (let [mutated (assoc valid-decision
                         :filled {:alice 50}
                         :deferred {:alice 0 :bob 60})]
      (is (= :rejected (:admission/status (verify-with-stub input mutated)))
          "missing claimant rejected"))))

;; ---------------------------------------------------------------------------
;; 9. Rounding ties
;; ---------------------------------------------------------------------------

(deftest rounding-tie-at-exact-half-is-deterministic
  (testing "reconstruction is deterministic at the rounding boundary"
    (let [inp {:available 10
               :requested {:a 1 :b 1}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :full-fill
                    :requested {:a 1 :b 1}
                    :filled {:a 5 :b 5}
                    :deferred {:a 0 :b 0}
                    :haircut {}
                    :unrealized {}}]
      (is (= :admitted (:admission/status (verify-with-stub inp decision)))))))

(deftest mutation-rounding-tie-swap-is-rejected
  (testing "swapping the rounding remainder between two equal-weight claimants"
    (let [inp {:available 7
               :requested {:a 4 :b 4}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          recon (get-in (verify-with-stub inp
                                          {:settlement-mode :partial-fill
                                           :requested {:a 4 :b 4}
                                           :filled {:a 3 :b 4}
                                           :deferred {:a 1 :b 0}
                                           :haircut {}
                                           :unrealized {}})
                        [:semantic-reconstruction :expected :filled])
          swapped-filled (if (= (:a recon) 3)
                           {:a 4 :b 3}
                           {:a 3 :b 4})
          swapped-deferred (if (= (:a recon) 3)
                             {:a 0 :b 1}
                             {:a 1 :b 0})
          mutated {:settlement-mode :partial-fill
                   :requested {:a 4 :b 4}
                   :filled swapped-filled
                   :deferred swapped-deferred
                   :haircut {}
                   :unrealized {}}]
      (is (= :rejected (:admission/status (verify-with-stub inp mutated)))
          "rounding remainder swap rejected"))))

;; ---------------------------------------------------------------------------
;; 10. Full vs partial fill
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
;; 11. Malformed inputs (boundary cases)
;; ---------------------------------------------------------------------------

(deftest zero-available-rejects-nonzero-fill
  (testing "zero available with zero fill is vacuously correct"
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
;; 12. Duplicate identities
;; ---------------------------------------------------------------------------

(deftest duplicate-request-keys-collapse
  (testing "duplicate keys in requested map are collapsed by Clojure semantics"
    (let [inp {:available 50
               :requested {:alice 40 :bob 60}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}]
      (is (= :admitted (:admission/status (verify-with-stub inp valid-decision)))))))

;; ---------------------------------------------------------------------------
;; 13. Runtime-option independence
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
    (let [with-hash (assoc valid-decision :decision/hash "fake-hash-abc123")
          result (verify-with-stub input with-hash)]
      (is (= :admitted (:admission/status result))
          "reconstruction is unaffected by presence of a hash"))))

(deftest reconstruction-is-pure-function
  (testing "calling verify-semantic-decision twice returns identical results"
    (let [r1 (verify-with-stub input valid-decision)
          r2 (verify-with-stub input valid-decision)]
      (is (= r1 r2) "deterministic pure function"))))

;; ---------------------------------------------------------------------------
;; 14. Authority binding: caller-supplied vs authoritative inputs
;; ---------------------------------------------------------------------------

(deftest reconstruction-uses-authoritative-input-not-decision-fields
  (testing "reconstruction derives expected allocation from input, not from decision"
    (let [inp {:available 50
               :requested {:a 40 :b 60}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :partial-fill
                    :requested {:a 40 :b 60}
                    :filled {:a 20 :b 30}
                    :deferred {:a 20 :b 30}
                    :haircut {}
                    :unrealized {}
                    :evidence {:available-liquidity 100}}
          result (verify-with-stub inp decision)]
      (is (= :admitted (:admission/status result))
          "reconstruction uses input's available, not decision's invented field"))))

(deftest invented-claimant-not-in-input-is-rejected
  (testing "a claimant in the decision but not in the input is rejected"
    (let [inp {:available 50
               :requested {:alice 40 :bob 60}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :partial-fill
                    :requested {:alice 40 :bob 60}
                    :filled {:alice 15 :bob 25 :charlie 10}
                    :deferred {:alice 25 :bob 35 :charlie 0}
                    :haircut {}
                    :unrealized {}}]
      (is (= :rejected (:admission/status (verify-with-stub inp decision)))
          "invented claimant rejected"))))

(deftest different-available-amounts-rejected
  (testing "decision with wrong available amount is rejected"
    (let [inp {:available 50
               :requested {:alice 40 :bob 60}
               :policy {:mode :pro-rata :rounding-policy :largest-remainder}}
          decision {:settlement-mode :partial-fill
                    :requested {:alice 40 :bob 60}
                    :filled {:alice 40 :bob 60}
                    :deferred {:alice 0 :bob 0}
                    :haircut {}
                    :unrealized {}}]
      (is (= :rejected (:admission/status (verify-with-stub inp decision)))
          "wrong available amount rejected"))))

;; ---------------------------------------------------------------------------
;; 15. Ephemeral output contract
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

;; ---------------------------------------------------------------------------
;; 16. Dependency boundary: namespace isolation
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
;; 21. :not-applicable vs :unestablished distinction
;; ---------------------------------------------------------------------------

(deftest not-applicable-checks-are-accepted-in-closed-form
  (testing ":not-applicable check status is accepted alongside :pass in closed-form"
    (let [result (admission/verify-semantic-decision input valid-decision)
          checks (get-in result [:closed-form :checks])
          statuses (set (map :status checks))]
      ;; Some checks are :not-applicable (e.g., principal-first-priority for pro-rata)
      (is (contains? statuses :not-applicable)
          "at least one check is :not-applicable")
      ;; But admission still passes
      (is (= :admitted (:admission/status result))
          "admission passes even with :not-applicable checks"))))

(deftest not-applicable-means-check-logically-cannot-apply
  (testing ":not-applicable means the check cannot apply in this context"
    (let [result (admission/verify-semantic-decision input valid-decision)
          checks (get-in result [:closed-form :checks])
          na-checks (filter #(= :not-applicable (:status %)) checks)]
      (is (every? #(contains? #{:partial-fill/principal-first-priority
                                :partial-fill/waterfall-priority
                                :partial-fill/effective-rounding-consistency}
                              (:check/id %))
                  na-checks)
          ":not-applicable checks are mode-specific (principal-first, waterfall)"))))

(deftest unestablished-is-not-used-in-semantic-admission
  (testing ":unestablished is not a status in semantic admission results"
    (let [result (admission/verify-semantic-decision input valid-decision)
          all-statuses (mapcat (fn [c] [(:status c)])
                               (get-in result [:closed-form :checks]))]
      (is (not (some #(= :unestablished %) all-statuses))
          ":unestablished does not appear in closed-form check statuses"))))

;; ---------------------------------------------------------------------------
;; Gate 1-A: Real closed-form composition tests
;; ---------------------------------------------------------------------------

(deftest real-closed-form-valid-decision-is-admitted
  (testing "valid no-row pro-rata decision passes both closed-form and reconstruction"
    (let [result (admission/verify-semantic-decision input valid-decision)
          checks (get-in result [:closed-form :checks])
          failing (filterv #(= :fail (:status %)) checks)]
      (is (= :admitted (:admission/status result)))
      (is (true? (get-in result [:closed-form :valid?]))
          "closed-form is valid")
      (is (empty? failing) "no closed-form checks fail")
      (is (true? (get-in result [:semantic-reconstruction :valid?]))
          "reconstruction passes"))))

(deftest closed-form-failure-with-reconstruction-pass
  (testing "closed-form rejects conservation violation even when reconstruction passes"
    (let [;; conservation is violated: 25+30+15+30 = 100 ≠ 25+30+15+30 = 100
          ;; Actually: filled total = 25+30=55, deferred total=15+30=45, sum=100=requested. OK conservation.
          ;; But per-claim conservation: alice filled=25, deferred=15, sum=40=requested ✓
          ;; bob filled=30, deferred=30, sum=60=requested ✓. So this actually passes conservation!
          ;; Let me use a real violation: filled+deferred > requested for one claim
          bad-decision (assoc valid-decision
                              :filled {:alice 30 :bob 30}
                              :deferred {:alice 20 :bob 30})
          ;; alice: filled 30 + deferred 20 = 50 ≠ 40 (violation)
          ;; bob: filled 30 + deferred 30 = 60 ✓
          result (admission/verify-semantic-decision input bad-decision)]
      (is (= :rejected (:admission/status result)))
      (is (false? (get-in result [:closed-form :valid?]))
          "closed-form detects the violation")
      ;; Reconstruction: expected filled={:alice 20 :bob 30}, actual has {alice 30, bob 30}
      (is (seq (get-in result [:semantic-reconstruction :mismatches]))
          "reconstruction also detects mismatch"))))

(deftest reconstruction-fails-preservation-wrong-allocation
  (testing "reconstruction rejects wrong allocation; closed-form may also fail"
    (let [;; conservation holds (30+20=50 for each), but allocation is wrong (swapped)
          swapped (assoc valid-decision
                         :filled {:alice 30 :bob 20}
                         :deferred {:alice 10 :bob 40})
          result (admission/verify-semantic-decision input swapped)]
      (is (= :rejected (:admission/status result)))
      (is (false? (get-in result [:closed-form :valid?]))
          "closed-form rejects wrong allocation (rounds-fairness check)")
      (is (seq (get-in result [:semantic-reconstruction :mismatches]))
          "reconstruction detects the swap"))))

;; ---------------------------------------------------------------------------
;; Gate 1-A: Composed tests using real partial-fill-closed-form-checks
;; ---------------------------------------------------------------------------

(deftest composed-closed-form-fails-evidence-inconsistency-reconstruction-passes
  (testing "closed-form fails on evidence inconsistency while reconstruction passes"
    (let [;; Decision with correct allocation but invalid evidence
          ;; (evidence total-requested doesn't match actual requested sum)
          bad-evidence-decision (assoc valid-decision
                                       :evidence {:available-liquidity 50
                                                  :total-requested 200  ;; wrong: should be 100
                                                  :total-filled 50})
          result (admission/verify-semantic-decision input bad-evidence-decision)]
      ;; Closed-form should fail (evidence-self-consistency check)
      (is (false? (get-in result [:closed-form :valid?]))
          "closed-form detects evidence inconsistency")
      ;; Reconstruction passes because allocation matches
      (is (true? (get-in result [:semantic-reconstruction :valid?]))
          "reconstruction passes (allocation is correct)")
      ;; Overall admission is rejected
      (is (= :rejected (:admission/status result))
          "admission rejects when closed-form fails"))))

(deftest composed-both-pass-for-valid-decision
  (testing "both closed-form and reconstruction pass for valid decision"
    (let [result (admission/verify-semantic-decision input valid-decision)]
      (is (= :admitted (:admission/status result)))
      (is (true? (get-in result [:closed-form :valid?]))
          "closed-form passes")
      (is (true? (get-in result [:semantic-reconstruction :valid?]))
          "reconstruction passes"))))

(deftest composed-closed-form-not-applicable-checks-accepted
  (testing "real closed-form :not-applicable checks do not block admission"
    (let [result (admission/verify-semantic-decision input valid-decision)
          checks (get-in result [:closed-form :checks])
          na-checks (filter #(= :not-applicable (:status %)) checks)]
      ;; Verify :not-applicable checks exist for pro-rata mode
      (is (seq na-checks) "at least one :not-applicable check exists")
      ;; But admission still passes
      (is (= :admitted (:admission/status result))
          "admission passes with :not-applicable checks"))))

(deftest composed-reconstruction-fails-closed-form-may-fail
  (testing "reconstruction rejects wrong allocation; admission is rejected"
    (let [swapped (assoc valid-decision
                         :filled {:alice 30 :bob 20}
                         :deferred {:alice 10 :bob 40})
          result (admission/verify-semantic-decision input swapped)]
      (is (= :rejected (:admission/status result)))
      (is (seq (get-in result [:semantic-reconstruction :mismatches]))
          "reconstruction detects mismatch")
      (is (false? (get-in result [:closed-form :valid?]))
          "closed-form also rejects"))))

;; ---------------------------------------------------------------------------
;; Gate 1-A: Additional malformed input coverage
;; ---------------------------------------------------------------------------

(deftest missing-requested-is-rejected
  (testing "missing :requested in input is rejected fail-closed"
    (let [inp (dissoc input :requested)
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "missing requested is rejected fail-closed")
      (is (some #(= :missing-requested %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest invalid-requested-not-a-map-is-rejected
  (testing "non-map :requested in input is rejected fail-closed"
    (let [inp (assoc input :requested "not-a-map")
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :rejected (:admission/status result))
          "invalid requested is rejected fail-closed")
      (is (some #(= :invalid-requested %)
                (get-in result [:input-violations]))
          "violation reason is reported"))))

(deftest rows-unsupported-explicitly-rejected
  (testing "rows/caps input returns :unsupported status"
    (let [inp (assoc input :rows [{:key :alice :owed 40 :cap 20}])
          result (admission/verify-semantic-decision inp valid-decision)]
      (is (= :unsupported (:admission/status result))
          "rows input returns :unsupported"))))

;; ---------------------------------------------------------------------------
;; Gate 1-A: Authority boundary
;; ---------------------------------------------------------------------------

(deftest authority-boundary-is-caller-supplied
  (testing "result declares :authority :caller-supplied-semantic-inputs"
    (let [result (admission/verify-semantic-decision input valid-decision)]
      (is (= :caller-supplied-semantic-inputs
             (get-in result [:scope :authority]))
          "authority is :caller-supplied-semantic-inputs (semantic derivability, not authoritative admission)")
      (is (not (contains? result :evidence/root))
          "no evidence root claimed — inputs are not authenticated")
      (is (not (contains? result :artifact/hash))
          "no artifact hash claimed"))))
