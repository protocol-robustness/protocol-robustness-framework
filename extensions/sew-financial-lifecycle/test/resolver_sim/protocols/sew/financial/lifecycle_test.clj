(ns resolver-sim.protocols.sew.financial.lifecycle-test
  "Tests for the insolvency lifecycle (P4) + response decision primitive (P4b).

   Covers the content-addressed chain hardening: predecessor binding, episode
   identity (splice-proof), temporal monotonicity, policy continuity, fail-closed
   chain verification, and the authoritative apply path that derives prev-state
   from a verified chain rather than trusting a caller-supplied one. Plus the
   adversarial rejection package (missing / duplicated / reordered /
   transplanted / modified events, mismatched roots/state/time/policy, invalid
   initial state) and the P4b permitted-action? decision."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.financial.lifecycle :as lc]
            [resolver-sim.protocols.sew.types :as t]))

(def policy (lc/default-policy))

(defn- solvent-world []
  (-> (t/empty-world 1000)
      (assoc :total-held {:USDC 1000})
      (assoc-in [:escrow-transfers 0]
                {:token :USDC :amount-after-fee 1000 :escrow-state :pending})))

(defn- insolvent-world []
  (-> (solvent-world)
      (assoc-in [:slash-credit-liabilities "0xRes0"] 500)))

(defn- impaired-world []
  (-> (t/empty-world 1000)
      (assoc :total-held {:USDC 0})
      (assoc-in [:escrow-transfers 0]
                {:token :USDC :amount-after-fee 10000 :escrow-state :released})
      (assoc-in [:claimable-v2 0 :settlement/principal "r"] 8000)
      (assoc-in [:yield/positions "o1"]
                {:token :USDC :principal 10000 :status :unwinding
                 :shortfall {:fulfilled-amount 8000 :deferred-amount 0
                             :haircut-amount 2000 :reason :principal-loss}})))

(defn- invalid-world []
  (-> (t/empty-world 1000)
      (assoc :total-held {:USDC 500})
      (assoc-in [:escrow-transfers 0]
                {:token :USDC :amount-after-fee 1000 :escrow-state :pending})))

(defn- assess [world] (solv/classify-solvency world))

(defn- build-chain
  "Build an authoritative chain of [assessment worlds] via apply-verified.
   Returns {:state <head state> :events [...]}."
  [subject worlds]
  (reduce (fn [acc world]
            (let [r (lc/apply-verified subject (:events acc) (assess world) {})]
              (when-not (:ok? r)
                (throw (ex-info "apply-verified failed" r)))
              {:state (:state r) :events (get-in r [:state :episode/events])}))
          {:state (lc/initial-state) :events []}
          worlds))

;; ── Assessment roots and episode identity ───────────────────────────────────

(deftest assessment-root-deterministic-and-sensitive
  (let [a (assess (solvent-world))
        a2 (assess (solvent-world))
        b (assess (insolvent-world))]
    (is (= (lc/assessment-root a) (lc/assessment-root a2)))
    (is (not= (lc/assessment-root a) (lc/assessment-root b)))
    (is (= 64 (count (lc/assessment-root a))))))

(deftest episode-id-is-deterministic-and-subject-bound
  (testing "genesis episode identity binds subject + policy + first assessment"
    (let [a (assess (insolvent-world))
          id-a1 (lc/genesis-episode-id :protocol-a (lc/policy-root policy) (lc/assessment-root a))
          id-a2 (lc/genesis-episode-id :protocol-a (lc/policy-root policy) (lc/assessment-root a))
          id-b (lc/genesis-episode-id :protocol-b (lc/policy-root policy) (lc/assessment-root a))]
      (is (= id-a1 id-a2) "deterministic for the same subject/policy/first-assessment")
      (is (not= id-a1 id-b) "different subject → different episode identity")
      (is (= 64 (count id-a1))))))

;; ── Content-addressed chain integrity ───────────────────────────────────────

(deftest valid-chain-verifies-and-reduces
  (let [{:keys [state events]} (build-chain :protocol-a [(solvent-world) (insolvent-world)])
        reduced (lc/reduce-events (lc/initial-state) events)]
    (is (:valid? (lc/verify-chain events)))
    (is (:valid? reduced))
    (is (= (:lifecycle/state state) (:lifecycle/state (:state reduced)))
        "reduction over the chain reproduces the head state")
    (is (= (:episode/consecutive-insolvent state)
           (:episode/consecutive-insolvent (:state reduced))))))

