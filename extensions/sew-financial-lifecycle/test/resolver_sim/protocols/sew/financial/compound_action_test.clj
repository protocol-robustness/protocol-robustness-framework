(ns resolver-sim.protocols.sew.financial.compound-action-test
  "Compound action (P4b batch) tests.

   A compound action is ONE canonical action whose identity commits, through the
   generic canonical consecutive-sequence machinery, to an ordered set of
   canonical request/action member bindings plus all-or-nothing lifecycle
   semantics. A lifecycle decision always authorizes exactly one action root —
   the compound root — so the authorization gate stays root-equality; what is
   new is the canonical member binding, the sequence root, and the consecutive
   execution lineage.

   Covers: member canonicalization (position-bound request/action pairing),
   sequence-root vs compound-root separation, normalization fixed point,
   aggregate effect declaration, per-member policy findings, the compound
   decision, sequential execution with consecutive lineage, substitution
   resistance, per-member effect-contract enforcement, all-or-nothing failure,
   single-use/idempotency, and consumption of the member request set."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.financial.lifecycle :as fl]
            [resolver-sim.protocols.sew.types :as t]))

(def policy (fl/default-policy))

(defn- solvent-world []
  (-> (t/empty-world 1000)
      (assoc :total-held {:USDC 1000})
      (assoc-in [:escrow-transfers 0]
                {:token :USDC :amount-after-fee 1000 :escrow-state :pending})))

(defn- insolvent-world []
  (-> (solvent-world)
      (assoc-in [:slash-credit-liabilities "0xRes0"] 500)))

(defn- assess [world] (solv/classify-solvency world))

(defn- chain-for [world]
  (let [r (fl/apply-verified :protocol-a [] (assess world) {})]
    (get-in r [:state :episode/events])))

(defn- recaption
  "allow-recapitalization member action (asset-inflow, recovery-operation)."
  [amount]
  {:member/request-id (str "recap-" amount)
   :member/action {:action/type :allow-recapitalization
                   :action/params {:amount amount}}})

(defn- withdraw
  "withdraw member action (asset-outflow, risk-increasing)."
  [amount]
  {:member/request-id (str "withdraw-" amount)
   :member/action {:action/type :withdraw
                   :action/params {:amount amount}}})

(defn- compound
  "Raw compound input bound to the given pre-state."
  [pre-state members & {:keys [atomicity]}]
  {:action/type :action/compound
   :compound/members members
   :compound/pre-state-root (fl/domain-state-root pre-state)
   :compound/atomicity (or atomicity :all-or-nothing)})

(defn- member-exec
  "Deterministic member executor honoring the declared action params."
  [state action]
  (let [action-type (:action/type action)
        params (:action/params action)]
    (case action-type
      :allow-recapitalization (update-in state [:total-held :USDC]
                                         + (long (:amount params)))
      :withdraw (update-in state [:total-held :USDC]
                           - (long (:amount params)))
      (throw (ex-info "unhandled member action" {:action/type action-type})))))

(defn- exec-with-amounts
  "A member executor that OVERRIDES the committed params — used to demonstrate
   that the SAME compound (same committed roots) can realize different
   intermediate states, which the lineage root then discriminates."
  [recap-amount withdraw-amount]
  (fn [state action]
    (case (:action/type action)
      :allow-recapitalization (update-in state [:total-held :USDC] + recap-amount)
      :withdraw (update-in state [:total-held :USDC] - withdraw-amount))))

(defn- new-store
  "A model of the authoritative store boundary: the last authoritative state,
   the consumed-request ledger, and the persisted transition set. A real store
   must satisfy exactly this commit discipline — see the store-boundary tests."
  ([]
   (new-store nil))
  ([authoritative-state]
   (atom {:authoritative-state authoritative-state
          :consumed-ids #{}
          :transitions []})))

(defn- atomic-commit!
  "The authoritative commit discipline: a store is mutated ONLY when the gate
   returns :ok? true. A failed compound leaves the store byte-for-byte intact."
  [store result]
  (when (:ok? result)
    (swap! store (fn [s]
                   (-> s
                       (assoc :authoritative-state (:post-state result))
                       (update :consumed-ids into (:consumed-ids result))
                       (update :transitions conj (:transition result)))))))

;; ── Member canonicalization ─────────────────────────────────────────────────

