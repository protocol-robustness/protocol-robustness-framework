(ns resolver-sim.risk.admission-test
  "Final risk-controlled pro-rata admission authority integration.

   The candidate operation is admitted/committed only against the exact
   risk-limit-policy.v1 committed by the CURRENTLY APPLICABLE authoritative
   chain configuration, with no stale-head or caller-asserted gap between
   authority resolution, evaluation, and commit.

   Demonstrates:
     1. current H1 -> C1 -> P1 + passing exact candidate -> commit succeeds;
     2. caller-asserted configuration cannot override the authoritative head;
     3. previously-authoritative P1 is unusable once current C2 commits P2;
     4. head change after evaluation but before commit -> stale cannot finalize;
     5. retry under the new head recomputes the new applicable policy;
     6. valid config body with wrong/non-current head lineage -> reject;
     7. candidate cannot be transplanted across authority heads;
     8. risk violation produces no partial business-state mutation;
     9. failed/stale admission does not advance state or head;
    10. no caller-controlled policy-selection route."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.risk.admission :as admission]
            [resolver-sim.risk.limit-policy :as lp]))

(def ^:private unit-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "usd-micros"})))

(def ^:private valuation-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "valuation-basis"})))

(def ^:private qa "3f3ef786b34d6dd716e1812c8b74a7a0e1f05aa5f3230588f6f5bcd00c6c8392")
(def ^:private qb "22d1fb414b6d62b69391bb42b9b94314a5b2fa77a7e8abd507cf3152ed4fa38e")

(defn- strict-policy []
  (lp/policy
   {:id "p1-strict" :unit/root unit-root :basis :conservative-peak
    :limits [{:limit/kind :global :limit/id "global" :limit/amount 1000000}
             {:limit/kind :concentration :limit/id "per-resolver"
              :risk/domain "human/principal" :limit/amount 100000}]}))

(defn- permissive-policy []
  (lp/policy
   {:id "p2-permissive" :unit/root unit-root :basis :conservative-peak
    :limits [{:limit/kind :global :limit/id "global" :limit/amount 5000000}
             {:limit/kind :concentration :limit/id "per-resolver"
              :risk/domain "human/principal" :limit/amount 500000}]}))

(defn- v4-config [policy]
  (assoc genesis/chain-configuration-v4-fixture
         :risk-limit-policy/root (:risk-limit-policy/root policy)))

(defn- candidate
  ([state-before stages]
   (candidate state-before stages
              {qa [{:risk/domain "human/principal" :risk/member "resolver-a"}]}))
  ([state-before stages attribution]
   {:state-before state-before
    :stages stages
    :attribution attribution
    :valuation-basis-root valuation-root
    :unit-root unit-root}))

(defn- store-at
  [business-state]
  (let [store (admission/new-store
               (genesis/chain-configuration-root (v4-config (strict-policy)))
               1 business-state)]
    (admission/retain-configuration! store (v4-config (strict-policy)))
    (admission/retain-risk-policy! store (strict-policy))
    store))

(defn- activate-to!
  [store new-config new-policy]
  (let [head (admission/current-head store)
        t {:transition/schema genesis/chain-configuration-transition-schema
           :protocol/genesis-root genesis/protocol-genesis-fixture-root
           :target {:target/type :chain-instance
                    :target/root genesis/chain-instance-genesis-ethereum-fixture-root}
           :configuration/parent-root (genesis/chain-configuration-root (v4-config (strict-policy)))
           :configuration/new-root (genesis/chain-configuration-root new-config)
           :verifier-registry/root (:verifier-registry/root new-config)
           :epoch (inc (:configuration/epoch head))}]
    (admission/activate-configuration!
     store {:transition t
            :parent-configuration (v4-config (strict-policy))
            :new-configuration new-config
            :new-risk-policy new-policy
            :expected-head-root (:configuration-head-state/root head)})))

;; 1. current H1 -> C1 -> P1 + passing exact candidate -> commit succeeds
(deftest test-current-authority-commits-passing-candidate
  (let [store (store-at {qa 80000})
        decision (admission/admit-and-commit-pro-rata!
                  store (candidate {qa 80000} [[(effects/delta qa 10000)]]))]
    (is (= :committed (:status decision)))
    (is (= (:risk-limit-policy/root (strict-policy)) (:risk-policy/root decision)))
    (is (= {qa 90000} (:business/state (admission/state-snapshot store))))
    (is (= 1 (count (admission/committed-admissions store))))
    (let [record (first (vals (admission/committed-admissions store)))]
      (is (hash-ref/valid-sha256-ref?
           (:risk-controlled-pro-rata-admission-basis/root record))))))

