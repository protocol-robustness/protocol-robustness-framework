(ns resolver-sim.resubmission.transaction-test
  "Tests for the transaction-ordering layer: the generic ordering primitive,
   the pure resubmission transition (pinned rejection precedence), the in-memory
   TransactionStore (CAS + ordering evidence), and reference-vs-store trace
   equivalence."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [are deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.resubmission.disposition :as disposition]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.support.ed25519 :as ed]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.transaction.protocol :as protocol]))

(def family "sha256:FAM")
(def disposition-authority (ed/keypair :disposition-authority))

(defn- bare-receipt [id]
  {:attempt-receipt/schema receipt/receipt-schema
   :attempt-receipt/id id
   :attempt-receipt/outcome :rejected
   :attempt-receipt/finality :final
   :attempt-receipt/resubmission-eligibility :eligible
   :attempt-receipt/lifecycle-status :active})

(defn- admit-cmd
  [& {:keys [child parent seq basis link idem expected-version]
      :or {seq 1 expected-version nil}}]
  {:transaction/action :prf.resubmission/admit-child
   :transaction/input
   {:parent-receipt-hash parent
    :link-artifact-hash link
    :candidate-attempt-receipt (bare-receipt child)
    :candidate-attempt-receipt-id child
    :idempotency-key idem
    :content-key basis
    :sequence seq
    :expected-chain-version expected-version}})

(defn- disposition-cmd
  [attempt status & {:keys [previous expected-disposition-head expected-version artifact]}]
  {:transaction/action :prf.resubmission/apply-disposition
   :transaction/input
   {:attempt-receipt-hash attempt
    :disposition-artifact
    (or artifact
        (disposition/sign-disposition
         (cond-> {:attempt-disposition/schema disposition/disposition-schema
                  :attempt-disposition/attempt-receipt-hash attempt
                  :attempt-disposition/status status}
           previous (assoc :attempt-disposition/previous-disposition-hash previous))
         (:private-key disposition-authority)))
    :expected-disposition-head expected-disposition-head
    :expected-chain-version expected-version}})

;; ── generic transaction-ordering ────────────────────────────────────────────

(deftest ordering-primitive
  (testing "golden ordering hash for fixed inputs"
    (let [o (ordering/transaction-ordering
             {:transaction/action :prf.resubmission/admit-child
              :transaction/scope :resubmission-family
              :transaction/conflict-key [:resubmission-family family]
              :transaction/commit-index 17
              :transaction/previous-transaction-hash "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
              :transaction/state-before-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
              :transaction/state-after-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
              :transaction/effects-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
              :transaction/expected {:chain-head "sha256:PARENT" :chain-version 16}
              :transaction/observed {:chain-head "sha256:PARENT" :chain-version 16}})]
      (is (= "transaction-ordering.v1" (:transaction-ordering/schema o)))
      (is (string? (:transaction-ordering/hash o)))
      (is (true? (:valid? (ordering/verify-ordering o))))))
  (testing "mutating any authoritative field changes the hash"
    (let [base {:transaction/action :prf.resubmission/admit-child
                :transaction/scope :resubmission-family
                :transaction/conflict-key [:resubmission-family family]
                :transaction/commit-index 17
                :transaction/state-before-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                :transaction/state-after-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                :transaction/effects-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}
          o1 (ordering/transaction-ordering base)
          o2 (ordering/transaction-ordering (assoc base :transaction/commit-index 18))]
      (is (not= (:transaction-ordering/hash o1) (:transaction-ordering/hash o2)))
      (let [tampered (assoc o1 :transaction/state-after-root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")]
        (is (= :ordering-hash-mismatch (:reason (ordering/verify-ordering tampered)))))))
  (testing "a malformed root reference is rejected even when the self-hash recomputes"
    (let [base {:transaction/action :prf.resubmission/admit-child
                :transaction/scope :resubmission-family
                :transaction/conflict-key [:resubmission-family family]
                :transaction/commit-index 17
                :transaction/state-before-root "not-a-hash"
                :transaction/state-after-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                :transaction/effects-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}
          o (ordering/transaction-ordering base)]
      (is (= :malformed-root-reference (:reason (ordering/verify-ordering o)))))
    (let [base {:transaction/action :prf.resubmission/admit-child
                :transaction/scope :resubmission-family
                :transaction/conflict-key [:resubmission-family family]
                :transaction/commit-index 17
                :transaction/state-before-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                :transaction/state-after-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                :transaction/effects-root "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
                :transaction/previous-transaction-hash "sha256:SHORT"}
          o (ordering/transaction-ordering base)]
      (is (= :malformed-root-reference (:reason (ordering/verify-ordering o)))
          "a malformed previous-transaction-hash is also a malformed root"))))

;; ── pure transition: pinned rejection precedence ────────────────────────────

(deftest transition-pinned-precedence
  (testing "admit-child happy path and ordering-input"
    (let [r (transition/apply-action
             (transition/empty-state family)
             (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))]
      (is (= :committed (:status r)))
      (is (= "sha256:R1" (:chain/head (:state r))))
      (is (= :prf.resubmission/admit-child
             (get-in r [:ordering-input :transaction/action])))
      (is (= [:resubmission-family family]
             (get-in r [:ordering-input :transaction/conflict-key])))))
  (testing "idempotent replay vs idempotency-content-mismatch (precedence 1-2)"
    (let [s0 (transition/empty-state family)
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          s2 (:state (transition/apply-action s1 (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")))
          replay (transition/apply-action s2 (admit-cmd :child "sha256:R3" :seq 3 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))
          mismatch (transition/apply-action s2 (admit-cmd :child "sha256:R3" :seq 3 :parent "sha256:R1" :basis "sha256:DIFFERENT" :link "sha256:L3" :idem "sha256:I2"))]
      (is (= :idempotent-replay (:status replay)))
      (is (= :submission-already-observed (:reason replay)))
      (is (= "sha256:R2" (get-in replay [:public-result :existing])))
      (is (= :rejected (:status mismatch)))
      (is (= :idempotency-content-mismatch (:reason mismatch)))))
  (testing "duplicate content checked before stale-head rejection (precedence 3 vs 6)"
    (let [s0 (transition/empty-state family)
          r1 (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))
          s1 (:state r1)
          r2 (transition/apply-action s1 (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))
          s2 (:state r2)
          ;; same content (B2) submitted to R1 which is NO LONGER the head:
          dup (transition/apply-action s2 (admit-cmd :child "sha256:R3" :seq 3 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L3" :idem "sha256:I3"))]
      (is (= :rejected (:status dup)))
      (is (= :duplicate-content-submission (:reason dup)))))
  (testing "stale-head rejection applies by contrast when content is unique (precedence 6)"
    (let [s0 (transition/empty-state family)
          r1 (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))
          s1 (:state r1)
          r2 (transition/apply-action s1 (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))
          s2 (:state r2)
          ;; UNIQUE content (B3) submitted to R1, which is no longer the head (R2 is):
          ;; stale-head applies because there is no duplicate-content conflict.
          stale (transition/apply-action s2 (admit-cmd :child "sha256:R3" :seq 3 :parent "sha256:R1" :basis "sha256:B3" :link "sha256:L3" :idem "sha256:I3"))]
      (is (= :rejected (:status stale)))
      (is (= :parent-not-current-head (:reason stale))
          "without duplicate content, stale-head rejection (precedence 6) applies,
           proving duplicate-content precedence by contrast")))
  (testing "transplant detection (precedence 4)"
    (let [s0 (transition/empty-state family)
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          s2 (:state (transition/apply-action s1 (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")))
          tx (transition/apply-action s2 (admit-cmd :child "sha256:R3" :seq 3 :parent "sha256:R2" :basis "sha256:B2" :link "sha256:L3" :idem "sha256:I3"))]
      (is (= :idempotency-key-rebound (:reason tx)))))
  (testing "disposition eligibility gates admission (precedence 5)"
    (let [s0 (transition/empty-state family)
          s1 (assoc (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
                    :chain/disposition-public-hex (:public-hex disposition-authority))
          sd (:state (transition/apply-action s1 (disposition-cmd "sha256:R1" :withdrawn)))
          blocked (transition/apply-action sd (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))]
      (is (= :rejected (:status blocked)))
      (is (= :parent-rejection-not-final (:reason blocked)))))
  (testing "review and final dispositions retain the receipt's active lifecycle"
    (let [s0 (transition/empty-state family (:public-hex disposition-authority))
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          final-state (:state (transition/apply-action s1 (disposition-cmd "sha256:R1" :final)))
          admission (transition/apply-action
                     final-state
                     (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1"
                                :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))]
      (is (= :active (transition/effective-disposition final-state "sha256:R1")))
      (is (= :final (get-in final-state [:chain/disposition-status-by-receipt "sha256:R1"])))
      (is (= :committed (:status admission)))))
  (testing "apply-disposition increments commit index WITHOUT a new resubmission sequence"
    (let [s0 (transition/empty-state family)
          s1 (assoc (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
                    :chain/disposition-public-hex (:public-hex disposition-authority))
          sd (:state (transition/apply-action s1 (disposition-cmd "sha256:R1" :superseded)))]
      (is (= 2 (:transaction/commit-index sd)))
      (is (= 1 (count (keys (:chain/successor-by-parent sd)))))
      (is (= :superseded (get-in sd [:chain/effective-disposition-by-receipt "sha256:R1"])))
      (is (= "sha256:R1" (:chain/head sd)))))
  (testing "forged caller fields cannot override a disposition artifact"
    (let [s0 (transition/empty-state family (:public-hex disposition-authority))
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          command (assoc-in (disposition-cmd "sha256:R1" :withdrawn)
                            [:transaction/input :disposition-status] :active)
          result (transition/apply-action s1 command)]
      (is (= :committed (:status result)))
      (is (= :withdrawn (get-in result [:state :chain/effective-disposition-by-receipt "sha256:R1"])))))
  (testing "disposition must be signed, bound to its receipt, and linked to the current head"
    (let [s0 (transition/empty-state family (:public-hex disposition-authority))
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          unsigned {:attempt-disposition/schema disposition/disposition-schema
                    :attempt-disposition/attempt-receipt-hash "sha256:R1"
                    :attempt-disposition/status :withdrawn}
          forged (transition/apply-action s1 {:transaction/action :prf.resubmission/apply-disposition
                                              :transaction/input {:attempt-receipt-hash "sha256:R1"
                                                                  :disposition-artifact unsigned}})
          wrong-receipt (transition/apply-action
                         s1
                         (disposition-cmd
                          "sha256:R1" :withdrawn
                          :artifact (disposition/sign-disposition
                                     {:attempt-disposition/schema disposition/disposition-schema
                                      :attempt-disposition/attempt-receipt-hash "sha256:R2"
                                      :attempt-disposition/status :withdrawn}
                                     (:private-key disposition-authority))))
          wrong-previous (transition/apply-action s1 (disposition-cmd "sha256:R1" :withdrawn :previous "sha256:NOT-THE-HEAD"))]
      (is (= :missing-disposition-signature (:reason forged)))
      (is (= :disposition-receipt-mismatch (:reason wrong-receipt)))
      (is (= :disposition-previous-hash-mismatch (:reason wrong-previous)))))
  (testing "terminal dispositions cannot be replaced"
    (let [s0 (transition/empty-state family (:public-hex disposition-authority))
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          first-command (disposition-cmd "sha256:R1" :withdrawn)
          s2 (:state (transition/apply-action s1 first-command))
          head (get-in s2 [:chain/disposition-head-by-receipt "sha256:R1"])
          second-result (transition/apply-action s2 (disposition-cmd "sha256:R1" :revoked :previous head))]
      (is (= :invalid-disposition-transition (:reason second-result)))))
  (testing "commit contention (precedence 10)"
    (let [s0 (transition/empty-state family)
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          stale (transition/apply-action s1 (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2" :expected-version 0))]
      (is (= :rejected (:status stale)))
      (is (= :commit-contention (:reason stale)))))
  (testing "unknown action is rejected"
    (is (= :rejected (:status (transition/apply-action (transition/empty-state family)
                                                       {:transaction/action :bogus/action
                                                        :transaction/input {}}))))))

(deftest re-admit-existing-receipt-rejected
  (testing "re-admitting an already-committed receipt under a new parent is rejected (prior-state integrity)"
    (let [s0 (transition/empty-state family)
          r1 (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))
          s1 (:state r1)
          r2 (transition/apply-action s1 (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))
          s2 (:state r2)
          fork (transition/apply-action s2 (admit-cmd :child "sha256:R1" :seq 3 :parent "sha256:R2" :basis "sha256:B3" :link "sha256:L3" :idem "sha256:I3"))]
      (is (= :rejected (:status fork)))
      (is (= :receipt-already-committed (:reason fork)))
      (is (= "sha256:R2" (:chain/head s2))
          "prior committed head must be untouched")
      (is (= {:sequence 1 :parent-receipt-hash nil}
             (select-keys (get-in s2 [:chain/attempt-receipts "sha256:R1"])
                          [:sequence :parent-receipt-hash]))
          "prior committed R1 parent/sequence must be preserved"))))

;; ── store: transact! + CAS + ordering evidence ──────────────────────────────

(deftest store-transact
  (testing "committed transitions attach ordering evidence with a linked chain"
    (let [s (store/new-resubmission-store family)
          r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          r2 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
          o1 (:transaction-ordering r1)
          o2 (:transaction-ordering r2)]
      (is (= :committed (:status r1)))
      (is (= :committed (:status r2)))
      (is (true? (:valid? (ordering/verify-ordering o1))))
      (is (true? (:valid? (ordering/verify-ordering o2))))
      (is (= 1 (:transaction/commit-index o1)))
      (is (= 2 (:transaction/commit-index o2)))
      (is (= (:transaction-ordering/hash o1) (:transaction/previous-transaction-hash o2)))
      (is (= "sha256:R2" (store/chain-head s)))
      (is (= 2 (store/chain-version s)))))
  (testing "expected-version contention does not invoke the transition"
    (let [s (store/new-resubmission-store family)
          _ (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          r (protocol/transact! s nil 0 (fn [_] (throw (Exception. "must not be invoked"))))]
      (is (= :contention (:status r)))
      (is (= :version-mismatch (:reason r)))
      (is (= 1 (:observed-version r)))))
  (testing "state roots change on every commit and are stable across the ordering hash"
    (let [s (store/new-resubmission-store family)
          r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          r2 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
          o1 (:transaction-ordering r1)
          o2 (:transaction-ordering r2)
          s2 (store/state-of s)]
      (is (not= (:transaction/state-before-root o1) (:transaction/state-after-root o1)))
      (is (= (:transaction/state-after-root o1) (:transaction/state-before-root o2)))
      (is (= (transition/state-root s2) (:transaction/state-after-root o2))))))

(deftest verify-ordering-chain-prior-state-fixed-point
  (testing "a committed chain verifies the prior-state fixed-point linkage"
    (let [s (store/new-resubmission-store family)
          r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          r2 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
          chain [(:transaction-ordering r1) (:transaction-ordering r2)]
          result (ordering/verify-ordering-chain chain)]
      (is (:valid? result))))
  (testing "a broken prior-state-after linkage is rejected"
    (let [s (store/new-resubmission-store family)
          r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          r2 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
          o2 (:transaction-ordering r2)
          tampered (ordering/transaction-ordering
                    (assoc (ordering/unsigned-ordering-projection o2)
                           :transaction/state-before-root "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))
          result (ordering/verify-ordering-chain
                  [(:transaction-ordering r1) tampered])]
      (is (not (:valid? result)))
      (is (some #(re-find #"state-before-root is not the prior-state fixed point" %)
                (:errors result)))))
  (testing "a first ordering with a non-nil previous-transaction-hash is rejected"
    (let [s (store/new-resubmission-store family)
          r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          o1 (:transaction-ordering r1)
          tampered (ordering/transaction-ordering
                    (assoc (ordering/unsigned-ordering-projection o1)
                           :transaction/previous-transaction-hash "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
          result (ordering/verify-ordering-chain [tampered])]
      (is (not (:valid? result)))
      (is (some #(re-find #"ordering\[0\]" %) (:errors result))))))

(deftest verify-ordering-chain-origin-anchor
  (testing "an anchored chain is rejected when the first ordering's state-before-root
            is not the supplied origin state root"
    (let [s (store/new-resubmission-store family)
          r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          r2 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
          chain [(:transaction-ordering r1) (:transaction-ordering r2)]
          true-origin (transition/state-root (transition/empty-state family))]
      (testing "the true empty-state origin anchors the honest chain"
        (is (:valid? (ordering/verify-ordering-chain chain true-origin))))
      (testing "a wrong origin fails the chain even though internal continuity holds"
        (let [result (ordering/verify-ordering-chain
                      chain "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")]
          (is (not (:valid? result)))
          (is (some #(re-find #"chain origin state root" %) (:errors result)))))
      (testing "the origin of the store's chain equals the domain empty-state root"
        (is (= (:transaction/state-before-root (:transaction-ordering r1)) true-origin))))))

(deftest state-root-excludes-transaction-last-hash
  (testing "mutating :transaction/last-hash on committed state does not change state-root"
    (let [s (store/new-resubmission-store family)
          _ (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          _ (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
          committed (store/state-of s)
          original-root (transition/state-root committed)
          last-hash (:transaction/last-hash committed)
          tampered (assoc committed :transaction/last-hash "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
          tampered-root (transition/state-root tampered)]
      (is (some? last-hash)
          "committed state carries a :transaction/last-hash (the ordering hash)")
      (is (= original-root tampered-root)
          (str "FAIL: state-root must be stable across :transaction/last-hash changes.\n"
               "chain-state-projection excludes :transaction/last-hash so the ordering\n"
               "hash does not create a cycle in the state root.\n"
               "original=" original-root " tampered=" tampered-root)))))

(deftest verify-ordering-chain-commit-monotonicity-and-conflict-key
  (testing "a non-monotonic commit-index is rejected"
    (let [s (store/new-resubmission-store family)
          r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          r2 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
          o2 (:transaction-ordering r2)
          ;; a true successor of o2 (correct prev-hash + state fixed point) but
          ;; with a commit-index that does NOT strictly increase
          successor (ordering/transaction-ordering
                     {:transaction/action :prf.resubmission/admit-child
                      :transaction/scope :resubmission-family
                      :transaction/conflict-key [:resubmission-family family]
                      :transaction/commit-index (:transaction/commit-index o2)
                      :transaction/previous-transaction-hash (:transaction-ordering/hash o2)
                      :transaction/state-before-root (:transaction/state-after-root o2)
                      :transaction/state-after-root (:transaction/state-after-root o2)
                      :transaction/effects-root (:transaction/effects-root o2)})
          result (ordering/verify-ordering-chain [o2 successor])]
      (is (not (:valid? result)))
      (is (some #(re-find #"commit-index does not strictly increase" %) (:errors result)))))
  (testing "a conflict-key that diverges mid-chain is rejected"
    (let [s (store/new-resubmission-store family)
          r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          r2 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
          o1 (:transaction-ordering r1)
          o2 (:transaction-ordering r2)
          ;; re-issue o2 under a different family conflict-key but same state roots,
          ;; then link it as the successor of o1
          other-family (ordering/transaction-ordering
                        (assoc (ordering/unsigned-ordering-projection o2)
                               :transaction/conflict-key [:resubmission-family "sha256:OTHER"]
                               :transaction/previous-transaction-hash
                               (:transaction-ordering/hash o1)))
          result (ordering/verify-ordering-chain [o1 other-family])]
      (is (not (:valid? result)))
      (is (some #(re-find #"conflict-key differs" %) (:errors result))))))

;; ── transaction-ordering.v2: change identity & input-root ──────────────────────

(deftest transaction-ordering-v2
  (let [s (store/new-resubmission-store family)
        r1 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
        r2 (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))))
        o1 (:transaction-ordering r1)
        o2 (:transaction-ordering r2)
        v1-hashes (fn []
                    ;; v1 ordering over the same v1 field set, derived from o1's
                    ;; projection with v2-only fields stripped and schema reset.
                    (ordering/transaction-ordering
                     (assoc (ordering/unsigned-ordering-projection-v1 o1)
                            :transaction-ordering/schema ordering/ordering-schema
                            :transaction/input-root nil
                            :transaction/change-identity nil)))]
    (testing "store commits v2 orderings with a derived change-identity"
      (is (= ordering/ordering-v2-schema (:transaction-ordering/schema o1)))
      (is (some? (:transaction/input-root o1)))
      (is (some? (:transaction/change-identity o1)))
      (is (= (:transaction/change-identity o1)
             (ordering/change-identity-hash o1))
          "change-identity is the recomputed domain hash of the canonical basis")
      (is (true? (:valid? (ordering/verify-ordering o1)))
          "self-hash + change-identity recompute both hold"))
    (testing "a v2 ordering hash diverges from the equivalent v1 hash"
      (let [v1 (v1-hashes)]
        (is (not= (:transaction-ordering/hash o1) (:transaction-ordering/hash v1))
            "v2 schema + input-root + change-identity are inside the v2 identity hash")
        (is (= ordering/ordering-schema (:transaction-ordering/schema v1)))
        (is (true? (:valid? (ordering/verify-ordering v1)))
            "the v1 projection remains a valid v1 ordering")))
    (testing "change-identity is positional-invariant across resequencing"
      (let [relocated (ordering/transaction-ordering
                       (assoc (ordering/unsigned-ordering-projection-v2 o1)
                              :transaction/commit-index 999
                              :transaction/previous-transaction-hash
                              "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                              :transaction/state-before-root
                              "sha256:1111111111111111111111111111111111111111111111111111111111111111"
                              :transaction/state-after-root
                              "sha256:2222222222222222222222222222222222222222222222222222222222222222"))]
        (is (= (:transaction/change-identity o1)
               (:transaction/change-identity relocated))
            "changing commit-index / previous-hash / state roots must not move change-identity")
        (is (not= (:transaction-ordering/hash o1)
                  (:transaction-ordering/hash relocated))
            "a relocated change still yields a different ordering hash")
        (is (true? (:valid? (ordering/verify-ordering relocated))))))
    (testing "change-identity excludes state-before-root (regression for spec §4.2)"
      ;; The change-identity basis is {scope, conflict-key, action, input-root} —
      ;; state-before-root is NOT part of the computation. Mutating only
      ;; state-before-root (leaving input-root unchanged) must preserve the
      ;; change-identity while changing the ordering hash.
      (let [o2 (ordering/transaction-ordering
                (assoc (ordering/unsigned-ordering-projection-v2 o1)
                       :transaction/state-before-root
                       "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))]
        (is (= (:transaction/change-identity o1)
               (:transaction/change-identity o2))
            "state-before-root does not participate in change-identity")
        (is (not= (:transaction-ordering/hash o1)
                  (:transaction-ordering/hash o2))
            "ordering hash still changes because state-before-root is in the ordering projection")
        (is (true? (:valid? (ordering/verify-ordering o2)))
            "recomputed ordering with mutated state-before-root verifies end-to-end")))
    (testing "input-root excludes concurrency / chain-position guards"
      (let [base (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")
            with-version (assoc-in base [:transaction/input :expected-chain-version] 1618)
            with-seq (assoc-in base [:transaction/input :sequence] 7)
            base-root (transition/command-input-root (:transaction/action base)
                                                     (:transaction/input base))]
        (is (= base-root
               (transition/command-input-root (:transaction/action with-version)
                                              (:transaction/input with-version)))
            "expected-chain-version (a concurrency guard) is absent from input-root")
        (is (= base-root
               (transition/command-input-root (:transaction/action with-seq)
                                              (:transaction/input with-seq)))
            "sequence (a chain-position guard) is absent from input-root"))
      (let [base (disposition-cmd "sha256:R1" :withdrawn)
            with-head (assoc-in base [:transaction/input :expected-disposition-head] "sha256:HEAD")
            with-version (assoc-in base [:transaction/input :expected-chain-version] 1618)]
        (is (= (transition/command-input-root (:transaction/action base) (:transaction/input base))
               (transition/command-input-root (:transaction/action with-head)
                                              (:transaction/input with-head)))
            "expected-disposition-head (a concurrency guard) is absent from input-root")
        (is (= (transition/command-input-root (:transaction/action base) (:transaction/input base))
               (transition/command-input-root (:transaction/action with-version)
                                              (:transaction/input with-version)))
            "expected-chain-version (a concurrency guard) is absent from disposition input-root")))
    (testing "v2 verify rejects a forged change-identity"
      (let [forged (assoc o1 :transaction/change-identity "sha256:0000000000000000000000000000000000000000000000000000000000000000")]
        (is (= :change-identity-mismatch (:reason (ordering/verify-ordering forged))))))
    (testing "v2 verify rejects an input-root that detaches from the change-identity"
      (let [tampered (assoc o1 :transaction/input-root "sha256:0000000000000000000000000000000000000000000000000000000000000000")]
        (is (= :change-identity-mismatch (:reason (ordering/verify-ordering tampered))))))
    (testing "v2 verify rejects a missing input-root"
      (is (= :missing-required-fields
             (:reason (ordering/verify-ordering (assoc o1 :transaction/input-root nil))))))
    (testing "v2 verify rejects a missing change-identity"
      (is (= :missing-required-fields
             (:reason (ordering/verify-ordering (assoc o1 :transaction/change-identity nil))))))
    (testing "a v2 chain carries positional change-identity and verifies end-to-end"
      (let [chain [o1 o2]
            chain-v (ordering/verify-ordering-chain chain)]
        (is (true? (:valid? chain-v)))
        (is (not= (:transaction/change-identity o1) (:transaction/change-identity o2))
            "distinct changes (different parents/basis) yield distinct change-identity")
        ;; per-record change-identity recompute catches a forged commit mid-chain
        (let [tampered-chain [o1 (assoc o2 :transaction/change-identity
                                        "sha256:0000000000000000000000000000000000000000000000000000000000000000")]
              bad (ordering/verify-ordering-chain tampered-chain)]
          (is (false? (:valid? bad)))
          (is (some #(re-find #"change-identity" %) (:errors bad))))))))

;; ── trace equivalence: reference transition vs persistent store ─────────────

(defn- reference-run
  "Apply a seq of commands against the pure transition (no store)."
  [state commands]
  (reduce (fn [{:keys [state steps]} cmd]
            (let [r (transition/apply-action state cmd)
                  next (if (= :committed (:status r)) (:state r) state)]
              {:state next :steps (conj steps r)}))
          {:state state :steps []}
          commands))

(defn- store-run
  "Apply a seq of commands through the store."
  [store commands]
  (reduce (fn [{:keys [steps]} cmd]
            (let [r (protocol/transact! store nil nil
                                        (fn [st] (transition/apply-action st cmd)))]
              {:steps (conj steps r)}))
          {:steps []}
          commands))

(defn- public-signature
  "The externally observable transition result (status + public-result)."
  [r]
  (select-keys r [:status :reason :public-result]))

(deftest trace-equivalence-reference-vs-store
  (let [traces
        [;; valid exact retry
         [(admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")
          (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")]
         ;; idempotent replay after admit
         [(admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")
          (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")
          (admit-cmd :child "sha256:R3" :seq 3 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")]
         ;; duplicate content before stale-head rejection
         [(admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")
          (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")
          (admit-cmd :child "sha256:R3" :seq 3 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L3" :idem "sha256:I3")]
         ;; disposition then admission blocked
         [(admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")
          (disposition-cmd "sha256:R1" "sha256:D1" :withdrawn)
          (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")]
         ;; cycle
         [(admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")
          (admit-cmd :child "sha256:R1" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")]]
        ref (reference-run (transition/empty-state family) traces)
        store (store/new-resubmission-store family)
        pers (store-run store traces)]
    (is (= (mapv public-signature (:steps ref))
           (mapv public-signature (:steps pers))))
    (is (= (transition/state-root (:state ref))
           (transition/state-root (store/state-of store))))
    (is (= (count (:steps ref)) (count (:steps pers))))))

(deftest schedule-equivalence-concurrent-children
  (testing "every concurrent outcome is explainable by one valid serial order"
    (let [s (store/new-resubmission-store family)
          _ (protocol/transact! s nil nil (fn [st] (transition/apply-action st (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1"))))
          futures (doall
                   (map (fn [i]
                          (future
                            (protocol/transact! s nil nil
                                                (fn [st]
                                                  (transition/apply-action
                                                   st (admit-cmd :child (str "sha256:C" i) :seq 2 :parent "sha256:R1"
                                                                 :basis (str "sha256:BC" i)
                                                                 :link (str "sha256:LC" i)
                                                                 :idem (str "sha256:IC" i)))))))
                        (range 6)))
          results (mapv deref futures)
          admitted (filter #(= :committed (:status %)) results)
          losers (remove #(= :committed (:status %)) results)]
      (is (= 1 (count admitted)))
      (let [winner (:public-result (first admitted))]
        (is (= (store/chain-head s) (:chain-head winner))))
      (testing "every loser is rejected because the winner advanced the head first"
        (is (every? #(= :parent-not-current-head (:reason %)) losers))))))

;; ── state-after stabilization regression tests ────────────────────────────────

(deftest v2-ordering-domain-registered
  (testing "prf-transaction-ordering-v2 is registered in domain-tags"
    (is (contains? hc/domain-tags :prf-transaction-ordering-v2))
    (is (= "prf.transaction-ordering.v2"
           (get hc/domain-tags :prf-transaction-ordering-v2)))))

(deftest registered-keyword-domains-produce-same-hashes-as-literal-strings
  (testing "registered keyword domain tags resolve to identical bytes as their
            literal domain strings, so hashes are unchanged"
    (let [state-body {:chain/family-id family
                      :chain/version 0
                      :transaction/commit-index 0
                      :chain/head nil
                      :chain/successor-by-parent {}
                      :chain/effective-disposition-by-receipt {}
                      :chain/disposition-head-by-receipt {}
                      :chain/idempotency-index {}
                      :chain/content-index {}}
          effects-body [{:parent "sha256:PARENT" :child "sha256:CHILD"}]]
      (are [kw str body] (= (hc/domain-hash kw body)
                            (hc/domain-hash str body))
        :prf-resubmission-chain-state-v1    "prf.resubmission-chain-state.v1"    state-body
        :prf-transaction-effects-v1         "prf.transaction-effects.v1"         effects-body
        :prf-transaction-input-v1           "prf.transaction-input.v1"         state-body
        :prf-transaction-ordering-v1        "prf.transaction-ordering.v1"        effects-body
        :prf-transaction-ordering-v2        "prf.transaction-ordering.v2"        effects-body))))

(deftest resubmission-state-root-is-pinned
  (testing "the empty resubmission chain state root is stable"
    (let [s (transition/empty-state family)
          root (transition/state-root s)]
      (is (= "sha256:53e5ae09087f3733a54110c9a00f4cb227894f18f1384b7a8d88a929e5b66ffb"
             root)))))

(deftest resubmission-effects-root-is-pinned
  (testing "the effects root for a fixed effects vector is stable"
    (let [effects [{:effect/type :chain-successor
                    :parent "sha256:PARENT"
                    :child "sha256:CHILD"}]
          root (transition/effects-root effects)]
      (is (= "sha256:50f4e36f29a6c8d26b99c93d5aa0f76890cfb0a0c8bf448a75f76f95bbdc931c"
             root)))))

(deftest disposition-status-absent-vs-empty-distinct
  (testing "absent vs present-but-empty :chain/disposition-status-by-receipt
            produce distinct state roots (v1 behavior preserved)"
    (let [s-absent (transition/empty-state family)
          s-empty (assoc s-absent :chain/disposition-status-by-receipt {})
          root-absent (transition/state-root s-absent)
          root-empty (transition/state-root s-empty)]
      (is (not= root-absent root-empty)
          "absent and empty disposition-status must remain distinct")
      (is (= "sha256:53e5ae09087f3733a54110c9a00f4cb227894f18f1384b7a8d88a929e5b66ffb"
             root-absent))
      (is (= "sha256:5ad475061e36b69e3f1b9f365617dc060c41a4a619b0b891389946d1175c7c12"
             root-empty)))))

(deftest chain-state-projection-excludes-unknown-fields
  (testing "only projected fields participate in the state root; unknown
            extra keys are ignored"
    (let [s (transition/empty-state family)
          s-polluted (assoc s :transaction/last-hash "sha256:DEAD"
                            :chain/attempt-receipts {"sha256:DEAD" {}}
                            :chain/disposition-public-hex "00112233")
          root-clean (transition/state-root s)
          root-polluted (transition/state-root s-polluted)]
      (is (= root-clean root-polluted)
          "extra non-projected keys must not affect the state root"))))

;; ── committed conformance fixture ───────────────────────────────────────────────

(defn- load-fixture
  "Load a committed resubmission transition conformance fixture by fixture-id."
  ([]
   (load-fixture "resubmission-transition"))
  ([fixture-name]
   (edn/read-string
    (slurp (str "etc/conformance/fixtures/" fixture-name ".edn")))))

(deftest conformance-fixture-validates-state-after-derivation
  (testing "the committed genesis-admit fixture reproduces every pinned root and
            canonical-byte hex"
    (let [fx (load-fixture "resubmission-transition-v1")
          s-before (:state-before fx)
          cmd (:command fx)
          ctx (:semantic-context fx)
          s-before-proj (:state-before-projection fx)
          proj-fn @#'transition/chain-state-projection

          result (transition/apply-action s-before cmd)

          s-after (:state result)
          effects (:effects result)
          ordering-input (:ordering-input result)

          ;; reconstruct ordering as the store does
          full-ordering-input (merge ordering-input ctx)
          ordering-record (ordering/transaction-ordering full-ordering-input)
          ordering-proj (ordering/unsigned-ordering-projection ordering-record)
          input-root-computed
          (transition/command-input-root
           (:transaction/action cmd) (:transaction/input cmd))]

      ;; --- transition outcome ---
      (is (= (:status (:transition-outcome fx)) (:status result))
          "fixture status must match transition result")
      (is (= (:public-result (:transition-outcome fx)) (:public-result result))
          "fixture public-result must match transition result")

      ;; --- state-after (complete) ---
      (is (= (:state-after fx) s-after)
          "fixture state-after must match transition result state")

      ;; --- effects ---
      (is (= (:effects fx) effects)
          "fixture effects must match transition result effects")
      (is (= (:effects-root fx) (transition/effects-root effects))
          "effects-root must match fixture")
      (is (= (:effects-canonical-bytes-hex fx)
             (hc/canonical-bytes-hex (vec effects)))
          "effects canonical bytes must match fixture")

      ;; --- ordering input ---
      (is (= (:ordering-input fx) ordering-input)
          "ordering-input must match fixture")

      ;; --- input root ---
      (is (= (:input-root fx) input-root-computed)
          "input-root must match fixture")

      ;; --- state-before projection + root ---
      (is (= s-before-proj (proj-fn s-before))
          "state-before projection must match fixture")
      (is (= (:state-before-projection fx) (proj-fn s-before))
          "recomputed state-before projection matches fixture")
      (is (= (:state-before-root fx) (transition/state-root s-before))
          "state-before root must match fixture")
      (is (= (:state-before-canonical-bytes-hex fx)
             (hc/canonical-bytes-hex (proj-fn s-before)))
          "state-before canonical bytes must match fixture")

      ;; --- state-after projection + root ---
      (is (= (:state-after-projection fx) (proj-fn s-after))
          "state-after projection must match fixture")
      (is (= (:state-after-root fx) (transition/state-root s-after))
          "state-after root must match fixture")
      (is (= (:state-after-canonical-bytes-hex fx)
             (hc/canonical-bytes-hex (proj-fn s-after)))
          "state-after canonical bytes must match fixture")

      ;; --- change identity ---
      (is (= (:change-identity fx)
             (ordering/change-identity-hash full-ordering-input))
          "change identity must match fixture")

      ;; --- ordering v2 projection + root ---
      (is (= (:ordering-v2-projection fx) ordering-proj)
          "ordering v2 projection must match fixture")
      (is (= (:ordering-canonical-bytes-hex fx)
             (hc/canonical-bytes-hex ordering-proj))
          "ordering canonical bytes must match fixture")
      (is (= (:ordering-root fx) (:transaction-ordering/hash ordering-record))
          "ordering root must match fixture"))))

(deftest conformance-fixture-rejection-duplicate-content-validates
  (testing "the committed duplicate-content-rejection fixture reproduces the pinned
            rejection outcome and unchanged state"
    (let [fx (load-fixture "resubmission-transition-rejection-v1")
          s-before (:state-before fx)
          cmd (:command fx)

          result (transition/apply-action s-before cmd)]
      (is (= :rejected (:status result))
          "rejection fixture must produce :rejected")
      (is (= (:reason (:transition-outcome fx)) (:reason result))
          "rejection reason must match fixture")
      (is (= (:public-result (:transition-outcome fx)) (:public-result result))
          "rejection public-result must match fixture")
      (is (= (:state-before-root fx) (transition/state-root s-before))
          "state-before-root must match fixture")
      (is (nil? (:effects result))
          "rejected transition must produce no effects")
      (is (nil? (:ordering-input result))
          "rejected transition must produce no ordering-input")
      (is (= (:state-before-root fx)
             (:state-before-root fx))
          "rejection leaves state unchanged (state-before-root == state-after-root)"))))

(deftest conformance-fixture-disposition-final-validates
  (testing "the committed disposition-final fixture reproduces every pinned root and
            canonical-byte hex"
    (let [fx (load-fixture "resubmission-transition-disposition-v1")
          s-before (:state-before fx)
          cmd (:command fx)
          ctx (:semantic-context fx)
          proj-fn @#'transition/chain-state-projection

          result (transition/apply-action s-before cmd)

          s-after (:state result)
          effects (:effects result)
          ordering-input (:ordering-input result)
          input-root-computed
          (transition/command-input-root
           (:transaction/action cmd) (:transaction/input cmd))

          full-ordering-input (merge ordering-input ctx)
          ordering-record (ordering/transaction-ordering full-ordering-input)
          ordering-proj (ordering/unsigned-ordering-projection ordering-record)]
      (is (= :committed (:status result))
          "disposition fixture must produce :committed")
      (is (= (:public-result (:transition-outcome fx)) (:public-result result))
          "public-result must match fixture")

      (is (= (:state-before fx) s-before)
          "state-before must match fixture")
      (is (= (:state-before-root fx) (transition/state-root s-before))
          "state-before-root must match fixture")
      (is (= (:state-before-canonical-bytes-hex fx)
             (hc/canonical-bytes-hex (proj-fn s-before)))
          "state-before canonical bytes must match fixture")

      (is (= (:state-after fx) s-after)
          "state-after must match fixture")
      (is (= (:state-after-root fx) (transition/state-root s-after))
          "state-after-root must match fixture")
      (is (= (:state-after-canonical-bytes-hex fx)
             (hc/canonical-bytes-hex (proj-fn s-after)))
          "state-after canonical bytes must match fixture")
      (is (contains? s-after :chain/disposition-status-by-receipt)
          "state-after must contain disposition-status-by-receipt")
      (is (= {:chain/disposition-status-by-receipt {"sha256:R1" :final}}
             (select-keys s-after [:chain/disposition-status-by-receipt]))
          "disposition-status-by-receipt must match fixture")

      (is (= (:effects fx) effects)
          "effects must match fixture")
      (is (= (:effects-root fx) (transition/effects-root effects))
          "effects-root must match fixture")
      (is (= (:effects-canonical-bytes-hex fx)
             (hc/canonical-bytes-hex (vec effects)))
          "effects canonical bytes must match fixture")

      (is (= (:input-root fx) input-root-computed)
          "input-root must match fixture")

      (is (= (:ordering-input fx) ordering-input)
          "ordering-input must match fixture")

      (is (= (:ordering-v2-projection fx) ordering-proj)
          "ordering v2 projection must match fixture")
      (is (= (:ordering-canonical-bytes-hex fx)
             (hc/canonical-bytes-hex ordering-proj))
          "ordering canonical bytes must match fixture")
      (is (= (:ordering-root fx) (:transaction-ordering/hash ordering-record))
          "ordering root must match fixture")
      (is (= (:change-identity fx)
             (ordering/change-identity-hash full-ordering-input))
          "change-identity must match fixture"))))

(deftest conformance-fixture-disposition-signature-verifies
  (testing "the disposition artifact in the fixture verifies against the pinned public key"
    (let [fx (load-fixture "resubmission-transition-disposition-v1")
          artifact (:disposition-artifact fx)
          pub-hex (:public-key-hex (:fixture/disposition-authority fx))]
      (is (some? pub-hex)
          "fixture must declare a disposition authority public key")
      (is (some? artifact)
          "fixture must contain a disposition artifact")
      (let [verification (disposition/verify-disposition artifact pub-hex)]
        (is (true? (:valid? verification))
            "disposition signature must verify against the pinned public key")
        (is (= :ok (:reason verification))
            "disposition must have a valid schema and status")))))