(deftest member-binding-is-position-bound
  (let [a (fl/compound-action-member {:index 0
                                      :request-root (fl/request-id-root "r1")
                                      :action-root (fl/action-root {:action/type :withdraw :action/params {:amount 100}})})
        b (fl/compound-action-member {:index 1
                                      :request-root (fl/request-id-root "r2")
                                      :action-root (fl/action-root {:action/type :withdraw :action/params {:amount 200}})})
        same (fl/compound-action-member {:index 0
                                         :request-root (fl/request-id-root "r1")
                                         :action-root (fl/action-root {:action/type :withdraw :action/params {:amount 100}})})
        moved (fl/compound-action-member {:index 2
                                          :request-root (fl/request-id-root "r1")
                                          :action-root (fl/action-root {:action/type :withdraw :action/params {:amount 100}})})]
    (is (= (:compound-member/root a) (:compound-member/root same))
        "same position/request/action → same member root")
    (is (not= (:compound-member/root a) (:compound-member/root b))
        "different request/action → different member root")
    (is (not= (:compound-member/root a) (:compound-member/root moved))
        "position is part of the member identity (same request/action at a different index)")
    (is (fl/valid-compound-member? a))
    (is (fl/valid-compound-member? b))))

(deftest request-id-root-binds-request-identity
  (let [r1 (fl/request-id-root "req-1")
        r2 (fl/request-id-root "req-2")]
    (is (= 64 (count r1)))
    (is (not= r1 r2) "different request ids → different roots")
    (is (= (fl/request-id-root "req-1") r1) "deterministic")))

(deftest member-sequence-root-commits-order
  (let [mk (fn [actions]
             (fl/compound-member-sequence-root
              (mapv (fn [i a] (fl/compound-action-member
                               {:index i
                                :request-root (fl/request-id-root (str "r" i))
                                :action-root (fl/action-root a)}))
                    (range) actions)))
        seq-a (mk [{:action/type :withdraw :action/params {:amount 100}}
                   {:action/type :allow-recapitalization :action/params {:amount 50}}])
        seq-b (mk [{:action/type :allow-recapitalization :action/params {:amount 50}}
                   {:action/type :withdraw :action/params {:amount 100}}])]
    (is (not= seq-a seq-b)
        "the SAME members in different order → different sequence root")
    (is (= (mk [{:action/type :withdraw :action/params {:amount 100}}
                {:action/type :allow-recapitalization :action/params {:amount 50}}])
           seq-a)
        "same members same order → same sequence root")))

;; ── build-compound-action: two identities ───────────────────────────────────