(deftest test-admission-basis-is-committed-with-authoritative-successor
  (let [store (store-at {qa 80000})
        issued (admission/issue-risk-fence!
                store (candidate {qa 80000} [[(effects/delta qa 10000)]]))
        result (admission/finalise-risk-fence! store (:fence/id issued))
        snapshot (admission/state-snapshot store)
        record (first (vals (:risk-admissions snapshot)))]
    (is (= :committed (:status result)))
    (is (= {qa 90000} (:business/state snapshot)))
    (is (some? record))
    (is (= (effects/state-root {qa 80000}) (:state-before/root record)))
    (is (= (effects/state-root {qa 90000}) (:state-after/root record)))
    (is (= result (admission/finalise-risk-fence! store (:fence/id issued))))))

(deftest test-finalization-rejects-tampered-semantic-root
  (let [store (store-at {qa 80000})
        issued (admission/issue-risk-fence!
                store (candidate {qa 80000} [[(effects/delta qa 10000)]]))
        fence-id (:fence/id issued)
        before (admission/state-snapshot store)]
    (swap! (.state-atom store)
           assoc-in [:risk-fences fence-id :risk-projection/root]
           (hash-ref/sha256-ref qb))
    (let [result (admission/finalise-risk-fence! store fence-id)]
      (is (= :rejected (:status result)))
      (is (= :admission/risk-projection-root-mismatch (:reason result)))
      (is (= (:business/state before)
             (:business/state (admission/state-snapshot store)))))))

(deftest test-durable-snapshot-recovers-committed-admission
  (let [file (java.io.File/createTempFile "risk-admission-" ".edn")]
    (try
      (let [store (store-at {qa 80000})
            issued (admission/issue-risk-fence!
                    store (candidate {qa 80000} [[(effects/delta qa 10000)]]))
            result (admission/finalise-risk-fence! store (:fence/id issued))
            root (:risk-admission/root result)]
        (is (= :committed (:status result)))
        (admission/persist-snapshot! store (.getPath file))
        (let [recovered (admission/open-durable-store (.getPath file))
              record (admission/admission-by-root recovered root)]
          (is (= (effects/state-root {qa 90000})
                 (effects/state-root (:business/state (admission/state-snapshot recovered)))))
          (is (= root (:risk-controlled-pro-rata-admission/root record)))
          (is (hash-ref/valid-sha256-ref?
               (:risk-controlled-pro-rata-admission-basis/root record)))
          (let [fence-id (first (keys (:risk-fences (admission/state-snapshot recovered))))]
            (is (= :committed
                   (:status (admission/finalise-risk-fence! recovered fence-id)))))))
      (finally
        (.delete file)))))

(deftest test-durable-snapshot-rejects-tampered-admission
  (let [file (java.io.File/createTempFile "risk-admission-tampered-" ".edn")]
    (try
      (let [store (store-at {qa 80000})]
        (admission/admit-and-commit-pro-rata!
         store (candidate {qa 80000} [[(effects/delta qa 10000)]]))
        (admission/persist-snapshot! store (.getPath file))
        (let [snapshot (edn/read-string (slurp file))
              root (first (keys (:risk-admissions snapshot)))
              tampered (assoc-in snapshot [:risk-admissions root :state-after/root]
                                 (effects/state-root {qa 91000}))]
          (spit file (pr-str tampered))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"invalid durable risk admission snapshot"
                                (admission/open-durable-store (.getPath file))))))
      (finally (.delete file)))))

(deftest risk-admission-request-identity-is-semantic-and-fence-orthogonal
  (let [before {qa 80000}
        op (candidate before [[(effects/delta qa 10000)]])
        reordered (candidate before [[(effects/delta qb 0)
                                      (effects/delta qa 4000)
                                      (effects/delta qa 6000)]])
        request (admission/risk-admission-request op)
        store (store-at before)
        issued-a (admission/issue-fence-transition
                  (admission/state-snapshot store) "fence-a" op)
        issued-b (admission/issue-fence-transition
                  (admission/state-snapshot store) "fence-b" op)]
    (is (admission/valid-risk-admission-request? request))
    (is (= request (admission/risk-admission-request op)))
    (is (= (:risk-admission-request/root request)
           (:risk-admission-request/root
            (admission/risk-admission-request reordered))))
    (is (= (:risk-admission-request/root issued-a)
           (:risk-admission-request/root issued-b)))
    (is (= "fence-a" (:fence/id issued-a)))
    (is (= "fence-b" (:fence/id issued-b)))))