(deftest events-commit-predecessor-and-time
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world) (insolvent-world) (insolvent-world)])
        [e0 e1 e2] events]
    (is (= :genesis (:previous-event-root e0)))
    (is (= (:event-root e0) (:previous-event-root e1)) "event[1] binds event[0]")
    (is (= (:event-root e1) (:previous-event-root e2)) "event[2] binds event[1]")
    (is (>= (:event/at e1) (:event/at e0)) "time non-decreasing")
    (is (some? (:episode/before e0)))
    (is (= (:episode/id e0) (:episode/id e1)) "same episode")))

;; ── Adversarial rejection package ───────────────────────────────────────────

(defn- t-events [worlds] (:events (build-chain :protocol-a worlds)))

(deftest rejects-missing-event
  (let [events (t-events [(solvent-world) (insolvent-world) (insolvent-world)])
        result (lc/verify-chain (subvec events 1))]
    (is (not (:valid? result)))
    (is (contains? #{:missing-genesis :predecessor-root-mismatch} (:error result))
        "dropping the genesis event breaks the chain head")))

(deftest rejects-duplicated-event
  (let [events (t-events [(solvent-world) (insolvent-world)])
        duped (conj events (first events))]
    (is (= :predecessor-root-mismatch (:error (lc/verify-chain duped))))))

(deftest rejects-reordered-events
  (let [events (t-events [(solvent-world) (insolvent-world) (insolvent-world)])
        [e0 e1 e2] events
        reordered [e2 e0 e1]
        result (lc/verify-chain reordered)]
    (is (not (:valid? result)))
    (is (contains? #{:missing-genesis :predecessor-root-mismatch} (:error result))
        "reordering breaks the chain (either a non-genesis head or a broken predecessor)")))

(deftest rejects-transplanted-event-from-another-episode
  (let [events-a (t-events [(solvent-world) (insolvent-world)])
        events-b (:events (build-chain :protocol-b [(solvent-world) (insolvent-world)]))
        transplanted (conj events-a (last events-b))]
    (is (not (:valid? (lc/verify-chain transplanted))))
    (is (contains? #{:episode-id-mismatch :predecessor-root-mismatch}
                   (:error (lc/verify-chain transplanted))))))

(deftest rejects-incorrect-previous-lifecycle-state
  (let [events (t-events [(solvent-world) (insolvent-world)])
        bad (update-in events [1 :previous-lifecycle-state] (constantly :insolvent))
        result (lc/verify-chain bad)]
    (is (not (:valid? result)))
    (is (contains? #{:lifecycle-state-mismatch :event-root-mismatch} (:error result)))))

(deftest rejects-tampered-transition-reasons
  (testing "a field committed ONLY in the event root is tamper-detectable"
    (let [events (t-events [(solvent-world) (insolvent-world)])
          bad (update-in events [1 :transition-reasons] conj :tampered)]
      (is (= :event-root-mismatch (:error (lc/verify-chain bad)))))))

(deftest rejects-incorrect-previous-assessment-root
  (let [events (t-events [(solvent-world) (insolvent-world)])
        bad (assoc-in events [1 :previous-assessment-root] "0000000000000000000000000000000000000000000000000000000000000000")
        result (lc/verify-chain bad)]
    (is (not (:valid? result)))
    (is (contains? #{:assessment-root-mismatch :event-root-mismatch} (:error result))
        "a mismatched previous assessment root is rejected (directly and/or via the event root)")))

(deftest rejects-modified-episode-after
  (let [events (t-events [(solvent-world) (insolvent-world)])
        bad (update-in events [1 :episode/after :episode/consecutive-insolvent] inc)]
    (is (= :event-root-mismatch (:error (lc/verify-chain bad)))
        "modifying :episode/after is detectable via the event root")))

(deftest rejects-recomputed-event-with-different-policy
  (let [events (t-events [(solvent-world) (insolvent-world)])
        bad (update-in events [1 :policy-root] (constantly (lc/policy-root
                                                           (assoc policy :allow-exit-from-terminal? true))))
        result (lc/verify-chain bad)]
    (is (not (:valid? result)))
    (is (contains? #{:policy-root-mismatch :event-root-mismatch} (:error result))
        "a changed policy root is rejected (directly and/or via the event root)")))

(deftest rejects-invalid-initial-state
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world) (insolvent-world)])]
    (is (= :state-before-mismatch (:error (lc/reduce-events
                                           (assoc (lc/initial-state) :episode/consecutive-assessments 99)
                                           events))))))

(deftest rejects-backdated-timestamp
  (testing "temporal monotonicity is enforced by the authoritative path"
    (let [r1 (lc/apply-verified :protocol-a [] (assess (solvent-world)) {:at 200})
          events1 (:episode/events (:state r1))
          r2 (lc/apply-verified :protocol-a events1 (assess (insolvent-world)) {:at 50})]
      (is (= :time-not-monotonic (:error r2))))))

(deftest authoritative-apply-enforces-policy-continuity
  (testing "policy is immutable per episode"
    (let [r1 (lc/apply-verified :protocol-a [] (assess (solvent-world)) {:policy policy})
          events1 (:episode/events (:state r1))
          other-policy (assoc policy :allow-exit-from-terminal? true)
          r2 (lc/apply-verified :protocol-a events1 (assess (insolvent-world))
                                {:policy other-policy})]
      (is (= :policy-root-mismatch (:error r2))))))

(deftest authoritative-apply-derives-prev-state-from-chain
  (testing "apply-verified does not trust a caller-supplied prev-state"
    (let [r1 (lc/apply-verified :protocol-a [] (assess (solvent-world)) {:at 100})
          events1 (:episode/events (:state r1))
          r2 (lc/apply-verified :protocol-a events1 (assess (insolvent-world)) {:at 200})]
      (is (:ok? r2))
      (is (= :insolvent (:lifecycle/state (:state r2))))
      (is (= 1 (:episode/consecutive-insolvent (:state r2)))))))

;; ── Lifecycle state machine semantics ───────────────────────────────────────

(deftest healthy-to-insolvent-to-terminal
  (let [a-ins (assess (insolvent-world))
        s1 (lc/apply-verified :protocol-a [] a-ins {:at 100})
        e1 (:episode/events (:state s1))
        s2 (lc/apply-verified :protocol-a e1 a-ins {:at 200})
        e2 (:episode/events (:state s2))
        s3 (lc/apply-verified :protocol-a e2 a-ins {:at 300})]
    (is (= :insolvent (:lifecycle/state (:state s1))))
    (is (= :insolvent (:lifecycle/state (:state s2))))
    (is (= :terminal (:lifecycle/state (:state s3))))
    (is (= :economic-insolvency-persisted (:episode/terminal-reason (:state s3))))
    (is (= 3 (:episode/consecutive-insolvent (:state s3))))
    (is (= 100 (:episode/onset-at (:state s1))))))

(deftest recovery-reaches-healthy-after-cure-threshold
  (let [a-ins (assess (insolvent-world))
        a-solv (assess (solvent-world))
        r1 (lc/apply-verified :protocol-a [] a-ins {:at 100})
        e1 (:episode/events (:state r1))
        r2 (lc/apply-verified :protocol-a e1 a-solv {:at 200})
        e2 (:episode/events (:state r2))
        r3 (lc/apply-verified :protocol-a e2 a-solv {:at 300})]
    (is (= :insolvent (:lifecycle/state (:state r1))))
    (is (= :recovering (:lifecycle/state (:state r2)))
        "recovering is historical: improved beyond insolvency, clearance not yet met")
    (is (= 200 (:episode/recovery-at (:state r2))))
    (is (= :healthy (:lifecycle/state (:state r3))) "cure threshold met")
    (is (zero? (:episode/consecutive-insolvent (:state r3))))))

(deftest impaired-is-distinct-from-insolvent
  (let [r1 (lc/apply-verified :protocol-a [] (assess (impaired-world)) {:at 100})]
    (is (= :impaired (:lifecycle/state (:state r1))))
    (is (= #{:impairment} (:transition-reasons (get-in r1 [:state :episode/events 0]))))))

(deftest indeterminate-assessments-carry-state-forward
  (let [r1 (lc/apply-verified :protocol-a [] (assess (invalid-world)) {:at 100})]
    (is (= :healthy (:lifecycle/state (:state r1)))
        "an invalid ledger is not evidence of insolvency — lifecycle unchanged")
    (is (= #{:assessment-invalid} (:transition-reasons (get-in r1 [:state :episode/events 0]))))))

(deftest terminal-is-sticky-by-default
  (let [a-ins (assess (insolvent-world))
        a-solv (assess (solvent-world))
        r1 (lc/apply-verified :protocol-a [] a-ins {:at 100}) e1 (:episode/events (:state r1))
        r2 (lc/apply-verified :protocol-a e1 a-ins {:at 200}) e2 (:episode/events (:state r2))
        r3 (lc/apply-verified :protocol-a e2 a-ins {:at 300}) e3 (:episode/events (:state r3))
        r4 (lc/apply-verified :protocol-a e3 a-solv {:at 400})]
    (is (= :terminal (:lifecycle/state (:state r3))))
    (is (= :terminal (:lifecycle/state (:state r4)))
        "terminal is sticky by default")))

(deftest terminal-exit-requires-explicit-authorization
  (testing "with :allow-exit-from-terminal? true, exit is explicit and exceptional"
    (let [exit-policy (assoc policy :allow-exit-from-terminal? true)
          a-ins (assess (insolvent-world))
          a-solv (assess (solvent-world))
          r1 (lc/apply-verified :protocol-a [] a-ins {:at 100 :policy exit-policy}) e1 (:episode/events (:state r1))
          r2 (lc/apply-verified :protocol-a e1 a-ins {:at 200 :policy exit-policy}) e2 (:episode/events (:state r2))
          r3 (lc/apply-verified :protocol-a e2 a-ins {:at 300 :policy exit-policy}) e3 (:episode/events (:state r3))
          r4 (lc/apply-verified :protocol-a e3 a-solv {:at 400 :policy exit-policy})]
      (is (= :terminal (:lifecycle/state (:state r3))))
      (is (= :recovering (:lifecycle/state (:state r4))))
      (is (= :policy-authorized (:episode/terminal-exit-reason (:state r4))))
      (is (contains? (:transition-reasons (get-in r4 [:state :episode/events 3]))
                     :terminal-exit-authorized)
          "terminal exit is visibly exceptional, never indistinguishable from recovery"))))

(deftest assessment-is-never-mutated
  (let [a (assess (insolvent-world))
        before (pr-str a)
        _ (lc/apply-verified :protocol-a [] a {:at 100})]
    (is (= before (pr-str a)))))

(deftest max-shortfall-is-per-token
  (testing "no synthetic cross-token aggregation in episode metrics"
    (let [deficit-world (-> (t/empty-world 1000)
                            (assoc :total-held {:USDC 1000})
                            (assoc-in [:escrow-transfers 0]
                                      {:token :USDC :amount-after-fee 1000 :escrow-state :pending})
                            (assoc-in [:slash-credit-liabilities "0xRes0"] 500))
          r1 (lc/apply-verified :protocol-a [] (assess deficit-world) {:at 100})]
      (is (= {:USDC 500} (:episode/max-shortfall-by-token (:state r1)))
          "shortfall is tracked per token (a map), never a synthetic scalar"))))

;; ── P4b response decision (authorization artifact) ──────────────────────────

(deftest response-decision-permits-and-denies
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        a (assess (solvent-world))
        pre (solvent-world)
        healthy-decision (lc/response-decision :protocol-a events a policy :open-escrow pre)
        denied (lc/response-decision :protocol-a events a policy :enter-resolution pre)]
    (is (= :permit (:decision healthy-decision)))
    (is (= :deny (:decision denied)))
    (is (lc/permitted-action? :protocol-a events a policy :open-escrow pre))
    (is (not (lc/permitted-action? :protocol-a events a policy :enter-resolution pre)))
    (is (= "insolvency-response-decision.v1" (:response-decision/version healthy-decision)))
    (is (string? (:decision-root healthy-decision)))
    (is (= 64 (count (:decision-root healthy-decision))))))

(deftest response-decision-reflects-insolvent-state
  (let [{:keys [events]} (build-chain :protocol-a [(insolvent-world)])
        a (assess (insolvent-world))
        pre (insolvent-world)]
    (is (not (lc/permitted-action? :protocol-a events a policy :open-escrow pre))
        "insolvency restricts new obligations")
    (is (lc/permitted-action? :protocol-a events a policy :enter-resolution pre))))

(deftest response-decision-fails-closed-on-invalid-chain
  (let [events (t-events [(solvent-world) (insolvent-world)])
        bad (subvec events 1)  ;; missing genesis
        a (assess (insolvent-world))
        d (lc/response-decision :protocol-a bad a policy :open-escrow (insolvent-world))]
    (is (= :invalid (:decision d)) "invalid provenance → :invalid (fail-closed, distinct from deny)")
    (is (contains? (:reasons d) [:invalid-lifecycle-chain])
        "invalid provenance is a structured finding, distinct from policy denial")
    (is (nil? (:lifecycle-head-root d)) "no head can be derived from an invalid chain")))

(deftest response-decision-binds-roots
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        a (assess (solvent-world))
        d (lc/response-decision :protocol-a events a policy :open-escrow (solvent-world))]
    (is (= (lc/assessment-root a) (:assessment-root d)))
    (is (= (lc/policy-root policy) (:policy-root d)))
    (is (string? (:lifecycle-head-root d)))
    (is (string? (:action/root d)))
    (is (= :open-escrow (:action/type d)))
    (is (= :protocol-a (:subject d)))))

;; ── P4b enforcement invariant + central gate ─────────────────────────────────

(deftest decision-authorizes-exact-request
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        a (assess (solvent-world))
        pre (solvent-world)
        head-root (:lifecycle-head-root (lc/response-decision :protocol-a events a policy :withdraw pre))
        d (lc/response-decision :protocol-a events a policy :withdraw pre {:request/id "req-1"})]
    (is (lc/decision-authorizes? d :withdraw "req-1" pre head-root :protocol-a))
    (is (not (lc/decision-authorizes? d :withdraw "req-1" pre head-root :protocol-b))
        "different subject → reject")
    (is (not (lc/decision-authorizes? d :open-escrow "req-1" pre head-root :protocol-a))
        "different action → reject")
    (is (not (lc/decision-authorizes? d :withdraw "req-2" pre head-root :protocol-a))
        "different request id → reject")
    (is (not (lc/decision-authorizes? d :withdraw "req-1" (insolvent-world) head-root :protocol-a))
        "different pre-state → reject")
    (is (not (lc/decision-authorizes? d :withdraw "req-1" pre "0" :protocol-a))
        "different lifecycle head (stale) → reject")))

(deftest action-binding-prevents-substitution
  (testing "a permit for withdrawal A cannot be reused for withdrawal B"
    (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
          a (assess (solvent-world))
          pre (solvent-world)
          withdrawal-a {:action/type :withdraw :action/params {:amount 100}}
          withdrawal-b {:action/type :withdraw :action/params {:amount 200}}
          d (lc/response-decision :protocol-a events a policy withdrawal-a pre
                                  {:request/id "req-1"})
          head-root (:lifecycle-head-root d)]
      (is (not= (lc/action-root withdrawal-a) (lc/action-root withdrawal-b))
          "economically different withdrawals have different action roots")
      (is (lc/decision-authorizes? d withdrawal-a "req-1" pre head-root :protocol-a))
      (is (not (lc/decision-authorizes? d withdrawal-b "req-1" pre head-root :protocol-a))
          "decision bound to withdrawal A cannot authorize withdrawal B"))))

(deftest central-gate-executes-and-binds-evidence
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        a (assess (solvent-world))
        pre (solvent-world)
        head-root (:lifecycle-head-root (lc/response-decision :protocol-a events a policy :allow-recapitalization pre))
        d (lc/response-decision :protocol-a events a policy :allow-recapitalization pre {:request/id "req-1"})
        result (lc/authorize-and-execute d :allow-recapitalization "req-1" pre head-root :protocol-a #{}
                                         (fn [s] (-> s (update-in [:total-held :USDC] + 100)
                                                     (assoc :executed true))))]
    (is (:ok? result) "declared effects (asset-inflow) are realized → contract passes")
    (is (:executed (:post-state result)))
    (is (= (:decision-root d) (:response-decision/root (:transition result)))
        "execution evidence binds the response decision")
    (is (string? (:transition/request-root (:transition result))))
    (is (string? (:transition/pre-state-root (:transition result))))
    (is (string? (:transition/post-state-root (:transition result))))
    (is (contains? (:transition/realized-effects (:transition result)) :asset-inflow))
    (is (string? (:transition/execution-root (:transition result))))))

;; ── P4b bypass-resistance suite ──────────────────────────────────────────────

(deftest gate-rejects-deny-invalid-and-no-decision-mutations
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        a (assess (solvent-world))
        pre (solvent-world)
        head-root (:lifecycle-head-root (lc/response-decision :protocol-a events a policy :withdraw pre))
        ;; :enter-resolution is DENIED in the healthy state
        deny-d (lc/response-decision :protocol-a events a policy :enter-resolution pre {:request/id "req-1"})
        ;; a chain missing its genesis is provenance-INVALID
        invalid-d (lc/response-decision :protocol-a (subvec (t-events [(solvent-world) (insolvent-world)]) 1)
                                        a policy :withdraw pre {:request/id "req-2"})
        run (fn [decision] (lc/authorize-and-execute decision :enter-resolution "req-1" pre head-root :protocol-a #{} identity))]
    (is (= :decision-denied (:error (run deny-d))) "deny → mutate anyway is rejected")
    (is (= :decision-invalid (:error (run invalid-d))) "invalid → mutate anyway is rejected")
    (is (= :no-decision (:error (run nil))) "no decision → mutate anyway is rejected")))

(deftest gate-rejects-substitution-and-stale-head
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        a (assess (solvent-world))
        pre (solvent-world)
        head-root (:lifecycle-head-root (lc/response-decision :protocol-a events a policy :withdraw pre))
        d (lc/response-decision :protocol-a events a policy :withdraw pre {:request/id "req-1"})
        newer-head (lc/lifecycle-state-root (assoc (lc/initial-state) :episode/consecutive-assessments 5))]
    (is (= :request-hash-mismatch
           (:error (lc/authorize-and-execute d :open-escrow "req-1" pre head-root :protocol-a #{} identity)))
        "permit for :withdraw cannot be used for :open-escrow (different action → request hash no longer matches)")
    (is (= :request-hash-mismatch
           (:error (lc/authorize-and-execute d :withdraw "req-1" (insolvent-world) head-root :protocol-a #{} identity)))
        "permit at S1 cannot execute against S2 (different pre-state → request hash no longer matches)")
    (is (= :decision-does-not-authorize-request
           (:error (lc/authorize-and-execute d :withdraw "req-1" pre newer-head :protocol-a #{} identity)))
        "permit at lifecycle head h1 cannot execute against newer head h2 (head is not part of the request hash)")))

(deftest gate-enforces-single-use
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        a (assess (solvent-world))
        pre (solvent-world)
        head-root (:lifecycle-head-root (lc/response-decision :protocol-a events a policy :allow-recapitalization pre))
        d (lc/response-decision :protocol-a events a policy :allow-recapitalization pre {:request/id "req-1"})
        recap (fn [s] (update-in s [:total-held :USDC] + 100))
        first (lc/authorize-and-execute d :allow-recapitalization "req-1" pre head-root :protocol-a #{} recap)]
    (is (:ok? first))
    (is (= :decision-reused (:error (lc/authorize-and-execute d :allow-recapitalization "req-1" pre head-root :protocol-a
                                                              (:consumed-ids first) recap)))
        "one decision cannot authorize the same economic inflow twice")))

(deftest gate-enforces-idempotent-reuse
  (testing "explicit idempotency allows re-execution of the IDENTICAL transition"
    (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
          a (assess (solvent-world))
          pre (solvent-world)
          head-root (:lifecycle-head-root (lc/response-decision :protocol-a events a policy :allow-recapitalization pre))
          d (lc/response-decision :protocol-a events a policy :allow-recapitalization pre
                                  {:request/id "req-1" :idempotent? true})
          recap (fn [s] (update-in s [:total-held :USDC] + 100))
          first (lc/authorize-and-execute d :allow-recapitalization "req-1" pre head-root :protocol-a #{} recap)
          second (lc/authorize-and-execute d :allow-recapitalization "req-1" pre head-root :protocol-a
                                           (:consumed-ids first) recap)]
      (is (contains? (:reasons d) [:idempotent]))
      (is (:ok? first))
      (is (:ok? second) "identical transition re-executed under explicit idempotency"))))

(deftest gate-cannot-authorize-a-batch-with-a-single-item-decision
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        a (assess (solvent-world))
        pre (solvent-world)
        head-root (:lifecycle-head-root (lc/response-decision :protocol-a events a policy :withdraw pre))
        d (lc/response-decision :protocol-a events a policy :withdraw pre {:request/id "req-batch-item"})
        batch-action {:action/type :withdraw :action/params {:amount 1000}}]
    (is (= :request-hash-mismatch
           (:error (lc/authorize-and-execute d batch-action "req-batch-item" pre head-root :protocol-a #{} identity)))
        "a decision for one withdrawal cannot authorize a different (batch) withdrawal")))

(deftest gate-never-invokes-execute-fn-on-rejection
  (testing "the gate guards execution: execute-fn is not called when validation fails"
    (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
          a (assess (solvent-world))
          pre (solvent-world)
          head-root (:lifecycle-head-root (lc/response-decision :protocol-a events a policy :withdraw pre))
          deny-d (lc/response-decision :protocol-a events a policy :enter-resolution pre {:request/id "req-1"})
          called (atom 0)
          result (lc/authorize-and-execute deny-d :enter-resolution "req-1" pre head-root :protocol-a #{}
                                           (fn [s] (swap! called inc) s))]
      (is (= :decision-denied (:error result)))
      (is (zero? @called)
          "a bypass that calls the mutation primitive directly is the only path; the gate itself never executes on rejection"))))

;; ── Request-hash commitment (invalid-request / request-hash-mismatch) ────────

(deftest request-hash-binds-request-content
  (testing "the request hash commits id + action-root + subject + pre-state-root"
    (let [base {:request/id "r1" :action/root "A" :subject :sew-v1 :pre-state/root "S"}]
      (is (= (lc/request-hash base) (lc/request-hash base)) "deterministic")
      (doseq [m [{:request/id "r2" :action/root "A" :subject :sew-v1 :pre-state/root "S"}
                 {:request/id "r1" :action/root "B" :subject :sew-v1 :pre-state/root "S"}
                 {:request/id "r1" :action/root "A" :subject :other :pre-state/root "S"}
                 {:request/id "r1" :action/root "A" :subject :sew-v1 :pre-state/root "T"}]]
        (is (not= (lc/request-hash base) (lc/request-hash m))
            "changing any request field changes the hash")))))

(deftest decision-commits-and-validates-request-hash
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        pre (solvent-world)
        a (assess (solvent-world))
        d (lc/response-decision :protocol-a events a policy :allow-recapitalization pre {:request/id "req-1"})]
    (is (string? (:request/hash d)) "the decision commits a request hash")
    (is (= 64 (count (:request/hash d))))
    (is (= (lc/request-hash {:request/id "req-1"
                             :action/root (:action/root d)
                             :subject :protocol-a
                             :pre-state/root (:pre-state/root d)})
           (:request/hash d))
        "the committed hash is exactly the request content the decision bound")
    (is (thrown? clojure.lang.ExceptionInfo
                 (lc/response-decision :protocol-a events a policy :allow-recapitalization pre
                                       {:request/id "req-1"
                                        :request/hash (apply str (repeat 64 \0))}))
        "a caller-supplied request-hash that does not match is rejected")))

(deftest gate-rejects-invalid-and-mismatched-request-hash
  (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
        pre (solvent-world)
        a (assess (solvent-world))
        d (lc/response-decision :protocol-a events a policy :allow-recapitalization pre {:request/id "req-1"})
        head-root (:lifecycle-head-root d)
        recap (fn [s] (update-in s [:total-held :USDC] + 100))
        no-hash-d (dissoc d :request/hash)
        wrong-id (lc/authorize-and-execute d :allow-recapitalization "req-2" pre head-root :protocol-a #{} recap)]
    (is (= :request-hash-mismatch (:error wrong-id))
        "executing with a different request id → the request hash no longer matches")
    (is (= :invalid-request (:error (lc/authorize-and-execute no-hash-d :allow-recapitalization "req-1"
                                                              pre head-root :protocol-a #{} recap)))
        "a decision with no committed request hash → :invalid-request")))

(deftest request-hash-is-stable-across-the-boundary
  (testing "the decision made on id X and the enforcement with id X share one request hash"
    (let [{:keys [events]} (build-chain :protocol-a [(solvent-world)])
          pre (solvent-world)
          a (assess (solvent-world))
          d (lc/response-decision :protocol-a events a policy :allow-recapitalization pre {:request/id "req-1"})
          head-root (:lifecycle-head-root d)
          result (lc/authorize-and-execute d :allow-recapitalization "req-1" pre head-root :protocol-a #{}
                                           (fn [s] (update-in s [:total-held :USDC] + 100)))]
      (is (:ok? result))
      (is (= (:request/hash d)
             (lc/request-hash {:request/id "req-1"
                               :action/root (:action/root d)
                               :subject :protocol-a
                               :pre-state/root (:pre-state/root d)}))
          "decision and enforcement agree on the committed request hash"))))