(deftest build-compound-action-commits-authorization-semantics
  (let [members [(fl/compound-action-member {:index 0
                                             :request-root (fl/request-id-root "r0")
                                             :action-root (fl/action-root {:action/type :withdraw})})
                 (fl/compound-action-member {:index 1
                                             :request-root (fl/request-id-root "r1")
                                             :action-root (fl/action-root {:action/type :settle})})]
        c (fl/build-compound-action {:members members
                                     :pre-state-root (fl/domain-state-root (solvent-world))
                                     :atomicity :all-or-nothing})]
    (is (= :action/compound (:action/type c)))
    (is (= 2 (:compound/member-count c)))
    (is (not= (:compound/member-sequence-root c) (:action/root c))
        "sequence-root (\"these members in this order\") is distinct from the
         compound-action-root (\"this sequence under authorization semantics\")")
    (is (= 64 (count (:compound/member-sequence-root c))))
    (is (= 64 (count (:action/root c))))))

(deftest compound-root-commits-pre-state-and-atomicity
  (let [members (fn [] [(fl/compound-action-member {:index 0
                                                    :request-root (fl/request-id-root "r0")
                                                    :action-root (fl/action-root :withdraw)})])
        s1 (fl/domain-state-root (solvent-world))
        s2 (fl/domain-state-root (-> (solvent-world) (assoc :total-held {:USDC 1})))
        base (fn [pre atomicity] (fl/build-compound-action {:members (members) :pre-state-root pre :atomicity atomicity}))]
    (is (not= (:action/root (base s1 :all-or-nothing))
              (:action/root (base s2 :all-or-nothing)))
        "different pre-state → different compound root")
    (is (not= (:action/root (base s1 :all-or-nothing))
              (:action/root (base s1 :best-effort)))
        "different atomicity semantics → different compound root")))

(deftest build-compound-action-rejects-malformed-members
  (is (thrown? clojure.lang.ExceptionInfo
               (fl/build-compound-action {:members [] :pre-state-root "x"}))
      "empty member list rejected")
  (is (thrown? clojure.lang.ExceptionInfo
               (fl/build-compound-action
                {:members [(fl/compound-action-member {:index 1
                                                       :request-root (fl/request-id-root "r")
                                                       :action-root (fl/action-root :withdraw)})]
                 :pre-state-root "x"}))
      "non-contiguous indices rejected"))

;; ── Normalization fixed point ───────────────────────────────────────────────

(deftest normalize-compound-action-is-a-true-fixed-point
  (let [raw (compound (solvent-world) [(recaption 100) (withdraw 50)])
        once (fl/normalize-action raw)
        twice (fl/normalize-action once)]
    (is (= :action/compound (:action/type once)))
    (is (fl/compound-action? raw))
    (is (fl/canonical-action? once) "normalized compound is a TRUE fixed point")
    (is (= once twice) "re-normalizing a canonical compound preserves identity")
    (is (= (:action/root once) (:action/root twice))
        "the committed compound root is stable across re-normalization")
    (is (= #{:asset-inflow :asset-outflow :recovery-operation :risk-increasing}
           (:action/effects once))
        "aggregate declared effects = union of member effects")))

(deftest compound-root-discriminates-member-content-and-order
  (let [pre (solvent-world)
        base (compound pre [(recaption 100) (withdraw 50)])
        bigger (compound pre [(recaption 100) (withdraw 200)])
        reordered (compound pre [(withdraw 50) (recaption 100)])
        other-request (-> (compound pre [(recaption 100) (withdraw 50)])
                          (assoc-in [:compound/members 0 :member/request-id] "different"))]
    (is (not= (fl/action-root base) (fl/action-root bigger))
        "economically different member → different compound root")
    (is (not= (fl/action-root base) (fl/action-root reordered))
        "same members, different order → different compound root")
    (is (not= (fl/action-root base) (fl/action-root other-request))
        "member request identity is committed, not dropped")))

(deftest compound-requires-nonempty-members
  (is (thrown? clojure.lang.ExceptionInfo
               (fl/normalize-action {:action/type :action/compound
                                     :compound/members []}))
      "a compound with no members is rejected"))

(deftest aggregate-effects-drops-no-economic-effect-when-mixed
  (let [c (fl/normalize-action
           (compound (solvent-world)
                     [{:member/request-id "none"
                       :member/action {:action/type :settle
                                       :action/effects #{:no-economic-effect}}}
                      (withdraw 50)]))]
    (is (= #{:asset-outflow :risk-increasing} (:action/effects c))
        ":no-economic-effect is a per-member statement and is dropped from a
         mixed aggregate so the union remains a valid declaration")))

;; ── Policy: per-member evaluation ───────────────────────────────────────────

(deftest compound-permitted-iff-every-member-permitted
  (let [pre (solvent-world)
        chain (chain-for pre)
        d (fl/response-decision :protocol-a chain (assess pre) policy
                                (compound pre [(recaption 100) (withdraw 50)])
                                pre {:request/id "compound-1"})]
    (is (= :permit (:decision d)))
    (is (contains? (:reasons d) [:compound/member 0 :action-type-permitted]))
    (is (contains? (:reasons d) [:compound/member 1 :action-type-permitted])
        "both members are individually evaluated under the same lifecycle state")
    (is (contains? (:reasons d) [:compound/member 0 :effect-permitted :asset-inflow]))
    (is (contains? (:reasons d) [:compound/member 1 :effect-permitted :asset-outflow]))))

(deftest compound-denied-when-any-member-denied
  (let [pre (insolvent-world)
        chain (chain-for pre)
        d (fl/response-decision :protocol-a chain (assess pre) policy
                                (compound pre [(recaption 100) (withdraw 50)])
                                pre {:request/id "compound-1"})]
    (is (= :deny (:decision d))
        "atomicity extends to authorization: one denied member denies the compound")
    (is (contains? (:reasons d) [:compound/member 0 :action-type-permitted])
        "the permitted member is still reported")
    (is (contains? (:reasons d) [:compound/member 1 :action-type-not-permitted :withdraw])
        "the denied member names the offending action type")
    (is (not (fl/permitted-action? :protocol-a chain (assess pre) policy
                                   (compound pre [(recaption 100) (withdraw 50)]) pre)))))

;; ── Decision commits authorization identity, not lineage ────────────────────

(deftest decision-commits-compound-not-lineage
  (let [pre (solvent-world)
        chain (chain-for pre)
        compound* (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy compound*
                                pre {:request/id "compound-1"})]
    (is (= :permit (:decision d)))
    (is (= (fl/action-root compound*) (:action/root d))
        "the decision binds the compound-action-root (authorization identity)")
    (is (string? (:action/sequence-root d)))
    (is (= 2 (:action/member-count d)))
    (is (nil? (:execution-lineage-root d))
        "the lineage does not exist at decision time and is NOT committed by it")
    (is (= (fl/request-hash {:request/id "compound-1"
                             :action/root (fl/action-root compound*)
                             :subject :protocol-a
                             :pre-state/root (fl/domain-state-root pre)})
           (:request/hash d))
        "the committed request hash uses the compound action root")))

;; ── Sequential execution + consecutive lineage ──────────────────────────────

(deftest authorize-and-execute-compound-produces-consecutive-lineage
  (let [pre (solvent-world)
        chain (chain-for pre)
        c (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy c
                                pre {:request/id "compound-1"})
        head-root (:lifecycle-head-root d)
        result (fl/authorize-and-execute-compound
                d c "compound-1" pre head-root :protocol-a #{} member-exec)]
    (is (:ok? result) "sequential member application succeeds")
    (is (= 1050 (get-in (:post-state result) [:total-held :USDC]))
        "state₀(1000) --recap+100--> state₁(1100) --withdraw-50--> state₃(1050)")
    (let [{:keys [lineage transition]} result]
      (is (= 2 (:lineage/step-count lineage)))
      (is (= (fl/domain-state-root pre)
             (get-in lineage [:lineage/steps 0 :state-before/root]))
          "step 0 begins at the compound pre-state")
      (is (= (get-in lineage [:lineage/steps 0 :state-after/root])
             (get-in lineage [:lineage/steps 1 :state-before/root]))
          "step 0's post-state IS step 1's pre-state (consecutive lineage)")
      (is (:consecutive? (fl/valid-execution-lineage? lineage (:compound/pre-state-root c)))
          "the consecutive-lineage invariant holds")
      (is (= (:action/root c) (:action/root transition))
          "transition binds the compound-action-root")
      (is (= (:compound/member-sequence-root c) (:action/sequence-root transition))
          "transition binds the action-sequence-root")
      (is (= (:lineage/root lineage) (:transition/execution-lineage-root transition))
          "transition binds the execution-lineage-root")
      (is (= (:decision-root d) (:response-decision/root transition))
          "execution evidence binds the response decision")
      (is (= {:USDC 50} (:economic-headroom-delta (:transition/economic-deltas transition)))
          "aggregate economic headroom: +100 − 50 = +50")
      (is (empty? (fl/undeclared-realized-effects (:action/effects d)
                                                  (:transition/realized-effects transition)))
          "observed ⊆ declared on the aggregate")
      (is (= #{"compound-1" "recap-100" "withdraw-50"} (:consumed-ids result))
          "the compound request id AND the member request set are consumed as one unit"))))

(deftest same-compound-different-intermediate-state-different-lineage
  (testing "authorization binds the compound; the lineage discriminates execution"
    (let [pre (solvent-world)
          chain (chain-for pre)
          c (compound pre [(recaption 100) (withdraw 50)])
          d (fl/response-decision :protocol-a chain (assess pre) policy c
                                  pre {:request/id "compound-1"})
          head-root (:lifecycle-head-root d)
          r1 (fl/authorize-and-execute-compound d c "compound-1" pre head-root :protocol-a #{}
                                                (exec-with-amounts 100 50))
          r2 (fl/authorize-and-execute-compound d c "compound-1" pre head-root :protocol-a #{}
                                                (exec-with-amounts 150 100))]
      (is (and (:ok? r1) (:ok? r2))
          "both executions are authorized by the SAME decision (same compound root)")
      (is (= (:post-state r1) (:post-state r2))
          "both reach the SAME final state (1050)")
      (is (not= (:lineage/root (:lineage r1)) (:lineage/root (:lineage r2)))
          "the execution-lineage-root differs when intermediate states differ")
      (is (= (fl/domain-state-root (:post-state r1)) (fl/domain-state-root (:post-state r2)))
          "the post-state roots agree — the discrimination is in the lineage, not the outcome")
      (is (not= (:transition/execution-root (:transition r1))
                (:transition/execution-root (:transition r2)))
          "the transition evidence commits the lineage and therefore differs"))))

(deftest valid-execution-lineage-detects-tampering
  (let [pre (solvent-world)
        member-actions [(fl/normalize-action {:action/type :allow-recapitalization
                                              :action/params {:amount 100}})
                        (fl/normalize-action {:action/type :withdraw
                                              :action/params {:amount 50}})]
        s1 (update-in pre [:total-held :USDC] + 100)
        s2 (update-in pre [:total-held :USDC] + 50)
        lineage (fl/build-execution-lineage member-actions [pre s1 s2])
        forged (update-in lineage [:lineage/steps 1 :state-before/root]
                           (constantly (apply str (repeat 64 \0))))
        wrong-head (fl/build-execution-lineage member-actions [(update-in pre [:total-held :USDC] + 1) s1 s2])]
    (is (:consecutive? (fl/valid-execution-lineage? lineage (fl/domain-state-root pre))))
    (is (not (:consecutive? (fl/valid-execution-lineage? forged (fl/domain-state-root pre))))
        "a lineage whose step[i+1] pre-state does not match step[i] post-state is NOT consecutive")
    (is (not (:consecutive? (fl/valid-execution-lineage? wrong-head (fl/domain-state-root pre))))
        "a lineage whose first step does not begin at the committed pre-state is NOT consecutive")))

;; ── Substitution resistance ─────────────────────────────────────────────────

(deftest compound-decision-cannot-be-substituted
  (let [pre (solvent-world)
        chain (chain-for pre)
        c (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy c
                                pre {:request/id "compound-1"})
        head-root (:lifecycle-head-root d)
        bigger (compound pre [(recaption 100) (withdraw 200)])
        reordered (compound pre [(withdraw 50) (recaption 100)])]
    (is (= :request-hash-mismatch
           (:error (fl/authorize-and-execute-compound
                    d bigger "compound-1" pre head-root :protocol-a #{} member-exec)))
        "a compound decision cannot authorize a different member set")
    (is (= :request-hash-mismatch
           (:error (fl/authorize-and-execute-compound
                    d reordered "compound-1" pre head-root :protocol-a #{} member-exec)))
        "a compound decision cannot authorize the members in a different order")
    (is (not (fl/decision-authorizes? d bigger "compound-1" pre head-root :protocol-a)))
    (is (fl/decision-authorizes? d c "compound-1" pre head-root :protocol-a))))

(deftest compound-pre-state-root-mismatch-is-rejected
  (let [pre (solvent-world)
        other (-> (solvent-world) (assoc :total-held {:USDC 1}))
        chain (chain-for pre)
        c (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy c
                                pre {:request/id "compound-1"})
        head-root (:lifecycle-head-root d)
        wrong-pre-state (assoc c :compound/pre-state-root (fl/domain-state-root other))]
    (is (= :permit (:decision d)))
    (is (= :compound-pre-state-root-mismatch
           (:error (fl/authorize-and-execute-compound
                    d wrong-pre-state "compound-1" pre head-root :protocol-a #{} member-exec)))
        "a compound whose committed pre-state does not match the execution pre-state is
         rejected before any mutation")))

(deftest single-action-decision-cannot-authorize-compound
  (let [pre (solvent-world)
        chain (chain-for pre)
        single-d (fl/response-decision :protocol-a chain (assess pre) policy
                                       {:action/type :withdraw :action/params {:amount 50}}
                                       pre {:request/id "single-1"})
        single-head (:lifecycle-head-root single-d)
        c (compound pre [(recaption 100) (withdraw 50)])
        compound-d (fl/response-decision :protocol-a chain (assess pre) policy c
                                         pre {:request/id "compound-1"})
        compound-head (:lifecycle-head-root compound-d)]
    (is (= :request-hash-mismatch
           (:error (fl/authorize-and-execute-compound
                    single-d c "compound-1" pre single-head :protocol-a #{} member-exec)))
        "a single-action decision does not bind the compound root → the committed
         request hash no longer matches")
    (is (= :compound-required
           (:error (fl/authorize-and-execute-compound
                    compound-d {:action/type :withdraw :action/params {:amount 50}}
                    "compound-1" pre compound-head :protocol-a #{} (fn [s _a] s))))
        "the compound gate only accepts compound executing actions (no special
         multi-root authorization logic)")
    (is (= :request-hash-mismatch
           (:error (fl/authorize-and-execute
                    compound-d {:action/type :withdraw :action/params {:amount 50}}
                    "compound-1" pre compound-head :protocol-a #{} (fn [s] s))))
        "a compound decision cannot authorize a single action")))

;; ── Per-member effect contract + all-or-nothing ─────────────────────────────

(deftest per-member-effect-contract-is-enforced
  (testing "a member that realizes an undeclared effect between its lineage
            states is caught before commit"
    (let [pre (solvent-world)
          chain (chain-for pre)
          c (compound pre [(recaption 100) (withdraw 50)])
          d (fl/response-decision :protocol-a chain (assess pre) policy c
                                  pre {:request/id "compound-1"})
          head-root (:lifecycle-head-root d)
          sneaky (fn [state action]
                   (case (:action/type action)
                     :allow-recapitalization (update-in state [:total-held :USDC] + 100)
                     ;; withdraw member secretly creates a liability
                     :withdraw (assoc-in state [:slash-credit-liabilities "0xEvil"] 1000)
                     state))
          result (fl/authorize-and-execute-compound
                  d c "compound-1" pre head-root :protocol-a #{} sneaky)]
      (is (= :effect-contract-violated (:error result))
          "an undeclared :liability-creating realization rejects the compound")
      (is (contains? (:issues result) :undeclared-realized-effects)))))

(deftest compound-is-all-or-nothing-on-member-failure
  (let [pre (solvent-world)
        chain (chain-for pre)
        c (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy c
                                pre {:request/id "compound-1"})
        head-root (:lifecycle-head-root d)
        failing (fn [state action]
                  (if (= :withdraw (:action/type action))
                    (throw (ex-info "withdrawal failed" {}))
                    (update-in state [:total-held :USDC] + 100)))
        result (fl/authorize-and-execute-compound
                d c "compound-1" pre head-root :protocol-a #{} failing)]
    (is (= :compound-member-execution-failed (:error result)))
    (is (= 1 (:member/index result)))
    (is (nil? (:post-state result))
        "no post-state is produced: the sequence commits as one unit or not at all")))

(deftest compound-all-or-nothing-when-last-member-fails
  (testing "A and B succeed INSIDE the executor; C fails at the latest possible
            pre-commit point — the gate must still emit nothing observable"
    (let [pre (solvent-world)
          chain (chain-for pre)
          c (compound pre [(recaption 100) (recaption 50) (withdraw 30)])
          d (fl/response-decision :protocol-a chain (assess pre) policy c
                                  pre {:request/id "compound-1"})
          head-root (:lifecycle-head-root d)
          fail-last (fn [state action]
                      (case (:action/type action)
                        :allow-recapitalization (update-in state [:total-held :USDC]
                                                           + (long (get-in action [:action/params :amount])))
                        :withdraw (throw (ex-info "withdraw C failed at the latest pre-commit point" {}))
                        state))
          result (fl/authorize-and-execute-compound
                  d c "compound-1" pre head-root :protocol-a #{} fail-last)]
      (is (= 2 (:member/index result))
          "A (0) and B (1) succeeded; C (2) is the failing member")
      (is (nil? (:post-state result)) "no partial post-state is ever emitted")
      (is (nil? (:consumed-ids result)) "no request is consumed")
      (is (nil? (:transition result)) "no compound transition is emitted")
      (is (nil? (:lineage result)) "no lineage is emitted / treated as authoritative"))))

;; ── Authoritative persistence: the conformance target ───────────────────────
;;
;; P4b itself has no persistence layer: authorize-and-execute-compound is a pure
;; functional gate that emits either ONE complete commit-capable payload (on
;; :ok? true: :post-state, :consumed-ids, :transition, :lineage) or NONE (on
;; :ok? false). Durable atomicity is the responsibility of the persistence
;; adapter that consumes a successful result. The tests below define the
;; consumer contract any real store must satisfy.
;;
;; Required boundary (to be implemented by the first persistence adapter, NOT
;; introduced now while no store consumer exists):
;;
;;   (commit-compound! store
;;     {:expected-pre-state-root <hex>
;;      :expected-store-version <v>
;;      :post-state            <state₃>
;;      :consumed-request-ids  #{R0 R1 R2 ...}
;;      :transition            <transition>
;;      :lineage               <lineage>})
;;
;; with ONE indivisible CAS/transaction: check expected state/version, then
;; atomically write post-state + consume the complete request set + persist the
;; transition + persist/bind the lineage evidence. The crucial property:
;;
;;   CAS succeeds → everything becomes visible
;;   CAS fails    → nothing becomes visible
;;
;; No recovery procedure should ever be needed to reconstruct half a compound.
;; The failure/success dual below is the conformance check for that adapter.

(deftest store-boundary-never-observes-partial-compound-on-failure
  (testing "the authoritative store sees byte-for-byte nothing on a late failure:
            authoritative-state stays == state₀, all requests unconsumed, no
            transition persisted, no lineage treated as authoritative"
    (let [pre (solvent-world)
          chain (chain-for pre)
          c (compound pre [(recaption 100) (recaption 50) (withdraw 30)])
          d (fl/response-decision :protocol-a chain (assess pre) policy c
                                  pre {:request/id "compound-1"})
          head-root (:lifecycle-head-root d)
          store (new-store pre)
          fail-last (fn [state action]
                      (case (:action/type action)
                        :allow-recapitalization (update-in state [:total-held :USDC]
                                                           + (long (get-in action [:action/params :amount])))
                        :withdraw (throw (ex-info "withdraw C failed" {}))
                        state))
          result (fl/authorize-and-execute-compound
                  d c "compound-1" pre head-root :protocol-a #{} fail-last)]
      (atomic-commit! store result)
      (is (= pre (:authoritative-state @store))
          "authoritative-state == state₀ (A/B mutations never reach the store)")
      (is (= (fl/domain-state-root pre) (fl/domain-state-root (:authoritative-state @store)))
          "the authoritative state root is exactly state₀'s")
      (is (= #{} (:consumed-ids @store)) "R0/R1/R2 remain unconsumed")
      (is (empty? (:transitions @store)) "no successful compound transition persisted")
      (is (= 0 (count (:lineage @store)))
          "no lineage is stored / treated as authoritative (the store holds none)"))))

(deftest store-boundary-commits-exactly-one-compound-transition
  (testing "the dual: success advances authoritative-state to state₃, consumes the
            whole request set as one unit, and persists exactly one transition
            whose roots match the committed compound and the recomputed lineage"
    (let [pre (solvent-world)
          chain (chain-for pre)
          c (compound pre [(recaption 100) (recaption 50) (withdraw 30)])
          d (fl/response-decision :protocol-a chain (assess pre) policy c
                                  pre {:request/id "compound-1"})
          head-root (:lifecycle-head-root d)
          store (new-store pre)
          result (fl/authorize-and-execute-compound
                  d c "compound-1" pre head-root :protocol-a #{} member-exec)
          lineage (:lineage result)]
      (is (:ok? result))
      (atomic-commit! store result)
      (is (= (:post-state result) (:authoritative-state @store))
          "authoritative-state == state₃")
      (is (not= pre (:authoritative-state @store)) "the authoritative state advanced")
      (is (= #{"compound-1" "recap-100" "recap-50" "withdraw-30"} (:consumed-ids @store))
          "R0/R1/R2 and the compound request are consumed as one unit")
      (is (= 1 (count (:transitions @store))) "exactly ONE compound transition persisted")
      (let [tr (first (:transitions @store))]
        (is (= (:action/root c) (:action/root tr))
            "transition.action-root == compound-root")
        (is (= (:compound/member-sequence-root c) (:action/sequence-root tr))
            "transition.sequence-root == committed-sequence-root")
        (is (= (:lineage/root lineage) (:transition/execution-lineage-root tr))
            "transition.lineage-root == recomputed-lineage-root")))))

;; ── Single-use / idempotency / consumption ─────────────────────────────────

(deftest compound-consumes-request-set-and-is-single-use
  (let [pre (solvent-world)
        chain (chain-for pre)
        c (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy c
                                pre {:request/id "compound-1"})
        head-root (:lifecycle-head-root d)
        first (fl/authorize-and-execute-compound d c "compound-1" pre head-root :protocol-a #{} member-exec)
        second (fl/authorize-and-execute-compound d c "compound-1" pre head-root :protocol-a
                                                  (:consumed-ids first) member-exec)]
    (is (:ok? first))
    (is (= :decision-reused (:error second))
        "one compound decision cannot authorize the same request set twice")
    (testing "member request ids are consumed so they cannot be reused elsewhere"
      (is (contains? (:consumed-ids first) "recap-100"))
      (is (contains? (:consumed-ids first) "withdraw-50")))))

(deftest compound-idempotent-reuse-allows-identical-transition
  (let [pre (solvent-world)
        chain (chain-for pre)
        c (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy c
                                pre {:request/id "compound-1" :idempotent? true})
        head-root (:lifecycle-head-root d)
        first (fl/authorize-and-execute-compound d c "compound-1" pre head-root :protocol-a #{} member-exec)
        second (fl/authorize-and-execute-compound d c "compound-1" pre head-root :protocol-a
                                                  (:consumed-ids first) member-exec)]
    (is (contains? (:reasons d) [:idempotent]))
    (is (:ok? first))
    (is (:ok? second) "explicit idempotency allows re-execution of the identical transition")))

;; ── Explicit compound claims (pure derived researcher/auditor queries) ───────

(deftest compound-claims-are-all-true-on-a-successful-execution
  (let [pre (solvent-world)
        chain (chain-for pre)
        c (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy c
                                pre {:request/id "compound-1"})
        head-root (:lifecycle-head-root d)
        result (fl/authorize-and-execute-compound d c "compound-1" pre head-root
                                                  :protocol-a #{} member-exec)
        claims (fl/compound-claims {:compound c :result result})]
    (is (:ok? result))
    (is (true? (:compound/membership-valid? claims))
        "these exact canonical members were committed")
    (is (true? (:compound/equal-cardinality? claims))
        "one request binding per action, one lineage step per action")
    (is (true? (:compound/order-valid? claims))
        "the lineage action roots match the committed member roots in order")
    (is (true? (:compound/consecutive? claims))
        "each member's post-state is the next member's pre-state")
    (is (true? (:compound/execution-atomic? claims))
        "the lifecycle emitted one complete all-or-nothing commit payload (NOT
         durable-store atomicity)")
    (is (true? (:compound/consumption-complete? claims))
        "every member request id was consumed as one unit")))

(deftest compound-claims-fail-closed-on-a-failed-execution
  (let [pre (solvent-world)
        chain (chain-for pre)
        c (compound pre [(recaption 100) (withdraw 50)])
        d (fl/response-decision :protocol-a chain (assess pre) policy c
                                pre {:request/id "compound-1"})
        head-root (:lifecycle-head-root d)
        failing (fn [state action]
                  (if (= :withdraw (:action/type action))
                    (throw (ex-info "withdraw failed" {}))
                    (update-in state [:total-held :USDC] + 100)))
        result (fl/authorize-and-execute-compound d c "compound-1" pre head-root
                                                  :protocol-a #{} failing)
        claims (fl/compound-claims {:compound c :result result})]
    (is (not (:ok? result)))
    (is (every? false? (vals claims))
        "no claim holds on a failed execution — the vocabulary fails closed")))

(deftest compound-claims-catch-tampered-lineage
  (testing "a forged lineage (intermediate state swapped) breaks the consecutive
            claim while leaving membership (a property of the committed compound) intact"
    (let [pre (solvent-world)
          chain (chain-for pre)
          c (compound pre [(recaption 100) (withdraw 50)])
          d (fl/response-decision :protocol-a chain (assess pre) policy c
                                  pre {:request/id "compound-1"})
          head-root (:lifecycle-head-root d)
          result (fl/authorize-and-execute-compound d c "compound-1" pre head-root
                                                    :protocol-a #{} member-exec)
          forged-lineage (update-in (:lineage result) [:lineage/steps 0 :state-after/root]
                                    (constantly (apply str (repeat 64 \0))))
          forged-result (assoc result :lineage forged-lineage)
          claims (fl/compound-claims {:compound c :result forged-result})]
      (is (true? (:compound/membership-valid? claims))
          "membership is a property of the committed compound, unaffected by lineage tampering")
      (is (false? (:compound/consecutive? claims))
          "a tampered intermediate state breaks the consecutive claim"))))