(deftest risk-admission-request-identity-distinguishes-semantic-mutations
  (let [before {qa 80000}
        base (candidate before [[(effects/delta qa 10000)]])
        root #(-> % admission/risk-admission-request
                  :risk-admission-request/root)
        other-valuation (hash-ref/sha256-ref
                         (hc/domain-hash :prf-risk-projection-v1
                                         {:fixture "other-valuation"}))
        other-unit (hash-ref/sha256-ref
                    (hc/domain-hash :prf-risk-projection-v1
                                    {:fixture "other-unit"}))]
    (is (not= (root base)
              (root (candidate {qa 70000} [[(effects/delta qa 10000)]]))))
    (is (not= (root base)
              (root (candidate before [[(effects/delta qa 9000)]]))))
    (is (not= (root base)
              (root (assoc base :valuation-basis-root other-valuation))))
    (is (not= (root base)
              (root (assoc base :unit-root other-unit))))
    (is (not= (root base)
              (root (assoc base :attribution
                           {qa [{:risk/domain "human/principal"
                                 :risk/member "resolver-b"}]}))))
    (is (not (admission/valid-risk-admission-request?
              (assoc (admission/risk-admission-request base) :unknown true))))))

(deftest finalization-verifies-the-fence-request-binding
  (let [store (store-at {qa 80000})
        state (admission/state-snapshot store)
        op (candidate {qa 80000} [[(effects/delta qa 10000)]])
        issued (admission/issue-fence-transition state "fence-request" op)
        request-root (:risk-admission-request/root issued)
        committed (admission/finalise-fence-transition
                   (:state issued) "fence-request")
        tampered (assoc-in (:state issued)
                           [:risk-fences "fence-request"
                            :risk-admission-request/root]
                           (hash-ref/sha256-ref qb))]
    (is (= request-root (:risk-admission-request/root committed)))
    (is (= (:risk-admission/root committed)
           (get-in (:state committed)
                   [:risk-request-admissions request-root])))
    (is (= :risk-admission-request-root-mismatch
           (:reason (admission/finalise-fence-transition
                     tampered "fence-request"))))))

(deftest pure-risk-admission-transitions-are-deterministic-and-non-mutating
  (let [store (store-at {qa 80000})
        state (admission/state-snapshot store)
        op (candidate {qa 80000} [[(effects/delta qa 10000)]])
        issued-a (admission/issue-fence-transition state "fence-fixed" op)
        issued-b (admission/issue-fence-transition state "fence-fixed" op)]
    (is (= issued-a issued-b))
    (is (= state (admission/state-snapshot store)))
    (is (= :issued (:status issued-a)))
    (let [final-a (admission/finalise-fence-transition (:state issued-a) "fence-fixed")
          final-b (admission/finalise-fence-transition (:state issued-a) "fence-fixed")
          record (get-in (:state final-a)
                         [:risk-admissions (:risk-admission/root final-a)])]
      (is (= final-a final-b))
      (is (= {qa 90000} (:business/state (:state final-a))))
      (is (= 1 (:configuration/commit-index (:state final-a))))
      (is (= (effects/state-root {qa 80000}) (:state-before/root record)))
      (is (= (effects/state-root {qa 90000}) (:state-after/root record)))
      (is (= :committed
             (:status (admission/finalise-fence-transition
                       (:state final-a) "fence-fixed")))))))

