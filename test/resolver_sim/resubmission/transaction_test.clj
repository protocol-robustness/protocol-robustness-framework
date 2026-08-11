(ns resolver-sim.resubmission.transaction-test
  "Tests for the transaction-ordering layer: the generic ordering primitive,
   the pure resubmission transition (pinned rejection precedence), the in-memory
   TransactionStore (CAS + ordering evidence), and reference-vs-store trace
   equivalence."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.transaction.protocol :as protocol]))

(def family "sha256:FAM")

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
  [attempt disposition-hash status & {:keys [expected-disposition-head expected-version]}]
  {:transaction/action :prf.resubmission/apply-disposition
   :transaction/input
   {:attempt-receipt-hash attempt
    :disposition-artifact-hash disposition-hash
    :disposition-status status
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
  (testing "transplant detection (precedence 4)"
    (let [s0 (transition/empty-state family)
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          s2 (:state (transition/apply-action s1 (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2")))
          tx (transition/apply-action s2 (admit-cmd :child "sha256:R3" :seq 3 :parent "sha256:R2" :basis "sha256:B2" :link "sha256:L3" :idem "sha256:I3"))]
      (is (= :idempotency-key-rebound (:reason tx)))))
  (testing "disposition eligibility gates admission (precedence 5)"
    (let [s0 (transition/empty-state family)
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          sd (:state (transition/apply-action s1 (disposition-cmd "sha256:R1" "sha256:D1" :withdrawn)))
          blocked (transition/apply-action sd (admit-cmd :child "sha256:R2" :seq 2 :parent "sha256:R1" :basis "sha256:B2" :link "sha256:L2" :idem "sha256:I2"))]
      (is (= :rejected (:status blocked)))
      (is (= :parent-rejection-not-final (:reason blocked)))))
  (testing "apply-disposition increments commit index WITHOUT a new resubmission sequence"
    (let [s0 (transition/empty-state family)
          s1 (:state (transition/apply-action s0 (admit-cmd :child "sha256:R1" :seq 1 :basis "sha256:B1" :link "sha256:L1" :idem "sha256:I1")))
          sd (:state (transition/apply-action s1 (disposition-cmd "sha256:R1" "sha256:D1" :superseded)))]
      (is (= 2 (:transaction/commit-index sd)))
      (is (= 1 (count (keys (:chain/successor-by-parent sd)))))
      (is (= :superseded (get-in sd [:chain/effective-disposition-by-receipt "sha256:R1"])))
      (is (= "sha256:R1" (:chain/head sd)))))
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