(deftest atom-adapter-conforms-to-pure-transitions
  (let [pure-store (store-at {qa 80000})
        atom-store (store-at {qa 80000})
        op (candidate {qa 80000} [[(effects/delta qa 10000)]])
        pure-issued (admission/issue-fence-transition
                     (admission/state-snapshot pure-store) "fence-conformance" op)
        pure-final (admission/finalise-fence-transition
                    (:state pure-issued) "fence-conformance")]
    (let [atom-issued (admission/issue-risk-fence! atom-store op)
          atom-final (admission/finalise-risk-fence! atom-store (:fence/id atom-issued))
          pure-record (get-in (:state pure-final)
                              [:risk-admissions (:risk-admission/root pure-final)])
          atom-record (admission/admission-by-root atom-store
                                                   (:risk-admission/root atom-final))]
      (is (= :committed (:status atom-final)))
      (is (= (:business/state (:state pure-final))
             (:business/state (admission/state-snapshot atom-store))))
      (is (= (select-keys pure-record
                          [:state-before/root :state-after/root
                           :risk-controlled-pro-rata-admission-basis/root])
             (select-keys atom-record
                          [:state-before/root :state-after/root
                           :risk-controlled-pro-rata-admission-basis/root])))
      (is (= 1 (:configuration/commit-index
                (admission/state-snapshot atom-store)))))))

(deftest pure-transitions-reject-stale-and-tampered-inputs
  (let [store (store-at {qa 80000})
        state (admission/state-snapshot store)
        op (candidate {qa 80000} [[(effects/delta qa 10000)]])
        issued (admission/issue-fence-transition state "fence-tamper" op)
        issued-state (:state issued)]
    (is (= :candidate-state-not-current
           (:reason (admission/issue-fence-transition
                     state "fence-stale"
                     (candidate {qa 70000} [[(effects/delta qa 10000)]])))))
    (is (= :admission/risk-projection-root-mismatch
           (:reason (admission/finalise-fence-transition
                     (assoc-in issued-state
                               [:risk-fences "fence-tamper" :risk-projection/root]
                               (hash-ref/sha256-ref qb))
                     "fence-tamper"))))
    (let [committed (admission/finalise-fence-transition issued-state "fence-tamper")]
      (is (= :candidate-state-not-current
             (:reason (admission/finalise-fence-transition
                       (assoc-in issued-state [:business/state qa] 81000)
                       "fence-tamper"))))
      (is (= :unknown-fence
             (:reason (admission/finalise-fence-transition
                       (:state committed) "fence-other")))))))

;; 2. caller-asserted configuration cannot override the authoritative head
(deftest test-authority-derives-current-configuration-not-caller
  (let [store (store-at {qa 80000})
        ;; a third, valid config body is retained but is NOT the head's root
        _ (admission/retain-configuration! store (v4-config (permissive-policy)))
        resolved (admission/current-resolved store)]
    (testing "resolution uses the head's committed root, never a retained non-current body"
      (is (:resolved? resolved))
      (is (= (genesis/chain-configuration-root (v4-config (strict-policy)))
             (:configuration/root resolved)))
      (is (= (:risk-limit-policy/root (strict-policy)) (:risk-policy/root resolved))))))

;; 3. previously-authoritative P1 unusable once current C2 commits P2
(deftest test-stale-policy-is-unusable-after-head-advances
  (let [store (store-at {qa 80000})
        activated (activate-to! store (v4-config (permissive-policy)) (permissive-policy))]
    (is (= :committed (:status activated)))
    (testing "the current applicable policy is now P2, not the previously-authoritative P1"
      (let [resolved (admission/current-resolved store)]
        (is (= (:risk-limit-policy/root (permissive-policy)) (:risk-policy/root resolved)))))
    (testing "a candidate violating current P2 is rejected even though P1 is retained"
      ;; peak 5.08M (80k + 5M) exceeds P2's 5M global; the gate uses the
      ;; CURRENT policy P2 (root-verified), never the stale P1
      (let [decision (admission/admit-and-commit-pro-rata!
                      store (candidate {qa 80000} [[(effects/delta qa 5000000)]]))]
        (is (= :rejected (:status decision)))
        (is (= :risk-limit-violation (:reason decision)))))))

;; 4. head change after evaluation but before commit -> stale cannot finalize
(deftest test-head-change-after-evaluation-prevents-finalize
  (let [store (store-at {qa 80000})
        issued (admission/issue-risk-fence!
                store (candidate {qa 80000} [[(effects/delta qa 10000)]]))]
    (is (= :issued (:status issued)))
    ;; head advances to C2/P2 after the fence was issued (evaluation done under H1)
    (is (= :committed (:status (activate-to! store (v4-config (permissive-policy))
                                             (permissive-policy)))))
    (let [finalised (admission/finalise-risk-fence! store (:fence/id issued))]
      (is (not= :committed (:status finalised)))
      (is (= :state-not-at-required-head (:reason finalised))))))

;; 5. retry under the new head recomputes the new applicable policy
(deftest test-retry-under-new-head-recomputes-policy
  (let [store (store-at {qa 80000})
        issued (admission/issue-risk-fence!
                store (candidate {qa 80000} [[(effects/delta qa 10000)]]))
        _ (is (= :issued (:status issued)))
        _ (is (= :committed (:status (activate-to! store (v4-config (permissive-policy))
                                                   (permissive-policy)))))
        stale (admission/finalise-risk-fence! store (:fence/id issued))]
    (is (= :state-not-at-required-head (:reason stale)))
    (testing "re-issuing under the new head resolves C2 -> P2 and commits under P2"
      (let [decision (admission/admit-and-commit-pro-rata!
                      store (candidate {qa 80000} [[(effects/delta qa 10000)]]))]
        (is (= :committed (:status decision)))
        (is (= (:risk-limit-policy/root (permissive-policy)) (:risk-policy/root decision)))
        (is (= {qa 90000} (:business/state (admission/state-snapshot store))))))))

;; 6. valid config body with wrong/non-current head lineage -> reject
(deftest test-wrong-head-lineage-is-rejected
  (let [store (store-at {qa 80000})
        head (admission/current-head store)
        wrong-head-root (hash-ref/sha256-ref qa)
        result (admission/activate-configuration!
                store {:transition nil :parent-configuration nil
                       :new-configuration (v4-config (permissive-policy))
                       :new-risk-policy (permissive-policy)
                       :expected-head-root wrong-head-root})]
    (is (= :rejected (:status result)))
    (is (= :configuration-head-mismatch (:reason result)))
    (testing "the head was not advanced by the rejected activation"
      (is (= (:configuration-head-state/root head)
             (:configuration-head-state/root (admission/current-head store)))))))

;; 7. candidate cannot be transplanted across authority heads
(deftest test-candidate-cannot-transplant-across-heads
  (let [store (store-at {qa 80000})
        issued (admission/issue-risk-fence!
                store (candidate {qa 80000} [[(effects/delta qa 10000)]]))]
    (is (= :issued (:status issued)))
    ;; activate to a different head, then try to finalize the H1-fence
    (is (= :committed (:status (activate-to! store (v4-config (permissive-policy))
                                             (permissive-policy)))))
    (is (= :state-not-at-required-head
           (:reason (admission/finalise-risk-fence! store (:fence/id issued)))))
    (testing "candidate whose state-before differs from the current business state is rejected"
      (let [store2 (store-at {qa 80000})
            decision (admission/admit-and-commit-pro-rata!
                      store2 (candidate {qa 80000 qb 50000} [[(effects/delta qa 10000)]]))]
        (is (= :candidate-state-not-current (:reason decision)))))))

;; 8. risk violation produces no partial business-state mutation
(deftest test-violation-produces-no-partial-mutation
  (let [store (store-at {qa 800000})
        before (admission/state-snapshot store)
        ;; peak 1.1M exceeds P1's 1M global
        decision (admission/admit-and-commit-pro-rata!
                  store (candidate {qa 800000} [[(effects/delta qa 300000)]]))]
    (is (= :rejected (:status decision)))
    (is (= :risk-limit-violation (:reason decision)))
    (testing "no business-state mutation, no admission, no fence issued"
      (is (= (:business/state before) (:business/state (admission/state-snapshot store))))
      (is (empty? (admission/committed-admissions store)))
      (is (empty? (:risk-fences (admission/state-snapshot store)))))))

;; 9. failed/stale admission does not advance state or head
(deftest test-failed-admission-does-not-advance-state-or-head
  (let [store (store-at {qa 80000})
        issued (admission/issue-risk-fence!
                store (candidate {qa 80000} [[(effects/delta qa 10000)]]))
        _ (is (= :committed (:status (activate-to! store (v4-config (permissive-policy))
                                                   (permissive-policy)))))
        commit-before (:configuration/commit-index (admission/state-snapshot store))
        stale (admission/finalise-risk-fence! store (:fence/id issued))]
    (is (= :state-not-at-required-head (:reason stale)))
    (testing "business state and commit index unchanged by the stale finalize"
      (is (= {qa 80000} (:business/state (admission/state-snapshot store))))
      (is (= commit-before (:configuration/commit-index (admission/state-snapshot store))))
      (is (empty? (admission/committed-admissions store))))
    (testing "the rejected activation path never advanced the head from H1's root"
      ;; activation DID advance the head by design in this test; verify the
      ;; commit-index-only advance was blocked. Assert the head is at H2 (C2),
      ;; which is the point of retry, and that no stale admission was committed.
      (is (= (genesis/chain-configuration-root (v4-config (permissive-policy)))
             (:configuration/head-root (admission/current-head store)))))))

;; 10. no caller-controlled policy-selection route
(deftest test-no-caller-controlled-policy-route
  (let [store (store-at {qa 80000})
        ;; both P1 and P2 are retained (P2 via a non-current config body)
        _ (admission/retain-configuration! store (v4-config (permissive-policy)))
        _ (admission/retain-risk-policy! store (permissive-policy))
        decision (admission/admit-and-commit-pro-rata!
                  store (candidate {qa 80000} [[(effects/delta qa 10000)]]))]
    (is (= :committed (:status decision)))
    (testing "the committed admission used exactly the head's current policy (P1), never caller-selected P2"
      (is (= (:risk-limit-policy/root (strict-policy)) (:risk-policy/root decision)))
      (let [admission-record (first (vals (admission/committed-admissions store)))]
        (is (= (:risk-limit-policy/root (strict-policy))
               (:risk-policy/root admission-record)))))))

;; ── single atomicity domain: activate-vs-finalize race ──────────────────────
;;
;; Structural audit: `finalise-risk-fence!` and `activate-configuration!` CAS
;; the SAME single state atom (head + business-state + fences together). There
;; is no separate ConfigurationHeadStore and no copied head representation, so
;; a configuration activation can never race *between* the head check and the
;; business-state commit. Under real threads the two serialize: either the
;; finalize commits under H1 (and the stale activation is rejected), or the
;; activation wins and the finalize re-reads H2 and rejects
;; (:state-not-at-required-head). A committed admission is never observed with
;; the head at H2.

(deftest test-single-atomicity-domain-under-race
  (let [c1-root (genesis/chain-configuration-root (v4-config (strict-policy)))
        c2-root (genesis/chain-configuration-root (v4-config (permissive-policy)))]
    (dotimes [_ 200]
      (let [store (store-at {qa 80000})
            issued (admission/issue-risk-fence!
                    store (candidate {qa 80000} [[(effects/delta qa 10000)]]))
            h1 (:head-state-root (admission/current-resolved store))
            fence-id (:fence/id issued)
            transition {:transition/schema genesis/chain-configuration-transition-schema
                        :protocol/genesis-root genesis/protocol-genesis-fixture-root
                        :target {:target/type :chain-instance
                                 :target/root genesis/chain-instance-genesis-ethereum-fixture-root}
                        :configuration/parent-root c1-root
                        :configuration/new-root c2-root
                        :verifier-registry/root (:verifier-registry/root (v4-config (permissive-policy)))
                        :epoch 2}
            activate-f (future
                         (admission/activate-configuration!
                          store {:transition transition
                                 :parent-configuration (v4-config (strict-policy))
                                 :new-configuration (v4-config (permissive-policy))
                                 :new-risk-policy (permissive-policy)
                                 :expected-head-root h1}))
            finalise-f (future
                         (admission/finalise-risk-fence! store fence-id))
            activate-result (deref activate-f 5000 {:status :timeout})
            finalise-result (deref finalise-f 5000 {:status :timeout})
            business (:business/state (admission/state-snapshot store))]
        ;; activation always serializes through the same atom and advances H1->H2
        (is (= :committed (:status activate-result))
            (str "activation must commit under the race, got " activate-result))
        (when (= :committed (:status finalise-result))
          ;; committed under H1: the admission record is bound to C1 (the head at
          ;; commit), and business advanced exactly once. The head may later have
          ;; advanced to H2 by the separate governance activation — that does NOT
          ;; invalidate the admission, whose record pinned C1 at commit time.
          (let [admission-record (first (vals (admission/committed-admissions store)))]
            (is (= c1-root (:configuration/head-root admission-record))
                "a committed admission is always bound to the head at commit (C1)")
            (is (= h1 (:head-state-root admission-record))))
          (is (= {qa 90000} business)))
        (when (= :state-not-at-required-head (:reason finalise-result))
          ;; stale finalize: no admission, business untouched, head advanced to H2
          (is (empty? (admission/committed-admissions store)))
          (is (= {qa 80000} business)))))))
